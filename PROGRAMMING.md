# Writing software for the Computer

*How to write programs that run on the in-game Computer — the Lua machine, not the VM. This is the
out-of-game companion to the in-game Manual (ROADMAP §9 U3c). The other docs describe how the **mod** works;
this one describes how the **machine** works, from the point of view of somebody writing software for it.*

*Everything here was verified by running it in the emulator. Where something is reported from the source
rather than exercised, it says so.*

---

## 1. The machine you are writing for

| | |
|---|---|
| Language | **Lua 5.2-ish, via Cobalt.** Close enough to standard Lua that a tutorial works, with the gaps in §9. |
| Screen | An **indexed** framebuffer — you write palette *indices*, not colours. 256×256 up to 1024×768 depending on the case and monitors. |
| Screen size, precisely | **256 px per monitor block, then clamped by the case.** A 1×1 monitor is 256×256 *whatever* case is behind it — a graphics card raises the clamp, not the monitor, so it does nothing for a single block. Only a bigger wall gives you more pixels: 2×2 → 512×512, 4×3 → 1024×768. **Never hardcode a size**; read `gfx.size()` and lay out from it. |
| Colours | 256 entries. 0–15 are the base colours; 16–255 are a 6×6×6 colour cube plus greys. A case only lets you *redefine* as many as it has (`os.info().colours`), but you can always *draw with* all 256. |
| Memory | 2 MB with a Memory I, up to 16 MB with a Memory III in an Advanced case. That is the whole machine — your program, your data, and the Lua runtime. |
| Sound | 4 synth channels (square, triangle, saw, sine or **noise** as the wave) + 2 sample channels. |
| Storage | `/rom` (read-only), `/disk` (this machine's own disk), `/cd0` (a CD in the drive), floppies. **`/disk` is not guaranteed** — a case with no hard drive in it has none (ROADMAP §9 U10(a)); `fs.exists("/disk")` answers, and `shell.home()` is where the shell actually started. |
| The parts are the machine | A case is only a *ceiling* (§9 U10(a)). No processor or memory and it does not boot at all; no graphics card and it runs with no framebuffer (`gfx.size()` is `0, 0`); no drive and there is no `/disk`. `os.info().graphics` / `.drive` say which. |
| The world | A `bus` — redstone, inventories, a world sensor, chat, `net` to other computers. This is the part no other computer has. |

Two useful facts before anything else: **`os.info()`** tells a program what it is running on
(`tierName`, `colours`, `desktop`, and more), and **the screen is not the desktop** — a program can take the
whole screen or live in a window, and §3 and §4 are those two shapes.

---

## 2. A program is a directory

A program is a folder with two files:

```
myprogram/
  main.lua      -- the code; it runs top to bottom, and returning a string prints it to the shell
  program.txt   -- line 1: the name.  line 2: the description shown to the player.
```

Put it in **`src/main/resources/virtualminecraft/cds/<name>/`** to ship it *inside the mod* as a CD (read-only), or in
**`…/floppies/<name>/`** to ship it as a **floppy template** — a floppy that arrives with the program on it and is
writable after, for software the player is meant to change (`/vmc give floppy starter`; the emulator's `--fd starter`) — those are packaged
into the jar and every world has them, no copying. Put it in **`config/virtualminecraft/cds/<name>/`** to have it
in one world without touching the repo. **The config directory wins** when both exist, so a world can override a
shipped program — which also means a stale copy there will silently shadow the real one.

Two things the launcher gives you for free:

- **`PROGRAM_DIR`** — a global holding your own directory. Use it to find your own files.
  `fs.read(PROGRAM_DIR .. "/words.txt")` works whether you were run from a CD, a floppy or a disk;
  hardcoding `/cd0/` only works from a CD.
- **A window**, already created and shown, fullscreen. You get at it through the program object:

```lua
local me = kernel.top() and kernel.top().program
if not me then
  for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end
end
```

`me` is where you attach your input handlers, and `me.window` is your window.

- **`program`** — a global table about *you*: `program.path`, `program.dir` (the same as `PROGRAM_DIR`),
  `program.args`, and the three things that make you survive a freeze. See §2b.

**Use `kernel.top().program`, not a name scan.** Your window is on top when your program starts, so that is
your program. Scanning `kernel.programs` for `"main.lua"` looks equivalent and is not: **there are two CD
slots**, and if the other one is also running a `main.lua` the scan hands you *its* program object — you then
attach your handlers to somebody else's program and neither works. Every program here got this wrong until a
Browser and a Server were run on one machine at the same time and found it.

---

## 2b. Coming back after a freeze

A Computer that nobody is looking at is **frozen** — written to a file and let go of, sometimes for days of
world time. Everything about the machine comes back except the thing you cannot serialise: a running coroutine.
So the desktop's windows come back, and **a program comes back only if it says how** (ROADMAP §9 U12).

Saying how is three lines:

```lua
program.version = 1                                     -- your own number; bump it when the shape changes
program.save = function() return { level = level, score = score } end

local kept = program.restore()                          -- nil on a cold start, or if the version has moved on
if kept then level, score = kept.level, kept.score else newgame() end
```

- `program.save` is called **when the machine freezes**, and whatever it returns is stored with your path.
  On the way back the kernel re-runs your `main.lua` from the top and `program.restore()` hands the table over.
- It is **data, not a call stack.** You are started fresh and told what you knew, not resumed mid-instruction —
  which is exactly why it still works after the mod is updated, where a real savestate would not.
- **`program.version` is yours.** If a saved table's version is not the one you declare, `program.restore()`
  returns nil and says so in the log rather than unpacking fields that have moved. Call `program.restore()`
  *after* setting the version.
- Whatever you return has to be **JSON** — tables, numbers, strings, booleans. A function or a userdata in
  there and the save is refused (with a line in the log) rather than silently half-written.
- There is a **32 KB limit** per program. Over it, your state is dropped and logged; the machine's whole saved
  state is capped at 256 KB, and one greedy program must not cost the desktop its windows.
- **No `program.save`, no restore.** A program that does not opt in simply is not running after a thaw, exactly
  as before — and so is one whose `save` throws or returns something unsaveable. Opting in is what brings you
  back.
- A **server** usually needs none of this: its pages are on disk, and `/disk/autostart.lua` starting it at boot
  is the honest answer. The two compose — a program restored from a freeze is not started a second time by
  autostart.

`cds/2048/main.lua` is the shipped example: a board, a score, four lines.

---

## 3. Shape one: a fullscreen program

The simplest thing that works. You own the screen, you draw, you loop.

```lua
-- hello/main.lua
local w, h = gfx.size()
local KEY = win.KEY
local me
for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end

local quit = false
if me then
  me.key = function(code, down)
    if down and (code == KEY.q or code == KEY.esc) then quit = true end
  end
end

gfx.clear(0)                                   -- 0 is black
gfx.text(10, 10, "hello, world", 15, nil, 1)   -- x, y, string, fg, bg (nil = transparent), font
gfx.present()

while not quit do gfx.present() end
return "hello: done"                            -- printed by the shell when the program exits
```

**`gfx.present()` is the frame boundary.** Nothing you draw appears until you call it, and — this is the part
that catches people — `present()` is also what yields to the rest of the machine. A loop without it hangs the
computer. A loop that *only* calls it is a perfectly good idle loop.

### Drawing

All colours are palette indices. All coordinates are pixels, origin top-left.

```lua
gfx.size()                          -- -> w, h
gfx.clear(c)
gfx.pixel(x, y, c)      gfx.get(x, y)          -- read a pixel back
gfx.fill(x, y, w, h, c)                        -- filled rectangle
gfx.rect(x, y, w, h, c)                        -- outline
gfx.line(x0, y0, x1, y1, c)
gfx.disc(cx, cy, r, c)  gfx.circle(cx, cy, r, c)   -- filled / outline
gfx.text(x, y, s, fg, bg, font)                -- font 1 = 6x8, font 2 = 8x16
gfx.fontw(font)  gfx.fonth(font)               -- 6,8 and 8,16
gfx.clip(x, y, w, h)                           -- confine drawing to a rectangle
gfx.copy(sx, sy, w, h, dx, dy)                 -- blit within the screen; the cheap way to scroll
gfx.palette(i)  gfx.palette(i, rgb)            -- read / redefine a palette entry
gfx.present()
```

**The desktop's font is the player's choice** (Settings > Desktop > Text, since 2026-08-28): `win.theme.font`, `fw`
and `fh` are whatever they chose, and every `win` widget follows. A window app should lay out from `win.theme`, never
from 6 and 8. A fullscreen program picks its own fonts and is unaffected — if it draws text the player must read,
prefer font 2 (8×16) where the screen has room, and read `kernel.textSize` if you want to honour the preference.

There is **no polygon fill**. A triangle is a stack of `gfx.line`s that get shorter — see `pip()` in
`src/main/resources/virtualminecraft/cds/solitaire/main.lua`, which draws the four card suits that way.

### Input

Attach functions to `me`. All three are optional.

```lua
me.pointer = function(x, y, buttons, pressed, released)
  -- x,y in pixels. buttons is a bitmask: 1 = left, 2 = right, 4 = middle.
  -- `pressed` is the LEFT button's press edge, handed to you. The right button's edge is yours to
  -- track: keep the previous `buttons` and compare (`b >= 2 and prev < 2`).
end

me.key = function(code, down, mods)
  -- `code` is an XT scancode. win.KEY names the common ones.
end

me.char = function(cp)
  -- A typed character as a codepoint. This is what you want for text and for any letter or symbol
  -- that win.KEY does not name -- see the gotcha in §9.
end

me.onbus = function(ev)
  -- Events from the world and the machine: redstone_changed, viewers, power, shell, disk events...
end
```

To get a mouse cursor in a fullscreen program you must ask for one:

```lua
me.window.cursor = true
kernel.showCursor(true)
```

---

## 4. Shape two: a window application

This is the one that is not obvious, because your program *starts* fullscreen. You turn that window into a
normal one — you do not create a second window.

```lua
-- winhello/main.lua  (this exact program was run to check this section)
local me
for _, p in ipairs(kernel.programs) do if p.name == "main.lua" then me = p end end

local wd = me.window
wd.fullscreen = false                       -- stop owning the screen
wd.title = "Hello"
wd.x, wd.y, wd.w, wd.h = 16, 16, 170, 92
wd:relayout()

local count = 0
local label = wd:add(win.Label{ x = 6, y = 6, w = 150, text = "clicked 0 times" })
wd:add(win.Button{ x = 6, y = 22, w = 70, h = 16, text = "Press me",
  onclick = function()
    count = count + 1
    label.text = "clicked " .. count .. " times"
    wd:invalidate()                          -- "redraw me" -- widgets do not repaint themselves
  end })
wd:add(win.TextField{ x = 6, y = 44, w = 150, h = 14, text = "type here" })
wd:invalidate()

while not wd.closed do os.sleep(100) end     -- the window is the program's lifetime now
return "window demo: " .. count .. " clicks"
```

You get the title bar, the close box, dragging, the resize grip and a taskbar entry for free, and the desktop
keeps running behind you. There is a runnable copy of this at **`/rom/examples/window.lua`** on every machine.

### Widgets

`wd:add(...)` returns the widget, so keep the ones you need to change. Every widget takes `x, y, w, h`
relative to the window's client area.

| Widget | The fields that matter |
|---|---|
| `win.Label` | `text`, `fg` |
| `win.Button` | `text`, `onclick` |
| `win.Toggle` | `text`, `value`, `onchange` |
| `win.List` | `items`, `selected`, `onselect(i, item, self)`, `onactivate(i, item, self)` |
| `win.TextField` | `text`, `placeholder`, `onchange(text, self)`, `onenter` |
| `win.TextArea` | `settext(s)`, `gettext()`, `append(s, fg)`, `onchange(self)`, `wrap` |

A **read-only** area is one of two different things, and since session 19 the widget knows which. An area you
*append* to is a console and follows its last line — the Terminal. An area you *read* does not: its arrow and Page
keys scroll the view where they should. (Before that the console behaviour was inferred from `readonly`, so every
navigation key in a read-only area ran through `scrollToCursor` and snapped the view to the end of the document.)
A reader wants more than that anyway — the Reader replaces `area.key` on its own instance, because scrolling a
page is not the same as moving a caret and Home/End should move the *view*.

`win.TextArea` wraps at spaces when `wrap = true`, and since session 18 it wraps in an **editable** area too:
the cursor still lives at a position in the text and the widget maps it to a row on screen, so up and down move
by the row you can see rather than by the paragraph. Edit's Wrap button is one line — `area.wrap = not
area.wrap` — and on a 1×1 monitor, where a window is 28 characters wide, it is the difference between a text
file you can read and one that runs off the edge.

`win.List` also answers `list:rowAt(localY)`, which is what a right-click asks before it opens a menu about
the row you pointed at.

**A widget the toolkit does not have.** The base `Widget` class is private, but you do not need it: make a
`win.Label` and replace its methods on that one instance.

```lua
local grid = wd:add(win.Label{})
grid.layout = function(self, cw, ch) self.x, self.y, self.w, self.h = 0, 0, cw, ch - 20 end
function grid:draw(ox, oy) gfx.fill(ox + self.x, oy + self.y, self.w, self.h, win.theme.field) end
function grid:press(lx, ly, button) end   -- lx/ly are relative to the widget
function grid:key(code, down, mods) return true end
function grid:wheel(dy) return true end
```

The window focuses a widget on click only if it overrides `press`, `key`, `char` or `release`, so defining any of
them is also what makes it take the keyboard. `drag` is delivered to whatever was pressed. Sheet's grid is exactly
this and nothing more — a canvas with five methods on it.

Useful window fields and calls: `wd:invalidate()` (redraw), `wd:relayout()`, `wd.closed`, `wd.onbus`,
`wd.onclose`, `wd.minW`/`wd.minH`, `wd.resizable`.

Dialogs and the desktop:

```lua
win.prompt("Title", "Question", "default", function(text) ... end)   -- text entry; text is nil if cancelled
win.ask("Title", "Question", { "Yes", "No" }, function(answer) ... end)
win.info("Title", { "line one", "line two" })  -- several lines and an OK button (win.ask's message is ONE line)
kernel.notify("something happened", 4)        -- a toast, for n seconds
kernel.say("a line to the shell")
kernel.w, kernel.h, kernel.taskbarH, kernel.iconW     -- desktop geometry, for sizing a window
kernel.spawn(name, fn, wd)                    -- a coroutine tied to a window, for background work
os.sleep(ms)
```

**Menus and the right button (session 18, ROADMAP §9 U6).** `win.menu(x, y, items)` puts up a popup and
returns its window. It flips and clamps itself to stay on the desktop, so passing the desktop's bottom edge
makes a menu that opens *upward* — which is how the start menu sits on the taskbar. An item is
`{ text = "Open", onclick = f }`, a rule (`{ sep = true }`), or a submenu (`{ text = "More", submenu = items }`,
or a function returning items so a long list is only built when it is opened); `disabled` greys one out and
`check` marks it. Choosing an item closes the whole chain *before* the handler runs, so a handler is free to
open a window, a dialog or another menu.

To give your own window a context menu, set `wd.onrightpress = function(self, lx, ly, px, py) ... end` and
return `true` if you handled it. The local coordinates say *what* was pointed at; the screen ones are where the
menu goes. Nothing handles the right button by default, so a window without the hook behaves exactly as before.
`src/main/resources/virtualminecraft/rom/apps/files.lua` has three of these — one for a file, one for the empty
space below the files, one for a mount.

`src/main/resources/virtualminecraft/rom/apps/redstone.lua` is a complete, small, real example of all of
this — it is worth reading once before writing your first window app.

---

## 5. Sound

```lua
snd.beep(freq, seconds, channel)
snd.channel(ch, wave, freq, volume, attack, decay, sustain, release)   -- snd.SQUARE, snd.NOISE, ...
snd.stop(ch)
snd.playsong(song)                            -- see /rom/examples/song.lua
```

**Channels 1–4 are the synth**, and noise is a *wave* on one of them (`snd.channel(1, snd.NOISE, ...)`); 5–6 play
samples, and `snd.channel` on them is a **program error** (`synth channel must be 1..4`). **The emulator does not
check this** — Pinball ran clean in every scripted run and crashed on its first drain in the real game. A short `snd.beep` on every click is most of what makes a program feel
like a real machine rather than a picture of one.

---

## 6. Files

```lua
fs.read(path)          fs.write(path, s)      fs.append(path, s)
fs.exists(path)        fs.remove(path)        fs.rename(a, b)
fs.list(dir)           fs.isdir(path)         fs.mkdir(path)
fs.stat(path)          fs.mounts()            fs.copy(src, dst)     -- a file, or a whole directory tree
fs.join(a, b)          fs.basename(p)         fs.dirname(p)
fs.validname(name)     fs.NAME_HELP
json.encode(t)         json.decode(s)
```

**Names are `[A-Za-z0-9._-]`, up to 64 — no spaces.** Check with `fs.validname` before you build a path, so a
dialog can say what the rule is instead of the filesystem's own error arriving as a toast. It is also why a
copy made by the file manager is `notes-copy.txt` and not `notes copy.txt`.

**`x and nil or y` does not work, and this project has paid for it three times.** `and nil` collapses, so the
`or` branch always wins and the expression can never produce a nil. It has silently stored the string `"nil"` in a
status line, and made every blank cell in Sheet's grid draw the word `nil`. Write the `if`.

```lua
local v = memo[key]
if v == BLANK then return nil end   -- NOT `return v == BLANK and nil or v`
return v
```

**Session state vs preferences.** The desktop's saved windows live in the *session blob*
(`state/kernel.dat`, written by syscall 2 through `kernel.save()`), and **a reboot deletes that on purpose** so a
machine comes up clean — right for window positions, wrong for anything the player chose. Preferences belong on
the machine's own disk instead: the desktop keeps its wallpaper and accent in `/disk/desktop.json`
(`kernel.savePrefs()` / `kernel.loadPrefs()`). If you write a program with settings worth keeping, put them on
`/disk`, not in the blob.

**Where to put things.** `/rom` is read-only. `/cd0` is the CD and is read-only too — so a program that
saves anything saves it to `/disk`, which belongs to the machine. That split matters: a CD is shared between
every computer that plays it, so the CD holds the *program* and `/disk` holds *this machine's* state. The
Keypad does exactly this — the program is on the CD, the PIN is on the disk.

---

## 7. Touching the world

The bus is what makes this machine worth writing for.

```lua
bus.list()                                    -- what components this computer actually has
bus.call("redstone", "setOutput", "right", 15)
bus.call("redstone", "getInputs")             -- by ABSOLUTE side (north/south/east/west/up/down)
bus.call("redstone", "getFacing")             -- so you can map front/back/left/right onto those
bus.call("world", "getPlayers", range)
bus.call("world", "getTime")
bus.call("inventory", "list") / "pushItems" / "pullItems"
```

**Always `pcall` a bus call.** A machine with no redstone component is a completely normal machine — the
emulator is one — and a program that crashes on a missing component is a program that cannot be tested and
will not survive being moved to a different computer:

```lua
local ok, err = pcall(bus.call, "redstone", "setOutput", side, level)
if not ok then  -- say so in the UI, do not die
end
```

Sides are `front, back, left, right, top, bottom` when you *set*, but `getInputs`/`getOutputs` answer by
absolute compass direction. `redstone.lua` shows the mapping.

---

## 8. Testing without launching Minecraft

This is the good part: you almost never need the game.

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
./gradlew computerEmulator --args="--size 256x256 --fresh --dir run/emulator-mine \
    --cd src/main/resources/virtualminecraft/cds/myprogram --script steps.txt"
```

- `--fresh` forgets the saved desktop, `--dir` keeps each program's disk separate,
  `--tier N` / `--parts ram,cpu,gfx,drive` pick the case, `--mem MB` the memory,
  `--pos X,Y` opens the window where you want it on a multi-monitor desk.
- `--script` drives it with no human present. The steps:

```
wait 1500                     # milliseconds
shell run /cd0/main.lua       # a line typed at the shell
click 60 60                   # move there and click
rclick 60 60                  # a RIGHT click (the context menus)
move 60 60
key 0x1c                      # press AND release together -- fine for a keystroke
key 0xc8,down                 # HOLD a key down (thrust, a held direction)
key 0xc8,up                   # let it go again
type hello                    # characters, as if typed
exec gfx.clear(0)             # run Lua inside the machine
viewers 0                     # nobody is watching (for testing the screen park)
park
shot run/shots/out.png        # a PNG you can look at
```

`--loopback` makes the pretend `net` deliver `net.send` straight back to the same machine as a `net_message`
event. Without it a message vanishes and a request/response protocol cannot be tested outside Minecraft; with it,
a server and a browser run on **one** machine and talk to each other, which is how the web programs were built.

Put screenshots under **`screenshots/`** (gitignored; [name] looks through it to follow progress and asked for every shot to land there, 2026-08-28). `print` from inside the machine comes out on
stdout.

---

## 9. Gotchas — every one of these has cost somebody an hour

- **There is no `//` operator.** The guest Lua does not take Lua 5.3's floor division: `a // b` is a syntax
  error that only shows up as `ROM kernel failed to load: unexpected symbol near '/'` when the file is loaded.
  Write `math.floor(a / b)`, which is what the rest of the ROM does.

1. **Cobalt has no `//` operator.** Use `math.floor(a / b)`, and hoist repeated ones into locals.
2. **Typed characters arrive several to a frame.** If you keep a single "next input" variable, a burst of
   typing overwrites it and you lose all but the last character. **Queue input and drain the queue each
   frame.** This bug ate a four-digit PIN and turned it into one digit.
3. **`win.KEY` only names the keys the ROM's own apps needed** (`s q c v a n o w d p r x z`, arrows,
   enter, space, escape…). For any other letter, use `me.char` rather than hunting for a scancode.
   If you truly need a scancode: F is `0x21`, digits 1–9 are `0x02`–`0x0a` and 0 is `0x0b`.
4. **The 16 base colours contain only two greys**, which is not enough to draw a raised button. The colour
   cube has more: index `16 + 43k` is a grey, so **102 / 145 / 188** make a shadow, a face and a lit edge.
5. **A fullscreen program must ask for the cursor** (`me.window.cursor = true` then
   `kernel.showCursor(true)`), and must track the **right** button's press edge itself.
6. **An animating program should listen for `viewers`** and stop when nobody is watching, or it burns its
   share of the CPU and holds the framebuffer open forever. `sentry/main.lua` does this.
7. **`gfx.present()` is also the yield.** No `present()` in your loop means a hung machine.
8. **Only redefine palette entries you have.** `os.info().colours` is the cap; writes past it are ignored.
9. **A bare `key X` in a test script presses *and releases* in the same instant.** A game that reads a held
   key (thrust, a held direction) sees nothing at all, and it looks exactly like a broken game. Use
   `key X,down` … `key X,up` to hold. This cost a debugging pass on Drift where the game was fine.
10. **`os.clock()` is your only timer.** It is monotonic seconds since *this machine* booted, and it is what
   you measure with. `os.time()` is the world's tick counter and `os.epoch()` is the world's date — neither is
   a stopwatch, and both jump when someone runs `/time set`. `os.realtime()` is the host's wall clock, for the
   rare case where you need real elapsed time across a freeze.
11. **In VR, Space and Shift reach your game only from the floating keyboard, never from the controller
   bindings** (session 27, refined the same day when Drift 3D could not shoot). A stick-click *is* a real
   Space keypress by the time the client sees it, so jump/sneak-bound presses are held until a character
   confirms they were poked on the keyboard — a poked space bar delivers `win.KEY.space` down (and held, if
   held), a stick-click delivers nothing. Practical upshot: Space works in games in VR; Shift mostly does not
   (Vivecraft's keyboard shift is its own overlay action and sends no real Shift press), so prefer another
   key for anything a VR player must hold.
12. **The world has a real date (ROADMAP §9 U10(b)).** World tick 0 is **1970-01-01 06:00**, a Minecraft day is
   a day, so a world that has been played 400 days is in 1971. `os.epoch()` is the world's milliseconds since
   1970; `os.date(fmt [, epochMs])` formats it (`%Y %y %m %d %e %H %I %M %S %p %j %A %a %B %b %F %T %D`, or
   `"*t"` for a table); `os.datetable([epochMs])` is that table directly (plus `worldday`); `os.daysfromdate(y,
   m, d)` and `os.monthdays(y, m)` are there so you can draw a calendar. `os.date()` with **no** arguments is
   still the old pair — `"HH:MM"` and the Minecraft day number — because the taskbar clock is built on it.

---

## 10. Where to look next

| I want to… | Read |
|---|---|
| see a small fullscreen game | `src/main/resources/virtualminecraft/cds/lightsout/main.lua` |
| see a pointer-first game with a real UI | `src/main/resources/virtualminecraft/cds/mines/main.lua` |
| see the biggest one | `src/main/resources/virtualminecraft/cds/solitaire/main.lua` |
| see a program that touches the world | `src/main/resources/virtualminecraft/cds/sentry/main.lua`, `src/main/resources/virtualminecraft/cds/keypad/main.lua` |
| see a real window app | `rom/apps/redstone.lua`, `/rom/examples/window.lua` |
| see the widget toolkit itself | `rom/lib/win.lua` |
| see what the kernel offers | `rom/kernel.lua` |
| know how the mod does all this | `HOWITWORKS.md`, `ARCHITECTURE.md` |
