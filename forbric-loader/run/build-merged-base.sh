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
# Each ecosystem's userdev artifact: its source patches say which instructions of a patched method are that
# ecosystem's edit, so a method both patched keeps both edits (EditSplicer). The installer downloads both.
FORGE_USERDEV="${FORGE_USERDEV:-$MC/.forbric-build/dl/forge-userdev.jar}"
NEO_USERDEV="${NEO_USERDEV:-$MC/.forbric-build/dl/neoforge-userdev.jar}"
OUT="${OUT:-$HERE/merged-base/patched-mc-merged-26.2.jar}"
REPORT="${REPORT:-$HERE/merged-base/merge-conflicts.txt}"

for f in "$VANILLA" "$FORGE" "$NEO" "$FORGE_RT" "$NEO_RT" "$FORGE_USERDEV" "$NEO_USERDEV"; do
  [ -f "$f" ] || { echo "input jar not found: $f" >&2; exit 2; }
done

# The base version's libraries, each jar named, in the order its version JSON lists them: the merge's proofs follow
# constructions into them (Guava's memoizing Supplier among them). Exactly these. This used to hand the tools the
# whole $MC/libraries folder, where every version any launcher ever fetched sits beside 26.2's own, and the first
# jar in sorted path order answered for a class (a player's guava-25.1-jre before 26.2's guava-33.6.0-jre); the
# tools now refuse a directory (ContractGraph.libraries). The list is tools/dev.py's merge_libraries: each library
# the JSON gives this platform by the rule tools/dev.py prepare fetches with, at the path the JSON gives it, checked
# against the JSON's SHA-1. That rule is the installer's (Installer.minecraftLibraryArtifacts) except that it keeps
# only this CPU's natives jars, which hold no class the merge reads -- so a directory prepared by tools/dev.py, by
# the installer or by a launcher has every jar it names. Nothing is downloaded here.
VERSION_JSON="${VERSION_JSON:-$MC/versions/26.2/26.2.json}"
[ -f "$VERSION_JSON" ] || { echo "base version JSON not found: $VERSION_JSON" >&2; exit 2; }
DEV_TOOLS="$(cd "$PROJECT/../tools" && pwd)"
# A function rather than a heredoc inside $( ): bash 3.2, macOS's own, mis-parses quotes in that form.
base_libraries() {
python3 -I - "$DEV_TOOLS" "$@" <<'PY'
import json, os, sys
tools, version_json, root = sys.argv[1:4]
sys.path.insert(0, tools)
import dev

with open(version_json, encoding="utf-8") as f:
    metadata = json.load(f)
jars, problems = dev.merge_libraries(metadata, root)
if problems:
    print("[build-merged-base] %d of the libraries %s names are not in place under %s:" % (len(problems), version_json, root), file=sys.stderr)
    for problem in problems[:10]:
        print("  " + problem, file=sys.stderr)
    print("  (start this version once in a launcher, or run tools/dev.py prepare --mc-dir on this directory)", file=sys.stderr)
    sys.exit(2)
print("[build-merged-base] libraries: the %d jar(s) %s names for this platform" % (len(jars), os.path.basename(version_json)), file=sys.stderr)
print(os.pathsep.join(str(jar) for jar in jars))
PY
}
LIBRARIES="$(base_libraries "$VERSION_JSON" "$MC/libraries")" || exit 2

ASM="$(find "$HOME/.gradle/caches" -name 'asm-9*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name 'asm-tree*' ! -name 'asm-commons*' ! -name 'asm-analysis*' ! -name 'asm-util*' 2>/dev/null | sort -V | tail -1)"
ASM_TREE="$(find "$HOME/.gradle/caches" -name 'asm-tree-9*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort -V | tail -1)"
# asm-commons: ClassRemapper/SimpleRemapper, used by the divergent-anonymous-sibling pass (PASS 5).
ASM_COMMONS="$(find "$HOME/.gradle/caches" -name 'asm-commons-9*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort -V | tail -1)"
# asm-analysis: the edit splice proves statement boundaries and verifies what it builds (EditSplicer).
ASM_ANALYSIS="$(find "$HOME/.gradle/caches" -name 'asm-analysis-9*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort -V | tail -1)"
[ -n "$ASM" ] && [ -n "$ASM_TREE" ] && [ -n "$ASM_COMMONS" ] && [ -n "$ASM_ANALYSIS" ] || { echo "ASM jars not found in ~/.gradle/caches (build the loader once to fetch them)" >&2; exit 3; }

CP="$ASM:$ASM_TREE:$ASM_COMMONS:$ASM_ANALYSIS"
BUILD="$HERE/.merged-base-tools"
rm -rf "$BUILD"; mkdir -p "$BUILD" "$(dirname "$OUT")"
# A previous build's provenance must not survive next to a base this run replaces and then fails to link.
rm -f "$OUT.provenance.json"
echo "[build-merged-base] compiling merge tools (ASM: $(basename "$ASM"), $(basename "$ASM_TREE"), $(basename "$ASM_COMMONS"), $(basename "$ASM_ANALYSIS")) …"
TOOL_SOURCES=(
  "$PROJECT/src/tools/java/net/forbric/tools/MergedBaseBuilder.java"
  "$PROJECT/src/tools/java/net/forbric/tools/AdditiveMethodMerger.java"
  "$PROJECT/src/tools/java/net/forbric/tools/RuntimeInteropPatcher.java"
  "$PROJECT/src/tools/java/net/forbric/tools/MergedLinkChecker.java"
  "$PROJECT/src/tools/java/net/forbric/tools/ContractGraph.java"
  "$PROJECT/src/tools/java/net/forbric/tools/EquivalentSuperclassBridge.java"
  "$PROJECT/src/tools/java/net/forbric/tools/MapContractRepair.java"
  "$PROJECT/src/tools/java/net/forbric/tools/FieldContractReconciler.java"
  "$PROJECT/src/tools/java/net/forbric/tools/MemoizedSupplierContract.java"
  "$PROJECT/src/tools/java/net/forbric/tools/NonNullReturnProof.java"
  "$PROJECT/src/tools/java/net/forbric/tools/TailHookComposition.java"
  "$PROJECT/src/tools/java/net/forbric/tools/EmptyArrayContractBridge.java"
  "$PROJECT/src/tools/java/net/forbric/tools/PatchLineProvenance.java"
  "$PROJECT/src/tools/java/net/forbric/tools/EditSplicer.java"
)
javac --release 17 -cp "$CP" -d "$BUILD" "${TOOL_SOURCES[@]}"

