#!/usr/bin/env bash
# M40: block energy between Team Reborn Energy (Fabric), NeoForge and MinecraftForge through the PUBLIC lookups only:
# six directed routes on NORTH and null, face refusal, native precedence, a refused custom Forge store, store limits,
# nested rollback on both engines, replacement invalidation (a Reborn cell's views included), a Fabric addon's explicit
# Reborn provider on a NeoForge block, a Forge battery loaded above its capacity (no crash, no loss, before and after a
# restart), dirty-once per root commit, long/int clamping, and save/reload of the amounts; then the same pack WITHOUT
# Team Reborn Energy (Forge <-> NeoForge only, and no Reborn class ever loaded); then a bridge-off negative control
# that must go RED.
# GATE-PARALLEL: rundirs=server-energy-m40 mem=2000
set -uo pipefail
GATE_PORT="${GATE_PORT:-25594}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
RUNDIR="$KERNEL/run/server-energy-m40"
RESULTS="$BUILD/verification/m40-energy"
# Its own canary output: M33 may be reading run/canary at the same time.
CANARIES="$BUILD/energy-canary"
# Team Reborn Energy 5.0.0 (team_reborn_energy, MIT), the mod a Fabric energy pack installs. Configurable; required.
REBORN="${M40_REBORN_ENERGY:-$OLD/../forbric-kernel/run/energy-api/energy-5.0.0.jar}"
mkdir -p "$RESULTS"

# Each phase is a separately hash-bound acceptance command, deliberately red when the probe is red; the outer gate
# recognises only the named bridge-off run as an expected negative.
if [ "${1:-}" = "--execute-phase" ]; then
  phase="$2"; token="$3"; bridge="$4"
  case "$phase" in prepare|reload|negative|noreborn) ;; *) exit 2;; esac
  result="$RESULTS/$phase-probe.json"
  rm -f "$result"
  classes=""
  # Which classes the JVM really loaded, for the pack without Reborn: the proof that no Reborn class was requested.
  if [ "$phase" = noreborn ]; then rm -f "$RESULTS/noreborn-classes.log"; classes="-Xlog:class+load=info:file=$RESULTS/noreborn-classes.log"; fi
  RUNDIR="$RUNDIR" FORBRIC_COMPAT_POLICY=strict \
    FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.compatibilityPolicy=strict -Dforbric.transferBridge=$bridge -Dforbric.energyCanaryPhase=$phase -Dforbric.energyCanaryToken=$token -Dforbric.energyCanaryRoot=$RUNDIR -Dforbric.energyCanaryOutput=$result $classes" \
    "$KERNEL/run/launch-kernel-server.sh" </dev/null
  code=$?
  [ "$code" -eq 0 ] || exit "$code"
  python3 - "$result" "$phase" "$token" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as stream: result = json.load(stream)
assert result['phase'] == sys.argv[2], result
assert result['runToken'] == sys.argv[3], 'stale or foreign probe result'
assert result['pass'] is True, result
if result['phase'] == 'noreborn': assert result['families'] == 2 and result['routes'] == 4 and result['energy'] == 40000, result
else: assert result['families'] == 3 and result['routes'] == 12 and result['energy'] == 60000, result
PY
  exit "$?"
fi

step "build the transfer canaries and the three energy cells"
if [ ! -f "$REBORN" ]; then
  echo "[kernel] FAIL Team Reborn Energy is required for M40 and was not found: $REBORN (set M40_REBORN_ENERGY)"; exit 1
fi
TRANSFER_CANARY_ENERGY=1 TRANSFER_CANARY_OUT="$CANARIES" M40_REBORN_ENERGY="$REBORN" \
  bash "$KERNEL/run/build-transfer-world-canaries.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }
INPUTS="$CANARIES/m33-build-inputs.json"
field() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]]["path"])' "$INPUTS" "$1"; }
FAPI="$(field fabricApi)"; MERGED="$(field merged)"; FORGE_RT="$(field forge)"; NEO_RT="$(field neo)"
COMPILE_GAME="$(field compileGame)"; REBORN="$(field rebornEnergy)"
export MERGED FORGE_RT NEO_RT

