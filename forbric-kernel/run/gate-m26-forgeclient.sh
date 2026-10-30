#!/usr/bin/env bash
# M26 — the client event buses must receive the game's real registration events, and a refused MinecraftForge mod is
# put to the player.
# NeoForge half (forbricneolive rides along): the same registration events in NeoForge's shape, read back from the
#   same consumer tables, plus its block's id. These outlive the MinecraftForge canary. NeoForge posts all twelve
#   itself, through ModLoader.postEvent, whose only fan-out is ModList.forEachModInOrder -- so the one kernel behaviour
#   under them is publishing the canary's container into NeoForge's ModList, and that has a switch.
#   RED with M26_EXTRA_JVM='-Dforbric.publishModList=off' (measured 2026-10-30, 21 of 77 red): the twelve NeoForge
#   "received" checks and the eight read-backs that follow from them (key in Options, the F9 binding, the baked layer,
#   the tint, both reload checks, the tooltip factory, the gui layer's frames), plus the MinecraftForge canary's
#   RegisterPresetEditorsEvent, which arrives off NeoForge's. The client still reaches its world: the canary subscribes
#   (on the bus its constructor is handed), reaches world tick 100, its block has an id, its plugin sees the client.
#   Those four NeoForge checks stay POSITIVE-ONLY, with no switch that turns them red: M26_EXTRA_JVM=
#   '-Dforbric.blockStateIdRefill=off' leaves the NeoForge block's id alone, because a NeoForge block is registered in
#   the first wave and the map's first fill covers it; and with '-Dforbric.forgeClientInit=off' Minecraft.<init> calls
#   NeoForge's own ClientHooks.initClientHooks directly, so all eleven NeoForge registrations made before the world
#   still arrive (the MinecraftForge canary's client then never reaches its world, so the in-world read-backs cannot be
#   judged in that run). What these prove is that each registration reaches its table on the merged base, through
#   whichever route the base takes.
# Fabric half (forbricfabriclive rides along, with -Dforbricfabriclive.lateBlock=true): the refill's own proof, moved
#   off MinecraftForge. The late registration wave the blockstate→id refill exists for is MinecraftForge's today and a
#   Fabric client entrypoint's after it, so the Fabric canary registers a block from onInitializeClient and reads its id
#   back in the world. RED with M26_EXTRA_JVM='-Dforbric.blockStateIdRefill=off': the late block's id is 0 (AIR) --
#   measured with the MinecraftForge pair beside it, the two Forge checks and the two Fabric ones below go red, and
#   that is the demonstration that survives Stage 4 deleting the MinecraftForge canary.
# Green since Phase 1 A landed (A3–A9 hooks, A8 bridge inventory); any red is a regression, exit 1.
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). Until Stage 3 the MinecraftForge canary (forbriclive)
#   joined this world too, and most of this gate was its: ten traditional-Forge client registration events and their
#   consumer read-backs, its HUD overlay layer drawing and its picture-in-picture renderer, a MinecraftForge fluid's
#   client extensions, its asset read from client setup, its creative injection, and where its @Mod was constructed.
#   Forbric now refuses a MinecraftForge-only jar, and a client launched straight into a world (quick-play) with a
#   refused jar stops, so the canary cannot join; every check that read it is gone (the M26_ASSERTIONS block and the H5
#   fluid step), and so are the -Dforbric.forgePipRenderers / forgeCtorGameInstance / forgeCreativeTabs RED controls,
#   which only those checks could show; forgeOverlayLayers and forgeFluidModels are RED controls again for the session
#   checks below (step 1b). Unchanged: the NeoForge half and the Fabric half
#   (written in Stage 2.5 to outlive them) and the session checks every client gate makes, kept in M26_SESSION_ASSERTIONS
#   with every kernel line that still prints with no MinecraftForge mod aboard, because each is a rewrite of vanilla or
#   NeoForge code that runs on every boot: both bridge sets installed, the client resource manager preloaded, the merged
#   Hud's own MinecraftForge overlay build and draw dropped (without that drop no NeoForge, Fabric or vanilla HUD layer
#   draws), MinecraftForge's overlay stack put on NeoForge's layer manager, GuiRenderer's orphaned picture-in-picture
#   map given its only writer (Xaero's minimap and malilib read it), and the fluid funnel consulted for vanilla water —
#   which the NeoForge canary now places beside spawn (-Dforbricneolive.placeWater=true), as the MinecraftForge canary
#   did, because the once-generated save has a random seed. Their RED controls stand:
#   M26_EXTRA_JVM='-Dforbric.forgeOverlayLayers=off' (the stack check) and '-Dforbric.forgeFluidModels=off' (the fluid
#   check); the HUD drop is exact both ways against the base the run read (HUD_TREE). The CLIENT_CANARY_STAGE block now stages the NeoForge canary, its data/ stripped as before, and
#   the persisted key binding is the NeoForge canary's F9.
#   In its place, phase 2 puts the canary in front of the user-visible switch on the CLIENT side, where the answer is a
#   question rather than a stop: launched without quick-play under the ask policy, with the confirmation window unable
#   to draw (FORBRIC_DEP_DIALOG=dryRun, as gate-m20 does), the refusal is a confirmed required finding naming the mod,
#   the question moves into the game's own window, and nothing stops the launch before the player has answered.
#   The server side of the switch (a stop) is gate-m29's. The file keeps its name until Stage 4. The red counts in the
#   notes above were measured with the MinecraftForge canary aboard; the NeoForge and Fabric checks they name are unchanged.
# GATE-PARALLEL: rundirs=client-forge-registration,client-forge-refusal mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m26-forgeclient.log"
RUNDIR="$KERNEL/run/client-forge-registration"
WORLD="ForgeClientProbe"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
mkdir -p "$BUILD" "$RUNDIR/quickPlay" "$RUNDIR/empty-mods"
[ -f "$NEOLIVE" ] || { echo "[kernel] FATAL: missing $NEOLIVE; run build-testmods.sh or run/build-neolive-canary.sh" >&2; exit 3; }
# The Fabric canary is built from this tree every run, as gate-m4/m18/m30 build it.
"$KERNEL/run/build-fabric-canary.sh" > "$BUILD/gate-m26-fabric-canary.log" 2>&1
[ -f "$FABRIC" ] || { echo "[kernel] FATAL: the Fabric canary did not build (see $BUILD/gate-m26-fabric-canary.log)" >&2; exit 3; }

