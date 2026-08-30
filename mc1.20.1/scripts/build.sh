#!/usr/bin/env bash
# Build the 1.20.1 jars. `scripts/build.sh install` also copies the main jar into a Prism instance's mods folder.
#   mc1.20.1/scripts/build.sh                 -> mc1.20.1/build/libs/virtualminecraft-<ver>+mc1.20.1.jar (+ vr/build/libs)
#   mc1.20.1/scripts/build.sh install         -> ...and copy it into $VMC_MODS (no default here: the 26.2 script's
#                                                "the instance that already has our jar" rule would find the 26.2 one)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -d /usr/lib/jvm/java-25-openjdk ] && export JAVA_HOME=/usr/lib/jvm/java-25-openjdk

./gradlew build -q
jar=$(ls -t build/libs/virtualminecraft-*+mc1.20.1.jar | grep -v -- '-sources' | head -1)
echo "built $jar"
ls vr/build/libs/virtualminecraft-vr-*+mc1.20.1.jar 2>/dev/null | grep -v -- '-sources' | sed 's/^/built /' || true

if [ "${1:-}" = "install" ]; then
  mods="${VMC_MODS:-}"
  if [ -z "$mods" ]; then
    echo "set VMC_MODS=/path/to/<1.20.1 instance>/minecraft/mods" >&2
    exit 1
  fi
  rm -f "$mods"/virtualminecraft-*.jar
  cp "$jar" "$mods/"
  echo "installed into $mods"
fi
