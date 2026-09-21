#!/usr/bin/env bash
# M25 — two independent biome-modifier pipelines must leave their own blocks in saved overworld chunks.
# Green since Phase 1 D: MinecraftForge's biome modifiers ride inside NeoForge's single pass (KernelForgeWorldgen).
# RED demonstration: M25_NO_DATA=1 ./gate-m25-worldgen.sh removes data/ from BOTH staged canaries;
# the NeoForge control, served-pack checks and both block probes must fail. No source jar is changed.
# Negative control (second boot, fresh world): -Dforbric.forgeWorldgen=off — every MinecraftForge claim goes red
# (no bridging line, probe = false, no end_stone, forbriclive named in load-report.txt) while NeoForge's stay green.
# TEETH (recorded 2026-09-20): with the switch off the Forge half read 0 end_stone / probe = false; NeoForge purpur
# and its probe = true were unchanged.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m25-worldgen.log"
PROBE_LOG="$BUILD/gate-m25-region.log"
RUNDIR="$KERNEL/run/server-worldgen-canaries"
mkdir -p "$BUILD"

step "stage the two worldgen canaries into a fresh fixed-seed world"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs"
mkdir -p "$RUNDIR/mods"
for jar in "$RUN_OLD/forge-runtime/forbriclive.jar" "$RUN_OLD/neoforge-runtime/forbricneolive.jar"; do
  [ -f "$jar" ] || { echo "[kernel] FATAL: missing $jar; run build-testmods.sh" >&2; exit 3; }
  cp "$jar" "$RUNDIR/mods/" || exit 3
done
if [ "${M25_NO_DATA:-0}" = 1 ]; then
  python3 - "$RUNDIR/mods" <<'PY' || exit 3
from pathlib import Path
import sys, zipfile
for jar in Path(sys.argv[1]).glob('*.jar'):
    with zipfile.ZipFile(jar) as archive:
        entries = [(item, archive.read(item)) for item in archive.infolist()
                   if not item.filename.startswith('data/')]
    with zipfile.ZipFile(jar, 'w') as archive:
        for item, data in entries:
            archive.writestr(item, data)
PY
fi
seed_server_properties "$RUNDIR"
printf 'online-mode=false\nview-distance=6\nmax-tick-time=-1\n' >> "$RUNDIR/server.properties"
# One fixed-seed boot: generate for 45 s after Done, save, stop. <log> <extra JVM flags>
boot() {
  local log="$1" extra="${2:-}"
  : > "$log"
  rm -rf "$RUNDIR/world" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs"
  # Start the generation interval when the server is ready, so a slow build cannot consume it.
  (
    for i in $(seq 1 180); do
      grep -aqE 'Done \(' "$log" && break
      sleep 1
    done
    sleep 45
    echo save-all
    echo stop
  ) | FORBRIC_JVM="${M25_EXTRA_JVM:-} $extra" RUNDIR="$RUNDIR" \
    "$KERNEL/run/launch-kernel-server.sh" > "$log" 2>&1 &
  BOOTPID=$!
  record_server_pid "$RUNDIR" "$BOOTPID"
  await_server "$BOOTPID" "$log" 250 30
  rm -f "$RUNDIR/.forbric-gate.pid"
}
boot "$LOG"

step "both canaries reached the datapack repository and NeoForge applied its modifier"
check "server reached Done" 'Done \(' "$LOG"
check "world finished saving" 'All dimensions are saved' "$LOG"
check "NeoForge loaded a nonempty modifier registry" "applied NeoForge's [1-9][0-9]* biome modifier" "$LOG"
check "Forge canary data was served" 'DataPacks\] served .*forbric/data/forbriclive' "$LOG"
check "NeoForge canary data was served" 'DataPacks\] served .*forbric/data/forbricneolive' "$LOG"
check "NeoForge probe ran"          'ForbricNeoLive/WORLDGEN\] probe ran: plains has [0-9]+ feature' "$LOG"
check "NeoForge probe saw its feature" 'ForbricNeoLive/WORLDGEN\] plains underground_ores has forbricneolive:probe = true' "$LOG"

