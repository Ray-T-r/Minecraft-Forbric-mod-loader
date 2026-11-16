#!/usr/bin/env bash
# M30 gate — a mod that loses PART of itself is named, with the reason, on every surface a player has.
#
# WHY THIS EXISTS. gate-m24 proves a mod that FAILS is named. This is the other, larger case: the mod loaded, and
# a piece of it — a mixin, a subscriber, a deferred task, its compile target — did not. Before Workstream J each
# of those was one WARN in a ten-thousand-line log that named a class and not a mod, and load-report.txt was
# written once, at load-complete, so a failure during world creation never reached it at all.
#
# Three attribution canaries beside two healthy ones, so each attribution is asserted on its own. Since D87 ("stop like
# native", lane STOP) two of them no longer lose PART of their mod: a subscriber class that cannot initialise fails its mod's
# construction, and a deferred setup task that throws fails the phase, and native NeoForge 26.2.0.93 stops the boot on both.
# So the mixin canary rides the main boot alone, and each of the other two boots on its own and must stop as native does,
# named by NeoForge's crash report and by Forbric's crash analysis, the kernel's own attribution line still logged (until
# D87 all three rode one boot that reached Done and wrote one load report naming three mods):
#   forbricmixincanary      UnfitMixin (left out by the fit check: its injector must inject — require = 1 — into a method
#                           the game lacks; without that require vanilla lacks it too, and native Mixin, and so the
#                           kernel, would drop just the injector) + ApplyFailingMixin (fails at apply on
#                           RegionFileStorage, a class first loaded at world creation — AFTER load-complete; one handler
#                           fits so the mixin is kept, and the refused one sits at a JUMP the fit check cannot prove
#                           Mixin meets, so it is Mixin that fails on it, not the fit check that takes it out first)
#   forbricsubscribercanary BrokenSubscriber (<clinit> throws) — stops "Mod Construction"
#   forbricabicanary        compiled against net.neoforged.neoforge.event.ForbricVanishedEvent, absent here; its
#                           common-setup deferred task touches it (the bucket_of_frog shape) — stops "Common setup: Deferred
#                           Queue"; the AbiAudit line naming the jar is logged before the stop
#
# STAGES 3 AND 4 OF DROPPING MinecraftForge (what changed and why). Until Stage 3 a fourth fixture rode here, the
# MinecraftForge-only forbricforgecanary, whose listener waited on an event the merged base never posted; DeadEventAudit
# named it. Stage 3 refuses a MinecraftForge-only jar, so that listener could never register and the dead-event row went
# then; DeadEventAudit itself went in Stage 4 C2 (D24). Stage 3 put the fixture into a boot of its own that asserted
# the refusal; Stage 4 deletes that boot (D32/D51: gate-m26 and gate-m29 cover the refusal, on the client and the
# server), and the fixture with it. The three canaries above, their checks and their controls carry the gate.
#
# RED demonstrations (measured on the genuine base 2026-11-09 after the refusal boot went, green 28/28, stage4-c5-g-a.md;
# each switch failed exactly the checks named — 7, 3, 2 — and none of the control's; the counts are the three-canary boot's,
# and lane STOP re-measured the first with the mixin canary alone, stage4-c5-stop.md):
#   M30_EXTRA_JVM=-Dforbric.loadReportRewrite=off     the report is written once at load-complete, before the subscriber
#                                                     registers and the deferred task runs and before the world creates
#                                                     RegionFileStorage: the summary names two mods, not three, no
#                                                     [Forbric/Load] line follows 'Done (', and the file lacks the
#                                                     apply failure, the two-reason row, the broken subscriber, the
#                                                     deferred task and the third 'partly' row (the 2026-09-20 count was
#                                                     3, on four canaries; measured again here, it is 7)
#   M30_EXTRA_JVM=-Dforbric.mixinErrorAttribution=off no 'ApplyFailingMixin failed to apply' anywhere (Mixin's own
#                                                     report still prints; nobody is marked for it): the log line, the
#                                                     report's apply failure and the two-reason row
#   M30_EXTRA_JVM=-Dforbric.abiAudit=off              no AbiAudit line and no 'compiled against a different NeoForge'
#                                                     in the report — forbricabicanary is still named, by its
#                                                     deferred task (J5), which is why the check is on the reason
# GATE-PARALLEL: rundirs=server-attribution mem=1800
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m30-attribution.log"
CONTROL="$BUILD/gate-m30-control.log"
SUBSCRIBER_LOG="$BUILD/gate-m30-subscriber-stop.log"
ABI_LOG="$BUILD/gate-m30-abi-stop.log"
RUNDIR="$KERNEL/run/server-attribution"
refuse_shared_rundir "$RUNDIR"
REPORT="$RUNDIR/.forbric-kernel/load-report.txt"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$NEOLIVE"
MIXIN_CANARY="$KERNEL/run/canary/forbricmixincanary.jar"
SUBSCRIBER_CANARY="$KERNEL/run/canary/forbricsubscribercanary.jar"
ABI_CANARY="$KERNEL/run/canary/forbricabicanary.jar"
CANARIES="$MIXIN_CANARY $SUBSCRIBER_CANARY $ABI_CANARY"
mkdir -p "$BUILD"

