# How VirtualMinecraft actually works

*Written for [name]. One chapter per subsystem, added as each lands — nothing here is hypothetical. The
interactive version of chapters 1–2 (with the real code, click-through) is the "VirtualMinecraft Field
Guide" artifact from 2026-08-24; this file is the plain-text record.*

## Ch. 0 — The shape of the thing

A Minecraft mod jar runs in two places: on the **server** (in singleplayer, a hidden server thread inside
your game) and on every **client**. Only the client has a GPU and a window; only the server is allowed to
change the world. This mod puts the *virtual machine* on the server side — QEMU is just a normal program the
server starts — and streams its screen to clients, the same way the server streams block changes. That one
decision is why multiplayer works and why a dedicated server can host computers for a whole group.

## Ch. 1 — One frame, six hops

1. The guest OS draws into its (virtual) video card. QEMU's built-in **VNC server** notices which rectangles
   changed and sends only those, about 30 times a second.
2. Our `RfbClient` decodes them (ZRLE: 64×64 tiles described as "solid colour" / "small palette" / "runs" /
   "raw", then zlib) into a byte array that is our copy of the screen.
3. It only *notes* "this rectangle is dirty"; 20 times a second the server thread collects the notes.
4. Each dirty rectangle is copied out as RGB, cut into bands small enough for one packet, zlib-compressed,
   and sent to the players who said "I'm looking at this" in the last 3 seconds. In singleplayer the host's
   copy is handed over in-process instead — no compression, no network.
5. The client inflates it and uploads *just that rectangle* into a texture living on the GPU.
6. The monitor's renderer draws one textured square on the block's front face, unlit so it glows.

## Ch. 2 — One keypress, five hops

You press `l` in the VM screen → Minecraft calls our screen's `charTyped` (for letters/digits/punctuation,
so shift and keyboard layout come for free) or `keyPressed` (arrows, F-keys, Ctrl-combos) → converted to an
X11 **keysym**, the vocabulary VNC speaks → batched by `InputSender` → sent to the server → forwarded to
QEMU as a 6-byte RFB KeyEvent → the guest sees a USB keyboard press.

## Ch. 3 — Sound

The VM has a virtual Intel HD Audio card. Instead of playing to speakers, QEMU's VNC server offers an audio
extension: we ask for 16-bit mono at 22,050 Hz and it resamples and streams PCM to us. The server squashes
each 50 ms chunk to 8-bit μ-law (the telephone codec — 1 byte per sample, cheap and honest) and sends it with
the frames. The client feeds those chunks into an **OpenAL** source — the same audio engine Minecraft uses
for every sound — placed at the computer block, so it gets quieter with distance and pans as you turn, and
"snaps to your head" while the VM screen is open.

## Ch. 4 — Where things live on disk

Global settings: `config/virtualminecraft.json`. ISOs: `config/virtualminecraft/iso/`. Each computer's disk
and QEMU log: `<world>/virtualminecraft/<vm-id>/`. The tiny sockets QEMU and the mod talk over live in
`/run/user/<uid>/virtualminecraft/` because Linux caps socket paths at ~104 characters and Prism's world
paths are longer than that.

## Ch. 5 — The bus: how a program inside the VM touches the world

This is the OpenComputers part. QEMU can give a guest a **virtio-serial port**: to Linux it is just a file,
`/dev/virtio-ports/vmc.bus`, no drivers, no network. On the host end of that port QEMU opens a socket, and
the mod connects to it the same way it connects to QEMU's control socket.

Through that pipe the two sides exchange **one JSON object per line** (JSON-RPC 2.0, the boring standard):

```
guest → mod   {"jsonrpc":"2.0","id":1,"method":"redstone.setOutput","params":["east",15]}
mod → guest   {"jsonrpc":"2.0","id":1,"result":0}
mod → guest   {"jsonrpc":"2.0","method":"event","params":{"name":"redstone_changed","side":"west","level":15,"previous":0,…}}
```

Anything that can open a file and print JSON can drive it — Python, Lua, a shell script, a C program. `tools/bus.py`
is the reference client (`python3 bus.py list`, `python3 bus.py redstone.setOutput east 15`,
`python3 bus.py subscribe redstone_changed --watch`).

Inside the mod, a reader thread collects lines from the socket into a queue and the **server thread** empties
that queue every tick (at most 64 requests a tick, because it must not stall the game). That matters: the
world may only be changed from the server thread, so the request is parsed *there* and dispatched to a
**component** — an object with an address, a type and a fixed list of methods. `list` returns them with their
docs. The first component is `redstone`, which lives on the computer block itself:
`setOutput(side, level)` makes the block emit redstone from that face exactly like a lever would, `getInput(side)`
reads what arrives, and if the program `subscribe`s to `redstone_changed` it gets pushed a line whenever an
input face changes — no polling. Sides can be `north`…`down`, or `front/back/left/right` relative to how the
computer was placed.

Because the guest is a whole untrusted operating system, the mod only ever runs the methods on that whitelist,
caps line length and queue depth, and never turns a string from the guest into a file path. Anything a program
can do through the bus, a player standing at the block could do by hand — that is the safety rule.

