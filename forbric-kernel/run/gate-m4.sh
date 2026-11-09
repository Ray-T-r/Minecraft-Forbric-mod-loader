#!/usr/bin/env bash
# M4 gate — Fabric and NeoForge REAL third-party mods (not synthetic canaries) live SIMULTANEOUSLY in ONE dedicated
# server on NeoForge's patched game, with no genuine loader lifecycle for either.
#
# The proof is real mods registering real content, one ecosystem per family, all at once:
#   • Fabric    fabric-api (38 JiJ modules) + Jade — main entrypoints run, Jade registers content + loads its server
#               plugins
#   • NeoForge  the genuine NeoForge baseline (NeoForgeMod, 38 entries + its internal @EventBusSubscribers) PLUS seven
#               real third-party NeoForge @Mods: the NeoForge builds of mcw-bridges, spark, TerraBlender, GeckoLib and
#               NoChatReports (Macaw's registers 303 blocks/items and its creative tab, GeckoLib its own content), and
#               FallingTree and collective, UNIVERSAL jars that ship fabric.mod.json + mods.toml + neoforge.mods.toml
#               and one glue class per family. MultiLoaderArbiter claims each for exactly ONE family — NeoForge, by
#               preference — sets its MinecraftForge half aside and suppresses its Fabric half: without it the same mod
#               initialised more than once.
# So both families run real third-party mod code, none of it synthetic, none of it twice.
#
# `collective` is the reason guest-config relaxation had to be generalised: its jar ALSO ships a Fabric mixin config
# (collective_fabric.mixins.json) whose PlayerMixin descriptor does not match the game. While the launch scripts relaxed
# only `fabric-*`, that was a FATAL MixinApplyError that aborted the boot; the kernel now relaxes every discovered guest
# mod's configs, so it soft-skips with a warning and the mod still loads.
#
# STAGE 4 OF DROPPING MinecraftForge (what changed and why). The game is NeoForge's own patched jar and Forbric carries
# no MinecraftForge runtime, so what this gate asserted about MinecraftForge went with them: the traditional-Forge
# baseline mod, the tick-bridge step (which read MinecraftServer.tickServer out of the merged base's bytecode and
# counted 20 ServerTickEvents forwarded 1:1 from NeoForge's to MinecraftForge's bus, or the bridges standing down), the
# "all N GAME_BUS bridge(s) installed" line and the -Dforbric.unifiedEvents=off control (switch retired with the
# multiplexer, D29), and two absences whose producers were deleted ("no third-party mod constructed as MinecraftForge",
# "no tick forward failure"; D34 — the universal jars' "MinecraftForge half is set aside" lines and "nothing was
# refused" are what stand for them). The boot now stops on "Done (" (stop_when_ready) instead of on the 20th forwarded
# tick; that the server ticked with the real mods loaded is now the stop command's own feedback, as in gate-m2/m3.
# GATE-PARALLEL: rundirs=server-tri-real mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m4-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-tri-real"
DL="$RUN_OLD/downloads"
FABRIC_API="$DL/fabric-26.2/fabric-api-0.154.0+26.2.jar"
JADE="$DL/fabric-26.2/Jade-mc26.2-Fabric-26.2.9.jar"
# Seven real mods, all server-side (side="BOTH"): a content mod, a profiler, a worldgen library, an animation
# library, a chat mod and a gameplay mod — a real cross-section, not one hand-picked easy case. The first five were
# MinecraftForge-only builds until Stage 3 of dropping MinecraftForge and are now the same mods' NeoForge builds
# (stage3-fixtures.md); the last two are universal tri-loader jars, read from the directory they always came from.
NEO_MODS=(
  "$NEO_BUILDS/mcw-bridges-3.1.2-mc26.2neoforge.jar"
  "$NEO_BUILDS/spark-1.10.173-neoforge.jar"
  "$NEO_BUILDS/TerraBlender-neoforge-26.2-26.2.0.0.1.jar"
  "$NEO_BUILDS/geckolib-neoforge-26.2-5.5.3.jar"
  "$NEO_BUILDS/NoChatReports-NEOFORGE-26.2-v2.20.0.jar"
  "$DL/forge-26.2/FallingTree-26.2-25.jar"
  "$DL/forge-26.2/collective-26.2.0-8.39.jar"
)

