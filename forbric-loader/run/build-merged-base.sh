#!/usr/bin/env bash
# Build the tri-in-one MERGED patched-Minecraft base: vanilla 26.2 + BOTH the traditional-MinecraftForge and
# the NeoForge injections in one jar (so Forbric can run Forge + NeoForge + Fabric mods in one instance).
# Compiles src/tools/MergedBaseBuilder against ASM (resolved from the gradle cache) and runs it.
#
# Usage: [VANILLA=…] [FORGE=…] [NEO=…] [OUT=…] ./build-merged-base.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"

VANILLA="${VANILLA:-$MC/versions/26.2/26.2.jar}"
FORGE="${FORGE:-$MC/libraries/net/forbric/patched-mc-forge/26.2-65.0.1/patched-mc-forge-26.2-65.0.1.jar}"
NEO="${NEO:-$HERE/neoforge-patched/patched-mc-neoforge-26.2.jar}"
FORGE_RT="${FORGE_RT:-$HERE/forge-runtime/forge-runtime.jar}"
NEO_RT="${NEO_RT:-$HERE/neoforge-runtime/neoforge-runtime.jar}"
OUT="${OUT:-$HERE/merged-base/patched-mc-merged-26.2.jar}"
REPORT="${REPORT:-$HERE/merged-base/merge-conflicts.txt}"

for f in "$VANILLA" "$FORGE" "$NEO" "$FORGE_RT" "$NEO_RT"; do
  [ -f "$f" ] || { echo "input jar not found: $f" >&2; exit 2; }
done

ASM="$(find "$HOME/.gradle/caches" -name 'asm-9*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name 'asm-tree*' ! -name 'asm-commons*' ! -name 'asm-analysis*' ! -name 'asm-util*' 2>/dev/null | sort -V | tail -1)"
ASM_TREE="$(find "$HOME/.gradle/caches" -name 'asm-tree-9*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort -V | tail -1)"
# asm-commons: ClassRemapper/SimpleRemapper, used by the divergent-anonymous-sibling pass (PASS 5).
ASM_COMMONS="$(find "$HOME/.gradle/caches" -name 'asm-commons-9*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort -V | tail -1)"
[ -n "$ASM" ] && [ -n "$ASM_TREE" ] && [ -n "$ASM_COMMONS" ] || { echo "ASM jars not found in ~/.gradle/caches (build the loader once to fetch them)" >&2; exit 3; }

CP="$ASM:$ASM_TREE:$ASM_COMMONS"
BUILD="$HERE/.merged-base-tools"
rm -rf "$BUILD"; mkdir -p "$BUILD" "$(dirname "$OUT")"
echo "[build-merged-base] compiling merge tools (ASM: $(basename "$ASM"), $(basename "$ASM_TREE"), $(basename "$ASM_COMMONS")) …"
javac --release 17 -cp "$CP" -d "$BUILD" \
  "$PROJECT/src/tools/java/net/forbric/tools/MergedBaseBuilder.java" \
  "$PROJECT/src/tools/java/net/forbric/tools/RuntimeInteropPatcher.java"

echo "[build-merged-base] vanilla=$(basename "$VANILLA")  forge=$(basename "$FORGE")  neo=$(basename "$NEO")"
java -Xmx4g -cp "$BUILD:$CP" net.forbric.tools.MergedBaseBuilder "$VANILLA" "$FORGE" "$NEO" "$OUT" "$REPORT" "$FORGE_RT" "$NEO_RT"
echo "[build-merged-base] merged base -> $OUT ($(du -h "$OUT" | cut -f1))"

# Cross-runtime-jar interop: forge-runtime.jar's own compiled classes (wholesale, never touched by the merge
# above) may no longer satisfy an interface the MERGED game jar extended on NeoForge's behalf (e.g.
# Registry$PendingTags gained an abstract contents() method) — patch a NEW copy, never the original (which the
# single-ecosystem Forge deployment still uses standalone).
FORGE_RT_PATCHED="${FORGE_RT_PATCHED:-$HERE/merged-base/forge-runtime-interop.jar}"
echo "[build-merged-base] patching cross-runtime interop gaps into $FORGE_RT_PATCHED"
java -cp "$BUILD:$CP" net.forbric.tools.RuntimeInteropPatcher "$FORGE_RT" "$FORGE_RT_PATCHED"
