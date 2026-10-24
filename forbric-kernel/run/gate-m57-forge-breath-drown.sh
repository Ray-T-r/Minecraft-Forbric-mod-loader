#!/usr/bin/env bash
# M57 — MinecraftForge's breathing, drowning and liquids in the merged game, judged against the native loaders.
#
# The merged LivingEntity hands its air to NeoForge's hook alone, so MinecraftForge's breathe and drown events are
# posted from NeoForge's (KernelGameWorldEvents.installBreathe / installDrown, at LOWEST); a MinecraftForge liquid is
# asked for its type through NeoForge's lookup (KernelForgeFluidTypeViews); a MinecraftForge LiquidBlock or bucket
# built from a supplier reads its fluid through MinecraftForge's getters (NativeCoremodParity, Family.FORGE); and
# MinecraftForge's Hurt and Damage events sit in NeoForge's damage pipeline through seams (ForgeDamageSeamsInjector). A
# dedicated server with a MinecraftForge canary and a NeoForge canary (canary/forge-breath-drown, both built here)
# plays 44 scenarios (Core.plan): pigs drowning in water with each family's drown listeners acting alone and both
# acting (NeoForge's answer first, then MinecraftForge's), including MinecraftForge's @SubscribeEvent listeners that
# cancel by returning true and a MinecraftForge bubble count that must leave the body's random draws as they are;
# breathing in open air the same way, and with Breath of the Nautilus alone in water, in a bubble column and in
# MinecraftForge's liquid, where MinecraftForge's listeners are told whether the body may refill; a pig drowning in each
# family's own liquid; the probe's player drowning three times through the damage chain (MinecraftForge's attack, hurt
# and damage, NeoForge's incoming damage); and each family's liquid placed, taken up and poured out with its bucket
# through the server's own use-item path. Every row's air and health (or a liquid row's result) and the listener views
# it names are compared with the same jar on the native loader whose answer stands
# (canary/forge-breath-drown/native-reference.json, three agreeing native runs each; regenerate it with
# run/compat/breath-drown.py reference), and the mixed rows also with what the other family must have been handed.
#
#   0. the reference was measured with the canary sources in this tree.
#   1. positive — STRICT: every row as native, zero confirmed required findings.
#   2. bridges-off — -Dforbric.unifiedEvents=off: exactly the rows a MinecraftForge breathe or drown listener decides or
#      sees fail, each on a MinecraftForge breathe or drown view.
#   3. getters-off — -Dforbric.forgeFieldGetters=off: exactly the rows in or with MinecraftForge's liquid fail, each
#      because its liquid block threw with "this.fluid" is null.
#   4. own-type-off — -Dforbric.forgeFluidOwnType=off: exactly the rows with a body in MinecraftForge's liquid fail, each
#      with the body in it and its air or MinecraftForge breathe view off native.
#   5. seams-off — -Dforbric.forgeDamageSeams=off: exactly the rows judged on a MinecraftForge Hurt listener fail, each
#      on a MinecraftForge attack, hurt or damage view. (MinecraftForge's attack is asked by the edit-spliced base itself
#      at the head of hurtServer, so the NeoForge incoming-damage cancel rows hold; a base without that call would fail
#      them here too, and the phase names them.)
# Judged only where a row says so: the body's random draws (the bubble-count rows must draw what drown-late draws).
# Not judged (measured, documented in introduction §19): the entity events the server broadcasts, and the random draws
# elsewhere — merged drowning goes through NeoForge's hook, as native NeoForge's does. Not covered: a rendered client,
# single player, a NeoForge listener registered at LOWEST after the kernel's bridges (it answers after
# MinecraftForge's; the canaries register in their constructors), Fabric's damage events (fabric-api is a third-party
# jar; the developer probe covers them).
# GATE-PARALLEL: rundirs=server-breath-m57 mem=2500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-breath-m57"
RESULTS="$BUILD/verification/m57-forge-breath-drown"
TOOL="$KERNEL/run/compat/breath-drown.py"
FAIL=0
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-breath-drown-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricbreathforge.jar" "$KERNEL/run/canary/forbricbreathneo.jar" "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  python3 "$TOOL" properties >> "$SERVER_DIR/server.properties"
  # The NeoForge canary drives: MinecraftForge's tick event is itself a bridge, which phase 2 switches off.
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
  check "$phase: the probe played every scenario" '\[BreathDrown\] DONE rows=44 error=null' "$RESULTS/$phase.log"
  if python3 "$TOOL" judge --result "$RESULTS/$phase.json" --phase "$phase"
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "0. the native reference belongs to these canaries"
python3 "$TOOL" check-sources || FAIL=1

step "1. positive: every row as the native loader whose answer stands"
run_server positive strict ""
judge positive "breathing, drowning, the damage chain and both liquids behave natively"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check_absent "positive: no breathe, drown or damage forward failed" '\[Forbric/EventMux\] (LivingBreatheEvent|LivingDrownEvent|LivingIncomingDamageEvent) forward failed' "$RESULTS/positive.log"

step "2. bridges-off: MinecraftForge's events from NeoForge's switched off"
run_server bridges-off continue "-Dforbric.unifiedEvents=off"
judge bridges-off "exactly the rows a MinecraftForge breathe or drown listener decides or sees fail, each on its view"

step "3. getters-off: MinecraftForge's field getters switched off"
run_server getters-off continue "-Dforbric.forgeFieldGetters=off"
judge getters-off "exactly the rows in or with MinecraftForge's liquid fail, each on its liquid block's missing fluid"
check "getters-off: ...because its LiquidBlock has no fluid without the getter" 'Cannot invoke "net\.minecraft\.world\.level\.material\.FlowingFluid\.[A-Za-z]+\(.*because "this\.fluid" is null' "$RESULTS/getters-off.log"

step "4. own-type-off: NeoForge's view of MinecraftForge's own fluid type switched off"
run_server own-type-off continue "-Dforbric.forgeFluidOwnType=off"
judge own-type-off "exactly the rows with a body in MinecraftForge's liquid fail, each with the body in it"

step "5. seams-off: MinecraftForge's damage seams switched off"
run_server seams-off continue "-Dforbric.forgeDamageSeams=off"
judge seams-off "exactly the rows judged on a MinecraftForge Hurt listener fail, each on a damage-chain view"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M57 FORGE BREATH/DROWN GATE GREEN — MinecraftForge's breathing, drowning, damage and liquids behave as on its own loader"
else
  echo "[kernel] ❌ M57 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
