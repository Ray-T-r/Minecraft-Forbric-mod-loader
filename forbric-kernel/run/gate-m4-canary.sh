#!/usr/bin/env bash
# M4-CANARY gate (synthetic) — TRI-IN-ONE server: Fabric + traditional MinecraftForge + NeoForge mods run SIMULTANEOUSLY in ONE
# instance on the merged 3-ABI base, with no genuine loader lifecycle for any of them.
#
# The proof is three canaries, one per ecosystem, all live at once:
#   • Fabric      forbricfabriclive — entrypoints run, JiJ nested mod runs, content registered survives the freeze
#   • NeoForge    forbricneolive    — @Mod constructed, ServerTickEvent fires NATIVELY (Neo won the merged tick hook)
#   • MinecraftForge forbriclive    — @Mod constructed, @EventBusSubscriber registered, ServerTickEvent fires via the
#                                     GameEventMultiplexer (Neo→Forge 1:1 re-emission)
#
# Both tick canaries firing in the same server loop is the B-5 shape: the merged tick hook reaches BOTH families,
# once each, no double-fire. This is past all public prior art (Connector/Kilt/ReForged are 2-ecosystem).
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m4-canary-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-tri"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
FORGE="$RUN_OLD/server-merged/mods/forbriclive.jar"
NEO="$RUN_OLD/neoforge-runtime/forbricneolive.jar"

step "stage one canary per ecosystem"
"$KERNEL/run/build-fabric-canary.sh" >"$BUILD/gate-m4-canary.log" 2>&1
[ -f "$FABRIC" ] || { echo "[kernel] FAIL Fabric canary build (see $BUILD/gate-m4-canary.log)"; exit 1; }

pkill -9 -f "KernelServerLaunch" 2>/dev/null; sleep 1
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
miss=0
for jar in "$FABRIC" "$FORGE" "$NEO"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] MISSING: $jar"; miss=1; fi
done
[ "$miss" -eq 0 ] || { echo "[kernel] FAIL a canary jar is missing"; exit 1; }
printf 'level-seed=forbrickernel\n' > "$RUNDIR/server.properties"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot all three ecosystems in one instance"
: > "$LOG"
( sleep 30; echo stop ) | RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
for i in $(seq 1 130); do
  pgrep -f KernelServerLaunch >/dev/null 2>&1 || break
  grep -qE 'Stopping server|Failed to start the minecraft server' "$LOG" 2>/dev/null && break
  sleep 1
done
pkill -9 -f "KernelServerLaunch" 2>/dev/null; wait "$BOOTPID" 2>/dev/null

step "all three ecosystems discovered + brought up in ONE instance (must PASS)"
check "both Forge-family baselines"           "constructed NeoForge baseline mod" "$LOG"
check "traditional-Forge baseline"            "constructed traditional-Forge baseline mod ForgeMod" "$LOG"
check "NeoForge @Mod constructed"             "constructed @Mod forbricneolive" "$LOG"
check "MinecraftForge @Mod constructed"       "constructed @Mod forbriclive" "$LOG"
# NOT `check`: that counts LINES, so "registered 0 @EventBusSubscriber class(es)" would still pass it. The
# subscriber wiring moved into the registration window, and the way that goes wrong is the count dropping
# to zero while the line itself keeps being printed — so assert the NUMBER.
EBS=$(grep -oE 'registered [0-9]+ @EventBusSubscriber' "$LOG" | grep -oE '[0-9]+' | head -1)
assert_eq "MinecraftForge @EventBusSubscriber classes" 1 "${EBS:-none}"
check "Fabric entrypoints ran"                "invoked [0-9]+ Fabric main entrypoint\(s\) \+ [0-9]+ server" "$LOG"
check "Fabric JiJ nested mod ran"             "\[ForbricFabricLib\] JiJ nested mod initialized" "$LOG"

step "BOTH game-event families tick in the same loop (must PASS — the B-5 1:1 shape)"
check "NeoForge tick fires natively"          "\[ForbricNeoLive\] 20 server ticks observed \(NeoForge native\)" "$LOG"
check "MinecraftForge tick fires via multiplexer" "\[ForbricLive\] 20 server ticks observed - the game loop posts TickEvent" "$LOG"
check "Fabric content survived the freeze"    "onInitializeServer .*registered content survives=true" "$LOG"

step "the server works (must PASS)"
check "vanilla datapack fully loaded"         "Loaded 1585 recipes" "$LOG"
check "server reached Done"                   "Done \(" "$LOG"
check "clean shutdown"                        "Stopping server" "$LOG"

step "nothing quietly broken (must be ABSENT)"
check_absent "no NoClassDefFound (the old Forge-tick blocker)" "NoClassDefFoundError" "$LOG"
check_absent "no tick forward failure"        "forward failed" "$LOG"
check_absent "no entrypoint failed"           "entrypoint of .* failed" "$LOG"
check_absent "no Tags not bound"              "Tags not bound" "$LOG"
check_absent "no genuine FancyModLoader"      "gatherAndInitializeMods|dispatchParallelEvent" "$LOG"
check_absent "no genuine Fabric Loader"       "FabricLoaderImpl|KnotClassLoader" "$LOG"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m4-canary-postdone.log"
check_absent "no post-Done exception"         "Encountered an unexpected exception" "$BUILD/gate-m4-canary-postdone.log"

step "M4 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M4 TRI-IN-ONE GATE GREEN — Fabric + MinecraftForge + NeoForge run in one instance, both families tick 1:1"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
