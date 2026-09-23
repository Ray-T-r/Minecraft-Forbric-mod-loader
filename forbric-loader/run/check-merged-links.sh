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
# The baseline's provenance says which build it was reviewed on. It has to describe THIS baseline -- a baseline
# edited without recording the review is refused -- and each input is reported as the reviewed jar or not, so a
# pass on another build reads as what it is: the reviewed exceptions applied to a build nobody reviewed.
PROVENANCE="${LINK_BASELINE%.txt}.provenance.json"
if [ -f "$PROVENANCE" ]; then
  python3 - "$PROVENANCE" "$LINK_BASELINE" "$@" <<'PY' || exit 2
import hashlib, json, sys
def sha(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()
provenance, baseline = json.load(open(sys.argv[1])), sys.argv[2]
if provenance.get("baselineSha256") != sha(baseline):
    print(f"[link-check] {sys.argv[1]} does not describe {baseline}: record the review that changed it", file=sys.stderr)
    sys.exit(1)
reviewed = {entry["sha256"]: role for role, entry in provenance.get("inputs", {}).items()}
for path in sys.argv[3:]:
    digest = sha(path)
    print(f"[link-check] provenance: {path} is " + (f"the reviewed {reviewed[digest]}" if digest in reviewed
          else f"not a reviewed input ({digest[:12]}); the baseline is applied to a build it was not reviewed on"))
PY
fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/forbric-link-gate-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
ASM="$(find "$HOME/.gradle/caches" -name 'asm-9*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name 'asm-tree*' ! -name 'asm-commons*' ! -name 'asm-analysis*' ! -name 'asm-util*' 2>/dev/null | sort -V | tail -1)"
ASM_TREE="$(find "$HOME/.gradle/caches" -name 'asm-tree-9*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | sort -V | tail -1)"
[ -n "$ASM" ] && [ -n "$ASM_TREE" ] || { echo '[link-check] ASM is not cached; build the tools first' >&2; exit 2; }
CP="$ASM:$ASM_TREE"
javac --release 17 -cp "$CP" -d "$WORK" "$PROJECT/src/tools/java/net/forbric/tools/MergedLinkChecker.java"
java -cp "$WORK:$CP" net.forbric.tools.MergedLinkChecker --baseline "$LINK_BASELINE" "$@"
