"""Phase 2: provision the installed Debian over the serial console — packages, tuning, bus tooling.

The preseed leaves a plain standard system. Everything that makes it *the VirtualMinecraft OS* happens
here, because it is only shell over a serial line and it stays readable and re-orderable.

  console   base + python3 + bus.py/vmctui, autologin on tty1
  plasma    console + KDE Plasma 6, tuned for a machine with no GPU behind a dirty-rectangle streamer
            (ROADMAP §6b: compositing off, animations off, Baloo off, no locker, no blanking)

Three rules learned the hard way, each of which produced a build that reported success and shipped a
half-provisioned image:

  * File bodies go over HTTP, never down the serial line. The emulated UART has no flow control, so a
    multi-KB write arrives with holes in it — and the shell keeps prompting either way.
  * Every step must prove it ran, and the proof cannot be a string that appears in the command, or the
    terminal's own echo of the command satisfies the check. Hence `echo $VOK`: the command text
    contains the variable, only the output contains its value.
  * A step is never typed at the shell directly. Waiting for a prompt cannot tell "the whole thing
    finished" from "line 1 of 6 finished", so every step is base64'd into a file in small chunks and
    run with `sh -e`; one prompt, one exit status, and no heredoc games with the terminal.
"""
import argparse
import base64

from sconsole import Console

OK = "VMC_STEP_OK"

ap = argparse.ArgumentParser()
ap.add_argument("sock")
ap.add_argument("log")
ap.add_argument("--profile", default="plasma", choices=["console", "plasma"])
ap.add_argument("--user", default="vmc")
ap.add_argument("--password", default="vmc")
ap.add_argument("--video", default="1280x800")
ap.add_argument("--http", required=True, help="base URL of build.sh's http.server, as the guest sees it")
ap.add_argument("--keep-serial", action="store_true")
a = ap.parse_args()
USER = a.user
HOME = f"/home/{USER}"

c = Console(a.sock, a.log)


def run_script(name, script, timeout=1800):
    """Ships a shell script to the guest in 400-byte base64 chunks and runs it as one command."""
    print(f"== {name}", flush=True)
    blob = base64.b64encode((script.rstrip() + "\necho $VOK\n").encode()).decode()
    c.run(": > /tmp/vmc-step.b64", timeout=60)
    for i in range(0, len(blob), 400):
        c.run(f"printf %s '{blob[i:i + 400]}' >> /tmp/vmc-step.b64", timeout=60)
    out = c.run("base64 -d /tmp/vmc-step.b64 > /tmp/vmc-step.sh && sh -e /tmp/vmc-step.sh", timeout=timeout)
    for line in out.strip().splitlines()[-3:]:
        print("   " + line.strip()[:160], flush=True)
    if OK not in out:
        raise SystemExit(f"step {name!r} failed (no {OK}); last output:\n{out[-2500:]}")
    return out


step = run_script


def fetch(url_path, dest, mode="755"):
    get = f"""python3 -c "import urllib.request; urllib.request.urlretrieve('{a.http}/{url_path}', '{dest}')" """
    run_script(f"fetch {dest}", get + f"\nchmod {mode} {dest}\nwc -c < {dest}", timeout=180)


def write_file(path, body, mode=None, owner=None):
    """Small config files. The heredoc is safe here because it is inside a script file, not typed."""
    script = f"mkdir -p $(dirname {path})\ncat > {path} <<'VMC_EOF'\n{body}\nVMC_EOF\n"
    if mode:
        script += f"chmod {mode} {path}\n"
    if owner:
        script += f"chown {owner} {path}\n"
    script += f"test -s {path}\n"
    run_script(f"write {path}", script, timeout=120)


c.expect(r"login: ", 420)
c.sendline("root")
c.expect(r"[Pp]assword: ", 60)
c.sendline(a.password)
c.expect(r"# $", 60)
# Wide and non-interactive: line wrapping at 80 columns corrupts the prompt matching, and apt must
# never stop to ask anything.
# VOK is assembled rather than written out, so that this line's own echo cannot satisfy the check.
out = c.run("stty cols 400 rows 100; export TERM=dumb DEBIAN_FRONTEND=noninteractive; "
            "export VOK=$(printf 'VMC%sSTEP%sOK' _ _); echo $VOK", timeout=60)
if OK not in out:
    raise SystemExit(f"could not log in on the serial console; last output:\n{out[-2000:]}")
print("== login", flush=True)

