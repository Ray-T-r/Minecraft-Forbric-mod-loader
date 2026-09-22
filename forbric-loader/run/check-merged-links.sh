#!/usr/bin/env bash
# The integration/release link gate. Missing inputs are failures; no artifacts are rebuilt here.
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
LINK_BASELINE="${LINK_BASELINE:-$PROJECT/src/test/resources/merge/link-check-baseline.txt}"
[ "$#" -gt 0 ] || { echo '[link-check] no merged jar supplied' >&2; exit 2; }
[ -f "$LINK_BASELINE" ] || { echo "[link-check] missing reviewed baseline: $LINK_BASELINE" >&2; exit 2; }
for input in "$@"; do
  [ -s "$input" ] || { echo "[link-check] missing or empty input: $input" >&2; exit 2; }
done
WORK="$(mktemp -d "${TMPDIR:-/tmp}/forbric-link-gate-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
ASM="$(find "$HOME/.gradle/caches" -name 'asm-9*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name 'asm-tree*' ! -name 'asm-commons*' ! -name 'asm-analysis*' ! -name 'asm-util*' 2>/dev/null | sort -V | tail -1)"
ASM_TREE="$(find "$HOME/.gradle/caches" -name 'asm-tree-9*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort -V | tail -1)"
[ -n "$ASM" ] && [ -n "$ASM_TREE" ] || { echo '[link-check] ASM is not cached; build the tools first' >&2; exit 2; }
CP="$ASM:$ASM_TREE"
javac --release 17 -cp "$CP" -d "$WORK" "$PROJECT/src/tools/java/net/forbric/tools/MergedLinkChecker.java"
java -cp "$WORK:$CP" net.forbric.tools.MergedLinkChecker --baseline "$LINK_BASELINE" "$@"