step "generate a zero-mod save once, then join it with the NeoForge and Fabric client canaries"
reap_stale_server "$RUNDIR"
if [ ! -f "$RUNDIR/saves/$WORLD/level.dat" ]; then
  TARGET="$RUNDIR" WORLD="$WORLD" SRC_MODS="$RUNDIR/empty-mods" \
    "$KERNEL/run/make-test-world.sh" || exit 1
fi
# The dedicated server saves this acknowledgement as false. The carriers can give even a zero-mod
# world an experimental generation lifecycle; make the fixture's consent explicit before quick-play.
# WORLD_CONFIRM_BEGIN — the contract test records this exact invocation against a temporary save.
python3 "$KERNEL/run/compat/win/prepare-world.py" "$RUNDIR/saves/$WORLD/level.dat" || exit 3
# WORLD_CONFIRM_END
rm -rf "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs"
mkdir -p "$RUNDIR/mods"
# A zero-mod save must not acquire a new worldgen datapack during quick-play. M25 exercises the full data.
# CLIENT_CANARY_STAGE_BEGIN — the test executes this exact staging step against a fixture jar.
python3 - "$NEOLIVE" "$RUNDIR/mods/forbricneolive.jar" <<'PY_STAGE' || exit 3
from pathlib import Path
import sys, zipfile
source, target = map(Path, sys.argv[1:])
if source.resolve() == target.resolve():
    raise SystemExit('the client fixture must not overwrite its source canary')
with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'w') as staged:
    for entry in original.infolist():
        if not entry.filename.startswith('data/'):
            staged.writestr(entry, original.read(entry))