echo "[build-merged-base] vanilla=$(basename "$VANILLA")  forge=$(basename "$FORGE")  neo=$(basename "$NEO")"
java -Xmx4g -cp "$BUILD:$CP" net.forbric.tools.MergedBaseBuilder "$VANILLA" "$FORGE" "$NEO" "$OUT" "$REPORT" "$FORGE_RT" "$NEO_RT" "$LIBRARIES" "$FORGE_USERDEV" "$NEO_USERDEV"
echo "[build-merged-base] merged base -> $OUT ($(du -h "$OUT" | cut -f1))"

# Cross-runtime-jar interop: forge-runtime.jar's own compiled classes (wholesale, never touched by the merge
# above) may no longer satisfy an interface the MERGED game jar extended on NeoForge's behalf (e.g.
# Registry$PendingTags gained an abstract contents() method) — patch a NEW copy, never the original (which the
# single-ecosystem Forge deployment still uses standalone).
FORGE_RT_PATCHED="${FORGE_RT_PATCHED:-$HERE/merged-base/forge-runtime-interop.jar}"
echo "[build-merged-base] patching cross-runtime interop gaps into $FORGE_RT_PATCHED"
java -cp "$BUILD:$CP" net.forbric.tools.RuntimeInteropPatcher "$FORGE_RT" "$FORGE_RT_PATCHED" "$OUT" "$NEO_RT" "$LIBRARIES"

# What the merge left pointing at nothing. A reference into a merged class that resolves nowhere is a
# NoSuchMethodError or NoSuchFieldError waiting for whichever mod reaches it first, named after the mod rather
# than after the merge, so it is worth seeing at build time instead.
#
# ONLY the merged jar and the two carriers. Handing it a libraries tree buries the answer: those jars carry
# obfuscated and SRG-named classes whose references resolve nowhere by design, and the count goes from 24 to
# over sixteen thousand.
#
# Known raw dangling references live in a committed baseline, so the exit code detects newly broken references.
# Removed stateful ancestors require explicit final-definition composition certificates separately; they cannot
# be licensed by adding raw references to this baseline.
#
# It used to end in `|| echo`, which is the same as not running it: the number appeared in scrollback and nothing
# ever compared it to anything. A check whose result nobody compares reads green forever.
#
# The baseline is supposed to SHRINK. When the tool prints [FIXED], delete that line from the file.
#   seed/refresh:  ... MergedLinkChecker --baseline "$LINK_BASELINE" --write-baseline "$OUT" "$NEO_RT" "$FORGE_RT_PATCHED"
#   escape hatch:  LINK_CHECK=warn ./build-merged-base.sh   (reports, never fails — for bisecting, not for CI)
LINK_BASELINE="${LINK_BASELINE:-$PROJECT/src/test/resources/merge/link-check-baseline.txt}"
echo "[build-merged-base] checking what the merge left dangling (baseline: $LINK_BASELINE) …"
if [ "${LINK_CHECK:-enforce}" = "warn" ]; then
  java -cp "$BUILD:$CP" net.forbric.tools.MergedLinkChecker --baseline "$LINK_BASELINE" "$OUT" "$NEO_RT" "$FORGE_RT_PATCHED" \
    || echo "[build-merged-base] (LINK_CHECK=warn: new dangling references reported above, not failing)"
else
  java -cp "$BUILD:$CP" net.forbric.tools.MergedLinkChecker --baseline "$LINK_BASELINE" "$OUT" "$NEO_RT" "$FORGE_RT_PATCHED"
fi

# How this base was made, next to it: every input and output hash, the tool sources compiled above and whether
# they were committed, and the link-check mode. Nothing else records it, and a release acceptance
# (evidence.py --release) refuses a merged base without it: the jar it attests has to be the output of the
# attested tools on the attested inputs, not merely a file of that name. Written last, so a build that failed
# its link check leaves none.
TOOL_ARGS=()
for tool in "${TOOL_SOURCES[@]}"; do TOOL_ARGS+=(--tool "$tool"); done
python3 "$PROJECT/../forbric-kernel/run/compat/evidence.py" merge-provenance --source "$PROJECT/.." \
  --output "$OUT.provenance.json" --link-check "${LINK_CHECK:-enforce}" \
  --input vanilla="$VANILLA" --input forge-patched="$FORGE" --input neo-patched="$NEO" \
  --input forge-runtime="$FORGE_RT" --input neo-runtime="$NEO_RT" --input link-baseline="$LINK_BASELINE" \
  --input forge-userdev="$FORGE_USERDEV" --input neo-userdev="$NEO_USERDEV" \
  --produced merged="$OUT" --produced forge-interop="$FORGE_RT_PATCHED" --produced report="$REPORT" \
  "${TOOL_ARGS[@]}"