step "build three attribution canaries and two healthy ones"
"$KERNEL/run/build-attribution-canaries.sh" >"$BUILD/gate-m30-canary.log" 2>&1
"$KERNEL/run/build-fabric-canary.sh" >>"$BUILD/gate-m30-canary.log" 2>&1
for jar in $CANARIES "$FABRIC" "$NEO"; do
  [ -f "$jar" ] || { echo "[kernel] FAIL missing canary: $jar (see $BUILD/gate-m30-canary.log)"; exit 1; }
done

stage() { # stage [jar...] — the two healthy mods, plus these
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
  mkdir -p "$RUNDIR/mods"
  cp "$FABRIC" "$NEO" "$RUNDIR/mods/"
  [ "$#" -gt 0 ] && cp "$@" "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"
}

boot() { # boot <log> [extra jvm flags]
  local log="$1"; shift
  : > "$log"
  ( stop_when_ready "$log" 30 ) | FORBRIC_JVM="${FORBRIC_JVM:-} ${M30_EXTRA_JVM:-} $*" RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 130
}

stop_boot() { # stop_boot <log> — a boot that must stop by itself: no stdin feeder, exit status kept in <log>.exit
  local log="$1"
  : > "$log"; rm -f "$log.exit" "$RUNDIR"/crash-reports/*.txt
  ( FORBRIC_JVM="${FORBRIC_JVM:-} ${M30_EXTRA_JVM:-}" RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" </dev/null; echo "$?" > "$log.exit" ) > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 130
}

stop_report() { # stop_report <log> <what> — the stop's load report: the game stopped, not the carry-on note (STOP review A-1)
  local log="$1" what="$2"
  rm -f "$log.load-report.txt"
  if [ -f "$REPORT" ]; then
    cp "$REPORT" "$log.load-report.txt"
    echo "[kernel] PASS $what: the stop left a load report ($REPORT)"
    check "$what: it says the game stopped" "The game stopped at this mod's error|游戏在上面这个 mod 的报错处停下了" "$log.load-report.txt"
    check_absent "$what: and not that Forbric carried on" \
      "does not stop at the first mod|不会因为一个 mod 出问题就停下来|depends on the compatibility decision|是否继续由兼容性选择决定" "$log.load-report.txt"
    check "$what: the row says the mod did not finish loading" "did not finish loading —|没有完成加载 —" "$log.load-report.txt"
  else
    echo "[kernel] FAIL $what: no load report at $REPORT after the stop"; FAIL=1
  fi
}

stopped_as_native() { # stopped_as_native <log> <what> <mod id> <NeoForge init task>
  local log="$1" what="$2" id="$3" task="$4" report
  check "$what: exited with NeoForge's launcher status 1"  "^1$"                                        "$log.exit"
  check "$what: NeoForge's ModLoader ended its task"       "Failed to wait for future $task, 1 errors found" "$log"
  check "$what: the kernel stops the boot as native does"  "the boot stops here, as native NeoForge stops it" "$log"
  check_absent "$what: no server came up"                  "Done \("                                   "$log"
  report=$(ls "$RUNDIR"/crash-reports/crash-*-fml.txt 2>/dev/null | head -1)
  if [ -n "$report" ]; then
    cp "$report" "$log.crash-fml.txt"
    echo "[kernel] PASS $what: NeoForge's mod-loading crash report was written ($(basename "$report"))"
  else
    echo "[kernel] FAIL $what: no crash-reports/crash-*-fml.txt"; FAIL=1
  fi
  cp "$RUNDIR/.forbric-kernel/crash-suspects.json" "$log.suspects.json" 2>/dev/null || : > "$log.suspects.json"
  if python3 - "$log.suspects.json" "$id" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as stream:
    report = json.load(stream)
assert [s["modId"] for s in report["suspects"]][:1] == [sys.argv[2]], report["suspects"]
PY
  then echo "[kernel] PASS $what: Forbric's crash analysis names $id"
  else echo "[kernel] FAIL $what: Forbric's crash analysis does not name $id first ($log.suspects.json)"; FAIL=1; fi
}

stage "$MIXIN_CANARY"
step "boot with the mixin canary"
boot "$LOG"

step "the server still works with its two mixin defects aboard"
check "server reached Done"                   "Done \("                             "$LOG"
check "server ticked (the stop command ran)"  "Stopping the server|commands\.stop\.stopping" "$LOG"
check_absent "no crash report was written"    "Preparing crash report"              "$LOG"
check "the Fabric canary still initialised"   "\[ForbricFabricLive\] onInitialize"  "$LOG"
check "the NeoForge canary still constructed" "\[ForbricNeoLive\]"                  "$LOG"

step "each defect happened, and the kernel said so where it happened"
check "the unfit mixin was left out"          "auto-suppressing guest mixin forbricmixincanary \(forbricmixincanary.mixins.json\):UnfitMixin" "$LOG"
check "the apply failure was attributed"      "Forbric/Mixin\] forbricmixincanary \(forbricmixincanary.mixins.json\):forbric.mixincanary.mixin.ApplyFailingMixin failed to apply to net.minecraft.world.level.chunk.storage.RegionFileStorage" "$LOG"

step "the load summary names exactly the one, and follows the world coming up"
check "one mod, by name" \
  "Forbric/Load\] 1 mod\(s\) did not finish loading: forbricmixincanary —" "$LOG"
check_absent "and never the healthy ones" "did not finish loading:.*(forbricfabriclive|forbricneolive)" "$LOG"
DONE_LINE=$(grep -a -n "Done (" "$LOG" | head -1 | cut -d: -f1)
LAST_LOAD_LINE=$(grep -a -n "Forbric/Load\] 1 mod(s) did not finish loading" "$LOG" | tail -1 | cut -d: -f1)
if [ -n "$DONE_LINE" ] && [ -n "$LAST_LOAD_LINE" ] && [ "$LAST_LOAD_LINE" -gt "$DONE_LINE" ]; then
  echo "[kernel] PASS the report was written again after the world came up (line $LAST_LOAD_LINE > Done at $DONE_LINE)"
else
  echo "[kernel] FAIL no load summary after 'Done (' (Done at ${DONE_LINE:-?}, last summary at ${LAST_LOAD_LINE:-?})"; FAIL=1
fi

step "load-report.txt carries every reason"
if [ -f "$REPORT" ]; then
  echo "[kernel] PASS the load report was written ($REPORT)"
  cp "$REPORT" "$BUILD/gate-m30-load-report.txt"   # the runs below wipe the instance's copy
  check "the unfit mixin"          "guest mixin UnfitMixin did not fit the game and was left out" "$REPORT"
  check "the apply failure"        "its mixin forbric.mixincanary.mixin.ApplyFailingMixin failed to apply" "$REPORT"
  check "two reasons on one row"   "left out; its mixin|ApplyFailingMixin.*; guest mixin UnfitMixin"        "$REPORT"
  # The report is written in the system language; both wordings are accepted.
  check "the row says partly"      "partly did not run|有一部分没有跑起来"                                 "$REPORT" 1
  check_absent "and none says the mod is broken" "did not finish loading —|没有完成加载 —"                 "$REPORT"
else
  echo "[kernel] FAIL no load report at $REPORT"; FAIL=1
fi

step "a subscriber class that cannot initialise stops the boot in construction, as native NeoForge does (D87; must PASS)"
stage "$SUBSCRIBER_CANARY"
stop_boot "$SUBSCRIBER_LOG"
check "subscriber: the canary constructed"           "\[ForbricSubscriberCanary\] constructed" "$SUBSCRIBER_LOG"
check "subscriber: the kernel said which class"      "Forbric/EBS\] could not register forbric.subscribercanary.BrokenSubscriber" "$SUBSCRIBER_LOG"
stopped_as_native "$SUBSCRIBER_LOG" subscriber forbricsubscribercanary "Mod Construction"
check "subscriber: NeoForge's issue names the mod"   "Mod loading issue for: forbricsubscribercanary" "$SUBSCRIBER_LOG.crash-fml.txt"
stop_report "$SUBSCRIBER_LOG" subscriber
check "subscriber: the report names the broken subscriber" "its @EventBusSubscriber BrokenSubscriber could not be registered" "$SUBSCRIBER_LOG.load-report.txt"

step "a deferred setup task that throws stops the boot, as native NeoForge does, and the jar's ABI is still named (D87; must PASS)"
stage "$ABI_CANARY"
stop_boot "$ABI_LOG"
check "abi: the canary constructed"                  "\[ForbricAbiCanary\] constructed"    "$ABI_LOG"
check "abi: the abi audit named the jar"             "Forbric/AbiAudit\] forbricabicanary.jar was compiled against a different NeoForge.*ForbricVanishedEvent" "$ABI_LOG"
check "abi: NeoForge named the deferred task's mod"  "Mod 'forbricabicanary' encountered an error in a deferred task" "$ABI_LOG"
stopped_as_native "$ABI_LOG" abi forbricabicanary "Common setup: Deferred Queue"
stop_report "$ABI_LOG" abi
check "abi: the report names the deferred task"       "one of its deferred setup tasks threw during common setup" "$ABI_LOG.load-report.txt"
# The reason it threw, which the deferred task's FAILED mark used to replace (lane STOP review A-2).
check "abi: and the abi finding, kept beside it"      "compiled against a different NeoForge — net.neoforged.neoforge.event.ForbricVanishedEvent is not in this instance" "$ABI_LOG.load-report.txt"

step "negative control: the same instance without the attribution canaries"
stage
boot "$CONTROL"
check "the control booted"                      "Done \("                    "$CONTROL"
check "every mod finished loading"              "Forbric/Load\] every mod finished loading" "$CONTROL"
check_absent "nothing was reported as failing"  "did not finish loading"     "$CONTROL"
if [ -f "$REPORT" ]; then
  echo "[kernel] FAIL a load report was written for a clean boot: $REPORT"; FAIL=1
else
  echo "[kernel] PASS a clean boot writes no load report"
fi

step "M30 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M30 ATTRIBUTION GATE GREEN — a mod that loses part of itself is named with the reason, on the screen, in the file and in the log"
else
  echo "[kernel] ❌ M30 GATE RED — run $LOG / subscriber $SUBSCRIBER_LOG / abi $ABI_LOG / control $CONTROL"
fi
exit "$FAIL"
