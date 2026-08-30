#!/usr/bin/env bash
# Builds the VirtualMinecraft default OS: Debian 13 "trixie", installed unattended by preseed and
# provisioned over a serial console, as a UEFI-bootable qcow2 you hand out as a hard-drive item.
# (ROADMAP §6. The superseded Alpine + Xfce image lives in alpine/build.sh.)
#
#   tools/guest-image/build.sh [--profile console|plasma] [--out FILE] [--size 16G] [--keep-serial]
#
# Profiles:
#   console   base system + python3 + the bus tooling, no X          (the server tier; fastest to boot)
#   plasma    console + a *tuned* KDE Plasma 6 desktop and apps       (the default; ROADMAP §6b)
#
# Host needs: qemu-system-x86_64, qemu-img, curl, python3, /dev/kvm, OVMF. No root, no display.
# Network is required throughout: the base system and every package come from a Debian mirror.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)

PROFILE=plasma
OUT=
SIZE=
KEEP_SERIAL=0
while [ $# -gt 0 ]; do
	case $1 in
		--profile) PROFILE=$2; shift 2 ;;
		--out) OUT=$2; shift 2 ;;
		--size) SIZE=$2; shift 2 ;;
		--keep-serial) KEEP_SERIAL=1; shift ;;   # leave console=ttyS0 in the shipped image (debugging)
		-h|--help) sed -n '2,14p' "$0"; exit 0 ;;
		*) echo "unknown argument: $1" >&2; exit 2 ;;
	esac
done
case $PROFILE in
	console) SIZE=${SIZE:-8G} ;;
	plasma)  SIZE=${SIZE:-16G} ;;
	*) echo "unknown profile: $PROFILE (console|plasma)" >&2; exit 2 ;;
esac
OUT=${OUT:-$ROOT/run/guest-images/vmcos-$PROFILE.qcow2}

SUITE=${DEBIAN_SUITE:-trixie}
MIRROR_HOST=${DEBIAN_MIRROR_HOST:-deb.debian.org}
MIRROR_DIR=${DEBIAN_MIRROR_DIR:-/debian}
HOSTNAME_=${VMC_HOSTNAME:-vmc}
USERNAME=${VMC_USER:-vmc}
PASSWORD=${VMC_PASSWORD:-vmc}
VIDEO=${VMC_VIDEO:-1280x800}
OVMF=${OVMF:-/usr/share/edk2/x64/OVMF.4m.fd}
TIMEZONE=${VMC_TZ:-$(timedatectl show -p Timezone --value 2>/dev/null || echo UTC)}

# Work and cache go on real disk, never /tmp: /tmp is tmpfs here and the build disk is up to 16 GB.
CACHE=${CACHE:-$ROOT/run/guest-images/cache}
WORK=${WORK:-$ROOT/run/guest-images/work-$PROFILE}
mkdir -p "$CACHE" "$WORK" "$(dirname "$OUT")"

NETBOOT=https://$MIRROR_HOST$MIRROR_DIR/dists/$SUITE/main/installer-amd64/current/images/netboot/debian-installer/amd64
for f in linux initrd.gz; do
	[ -s "$CACHE/$SUITE-$f" ] || { echo "== fetching installer $f"; curl -fsS -o "$CACHE/$SUITE-$f" "$NETBOOT/$f"; }
done

cd "$WORK"
rm -f disk.qcow2 serial.sock serial1.log serial2.log
qemu-img create -f qcow2 disk.qcow2 "$SIZE" >/dev/null

sed -e "s|@HOSTNAME@|$HOSTNAME_|g" -e "s|@USERNAME@|$USERNAME|g" -e "s|@PASSWORD@|$PASSWORD|g" \
    -e "s|@MIRROR_HOST@|$MIRROR_HOST|g" -e "s|@MIRROR_DIR@|$MIRROR_DIR|g" -e "s|@SUITE@|$SUITE|g" \
    -e "s|@TIMEZONE@|$TIMEZONE|g" -e "s|@VIDEO@|$VIDEO|g" "$HERE/preseed.cfg" > preseed.cfg

# Everything the guest needs from us is served over QEMU user networking (the host is 10.0.2.2):
# the preseed in phase 1, and the bus tooling in phase 2. Pushing files down the serial line instead
# corrupts them — the emulated UART has no flow control and drops bytes under a multi-KB write.
cp "$ROOT/tools/bus.py" "$ROOT/tools/bustui.py" "$WORK/"
PORT=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
python3 -m http.server -b 127.0.0.1 -d "$WORK" "$PORT" >http.log 2>&1 &
HTTP_PID=$!
# Kill the VM too, not just the web server: a build that fails in phase 2 used to leave its QEMU
# running forever, quietly eating four cores and fighting the next build over the same work dir.
QEMU_PID=
trap 'kill $HTTP_PID 2>/dev/null || true; [ -n "$QEMU_PID" ] && kill $QEMU_PID 2>/dev/null || true' EXIT

# 4 CPUs / 4 GB is the *build* machine, not the shipped one — apt and the Plasma install are the slow parts.
COMMON=(-machine q35,accel=kvm -cpu host -smp 4 -m 4096M -bios "$OVMF"
        -drive if=none,id=hd,format=qcow2,file=disk.qcow2,discard=unmap,detect-zeroes=unmap
        -device ide-hd,drive=hd,bus=ide.0 -nic user,model=e1000
        -display none -monitor none -serial unix:serial.sock,server=on,wait=off -no-reboot)

T0=$(date +%s)
echo "== phase 1: Debian $SUITE unattended install (preseed over http://10.0.2.2:$PORT)"
qemu-system-x86_64 "${COMMON[@]}" \
	-kernel "$CACHE/$SUITE-linux" -initrd "$CACHE/$SUITE-initrd.gz" \
	-append "auto-install/enable=true priority=critical preseed/url=http://10.0.2.2:$PORT/preseed.cfg locale=en_US.UTF-8 keymap=us hostname=$HOSTNAME_ domain= interface=auto console=ttyS0,115200 DEBIAN_FRONTEND=text" \
	>qemu1.log 2>&1 &
QEMU_PID=$!
sleep 2
python3 "$HERE/phase1_install.py" serial.sock serial1.log
wait "$QEMU_PID"          # not a bare `wait`: the http.server is a child too and never exits
T1=$(date +%s)

echo "== phase 2: profile '$PROFILE' — packages, tuning, bus tooling"
rm -f serial.sock
qemu-system-x86_64 "${COMMON[@]}" >qemu2.log 2>&1 &
QEMU_PID=$!
sleep 2
python3 "$HERE/phase2_provision.py" serial.sock serial2.log \
	--profile "$PROFILE" --user "$USERNAME" --password "$PASSWORD" --video "$VIDEO" \
	--http "http://10.0.2.2:$PORT" \
	$([ "$KEEP_SERIAL" = 1 ] && echo --keep-serial)
wait "$QEMU_PID"
T2=$(date +%s)

echo "== compacting"
qemu-img convert -c -O qcow2 disk.qcow2 "$OUT"
T3=$(date +%s)
echo "== done: $OUT"
echo "   size      $(du -h "$OUT" | cut -f1) compressed, $(qemu-img info --output=json "$OUT" | python3 -c 'import json,sys; print(json.load(sys.stdin)["virtual-size"]//2**30)') GB virtual"
echo "   install   $((T1-T0))s    provision $((T2-T1))s    compact $((T3-T2))s"
echo "   work dir  $WORK (serial1.log / serial2.log if anything looked wrong)"
echo "   login     $USERNAME / $PASSWORD  (root / $PASSWORD)"
