#!/usr/bin/env python3
"""Give every chest in a structure .nbt a loot table, so a piece built with plain chests fills itself when placed.

    python3 tools/structure_loot.py <in.nbt> [out.nbt] [--table virtualminecraft:chests/software] [--keep-items]

A structure block saves a chest with whatever was in it; this empties it and sets `LootTable`, which is what
makes a placed copy roll the table on first open. Barrels and shulker boxes count as chests here. No library:
the NBT reader/writer below is the whole format (gzip, big-endian, thirteen tag types).
"""
import gzip
import struct
import sys

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Reader:
    def __init__(self, data):
        self.d, self.p = data, 0

    def take(self, fmt):
        v = struct.unpack_from(">" + fmt, self.d, self.p)
        self.p += struct.calcsize(">" + fmt)
        return v[0] if len(v) == 1 else v

    def string(self):
        n = self.take("H")
        s = self.d[self.p:self.p + n].decode("utf-8", "replace")
        self.p += n
        return s

    def payload(self, t):
        if t == BYTE: return self.take("b")
        if t == SHORT: return self.take("h")
        if t == INT: return self.take("i")
        if t == LONG: return self.take("q")
        if t == FLOAT: return self.take("f")
        if t == DOUBLE: return self.take("d")
        if t == BYTE_ARRAY:
            n = self.take("i"); v = self.d[self.p:self.p + n]; self.p += n; return ("ba", bytes(v))
        if t == STRING: return self.string()
        if t == LIST:
            et, n = self.take("b"), self.take("i")
            return ("list", et, [self.payload(et) for _ in range(n)])
        if t == COMPOUND:
            out = {}
            while True:
                t2 = self.take("b")
                if t2 == END: return out
                name = self.string()
                out[name] = (t2, self.payload(t2))
        if t == INT_ARRAY:
            n = self.take("i"); return ("ia", list(self.take(f"{n}i") if n != 1 else (self.take("i"),)))
        if t == LONG_ARRAY:
            n = self.take("i"); return ("la", list(self.take(f"{n}q") if n != 1 else (self.take("q"),)))
        raise ValueError(f"bad tag {t}")


class Writer:
    def __init__(self):
        self.out = bytearray()

    def put(self, fmt, *v):
        self.out += struct.pack(">" + fmt, *v)

    def string(self, s):
        b = s.encode("utf-8")
        self.put("H", len(b))
        self.out += b

    def payload(self, t, v):
        if t == BYTE: self.put("b", v)
        elif t == SHORT: self.put("h", v)
        elif t == INT: self.put("i", v)
        elif t == LONG: self.put("q", v)
        elif t == FLOAT: self.put("f", v)
        elif t == DOUBLE: self.put("d", v)
        elif t == BYTE_ARRAY: self.put("i", len(v[1])); self.out += v[1]
        elif t == STRING: self.string(v)
        elif t == LIST:
            _, et, items = v
            self.put("b", et); self.put("i", len(items))
            for it in items: self.payload(et, it)
        elif t == COMPOUND:
            for name, (t2, v2) in v.items():
                self.put("b", t2); self.string(name); self.payload(t2, v2)
            self.put("b", END)
        elif t == INT_ARRAY: self.put("i", len(v[1])); self.put(f"{len(v[1])}i", *v[1])
        elif t == LONG_ARRAY: self.put("i", len(v[1])); self.put(f"{len(v[1])}q", *v[1])
        else: raise ValueError(f"bad tag {t}")


def load(path):
    r = Reader(gzip.open(path, "rb").read())
    t = r.take("b")
    assert t == COMPOUND, "not an NBT file"
    name = r.string()
    return name, r.payload(COMPOUND)


def save(path, name, root):
    w = Writer()
    w.put("b", COMPOUND); w.string(name); w.payload(COMPOUND, root)
    with gzip.open(path, "wb") as f:
        f.write(bytes(w.out))


CONTAINERS = {"minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:shulker_box"}


def main(argv):
    args = [a for a in argv if not a.startswith("--")]
    table = "virtualminecraft:chests/software"
    keep = "--keep-items" in argv
    for i, a in enumerate(argv):
        if a == "--table": table = argv[i + 1]; args.remove(table) if table in args else None
    if not args:
        print(__doc__); return 2
    src = args[0]
    dst = args[1] if len(args) > 1 else src
    name, root = load(src)
    blocks = root["blocks"][1][2]
    # a piece saved by a structure block has one palette; vanilla pieces with random palettes carry `palettes`,
    # a list of them with the same block ids in the same order, so the first says what every block is
    palette = root["palette"][1][2] if "palette" in root else root["palettes"][1][2][0][2]
    tagged = 0
    for b in blocks:
        state = palette[b["state"][1]]
        block_id = state["Name"][1]
        nbt = b.get("nbt")
        if block_id in CONTAINERS or (nbt and nbt[1].get("id", (0, ""))[1] in CONTAINERS):
            if nbt is None:
                b["nbt"] = (COMPOUND, {"id": (STRING, block_id)})
                nbt = b["nbt"]
            c = nbt[1]
            c["LootTable"] = (STRING, table)
            c.pop("LootTableSeed", None)      # a seed pins the roll: every placed copy would get the same CDs
            if not keep:
                c.pop("Items", None)
            tagged += 1
    save(dst, name, root)
    print(f"{src}: {len(blocks)} blocks, {tagged} container(s) now roll {table} -> {dst}")
    return 0 if tagged else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
