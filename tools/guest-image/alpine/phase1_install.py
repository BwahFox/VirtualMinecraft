"""Phase 1: drive the Alpine installer over the serial console (answer file, unattended), then enable a serial getty on the installed system."""
import sys
import pathlib as _pl
sys.path.insert(0, str(_pl.Path(__file__).resolve().parent.parent))  # sconsole.py lives one level up
from sconsole import Console

sock, log = sys.argv[1], sys.argv[2]
c = Console(sock, log)
c.expect(r"login: ", 180)
c.sendline("root")
c.expect(r"# $", 30)

answers = r"""cat > /tmp/answers <<'EOF'
KEYMAPOPTS="us us"
HOSTNAMEOPTS="vmc"
DEVDOPTS=mdev
INTERFACESOPTS="auto lo
iface lo inet loopback

auto eth0
iface eth0 inet dhcp
"
DNSOPTS="1.1.1.1"
TIMEZONEOPTS="UTC"
PROXYOPTS=none
APKREPOSOPTS="-1 -c"
USEROPTS=none
SSHDOPTS=none
NTPOPTS="busybox"
DISKOPTS="-m sys /dev/sda"
LVMOPTS=none
EOF"""
c.run(answers, timeout=30)
print("answers written", flush=True)
out = c.run("ERASE_DISKS=/dev/sda setup-alpine -e -f /tmp/answers", timeout=900)
print(out[-1500:], flush=True)
if "Installation is complete" not in out:
    raise SystemExit("setup-alpine did not finish")
out = c.run("lsblk -o NAME,FSTYPE,SIZE /dev/sda; mount | grep /mnt || true", timeout=30)
print(out, flush=True)
# Root partition is the ext4 one; mount it and switch the serial getty on for the next phase.
out = c.run("ROOTP=$(lsblk -rno NAME,FSTYPE /dev/sda | awk '$2==\"ext4\"{print $1; exit}'); echo ROOT=$ROOTP; mkdir -p /mnt; mount /dev/$ROOTP /mnt; grep -q '^ttyS0' /mnt/etc/inittab || echo 'ttyS0::respawn:/sbin/getty -L 115200 ttyS0 vt100' >> /mnt/etc/inittab; tail -3 /mnt/etc/inittab; umount /mnt; echo MOUNT_DONE", timeout=60)
print(out, flush=True)
c.sendline("poweroff")
print("PHASE1_OK", flush=True)
