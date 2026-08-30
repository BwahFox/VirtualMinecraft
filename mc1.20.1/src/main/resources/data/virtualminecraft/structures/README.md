# The software store piece goes here

**`software_store.nbt` is here** (2026-08-28): [name]'s build, cut straight out of her void world with
`tools/world_to_structure.py <world>` (no structure block needed — `--scan` first to see what is there), then
`tools/structure_loot.py` for the chests. Rebuild it the same way after she edits the building — or just `scripts/store.sh`, which does all of it: cut,
chests, the two **jigsaw blocks** (`tools/store_jigsaws.py`: the entrance at the door step, the villager socket under the
carpet — what makes it a village house), build, install. The cash register inside
is the clerk's workstation; a villager that finds it becomes a Clerk. (It was a fletching table in [name]'s world — the
palette was swapped in the file; put a real cash register in the world before the next re-cut.)

Build it in creative, then save it with a **structure block** whose name is `virtualminecraft:software_store`
(Save mode; include entities off). Minecraft writes it to
`<that world>/generated/virtualminecraft/structures/software_store.nbt` — copy that file **here**, as
`software_store.nbt`, and rebuild (`scripts/build.sh install`). The structure spawns in village biomes
(`worldgen/structure_set/software_store.json` sets how rare; `spacing 40 / separation 12` is roughly one per
40 chunks), on the surface, and never within 6 chunks of a village.

Chests: leave them plain. After copying the file here, run
`python3 tools/structure_loot.py src/main/resources/data/virtualminecraft/structure/software_store.nbt` — it empties
every chest, barrel and shulker box in the piece and tags it with `virtualminecraft:chests/software`, so each placed
copy fills with program CDs (`loot_table/chests/software.json`) the first time someone opens it. Leave the shop's
shelves as blocks — a computer or a drive in the piece would copy its identity into every store (see ROADMAP §9
U3c step 2).
