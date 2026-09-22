#!/usr/bin/env bash
# What a CANDIDATE game build would do to the 49 repairs this tree carries.
#
# Every repair is calibrated against one pair of carrier versions, so "fixed" has a half-life. The 26.2.0.38-beta
# -> .88 bump gave RegistryDataLoader.load a fifth parameter; fabric-api's WorldLoaderMixin stopped replacing the
# list, and every datapack registry a Fabric mod declared silently vanished. Nothing measured that. The staged
# test proves the repairs land on the base that is HERE, which says nothing about the one that is coming.
#
# Run this before adopting a carrier bump, not after a player reports it.
#
# Usage: ./repair-drift.sh <candidate-merged-base.jar> [<carrier.jar> ...]
#        ./repair-drift.sh                     # the currently staged build, as a baseline
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/../.." && pwd)"
OLD="${FORBRIC_OLD:-$(cd "$KERNEL/../forbric-loader" && pwd)}"
RUN_OLD="$OLD/run"

if [ "$#" -eq 0 ]; then
  set -- "$RUN_OLD/merged-base/patched-mc-merged-26.2.jar" \
         "$RUN_OLD/neoforge-runtime/neoforge-runtime.jar" \
         "$RUN_OLD/forge-runtime/forge-runtime.jar"
fi
for f in "$@"; do
  [ -f "$f" ] || { echo "not a file: $f" >&2; exit 3; }
done

cd "$KERNEL"
./gradlew --offline -q jar || exit 3
CP="$(./gradlew --offline -q printBootClasspath 2>/dev/null | tail -1)"
JAR="$(find build/libs -name '*.jar' ! -name '*sources*' | head -1)"

# The transformer narrates every repair it applies; that is its job at boot and noise here. The verdict lines
# are the ones prefixed RepairDrift, and a script asserting on this greps THOSE.
java -cp "$JAR:$CP" net.forbric.kernel.transform.RepairDriftCensus "$@" 2>&1 \
  | grep -E 'RepairDrift|DECLINED|ABSENT'
exit "${PIPESTATUS[0]}"
