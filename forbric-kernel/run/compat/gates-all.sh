#!/usr/bin/env bash
# Run every checked-in gate, sequentially: each gate rebuilds the same kernel jar.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/../.." && pwd)"
RUN="${FORBRIC_GATE_DIR:-$KERNEL/run}"
OUT="${FORBRIC_GATE_RESULTS:-$KERNEL/build/gates}"
export GATE_PORT="${GATE_PORT:-25599}"
SKIP=()
LIST=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --list) LIST=1; shift;;
    --skip) [ "$#" -ge 2 ] || { echo '--skip needs a gate name' >&2; exit 2; }
      SKIP+=("$2"); shift 2;;
    --help) echo 'gates-all.sh [--list] [--skip gate-m12-multiplayer.sh] ...'; exit 0;;
    *) echo "Unknown argument: $1" >&2; exit 2;;
  esac
done
# Sort m2 before m2b, before m10. The glob is authoritative; no parallel gate list.
GATES=()
while IFS= read -r name; do GATES+=("$name"); done < <(python3 - "$RUN" <<'PY'
import pathlib, re, sys
paths = pathlib.Path(sys.argv[1]).glob('gate-m*.sh')
def key(p):
    m = re.match(r'gate-m(\d+)(.*)', p.name)
    return int(m[1]), m[2].removesuffix('.sh')
for p in sorted(paths, key=key):
    print(p.name)
PY
)
[ "${#GATES[@]}" -gt 0 ] || { echo "No gates found: $RUN" >&2; exit 2; }
if [ "$LIST" -eq 1 ]; then printf '%s\n' "${GATES[@]}"; exit 0; fi
for skip in "${SKIP[@]}"; do
  found=0
  for gate in "${GATES[@]}"; do [ "$skip" != "$gate" ] || found=1; done
  [ "$found" -eq 1 ] || { echo "Unknown --skip gate: $skip" >&2; exit 2; }
done
mkdir -p "$OUT"
: > "$OUT/summary.txt"
failed=0
for gate in "${GATES[@]}"; do
  skip_gate=0
  for skip in "${SKIP[@]}"; do [ "$skip" != "$gate" ] || skip_gate=1; done
  if [ "$skip_gate" -eq 1 ]; then
    printf 'RESULT %s SKIP (explicit --skip)\n' "$gate" | tee -a "$OUT/summary.txt"
    continue
  fi
  rc=0
  bash "$RUN/$gate" > "$OUT/$gate.log" 2>&1 || rc=$?
  verdict=GREEN
  if [ "$rc" -eq 2 ] && grep -q '^# EXPECTED: RED until ' "$RUN/$gate" \
      && grep -q '^\[kernel\] EXPECTED-RED ' "$OUT/$gate.log"; then
    verdict=EXPECTED_RED
  elif [ "$rc" -ne 0 ]; then
    verdict=RED
    failed=1
  fi
  printf 'RESULT %s %s (exit=%s)\n' "$gate" "$verdict" "$rc" | tee -a "$OUT/summary.txt"
done
exit "$failed"
