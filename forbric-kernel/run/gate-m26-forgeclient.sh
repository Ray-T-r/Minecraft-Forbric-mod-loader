#!/usr/bin/env bash
# EXPECTED: RED until Phase 1 A (Forge client registration and creative-tab hooks are restored).
# M26 — traditional Forge's client event buses must receive the game's real registration events.
# Future repair controls: M26_EXTRA_JVM='-Dforbric.forgeClientInit=off' (keys/renderers),
# M26_EXTRA_JVM='-Dforbric.forgeCreativeTabs=off' (creative contents) must restore the missing lines.
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
printf 'onboardAccessibility:false\n' > "$RUNDIR/options.txt"
: > "$LOG"
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=160 ${M26_EXTRA_JVM:-}" \
RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$LOG" 2>&1 &
CLIENT_PID=$!
record_server_pid "$RUNDIR" "$CLIENT_PID"
await_server "$CLIENT_PID" "$LOG" 360 30
rm -f "$RUNDIR/.forbric-gate.pid"
cat "$RUNDIR/logs/latest.log" >> "$LOG" 2>/dev/null || true

step "the canary subscribed, entered its save and exercised the creative contents builder"
check "client canary subscribed" 'ForbricLive/CLIENT\] subscribed to three Forge registration events' "$LOG"
check "joined world" 'ClientSmoke\] joined world via quick-play' "$LOG"
check "simulation survived" 'ClientSmoke\] client-ready after' "$LOG"
check "creative contents were requested" 'ForbricLive/CLIENT\] creative contents builder exercised in a live world' "$LOG"
check "clean disconnect" 'ClientSmoke\] clean disconnect observed' "$LOG"
check_absent "client canary initialized successfully" 'ForbricLive/CLIENT\] FAILED initialization' "$LOG"
check_absent "no missing client classes or crashes" 'NoClassDefFoundError|Preparing crash report|Mod Loading has failed' "$LOG"
CONTROL_FAIL=$FAIL

step "all three traditional-Forge registration events arrived"
for event in 'RegisterKeyMappingsEvent' 'EntityRenderersEvent.RegisterRenderers' 'BuildCreativeModeTabContentsEvent'; do
  check "$event received" "ForbricLive/CLIENT\] ${event//./\.} RECEIVED" "$LOG"
done
if [ "$CONTROL_FAIL" -eq 0 ] && [ "$FAIL" -ne 0 ]; then
  echo "[kernel] EXPECTED-RED Forge registration events were not delivered (Phase 1 A)"
  exit 2
fi
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M26 FORGE-CLIENT GATE GREEN — all three real registration events arrived"
else
  echo "[kernel] M26 FORGE-CLIENT GATE RED — see $LOG"
fi
exit "$FAIL"
