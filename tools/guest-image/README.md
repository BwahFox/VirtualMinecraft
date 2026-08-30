# Guest images

`build.sh` builds **the default OS**: Debian 13 "trixie", installed unattended and handed out as a
UEFI-bootable qcow2 hard-drive item (the computer needs **UEFI: ON**). Two profiles from one builder:

| profile | what it is | for |
|---|---|---|
| `console` | base system, python3, `bus.py`/`vmctui`, autologin on tty1 | the cheap tier; fastest to boot |
| `plasma` | the above + KDE Plasma 6, *tuned* for a machine with no GPU | the default desktop |

```sh
tools/guest-image/build.sh --profile console          # -> run/guest-images/vmcos-console.qcow2
tools/guest-image/build.sh                            # --profile plasma is the default
tools/guest-image/measure.py run/guest-images/vmcos-plasma.qcow2 --mem 2048 --cpus 2
```

Host needs `qemu-system-x86_64`, `qemu-img`, `curl`, `python3`, a writable `/dev/kvm` and OVMF at
`/usr/share/edk2/x64/OVMF.4m.fd` (`OVMF=` overrides). **No root, no display, network throughout.**
Never commit an image — `run/` is gitignored. Work directories are left in `run/guest-images/work-<profile>/`
with `serial1.log` / `serial2.log`; delete them when a build looks right, they cost gigabytes.

Login is `vmc` / `vmc`, `sudo` needs no password, root has the same password. Override with
`VMC_USER` / `VMC_PASSWORD`; `VMC_VIDEO` (default `1280x800`) sets the guest's resolution,
`DEBIAN_SUITE` / `DEBIAN_MIRROR_HOST` the base.

## How it works

**Phase 1 — preseed.** The netboot installer kernel and initrd are booted directly (`-kernel`/`-initrd`,
~50 MB, cached in `run/guest-images/cache/`) and answered by `preseed.cfg`, which `build.sh` templates and
serves from a throwaway `http.server`; QEMU user networking puts the host at `10.0.2.2`.
`phase1_install.py` only watches the serial console and fails loudly rather than hanging for an hour.

**Phase 2 — provisioning.** `phase2_provision.py` logs in over the serial console and installs the profile.
Three rules in that file exist because breaking them produced builds that *reported success* and shipped a
half-provisioned image:

* file bodies go over HTTP, never down the serial line (the emulated UART has no flow control and drops
  bytes under a multi-KB write);
* every step proves it ran with a token the command text cannot contain (`echo $VOK`), so the terminal's
  echo of the command cannot satisfy the check;
* a step is never typed at the shell — it is base64'd to a file and run with `sh -e`, because waiting for a
  prompt cannot tell "all six lines finished" from "line one finished".

**Why the image looks the way it does.** The guest has no GPU: every pixel is drawn by llvmpipe and shipped
to the monitor block as dirty rectangles, so the `plasma` profile ships with compositing off, animations at
zero, Baloo off, no splash, no screen locker and no DPMS blanking (a blanked screen in-world reads as a
crash). Details and the reasoning are in ROADMAP §6b.

Two things the shipped image must get right for the mod specifically: GRUB installs to the removable
`\EFI\BOOT\BOOTX64.EFI` path, because the mod boots OVMF from a read-only `-bios` image with no EFI
variable store; and `console=ttyS0` is stripped from the kernel command line at the end of phase 2, because
the mod runs QEMU with `-serial none` (pass `--keep-serial` to keep it for debugging).

## Handing an image to a computer

Copy it to `<world>/virtualminecraft/items/<uuid>.qcow2`, then

```
/give @s virtualminecraft:hard_drive[virtualminecraft:disk={id:"<uuid>",sizeMb:8192}]
```

Set the computer to **UEFI: ON**, insert the drive, Start. (ROADMAP §6c plans the real distribution route:
one shared base image with a per-computer qcow2 overlay, so ten computers cost one base plus ten small files.)

## `alpine/`

The superseded Alpine 3.24 + Xfce test image (`alpine/build.sh`, 581 MB, verified in-game 2026-08-24).
Kept only so the image that already exists stays reproducible until the Debian profiles have been measured
in-world; the project is one base distro from here (ROADMAP §6f).
