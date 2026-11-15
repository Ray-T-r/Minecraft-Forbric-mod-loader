#!/usr/bin/env bash
# M24 broken-mod gate — a mod that fails while the game loads stops the boot as its own loader stops it, and is named.
#
# WHY THIS EXISTS. Every other gate here asserts that NO mod failed: gate-m4 and gate-m7 each carry a
# check_absent "no @Mod construction failure". This one stages mods that fail on purpose and asserts what then happens.
#
# Until Stage 4 C5 that was Forbric's own posture: load as much as it can and carry on, the failing mod contained and
# attributed, the compatibility policy deciding (continue went on, strict stopped with exit 78). Owner ruling D82 ("stop
# like native") reversed it for NeoForge's registration (lane K3c) and D87 everywhere (lane STOP): a mod that throws while
# the game loads stops the boot as native NeoForge 26.2.0.93 / native Fabric Loader stops it, whatever the policy, with
# the native error:
#   * a Fabric main entrypoint (the Fabric broken canary): Fabric Loader's "Could not execute entrypoint stage 'main' due
#     to errors, provided by '<mod>'", "Failed to start the minecraft server", no NeoForge mod-loading report, exit 1;
#   * the register-stop canary, one NeoForge phase per boot (-Dforbric.registerstop): its RegisterEvent listener
#     (listener) and a throw inside GameData.postRegisterEvents' try (try) — NeoForge's roll-back; its constructor
#     (construct) and @EventBusSubscriber class (ebs) — "Mod Construction"; an attribute event in postRegisterEvents'
#     tail (tail); common setup (common) and its deferred work (deferred); RegisterCapabilitiesEvent (regevents);
#     payload registration (network). Each: NeoForge's "Failed to wait for future <task>, 1 errors found", the kernel's
#     stop line, the crash-*-fml.txt report naming the cause, Forbric's crash analysis naming the mod, "Failed to start
#     the minecraft server", no world, exit status 1 (FML's launcher, finding no server thread).
# The phases left out (constructevent, newregistry, sided, imc, complete) take the same code paths as ones above and have
# unit tests (NeoSetupPhaseStopStagedTest); the lane's evidence ran every mode once on Forbric and on native NeoForge.
# The same register-stop jar with =off is in the clean control. The old continue and strict boots of the Fabric broken
# canary asserted the containment D87 retired (the continue boot reached Done and wrote a load report; strict exited 78).
# GATE-PARALLEL: rundirs=server-brokenmod mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m24-brokenmod.log"
CONTROL="$BUILD/gate-m24-control.log"
RUNDIR="$KERNEL/run/server-brokenmod"
refuse_shared_rundir "$RUNDIR"
REPORT="$RUNDIR/.forbric-kernel/load-report.txt"
REGSTOP="$KERNEL/run/canary/forbricregisterstop.jar"
STOP_LISTENER="$BUILD/gate-m24-registerstop-listener.log"
STOP_TRY="$BUILD/gate-m24-registerstop-try.log"
STOP_LOG() { echo "$BUILD/gate-m24-registerstop-$1.log"; }
COMPAT="$RUNDIR/.forbric-kernel/compatibility-report.json"
BROKEN="$KERNEL/run/canary/forbricbrokencanary.jar"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$NEOLIVE"
mkdir -p "$BUILD"

step "stage the broken mod and two healthy ones"
"$KERNEL/run/build-broken-canary.sh" >"$BUILD/gate-m24-canary.log" 2>&1
"$KERNEL/run/build-fabric-canary.sh" >>"$BUILD/gate-m24-canary.log" 2>&1
"$KERNEL/run/build-register-stop-canary.sh" >>"$BUILD/gate-m24-canary.log" 2>&1
for jar in "$BROKEN" "$FABRIC" "$NEO" "$REGSTOP"; do
  [ -f "$jar" ] || { echo "[kernel] FAIL missing canary: $jar (see $BUILD/gate-m24-canary.log)"; exit 1; }
