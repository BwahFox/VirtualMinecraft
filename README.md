## AI disclosure

This mod was developed **collaboratively between a human and an AI**, and you deserve to know that before
deciding how you feel about it. The design, direction, decisions, art judgment and in-game testing are
[BwahFox](https://github.com/BwahFox)'s; the majority of the code and documentation was written by Claude
(Anthropic's AI) working under that direction, across six days of sessions. Everything shipped was verified by automated test harnesses
and by a human playing it. This is also why the mod is **not on Modrinth**: their policy restricts
AI-generated content, and we'd rather respect that than lawyer around it. If AI-assisted software isn't for
you, that's a fair choice, and this notice exists so you can make it.

Note: Some textures were made using a python script. NOT AI. AI was not used in the generation of any art assets.
Textures may change in the future to hand-drawn ones. For now though, the textures are fine.

# VirtualMinecraft

Real computers inside Minecraft — two kinds of them.

The **Computer** is a machine you craft, build from parts, and use: it boots to a desktop with a terminal, a
file manager, an editor, paint, music, a spreadsheet, a browser, and a shelf of games. Software ships on CDs
you find in a **software store** that generates in the world, or buy from a **Clerk** villager. Run **bus
cable** between machines and they talk to each other, read chests, drive redstone, serve web pages — and a
crafted pair of **Bus Bridges** joins two networks across any distance, up to and including opposite world
borders (60 million blocks; it works). Computers sleep when you leave and wake when a message arrives, so a
server in a mountain you never visit still answers.

The **Command Computer** is the admin tier: a block that runs a genuine **x86 virtual machine** (QEMU/KVM) on
the server, installed from a real ISO, its screen streamed only to players looking at its monitors. One or two
per world; operator-gated; not craftable, on purpose.

There is also full **VR support** (Vivecraft, optional second jar): the controller is the mouse, a floating
keyboard types at whatever screen you point at, and a **Keyboard block** on your desk anchors that keyboard in
world space so your hands can learn where it is.

## Versions

- Minecraft **26.2** · Fabric Loader **≥ 0.19.3** · Fabric API · **Java 25**
- Everything the Computer needs ships in the jar. Multiplayer works; singleplayer is the primary target.
- VR additionally needs **Vivecraft** and the `virtualminecraft-vr` jar (omit both and nothing changes).

## Playing

**[PLAYERS.md](PLAYERS.md) is the guide** — installation, every crafting recipe, your first computer, disks
and software, networking, VR. In game, the machine documents itself: the **Manual** app, `man` in the shell,
and a book that generates in village chests pointing at both.

## Writing software for it

The machines run Lua with a small, honest API — a framebuffer, a synthesizer, files, windows, the bus.
**[PROGRAMMING.md](PROGRAMMING.md)** is the out-of-game guide; the emulator
(`./gradlew computerEmulator --args="--cd sheet"`) runs the identical machine in a desktop window, no
Minecraft required. Twenty-one programs ship on CDs; every one of them started as a file in
[`src/main/resources/virtualminecraft/cds/`](src/main/resources/virtualminecraft/cds/).

## The Command Computer (server admins)

The VM tier runs QEMU on the machine hosting the world, so that host needs:

- `qemu-system-x86_64` and `qemu-img` on `PATH`
- `/dev/kvm` readable and writable (group `kvm`) — without it QEMU falls back to slow TCG emulation
- optional OVMF (`edk2-ovmf`) for UEFI guests

ISOs go in `config/virtualminecraft/iso/`; disks live under `<world>/virtualminecraft/<vm-id>/`; everything is
driven with `/vmc`. Linux first — unix sockets and KVM; other platforms fall back to TCP and TCG, untested.

## Building from source

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk   # Minecraft 26.2 wants Java 25
./gradlew build
```

produces `build/libs/virtualminecraft-<version>.jar` (install this one) and
`vr/build/libs/virtualminecraft-vr-<version>.jar` (only for VR). The VR module compiles against a Vivecraft
jar dropped into `vivecraft/` (gitignored); a clone without one still builds everything else. Verification is
eight harnesses, all runnable without a game:

```sh
./gradlew romBootTest luaMachineTest schedulerTest soundChipTest screenDeviceTest blockShapeTest machineFilesTest chassisVoiceTest
```

## Repository map

| File | What it holds |
|---|---|
| [PLAYERS.md](PLAYERS.md) | the player's guide |
| [PROGRAMMING.md](PROGRAMMING.md) | writing programs for the Computer |
| [HOWITWORKS.md](HOWITWORKS.md) | the mod explained mechanism by mechanism, for the curious |
| [ARCHITECTURE.md](ARCHITECTURE.md) | code structure, for developers |

## License

[LGPL-3.0-or-later](COPYING.LESSER). Use it, ship it in packs, learn from it — derivatives stay open.

## Status

**Complete.** Development ran August 24–29, 2026; everything above is built, tested, and verified in game.
The source lives here on GitHub; the mod is deliberately not distributed on mod-hosting sites. Future work
happens in companion mods, in their own repositories — the first is
[Continents of Time](https://github.com/BwahFox/continents-of-time).
