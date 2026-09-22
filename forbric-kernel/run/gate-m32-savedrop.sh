#!/usr/bin/env bash
# M32 — a world written by a set of mods still opens when one of them is GONE.
#
# WHY THIS EXISTS. run/make-test-world.sh states the asymmetry in its own header: "a save may be opened by a
# superset of the mods that wrote it; the reverse is what rots." Nothing tested the reverse. Every gate in this
# tree opens a world with the mods that made it, and a player's mods folder changes constantly — a mod is
# removed, a pack is updated, a jar is disabled to bisect a crash. When that goes wrong on a loader the symptom
# is not a message, it is a world that will not open, and "Forbric ate my save" and "Forbric does not support my
# mod" are the same sentence to the person saying it.
#
# The mod dropped is mcw-bridges, chosen because it registers hundreds of blocks and items: the save genuinely
# contains its content, so the second boot has to resolve references to a mod that is no longer there. Dropping
# a mod whose content never reached the region files would make this gate pass without asking anything.
#
# GATE-PARALLEL: rundirs=savedrop mem=2500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

PORT="${M32_PORT:-25805}"
DIR="$KERNEL/run/savedrop"
LOG_FULL="$BUILD/gate-m32-full.log"
LOG_DROPPED="$BUILD/gate-m32-dropped.log"
mkdir -p "$BUILD"

DL="$RUN_OLD/downloads"
DROPPED_JAR="$DL/forge-26.2/mcw-bridges-3.1.2-mc26.2forge.jar"
KEPT=(
  "$DL/fabric-26.2/fabric-api-0.154.0+26.2.jar"
  "$DL/forge-26.2/collective-26.2.0-8.39.jar"
)
for m in "$DROPPED_JAR" "${KEPT[@]}"; do
  [ -f "$m" ] || { echo "[kernel] SKIP-FATAL: missing mod $m" >&2; exit 3; }
done

kernel_jar

# Boot, wait for Done, put the dropped mod's content into the world, flush, stop.  <log> <extra-stdin-commands>
boot() {
  local log="$1" extra="${2:-}"
  : > "$log"
  reap_stale_server "$DIR"
  (
    for i in $(seq 1 240); do
      grep -aqE 'Done \(' "$log" && break
      grep -aqE 'Failed to start the minecraft server' "$log" && break
      sleep 1
    done
    sleep 5
    # Semicolon-separated, each with room to be answered. The first attempt sent one setblock and got "That
    # position is not loaded": with nobody online the server does not hold the spawn chunks, so the command has
    # to forceload first — the same reason gate-m31 forceloads before it generates.
    if [ -n "$extra" ]; then
      printf '%s\n' "$extra" | tr ';' '\n' | while IFS= read -r cmd; do
        [ -n "$cmd" ] || continue
        echo "$cmd"
        sleep 4
      done
    fi
    echo save-all flush
    sleep 10
    echo stop
  ) | RUNDIR="$DIR" "$KERNEL/run/launch-kernel-server.sh" > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$DIR" "$pid"
  await_server "$pid" "$log" 300 45
  rm -f "$DIR/.forbric-gate.pid"
}

step "1. write a world WITH the mod that is about to be dropped"
rm -rf "$DIR"
mkdir -p "$DIR/mods"
cp "$DROPPED_JAR" "${KEPT[@]}" "$DIR/mods/"
echo "eula=true" > "$DIR/eula.txt"
printf 'level-seed=forbrickernel\nlevel-name=world\nserver-port=%s\nonline-mode=false\nview-distance=6\nmax-tick-time=-1\nsync-chunk-writes=true\npause-when-empty-seconds=0\n' \
  "$PORT" > "$DIR/server.properties"
# setblock, not just "generate terrain": the whole point is that the save REFERENCES the mod. A world that
# merely loaded it and stored nothing of it would make the second boot trivially fine.
boot "$LOG_FULL" "forceload add 0 0;setblock 0 -60 0 mcwbridges:acacia_bridge_pier"

check "the first server started"        "Done \("                         "$LOG_FULL"
check "the dropped mod was loaded"      "mcwbridges|mcw-bridges"          "$LOG_FULL"
# "Changed the block" is vanilla's own success line, and "Unknown block type" is what the first attempt at
# this gate produced from a guessed id — an assertion that accepted either would have passed on a world that
# contains nothing of the mod, which is the one thing this gate must not do.
check "its block was placed in the world" "Changed the block"        "$LOG_FULL"
check_absent "the block id is a real one" "Unknown block type"       "$LOG_FULL"
check "the first server stopped cleanly" "Stopping server"                "$LOG_FULL"
# 26.2 puts the overworld under dimensions/, not at world/region. Looking in the old place reported "no region
# files" on a world that had them, which would have made this gate red for the wrong reason forever.
REGIONS="$(find "$DIR/world" -type d -name region 2>/dev/null | head -1)"
if [ -n "$REGIONS" ] && [ -n "$(ls -A "$REGIONS" 2>/dev/null)" ]; then
  echo "[kernel] PASS the world has region files ($REGIONS: $(ls -1 "$REGIONS" | wc -l | tr -d ' ') file(s))"
else
  echo "[kernel] FAIL no region files were written under $DIR/world"; FAIL=1
fi

step "2. take the mod away and open the SAME world again"
rm -f "$DIR/mods/$(basename "$DROPPED_JAR")"
echo "[kernel] mods now: $(ls -1 "$DIR/mods" | paste -sd' ' -)"
boot "$LOG_DROPPED"

# The gate's whole claim, and each half is separately capable of being false.
check "the reduced server started"       "Done \("                        "$LOG_DROPPED"
check_absent "the dropped mod is gone"   "mcw-bridges-3\.1\.2" "$LOG_DROPPED"
check "the reduced server stopped cleanly" "Stopping server"              "$LOG_DROPPED"
check_absent "no crash opening the reduced world" "Exception in thread \"main\"|Failed to start the minecraft server|Encountered an unexpected exception" "$LOG_DROPPED" '\[Forbric/'

# Vanilla's own answer to content it cannot resolve is to substitute a default and say so. That is FINE and is
# what "the world still opens" means; what is not fine is a silent exit or a stack trace. Reported as evidence,
# not asserted on, because how many appear depends on how much of the mod's content the world happened to hold.
echo "[kernel] recoverable-section notes: $(grep -acE 'Recoverable errors when loading|Unknown registry key' "$LOG_DROPPED" || echo 0)"

step "M32 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M32 GATE GREEN — a world written by three mods opened again with one of them removed"
else
  echo "[kernel] ❌ M32 GATE RED — full $LOG_FULL / dropped $LOG_DROPPED"
fi
exit "$FAIL"
