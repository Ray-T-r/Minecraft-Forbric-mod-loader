#!/usr/bin/env bash
# M57 — breathing, drowning and liquids on NeoForge's patched game, judged against native NeoForge.
#
# NeoForge's LivingEntity hands its air to NeoForge's hook (CommonHooks.onLivingBreathe), where the kernel gives a body
# that breathes with its eye in a fluid vanilla's refill (VanillaAirRefillInjector), and its damage to NeoForge's
# pipeline. A dedicated server with the NeoForge canary (canary/forge-breath-drown, built here) plays 53 scenarios
# (Core.plan): pigs drowning in water with NeoForge's drown listeners acting at NORMAL, at LOW (registered from common
# setup) and at LOWEST (registered in its constructor); breathing in open air the same way, with water breathing in
# water, and with Breath of the Nautilus alone in open air, in water and in a bubble column; a pig drowning in NeoForge's
# own liquid; the probe's player drowning three times through the damage chain (NeoForge's incoming-damage and damage
# events); and water and NeoForge's liquid each placed, taken up and poured out with its bucket through the server's own
# use-item path. Every row's air and health (or a liquid row's result) and what NeoForge's listeners saw are compared
# with the same jar on native NeoForge (canary/forge-breath-drown/native-reference.json, three agreeing native runs per
# loader; regenerate it with run/compat/breath-drown.py reference). The rows of the MinecraftForge canary's liquids are
# skipped (its blocks and items are never registered), as on native NeoForge, and no row may carry a view of a
# MinecraftForge listener. Two rows are the owner's deliberate difference, pinned both ways ('vanilla-refill'): a body
# that breathes with its eye in water refills as vanilla does, so NeoForge's breathe listener is handed vanilla's refill
# where native NeoForge hands it nothing, and the water-breathing pig, which keeps that refill, has the air native
# MinecraftForge gives the same row (its hook refills as vanilla does there, and no MinecraftForge listener acts in that
# row); the reference must still show native NeoForge handing nothing and holding. That comparison is why the reference
# keeps its native MinecraftForge half (D32).
#
#   0. the reference was measured with the canary sources in this tree.
#   1. positive — the NeoForge canary alone, STRICT: every row as native NeoForge (the two vanilla-refill rows as above),
#      zero confirmed required findings, and the breathing hook names the vanilla refill.
#   2. vanilla-refill-off — -Dforbric.vanillaAirRefill=off, the NeoForge canary alone: exactly the two vanilla-refill
#      rows fail, each because NeoForge's breathe listener is handed NeoForge's own refill, and the hook names no
#      vanilla refill.
# Not judged (measured, documented in introduction §19): the entity events the server broadcasts, and the body's random
# draws. Not covered: a rendered client, single player, Fabric's damage events (fabric-api is a third-party jar; the
# developer probe covers them), lava.
#
# STAGE 3 OF DROPPING MinecraftForge. This gate judged MinecraftForge's breathing, drowning, damage and liquids in the
# merged game against native MinecraftForge: a MinecraftForge canary (forbricbreathforge) beside the NeoForge one, with
# the mixed rows' answering order, a MinecraftForge bubble count's random draws, and four switch-off phases
# (bridges-off, getters-off, own-type-off, seams-off). Forbric refuses forbricbreathforge.jar (a MinecraftForge-only
# jar), so what only that canary produced went, the four switch-off phases with it (measured with the NeoForge canary
# alone, each left all 53 rows as in phase 1), and every row is judged against native NeoForge.
# STAGE 4. Phase 2 "refused" staged the MinecraftForge canary (built against stand-ins) beside the NeoForge one and
# showed it refused by name and taking no part. It passed, and it goes anyway (D32/D51): a refused jar is gate-m26's and
# gate-m29's subject. The positive phase's absence of "[Forbric/EventMux] ... forward failed" went with the event
# multiplexer that printed it (D34).
# GATE-PARALLEL: rundirs=server-breath-m57 mem=2500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-breath-m57"
RESULTS="$BUILD/verification/m57-forge-breath-drown"
refuse_shared_rundir "$SERVER_DIR"
TOOL="$KERNEL/run/compat/breath-drown.py"
NEO="$KERNEL/run/canary/forbricbreathneo.jar"
REFILL='\[Forbric/Fluid\] a body that breathes in its fluid refills its air as in vanilla'
FAIL=0
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-breath-drown-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags> <jar>...
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  shift 3
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$@" "$SERVER_DIR/mods/"
  echo "[kernel] $phase staged: $(ls -1 "$SERVER_DIR/mods" | tr '\n' ' ')"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  python3 "$TOOL" properties >> "$SERVER_DIR/server.properties"
  # The NeoForge canary drives.
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.breathDrownDriver=neo -Dforbric.breathDrownProbe=$RESULTS/$phase.json $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 400 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
  cp "$SERVER_DIR/.forbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json" 2>/dev/null || true
}

# judge <phase> <what>
judge() {
  local phase="$1" what="$2"
  check "$phase: the server started" 'Done \(' "$RESULTS/$phase.log"
  check "$phase: the probe played every scenario" '\[BreathDrown\] DONE rows=53 error=null' "$RESULTS/$phase.log"
  if python3 "$TOOL" judge --result "$RESULTS/$phase.json" --phase "$phase"
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "0. the native reference belongs to these canaries"
python3 "$TOOL" check-sources || FAIL=1

step "1. positive: the NeoForge canary alone, every row as native NeoForge"
run_server positive strict "" "$NEO"
judge positive "breathing, drowning, the damage chain and NeoForge's liquid behave as on native NeoForge"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check "positive: the breathing hook gives a body that breathes in its fluid vanilla's refill" "$REFILL" "$RESULTS/positive.log"

step "2. vanilla-refill-off: the breathing hook keeps NeoForge's own refill"
run_server vanilla-refill-off continue "-Dforbric.vanillaAirRefill=off" "$NEO"
judge vanilla-refill-off "exactly the two vanilla-refill rows fail, each on what NeoForge's breathe listener is handed"
check_absent "vanilla-refill-off: the breathing hook names no vanilla refill" "$REFILL" "$RESULTS/vanilla-refill-off.log"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M57 BREATH/DROWN GATE GREEN — breathing, drowning, damage and liquids behave as on native NeoForge"
else
  echo "[kernel] ❌ M57 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
