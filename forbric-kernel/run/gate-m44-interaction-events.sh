#!/usr/bin/env bash
# M44 — using an item on a block and picking a block, as Fabric's and NeoForge's events see them on the merged base.
#
# The merged ItemStack.useOn is MinecraftForge's body: it never posted NeoForge's UseItemOnBlockEvent ITEM_AFTER_BLOCK
# phase, and its Item.useOn calls live in ForgeHooks.onPlaceItemIntoWorld and a lambda, where fabric-api's
# ItemEvents.USE_ON wrap could not reach them (ItemUseOnInjector restores the phase and routes both calls through one
# ItemStack relay; MixinRelocatedCall moves the wrap there). The merged pick-block handler calls NeoForge's
# getCloneItemStack(BlockPos, LevelReader, boolean, Player), so fabric-api's PlayerPickItemEvents.BLOCK wrap of vanilla's
# three-argument call matched nothing (MixinWrapOperationShim binds it to NeoForge's call in its own argument order).
# A dedicated server with a probe mod (canary/interaction-events) and the unmodified fabric-events-interaction-v0 uses a
# stone block item on stone with a fake player and picks blocks through the server's own packet handler:
#   use-on — the block is placed (control); NeoForge's ITEM_BEFORE_BLOCK (control) and ITEM_AFTER_BLOCK each once, in
#   order, before Fabric's USE_ON, which fires once; a Fabric result replaces the use (nothing placed); a NeoForge cancel
#   returns its result and skips the item and Fabric's event; ItemStack.useOn called directly by a mod does the same;
#   pick-block — Fabric's BLOCK event with the real state and includeData flag, its stack picked, EMPTY picking nothing,
#   and null leaving vanilla's clone; pick-entity (control: its call was never moved).
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. off — -Dforbric.itemUseOn=off -Dforbric.mixinRelocatedCall=off -Dforbric.wrapOperationShim=off: exactly the
#      repaired cases fail, and the controls (block placed, ITEM_BEFORE_BLOCK, pick-entity) still hold.
# Not covered here: a client (the use-on lambda and client pick), and a native NeoForge or Fabric server as an oracle.
# GATE-PARALLEL: rundirs=server-interaction-m44 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-interaction-m44"
RESULTS="$BUILD/verification/m44-interaction-events"
FAIL=0
REPAIRED="{'use.place.fabric', 'use.place.neoAfter', 'use.fabricOverrides', 'use.neoCancel', 'use.direct', 'pick.block.vanilla', 'pick.block.fabric', 'pick.block.empty', 'pick.block.includeData'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-interaction-events-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricinteraction.jar" "$KERNEL"/run/canary/m44-modules/*.jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.interactionProbe=$RESULTS/$phase.json -Dforbric.interactionPhase=$phase $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 240 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
  cp "$SERVER_DIR/.forbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json" 2>/dev/null || true
}

# judge <phase> <python expression over `failed`> <what>
judge() {
  local phase="$1" rule="$2" what="$3"
  check "$phase: the server started and stopped" 'Done \(' "$RESULTS/$phase.log"
  if python3 - "$RESULTS/$phase.json" "$phase" "$rule" <<'PY'
import json, sys
report, phase, rule = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
assert report['phase'] == phase, report['phase']
cases = {c['name']: c for c in report['cases']}
assert len(cases) == 12, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: Fabric's USE_ON and pick-block events and NeoForge's ITEM_AFTER_BLOCK fire"
run_server positive strict ""
judge positive "not failed" "all 12 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check "positive: the use-on relay was installed" 'ItemStack.useOn posts NeoForge' "$RESULTS/positive.log"
check "positive: NeoForge's place hook calls the item through the relay" 'CommonHooks.onPlaceItemIntoWorld calls Item.useOn through ItemStack' "$RESULTS/positive.log"
check "positive: fabric-api's use-on wrap moved to the relay" 'handleUseOnEvent now wraps Item.useOn in forbric\$useOnItem' "$RESULTS/positive.log"
check "positive: fabric-api's pick-block wrap bound to NeoForge's call" 'onPickItemFromBlock now wraps getCloneItemStack' "$RESULTS/positive.log"

step "2. off: the same server with the three repairs switched off"
run_server off continue "-Dforbric.itemUseOn=off -Dforbric.mixinRelocatedCall=off -Dforbric.wrapOperationShim=off"
judge off "failed == $REPAIRED" "exactly the repaired cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M44 INTERACTION GATE GREEN — item use-on and pick-block events fire as natively, each by its repair"
else
  echo "[kernel] ❌ M44 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