PY_STAGE
# CLIENT_CANARY_STAGE_END
# The Fabric canary as built: it carries no data/ at all, so nothing in it can give the save a datapack.
cp "$FABRIC" "$RUNDIR/mods/forbricfabriclive.jar" || exit 3
# Skip the first-run accessibility screen so quick-play can reach the saved world.
# M26_OPTIONS_BEGIN — the NeoForge canary's key defaults to F8; this persisted F9 must survive its registration.
python3 - "${MERGED:-$RUN_OLD/merged-base/patched-mc-merged-26.2.jar}" "$RUNDIR/options.txt" <<'PY_OPTIONS' || exit 3
import json, sys, zipfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[1]) as game:
    version = json.loads(game.read('version.json'))['world_version']
if not isinstance(version, int) or version <= 0:
    raise SystemExit('game metadata has no valid options data version')
# Without version, OptionsKeyLwjgl3Fix treats the modern key name as a legacy integer key code.
Path(sys.argv[2]).write_text(f'version:{version}\nonboardAccessibility:false\nkey_key.forbricneolive.probe:key.keyboard.f9\n')
PY_OPTIONS
# M26_OPTIONS_END
: > "$LOG"
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=160 -Dforbricfabriclive.lateBlock=true -Dforbricneolive.placeWater=true ${M26_EXTRA_JVM:-}" \
RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$LOG" 2>&1 &
CLIENT_PID=$!
record_server_pid "$RUNDIR" "$CLIENT_PID"
await_server "$CLIENT_PID" "$LOG" 360 30
rm -f "$RUNDIR/.forbric-gate.pid"
cat "$RUNDIR/logs/latest.log" >> "$LOG" 2>/dev/null || true

# Whether the merged Hud names MinecraftForge's overlay tree (a both-edits base builds and draws it there itself),
# for the HUD assertion below.
HUD_MERGED="${MERGED:-$RUN_OLD/merged-base/patched-mc-merged-26.2.jar}"
if ! javap -cp "$HUD_MERGED" net.minecraft.client.gui.Hud >/dev/null 2>&1; then
  echo "[kernel] FAIL cannot read Hud from the merged base ($HUD_MERGED)"; FAIL=1; HUD_TREE=unread
elif javap -c -p -cp "$HUD_MERGED" net.minecraft.client.gui.Hud 2>/dev/null | grep -aq 'Method net/minecraftforge/client/gui/overlay/ForgeLayeredDraw\.'; then
  HUD_TREE=kept
else
  HUD_TREE=absent
fi

# M26_NEO_ASSERTIONS_BEGIN — the NeoForge canary's registrations and consumer read-backs (the MinecraftForge checks they twinned went in Stage 3).
step "the NeoForge canary's client registrations arrived and reached the live consumer tables"
check "NeoForge: client canary subscribed" 'ForbricNeoLive/CLIENT\] subscribed to twelve NeoForge client registration events' "$LOG"
check_absent "NeoForge: client canary initialized successfully" 'ForbricNeoLive/CLIENT\] FAILED initialization' "$LOG"
for event in 'RegisterKeyMappingsEvent' 'EntityRenderersEvent.RegisterRenderers' 'BuildCreativeModeTabContentsEvent' \
  'EntityRenderersEvent.RegisterLayerDefinitions' 'RegisterParticleProvidersEvent' 'RegisterColorHandlersEvent.BlockTintSources' \
  'AddClientReloadListenersEvent' 'RegisterClientTooltipComponentFactoriesEvent' 'ModelEvent.RegisterLoaders' \
  'RegisterPresetEditorsEvent' 'RegisterPictureInPictureRenderersEvent' 'RegisterGuiLayersEvent'; do
  check "NeoForge: $event received" "ForbricNeoLive/CLIENT\] ${event//./\\.} RECEIVED" "$LOG"
