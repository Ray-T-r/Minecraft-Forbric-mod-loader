#!/usr/bin/env bash
# Which dead hooks a real modpack is actually waiting on, most-waited-on first.
#
# The census says 140 of ForgeEventFactory's 160 hooks have no call site. True, and nearly useless on its own:
# repairing them in census order is repairing them in an order the byte-merge chose. This joins the dead set to
# the jars that NAME each event, so the list is ordered by how many mods notice.
#
# Usage: [MODS=<dir>] [FORBRIC_OLD=<loader-tree>] ./hook-worklist.sh
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/../.." && pwd)"
OLD="${FORBRIC_OLD:-$(cd "$KERNEL/../forbric-loader" && pwd)}"
RUN_OLD="$OLD/run"
MODS="${MODS:-$KERNEL/run/client-merged-pack/mods}"

BASE="$RUN_OLD/merged-base/patched-mc-merged-26.2.jar"
FORGE_RT="$RUN_OLD/forge-runtime/forge-runtime.jar"
NEO_RT="$RUN_OLD/neoforge-runtime/neoforge-runtime.jar"
for f in "$BASE" "$FORGE_RT"; do
  [ -f "$f" ] || { echo "staged artifact not found: $f (run $RUN_OLD/build-merged-base.sh first)" >&2; exit 3; }
done
[ -d "$MODS" ] || { echo "no mods directory: $MODS (set MODS=<dir>)" >&2; exit 3; }

cd "$KERNEL"
./gradlew --offline -q jar || exit 3
CP="$(./gradlew --offline -q printBootClasspath 2>/dev/null | tail -1)"
JAR="$(find build/libs -name '*.jar' ! -name '*sources*' | head -1)"

ARGS=("$FORGE_RT=net/minecraftforge/event/ForgeEventFactory"
      "$FORGE_RT=net/minecraftforge/client/event/ForgeEventFactoryClient")
[ -f "$NEO_RT" ] && ARGS+=("$NEO_RT=net/neoforged/neoforge/event/EventHooks")

exec java -cp "$JAR:$CP" net.forbric.kernel.boot.DeadHookWorklist "$BASE" "$MODS" "${ARGS[@]}"
