#!/usr/bin/env bash
# M4 gate — TRI-IN-ONE server with REAL third-party mods (not synthetic canaries): Fabric + traditional
# MinecraftForge + NeoForge all live SIMULTANEOUSLY in ONE instance on the merged 3-ABI base, with no genuine
# loader lifecycle for any of them.
#
# The proof is real mods registering real content, one ecosystem per family, all at once:
#   • Fabric         fabric-api (38 JiJ modules) + Jade — main entrypoints run, Jade registers content + loads its
#                    server plugins
#   • MinecraftForge FIVE Forge-only real mods — mcw-bridges, spark, TerraBlender, GeckoLib, NoChatReports —
#                    all construct on genuine FMLJavaModLoadingContexts; Macaw's registers 303 blocks/items,
#                    GeckoLib registers its own content
#   • NeoForge       the genuine NeoForge baseline (NeoForgeMod, 35 entries + its internal @EventBusSubscribers)
#                    PLUS real third-party NeoForge @Mods: FallingTree and collective are UNIVERSAL jars (they ship
#                    fabric.mod.json + mods.toml + neoforge.mods.toml and one glue class per family). All three
#                    loaders are live here, so MultiLoaderArbiter claims each jar for exactly ONE family — NeoForge
#                    by default — and suppresses the other two. Without it the same mod initialised THREE times.
#                    So all three families run real third-party mod code, none of it synthetic, none of it twice.
#
# `collective` is the reason guest-config relaxation had to be generalised: its Forge jar ALSO ships a Fabric mixin
# config (collective_fabric.mixins.json) whose PlayerMixin descriptor no longer matches the merged base. While the
# launch scripts relaxed only `fabric-*`, that was a FATAL MixinApplyError that aborted the boot; the kernel now
# relaxes every discovered guest mod's configs, so it soft-skips with a warning and the mod still loads.
#
# Both game-event families ticking 1:1 in the same loop (the B-5 shape, past all public prior art) is proven by the
# kernel's GameEventMultiplexer: it subscribes to NeoForge's ServerTickEvent (the surviving merged hook) and
# re-emits MinecraftForge's, logging once at 20 forwards — so 20 forwards means both families ticked 20 times, once
# each. Real mods don't count ticks, so this proof is kernel-instrumented rather than canary-emitted.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m4-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-tri-real"
DL="$RUN_OLD/downloads"
FABRIC_API="$DL/fabric-26.2/fabric-api-0.154.0+26.2.jar"
JADE="$DL/fabric-26.2/Jade-mc26.2-Fabric-26.2.9.jar"
# Seven real MinecraftForge-distribution mods, all server-side (5 Forge-only + 2 universal tri-loader) (side="BOTH"): a content mod, a profiler, a worldgen library, an
# animation library, a chat mod and a gameplay mod — a real cross-section, not one hand-picked easy case.
FORGE_MODS=(
  "$DL/forge-26.2/mcw-bridges-3.1.2-mc26.2forge.jar"
  "$DL/forge-26.2/spark-1.10.173-forge.jar"
  "$DL/forge-26.2/TerraBlender-forge-26.2-26.2.0.0.1.jar"
  "$DL/forge-26.2/geckolib-forge-26.2-5.5.3.jar"
  "$DL/forge-26.2/NoChatReports-FORGE-26.2-v2.20.0.jar"
  "$DL/forge-26.2/FallingTree-26.2-25.jar"
  "$DL/forge-26.2/collective-26.2.0-8.39.jar"
)

step "stage real mods (Fabric: fabric-api + Jade; MinecraftForge: 5 Forge-only + 2 universal; NeoForge: baseline)"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
miss=0
for jar in "$FABRIC_API" "$JADE" "${FORGE_MODS[@]}"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] MISSING: $jar"; miss=1; fi
done
[ "$miss" -eq 0 ] || { echo "[kernel] FAIL a real mod jar is missing (see $DL)"; exit 1; }
printf 'level-seed=forbrickernel\n' > "$RUNDIR/server.properties"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot all three ecosystems in one instance, run until both families have ticked 20×"
: > "$LOG"
# Stop as soon as the 1:1 tick proof lands (real mods boot slower than the old canaries, so a fixed timer would
# either cut ticking short or waste minutes); fall back to a hard cap so a stuck boot fails loudly instead of hanging.
(
  for i in $(seq 1 150); do
    grep -q 'bridged 20 ServerTickEvent.Post to MinecraftForge' "$LOG" 2>/dev/null && break
    grep -qE 'Failed to start the minecraft server|Encountered an unexpected exception' "$LOG" 2>/dev/null && break
    sleep 1
  done
  sleep 2
  echo stop
) | RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 200