The second component is `inventory`: every container touching the computer shows up as `inventory@<side>`.
`list` tells you what is in it, `getItemDetail` everything about one stack (down to the item's tags), and
`pushItems("south", 1, 10)` moves ten items from slot 1 into the chest on the south side. The move goes through
the same machinery a hopper uses, so furnaces only accept fuel where fuel goes, and modded storage works
without us knowing about it. Slots count from 1, like OpenComputers and ComputerCraft, so old scripts port.
If a program subscribes to `component_added`/`component_removed`, it hears about chests being placed or broken
next to the computer the moment it happens.

The third component is `screen`: every monitor linked to the computer can be switched into **text mode**, where
instead of the VM's pixels it shows a grid of characters the program writes over the bus — `write('Hello')`,
`set(1, 3, 'status: ok')`, colours per cell, `scroll`, `clear`. The grid lives in the monitor block itself and
is saved with the world, so it stays put when the VM is off; only changed rows are sent to players. This is
what makes a tiny guest OS with no graphics driver still useful, and it's how a program can draw a dashboard on
a wall. Right-clicking a monitor in text mode doesn't open the VM window — it sends the program a
`screen_touch` event with the cell you clicked, so a monitor can be a button panel.

What comes next: a small guest OS that boots straight into a REPL with the bus loaded — the "OpenOS moment".

## Ch. 6 — Sleep and wake: why the computer is still where you left it

Leave a world, or walk far enough that the computer's chunk unloads, and QEMU cannot just keep running: the
world it belongs to is gone. Killing it would mean a reboot every time you come back — the "VM Computers"
feel, not the OpenComputers feel. So instead the mod tells QEMU to **`savevm`**: QEMU pauses the guest for a
second or two, writes every page of its RAM plus the current state of the disk into the `disk.qcow2` file
itself (qcow2 files have room for such "internal snapshots"), and then the mod tells it to quit. A tiny marker
file, `suspended`, lands next to the disk. That's the whole secret: a 2 GB computer becomes a ~1 GB blob
inside a file it already had.

Coming back — loading the world, walking into the chunk, pressing Start, a redstone pulse — starts QEMU with
`-loadvm`, which reads that blob back before the guest gets to run a single instruction. The guest never knew
it stopped: the clock jumps, the shell prompt is still half-typed, the program you left running keeps going.
If the snapshot no longer fits the machine (you changed the RAM or CPU count, or QEMU was upgraded), the
mod notices QEMU giving up, throws the snapshot away and boots cold instead — you get a fresh boot, never a
broken computer. "Force stop" on a suspended computer does the same on purpose: it is the "pull the plug"
button, so it also forgets the saved state.

Two things do not come back with the guest. The mod's side of the bus is new, so programs that had
`subscribe`d to events must subscribe again (a reconnect is visible to them as the port going away and
coming back). And the redstone outputs are deliberately *left as they were*: the guest still believes it set
them, and it is right.

**Redstone wake** is the OpenComputers trick: set "Wake at ≥ N" in the config screen and any face whose
signal *rises* to N starts the computer. It is the rise that counts — a lever that is already on when the
chunk loads does nothing, so a computer you shut down on purpose stays down. "Sleep when low" is the mirror
image: when every face has dropped back below N, the mod sends the same ACPI power button the Shutdown
button sends, and the guest shuts down cleanly. One catch worth knowing: a face that the computer itself
powers strongly (a lamp it lit) reads that signal back as an input, so a guest driving a lamp can trip its
own threshold. OpenComputers has exactly the same quirk.

## Ch. 7 — Disks you can carry

A computer used to be one file: `disk.qcow2` in its own folder. Now the file can live in your pocket. A **hard
drive** item, a **floppy** item and a **CD** item are each a handle on a file under
`<world>/virtualminecraft/items/<serial>.qcow2` (CDs are different: they point at an ISO by name, because an
ISO is already a file you have). The first time a computer starts with a new disk inside, the file is created
— a hard drive is a sparse qcow2 of the size on its tooltip, a floppy is a 1.44 MB one. Install an OS on a hard
drive in one computer, sneak + right-click to take the drive out, right-click another computer while holding
it, press Start: the same OS boots there, with everything you saved. That is the whole trick, and it is the
one that makes the computer a *thing* rather than a place.

Hard drives and CDs go inside the case (up to three; the config screen lists them). Floppies and CDs also go
into a **disk drive** block placed against the computer — that one is hot: while the guest runs, putting a
floppy in makes `/dev/fd0` appear with your data, taking it out makes it go away, and a CD shows up on
`/dev/sr0` the way a real disc does. The mod does this with QEMU's `blockdev-change-medium` / `eject`
monitor commands on a device that was created empty at boot. (Which is why a disk drive you place *after*
the computer started needs a restart: QEMU cannot grow a new controller port on the fly, so every drive that
was touching the computer at boot gets an empty CD unit and — for the first two drives — a floppy unit.)

Boot order is no longer a `-boot order=dc` letter soup: every device gets its own `bootindex`, numbered from
the order you would expect. "Boot: removable 1st" tries the ISO field, then whatever is in the disk drives,
then the disks in the case, then the internal disk; "disk 1st" is the reverse. If there is *nothing* with a
medium at all, the mod does not even start QEMU: the monitor shows a small blue "VirtualMinecraft BIOS"
screen (the same text mode the bus uses) listing the boot devices and saying what to do. Set "Disk GB" to 0
and a new computer has no internal disk at all — a bare case that boots only from what you put in it.

Two things to know. Suspending a computer (Ch. 6) writes the snapshot into *every* writable disk that was
attached, so pulling a hard drive out of a suspended computer throws that saved state away (the mod tells you,
and it boots fresh next time); swapping a floppy while it is suspended is fine, it simply boots cold. And a
disk item that burns in lava takes its file with it only in spirit — the file stays on disk until you delete
it by hand; nothing in the game can reach it any more.

## Ch. 8 — The default OS (how the image is made)

A computer needs something to boot, and the thing it boots is an ordinary Debian 13 that was installed by a script
instead of by a person. `tools/guest-image/build.sh` boots the Debian installer in QEMU on your PC with **no window
at all** — the only way in is a serial port wired to a socket — and hands it a *preseed*, which is Debian's own
answer file: partition the disk this way, this user, this password, this package set, no desktop yet. The builder
serves that file from a throwaway web server on your machine; QEMU's fake network puts the host at `10.0.2.2`, so
from the installer's point of view it is just downloading a config off the LAN. Nothing needs root and nothing
needs a screen. About two and a half minutes later the installer reboots and the disk has a working Debian on it.

Then the builder boots that disk and does the second half over the same serial port: install the profile's
packages, tune them, drop `bus.py` in as `vmc` and `vmctui` next to it, write a README on the desktop, and power
off. The result is squashed with `qemu-img convert -c`, and that file *is* a hard-drive item once it sits under
`<world>/virtualminecraft/items/<uuid>.qcow2` (Ch. 7).

There are two profiles from the one builder. `--profile console` is a plain machine with no desktop — small and
fast, the one a server would hand out. `--profile plasma` is the default: a KDE Plasma desktop, **deliberately
detuned**. That last part matters more than it sounds. The virtual machine has no graphics card, so every pixel
of that desktop is drawn by its CPU, and then the mod ships only the *rectangles that changed* to your monitor
block. A modern desktop assumes a GPU and animates constantly, which means it would repaint most of the screen
every frame — the worst possible thing to feed a system that is trying to send only what moved. So the image
ships with compositing off, animations set to instant, the file indexer off, no splash screen, no screen locker
and no screen blanking, and it installs `plasma-desktop` rather than the full KDE bundle. Left stock it would
feel slow, and it would look like *the mod* was slow.

Two details exist purely because of how the mod runs QEMU. The bootloader is installed to the "removable device"
path that a USB stick would use, because the mod gives the virtual machine a read-only firmware chip with nowhere
to remember what to boot. And the serial console the builder relies on is *removed* from the finished image, since
a real computer block is started with no serial port at all, and a system still trying to talk to one waits for a
door that isn't there.

Three things the builder had to learn the hard way are worth knowing if you ever poke at it, because all three
fail *silently* — the build says it worked and quietly ships a half-made image. A serial port has no way to say
"slow down", so a large file poured into it arrives with pieces missing; files go over the web server instead.
Waiting for a shell prompt only tells you one line finished, not six, so each step is packed up and run as a
single script. And checking the output for the word "OK" proves nothing when the terminal echoes your command
back at you — the command has to print a value it does not itself contain.

The earlier Alpine + Xfce test desktop still exists in `tools/guest-image/alpine/`, kept only so the image built
on 2026-08-24 stays reproducible. Alpine was a placeholder: it uses a different C library, which means Discord,
VS Code, Chrome and most commercial software simply do not run on it — and running real software is the whole
point of a desktop.

## Ch. 9 — Cable, and a computer that can look around, make noise and talk

Until now a computer could only touch the six blocks pressed against it. That is fine for a chest and a
monitor, but it means the machine room *is* the machine: everything has to be crammed against one block.

**Bus cable** fixes that. Place a run of cable from the computer to wherever you like, and anything touching
that run belongs to the computer — a chest at the end of a corridor, a disk drive across the room. Cable is
just cable: it holds nothing, remembers nothing, and needs no setup. When the guest asks "what am I connected
to?", the mod walks the run right then and there and reports what it finds. Walk it too far (past 128 cable
blocks, or into terrain that is not loaded) and it simply stops looking.

