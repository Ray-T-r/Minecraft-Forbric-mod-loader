#!/usr/bin/env bash
# M30 gate — a mod that loses PART of itself is named, with the reason, on every surface a player has.
#
# WHY THIS EXISTS. gate-m24 proves a mod that FAILS is named. This is the other, larger case: the mod loaded, and
# a piece of it — a mixin, a subscriber, a deferred task, its compile target — did not. Before Workstream J each
# of those was one WARN in a ten-thousand-line log that named a class and not a mod, and load-report.txt was
# written once, at load-complete, so a failure during world creation never reached it at all.
#
# Three attribution canaries beside two healthy ones, so each attribution is asserted on its own:
#   forbricmixincanary      UnfitMixin (left out by the fit check: its injector must inject — require = 1 — into a method
#                           the game lacks; without that require vanilla lacks it too, and native Mixin, and so the
#                           kernel, would drop just the injector) + ApplyFailingMixin (fails at apply on
#                           RegionFileStorage, a class first loaded at world creation — AFTER load-complete; one handler
#                           fits so the mixin is kept, and the refused one sits at a JUMP the fit check cannot prove
#                           Mixin meets, so it is Mixin that fails on it, not the fit check that takes it out first)
#   forbricsubscribercanary BrokenSubscriber (<clinit> throws)
#   forbricabicanary        compiled against net.neoforged.neoforge.event.ForbricVanishedEvent, absent here; its
#                           common-setup deferred task touches it (the bucket_of_frog shape)
#
# STAGES 3 AND 4 OF DROPPING MinecraftForge (what changed and why). Until Stage 3 a fourth fixture rode here, the
# MinecraftForge-only forbricforgecanary, whose listener waited on an event the merged base never posted; DeadEventAudit
# named it. Stage 3 refuses a MinecraftForge-only jar, so that listener could never register and the dead-event row went
# then; DeadEventAudit itself went in Stage 4 C2 (D24). Stage 3 put the fixture into a boot of its own that asserted
# the refusal; Stage 4 deletes that boot (D32/D51: gate-m26 and gate-m29 cover the refusal, on the client and the
# server), and the fixture with it. The three canaries above, their checks and their controls carry the gate.
#
# RED demonstrations (measured on the genuine base 2026-11-09 after the refusal boot went, green 28/28, stage4-c5-g-a.md;
# each switch fails exactly the checks named — 7, 3, 2 — and none of the control's):
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
RUNDIR="$KERNEL/run/server-attribution"
refuse_shared_rundir "$RUNDIR"
REPORT="$RUNDIR/.forbric-kernel/load-report.txt"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$NEOLIVE"
CANARIES="$KERNEL/run/canary/forbricmixincanary.jar $KERNEL/run/canary/forbricsubscribercanary.jar $KERNEL/run/canary/forbricabicanary.jar"
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

stage $CANARIES
step "boot with the three attribution canaries"
boot "$LOG"

step "the server still works with all three defects aboard"
check "server reached Done"                   "Done \("                             "$LOG"
check "server ticked (the stop command ran)"  "Stopping the server|commands\.stop\.stopping" "$LOG"
check_absent "no crash report was written"    "Preparing crash report"              "$LOG"
check "the Fabric canary still initialised"   "\[ForbricFabricLive\] onInitialize"  "$LOG"
check "the NeoForge canary still constructed" "\[ForbricNeoLive\]"                  "$LOG"
check "every canary constructed"              "\[ForbricSubscriberCanary\] constructed" "$LOG"
check "every canary constructed (abi)"        "\[ForbricAbiCanary\] constructed"    "$LOG"

step "each defect happened, and the kernel said so where it happened"
check "the unfit mixin was left out"          "auto-suppressing guest mixin forbricmixincanary \(forbricmixincanary.mixins.json\):UnfitMixin" "$LOG"
check "the apply failure was attributed"      "Forbric/Mixin\] forbricmixincanary \(forbricmixincanary.mixins.json\):forbric.mixincanary.mixin.ApplyFailingMixin failed to apply to net.minecraft.world.level.chunk.storage.RegionFileStorage" "$LOG"
check "the subscriber could not register"     "Forbric/EBS\] could not register forbric.subscribercanary.BrokenSubscriber" "$LOG"
check "the deferred task named its owner"     "deferred task\(s\) failed during common setup — forbricabicanary" "$LOG"
check "the abi audit named the jar"           "Forbric/AbiAudit\] forbricabicanary.jar was compiled against a different NeoForge.*ForbricVanishedEvent" "$LOG"

step "the load summary names exactly the three, and follows the world coming up"
check "three mods, by name" \
  "Forbric/Load\] 3 mod\(s\) did not finish loading: forbricabicanary, forbricmixincanary, forbricsubscribercanary —" "$LOG"
check_absent "and never the healthy ones" "did not finish loading:.*(forbricfabriclive|forbricneolive)" "$LOG"
DONE_LINE=$(grep -a -n "Done (" "$LOG" | head -1 | cut -d: -f1)
LAST_LOAD_LINE=$(grep -a -n "Forbric/Load\] 3 mod(s) did not finish loading" "$LOG" | tail -1 | cut -d: -f1)
if [ -n "$DONE_LINE" ] && [ -n "$LAST_LOAD_LINE" ] && [ "$LAST_LOAD_LINE" -gt "$DONE_LINE" ]; then
  echo "[kernel] PASS the report was written again after the world came up (line $LAST_LOAD_LINE > Done at $DONE_LINE)"
else
  echo "[kernel] FAIL no load summary after 'Done (' (Done at ${DONE_LINE:-?}, last summary at ${LAST_LOAD_LINE:-?})"; FAIL=1
fi

step "load-report.txt carries every reason"
if [ -f "$REPORT" ]; then
  echo "[kernel] PASS the load report was written ($REPORT)"
  cp "$REPORT" "$BUILD/gate-m30-load-report.txt"   # the control run below wipes the instance's copy
  check "the unfit mixin"          "guest mixin UnfitMixin did not fit the game and was left out" "$REPORT"
  check "the apply failure"        "its mixin forbric.mixincanary.mixin.ApplyFailingMixin failed to apply" "$REPORT"
  check "two reasons on one row"   "left out; its mixin|ApplyFailingMixin.*; guest mixin UnfitMixin"        "$REPORT"
  check "the broken subscriber"    "its @EventBusSubscriber BrokenSubscriber could not be registered"    "$REPORT"
  check "the deferred task"        "one of its deferred setup tasks threw during common setup"          "$REPORT"
  check "the abi finding"          "compiled against a different NeoForge — net.neoforged.neoforge.event.ForbricVanishedEvent is not in this instance" "$REPORT"
  # The report is written in the system language; both wordings are accepted.
  check "every row says partly"    "partly did not run|有一部分没有跑起来"                                 "$REPORT" 3   # one per canary
  check_absent "and none says the mod is broken" "did not finish loading —|没有完成加载 —"                 "$REPORT"
else
  echo "[kernel] FAIL no load report at $REPORT"; FAIL=1
fi

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
  echo "[kernel] ❌ M30 GATE RED — run $LOG / control $CONTROL"
fi
exit "$FAIL"