step "MinecraftForge's biome modifier rode inside NeoForge's pass (Phase 1 D)"
check "forge:biome_modifier is declared"      'posted datapack-registry declaration for MinecraftForge.s modifier registries — 2 declared' "$LOG"
# The bridge only runs when the round-trip audit is satisfied, so its verdict is an assertion of its own: a
# comparison that reports a difference where there is none stands EVERY MinecraftForge modifier down, and the
# only thing a player sees is a load-report row. RED with M25_EXTRA_JVM=-Dforbric.worldgenAuditNormalise=off on
# a pack whose biomes write out empty spawner categories or a trailing empty decoration step (Stellarity's do:
# 36 of 98 biomes, verified locally) — on this canary pack there are none, so it stays green either way.
check "the MinecraftForge builder round-trip loses nothing" \
  'Forbric/Worldgen\] MinecraftForge builder round-trip: [0-9]+ biome\(s\) and [0-9]+ structure\(s\) checked, 0 differ' "$LOG"
check "Forge modifiers were bridged"          'Forbric/Worldgen\] bridging [1-9][0-9]* MinecraftForge biome modifier' "$LOG"
check "the round-trip audit passed"           'Forbric/Worldgen\] MinecraftForge builder round-trip: [1-9][0-9]* biome\(s\) and [0-9]+ structure\(s\) checked, 0 differ' "$LOG"
check "Forge modifiers changed biomes"        'Forbric/Worldgen\] MinecraftForge modifiers changed [1-9][0-9]* biome' "$LOG"
check "Forge probe ran"                       'ForbricLive/WORLDGEN\] probe ran: plains has [0-9]+ feature' "$LOG"
check "Forge probe saw its feature"           'ForbricLive/WORLDGEN\] plains underground_ores has forbriclive:probe = true' "$LOG"
check_absent "the bridge did not stand down"  'Forbric/Worldgen\] MinecraftForge modifier bridge standing down' "$LOG"

step "MinecraftForge's structure modifier (a mod-registered serializer) rode inside the same pass (D6)"
check "the canary's serializer registered"     'ForbricLive/WORLDGEN\] registered structure modifier serializer forbriclive:probe_spawn' "$LOG"
check "Forge structure modifiers were bridged" 'Forbric/Worldgen\] bridging [1-9][0-9]* MinecraftForge structure modifier' "$LOG"
check "Forge modifiers changed structures"     'Forbric/Worldgen\] MinecraftForge modifiers changed [1-9][0-9]* biome\(s\) and [1-9][0-9]* structure' "$LOG"
check "Forge structure probe saw its spawn"    'ForbricLive/WORLDGEN\] mineshaft creature override has minecraft:mooshroom = true' "$LOG"
check "NeoForge structure probe saw its spawn" 'ForbricNeoLive/WORLDGEN\] mineshaft creature override has minecraft:llama = true' "$LOG"

step "a MinecraftForge mod can still refuse a block break (must PASS)"
# ServerPlayerGameMode on the merged base posts only NeoForge's BreakBlockEvent and branches on its isCanceled();
# it carries no MinecraftForge hook at all. So a MinecraftForge claim or protection mod's BreakEvent listener
# never ran, and the cost was silent both ways round: the mod loaded, its listener was registered, and the block
# simply broke.
#
# Both halves are asserted, because either alone passes with the bridge half-built. The Forge canary must RECEIVE
# the event, and its refusal must come back as isCanceled() on the NeoForge event the game reads — a forward that
# observes but drops the veto gives that mod a listener which runs, decides and is ignored, which looks like it
# works. RED with M25_EXTRA_JVM=-Dforbric.unifiedEvents=off: both lines go red, and so do this gate's other
# MinecraftForge-side checks — that switch turns off EVERY Neo->Forge bridge, including the server-lifecycle one
# the worldgen probes ride on. There is no narrower switch, so the demonstration is read together with the rest.
check "the MinecraftForge canary received the break" \
  'ForbricLive/BLOCKBREAK\] BreakEvent RECEIVED at .* probe=true refused=true' "$LOG"
