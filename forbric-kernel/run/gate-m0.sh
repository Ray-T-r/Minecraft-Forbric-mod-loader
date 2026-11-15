#!/usr/bin/env bash
# M0 gate — kernel scaffold + ported libs + oracle harness.
#   1. offline build (boot jar) succeeds
#   1b. capture what the kernel defines with fabric-api installed (the one opt-in unit-test fixture)
#   2. ported unit tests green (transform / access / mapping / metadata / discovery), with nothing skipped
#   3. kernel --scan produces well-formed JSON discovering Fabric and NeoForge mods, and no MinecraftForge mod, in both
#      fixture sets (server-merged, client-merged: the names are the fixtures', Stage 5 renames them)
#   4. the link check -- reported SKIP until C6 reseeds it on the game base (D32, D64)
#   5. differential oracle: kernel parser ≡ independent ground-truth parser on the real mod sets, and its negative
#      control (step 5b): with the kernel's refusals and set-asides withheld it fails on exactly those mods
#   6. the evidence, soak and link-gate tools' own tests, and the transfer-engine suite, all actually executed
# This is the differential-oracle foundation every later milestone builds on.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). The kernel now refuses a MinecraftForge-only jar, naming it
# on a "[Forbric/Unsupported] refusing <jar> (<id>)" line and reporting none of its mods. Both merged sets hold such
# jars (server-merged: forbric-bridge, forbriclive, spark; client-merged: forbric-bridge, xaerominimap), and the
# oracle's independent reader still reads every mods.toml as FORGE, so step 5 went red with those mods "MISSED". No
# check is dropped. diff-oracle.sh now accepts a missing FORGE mod only as an asserted divergence -- every jar declaring
# it refused by name on the kernel's own line, naming an id that jar declares, the jar declaring no Fabric or NeoForge
# mod, and nothing reported from it -- and its PASS line names each refused mod; every other difference (a FORGE mod
# missing with no refusal, any Fabric or NeoForge miss, anything spurious, an unsound refusal) still fails. Step 5b is
# new: the same scans with the refusal lines withheld must fail on exactly the mods step 5 named, so the acceptance is
# shown to rest on the refusals.
#
# STAGE 4 OF DROPPING MinecraftForge (what changed and why). The base is NeoForge's own patched game; the merged base
# and the MinecraftForge carrier are gone from the stage. Step 0 compares the base the tests read (BASE) instead of
# MERGED. Step 3: discovery no longer reports any MinecraftForge mod (a universal jar's mods.toml half is set aside, a
# MinecraftForge-only jar is refused), so the scan must show Fabric and NeoForge and NOT FORGE; it used to require all
# three. Step 4 checked the merged base's links against the merged baseline, an input that no longer exists; the link
# check is reseeded on the game base in C6 (kernel tree, access levels and invokedynamic handles, D32), and until then
# the step prints an explicit "[kernel] SKIP (C6: ...)" line instead of a FAIL (D64). Step 5: the fixture sets still
# hold forge-runtime.jar, the old MinecraftForge carrier with a Fabric half ("forge") and a mods.toml half; the kernel
# loads the Fabric half and sets the mods.toml half aside, so the oracle (which reads every mods.toml as FORGE) sees
# (FORGE, forge) missing. diff-oracle accepts that too, only as an asserted divergence of its own (a set-aside half,
# D32): the jar also declares a Fabric or NeoForge mod, every one of those the kernel reported from that jar, and the
# jar was not refused. Step 5b withholds the set-asides with the refusals, and must fail on exactly both sets of mods.
# Step 6's two installer checks (the packaged link tools and the bundled baseline) still pass on what the installer
# bundles today; C6 rewrites them with the link check.
# The unit suite runs in up to four worker JVMs (build.gradle maxParallelForks): measured 2026-11-16 (lane OPT, three
# forked runs), every java process of the suite together peaked at 3.4 GB resident (the four workers 2.9-3.1 GB),
# against 2.7 GB in one fork. mem is what the scheduler reserves beside the other gates of a sweep.
# GATE-PARALLEL: rundirs=server-m0-definitions mem=3500  (builds and runs the unit suite)
# GATE-CACHE: tests   (runs test sources, so a test-only change re-runs it; see run/compat/gate_cache.py)
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
# The bytecode tests and the kernel-runtime compile read $RUN_OLD/... (build.gradle hands them that root); a BASE or
# NEO_RT naming a candidate while FORBRIC_OLD names the reference checkout would be two different jars, and this gate
# would pass the unit tests on one base while the evidence recorded the other. Same bytes, or no verdict.
for pair in "BASE:$RUN_OLD/neoforge-patched/patched-mc-neoforge-26.2.jar" "NEO_RT:$RUN_OLD/neoforge-runtime/neoforge-runtime.jar"; do
  var="${pair%%:*}"; staged="${pair#*:}"; requested="${!var:-}"
  if [ -z "$requested" ]; then
    if [ -f "$staged" ]; then echo "[kernel] PASS $var unset: the tests read $staged"
    else echo "[kernel] FAIL $var unset and the base the tests read is missing: $staged"; FAIL=1; fi
    continue
  fi
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

