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
#
# The exit hook was not the only class read late. Every hook spliced into the game is read from the jar at its first
# use: the first nightly (2026-10-05) crashed the server here when the first lava flow came after the truncation
# (NoClassDefFoundError: net/forbric/kernel/interop/ForgeRuntimeInterop, through MinecraftForge's fluid rules), and
# every run, green ones included, logged NoClassDefFoundError: net/forbric/kernel/boot/KernelRegistryRevert when the
# client left the world — this gate only looked for the interop package, so that one never counted. The kernel now
# defines its whole jar at boot (KernelJarPreload), and the absence check below covers every kernel class.
#
# RED control: M22_EXTRA_JVM='-Dforbric.bootJarPreload=off' — the disconnect's KernelRegistryRevert fails again.
# GATE-PARALLEL: clone=client-merged-pack:M22_RUNDIR mem=3000
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
LAUNCHED=$SECONDS
(
  FORBRIC_BOOT_JAR="$COPY" RUNDIR="$RUNDIR" \
  FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140 ${M22_EXTRA_JVM:-}" \
  "$KERNEL/run/launch-kernel-client.sh" \
    --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$LOG" 2>&1
) &
CLIENTPID=$!
record_server_pid "$RUNDIR" "$CLIENTPID"

step "destroy that copy while the game is in a world"
# Wait for the world, so the destruction lands mid-session rather than mid-boot — booting from a jar that is
# already gone is a different (and far louder) failure than one that disappears underneath a running JVM.
#
# Two waits, because the launcher does two things before there is a world, and only the second is this gate's
# subject. First it BUILDS: `gradlew jar`, `gradlew printBootClasspath` and the metadata jar, each a fresh no-daemon
# Gradle. Then `[kernel-launch] mods:` -- its last line before `exec java` -- and the client JVM has
# M22_READY_SECONDS to reach a world. The window used to be one flat 120 s from the launch, build included: a
# parallel gates-all run turned it red on both integration runs of 2026-10-24, once with the JVM reaching the world
# 79 s after it started (the other 41 s were Gradle) and once at 121 s with the build on top. Alone it had passed
# with ~20 s to spare. The build gets its own loose bound, M22_BUILD_SECONDS, so a hung Gradle still ends the gate;
# how long it took is printed, not judged (6-41 s measured; the 41 s in a full parallel gates-all run).
#
# M22_READY_SECONDS, measured from JVM start to client-ready with this 97-jar pack: 84-88 s alone on a quiet
# machine; 79-125 s for the eight client-merged-pack clients (m9, m22, m23, m27) of two parallel gates-all runs;
# 97-204 s for this gate run alone (seven runs) while other worktrees' gates and probes (and a soak) kept the load
# average at 10-29; 205, 231 and 235 s with ten gates at once on this tree and ten on another (load average medians
# 28-59, peaks 40-111); and 270 s, the worst, in a full -j 5 gates-all run beside other worktrees' work, whose load
# samples in its first half hour read 44-163 (none was taken while this gate ran). A vanilla 26.2 client (no loader, a
# vanilla world) took 10-51 s to join under those loads, so most of what load stretches is the pack's loading, not
# the game's. 600 s is 2.2 times the worst of those. It bounds a client that is still loading, not its speed: one
# that is alive and will never reach a world is red when the bound runs out, and one that exits first is red at once. The negative controls are a client JVM stopped (SIGSTOP) as soon
# as it starts, and a pack whose only world has an unreadable level.dat; both went red at the bound. With a loadable
# world beside the unreadable one, a mouse click from outside reached the window and opened that world instead (twice),
# so a control that leaves one is no control.
BUILD_LIMIT="${M22_BUILD_SECONDS:-900}"
READY_LIMIT="${M22_READY_SECONDS:-600}"
JVM_AT=""
while [ $((SECONDS - LAUNCHED)) -lt "$BUILD_LIMIT" ]; do
  grep -aq '^\[kernel-launch\] mods:' "$LOG" 2>/dev/null && { JVM_AT=$SECONDS; break; }
  kill -0 "$CLIENTPID" 2>/dev/null || break
  sleep 1
done
ready() { grep -aq 'ClientSmoke\] client-ready after' "$LOG" 2>/dev/null; }
REACHED=0
if [ -n "$JVM_AT" ]; then
  echo "[kernel] the launcher's own build took $((JVM_AT - LAUNCHED))s; the client JVM has ${READY_LIMIT}s from its start to reach a world"
  while [ $((SECONDS - JVM_AT)) -lt "$READY_LIMIT" ]; do
    ready && break
    kill -0 "$CLIENTPID" 2>/dev/null || break
    sleep 1
  done
  ready && REACHED=1
fi
if [ "$REACHED" -eq 1 ]; then
  : > "$COPY"
  echo "[kernel] client-ready $((SECONDS - JVM_AT))s after the client JVM started; truncated the boot jar it is running from ($(wc -c < "$COPY") bytes)"
else
  if [ -z "$JVM_AT" ]; then
    if kill -0 "$CLIENTPID" 2>/dev/null; then why="the launcher was still building after ${BUILD_LIMIT}s and never started the client JVM"
    else why="the launcher exited before it started the client JVM"; fi
  elif kill -0 "$CLIENTPID" 2>/dev/null; then why="the client JVM was still running ${READY_LIMIT}s after it started, without a world"
  else why="the client JVM exited $((SECONDS - JVM_AT))s after it started, without a world"; fi
  echo "[kernel] FAIL the client never reached a world, so nothing was destroyed mid-session: $why — see $LOG"; FAIL=1
fi

step "wait for it to quit"
if [ "$REACHED" -eq 1 ]; then
  await_server "$CLIENTPID" "$LOG" 200
else
  # Nothing to wait for: a client with no world will not disconnect, and the checks below are about a quit.
  kill_tree "$CLIENTPID"
  wait "$CLIENTPID" 2>/dev/null
  echo "[kernel] stopped the client (it had no world to quit from); the quit checks below do not apply"
fi

if [ "$REACHED" -eq 1 ]; then
  step "the quit is still a quit (must PASS)"
  # The outcome, from the two places a player and a launcher each read it: no crash report on disk, and the
  # shutdown hook's own line in the log. Either alone is weaker than it looks — a hook that never ran leaves no
  # crash report either.
  check "the kernel jar was defined while it was readable" "Forbric/Boot\] defined [0-9]+ of the kernel jar's [0-9]+ classes up front" "$LOG"
  check "the shutdown hook still ran"   "Forbric/Shutdown\] stopped [0-9]+ config file-watcher" "$LOG"
  check_absent "no crash report was written"  "Preparing crash report"                          "$LOG"
  check_absent "every kernel class resolved"  "NoClassDefFoundError: net/forbric/"              "$LOG"
  if [ -d "$RUNDIR/crash-reports" ] && [ -n "$(ls -A "$RUNDIR/crash-reports" 2>/dev/null)" ]; then
    echo "[kernel] FAIL a crash report reached disk: $(ls "$RUNDIR/crash-reports")"; FAIL=1
  else
    echo "[kernel] PASS the crash-reports directory is empty"
  fi
fi

rm -f "$COPY"

step "M22 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M22 EXIT GATE GREEN — the kernel jar vanished under a live client and quitting was still a quit"
else
  echo "[kernel] ❌ M22 GATE RED — run $LOG"
fi
exit "$FAIL"
