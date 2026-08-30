"""Phase 1: watch the preseeded Debian installer run to completion over the serial console.

There is nothing to *drive* here — preseed.cfg answers everything — so this only reports progress and
fails loudly instead of hanging for an hour when the installer stops to ask a question we forgot.
"""
import re
import sys
import time

from sconsole import Console

sock, log = sys.argv[1], sys.argv[2]
c = Console(sock, log)

# Milestones the text frontend prints as it goes, so a watching human sees movement.
# Wording taken from the text frontend's own progress lines; they arrive roughly in this order but
# nothing depends on that, because a milestone d-i skips must not hide the ones after it.
MILESTONES = [
    (r"Configuring the network with DHCP", "network up"),
    (r"Checking the Debian archive mirror", "mirror reachable"),
    (r"Starting up the partitioner", "partitioning"),
    (r"Installing the base system", "base system"),
    (r"Select and install software", "packages"),
    (r"Installing GRUB boot loader", "bootloader"),
]
# Anything here means the installer gave up or stopped to ask; nothing later will ever arrive.
FAILURES = [
    (r"Installation step failed", "an installation step failed"),
    (r"No root file system", "partitioning produced no root filesystem"),
    (r"\[!!\] Configure the network", "network configuration needs an answer"),
    (r"Bad archive mirror", "the mirror was unreachable"),
]

start = time.time()
seen = set()
while True:
    c._read()  # returns False on a plain read timeout; raises if QEMU closed the socket
    for rx, why in FAILURES:
        if re.search(rx, c.buf):
            raise SystemExit(f"installer stopped: {why}\n--- last output ---\n{c.buf[-1500:]}")
    for i, (rx, what) in enumerate(MILESTONES):
        if i not in seen and re.search(rx, c.buf):
            seen.add(i)
            print(f"   [{int(time.time() - start):4d}s] {what}", flush=True)
    if "VMC_PRESEED_DONE" in c.buf:
        print(f"   [{int(time.time() - start):4d}s] install finished, rebooting", flush=True)
        break
    if time.time() - start > 3600:
        raise SystemExit(f"installer still running after an hour; giving up\n{c.buf[-1500:]}")
    if len(c.buf) > 400000:
        c.buf = c.buf[-40000:]

# -no-reboot means QEMU exits when the installer reboots; wait for that so build.sh's `wait` is clean.
end = time.time() + 180
while time.time() < end:
    try:
        if not c._read():
            continue
    except SystemExit:
        print("PHASE1_OK", flush=True)
        sys.exit(0)
raise SystemExit("the installer never rebooted")
