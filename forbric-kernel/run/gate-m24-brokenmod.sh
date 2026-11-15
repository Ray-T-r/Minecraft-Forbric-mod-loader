#!/usr/bin/env bash
# M24 expected-failure policy gate — continuation is explicit and failure evidence never turns green.
#
# WHY THIS EXISTS. Every other gate here asserts that NO mod failed: gate-m4 and gate-m7 each carry a
# check_absent "no @Mod construction failure". That is the right assertion for those gates and it is the exact
# opposite of this one. Nothing anywhere proved the other half.
#
# And the other half is the cost of Forbric's whole posture. Real loaders stop and show an error screen; Forbric
# deliberately loads as much as it can and carries on (DependencyAudit and KernelLifecycle both say so in as many
# words). The price of that trade is that a failure must not spread, and must not vanish — for most of this
# project's life a failed mod was one WARN line in a ten-thousand-line log, and the README's own advice was to
# remove half your mods and try again.
#
# So: stage a mod that fails on purpose next to two healthy ones, and assert all three halves — the healthy mods
# still load under explicit continue, and the broken one remains a confirmed necessary failure in the report.
# Strict policy must stop the same broken pack before Done. A final clean control must start under strict.
#
# The one place carrying on is NOT the posture: NeoForge's registration (owner ruling D82, "stop like native"). When a
# NeoForge mod's RegisterEvent listener throws, or anything else inside NeoForge's per-registry try does, native NeoForge
# 26.2.0.93 rolls every registry back to vanilla and stops the boot — whatever the compatibility policy, since nothing
# could run on what is left. The register-stop canary does both (-Dforbric.registerstop=listener / try) and must stop the
# boot as native does: NeoForge's own roll-back and "Failed to wait for future Registry initialization", the
# crash-*-fml.txt report naming the mod and the cause, "Failed to start the minecraft server", no world, no Fabric main,
# exit status 1 (FML's launcher, finding no server thread). The same jar with =off is in the clean control. Until D82 the
# kernel contained both (that mod lost the registry; a roll-back left the boot running on vanilla registries).
# GATE-PARALLEL: rundirs=server-brokenmod mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m24-brokenmod.log"
CONTROL="$BUILD/gate-m24-control.log"
STRICT="$BUILD/gate-m24-strict.log"
RUNDIR="$KERNEL/run/server-brokenmod"
refuse_shared_rundir "$RUNDIR"
REPORT="$RUNDIR/.forbric-kernel/load-report.txt"
REGSTOP="$KERNEL/run/canary/forbricregisterstop.jar"
STOP_LISTENER="$BUILD/gate-m24-registerstop-listener.log"
STOP_TRY="$BUILD/gate-m24-registerstop-try.log"
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

required_finding() {
  if python3 - "$COMPAT" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)
required = [f for f in report["findings"] if f["confidence"] == "CONFIRMED" and f["required"]]
assert report["confirmedRequired"] == 1 and len(required) == 1, required
finding = required[0]
assert finding["modId"] == "forbricbrokencanary", finding
assert finding["id"] == "initialization:entrypoint:main", finding
assert "ModCatalog.Status.FAILED" in finding["evidence"], finding
assert finding["source"] == "KernelFabricEcosystem main entrypoint", finding
PY
  then
    echo "[kernel] PASS the intentional entrypoint failure remains CONFIRMED and required"
  else
    echo "[kernel] FAIL missing or misclassified necessary initialization evidence"; FAIL=1
  fi
}

stage yes
step "explicit continue with one mod that fails on purpose"
boot "$LOG" continue
check "explicit continue exited normally" "^0$" "$LOG.exit"

step "the broken mod really did fail, and really did run (must PASS)"
# Both halves: a mod that never ran would also produce no failure, and would satisfy every assertion below about
# the OTHER mods while proving nothing at all.
check "the broken mod reached its entrypoint" "ForbricBrokenCanary\] reached onInitialize" "$LOG"
check "and it failed there"                   "main entrypoint of forbricbrokencanary failed" "$LOG"

step "the other mods loaded anyway, and the server still works (this is the whole point)"
check "the Fabric canary still initialised"   "\[ForbricFabricLive\] onInitialize"  "$LOG"
check "the NeoForge canary still constructed" "\[ForbricNeoLive\]"                  "$LOG"
check "server reached Done"                   "Done \("                             "$LOG"
# "Stopping the server" is the /stop command's OWN feedback (commands.stop.stopping in en_us), and the console
# queue is drained only by tickConnection(), which runs only inside tickServer() — so that line cannot exist
# unless the tick loop ran and was still running when this gate fed it "stop" on stdin. Bare "Stopping server"
# is stopServer(), which runServer() reaches on EVERY exit path including ones that never ticked at all (see
# the GATE_PORT note in lib.sh): evidence that shutdown began, not that the server ticked and not that it
# finished — await_server is what fails a server that cannot finish. The alternation covers a base that
# lost en_us and renders the raw key. Dedicated-server gates only: an integrated server prints the bare line
# and never the command's, so this pair must not be copied into a client gate.
check "server ticked (the stop command ran)"  "Stopping the server|commands\.stop\.stopping" "$LOG"
check "shutdown began"                        "Stopping server"                     "$LOG"
check_absent "no crash report was written"    "Preparing crash report"              "$LOG"

