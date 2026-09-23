#!/usr/bin/env bash
# M0 gate — kernel scaffold + ported libs + oracle harness.
#   1. offline build (boot jar) succeeds
#   2. ported unit tests green (transform / access / mapping / metadata / discovery)
#   3. kernel --scan produces well-formed JSON discovering mods across all 3 ecosystems (both merged sets)
#   4. differential oracle: kernel parser ≡ independent ground-truth parser on the real mod sets
#   6. the evidence, soak and link-gate tools' own tests, and the transfer-engine suite, all actually executed
# This is the differential-oracle foundation every later milestone builds on.
# GATE-PARALLEL: mem=2000  (builds and runs the unit suite; owns no rundir)
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

# The verdict of a JUnit task: report files, tests, skipped, failures, errors -- read from the XML it wrote.
junit_summary() {
  python3 - "$1" <<'PYEOF'
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
}

step "0. the base the unit tests read is the base this run checks"
# The bytecode tests and the kernel-runtime compile read $RUN_OLD/... (build.gradle hands them that root); step 4
# link-checks ${MERGED:-...}. With MERGED naming a candidate and FORBRIC_OLD the reference checkout they are two
# different jars, and this gate would pass the unit tests on one base and the link check on another while the
# evidence recorded only the second. Same bytes, or no verdict. (FORGE_RT is the interop jar, derived from the raw
# runtime the tests read, so it has no staged twin to compare.)
for pair in "MERGED:$RUN_OLD/merged-base/patched-mc-merged-26.2.jar" "NEO_RT:$RUN_OLD/neoforge-runtime/neoforge-runtime.jar"; do
  var="${pair%%:*}"; staged="${pair#*:}"; requested="${!var:-}"
  [ -n "$requested" ] || { echo "[kernel] PASS $var unset: the tests and the link check both read $staged"; continue; }
  if [ -f "$requested" ] && [ -f "$staged" ] && cmp -s "$requested" "$staged"; then
    echo "[kernel] PASS $var is the jar the unit tests read ($staged)"
  else
    echo "[kernel] FAIL $var=$requested is not the jar the unit tests read ($staged) -- point FORBRIC_OLD at a"
    echo "[kernel]      staged root holding the candidate, or unset $var"; FAIL=1
  fi
done

step "1. offline build (boot jar)"
if "$KERNEL/gradlew" --offline -q -p "$KERNEL" jar >"$BUILD/gate-m0-build.log" 2>&1; then
  echo "[kernel] PASS build"
else
  echo "[kernel] FAIL build — see $BUILD/gate-m0-build.log"; FAIL=1
fi

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
read -r xmls tests skipped failures errors <<<"$(junit_summary "$RESULTS")"
if [ "$rc" -ne 0 ]; then
  echo "[kernel] FAIL unit tests — gradle exited $rc, see $TESTLOG"; FAIL=1
elif [ "${xmls:-0}" -eq 0 ] || [ "${tests:-0}" -eq 0 ]; then
  echo "[kernel] FAIL unit tests — the task produced no results ($xmls report file(s), $tests test(s)); see $TESTLOG"
  FAIL=1
elif [ "${failures:-0}" -ne 0 ] || [ "${errors:-0}" -ne 0 ]; then
  echo "[kernel] FAIL unit tests — $failures failed, $errors errored of $tests; see $TESTLOG"; FAIL=1
elif [ "${skipped:-0}" -gt 0 ]; then
  # This integration gate requires every staged fixture; an unexecuted assertion is not a passing one.
  # Ordinary boot-only compilation and tests can still run independently without game artifacts.
  echo "[kernel] FAIL unit tests — $skipped of $tests skipped; on a machine with the staged artifacts that means "
  echo "[kernel]      those assertions are no longer reading them. See $TESTLOG"
  FAIL=1
else
  echo "[kernel] PASS unit tests ($((tests - skipped)) ran, $skipped skipped, 0 failed)"
fi

step "2b. transfer-engine tests (the real Fabric and NeoForge transaction engines)"
# `check` depends on transferTest but nothing ran `check`, and the task is onlyIf(staged fixtures present), so its
# 23 tests reported nothing either way. Forced to run and read like step 2: no report means the fixtures are
# missing, which on this integration gate is a failure, not a skip.
TRANSFERLOG="$BUILD/gate-m0-transfer.log"
"$KERNEL/gradlew" --offline -p "$KERNEL" cleanTransferTest transferTest >"$TRANSFERLOG" 2>&1
rc=$?
read -r xmls tests skipped failures errors <<<"$(junit_summary "$KERNEL/build/test-results/transferTest")"
if [ "$rc" -ne 0 ]; then
  echo "[kernel] FAIL transfer tests — gradle exited $rc, see $TRANSFERLOG"; FAIL=1
elif [ "${tests:-0}" -eq 0 ]; then
  echo "[kernel] FAIL transfer tests — none ran ($xmls report file(s)); the staged jars or Fabric API are missing, see $TRANSFERLOG"
  FAIL=1
