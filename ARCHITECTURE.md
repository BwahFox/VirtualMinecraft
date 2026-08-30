# Architecture — how the mod actually works

*(State: [HANDOFF.md](HANDOFF.md). This file explains the code so nobody reverse-engineers it twice.
Plain-language version for [name]: [HOWITWORKS.md](HOWITWORKS.md).)*

## The one picture

```
guest OS → QEMU (VNC unix socket) → RfbClient (VNC thread)  ─ dirty rects ─┐
                                    ↑ key/pointer (RFB)                      │  VmInstance.tick() on the
QMP unix socket ← QmpClient (power/reset)                                    │  SERVER thread, 20/s:
                                                                             ▼
   viewers who heart-beat  ←  ScreenInfo / ScreenRect (zlib RGB bands) / Audio (μ-law)  payloads
   singleplayer host       ←  VirtualMinecraft.localBridge (same JVM, raw bytes, no network)
                                                                             │
   guest /dev/virtio-ports/vmc.bus ⇄ chardev socket ⇄ BusLink threads ⇄ VmBus.tick() (server thread) ⇄ Components
                                                                             │
   CLIENT thread: ScreenTextures (RGBA8 GPU texture per VM, sub-rect uploads) → MonitorRenderer quad (or text grid)
                  VmAudio (OpenAL streaming source at the computer block)
                  VmScreen / InputSender → VmInputPayload → VmInstance.input() → RfbClient.sendKey/Pointer
```

## Sides and threads (the invariants)

- `src/main` runs on **both** sides; `src/client` only on a player's PC (Loom split source sets — a dedicated
  server must never load client classes). Shared code reaches client code only through the two hook
  interfaces in `VirtualMinecraft`: `clientHooks` (open screens) and `localBridge` (in-process frames/audio),
  both installed by `VirtualMinecraftClient` and no-ops on a dedicated server.
- **VNC thread** (`vmc-vm-<name>`, one per VM): owns the RFB socket, decodes into `RfbClient.fb` under
  `framebufferLock`, and only *records* dirty rects / audio under `dirtyLock` / `audioLock`. It never touches
  Minecraft objects except through those locks and `setStatus` (volatile fields).
- **Server thread**: `ScreenViewers.tick()` (viewer pruning) then `VmManager.tick()` → each `VmInstance.tick()`:
  exit-code handling, status sync to the block entity, `flushFrames()` and `flushAudio()`. All packet sending
  happens here.
- **Client/render thread**: everything GPU (texture create/upload/release) and OpenAL. Payload handlers run
  here already; the local bridge and the disconnect hook marshal with `Minecraft.execute`. Releasing a
  texture from the network thread throws `Rendersystem called from wrong thread` (burned once).
- The **integrated server** in singleplayer is a separate thread in the same JVM; the local bridge exists so
  frames for the host player skip compression and Netty entirely.

## VM lifecycle (`vm/`)

- `VmManager` — one per `MinecraftServer` (weak map), keyed by VM UUID. `start/shutdown/forceStop/reset/remove`,
  `tick`, `shutdownAll` on `SERVER_STOPPING`. VM directory = `<world>/virtualminecraft/<uuid>/`.
- `VmInstance` states (`VmStatus`): STOPPED → STARTING (disk created with `qemu-img` if missing, QEMU spawned,
  VNC socket polled for ≤ 60 s) → RUNNING (RFB connected) → STOPPED/ERROR/SUSPENDED. **The exit code decides the
  final status in `tick()`**, not the VNC disconnect: a guest `poweroff` closes VNC a moment before QEMU exits.
  The exit check only fires while RUNNING/STARTING (an ERROR instance must not re-read the log every tick).
- `QemuLauncher.buildCommand`: q35, KVM if `/dev/kvm` is writable else TCG, `-cpu host|max`, block devices from
  the attachment list (see "Disks as items": SATA `ide-hd`/`ide-cd`, ISA floppy, per-device `bootindex`), `-boot menu=on`, `-vga <cfg>`,
  `-display none`, `-vnc unix:…,audiodev=snd0`, `-audiodev none,id=snd0` + `ich9-intel-hda`/`hda-output`,
  `-qmp unix:…`, `usb-tablet` (absolute pointer), `-nic user,model=<cfg>`, `-rtc base=localtime`, extra args.
  UEFI = `-bios <ovmfPath>`. Sockets live in `$XDG_RUNTIME_DIR/virtualminecraft/<12-char id>/` because unix
  socket paths are capped at ~104 bytes and Prism world paths exceed that; Windows falls back to loopback TCP.
- `QmpClient` — one connection per command: greeting, `qmp_capabilities`, then the command (`system_powerdown`,
  `system_reset`, `quit`) or an HMP line through `human-monitor-command` (`hmp(ep, "savevm vmc")`). HMP reports
  failure as *text in the return value*, not a QMP error — `savevm` prints nothing on success.
