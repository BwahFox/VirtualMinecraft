#!/usr/bin/env bash
# Run the Computer emulator (the real ROM in a desktop window, no Minecraft needed).
#   scripts/emulator.sh                   -> a 2x2 wall (512x512) on a tier-3 case, window scaled 2x, disk in run/emulator
#   scripts/emulator.sh maze              -> the same with the Maze CD in the drive (any shipped CD name, or a path)
#   scripts/emulator.sh pinball --size 256x256 --fresh   -> extra flags go straight to the emulator (--help lists them)
# The window is resizable; the screen scales to it.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d /usr/lib/jvm/java-25-openjdk ] && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk

args="--size 512x512 --tier 3 --scale 2 --dir run/emulator"
if [ $# -gt 0 ] && [ "${1#-}" = "$1" ]; then
  args="$args --cd $1"
  shift
fi
exec ./gradlew computerEmulator -q --args="$args $*"
