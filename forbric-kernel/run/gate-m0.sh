#!/usr/bin/env bash
# M0 gate — kernel scaffold + ported libs + oracle harness.
#   1. offline build (boot jar) succeeds
#   2. ported unit tests green (transform / access / mapping / metadata / discovery)
#   3. kernel --scan produces well-formed JSON discovering mods across all 3 ecosystems (both merged sets)
#   4. differential oracle: kernel parser ≡ independent ground-truth parser on the real mod sets
# This is the differential-oracle foundation every later milestone builds on.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

step "1. offline build (boot jar)"
if "$KERNEL/gradlew" --offline -q -p "$KERNEL" jar 2>&1 | filter_noise | grep -iE 'error|failed'; then
  echo "[kernel] FAIL build"; FAIL=1
else echo "[kernel] PASS build"; fi

step "2. ported unit tests"
TESTLOG="$BUILD/gate-m0-test.log"
if "$KERNEL/gradlew" --offline -p "$KERNEL" test >"$TESTLOG" 2>&1; then
  n=$(grep -oE '[0-9]+ tests completed' "$TESTLOG" | tail -1)
  echo "[kernel] PASS unit tests (${n:-all green})"
else
  echo "[kernel] FAIL unit tests — see $TESTLOG"; FAIL=1
fi

step "3. --scan across both merged mod sets"
kernel_classpath
for set in server-merged client-merged; do
  dir="$RUN_OLD/$set/mods"
  [ -d "$dir" ] || { echo "[kernel] SKIP $set (no $dir)"; continue; }
  out="$BUILD/scan/$set.json"; mkdir -p "$BUILD/scan"
  kernel_scan "$dir" "$out" >/dev/null
  # well-formed + non-empty + all three ecosystems represented
  python3 - "$out" "$set" <<'PY'
import json,sys
o=json.loads(open(sys.argv[1]).read()); s=sys.argv[2]
ecos={m["ecosystem"] for m in o["mods"]}
ok = o["count"]>0 and {"FABRIC","FORGE","NEOFORGE"}<=ecos
print(f"[kernel] {'PASS' if ok else 'FAIL'} scan {s}: {o['count']} mods, ecosystems={sorted(ecos)}")
sys.exit(0 if ok else 1)
PY
  [ $? -eq 0 ] || FAIL=1
done

step "4. differential oracle (kernel parser vs independent ground truth)"
"$KERNEL/run/diff-oracle.sh" "$RUN_OLD/server-merged/mods" "$RUN_OLD/client-merged/mods" || FAIL=1

step "M0 result"
if [ "$FAIL" -eq 0 ]; then echo "[kernel] ✅ M0 GATE GREEN"; else echo "[kernel] ❌ M0 GATE RED"; fi
exit "$FAIL"
