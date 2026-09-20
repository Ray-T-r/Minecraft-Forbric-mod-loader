#!/usr/bin/env bash
# M26 — traditional Forge's client event buses must receive the game's real registration events.
# RED controls after A3/A6/A7: M26_EXTRA_JVM='-Dforbric.forgeClientInit=off' loses client registrations;
#   M26_EXTRA_JVM='-Dforbric.clientResourcePreload=off' — the client resource manager is empty at mod setup again;
# M26_EXTRA_JVM='-Dforbric.forgeCreativeTabs=off' loses command_block in parent/search collections;
# M21_EXTRA_JVM='-Dforbric.forgeSpawnPlacements=off' loses the zombie WORLD_SURFACE result in M21.
# Green since Phase 1 A landed (A3–A9 hooks, A8 bridge inventory); any red is a regression, exit 1.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m26-forgeclient.log"
RUNDIR="$KERNEL/run/client-forge-registration"
WORLD="ForgeClientProbe"
FORGE="$RUN_OLD/forge-runtime/forbriclive.jar"
mkdir -p "$BUILD" "$RUNDIR/quickPlay" "$RUNDIR/empty-mods"
[ -f "$FORGE" ] || { echo "[kernel] FATAL: missing $FORGE; run build-testmods.sh" >&2; exit 3; }

step "generate a zero-mod save once, then join it with the Forge client canary"
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
python3 - "$FORGE" "$RUNDIR/mods/forbriclive.jar" <<'PY_STAGE' || exit 3
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
# Skip the first-run accessibility screen so quick-play can reach the saved world.
# M26_OPTIONS_BEGIN — the default canary key is F7; this persisted F6 must survive late registration.
python3 - "${MERGED:-$RUN_OLD/merged-base/patched-mc-merged-26.2.jar}" "$RUNDIR/options.txt" <<'PY_OPTIONS' || exit 3
import json, sys, zipfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[1]) as game:
    version = json.loads(game.read('version.json'))['world_version']
if not isinstance(version, int) or version <= 0:
    raise SystemExit('game metadata has no valid options data version')
# Without version, OptionsKeyLwjgl3Fix treats the modern key name as a legacy integer key code.
Path(sys.argv[2]).write_text(f'version:{version}\nonboardAccessibility:false\nkey_key.forbriclive.probe:key.keyboard.f6\n')
PY_OPTIONS
# M26_OPTIONS_END
: > "$LOG"
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=160 ${M26_EXTRA_JVM:-}" \
RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$LOG" 2>&1 &
CLIENT_PID=$!
record_server_pid "$RUNDIR" "$CLIENT_PID"
await_server "$CLIENT_PID" "$LOG" 360 30
rm -f "$RUNDIR/.forbric-gate.pid"
cat "$RUNDIR/logs/latest.log" >> "$LOG" 2>/dev/null || true

step "a MinecraftForge fluid's client extensions are asked on the render path (H5)"
# The Forge canary places one water source beside the respawn point from ServerStartedEvent; the client's
# FluidRenderer.tesselate then asks IClientFluidTypeExtensions.of(state) for its model and tint (vanilla water
# answers DEFAULT and is counted as consulted). Not asserted in gate-m9: its pack carries sodium, which replaces
# vanilla's fluid meshing entirely. RED control: M26_EXTRA_JVM='-Dforbric.forgeFluidModels=off' (water still
# placed, no Forbric/Fluids line).
check "the canary placed its water source" 'ForbricLive/FLUID\] water at -?[0-9]+ -?[0-9]+ -?[0-9]+: Block\{minecraft:water\}' "$LOG"
check "MinecraftForge fluid extensions were consulted while rendering" 'Forbric/Fluids\] MinecraftForge client extensions consulted for [1-9][0-9]* fluid' "$LOG"

