#!/usr/bin/env bash
# M26 — the client event buses must receive the game's real registration events, and a refused MinecraftForge mod is
# put to the player.
# NeoForge half (forbricneolive rides along): the client registration events in NeoForge's shape, read back from the
#   consumer tables, plus its block's id. NeoForge posts all twelve itself, through ModLoader.postEvent, whose only
#   fan-out is ModList.forEachModInOrder -- so the one kernel behaviour under them is publishing the canary's container
#   into NeoForge's ModList, and that has a switch.
#   RED with M26_EXTRA_JVM='-Dforbric.publishModList=off' (measured on the genuine base 2026-11-09, stage4-c5-g-a.md):
#   exactly the twenty NeoForge checks that read what the twelve events registered (the twelve "received" lines, the
#   key in Options and its reloaded F9, the baked layer, the granite tint, the reload listener's two, the tooltip
#   factory, the drawn gui layer).
#   The client still reaches its world: the canary subscribes (on the bus its constructor is handed), reaches world tick
#   100, its block has an id, its plugin sees the client. Those NeoForge checks stay POSITIVE-ONLY, with no switch that
#   turns them red: '-Dforbric.blockStateIdRefill=off' leaves the NeoForge block's id alone, because a NeoForge block is
#   registered in the first wave and the map's first fill covers it.
# Fabric half (forbricfabriclive rides along, with -Dforbricfabriclive.lateBlock=true): a Fabric client entrypoint
#   registers in a late wave, after the blockstate→id map's first fill, so the Fabric canary registers a block from
#   onInitializeClient and reads its id back in the world. POSITIVE-ONLY on the genuine base: NeoForge's own block
#   callback maps each state as its block is registered, the late wave included, so the kernel's refill
#   (KernelRegistryContent.rebuildBlockStateIds) finds the map agreeing with the registry and does nothing. Measured
#   2026-11-09: no genuine boot of G-A's C5 gate runs printed its "rebuilt NeoForge blockstate→id map" line, and with
#   M26_EXTRA_JVM='-Dforbric.blockStateIdRefill=off' the late block reads the same id as by default. The check on that
#   line held on the merged base only and went (D34); the refill's own fate is the kernel's (stage4-c5-g-a.md).
# Session: the checks every client gate makes, plus the kernel rewrite that runs on every boot with no mod asking for it:
#   GuiRenderer's orphaned picture-in-picture map given its only writer (Xaero's minimap and malilib read it; the
#   bridge's writer line is asserted explicitly, D65), and vanilla water placed beside spawn by the NeoForge canary.
# Phase 2 puts a MinecraftForge-only jar in front of the user-visible switch on the CLIENT side, where the answer is a
#   question rather than a stop: launched without quick-play under the ask policy, with the confirmation window unable to
#   draw (FORBRIC_DEP_DIALOG=dryRun, as gate-m20 does), the refusal is a confirmed required finding naming the mod, the
#   question moves into the game's own window, and nothing stops the launch before the player has answered. The server
#   side of the switch (a stop) is gate-m29's; together they are the refusal's coverage (D32).
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). Until Stage 3 the MinecraftForge canary (forbriclive) joined
#   this world too and most of this gate was its: traditional-Forge client registration events, its HUD layer and
#   picture-in-picture renderer, a MinecraftForge fluid's client extensions, its creative injection. Forbric refuses a
#   MinecraftForge-only jar, so every check that read it went then, with the controls only those checks could show.
# STAGE 4 OF DROPPING MinecraftForge (what changed and why). The game base is NeoForge's own patched game and the
#   MinecraftForge carrier is off the stage. The session lines Stage 3 kept because the merged base still printed them
#   have no producer any more and went (D34): the CLIENT_INIT and REGISTRATION bridge inventories (the event multiplexer
#   went in C2), the HudBridge lines (the merged Hud's MinecraftForge overlay tree does not exist on NeoForge's Hud), and
#   MinecraftForge's client fluid extensions. With the HUD line went the javap of the merged Hud (HUD_TREE) that chose
#   between its two forms; the options seed reads the world version from the game base (BASE) instead of MERGED. The
#   -Dforbric.forgeClientInit / forgeOverlayLayers / forgeFluidModels / forgePipRenderers / forgeCtorGameInstance /
#   forgeCreativeTabs recipes are retired switches (D29). The fixed rundirs take M26_RUNDIR / M26_PROMPT_DIR and refuse
#   a shared fixture (D46). The file keeps its name until Stage 6 (D4).
# GATE-PARALLEL: rundirs=client-forge-registration,client-forge-refusal mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m26-forgeclient.log"
RUNDIR="${M26_RUNDIR:-$KERNEL/run/client-forge-registration}"
PROMPT_DIR="${M26_PROMPT_DIR:-$KERNEL/run/client-forge-refusal}"
refuse_shared_rundir "$RUNDIR"
refuse_shared_rundir "$PROMPT_DIR"
WORLD="ForgeClientProbe"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
mkdir -p "$BUILD" "$RUNDIR/quickPlay" "$RUNDIR/empty-mods"
[ -f "$NEOLIVE" ] || { echo "[kernel] FATAL: missing $NEOLIVE; run build-testmods.sh or run/build-neolive-canary.sh" >&2; exit 3; }
# The Fabric canary is built from this tree every run, as gate-m4/m18/m30 build it.
"$KERNEL/run/build-fabric-canary.sh" > "$BUILD/gate-m26-fabric-canary.log" 2>&1
[ -f "$FABRIC" ] || { echo "[kernel] FATAL: the Fabric canary did not build (see $BUILD/gate-m26-fabric-canary.log)" >&2; exit 3; }
# Phase 2's refused canary, built now so a broken build stops the gate before the session phase rather than after it.
# Its exit code, not only the jar: a failed build leaves the previous jar in place.
"$KERNEL/run/build-forge-refusal-fixtures.sh" > "$BUILD/gate-m26-refusal-fixtures.log" 2>&1 \
  || { echo "[kernel] FATAL: the refusal fixtures did not build (see $BUILD/gate-m26-refusal-fixtures.log)" >&2; exit 3; }

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
python3 - "${BASE:-$RUN_OLD/neoforge-patched/patched-mc-neoforge-26.2.jar}" "$RUNDIR/options.txt" <<'PY_OPTIONS' || exit 3
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

