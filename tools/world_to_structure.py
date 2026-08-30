#!/usr/bin/env python3
"""Cut a structure piece straight out of a world's region files -- for a building made in a world with nothing
else in it, when no structure block was used.

    python3 tools/world_to_structure.py <world dir> --scan
        what is in the world: blocks per y level (the flat ground layers stand out), the bounding box of
        everything else, and the block entities
    python3 tools/world_to_structure.py <world dir> <out.nbt> [--ground Y] [--box x1,y1,z1,x2,y2,z2]
        write everything above --ground (the top ground layer's y; found by --scan) as a structure .nbt,
        air inside the box included, block entities carried along

Uses the NBT reader/writer in structure_loot.py. Chunks are read from dimensions/minecraft/overworld/region
(26.2's layout) or region/ (older). Entities (a villager) are NOT copied -- add those in game.
"""
import glob
import os
import struct
import sys
import zlib
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import structure_loot as nbt  # noqa: E402

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


def region_files(world):
    for rel in ("dimensions/minecraft/overworld/region", "region"):
        files = glob.glob(os.path.join(world, rel, "r.*.mca"))
        if files:
            return files
    return []


def chunks(path):
    """Yield (cx, cz, root compound) for every chunk stored in one region file."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 8192:
        return
    base = os.path.basename(path).split(".")
    rx, rz = int(base[1]), int(base[2])
    for i in range(1024):
        off = struct.unpack_from(">I", data, i * 4)[0]
        sectors, count = off >> 8, off & 0xFF
        if sectors == 0 or count == 0:
            continue
        p = sectors * 4096
        length = struct.unpack_from(">I", data, p)[0]
        kind = data[p + 4]
        raw = data[p + 5:p + 4 + length]
        if kind == 2:
            raw = zlib.decompress(raw)
        elif kind == 1:
            import gzip
            raw = gzip.decompress(raw)
        elif kind == 3:
            pass
        else:
            raise ValueError(f"chunk compression {kind} (lz4?) not supported")
        r = nbt.Reader(raw)
        assert r.take("b") == nbt.COMPOUND
        r.string()
        root = r.payload(nbt.COMPOUND)
        cx, cz = rx * 32 + (i % 32), rz * 32 + (i // 32)
        yield cx, cz, root


def section_blocks(sec):
    """Yield (x, y, z, palette entry compound) for the 4096 blocks of one section, local coordinates."""
    bs = sec.get("block_states")
    if not bs:
        return
    pal = bs[1]["palette"][1][2]
    if "data" not in bs[1]:
        entry = pal[0]
        if entry["Name"][1] in AIR:
            return
        for i in range(4096):
            yield i & 15, i >> 8, (i >> 4) & 15, entry
        return
    longs = bs[1]["data"][1][1]
    bits = max(4, (len(pal) - 1).bit_length())
    per = 64 // bits
    mask = (1 << bits) - 1
    i = 0
    for L in longs:
        L &= (1 << 64) - 1
        for k in range(per):
            if i >= 4096:
                return
            entry = pal[(L >> (k * bits)) & mask]
            if entry["Name"][1] not in AIR:
                yield i & 15, i >> 8, (i >> 4) & 15, entry
            i += 1


def read_world(world):
    """All non-air blocks: {(x, y, z): palette entry}, plus block entities by position."""
    blocks = {}
    bes = {}
    files = region_files(world)
    if not files:
        sys.exit("no region files under " + world)
    for f in files:
        for cx, cz, root in chunks(f):
            for sec in root.get("sections", (0, ("list", 0, [])))[1][2]:
                sy = sec["Y"][1]
                for lx, ly, lz, entry in section_blocks(sec):
                    blocks[(cx * 16 + lx, sy * 16 + ly, cz * 16 + lz)] = entry
            for be in root.get("block_entities", (0, ("list", 0, [])))[1][2]:
                bes[(be["x"][1], be["y"][1], be["z"][1])] = be
    return blocks, bes


def scan(world):
    blocks, bes = read_world(world)
    per_y = defaultdict(Counter)
    for (x, y, z), e in blocks.items():
        per_y[y][e["Name"][1]] += 1
    print(f"{len(blocks)} non-air blocks, {len(bes)} block entities")
    ground_top = None
    for y in sorted(per_y):
        c = per_y[y]
        total = sum(c.values())
        top = c.most_common(1)[0]
        full = top[1] == total and total >= 4096  # one block type over at least 16 chunks: a flat layer
        print(f"  y={y:4d}: {total:6d} blocks  {dict(c.most_common(4))}{'   <- flat ground layer' if full else ''}")
        if full:
            ground_top = y
    above = [(x, y, z) for (x, y, z) in blocks if ground_top is None or y > ground_top]
    if above:
        xs, ys, zs = zip(*above)
        print(f"ground top y={ground_top}; above it: {len(above)} blocks in x {min(xs)}..{max(xs)}  y {min(ys)}..{max(ys)}  z {min(zs)}..{max(zs)}")
    for (x, y, z), be in sorted(bes.items()):
        print(f"  block entity {be['id'][1]} at {x} {y} {z}")


def extract(world, out, ground, box):
    blocks, bes = read_world(world)
    keep = {p: e for p, e in blocks.items() if ground is None or p[1] > ground}
    if box:
        x1, y1, z1, x2, y2, z2 = box
        keep = {p: e for p, e in keep.items() if x1 <= p[0] <= x2 and y1 <= p[1] <= y2 and z1 <= p[2] <= z2}
    if not keep:
        sys.exit("nothing to save")
    xs, ys, zs = zip(*keep)
    x0, y0, z0 = min(xs), min(ys), min(zs)
    sx, sy, sz = max(xs) - x0 + 1, max(ys) - y0 + 1, max(zs) - z0 + 1
    palette = []
    index = {}

    def state_of(entry):
        key = nbt.Writer()
        key.payload(nbt.COMPOUND, entry)
        k = bytes(key.out)
        if k not in index:
            index[k] = len(palette)
            palette.append(entry)
        return index[k]

    air = {"Name": (nbt.STRING, "minecraft:air")}
    out_blocks = []
    for x in range(sx):
        for y in range(sy):
            for z in range(sz):
                p = (x0 + x, y0 + y, z0 + z)
                entry = keep.get(p, air)
                b = {"pos": (nbt.LIST, ("list", nbt.INT, [x, y, z])), "state": (nbt.INT, state_of(entry))}
                be = bes.get(p)
                if be is not None and p in keep:
                    c = dict(be)
                    for k in ("x", "y", "z", "keepPacked"):
                        c.pop(k, None)
                    b["nbt"] = (nbt.COMPOUND, c)
                out_blocks.append(b)
    root = {
        "size": (nbt.LIST, ("list", nbt.INT, [sx, sy, sz])),
        "palette": (nbt.LIST, ("list", nbt.COMPOUND, palette)),
        "blocks": (nbt.LIST, ("list", nbt.COMPOUND, out_blocks)),
        "entities": (nbt.LIST, ("list", nbt.COMPOUND, [])),
        "DataVersion": (nbt.INT, data_version(world)),
    }
    nbt.save(out, "", root)
    print(f"wrote {out}: {sx}x{sy}x{sz} from ({x0},{y0},{z0}), {len(keep)} blocks + air, {len(palette)} palette entries, "
          f"{sum(1 for b in out_blocks if 'nbt' in b)} block entities")


def data_version(world):
    _, root = nbt.load(os.path.join(world, "level.dat"))
    return root["Data"][1]["DataVersion"][1]


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        print(__doc__)
        sys.exit(2)
    world = a[0]
    if "--scan" in a:
        scan(world)
    else:
        ground = int(a[a.index("--ground") + 1]) if "--ground" in a else None
        box = [int(v) for v in a[a.index("--box") + 1].split(",")] if "--box" in a else None
        extract(world, a[1], ground, box)
