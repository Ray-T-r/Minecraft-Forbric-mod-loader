#!/usr/bin/env bash
# M24c switched-off gate — a jar listed in forbric-disabled.txt stays in mods/ and is loaded by nobody.
#
# WHY THIS EXISTS. The fix every crash analysis and load report ends in is "take it out of your mods folder", and
# forbric-disabled.txt is the version of that a player can undo by deleting a line (the crash-suspects offer writes
# it for them). DisabledModsTest proves the decision and every reader of it with synthetic jars; nothing proved a
# real boot. A suppressed jar that some walk of mods/ still reads would construct its mod anyway, and this is the
# only place that would show it.
#
# Staged: the Fabric canary (with its nested library) and the NeoForge canary. The file switches the Fabric one off:
# its entrypoint must not run, the server must still reach Done under strict policy (switching a mod off is not a
# failure), and the load report must name the jar. A pending crash-suspects.json naming the NeoForge canary proves
# the offer is read before arbitration and that a server only logs it. The control has neither file and must load
# the Fabric canary.
# GATE-PARALLEL: rundirs=server-disabled mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m24c-disabled.log"
CONTROL="$BUILD/gate-m24c-control.log"
RUNDIR="$KERNEL/run/server-disabled"
REPORT="$RUNDIR/.forbric-kernel/load-report.txt"
PENDING="$RUNDIR/.forbric-kernel/crash-suspects.json"
DISABLED="$RUNDIR/forbric-disabled.txt"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$NEOLIVE"
mkdir -p "$BUILD"

step "stage the two canaries"
"$KERNEL/run/build-fabric-canary.sh" >"$BUILD/gate-m24c-canary.log" 2>&1
for jar in "$FABRIC" "$NEO"; do
  [ -f "$jar" ] || { echo "[kernel] FAIL missing canary: $jar (see $BUILD/gate-m24c-canary.log)"; exit 1; }
done

stage() { # stage <switch-the-fabric-canary-off>
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" "$DISABLED" 2>/dev/null
  mkdir -p "$RUNDIR/mods"
  cp "$FABRIC" "$NEO" "$RUNDIR/mods/"
  if [ "$1" = "yes" ]; then
    printf '# switched off by gate-m24c\nforbricfabriclive.jar\n' > "$DISABLED"
    mkdir -p "$RUNDIR/.forbric-kernel"
    printf '%s\n' '{"schema":1,"report":"crash-gate-m24c.txt","clash":false,"suspects":[{"modId":"forbricneolive","name":"Forbric Neo Live","jar":"forbricneolive.jar","reason":"its code is in the crash","depth":2}]}' > "$PENDING"
  fi
  seed_server_properties "$RUNDIR"
  echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"
}

boot() { # boot <log>
  local log="$1"
  : > "$log"
  rm -f "$log.exit"
  (
    # "stop" once the server is up, not after a fixed 30 s: a stop that lands on the first tick, when this boot
    # happened to take exactly 30 s, failed with "An unexpected error occurred while trying to execute that command"
    # and the server ran on until the gate killed it. Waiting for Done is the same proof of a ticking server.
    ( for _ in $(seq 120); do grep -aq 'Done (' "$log" 2>/dev/null && break; sleep 1; done; sleep 5; echo stop ) \
      | RUNDIR="$RUNDIR" FORBRIC_COMPAT_POLICY=strict \
      FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.compatibilityPolicy=strict" "$KERNEL/run/launch-kernel-server.sh"
    code=$?
    printf '%s\n' "$code" > "$log.exit"
    exit "$code"
  ) > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 130
}

stage yes
step "forbric-disabled.txt switches the Fabric canary off"
boot "$LOG"
check "the server exited normally under strict policy" "^0$" "$LOG.exit"
check "server reached Done"                           "Done \("                                  "$LOG"
check "server ticked (the stop command ran)"          "Stopping the server|commands\.stop\.stopping" "$LOG"
check_absent "no crash report was written"            "Preparing crash report"                   "$LOG"