One detail you can see in the names. A chest pressed against the computer is called `inventory@north` — the
side it is on. A chest four blocks down a cable is called `inventory@0,0,4` — how far it is from the computer,
in blocks. That is not an inconsistency; it is the address of a socket. A block that touches the computer keeps
the name it always had (so anything you wrote before cable existed still works), and a block out on the cable is
named by where it is. Move it and it becomes a different component, because it *is* one.

Disk drives came along for free, which was the real point. A drive on a cable becomes a genuine drive bay on
the virtual machine — the guest sees a CD tray, and swapping the disk in-world swaps it inside the running
computer, same as if the drive were bolted to the case.

The computer also grew three new senses:

- **`world`** — it can look. What block is on that side, what is at these coordinates, what time is it, is it
  raining, what biome, how bright, who is standing nearby. All of it is read-only and reaches at most 32 blocks,
  and it will never quietly load a distant chunk just because a program asked about it.
- **`speaker`** — it can make noise: note-block notes (all the instruments) or any sound in the game. It plays
  on the "Jukebox/Note Blocks" volume slider, so a computer that gets annoying can be turned down the normal way.
- **`chat`** — it can talk, and listen. `chat.say` puts a line in chat prefixed with the computer's name, and a
  program can subscribe to `chat` to hear what players say near it. Within 32 blocks by default, both ways.

Those last two are the first things a program inside the VM can do that *reach out at people*, so they are on a
budget: about eight sounds a second and twenty chat lines a minute per computer. Past that the guest gets a
polite "you're talking too much, try again in 2 seconds" rather than being ignored. Anything the guest types is
also scrubbed of Minecraft's formatting codes first, so a program cannot forge a message that looks like it came
from another player. The server owner can turn chat off entirely, and then the component simply is not there —
the guest sees an honest list of what it has, not a list of things that silently fail.

**Trying all of this by hand.** Inside the test desktop there is a program called `vmctui`: open a terminal and
run it. It lists what the computer is connected to, completes component and method names with Tab, explains any
method with `/help <name>`, and prints world events as they happen — flip a lever, say something in chat, place
a chest on the cable, and you see the computer being told about it in real time. It is the same bus a program
inside the VM would use; the console just makes it something you can poke at with your fingers.

## Ch. 10 — Monitors grow up: walls, pointing, and only sending what you can see

Until this chapter a monitor was a single block that showed one computer's screen and, if you right-clicked it,
opened a full-screen window. Milestone 5 turned it into a real display, in four moves, and every one of them was
built so the Computer of milestone 7 can use it without a single change to the monitor.

