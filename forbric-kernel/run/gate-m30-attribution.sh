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
# and, in a boot of its own, the MinecraftForge fixture forbricforgecanary (FluidSourceWaiter, a listener for Forge's
# CreateFluidSourceEvent), which Forbric now refuses.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). Stage 3 refuses a MinecraftForge-only jar, and
# forbricforgecanary is one: a dedicated server stops on the refusal under the default (strict) policy, so with it
# staged the attribution boot never reached Done and almost every check went red. That fixture carried the gate's
# dead-event row: its FluidSourceWaiter waited on Forge's CreateFluidSourceEvent, which the merged base never posts,
# and DeadEventAudit named it ("the dead event named its listener" in the log, "the dead event" in load-report.txt,
# both turned red by -Dforbric.deadEventAudit=off). The owner had decided (2026-10-30) that the row stays on this
# fixture until Stage 4 deletes it together with DeadEventAudit, and declined moving it to a NeoForge canary. That
# decision cannot hold through Stage 3: no loader loads any of a refused jar, so its listener is never registered
# and there is nothing left for the audit to name. The row therefore goes in Stage 3 -- its two checks and
# the -Dforbric.deadEventAudit control -- and it is NOT moved to NeoForge; DeadEventAudit itself still goes in Stage 4.
# Nothing else here was the fixture's: the other three canaries (a Fabric mixin canary, two NeoForge canaries), their
# checks and their controls carry the gate unchanged, the summary and the report now name exactly those three, and the
# fixture leaves the boot that must reach Done. In its place a third boot stages it beside the healthy pair under
# -Dforbric.compatibilityPolicy=continue and asserts the refusal: named in the log, counted in the load summary, and
# reported in load-report.txt as not loaded because MinecraftForge is unsupported -- never as a mod that partly ran.
#
# RED demonstrations (re-measured 2026-10-30 after the change above: green 38/38; each switch fails exactly the checks
# named — 7, 3, 2 — and none of the refusal boot's or the control's):
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
REFUSED="$BUILD/gate-m30-refused.log"
RUNDIR="$KERNEL/run/server-attribution"
REPORT="$RUNDIR/.forbric-kernel/load-report.txt"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
NEO="$NEOLIVE"
CANARIES="$KERNEL/run/canary/forbricmixincanary.jar $KERNEL/run/canary/forbricsubscribercanary.jar $KERNEL/run/canary/forbricabicanary.jar"
FORGEFIXTURE="$KERNEL/run/canary/forbricforgecanary.jar"   # refused under Stage 3: only the refusal boot stages it
mkdir -p "$BUILD"

step "build three attribution canaries, the refused MinecraftForge fixture and two healthy ones"
"$KERNEL/run/build-attribution-canaries.sh" >"$BUILD/gate-m30-canary.log" 2>&1
"$KERNEL/run/build-fabric-canary.sh" >>"$BUILD/gate-m30-canary.log" 2>&1
for jar in $CANARIES "$FORGEFIXTURE" "$FABRIC" "$NEO"; do
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
  check "the unfit mixin"          "guest mixin UnfitMixin did not fit the merged game and was left out" "$REPORT"
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

step "the MinecraftForge fixture is refused by name, and only refused (must PASS)"
# Stage 3: the fixture that used to carry the dead-event row. Under the default policy a dedicated server stops on the
# refusal (exit 78), so this boot asks to continue: the refusal must be named and reported, and the healthy pair must
# still come up beside it.
stage "$FORGEFIXTURE"
boot "$REFUSED" "-Dforbric.compatibilityPolicy=continue"
check "the fixture is refused by name" \
  "\[Forbric/Unsupported\] refusing forbricforgecanary\.jar \(forbricforgecanary\): it is a MinecraftForge mod with no usable Fabric or NeoForge half" "$REFUSED"
check "the server came up under the continue policy" "Done \("                     "$REFUSED"
check "the healthy Fabric mod still initialised"   "\[ForbricFabricLive\] onInitialize" "$REFUSED"
check "the healthy NeoForge mod still constructed" "\[ForbricNeoLive\]"                  "$REFUSED"
check_absent "no crash report was written"         "Preparing crash report"              "$REFUSED"
check "the load summary counts it, and only it" \
  "Forbric/Load\] 1 mod\(s\) did not finish loading: forbricforgecanary —" "$REFUSED"
if [ -f "$REPORT" ]; then
  cp "$REPORT" "$BUILD/gate-m30-refused-report.txt"
  # Written in the system language like the rows above; the English and Chinese wordings are accepted.
  check "the report says why it was not loaded" \
    "not loaded: Forbric no longer supports MinecraftForge|没有加载：Forbric 已不再支持 MinecraftForge" "$REPORT"
  check "and names the jar"                      "^    forbricforgecanary\.jar$"                      "$REPORT"
  check_absent "and never as a mod that partly ran" "partly did not run|有一部分没有跑起来"             "$REPORT"
else
  echo "[kernel] FAIL no load report for the refused fixture at $REPORT"; FAIL=1
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
