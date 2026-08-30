# VirtualMinecraft for Minecraft 1.20.1

The 1.20.1 backport of the mod in the parent directory. **Same mod, same features, same ROM, same software** —
only the thin Minecraft-facing shell is different, because 1.20.1's API is. The 26.2 build in `..` is the real
thing and does not know this directory exists.

## Build

```sh
cd mc1.20.1
./gradlew build                 # build/libs/virtualminecraft-<ver>+mc1.20.1.jar and vr/build/libs/virtualminecraft-vr-…
scripts/build.sh                # the same, and `scripts/build.sh install` with VMC_MODS=… copies the jar into an instance
scripts/client.sh vmctest       # the dev client into mc1.20.1/run/saves/vmctest (puppet on 25597, like the 26.2 script)
./gradlew runServer             # the dev server in mc1.20.1/run/server (RCON on 25598, password vmctest)
```

The jar needs **Fabric Loader 0.16+, Fabric API 0.92.x for 1.20.1, and Java 17 or newer** — 1.20.1's own
minimum, so it drops into any 1.20.1 modpack (since 2026-08-30; it was 21 for the first day). The class files
are Java 17 (`--release 17`, so nothing newer can leak in by accident); the shared 26.2 sources stay 17-clean
through two small helpers in `src/main/java/dev/virtualminecraft/util/`: `Nums.clamp` (Java 21's `Math.clamp`,
same four overloads, same semantics) and `Threads` (`Thread.ofVirtual` became named daemon platform threads,
`threadId()` is `getId()` behind one `@SuppressWarnings`). Keep it that way: new shared code uses `Nums` /
`Threads`, and the 1.20.1 build is the check — it will not compile a 21-only call.

The QEMU D-Bus display link (`display = auto`) needs Java 22+ on top of that, because it is written against
`java.lang.foreign`. `dbus/Libc` is compiled separately for 22 and only ever loaded behind
`dev.virtualminecraft.dbus.Ffm.available()`, which checks the running JVM first; on Java 17–21 the VM tier simply
uses VNC for the keyboard, exactly as it does on Windows.

## How the two builds share code

```
../src/<set>/<kind>/…      the 26.2 tree: everything with no Minecraft in it is compiled from HERE, unchanged
mc1.20.1/src/<set>/<kind>/… the 1.20.1 versions of the Minecraft-facing files
```

A file under `mc1.20.1/src` **shadows** the file at the same relative path under `../src`: the build syncs
`../src` into `build/shared/` minus every shadowed path and compiles both. To port a file, put its 1.20.1 version
at the same path here; to un-port one, delete it. Nothing is copied by hand and nothing here writes into `..`.

Resources work the same way, except for whole directories whose 1.20.1 *format* differs and are rebuilt here from
the 26.2 files (a Python step, run once; re-run it when the 26.2 data changes):

| 26.2 | 1.20.1 |
|---|---|
| `data/…/recipe/` (string ingredients, `result.id`) | `data/…/recipes/` (`{"item":…}` ingredients, `result.item`) |
| `data/…/loot_table/` (`set_components`, `copy_components`) | `data/…/loot_tables/` (`set_nbt`, `copy_nbt`) |
| `data/…/structure/` | `data/…/structures/` (same NBT, `DataVersion` stamped 1.20.1) |
| `data/…/villager_trade/`, `trade_set/`, `tags/villager_trade/` | code: `ClerkTrades.java` (1.20.1 has no data-driven trades) |
| `assets/…/items/` (1.21.4 item-model definitions) | `assets/…/models/item/<block>.json` |
| `tags/block/` | `tags/blocks/` |
| `lang/en_us.json` | the same file plus `lang-extra/` (`entity.minecraft.villager.clerk`), merged at build time |

Item data that is a **data component** on 26.2 (`virtualminecraft:disk`, `computer_id`, `bridge_pair`,
`computer_label`, `computer_mem_mb`) is plain NBT on the stack here — `disk`, `computer_id`, `bridge_pair`,
`computer_label`, `computer_mem_mb` — through `item/StackData.java`.

## What is genuinely different in play

- Java 17+ (see above); the D-Bus link wants 22+.
- The full-screen view gets no horizontal scroll wheel: 1.20.1 only delivers vertical scrolling to a screen.
- The case GUI draws its four slots itself (1.20.1 has no slot sprite); it looks the same.
- Everything else — blocks, the bus, the Computer and its ROM, disks, CDs, the store, the clerk's trades, the
  manual book, bridges, modems, VR (the same Vivecraft 1.3.15, which also builds for 1.20.1) — is the 26.2 code
  or a line-for-line port of it.

## Checking it

Before a release, check the *built* jar — the dev client runs under named mappings and cannot show remap
breakage (1.0.1 shipped with every Computer crashing on tick one because of exactly that; TESTING has the story):

```sh
tools/check-remap-collisions.py mc1.20.1/build/libs/virtualminecraft-<ver>+mc1.20.1.jar   # from the repo root
```

then launch the jar in a real 1.20.1 instance and open a Computer. The rule behind it: an interface of ours must
not declare a method whose name and signature a Minecraft superclass of an implementor already has (`getBlockPos`,
`getLevel`, …) — the remap renames the superclass's, not the interface's, and nothing implements it any more.

The eight harnesses of the 26.2 build run here too, against the Java 17 compile of the shared code:

```sh
./gradlew luaMachineTest schedulerTest screenDeviceTest machineFilesTest chassisVoiceTest soundChipTest blockShapeTest romBootTest --continue
```

`blockShapeTest` is the one harness with a 1.20.1 version of its own: this game's `PipeBlock` takes the cable's
half-width as a *fraction* of a block where 26.2's takes pixels, so `BusCableBlock.APOTHEM` exists here and the
test checks the shape still lands on the model's 5..11 core.