done

stage() { # stage <include-broken> [include-register-stop]
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" "$RUNDIR/crash-reports" 2>/dev/null
  mkdir -p "$RUNDIR/mods"
  cp "$FABRIC" "$NEO" "$RUNDIR/mods/"
  [ "$1" = "yes" ] && cp "$BROKEN" "$RUNDIR/mods/"
  [ "${2:-no}" = "yes" ] && cp "$REGSTOP" "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"
}

boot() { # boot <log> <policy> [expect-policy-stop|expect-stop] [register-stop-mode]
  local log="$1" policy="$2" mode="${3:-normal}" regstop="${4:-}"
  : > "$log"
  rm -f "$log.exit"
  (
    launch() {
      RUNDIR="$RUNDIR" FORBRIC_COMPAT_POLICY="$policy" \
        FORBRIC_JVM="${FORBRIC_JVM:-} -Dforbric.compatibilityPolicy=$policy${regstop:+ -Dforbric.registerstop=$regstop}" \
        "$KERNEL/run/launch-kernel-server.sh"
    }
    if [ "$mode" = "expect-policy-stop" ] || [ "$mode" = "expect-stop" ]; then
      # No sleeping stdin feeder may outlive an early stop and look like a leaked server process.
      launch </dev/null
    else
      ( stop_when_ready "$log" 30 ) | launch
    fi
    code=$?
    printf '%s\n' "$code" > "$log.exit"
    exit "$code"
  ) > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 130
}

stage yes
step "a Fabric main entrypoint that throws takes the server down, as native Fabric Loader does (D87; must PASS)"
# Under explicit continue: the compatibility policy no longer decides an entrypoint that threw — natively the game is down.
boot "$LOG" continue expect-stop
check "fabric: exited with status 1"                     "^1$"                                          "$LOG.exit"
check "fabric: the broken mod reached its entrypoint"   "ForbricBrokenCanary\] reached onInitialize"   "$LOG"
check "fabric: and failed there"                         "main entrypoint of forbricbrokencanary failed" "$LOG"
check "fabric: Fabric Loader's stage error names the mod" \
  "Could not execute entrypoint stage 'main' due to errors, provided by 'forbricbrokencanary'" "$LOG"
check "fabric: the kernel stops the boot as native Fabric does" "the boot stops here, as native Fabric Loader stops it" "$LOG"
check "fabric: the game's Main gave up on it"            "Failed to start the minecraft server"         "$LOG"
check "fabric: and the launcher exits 1"                 "the server did not start .*exiting with status 1" "$LOG"
check "fabric: the NeoForge mod was constructed before it" "\[ForbricNeoLive\]"                        "$LOG"
check_absent "fabric: no server came up"                 "Done \("                                     "$LOG"
check_absent "fabric: no NeoForge mod-loading error around a Fabric failure" "Failed to wait for future" "$LOG"
check_absent "fabric: no compatibility decision was asked for it" "Forbric/Compatibility\] launch stopped" "$LOG"
if ls "$RUNDIR"/crash-reports/crash-*-fml.txt >/dev/null 2>&1; then
  echo "[kernel] FAIL fabric: NeoForge's mod-loading crash report was written for a Fabric failure"; FAIL=1
else
  echo "[kernel] PASS fabric: no NeoForge mod-loading crash report (native Fabric writes none)"
fi
if [ -d "$RUNDIR/world" ]; then echo "[kernel] FAIL fabric: a world directory was created"; FAIL=1
else echo "[kernel] PASS fabric: no world was created or loaded"; fi