# Evidence owns an inner shell and the JVM below it; a timeout terminates only this run's descendants.
kill_owned_descendants() {
  local parent="$1" child
  for child in $(pgrep -P "$parent" 2>/dev/null || true); do
    kill_owned_descendants "$child"
    kill -9 "$child" 2>/dev/null || true
  done
}
# fresh_world <with-reborn: yes|no>
fresh_world() {
  if [ -d "$RUNDIR/world" ] && [ ! -f "$RUNDIR/.energy-owned" ]; then
    echo "[kernel] FAIL refusing to replace a world without M40 ownership proof: $RUNDIR/world"; exit 1
  fi
  local stale command
  stale="$(cat "$RUNDIR/.forbric-gate.pid" 2>/dev/null || true)"
  case "$stale" in
    ''|*[!0-9]*) ;;
    *) if kill -0 "$stale" 2>/dev/null; then
         command="$(ps -p "$stale" -o command= 2>/dev/null || true)"
         if [[ "$command" != *"$KERNEL/run/compat/evidence.py"* || "$command" != *"$RESULTS/"* ]]; then
           echo "[kernel] FAIL stale PID no longer belongs to M40; refusing to terminate it: $stale"; exit 1
         fi
         kill_owned_descendants "$stale"; kill -9 "$stale" 2>/dev/null || true
       fi ;;
  esac
  rm -f "$RUNDIR/.forbric-gate.pid"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel"
  mkdir -p "$RUNDIR/mods"
  python3 -c 'import uuid; print(uuid.uuid4())' > "$RUNDIR/.energy-owned"
  cp "$FAPI" "$CANARIES/forbrictransferfabric.jar" "$CANARIES/forbricenergyforge.jar" "$CANARIES/forbricenergyneo.jar" "$RUNDIR/mods/"
  if [ "$1" = yes ]; then cp "$REBORN" "$CANARIES/forbricenergyfabric.jar" "$RUNDIR/mods/"; fi
  seed_server_properties "$RUNDIR"
  printf '\nlevel-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=-1\n' >> "$RUNDIR/server.properties"
}
run_phase() {
  local phase="$1" bridge="$2" token pid i reborn=()
  python3 -c 'import uuid; print(uuid.uuid4())' > "$RUNDIR/.energy-owned"
  token="$(cat "$RUNDIR/.energy-owned")"
  [ -f "$RUNDIR/mods/$(basename "$REBORN")" ] && reborn=(--artifact "reborn-energy=$REBORN")
  python3 "$KERNEL/run/compat/evidence.py" run --source "$KERNEL/.." \
    --artifact "merged=$MERGED" --artifact "forge-interop=$FORGE_RT" --artifact "neo-runtime=$NEO_RT" \
    --artifact "kernel=$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar" \
    --artifact "kernel-runtime=$BUILD/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar" \
    --artifact "compile-game=$COMPILE_GAME" --artifact "fabric-api=$FAPI" ${reborn[@]+"${reborn[@]}"} \
    --mods "$RUNDIR/mods" --output "$RESULTS/$phase-inputs.json" \
    -- bash "$KERNEL/run/gate-m40-energy.sh" --execute-phase "$phase" "$token" "$bridge" \
    > "$RESULTS/$phase-driver.log" 2>&1 &
  pid=$!; record_server_pid "$RUNDIR" "$pid"
  for i in $(seq 1 "${M40_TIMEOUT:-240}"); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "[kernel] FAIL M40 phase $phase timed out"; FAIL=1
    kill_owned_descendants "$pid"
    for i in $(seq 1 15); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
  rm -f "$RUNDIR/.forbric-gate.pid"
}
assert_phase() {
  local phase="$1" expected="$2"
  if python3 - "$RESULTS" "$phase" "$expected" "$(cat "$RUNDIR/.energy-owned")" <<'PY'
import hashlib, json, pathlib, sys
root, phase, expected = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3] == 'pass'
outcome = json.loads((root / (phase + '-inputs.result.json')).read_text())
probe_path = root / (phase + '-probe.json')
probe = json.loads(probe_path.read_text())
assert outcome['inputsUnchanged'] is True, outcome
assert outcome['commandPassed'] is expected, outcome
assert probe['pass'] is expected and probe['phase'] == phase, probe
assert probe['runToken'] == sys.argv[4], 'stale or foreign probe result'
if not expected:
    assert outcome['exitCode'] != 0 and 'missing ' in probe['detail'] and ' provider for ' in probe['detail'], (outcome, probe)
elif phase == 'noreborn':
    assert outcome['exitCode'] == 0 and probe['families'] == 2 and probe['routes'] == 4 and probe['energy'] == 40000, (outcome, probe)
else:
    assert outcome['exitCode'] == 0 and probe['families'] == 3 and probe['routes'] == 12 and probe['energy'] == 60000, (outcome, probe)
(root / (phase + '-probe.sha256')).write_text(hashlib.sha256(probe_path.read_bytes()).hexdigest() + '\n')
PY
  then echo "[kernel] PASS M40 $phase has the expected $expected result and unchanged hashed inputs"
  else echo "[kernel] FAIL M40 $phase result or evidence invalid"; FAIL=1
  fi
}
# count_exactly <what> <grep-pattern> <file> <n>
count_exactly() {
  local got
  if ! readable "$3"; then printf '[kernel] FAIL %s (no log to read: %s)\n' "$1" "$3"; FAIL=1; return; fi
  got=$(grep -acE "$2" "$3" 2>/dev/null || true)
  if [ "${got:-0}" -eq "$4" ]; then printf '[kernel] PASS %s (%s)\n' "$1" "$got"
  else printf '[kernel] FAIL %s (want exactly %s got %s)\n' "$1" "$4" "${got:-0}"; FAIL=1; fi
}

