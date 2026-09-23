#!/usr/bin/env bash
# M33: public world lookup routing, six directed transfers, invalidation, and two-boot persistence.
# GATE-PARALLEL: rundirs=server-transfer-m33 mem=2000
set -uo pipefail
GATE_PORT="${GATE_PORT:-25593}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
RUNDIR="$KERNEL/run/server-transfer-m33"
RESULTS="$BUILD/verification/m33-transfer"
mkdir -p "$RESULTS"

# Each phase is a separately hash-bound acceptance command. It is deliberately red when the probe is red;
# the outer gate recognises only the named bridge-off run as an expected negative control.
if [ "${1:-}" = "--execute-phase" ]; then
  phase="$2"; token="$3"; bridge="$4"
  case "$phase" in prepare|reload|negative) ;; *) exit 2;; esac
  result="$RESULTS/$phase-probe.json"
  rm -f "$result"
  RUNDIR="$RUNDIR" FORBRIC_COMPAT_POLICY=strict \
    FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.compatibilityPolicy=strict -Dforbric.transferBridge=$bridge -Dforbric.transferCanaryPhase=$phase -Dforbric.transferCanaryToken=$token -Dforbric.transferCanaryRoot=$RUNDIR -Dforbric.transferCanaryOutput=$result" \
    "$KERNEL/run/launch-kernel-server.sh" </dev/null
  code=$?
  [ "$code" -eq 0 ] || exit "$code"
  python3 - "$result" "$phase" "$token" <<'PY'
import json, sys
with open(sys.argv[1], encoding='utf-8') as stream: result = json.load(stream)
assert result['phase'] == sys.argv[2], result
assert result['runToken'] == sys.argv[3], 'stale or foreign probe result'
assert result['pass'] is True, result
assert result['items'] == 60 and result['fluidFabricUnits'] == 48617, result
if result['phase'] != 'reload': assert result['itemRoutes'] == result['fluidRoutes'] == 12, result
PY
  exit "$?"
fi

step "build three independently declared ecosystem canaries"
bash "$KERNEL/run/build-transfer-world-canaries.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }
INPUTS="$KERNEL/run/canary/m33-build-inputs.json"
FAPI="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["fabricApi"]["path"])' "$INPUTS")"
MERGED="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["merged"]["path"])' "$INPUTS")"
FORGE_RT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["forge"]["path"])' "$INPUTS")"
NEO_RT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["neo"]["path"])' "$INPUTS")"
COMPILE_GAME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["compileGame"]["path"])' "$INPUTS")"
export MERGED FORGE_RT NEO_RT

fresh_world() {
  if [ -d "$RUNDIR/world" ] && [ ! -f "$RUNDIR/.m33-owned" ]; then
    echo "[kernel] FAIL refusing to replace a world without M33 ownership proof: $RUNDIR/world"; exit 1
  fi
  local stale command
  stale="$(cat "$RUNDIR/.forbric-gate.pid" 2>/dev/null || true)"
  case "$stale" in
    ''|*[!0-9]*) ;;
    *) if kill -0 "$stale" 2>/dev/null; then
         command="$(ps -p "$stale" -o command= 2>/dev/null || true)"
         if [[ "$command" != *"$KERNEL/run/compat/evidence.py"* || "$command" != *"$RESULTS/"* ]]; then
           echo "[kernel] FAIL stale PID no longer belongs to M33; refusing to terminate it: $stale"; exit 1
         fi
         kill_owned_descendants "$stale"; kill -9 "$stale" 2>/dev/null || true
       fi ;;
  esac
  rm -f "$RUNDIR/.forbric-gate.pid"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel"
  mkdir -p "$RUNDIR/mods"
  python3 -c 'import uuid; print(uuid.uuid4())' > "$RUNDIR/.m33-owned"
  cp "$FAPI" "$KERNEL/run/canary/forbrictransferfabric.jar" "$KERNEL/run/canary/forbrictransferforge.jar" \
    "$KERNEL/run/canary/forbrictransferneo.jar" "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  printf '\nlevel-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=-1\n' >> "$RUNDIR/server.properties"
}