**The monitor no longer knows what it is showing.** It used to reach into the computer block for the VM's id. Now
it links to anything that promises to *own a screen* — a small contract called a `ScreenSource`: give me a screen
id, tell me your name, take my touches and clicks. The VM computer keeps that promise today; the milestone-7
Computer will keep the same promise, and monitors will not notice the difference. In the same move, the list of
who is watching which screen moved out of the VM and into one shared registry. That matters because *everything
that costs something per watcher asks that registry first*: the picture, the sound, the text rows, and even the
question "may this machine go to sleep". A screen nobody is looking at sends nothing. (Two bugs fell out of the
floor while doing this: the text screen was being sent to everyone with the chunk loaded, watching or not; and
the "don't stream past 48 blocks" setting had never been wired to anything.)

**A monitor left behind finds a new owner.** A monitor remembers *where* its machine was, not which machine it
was, and a rebuild is a normal thing to do — you blow up the computer, or overwrite it, and the screen above it is
still there. Until it was fixed the screen stayed loyal to a machine that no longer existed: the replacement
computer was perfectly healthy, said "waiting", drew nothing, and looked like broken hardware. Now a computer that
starts up next to a monitor whose owner is *gone* takes it over — and takes over the rest of that screen's wall
with it, so a rectangle never ends up half-adopted. It is careful about the difference between "gone" and "not
loaded right now": a machine in an unloaded chunk keeps its screens. And if the computer is powered but has no
screen at all, the case now says **"no monitor"** instead of "waiting", because "waiting" is the truth about the
software and a lie about the hardware.

**You get the resolution you can actually see.** Your client knows how big a monitor is on your screen — how far
away it is, how many blocks wide it is — and every half-second it tells the server which of four resolutions it
wants: full, half, quarter, or an eighth. The server keeps one full-size picture per computer and shrinks it on
the way out, averaging each little square of pixels into one, and shares that work between everyone who asked
for the same size. Walk up to a monitor and it sharpens; walk away and it softens. In numbers: the 1280×800
desktop is sent at 1280×800 when you are at arm's length, 640×400 at twelve blocks, 320×200 at twenty, 160×100 at
forty — a sixty-fourth of the pixels — and it snaps back to full when you return. Your clicks always land on the
right guest pixel whatever size you were being sent, because the client maps them into the full picture, not
the shrunken one. This is the trick that pays for the 1024×768 screens the Computer will have: a wall of them
costs almost nothing unless you are standing in front of one.

**You can click the monitor itself.** Right-click on a live picture is a left click on the guest at that exact
spot; resting your crosshair on the picture moves the guest's mouse; sneak + right-click opens the full-screen
window when you want the keyboard. A monitor with nothing live on it — a stopped machine, or the blue BIOS page
— opens the window on a plain right-click, because there is nothing to click on. The click is the *server's*
decision: it takes where your crosshair hit the block, works out which pixel that is using the same maths the
renderer uses to draw the picture, and hands the click to the machine. That is one piece of input work with
three customers: the VM, the Computer, and — since session 26 — a VR controller pointing at a screen, which
turned out to need no changes here at all: it arrives as the same ray → pixel → `InputSender` path the crosshair
always used. The keyboard was the half that needed new plumbing; ARCHITECTURE "The VR seam" has it.

**Monitors join into walls.** Put monitors next to each other, facing the same way, linked to the same machine,
and they become one screen. There is no "wall object" anywhere: each monitor just remembers its position in the
rectangle and how big the rectangle is, four small numbers, and one routine recomputes those numbers for every
monitor in a connected group whenever one is placed, broken or relinked — always the same way, bottom-left first,
so an awkward shape splits into the same rectangles whichever block you touched. The bottom-left monitor is the
*origin*: it holds the picture, it is the one screen the guest sees on the bus, the BIOS page draws on it, and
its renderer paints the whole wall — a black sheet over all the interior bezels first, so the wall reads as one
pane of glass, then the picture fitted inside. Click any block of the wall and the game works out where that is
on the whole rectangle before mapping it to a pixel. In testing, a 3×2 wall showed the desktop as one picture,
a click on its top-right block landed within six pixels of the computed spot, text mode drew across the whole
wall, and breaking a block in the top row split it into a 3×1 strip and two singles, as the rules say it should.

What is left on this milestone is the part only the admin's virtual machine cares about: sending real keyboard
scancodes to the guest through QEMU's D-Bus display instead of the VNC keysyms it gets today, which is what makes
non-US keyboards and games behave.


## Ch. 11 — The Computer: a small machine that lives inside the server

*(Milestone 7, step S1, built 2026-08-26. The design is ROADMAP §7h; this is the plain-language version of what
exists now.)*

The Command Computer is a real PC in a box: QEMU, an operating system, gigabytes. The **Computer** is the other
thing you asked for — the machine you can have twenty of, that boots the moment you place it, that you can port
a game to. It is not a virtual machine at all. It is a **Lua interpreter** (Cobalt, the same one ComputerCraft has
run a thousand computers on for years) living inside the game's own Java process, with a few hard rules around it.

**Why not the WebAssembly idea.** The design first chose Lua compiled to WebAssembly, because a WebAssembly
memory is a single byte array with a hard size — a memory limit, an exact snapshot and a locked door, all for free.
I built it, and it worked: every property it promised came true. Then I measured it, and it ran **fifteen to
thirty-four times slower** than Cobalt, because Java's JIT cannot make sense of an interpreter loop that arrives as
one 24-kilobyte method. That is pico-8 speed on a whole core, and you asked for more than a pico-8. So Cobalt it is,
and the three free properties are built by hand instead. The experiment stays in the repository as the record.

**The rules around the machine.** *It cannot touch the host:* its Lua world is built from scratch — no files, no
clock, no network, nothing Java can be reached from — and everything it can do outside itself goes through a
short list of `vmc.*` functions we wrote. *It cannot hog the server:* the game's own thread never runs Lua. A small
pool of worker threads runs machines in five-millisecond slices, round robin, and each machine gets a quarter of
one core over time (with a one-second burst); a machine over its share slows down, nothing else does. A hundred
machines spinning in `while true do end` left the server tick at 2.5 ms in testing. *It cannot eat the memory:*
a machine gets a budget — whatever its memory part is worth, 2 to 16 MB. Java cannot count a machine's memory directly, so the mod counts what
its worker thread allocates (exactly) and, when that adds up to a budget's worth, walks everything the machine can
still reach and estimates its size. Over budget, the running program gets an ordinary Lua error, "not enough
memory", and dies; the machine survives. A single call that would build a monster string is refused up front.

