#!/usr/bin/env bash
# EXPECTED: RED until Phase 1 D (Forge biome modifiers reach the merged world's biomes).
# M25 — two independent biome-modifier pipelines must leave their own blocks in saved overworld chunks.
# RED demonstration: M25_NO_DATA=1 ./gate-m25-worldgen.sh removes data/ from BOTH staged canaries;
# the NeoForge control, served-pack checks and both block probes must fail. No source jar is changed.
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
: > "$LOG"
# Start the generation interval when the server is ready, so a slow build cannot consume it.
(
  for i in $(seq 1 180); do
    grep -aqE 'Done \(' "$LOG" && break
    sleep 1
  done
  sleep 45
  echo save-all
  echo stop
) | FORBRIC_JVM="${M25_EXTRA_JVM:-}" RUNDIR="$RUNDIR" \
  "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 250 30
rm -f "$RUNDIR/.forbric-gate.pid"

step "both canaries reached the datapack repository and NeoForge applied its modifier"
check "server reached Done" 'Done \(' "$LOG"
check "world finished saving" 'All dimensions are saved' "$LOG"
check "NeoForge loaded a nonempty modifier registry" "applied NeoForge's [1-9][0-9]* biome modifier" "$LOG"
check "Forge canary data was served" 'DataPacks\] served .*forbric/data/forbriclive' "$LOG"
check "NeoForge canary data was served" 'DataPacks\] served .*forbric/data/forbricneolive' "$LOG"

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

# Only the missing Forge marker is an accepted baseline failure. A broken NeoForge control, failed boot,
# unreadable region or disabled datapack is a regression, even while the Forge pipeline is still absent.
CONTROL_FAIL=$FAIL
check "Forge marker really generated" '^minecraft:end_stone: [1-9][0-9]*' "$PROBE_LOG"
if [ "$CONTROL_FAIL" -eq 0 ] && [ "$FAIL" -ne 0 ]; then
  echo "[kernel] EXPECTED-RED Forge biome modifiers have not generated end_stone (Phase 1 D)"
  exit 2
fi

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M25 WORLDGEN GATE GREEN — both families changed saved overworld terrain"
else
  echo "[kernel] M25 WORLDGEN GATE RED — see $LOG and $PROBE_LOG"
fi
exit "$FAIL"
