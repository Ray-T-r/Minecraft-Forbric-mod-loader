#!/usr/bin/env bash
# M7-NeoForge gate — REAL, PURE NeoForge mods (jars that carry ONLY neoforge.mods.toml) run on the kernel.
#
# Why this exists as its own gate: gate-m4's "NeoForge @Mods" all come from UNIVERSAL jars (FallingTree, collective
# ship fabric.mod.json + mods.toml + neoforge.mods.toml, and MultiLoaderArbiter claims them for NeoForge). That
# proves the arbiter and the NeoForge construction path, but a universal jar's NeoForge glue is deliberately thin —
# it registers listeners and returns. A mod distributed ONLY for NeoForge leans on the loader much harder, and the
# first two tried here both failed on things gate-m4 could never surface:
#
#   • Bookshelf   ModList.get().getModContainerById("bookshelf") -> narrowed to FMLModContainer, for its own bus.
#                 The kernel published no mods at all, so this was "Could not find mod 'bookshelf'."; once published
#                 as a generated ModContainer subclass it became "Mod 'bookshelf' is not an FML mod!". The container
#                 has to BE net.neoforged.fml.javafmlmod.FMLModContainer (KernelModContainerFactory now allocates a
#                 genuine one with UnsafeHacks, the NeoForge twin of the traditional-Forge container).
#   • Architectury  EventBusesHooks.whenAvailable("architectury") -> "Mod 'architectury' is not available!".
#
# Both are the same shape: a library mod resolves ITSELF through ModList during its own constructor. So the mod set
# below is deliberately library-heavy — that is where the loader contract actually gets exercised.
#
# The gate also pins the regression that fixing the above exposed: publishing a non-empty ModList made NeoForge's
# own GameData.postRegisterEvents (which the kernel had been calling for its bake) start dispatching RegisterEvent
# a SECOND time through ModLoader.postEventWrapContainerInModOrder. Every DeferredRegister then double-registered
# ("Adding duplicate key 'neoforge:condition_codecs / balm:config'"), and NeoForge's error path answers that with
# RegistryManager.revertToVanilla() — which silently rolled the registries back and destroyed 21 baseline entries.
# The baseline counts below are therefore load-bearing, not decoration: they are how that rollback is detected.
# GATE-PARALLEL: rundirs=server-neo-only mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m7-neo-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-neo-only"
DL="$RUN_OLD/downloads/neoforge-26.2"
# Five PURE NeoForge distributions (neoforge.mods.toml only): an optimisation mod that mixins deep into blockstate
# internals, a HUD/network mod, and three library layers that all resolve themselves through ModList.
NEO_MODS=(
  "$DL/ferritecore-9.0.0-neoforge.jar"
  "$DL/appleskin-neoforge-mc26.2-3.0.10.jar"
  "$DL/balm-neoforge-26.2-26.2.0.4.jar"
  "$DL/Bookshelf-neoforge-MC26.2-26.2.0.4.jar"
  "$DL/architectury-neoforge-21.0.6.jar"
)