neo_stop() { # neo_stop <log> <mode> <NeoForge init task> — what every NeoForge stop shows
  local log="$1" mode="$2" task="$3"
  check "$mode: exited with NeoForge's launcher status 1"        "^1$"                                "$log.exit"
  check "$mode: NeoForge's ModLoader ended its task"             "Failed to wait for future $task, 1 errors found" "$log"
  check "$mode: the kernel stops the boot as native does"        "the boot stops here, as native NeoForge stops it" "$log"
  check "$mode: the game's Main gave up on the error"            "Failed to start the minecraft server" "$log"
  check "$mode: and the launcher exits as NeoForge's does"       "the server did not start .*exiting with status 1" "$log"
  local report
  report=$(ls "$RUNDIR"/crash-reports/crash-*-fml.txt 2>/dev/null | head -1)
  if [ -n "$report" ]; then
    echo "[kernel] PASS $mode: NeoForge's mod-loading crash report was written ($(basename "$report"))"
    cp "$report" "$log.crash-fml.txt"
    check "$mode: the crash report says mod loading failed"      "Mod loading has failed"             "$report"
    check "$mode: the crash report names the cause"              "forbricregisterstop('s|:) .* fails on purpose" "$report"
  else
    echo "[kernel] FAIL $mode: no crash-reports/crash-*-fml.txt (NeoForge's ServerModLoader writes one)"; FAIL=1
  fi
  # The player's answer to "which mod?": NeoForge's report names the mod only below the walkthrough divider, in its
  # "-- Mod loading issue for: <id> --" section (or the issue's own trace), which Forbric's crash analysis must read.
  local suspects="$RUNDIR/.forbric-kernel/crash-suspects.json"
  cp "$suspects" "$log.suspects.json" 2>/dev/null || : > "$log.suspects.json"
  if python3 - "$suspects" <<'PY2'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)
ids = [s["modId"] for s in report["suspects"]]
assert ids[:1] == ["forbricregisterstop"], ids
PY2
  then
    echo "[kernel] PASS $mode: Forbric's crash analysis names the mod that failed"
  else
    echo "[kernel] FAIL $mode: Forbric's crash analysis does not name forbricregisterstop ($log.suspects.json)"; FAIL=1
  fi
  check_absent "$mode: and does not tell the player no installed mod is in the crash" \
    "No mod you installed appears in this crash|这次崩溃里没有出现任何一个你装的 mod" "$log"
  check_absent "$mode: no server came up"                        "Done \("                           "$log"
  if [ -d "$RUNDIR/world" ]; then
    echo "[kernel] FAIL $mode: a world directory was created after the stop"; FAIL=1
  else
    echo "[kernel] PASS $mode: no world was created or loaded"
  fi
}

registration_stop() { # registration_stop <log> <mode listener|try>
  local log="$1" mode="$2"
  neo_stop "$log" "$mode" "Registry initialization"
  check "$mode: NeoForge rolled every registry back to vanilla"  "Rolling back to VANILLA state"      "$log"
  check_absent "$mode: no Fabric main ran after the roll-back"   "\[ForbricFabricLive\] onInitialize" "$log"
  # Natively freezeData never runs after the roll-back; the clean control below shows the line on a boot that froze.
  check_absent "$mode: the registries were not frozen after the roll-back" "froze the registries NeoForge-first" "$log"
}

step "a NeoForge mod whose RegisterEvent listener throws stops the boot as native NeoForge does (D82; must PASS)"
# Under explicit continue: the compatibility policy does not decide this one, as nothing can run on registries NeoForge
# has rolled back to vanilla.
stage no yes
boot "$STOP_LISTENER" continue expect-stop listener
check "listener: the listener really ran and threw"  "\[ForbricRegisterStop\] reached RegisterEvent for minecraft:item, mode=listener" "$STOP_LISTENER"
check "listener: the kernel names the mod that ended the registry's dispatch" \
  "a NeoForge mod's RegisterEvent listener failed on .*minecraft:item.* — forbricregisterstop ends that registry's dispatch" "$STOP_LISTENER"
check "listener: NeoForge's error names the mod and the event" \
  "\(forbricregisterstop\) encountered an error while dispatching the net\.neoforged\.neoforge\.registries\.RegisterEvent event" "$STOP_LISTENER"
registration_stop "$STOP_LISTENER" listener