step("apt update", "apt-get update -qq", timeout=600)

if a.profile == "console":
    step("packages", "apt-get install -y -q --no-install-recommends htop nano less ca-certificates", timeout=900)

if a.profile == "plasma":
    # plasma-desktop, not the `plasma`/`kde-plasma-desktop` metas — those drag in half of KDE PIM (ROADMAP §6b).
    # kwin-x11 is not optional and is easy to miss: trixie has no `plasma-workspace-x11` package (the X11
    # session file and startplasma-x11 both live in plasma-workspace), but plasma-workspace depends only on
    # kwin-wayland, so without this the X11 session starts with no window manager. On a guest with no GPU,
    # X11 costs far less than Wayland, so X11 is the session we want.
    step("desktop", "apt-get install -y -q plasma-desktop kwin-x11 sddm xserver-xorg xinit "
                    "dbus-x11 kde-cli-tools systemsettings", timeout=3600)
    # plasma-welcome is a Recommends of plasma-desktop and it opens a tour window on every first login —
    # centred exactly where the desktop icons are, so the README on the desktop is hidden behind it. A
    # computer you place in the world should show its own desktop, not a KDE tour.
    step("no welcome tour", "apt-get purge -y -q plasma-welcome", timeout=600)
    step("apps", "apt-get install -y -q konsole dolphin kate gwenview kcalc ark firefox-esr "
                 "pipewire-audio alsa-utils htop nano less fonts-dejavu", timeout=3600)

    step("session", rf"""S=$(ls /usr/share/xsessions/ 2>/dev/null | grep -i plasma | sort | head -1 || true)
S=${{S%.desktop}}
[ -n "$S" ] || S=plasmax11
mkdir -p /etc/sddm.conf.d
cat > /etc/sddm.conf.d/vmc.conf <<EOF
[Autologin]
User={USER}
Session=$S
Relogin=false

[General]
DisplayServer=x11
EOF
systemctl enable sddm >/dev/null 2>&1
systemctl set-default graphical.target >/dev/null 2>&1
echo "session $S" """, timeout=300)

    # KDE reads /etc/xdg as the system-wide default layer, so these apply to any user, including one
    # created later, and a player can still override any of them from System Settings.
    write_file("/etc/xdg/kwinrc", "[Compositing]\nEnabled=false\nOpenGLIsUnsafe=true\n\n"
                                  "[Effect-slide]\nDuration=0\n\n[Windows]\nPlacement=Smart\n")
    write_file("/etc/xdg/kdeglobals", "[KDE]\nAnimationDurationFactor=0\nSingleClick=false\n\n"
                                      "[General]\nBrowserApplication=firefox-esr.desktop\n")
    write_file("/etc/xdg/baloofilerc", "[Basic Settings]\nIndexing-Enabled=false\n")
    write_file("/etc/xdg/kscreenlockerrc", "[Daemon]\nAutolock=false\nLockGrace=0\nLockOnResume=false\nTimeout=0\n")
    write_file("/etc/xdg/ksmserverrc", "[General]\nloginMode=emptySession\nconfirmLogout=false\n")
    write_file("/etc/xdg/ksplashrc", "[KSplash]\nEngine=none\nTheme=None\n")
    # Empty power profiles = powerdevil has no action to take, so the screen never blanks. A blank
    # monitor in the world reads as "the computer crashed", which is the worst possible default.
    write_file("/etc/xdg/powermanagementprofilesrc",
               "[AC]\nicon=battery-charging\n\n[Battery]\nicon=battery-060\n\n[LowBattery]\nicon=battery-low\n")
    # Belt and braces: X's own blanking and DPMS are independent of powerdevil.
    write_file("/usr/local/bin/vmc-session-setup",
               f"#!/bin/sh\n# Runs once per desktop session: no blanking, and the resolution the monitor expects.\n"
               f"xset s off -dpms 2>/dev/null\nxrandr -s {a.video} 2>/dev/null\nexit 0\n", mode="755")
    write_file("/etc/xdg/autostart/vmc-session-setup.desktop",
               "[Desktop Entry]\nType=Application\nName=VirtualMinecraft display settings\n"
               "Exec=/usr/local/bin/vmc-session-setup\nX-KDE-autostart-phase=1\nNoDisplay=true\n")

fetch("bus.py", "/usr/local/bin/bus.py")
step("bus.py", "ln -sf /usr/local/bin/bus.py /usr/local/bin/vmc\n"
               "python3 -c 'import ast; ast.parse(open(\"/usr/local/bin/bus.py\").read())'", timeout=120)
