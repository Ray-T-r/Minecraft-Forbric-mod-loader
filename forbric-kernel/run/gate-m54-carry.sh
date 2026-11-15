#!/usr/bin/env bash
# M54 gate — a NeoForge mod's play-phase packet to the server is heard: Carry On picks a chest up and puts it back,
# then a pig, driven through the game's own keyboard and mouse.
#
# WHY THIS EXISTS. Carry On tells the server "the carry key is held" with a play-phase payload. NeoForge's patched
# ServerGamePacketListenerImpl.handleCustomPayload is super.handleCustomPayload(packet), because NeoForge's whole
# dispatcher lives in ServerCommonPacketListenerImpl's. fabric-api injects a handler at the HEAD of that super that serves
# only the configuration listener and throws "Unknown addon" for any other, so with fabric-api installed the first play
# payload fabric-api's own play receiver does not take ends the connection the moment Carry On sends its key. On vanilla
# no play payload reaches the super: the play listener's override is empty. FabricCommonPayloadMixinAdapter returns from
# that handler at its head for a play listener, so NeoForge's body runs as on NeoForge. (On the merged base the override
# was MinecraftForge's, which never reached NeoForge's dispatcher at all, so the server never believed the key was down;
# that repair went with MinecraftForge in Stage 4, and the switch below now governs the fabric-api handler.)
#
# WHY fabric-api IS IN THE PACK. It is the half of the conflict: without fabric-api nothing throws "Unknown addon".
#
# WHY THE VERDICT IS THE WORLD. The drill (KernelClientSmoke, -Dforbric.clientSmokeCarry) presses LEFT_SHIFT through
# KeyboardHandler.keyPress and the right button through MouseHandler.onButton, then reads on the SERVER whether the
# chest left its block and came back with its 7 diamonds, and whether the pig left and came back. Carry On's own
# carry data is logged beside that as evidence of which link broke; nothing Forbric says about itself is asserted.
#
# THE NEGATIVE CONTROL. The same run with -Dforbric.playPayloadFallThrough=off leaves fabric-api's handler as written.
# Measured on NeoForge's base (C5, 2026-11-09, gate 11 PASS / 0 FAIL): the server disconnects the player ("ForbricKernel
# lost connection: Internal Exception: java.lang.IllegalStateException: Unknown addon", and the client's "Client
# disconnected with reason: ..." the same) when Carry On sends its key, and the drill reports no verdict at all -- it lost
# its world before it could carry anything. The repaired run carried and put back both. A gate that cannot go red without
# the repair is not measuring it. A disconnected client would be left on the disconnect screen with no smoke left to
# stop it, so the control is stopped by the gate once its integrated server has stopped, and that is not counted
# against it.
#
# Needs: carryon-neoforge-26.2-2.11.2.jar (M54_CARRYON, default run/carry-on/) and a fabric-api jar (M54_FABRIC_API,
# default the client-merged-pack's). Both are third-party jars this tree does not ship. Rundir: M54_RUNDIR (default
# run/client-carry-m54, which the gate wipes per run).
# GATE-PARALLEL: rundirs=client-carry-m54 mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="${M54_RUNDIR:-$KERNEL/run/client-carry-m54}"
refuse_shared_rundir "$RUNDIR"
WORLD="ForbricTest"
FIXTURE="${M54_WORLD_FIXTURE:-$KERNEL/run/client-merged-pack/saves/$WORLD}"
CARRYON="${M54_CARRYON:-$KERNEL/run/carry-on/carryon-neoforge-26.2-2.11.2.jar}"
FABRIC_API="${M54_FABRIC_API:-$(ls "$KERNEL"/run/client-merged-pack/mods/fabric-api-*.jar 2>/dev/null | head -1)}"
AT="${M54_AT:-100}"
mkdir -p "$BUILD"

for need in "$CARRYON" "$FABRIC_API"; do
  [ -f "$need" ] || { echo "[kernel] SKIP-FATAL: M54 needs $need (set M54_CARRYON / M54_FABRIC_API)" >&2; exit 3; }
done
[ -d "$FIXTURE" ] || { echo "[kernel] SKIP-FATAL: no world fixture at $FIXTURE (set M54_WORLD_FIXTURE)" >&2; exit 3; }

kernel_jar