# Evidence owns an inner shell and the JVM below it. Recursively terminate only this run's descendants on a
# timeout, then let evidence.py record the failed command and unchanged/different inputs.
kill_owned_descendants() {
  local parent="$1" child
  for child in $(pgrep -P "$parent" 2>/dev/null || true); do
    kill_owned_descendants "$child"
    kill -9 "$child" 2>/dev/null || true
  done
}
run_phase() {
  local phase="$1" bridge="$2" token pid i
  python3 -c 'import uuid; print(uuid.uuid4())' > "$RUNDIR/.m33-owned"
  token="$(cat "$RUNDIR/.m33-owned")"
  python3 "$KERNEL/run/compat/evidence.py" run --source "$KERNEL/.." \
    --artifact "merged=$MERGED" --artifact "forge-interop=$FORGE_RT" --artifact "neo-runtime=$NEO_RT" \
    --artifact "kernel=$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar" \
    --artifact "kernel-runtime=$BUILD/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar" \
    --artifact "compile-game=$COMPILE_GAME" \
    --artifact "fabric-api=$FAPI" --mods "$RUNDIR/mods" --output "$RESULTS/$phase-inputs.json" \
    -- bash "$KERNEL/run/gate-m33-transfer.sh" --execute-phase "$phase" "$token" "$bridge" \
    > "$RESULTS/$phase-driver.log" 2>&1 &
  pid=$!; record_server_pid "$RUNDIR" "$pid"
  for i in $(seq 1 "${M33_TIMEOUT:-240}"); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "[kernel] FAIL M33 phase $phase timed out"; FAIL=1
    kill_owned_descendants "$pid"
    for i in $(seq 1 15); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
  rm -f "$RUNDIR/.forbric-gate.pid"
}
assert_phase() {
  local phase="$1" expected="$2"
  if python3 - "$RESULTS" "$phase" "$expected" "$(cat "$RUNDIR/.m33-owned")" <<'PY'
import hashlib, json, pathlib, sys
root, phase, expected = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3] == 'pass'
outcome = json.loads((root / (phase + '-inputs.result.json')).read_text())
probe_path = root / (phase + '-probe.json')
probe = json.loads(probe_path.read_text())
assert outcome['inputsUnchanged'] is True, outcome
assert outcome['commandPassed'] is expected, outcome
assert probe['pass'] is expected and probe['phase'] == phase, probe
assert probe['runToken'] == sys.argv[4], 'stale or foreign probe result'
if expected:
    assert outcome['exitCode'] == 0 and probe['items'] == 60 and probe['fluidFabricUnits'] == 48617, (outcome, probe)
else:
    assert outcome['exitCode'] != 0 and 'missing ' in probe['detail'] and ' provider for ' in probe['detail'], (outcome, probe)
(root / (phase + '-probe.sha256')).write_text(hashlib.sha256(probe_path.read_bytes()).hexdigest() + '\n')
PY
  then echo "[kernel] PASS M33 $phase has the expected $expected result and unchanged hashed inputs"
  else echo "[kernel] FAIL M33 $phase result or evidence invalid"; FAIL=1
  fi
}

fresh_world
step "positive: six directed routes through real public block queries"
run_phase prepare on
assert_phase prepare pass
check_absent "positive probe emitted no failure marker" "M33Transfer\] FAIL" "$RESULTS/prepare-inputs.log"
for family in fabric forge neo; do check "the independent $family canary initialized" "M33Transfer\] REGISTERED $family " "$RESULTS/prepare-inputs.log"; done
check "public API face/null coverage ran" "PASS all three public APIs preserve NORTH/null" "$RESULTS/prepare-inputs.log"
check "native priority ran" "PASS native providers take priority" "$RESULTS/prepare-inputs.log"
check "owner providers precede generic Container wrappers" "PASS owner providers precede Fabric's generic Container view" "$RESULTS/prepare-inputs.log"
check "Forge's own InvWrapper answers only as its owner's whole Container" "PASS Forge's generic InvWrapper: NeoForge's own Container view" "$RESULTS/prepare-inputs.log"
check "a Container with its own writes was kept from NeoForge's wrapper" "CONTAINER_WRITES_NOT_VANILLA: forbric\.transferworld\.Machines.Kiln" "$RESULTS/prepare-inputs.log"
check_absent "Forge's own InvWrapper was never refused as an unaudited handler" "FORGE_HANDLER_NOT_ROLLBACK_SAFE: net\.minecraftforge\.items\.wrapper\.InvWrapper" "$RESULTS/prepare-inputs.log"
check "actual fractional return was rolled back and retried" "PASS world fluid quantization retains 17 units" "$RESULTS/prepare-inputs.log"
check "world replacement invalidation ran" "PASS cached foreign views" "$RESULTS/prepare-inputs.log"
check "a real chunk unload and reload ran" "PASS chunk unload: cached views refuse" "$RESULTS/prepare-inputs.log"

step "reload the same gate-owned world and verify persisted components and quantities"
run_phase reload on
assert_phase reload pass
check_absent "reload probe emitted no failure marker" "M33Transfer\] FAIL" "$RESULTS/reload-inputs.log"
check "real deserialization was verified" "PASS save/reload:" "$RESULTS/reload-inputs.log"

step "negative control: bridge off must make the same world-query assertions RED"
fresh_world
run_phase negative off
assert_phase negative fail
check "negative failed in a foreign public lookup" "M33Transfer\] FAIL phase=negative.*missing .* provider" "$RESULTS/negative-inputs.log"
check_absent "negative never claimed probe success" "M33Transfer\] PASS phase=negative" "$RESULTS/negative-inputs.log"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M33 TRANSFER WORLD GATE GREEN — public routing, save/reload and red bridge-off control proved"
else
  echo "[kernel] ❌ M33 GATE RED — evidence and phase logs: $RESULTS"
fi
exit "$FAIL"
