#!/usr/bin/env bash
# M43 — Fabric's PlayerBlockBreakEvents.AFTER, experience from mining, and loot pool conditions, on NeoForge's patched
# game.
#
# fabric-api fires AFTER at vanilla's Block.destroy call in destroyBlock; NeoForge's body moved that call into its own
# removeBlock, so AFTER never fired (CarrierHelperResults, which knows no mod, now runs fabric-api's handler on removeBlock's
# answer: the helper answers true exactly when it called Block.destroy, and the handler's @Locals are read where NeoForge's
# destroyBlock keeps the same values). A dedicated server with a probe mod (canary/break-and-loot) and the unmodified
# fabric-events-interaction-v0 breaks blocks with a fake player and rolls a loot table — 17 cases:
#   block breaking — survival, a block entity (the chest's block entity reaches AFTER), creative, a break inside an
#   AFTER listener (exactly one AFTER per block, in order), breaking air (NeoForge's removal reports nothing removed:
#   no AFTER), a Fabric BEFORE veto (CANCELED, no AFTER) and a NeoForge BreakBlockEvent cancel (no Fabric event at all);
#   experience — six diamond ore breaks each drop 3..7 orbs' worth, exactly what NeoForge's BlockDropsEvent (fired once)
#   ends with; silk touch drops none; a NeoForge listener's number (50 on lapis) is what drops; and a Fabric mod's mixins
#   in vanilla's Block.tryDropExperience (canary forbricxprelay) — redstone ore's experience plus a hundred at the store of
#   its value, nether quartz ore's cancelled at HEAD by a handler handed every argument — drop as they would natively
#   (101..105, and 0), relayed by the kernel to the drops event's value (UncalledValueRelay);
#   loot — a plain pool, neoforge:conditions never (dropped), both families' conditions true (kept), and two pools guarded
#   only by MinecraftForge's forge:condition, false and true: native NeoForge 26.2.0.93 does not read that key and keeps
#   both (D49, measured on a native NeoForge server with this table: c5/g-b1/w2/d49), so both are controls.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. off — -Dforbric.carrierHelperResults=off -Dforbric.uncalledValueRelay=off: exactly the repaired cases fail (AFTER
#      in survival, block entity, creative and nested; the Fabric hooks in tryDropExperience, which then never run, so
#      redstone drops its bare 1..5 and quartz its 2..5), and the controls (air, veto, NeoForge cancel, vanilla and
#      NeoForge experience, every pool) still hold.
# Not covered here: a native Fabric server as an oracle, and a rendered client.
#
# STAGE 4 OF DROPPING MinecraftForge (what changed and why). On the merged base this gate also judged MinecraftForge's
# pool conditions (a forge:condition false pool replaced by an empty one, LootPool.Builder.when(ICondition) built in code,
# encoded and read back by MinecraftForge's codec: ForgeLootPoolConditionsInjector, switch forgePoolConditions) and
# MinecraftForge's experience decisions (a BreakEvent listener's setExpToDrop, a block only getExpDrop answers:
# KernelGameBlockEvents, switch forgeBreakExperience). Those cases left the probe with the MinecraftForge carrier
# (22 -> 17), both switches are retired (D29), and loot.json.forgeFalse now expects native NeoForge's answer (D49).
# GATE-PARALLEL: rundirs=server-break-m43 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-break-m43"
RESULTS="$BUILD/verification/m43-break-and-loot"
FAIL=0
REPAIRED="{'break.survival.plain', 'break.survival.blockEntity', 'break.creative', 'break.nested', 'xp.fabricReplace', 'xp.fabricCancel'}"
refuse_shared_rundir "$SERVER_DIR"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-break-and-loot-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricbreakandloot.jar" "$KERNEL/run/canary/forbricxprelay.jar" "$KERNEL"/run/canary/m43-modules/*.jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.breakProbe=$RESULTS/$phase.json -Dforbric.breakPhase=$phase $extra" \
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
assert len(cases) == 17, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: Fabric's AFTER fires, mining drops experience, every pool as on native NeoForge"
run_server positive strict ""
judge positive "not failed" "all 17 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi

step "2. off: the same server with both repairs switched off"
run_server off continue "-Dforbric.carrierHelperResults=off -Dforbric.uncalledValueRelay=off"
judge off "failed == $REPAIRED" "exactly the repaired cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M43 BREAK-AND-LOOT GATE GREEN — Fabric's AFTER, experience from mining and loot pools behave natively, each by its repair"
else
  echo "[kernel] ❌ M43 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
