#!/usr/bin/env bash
# Builds the NeoForge live canary (forbric-loader/run/livemod-src-neoforge) into run/canary/forbricneolive.jar, from
# THIS tree's sources, the way tools/dev.py stage_canaries and forbric-loader/run/build-testmods.sh build it into the
# stage.
#
# WHY A SECOND BUILD. The gates read forbricneolive.jar from the stage ($RUN_OLD/neoforge-runtime/), which DevPrepare
# fills from whichever tree prepared it, and which several working trees share read-only. A change to the canary's
# sources is therefore invisible to a gate until someone re-prepares the shared stage — and a lane must not. This
# builds the same jar into this tree's own run/canary, and FORBRIC_NEOLIVE (lib.sh) points every gate at it:
#
#   run/build-neolive-canary.sh && FORBRIC_NEOLIVE="$PWD/run/canary/forbricneolive.jar" run/gate-m28-forgeconfig.sh
#
# Same compile classpath as the stage build: the NeoForge carrier and NeoForge's own patched game (the GENUINE base,
# not the merged one — a NeoForge mod is built against what NeoForge ships), plus Mixin and ASM for the config plugin.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SRC="$KERNEL/../forbric-loader/run/livemod-src-neoforge"
OUT="$KERNEL/run/canary/forbricneolive.jar"
canary_scratch neolive
NEO_RT="$OLD/run/neoforge-runtime/neoforge-runtime.jar"
NEO_MC="$OLD/run/neoforge-patched/patched-mc-neoforge-26.2.jar"
MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
VLIBS="$(find "$MC_DIR/libraries" -name '*.jar' 2>/dev/null | paste -sd: -)"
GRADLE_JARS="$HOME/.gradle/caches/modules-2/files-2.1"

step "prerequisites"
for f in "$NEO_RT" "$NEO_MC" "$SRC/META-INF/neoforge.mods.toml"; do
  [ -f "$f" ] || { echo "[kernel] FAIL missing: $f"; exit 1; }
done
# Mixin and ASM are not among a launcher's libraries for 26.2; the kernel's own build resolved them into Gradle's cache.
newest() { find "$@" -name '*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1; }
MIXIN="$(newest "$MC_DIR/libraries/net/fabricmc/sponge-mixin")"
[ -n "$MIXIN" ] || MIXIN="$(newest "$GRADLE_JARS/net.fabricmc/sponge-mixin")"
ASM="$(newest "$GRADLE_JARS/org.ow2.asm/asm")"
ASM_TREE="$(newest "$GRADLE_JARS/org.ow2.asm/asm-tree")"
for f in "$MIXIN" "$ASM" "$ASM_TREE"; do
  [ -n "$f" ] || { echo "[kernel] FAIL sponge-mixin / asm / asm-tree jar not found"; exit 1; }
done

step "compile and package"
classes="$WORK/classes"
mkdir -p "$classes/META-INF"
javac -nowarn -proc:none --release 21 -cp "$NEO_RT:$NEO_MC:$MIXIN:$ASM:$ASM_TREE:$VLIBS" -d "$classes" \
      $(find "$SRC" -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -f "$classes/forbric/live/neoforge/ForbricNeoLiveMod.class" ] || { echo "[kernel] FAIL the canary did not compile"; exit 1; }
cp "$SRC/META-INF/neoforge.mods.toml" "$classes/META-INF/"
# The mixin config at the JAR ROOT, where the [[mixins]] entry names it: a declared config that is missing is dropped
# with a warning, and the plugin the gates read never constructs.
cp "$SRC/forbricneolive.mixins.json" "$classes/"
for dir in data assets; do
  if [ -d "$SRC/$dir" ]; then cp -R "$SRC/$dir" "$classes/"; fi
done
(cd "$classes" && jar --create --file "$WORK/forbricneolive.jar" .) || exit 1
publish_canary "$WORK/forbricneolive.jar" "$OUT" || exit 1
echo "[kernel] ✅ built $OUT"