**Freeze and thaw.** Like the Command Computer, a Computer you walk away from stops costing anything. When its
chunk stops ticking, when the world closes, or when nobody has used or watched it for five minutes, it is
*frozen*: the kernel is asked to save what it wants to keep (the REPL keeps its variables and history), the machine
is stopped, and a couple of small files are written next to its directory. Coming back is *lazy*: a frozen
machine boots again when somebody looks at its monitor, when an event arrives for it (redstone, a click), or when
you ask — except that a machine frozen by an *unload* or a server stop comes back as soon as its chunk ticks,
because an automation machine that polls must not sleep forever. What is *not* yet exact is the running program's
heap: today the kernel saves what it chooses (the "floor" in the design); the exact snapshot is a later step.

**What you see today.** A beige box. Put a monitor next to it and the monitor shows a text screen with a Lua
prompt. Sneak + right-click the monitor for the keyboard; type `1+1`, or `bus.call("redstone","setOutput","east",15)`
to light a lamp; `help()` lists everything on the bus. A lever next to it prints `(redstone west 0 -> 15)`. Break
the block and the item you get keeps the machine: place it somewhere else and it says `Restored: N=41`. The
graphical screen, the sound chip, files and floppies, and the real shell are the next steps (S2–S4).

**And then a picture (S2, the same night).** The Computer now has a real screen: a 256-colour framebuffer that
sizes itself to the monitor it is wired to — 256×256 on one block, the full 1024×768 on a 4×3 wall — with the
usual drawing calls (`gfx.fill`, `gfx.line`, `gfx.text` with two built-in fonts, sprites with `gfx.blit`), a
palette you can change on the fly, and `gfx.present()`, which ends a frame and returns when the next one has been
sent — a game loop written the obvious way runs at the stream rate. The picture goes to players over the same
path the virtual machine's does, one byte per pixel instead of three, and shrinks with distance exactly like the
VM's. Typing works the way you would expect on a machine with no operating system of its own: the view sends
both the physical key (for games) and the character it produced on *your* keyboard layout (for typing).

**And files (S3, still the same night).** The Computer has a disk of its own (8 MB by default), and the floppies
and CDs from milestone 3 work in it too — but as *folders of files*, not disk images: a floppy is 1.44 MB of files
you can carry between Computers, a CD is read-only and holds up to 700 MB. A CD can come from you burning it in
the machine, or from a folder the server's admin drops into the config directory — which is how a game gets onto
a server from outside. The one rule is that a floppy belongs to one tier: format it in a Computer and the virtual
machine will ignore it, and the other way round. And the piece you asked for: put a file called `boot.lua` on a
floppy or CD, and the machine boots *that* instead of the ROM — your own operating system, with nothing but the
bare machine underneath it.

**The desktop (S4 and S6, the next day).** What the ROM actually shows is a little desktop: icons down the left,
a taskbar with a clock and a power button, windows you drag by their title bars, resize by the grip in their
bottom-right corner and close with the x, all done with the pointer on the monitor's face or in the full-screen
view. (The clock keeps time on its own now: the machine asks the server to wake it once an in-game minute and
repaints only those five characters — before that it only redrew when you touched it, which is why two
monitors could disagree about the time.) Games run at up to 60 frames a second on any monitor size now — the
machine hands each frame to whoever is looking the moment it is drawn, instead of waiting for the server's next
tick — and a game nobody is looking at drops back to 20, so it costs what it always did. The mouse pointer is
drawn by *your* game client, not by the machine: the machine tells it "the pointer is here" and the arrow is
painted over the picture on your side — which is why the arrow no longer costs the machine a redraw every time
you wave the crosshair across a screen, and why it stays sharp on a monitor far away. Terminal is the command line for those who
want it; Files browses the disk, floppies and CDs — and since session 18 it has a clipboard, so Copy, Cut and
Paste move a file between two directories or copy a whole program folder off a CD onto the machine's own disk,
which is what "installing" a game means here; Edit writes programs and runs them with F5, wrapping long lines so
none of them run off the edge of a small monitor; Settings names the machine and holds the wallpaper, the window
colour, the screensaver and the volume on three pages. The three "world" apps are what makes a Computer useful in a base
without writing a line of code: Inventory lists every chest on the bus and moves stacks between them by pointer,
Redstone shows the six sides with Off/On buttons (and lets you name them "door", "lamp"), World shows where you
are, the time as a day strip, the weather, who is near, and lets the machine talk in chat. The **Apps** button on
the taskbar is a start menu: Programs (every app, and every *program on a disk* — a folder with `main.lua` in it,
so a game on a CD shows up the moment the CD goes in), Documents (the last things this machine opened), Settings,
About this computer, and Shut down. **The right button opens a menu about whatever is under it** — bare desktop,
an icon, a title bar, a file in the file manager. Windows minimise and maximise from three boxes in the title
bar, and a minimised window waits in the taskbar. Leave the machine alone for a while and a **screensaver**
comes on — a starfield, a bouncing line, or just black — dismissed by any input; it runs only while somebody is
actually watching, so a machine nobody is looking at still goes quiet and hands its picture back to the server
the way it always did. And the desktop remembers: close the world and open it again, the same windows are open
with the same contents.

