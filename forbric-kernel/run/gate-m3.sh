#!/usr/bin/env bash
# M3-server gate — the kernel natively constructs BOTH ecosystem baselines and loads a real Forge-family @Mod.
#
# On the merged base the kernel: constructs the NeoForge baseline mod (NeoForgeMod) AND the traditional-Forge
# baseline mod (ForgeMod) via the container factory + UnsafeHacks, fires each family's RegisterEvent so their
# default content registers (e.g. the empty fluid types EntityFluidInteraction reads for every entity), and
# constructs a discovered real @Mod (its own ctor runs) — all with no genuine FancyModLoader/FML lifecycle. Then
# the server reaches Done, ticks, and shuts down cleanly.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m3-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-kernel"
CANARY="$OLD/run/neoforge-runtime/forbricneolive.jar"

step "stage a real NeoForge @Mod + boot under the kernel"
"$KERNEL/gradlew" --offline -q -p "$KERNEL" jar >/dev/null 2>&1
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" 2>/dev/null
rm -rf "$RUNDIR/mods" 2>/dev/null; mkdir -p "$RUNDIR/mods"
if [ -f "$CANARY" ]; then cp "$CANARY" "$RUNDIR/mods/"; else echo "[kernel] WARN canary mod absent: $CANARY"; fi
# Pin a seed for reproducible worldgen (entity spawns exercise the Forge/Neo fluid-type RegistryObjects).
seed_server_properties "$RUNDIR"
: > "$LOG"
( sleep 22; echo stop ) | FORBRIC_JVM="-Dforbric.debug=true" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 90

step "native ecosystem construction (must PASS)"
check "NeoForge baseline mod constructed"        "constructed NeoForge baseline mod" "$LOG"
check "traditional-Forge baseline mod constructed" "constructed traditional-Forge baseline mod ForgeMod" "$LOG"
check "NeoForge RegisterEvent fired for content"  "fired RegisterEvent x[1-9][0-9]* on [1-9][0-9]* bus" "$LOG"
check "Forge RegisterEvent fired for content"     "fired Forge RegisterEvent x[1-9][0-9]*" "$LOG"
check "real @Mod discovered + constructed"        "constructed @Mod forbricneolive" "$LOG"
check "mod's own ctor ran (its log line)"         "@Mod\(.forbricneolive.\) constructed" "$LOG"
check "game event buses started"                  "started NeoForge.EVENT_BUS" "$LOG"
check "mod's ServerTickEvent listener FIRED (game loop)" "server ticks observed" "$LOG"

step "server reached Done + clean shutdown (must PASS)"
check "server reached Done"                       "Done \(" "$LOG"
check "server ticked + shut down cleanly"         "Stopping server" "$LOG"

step "no crash (must be ABSENT)"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m3-postdone.log"
check_absent "no post-Done unexpected exception"  "Encountered an unexpected exception" "$BUILD/gate-m3-postdone.log"
check_absent "no Forge/Neo RegistryObject unbound" "Registry Object not present|Trying to access unbound value" "$LOG"
check_absent "no Tags not bound"                  "Tags not bound" "$LOG"
check_absent "no genuine FancyModLoader loading"  "gatherAndInitializeMods|dispatchParallelEvent" "$LOG"

step "M3 result"
if [ "$FAIL" -eq 0 ]; then echo "[kernel] ✅ M3-server GATE GREEN — both baselines + real @Mod constructed natively, Done, zero genuine lifecycle";
else echo "[kernel] ❌ GATE RED"; fi
exit "$FAIL"
