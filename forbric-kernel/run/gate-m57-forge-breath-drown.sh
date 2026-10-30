#!/usr/bin/env bash
# M57 — breathing, drowning and liquids in the merged game, judged against native NeoForge.
#
# The merged LivingEntity hands its air to NeoForge's hook (CommonHooks.onLivingBreathe), where the kernel gives a body
# that breathes with its eye in a fluid vanilla's refill (VanillaAirRefillInjector), and its damage to NeoForge's
# pipeline. A dedicated server with the NeoForge canary (canary/forge-breath-drown, built here) plays 53 scenarios
# (Core.plan): pigs drowning in water with NeoForge's drown listeners acting at NORMAL, at LOW (registered from common
# setup) and at LOWEST (registered in its constructor); breathing in open air the same way, with water breathing in
# water, and with Breath of the Nautilus alone in open air, in water and in a bubble column; a pig drowning in NeoForge's
# own liquid; the probe's player drowning three times through the damage chain (NeoForge's incoming-damage and damage
# events); and water and NeoForge's liquid each placed, taken up and poured out with its bucket through the server's own
# use-item path. Every row's air and health (or a liquid row's result) and what NeoForge's listeners saw are compared
# with the same jar on native NeoForge (canary/forge-breath-drown/native-reference.json, three agreeing native runs each;
# regenerate it with run/compat/breath-drown.py reference). Two rows are the owner's deliberate difference, pinned both
# ways ('vanilla-refill'): a body that breathes with its eye in water refills as vanilla does, so NeoForge's breathe
# listener is handed vanilla's refill where native NeoForge hands it nothing, and the water-breathing pig, which keeps
# that refill, has the air native MinecraftForge gives the same row (its hook refills as vanilla does there, and no
# MinecraftForge listener acts in that row); the reference must still show native NeoForge handing nothing and holding.
#
#   0. the reference was measured with the canary sources in this tree.
#   1. positive — the NeoForge canary alone, STRICT: every row as native NeoForge (the two vanilla-refill rows as above),
#      no listener of the MinecraftForge canary in any row, its six liquid rows skipped as on native NeoForge, zero
#      confirmed required findings, and the breathing hook names the vanilla refill.
#   2. refused — the MinecraftForge canary staged beside it, continue policy: it is refused by name and every row is
#      judged as in 1. Its f-* tags change no row (its listeners would cancel, re-damage, recount or refill there), none
#      of its listeners hears a body, and the six rows in its liquids are skipped because its blocks and items were never
#      registered.
#   3. vanilla-refill-off — -Dforbric.vanillaAirRefill=off, the NeoForge canary alone: exactly the two vanilla-refill
#      rows fail, each because NeoForge's breathe listener is handed NeoForge's own refill, and the hook names no
#      vanilla refill.
# Not judged (measured, documented in introduction §19): the entity events the server broadcasts, and the body's random
# draws. Not covered: a rendered client, single player, Fabric's damage events (fabric-api is a third-party jar; the
# developer probe covers them), lava.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). This gate judged MinecraftForge's breathing, drowning,
# damage and liquids in the merged game against native MinecraftForge: a MinecraftForge canary (forbricbreathforge: its
# breathe, drown, attack, hurt and damage listeners, and two liquids built from suppliers, murk and calm) beside the
# NeoForge one, with the mixed rows' answering order and what MinecraftForge was handed, a MinecraftForge bubble count's
# random draws, and four switch-off phases (bridges-off, getters-off, own-type-off, seams-off), each predicting exactly
# the rows a MinecraftForge listener, liquid or Hurt seam decides. Forbric now refuses forbricbreathforge.jar (a
# MinecraftForge-only jar) and a STRICT server stops on the refusal (exit 78), so the positive boot never reached Done.
# What only that canary produced is gone: its listeners' views and the checks on them, the answering order, the bubble
# rows' random draws, the comparison with native MinecraftForge, its six liquid rows as parity rows (with the
# 'owner-refill' row in its calm liquid), and the getters-off "this.fluid" is null check. Every other row is now judged
# against native NeoForge, the 27 that were judged against native MinecraftForge alone included: with the MinecraftForge
# canary refused, their f-* tags do nothing, as on native NeoForge. Vanilla breathing on the merged base is the kernel's
# code and runs with no MinecraftForge mod, so the water-breathing rows stay, pinned against native NeoForge as the
# vanilla-refill difference (what NeoForge's listener is handed there was never judged before; only its air was, against
# native MinecraftForge, and the one row whose body keeps the refill still reads that air from it). The four switch-off
# phases are dropped: measured with the NeoForge canary alone, -Dforbric.unifiedEvents=off, -Dforbric.forgeFieldGetters=off,
# -Dforbric.forgeFluidOwnType=off and -Dforbric.forgeDamageSeams=off each leave all 53 rows judged as in phase 1, every one
# passing, because what each turns off (MinecraftForge's events posted from NeoForge's, its LiquidBlock and BucketItem
# field getters, NeoForge's view of a MinecraftForge fluid's own type, its Hurt and Damage seams) serves only
# MinecraftForge listeners and liquids; no row can turn red there, so each phase would only repeat phase 1. The kernel
# still installs all four (Stage 4 deletes them), and phase 1 is what proves they leave NeoForge's and vanilla's rows as
# native. Phase 3 is the RED control in their place, and phase 2 proves a refused MinecraftForge jar takes no part.
# GATE-PARALLEL: rundirs=server-breath-m57 mem=2500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-breath-m57"
RESULTS="$BUILD/verification/m57-forge-breath-drown"
TOOL="$KERNEL/run/compat/breath-drown.py"
NEO="$KERNEL/run/canary/forbricbreathneo.jar"
FORGE="$KERNEL/run/canary/forbricbreathforge.jar"
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
  # The NeoForge canary drives (the probe in the MinecraftForge one never runs: Forbric refuses that jar).
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
check_absent "positive: no breathe, drown or damage forward failed" '\[Forbric/EventMux\] (LivingBreatheEvent|LivingDrownEvent|LivingIncomingDamageEvent) forward failed' "$RESULTS/positive.log"
check "positive: the breathing hook gives a body that breathes in its fluid vanilla's refill" "$REFILL" "$RESULTS/positive.log"

step "2. refused: the MinecraftForge canary staged beside it takes no part"
run_server refused continue "" "$NEO" "$FORGE"
check "refused: the MinecraftForge canary is refused by name" \
  '\[Forbric/Unsupported\] refusing forbricbreathforge\.jar \(forbricbreathforge\)' "$RESULTS/refused.log"
judge refused "every row as in 1: no f-* tag acts, no MinecraftForge listener hears a body, its liquids never registered"

step "3. vanilla-refill-off: the breathing hook keeps NeoForge's own refill"
run_server vanilla-refill-off continue "-Dforbric.vanillaAirRefill=off" "$NEO"
judge vanilla-refill-off "exactly the two vanilla-refill rows fail, each on what NeoForge's breathe listener is handed"
check_absent "vanilla-refill-off: the breathing hook names no vanilla refill" "$REFILL" "$RESULTS/vanilla-refill-off.log"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M57 BREATH/DROWN GATE GREEN — breathing, drowning, damage and liquids behave as on native NeoForge, a refused MinecraftForge jar takes no part"
else
  echo "[kernel] ❌ M57 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
