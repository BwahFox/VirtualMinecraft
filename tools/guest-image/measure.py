"""Measure a built guest image the way the mod actually runs it (ROADMAP §6b: "then measure").

Boots the image with QemuLauncher's own flags — q35, -vga std, -display none, ich9-intel-hda, the
virtio-serial bus port, e1000, OVMF — on a throwaway overlay so the master image is never written to,
then reports size, host CPU burned during boot, where CPU settles at idle, and QEMU's RSS. Screenshots
come from QMP `screendump`, which needs nothing inside the guest, so this works on any image.

    tools/guest-image/measure.py run/guest-images/vmcos-plasma.qcow2 --mem 2048 --cpus 2

What this does NOT measure: the streaming path. Dirty-rectangle cost and how the desktop feels at
20 fps can only be judged in-game on a monitor block (TESTING.md).
"""
import argparse
import json
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import time

ap = argparse.ArgumentParser()
ap.add_argument("image")
ap.add_argument("--mem", type=int, default=2048)
ap.add_argument("--cpus", type=int, default=2)
ap.add_argument("--seconds", type=int, default=180, help="how long to run before powering off")
ap.add_argument("--idle-from", type=int, default=120, help="treat everything after this as idle")
ap.add_argument("--shots", default="10,20,30,45,60,90,120,150", help="screenshot times, seconds")
ap.add_argument("--out", default=None, help="directory for the screenshots and the report")
ap.add_argument("--ovmf", default=os.environ.get("OVMF", "/usr/share/edk2/x64/OVMF.4m.fd"))
a = ap.parse_args()

image = pathlib.Path(a.image).resolve()
out = pathlib.Path(a.out) if a.out else image.with_name(f"measure-{image.stem}")
out.mkdir(parents=True, exist_ok=True)
work = pathlib.Path(tempfile.mkdtemp(prefix="vmc-measure-", dir=str(image.parent)))
overlay = work / "overlay.qcow2"
subprocess.run(["qemu-img", "create", "-f", "qcow2", "-b", str(image), "-F", "qcow2", str(overlay)],
               check=True, stdout=subprocess.DEVNULL)

qmp = work / "qmp.sock"
bus = work / "bus.sock"
cmd = [
    "qemu-system-x86_64", "-name", "vmc-measure",
    "-machine", "q35,accel=kvm", "-cpu", "host", "-smp", str(a.cpus), "-m", f"{a.mem}M",
    "-bios", a.ovmf,
    "-drive", f"if=none,id=hd,format=qcow2,file={overlay}", "-device", "ide-hd,drive=hd,bus=ide.0",
    "-boot", "menu=on,splash-time=1500",
    "-vga", "std", "-display", "none",
    "-audiodev", "none,id=snd0", "-device", "ich9-intel-hda", "-device", "hda-output,audiodev=snd0",
    "-qmp", f"unix:{qmp},server=on,wait=off",
    "-usb", "-device", "usb-tablet",
    "-device", "virtio-serial-pci,id=vmcserial",
    "-chardev", f"socket,id=vmcbus,path={bus},server=on,wait=off",
    "-device", "virtserialport,bus=vmcserial.0,chardev=vmcbus,name=vmc.bus",
    "-nic", "user,model=e1000",
    "-rtc", "base=localtime", "-monitor", "none", "-serial", "none", "-parallel", "none",
]
proc = subprocess.Popen(cmd, stdout=open(work / "qemu.log", "wb"), stderr=subprocess.STDOUT)


class Qmp:
    def __init__(self, path):
        for _ in range(60):
            try:
                self.s = socket.socket(socket.AF_UNIX)
                self.s.connect(str(path))
                break
            except OSError:
                time.sleep(0.5)
        else:
            raise SystemExit("QMP socket never appeared")
        self.f = self.s.makefile("rwb")
        self.f.readline()  # greeting
        self.cmd("qmp_capabilities")

    def cmd(self, name, **args):
        self.f.write((json.dumps({"execute": name, "arguments": args}) + "\n").encode())
        self.f.flush()
        while True:
            line = json.loads(self.f.readline())
            if "return" in line or "error" in line:
                return line


q = Qmp(qmp)
ticks = os.sysconf("SC_CLK_TCK")


def cpu_seconds():
    with open(f"/proc/{proc.pid}/stat") as fh:
        f = fh.read().rsplit(") ", 1)[1].split()
    return (int(f[11]) + int(f[12])) / ticks  # utime + stime, in seconds


def rss_mb():
    with open(f"/proc/{proc.pid}/status") as fh:
        for line in fh:
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) / 1024
    return 0.0


shots = sorted(int(s) for s in a.shots.split(",") if s)
start = time.time()
samples = []
taken = []
while True:
    now = time.time() - start
    if now >= a.seconds:
        break
    if proc.poll() is not None:
        print("QEMU exited early — see", work / "qemu.log")
        break
    while shots and now >= shots[0]:
        t = shots.pop(0)
        png = out / f"t{t:03d}.png"
        r = q.cmd("screendump", filename=str(png), format="png")
        taken.append((t, png, "error" not in r))
    samples.append((now, cpu_seconds(), rss_mb()))
    time.sleep(1.0)

total_cpu = cpu_seconds()
idle = [s for s in samples if s[0] >= a.idle_from]
idle_pct = None
if len(idle) > 1:
    idle_pct = 100.0 * (idle[-1][1] - idle[0][1]) / (idle[-1][0] - idle[0][0])
boot_window = [s for s in samples if s[0] <= a.idle_from]
boot_cpu = boot_window[-1][1] if boot_window else 0.0
peak_rss = max((s[2] for s in samples), default=0.0)

try:
    q.cmd("quit")
except Exception:
    pass
proc.wait(timeout=30)

virt = json.loads(subprocess.run(["qemu-img", "info", "--output=json", str(image)],
                                 capture_output=True, text=True).stdout)["virtual-size"]
report = [
    f"image           {image}",
    f"on disk         {image.stat().st_size / 2**20:.0f} MB compressed qcow2, {virt / 2**30:.0f} GB virtual",
    f"guest           {a.cpus} CPUs, {a.mem} MB RAM",
    f"CPU to boot     {boot_cpu:.1f} host-CPU-seconds in the first {a.idle_from}s",
    f"idle CPU        {idle_pct:.1f}% of one core (t={a.idle_from}..{a.seconds}s)" if idle_pct is not None else "idle CPU        not sampled",
    f"peak QEMU RSS   {peak_rss:.0f} MB (of {a.mem} MB granted)",
    f"total CPU       {total_cpu:.1f} host-CPU-seconds over {a.seconds}s",
    "screenshots     " + ", ".join(f"t={t}s {'ok' if ok else 'FAILED'}" for t, _, ok in taken),
    f"                in {out}",
]
text = "\n".join(report)
print(text)
(out / "report.txt").write_text(text + "\n")
shutil.rmtree(work, ignore_errors=True)
