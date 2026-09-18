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

# The verdict comes from the JUnit XML, not from gradle's exit code, and the task is forced to RUN.
#
# Both halves were wrong. `[0-9]+ tests completed` is a string gradle prints only when tests FAIL, so `n` was
# empty on every green run and this gate has never once reported a test count -- it printed "(all green)"
# unconditionally. And an UP-TO-DATE test task exits 0, so the gate reported PASS for a run in which not one
# test executed: build/gate-m0-test.log from the last sweep before this change reads "7 actionable tasks: 7
# up-to-date", under a line saying "PASS unit tests (all green)".
#
# UP-TO-DATE would be sound if every input were declared, and twice now one was not (the merged base, then the
# game-side classes -- see build.gradle). A gate is the wrong place to trust that, so cleanTest empties the
# results directory first: whatever is parsed below was produced by this run or does not exist.
step "2. ported unit tests"
TESTLOG="$BUILD/gate-m0-test.log"
RESULTS="$KERNEL/build/test-results/test"
"$KERNEL/gradlew" --offline -p "$KERNEL" cleanTest test >"$TESTLOG" 2>&1
rc=$?
SUMMARY=$(python3 - "$RESULTS" <<'PYEOF'
import glob, os, sys, xml.etree.ElementTree as ET
tests = skipped = failures = errors = 0
files = glob.glob(os.path.join(sys.argv[1], "*.xml"))
for f in files:
    try:
        r = ET.parse(f).getroot()
    except Exception:
        continue
    tests    += int(r.get("tests", 0))
    skipped  += int(r.get("skipped", 0))
    failures += int(r.get("failures", 0))
    errors   += int(r.get("errors", 0))
print(f"{len(files)} {tests} {skipped} {failures} {errors}")
PYEOF
)
read -r xmls tests skipped failures errors <<<"$SUMMARY"
if [ "$rc" -ne 0 ]; then
  echo "[kernel] FAIL unit tests — gradle exited $rc, see $TESTLOG"; FAIL=1
elif [ "${xmls:-0}" -eq 0 ] || [ "${tests:-0}" -eq 0 ]; then
  echo "[kernel] FAIL unit tests — the task produced no results ($xmls report file(s), $tests test(s)); see $TESTLOG"
  FAIL=1
elif [ "${failures:-0}" -ne 0 ] || [ "${errors:-0}" -ne 0 ]; then
  echo "[kernel] FAIL unit tests — $failures failed, $errors errored of $tests; see $TESTLOG"; FAIL=1
else
  # skipped is printed, not asserted. Most of them are assumeTrue guards on staged artifacts, and what the right
  # floor is belongs to the change that declares those artifacts as inputs -- not here.
  echo "[kernel] PASS unit tests ($tests ran, $skipped skipped, 0 failed)"
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
