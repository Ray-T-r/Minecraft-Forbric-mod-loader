#!/usr/bin/env bash
# M35: actual game callers in a fresh world (NeoForge's portal, spawner-finalize and item-finish events through the live
# BaseFireBlock, BaseSpawner and LivingEntity callers), seven cases, and a premise control that must go red exactly.
#
# STAGE 4 (the game base is NeoForge's own patched game). The three repair-off controls are gone with what they switched
# off: portal-off (-Dforbric.portalSpawn, PortalSpawnInjector composed NeoForge's and MinecraftForge's portal hooks),
# spawner-off (-Dforbric.spawnerFinalize, SpawnerFinalizeInjector) and item-off (-Dforbric.unifiedEvents, the
# NeoForge->MinecraftForge item-finish bridge) are all retired switches whose code was deleted in C2 (D22, D29): on this
# base BaseFireBlock.onPlace asks only EventHooks.onTrySpawnPortal and EventHooks.finalizeMobSpawnSpawner is NeoForge's
# own (javap: invokevirtual Mob.finalizeSpawn; pop), so each switch only warns and its phase could not fail. So did the
# javap read of the merged base's portal-hook order and the MinecraftForge carrier this gate required. No kernel
# transformer touches these seven paths (NativeCoremodParity's targets have no BaseSpawner, BaseFireBlock or
# LivingEntity), so the replacement control is a premise control (D48): the probe's `withhold` phase keeps every NeoForge
# listener counting its event but withholds its answer, and the cases that judge an answer reaching the game must fail,
# exactly portal-neo-veto, the four spawner-* cases and item-result; portal-allow, which asks only that NeoForge's event
# fired once, holds.
# GATE-PARALLEL: rundirs=server-behavior-m35 mem=2000
set -uo pipefail
GATE_PORT="${GATE_PORT:-25595}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
RUNDIR="$KERNEL/run/server-behavior-m35"
RESULTS="$BUILD/verification/m35-behavior"
mkdir -p "$RESULTS"

if [ "${1:-}" = "--execute-phase" ]; then
  phase="$2"; token="$3"; result="$RESULTS/$phase-probe.json"
  case "$phase" in positive|withhold) ;; *) exit 2 ;; esac
  rm -f "$result" "$RESULTS/$phase-compatibility.json"
  RUNDIR="$RUNDIR" FORBRIC_COMPAT_POLICY=strict \
    FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.relaxGuestMixins=off -Dforbric.behaviorPhase=$phase -Dforbric.behaviorToken=$token -Dforbric.behaviorRoot=$RUNDIR -Dforbric.behaviorOutput=$result" \
    "$KERNEL/run/launch-kernel-server.sh" </dev/null
  code=$?
  if [ -f "$RUNDIR/.forbric-kernel/compatibility-report.json" ]; then
    cp "$RUNDIR/.forbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json"
  fi
  [ "$code" -eq 0 ] || exit "$code"
  python3 - "$result" "$phase" "$token" <<'PY'
import json, sys
proof = json.load(open(sys.argv[1]))
assert proof['phase'] == sys.argv[2] and proof['token'] == sys.argv[3], proof
assert len(proof['cases']) == proof['expectedCases'] == 7, proof
assert proof['pass'] is True, proof
PY
  exit "$?"
fi

step "build the kernel and one explicitly mixed-bus behavioral test mod"
kernel_jar
bash "$KERNEL/run/build-behavior-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }
INPUTS="$KERNEL/run/canary/m35-build-inputs.json"
BASE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["base"]["path"])' "$INPUTS")"
NEO_RT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["neo"]["path"])' "$INPUTS")"
COMPILE_GAME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["compileGame"]["path"])' "$INPUTS")"
# The launcher's own inputs: the game base and its carrier the probe was compiled and recorded against.
export BASE NEO_RT

