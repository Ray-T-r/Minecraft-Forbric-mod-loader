#!/usr/bin/env bash
# M33: public world lookup routing between Fabric and NeoForge, both directed transfers, invalidation, and two-boot
# persistence.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). Three machine mods rode in this gate, one per ecosystem,
# and the probe moved items and fluids along all six directed routes between them. Forbric now refuses a
# MinecraftForge-only jar, and the strict launch this gate uses stops on that refusal (exit 78) before the world loads,
# so the MinecraftForge machine mod (forbrictransferforge) is no longer staged; the shared build script still builds it
# (gate-m40 builds through the same script) and nothing here copies it. With it went every check whose provider or
# consumer was MinecraftForge's -- the probe no longer asks MinecraftForge's capabilities either, since only a
# MinecraftForge mod would: the four routes with a Forge end, the Forge crate, Forge's InvWrapper as a Forge bin's
# whole Container (NeoForge's own Container view of it, and none for the Forge kiln, whose CONTAINER_WRITES_NOT_VANILLA
# finding the bridge only raises for a Forge owner), a Forge consumer's view of the NeoForge cabinet and of a Fabric
# bin, and every Forge LazyOptional. What stays is everything between Fabric and NeoForge: face/null preservation and
# SOUTH refusal, native priority, owner precedence over Fabric's generic Container view, both directed routes on NORTH
# and null with the 17-unit fractional return, the real chunk unload, save/reload, and the bridge-off control.
# Replacement invalidation stood on a Forge machine and was moved, not dropped: it now caches a Fabric consumer's views
# of a NeoForge machine and a NeoForge consumer's views of a Fabric machine. The clean-chunk dirty probe was not carried
# over intact. Its Forge machine's ItemStackHandler/FluidTank never mark their host changed, so only the bridge's own
# dirty notification on commit (BlockTransferBridge.Endpoint.committed, reached only through the Forge item, fluid and
# energy adapters) could dirty that chunk and carry the writes through save/reload; with no MinecraftForge handler
# aboard that notification is no longer reached, and that check is dropped. The probe that replaces it is a NeoForge
# machine, which marks itself changed when its own handlers' contents change, written through public Fabric queries: it
# proves an aborted Fabric transaction leaves the chunk clean (the bridge does not commit NeoForge's root) and a
# committed one dirties it and survives save/reload. The probe also asks NeoForge for the NeoForge cabinet on the face
# its owner refuses, and NeoForge must get nothing there. The kernel's own
# "initialized cross-ecosystem block transfer" line does not depend on a MinecraftForge mod (it prints when Fabric's and
# NeoForge's transfer APIs and the kernel's transfer classes are present and -Dforbric.transferBridge is not off), so it
# is now asserted on both boots and asserted absent with the bridge off. The census is two primary machines now:
# 40 items, 32,417 Fabric fluid units, 4 item and 4 fluid routes.
#
# STAGE 4 (the game base is NeoForge's own patched game). There is no merged base and no MinecraftForge carrier any more,
# so the gate launches and attests what the fixture build recorded: the base (NeoForge's patched game, evidence role
# neo-patched) and the NeoForge carrier, exported as the launcher's BASE and NEO_RT. The refused-face query on the
# NeoForge cabinet now guards Fabric's generic Container view: Fabric API wraps any Container on every face, and the
# bridge must not hand that wrapper to a NeoForge consumer for a NeoForge owner (BlockTransferBridge.neoFallback asks
# TransferPrecedence, which admits only Fabric's explicit providers for a NeoForge owner). The check that MinecraftForge's
# InvWrapper was never refused as an unaudited handler is gone: no net.minecraftforge class is on this base, so it could
# only pass vacuously, and the cabinet's refused-face query in the probe is what reaches that path now.
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
assert result['items'] == 40 and result['fluidFabricUnits'] == 32417, result
if result['phase'] != 'reload': assert result['itemRoutes'] == result['fluidRoutes'] == 4, result
PY
  exit "$?"
fi

