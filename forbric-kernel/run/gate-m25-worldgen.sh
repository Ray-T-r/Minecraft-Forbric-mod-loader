#!/usr/bin/env bash
# M25 — NeoForge's biome and structure modifiers leave the NeoForge canary's blocks in saved overworld chunks.
# RED demonstration: M25_NO_DATA=1 ./gate-m25-worldgen.sh removes data/ from the staged canary; the NeoForge modifier,
# served-pack and probe checks and the block probe must fail. No source jar is changed.
# Second boot (fresh world): -Dforbric.forgeWorldgen=off takes nothing from NeoForge -- its modifier, probes and marker
# must all stay.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). This gate ran two independent pipelines side by side: the
# MinecraftForge canary (forbriclive) proved MinecraftForge's biome and structure modifiers ride inside NeoForge's single
# pass (Phase 1 D, KernelForgeWorldgen) with end_stone in saved chunks, and it refused a block break, a right-click, a
# left-click, an item use and a placement, and emptied its own loot table, through the Neo->Forge bridges. Forbric now
# refuses a MinecraftForge-only jar and a dedicated server stops on the refusal, so the canary is gone and so are its
# checks (end_stone, the bridging and round-trip lines, its probes, its data pack, and the control's MinecraftForge
# half). Everything NeoForge's stays as it was. The NeoForge canary's five event probes stay too, turned around: they
# post NeoForge's own BreakBlockEvent, the two click events, the item use and the placement, and with no refusing
# listener left they must come back untouched (refused=false, DEFAULT decisions) -- the bridges that still forward each
# of them to MinecraftForge's bus, with nobody listening there, must not flip a decision the game reads. The
# -Dforbric.forgeWorldgen=off boot stays as the evidence that the NeoForge half does not lean on the MinecraftForge
# bridge. The worldgen bridge is kernel code Stage 3 keeps; Stage 4 deletes it.
# GATE-PARALLEL: rundirs=server-worldgen-canaries mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m25-worldgen.log"
PROBE_LOG="$BUILD/gate-m25-region.log"
RUNDIR="$KERNEL/run/server-worldgen-canaries"
mkdir -p "$BUILD"

step "stage the NeoForge worldgen canary into a fresh fixed-seed world"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs"
mkdir -p "$RUNDIR/mods"
for jar in "$NEOLIVE"; do
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

step "the canary reached the datapack repository and NeoForge applied its modifier"
check "server reached Done" 'Done \(' "$LOG"
check "world finished saving" 'All dimensions are saved' "$LOG"
check "NeoForge loaded a nonempty modifier registry" "applied NeoForge's [1-9][0-9]* biome modifier" "$LOG"
check "NeoForge canary data was served" 'DataPacks\] served .*forbric/data/forbricneolive' "$LOG"
check "NeoForge probe ran"          'ForbricNeoLive/WORLDGEN\] probe ran: plains has [0-9]+ feature' "$LOG"
check "NeoForge probe saw its feature" 'ForbricNeoLive/WORLDGEN\] plains underground_ores has forbricneolive:probe = true' "$LOG"

step "the MinecraftForge modifier registries are still declared, and the bridge did not stand down"
# Kernel code Stage 3 keeps runs on every boot: with the MinecraftForge carrier present, KernelLifecycle declares
# forge:biome_modifier / forge:structure_modifier on NeoForge's datapack-registry list. That declaration is what makes
# the merged RegistryDataLoader read a universal jar's data/<ns>/forge/biome_modifier and forge/structure_modifier files
# at all; without it those files are silently ignored, though the world still loads, as the off boot below shows. RED:
# the -Dforbric.forgeWorldgen=off boot below prints no declaration line, and its check_absent says so.
check "forge:biome_modifier is declared" 'posted datapack-registry declaration for MinecraftForge.s modifier registries — 2 declared' "$LOG"
check_absent "the bridge did not stand down" 'Forbric/Worldgen\] MinecraftForge modifier bridge standing down' "$LOG"

step "NeoForge's structure modifier reached a structure (D6)"
check "NeoForge structure probe saw its spawn" 'ForbricNeoLive/WORLDGEN\] mineshaft creature override has minecraft:llama = true' "$LOG"

step "NeoForge's own block and interaction events come back untouched with no listener refusing them (must PASS)"
# Until Stage 3 the MinecraftForge canary refused each of these through the Neo->Forge bridges, and these lines carried
# its refusal back. The bridges are still installed, and still forward every one of these events to MinecraftForge's
# bus; with no MinecraftForge mod listening there, the event the game reads must be exactly what NeoForge's own
# listeners left -- here, nobody's -- so a bridge that writes a decision back without one is red.
check "a block break is not refused" 'ForbricNeoLive/BLOCKBREAK\] posted BreakBlockEvent at .* refused=false' "$LOG"
check_absent "the probe did not fail" 'BLOCKBREAK\] probe FAILED' "$LOG"
check "a right-click keeps both default decisions" 'ForbricNeoLive/INTERACT\] right-click useBlock=DEFAULT useItem=DEFAULT' "$LOG"
check "a left-click keeps both default decisions" 'ForbricNeoLive/INTERACT\] left-click useBlock=DEFAULT useItem=DEFAULT' "$LOG"
check "an item use is not refused" 'ForbricNeoLive/INTERACT\] right-click-item refused=false' "$LOG"
check "a placement is not refused" 'ForbricNeoLive/PLACE\] posted EntityPlaceEvent at .* refused=false' "$LOG"

step "the saved overworld contains the NeoForge marker, with no unreadable chunks"
# REGION_PROBE_BEGIN — execute this exact command with an argv recorder in the contract test.
python3 "$KERNEL/run/compat/region-probe.py" "$RUNDIR/world/dimensions/minecraft/overworld/region" \
  minecraft:purpur_block > "$PROBE_LOG" 2>&1 || FAIL=1
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

step "second boot: -Dforbric.forgeWorldgen=off takes nothing from NeoForge"
CONTROL_LOG="$BUILD/gate-m25-worldgen-off.log"
CONTROL_PROBE="$BUILD/gate-m25-region-off.log"
boot "$CONTROL_LOG" "-Dforbric.forgeWorldgen=off"
check "control server reached Done" 'Done \(' "$CONTROL_LOG"
check_absent "control: the switch really took the MinecraftForge declaration out" \
  'posted datapack-registry declaration for MinecraftForge.s modifier registries' "$CONTROL_LOG"
check "control: NeoForge still applied its modifier" "applied NeoForge's [1-9][0-9]* biome modifier" "$CONTROL_LOG"
check "control: NeoForge probe still true" 'ForbricNeoLive/WORLDGEN\] plains underground_ores has forbricneolive:probe = true' "$CONTROL_LOG"
check "control: NeoForge structure probe still true" 'ForbricNeoLive/WORLDGEN\] mineshaft creature override has minecraft:llama = true' "$CONTROL_LOG"
python3 "$KERNEL/run/compat/region-probe.py" "$RUNDIR/world/dimensions/minecraft/overworld/region" \
  minecraft:purpur_block > "$CONTROL_PROBE" 2>&1 || FAIL=1
cat "$CONTROL_PROBE"
check "control: NeoForge marker still generated" '^minecraft:purpur_block: [1-9][0-9]*' "$CONTROL_PROBE"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M25 WORLDGEN GATE GREEN — NeoForge's modifiers changed saved overworld terrain, and its events came back untouched"
else
  echo "[kernel] M25 WORLDGEN GATE RED — see $LOG and $PROBE_LOG"
fi
exit "$FAIL"
