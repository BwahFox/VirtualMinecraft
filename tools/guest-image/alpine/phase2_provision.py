"""Provision the installed Alpine: Xfce desktop, apps, root autologin to the desktop, bus.py + vmctui."""
import pathlib
import sys
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))  # sconsole.py lives one level up
from sconsole import Console

sock, log, buspy = sys.argv[1], sys.argv[2], sys.argv[3]
# The TUI lives next to bus.py in tools/; optional so an older build.sh still works.
tuipy = sys.argv[4] if len(sys.argv) > 4 else str(pathlib.Path(buspy).with_name("bustui.py"))
c = Console(sock, log)


def step(name, cmd, timeout=1500):
    print(f"== {name}", flush=True)
    out = c.run(cmd, timeout=timeout)
    tail = out.strip().splitlines()[-3:]
    for l in tail:
        print("   " + l[:160], flush=True)
    return out


c.expect(r"login: ", 240)
c.sendline("root")
c.expect(r"# $", 30)

step("repos", """cat > /etc/apk/repositories <<'EOF'
https://dl-cdn.alpinelinux.org/alpine/v3.24/main
https://dl-cdn.alpinelinux.org/alpine/v3.24/community
EOF
apk update""")
step("xorg base", "setup-xorg-base && echo XORG_OK")
step("desktop + apps", "apk add xfce4 xfce4-terminal mousepad thunar ristretto galculator htop nano python3 alsa-utils dbus dbus-x11 font-dejavu xrandr agetty xf86-video-fbdev && echo APPS_OK")
step("firefox", "apk add firefox && echo FIREFOX_OK")
step("services", "rc-update add dbus default; rc-update add alsa default; echo SVC_OK")
step("autologin", r"""sed -i 's|^tty1::respawn:.*|tty1::respawn:/sbin/agetty --autologin root --noclear tty1 linux|' /etc/inittab
cat > /root/.profile <<'EOF'
# Auto-start the desktop on the first console (the VNC screen); serial logins stay a plain shell.
if [ -z "$DISPLAY" ] && [ "$(tty)" = "/dev/tty1" ]; then
  exec startx
fi
EOF
cat > /root/.xinitrc <<'EOF'
xrandr -s 1280x800 2>/dev/null
exec startxfce4
EOF
mkdir -p /root/.config/xfce4/xfconf/xfce-perchannel-xml /root/Desktop
cp /etc/xdg/xfce4/panel/default.xml /root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-panel.xml
grep tty1 /etc/inittab; echo AUTOLOGIN_OK""")

bus = pathlib.Path(buspy).read_text()
step("bus.py", "cat > /usr/local/bin/bus.py <<'VMC_EOF'\n" + bus + "\nVMC_EOF\nchmod +x /usr/local/bin/bus.py; ln -sf /usr/local/bin/bus.py /usr/local/bin/vmc; python3 -c 'import ast,sys; ast.parse(open(\"/usr/local/bin/bus.py\").read()); print(\"BUS_OK\")'", timeout=120)

if pathlib.Path(tuipy).is_file():
    tui = pathlib.Path(tuipy).read_text()
    step("vmctui", "cat > /usr/local/bin/vmctui <<'VMC_EOF'\n" + tui + "\nVMC_EOF\nchmod +x /usr/local/bin/vmctui; python3 -c 'import ast,curses; ast.parse(open(\"/usr/local/bin/vmctui\").read()); print(\"TUI_OK\")'", timeout=120)

step("readme", r"""cat > /root/Desktop/README.txt <<'EOF'
VirtualMinecraft test desktop (Alpine Linux + Xfce)

- You are root; there is no password. The desktop starts by itself on the first console.
- Terminal, Firefox, Mousepad (editor), Thunar (files), Ristretto (images), Galculator, htop, nano, python3.
- The world bus: open a terminal and run  vmctui  for the interactive console
  (Tab completes, /help lists everything, world events stream in as they happen).
  One-shot calls without the console:
    vmc list
    vmc redstone.setOutput east 15
    vmc subscribe redstone_changed --watch
  (vmc is /usr/local/bin/bus.py; the port is /dev/virtio-ports/vmc.bus)
- Sound test:  speaker-test -t sine -f 440 -l 1
- Packages:    apk add <name>     (needs the computer's network on)
EOF
cat > /root/Desktop/Terminal.desktop <<'EOF'
[Desktop Entry]
Type=Application
Name=Terminal
Exec=xfce4-terminal
Icon=utilities-terminal
EOF
chmod +x /root/Desktop/*.desktop; echo README_OK""")

step("cleanup", "rm -rf /var/cache/apk/* /root/.ash_history; sync; df -h / | tail -1; echo CLEAN_OK")
c.sendline("poweroff")
print("PHASE2_OK", flush=True)