step "stage real mods (Fabric: fabric-api + Jade; NeoForge: 5 NeoForge builds + 2 universal + the baseline)"
refuse_shared_rundir "$RUNDIR"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
miss=0
for jar in "$FABRIC_API" "$JADE" "${NEO_MODS[@]}"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] MISSING: $jar"; miss=1; fi
done
[ "$miss" -eq 0 ] || { echo "[kernel] FAIL a real mod jar is missing (see $DL)"; exit 1; }
seed_server_properties "$RUNDIR"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot both ecosystems in one instance, stop once it is up"
: > "$LOG"
# Real mods boot slower than the canaries: give the server 20 s before looking for "Done (", then stop_when_ready's own
# cap (150 s more) and stall detection; await_server's limit stays above both.
( stop_when_ready "$LOG" 20 ) | RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 200

step "one list holds both ecosystems' mods (must PASS)"
# Every family's own Mods screen reads its own family's registry and is complete for the loader it was written
# against -- which on this instance is never the whole answer. Assert the NUMBERS, not the sentence: the line
# would still print with a zero in it, and a zero for a family is exactly the regression.
#
# INSTALLED mods, i.e. the jars in mods/. The jars those carry inside themselves are running too, and are not
# what a player means by "my mods" -- on a real pack they outnumber the answer five to one.
CAT=$(grep -oE 'Catalog\] [0-9]+ installed mod\(s\) for the unified Mods screen: [0-9]+ Fabric, [0-9]+ NeoForge, [0-9]+ refused' "$LOG" | head -1)
if [ -n "$CAT" ]; then
  set -- $(echo "$CAT" | grep -oE '[0-9]+')
  assert_eq "the catalogue counts add up"  "$1" "$(( $2 + $3 + $4 ))"
  [ "${2:-0}" -ge 1 ] && echo "[kernel] PASS Fabric mods in the catalogue ($2)"        || { echo "[kernel] FAIL no Fabric mod in the catalogue"; FAIL=1; }
  [ "${3:-0}" -ge 1 ] && echo "[kernel] PASS NeoForge mods in the catalogue ($3)"      || { echo "[kernel] FAIL no NeoForge mod in the catalogue"; FAIL=1; }
  # Stage 3: nothing here is a MinecraftForge mod any more; a refused jar would be counted apart, under no ecosystem.
  assert_eq "no refused jar in the catalogue" 0 "${4:-missing}"
else
  echo "[kernel] FAIL the unified mod catalogue was never published"; FAIL=1
fi

step "both shipping ecosystems' REAL mods brought up in ONE instance (must PASS)"
check "NeoForge baseline"                      "constructed NeoForge baseline mod" "$LOG"
check_absent "and nothing was refused"         "\[Forbric/Unsupported\] refusing" "$LOG"
check "real NeoForge @Mod (mcw-bridges)"       "constructed @Mod mcwbridges \(NeoForge," "$LOG"
check "real NeoForge @Mod (TerraBlender)"      "constructed @Mod terrablender \(NeoForge," "$LOG"
check "real NeoForge @Mod (GeckoLib)"          "constructed @Mod geckolib \(NeoForge," "$LOG"
# The two UNIVERSAL jars (FallingTree, collective) are claimed for exactly one family. collective loads at all only
# because guest-config relaxation is now general (its Fabric-side PlayerMixin cannot apply to this game and
# would otherwise be a fatal MixinApplyError). FallingTree's CLIENT-only @Mod class is not constructed on a
# DEDICATED SERVER — its own annotation says dist = {CLIENT}; the client half is asserted skipped below. So seven:
# the five NeoForge builds and the two universal jars.
check "real third-party NeoForge @Mods (5 builds + 2 universal)" "constructed @Mod [a-z_]+ \(NeoForge," "$LOG" 7
check "and FallingTree's client half stays off the server" \
  "@Mod fallingtree \(.*FallingTreeClient\) declares it belongs to \[CLIENT\]" "$LOG"
