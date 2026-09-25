#!/usr/bin/env bash
# M53 — fabric-particles puts the ground block on a mob's landing dust and on sprint dust again.
#
# NeoForge builds BlockParticleOption with the block position appended in Entity.spawnSprintParticle and
# LivingEntity.checkFallDamage; fabric-particles' @ModifyExpressionValue there names vanilla's NEW
# (ParticleType, BlockState), which nothing constructs, so it attached nowhere and the option's Fabric block position
# stayed null — terrain particles were then tinted, and asked ALLOW_TERRAIN_PARTICLE_TINT, at the air block the
# particle spawns in. MixinAtWidenedCall moves an argument-blind @At(NEW) to the one construction that appends
# parameters. A dedicated server with the unmodified fabric-particles-v1, a Fabric mod capturing the BLOCK options
# sent and added (canary/widened-new/probe) and a NeoForge driver that makes a zombie land and sprint on stone:
#   NeoForge's own position on each option (control), fabric-particles' ground block on each (repaired).
#
#   1. positive — STRICT, every case passes, zero confirmed required findings, two "injection point NEW" moves.
#   2. off — -Dforbric.mixinAtWidenNew=off: exactly the two Fabric cases fail; NeoForge's positions hold.
# GATE-PARALLEL: rundirs=server-widened-new-m53 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-widened-new-m53"
RESULTS="$BUILD/verification/m53-widened-new"
FAIL=0
REPAIRED="{'fall.fabric', 'sprint.fabric'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-widened-new-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricnewdriver.jar" "$KERNEL/run/canary/forbricnewprobe.jar" "$KERNEL"/run/canary/m53-modules/*.jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\nspawn-monsters=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.newProbe=$RESULTS/$phase.json -Dforbric.newPhase=$phase $extra" \
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
assert len(cases) == 4, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: landing and sprint dust carry fabric-particles' ground block"
run_server positive strict ""
judge positive "not failed" "all 4 cases pass"
if python3 - "$RESULTS/positive-compatibility.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1]))
assert r['confirmedRequired'] == 0, r['confirmedRequired']
rows = [f['id'] for f in r['findings'] if 'fabric-particles-v1' in f['id'] and 'modifyBlockStateParticleOption' in f['id'] and f['confidence'] != 'RESOLVED']
assert not rows, rows
PY
then echo "[kernel] PASS positive: zero confirmed required findings; no particle NEW injector reported"
else echo "[kernel] FAIL positive: STRICT report missing, confirmed required findings, or particle injector rows"; FAIL=1; fi
check "positive: fabric-particles' sprint dust point moved" 'EntityMixin: injection point NEW .*BlockParticleOption; names the vanilla constructor' "$RESULTS/positive.log"
check "positive: fabric-particles' landing dust point moved" 'LivingEntityMixin: injection point NEW .*BlockParticleOption; names the vanilla constructor' "$RESULTS/positive.log"

step "2. off: the same server with NEW points left as compiled"
run_server off continue "-Dforbric.mixinAtWidenNew=off"
judge off "failed == $REPAIRED" "exactly the Fabric cases fail; NeoForge's positions hold"
check_absent "off: no NEW point moved" 'injection point NEW' "$RESULTS/off.log"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M53 WIDENED-NEW GATE GREEN — fabric-particles' ground block reaches landing and sprint dust"
else
  echo "[kernel] ❌ M53 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
