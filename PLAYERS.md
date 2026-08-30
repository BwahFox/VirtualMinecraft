# VirtualMinecraft — Player's Guide

Real computers in Minecraft. Craft a Computer, put a Monitor on it, and you get a machine with a desktop,
files, games, a paint program, a spreadsheet — even its own tiny web. Run cable between two of them and they
can talk, share files, and reach chests and redstone along the wire. Everything in this guide is obtainable in
survival.

*(There is also a second, admin-only kind of computer that runs a genuine x86 virtual machine — see
"For server admins" at the end.)*

---

## Installing

You need, in a Fabric instance:

- **Minecraft 26.2** with **Fabric Loader 0.19.3 or newer** and **Fabric API**
- **Java 25**
- the mod jar: `virtualminecraft-<version>.jar`

That's the whole mod. One optional extra: if you play VR with **Vivecraft**, also install
`virtualminecraft-vr-<version>.jar` next to it (see "Playing in VR"). Without a headset, leave it out —
nothing changes.

---

## Your first computer

1. **Craft a case.** Three tiers — a better case fits better parts and bigger screens:

   | Case | Recipe (3×3) |
   |---|---|
   | **Basic Computer** | iron / redstone–glass pane–redstone / iron–wooden slab–iron |
   | **Computer** | iron / redstone–glass pane–redstone / iron–gold–iron |
   | **Advanced Computer** | iron / redstone–diamond–redstone / iron–gold–iron |

2. **Craft parts.** A case is just a box — the parts are the machine. Each comes in levels I, II and III
   (the middle ingredient sets the level: **iron → gold → diamond**, except Processors which go
   iron → quartz → diamond):

   | Part | Recipe | What it does |
   |---|---|---|
   | **Memory** | redstone–metal–redstone / paper×3 | how big a program can be (2–16 MB) |
   | **Processor** | redstone×3 / redstone–metal–redstone / redstone–gold nugget–redstone | how fast it runs |
   | **Graphics Card** | pane–redstone–redstone / pane–metal–redstone / pane–paper–paper | screen size and colours |
   | **Hard Drive (part)** | iron×3 / iron–metal–iron / iron–redstone–iron | built-in disk space |

3. **Place the case and open it** (right-click) to slot the parts in. The minimum to boot is a **Processor
   and Memory** — an empty case is a dead box, and it will tell you so.

4. **Craft a Monitor** (glass panes / iron–redstone–iron / iron–glowstone–iron) and place it on or next to
   the case, facing you. The machine boots to a desktop.

More monitors placed side by side, facing the same way, join into **one big screen**. Screens glow in the
dark, go dark when the machine is off, and get sharper as you walk closer.

---

## Using it

- **Point your crosshair at the screen** — that moves the machine's mouse.
- **Right-click the screen** — that's a click, right where you're aiming.
- **Sneak + right-click** — opens the screen full-window, with your whole keyboard. **Esc** leaves;
  hold **Right Alt** and press Esc to send Esc to the machine instead.
- Text too small? **Settings → Desktop → Text** on the machine's own desktop.

The desktop has a Terminal (a real shell), a file manager, a text editor, Paint, Music, a Calendar (the
world has real dates — day one is January 1st, 1970), and a few built-in games. **The full manual is on the
machine itself**: open the **Manual** app, or type `man` in the Terminal. A written **book pointing to all of
this generates in village chests**.

---

## Disks and software

| Item | Recipe | What it is |
|---|---|---|
| **Floppy Disk** (×4) | paper×2 / iron nugget–redstone | small, writable, carry your files around |
| **CD** (×4) | pane–nugget / nugget–pane | read-only — software ships on these |
| **Hard Drive** | iron ring / diamond centre / redstone below | big removable storage |
| **Disk Drive** | iron×3 / iron–slab–iron / iron–redstone–iron | the block that reads all of them |

Place a Disk Drive next to a computer (or on its cable), right-click with a disk to insert. Programs on a CD
show up in the Apps menu. A machine also runs `/disk/autostart.lua` at every boot — that's how you make a
server come back up on its own.

**Where software comes from:**

- **The software store** — a small quartz building that generates in the world and in villages. Its chests
  hold program CDs.
- **The Clerk** — a villager profession. Craft a **Cash Register** (stone buttons / iron–gold nugget–iron /
  iron–chest–iron), place it near an unemployed villager, and they'll take the job and **sell software for
  emeralds**, better titles at higher levels.
- Two games (Snake, Breakout) are built into every machine.

The catalogue so far: 2048, Barrage, Blocks, Browser, Calculator, Drift, Drift 3D, Hangman, Keypad, Life,
Lights Out, Maze, Mines, Notes, Pinball, Reader, Sentry, Server, Sheet (a spreadsheet), Solitaire, and a
Sprite editor. A **Starter floppy** (from stores and clerks) has a small commented program meant to be
edited — the machine's Edit app is how you write your own. Curious about programming it? The in-game Manual
and the repo's PROGRAMMING.md cover everything.