fetch("bustui.py", "/usr/local/bin/vmctui")
step("vmctui", "python3 -c 'import ast, curses; ast.parse(open(\"/usr/local/bin/vmctui\").read())'", timeout=120)

# The bus port is root-only by default and the desktop user is not root — without this, `vmc list`
# works over the serial console and fails in the guest's own terminal, which is a baffling bug to hit.
write_file("/etc/udev/rules.d/70-vmc-bus.rules",
           'SUBSYSTEM=="virtio-ports", ATTR{name}=="vmc.bus", MODE="0666", SYMLINK+="vmcbus"\n')

if a.profile == "console":
    step("autologin", rf"""mkdir -p /etc/systemd/system/getty@tty1.service.d
cat > /etc/systemd/system/getty@tty1.service.d/autologin.conf <<'EOF'
[Service]
ExecStart=
ExecStart=-/sbin/agetty --autologin {USER} --noclear %I $TERM
EOF
systemctl set-default multi-user.target >/dev/null 2>&1""", timeout=120)

DESKTOP_HINT = ("- The desktop starts by itself; there is no login screen.\n" if a.profile == "plasma"
                else "- You are logged in automatically on the first console.\n")
write_file(f"{HOME}/Desktop/README.txt", f"""VirtualMinecraft — the default OS (Debian 13, {a.profile} profile)

{DESKTOP_HINT}- User "{USER}", password "{a.password}" (sudo needs no password). root has the same password.
- The world bus: open a terminal and run  vmctui  for the interactive console
  (Tab completes, /help lists everything, world events stream in as they happen).
  One-shot calls without the console:
    vmc list
    vmc redstone.setOutput east 15
    vmc subscribe redstone_changed --watch
  (vmc is /usr/local/bin/bus.py; the port is /dev/virtio-ports/vmc.bus)
- Sound test:  speaker-test -t sine -f 440 -l 1
- Packages:    sudo apt install <name>     (needs the computer's network on)
- This machine has no GPU: everything is drawn on the CPU and streamed to the monitor block as
  changed rectangles. Full-screen video and animation will be slow; ordinary desktop use is fine.
""")

if a.profile == "plasma":
    write_file(f"{HOME}/Desktop/Konsole.desktop",
               "[Desktop Entry]\nType=Application\nName=Terminal\nExec=konsole\nIcon=utilities-terminal\n", mode="755")
step("desktop files", f"chown -R {USER}:{USER} {HOME}/Desktop\nls {HOME}/Desktop", timeout=60)

# GRUB was pointed at the serial console for this build; the mod runs QEMU with -serial none, so a
# shipped image that still talks to ttyS0 spawns a getty on a port that does not exist, and a
# GRUB_TERMINAL of `serial` would put the boot menu somewhere the player can never see it.
serial_fix = "" if a.keep_serial else "sed -i 's/ console=ttyS0,115200//g' /etc/default/grub\n"
step("grub", serial_fix + r"""sed -i 's/^#\?GRUB_TIMEOUT=.*/GRUB_TIMEOUT=1/' /etc/default/grub
sed -i '/^GRUB_TERMINAL/d' /etc/default/grub
printf 'GRUB_TERMINAL_INPUT=console\nGRUB_TERMINAL_OUTPUT=gfxterm\n' >> /etc/default/grub
update-grub >/dev/null 2>&1
grep -E '^GRUB_(CMDLINE|TERMINAL|TIMEOUT)' /etc/default/grub""", timeout=300)

# A blank machine-id makes systemd mint a fresh one at first boot, so every overlay of this base
# (ROADMAP §6c) is a distinct machine rather than a thousand clones sharing an identity.
step("cleanup", r"""apt-get clean
rm -rf /var/lib/apt/lists/* /root/.bash_history
truncate -s 0 /etc/machine-id
rm -f /var/lib/dbus/machine-id
ln -s /etc/machine-id /var/lib/dbus/machine-id
sync
df -h / | tail -1""", timeout=600)
# fstrim is a size optimisation, not a correctness step: if the emulated AHCI disk refuses discard the
# image is simply larger, so this one is allowed to fail.
step("trim", "fstrim -av || true\nsync\ndf -h / | tail -1", timeout=1200)

c.sendline("poweroff")
print("PHASE2_OK", flush=True)
