#!/usr/bin/env bash
# Build the mod jar. `scripts/build.sh install` also copies it into the Prism instance's mods folder.
#   scripts/build.sh                      -> build/libs/virtualminecraft-<ver>.jar
#   scripts/build.sh install              -> ...and copy it into $VMC_MODS (default: the Prism instance that already
#                                            has a virtualminecraft-*.jar, i.e. the one you play on)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d /usr/lib/jvm/java-25-openjdk ] && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk

./gradlew build -q
jar=$(ls -t build/libs/virtualminecraft-*.jar | grep -v -- '-sources' | head -1)
echo "built $jar"

if [ "${1:-}" = "install" ]; then
  mods="${VMC_MODS:-}"
  if [ -z "$mods" ]; then
    # the instance that already has one of our jars is the one to update
    old=$(ls ~/.local/share/PrismLauncher/instances/*/minecraft/mods/virtualminecraft-*.jar 2>/dev/null | head -1 || true)
    [ -n "$old" ] && mods=$(dirname "$old")
  fi
  if [ -z "$mods" ]; then
    echo "no Prism instance with a virtualminecraft jar found; set VMC_MODS=/path/to/instance/minecraft/mods" >&2
    exit 1
  fi
  rm -f "$mods"/virtualminecraft-*.jar
  cp "$jar" "$mods/"
  echo "installed into $mods"
fi