---

## Networks: cable, chests, redstone, and the web

**Bus Cable** (iron nugget–redstone–iron nugget, ×6) is the nervous system. Run it from a computer to
whatever you want the computer to see:

- **Chests and other inventories** along the cable can be listed and counted from the Terminal or a program.
- **Redstone** can be read and set.
- **Disk Drives and Monitors** work over cable too — the screen doesn't have to touch the case.
- **Other computers** on the same cable can message each other, browse each other's files, and serve web
  pages to each other (the **Server** and **Browser** CDs are a matching pair).

A **Wireless Modem** (iron–ender pearl–iron / redstone–iron–redstone) placed on a cable gives the machines on
it wireless reach to other modems — for talking *between* bases.

**Computers sleep.** Walk away and a machine freezes where it is, costing the server nothing. It wakes when
you come back — or **when another computer sends it a message**, even from an unloaded corner of the world.
A frozen web server still answers, seconds after the request wakes it. Set up autostart and you can genuinely
run a server in a mountain you visit once a month.

### Bus Bridges — networks with no cable between them

The **Bus Bridge** (iron–cable–iron / iron–eye of ender–iron / iron–cable–iron) crafts **two at a time,
already linked to each other**. Place each half touching bus cable (one cable block is enough), however far
apart — the two cable runs become one network. Chests, drives and machines on the far side all show up, and
nothing in between needs to exist, let alone be loaded. Two computers at opposite world borders can talk;
it's been done, all 60 million blocks of it.

- **Right-click a bridge** to be told where its partner is.
- **Right-click a placed bridge while holding another bridge** to pair them instead.
- **Sneak + right-click with an empty hand** to give a bridge a fresh identity (unlinks it).
- Bridges don't cross dimensions.

---

## Sound

Machines have a 4-voice synthesizer — games use it, the **Music** app sequences real songs with it, and
programs you write can too. The case itself hums: a POST beep at boot, drive clicks, a fan that spins up
when the machine is working hard. (Config `computerChassisVolume`, `0` for silence.)

---

## The Keyboard block

**Keyboard** (stone buttons ×3 / iron ×3) — a slab of keys for your desk. On a normal client it's furniture
that makes a computer corner look right. **In VR it's the point**: see below.

---

## Playing in VR (Vivecraft)

Install the optional `virtualminecraft-vr` jar alongside Vivecraft. Then:

- **Your hand is the mouse.** Point the controller at any screen — the cursor follows. Dominant trigger =
  left click, the other hand's = right click.
- **Press "use" on a monitor** to summon the floating keyboard, wired to that screen. Same button puts it
  away. What you poke lands on the machine, not on your movement — clicking a stick mid-typing won't jump you
  or type stray spaces, but poking the space bar still fires your ship in Drift.
- **Place a Keyboard block on your desk** and the floating keyboard anchors itself over it and *stays there*,
  like a real keyboard — so your hands learn where it is. (Tune with `vrKeyboardHeight` / `vrKeyboardForward`
  / `vrKeyboardTilt` in the config.)
- **Scrolling**: bind **"Pointer Scroll Up"** and **"Pointer Scroll Down"** to your right stick in
  Vivecraft's controller bindings — they do nothing until you bind them, and everything after.
- Sneak + use still opens the classic full-screen panel if you prefer it.

---

## For server admins: the Command Computer

The **Command Computer** is the mod's second tier: a block that runs a **real x86 virtual machine** — an
actual operating system from an actual ISO, screen streamed to its monitors, and only to players looking at
them. It is deliberately **not craftable** and gated to operators, because it runs QEMU on the server host.

The host needs `qemu-system-x86_64`, `qemu-img` and writable `/dev/kvm`; ISOs go in
`config/virtualminecraft/iso/`. Everything is driven by the `/vmc` command. One or two per world is the
intended dose — the Lua computers above are the ones you build twenty of.

---

## Odds and ends

- Config lives in `config/virtualminecraft.json` (per world in singleplayer). Highlights:
  `computerChassisVolume` (case sounds), `netWakeSeconds` (how long a message keeps a sleeping machine's
  chunk awake; `0` disables remote waking), `busMaxCables` (network size cap — use bridges past it).
- A cable network tops out at 1024 cable blocks per run. Bridges are the answer to "bigger", and to
  "elsewhere".
- Monitors under shader packs: screens draw full-bright and unlit on purpose (they're screens). If a shader
  re-lights them oddly, that's the interaction to suspect.
- Everything here drops itself when broken — and **a broken computer is not a lost computer**: its parts pop
  out, a drive drops its disk, and the case item remembers which machine it is. Place it back down anywhere
  (parts back in) and it's the same computer, files and all. Moving house is safe.