done
check "NeoForge: observations reached world tick 100" 'ForbricNeoLive/CLIENT\] registration observations completed at world tick 100' "$LOG"
check "NeoForge: key is in Options" 'ForbricNeoLive/CLIENT\] key in Options\.keyMappings: true' "$LOG"
check "NeoForge: persisted F9 binding was reloaded" 'ForbricNeoLive/CLIENT\] key saved binding: key\.keyboard\.f9([[:space:]]|$)' "$LOG"
check "NeoForge: registered layer was baked" 'ForbricNeoLive/CLIENT\] layer forbricneolive:probe baked: true' "$LOG"
check "NeoForge: granite has the registered canary tint" 'ForbricNeoLive/CLIENT\] granite probe tint present: true' "$LOG"
check "NeoForge: reload registration happened exactly once" 'ForbricNeoLive/CLIENT\] reload posts=1([[:space:]]|$)' "$LOG"
check "NeoForge: registered reload listener really applied" 'ForbricNeoLive/CLIENT\] reload listener applies=[1-9][0-9]*' "$LOG"
check "NeoForge: tooltip factory is consumed" 'ForbricNeoLive/CLIENT\] tooltip factory consumed: true' "$LOG"
# DRAWS, not the registration: the event proves only that a layer stack exists to register into.
check "NeoForge: its gui layer drew real frames" 'ForbricNeoLive/CLIENT\] gui layer DREW 20 frames' "$LOG"
check "NeoForge: the canary's own block has a real id" \
  'ForbricNeoLive/BLOCKID\] forbricneolive:probe_block id=[1-9][0-9]* roundTrip=Block\{forbricneolive:probe_block\}' "$LOG"
check "NeoForge: its mixin config plugin saw the client" 'ForbricNeoLive/PLUGIN\] prepareConfigs saw dist=CLIENT' "$LOG"
# M26_NEO_ASSERTIONS_END

# M26_FABRIC_ASSERTIONS_BEGIN — the blockstate→id refill's proof on the family that registers late after MinecraftForge.
step "a block a Fabric client entrypoint registers gets a real blockstate id (must PASS)"
# The kernel fills the blockstate→id map when its registration window closes, before Minecraft.<init>; Fabric's client
# entrypoints run inside Minecraft.<init> with the registries reopened, so their blocks are a second wave the map has
# to be rebuilt for. A map that stops at "already non-empty" answers 0 -- AIR -- for every one of them.
check "Fabric: the canary registered its block from onInitializeClient" \
  'ForbricFabricLive/LATEBLOCK\] registered forbricfabriclive:late_block from onInitializeClient at raw id [1-9][0-9]*' "$LOG"
check "Fabric: the blockstate→id map is rebuilt after the client entrypoints' wave" \
  '\[Forbric/Lifecycle\] rebuilt NeoForge blockstate→id map \([1-9][0-9]* states, was [1-9][0-9]*\)' "$LOG"
# The id itself, read in the world on the render thread: 0 is AIR and is what a missing entry answers.
check "Fabric: the late block has a real id, read back in the world" \
  'ForbricFabricLive/LATEBLOCK\] forbricfabriclive:late_block id=[1-9][0-9]* roundTrip=Block\{forbricfabriclive:late_block\}' "$LOG"
check_absent "Fabric: the late-block probe never failed" 'ForbricFabricLive/LATEBLOCK\] (registration|read-back) FAILED' "$LOG"
# M26_FABRIC_ASSERTIONS_END

# M26_SESSION_ASSERTIONS_BEGIN — the session itself, and the plumbing that still prints with no MinecraftForge mod.
step "1. the client entered its save, played and left, with nothing missing"
check "joined world" 'ClientSmoke\] joined world via quick-play' "$LOG"
check "simulation survived" 'ClientSmoke\] client-ready after' "$LOG"
check "clean disconnect" 'ClientSmoke\] clean disconnect observed' "$LOG"
check_absent "no missing client classes or crashes" 'NoClassDefFoundError|Preparing crash report|Mod Loading has failed' "$LOG"
check_absent "nothing was refused" '\[Forbric/Unsupported\] refusing' "$LOG"
check "the kernel says how many packs it preloaded" \
  'Forbric/ClientResources\] client resource manager preloaded with [1-9][0-9]* selected pack' "$LOG"
