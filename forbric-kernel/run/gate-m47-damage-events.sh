#!/usr/bin/env bash
# M47 — MinecraftForge's damage events reach a MinecraftForge mod again, and its answers reach the hit.
#
# The merged damage pipeline is NeoForge's: nothing called MinecraftForge's attack, hurt, damage or knockback hooks,
# and its fall hook only for horses and llamas, so a MinecraftForge mod's damage listeners (Tombstone's ghost
# immunity, Voodoo Poppet and perks are exactly these) loaded, registered and never ran. Attack, knockback and fall
# are forwarded off NeoForge's events at the same positions (KernelGameDamageEvents); Hurt, Damage and a player's
# Attack have no such event and are posted by seams in actuallyHurt and Player.hurtServer (ForgeDamageSeamsInjector).
# A dedicated server with a NeoForge mod whose MinecraftForge listeners are written the way Tombstone's are
# (canary/damage-events), driven through the game's own hurtServer, knockback and causeFallDamage:
#   an untouched hit (control) and a vetoed attack; a halved and a cancelled hurt; a lethal hit a LivingDamageEvent
#   listener turns away, and the post-absorption amount that event sees; a player's zero-damage attack (a snowball)
#   and one attack event per hit on a player; an untouched (control) and a cancelled knockback and fall.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. off — -Dforbric.unifiedEvents=off -Dforbric.forgeDamageSeams=off: exactly the repaired cases fail and the
#      controls hold.
# Not covered here: shield blocking (ShieldBlockEvent is forwarded; driving a block needs a raised shield).
# GATE-PARALLEL: rundirs=server-damage-m47 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-damage-m47"
RESULTS="$BUILD/verification/m47-damage-events"
FAIL=0
REPAIRED="{'attack.veto', 'hurt.halve', 'hurt.cancel', 'damage.lethal', 'damage.absorption', 'player.attack.zero', 'player.attack.once', 'knockback.cancel', 'fall.cancel'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-damage-events-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricdamageprobe.jar" "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\nspawn-animals=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.damageProbe=$RESULTS/$phase.json -Dforbric.damagePhase=$phase $extra" \
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
assert len(cases) == 12, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: MinecraftForge's damage listeners run and are obeyed"
run_server positive strict ""
judge positive "not failed" "all 12 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check "positive: the Hurt and Damage seams went into both bodies" 'Forbric/Damage\] net.minecraft.world.entity.LivingEntity: MinecraftForge.s Hurt and Damage' "$RESULTS/positive.log"
check "…and the player's attack seam" 'Forbric/Damage\] net.minecraft.world.entity.player.Player: .*player Attack in hurtServer' "$RESULTS/positive.log"
check_absent "positive: no damage forward or seam failed" 'forward failed|failed inside the damage pipeline' "$RESULTS/positive.log"

step "2. off: the same server with the bridges and the seams switched off"
run_server off continue "-Dforbric.unifiedEvents=off -Dforbric.forgeDamageSeams=off"
judge off "failed == $REPAIRED" "exactly the repaired cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M47 DAMAGE GATE GREEN — MinecraftForge's attack, hurt, damage, knockback and fall listeners run and are obeyed"
else
  echo "[kernel] ❌ M47 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