# carry_run <label> <extra-jvm> [stranded] — a fresh world and the drill, one client; the log is $BUILD/gate-m54-<label>.log.
# "stranded": the run is expected to lose its connection, so its client is stopped once the integrated server has
# stopped (or the budget ran out) instead of being judged for not leaving on its own.
carry_run() {
  local label="$1" extra="$2" stranded="${3:-}" log="$BUILD/gate-m54-$1.log" i
  reap_stale_server "$RUNDIR"
  # The drill builds its stage in the world, so every run starts from the fixture rather than the last run's save.
  rm -rf "$RUNDIR"
  mkdir -p "$RUNDIR/mods" "$RUNDIR/saves" "$RUNDIR/quickPlay"
  cp "$CARRYON" "$FABRIC_API" "$RUNDIR/mods/"
  cp -R "$FIXTURE" "$RUNDIR/saves/"
  # Its own options rather than the fixture pack's: default key bindings (sneak and Carry On's key both on LEFT_SHIFT,
  # as a player who never opened the controls screen has them), and no pause when the unattended window loses focus.
  printf 'version:4903\nonboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nlang:en_us\n' \
    > "$RUNDIR/options.txt"
  : > "$log"
  FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 \
-Dforbric.clientSmokeCarry=$AT -Dforbric.clientSmokeDisconnectTicks=$((AT + 260)) $extra ${M54_EXTRA_JVM:-}" \
  RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
    --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  echo "[kernel] client pid=$pid (this gate never kills by name — another client may be running)"
  if [ -z "$stranded" ]; then
    await_server "$pid" "$log" 320
    return
  fi
  for i in $(seq 1 320); do
    kill -0 "$pid" 2>/dev/null || break
    grep -qE 'Stopping server|Game crashed' "$log" 2>/dev/null && break
    sleep 1
  done
  for i in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  stop_tree "$pid"
  wait "$pid" 2>/dev/null
}

verdict() { grep -aoE 'carry drill result: .*' "$1" | head -1; }

step "the drill with the repair (must PASS)"
carry_run repaired ""
LOG="$BUILD/gate-m54-repaired.log"
RESULT="$(verdict "$LOG")"
check "the drill ran to its verdict" "carry drill result" "$LOG"
for want in 'chest picked up=true' 'chest put back=true' 'with its contents=true' 'pig picked up=true' 'pig put back=true'; do
  case "$RESULT" in
    *"$want"*) echo "[kernel] PASS $want" ;;
    *) echo "[kernel] FAIL $want — $RESULT"; FAIL=1 ;;
  esac
done
check_absent "nobody was disconnected (fabric-api's \"Unknown addon\", NeoForge's \"No Channel\")" \
  "Unknown addon|lost connection: Internal Exception|No Channel for" "$LOG" '\[Forbric/'
check_absent "no crash report" "Preparing crash report" "$LOG"
check "the client left the world cleanly" "clean disconnect observed" "$LOG"

step "the same drill with the repair switched off (must be RED)"
carry_run control "-Dforbric.playPayloadFallThrough=off" stranded
CLOG_CONTROL="$BUILD/gate-m54-control.log"
CONTROL="$(verdict "$CLOG_CONTROL")"
# Either end may say it first: the server's "<player> lost connection: ..." or the client's "Client disconnected with
# reason: ..." (the client's line is the one a stranded client always writes; the server's may still be unflushed when
# the gate stops it).
check "the control's player was disconnected by fabric-api's configuration handler" \
  "(lost connection|Client disconnected with reason): Internal Exception: java.lang.IllegalStateException: Unknown addon" "$CLOG_CONTROL"
case "$CONTROL" in
  *'chest picked up=true'*|*'pig picked up=true'*)
    echo "[kernel] FAIL the control still carried something, so the gate does not measure the repair: $CONTROL"; FAIL=1 ;;
  *) echo "[kernel] PASS the control carried nothing — the gate measures the repair (${CONTROL:-no verdict: the drill lost its world})" ;;
esac

step "M54 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M54 CARRY GATE GREEN — Carry On's key reached the server: a chest and a pig carried and put back"
else
  echo "[kernel] ❌ M54 GATE RED — see $BUILD/gate-m54-repaired.log and $BUILD/gate-m54-control.log"
fi
exit "$FAIL"
