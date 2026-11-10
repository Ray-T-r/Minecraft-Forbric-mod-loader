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
# javap read of the merged base's portal-hook order and the MinecraftForge carrier this gate required.
#
# One kernel transformer does sit on these paths: SpawnPositionCallsInjector (COREMOD, -Dforbric.spawnPositionCalls)
# rewrites BaseSpawner.serverTick, the live caller of the four spawner-* cases, putting vanilla's checkSpawnRules /
# checkSpawnObstruction back after NeoForge's PositionCheck event (KernelSpawnPosition.decide posts the same event and
# uses its answer as NeoForge's hook does). It decides nothing differently, so it cannot be a red control; instead the
# `spawn-position-off` phase runs the same probe with -Dforbric.spawnPositionCalls=off -- NeoForge's own hook bytes -- and
# must pass all seven cases like `positive`: a native A/B on the exact path, not a red control. The gate also asserts
# the rewrite is logged for BaseSpawner in `positive` and absent in `spawn-position-off`, so the A/B is known to differ.
# Nothing else in the kernel rewrites BaseFireBlock.onPlace, BaseSpawner or LivingEntity.completeUsingItem for a probe
# with no mixins (VanillaEarlyReturns and NativeValueStores act only on guest-mixin targets).
#
# The red control is a premise control (D48, amended by D72): the probe's `withhold` phase keeps every NeoForge listener
# counting its event but withholds its answer, and exactly five cases must fail, each for its pinned reason (the failure
# text and the game state the probe recorded): portal-neo-veto, spawner-data, spawner-neo-cancel-finalize,
# spawner-neo-veto-spawn and item-result. portal-allow, which asks only that NeoForge's event fired once, holds.
# Why spawner-nonempty-input, the sixth case PLAN named, is not in the set (D72): a red control that is red for a
# probe-side reason shows nothing. In that case no finalization runs in either phase (BaseSpawner finalizes only a bare
# {id} entity tag), the world inserts the mob and Entity.load sees the spawner's tag in both, so none of NeoForge's
# answers reaches the game there; its withhold red (measured in wave 2) came only from the probe reading back the
# replacement SpawnGroupData its own listener had withheld. That read-back is phase-aware now -- it expects what native
# leaves in the event after the post: the listener's answer in positive, none in withhold (BaseSpawner hands the event
# null and EventHooks pops finalizeSpawn's return, javap) -- so the case passes in withhold, and the gate pins its game
# state there equal to positive's (no finalization, inserted, the tag's marker 35, no SpawnGroupData in the event).
# GATE-PARALLEL: rundirs=server-behavior-m35 mem=2000
set -uo pipefail
GATE_PORT="${GATE_PORT:-25595}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
RUNDIR="$KERNEL/run/server-behavior-m35"
RESULTS="$BUILD/verification/m35-behavior"
mkdir -p "$RESULTS"

if [ "${1:-}" = "--execute-phase" ]; then
  phase="$2"; token="$3"; result="$RESULTS/$phase-probe.json"
  case "$phase" in positive|withhold) ab="" ;; spawn-position-off) ab="-Dforbric.spawnPositionCalls=off" ;; *) exit 2 ;; esac
  rm -f "$result" "$RESULTS/$phase-compatibility.json"
  RUNDIR="$RUNDIR" FORBRIC_COMPAT_POLICY=strict \
    FORBRIC_JVM="${FORBRIC_JVM:-} $ab -Dforbric.relaxGuestMixins=off -Dforbric.behaviorPhase=$phase -Dforbric.behaviorToken=$token -Dforbric.behaviorRoot=$RUNDIR -Dforbric.behaviorOutput=$result" \
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

