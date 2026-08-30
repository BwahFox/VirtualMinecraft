#!/usr/bin/env bash
# Builds the superseded Alpine + Xfce test image: Alpine Linux + Xfce + a few apps + bus.py/vmctui, root
# autologin to the desktop, as a UEFI-bootable qcow2. Kept only so the image that already exists stays
# reproducible until the Debian profiles are measured in-world; the default OS is ../build.sh (ROADMAP §6f).
# Needs qemu-system-x86_64, qemu-img, bsdtar, curl, OVMF at /usr/share/edk2/x64/OVMF.4m.fd, /dev/kvm.
# No root. ~10 min, ~600 MB result (compressed qcow2).
#   tools/guest-image/alpine/build.sh [out.qcow2]    (default: run/guest-images/alpine-desktop.qcow2)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../../.." && pwd)
OUT=${1:-$ROOT/run/guest-images/alpine-desktop.qcow2}
WORK=${WORK:-$(mktemp -d)}
VER=${ALPINE_VERSION:-3.24.1}
BRANCH=v${VER%.*}
ISO=alpine-standard-$VER-x86_64.iso
OVMF=${OVMF:-/usr/share/edk2/x64/OVMF.4m.fd}
cd "$WORK"
[ -f "$ISO" ] || curl -sS -o "$ISO" "https://dl-cdn.alpinelinux.org/alpine/$BRANCH/releases/x86_64/$ISO"
bsdtar -xf "$ISO" boot/vmlinuz-lts boot/initramfs-lts
rm -f disk.qcow2 serial.sock
qemu-img create -f qcow2 disk.qcow2 8G >/dev/null
COMMON=(-machine q35,accel=kvm -cpu host -smp 2 -m 2048M -bios "$OVMF" -drive if=none,id=hd,format=qcow2,file=disk.qcow2 -device ide-hd,drive=hd,bus=ide.0 -nic user,model=e1000 -display none -monitor none -serial unix:serial.sock,server=on,wait=off -no-reboot)
echo "== phase 1: unattended install (serial console)"
qemu-system-x86_64 "${COMMON[@]}" -kernel boot/vmlinuz-lts -initrd boot/initramfs-lts -append "modules=loop,squashfs,sd-mod,usb-storage console=ttyS0,115200" -drive if=none,id=cd,media=cdrom,file="$ISO",format=raw,read-only=on -device ide-cd,drive=cd,bus=ide.1 >qemu1.log 2>&1 &
sleep 2
python3 "$HERE/phase1_install.py" serial.sock serial1.log
wait
echo "== phase 2: desktop, apps, autologin, bus.py + vmctui"
rm -f serial.sock
qemu-system-x86_64 "${COMMON[@]}" >qemu2.log 2>&1 &
sleep 2
python3 "$HERE/phase2_provision.py" serial.sock serial2.log "$ROOT/tools/bus.py" "$ROOT/tools/bustui.py"
wait
mkdir -p "$(dirname "$OUT")"
qemu-img convert -c -O qcow2 disk.qcow2 "$OUT"
echo "== done: $OUT ($(du -h "$OUT" | cut -f1)). Work dir: $WORK"