**The shell (U2, session 10).** The Terminal used to be a Lua prompt — powerful, but homework. Now it is a shell in
the Linux idiom, because that is what you asked for: a prompt that shows where you are (`/disk $`), `ls` with
directories in blue and programs in green, `cd`, `pwd`, `cat`, `cp`, `mv`, `rm`, `mkdir`, `echo`, `clear`, and the
machine's own verbs — `run` (or just the program's name — `2048` starts the CD game; `hello arg` runs
`/disk/hello.lua` with `arg` in `...`), `ps`, `kill`, `top`, `df`, `apps`, `open paint`, `edit file`, `set NAME
value` + `$NAME`, `history`, `date`, `label`, `reboot`. Tab completes commands, program names and paths; `help`
lists everything and `help cat` explains one. A `.sh` file is a script: one command per line, `$1`..`$9` and `$*`
for its arguments, `#` for comments. Lua is one keystroke away — `=1+1` evaluates an expression, `lua` switches the
prompt until `exit` — so nothing the old Terminal could do is gone. No pipes, no `&&`, no grep: the aim is the
*feel* of a terminal, not POSIX; those come back only if a real use turns up.

**The emulator (your idea, session 10).** `./gradlew computerEmulator` runs the very same machine — the same Lua
runtime, screen, files and ROM — in a plain window on your desktop, with a pretend world behind the bus (a redstone
component, a world, chat and two chests that answer like the real ones). It is how the shell and the apps get tried
and fixed in seconds instead of minutes, and it means Minecraft is only needed for the part that *is* Minecraft:
real chests and lamps, monitors, the view, freezing and waking, sound, other players. Your `/disk` in it persists
under `run/emulator/`, and closing the window saves the desktop like leaving a world does. It keeps the same
memory rule the real machine has — the same budget, metered the same way — so a program that hoards dies there
the way it would in the world.