check "client initialization bridges report installed" 'all 3 CLIENT_INIT bridge\(s\) installed' "$LOG"
check "registration bridges report installed" 'all 2 REGISTRATION bridge\(s\) installed' "$LOG"

step "1b. the HUD, picture-in-picture and fluid rewrites that run with no MinecraftForge mod (must PASS)"
# A merged Hud that kept both families' edits builds MinecraftForge's overlay tree in Hud.<init> and draws it in place of
# NeoForge's layer manager, returning before the manager draws -- so no NeoForge or Fabric layer drew, and once the
# kernel silenced the tree's vanilla leaves, no vanilla HUD either. The kernel drops those two calls
# (ForgeHudPipelineInjector) and puts the tree on NeoForge's manager. Exact both ways against the base this run booted
# (HUD_TREE, read before these assertions so they stay a function of the log). RED with
# M26_EXTRA_JVM='-Dforbric.forgeOverlayLayers=off' (the stack is not built).
check "the stack is on NeoForge's layer manager" \
  "Forbric/HudBridge\] MinecraftForge's overlay stack is on NeoForge's layer manager" "$LOG"
if [ "${HUD_TREE:-absent}" = kept ]; then
  check "the merged Hud's own MinecraftForge build and draw were dropped" \
    "Forbric/HudBridge\] net.minecraft.client.gui.Hud kept MinecraftForge's own overlay tree \(built in 1 place\(s\), drawn instead of NeoForge's layer manager in 1\)" "$LOG"
else
  check_absent "a merged Hud without MinecraftForge's tree had nothing dropped" \
    "Forbric/HudBridge\] net.minecraft.client.gui.Hud kept MinecraftForge's own overlay tree" "$LOG"
fi
# GuiRenderer declares vanilla's Class -> renderer map, reads it in ONE place and writes it NOWHERE: NeoForge's
# constructor won the byte merge and fills its pooled map instead, and the kernel's pooled-miss fallback would read that
# map with no null check. A transform-time repair with no switch.
check "the map now has a writer at all" \
  "MergedBaseCompat\] gave GuiRenderer's pooled picture-in-picture lookup a fallback.*only writer" "$LOG"
# FluidRenderer.tesselate asks IClientFluidTypeExtensions.of(state) for its model and tint (vanilla water answers
# DEFAULT and is counted). Not asserted in gate-m9: its pack carries sodium, which replaces vanilla's fluid meshing.
# RED with M26_EXTRA_JVM='-Dforbric.forgeFluidModels=off' (water still placed, no Forbric/Fluids line).
check "the NeoForge canary placed its water source" \
  'ForbricNeoLive/FLUID\] water at -?[0-9]+ -?[0-9]+ -?[0-9]+: Block\{minecraft:water\}' "$LOG"
check "MinecraftForge fluid extensions were consulted while rendering" \
  'Forbric/Fluids\] MinecraftForge client extensions consulted for [1-9][0-9]* fluid' "$LOG"
# M26_SESSION_ASSERTIONS_END

# M26_REFUSAL_BEGIN — phase 2 (header): a MinecraftForge-only mod on a client is put to the player, not swallowed.
step "2. a MinecraftForge-only mod on a client: the refusal is asked about in the game (must PASS)"
PROMPT_DIR="$KERNEL/run/client-forge-refusal"
PROMPT_LOG="$BUILD/gate-m26-refusal.log"
reap_stale_server "$PROMPT_DIR"
rm -rf "$PROMPT_DIR"; mkdir -p "$PROMPT_DIR/mods"
cp "$RUN_OLD/forge-runtime/forbriclive.jar" "$PROMPT_DIR/mods/" || exit 3
cp "$RUNDIR/options.txt" "$PROMPT_DIR/options.txt" || exit 3
: > "$PROMPT_LOG"
# No quick-play: a launch told to open a world at once is refused instead of asked (gate-m20 says why).
FORBRIC_COMPAT_POLICY=ask FORBRIC_DEP_DIALOG=dryRun FORBRIC_JVM="-Duser.language=en -Duser.country=US ${M26_EXTRA_JVM:-}" \
  RUNDIR="$PROMPT_DIR" "$KERNEL/run/launch-kernel-client.sh" > "$PROMPT_LOG" 2>&1 &