# A phase runs in the background as evidence.py -> the inner shell -> the server JVM. Two ways that tree outlived the
# gate and held the port (D76): the timeout listed evidence.py's children once and killed evidence.py itself 15 s
# later, so an evidence.py still capturing its inputs at the timeout started the server after the listing and was then
# killed above it, leaving the shell and the JVM re-parented to launchd; and a gate stopped by a signal (heavy.sh
# forwards TERM to the gate alone) never touched its background phase. stop_phase freezes evidence.py before it lists
# anything, so it can start nothing while its tree is collected, and the EXIT trap below stops the phase still in
# flight however the gate ends.
PHASE_PID=""
stop_phase() {
  local pid="$1" child i
  case "$pid" in ''|*[!0-9]*) return 0;; esac
  kill -STOP "$pid" 2>/dev/null || return 0
  if [ -n "$(pgrep -P "$pid" 2>/dev/null)" ]; then
    # It waits on one child: the phase command, or a git call while it captures the inputs (a failed one ends it before
    # the command). With that tree gone it starts nothing more, so thaw it to record the killed command and the inputs.
    for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
    kill -CONT "$pid" 2>/dev/null
    for i in $(seq 1 15); do _running "$pid" || break; sleep 1; done
  fi
  kill_tree "$pid"   # no child yet (thawed, it would start the server), or it did not finish recording in time
}
trap 'rc=$?; kill_tree "$PHASE_PID"; _gate_exit "$rc"' EXIT
trap 'exit 129' HUP; trap 'exit 130' INT; trap 'exit 143' TERM
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
         kill_tree "$stale"
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
  pid=$!; PHASE_PID=$pid; record_server_pid "$RUNDIR" "$pid"
  for i in $(seq 1 "${M35_TIMEOUT:-240}"); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  if kill -0 "$pid" 2>/dev/null; then
    echo "[kernel] FAIL M35 $phase timed out"; FAIL=1; stop_phase "$pid"
  fi
  wait "$pid" 2>/dev/null || true; PHASE_PID=""; rm -f "$RUNDIR/.forbric-gate.pid"
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
red = phase == 'withhold'
# withhold: NeoForge's listeners still count their events but give no answer (D48's premise control).
# spawn-position-off: NeoForge's own position-check hook in BaseSpawner (native A/B), must equal positive.
expected = {
    'positive': set(), 'spawn-position-off': set(),
    'withhold': {'portal-neo-veto', 'spawner-data', 'spawner-neo-cancel-finalize', 'spawner-neo-veto-spawn',
                 'item-result'},
}[phase]
assert failures == expected, (phase, failures, expected, proof)
assert proof['pass'] is (not red), proof
assert outcome['inputsUnchanged'] is True, outcome
assert outcome['commandPassed'] is (not red), outcome
assert outcome['exitCode'] == (1 if red else 0), outcome
log = (root / (phase + '-inputs.log')).read_text(errors='replace')
assert 'Done (' in log and '[M35Behavior] ARMED natural item consumption' in log, 'world/action startup absent'
rewrite = '[Forbric/Spawner] put checkSpawnRules/checkSpawnObstruction back into net.minecraft.world.level.BaseSpawner '
if phase == 'spawn-position-off':
    assert rewrite not in log, 'the BaseSpawner rewrite ran although -Dforbric.spawnPositionCalls=off'
else:
    assert rewrite in log, 'the BaseSpawner rewrite was not logged, so spawn-position-off would not be an A/B'
rows = {row['name']: row for row in proof['cases']}
if red:
    # Each red for its own reason, not merely by name: the failure text and the state the probe recorded.
    def why(name, text, **state):
        row = rows[name]
        assert text in str(row.get('detail', '')), (name, 'reason differs', text, row)
        for key, value in state.items():
            assert row.get(key) == value, (name, key, value, row)
    why('portal-neo-veto', 'original frame result differs', originalPortalBlocks=6)
    why('spawner-data', "NeoForge's replacement SpawnGroupData did not reach finalizer", finalizations=1, neoDataUsed=False)
    why('spawner-neo-cancel-finalize', 'finalization count differs', finalizations=1)
    why('spawner-neo-veto-spawn', 'event cancellation and world-insertion veto were conflated', worldInsertion=True)
    # Not in the set (D72): no answer of NeoForge's reaches the game in this case, so withhold must leave it as positive
    # does -- and its phase-aware read-back finds no SpawnGroupData in the event, which is what native leaves there.
    nonempty = rows['spawner-nonempty-input']
    for key, value in {'pass': True, 'finalizations': 0, 'worldInsertion': True, 'valueInputMarker': 35,
                       'eventDataAfter': 'none'}.items():
        assert nonempty.get(key) == value, ('spawner-nonempty-input', key, value, nonempty)
    item = rows['item-result']
    assert 'detail' not in item and item.get('held') == '1 minecraft:bucket', ('item-result reason differs', item)
# Every listener was asked in both phases: a withheld answer is not a missing event.
for row in proof['cases']:
    assert row.get('neoEvents') == 1, ('NeoForge was not asked exactly once', phase, row)
(root / (phase + '-probe.sha256')).write_text(hashlib.sha256(proof_path.read_bytes()).hexdigest() + '\n')
print('[kernel] PASS M35', phase, 'expected behavioral failures:', sorted(failures),
      '(BaseSpawner rewrite', 'absent)' if phase == 'spawn-position-off' else 'logged)')
PY
  then :
  else echo "[kernel] FAIL M35 $phase evidence or behavior differs"; FAIL=1
  fi
}

for phase in positive spawn-position-off withhold; do
  step "M35 actual world behavior: $phase"
  run_phase "$phase"
done
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M35 WORLD BEHAVIOR GATE GREEN — 7 positive cases, the same 7 on NeoForge's own spawner hook, and the withhold premise control red on exactly its five, each for its pinned reason"
else
  echo "[kernel] ❌ M35 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