step "build the independently declared ecosystem canaries (Fabric and NeoForge)"
bash "$KERNEL/run/build-transfer-world-canaries.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }
INPUTS="$KERNEL/run/canary/m33-build-inputs.json"
FAPI="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["fabricApi"]["path"])' "$INPUTS")"
BASE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["base"]["path"])' "$INPUTS")"
NEO_RT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["neo"]["path"])' "$INPUTS")"
COMPILE_GAME="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["compileGame"]["path"])' "$INPUTS")"
# The launcher's own inputs: the game base and its carrier the canaries were built and recorded against.
export BASE NEO_RT

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
         kill_tree "$stale"
       fi ;;
  esac
  rm -f "$RUNDIR/.forbric-gate.pid"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel"
  mkdir -p "$RUNDIR/mods"
  python3 -c 'import uuid; print(uuid.uuid4())' > "$RUNDIR/.m33-owned"
  cp "$FAPI" "$KERNEL/run/canary/forbrictransferfabric.jar" "$KERNEL/run/canary/forbrictransferneo.jar" "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  printf '\nlevel-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=-1\n' >> "$RUNDIR/server.properties"
}

# A phase runs in the background as evidence.py -> the inner shell -> the server JVM. Two ways that tree outlived the
# gate and held the port (D76): the timeout listed evidence.py's children once and killed evidence.py itself 15 s
# later, so an evidence.py still capturing its inputs at the timeout started the server after the listing and was then
# killed above it, leaving the shell and the JVM re-parented to launchd; and a gate stopped by a signal (heavy.sh
# forwards TERM to the gate alone) never touched its background phase. stop_phase freezes evidence.py before it lists
# anything, so it can start nothing while its tree is collected, and the EXIT trap below stops the phase still in
# flight and removes its pid file, so a later process that reuses that pid is not taken for this gate's stale phase.
# The trap runs when the gate ends on its own or on a TERM, which is what heavy.sh forwards (and what it turns its own
# INT and HUP into). The gate's own HUP and INT traps take effect only where those signals were not ignored when it
# started: an async child of a non-interactive shell, as the gate is under heavy.sh, starts with INT ignored, and nohup
# ignores HUP. A SIGKILL to the gate runs no trap; the next run's fresh_world reaps the phase its pid file names.
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
trap 'rc=$?; if [ -n "$PHASE_PID" ]; then kill_tree "$PHASE_PID"; rm -f "$RUNDIR/.forbric-gate.pid"; fi; _gate_exit "$rc"' EXIT
trap 'exit 129' HUP; trap 'exit 130' INT; trap 'exit 143' TERM
run_phase() {
  local phase="$1" bridge="$2" token pid i
  python3 -c 'import uuid; print(uuid.uuid4())' > "$RUNDIR/.m33-owned"
  token="$(cat "$RUNDIR/.m33-owned")"
  rm -f "$RESULTS/$phase-inputs.log"   # port_was_free below must not read an earlier run's log
  python3 "$KERNEL/run/compat/evidence.py" run --source "$KERNEL/.." \
    --artifact "neo-patched=$BASE" --artifact "neo-runtime=$NEO_RT" \
    --artifact "kernel=$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar" \
    --artifact "kernel-runtime=$BUILD/libs/forbric-kernel-runtime-0.1.0-SNAPSHOT.jar" \
    --artifact "compile-game=$COMPILE_GAME" \
    --artifact "fabric-api=$FAPI" --mods "$RUNDIR/mods" --output "$RESULTS/$phase-inputs.json" \
    -- bash "$KERNEL/run/gate-m33-transfer.sh" --execute-phase "$phase" "$token" "$bridge" \
    > "$RESULTS/$phase-driver.log" 2>&1 &
  pid=$!; PHASE_PID=$pid; record_server_pid "$RUNDIR" "$pid"
  for i in $(seq 1 "${M33_TIMEOUT:-240}"); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "[kernel] FAIL M33 phase $phase timed out"; FAIL=1
    stop_phase "$pid"
  fi
  wait "$pid" 2>/dev/null || true; PHASE_PID=""
  rm -f "$RUNDIR/.forbric-gate.pid"
  port_was_free "$RESULTS/$phase-inputs.log"
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
    assert outcome['exitCode'] == 0 and probe['items'] == 40 and probe['fluidFabricUnits'] == 32417, (outcome, probe)
else:
    assert outcome['exitCode'] != 0 and 'missing ' in probe['detail'] and ' provider for ' in probe['detail'], (outcome, probe)
(root / (phase + '-probe.sha256')).write_text(hashlib.sha256(probe_path.read_bytes()).hexdigest() + '\n')
PY
  then echo "[kernel] PASS M33 $phase has the expected $expected result and unchanged hashed inputs"
  else echo "[kernel] FAIL M33 $phase result or evidence invalid"; FAIL=1
  fi
}