fresh_world yes
step "positive: six directed energy routes through the real public lookups"
run_phase prepare on
assert_phase prepare pass
LOG="$RESULTS/prepare-inputs.log"
check_absent "positive probe emitted no failure marker" "M40Energy\] FAIL" "$LOG"
for family in fabric forge neo; do check "the independent $family energy cell initialized" "M40Energy\] REGISTERED $family " "$LOG"; done
check "the kernel connected Team Reborn Energy" "Forbric/Transfer\] connected Team Reborn Energy" "$LOG"
check "public lookups preserve NORTH/null and refuse SOUTH" "PASS all public energy lookups preserve NORTH/null and refuse SOUTH" "$LOG"
check "native providers take priority and the owner answers first" "PASS native energy providers take priority" "$LOG"
check "a custom Forge store was refused, a standard-shaped subclass bridged" "PASS a custom Forge energy store gets no write bridge" "$LOG"
count_exactly "the custom Forge store was reported once for its class" "FORGE_HANDLER_NOT_ROLLBACK_SAFE: forbric\.transferworld\.energy\.ForbricEnergyForge\\\$Rogue" "$LOG" 1
check_absent "Forge's standard EnergyStorage and its subclass were never refused" "FORGE_HANDLER_NOT_ROLLBACK_SAFE: (net\.minecraftforge\.energy\.EnergyStorage|forbric\.transferworld\.EnergyMachines)" "$LOG"
check_absent "the final Forge EnergyStorage carried its transfer-shape certificate" "TRANSFER_HELPER_UNVERIFIED: net\.minecraftforge\.energy\.EnergyStorage" "$LOG"
check "store limits are the stores' own" "PASS capacity, maxInsert and maxExtract are each store's own" "$LOG"
check "all twelve routes ran" "M40Energy\] PASS route " "$LOG" 12
check "nested rollback on both engines" "PASS nested commit then root abort restored every cell" "$LOG"
check "replacement invalidated every cached view" "PASS cached foreign energy views and Forge LazyOptional cannot write replaced" "$LOG"
check "cached views of a replaced Reborn cell moved nothing, fresh ones reached the new cell" "PASS cached NeoForge and Forge views of a replaced Reborn cell move nothing" "$LOG"
check "a Fabric addon's explicit provider reached NeoForge and Forge consumers" "PASS a Fabric addon's explicit Reborn provider on a NeoForge block" "$LOG"
check "an overfull Forge battery moved nothing on bridged insertion" "PASS an overfull Forge battery \(1500/1000\) moves nothing" "$LOG"
check_absent "no energy provider answer was rejected" "Energy provider returned an invalid amount" "$LOG"
check "one root commit dirtied the block entity once" "PASS clean chunk -> abort stays clean -> one root commit dirties it once" "$LOG"
check "long amounts clamp to int without loss" "PASS long energy: int views saturate" "$LOG"

step "reload the same gate-owned world and verify persisted energy"
run_phase reload on
assert_phase reload pass
check_absent "reload probe emitted no failure marker" "M40Energy\] FAIL" "$RESULTS/reload-inputs.log"
check "real deserialization was verified" "M40Energy\] PASS save/reload:" "$RESULTS/reload-inputs.log"
check "the overfull Forge battery still moved nothing after a restart and drained into its bounds" "PASS an overfull Forge battery reloaded at 1500/1000" "$RESULTS/reload-inputs.log"

step "a pack without Team Reborn Energy: Forge and NeoForge still bridge, no Reborn class is loaded"
fresh_world no
run_phase noreborn on
assert_phase noreborn pass
LOG="$RESULTS/noreborn-inputs.log"; CLASSES="$RESULTS/noreborn-classes.log"
check_absent "the Reborn-free probe emitted no failure marker" "M40Energy\] FAIL" "$LOG"
check "Forge <-> NeoForge routes ran on both faces" "M40Energy\] PASS route " "$LOG" 4
check "an overfull Forge battery moved nothing on bridged insertion without Reborn" "PASS an overfull Forge battery \(1500/1000\) moves nothing" "$LOG"
check_absent "the kernel did not try to connect Team Reborn Energy" "Team Reborn Energy|transfer-energy" "$LOG"
check "the energy path really ran in this JVM" "net\.forbric\.kernel\.runtime\.transfer\.ForgeEnergyAdapters " "$CLASSES"
check_absent "no Team Reborn Energy class and no Reborn half of the bridge was loaded" "team\.reborn\.|RebornEnergy" "$CLASSES"

step "negative control: bridge off must make the same energy assertions RED"
fresh_world yes
run_phase negative off
assert_phase negative fail
check "negative failed in a foreign public lookup" "M40Energy\] FAIL phase=negative.*missing .* provider" "$RESULTS/negative-inputs.log"
check_absent "negative never claimed probe success" "M40Energy\] PASS phase=negative" "$RESULTS/negative-inputs.log"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M40 ENERGY WORLD GATE GREEN — six public routes, save/reload, a Reborn-free pack and a red bridge-off control"
else
  echo "[kernel] ❌ M40 GATE RED — evidence and phase logs: $RESULTS"
fi
exit "$FAIL"