# M26_ASSERTIONS_BEGIN — exercised against synthetic logs without launching the game.
step "the canary subscribed, entered its save and completed real consumer observations"
check "client canary subscribed" 'ForbricLive/CLIENT\] subscribed to ten Forge registration events' "$LOG"
check "joined world" 'ClientSmoke\] joined world via quick-play' "$LOG"
check "simulation survived" 'ClientSmoke\] client-ready after' "$LOG"
check "creative contents were requested" 'ForbricLive/CLIENT\] creative contents builder exercised in a live world' "$LOG"
check "observations reached world tick 100" 'ForbricLive/CLIENT\] registration observations completed at world tick 100' "$LOG"
check "clean disconnect" 'ClientSmoke\] clean disconnect observed' "$LOG"
check_absent "client canary initialized successfully" 'ForbricLive/CLIENT\] FAILED initialization' "$LOG"
check_absent "no missing client classes or crashes" 'NoClassDefFoundError|Preparing crash report|Mod Loading has failed' "$LOG"
check_absent "reload registration was not duplicated" 'ForbricLive/CLIENT\] reload posts=([2-9]|[1-9][0-9]+)([[:space:]]|$)' "$LOG"

step "all ten traditional-Forge registration events arrived"
for event in 'RegisterKeyMappingsEvent' 'EntityRenderersEvent.RegisterRenderers' 'BuildCreativeModeTabContentsEvent' \
  'EntityRenderersEvent.RegisterLayerDefinitions' 'RegisterParticleProvidersEvent' 'RegisterColorHandlersEvent.Block' \
  'RegisterClientReloadListenersEvent' 'RegisterClientTooltipComponentFactoriesEvent' 'ModelEvent.RegisterGeometryLoaders' \
  'RegisterPresetEditorsEvent'; do
  check "$event received" "ForbricLive/CLIENT\] ${event//./\\.} RECEIVED" "$LOG"
done
step "a MinecraftForge mod can read its OWN asset from client setup"
# MinecraftForge runs mod loading INSIDE the first resource reload, so by the time a mod's client setup runs the
# resource manager holds its packs. The kernel's window is genuine NeoForge's, which is BEFORE that reload, so
# this read came back empty -- and Xaero's World Map calls get() on it with no isPresent check, which is
# "Xaero's World Map has crashed!" out of a deferred setup task, naming the mod and not the moment.
check "the setup-time resource read finds the mod's own file" \
  'ForbricLive/CLIENT\] setup resource PRESENT: forbriclive:setup_probe\.txt = forbric-live-setup-probe' "$LOG"
check_absent "and never reports it missing" \
  'ForbricLive/CLIENT\] setup resource ABSENT' "$LOG"
check "the kernel says how many packs it preloaded" \
  'Forbric/ClientResources\] client resource manager preloaded with [1-9][0-9]* selected pack' "$LOG"

step "registered content reached the live consumer tables"
check "key is in Options" 'ForbricLive/CLIENT\] key in Options\.keyMappings: true' "$LOG"
check "persisted F6 binding was reloaded" 'ForbricLive/CLIENT\] key saved binding: key\.keyboard\.f6([[:space:]]|$)' "$LOG"
check "registered layer was baked" 'ForbricLive/CLIENT\] layer forbriclive:probe baked: true' "$LOG"
check "stone has tint sources" 'ForbricLive/CLIENT\] stone tint sources: [1-9][0-9]*' "$LOG"
check "stone has the registered canary tint" 'ForbricLive/CLIENT\] stone probe tint present: true' "$LOG"
check "reload registration happened exactly once" 'ForbricLive/CLIENT\] reload posts=1([[:space:]]|$)' "$LOG"
check "registered listener really applied" 'ForbricLive/CLIENT\] reload listener applies=[1-9][0-9]*' "$LOG"
check "tooltip factory is consumed" 'ForbricLive/CLIENT\] tooltip factory consumed: true' "$LOG"
check "canary geometry loader is published" 'ForbricLive/CLIENT\] forbriclive:probe geometry loader present: true' "$LOG"
check "Forge internal geometry loader is published" 'ForbricLive/CLIENT\] forge:obj geometry loader present: true' "$LOG"
check "creative injection reached both collections" 'ForbricLive/REGISTRATION\] injection VISIBLE: true search=true phase=client tick 100' "$LOG"
check "client initialization bridges report installed" 'all 3 CLIENT_INIT bridge\(s\) installed' "$LOG"
check "registration bridges report installed" 'all 2 REGISTRATION bridge\(s\) installed' "$LOG"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M26 FORGE-CLIENT GATE GREEN — real registration events and consumer results agree"
else
  echo "[kernel] M26 FORGE-CLIENT GATE RED — see $LOG"
fi
exit "$FAIL"
# M26_ASSERTIONS_END
