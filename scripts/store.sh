#!/usr/bin/env bash
# Re-cut the software store from [name]'s build world and make it a village house again: extract, tag the chests,
# place the jigsaws, build and install.   scripts/store.sh [world dir]
set -euo pipefail
cd "$(dirname "$0")/.."
W="${1:-$HOME/.local/share/PrismLauncher/instances/26.2/minecraft/saves/villager_computer_market}"
S=src/main/resources/data/virtualminecraft/structure/software_store.nbt
python3 tools/world_to_structure.py "$W" "$S"
python3 tools/structure_loot.py "$S"
python3 tools/store_jigsaws.py "$S"
scripts/build.sh install
