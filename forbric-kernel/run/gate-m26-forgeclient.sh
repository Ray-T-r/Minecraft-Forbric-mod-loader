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
#   turns them red: a NeoForge block is registered in the first wave, and NeoForge's own block bake maps it.
# Fabric half (forbricfabriclive rides along, with -Dforbricfabriclive.lateBlock=true): a Fabric client entrypoint
#   registers in a late wave, after the blockstate→id map's first fill, so the Fabric canary registers a block from
#   onInitializeClient and reads its id back in the world. POSITIVE-ONLY on the genuine base: NeoForge's own block bake
#   (BlockCallbacks.onBake, run by every registry freeze) maps each state of the whole block registry, the late wave
#   included. The kernel's refill of that map (KernelRegistryContent.rebuildBlockStateIds) was there for the merged base;
#   on this base no boot printed its "rebuilt NeoForge blockstate→id map" line, and with its switch off the late block
#   read the same id as by default (measured 2026-11-09, stage4-c5-g-a.md). Lane S deleted the refill on that evidence
#   (35985d32, Stage 4 C5) and -Dforbric.blockStateIdRefill is a retired switch, so nothing in the kernel stands between
#   NeoForge's bake and this check. The check on the refill's line held on the merged base only and went (D34).
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
# Phase 1c (C5 wave 2b, lane K1's open item) boots a DEDICATED Forbric server with the same two canaries and asks it for
#   the NeoForge canary's block-state id: the client must number the block as the server does. The id is NeoForge's own
#   numbering on each side (its block bake at every freeze); a socket join sends block states by these ids, and nothing
#   syncs them. The client's numbering runs through one more freeze than the server's (the client entrypoint window,
#   where the Fabric canary's late block registers), so it is the client that can drift. The probe block is registered in
#   the first wave on both ends, so its id is the same on both: 32366 (K1 by dump; this gate 49 PASS / 0 FAIL, wave 2b review fixes).
#   RED, measured in wave 2b on a clone without lane S's clear before the client window's freeze (835ec80c;
#   c5/g-c/w2b/gates/m26-noclientclear): 46 PASS / 1 FAIL, exactly this check (client 64733, server 32366), with the
#   kernel's own CAUGHT "not one id per block state after the client entrypoint window freeze" in the client log. The
#   id >= 1 and round-trip checks above stay green on that clone: a state numbered twice still round-trips. Since the
#   review fixes the phase also asserts that CAUGHT line absent on both ends; the same clone, re-run
#   (c5/g-c/w2b/rf/gates/m26-noclientclear): 47 PASS / 2 FAIL, the comparison and the client's line (present x6).
# GATE-PARALLEL: rundirs=client-forge-registration,client-forge-refusal,server-forge-registration mem=3000
# GATE-TAGS: localized   (reads the load report, a crash report or a dialog: output that follows the language; gates-all --only-tag localized is the zh sweep)
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m26-forgeclient.log"
RUNDIR="${M26_RUNDIR:-$KERNEL/run/client-forge-registration}"
PROMPT_DIR="${M26_PROMPT_DIR:-$KERNEL/run/client-forge-refusal}"
SERVER_DIR="${M26_SERVER_DIR:-$KERNEL/run/server-forge-registration}"
refuse_shared_rundir "$RUNDIR"
refuse_shared_rundir "$PROMPT_DIR"
refuse_shared_rundir "$SERVER_DIR"
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
FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=160 -Dforbricfabriclive.lateBlock=true -Dforbricneolive.placeWater=true ${M26_EXTRA_JVM:-}" \
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
# wave after the blockstate→id map's first fill. On the genuine base NeoForge's own block bake maps them (the kernel's
# refill is gone, header), so what is asserted is the id itself.
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

step "1c. a dedicated server with the same canaries numbers the NeoForge canary's block as the client does (must PASS)"
SERVER_LOG="$BUILD/gate-m26-server.log"
reap_stale_server "$SERVER_DIR"
rm -rf "$SERVER_DIR"; mkdir -p "$SERVER_DIR/mods"
# The jars the client ran, as staged above. Nothing connects to this server, but it still binds GATE_PORT like every
# other gate's server (compat/test_gate_ports.py holds every gate to the knob, no literal port, and await_server says
# whether the port was lost); the client above is singleplayer, so the port is free for it.
cp "$RUNDIR/mods/forbricneolive.jar" "$RUNDIR/mods/forbricfabriclive.jar" "$SERVER_DIR/mods/" || exit 3
printf 'eula=true\n' > "$SERVER_DIR/eula.txt"
printf 'server-ip=127.0.0.1\nserver-port=%s\nonline-mode=false\nlevel-name=M26Server\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nmax-tick-time=-1\nsync-chunk-writes=false\n' \
  "$GATE_PORT" > "$SERVER_DIR/server.properties"
