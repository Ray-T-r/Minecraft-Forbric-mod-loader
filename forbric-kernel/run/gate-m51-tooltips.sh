#!/usr/bin/env bash
# M51 — item tooltips show their component lines again, and NeoForge's appenders and Fabric's component tooltip
# providers land where they asked.
#
# The merged ItemStack.addDetailsToTooltip is NeoForge's dispatcher over the appender lists ItemTooltipHandler.init
# builds. init's only caller is GameData.postRegisterEvents, which the kernel replaces with its own copy of the tail,
# and that copy left init out: tooltips showed the item's name and nothing else — no lore, enchantments, attribute
# modifiers or durability. fabric-item-api's own tooltip injectors assume vanilla's single body and drew a Fabric
# mod's providers nowhere in normal tooltips; the kernel prunes them and draws the providers from NeoForge's appenders.
# A dedicated server with a NeoForge mod (canary/tooltips) renders tooltips through the game's own getTooltipLines,
# normal and advanced, and registers an appender before lore; a Fabric mod (canary/tooltips/fabric, with the
# unmodified fabric-item-api-v1) registers providers first, last, before/after lore, after durability — and one when
# the server starts, after NeoForge's event.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. bridge-off — -Dforbric.fabricTooltipBridge=off: exactly the Fabric cases fail; vanilla, NeoForge and controls hold.
#   3. appenders-off — -Dforbric.neoTooltipAppenders=off: exactly the vanilla, NeoForge and Fabric cases fail; the
#      name, the advanced id line, a plain stick and the hidden/absent providers hold.
# GATE-PARALLEL: rundirs=server-tooltips-m51 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-tooltips-m51"
RESULTS="$BUILD/verification/m51-tooltips"
FAIL=0
CASES=19
VANILLA_AND_NEO="{'vanilla.lore', 'vanilla.attributes', 'vanilla.enchantment', 'vanilla.durability', 'neo.eventPosted', 'neo.before'}"
FABRIC="{'fabric.first', 'fabric.chain', 'fabric.before', 'fabric.late', 'fabric.after', 'fabric.afterDamage', 'fabric.last'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-tooltips-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbrictooltips.jar" "$KERNEL/run/canary/forbrictooltipsfabric.jar" "$KERNEL"/run/canary/m51-modules/*.jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\nspawn-animals=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.tooltipProbe=$RESULTS/$phase.json -Dforbric.tooltipPhase=$phase $extra" \
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
  if python3 - "$RESULTS/$phase.json" "$phase" "$rule" "$CASES" <<'PY'
import json, sys
report, phase, rule, count = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3], int(sys.argv[4])
assert report['phase'] == phase, report['phase']
cases = {c['name']: c for c in report['cases']}
assert len(cases) == count, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: tooltips show vanilla's component lines, a NeoForge mod's appender and a Fabric mod's providers"
run_server positive strict ""
judge positive "not failed" "all $CASES cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check "positive: NeoForge's appenders built" 'Tooltips\] NeoForge tooltip appenders built: 32 vanilla component appender' "$RESULTS/positive.log"
check "positive: the registration event goes to each mod" 'Tooltips\] ItemTooltipHandler.init posts its RegisterTooltipAppendersEvent to each mod' "$RESULTS/positive.log"
check_absent "positive: no appender registration failed" 'threw during net.neoforged.neoforge.event.RegisterTooltipAppendersEvent' "$RESULTS/positive.log"
check "positive: fabric-item-api's tooltip injectors pruned" 'GuestInjectorPruner\] pruned 5 injector\(s\) from net.fabricmc.fabric.mixin.item.ItemStackMixin' "$RESULTS/positive.log"
check "positive: each component appender goes through the bridge" 'Tooltips\] ItemTooltipHandler hands each component appender to KernelNeoTooltips.around' "$RESULTS/positive.log"
check "positive: Fabric's providers drawn from NeoForge's appenders" "Tooltips\] fabric-item-api's component tooltip providers are drawn from NeoForge's appenders" "$RESULTS/positive.log"
check_absent "positive: fabric-item-api's ItemStackMixin is not retargeted" 'retargeted guest mixin fabric-item-api-v1.*ItemStackMixin' "$RESULTS/positive.log"
check_absent "positive: no provider threw" 'a Fabric component tooltip provider threw' "$RESULTS/positive.log"

step "2. bridge-off: the same server with fabric-item-api's injectors left where the retarget put them"
run_server bridge-off continue "-Dforbric.fabricTooltipBridge=off"
judge bridge-off "failed == $FABRIC" "exactly the Fabric cases fail; vanilla, NeoForge and the controls hold"
check_absent "bridge-off: nothing pruned from ItemStackMixin" 'GuestInjectorPruner\] pruned .* from net.fabricmc.fabric.mixin.item.ItemStackMixin' "$RESULTS/bridge-off.log"

step "3. appenders-off: the same server with NeoForge's appenders left unbuilt"
run_server appenders-off continue "-Dforbric.neoTooltipAppenders=off"
judge appenders-off "failed == $VANILLA_AND_NEO | $FABRIC" "exactly the vanilla, NeoForge and Fabric cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M51 TOOLTIPS GATE GREEN — item tooltips show their component lines, NeoForge appenders and Fabric providers"
else
  echo "[kernel] ❌ M51 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
