#!/usr/bin/env bash
# Builds the Fabric canary mod used by gate-m2.sh.
#
# There is no pre-existing Fabric canary in this tree (forbriclive.jar / forbricneolive.jar are the Forge and
# NeoForge canaries — they carry a mods.toml, not a fabric.mod.json). This compiles one against the merged base
# (for net.minecraft.*) and the kernel's vendored Fabric API (for net.fabricmc.*), then packages it with its
# JiJ-nested library so the gate exercises nested-jar extraction too.
#
# Output: run/canary/forbricfabriclive.jar  (contains META-INF/jars/forbricfabriclib.jar)
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SRC="$KERNEL/canary/fabric"
OUT="$KERNEL/run/canary"
WORK="$BUILD/canary-fabric"
MERGED="$OLD/run/merged-base/patched-mc-merged-26.2.jar"

step "prerequisites"
if [ ! -f "$MERGED" ]; then
  echo "[kernel] FAIL merged base absent: $MERGED"
  echo "[kernel]      run $RUN_OLD/build-merged-base.sh first"
  exit 1
fi

mkdir -p "$BUILD"
kernel_jar
KERNEL_JAR="$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar"
if [ ! -f "$KERNEL_JAR" ]; then echo "[kernel] FAIL kernel jar not built"; exit 1; fi
echo "[kernel] merged base + kernel jar present"

rm -rf "$WORK" "$OUT/forbricfabriclive.jar"
mkdir -p "$WORK/lib/classes" "$WORK/live/classes" "$OUT"

step "compile the JiJ-nested library mod (forbricfabriclib)"
# The nested lib only needs the Fabric API surface, not the game.
javac -nowarn -proc:none --release 21 \
      -cp "$KERNEL_JAR" \
      -d "$WORK/lib/classes" \
      $(find "$SRC/forbricfabriclib/src" -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -n "$(find "$WORK/lib/classes" -name '*.class')" ] || { echo "[kernel] FAIL lib did not compile"; exit 1; }

cp "$SRC/forbricfabriclib/fabric.mod.json" "$WORK/lib/classes/"
(cd "$WORK/lib/classes" && jar --create --file "$WORK/forbricfabriclib.jar" .) || exit 1
echo "[kernel] built forbricfabriclib.jar"

step "compile the canary mod (forbricfabriclive)"
# Needs the game (Registry/BuiltInRegistries/Identifier), the vendored Fabric API, and — because the merged base's
# registry types carry Forge/Neo supertypes and Mojang serialization generics — the NeoForge carrier plus the MC
# libraries (DataFixerUpper). Compiling against exactly what the kernel runs against is the point of the canary.
MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
NEO_RT="$OLD/run/neoforge-runtime/neoforge-runtime.jar"
DFU="$(find "$MC_DIR/libraries/com/mojang/datafixerupper" -name '*.jar' 2>/dev/null | sort | tail -1)"
[ -f "$NEO_RT" ] || { echo "[kernel] FAIL neoforge runtime absent: $NEO_RT"; exit 1; }
[ -n "$DFU" ] || { echo "[kernel] FAIL DataFixerUpper not found under $MC_DIR/libraries"; exit 1; }

javac -nowarn -proc:none --release 21 \
      -cp "$MERGED:$KERNEL_JAR:$NEO_RT:$DFU" \
      -d "$WORK/live/classes" \
      $(find "$SRC/forbricfabriclive/src" -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -n "$(find "$WORK/live/classes" -name '*.class')" ] || { echo "[kernel] FAIL canary did not compile"; exit 1; }

cp "$SRC/forbricfabriclive/fabric.mod.json" "$WORK/live/classes/"
mkdir -p "$WORK/live/classes/META-INF/jars"
cp "$WORK/forbricfabriclib.jar" "$WORK/live/classes/META-INF/jars/"
(cd "$WORK/live/classes" && jar --create --file "$OUT/forbricfabriclive.jar" .) || exit 1

step "result"
echo "[kernel] ✅ built $OUT/forbricfabriclive.jar"
unzip -l "$OUT/forbricfabriclive.jar" | sed -n '4,20p'