step "and the failure is attributed, not merely logged (must PASS)"
# One WARN in a ten-thousand-line log is what this gate exists to stop being the whole answer. The count is
# pinned at exactly one: a report naming every mod would satisfy a looser pattern and would be useless.
check "the load summary names the broken mod" \
  "Forbric/Load\] 1 mod\(s\) did not finish loading: forbricbrokencanary" "$LOG"
check_absent "and does not name the healthy ones" \
  "did not finish loading:.*(forbricfabriclive|forbricneolive)" "$LOG"

if [ -f "$REPORT" ]; then
  echo "[kernel] PASS the load report was written ($REPORT)"
  check "the report names the broken mod"   "forbricbrokencanary"     "$REPORT"
  check "the report names its jar"          "forbricbrokencanary.jar" "$REPORT"
  # The file is in the system language (the log lines above are always English), so on a Chinese host these two
  # read the Chinese wording. KernelLoadReportTest pins each language's words where it names the language.
  check "the report says what happened"     "did not finish loading|没有完成加载"  "$REPORT"
  # The wording carries a claim that is easy to get wrong and expensive when it is: a withdrawn mod's classes ARE
  # loaded and its mixins ARE applied, so telling a player it is absent sends them to reinstall what is there.
  check        "the report says the mod is still partly present" "still partly present|还有一部分留在游戏里" "$REPORT"
  check_absent "and never claims the mod is not running"         "is not running"       "$REPORT"
else
  echo "[kernel] FAIL no load report at $REPORT"; FAIL=1
fi
required_finding
[ ! -f "$COMPAT" ] || cp "$COMPAT" "$BUILD/gate-m24-continue-compatibility.json"

step "strict negative control: the same broken pack stops before a usable world"
stage yes
boot "$STRICT" strict expect-policy-stop
check "strict exited with the policy stop code" "^78$" "$STRICT.exit"
check "strict reached the actual failing entrypoint" "main entrypoint of forbricbrokencanary failed" "$STRICT"
check "strict made an explicit compatibility decision" "Forbric/Compatibility\] launch stopped:" "$STRICT"
check_absent "strict did not expose a running server" "Done \(" "$STRICT"
check_absent "strict did not write a game crash report" "Preparing crash report" "$STRICT"
required_finding
[ ! -f "$COMPAT" ] || cp "$COMPAT" "$BUILD/gate-m24-strict-compatibility.json"

registration_stop() { # registration_stop <log> <mode listener|try>
  local log="$1" mode="$2"
  check "$mode: exited with NeoForge's launcher status 1"        "^1$"                                "$log.exit"
  check "$mode: NeoForge rolled every registry back to vanilla"  "Rolling back to VANILLA state"      "$log"
  check "$mode: NeoForge's ModLoader ended its registry phase"   "Failed to wait for future Registry initialization, 1 errors found" "$log"
  check "$mode: the kernel stops the boot as native does"        "the boot stops here, as native NeoForge stops it" "$log"
  check "$mode: the game's Main gave up on the error"            "Failed to start the minecraft server" "$log"
  check "$mode: and the launcher exits as NeoForge's does"       "the server did not start .*exiting with status 1" "$log"
  local report
  report=$(ls "$RUNDIR"/crash-reports/crash-*-fml.txt 2>/dev/null | head -1)
  if [ -n "$report" ]; then
    echo "[kernel] PASS $mode: NeoForge's mod-loading crash report was written ($(basename "$report"))"
    cp "$report" "$log.crash-fml.txt"
    check "$mode: the crash report says mod loading failed"      "Mod loading has failed"             "$report"
    check "$mode: the crash report names the cause"              "forbricregisterstop's .* fails on purpose" "$report"
  else
    echo "[kernel] FAIL $mode: no crash-reports/crash-*-fml.txt (NeoForge's ServerModLoader writes one)"; FAIL=1
  fi
  check_absent "$mode: no server came up"                        "Done \("                           "$log"
  check_absent "$mode: no Fabric main ran after the roll-back"   "\[ForbricFabricLive\] onInitialize" "$log"
  # Natively freezeData never runs after the roll-back; the clean control below shows the line on a boot that froze.
  check_absent "$mode: the registries were not frozen after the roll-back" "froze the registries NeoForge-first" "$log"
  if [ -d "$RUNDIR/world" ]; then
    echo "[kernel] FAIL $mode: a world directory was created after the stop"; FAIL=1
  else
    echo "[kernel] PASS $mode: no world was created or loaded"
  fi
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

step "negative control: the same instance without the broken mod, the register-stop canary in it with =off"
stage no yes
boot "$CONTROL" strict normal off
check "healthy strict control exited normally" "^0$" "$CONTROL.exit"

check "the control booted"                      "Done \("                    "$CONTROL"
check "the control froze the registries (what the stops above must not do)" "froze the registries NeoForge-first" "$CONTROL"
check "the register-stop canary ran and threw nothing" \
  "\[ForbricRegisterStop\] reached RegisterEvent for minecraft:item, mode=off" "$CONTROL"
check "every mod finished loading"              "Forbric/Load\] every mod finished loading" "$CONTROL"
check_absent "nothing was reported as failing"  "did not finish loading"     "$CONTROL"
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
  echo "[kernel] ✅ M24 EXPECTED-FAILURE POLICY GATE GREEN — continue/strict decisions proved; the broken fixture remains incompatible"
else
  echo "[kernel] ❌ M24 GATE RED — continue $LOG / strict $STRICT / healthy $CONTROL"
fi
exit "$FAIL"
