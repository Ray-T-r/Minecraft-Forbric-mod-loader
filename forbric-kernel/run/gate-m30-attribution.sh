#!/usr/bin/env bash
# M30 gate — a mod that loses PART of itself is named, with the reason, on every surface a player has.
#
# WHY THIS EXISTS. gate-m24 proves a mod that FAILS is named. This is the other, larger case: the mod loaded, and
# a piece of it — a mixin, a subscriber, a deferred task, its compile target — did not. Before Workstream J each
# of those was one WARN in a ten-thousand-line log that named a class and not a mod, and load-report.txt was
# written once, at load-complete, so a failure during world creation never reached it at all.
#
# Three single-defect canaries beside two healthy ones, so each attribution is asserted on its own:
#   forbricmixincanary      UnfitMixin (left out by the fit check) + ApplyFailingMixin (fails at apply on RegionFileStorage,
#                           a class first loaded at world creation — AFTER load-complete)
#   forbricsubscribercanary BrokenSubscriber (<clinit> throws) + TooltipWaiter (NeoForge ItemTooltipEvent, dead here)
#   forbricabicanary        compiled against net.neoforged.neoforge.event.ForbricVanishedEvent, absent here; its
#                           common-setup deferred task touches it (the bucket_of_frog shape)
#
# RED demonstrations (measured 2026-09-20: green 30/30; each switch fails exactly the checks named — 3, 4, 2, 2):
#   M30_EXTRA_JVM=-Dforbric.loadReportRewrite=off     the report is written once at load-complete, so 'ApplyFailingMixin
#                                                     failed to apply' never reaches the file, and no [Forbric/Load]
#                                                     line follows 'Done ('
#   M30_EXTRA_JVM=-Dforbric.mixinErrorAttribution=off no 'ApplyFailingMixin failed to apply' anywhere (Mixin's own
#                                                     report still prints; nobody is marked for it)
#   M30_EXTRA_JVM=-Dforbric.abiAudit=off              no AbiAudit line and no 'compiled against a different NeoForge'
#                                                     in the report — forbricabicanary is still named, by its
#                                                     deferred task (J5), which is why the check is on the reason
#   M30_EXTRA_JVM=-Dforbric.deadEventAudit=off        no DeadEvents line and no 'it listens for ItemTooltipEvent'
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m30-attribution.log"
CONTROL="$BUILD/gate-m30-control.log"
RUNDIR="$KERNEL/run/server-attribution"
REPORT="$RUNDIR/.forbric-kernel/load-report.txt"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$RUN_OLD/neoforge-runtime/forbricneolive.jar"
CANARIES="$KERNEL/run/canary/forbricmixincanary.jar $KERNEL/run/canary/forbricsubscribercanary.jar $KERNEL/run/canary/forbricabicanary.jar $KERNEL/run/canary/forbricforgecanary.jar"
mkdir -p "$BUILD"

step "stage three single-defect canaries and two healthy ones"
"$KERNEL/run/build-attribution-canaries.sh" >"$BUILD/gate-m30-canary.log" 2>&1
"$KERNEL/run/build-fabric-canary.sh" >>"$BUILD/gate-m30-canary.log" 2>&1
for jar in $CANARIES "$FABRIC" "$NEO"; do
  [ -f "$jar" ] || { echo "[kernel] FAIL missing canary: $jar (see $BUILD/gate-m30-canary.log)"; exit 1; }
done

stage() { # stage <include-canaries>
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
  mkdir -p "$RUNDIR/mods"
  cp "$FABRIC" "$NEO" "$RUNDIR/mods/"
  [ "$1" = "yes" ] && cp $CANARIES "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"
}

boot() { # boot <log>
  local log="$1"
  : > "$log"
  ( sleep 30; echo stop ) | FORBRIC_JVM="${FORBRIC_JVM:-} ${M30_EXTRA_JVM:-}" RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 130
}

stage yes
step "boot with the three canaries"
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
# A MinecraftForge canary, because NeoForge's side of the ledger no longer has a dead row: ItemTooltipEvent was
# the last one and it is bridged now. FluidPlaceBlockEvent still is — the merged LiquidBlock asks only NeoForge's
# hook — so it is what proves the kernel still NAMES a mod waiting on something nothing posts.
check "the dead event named its listener"     "Forbric/DeadEvents\].*FluidPlaceBlockEvent.*forbricforgecanary" "$LOG"
check "the deferred task named its owner"     "deferred task\(s\) failed during common setup — forbricabicanary" "$LOG"
check "the abi audit named the jar"           "Forbric/AbiAudit\] forbricabicanary.jar was compiled against a different NeoForge.*ForbricVanishedEvent" "$LOG"

step "the load summary names exactly the four, and follows the world coming up"
check "four mods, by name" \
  "Forbric/Load\] 4 mod\(s\) did not finish loading: forbricabicanary, forbricforgecanary, forbricmixincanary, forbricsubscribercanary" "$LOG"
check_absent "and never the healthy ones" "did not finish loading:.*(forbricfabriclive|forbricneolive)" "$LOG"
DONE_LINE=$(grep -a -n "Done (" "$LOG" | head -1 | cut -d: -f1)
LAST_LOAD_LINE=$(grep -a -n "Forbric/Load\] 4 mod(s) did not finish loading" "$LOG" | tail -1 | cut -d: -f1)
if [ -n "$DONE_LINE" ] && [ -n "$LAST_LOAD_LINE" ] && [ "$LAST_LOAD_LINE" -gt "$DONE_LINE" ]; then
  echo "[kernel] PASS the report was written again after the world came up (line $LAST_LOAD_LINE > Done at $DONE_LINE)"
else
  echo "[kernel] FAIL no load summary after 'Done (' (Done at ${DONE_LINE:-?}, last summary at ${LAST_LOAD_LINE:-?})"; FAIL=1
fi

step "load-report.txt carries every reason"
if [ -f "$REPORT" ]; then
  echo "[kernel] PASS the load report was written ($REPORT)"
  cp "$REPORT" "$BUILD/gate-m30-load-report.txt"   # the control run below wipes the instance's copy
  check "the unfit mixin"          "guest mixin UnfitMixin did not fit the merged game and was left out" "$REPORT"
  check "the apply failure"        "its mixin forbric.mixincanary.mixin.ApplyFailingMixin failed to apply" "$REPORT"
  check "two reasons on one row"   "left out; its mixin"                                                  "$REPORT"
  check "the broken subscriber"    "its @EventBusSubscriber BrokenSubscriber could not be registered"    "$REPORT"
  check "the dead event"           "it listens for BlockEvent.FluidPlaceBlockEvent, which this merged game never posts" "$REPORT"
  check "the deferred task"        "one of its deferred setup tasks threw during common setup"          "$REPORT"
  check "the abi finding"          "compiled against a different NeoForge — net.neoforged.neoforge.event.ForbricVanishedEvent is not in this instance" "$REPORT"
  # The report is written in the system language; both wordings are accepted.
  check "every row says partly"    "partly did not run|有一部分没有跑起来"                                 "$REPORT" 3
  check_absent "and none says the mod is broken" "did not finish loading —|没有完成加载 —"                 "$REPORT"
else
  echo "[kernel] FAIL no load report at $REPORT"; FAIL=1
fi

step "negative control: the same instance without the three canaries"
stage no
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