step "a throw inside NeoForge's per-registry try, outside any listener, stops the boot as native NeoForge does (D82; must PASS)"
stage no yes
boot "$STOP_TRY" continue expect-stop try
check "try: the mixin really ran and threw" "\[ForbricRegisterStop\] mixin after minecraft:item's dispatch, about to fail on purpose" "$STOP_TRY"
check "try: NeoForge's error names the uncaught error" "An uncaught parallel processing error has occurred" "$STOP_TRY"
registration_stop "$STOP_TRY" try

step "every other phase of NeoForge's mod loading in which a mod throws stops the boot as native NeoForge does (D87; must PASS)"
for spec in "construct:Mod Construction" "ebs:Mod Construction" "tail:Registry initialization" "common:Common setup" \
            "deferred:Common setup: Deferred Queue" "regevents:Registration events" "network:Network registry lock"; do
  mode="${spec%%:*}"; task="${spec#*:}"; log="$(STOP_LOG "$mode")"
  stage no yes
  boot "$log" continue expect-stop "$mode"
  check "$mode: the canary really reached its failing point" "\[ForbricRegisterStop\] .* is about to fail on purpose, mode=$mode" "$log"
  neo_stop "$log" "$mode" "$task"
  case "$mode" in
    construct|ebs)
      check "$mode: NeoForge's issue names the mod"              "Mod loading issue for: forbricregisterstop" "$log.crash-fml.txt"
      check_absent "$mode: construction stops before any registry event" "\[ForbricRegisterStop\] reached RegisterEvent" "$log"
      check_absent "$mode: and before the Fabric mains"          "\[ForbricFabricLive\] onInitialize" "$log"
      check_absent "$mode: and nothing was frozen"               "froze the registries NeoForge-first" "$log" ;;
    tail)
      check_absent "$mode: the tail is outside the roll-back"    "Rolling back to VANILLA state" "$log"
      check_absent "$mode: and nothing was frozen"               "froze the registries NeoForge-first" "$log" ;;
    *)
      check "$mode: NeoForge's issue names the mod"              "Mod loading issue for: forbricregisterstop|-- Mod loading issue --" "$log.crash-fml.txt"
      check "$mode: the registry phase had finished"             "froze the registries NeoForge-first" "$log" ;;
  esac
done

step "negative control: the same instance without the broken mod, the register-stop canary in it with =off"
stage no yes
boot "$CONTROL" strict normal off
check "healthy strict control exited normally" "^0$" "$CONTROL.exit"

check "the control booted"                      "Done \("                    "$CONTROL"
check "the control froze the registries (what the stops above must not do)" "froze the registries NeoForge-first" "$CONTROL"
check "the register-stop canary ran and threw nothing" \
  "\[ForbricRegisterStop\] reached RegisterEvent for minecraft:item, mode=off" "$CONTROL"
check "every mod finished loading"              "Forbric/Load\] every mod finished loading" "$CONTROL"
check_absent "nothing was reported as failing"  "Forbric/Load\] [0-9]+ mod\(s\) did not finish loading"     "$CONTROL"
if [ -f "$REPORT" ]; then
  echo "[kernel] FAIL a load report was written for a clean boot: $REPORT"; FAIL=1
else
  # A file that appears only when something is wrong is a file whose presence already means something.
  echo "[kernel] PASS a clean boot writes no load report"
fi
if python3 - "$COMPAT" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)
assert report["confirmedRequired"] == 0, report
assert not report["catalogFailures"], report
PY
then
  echo "[kernel] PASS the healthy strict control has no required or unclassified loading failures"
else
  echo "[kernel] FAIL the healthy strict control reported a loading failure"; FAIL=1
fi

step "M24 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M24 BROKEN-MOD GATE GREEN — a mod that throws while the game loads stops the boot as its own loader does, and is named"
else
  echo "[kernel] ❌ M24 GATE RED — fabric $LOG / register-stop $BUILD/gate-m24-registerstop-*.log / healthy $CONTROL"
fi
exit "$FAIL"
