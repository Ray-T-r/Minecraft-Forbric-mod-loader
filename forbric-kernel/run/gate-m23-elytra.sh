#!/usr/bin/env bash
# M23 gate — an elytra enters flight AND stays in it.
#
# WHY THIS EXISTS. The merge split one mechanism down the middle: it took NeoForge's LivingEntity.canGlide, which
# decides gliding from the neoforge:gliding_flight attribute and never looks at the item, and vanilla's
# ItemStack.forEachModifier, which reads the raw component instead of calling the method that posts the event
# NeoForge's own listener answers. The listener is the only thing anywhere that raises that attribute. Producer on
# one side of the merge, consumer on the other, attribute stuck at its false default.
#
# WHY IT ASSERTS SUSTAIN AND NOT JUST START. canGlide being false did not stop flight from starting — it made
# updateFallFlying clear the flag on the very next tick, so a player got a moment of flight and was pulled back.
# A gate that only asked "did flight start" would have passed on the broken build. This one gives
# updateFallFlying forty chances to cancel it and then asks again.
#
# WHY THE SERVER'S PLAYER. Equipment attributes are applied server-side (detectEquipmentUpdates casts the level to
# ServerLevel) and flight is server-authoritative. The client only predicts — its own isFallFlying reads true even
# on a build where the server refuses, which is exactly how a broken build can look fixed.
# GATE-PARALLEL: rundirs=client-merged-pack mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m23-elytra.log"
RUNDIR="${M23_RUNDIR:-$KERNEL/run/client-merged-pack}"
WORLD="${M23_WORLD:-ForbricTest}"
AT="${M23_AT:-100}"
mkdir -p "$BUILD"

kernel_jar
[ -d "$RUNDIR/saves/$WORLD" ] || { echo "[kernel] SKIP-FATAL: no world at $RUNDIR/saves/$WORLD" >&2; exit 3; }

step "fly an elytra in a real client"
: > "$LOG"
reap_stale_server "$RUNDIR"
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 \
-Dforbric.clientSmokeElytra=$AT -Dforbric.clientSmokeDisconnectTicks=$((AT + 120)) ${M23_EXTRA_JVM:-}" \
RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$LOG" 2>&1 &
CLIENT_PID=$!
record_server_pid "$RUNDIR" "$CLIENT_PID"
echo "[kernel] client pid=$CLIENT_PID (this gate never kills by name — another client may be running)"
await_server "$CLIENT_PID" "$LOG" 320

step "the flight (must PASS)"
check "the sequence ran"  "elytra 1/3: equipped"    "$LOG"
RESULT=$(grep -oE 'elytra 3/3: started=[a-z]+ entered=[a-z]+ sustained=[a-z]+ glidedHorizontally=[0-9.]+ fell=[0-9.-]+ attribute=[0-9.]+ canGlide=[a-z]+' "$LOG" | head -1)
if [ -z "$RESULT" ]; then
  echo "[kernel] FAIL the flight never reported — see $LOG"; FAIL=1
else
  echo "[kernel] $RESULT"
  for want in 'started=true' 'entered=true' 'sustained=true' 'canGlide=true'; do
    case "$RESULT" in
      *"$want"*) echo "[kernel] PASS $want" ;;
      *) echo "[kernel] FAIL $want"; FAIL=1 ;;
    esac
  done
  case "$RESULT" in
    *attribute=0.0*) echo "[kernel] FAIL the gliding attribute never reached the player"; FAIL=1 ;;
    *) echo "[kernel] PASS the gliding attribute reached the player" ;;
  esac
  # WHAT THIS GATE DOES NOT COVER, said here so "elytra gate green" is not read as more than it is: the player
  # does not travel. This harness's client never receives input and its local player's physics does not run --
  # two motion samples 40 ticks apart come back byte-identical -- so distance cannot be measured here, and an
  # assertion on it would be permanently red for a reason that has nothing to do with the repair.
  #
  # What IS covered is the thing that was broken. canGlide being false never stopped flight from starting; it
  # made updateFallFlying clear the flag on the next tick, so the player got a moment of flight and was pulled
  # back. Forty ticks of sustain, asked of the server's own player, is exactly that failure's opposite.
  echo "[kernel] NOTE the player does not travel in this harness (no input, no local physics) — sustain is the"
  echo "[kernel]      assertion, and it is the one the bug broke; distance is reported above, not asserted"
fi
check_absent "no crash report"  "Preparing crash report"  "$LOG"

step "M23 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M23 ELYTRA GATE GREEN — an elytra entered flight and stayed in it for 40 server ticks"
else
  echo "[kernel] ❌ M23 GATE RED — run $LOG"
fi
exit "$FAIL"