kill_owned_descendants() {
  local parent="$1" child
  for child in $(pgrep -P "$parent" 2>/dev/null || true); do
    kill_owned_descendants "$child"; kill -9 "$child" 2>/dev/null || true
  done
}
fresh_world() {
  if [ -d "$RUNDIR/world" ] && [ ! -f "$RUNDIR/.m35-owned" ]; then
    echo "[kernel] FAIL refusing to replace a world without M35 ownership proof"; exit 1
  fi
  local stale command
  stale="$(cat "$RUNDIR/.forbric-gate.pid" 2>/dev/null || true)"
  case "$stale" in
    ''|*[!0-9]*) ;;
    *) if kill -0 "$stale" 2>/dev/null; then
         command="$(ps -p "$stale" -o command= 2>/dev/null || true)"
         if [[ "$command" != *"$KERNEL/run/compat/evidence.py"* || "$command" != *"$RESULTS/"* ]]; then
           echo "[kernel] FAIL stale PID no longer belongs to M35; refusing to kill $stale"; exit 1
         fi
         kill_owned_descendants "$stale"; kill -9 "$stale" 2>/dev/null || true
       fi ;;
  esac
  rm -f "$RUNDIR/.forbric-gate.pid"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel"
  mkdir -p "$RUNDIR/mods"
  python3 -c 'import uuid; print(uuid.uuid4())' > "$RUNDIR/.m35-owned"
  cp "$KERNEL/run/canary/forbricbehaviorprobe.jar" "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  printf '\nlevel-name=world\nlevel-seed=8035262\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\n' >> "$RUNDIR/server.properties"
}
run_phase() {
  local phase="$1" token pid i
  fresh_world; token="$(cat "$RUNDIR/.m35-owned")"
  rm -f "$RESULTS/$phase-inputs.log"   # port_was_free below must not read an earlier run's log
  python3 "$KERNEL/run/compat/evidence.py" run --source "$KERNEL/.." \
    --artifact "neo-patched=$BASE" --artifact "neo-runtime=$NEO_RT" \
    --artifact "kernel=$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar" \
    --artifact "kernel-runtime=$BUILD/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar" \
    --artifact "compile-game=$COMPILE_GAME" --mods "$RUNDIR/mods" --output "$RESULTS/$phase-inputs.json" \
    -- bash "$KERNEL/run/gate-m35-behavior.sh" --execute-phase "$phase" "$token" \
    > "$RESULTS/$phase-driver.log" 2>&1 &
  pid=$!; record_server_pid "$RUNDIR" "$pid"
  for i in $(seq 1 "${M35_TIMEOUT:-240}"); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then
    echo "[kernel] FAIL M35 $phase timed out"; FAIL=1; kill_owned_descendants "$pid"
    for i in $(seq 1 15); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true; rm -f "$RUNDIR/.forbric-gate.pid"
  port_was_free "$RESULTS/$phase-inputs.log"
  if python3 - "$RESULTS" "$phase" "$token" <<'PY'
import hashlib, json, pathlib, sys
root, phase, token = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
proof_path = root / (phase + '-probe.json')
proof = json.loads(proof_path.read_text())
outcome = json.loads((root / (phase + '-inputs.result.json')).read_text())
assert proof['phase'] == phase and proof['token'] == token, 'stale or foreign proof'
assert len(proof['cases']) == proof['expectedCases'] == 7, proof
names = [row['name'] for row in proof['cases']]
assert set(names) == {'portal-allow', 'portal-neo-veto', 'spawner-data', 'spawner-neo-cancel-finalize',
                      'spawner-neo-veto-spawn', 'spawner-nonempty-input', 'item-result'}, names
failures = {row['name'] for row in proof['cases'] if row['pass'] is not True}
# withhold: NeoForge's listeners still count their events but give no answer (D48's premise control).
expected = {
    'positive': set(),
    'withhold': {'portal-neo-veto', 'spawner-data', 'spawner-neo-cancel-finalize', 'spawner-neo-veto-spawn',
                 'spawner-nonempty-input', 'item-result'},
}[phase]
assert failures == expected, (phase, failures, expected, proof)
assert proof['pass'] is (phase == 'positive'), proof
assert outcome['inputsUnchanged'] is True, outcome
assert outcome['commandPassed'] is (phase == 'positive'), outcome
assert outcome['exitCode'] == (0 if phase == 'positive' else 1), outcome
log = (root / (phase + '-inputs.log')).read_text(errors='replace')
assert 'Done (' in log and '[M35Behavior] ARMED natural item consumption' in log, 'world/action startup absent'
# Every listener was asked in both phases: a withheld answer is not a missing event.
for row in proof['cases']:
    assert row.get('neoEvents') == 1, ('NeoForge was not asked exactly once', phase, row)
(root / (phase + '-probe.sha256')).write_text(hashlib.sha256(proof_path.read_bytes()).hexdigest() + '\n')
print('[kernel] PASS M35', phase, 'expected behavioral failures:', sorted(failures))
PY
  then :
  else echo "[kernel] FAIL M35 $phase evidence or behavior differs"; FAIL=1
  fi
}

for phase in positive withhold; do
  step "M35 actual world behavior: $phase"
  run_phase "$phase"
done
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M35 WORLD BEHAVIOR GATE GREEN — 7 positive cases and the withhold premise control red on exactly its six"
else
  echo "[kernel] ❌ M35 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
