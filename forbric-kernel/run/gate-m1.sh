#!/usr/bin/env bash
# M1/M3-server gate — the sovereign kernel boots the merged base to Done with ZERO genuine loader lifecycle.
#
# The kernel's own transforming class loader loads the merged base, redirects the genuine FancyModLoader
# server-loading trigger to the kernel's native lifecycle, seeds only passive genuine-loader identity, natively
# registers both ecosystems' baseline registries + content (container factory + RegisterEvent dispatch + Forge
# bake), and drives the vanilla server boot to Done — then ticks and shuts down cleanly.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m1-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-kernel"

step "boot merged-base server under the kernel (zero mods), tick, clean stop"
"$KERNEL/gradlew" --offline -q -p "$KERNEL" jar >/dev/null 2>&1
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" 2>/dev/null
rm -rf "$RUNDIR/mods" 2>/dev/null; mkdir -p "$RUNDIR/mods"  # genuinely zero-mod
# This gate used to write no server.properties at all, so it ran on whichever file the last gate to use this
# rundir left behind -- m2 and m3 share it. The baseline gate should not inherit another gate's settings, and
# it certainly should not inherit its port.
seed_server_properties "$RUNDIR"
: > "$LOG"
# Feed "stop" once the server has actually reached Done and ticked a little, then let it shut down gracefully.
# A fixed timer raced: if boot happens to finish right at the deadline, "stop" lands on the Done boundary (the
# permission handler is still initialising) and the command dies with "An unexpected error occurred while trying to
# execute that command" — the server then never stops and the gate burns its whole 90s poll before the hammer.
(
  for i in $(seq 1 90); do
    grep -q 'Done (' "$LOG" 2>/dev/null && break
    grep -qE 'Failed to start the minecraft server' "$LOG" 2>/dev/null && break
    sleep 1
  done
  sleep 5
  echo stop
) | FORBRIC_JVM="-Dforbric.debug=true" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 90

step "boot achievements (must PASS)"
check "kernel loaded merged base through its own loader"  "sovereign kernel .* owned jar" "$LOG"
# The kernel's OWN game-side half. Asserted with a literal count and not [0-9]+, because [0-9]+ matches 0 and a
# kernel that delivered nothing would read exactly like one that delivered everything. The right-hand number is
# how many net.forbric.kernel.runtime classes KernelRuntimeClasses marks COMPILED; when that grows, this grows.
check "kernel's own game-side classes linked"             "game-side kernel classes: 5/5 linked" "$LOG"
check "genuine server-loading lifecycle redirected"       "(redirected|excised) genuine loader trigger .*ServerModLoader.load" "$LOG"
check "native ecosystem registration ran"                 "fired RegisterEvent x[1-9][0-9]* on [1-9][0-9]* bus" "$LOG"
check "server reached Done"                               "Done \(" "$LOG"
check "server ticked + shut down cleanly"                 "Stopping server" "$LOG"
check "worlds saved on shutdown"                          "All dimensions are saved" "$LOG"

step "no crash after Done (must be ABSENT)"
# Everything logged after the Done line; a post-Done 'Encountered an unexpected exception' is a failure.
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m1-postdone.log"
check_absent "no unexpected exception after Done"         "Encountered an unexpected exception" "$BUILD/gate-m1-postdone.log"
check_absent "no crash report after Done"                 "Preparing crash report" "$BUILD/gate-m1-postdone.log"

step "no genuine loader lifecycle actually ran (must be ABSENT)"
check_absent "no genuine FancyModLoader mod loading"      "gatherAndInitializeMods|Constructing [0-9]+ mods|dispatchParallelEvent" "$LOG"
check_absent "no 'no current FML Loader' crash"           "There is no current FML Loader" "$LOG"
check_absent "no Tags not bound"                          "Tags not bound" "$LOG"

step "M1 result"
if [ "$FAIL" -eq 0 ]; then echo "[kernel] ✅ M1/M3-server GATE GREEN — kernel boots merged base to Done, zero genuine lifecycle";
else echo "[kernel] ❌ GATE RED"; fi
exit "$FAIL"