step "1b. capture the final definitions the opt-in replay test reads"
# SharedFinalSourceActualTest replays fabric-content-registries' Entity source bodies against Entity as the kernel
# DEFINES it with fabric-api installed -- bytes only a running game has, so the test is [fixture:opt-in] and skips
# without them. Step 2 counts every skip as a failure, and nothing here produced the capture, so this gate was red on
# every machine for two tests nobody could run. It is produced here instead: a dedicated server with fabric-api
# alone, recording every class its loader defines (-Dforbric.definedClassEvidence).
refuse_shared_rundir "$KERNEL/run/server-m0-definitions"   # outside the fragment: its contract test stubs lib.sh
# M0_CAPTURE_BEGIN (AntiCheatGateLogTest's way: GateM0CaptureStepTest runs this fragment against a stubbed server)
DEFINED="$BUILD/gate-m0-definitions"
DRUN="$KERNEL/run/server-m0-definitions"
DLOG="$BUILD/gate-m0-definitions.log"
reap_stale_server "$DRUN"
rm -rf "$DEFINED" "$DRUN"
mkdir -p "$DRUN/mods"
# Without fabric-api the capture is of the wrong Entity: a server still boots and defines one. So a copy that fails is
# a verdict of its own, and the capture must also show that fabric-api's classes were defined.
if ! cp "$KERNEL/run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar" "$DRUN/mods/" 2>/dev/null; then
  echo "[kernel] FAIL no fabric-api 0.155.2 under run/client-merged-pack/mods to capture with"; FAIL=1
else
  seed_server_properties "$DRUN"
  # Recording every definition makes this boot slow (a minute or more), and a "stop" fed before the console reads
  # anything is lost -- the server then runs until await_server kills it. So the feeder waits for Done, in a log
  # emptied first: the previous run's "Done (" would release it at once.
  : > "$DLOG"
  ( for _ in $(seq 1 240); do grep -aq 'Done (' "$DLOG" 2>/dev/null && break; sleep 1; done; sleep 2; echo stop ) \
    | RUNDIR="$DRUN" FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.definedClassEvidence=$DEFINED" \
    "$KERNEL/run/launch-kernel-server.sh" > "$DLOG" 2>&1 &
  DPID=$!
  record_server_pid "$DRUN" "$DPID"
  await_server "$DPID" "$DLOG" 300
  if grep -aqE "^net/minecraft/world/entity/Entity	" "$DEFINED"/definitions-*/definitions.tsv 2>/dev/null \
     && grep -aqE "^net/fabricmc/fabric/" "$DEFINED"/definitions-*/definitions.tsv 2>/dev/null \
     && [ -f "$(ls -d "$DEFINED"/definitions-* 2>/dev/null | head -1)/intact" ]; then
    echo "[kernel] PASS the kernel's final Entity was captured with fabric-api's classes, and every definition recorded ($DEFINED)"
  else
    echo "[kernel] FAIL no complete capture with Entity and fabric-api's classes in it — see $DLOG"; FAIL=1
  fi
fi
# M0_CAPTURE_END

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
"$KERNEL/gradlew" --offline -p "$KERNEL" cleanTest test -Pforbric.sharedFinalDefined="$DEFINED" >"$TESTLOG" 2>&1
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

