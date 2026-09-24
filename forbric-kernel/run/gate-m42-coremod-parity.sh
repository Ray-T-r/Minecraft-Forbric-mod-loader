#!/usr/bin/env bash
# M42 — what NeoForge's own coremods (and MinecraftForge's identical ones) do to the game, done by the kernel.
#
# Neither family's coremods run on the merged base, and its bodies are written for them. A dedicated server with a
# probe mod drives the game paths a player's actions reach and records each verdict (canary/coremod-parity):
#   flower pots — vanilla, a NeoForge-declared pot, an addPlant-declared pot, pick-block, NeoForge's getFullPot;
#   the liquid block's getter; spawn finalization of a counting probe mob — both families' events and exactly one
#   finalization carrying MinecraftForge's data from EntityType.spawn and /summon, a NeoForge cancel and a
#   MinecraftForge cancel (no finalization, mob still spawned), a NeoForge veto a MinecraftForge listener clears; a
#   NeoForge biome modifier (temperature, water colour, and what the network codec sends a client); a NeoForge
#   structure modifier; a Fabric weather change through the unmodified fabric-biome-api, which must survive NeoForge's
#   pass; and a biome climate replaced after the pass, which must win over it as it would on Fabric.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. off — -Dforbric.coremodParity=off: every repaired case fails, and only the two Fabric climates (read raw) still
#      hold, so each case is shown to depend on the repair.
#   3. rebase-off — -Dforbric.biomeRebase=off: everything passes except the two Fabric climates, so the pass
#      bookkeeping (rebase and pass-time record) is what keeps them.
# Not covered here: the trial spawner (its helper is unit-tested, KernelFinalizeSpawnTest; no trial spawner runs in
# this world), natural and structure spawns (the redirect is unit-tested), and a rendered client.
# GATE-PARALLEL: rundirs=server-coremod-m42 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-coremod-m42"
RESULTS="$BUILD/verification/m42-coremod-parity"
FAIL=0
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-coremod-parity-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbriccoremodparity.jar" "$KERNEL"/run/canary/m42-modules/*.jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.coremodProbe=$RESULTS/$phase.json -Dforbric.coremodPhase=$phase $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 240 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
  cp "$SERVER_DIR/.forbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json" 2>/dev/null || true
}

# judge <phase> <python expression over `failed`, the set of failed case names>
judge() {
  local phase="$1" rule="$2" what="$3"
  check "$phase: the server started and stopped" 'Done \(' "$RESULTS/$phase.log"
  if python3 - "$RESULTS/$phase.json" "$phase" "$rule" <<'PY'
import json, sys
report, phase, rule = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
assert report['phase'] == phase, report['phase']
cases = {c['name']: c for c in report['cases']}
assert len(cases) == 22, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: every coremod path behaves as on the native loaders"
run_server positive strict ""
judge positive "not failed" "all 22 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi

step "2. off: the same server with the repairs switched off"
run_server off continue "-Dforbric.coremodParity=off"
judge off "failed == set(cases) - {'biome.fabric.keep', 'biome.late.keep'}" "every repaired case fails, and only the raw Fabric climates hold"

step "3. rebase-off: NeoForge's pass from the biome's original info"
run_server rebase-off continue "-Dforbric.biomeRebase=off"
judge rebase-off "failed == {'biome.fabric.keep', 'biome.late.keep'}" "only the Fabric climates are lost"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M42 COREMOD PARITY GATE GREEN — pots, fluids, finalization and modifiers behave natively, each by its repair"
else
  echo "[kernel] ❌ M42 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
