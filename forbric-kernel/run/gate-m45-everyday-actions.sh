#!/usr/bin/env bash
# M45 — the things every player does: craft, smelt, brew, swim, meet the Ender Dragon. Each of them threw on the merged
# base; on NeoForge's patched game the smelting and the dragon are the plain game's, and the rest are Fabric-on-NeoForge
# repairs.
#
#   crafting remainders — fabric-item-api-v1 puts FabricItemStack on ItemStack, whose NeoForge hierarchy already defaults
#     getCraftingRemainder() (ItemInstanceExtension): with fabric-api installed every crafting-table result, brew and
#     bucket fuel threw IncompatibleClassChangeError until InterfaceDefaultConflictRepair settled it with Fabric's default.
#     That default asks Item.getCraftingRemainder(ItemStack), which fabric-item-api's class tweaker gives Item as
#     FabricItem's default — a call that never reaches a NeoForge item's override of getCraftingRemainder(ItemInstance),
#     so the repair also gives Item a body that asks NeoForge's overload (its CHAINED row). The probe mod's jug overrides
#     NeoForge's overload; its remainder is asked directly and through a crafting grid.
#   smelting — on the merged base NeoForge's furnace tick called MinecraftForge's instance canBurn/consumeFuel/burn as
#     static, and every furnace threw on its first smelt (FurnaceTickCallsInjector, deleted in Stage 4: NeoForge's own
#     tick calls its own static methods, so the lit furnace smelting raw iron is now the plain game's).
#   the Ender Dragon — on the merged base its parts were MinecraftForge PartEntitys while every consumer is
#     NeoForge-typed, so adding a dragon threw and a server holding one could not stop. NeoForge's own game has one
#     PartEntity and EnderDragon's getParts() answers its real parts, so the three dragon cases are controls now (the
#     merged base's ancestor bridge and its "[Forbric/Hierarchy]" line went with the MinecraftForge carrier,
#     PlatformAncestorBridges deleted in c56a5243).
# A dedicated server with a probe mod (canary/everyday-actions) and the unmodified fabric-item-api-v1:
#   remainders through ItemStack, Item(ItemStack) and NeoForge's Item(ItemInstance) (control); a cake crafted from its
#   recipe leaves three buckets; an idle furnace ticks (control) and a lit one smelts raw iron; a brewing stand makes
#   awkward potions; a pig stands in a Fabric mod's untagged fluid (no interaction) and in its water-tagged fluid (it
#   swims), and a burning pig in vanilla water (control, put out) and in the water-tagged fluid (still burning); a dragon is added and found by its part, hurt through it, and removed; the server stops cleanly.
#   Fabric fuels — fabric-content-registries fires its fuel events from vanilla's vanillaBurnTimes, which NeoForge's
#     game never calls (it builds fuels from NeoForge's data map), so a Fabric mod's fuel could not go in a furnace
#     (FabricFuelValuesInjector runs them on NeoForge's builder): coal (control), the probe mod's dirt fuel, and the
#     carpets its exclusion removes.
#   NeoForge burn times — the probe mod's FurnaceFuelBurnTimeEvent listener makes cobblestone burn 321 ticks: asked the
#     way the furnace asks (ItemStack.getBurnTime), in a lit furnace that smelts on cobblestone alone, and the Fabric
#     mod's dirt fuel reaching the same event with its 300 ticks. The game posts the event itself; on the merged base a
#     compat repair (letBothEcosystemsSetBurnTime) also asked NeoForge from MinecraftForge's FuelValues overload.
#   Fabric fluids — a Fabric mod's fluid declares no NeoForge FluidType, and NeoForge's lookup threw "Mod fluids must
#     override getFluidType" at the first entity to touch one: 'Ticking entity' took the server down
#     (ForeignFluidTypeInjector gives it the type its fluid tags imply). That stand-in must not put out fire: NeoForge's
#     Fluid.entityInside extinguishes whatever the fluid's type says canExtinguish, and for a Fabric fluid in
#     minecraft:water that was water's type, where vanilla (Fabric's base) extinguishes only in WaterFluid
#     (OwnFluidTypeClausesInjector asks that clause the fluid's own type, NeoForge's empty one for a stand-in).
#
#   1. positive — STRICT, every case passes, zero confirmed required findings, no exception on stop; each runtime
#      repair logs its line.
#   2. off — -Dforbric.defaultConflictRepair=off -Dforbric.foreignFluidTypes=off -Dforbric.fabricFuel=off: exactly the
#      runtime-repaired cases fail and the controls (the three dragon cases among them) hold.
#      STAGE 4: this phase also passed -Dforbric.forgePartTracking=off (retired with the MinecraftForge carrier, D29), and
#      phases 1 and 2 asserted the merged base's "[Forbric/Hierarchy] net.minecraftforge.entity.PartEntity ... extends
#      net.neoforged.neoforge.entity.PartEntity" line; there is no MinecraftForge PartEntity to bridge, so both checks
#      went (D34) and the dragon cases themselves are what is left to show the dragon works.
#   3. withhold — -Dforbric.everydayWithholdFuelEvent=true: the probe mod registers no burn-time listener, so exactly the
#      three NeoForge burn-time cases fail (the control that shows they measure the listener).
#   4. own-clauses-off — -Dforbric.ownFluidTypeClauses=off: exactly fire.fabricWater fails (the stand-in puts the fire out).
# Not covered here: a client (the dragon's parts in the client's entity lookups), and a native server as an oracle.
# GATE-PARALLEL: rundirs=server-everyday-m45 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-everyday-m45"
RESULTS="$BUILD/verification/m45-everyday-actions"
FAIL=0
refuse_shared_rundir "$SERVER_DIR"
# The cases a runtime repair (switched off in phase 2) is responsible for. The dragon's three are not among them: they are
# the plain game's, and phase 2 asserts they hold without any runtime repair.
# fuel.neoEventFurnace smelts in a lit furnace, so it fails with the remainder conflict unsettled, as furnace.smelt does.
# remainder.item is a control on NeoForge's base: Item.getCraftingRemainder(ItemStack) has only FabricItem's default there,
# so nothing conflicts; ItemStack.getCraftingRemainder() does, and every case that asks it fails. fire.fabricWater throws
# with the foreign-fluid repair off, like the other two fluid cases.
REPAIRED="{'remainder.stack', 'remainder.neoOverride', 'craft.cake', 'craft.neoRemainder', 'furnace.smelt', 'brewing.awkward', 'fuel.fabric', 'fuel.exclusion', 'fuel.fabricInNeoEvent', 'fuel.neoEventFurnace', 'fluid.untagged', 'fluid.water', 'fire.fabricWater'}"
# The cases the probe mod's own burn-time listener answers (phase 3 withholds it).
NEO_FUEL="{'fuel.neoEvent', 'fuel.neoEventFurnace', 'fuel.fabricInNeoEvent'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-everyday-actions-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbriceveryday.jar" "$KERNEL/run/canary/forbricgoo.jar" "$KERNEL"/run/canary/m45-modules/*.jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.everydayProbe=$RESULTS/$phase.json -Dforbric.everydayPhase=$phase $extra" \
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
assert len(cases) == 22, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: crafting, smelting, brewing and the dragon work"
run_server positive strict ""
judge positive "not failed" "all 22 cases pass"
check_absent "positive: the server stopped without an exception" 'Exception stopping the server|still alive .* after announcing its stop' "$RESULTS/positive.log"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check "positive: ItemStack's remainder conflict was settled" 'ItemStack inherits getCraftingRemainder\(\).*gave it one that delegates to net\.fabricmc\.fabric\.api\.item\.v1\.FabricItemStack' "$RESULTS/positive.log"
check "positive: Item's Fabric remainder asks NeoForge's overload" 'Item inherits getCraftingRemainder.*gave it one that asks getCraftingRemainder\(Lnet/minecraft/world/item/ItemInstance' "$RESULTS/positive.log"
check "positive: Fluid.entityInside asks a fluid its own NeoForge type" 'Forbric/Fluid\] net\.minecraft\.world\.level\.material\.Fluid: 1 method' "$RESULTS/positive.log"
check "positive: foreign fluids get a NeoForge type" 'Forbric/Fluid\] .*gets the one its fluid tags imply' "$RESULTS/positive.log"
check "positive: Fabric's fuel events run on NeoForge's fuel builder" 'Forbric/Fuel\] DataMapHooks.populateFuelValues runs fabric-content-registries' "$RESULTS/positive.log"

step "2. off: the same server with the runtime repairs switched off"
run_server off continue "-Dforbric.defaultConflictRepair=off -Dforbric.foreignFluidTypes=off -Dforbric.fabricFuel=off"
judge off "failed == $REPAIRED" "exactly the runtime-repaired cases fail; the controls and the dragon hold"
check_absent "off: the foreign-fluid repair really was switched off" 'Forbric/Fluid\] .*gets the one its fluid tags imply' "$RESULTS/off.log"

step "3. withhold: the same server with the probe mod's burn-time listener withheld"
run_server withhold continue "-Dforbric.everydayWithholdFuelEvent=true"
judge withhold "failed == $NEO_FUEL" "exactly the NeoForge burn-time cases fail; every other case holds"

step "4. own-clauses-off: the same server with a Fabric fluid's stand-in type asked whether it puts fire out"
run_server own-clauses-off continue "-Dforbric.ownFluidTypeClauses=off"
judge own-clauses-off "failed == {'fire.fabricWater'}" "exactly the burning pig in the water-tagged Fabric fluid is put out"
check_absent "own-clauses-off: the clause repair really was switched off" 'Forbric/Fluid\] net\.minecraft\.world\.level\.material\.Fluid: 1 method' "$RESULTS/own-clauses-off.log"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M45 EVERYDAY GATE GREEN — crafting, smelting, brewing, mod fluids and the dragon work, each repair shown by its switch"
else
  echo "[kernel] ❌ M45 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