check "universal jars arbitrated to ONE family" "declares 3 loaders — loading it as NEOFORGE only" "$LOG" 2
check "their MinecraftForge half was set aside, not chosen" \
  "\[Forbric/MultiLoader\] (FallingTree|collective)[^ ]*\.jar: its MinecraftForge half is set aside" "$LOG" 2
check "Fabric side yields the universal jars"  "skipped [1-9][0-9]* Fabric registration\(s\)" "$LOG"
check "real Fabric mods discovered"            "discovered [1-9][0-9]* Fabric mod\(s\)" "$LOG"
check "real Fabric main entrypoints ran"       "invoked [1-9][0-9]* Fabric main entrypoint\(s\)" "$LOG"

step "each ecosystem registered its REAL content into the one shared registry set (must PASS)"
check "NeoForge content (Macaw's 304)"         "registered content: mcwbridges: 304 entr" "$LOG"
# 151 blocks + 152 items + its CreativeModeTab. The tab is the whole point of the +1: creative_mode_tab has no Forge
# wrapper, and while RegisterEvent was fired only for wrapper-backed registries the tab was never created — the mod's
# 303 blocks/items existed but were unreachable in the creative menu and its search. Assert the tab by name so a
# regression shows up as "the content is invisible in game" rather than as a count that still looks plausible.
check "Macaw's CreativeModeTab exists"         "registered content: mcwbridges: .*creative_mode_tab=1" "$LOG"
check "NeoForge content (GeckoLib)"            "registered content: geckolib: [1-9][0-9]* entr" "$LOG"
# 35 until NeoForge 26.2.0.88, which adds incoming_rpc_method=3 (its new server/jsonrpc API) and
# changes nothing else in the breakdown. The number is a canary for "NeoForge still registers its own built-ins",
# so it is pinned exactly and re-derived from the log when the carrier moves.
check "NeoForge baseline content (38)"         "registered content: neoforge: 38 entr" "$LOG"
check "Fabric mod content (Jade)"              "registered content: jade: [1-9][0-9]* entr" "$LOG"
check "Jade server plugins load"               "Start loading plugin from Jade" "$LOG"

step "the server works (must PASS)"
check "vanilla datapack fully loaded"          "Loaded [1-9][0-9]* recipes" "$LOG"
check "server reached Done"                    "Done \(" "$LOG"
# The tick-bridge step that used to prove the real-mod server ticked went with MinecraftForge; this is what m2, m2b and
# m3 use instead. "Stopping the server" is the /stop command's own feedback, and the console queue is drained only
# inside tickServer(), so it cannot exist unless the tick loop ran with these mods loaded. Bare "Stopping server" is
# stopServer(), reached on every exit path (see gate-m2).
check "server ticked (the stop command ran)"   "Stopping the server|commands\.stop\.stopping" "$LOG"
check "clean shutdown"                         "Stopping server" "$LOG"

step "nothing quietly broken (must be ABSENT)"
check_absent "no NoClassDefFound (the old Forge-tick blocker)" "NoClassDefFoundError" "$LOG"
check_absent "no entrypoint failed"            "entrypoint of .* failed" "$LOG"
check_absent "no Tags not bound"               "Tags not bound" "$LOG"
check_absent "no @Mod construction failure"    "failed to construct @Mod" "$LOG"
check_absent "no genuine FancyModLoader"       "gatherAndInitializeMods|dispatchParallelEvent" "$LOG"
# The kernel ships a FabricLoaderImpl facade that Core Lib links against, so a stack trace through it names the
# class; only Knot and the genuine loader's own setup/load/freeze mean Fabric Loader itself ran.
check_absent "no genuine Fabric Loader"        "KnotClassLoader|net\.fabricmc\.loader\.impl\.launch\.knot|FabricLoaderImpl\.(setup|load|freeze)" "$LOG"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m4-postdone.log"
check_absent "no post-Done exception"          "Encountered an unexpected exception" "$BUILD/gate-m4-postdone.log"

step "M4 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M4 GATE GREEN — REAL third-party Fabric + NeoForge mods run in one instance on NeoForge's patched game"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
