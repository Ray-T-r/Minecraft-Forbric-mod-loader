#!/usr/bin/env bash
# M2a gate — the sovereign kernel runs a REAL Fabric mod natively, with NO Fabric Loader present.
#
# The kernel implements the Fabric ecosystem itself: it parses fabric.mod.json, extracts JiJ-nested jars, builds
# the mod-facing FabricLoader view, and invokes the entrypoints at the correct lifecycle points — preLaunch before
# any game class loads, `main` inside the registration window (registries unfrozen, so a mod's Registry.register
# works), then the side-specific `server` entrypoint. No net.fabricmc.loader.impl class exists in the process.
#
# The canary (run/build-fabric-canary.sh) is an ordinary Fabric mod: every call it makes is published Fabric API.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m2-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-kernel"
CANARY="$KERNEL/run/canary/forbricfabriclive.jar"

step "build the Fabric canary"
"$KERNEL/run/build-fabric-canary.sh" >"$BUILD/gate-m2-canary.log" 2>&1
if [ ! -f "$CANARY" ]; then echo "[kernel] FAIL canary build (see $BUILD/gate-m2-canary.log)"; exit 1; fi
echo "[kernel] canary built"

step "boot the merged base under the kernel with ONLY the Fabric canary in mods/"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
cp "$CANARY" "$RUNDIR/mods/"
seed_server_properties "$RUNDIR"
: > "$LOG"
( sleep 22; echo stop ) | FORBRIC_JVM="-Dforbric.debug=true" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 90

step "discovery + JiJ (must PASS)"
check "Fabric mods discovered"                "discovered [1-9][0-9]* Fabric mod\(s\) in [1-9][0-9]* jar\(s\)" "$LOG"
check "FabricLoader ready with entrypoint keys" "FabricLoader ready" "$LOG"
check "JiJ nested lib was extracted + initialized" "\[ForbricFabricLib\] JiJ nested mod initialized" "$LOG"
check "nested lib sees its parent mod"        "JiJ nested mod initialized \(parent visible=true\)" "$LOG"

step "entrypoints at the right lifecycle points (must PASS)"
check "preLaunch entrypoint ran"              "\[ForbricFabricLive\] preLaunch entrypoint" "$LOG"
check "main entrypoint ran"                   "\[ForbricFabricLive\] onInitialize \(Fabric main entrypoint\)" "$LOG"
check "server entrypoint ran"                 "\[ForbricFabricLive\] onInitializeServer" "$LOG"
check "custom entrypoint key resolved 2 probes" "custom entrypoint key 'forbric:probe' ran 2 probe" "$LOG"
check "plain-class entrypoint form"           "probe via plain-class entrypoint" "$LOG"
check "Class::STATIC_FIELD entrypoint form"   "probe via Class::STATIC_FIELD entrypoint" "$LOG"

# preLaunch must precede the game's own boot banner; main must follow it.
PRE=$(grep -n 'preLaunch entrypoint' "$LOG" | head -1 | cut -d: -f1)
STARTING=$(grep -nE 'Starting minecraft server|Loaded [0-9]+ recipes' "$LOG" | head -1 | cut -d: -f1)
if [ -n "$PRE" ] && [ -n "$STARTING" ] && [ "$PRE" -lt "$STARTING" ]; then
  printf '[kernel] PASS preLaunch ran BEFORE the game boot (line %s < %s)\n' "$PRE" "$STARTING"
else
  printf '[kernel] FAIL preLaunch ordering (preLaunch=%s gameBoot=%s)\n' "${PRE:-none}" "${STARTING:-none}"; FAIL=1
fi

step "FabricLoader API surface answers correctly (must PASS)"
check "builtin mods resolvable"               "builtins minecraft=true java=true fabricloader=true" "$LOG"
check "runtime namespace is named (Mojmap)"   "namespace=named" "$LOG"
check "game version detected from version.json" "gameVersion=26.2" "$LOG"
check "metadata + customValue round-trip"     "customKind=fabric customExpects=3" "$LOG"
check "findPath reaches inside the mod jar"   "findPath\(fabric.mod.json\) present=true" "$LOG"
check "objectShare round-trip"                "objectShare roundtrip=world" "$LOG"

step "registration window is OPEN for Fabric mods (must PASS)"
check "Registry.register succeeded in onInitialize" "registered custom stat, registry contains it=true" "$LOG"
check "registered content survives the freeze" "onInitializeServer .*registered content survives=true" "$LOG"

step "server reached Done + clean shutdown (must PASS)"
check "server reached Done"                   "Done \(" "$LOG"
check "server ticked + shut down cleanly"     "Stopping server" "$LOG"

step "zero genuine Fabric Loader (must be ABSENT)"
check_absent "no FabricLoaderImpl"            "FabricLoaderImpl" "$LOG"
check_absent "no Knot classloader"            "net\.fabricmc\.loader\.impl\.launch\.knot|KnotClassLoader" "$LOG"
check_absent "no genuine FancyModLoader loading" "gatherAndInitializeMods|dispatchParallelEvent" "$LOG"
check_absent "no entrypoint failed"           "entrypoint of .* failed" "$LOG"

step "no crash (must be ABSENT)"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m2-postdone.log"
check_absent "no post-Done unexpected exception" "Encountered an unexpected exception" "$BUILD/gate-m2-postdone.log"
check_absent "no Tags not bound"              "Tags not bound" "$LOG"

step "M2a result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M2a GATE GREEN — a real Fabric mod runs natively on the sovereign kernel (no Fabric Loader)"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