PROMPT_PID=$!
record_server_pid "$PROMPT_DIR" "$PROMPT_PID"
for i in $(seq 1 240); do
  kill -0 "$PROMPT_PID" 2>/dev/null || { echo "[kernel] the refusal client left on its own after ~${i}s"; break; }
  grep -qaE 'asking in the game|launch stopped|Game crashed|Mod Loading has failed' "$PROMPT_DIR/logs/latest.log" 2>/dev/null \
    && { echo "[kernel] the refusal client reached its decision after ~${i}s"; sleep 3; break; }
  sleep 1
done
if kill -0 "$PROMPT_PID" 2>/dev/null; then
  PROMPT_EXIT=waiting; stop_tree "$PROMPT_PID"
else
  wait "$PROMPT_PID"; PROMPT_EXIT=$?
fi
wait "$PROMPT_PID" 2>/dev/null
rm -f "$PROMPT_DIR/.forbric-gate.pid"
cat "$PROMPT_DIR/logs/latest.log" >> "$PROMPT_LOG" 2>/dev/null || true
cp "$PROMPT_DIR/.forbric-kernel/compatibility-report.json" "$PROMPT_LOG.json" 2>/dev/null || : > "$PROMPT_LOG.json"
check "the client refuses the canary by name" \
  '\[Forbric/Unsupported\] refusing forbriclive\.jar \(forbriclive\): it is a MinecraftForge mod with no usable Fabric or NeoForge half' "$PROMPT_LOG"
if python3 - "$PROMPT_LOG.json" <<'PY_REFUSAL'
import json, sys
report = json.load(open(sys.argv[1], encoding='utf-8'))
assert report['policy'] == 'ASK', report['policy']
found = [f for f in report['findings'] if f['id'] == 'unsupported:minecraftforge' and f['modId'] == 'forbriclive']
assert len(found) == 1 and found[0]['confidence'] == 'CONFIRMED' and found[0]['required'] is True, found
assert 'Forbric Live Canary (forbriclive.jar) is a MinecraftForge mod. Forbric no longer supports MinecraftForge' in found[0]['detail'], found
# No mods inventory yet: this report is the one the launch-time decision writes, before the catalogue exists (measured:
# mods=[] here). The FAILED MinecraftForge row of a refused jar is gate-m29's, read from a server's report at its stop.
print('[kernel]   the client report holds the refusal as a CONFIRMED required finding, in the player-facing wording')
PY_REFUSAL
then echo "[kernel] PASS the client report names the refused mod as a required loss"
else echo "[kernel] FAIL the client report (see $PROMPT_LOG.json)"; FAIL=1; fi
check "the confirmation window could not draw, so the question moved into the game" \
  "Forbric/Compatibility\] the confirmation window could not be shown; [1-9][0-9]* required feature loss\(es\) will be asked about in the game's own window" "$PROMPT_LOG"
check "and the game asked" \
  "Forbric/Compatibility\] asking in the game about [1-9][0-9]* required feature loss\(es\) the launch could not ask about in a window" "$PROMPT_LOG"
check_absent "nothing stopped the launch before the player was asked" "Forbric/Compatibility\] launch stopped" "$PROMPT_LOG"
assert_eq "and it was still waiting for the answer when the gate stopped it" "waiting" "$PROMPT_EXIT"
check_absent "nothing of the refused canary ran" 'constructed @Mod forbriclive|\[ForbricLive' "$PROMPT_LOG"
check_absent "no crash" "Preparing crash report|Game crashed" "$PROMPT_LOG"
# M26_REFUSAL_END

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M26 CLIENT GATE GREEN — real registration events and consumer results agree, and a refused MinecraftForge mod is put to the player"
else
  echo "[kernel] M26 CLIENT GATE RED — see $LOG and $PROMPT_LOG"
fi
exit "$FAIL"