step "all three ecosystems' REAL mods brought up in ONE instance (must PASS)"
check "both Forge-family baselines"            "constructed NeoForge baseline mod" "$LOG"
check "traditional-Forge baseline"             "constructed traditional-Forge baseline mod ForgeMod" "$LOG"
check "all 5 Forge-only real @Mods"            "constructed @Mod [a-z_]+ \(traditional-Forge," "$LOG" 5
check "real MinecraftForge @Mod (mcw-bridges)" "constructed @Mod mcwbridges \(traditional-Forge," "$LOG"
check "real MinecraftForge @Mod (TerraBlender)" "constructed @Mod terrablender \(traditional-Forge," "$LOG"
check "real MinecraftForge @Mod (GeckoLib)"    "constructed @Mod geckolib \(traditional-Forge," "$LOG"
# The two UNIVERSAL jars (FallingTree, collective) are claimed for exactly one family and give the gate its real
# third-party NeoForge @Mods. collective loads at all only because guest-config relaxation is now general (its
# Fabric-side PlayerMixin cannot apply on the merged base and would otherwise be a fatal MixinApplyError).
check "real third-party NeoForge @Mods"        "constructed @Mod [a-z_]+ \(NeoForge," "$LOG" 3
check "universal jars arbitrated to ONE family" "declares 3 loaders — loading it as" "$LOG" 2
check "Fabric side yields the universal jars"  "skipped [0-9]+ Fabric registration\(s\)" "$LOG"
check "real Fabric mods discovered"            "discovered [0-9]+ Fabric mod\(s\)" "$LOG"
check "real Fabric main entrypoints ran"       "invoked [0-9]+ Fabric main entrypoint\(s\)" "$LOG"

step "each ecosystem registered its REAL content into the one shared registry set (must PASS)"
check "MinecraftForge content (Macaw's 304)"   "registered content: mcwbridges: 304 entr" "$LOG"
# 151 blocks + 152 items + its CreativeModeTab. The tab is the whole point of the +1: creative_mode_tab has no Forge
# wrapper, and while RegisterEvent was fired only for wrapper-backed registries the tab was never created — the mod's
# 303 blocks/items existed but were unreachable in the creative menu and its search. Assert the tab by name so a
# regression shows up as "the content is invisible in game" rather than as a count that still looks plausible.
check "Macaw's CreativeModeTab exists"         "registered content: mcwbridges: .*creative_mode_tab=1" "$LOG"
check "MinecraftForge content (GeckoLib)"      "registered content: geckolib: [0-9]+ entr" "$LOG"
check "NeoForge baseline content (35)"         "registered content: neoforge: 35 entr" "$LOG"
check "Fabric mod content (Jade)"              "registered content: jade: [0-9]+ entr" "$LOG"
check "Jade server plugins load"               "Start loading plugin from Jade" "$LOG"

step "BOTH game-event families tick in the same loop (must PASS — the B-5 1:1 shape)"
check "NeoForge+Forge tick 1:1 (Pre)"          "bridged 20 ServerTickEvent.Pre to MinecraftForge — 1:1 from NeoForge" "$LOG"
check "NeoForge+Forge tick 1:1 (Post)"         "bridged 20 ServerTickEvent.Post to MinecraftForge — 1:1 from NeoForge" "$LOG"
# The tick bridges are only two of five. The other three were installed in the same try{} and summed into one
# unasserted number, so a setup failure in the first one silently skipped the rest -- including the one whose
# absence leaves MinecraftForge's login gate permanently closed. Assert the whole declared set, by count.
check "every declared Neo→Forge game-event bridge installed" \
  "all 5 GAME_BUS bridge\(s\) installed" "$LOG"
check_absent "and none reported missing"       "bridge\(s\) MISSING" "$LOG"

step "the server works (must PASS)"
check "vanilla datapack fully loaded"          "Loaded [0-9]+ recipes" "$LOG"
check "server reached Done"                    "Done \(" "$LOG"
check "clean shutdown"                         "Stopping server" "$LOG"

step "nothing quietly broken (must be ABSENT)"
check_absent "no NoClassDefFound (the old Forge-tick blocker)" "NoClassDefFoundError" "$LOG"
check_absent "no tick forward failure"         "forward failed" "$LOG"
check_absent "no entrypoint failed"            "entrypoint of .* failed" "$LOG"
check_absent "no Tags not bound"               "Tags not bound" "$LOG"
check_absent "no @Mod construction failure"    "failed to construct @Mod" "$LOG"
check_absent "no genuine FancyModLoader"       "gatherAndInitializeMods|dispatchParallelEvent" "$LOG"
check_absent "no genuine Fabric Loader"        "FabricLoaderImpl|KnotClassLoader" "$LOG"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m4-postdone.log"
check_absent "no post-Done exception"          "Encountered an unexpected exception" "$BUILD/gate-m4-postdone.log"

step "negative control: the same instance with -Dforbric.unifiedEvents=off"
# Without this the tick-1:1 assertions above could be passing for a reason that has nothing to do with the
# multiplexer -- e.g. the merged base firing both families' hooks after all. Turning the bridges off must take the
# forwarding with it, and must NOT take the server down: a missing bridge is a degraded instance, not a broken one.
CONTROL="$BUILD/gate-m4-control.log"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/.forbric-kernel" 2>/dev/null
( sleep 30; echo stop ) | RUNDIR="$RUNDIR" FORBRIC_JVM="-Dforbric.unifiedEvents=off" \
  "$KERNEL/run/launch-kernel-server.sh" > "$CONTROL" 2>&1 &
CTLPID=$!
record_server_pid "$RUNDIR" "$CTLPID"
await_server "$CTLPID" "$CONTROL" 200

check "the control booted"                     "Done \(" "$CONTROL"
check "the control says it installed no bridges" "installing no bridges" "$CONTROL"
check_absent "and MinecraftForge gets no forwarded Pre tick"  "bridged 20 ServerTickEvent.Pre"  "$CONTROL"
check_absent "and MinecraftForge gets no forwarded Post tick" "bridged 20 ServerTickEvent.Post" "$CONTROL"

step "M4 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M4 TRI-IN-ONE GATE GREEN — REAL third-party Fabric + MinecraftForge + NeoForge mods run in one instance, both families tick 1:1"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