step "the switched-off jar was loaded by nobody (must PASS)"
check "the file was read"                    "Forbric/Disabled\] forbric-disabled\.txt switches off 1 jar\(s\): forbricfabriclive\.jar" "$LOG"
check_absent "its entrypoint never ran"      "\[ForbricFabricLive\]"                     "$LOG"
check_absent "its nested library never ran"  "\[ForbricFabricLib\]"                      "$LOG"
check "the other canary still constructed"   "\[ForbricNeoLive\]"                        "$LOG"
# Switching a mod off is the player's choice, not a failure: the success line stays, and strict did not stop.
check "every mod that loaded finished"       "Forbric/Load\] every mod finished loading" "$LOG"
check_absent "nothing was reported as failing" "did not finish loading"                  "$LOG"

step "and it is named where a player looks (must PASS)"
check "the load summary names the jar" \
  "Forbric/Load\] 1 mod\(s\) switched off in forbric-disabled\.txt: forbricfabriclive\.jar" "$LOG"
if [ -f "$REPORT" ]; then
  echo "[kernel] PASS the load report was written ($REPORT)"
  # The file is in the system language; the log line above is always English.
  check "the report names the jar" \
    "switched off in forbric-disabled\.txt: forbricfabriclive\.jar|forbric-disabled\.txt 里关掉了 1 个 mod：forbricfabriclive\.jar" "$REPORT"
  check "the report says how to undo it" "delete its line|删掉" "$REPORT"
else
  echo "[kernel] FAIL no load report at $REPORT"; FAIL=1
fi

step "a server reads the crash-suspects offer before arbitration and only logs it (must PASS)"
check "the offer was logged, not asked" \
  "Forbric/Crash\] the last crash \(crash-gate-m24c\.txt\) pointed at Forbric Neo Live — not the client, so nothing is asked" "$LOG"
offer_line=$(grep -an "Forbric/Crash\] the last crash" "$LOG" | head -1 | cut -d: -f1)
disabled_line=$(grep -an "Forbric/Disabled\] forbric-disabled" "$LOG" | head -1 | cut -d: -f1)
if [ -n "$offer_line" ] && [ -n "$disabled_line" ] && [ "$offer_line" -lt "$disabled_line" ]; then
  echo "[kernel] PASS the offer ran before arbitration read forbric-disabled.txt (line $offer_line < $disabled_line)"
else
  echo "[kernel] FAIL the offer did not run before arbitration (offer line '${offer_line:-none}', file line '${disabled_line:-none}')"; FAIL=1
fi
if [ -f "$PENDING" ] && [ ! -f "$RUNDIR/.forbric-kernel/crash-suspects.offered.json" ]; then
  echo "[kernel] PASS nobody was asked, so the offer is still pending"
else
  echo "[kernel] FAIL a server retired the crash-suspects offer it cannot show"; FAIL=1
fi
check_absent "and the server wrote nothing into the player's file" "forbricneolive" "$DISABLED"

step "control: the same instance without forbric-disabled.txt"
stage no
boot "$CONTROL"
check "the control exited normally"              "^0$"                                       "$CONTROL.exit"
check "the control booted"                       "Done \("                                   "$CONTROL"
check "the Fabric canary initialised"            "\[ForbricFabricLive\] onInitialize"        "$CONTROL"
check "the NeoForge canary constructed"          "\[ForbricNeoLive\]"                        "$CONTROL"
check "every mod finished loading"               "Forbric/Load\] every mod finished loading" "$CONTROL"
check_absent "nothing was switched off"          "switched off in forbric-disabled|Forbric/Disabled\]" "$CONTROL"
if [ -f "$REPORT" ]; then
  echo "[kernel] FAIL a load report was written for a clean boot: $REPORT"; FAIL=1
else
  echo "[kernel] PASS a clean boot writes no load report"
fi

step "M24c result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M24c SWITCHED-OFF GATE GREEN — a listed jar is loaded by nobody and named; the control loads it"
else
  echo "[kernel] ❌ M24c GATE RED — switched off $LOG / control $CONTROL"
fi
exit "$FAIL"
