#!/usr/bin/env bash
# M22 gate — a quit stays a quit when the kernel jar stops being readable.
#
# WHY THIS EXISTS. The exit hook is spliced into Minecraft.close, so the first and only attempt to load
# net.forbric.kernel.interop.ClientShutdown used to happen while the game was shutting down. On a real install
# that produced three "Game shutdown / NoClassDefFoundError" crash reports in one evening — from sessions whose
# kernel jar had been REPLACED on disk while they ran (a developer redeploying mid-session), with a session
# started after the last write exiting clean. The class was in both jars the whole time; it simply had not been
# loaded yet when the file underneath it changed.
#
# A jar swapped under a live JVM is one way to reach that. A jar on a network or removable volume is another. The
# player's report is the same either way: quitting the game is reported by the launcher as a crash.
#
# So this launches with a COPY of the boot jar, destroys that copy while the client is in a world, and asserts
# the quit is still a quit. The destruction is total (the file is truncated), which is far beyond a swap — if the
# shutdown path survives that, it survives a replacement.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m22-exitswap.log"
RUNDIR="${M22_RUNDIR:-$KERNEL/run/client-merged-pack}"
WORLD="${M22_WORLD:-ForbricTest}"
COPY="$BUILD/gate-m22-boot-copy.jar"
mkdir -p "$BUILD"

kernel_jar
REAL="$(ls "$KERNEL"/build/libs/forbric-kernel-*.jar | head -1)"
[ -f "$REAL" ] || { echo "[kernel] FAIL no boot jar to copy"; exit 1; }
[ -d "$RUNDIR/saves/$WORLD" ] || { echo "[kernel] SKIP-FATAL: no world at $RUNDIR/saves/$WORLD" >&2; exit 3; }

step "launch the client from a COPY of the boot jar"
cp "$REAL" "$COPY"
rm -rf "$RUNDIR/crash-reports"
: > "$LOG"
reap_stale_server "$RUNDIR"
(
  FORBRIC_BOOT_JAR="$COPY" RUNDIR="$RUNDIR" \
  FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140" \
  "$KERNEL/run/launch-kernel-client.sh" \
    --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$LOG" 2>&1
) &
CLIENTPID=$!
record_server_pid "$RUNDIR" "$CLIENTPID"

step "destroy that copy while the game is in a world"
# Wait for the world, so the destruction lands mid-session rather than mid-boot — booting from a jar that is
# already gone is a different (and far louder) failure than one that disappears underneath a running JVM.
for i in $(seq 1 120); do
  grep -q 'ClientSmoke\] client-ready after' "$LOG" 2>/dev/null && break
  kill -0 "$CLIENTPID" 2>/dev/null || break
  sleep 1
done
if grep -q 'ClientSmoke\] client-ready after' "$LOG" 2>/dev/null; then
  : > "$COPY"
  echo "[kernel] truncated the boot jar the client is running from ($(wc -c < "$COPY") bytes)"
else
  echo "[kernel] FAIL the client never reached a world, so nothing was destroyed mid-session"; FAIL=1
fi

step "wait for it to quit"
await_server "$CLIENTPID" "$LOG" 200

step "the quit is still a quit (must PASS)"
# The outcome, from the two places a player and a launcher each read it: no crash report on disk, and the
# shutdown hook's own line in the log. Either alone is weaker than it looks — a hook that never ran leaves no
# crash report either.
check "the shutdown hook still ran"   "Forbric/Shutdown\] stopped [0-9]+ config file-watcher" "$LOG"
check_absent "no crash report was written"  "Preparing crash report"                          "$LOG"
check_absent "the exit hook resolved"       "NoClassDefFoundError: net/forbric/kernel/interop" "$LOG"
if [ -d "$RUNDIR/crash-reports" ] && [ -n "$(ls -A "$RUNDIR/crash-reports" 2>/dev/null)" ]; then
  echo "[kernel] FAIL a crash report reached disk: $(ls "$RUNDIR/crash-reports")"; FAIL=1
else
  echo "[kernel] PASS the crash-reports directory is empty"
fi

rm -f "$COPY"

step "M22 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M22 EXIT GATE GREEN — the kernel jar vanished under a live client and quitting was still a quit"
else
  echo "[kernel] ❌ M22 GATE RED — run $LOG"
fi
exit "$FAIL"