step "3. --scan across both fixture mod sets (Fabric and NeoForge, no MinecraftForge)"
kernel_classpath
for set in server-merged client-merged; do
  dir="$RUN_OLD/$set/mods"
  [ -d "$dir" ] || { echo "[kernel] FAIL $set (required fixture missing: $dir)"; FAIL=1; continue; }
  out="$BUILD/scan/$set.json"; mkdir -p "$BUILD/scan"
  kernel_scan "$dir" "$out" >/dev/null
  # well-formed + non-empty + both loaded ecosystems represented, and no MinecraftForge mod reported
  # M0_SCAN_JUDGE_BEGIN (GateM0CaptureStepTest, the red control, runs this exact judge on a scan JSON with a FORGE row)
  python3 - "$out" "$set" <<'PY'
import json,sys
o=json.loads(open(sys.argv[1]).read()); s=sys.argv[2]
ecos={m["ecosystem"] for m in o["mods"]}
ok = o["count"]>0 and {"FABRIC","NEOFORGE"}<=ecos and "FORGE" not in ecos
print(f"[kernel] {'PASS' if ok else 'FAIL'} scan {s}: {o['count']} mods, ecosystems={sorted(ecos)}")
sys.exit(0 if ok else 1)
PY
  [ $? -eq 0 ] || FAIL=1
  # M0_SCAN_JUDGE_END
done

step "4. the game base links (C6)"
# The link check lived only in build-merged-base.sh and was then run here against the merged base, the MinecraftForge
# interop jar and a baseline recorded on that pair. None of those inputs exists on the genuine stage: the base is
# NeoForge's own patched game and C6 reseeds the check for it, in the kernel tree, with access levels and invokedynamic
# handles (D32). Until C6 lands the step cannot give a verdict on anything, so it says so on a line of its own (D64) --
# never a FAIL (a known red is not allowed in C5) and never silence (a dropped check would look like a passed one).
# C6's acceptance removes this line. The installer's own link gate (step 6) still runs on what the installer ships.
echo "[kernel] SKIP (C6: the link check is reseeded on the game base in C6 -- kernel tree, access levels, invokedynamic handles (D32); its merged-base inputs no longer exist)"

step "5. differential oracle (kernel parser vs independent ground truth)"
ORACLELOG="$BUILD/gate-m0-oracle.log"
"$KERNEL/run/diff-oracle.sh" "$RUN_OLD/server-merged/mods" "$RUN_OLD/client-merged/mods" >"$ORACLELOG" 2>&1 || FAIL=1
cat "$ORACLELOG"

step "5b. negative control: the same sets with the refusals and set-asides withheld (must FAIL on exactly those mods)"
# Step 5 accepts a MinecraftForge mod the kernel left out only on the kernel's own refusal of its jar, or as the
# set-aside half of a jar whose Fabric or NeoForge half the kernel reported. Withhold both and the same scans must turn
# into misses -- exactly the mods step 5 named as refused or set aside, in each set, and nothing else. If the oracle
# still passed, its acceptance would not be reading them at all; and with no refused or no set-aside mod in the sets
# that acceptance (and this control) would be measuring nothing, which is a failure here too.
CONTROLLOG="$BUILD/gate-m0-oracle-control.log"
if ORACLE_CONTROL=unrefused "$KERNEL/run/diff-oracle.sh" "$RUN_OLD/server-merged/mods" "$RUN_OLD/client-merged/mods" \
    >"$CONTROLLOG" 2>&1; then
  echo "[kernel] FAIL the oracle passed with the refusal lines withheld — see $CONTROLLOG"; FAIL=1
fi
python3 - "$ORACLELOG" "$CONTROLLOG" <<'PY' || FAIL=1
import ast, re, sys
main, control = (open(p, errors="replace").read().splitlines() for p in sys.argv[1:3])
refused = {}  # set -> {(FORGE, id)} step 5 accepted as refused by name or as a set-aside half
kinds = {"refused": 0, "set aside": 0}
for line in main:
    m = re.match(r"\[oracle\] PASS (\S+): (.*)$", line)
    if not m:
        continue
    for note in m.group(2).split("; "):
        for marker, kind in (("refused them by name: ", "refused"), ("set aside beside a loaded Fabric or NeoForge half: ", "set aside")):
            if marker in note:
                ids = {("FORGE", part.split(" (")[0]) for part in note.split(marker, 1)[1].split(", ")}
                refused.setdefault(m.group(1), set()).update(ids)
                kinds[kind] += len(ids)
missed, other = {}, []
for line in control:
    m = re.match(r"\[oracle\] FAIL (\S+): kernel MISSED (\[.*?\]) \(", line)
    if m:
        missed[m.group(1)] = set(ast.literal_eval(m.group(2)))
    elif line.startswith("[oracle] FAIL"):
        other.append(line)