- **Suspend / resume** (`VmInstance.suspend`, `finishSuspend`, `launch`): `savevm vmc` writes RAM + disk state as an
  internal snapshot *inside* `disk.qcow2` (the VM is paused for the ~1–3 s it takes), then a marker file
  `<vm-dir>/suspended` is written and QEMU is told to `quit`. `tick()` turns that exit into **SUSPENDED** (not
  STOPPED) because the `suspending` flag is set; the RFB/bus threads ignore the disconnect for the same reason.
  The next start sees the marker and launches with `-loadvm vmc`; if QEMU exits before the display comes up
  (RAM/CPU/device change, QEMU upgrade), the snapshot is discarded (`qemu-img snapshot -d`) and it boots cold.
  The marker is deleted once the resumed VM is RUNNING — the snapshot left in the qcow2 is then stale and is
  simply overwritten by the next `savevm`. Triggers: `VmInstance.tickUnload` (the owning chunk has failed
  `ServerLevel.shouldTickBlocksAt(chunkPos)` — "is it block-ticking" — for 200 consecutive ticks; that flips
  seconds after the last player leaves, whereas `hasChunk` / `hasChunkAt` / `isLoaded` only ask whether a
  holder exists at FULL level and stay true for many minutes; and **do not hook `BlockEntity.setRemoved`**:
  in 26.2 the demoted chunk stays in memory, with its block entities, and the server-side `setRemoved` never
  came in testing), `VmManager.shutdownAll` on
  `SERVER_STOPPING` (waits ≤ 120 s per VM, on the server thread, then persists SUSPENDED into the block entity
  before the world saves), `/vmc suspend`. Per-VM `config.suspend` (default on) gates the automatic triggers
  (`ComputerBlockEntity.setConfig` pushes flag changes into the running instance). Resume: the first server
  tick of a freshly loaded block entity starts the VM if the marker exists; a *re-promoted* chunk keeps its
  block entity (no first tick), so an unload-suspended instance also resumes itself from `tickUnload` once
  the chunk ticks again. **The marker file, not block-entity NBT, is the source of truth**: chunks are
  saved before their block entities go away, so nothing written to the BE at unload time would persist.
  Force-stop on a suspended computer discards the saved state. Redstone outputs are *not* cleared on suspend —
  the restored guest still believes they are set. **A block that vanished from a still-ticking chunk**
  (`VmInstance.tickOrphan`, session 16, the Computer's rule a tier up): `/setblock`, `/fill`, `/clone` and the world
  editors built on them place with flag 256, so `preRemoveSideEffects` never runs and nothing stops the VM — before
  this it kept its QEMU process, its 2 GB of guest RAM and its bus for the rest of the server's life, with no block
  to show or stop it. 20 ticks of "the chunk block-ticks and there is no block entity at `pos`" → suspend (or stop,
  if `config.suspend` is off), then `VmManager.forget` drops the instance once the status has settled, so a block put
  back with the same id starts a fresh one from the snapshot and a *new* Command Computer at that position is not
  trampled by the old instance's status (`blockEntity()` and `syncStatusToBlock` return early once orphaned). A
  `/clone` that *moves* a computer is not this case: the copy carries the block entity's NBT, so `VmManager.attach`
  re-points the instance at its new position on the copy's first tick, well inside the 20 ticks — verified, the guest
  keeps running. The disk image stays where it is, exactly as it does after a break with `deleteDiskOnBreak` off. Host-side bus subscriptions do not survive (new `VmBus`);
  the guest must re-subscribe.
- **Redstone wake / sleep** (`ComputerBlockEntity.sampleInputs`, and `LuaComputerBlockEntity.sampleInputs` with
  the same rule for the Computer tier — see the Computer's own section): `config.wakeThreshold` 1–15 starts the VM
  when the max input over the six faces *rises* to the threshold (edge-triggered, like OC's wake threshold,
  so a level that is already high when the chunk loads does not restart a computer that was shut down on
  purpose); `config.redstoneSleep` sends ACPI power-down when it falls back below. Our own outputs echo back
  through conductors (see limitations), so a guest that drives a lamp on a face can trip its own threshold.
- Global settings `config/VmcConfig` (`config/virtualminecraft.json`): binaries, OVMF path, ISO dir,
  `enableKvm`, `maxRunningVms`, `streamFps`, `requireOp`, `deleteDiskOnBreak`, `extraArgs`.

## RFB client (`rfb/RfbClient.java`)

Dependency-free VNC client tuned for QEMU. Negotiates 32 bpp little-endian, R in the low byte, so raw
pixels arrive as `R,G,B,X` and copy straight into the RGBA framebuffer (alpha forced to 0xFF). Encodings:
ZRLE (preferred: 64×64 tiles, palette/RLE, one persistent `Inflater`), Hextile, CopyRect, Raw; pseudo:
DesktopSize (reallocates `fb`), LastRect, **QEMU Audio (-259, empty rect = ack)**. Audio: client message
255/1 sets S16 mono 22050 Hz then enables; server message 255/1 op 2 carries PCM. Input: KeyEvent (keysym)
and PointerEvent (button mask, abs x/y). Messages we skip: colour map, cut text (incl. negative-length
extended clipboard).

## Screens (`screen/`) — the seam between monitors and whatever draws on them

*(Milestone 5 A1/A2, 2026-08-25. Built so the in-JVM Computer of milestone 7 can drive a monitor with no monitor
or protocol changes; the VM is just the first implementation.)*

- `ScreenSource`: a block entity that owns a screen — `screenId()` (the UUID every payload is keyed by),
  `screenName()`, `registerMonitor(pos)`, `monitorTouched(...)`. `ComputerBlockEntity` implements it with
  `vmId`; a monitor links to *any* implementation.
- `ScreenViewers` (one per server, like `VmManager`): who is watching which screen UUID. Fed by the
  `ViewerPayload` heartbeat, pruned after 60 ticks of silence, ticked from `END_SERVER_TICK`. **Every
  per-watcher cost asks here** — `VmInstance.flushFrames`/`flushAudio`, the owner-offline "is anyone watching"
  test, and `MonitorBlockEntity.serverTick`'s text rows — so a screen nobody looks at sends nothing. Each
  `Viewer` carries `needFull` (consumed by the framebuffer path) and a requested `lod` (A3).
- `ScreenSources`: screen UUID → (dimension, pos) hint, noted by a source on its first tick after load and
  re-verified on lookup (`setRemoved` is unreliable server-side). Used by the heartbeat receiver to enforce
  `VmcConfig.viewDistance` — which, before this, was a config field nothing read — and later to route input
  to non-VM sources.
- Text mode is viewer-gated the same way as the framebuffer: a viewer the monitor has not synced yet gets the
  whole grid, then dirty rows; `textSynced` is cleared when nobody watches so returning viewers resync. The
  block-entity update tag still carries the grid, so a chunk load also delivers it.
- Heartbeats are accepted for any screen id, running VM or not: a stopped computer's BIOS page has viewers too.

## Multi-block monitors (`block/MonitorMultiblock`, milestone 5 A5)

- Adjacent monitors in one wall plane with the same facing **and the same source** form a rectangle. Each block
  entity stores only its index and the rectangle's size (`mbX, mbY, mbW, mbH`; NBT `mb`; singles are `0,0,1,1`);
  there is no group object. `MonitorMultiblock.rebuildAround(level, pos, state)` flood-fills the connected set in
  the plane and carves it into rectangles in one deterministic pass (bottom-left-most free cell, grow right while
  the row runs, grow up while the whole next row is free), so an L-shape splits the same way whichever block
  triggered it. Called from `onPlace`, `affectNeighborsAfterRemoval` and `setSourcePos` (a relink can split or join).
- The **origin** (bottom-left as a viewer sees it; x runs to screen-right = `facing.getCounterClockWise()`, y up)
  owns everything: it is the one `screen` bus component, the BIOS page draws on origins only, it holds the text
  grid, and its renderer draws the whole rectangle — a black "glass" quad over the bezel-inset area (hides the
  interior blocks' bezel textures) and the picture aspect-fitted inside it (`MonitorBlock.pictureBox`, shared with
  the hit mapping). Non-origin blocks render nothing. Because the origin draws outside its own block,
  `MonitorRenderer.shouldRenderOffScreen()` is true — 26.2 has no per-entity render box — so monitors within
  `getViewDistance()` (64) submit even when off-screen; one quad each, so it is cheap, but text mode submits
  per cell.
- Hits go through the rectangle: `wallHit` turns a hit on any block into wall-space coordinates (blocks to the
  right and up from the rectangle's bottom-left corner) using the hit block's index; `hitToCell`/`hitToPixel`
  then apply the same `pictureBox` fit. `MonitorBlock.useWithoutItem` and the client hover both resolve the
  origin first (`MonitorBlockEntity.origin()`) and map through the hit block.
- LOD (A3) reports distance to the rectangle's centre divided by `mbW`, so a wide wall stays sharp from further.
- Not done: the guest's resolution does not follow the monitor (a VM keeps whatever the guest set; the M7
  Computer's will be per-monitor up to 1024×768).

## Streaming protocol (`net/`)

| payload | dir | content |
|---|---|---|
| `ViewerPayload(vm, needFullFrame, lod)` | C→S | heartbeat every 10 ticks while a monitor for `vm` was rendered or its screen is open; `lod` 0–3 is the level the client wants |
| `ScreenInfoPayload(vm, w, h, running, lod)` | S→C | on subscribe, resize and level change; `w`/`h` are the **full** size, the texture is `scaled(w, lod) × scaled(h, lod)` (halved per level, rounded up) |
| `ScreenRectPayload(vm, x, y, w, h, format, data)` | S→C | one band in the viewer's level's coordinates; `format` 0 = zlib RGB8 (the only one; indexed colour reserved); raw ≤ 384 KB so it fits the 1 MB limit even uncompressible |
| `AudioPayload(vm, ulaw)` | S→C | per tick, μ-law mono 22050 Hz (~1.1 KB/tick) |
| `VmInputPayload(vm, events[])` | C→S | key (keysym, down) / pointer (mask, x, y); ≤ 256 events; C→S limit is 32 KB |
| `VmControlPayload(pos, action, config)` | C→S | SAVE/START/SHUTDOWN/FORCE_STOP/RESET; server checks distance ≤ 12 and `requireOp` |
| `ScreenTextPayload(pos, textMode, cols, rows, rows[])` | S→C | monitor text mode: full grid to a new viewer, then dirty rows, to the screen's viewers only (see "Screens") |

Viewers expire after 60 ticks without a heartbeat. New viewers get `ScreenInfo` + a full frame. Dirty rects
are coalesced into a bounding box past 32 per tick. `streamFps` (default 20) sets the flush interval.

**Level of detail (milestone 5 A3).** The client chooses: `MonitorRenderer.extractRenderState` reports the
camera distance per block of monitor width to `ScreenTextures.touch`, the closest observation since the last
heartbeat picks the level with hysteresis (coarser past 8/16/32 blocks per monitor-block, finer below 6/12/24, one
notch per heartbeat), the full-screen view reports distance 0. The server (`VmInstance.flushFrames`) keeps one
full-resolution framebuffer and box-filters per level on the way out (`copyBands(…, lod)`: the source rect is
widened outward to a multiple of `2^lod`, each output pixel averages its block, edges clamp), sharing the copy and
the deflate between viewers at the same level. A level change re-`ScreenInfo`s and forces a full frame, which
recreates the client texture at the new size; pointer coordinates are always mapped into the **full** size
(`ScreenTextures.fullSize`), so a click lands on the right guest pixel at any level. The local bridge honours the
level too — it saves the host player's GPU upload and texture memory, which is the constraint that matters at
many screens. Far limit: the heartbeat receiver drops players past `viewDistance`.

## Client rendering (`client/render/`)

- `ScreenTexture extends AbstractTexture`: `GpuTexture` RGBA8 with `USAGE_COPY_DST|TEXTURE_BINDING`, clamp
  sampler (linear min / nearest mag), registered in `TextureManager` under `virtualminecraft:vm/<uuid>`;
  `upload()` = `CommandEncoder.writeToTexture(texture, directByteBuffer, 0, 0, x, y, w, h)` — backend-agnostic
  (works on GL and the experimental Vulkan backend).
- `ScreenTextures`: registry + heartbeat + expiry (release after 60 s untouched, on disconnect).
- `MonitorRenderer` (`BlockEntityRenderer<MonitorBlockEntity, MonitorRenderState>`): one quad on the north
  face rotated by `180 - facing.toYRot()`, `RenderTypes.text(textureId)` at `LightCoordsUtil.FULL_BRIGHT`
  (unlit, blends, Iris-compatible), aspect-fitted inside `BEZEL = 1/16`. Screen-left is +x on the north face.
  `extractRenderState` is where the viewer heartbeat (`touch`) and audio position are refreshed.
- GUI: `VmScreen` blits the same texture with `GuiGraphicsExtractor.blit(id, x0,y0,x1,y1, u0,u1,v0,v1)`.

## QEMU D-Bus display link (`dbus/`, `vm/QemuDisplayLink`, milestone 5 B)

*(Linux hosts only; the VM runs on VNC alone everywhere else, and on Linux too if attaching fails — one WARN line.)*

- **Why:** VNC carries X11 keysyms, so QEMU has to guess a keycode from a symbol using the *host's* layout,
  which is why a non-US guest layout and raw-scancode games misbehave. QEMU's D-Bus display takes **key
  numbers** — XT set-1 scancodes, extended keys folded into bit 7 — i.e. physical keys, and the guest's own
  layout does the rest. (Not `QKeyCode` enum values, despite the argument being called `keycode`: sending those
  types Caps-Lock-flavoured garbage, which is how this was found.) The same connection carries the
  shared-memory scanout and audio in later steps (PERFORMANCE.md T2).
- **How the connection exists:** QEMU is launched with `-display dbus,p2p=on` alongside `-vnc`; it listens
  nowhere for it. `QemuDisplayLink.connect` makes a unix `socketpair`, hands one end to QEMU over a short-lived
  QMP connection with `getfd` (`SCM_RIGHTS`) + `add_client` `{"protocol":"@dbus-display"}`, and speaks D-Bus on
  the other end. The mod's `QmpClient` is one-connection-per-command, so they never collide.
- **`dbus/Libc`:** the six libc calls Java lacks — `socket/connect/socketpair`, `sendmsg/recvmsg` with fd
  passing, `mmap/munmap` — via Java 25 FFM (`Linker.nativeLinker()`, `captureCallState("errno")`), with the
  x86-64/aarch64 Linux `msghdr`/`iovec`/`cmsghdr` layouts written out. No natives of our own. The dev runs pass
  `--enable-native-access=ALL-UNNAMED`; without it the JVM warns once and carries on.
- **`dbus/DbusMessage`:** just enough of the wire format — little-endian, the header fields we use, body types
  `y b q i u x t d s o g h ay as au`, structs and variants. Alignment is relative to the message start; the
  body starts 8-aligned so a fresh `Writer` is correct for it.
- **`dbus/DbusPeer`:** SASL as the client (`AUTH EXTERNAL <hex uid>`, `NEGOTIATE_UNIX_FD`, `BEGIN`), then a
  reader thread that parses messages, routes replies to the future waiting on their serial, method calls to a
  `Handler` (the listener object we export in step B2), and drops signals. Calls to QEMU are fire-and-forget
  (`NO_REPLY_EXPECTED`) for input, awaited for setup.
- **Pacing:** `QemuDisplayLink.key` queues; a thread sends one event every 4 ms. The guest's i8042 has a
  16-byte buffer and drops what arrives faster than it drains — a burst of press/release pairs lost a run of
  characters mid-line in testing, and the VNC path's "~150 characters" limit (TESTING gotchas) is the same buffer.
- **Protocol:** `ScreenInfoPayload.flags` bit 0 = `FLAG_SCANCODES`; when set the client's `VmScreen` sends
  `VmInputPayload.SCANCODE` (a = QEMU key number, b = down) for every physical key from `keyPressed`/`keyReleased`
  and ignores `charTyped`; otherwise the old keysym path. `client/input/QCodes` is the GLFW → key-number table,
  generated from QEMU's keycodemapdb (`data/keymaps.csv`, AT set-1 column, Pause special-cased to `0xc6`). `VmInstance.input` routes
  `SCANCODE` to `QemuDisplayLink.key`, `KEY`/`POINTER` to VNC.
- **Probing:** `tools/…/dbus_probe.py` in the session scratchpad did the whole handoff in Python first; the
  wire format was verified against QEMU before a line of Java existed. `gdbus` cannot be used — it has no way
  to take an fd — so `-display dbus,p2p=on` can only be exercised through `add_client`.

## Input (`client/screen/VmScreen`, `client/input/`, `MonitorBlock`)

**In-world (milestone 5 A4).** A monitor face is a pointing surface without opening anything: right-click on a
live picture is a left click at that pixel (`MonitorBlock.useWithoutItem` on the server maps the hit through
`hitToPixel`, the same aspect fit the renderer uses, and hands press+release to `ScreenSource.screenInput`), and
the crosshair resting on the face moves the guest's mouse (at most every other tick and only when the pixel
changed, never while a GUI is open — this was `InputSender.hoverTick` until §9 U4.0 made the ray a seam, and it
is `client/pointer/WorldPointer` driven by `CameraPointerSource` now; see "The VR seam" above). Sneak + right-click opens the full-screen
view; a monitor with nothing live (`screenActive()` false: stopped, or text mode) opens it on a plain right-click.
Relinking is placement or `/vmc link` — it is no longer on sneak. The `VmInputPayload` receiver routes by screen
id through `ScreenSources` to the source's `screenInput`, and drops players farther than `viewDistance` from the
machine (before A4 any client could send input to any VM id).

**Full-screen view.** 
Printable keys are sent from `charTyped` (press+release of the Unicode keysym: `cp` for Latin-1, else
`0x01000000|cp`) so shift/layout come from GLFW; `keyPressed` sends only non-character keys (table in
`Keysyms.fromGlfwKey`) or printable keys held with Ctrl/Alt/Super (`fromPrintableKey`). `downKeys` tracks
what we pressed so releases match. `Esc` closes unless Right Alt is held. `InputSender` batches: keys and
button changes flush immediately, plain motion once per tick (latest position only). Mouse coordinates map
through the fitted rectangle to framebuffer pixels; wheel = RFB buttons 4/5 (6/7 horizontal) press+release.

**The wheel's sign, once, for everyone.** The VM tier takes the RFB buttons as they are. For the Computer,
`LuaComputerBlockEntity.screenInput` turns button 4 (`0x08`, wheel pushed *away*) into **`dy = +1`** and button 5
into `dy = −1`, and the kernel hands that to `Window:wheel(dy, x, y)`. A widget scrolling up must therefore
**subtract**: `scroll = scroll − dy * 3`. Both `win.List` and `win.TextArea` had it the other way round until
session 12 — every scrollable thing in the ROM was inverted, and nothing in the harness looked at the wheel.

## Audio (`audio/ULaw`, `client/audio/VmAudio`)

Server encodes the VNC PCM to G.711 μ-law per tick (backlog capped at 1 s). Client decodes and queues
50 ms `AL_FORMAT_MONO16` buffers on one OpenAL source per VM inside Minecraft's own AL context (OpenAL Soft
is thread-safe; the context is process-global). Source uses vanilla's attenuation constants
(`AL_LINEAR_DISTANCE_CLAMPED`, max 32 blocks), gain = master × blocks volume, position = computer block
centre, `AL_SOURCE_RELATIVE` while `VmScreen` is open. Max 12 queued buffers; older ones are dropped.
Any AL error disables VM audio for the session (`alBroken`).

## Blocks, block entities, persistence (`block/`, `ModContent`)

- Registration: `Registry.register` with `ResourceKey`s and `Properties.setId(key)` (26.x requirement);
  `BlockEntityType` built directly (`new BlockEntityType<>(factory, Set.of(block))`); creative tab via
  `CreativeModeTabEvents.modifyOutputEvent(REDSTONE_BLOCKS)`.
- `ComputerBlockEntity`: `vmId` (UUID, generated on creation), `VmConfig`, `status`+`statusMessage`. Saved
  via `ValueOutput/ValueInput` (`child("config")`). `getUpdateTag`/`getUpdatePacket` sync everything to
  clients; `setStatus` only syncs on change. A ticker runs once after load for `autostart` and to re-attach
  a live VM to its block. `preRemoveSideEffects` stops/removes the VM.
- `MonitorBlockEntity`: `sourcePos` (int[3], NBT key still `computer`); `linkToNearestSource()` scans a radius-8
  cube for any block entity implementing `ScreenSource`; both sides resolve the screen UUID by looking the source
  up (so the source's chunk must be loaded). The monitor does not know what kind of machine it shows.
  `adoptOrphansAround(level, pos)` is the other direction, called from **both** computer block entities on their
  first tick (session 19): a monitor touching the new machine whose own source position holds no `ScreenSource`
  *and whose chunk is loaded* is adopted, and the rest of that dead source's rectangle follows it. First tick
  rather than `setPlacedBy` because `/setblock` and `/fill` never call that one, and those are exactly how a
  computer gets replaced under a screen that is still standing. Seeding from the six touching faces and then
  following the dead source outwards keeps a neighbouring dead computer's wall out of it. A monitor also
  **re-registers with its source once a second** instead of once per block entity: a source whose chunk reloaded
  comes back with an empty monitor set, and the old one-shot flag left it empty for good.
- `ComputerBlock.useWithoutItem` → `clientHooks.openComputerScreen`; `MonitorBlock` → sneak = relink, text mode =
  `source.monitorTouched(...)`, else `openMonitorScreen(sourcePos)`.
- Assets: cube models with per-face textures, 4 facing variants, `items/*.json` client item definitions,
  self-drop loot tables, pickaxe-mineable tag, `lang/en_us.json`.

## The Computer (`computer/`, milestone 7 — S1 as built, 2026-08-26)

*Design: ROADMAP §7h. This is the in-JVM tier: a Lua machine per block, on Cobalt, on a worker pool. The Command
Computer (the VM) is untouched by it except for sharing the bus components through `BusHost`.*

```
 block: LuaComputerBlock / LuaComputerBlockEntity (ScreenSource + BusHost; id, name, memMb, owner, powered, outputs)
            │ serverTick → ComputerManager.attach   (boot or thaw)          events: redstone_changed, component_added/removed,
            ▼                                                                       screen_touch, key/scancode/pointer, player_used, save, resume
 ComputerManager (per server): live LuaComputers by id, serverTasks queue, MachineScheduler, <world>/virtualminecraft/computers/<uuid>/
            │
 LuaComputer (Host + Listener): syscalls (bus → server thread and back, state, machine), console ring, freeze policies
            │                                     ▲ onResult (worker) → manager.post → status on the server thread
 LuaMachine (Cobalt LuaState, Sandbox globals, vmc.*, event queue) ⇄ MachineScheduler workers (slices, CPU bucket, allocation monitor, walks)
            │
 ROM boot.lua: json, bus, state, events, screen (text mode over the bus), the REPL kernel
```

- **Threads.** The server thread never runs Lua. `MachineScheduler` owns `computerThreads` workers
  (`max(2, cores/4)`) and one monitor thread. A machine runs on one worker at a time for a slice of
  `computerSliceMs` (5); the monitor calls `LuaMachine.interruptSlice()` when a slice overruns, and Cobalt's
  interrupt handler returns `SUSPEND`, which unwinds cleanly to `LuaThread.run` → `Result.SLICE`. The same
  handler throws a `LuaError` when `raise(msg)` was called (the memory errors: catchable by the program) and
  returns `SUSPEND` when `kill()` was called (not catchable: a `pcall` loop cannot survive a kill — the test
  `LuaMachineTest` checks exactly that). `LuaMachine.suspended` remembers which coroutine was cut so the next
  slice resumes it rather than `main` (an `eval` in between runs on its own scratch coroutine).
- **The kernel/host protocol.** `coroutine.yield("wait")` at the top level = block until an event
  (`Result.WAIT`; the scheduler drops `wantsRun`, `wake()` sets it again); `yield("wait", ms)` = the same, but
  the scheduler's one timer thread (`vmc-machine-timer`, a `ScheduledExecutorService`) calls `wake()` after `ms`
  unless an event woke the machine first (`Entry.timedWake`, cancelled in `wake`/`remove`) — a timed wait is still
  a `WAIT` for the freeze policy, so a desktop ticking its clock idles out like any other; `yield("flip")` = a frame is
  ready: with `computerWorkerFlush` (default) `LuaComputer.onResult` calls `ScreenDevice.flush` **on the worker** right
  there and the machine continues at once — the kernel paces itself by putting the presenting program to sleep for
  `vmc.frame_ms()` (`Host.frameMillis`: 1000/`computerMaxFps` while `ScreenViewers.anyone`, else 50) and yielding a
  timed wait; with it off, the old path: park until the server-tick flush wakes it (`awaitingFlip`). The tick flush
  still runs every tick for anything drawn outside a `flip` (the clock, the REPL). Both flushes take
  `ScreenDevice.flushLock`, which is what makes the viewers' `needFull`/`sentLod` safe. **The hardware cursor (U1.3):**
  `vmc.gfx_cursor(x, y, visible)` / `gfx_cursor_shape(w, h, hotx, hoty, data, key)` set cursor state on the
  `ScreenDevice` (never the framebuffer); every flush sends `ScreenCursorPayload` (`LocalBridge.screenCursor`) to a
  viewer whose cursor moved, with the sprite as RGBA (≤ 32×32, the key colour transparent) when the shape changed or
  the viewer is new. The client (`ScreenTextures.cursor`) keeps it as a nearest-filtered `ScreenTexture` and both
  `MonitorRenderer` (a quad in front of the picture, through the same aspect box, clipped to the screen) and
  `VmScreen` draw it at the last pointer position — so the kernel's pointer handler redraws only on a press, release
  or drag, and a hover is a 20-byte message; a yield with no values = slice over;
  any other first value = `Result.VALUE` (the floor's `"saved"` acknowledgement). Events are JSON strings in a
  bounded queue (256; overflow drops the newest and counts) pulled with `vmc.event_next()`, which never blocks.
- **CPU share.** Per machine a leaky bucket of `computerCpuPercent` (25) of one core with a one-second burst,
  charged with the worker's thread CPU time per slice; an empty bucket defers the machine (it slows, nothing
  else does). A world call (`vmc.call(1, …)`) blocks the worker on a `CompletableFuture` completed by the server
  thread (≤ 2 s; `LuaMachine.inHostCall` tells the monitor not to count it as stuck).
- **Memory (§1b).** `MemoryMeter`: the worker's allocated bytes per slice are exact (`ThreadMXBean`); when the
  allocation since the last walk exceeds the budget, `walk()` re-estimates the live size from globals, registry
  and coroutine frames (400k objects ≈ 40 ms); over budget → `raise("not enough memory (live ~X KB of Y KB, N
  objects)")`. The monitor also raises into a slice that has allocated more than **16×** the budget (`… (N MB
  allocated in one slice)`) — it was 2× until session 11, when `json.decode` of a 47 KB file (≈ 8 MB of one-character
  garbage in a 5 ms slice) killed half of Meowzie's boots; the walk after every budget's worth of allocation is the
  real limit, the per-slice check only has to catch a Java call that never polls. The ROM kernel runs each turn of
  its loop under `pcall`, so a raise that lands at the kernel's own yield (it lands at the *next safe point*, whoever
  that is) costs a notification, not the desktop. **`string.rep`, `string.format` and `table.concat` are sized
  before they allocate** and refused with "not enough memory" above the remaining budget — the three calls that
  can build an object far larger than their arguments in one step. `rep` is reimplemented in `Sandbox`; the other
  two keep Cobalt's implementation and are wrapped in Lua (so `__index`/`__len` and coroutine yields still work)
  around a Java size check — an upper bound for `format` (Cobalt caps a directive's width and precision at two
  digits, so only `%s`/`%q` can be large), the exact size for `concat` of a plain table, abandoned early.
- **Sandbox (§1d).** `Sandbox.install`: Cobalt's base, table, string, math, coroutine; no `io`/`os`/`package`/
  `require`/`dofile`/`loadfile`/`loadstring`/`string.dump`/`getfenv`/`setfenv`; `load` refuses bytecode and
  function chunks; `debug` has only `traceback`; `print` → the host's console; `collectgarbage("count")` → the
  meter. Everything world-facing is `vmc.*`: `log`, `clock`, `event_next`, `events`, `call(fn, payload)`,
  `mem`, `name`.
- **Syscalls (`LuaComputer.call`).** `1` bus: `{"op":"list"}` / `{"op":"call","target","method","args"}` →
  `Components.collect(level, be)` + `Components.find` + `invoke` on the server thread, replies and errors as
  JSON — the VM bus's semantics reused, including rate limits. `2` state: `""` reads
  `state/kernel.dat`, anything else writes it (≤ 256 KB, atomic). `4` machine: `reboot`, `shutdown`,
  `label:<name>`.
- **Lifecycle (§2, the floor).** `attach` on the block entity's first tick (or whenever the manager has no
  machine for a powered block — a re-promoted chunk has no first tick). `dispose(freeze=true)` pushes a `save`
  event and waits ≤ 250 ms for the kernel to yield `"saved"` (the ROM writes its state through syscall 2), kills
  the machine, writes `state/meta.json` (`frozenAtTick`). The next boot with `kernel.dat` present gets a
  `resume {frozen_for_ticks, world_ticks, real_ticks, reason, exact=false}` event first — `frozen_for_ticks` is the
  **longer** of the two clocks, because the world's stands still while the server is down and a machine frozen by a
  stop used to wake up believing no time had passed (`meta.json` records `frozenAtTick` *and* `frozenAtMillis`). Freeze triggers in `LuaComputer.tick`: chunk not
  block-ticking for 200 ticks (`shouldTickBlocksAt`, the VM's proven test), `computerIdleFreezeSeconds` (300) of
  no events while waiting with no `ScreenViewers`, owner offline when `computersRunWhileOwnerOffline` is off;
  `ComputerManager.shutdownAll` on `SERVER_STOPPING`; `/vmc computer freeze`. Breaking the block freezes and
  drops an item carrying `COMPUTER_ID`/`COMPUTER_LABEL`/`COMPUTER_MEM_MB`; placing it adopts the id, so the
  machine's directory and state follow the item. **A block entity that vanished from a still-ticking chunk** is
  the fourth freeze trigger (20 ticks, added session 15): `/setblock`, `/fill`, `/clone` and every world editor
  built on them place with flag 256, which tells `LevelChunk` to skip block-entity side effects, so
  `preRemoveSideEffects` never runs — nothing froze the machine, nothing dropped its item, and it kept its worker
  slices until the server stop found it. Ticking is the discriminator: a chunk *demotion* stops the chunk ticking
  first, so the 200-tick unload branch still owns that case (and `setRemoved` is not a demotion signal on the
  server at all — TESTING). Nothing is dropped, exactly as `/setblock` drops nothing else; `/vmc gc` is what finds
  the files afterwards. Reboot/shutdown delete `kernel.dat` (cold boot next) **and the
  now-empty `state/` and `computers/<uuid>/` with them** — that residue is what left 86 empty directories behind
  during the 100-spinner test. `/vmc gc` reports what is under `computers/`, `items/` **and the per-VM
  directories beside them** (`<world>/virtualminecraft/<uuid>/`, added session 19) with size, age and whether a
  block with that id has ticked this session; `/vmc gc empty` clears the provably empty ones and
  `/vmc gc drop <uuid>` one named machine, disk file **or VM**. VM directories are listed first and their share of
  the total is called out on its own, because they are gigabytes where the other two are kilobytes — which is also
  how two of them survived a `/fill` as 2.4 GB the collector could not see. Both computer block entities `note()`
  their position into the same map, so a VM directory backed by a real block reads as `overworld x, y, z` rather
  than `not loaded`. Nothing else deletes: a computer in an unloaded chunk is indistinguishable from an orphan,
  and guessing wrong destroys somebody's work.
  **Redstone wake / sleep** (`LuaComputerBlockEntity.sampleInputs`, the VM tier's rule verbatim): `wakeThreshold`
  1–15 and `redstoneSleep` are saved on the block entity and set through the redstone component (`rs wake 5`,
  `rs sleep on`). Edges only — a rising edge to the threshold `thaw()`s a machine that is *powered off*; a falling
  edge below it sends a `power {reason}` event and gives the program 100 ticks to save and call `os.shutdown()`
  before the block pulls the plug (the ROM's `handlers.power` does exactly that). A redstone change on a machine
  that is merely *frozen* already thaws it — every event does, through `emitEvent`.
- **Screen (S2, `ScreenDevice`).** One per `LuaComputer`: an indexed-8 framebuffer (`byte[w*h]`), a 256-entry
  palette, primitives called from the worker through `vmc.gfx_*` (all `synchronized`, all no-ops at 0×0), dirty
  rects (coalesced past 32), and `flush()` on the server tick from `LuaComputer.tick` at `streamFps` — the VM's
  `flushFrames` shape: per-level band lists shared between viewers, level 0 as `FORMAT_ZLIB_INDEXED` (+
  `ScreenPalettePayload` on subscribe/change), levels ≥ 1 palette-expanded and box-filtered into `FORMAT_ZLIB_RGB`;
  the local bridge takes `screenRectIndexed`/`screenPalette`. The client keeps an index buffer per indexed screen
  (`ScreenTextures.Entry.indices`) and re-expands it on a palette change. **Resolution follows the monitor**
  (`ScreenDevice.resolutionFor`: 256 px per block, uniformly fitted to 1024×768; the largest linked rectangle wins;
  a change fires `screen {w, h}` and clears). `takeDrawn()` once per tick flips linked monitors out of text mode
  (`pictureStarted`); any text write through the `screen` component flips them back — the S1 REPL is a text program.
  `gfx.present()` = `coroutine.yield("flip")` → `LuaComputer.onResult` flushes on the worker (U1.2; or, with
`computerWorkerFlush = false`, parks the machine — `MachineScheduler.park`)
  until the next flush wakes it, so a game loop runs at the stream rate. A freeze writes `state/screen.bin`
  (`snapshot()`: size, palette, deflated pixels) and the constructor restores it. **A framebuffer that nobody has
  watched and nothing has drawn into for `computerScreenParkSeconds` (20) is *parked*** — `park()` deflates the
  picture the same way a freeze does and drops the `byte[w*h]` (up to 768 KB per loaded machine) plus the deflate
  scratch; everything that touches pixels goes through `pixels()`, which inflates it back, so the first draw or the
  first viewer un-parks it and neither can tell. For that to ever happen the machine has to stop drawing, so
  `LuaComputer.tick` also sends a **`viewers {n}`** event on every 0↔n change: the ROM's `handlers.viewers` sets
  `kernel.watched`, and while it is false `tickClock` does not repaint the taskbar clock and `waitDelay()` returns
  nil (sleep until an event). An unwatched desktop then costs *no slices at all* instead of one every 833 ms, and
  its picture parks. Programs see the event too (`busEvent`), so a game can pause itself.
  `LuaComputer.tick` watches `drawSeq()` and `ScreenViewers`;
  `snapshot()` on a parked screen hands back the bytes it already has. `/vmc computer state` says which it is. Fonts: `font8x16.bin`/`font6x8.bin`
  from `tools/computer/fontgen.py` (256 glyphs, one byte per row, bit 7 leftmost; 0x80–0x92 hold box/block glyphs).
- **Storage (S3, `MachineFiles`).** One per `LuaComputer`; `refresh(level, be)` on the server thread every 8
  ticks rebuilds the mount table from the drive blocks on the machine's bus (`be.attached`, nearest first: the
  first two floppies are `/fd0` `/fd1`, the first two CDs `/cd0` `/cd1`); the syscalls run on the worker against a
  volatile snapshot of the table. Mounts: `/rom` (classpath: a directory in dev, a jar `FileSystem` in play),
  `/disk` (`<machine>/disk/`, quota from `computerDiskKb`), floppies as `items/<id>/` (foreign when only
  `items/<id>.qcow2` exists), CDs as `config/virtualminecraft/cds/<name>/` (`DiskData.iso = "cds:<name>"`, minted by
  `/vmc give cd <name>`) or `items/<id>/` (a blank CD; `burn(src, cd)` copies a mount's tree in and marks it
  read-only), `/import`. Path rule: `[A-Za-z0-9._-]{1,64}` per segment, ≤ 16 deep, resolved and normalized under
  the mount root (the world path Minecraft hands out contains `./` — compare normalized to normalized). Quota:
  a per-mount cached sum of file sizes, checked before every write. `bootSource()` returns the first `boot.lua`
  on a removable mount; `LuaComputer.boot` prefers it over the ROM (the custom OS gets bare `vmc.*`). The drive
  block's `findComputer()` returns a `BusHost` (either tier); `Attachments` skips a floppy that has a directory.
- **Input (S2).** `ScreenInfoPayload.FLAG_SCANCODES | FLAG_CHARS`: the view sends every physical key as `SCANCODE`
  *and* every typed character as `CHAR` (`VmInputPayload.Event.chr`); Ctrl+V sends `ScreenPastePayload` →
  `ScreenSource.screenPaste`. `LuaComputerBlockEntity.screenInput` maps `SCANCODE` → `scancode {code, down}`, `CHAR`
  → `char {cp}`, `KEY` → `key {sym, down}` (the keysym path, still accepted), `POINTER` with RFB wheel bits (0x78) →
  `wheel {dx, dy}`, otherwise `pointer {buttons, x, y}`; every event carries `player`. The monitor face's click
  and hover (M5 A4) arrive the same way. Not done: the hardware cursor; the worker-side local flush.
- **Caps.** `ComputerManager.placementRefusal`: `maxLoadedComputers` (1000) and `maxComputersPerPlayer` (200),
  checked when powering on (placement itself is not blocked yet — S1 follow-up: refuse in `setPlacedBy`).
- **Harness.** `./gradlew luaMachineTest` (protocol, sandbox, preemption, memory, kill) and
  `./gradlew schedulerTest` (fairness, wake latency, share, monitor, walk) run without Minecraft;
  `/vmc computer state|lua|event|freeze|thaw|reboot|shutdown|list` in-game. `machineBench` is the S0 record.

**The VR seam (§9 U4.0, 2026-08-29) — built before anything VR-facing, on purpose.** Two interfaces in
`client/pointer/`, both made only of numbers so a Minecraft update and a Vivecraft update touch different sides of
them: **`PointerSource`** (`name()`, a nullable `PointerRay` of origin + direction + reach, RFB `buttons()`,
`takeWheel()`, `priority()`) and **`TextSource`** (`takeText()`). `Pointers` is the registry — sorted by priority,
polled once a client tick after `InputSender.tick()`, and the first source actually pointing wins. `WorldPointer`
is the main mod's half: ray → `Level.clip` → the monitor → `MonitorBlock.hitToPixel` → `InputSender`, with the
motion rate limit (every other tick, only on change) that the crosshair hover always had, buttons sent the instant
they change (WiVRn's 30–50 ms means the press has to feel like the press), and `release()` so a button can never
stick down on a screen nobody is pointing at. It clips entities on the same segment as well as blocks, because a
mob in front of a screen has always blocked the crosshair and has to block a controller too.
<p>
**What moved:** `InputSender.hoverTick` *was* this, inlined, back when the camera was the only thing that could
point. `InputSender` now only sends (`worldPointer`, `worldWheel`, `worldChars`), and `CameraPointerSource` is the
crosshair expressed through the seam — **its buttons are always zero**, so clicking a monitor stays
`MonitorBlock.useWithoutItem` on the server where it is authoritative, and a build with nothing else registered
behaves exactly as before. That promise is the regression test: TESTING's monitor recipes must pass unchanged.
<p>
**The far side.** `vr/` is a Gradle subproject producing `virtualminecraft-vr-0.1.0.jar`, depending on the root
project's `main` and `client` output for the seam and on nothing else of it. Vivecraft is a plain `compileOnly`
against whatever `vivecraft-*.jar` sits in the gitignored `vivecraft/` directory — compileOnly permanently,
because we never redistribute anyone else's code, and *plain* rather than loom's `modCompileOnly` because the jar
is already in official names and has nothing to remap. A clone without it still builds. **`FakePointerSource`** is
how the near side is tested without a headset — the puppet's `vraim` / `vrbutton` / `vrwheel` / `vrpointer` drive
the real path end to end.

**What the far side actually registers (§9 U4.2 and U4.3, 2026-08-29, all verified in the headset).**
- **`VrPointerSource`** — the dominant controller's ray. Vivecraft hands out `getPos()`/`getDir()` as `Vec3`
  already, so this is a constructor call plus an exponential moving average for hand tremor
  (`vrPointerSmoothing`, measured at 0.5). It returns null whenever `isVRActive()` is false, which is what keeps a
  desktop client with the jar installed identical to one without it.
- **`MonitorInteractModule`** — the trigger, *bid for* rather than polled. Vivecraft has no button to read; it has
  a contextual interact binding that modules compete for, one per hand per tick, and the winner gets it. Both
  hands hit-test the *dominant* hand's ray, so you aim with one and both triggers act on the pixel you are aiming
  at: dominant is left click, the other is right click.
- **`KeyboardOnUse`** — the keyboard, on Minecraft's ordinary "use". In VR that button is free, because the ray is
  already the mouse and the triggers are already its buttons.

**The keyboard half of the seam (§9 U4.3).** `MonitorUse` is the third interface across it — a UUID and a
`BlockPos`, registered by the VR module, consulted by a `UseBlockCallback` in the main mod. When it fires,
**`WorldKeyboard`** starts typing at that screen by borrowing `InputSender`'s ordinary session, so the batching,
the payload and the server's distance check are the path the full-screen panel always used. `WorldPointer` keeps
pointing while the session belongs to the keyboard rather than to an open view, because aiming and typing at once
is the whole point.

Reaching an in-world screen at all needed **the mod's only client mixin**, `KeyboardHandlerMixin`: Minecraft
delivers key events to the focused `Screen`, and a monitor is not one, and there is no event for "a key was
pressed with no GUI open" short of a key binding — which is the wrong shape, since we want *every* key including
the ones bound to walking. It is inert unless `WorldKeyboard` is open, and the only thing that opens it lives in
the VR jar, so the promise still holds. Closing the keyboard calls `KeyMapping.releaseAll()`, because cancelling
key events can leave the game holding a key whose release it never saw.

**`KeyRelay`** is the key translation — chars, scancodes, keysyms and QEMU's Shift quirk — extracted from
`VmScreen` and shared with `WorldKeyboard`. Two ways to reach a machine's keyboard now exist and they must be
indistinguishable to the guest; sharing the code is how that is guaranteed rather than hoped for.

**Three session-27 additions finished the VR module (2026-08-29).** `WorldKeyboard.keyEvent` swallows key events
matching the jump/sneak bindings while the keyboard is open ([name]'s option A: a VR stick-click is a synthesized
Space by the time the mixin sees it — consuming beats passing through, or poking the space bar would jump).
**`KeyboardAnchor`** pins Vivecraft's floating keyboard over the nearest `KeyboardBlock` (a main-mod block that
is pure furniture on a desktop): the pin is `KeyboardHandler.POS_ROOM`/`ROTATION_ROOM` — Vivecraft *internals*,
so the write lives in `VivecraftLink.anchorKeyboard`, javap'd and source-checked (`orientOverlay` writes those
fields only at open, so ours stick) — re-derived every client tick because room coordinates move with the
player (`VRPlayer.worldToRoomPos`). **`VrScroll`** registers two deliberately-unbound KeyMappings; Vivecraft
turns any mod KeyMapping into a bindable VR action and presses an unbound one directly (`setKeyBindState`, no
synthesized GLFW key — so it cannot collide with the keyboard capture), feeding `VrPointerSource.takeWheel()`.

**Program state across a freeze (§9 U12, 2026-08-29).** The kernel's saved blob (syscall 2) gained a `programs`
list beside `windows`. `kernel.runfile(path, args, restored)` puts a **`program`** table into the program's
environment next to `PROGRAM_DIR` — `path`, `dir`, `args`, a `version` the program sets, a `save` function it
sets, and a `restore()` closure that hands back the kept state exactly once and only when the versions agree —
and hangs it on the window as `wd.programFile`. `kernel.save` walks the same window list it always did: an app
window still goes through `app.save`, and a **full-screen program window with a `programFile.save`** is asked
too. Three things are refused there rather than allowed to break the whole save, each with a line in the log: a
`save` that throws (pcall), one that returns something `json.encode` will not take, and one over
`kernel.MAX_PROGRAM_STATE` (32 KB — the host caps the whole blob at 256 KB and a save that fails costs the
desktop its windows). `state.save` itself is pcalled for the same reason. `restore()` re-runs each saved path
after the windows, skipping any whose file is gone (a CD can be ejected while the machine is frozen) and
recording `kernel.restoredPrograms[path]`, which is what stops `/disk/autostart.lua` starting a second copy of a
program that just came back. **What this deliberately is not** is a savestate: the program is re-run from the
top and handed *data*, which is why it survives a mod update where a paused Cobalt call stack could not.
ROADMAP §9 U12 has the argument in full.

**The world's clock and calendar (§9 U10(b), 2026-08-29).** `LuaComputer.clock(kind)`: 0 monotonic nanoseconds,
1 `level.getDefaultClockTime()` (the world's day-time counter, which honours `/time set`), **2 the world's own
milliseconds since 1970** — `WORLD_EPOCH_OFFSET_MS + ticks * MS_PER_TICK`, where a tick is **3600 ms** (a Minecraft
day is 24000 ticks and lasts a day, so the world's clock runs 72× real time) and the offset is six hours, because
world tick 0 is **1970-01-01 06:00** — and 3 the host's wall clock, which nothing but `os.realtime()` wants. On top
of that `sys.lua` has the calendar: `os.epoch()`, `os.datetable([ms])`, `os.date(fmt [, ms])` (a strftime subset,
`"*t"` for the table), `os.daysfromdate(y, m, d)` and `os.monthdays(y, m)`, all on Hinnant's civil-calendar
algorithms so leap years need no table. `os.date()` with **no** arguments is still `"HH:MM"` plus the Minecraft day
number — the taskbar clock and `top` are built on it. `apps/calendar.lua` is the month grid. Consequence worth
knowing: the *date* rolls over at world tick 18000, which is Minecraft midnight, while the *day number* rolls over
at dawn, so one Minecraft day spans two dates exactly the way a real night does.

**The ROM (S4 + S6, 2026-08-26).** Lua files under `src/main/resources/virtualminecraft/rom/`, mounted read-only
as `/rom`. `boot.lua` loads `kernel.lua`; the kernel installs `lib/sys.lua`'s libraries as globals (`bus`, `state`,
`fs`, `gfx`, `snd`, `os`, plus `json`) and `lib/win.lua` (`win`), then runs an event loop: `vmc.event_next()` →
handlers — input (`pointer`, `scancode`, `char`, `wheel`, `paste`), the monitor's `screen {w, h}`, the lifecycle's
`save`, `resume`, `power` and `viewers`, the disks' `disk_inserted`/`disk_ejected`, and `exec`/`shell` for the
harness and `/vmc computer event`; `key` is the old keysym path and is ignored. Everything else is a bus event
handed to every window's `onbus` and every program's `onbus` — as are the disk, `viewers` and `power` events after
the kernel has had them, so a program can follow the same things the desktop does. What the lifecycle ones carry:
**`resume {frozen_for_ticks, world_ticks, real_ticks, reason, exact}`** — the first event after a thaw;
`frozen_for_ticks` is the longer of the world and wall clocks (see the lifecycle bullet above), `reason` is what
froze it (`unload`, `idle`, `owner`, `command`, `removed`, `stop`) and the ROM's toast singles out `stop` as
"server was down"; **`power {reason}`** — `redstone` today (a falling edge under `wakeThreshold` with
`redstoneSleep` on), with 100 ticks to save and call `os.shutdown()` before the block pulls the plug;
**`viewers {n}`** — sent only on a 0↔n change, `n` being how many players are looking at a linked monitor; with no events it resumes the programs that are due, redraws when
dirty and yields `flip` (a frame is ready) or `wait` (nothing to do — the machine parks; on the desktop it is
`wait, ms` until the taskbar clock's next in-game minute, and `kernel.tickClock` then redraws just the clock's
rectangle unless a window or the cursor covers it). Windows resize by the grip in their bottom-right corner
(`Window:gripHit`, a `resize` state next to `drag` in the pointer handler, `win.MIN_W/MIN_H` or the window's
`minW/minH`, `relayout()` on every move; dialogs set `resizable = false`). The desktop shows the hardware cursor
(`kernel.showCursor`); a full-screen program hides it unless its window has `cursor = true`. **The shell (U2,
`lib/shell.lua`, global `shell`)** is the Terminal's brain: `shell.new(out)` makes a session (`cwd`, `vars` with
`PATH`/`HOME`, `history`, a Lua env), `sh:run(line)` runs one line — `=<expr>` and `lua …` go to the Lua REPL
(the raw line, unsplit), otherwise the line is `$`-expanded and split (quotes, `#` comments) and the first word is a
command from `shell.commands` (each with `usage` + `help`) or a program found by `shell.find` (a path; `name`,
`name.lua`, `name.sh`, `name/main.lua` in the cwd, `$PATH` and the removable mounts; a launcher name from
`kernel.diskPrograms()`): `.lua` runs inline through `fs.run` with the arguments as `...`, `.sh` line by line with
`$1..$9`/`$*` (`shell.script`, nesting ≤ 8), a program directory / CD full-screen via `kernel.runfile`.
`sh:complete(line)` is Tab (commands + programs for the first word, paths after). Output goes to `out.print(text,
fg)` (the Terminal's `TextArea`, which now keeps a colour per line) and, when `sh.tee` is set, to `vmc.log` — the
Terminal sets it, so everything the shell prints also reaches the machine's console (`/vmc computer state`) and the
harness. The `shell` event (`{"line": …}`) types a line into the Terminal; `exec` still types Lua. Outside
Minecraft the same machine runs in `RomBootTest` (headless, a pretend bus) and in `ComputerEmulator` (a Swing
window over that host; TESTING "The Computer emulator") — both in `src/test/java`. A **program** is a
coroutine with a window (`kernel.spawn`): it yields `flip` (the kernel redraws, the host sends the frame, and the
program is due again `kernel.frameTime()` later — the loop's next `wait, ms` is the earliest program wake, the
toast's end or the clock's minute, whichever is first), `sleep, ms` or `wait`; an error kills it and shows a
dialog, the desktop survives. **Apps** are `rom/apps/<id>.lua` modules `{ id, name, icon, open(args) → window,
save(window) → state }`, registered in order; the desktop shows as many icons as fit above the taskbar, the
taskbar's **Apps** button opens the launcher (every app plus **programs on disk**: a directory with `main.lua` and
an optional `program.txt` on any mount but `/rom`, run full-screen with `PROGRAM_DIR` set). `kernel.save()` (on the
host's `save` event) writes every open window's app state through `state.save`; `restore()` reopens them on the
next boot — the "floor" of §2. `win` is the toolkit: `Window` (title bar, close box, drag, focus, modal), widgets
`Label`/`Button`/`Toggle`/`List`/`TextField`/`TextArea` (each a table with `x,y,w,h`, `draw`, `press`, `release`,
`key`, `char`, `wheel`; an instance may override any of them), `win.ask` and `win.prompt` dialogs, `win.fit` for
truncation, two fonts (`gfx.text` font 0 = 8×16, 1 = 6×8; the theme uses 6×8). `TextArea` takes `wrap = true`
(the Terminal's console; Edit does not, code wants the horizontal scroll): one logical line becomes several
*visual rows* broken after the last space that fits — hard at the edge for a word longer than the area —
and `scroll`, the wheel and a click all count visual rows. `TextArea:visual()` is the cached row list, keyed on
the text generation (`touch()`, called by `append`/`settext`/`changed`) and the width, so a resize re-wraps. Apps as built: Terminal (REPL over
`load` with a persistent env, `help()`, bus events echoed, the harness's `exec` types into it), Files, Edit
(Ctrl+S, F5 save + run), Settings, Inventory / Redstone / World (S6, on `bus.call`; periodic refresh through a
sleeping program on the window), Music (S5, a 16-step tracker on `snd`), Demo.

**Sound (S5, 2026-08-26).** `computer/SoundChip`: four synth channels (square with duty, triangle, saw, sine,
noise; ADSR in seconds; pitch slide) and two sample channels (8-bit unsigned PCM ≤ 64 KB, ≤ 16 loaded, any rate ≤
48 kHz), a master volume. The worker sets parameters through synchronized setters (`vmc.snd_channel/note_off/
slide/sample_load/sample_play/stop/master`, wrapped by `snd.*` in `sys.lua`; channels are 1-based in Lua, 1–4
synth, 5–6 samples); `LuaComputer.tick` calls `mixTick()` once per server tick, which returns 1102/1103 samples of
16-bit mono at 22050 Hz or null. Silence sends nothing, but the chip keeps sending a **second of silence after the
last active tick** (`TAIL_TICKS`): the client starts an OpenAL source only with two buffers queued, so a stream
that stopped at every rest restarted and underran at every note. The PCM goes through the existing `ULaw` encoder
and `AudioPayload` — now carrying an optional `BlockPos` so the client places a source it has never seen — to every
player within `computerSoundRange` (32) blocks, the singleplayer host through `LocalBridge.audioAt`. The client side
is `VmAudio` unchanged (one streaming source per machine id). Cost: ~50 µs per tick for six busy channels.

### The tier ladder (`computer/MachineSpec`, `ComputerMenu`, `item/PartItem`; ROADMAP §9 U3b — built 2026-08-26, session 13)

- **Three case blocks, one block entity.** `basic_computer` / `computer` / `advanced_computer` are three
  `LuaComputerBlock` instances with a `tier` field (1–3); `LuaComputerBlockEntity.tier()` reads it off the block
  state, so nothing about the tier is stored twice. Everything below the block (`LuaComputer`, the scheduler, the
  ROM) never sees a tier — it sees a **`MachineSpec`**.
- **`MachineSpec` is the whole rule, and since §9 U10(a) the rule is "the parts are the machine".** Each axis is
  the part's own value capped by the case's *ceiling* — memory 2 / 8 / 16 MB, CPU 25 / 35 / 50 % of a core, screen
  256² / 512² / 1024×768, colours 256, disk 2 / 8 / 32 MB, sound 3+0 / 4+2 / 4+2 (the sound is the case's, not a
  part's) — and memory is capped again by `maxComputerMemMb`. **An empty slot contributes nothing**, so a case with
  no RAM or no CPU has `memMb`/`cpuPercent` of 0 and `canBoot()` is false; no graphics card means `maxW/maxH/colours`
  of 0 and no framebuffer at all; no drive means `diskKb` 0 and no `/disk` mount. `bootRefusal()` / `shortRefusal()`
  are the words every surface uses for the first case. Any part still fits any case, and the ROM still cannot tell a
  clamped part from a smaller one. *(Until 2026-08-29 each axis also had a per-tier **base** and an empty case was a
  complete machine; `MachineSpec.migrationLevels` still holds those numbers, because they are what an existing case
  has to be given.)*
- **Parts are plain items** (`PartItem(kind, level)`, twelve registry entries `ram_1` … `drive_3`, no data on the
  stack — recipes stay vanilla JSON). The case holds a `SimpleContainer(4)`, one slot per `MachineSpec.Part`, saved
  as `parts` in the block entity and synced to the client with it. `setChanged()` on that container is the only
  hook: it syncs and, if the machine is live, **restarts it** (`manager.remove` + `requestBoot`) with a toast to
  players within 8 blocks — a memory cap cannot shrink under a running heap, and the quota/screen/voices are set
  once at construction, so a part change is a reboot by design.
- **A part's slot is derived from the part, never written down** (`loadParts`, fixed session 15). `SimpleContainer`
  saves the list with the empty slots dropped and reads it back with `addItem`, which packs from slot 0 up: the
  four slots only round-tripped while they were filled from RAM downwards, and a case holding just a graphics card
  read it back into the RAM slot, where `PartItem.levelOf` sees the wrong kind and reports nothing. Every part item
  knows its own kind, so the load puts each one where it belongs; old saves read correctly and the format did not
  change. The load also suppresses `partsChanged` until every slot is written, so it costs one restart, not four.
- **Where the spec lands** (all in `LuaComputer`): the constructor builds `MachineFiles` with
  `spec.diskQuotaBytes()`, calls `ScreenDevice.setLimits(maxW, maxH, colours)` *after* restoring the snapshot
  (a smaller cap shrinks a live picture) and `SoundChip.setChannels(synth, samples)`; `memoryCapBytes()` is
  `spec.memoryCapBytes()`; `boot()` hands the scheduler `spec.cpuShare()` (`MachineScheduler.submit(machine,
  listener, share)` — the share is per `Entry` now, the pool's `computerCpuPercent` is only the default);
  `LuaComputerBlockEntity.monitorResolution` fits the monitor rectangle with `resolutionFor(mbW, mbH, capW, capH)`,
  so a 2×2 wall on a Basic Computer shows 256×256 scaled up. **Caps degrade, never refuse:** `setPalette` above
  `colours` is a no-op, a channel the case does not have plays silence (`Channel.enabled`), `resize` clamps.
- **What the ROM sees.** `vmc.info()` (`Host.info()`, JSON from `LuaComputerBlockEntity.info()`: the spec plus
  `desktop`, `desktopMode`, `name`) → `os.info()` in `sys.lua`. The kernel sets `kernel.console = not
  info.desktop` at boot: no icon column, no taskbar (`taskbarH = iconW = 0`), the Terminal `borderless` over the
  whole screen, every other window laid out full size, closing the last window reopens the shell. `os.desktop(mode)`
  is syscall 4 `desktop:auto|desktop|shell` → `desktopMode` on the block entity (auto = the tier decides: tier 1 is
  the shell); Settings has the button, the GUI has the same one. The harness host and the emulator implement
  `info()` from a whole `MachineSpec` (`--tier N`, `--parts A,B,C,D` as levels 0–3, `--shell`/`--desktop`).
- **The GUI** is a vanilla container menu: `ModContent.COMPUTER_MENU` is Fabric's `ExtendedMenuType<ComputerMenu,
  BlockPos>` (menu API v1 — its classtweaker is what makes `MenuScreens.register` reachable on the client), the block
  entity is the `ExtendedMenuProvider` (`player.openMenu(be)` on a plain right-click; sneak still toggles power),
  `ComputerMenu` has the four filtered slots + the player inventory and relays two `clickMenuButton` ids (power,
  boot mode); `client/screen/ComputerScreen` draws a panel with the vanilla `container/slot` sprite and reads every
  number it shows from the client's block entity (`spec()` is computed there from the synced parts, `memMb()` from
  the synced value because the server's cap is not known client-side). The old right-click (open the view) is the
  GUI's "Open screen" button.
- **Migration, twice over.** A block saved without `parts` keeps its `memMb` as `legacyMemMb` and turns it into
  the smallest RAM part that gives it on its first tick (`installLegacyMemory`); a placed item carrying the old
  `computer_mem_mb` component does the same. The clamp applies: a 16 MB `computer` from before the ladder comes
  back as 8 MB with a RAM III inside — re-place it as an `advanced_computer` (the test world's 2×2 wall was).
  **§9 U10(a) adds a second one**: a save with no `partsMigrated` flag predates "a case is only a ceiling", when an
  empty case was a whole machine, so `migrateParts()` fills its *empty* slots with `MachineSpec.migrationLevels`
  — the cheapest parts worth at least what a bare case of that tier used to hand out free. No axis goes backwards
  (a bare Computer's 4 MB / 25 % / 256²·256 colours / 1 MB becomes 8 MB / 25 % / 512²·256 colours / 2 MB), and the
  graphics level satisfies width, height *and* colours, which is why it is a loop and not a `levelFor`. The check
  lives on the tick, not in the first-tick block, so a case whose NBT is rewritten under it (`/data merge`, a
  schematic paste) is fitted too. Breaking a case drops its parts as items and the case item with the machine id.
- **What a dead box does** (§9 U10(a), [name]: *"dead box, full stop"*). `serverTick` returns before `attach` when
  `!canBoot()`; `ComputerManager.attach` throws the reason as well, so a command cannot start one either. The
  once-a-second beat that already answers "is there a monitor" also computes `hardwareNotice()` — the boot refusal,
  or `"no graphics card"` when a monitor is standing there with nothing to show — and writes it onto the monitors'
  `TextGrid` in the firmware voice the Command Computer already uses. `screenActive()` returns true while a notice
  is up so the glass lights instead of going dark, which puts a `"picture …"` prefix on the synced status and lets
  the client answer the same way. The case GUI spends both of its spec lines on the reason and puts the short form
  ("no CPU/RAM", "no GFX card") where the status goes, in the warning colour. The case *item* has a tooltip saying
  it is empty (`item/CaseItem`) — crafting hands you a box, and nothing else would say so.
- **The GUI says what the case can take.** Each part slot's tooltip (`ComputerScreen.extractTooltip`) names what is
  in it — or what it takes, when it is empty — and then `MachineSpec.ceilingLabel(part, tier)`: "Up to 2 MB in this
  case". That is the only place a clamp is visible before it happens, which is why an empty slot has a tooltip at
  all. The boot button is the mode (`Auto` / `Desktop` / `Shell`) with the resolved answer in its own tooltip; its
  label has to stay short — the panel's three buttons have 162 px between the margins.

## Guest ↔ world bus (`bus/`)

```
guest: /dev/virtio-ports/vmc.bus  ⇄  QEMU virtserialport  ⇄  chardev socket <runtime>/bus.sock (server=on)
                                                                     ⇅ BusLink (reader + writer threads, bounded queues)
                                                     server thread: VmBus.tick() ≤ 64 requests/tick → Components → world
```

- **Transport**: `QemuLauncher` adds `-device virtio-serial-pci -chardev socket,id=vmcbus,path=…/bus.sock,server=on,wait=off
  -device virtserialport,chardev=vmcbus,name=vmc.bus`. `VmInstance` creates a `VmBus` right after the RFB
  connection succeeds and connects to that socket (client side, like QMP); `tick()` retries every 2 s if it
  drops. `BusLink` owns the socket: a reader thread splits UTF-8 lines (cap 64 KB, oversized lines replaced by
  the `OVERSIZED` marker) into a bounded queue (512; overflow counted and reported as `RATE_LIMITED`), a writer
  thread drains an outgoing queue (1024) so the server thread never blocks on I/O. Windows falls back to
  loopback TCP (`4690 + slot`).
- **The port from inside the guest, per OS** (the mod must work with any OS; nothing here needs a special
  image): **Linux** — `/dev/virtio-ports/vmc.bus` (udev symlink from the port name; the raw node is
  `/dev/vport<N>p<M>`), in-kernel `virtio_console`, no driver to install; verified on the Arch ISO in BIOS and
  UEFI (OVMF) boots. Open it read/write, unbuffered, one process at a time (a second opener gets `EBUSY`).
  **Windows** — install the virtio-win "vioserial" driver (`virtio-win-*.iso`, in `~/Downloads`), then the port
  is `\\.\Global\vmc.bus` (CreateFile / Python `open(r"\\.\Global\vmc.bus", "r+b", buffering=0)`);
  untested, deliberately deferred. **macOS** guests have no virtio-serial driver — out of scope. Host-side
  subscriptions are lost across suspend/resume; a guest program that wants events must re-`subscribe` when
  the port reports the host reconnecting (Linux: the read returns 0 bytes / `EAGAIN` until then).
- **Wire format**: line-delimited JSON-RPC 2.0. Guest → mod requests: `ping`, `info`, `list`, `invoke`
  (`[address, method, args…]` or `{address, method, args}`), `subscribe`/`unsubscribe` (`"*"`, a name or a
  list), and the shortcut **`<type>.<method>`** / `<address>.<method>` with `params` = args array — so a shell
  one-liner works. Responses `{"jsonrpc":"2.0","id":…,"result":…}` or `{"error":{"code","message"}}`;
  codes in `BusException` (JSON-RPC standard ones plus `-32000` component error, `-32001` rate limited,
  `-32002` computer not loaded, `-32003` no such component). Mod → guest events are notifications:
  `{"jsonrpc":"2.0","method":"event","params":{"name":"redstone_changed", …}}`, only for subscribed names.
  Requests without an `id` are notifications and get no reply.
- **Threading**: everything that touches the world runs in `VmBus.tick()` from `VmInstance.tick()` (server
  thread), handed the `ComputerBlockEntity` if its chunk is loaded (`NOT_LOADED` error otherwise). Events are
  emitted from server-thread code paths (`ComputerBlock.neighborChanged`) via `VmBus.event`.
- **Component model**: `Component` = stable `address()` (`UUID.nameUUIDFromBytes(vmId/type/location)`; the location
  is a side name for a neighbour and a `dx,dy,dz` offset for anything reached over cable, so moving a block gives it
  a new address — deliberately: it is a different socket),
  `type()`, `location()` (`self` or a side), `methods()` (the whitelist; `list` returns it with doc strings)
  and `invoke(method, JsonArray args)`. `ComponentProvider`s registered in `Components` build the list fresh
  for every request, so adjacent blocks can come and go. Other mods can register providers.
- **`RedstoneComponent`** (`type=redstone`, `location=self`): `getInput(side)`, `getInputs()`,
  `getOutput(side)`, `getOutputs()`, `setOutput(side, level|bool)`, `setOutputs({side: level})`, `getFacing()`,
  and the wake pair `getWake()` / `setWake(0..15)` / `getSleep()` / `setSleep(bool)` (`BusHost.getWakeThreshold`
  &co., which the VM tier maps onto its `VmConfig` and the Computer tier onto its own saved fields).
  Sides: absolute names, `front/back/left/right/top/bottom` relative to the block's `FACING`, or 0–5
  (`Direction` 3D index). Outputs live in `ComputerBlockEntity.outputs[6]` (persisted as `outputs` int array);
  `ComputerBlock` is a signal source: `getSignal`/`getDirectSignal` return the output of face
  `direction.getOpposite()` (the parameter points from the asking block towards us). `setOutput` calls
  `updateNeighborsAt` for the block and the affected neighbour. Inputs are `level.getSignal(pos.relative(side),
  side)` — dust we power echoes back, as in OpenComputers. `neighborChanged` re-samples all six inputs and fires
  `redstone_changed {address, side, level, previous}` per changed face. Outputs are cleared when the VM stops.
- **`InventoryComponent`** (`type=inventory`, `location=<side>`, one per adjacent block exposing Fabric's
  `ItemStorage.SIDED` as a `SlottedStorage` — unsided view first, then the touching face, like CC: Tweaked):
  `size()`, `name()`, `list()` (sparse `{slot: {name, count, displayName, maxCount}}`), `getItemDetail(slot)`
  (adds damage, customName, tags), `getItemLimit(slot)`, `pushItems(to, fromSlot[, limit[, toSlot]])`,
  `pullItems(from, …)`. **Slots are 1-based** (OC/CC convention). Moves are `StorageUtil.move` on the source
  slot into the target storage (or one target slot) inside a committed `Transaction`, so hopper/furnace slot
  rules and modded containers behave normally. Address is per *side*, not per container.
- **Targets**: `Components.find(list, target, defaultType)` resolves what the guest names: a full address,
  `type@side` (`inventory@north`), a bare type (first match), or — inside `pushItems/pullItems` — a bare side.
  The JSON-RPC shortcut accepts the same: `inventory@north.list`.
- **`ScreenComponent`** (`type=screen`, `location=dx,dy,dz` = monitor offset from the computer; one per loaded
  monitor that registered itself with the computer via `ComputerBlockEntity.registerMonitor` on its first
  server tick): a **text mode** for monitors. The grid (`bus/TextGrid`: `cols×rows` of codepoint/fg/bg, cursor,
  current colours) lives in `MonitorBlockEntity` on both sides, persisted as int arrays under `text`, and is
  synced by `ScreenTextPayload` (size + text-mode flag + only the dirty rows, encoded straight into the
  buffer) from `MonitorBlockEntity.serverTick` to `PlayerLookup.tracking(be)`; the full grid rides along in
  the block entity update tag for chunk loads. Methods (1-based): `getSize/setSize`, `getTextMode/setTextMode`,
  `clear`, `clearLine`, `write` (cursor, wrap, scroll), `set(x,y,text)`, `get`, `fill`, `scroll`,
  `get/setCursorPos`, `get/setColors` (0xRRGGBB or `#rrggbb`). Any drawing call turns text mode on.
  **Rendering** (`MonitorRenderer.submitText`): the grid's pixel box (`cols*CELL_W(6) × rows*CELL_H(9)`) is
  scaled to fit the bezel-inset square and centred; backgrounds are runs of equal colour as quads on
  `textures/block/white.png` via `RenderTypes.text`, characters are `submitText` one per non-blank cell
  (monospace by construction — MC fonts are proportional, Unifont trims glyphs). `MonitorBlock.hitToCell` uses
  the same maths so a right-click in text mode becomes a `screen_touch {address, location, x, y, player}`
  event instead of opening the VM screen (sneak-click still relinks).
- **`player_used`**: `ComputerBlock.useWithoutItem` (server side) pushes `{player, side, sneaking}` when a
  player right-clicks the computer, if subscribed; the client still opens the config screen.
- **Hot-plug events**: `ComputerBlockEntity.sampleComponents` (called from `neighborChanged` and primed by
  `subscribe`) diffs the component address set and fires `component_added` / `component_removed`
  `{address, type, location}` — only tracked while the guest subscribes to either.
- **Bus cable** (`block/BusCableBlock`, `bus/BusNetwork`): a computer's components need not touch it. A cable run
  connected to the computer carries the bus, and anything touching that run is a component of every computer on it.
  The block has **no block entity and stores nothing** — `BusNetwork.attached(level, computerPos)` floods the run on
  demand and returns an ordered `BlockPos → location` map: the six neighbours first (in `Direction` order, keeping
  their **side names** so addresses and device ids predate cables unchanged), then cable-reached blocks nearest
  first, located by offset `dx,dy,dz` (the form `ScreenComponent` already used for monitors). Bounds:
  `busMaxCables` / `busMaxAttached` (config; defaults 1024/256 since U9, with block entities seated first so far
  hardware beats near stone). **Since U11 this is a lookup, not a fill**: the run is walked once when the cable
  changes and `attached()` reads the stored `BusRegistry.Net` (see the next bullet). The walk never *loads* a
  chunk, but since U11b it follows cables the registry remembers through unloaded ones, so a half-loaded run
  reads as one run; candidates are only inspected where the world is loaded. The result is still
  cached for one tick in `ComputerBlockEntity.attached` (every `list`/`invoke` asks) and dropped in
  `onNeighborChanged` so the hot-plug diff never sees a stale list. `BusCableBlock.neighborChanged` asks the
  registry whether the update changed anything it stores and, only then, calls `onNeighborChanged` on every
  computer on the run — which is how `component_added`/`component_removed` fire for a
  block placed at the far end, without a redstone level next door costing anything. The six boolean properties are **cosmetic only** (`PipeBlock`, arms drawn towards
  cable, our blocks, and anything with a block entity); what actually counts as a component is decided by the fill.
  `InventoryComponent`, `DriveComponent` and `Attachments` all iterate the same map, so containers and disk drives
  come along for free; `BusNetwork.computerFor` is the reverse lookup a drive block uses (a computer it touches,
  else the nearest one on the run).
- **Networks as stored facts** (`BusRegistry.Net`; §9 U11, session 24). **A run is a connected component of
  cable and nothing else.** A `Net` holds its cable positions, the machines hanging off it, the `attachments`
  (positions with a block entity) and the `plain` ones (non-air, no block entity — a composter is a real
  inventory through Fabric's API with no block entity, so these still count). It is built by one walk
  (`build`, the only flood fill left in the mod) and read by lookup thereafter: `netsAt(pos)` returns the run a
  cable is on, or — for a block that merely *touches* cable — every run it touches, so a computer bridging two
  runs is on both rather than welding them into one. That last rule is load-bearing: seeding a walk from an
  arbitrary asking position made a **cut cable heal itself**, because the gap's own neighbours were the two
  severed ends. Runs are **not persisted** (they are derived from what is) and are discarded whenever a cable
  is placed or broken, a machine appears or goes, or a neighbour update actually changes what the registry
  believes — `noteNeighbourChanged` answers that in a handful of lookups, which is what stops a redstone clock
  beside one cable from re-flooding a 500-cable run every tick. `busRebuildSeconds` (60) re-walks a run anyway
  on a slow beat, as insurance against an edit that fires no block update. `/vmc bus` reports the counters.
- **Bridges** (`block/BridgeBlock`, `block/BridgeBlockEntity`, `item/PairedBridgeRecipe`; §9 U11): a block that
  joins the run it sits on to whatever run its partner sits on, however far away. All it holds is a **pair id**,
  stamped on both halves at craft time — `PairedBridgeRecipe` is an ordinary `ShapedRecipe` whose `assemble()`
  sets `virtualminecraft:bridge_pair` on an output stack of two, so both bridges share it and pairing is done
  before either is placed. `BusRegistry.reachableNets(pos)` walks the bridge graph (`busMaxBridgeHops`, 8;
  loops are harmless because a run is never visited twice), and `onRun` / `attachedOnRun` / `phantomsOnRun` all
  read that set — so **peers and components both cross**, which was [name]'s call. Bridged runs stay *separate*
  `Net` objects: that keeps each segment walked and capped on its own, and it is what lets the far side be
  entirely unloaded, since the partner's position is saved and the hop is a map lookup. A bridge never ticks and
  forwards nothing itself. **`BridgeBlockEntity` deliberately has no `setRemoved` override** — that fires on
  chunk *unload*, so forgetting the bridge there made it vanish whenever nobody was near it; removal is
  `BridgeBlock.affectNeighborsAfterRemoval` (a real break) plus verify-on-use. Cross-dimension pairs are not
  supported: the registry is per-dimension and `dx,dy,dz` component offsets are meaningless across levels.
- **The bus registry and demand-load** (`bus/BusRegistry`, `bus/BusWake`; §9 U9 + U11b): where the bus goes when
  nobody is looking. `BusRegistry` remembers, per dimension in `world/virtualminecraft/bus.json`, the **cables**,
  the **computers** (`pos, id, name`) and — since U11b — the **attachments** (positions beside a run's cables
  holding a block entity that is neither cable nor machine: the chests, drives, monitors). A loaded Computer
  records itself every five seconds (`LuaComputerBlockEntity.serverTick`; until session 23 that beat was
  accidentally inside the first-tick block and almost never fired) and that beat also rebuilds a run that was
  thrown away. Entries are *hints, verified on use* — a
  loaded position that no longer matches is dropped on the spot — so a stale entry is at worst a peer that does
  not answer, never a wrong delivery. On top of it: `BusWake` wakes a frozen machine a `send` is addressed to
  (radius-1 `FLAG_LOADING|FLAG_SIMULATION` ticket, `netWakeSeconds` lifetime, a queued delivery that waits for
  `busReady()`), and **demand-loads far components** for the bus call that wants them (`loadComponents`: a
  radius-0 `FLAG_LOADING`-only ticket per chunk plus the vanilla synchronous `level.getChunk`, renewed by
  `touchHold` while the run keeps being walked). `LuaComputer.busCall` is the consumer: `list` loads every
  remembered-but-away component, `call` resolves live first and loads only on a miss, and with
  `netWakeSeconds = 0` both **error naming the positions** rather than answering with a quietly shorter world.
  The VM tier's `VmBus` does not do this — VM-tier work is closed by decision.
- **Disk drives on cable**: `Attachment.driveLocation` is the bus location string (was a `Direction`), and
  `Attachments.cdId/floppyId` run it through `driveKey`, which passes side names through untouched and encodes an
  offset as `1,0,-3` → `1_0_m3` — **a comma in `-drive id=` would start a new QEMU option**. So `drive-west-fd`
  still names the same unit it always did, and a cable drive is `drive-1_0_2-cd`. Floppy units are still handed to
  the first two drives in the map's order (neighbours first), so unit numbering is stable as cable is added.
- **`WorldComponent`** (`type=world`, `location=self`): the read-only sensor — `getPosition`, `detect(side)`,
  `getBlock(dx,dy,dz)` (±`MAX_RANGE` 32), `getTime`, `getWeather`, `getBiome`, `getLight`, `getPlayers([radius])`
  (≤ 64, positions relative to the computer). Unloaded positions return `null` and **never load a chunk** — a guest
  polling coordinates must not be able to drag terrain into memory. Time comes from 26.2's new clock system
  (`Level.getDefaultClockTime()`; the old day-time counter is gone — see `world/clock`, `world/timeline`,
  `ServerClockManager`), so `time = ticks mod 24000` and `day = ticks / 24000`.
- **`SpeakerComponent`** (`type=speaker`, `location=self`): `playNote(instrument, note[, volume])` (note-block
  instruments, pitch `2^((note-12)/12)`), `playSound(id[, volume[, pitch]])` (registry lookup only — an arbitrary
  string would be an uncontrolled client-side resource lookup), `getInstruments`, `stop`. Volume is capped at 3.0
  and sounds go out on `SoundSource.RECORDS` (the "Jukebox/Note Blocks" slider), like note blocks.
- **`ChatComponent`** (`type=chat`, `location=self`): `say`, `send(player, text)`, `getPlayers`, `getRange`, and the
  `chat` **event** — `ServerMessageEvents.CHAT_MESSAGE` (registered once at mod init) walks the live instances and
  pushes `{player, message, distance}` to every subscribed guest whose computer is in range. Both directions are
  limited to `VmcConfig.chatRange` (32 blocks; -1 = server-wide) and the whole component disappears from `list` when
  `allowChat` is false. Outgoing text is prefixed with the computer's name and stripped of § and control characters,
  so a guest cannot forge a player's line or inject formatting.
- **`NetComponent`** (`type=net`, `location=self`, ROADMAP §9 U3 option C, session 11): computers on one bus talk to
  each other. *Peers* are every other `BusHost` among `attached()` — the six neighbours and the cable run, either
  tier — so there is no reach code and no network state of its own: the cable flood fill decides who is connected.
  `address()`, `list()` → `[{address, name, location}]` (nearest first), `send(to, message)` (`to` = a peer's machine
  id or its name, case-insensitive, first match) and `broadcast(message)` → the peer(s) get a **`net_message
  {from, sender, message}`** event through `BusHost.emitEvent` — which **thaws a frozen Computer** ("an event is a
  reason to exist again") and reaches a VM guest only if it subscribed. `message` is any JSON value (a Lua table
  arrives as a table), capped at `netMessageMaxBytes` (4096) once encoded; the sender is budgeted by
  `netMessagesPerMinute` (600, burst of a sixth; a broadcast costs one); `allowNet = false` removes the component.
  The field is `sender`, not `name`, because the event envelope's `name` is the event's own. Lua: `net.*` in
  `sys.lua`; the Terminal echoes `<sender> message` (tables JSON-encoded); the shell has `net`, `net send`, `net all`.
- **The wireless modem** (`block/ModemBlock`, `bus/Modems`, session 12): the same `net` API with a radius instead
  of a wire. A modem block on a machine's bus (touching it, or on its cable run) gives *that machine* wireless
  reach; `NetComponent.peers()` appends, after the bus peers, every machine served by a modem within
  `VmcConfig.modemRange` (64) blocks in the same dimension, located `"wireless"`, deduped by machine id — a peer
  reachable both ways is listed once, as the cable peer. `Modems.hostsOf(modem)` is the mirror of "own modems":
  the machines a modem touches plus every machine on the cable run it sits on, which keeps the relation symmetric
  (if A hears B, B hears A). Modems do **not** relay: a chain of three does not join the ends, deliberately —
  range is the only thing a player has to reason about.
  The registry (`Modems`) is a per-dimension set of positions that each modem notes on its first server tick;
  entries are *hints* and every lookup re-checks that the position is loaded and still a modem, dropping the ones
  that are not — the same rule as `screen/ScreenSources`, and for the same reason (`setRemoved` does not fire
  server-side on chunk demotion in 26.2). Right-clicking a modem reports its range, the modems it hears and how
  many machines that adds up to, because a radio that fails silently is the worst kind.
- **Rate limiting** (`bus/RateLimiter`): the components that reach *out* — at players or at other computers — are
  budgeted per computer — `speakerSoundsPerSecond` (8), `chatMessagesPerMinute` (20, as a small burst plus a slow
  refill) and `netMessagesPerMinute` (600, `netBudget()` on `BusHost`). The buckets
  live in `ComputerBlockEntity` (transient, so a reload forgives a spammer) and refill on **game time**, so an idle
  server does not hand out free tokens. Over budget is a normal `-32001 RATE_LIMITED` error the guest can back off on.
- **`BusHost.mediaChanged`** (session 12): a disk drive calls it *before* it emits `disk_inserted` /
  `disk_ejected`, and the Computer rebuilds its mount table there. The table is otherwise rebuilt on a timer
  (every 8 ticks in `LuaComputer.tick`), which is late enough that a program woken by the event looked and saw
  nothing — the ROM's "a CD went in, show its programs" did exactly that.
- **Security**: only whitelisted methods; no strings from the guest reach file paths or commands; per-tick
  request cap and bounded queues; line-size cap; the outward-facing components are budgeted and range-limited and
  their text is sanitised. Anything the guest can do, a player standing there could do.
- Tooling: `/vmc bus <pos>` prints connection state, counters, components (including cable-reached ones), outputs
  and inputs; `tools/bus.py` is the guest-side reference client (Python, no dependencies).

## Disks as items (`item/`, `block/DiskDrive*`, `vm/Attachment*`)

```
computer slots (3: hard drives / CDs)  ─┐
config ISO field                         ├─ Attachments.collect() ─► List<Attachment> (command-line order, bootIndex each)
adjacent disk-drive blocks (floppy / CD) ┘        │
                                                  ▼
       QemuLauncher.addDisks: -drive if=none,id=<id> … -device ide-hd|ide-cd|floppy,id=dev-<id>,bootindex=N
       running VM: drive block insert/eject ─► VmInstance.changeMedium(dev-<id>) ─► QMP blockdev-change-medium / eject
```

- **Items** (`DiskItem`, kinds `FLOPPY` / `CD` / `HARD_DRIVE`) carry the `virtualminecraft:disk` component
  (`DiskData{id, sizeMb, iso}`). Floppies and hard drives are a qcow2 at `<world>/virtualminecraft/items/<id>.qcow2`,
  created lazily by `VmInstance.startBlocking` (or by `changeMedium` for a hot-inserted floppy) — 1474560 bytes for
  a floppy, `sizeMb` for a hard drive; creative-tab items have no component until first inserted
  (`DiskItem.ensureData` assigns the UUID), so copies never share a file. A CD holds an ISO name/path resolved
  like the config field (`QemuLauncher.resolveIso`); `/vmc give cd <iso>` mints one, `/vmc give hdd [gb]` and
  `/vmc give floppy` the others. Files are never deleted by the mod (an item lost to lava leaves an orphan).
- **Where they go**: `ComputerBlockEntity.disks` (`NonNullList`, `DISK_SLOTS = 3`, persisted as `disks` with
  `ItemStack.OPTIONAL_CODEC`, in the update tag so the config screen lists them, dropped in `preRemoveSideEffects`).
  `ComputerBlock.useItemOn` inserts a hard drive/CD (floppies are refused), sneak + empty hand `useWithoutItem`
  ejects the last one; both only while the VM is not alive (`disksChangeable`: a SUSPENDED computer is force-stopped,
  i.e. its snapshot discarded, first). `DiskDriveBlockEntity.media` (one stack, `media` via the same codec) takes
  floppies/CDs at any time; `useWithoutItem` ejects into the player's inventory.
- **Attachments** (`Attachment` record: backend id, type HDD/CD/FLOPPY, file or null = empty unit, sizeBytes,
  readOnly, label, driveSide, bootIndex). `Attachments.collect` builds: `hd0` internal disk (if `diskGb > 0` or the
  file exists — `diskGb = 0` means no internal disk), `slotN` per computer slot, `iso` for the config field, and per
  adjacent drive block (Direction order) `drive-<side>-cd` plus, for the first two, `drive-<side>-fd`. Boot order:
  `bootFromCd` ("removable 1st") = iso → drive units → slots → internal, else internal → slots → iso → drive units.
  Command-line order puts `hd0` first so `savevm` writes the RAM into the internal disk, not into a carried item.
- **QEMU side** (`QemuLauncher.addDisks`): every device is `-drive if=none,id=X` + `-device …,id=dev-X`; SATA
  devices on `ide.0–5`, then a second `ahci,id=vmcahci` for more; `-device isa-fdc,id=fdc0,fallback=144,
  bootindexA/B=` carries the floppy boot indices (the `floppy` device has no `bootindex` property; `fallback=144`
  keeps an empty unit a 1.44 MB drive). Empty units are `-drive if=none,id=X` with no file (`media=cdrom` for CDs).
  `-boot menu=on` only, no `order=`.
- **Hot-swap**: `VmInstance.hasDevice(devId)` says whether the unit existed at launch (a drive placed later needs a
  restart — QEMU cannot add controller ports live); `changeMedium` runs `eject {id, force}` or
  `blockdev-change-medium {id, filename, format, read-only-mode}` on a virtual thread. **Always pass
  `read-only-mode` explicitly** (`read-write` for qcow2 media): with the default `retain` a floppy unit that was
  empty or ejected comes back unreadable to the guest ("floppy: error 10 while reading block 0"), while the same
  file inserted with `read-write` reads fine (found 2026-08-24). CDs use `raw` + `read-only`.
- **Suspend interplay**: `savevm` snapshots every writable qcow2 attached, so the marker JSON lists them (`files`)
  and `VmInstance.discardSnapshotFiles` removes the `vmc` tag from each. A writable medium that is not qcow2 (never
  produced by the mod; seen once with a raw test image) makes `savevm` fail → server stop force-stops the VM.
- **"BIOS" screen**: `VmManager.start` refuses to launch when no attachment has a medium and instead calls
  `ComputerBlockEntity.showFirmware`, which draws a blue text-mode page (title, name, RAM/CPUs, boot list, reason)
  on every linked monitor via the same `TextGrid` path the bus `screen` component uses; `setStatus(RUNNING)` turns
  text mode off again (`hideFirmware`).
- **Bus**: `DriveComponent` (`type=drive`, `location=<side>`): `hasMedia()`, `getMedia()` → `{kind, description,
  serial|iso}`, `eject()` (pops the item on the ground); events `disk_inserted` / `disk_ejected`
  `{address, side, kind, description, serial?}` from the drive block entity.

## Dev-only

`client/dev/Puppet` (`-Dvirtualminecraft.puppet=<port>`) and `-Dvirtualminecraft.debugOpenScreen=x,y,z` —
inert unless the property is set. See TESTING.md.
