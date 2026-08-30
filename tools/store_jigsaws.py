#!/usr/bin/env python3
"""Put the two jigsaw blocks a village house needs into the software store piece -- run after every re-cut.

    python3 tools/store_jigsaws.py [piece.nbt]

The entrance jigsaw replaces the pressure plate outside the door (local 4,1,7, facing south; the plate is its
final state, so it comes back) and hooks the store onto a village street; the socket under the carpet (local 3,0,3,
pointing up) pulls a villager from the village's villager pool, the way every vanilla house gets one. The positions
are the store as [name] built it on 2026-08-28; if the building changes shape, change them here.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import structure_loot as sl  # noqa: E402

ENTRANCE = (4, 1, 7)
SOCKET = (3, 0, 3)


def main(path):
    name, root = sl.load(path)
    pal = root["palette"][1][2]
    blocks = root["blocks"][1][2]

    def state(props):
        pal.append({"Name": (sl.STRING, "minecraft:jigsaw"), "Properties": (sl.COMPOUND, {k: (sl.STRING, v) for k, v in props.items()})})
        return len(pal) - 1

    def jig(n, pool, final, joint):
        return (sl.COMPOUND, {"id": (sl.STRING, "minecraft:jigsaw"), "name": (sl.STRING, n), "target": (sl.STRING, n),
                              "pool": (sl.STRING, pool), "final_state": (sl.STRING, final), "joint": (sl.STRING, joint)})

    entrance, socket = state({"orientation": "south_up"}), state({"orientation": "up_north"})
    done = 0
    for b in blocks:
        p = tuple(b["pos"][1][2])
        if p == ENTRANCE:
            b["state"] = (sl.INT, entrance)
            b["nbt"] = jig("minecraft:building_entrance", "minecraft:village/plains/streets", "minecraft:heavy_weighted_pressure_plate", "aligned")
            done += 1
        elif p == SOCKET:
            b["state"] = (sl.INT, socket)
            b["nbt"] = jig("minecraft:bottom", "minecraft:village/plains/villagers", "minecraft:stone_bricks", "rollable")
            done += 1
    sl.save(path, name, root)
    print(f"{path}: {done} jigsaw block(s) placed")
    return 0 if done == 2 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/data/virtualminecraft/structure/software_store.nbt"))