check "and its refusal reached the event the game reads" \
  'ForbricNeoLive/BLOCKBREAK\] posted BreakBlockEvent at .* refused=true' "$LOG"
check_absent "the probe did not fail" 'BLOCKBREAK\] probe FAILED' "$LOG"

step "and can still see and refuse a click on one (must PASS)"
# The same class in the merged base posts NeoForge's RightClickBlock and LeftClickBlock and nothing of
# MinecraftForge's. These two carry a useBlock/useItem decision as well as a cancel — TriState on one side,
# Result on the other — so the forward has to translate rather than observe, and a translation that goes one way
# only, or the wrong way, is invisible in every log.
#
# The canary refuses a DIFFERENT decision on each event, so a bridge that carried one of the pair still fails.
check "the MinecraftForge canary received the right-click" \
  'ForbricLive/INTERACT\] RightClickBlock RECEIVED at .* probe=true' "$LOG"
check "and its refusal of the BLOCK use crossed back" \
  'ForbricNeoLive/INTERACT\] right-click useBlock=FALSE useItem=DEFAULT' "$LOG"
check "the MinecraftForge canary received the left-click" \
  'ForbricLive/INTERACT\] LeftClickBlock RECEIVED at .* action=START probe=true' "$LOG"
check "and its refusal of the ITEM use crossed back" \
  'ForbricNeoLive/INTERACT\] left-click useBlock=DEFAULT useItem=FALSE' "$LOG"
# The third of the family, and the one that proves the CANCEL half of the read: a cancelling listener on
# MinecraftForge's eventbus is a Predicate that returns true — the event has no setCanceled — so this refusal
# reaches the caller only as post()'s return value.
check "the MinecraftForge canary received the item use" \
  'ForbricLive/INTERACT\] RightClickItem RECEIVED probe=true' "$LOG"
check "and cancelling it crossed back" \
  'ForbricNeoLive/INTERACT\] right-click-item refused=true' "$LOG"

step "a MinecraftForge loot mod can change a table again (must PASS)"
# The merged ReloadableServerRegistries posts only NeoForge's LootTableLoadEvent, so a MinecraftForge loot mod
# adding to or replacing a table on load was a no-op that looked healthy. MinecraftForge's event now sits in the
# same chain, between NeoForge's and Fabric's.
#
# The read-back is the assertion that matters. Receiving the event proves delivery; only reading the live table
# afterwards proves the listener's edit survived the chain — the canary empties its own table's pools, so a
# forward that delivers and discards still leaves an item in it.
check "the MinecraftForge canary was offered its own table" \
  'ForbricLive/LOOT\] LootTableLoadEvent RECEIVED for forbriclive:probe' "$LOG"
check "and the live table is the one it left behind" \
  'ForbricLive/LOOT\] saw [1-9][0-9]* table\(s\); forbriclive:probe now rolls 0 item' "$LOG"
check_absent "the read-back did not fail" 'ForbricLive/LOOT\] read-back FAILED' "$LOG"

step "a MinecraftForge mod can still refuse a block being PLACED (must PASS)"
# The merged ItemStack.useOn calls only NeoForge's onPlaceItemIntoWorld, because the snapshot list it drains is
# NeoForge-typed, so the MinecraftForge event went with it — the other half of every protection rule.
#
# The REPLACED block is asserted, not just the refusal. A snapshot is taken before the block is placed and the
# event posted after, so a bridge that rebuilt the snapshot at forward time would hand a mod the block that was
# just placed and call it the one that was there — and a mod restoring that on cancel would put the new block
# back, which is the exact opposite of refusing the placement.
check "the MinecraftForge canary received the placement" \
  'ForbricLive/PLACE\] EntityPlaceEvent RECEIVED at .* replaced=minecraft:[a-z_]* probe=true' "$LOG"
