#!/usr/bin/env bash
# M48 — what a player keeps: MinecraftForge's respawn copy, experience drop, explosion and brewing events reach a
# MinecraftForge mod again, and its answers reach the game.
#
# Each merged call site posts NeoForge's event and reads it back, with no MinecraftForge hook left: packedup keeps a dead
# player's backpacks and gives them back on Clone (they vanished), Tombstone cancels the experience drop it keeps with the
# grave (the experience came back twice) and takes its graves off an explosion's list (creepers blew them up), and a
# MinecraftForge mod's brewing recipes were never registered — and would have thrown ClassCastException at the first
# brewing-stand check once they were, because the merged builder put them in NeoForge's list unconverted.
# A dedicated server with a NeoForge mod whose MinecraftForge listeners are written that way (canary/server-events):
#   a respawn copy after death; an untouched (control), cancelled and changed experience drop through NeoForge's own
#   hook; an explosion between two dirt blocks, one of which a listener spares (the other is the control); a brewing
#   recipe registered through MinecraftForge's event, looked up and mixed through the server's PotionBrewing.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. adapter-off — -Dforbric.forgeBrewingRecipes=off: only the brewing case fails (the recipe arrives and throws).
#   3. off — -Dforbric.unifiedEvents=off -Dforbric.forgeBrewingRecipes=off: exactly the repaired cases fail and the
#      controls hold.
# GATE-PARALLEL: rundirs=server-events-m48 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-events-m48"
RESULTS="$BUILD/verification/m48-server-events"
FAIL=0
REPAIRED="{'clone.forge', 'xp.cancel', 'xp.change', 'detonate.spare', 'brewing.forge'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-server-events-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricserverevents.jar" "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\nspawn-animals=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.serverEventsProbe=$RESULTS/$phase.json -Dforbric.serverEventsPhase=$phase $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 240 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
  cp "$SERVER_DIR/.forbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json" 2>/dev/null || true
}

# judge <phase> <python expression over `failed`> <what>
judge() {
  local phase="$1" rule="$2" what="$3"
  check "$phase: the server started" 'Done \(' "$RESULTS/$phase.log"
  if python3 - "$RESULTS/$phase.json" "$phase" "$rule" <<'PY'
import json, sys
report, phase, rule = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
assert report['phase'] == phase, report['phase']
cases = {c['name']: c for c in report['cases']}
assert len(cases) == 7, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: MinecraftForge's respawn, experience, explosion and brewing listeners run and are obeyed"
run_server positive strict ""
judge positive "not failed" "all 7 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check "positive: the brewing builder wraps MinecraftForge recipes" 'Forbric/Brewing\] PotionBrewing.Builder.add wraps' "$RESULTS/positive.log"
check_absent "positive: no forward failed" 'forward failed' "$RESULTS/positive.log"

step "2. adapter-off: the bridges on, the brewing wrap off"
run_server adapter-off continue "-Dforbric.forgeBrewingRecipes=off"
judge adapter-off "failed == {'brewing.forge'} and 'ClassCastException' in cases['brewing.forge']['detail']" "only the brewing case fails, on the unconverted recipe"

step "3. off: the same server with the bridges and the wrap switched off"
run_server off continue "-Dforbric.unifiedEvents=off -Dforbric.forgeBrewingRecipes=off"
judge off "failed == $REPAIRED" "exactly the repaired cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M48 SERVER EVENTS GATE GREEN — respawn copy, experience drop, explosion and brewing reach MinecraftForge mods"
else
  echo "[kernel] ❌ M48 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
