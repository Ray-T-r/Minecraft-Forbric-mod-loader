#!/usr/bin/env bash
# M49 — the rest of MinecraftForge's world and entity events reach a MinecraftForge mod again, and its answers reach
# the game.
#
# Every merged call site here posts NeoForge's event and reads it back; none calls MinecraftForge's hook
# (KernelGameWorldEvents). A dedicated server with a NeoForge mod whose MinecraftForge listeners hear, veto or change
# each one (canary/world-events), driven through the game's own methods where it has one — a section move, waking,
# adding an effect and letting it run out, a lightning-struck pig, farmland fallen on, a command, right-clicking an
# entity, healing, a mob's view of a player, a hoe on dirt — and through NeoForge's own hook where the call is deep in
# a tick (a projectile's impact, a critical hit). Chunk loads and tag reloads are heard at startup; an entity leaving
# the level and an effect running out are checked a second later. Untouched controls beside each answer.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. off — -Dforbric.unifiedEvents=off: exactly the repaired cases fail and the controls hold.
# Not covered here: chunk unloads, permissions (needs a real connected player), anvils (needs an open anvil menu).
# GATE-PARALLEL: rundirs=world-events-m49 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/world-events-m49"
RESULTS="$BUILD/verification/m49-world-events"
FAIL=0
REPAIRED="{'chunk.load', 'tags.updated', 'entity.section', 'player.wakeup', 'effect.added', 'applicable.deny', 'conversion.post', 'conversion.veto', 'projectile.cancel', 'trample.cancel', 'command.cancel', 'interact.specific', 'heal.cancel', 'heal.change', 'visibility.halve', 'critical.force', 'tool.veto', 'effect.expired', 'entity.leave', 'drown.neo'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-world-events-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricworldevents.jar" "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\nspawn-animals=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.worldEventsProbe=$RESULTS/$phase.json -Dforbric.worldEventsPhase=$phase $extra" \
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
assert len(cases) == 28, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: MinecraftForge's world and entity listeners hear and are obeyed"
run_server positive strict ""
judge positive "not failed" "all 28 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check_absent "positive: no forward failed" 'forward failed' "$RESULTS/positive.log"

step "2. off: the same server with the bridges switched off"
run_server off continue "-Dforbric.unifiedEvents=off -Dforbric.neoConversionPost=off"
judge off "failed == $REPAIRED" "exactly the repaired cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M49 WORLD EVENTS GATE GREEN — MinecraftForge world and entity listeners hear and are obeyed"
else
  echo "[kernel] ❌ M49 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