check "and its refusal reached the event the game reads" \
  'ForbricNeoLive/PLACE\] posted EntityPlaceEvent at .* refused=true' "$LOG"

step "the saved overworld contains both markers, with no unreadable chunks"
# REGION_PROBE_BEGIN — execute this exact command with an argv recorder in the contract test.
python3 "$KERNEL/run/compat/region-probe.py" "$RUNDIR/world/dimensions/minecraft/overworld/region" \
  minecraft:purpur_block minecraft:end_stone > "$PROBE_LOG" 2>&1 || FAIL=1
# REGION_PROBE_END
cat "$PROBE_LOG"
check "NeoForge marker really generated" '^minecraft:purpur_block: [1-9][0-9]*' "$PROBE_LOG"
check "every stored chunk was readable" '^unreadable: 0([[:space:]]|$)' "$PROBE_LOG"
CHUNKS=$(sed -nE 's/^chunks read: ([0-9]+).*/\1/p' "$PROBE_LOG")
if [ -n "$CHUNKS" ] && [ "$CHUNKS" -ge 20 ]; then
  echo "[kernel] PASS sampled $CHUNKS chunks (at least 20)"
else
  echo "[kernel] FAIL too little generated terrain: ${CHUNKS:-no count} chunks (need at least 20)"; FAIL=1
fi

check "Forge marker really generated" '^minecraft:end_stone: [1-9][0-9]*' "$PROBE_LOG"

step "negative control: -Dforbric.forgeWorldgen=off loses exactly the MinecraftForge half"
CONTROL_LOG="$BUILD/gate-m25-worldgen-off.log"
CONTROL_PROBE="$BUILD/gate-m25-region-off.log"
boot "$CONTROL_LOG" "-Dforbric.forgeWorldgen=off"
check "control server reached Done" 'Done \(' "$CONTROL_LOG"
check "control: NeoForge still applied its modifier" "applied NeoForge's [1-9][0-9]* biome modifier" "$CONTROL_LOG"
check "control: NeoForge probe still true" 'ForbricNeoLive/WORLDGEN\] plains underground_ores has forbricneolive:probe = true' "$CONTROL_LOG"
check_absent "control: no Forge bridging" 'Forbric/Worldgen\] bridging [1-9][0-9]* MinecraftForge biome modifier' "$CONTROL_LOG"
check "control: Forge probe false" 'ForbricLive/WORLDGEN\] plains underground_ores has forbriclive:probe = false' "$CONTROL_LOG"
check "control: Forge structure probe false" 'ForbricLive/WORLDGEN\] mineshaft creature override has minecraft:mooshroom = false' "$CONTROL_LOG"
check "control: NeoForge structure probe still true" 'ForbricNeoLive/WORLDGEN\] mineshaft creature override has minecraft:llama = true' "$CONTROL_LOG"
check "control: the shipper is named" 'forgeWorldgen=off — [1-9][0-9]* MinecraftForge mod jar\(s\) ship biome/structure modifiers that will NOT apply: .*forbriclive' "$CONTROL_LOG"
check "control: forbriclive is DEGRADED in the load report" 'forbriclive' "$RUNDIR/.forbric-kernel/load-report.txt"
python3 "$KERNEL/run/compat/region-probe.py" "$RUNDIR/world/dimensions/minecraft/overworld/region" \
  minecraft:purpur_block minecraft:end_stone > "$CONTROL_PROBE" 2>&1 || FAIL=1
cat "$CONTROL_PROBE"
check "control: NeoForge marker still generated" '^minecraft:purpur_block: [1-9][0-9]*' "$CONTROL_PROBE"
check "control: no Forge marker" '^minecraft:end_stone: 0([[:space:]]|$)' "$CONTROL_PROBE"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M25 WORLDGEN GATE GREEN — both families changed saved overworld terrain"
else
  echo "[kernel] M25 WORLDGEN GATE RED — see $LOG and $PROBE_LOG"
fi
exit "$FAIL"