elif [ "${failures:-0}" -ne 0 ] || [ "${errors:-0}" -ne 0 ] || [ "${skipped:-0}" -ne 0 ]; then
  echo "[kernel] FAIL transfer tests — $failures failed, $errors errored, $skipped skipped of $tests; see $TRANSFERLOG"; FAIL=1
else
  echo "[kernel] PASS transfer tests ($tests ran, 0 skipped, 0 failed)"
fi

step "3. --scan across both merged mod sets"
kernel_classpath
for set in server-merged client-merged; do
  dir="$RUN_OLD/$set/mods"
  [ -d "$dir" ] || { echo "[kernel] FAIL $set (required fixture missing: $dir)"; FAIL=1; continue; }
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

step "4. the merged base links (against the committed baseline)"
# The link check lived only in build-merged-base.sh, which runs when someone rebuilds the base -- so between
# rebuilds nothing ever asked whether the artifact every test and every gate reads still resolves. That is the
# same shape as the check whose exit code was thrown away: present, and never consulted.
#
# Here it costs one pass over three staged jars and answers the only question that matters between rebuilds:
# is this the base the tree was calibrated against. This is an integration gate: missing inputs FAIL.
# A clean checkout can still run `gradlew jar test` independently without staged game artifacts.
# The baseline and the TOOL are SOURCE (committed, in this tree); the jars are ARTIFACTS (staged, wherever
# FORBRIC_OLD says). Resolving source through RUN_OLD sends a second worktree to another checkout for both --
# which it did on the first run here, compiling that checkout's copy of the tool, which had no --baseline flag
# and read the flag as a jar path.
LINK_BASELINE="${LINK_BASELINE:-$KERNEL/../forbric-loader/src/test/resources/merge/link-check-baseline.txt}"
LINKLOG="$BUILD/gate-m0-linkcheck.log"
if LINK_BASELINE="$LINK_BASELINE" bash "$KERNEL/../forbric-loader/run/check-merged-links.sh" \
    "${MERGED:-$RUN_OLD/merged-base/patched-mc-merged-26.2.jar}" \
    "${NEO_RT:-$RUN_OLD/neoforge-runtime/neoforge-runtime.jar}" \
    "${FORGE_RT:-$RUN_OLD/merged-base/forge-runtime-interop.jar}" > "$LINKLOG" 2>&1; then
  check "the merged base links no worse than the baseline" "dangling references: [0-9]+ \(known [0-9]+, new 0\)" "$LINKLOG"
else
  echo "[kernel] FAIL link check — missing inputs or new dangling references (see $LINKLOG)"; FAIL=1
  tail -15 "$LINKLOG"
fi

step "5. differential oracle (kernel parser vs independent ground truth)"
"$KERNEL/run/diff-oracle.sh" "$RUN_OLD/server-merged/mods" "$RUN_OLD/client-merged/mods" || FAIL=1

step "6. the evidence, soak and link-gate tools' own tests"
# These tools decide what a release is. Their tests used to run only by hand, so a regression in the recorder
# or the link gate would have been found by the release it waved through.
TOOLLOG="$BUILD/gate-m0-tooling.log"
if (cd "$KERNEL/run/compat" && python3 -m unittest -v test_evidence test_soak) >"$TOOLLOG" 2>&1; then
  ran=$(grep -aoE '^Ran [0-9]+ tests?' "$TOOLLOG" | grep -oE '[0-9]+' | tail -1)
  if [ "${ran:-0}" -gt 0 ] && grep -aqE '^OK$' "$TOOLLOG"; then
    echo "[kernel] PASS evidence and soak tool tests ($ran ran, none skipped)"
  else
    echo "[kernel] FAIL evidence and soak tool tests — ${ran:-0} ran, or some were skipped; see $TOOLLOG"; FAIL=1
  fi
else
  echo "[kernel] FAIL evidence and soak tool tests — see $TOOLLOG"; FAIL=1
fi
LINKTESTLOG="$BUILD/gate-m0-link-selftest.log"
if bash "$KERNEL/../forbric-loader/run/test-link-check.sh" >"$LINKTESTLOG" 2>&1; then
  check "the link checker's own negative controls" "ALL GREEN" "$LINKTESTLOG"
else
  echo "[kernel] FAIL the link checker's own negative controls — see $LINKTESTLOG"; FAIL=1
fi
# The installer's gate runs the PACKAGED tool and baseline, so it is exercised on the jar the installer ships.
INSTALLERLOG="$BUILD/gate-m0-installer-link.log"
if "$KERNEL/../forbric-loader/gradlew" --offline -q -p "$KERNEL/../forbric-loader" mergeToolsJar >"$INSTALLERLOG" 2>&1 \
    && python3 "$KERNEL/../forbric-kernel-installer/run/test-link-gate.py" >>"$INSTALLERLOG" 2>&1; then
  check "the installer's link gate, on the packaged tools" "PASS installer gate" "$INSTALLERLOG"
else
  echo "[kernel] FAIL the installer's link gate — see $INSTALLERLOG"; FAIL=1
fi

step "M0 result"
if [ "$FAIL" -eq 0 ]; then echo "[kernel] ✅ M0 GATE GREEN"; else echo "[kernel] ❌ M0 GATE RED"; fi
exit "$FAIL"