ok = all(kinds.values()) and missed == refused and not other
total = sum(len(v) for v in refused.values())
if ok:
    print(f"[kernel] PASS withholding the refusals and set-asides fails the oracle on exactly the {total} mod(s) "
          f"({kinds['refused']} refused, {kinds['set aside']} set aside) of {len(refused)} set(s), and on nothing else")
else:
    print(f"[kernel] FAIL withholding the refusals and set-asides did not fail the oracle on exactly those mods ({kinds}): "
          f"refused={sorted((s, sorted(v)) for s, v in refused.items())} "
          f"control missed={sorted((s, sorted(v)) for s, v in missed.items())} other={other}")
sys.exit(0 if ok else 1)
PY

step "6. the evidence, soak and link-gate tools' own tests"
# These tools decide what a release is. Their tests used to run only by hand, so a regression in the recorder
# or the link gate would have been found by the release it waved through. test_gate_locale holds every gate to
# handing its game JVMs the caller's FORBRIC_JVM: without it a sweep run in another language tests the host's.
# test_gate_cache and test_control_arm hold the verdict cache and lib.sh's control arms to what they must see: every
# gate that runs tests keys on them, every fixture a gate stages is hashed, every environment knob a script reads is
# in the key. They ran only under toolTest and CI before, so a sweep could reuse a stale GREEN they would have caught
# (review OPT wave 2d, both lenses).
TOOLLOG="$BUILD/gate-m0-tooling.log"
if (cd "$KERNEL/run/compat" && python3 -m unittest -v test_evidence test_soak test_world_parity test_gate_locale \
      test_gate_cache test_control_arm) >"$TOOLLOG" 2>&1; then
  ran=$(grep -aoE '^Ran [0-9]+ tests?' "$TOOLLOG" | grep -oE '[0-9]+' | tail -1)
  if [ "${ran:-0}" -gt 0 ] && grep -aqE '^OK$' "$TOOLLOG"; then
    echo "[kernel] PASS evidence, soak, locale and cache tool tests ($ran ran, none skipped)"
  else
    echo "[kernel] FAIL evidence, soak, locale and cache tool tests — ${ran:-0} ran, or some were skipped; see $TOOLLOG"; FAIL=1
  fi
else
  echo "[kernel] FAIL evidence, soak, locale and cache tool tests — see $TOOLLOG"; FAIL=1
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
# And the installer build bundles the tracked baseline, not the one it bundled last (in a throwaway copy).
BUNDLELOG="$BUILD/gate-m0-bundled-baseline.log"
bash "$KERNEL/../forbric-kernel-installer/run/test-bundled-baseline.sh" >"$BUNDLELOG" 2>&1
BUNDLE_RC=$?
if [ "$BUNDLE_RC" -eq 0 ]; then
  check "the installer bundles the tracked link baseline" "ALL GREEN" "$BUNDLELOG"
elif [ "$BUNDLE_RC" -eq 3 ]; then
  # Exit 3 is the script saying it could not run (no ../fabric-loader substrate, or no copy of the sources) — not a
  # verdict on the baseline. The first nightly reported a worktree without the substrate as "a stale link baseline".
  echo "[kernel] FAIL could not check the installer's bundled link baseline: $(tail -1 "$BUNDLELOG")"; FAIL=1
else
  echo "[kernel] FAIL the installer bundles a stale link baseline — see $BUNDLELOG"; FAIL=1
fi
# And -Pforbric.stagedRoot keeps its documented use (a root that does not exist still configures the boot-jar
# build) while the test task still refuses a root its tests cannot resolve.
STAGEDLOG="$BUILD/gate-m0-staged-root.log"
if bash "$KERNEL/run/test-staged-root.sh" >"$STAGEDLOG" 2>&1; then
  check "a staged-root override is refused by the test task only" "ALL GREEN" "$STAGEDLOG"
else
  echo "[kernel] FAIL a staged-root override breaks the build or reaches the tests — see $STAGEDLOG"; FAIL=1
fi

step "M0 result"
if [ "$FAIL" -eq 0 ]; then echo "[kernel] ✅ M0 GATE GREEN"; else echo "[kernel] ❌ M0 GATE RED"; fi
exit "$FAIL"
