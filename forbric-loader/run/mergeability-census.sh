#!/usr/bin/env bash
# How many of the merge's dropped Forge hooks were a CHOICE rather than a necessity.
#
# When both ecosystems patched the same method the merge keeps one body and drops the other's hook, a thousand
# times, and the report says only that it happened. "Change the arbitration" is the obvious response and the
# expensive one — it rewrites the calibration every repair in the kernel depends on. The cheaper question first
# is how many of those are even a choice: a method where each side only INSERTED into vanilla's body could keep
# both insertions; a method where either side rewrote what vanilla did could not.
#
# Reports its own trustworthiness first. The three jars come from three different decompile pipelines, so the
# test is only as good as the normalisation, and a number without that caveat would be worse than none.
#
# Usage: [VANILLA=…] [FORGE=…] [NEO=…] [REPORT=…] ./mergeability-census.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
# Artifacts live wherever FORBRIC_OLD says; the TOOL is source and lives beside this script. Resolving both the
# same way is how a second worktree ends up compiling another checkout's copy of a tool, which has happened.
STAGED="${FORBRIC_OLD:+$FORBRIC_OLD/run}"
STAGED="${STAGED:-$HERE}"

VANILLA="${VANILLA:-$MC/versions/26.2/26.2.jar}"
FORGE="${FORGE:-$MC/libraries/net/forbric/patched-mc-forge/26.2-65.0.1/patched-mc-forge-26.2-65.0.1.jar}"
NEO="${NEO:-$STAGED/neoforge-patched/patched-mc-neoforge-26.2.jar}"
REPORT="${REPORT:-$STAGED/merged-base/merge-conflicts.txt}"
for f in "$VANILLA" "$FORGE" "$NEO" "$REPORT"; do
  [ -f "$f" ] || { echo "input not found: $f" >&2; exit 2; }
done

ASM="$(find "$HOME/.gradle/caches" -name 'asm-9*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name 'asm-tree*' ! -name 'asm-commons*' ! -name 'asm-analysis*' ! -name 'asm-util*' 2>/dev/null | sort -V | tail -1)"
ASM_TREE="$(find "$HOME/.gradle/caches" -name 'asm-tree-9*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort -V | tail -1)"
[ -n "$ASM" ] && [ -n "$ASM_TREE" ] || { echo "ASM jars not found in ~/.gradle/caches" >&2; exit 3; }

BUILD="$HERE/.mergeability-tools"
rm -rf "$BUILD"; mkdir -p "$BUILD"
javac --release 17 -cp "$ASM:$ASM_TREE" -d "$BUILD" \
  "$PROJECT/src/tools/java/net/forbric/tools/MergeabilityCensus.java"
exec java -Xmx4g -cp "$BUILD:$ASM:$ASM_TREE" net.forbric.tools.MergeabilityCensus \
  "$VANILLA" "$FORGE" "$NEO" "$REPORT"