fresh_world
step "positive: both directed Fabric/NeoForge routes through real public block queries"
run_phase prepare on
assert_phase prepare pass
check_absent "positive probe emitted no failure marker" "M33Transfer\] FAIL" "$RESULTS/prepare-inputs.log"
for family in fabric neo; do check "the independent $family canary initialized" "M33Transfer\] REGISTERED $family " "$RESULTS/prepare-inputs.log"; done
check_absent "nothing was refused" "\[Forbric/Unsupported\] refusing" "$RESULTS/prepare-inputs.log"
check "the kernel connected the bridge after native registration" "\[Forbric/Transfer\] initialized cross-ecosystem block transfer" "$RESULTS/prepare-inputs.log"
check "public API face/null coverage ran" "PASS the Fabric and NeoForge public APIs preserve NORTH/null" "$RESULTS/prepare-inputs.log"
check "native priority ran" "PASS native providers take priority" "$RESULTS/prepare-inputs.log"
check "owner providers precede generic Container wrappers" "PASS owner providers precede Fabric's generic Container view" "$RESULTS/prepare-inputs.log"
check "Fabric's generic Container view answers no NeoForge consumer on the owner's refused face" "Fabric's generic Container view answers no NeoForge consumer on the owner's refused face" "$RESULTS/prepare-inputs.log"
check "actual fractional return was rolled back and retried" "PASS world fluid quantization retains 17 units" "$RESULTS/prepare-inputs.log"
check "world replacement invalidation ran" "PASS cached foreign views" "$RESULTS/prepare-inputs.log"
check "a real chunk unload and reload ran" "PASS chunk unload: cached views refuse" "$RESULTS/prepare-inputs.log"
# This pack has no energy mod and no Team Reborn Energy: the energy half of the bridge must stay silent (M40 tests it).
check_absent "an item/fluid pack sees no energy bridge activity or finding" "Team Reborn Energy|transfer-energy|RebornEnergy" "$RESULTS/prepare-inputs.log"

step "reload the same gate-owned world and verify persisted components and quantities"
run_phase reload on
assert_phase reload pass
check_absent "reload probe emitted no failure marker" "M33Transfer\] FAIL" "$RESULTS/reload-inputs.log"
check "real deserialization was verified" "PASS save/reload:" "$RESULTS/reload-inputs.log"
check "the kernel connected the bridge again on the second boot" "\[Forbric/Transfer\] initialized cross-ecosystem block transfer" "$RESULTS/reload-inputs.log"

step "negative control: bridge off must make the same world-query assertions RED"
fresh_world
run_phase negative off
assert_phase negative fail
check "negative failed in a foreign public lookup" "M33Transfer\] FAIL phase=negative.*missing .* provider" "$RESULTS/negative-inputs.log"
check_absent "negative never claimed probe success" "M33Transfer\] PASS phase=negative" "$RESULTS/negative-inputs.log"
check_absent "the switched-off bridge was never connected" "\[Forbric/Transfer\] initialized cross-ecosystem block transfer" "$RESULTS/negative-inputs.log"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M33 TRANSFER WORLD GATE GREEN — Fabric/NeoForge public routing, save/reload and red bridge-off control proved"
else
  echo "[kernel] ❌ M33 GATE RED — evidence and phase logs: $RESULTS"
fi
exit "$FAIL"
