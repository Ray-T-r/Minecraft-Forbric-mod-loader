#!/usr/bin/env bash
# M43 — Fabric's PlayerBlockBreakEvents.AFTER, and MinecraftForge loot pool conditions, on the merged base.
#
# fabric-api fires AFTER at vanilla's Block.destroy call in destroyBlock; NeoForge's body moved that call into its own
# removeBlock, so AFTER never fired (FabricBlockBreakMixinAdapter now wraps fabric-api's handler on removeBlock's
# result). MinecraftForge's LootPool.Builder.when(ICondition) was dropped by NeoForge's builder, and a pool-level
# forge:condition was parsed but never judged by NeoForge's pool list codec (ForgeLootPoolConditionsInjector). A
# dedicated server with a probe mod (canary/break-and-loot) and the unmodified fabric-events-interaction-v0 breaks
# blocks with a fake player and rolls a loot table:
#   block breaking — survival, a block entity (the chest's block entity reaches AFTER), creative, a break inside an
#   AFTER listener (exactly one AFTER per block, in order), breaking air (NeoForge's removal reports nothing removed:
#   no AFTER), a Fabric BEFORE veto (CANCELED, no AFTER) and a NeoForge BreakBlockEvent cancel (no Fabric event at all);
#   loot — a plain pool, forge:condition false (an empty pool in its place) and true (kept), neoforge:conditions never
#   (dropped), both
#   families true (kept); a pool built in code with when(FalseCondition): kept in the pool, encoded, and read back
#   by MinecraftForge's own codec as an empty pool.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. off — -Dforbric.fabricBlockBreak=off -Dforbric.forgePoolConditions=off: exactly the repaired cases fail (AFTER
#      in survival, block entity, creative and nested; forge:condition false; the code-built condition, its encoding
#      and round trip), and the controls (air, veto, NeoForge cancel, plain/true/NeoForge pools) still hold.
# Not covered here: a native MinecraftForge or Fabric server as an oracle, and a rendered client.
# GATE-PARALLEL: rundirs=server-break-m43 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-break-m43"
RESULTS="$BUILD/verification/m43-break-and-loot"
FAIL=0
REPAIRED="{'break.survival.plain', 'break.survival.blockEntity', 'break.creative', 'break.nested', 'loot.json.forgeFalse', 'loot.code.condition', 'loot.code.encode', 'loot.code.roundtrip'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-break-and-loot-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricbreakandloot.jar" "$KERNEL"/run/canary/m43-modules/*.jar "$SERVER_DIR/mods/"
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
assert len(cases) == 15, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: Fabric's AFTER fires and MinecraftForge's pool conditions hold"
run_server positive strict ""
judge positive "not failed" "all 15 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi

step "2. off: the same server with both repairs switched off"
run_server off continue "-Dforbric.fabricBlockBreak=off -Dforbric.forgePoolConditions=off"
judge off "failed == $REPAIRED" "exactly the repaired cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M43 BREAK-AND-LOOT GATE GREEN — Fabric's AFTER and MinecraftForge pool conditions behave natively, each by its repair"
else
  echo "[kernel] ❌ M43 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