SERVER_FIFO="$SERVER_DIR/.stdin"; rm -f "$SERVER_FIFO"; mkfifo "$SERVER_FIFO"
: > "$SERVER_LOG"
FORBRIC_JVM="${FORBRIC_JVM:-} ${M26_EXTRA_JVM:-}" RUNDIR="$SERVER_DIR" "$KERNEL/run/launch-kernel-server.sh" < "$SERVER_FIFO" > "$SERVER_LOG" 2>&1 &
SERVER_PID=$!
record_server_pid "$SERVER_DIR" "$SERVER_PID"
exec 8>"$SERVER_FIFO"
for i in $(seq 1 240); do
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "[kernel] the dedicated server left on its own after ~${i}s"; break; }
  grep -aqE 'Done \(' "$SERVER_LOG" && grep -aq 'ForbricNeoLive/BLOCKID\]' "$SERVER_LOG" && break
  sleep 1
done
echo "stop" >&8
exec 8>&-
await_server "$SERVER_PID" "$SERVER_LOG" 90
rm -f "$SERVER_FIFO" "$(_pidfile "$SERVER_DIR")"
check "the dedicated server with the same canaries reached Done" 'Done \(' "$SERVER_LOG"
# M26_SERVER_IDS_BEGIN — the client's id for the NeoForge canary's block equals a dedicated server's.
# Each side prints the id once, at its ServerStartedEvent (the client's integrated server shares the client's map).
probe_ids() { grep -aoE 'ForbricNeoLive/BLOCKID\] forbricneolive:probe_block id=[0-9]+' "$1" 2>/dev/null | grep -oE '[0-9]+$' | sort -u | paste -sd' ' -; }
CLIENT_PROBE_ID="$(probe_ids "$LOG")"; SERVER_PROBE_ID="$(probe_ids "$SERVER_LOG")"
if [[ "$CLIENT_PROBE_ID" =~ ^[1-9][0-9]*$ ]] && [ "$CLIENT_PROBE_ID" = "$SERVER_PROBE_ID" ]; then
  echo "[kernel] PASS the client numbers forbricneolive:probe_block as the dedicated server does (id $CLIENT_PROBE_ID on both)"
else
  echo "[kernel] FAIL the client numbers forbricneolive:probe_block as the dedicated server does (client: ${CLIENT_PROBE_ID:-none}, server: ${SERVER_PROBE_ID:-none})"
  FAIL=1
fi
# The comparison above cannot see a numbering defect both ends share (the same wrong id on each); the kernel's own check
# after every freeze it drives can (KernelLifecycle.verifyBlockStateIds, lane K1): its CAUGHT line is absent on both ends
# (review A-3 of C5 wave 2b; the clone without lane S's clear printed it 6 times in the client log, c5/g-c/w2b/gates/
# m26-noclientclear, and its re-run in c5/g-c/w2b/rf/gates is red on the client's line).
check_absent "the client's blockstate->id map is one id per block state" "blockstate->id map is not one id per block state" "$LOG"
check_absent "the dedicated server's blockstate->id map is one id per block state" "blockstate->id map is not one id per block state" "$SERVER_LOG"
# M26_SERVER_IDS_END

# M26_REFUSAL_BEGIN — phase 2 (header): a MinecraftForge-only mod on a client is put to the player, not swallowed.
step "2. a MinecraftForge-only mod on a client: the refusal is asked about in the game (must PASS)"
PROMPT_LOG="$BUILD/gate-m26-refusal.log"
reap_stale_server "$PROMPT_DIR"
rm -rf "$PROMPT_DIR"; mkdir -p "$PROMPT_DIR/mods"
cp "$KERNEL/run/canary/forge-refused/forbriclive.jar" "$PROMPT_DIR/mods/" || exit 3
cp "$RUNDIR/options.txt" "$PROMPT_DIR/options.txt" || exit 3
: > "$PROMPT_LOG"
# No quick-play: a launch told to open a world at once is refused instead of asked (gate-m20 says why).
FORBRIC_COMPAT_POLICY=ask FORBRIC_DEP_DIALOG=dryRun FORBRIC_JVM="${FORBRIC_JVM:-} -Duser.language=en -Duser.country=US -Dforbric.dialogLanguage=en ${M26_EXTRA_JVM:-}" \
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
