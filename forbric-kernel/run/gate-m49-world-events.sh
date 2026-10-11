#!/usr/bin/env bash
# M49 — the rest of MinecraftForge's world and entity events reach a MinecraftForge mod again, and its answers reach
# the game.
#
# A merged call site here posts NeoForge's event and reads it back; where it does not call MinecraftForge's hook too,
# KernelGameWorldEvents forwards it. A dedicated server with a NeoForge mod whose MinecraftForge listeners hear, veto or change
# each one (canary/world-events), driven through the game's own methods where it has one — a section move, waking,
# adding an effect and letting it run out, a lightning-struck pig, farmland fallen on, a command, right-clicking an
# entity, healing, a mob's view of a player, a hoe on dirt — and through NeoForge's own hook where the call is deep in
# a tick (a projectile's impact, a critical hit). Chunk loads and tag reloads are heard at startup; an entity leaving
# the level and an effect running out are checked a second later. Untouched controls beside each answer.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. off — -Dforbric.unifiedEvents=off -Dforbric.neoConversionPost=off: exactly the repaired cases fail and the
#      controls hold — the repaired cases being those whose hook the merged base does not call itself, read from it.
# Not covered here: chunk unloads, permissions (needs a real connected player), anvils (needs an open anvil menu).
# GATE-PARALLEL: rundirs=world-events-m49 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/world-events-m49"
RESULTS="$BUILD/verification/m49-world-events"
FAIL=0
# Every case a kernel repair can decide. Which of them the merged base decides itself is read from the base below.
REPAIRABLE="{'chunk.load', 'tags.updated', 'entity.section', 'player.wakeup', 'effect.added', 'applicable.deny', 'conversion.post', 'conversion.veto', 'projectile.cancel', 'trample.cancel', 'command.cancel', 'interact.specific', 'heal.cancel', 'heal.change', 'visibility.halve', 'critical.force', 'tool.veto', 'effect.expired', 'entity.leave', 'drown.forge', 'drown.neo'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-world-events-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricworldevents.jar" "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\nspawn-animals=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.worldEventsProbe=$RESULTS/$phase.json -Dforbric.worldEventsPhase=$phase $extra" \
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
assert len(cases) == 28, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: MinecraftForge's world and entity listeners hear and are obeyed"
run_server positive strict ""
judge positive "not failed" "all 28 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check_absent "positive: no forward failed" 'forward failed' "$RESULTS/positive.log"

step "2. off: the same server with the bridges switched off"
# Each case below is decided at one merged site, by one family's hook. Where the merged base calls MinecraftForge's
# hook there itself (a merge that keeps both families' edits does at most of these), the case holds with the bridges
# off: the base delivers it, the kernel did not repair it. The drowned zombie is asked both ways: whichever family's
# Post the lambda convertToZombieType captures is native, and the other one is the kernel's (the bridge forwards
# NeoForge's to MinecraftForge; neoConversionPost adds NeoForge's where only MinecraftForge's is there). Only the
# captured lambda counts — the losing compile can stay in the class with nothing calling it. The tag reload, the
# projectile impact and the critical hit are driven where no merged class decides them, so they are always repaired.
# Both directions stay exact: a native call that does not decide its case, or a case that holds without one, is red.
EF=net/minecraftforge/event/ForgeEventFactory
LE=net.minecraft.world.entity.LivingEntity
merged_delivers chunk.load net.minecraft.world.level.chunk.status.ChunkStatusTasks full "$EF.onChunkLoad"
merged_delivers entity.section 'net.minecraft.world.level.entity.PersistentEntitySectionManager$Callback' onMove "$EF.onEntityEnterSection"
merged_delivers player.wakeup net.minecraft.world.entity.player.Player stopSleepInBed "$EF.onPlayerWakeup"
merged_delivers effect.added "$LE" addEffect "$EF.onLivingEffectAdd"
merged_delivers applicable.deny "$LE" canBeAffected "$EF.onLivingEffectCanApply"
merged_delivers conversion.post net.minecraft.world.entity.animal.pig.Pig thunderHit "$EF.onLivingConvert"
merged_delivers conversion.veto net.minecraft.world.entity.animal.pig.Pig thunderHit "$EF.canLivingConvert"
merged_delivers trample.cancel net.minecraft.world.level.block.FarmlandBlock fallOn net/minecraftforge/common/ForgeHooks.onFarmlandTrample
merged_delivers command.cancel net.minecraft.commands.Commands performCommand new:net/minecraftforge/event/CommandEvent
merged_delivers interact.specific net.minecraft.world.entity.player.Player interactOn 'new:net/minecraftforge/event/entity/player/PlayerInteractEvent$EntityInteractSpecific'
merged_delivers heal.cancel "$LE" heal "$EF.onLivingHeal"
merged_delivers heal.change "$LE" heal "$EF.onLivingHeal"
merged_delivers visibility.halve "$LE" getVisibilityPercent net/minecraftforge/common/ForgeHooks.getEntityVisibilityMultiplier
merged_delivers tool.veto net.minecraft.world.item.HoeItem useOn 'net/minecraft/world/level/block/state/BlockState.getToolModifiedState:(Lnet/minecraft/world/item/context/UseOnContext;Lnet/minecraftforge/common/ToolAction;'
merged_delivers effect.expired "$LE" tickEffects "$EF.onLivingEffectExpire"
merged_delivers entity.leave 'net.minecraft.server.level.ServerLevel$EntityCallbacks' onTrackingEnd "$EF.onEntityLeaveLevel"
merged_delivers drown.forge net.minecraft.world.entity.monster.zombie.Zombie convertToZombieType "$EF.onLivingConvert"
merged_delivers drown.neo net.minecraft.world.entity.monster.zombie.Zombie convertToZombieType net/neoforged/neoforge/event/EventHooks.onLivingConvert
NATIVE="set([${MERGED_NATIVE%, }])"
echo "[kernel] off: cases the merged base delivers itself: $NATIVE"
run_server off continue "-Dforbric.unifiedEvents=off -Dforbric.neoConversionPost=off"
judge off "failed == $REPAIRABLE - $NATIVE" "exactly the repaired cases the base does not deliver itself fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M49 WORLD EVENTS GATE GREEN — MinecraftForge world and entity listeners hear and are obeyed"
else
  echo "[kernel] ❌ M49 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
