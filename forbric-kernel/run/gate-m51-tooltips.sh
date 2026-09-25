#!/usr/bin/env bash
# M51 — item tooltips show their component lines again, and a NeoForge mod's appender lands where it asked.
#
# The merged ItemStack.addDetailsToTooltip is NeoForge's dispatcher over the appender lists ItemTooltipHandler.init
# builds. init's only caller is GameData.postRegisterEvents, which the kernel replaces with its own copy of the tail,
# and that copy left init out: tooltips showed the item's name and nothing else — no lore, enchantments, attribute
# modifiers or durability. A dedicated server with a NeoForge mod (canary/tooltips) renders tooltips through the
# game's own getTooltipLines, normal and advanced, and registers an appender before lore.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. appenders-off — -Dforbric.neoTooltipAppenders=off: exactly the vanilla and NeoForge cases fail; the name, the
#      advanced id line and a plain stick hold.
# GATE-PARALLEL: rundirs=server-tooltips-m51 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-tooltips-m51"
RESULTS="$BUILD/verification/m51-tooltips"
FAIL=0
CASES=10
VANILLA_AND_NEO="{'vanilla.lore', 'vanilla.attributes', 'vanilla.enchantment', 'vanilla.durability', 'neo.eventPosted', 'neo.before'}"
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
  cp "$KERNEL/run/canary/forbrictooltips.jar" "$SERVER_DIR/mods/"
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

step "1. positive: tooltips show vanilla's component lines and a NeoForge mod's appender"
run_server positive strict ""
judge positive "not failed" "all $CASES cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check "positive: NeoForge's appenders built" 'Tooltips\] NeoForge tooltip appenders built: 32 vanilla component appender' "$RESULTS/positive.log"
check "positive: the registration event goes to each mod" 'Tooltips\] ItemTooltipHandler.init posts its RegisterTooltipAppendersEvent to each mod' "$RESULTS/positive.log"
check_absent "positive: no appender registration failed" 'threw during net.neoforged.neoforge.event.RegisterTooltipAppendersEvent' "$RESULTS/positive.log"

step "2. appenders-off: the same server with NeoForge's appenders left unbuilt"
run_server appenders-off continue "-Dforbric.neoTooltipAppenders=off"
judge appenders-off "failed == $VANILLA_AND_NEO" "exactly the vanilla and NeoForge cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M51 TOOLTIPS GATE GREEN — item tooltips show their component lines and NeoForge appenders"
else
  echo "[kernel] ❌ M51 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
