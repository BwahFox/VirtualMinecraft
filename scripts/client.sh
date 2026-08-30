#!/usr/bin/env bash
# Launch the dev client (Minecraft with the mod from source, puppet remote control on 25597).
#   scripts/client.sh                     -> to the title screen
#   scripts/client.sh vmctest             -> straight into that singleplayer world, if run/saves/vmctest exists
# VMC_WINDOW="--width 2560 --height 1440" adds a window size (guest text must be legible in screenshots).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d /usr/lib/jvm/java-25-openjdk ] && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk

args="${VMC_WINDOW:-}"
if [ -n "${1:-}" ]; then
  if [ -d "run/saves/$1" ]; then
    args="--quickPlaySingleplayer $1 $args"
  else
    echo "no world run/saves/$1 here (the saves are not in git); starting at the title screen" >&2
  fi
fi
# :runClient qualified: bare runClient also matches :vr:runClient, which fights this one for the world
exec ./gradlew :runClient -Dvirtualminecraft.puppet=25597 --args="$args"
