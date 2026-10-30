#!/usr/bin/env bash
# M4-CANARY gate (synthetic) — Fabric + NeoForge mods run SIMULTANEOUSLY in ONE instance on the merged base, with no
# genuine loader lifecycle for either, and the MinecraftForge carrier still up with no MinecraftForge mod on it.
#
# The proof is two canaries, one per shipping ecosystem, both live at once:
#   • Fabric      forbricfabriclive — entrypoints run, JiJ nested mod runs, content registered survives the freeze
#   • NeoForge    forbricneolive    — @Mod constructed, ServerTickEvent fires NATIVELY (Neo won the merged tick hook)
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). This gate was TRI-IN-ONE: a third canary, the
# MinecraftForge forbriclive, was constructed here, registered its @EventBusSubscriber and counted 20 server ticks
# re-emitted by GameEventMultiplexer. Forbric now refuses a MinecraftForge-only jar, and a dedicated server stops on
# that refusal, so the canary would stop the boot and take both other families' checks with it. It is no longer
# staged, and its four checks are gone: "MinecraftForge @Mod constructed", the MinecraftForge tick re-emission (the
# carrier's tick bridges still run with zero MinecraftForge mods, and gate-m4 still asserts them on real mods), and
# the @EventBusSubscriber count of 1, which was the MinecraftForge canary's one subscriber. The NeoForge canary
# declares none, so the count (its line prints only for N > 0) must now come back empty: a
# subscriber appearing from nowhere is the regression this can still catch. A NeoForge mod's own @EventBusSubscriber is
# counted by KernelEventSubscribersGenuineBaseTest. What a refused MinecraftForge jar does to a server is gate-m29's.
# Every Fabric and NeoForge check is unchanged; the gate also asserts that nothing here was refused.
# GATE-PARALLEL: rundirs=server-tri mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m4-canary-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-tri"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$NEOLIVE"

step "stage one canary per shipping ecosystem"
"$KERNEL/run/build-fabric-canary.sh" >"$BUILD/gate-m4-canary.log" 2>&1
[ -f "$FABRIC" ] || { echo "[kernel] FAIL Fabric canary build (see $BUILD/gate-m4-canary.log)"; exit 1; }

reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
miss=0
for jar in "$FABRIC" "$NEO"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] MISSING: $jar"; miss=1; fi
done
[ "$miss" -eq 0 ] || { echo "[kernel] FAIL a canary jar is missing"; exit 1; }
seed_server_properties "$RUNDIR"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot both ecosystems in one instance"
: > "$LOG"
( stop_when_ready "$LOG" 30 ) | RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 130

step "both shipping ecosystems discovered + brought up in ONE instance (must PASS)"
check "both Forge-family baselines"           "constructed NeoForge baseline mod" "$LOG"
check "traditional-Forge baseline"            "constructed traditional-Forge baseline mod ForgeMod" "$LOG"
check "NeoForge @Mod constructed"             "constructed @Mod forbricneolive" "$LOG"

# A2: NeoForge's @Mod declares which sides it belongs to, and the kernel constructed every @Mod on every side
# regardless. Sodium's client entry point is the real case; on a dedicated server its constructor reaches a
# client-only type and throws with the mod's name on it. This gate is a dedicated server, so the canary's
# client-only @Mod must be reported and skipped.
# A17: the kernel asked the config tracker to load STARTUP configs during its early pass, on top of the tracker
# already opening each one at registration (javap, ConfigTracker.registerConfig offsets 53..73) — so every STARTUP
# config opened twice, fired its Loading event twice and stacked a second file watcher. The second ask was
# removed; this proves nothing was lost. Reading a value out of a spec that was never opened throws, so an answer
# here means the config really did load.
check "a STARTUP config still loads, by the path that always did the work" \
  "\[ForbricNeoLive\] STARTUP config loaded, startupProbe=startup-default" "$LOG"
check_absent "and no config is opened twice" "Opening a config that was already loaded" "$LOG"

check "a client-only @Mod is recognised as client-only" \
  "@Mod forbricneoclientonly .*declares it belongs to \[CLIENT\]" "$LOG"
check_absent "and its constructor never runs on a server" \
  "ForbricNeoClientOnly\] client-only @Mod CONSTRUCTED" "$LOG"
# It was 1, the MinecraftForge canary's one subscriber. With that canary gone (header) nothing staged here declares
# one, and KernelEventSubscribers prints its "registered N @EventBusSubscriber class(es) on the game bus" line only when
# N > 0, so the count must come back empty: a subscriber appearing from nowhere — a refused jar's, say — is the
# regression now. Read with the same grep as before; KernelEventSubscribersGenuineBaseTest pins the kernel's line
# against this pattern, read out of this file.
EBS=$(grep -oE 'registered [0-9]+ @EventBusSubscriber' "$LOG" | grep -oE '[0-9]+' | head -1)
assert_eq "no @EventBusSubscriber class registered: none of the staged mods declares one" none "${EBS:-none}"
check_absent "no staged jar was refused" "\[Forbric/Unsupported\] refusing|\[Forbric/MultiLoader\] .* is refused" "$LOG"
check "Fabric entrypoints ran"                "invoked [1-9][0-9]* Fabric main entrypoint\(s\) \+ [1-9][0-9]* server" "$LOG"
check "Fabric JiJ nested mod ran"             "\[ForbricFabricLib\] JiJ nested mod initialized" "$LOG"

step "the game loop ticks the NeoForge canary natively (must PASS)"
check "NeoForge tick fires natively"          "\[ForbricNeoLive\] 20 server ticks observed \(NeoForge native\)" "$LOG"

# ModLoadingContext.getActiveContainer() falls back to getModContainerById("minecraft").orElseThrow() when no
# container is active, and the kernel published none — so a mod registering an extension point from outside a
# kernel-wrapped window got NeoForge's own "Where is minecraft???!". Asked from a GAME-bus listener on purpose:
# from a mod constructor the kernel has a container active and the fallback is never reached.
check "getActiveContainer falls back to the minecraft container" \
  "\[ForbricNeoLive\] getActiveContainer\(\) with none active answered minecraft" "$LOG"
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
# The kernel ships a FabricLoaderImpl facade that Core Lib links against, so a stack trace through it names the
# class; only Knot and the genuine loader's own setup/load/freeze mean Fabric Loader itself ran.
check_absent "no genuine Fabric Loader"       "KnotClassLoader|net\.fabricmc\.loader\.impl\.launch\.knot|FabricLoaderImpl\.(setup|load|freeze)" "$LOG"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m4-canary-postdone.log"
check_absent "no post-Done exception"         "Encountered an unexpected exception" "$BUILD/gate-m4-canary-postdone.log"

step "M4 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M4 CANARY GATE GREEN — Fabric + NeoForge run in one instance on the carrier that still holds MinecraftForge"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