# M26_FABRIC_ASSERTIONS_BEGIN — a block a Fabric client entrypoint registers late gets a real blockstate id.
step "a block a Fabric client entrypoint registers gets a real blockstate id (must PASS)"
# Fabric's client entrypoints run inside Minecraft.<init> with the registries reopened, so their blocks are a second
# wave after the blockstate→id map's first fill. On the genuine base NeoForge's own block callback maps them as they
# register (the kernel's refill stays silent, header), so what is asserted is the id itself.
check "Fabric: the canary registered its block from onInitializeClient" \
  'ForbricFabricLive/LATEBLOCK\] registered forbricfabriclive:late_block from onInitializeClient at raw id [1-9][0-9]*' "$LOG"
# The id, read in the world on the render thread: 0 is AIR and is what a missing entry answers.
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

step "1b. the picture-in-picture bridge and vanilla water (must PASS)"
# GuiRenderer declares vanilla's Class -> renderer map, reads it in ONE place and writes it NOWHERE: NeoForge's
# constructor fills its pooled map instead. The kernel's bridge (ForbricMergedBaseCompatTransformer
# .bridgeOrphanedPipRenderers, REQUIRED) gives the pooled miss a fallback to that map AND gives the map its only writer;
# the writer half is what guest renderers (Xaero's minimap, malilib) depend on, so its clause is asserted on its own
# (D65). A transform-time repair with no switch.
check "the picture-in-picture bridge gave the orphaned map its only writer" \
  "MergedBaseCompat\] gave GuiRenderer's pooled picture-in-picture lookup a fallback .*, and gave that map its only writer" "$LOG"
check "the NeoForge canary placed its water source" \
  'ForbricNeoLive/FLUID\] water at -?[0-9]+ -?[0-9]+ -?[0-9]+: Block\{minecraft:water\}' "$LOG"
# M26_SESSION_ASSERTIONS_END

# M26_REFUSAL_BEGIN — phase 2 (header): a MinecraftForge-only mod on a client is put to the player, not swallowed.
step "2. a MinecraftForge-only mod on a client: the refusal is asked about in the game (must PASS)"
PROMPT_LOG="$BUILD/gate-m26-refusal.log"
reap_stale_server "$PROMPT_DIR"
rm -rf "$PROMPT_DIR"; mkdir -p "$PROMPT_DIR/mods"
cp "$KERNEL/run/canary/forge-refused/forbriclive.jar" "$PROMPT_DIR/mods/" || exit 3
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
# The fixture keeps the live canary's mixin config and its plugin, whose static initialiser prints [ForbricLive/PLUGIN]
# the moment Mixin is handed the config, so "nothing ran" covers the earliest thing a refused jar could do. The kernel's
# own count of the Forge-family configs it registered says the same from the other side: present, and none of them
# MinecraftForge's or the refused canary's.
check_absent "nothing of the refused canary ran" 'constructed @Mod forbriclive|\[ForbricLive' "$PROMPT_LOG"
check_forge_family_configs_are_neoforge "the Forge-family mixin configs registered are NeoForge's alone" "$PROMPT_LOG"
check_absent "and not the refused canary's under another name" \
  '\[Forbric/Mixin\] mixin configs: .*forbriclive\.mixins\.json' "$PROMPT_LOG"
check_absent "no crash" "Preparing crash report|Game crashed" "$PROMPT_LOG"
# M26_REFUSAL_END

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M26 CLIENT GATE GREEN — real registration events and consumer results agree, and a refused MinecraftForge mod is put to the player"
else
  echo "[kernel] M26 CLIENT GATE RED — see $LOG and $PROMPT_LOG"
fi
exit "$FAIL"