step "stage pure-NeoForge mods"
kernel_jar
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
miss=0
for jar in "${NEO_MODS[@]}"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] MISSING: $jar"; miss=1; fi
done
[ "$miss" -eq 0 ] || { echo "[kernel] FAIL a NeoForge mod jar is missing (see $DL)"; exit 1; }
# Each staged jar must be NeoForge-ONLY, or this gate silently degrades into a re-run of gate-m4's universal-jar path.
for jar in "$RUNDIR/mods"/*.jar; do
  if unzip -l "$jar" 2>/dev/null | grep -qE 'fabric\.mod\.json$|META-INF/mods\.toml$'; then
    echo "[kernel] FAIL $(basename "$jar") is not a pure NeoForge jar"; exit 1
  fi
done
seed_server_properties "$RUNDIR"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot the kernel with them, reach Done, stop cleanly"
: > "$LOG"
(
  for i in $(seq 1 180); do
    grep -q 'Done (' "$LOG" 2>/dev/null && break
    grep -qE 'Failed to start the minecraft server|Encountered an unexpected exception' "$LOG" 2>/dev/null && break
    sleep 1
  done
  sleep 6
  echo stop
) | FORBRIC_JVM="${M7_EXTRA_JVM:-}" RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 240

step "the registries froze in the one order both carriers' freezeData() finish in (must PASS)"
# MinecraftForge-first aborted NeoForge's GameData.freezeData at the first already-frozen registry
# (bindAllTagsToEmpty → validateWrite throws): the THREW warning on every boot, no tag keys bound to empty, no
# snapshot. NeoForge-first completes; MinecraftForge's pass afterwards early-returns on every plain registry. RED
# with M7_EXTRA_JVM=-Dforbric.freezeNeoForgeFirst=off (the THREW line returns, the count line says MinecraftForge-first).
check_absent "NeoForge's freezeData finished"  "GameData.freezeData\(\) THREW" "$LOG"
check "registries frozen NeoForge-first"       "froze the registries NeoForge-first: [1-9][0-9]* registr(ies|y), [0-9]+ tag key" "$LOG"

step "NeoForge's own data-map reload path is live, and the kernel's fallback stood down (must PASS)"
# The merged base carries the whole path: NeoForgeEventHandler registers the DataMapLoader on
# AddServerReloadListenersEvent, the live condition context is injected, TagsUpdatedEvent applies it. The kernel
# used to load the maps a second time from about-to-start with an EMPTY context, over the genuine apply. RED with
# M7_EXTRA_JVM=-Dforbric.eventBridges=off is not the switch here — the watch observes; the only kernel behaviour to
# switch is the fallback (-Dforbric.neoDataMapFallback=off), under which these lines must STILL pass.
check "NeoForge's own data-map path is live" "reload #1: NeoForge's own reload path applied data maps for [1-9][0-9]* registr" "$LOG"
check "the kernel's fallback stood down"     "already applied data maps for [1-9][0-9]* registr.* fallback stood down" "$LOG"
check_absent "no DataMapLoader registration gap" "DataMapLoader is NOT registered" "$LOG"

step "every pure-NeoForge @Mod constructed (must PASS)"
check "ModList published to the mods"     "published [1-9][0-9]* NeoForge mod\(s\) into ModList" "$LOG"
check "ferritecore"                        "constructed @Mod ferritecore \(NeoForge," "$LOG"
check "appleskin"                          "constructed @Mod appleskin \(NeoForge," "$LOG"
# balm ships TWO @Mod classes under one id: a common one and a client-only one (dist = {CLIENT}). This gate runs
# a DEDICATED SERVER, so exactly ONE of them belongs here. The assertion used to demand 2 — which only held
# because the kernel ignored the annotation and constructed both, and on a real client-only entry point that is a
# crash inside the mod. Asserted as an exact count: ">=1" would still pass if both came back.
assert_eq "balm (its server-side @Mod only)" 1 \
  "$(grep -cE "constructed @Mod balm \(NeoForge," "$LOG")"
check "balm's client-only @Mod stayed off the server" \
  "@Mod balm \(net.blay09.mods.balm.neoforge.client.*declares it belongs to \[CLIENT\]" "$LOG"
check "bookshelf (needs a real FMLModContainer)" "constructed @Mod bookshelf \(NeoForge," "$LOG"
check "architectury (needs ModList self-lookup)" "constructed @Mod architectury \(NeoForge," "$LOG"
check_absent "no @Mod construction failure" "failed to construct @Mod" "$LOG"

step "content registered, and the baseline NOT rolled back (must PASS)"
check "a pure-NeoForge mod registered real content" "registered content: bookshelf: [1-9][0-9]* entr" "$LOG"
# These two are the revertToVanilla() tripwire — see the header.
# 35 until NeoForge 26.2.0.88, which adds incoming_rpc_method=3 (its new server/jsonrpc API) and
# changes nothing else in the breakdown. The number is a canary for "NeoForge still registers its own built-ins",
# so it is pinned exactly and re-derived from the log when the carrier moves.
check "NeoForge baseline intact (38)"      "registered content: neoforge: 38 entr" "$LOG"
check "MinecraftForge baseline intact (10)" "registered content: forge: 10 entr" "$LOG"
check_absent "no double-registration"      "Adding duplicate key" "$LOG"
check_absent "no registry rollback"        "Rolling back to VANILLA state" "$LOG"

step "the server works (must PASS)"
check "server reached Done"                "Done \(" "$LOG"
check "clean shutdown"                     "Stopping server" "$LOG"

step "the full FML mod lifecycle ran on the server side too"
# CommonModLoader.load's task order: construct, common setup, SIDED setup, registration events, IMC, complete.
# The kernel used to know only the first two and the last, so a dedicated server never posted its sided phase,
# no capability was ever registered, and all IMC was dead.
check "construct phase posted"       "posted FML construct to [1-9][0-9]* NeoForge mod"              "$LOG"
check "sided phase posted"           "posted FML dedicated server setup to [1-9][0-9]* NeoForge mod" "$LOG"
check "registration events ran"      "ran NeoForge.s registration events"                       "$LOG"
check "IMC enqueued and processed"   "posted FML IMC (enqueue|process) to [1-9][0-9]* NeoForge mod"  "$LOG" 2
check_absent "no mod failed a phase" "failed during (construct|dedicated server setup|IMC)"     "$LOG"
# ModConfig.Type.SERVER is NOT one of the missing phases, which is worth pinning down rather than re-deriving:
# ServerLifecycleHooks.handleServerAboutToStart loads it through the 3-arg ConfigTracker overload, and the kernel
# never excised that call. The proof is the readme NeoForge's own server-config machinery writes into the world
# folder — and the staging step deletes that folder, so the file can only have come from this run.
[ -f "$RUNDIR/world/serverconfig/readme.txt" ] && SRVCFG=written || SRVCFG=missing
assert_eq "SERVER-type configs loaded for the world" written "$SRVCFG"

step "nothing quietly broken (must be ABSENT)"
check_absent "no NoClassDefFound"          "NoClassDefFoundError" "$LOG"
check_absent "no Tags not bound"           "Tags not bound" "$LOG"
check_absent "no genuine FancyModLoader"   "gatherAndInitializeMods|dispatchParallelEvent" "$LOG"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m7-neo-postdone.log"
check_absent "no post-Done exception"      "Encountered an unexpected exception" "$BUILD/gate-m7-neo-postdone.log"

step "M7-NeoForge result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M7-NEOFORGE GATE GREEN — real PURE NeoForge mods (incl. libraries that resolve themselves through ModList) run on the sovereign kernel"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