**Porting a game to it: Meowzie's Adventure (U3, session 10).** The proof that the machine is "a computer you can
port games to" is your own game. `tools/computer/ma1/build.py` reads the Godot port's manifest and the 2× PNGs it
already extracted from the `.sb3`, shrinks every costume to *half* Scratch resolution (so the 480×360 stage is
240×180 and the whole game fits a single monitor block), finds one 251-colour palette for all of it, and writes a
CD: raw indexed pictures plus `meta.json` (sizes and rotation centres). The clever part is free: MA1's collision
*is* its colours (that blue is water, that red is the exit, that dark red hurts), so the build reserves palette
entries 1/2/3 for those three and the game's hitbox test is "is the byte under this pixel a 1, 2 or 3" — the same
idea the Scratch original had, just cheaper. `tools/computer/cds/ma1/main.lua` is `main.gd` moved over
function by function: the world scrolls around Meowzie, fixed-height jumps, the ±415 screen pop-in, the menus,
lives/timer, saves in `/disk/ma1.json`. Play it: build the CD, `/vmc give cd ma1`, into a drive, then `run "Meowzie's
Adventure"` in the shell or pick it in the Apps list. What did not port: the music (MP3s; the chip plays 8-bit
samples under 64 KB), the logo jingle, and — your call the next day — **the slideshows**: the 60-frame intro
animation, the zone cutscenes and the 95-frame ending. They were just giant images (three quarters of the CD, and a
zone cutscene alone was 30 MB of frames, more than seven machines' worth of memory), so the logo goes straight to
the title, a boss act straight on to the next zone, and the ending shows its backdrop for a moment. The build ships
only what the basic game touches — the ten backdrops and thirty-odd menu screens it names, not all eighty — and the
game loads a level's art when the level starts. The whole CD is a few MB now instead of 81, and a running level
costs about 1 MB of the machine's budget. The same build with `--scale 1` makes the native-resolution version for a 2×2
wall. *How we know it all works without anyone playing 24 levels:* `tools/computer/ma1/solve.py` re-implements the
game's physics in Python over the CD's level pictures and searches every level for a route to its exit; the routes
then drive the real `main.lua`, tick by tick, on plain Lua outside the machine (`replay.lua`), through every menu,
level, the game over and the ending — TESTING has the recipe.

**Computers talking to each other (U3, session 11).** Put a bus cable between two Computers (or stand them next
to each other) and each shows up in the other's `net` list, by name and by address. `net send bob hello` in the
shell — or `net.send("bob", {kind = "ping", n = 1})` from Lua, tables travel as tables — and bob's Terminal prints
`<alice> hello`; a program on bob sees it as a `net_message` event with `from` (alice's address), `sender` (her name)
and `message`. The pleasant surprise: a computer that has dozed off (frozen to disk because nobody was using it)
**wakes up for a message** — mail is a reason to exist again — so a computer can wait for an order for days at no
cost and spring to life when it arrives. A computer may send about ten messages a second (a burst of a hundred,
then the rate) and each is at most 4 KB, so a runaway program cannot flood its neighbours; the server owner can
switch networking off, and then the component is simply not there.

**The wireless modem (U3, session 12).** The cable is fine inside a base and hopeless between two of them, so
there is now a **modem** block. Put one against a computer (or on its bus cable) and put another against a
computer somewhere else, and if the two modems are within 64 blocks the machines are in each other's `net` list
— same `net send`, same `net_message`, same budgets; only the *location* column changes, from `north` or an
offset to `wireless`. Three things worth knowing, all of them deliberate: a modem serves the machines on **its**
bus, so a machine without one of its own stays cable-only even if a neighbour has one; modems do **not** relay,
so a chain of three does not join the ends and the only thing to reason about is distance; and right-clicking a
modem tells you what it can hear (`range 64 blocks, 1 modem(s) in range, 1 computer(s) reachable`), because a
radio that fails silently is the worst kind of radio. Nothing is stored: who is reachable is worked out from the
world each time you ask, exactly as the cable's answer is.

**Making something, then using it (U3, session 12).** Paint and Music were islands: you could draw a sprite or
write a tune and then only look at them. Now what they write is what a program reads. A song saved by Music is a
plain file, and `snd.playsong("/disk/songs/tune.json")` plays it in the background from any program of yours —
literally the same call the app's own Play button makes — while your game gets on with drawing. A sprite saved
by Paint is `gfx.loadsprite`, and `gfx.sprite` puts it on the screen. The shell has `play tune.json` (and `play
stop`) for when you just want to hear it, and `examples` lists two small programs in the ROM that do exactly
these two things — read them, run them, copy one to your own disk and change it. And because a program that
loads its own colours (every ported game does) used to leave the desktop wearing them, a program's palette is
now put back the moment it exits.

**A disk you can see arrive.** Put a CD in a drive and the machine says so: a message names the disk and the
programs on it, and the program itself appears as an icon at the top of the desktop's icon column, ready to
click — no menu, no path to type. Files names the disk on its status line, so a stack of floppies is tellable
apart from inside the machine.

**Sound (S5).** The Computer has a sound chip, the kind an 8-bit console had: four voices (square, triangle, saw,
sine or noise, each with an envelope so notes have an attack and a tail) and two channels that play short samples.
Every server tick the chip mixes 1/20 s of audio and sends it down the same pipe the virtual machine's sound card
uses, to everyone within 32 blocks, coming from the block itself — you hear a Computer you are not looking at, and
it fades with distance. The Music app is a small tracker (sixteen steps, four voices, Play) so the chip is heard
without programming; `snd.beep()` in the Terminal is the one-liner.

**Software on disks (S7).** Two games ship in the ROM — Snake and Breakout — and everything else arrives on a
**CD**: a folder with `main.lua` and a `program.txt` naming it, handed to a player, and listed by the Apps button
the moment it is in a drive. **The CDs now travel inside the mod itself**, so `/vmc give cd mines` works in a
fresh world with nothing copied anywhere; a world's own `config/virtualminecraft/cds/<name>` still wins if you
want to override one or add your own. There are twenty of them: the games (**Mines**, **Blocks**, **Sentry**,
**2048**, **Life**, **Keypad**, **Calculator**, **Lights Out**, **Hangman**, **Solitaire**, **Barrage**,
**Drift**, **Drift 3D**, **Pinball**, **Maze**, and Meowzie's Adventure), the two halves of the web (**Server** and **Browser**), and the productivity
ones — **Notes**, the **Reader** and **Sheet**, a real spreadsheet with formulas and a chart. A game is just a program: draw with `gfx`, read the keys the
kernel hands you, make noise with `snd`, call `gfx.present()` once per frame. Paint makes the sprites. Mines takes
the pointer instead of the keyboard, because the desktop is pointer-first and so is a minefield; **Sentry** is the
one that could not exist anywhere else — it asks the world sensor who is nearby, draws them on a radar sweep, keeps
a log of who came and went against the in-game clock, and, armed, holds a redstone output on while somebody is
inside the ring. It also stops animating when nobody is watching it, which is the same courtesy the desktop pays. The whole
machine — 992 of them booted at once in the test — costs the server about two milliseconds a tick, and once
they sit idle for half a minute they are files on disk and cost nothing until someone looks again.

## Ch. 12 — Three computers, and the parts that go in them

Until now every Computer was the same beige box with 4 MB in it. Now there are three cases — and **a case is not a
computer, it is an empty box with a ceiling written on it.** What the machine *is* comes out of the four parts you
put in it; all the case decides is how far those parts are allowed to go:

- A **Basic Computer** is the cheap one. It boots straight into the shell — no desktop, no icons, the prompt fills
  the screen like a home computer from the early eighties — and it caps everything low: 2 MB, a 256×256 picture
  whatever size the monitor is (a wall just shows it bigger), three voices, 2 MB of disk. Floppies are how you keep
  things. Every stock app still opens; it just comes up full-screen, one at a time, and closing it drops you back at
  the prompt. If you would rather have the desktop on it anyway, Settings has a "Boot:" button (so does the case's
  GUI) and the machine obeys from its next boot.
- A **Computer** is the machine you already know: up to 8 MB, the desktop, and a 2×2 wall showing 512×512.
- An **Advanced Computer** is the 1993 PC: up to 16 MB and a 1024×768 picture, half a core to itself, 32 MB of disk.
  Meowzie's Adventure at native size wants one of these.

**Parts** are what the machine actually has — Memory, Processor, Graphics Card and Hard Drive, each in I, II and
III. Right-click the case and the GUI opens: four slots along the top, your inventory underneath, and under the
slots a line saying what the machine adds up to right now ("2 MB · CPU 25 % · disk 2 MB", "256×256, 256 colours,
3 voices"). Drop a part in and the line changes; the machine restarts for its new hardware (a memory budget cannot
shrink under a running program, so a part change is always a reboot — you see a toast). A part bigger than the case
can use is not refused, it is simply clamped: a Memory III in a Basic Computer gives 2 MB, the case's ceiling, and
works. Any part fits any case, and it is only ever better or the same.

**An empty case does nothing at all, and it tells you so.** A processor and memory are the minimum; without them
the monitor shows a black screen with NO PROCESSOR OR MEMORY on it in red and a sentence saying what to fit, the
case's GUI says the same, and the power button answers "This case has no processor or memory" instead of pretending.
The other two parts are optional and fail honestly rather than fatally: with no **graphics card** the machine runs
fine but has no picture at all — a monitor wired to it says NO GRAPHICS CARD — and with no **hard drive** it boots
the ROM with no `/disk`, so the shell starts in `/rom` and a floppy or a CD is your only storage. Computers already
standing in a world are given parts the first time the world loads after this change, chosen so that no part of
them gets *worse*; nothing you have built goes dark.

Hover a slot and it tells you the ceiling before you commit: "Memory III — 8 MB — Up to 2 MB in this case". An
empty slot does the same, so you can see what a case would take before you have the part; so does a case held in
the hand, which says outright that it is empty and what it takes.

For programs nothing changes except honesty: `os.info()` tells a program what it is running on (tier, memory,
colours, the screen cap, voices), `gfx.size()` still tells the truth about the picture, and a program that sets
palette entry 200 on a 16-colour case just sees the default colour stay — asking for more than the case has never
errors, it quietly gives you what there is. The shell's `top` prints the case on its first line. If you want to
*see* that last rule, `examples colours` paints all 256 swatches after turning the palette grey: on a machine with
a Graphics Card I only the top row goes grey and a line marks where the card stopped listening; put a better card
in and the whole page goes grey.

Crafting a case gives you the case and nothing else, so a first machine is a case plus a Memory I and a Processor I
— which is the point of the ladder rather than an obstacle in front of it. Everything is craftable — the three
cases, the twelve parts, monitors, disk drives, floppies, blank CDs, bus
cable and the wireless modem — from plain vanilla things: iron, redstone, glass and a slab for a Basic Computer, gold
for a Computer and the II parts, a diamond for an Advanced Computer and the III parts, an ender pearl for the modem.
Only the Command Computer stays uncraftable: it is the operators' machine.

## Ch. 13 — The manual is on the machine

There is a **Manual** on the desktop, and it is the whole point of the phrase "never leave the game". Ten pages:
what the thing is, the parts and the case, the desktop, the shell, disks, the world outside, computers talking
to each other, what happens when you walk away, writing your own, and a page of what to do when something looks
broken. It lives in the ROM, not on a CD, because it is what you reach for on the evening the computer will not
switch on — and a manual you need a disk in a drive to read is no use that evening.

The shell has the same thing: `man` lists the pages, `man 4` prints one, and `man ls` prints the help for `ls`,
because `man` meant that before the manual existed.

And because a manual on the machine is no use to somebody who has never met a machine, there is a written book —
*Notes on the Machine* — in village house chests. Four pages in the voice of whoever owned one before you, and
the last of them tells you to go and read the Manual.

## Ch. 14 — Coming back where you left off

A Computer nobody is looking at is frozen: written to a file and let go of, sometimes for days. When somebody
walks up again it comes back with its desktop exactly as it was — the windows in their places, the spreadsheet
still holding its cells — because that is *data*, and data can be written down. What cannot be written down is a
running program: it is a live call stack on the Java heap, and there is no honest way to put one in a file and
take it out again after the mod has been updated.

So a program comes back **if it says how**. Three lines in its `main.lua` — a version number, a function that
returns what it wants to keep, and a call that hands it back — and the game you were halfway through is the game
you find when you return. 2048 does exactly this: the board and the score, nothing else. Behind it the kernel
re-runs the program from the top and gives it its table; it is not a savestate, it is a save *file*, and that is
why it still works after the mod is updated. A program that keeps nothing is simply not running when you come
back, exactly as before. And if what you want is a machine that starts something at every boot rather than
resuming it, `/disk/autostart.lua` is still the answer — a program that came back from a freeze is not started a
second time by it.

## Ch. 15 — What day is it?

A Minecraft day is a day. So the clock inside these machines is not a decoration: **world tick 0 is
1970-01-01 at 6 in the morning**, and every tick after it is 3.6 seconds of the world's own time. A world you have
played for four hundred days is in 1971, and the machines standing in it know that. `os.epoch()` is the world's
milliseconds since 1970 — the world's, not yours; a machine has no idea what the date is where *you* are sitting —
`os.date("%A %d %B %Y")` spells it out, and the **Calendar** on the desktop draws the month with today boxed in.
`date` in the shell prints the same thing.

The one thing worth knowing is that the two counters disagree on purpose. The *day number* — the one the world
component reports and the taskbar shows — ticks over at dawn, because that is when a Minecraft day starts. The
*date* ticks over at midnight, because that is when a date starts. So one Minecraft day always spans two dates, in
exactly the way a real night does.

## Ch. 16 — Sitting at it in VR

Put on a headset (Vivecraft plus the optional `virtualminecraft-vr` jar) and the mod stops pretending you have a
mouse. **Your dominant hand is the pointer**: aim the controller at any live monitor and the guest's cursor
follows the dot; the trigger and grip are its buttons; a slight smoothing keeps a four-block-away scroll bar
hittable despite the fact that a held hand is never still. None of that needed changes to the input path you
read about in Ch. 2 and Ch. 10 — a controller ray goes in where the crosshair ray used to, and the machine on
the far end cannot tell the difference.

**The "use" button opens the keyboard**, because in VR it is the one button with nothing left to do — pointing
took the mouse's job, the triggers took its buttons. Use a monitor and Vivecraft's floating keyboard appears,
wired straight into the screen you were pointing at: what you poke lands on the machine, not on your movement
keys, and using the monitor again puts the keyboard away. The keys bound to jump and sneak are deliberately
ignored while it is open — a stick click *is* a Space press by the time the game sees one, and the alternative
was typing stray spaces or hopping every time you poked the space bar.

**The Keyboard block is muscle memory made of iron and stone buttons.** On a desktop client it is furniture — a
thin slab of keys to dress a desk with. In VR, opening the keyboard near one pins the floating keyboard over it,
and it *stays* there, in the world, like a real keyboard on a real desk — which is the whole point, in [name]'s
words: "so the user can get used to where the keyboard is, kinda like how I know where everything is in real
life." Walk around, come back, and the keys are where you left them. No block nearby, and the keyboard floats in
front of you the way Vivecraft always did.

**Scrolling is the right stick**, once you bind it: the mod registers "Pointer Scroll Up/Down" as ordinary key
mappings, Vivecraft turns any mod's key mapping into a bindable controller action, and holding the stick repeats
the way holding a wheel would. The one thing VR still does not have is a mouse you can push across a desk —
parked on purpose, because a pointed ray is a touchscreen, and nothing on these machines wants mouselook yet.
