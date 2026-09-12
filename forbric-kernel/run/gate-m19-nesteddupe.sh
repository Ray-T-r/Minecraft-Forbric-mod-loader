#!/usr/bin/env bash
# M19 gate — one library, nested by two ecosystems' mods, is initialised ONCE.
#
# WHY THIS EXISTS. Each loader deduplicates only within its own family: KernelFabricLoader.register keeps the
# first Fabric mod id, KernelModLoader the first @Mod. Nobody was checking across, and DuplicateModArbiter — which
# is that judge — only walked mods/. A JarJar/JiJ child is not in mods/; it is extracted into .forbric-kernel/
# afterwards. So a library nested by a Fabric mod AND by a MinecraftForge mod loaded twice and was constructed
# twice.
#
# On a real 26.2 pack that was Xaero's: xaerominimap-fabric nests xaerolib-fabric, xaeroworldmap-forge nests
# xaerolib-forge, both claiming "xaerolib". The second construction threw "Attempted to register a duplicate
# config channel: xaerolib:main" — but only AFTER XaeroLib.<init> had already done INSTANCE = this, so a live
# mixin then called into a half-built object and took the client down on a render frame.
#
# No existing gate pack has that shape: every one of their nested duplicates is same-family (one fabric-api-base
# nested by five Fabric mods), which both loaders already handle. So the arbitration pass is a provable no-op on
# all of them and they would stay green through its removal. This gate stages the shape on purpose.
#
# It asserts BEHAVIOUR, not only the log line: the canary library carries a one-shot registry that throws on a
# second registration, the way xaerolib's config channel does.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m19-nesteddupe.log"
CONTROL="$BUILD/gate-m19-control.log"
RUNDIR="$KERNEL/run/server-nesteddupe"
FAB="$KERNEL/run/canary/forbricnestfab.jar"
FORGE="$KERNEL/run/canary/forbricnestforge.jar"
mkdir -p "$BUILD"

step "build and stage the two parents"
"$KERNEL/run/build-nested-dupe-canary.sh" >"$BUILD/gate-m19-canary.log" 2>&1
for jar in "$FAB" "$FORGE"; do
  [ -f "$jar" ] || { echo "[kernel] FAIL canary build (see $BUILD/gate-m19-canary.log): $jar"; exit 1; }
done

reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
cp "$FAB" "$FORGE" "$RUNDIR/mods/"
seed_server_properties "$RUNDIR"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

boot() { # boot <log> [extra jvm flags]
  local log="$1"; shift
  : > "$log"
  ( sleep 30; echo stop ) | RUNDIR="$RUNDIR" FORBRIC_JVM="${*:-}" "$KERNEL/run/launch-kernel-server.sh" > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 130
}

step "boot with both parents installed"
boot "$LOG"

step "both nested copies were seen, and the contest was decided (must PASS)"
check "the Forge family extracted its nested build" \
  "extracted nested JarJar library forbricnestlib-forge" "$LOG"
check "the contest was found and named as cross-ecosystem" \
  "nested mod id 'forbricnestlib' is claimed by 2 jars across" "$LOG"
check "exactly one nested jar was withdrawn" \
  "nested pass: [0-9]+ nested jar\(s\), 1 mod id\(s\) claimed across ecosystems, 1 nested jar\(s\) suppressed" "$LOG"

step "the library was constructed exactly ONCE (must PASS)"
# The behavioural half. Without arbitration both bootstraps reach the one loaded copy of NestLibRegistry and the
# second throws — which is the defect, reproduced. The count is pinned at exactly 1: a bare `check` for >=1 would
# pass while it was ALSO claimed by the other side.
assert_eq "the canary library was claimed once" "1" "$(grep -acE '\[ForbricNestLib\] claimed by' "$LOG")"
check_absent "no duplicate registration" "ForbricNestLib\] DUPLICATE registration" "$LOG"

step "both parents still ran (must PASS)"
# The point of arbitrating rather than deleting: the side that lost its nested build still has a working library,
# because the winner's copy carries the same shared classes.
check "the Fabric parent came up"  "ForbricNestParent\] fabric parent up" "$LOG"
check "the MinecraftForge parent came up" "ForbricNestParent\] forge parent up" "$LOG"
check "the losing family kept the library's IDENTITY" \
  "presence alias 'forbricnestlib'|aliased into \[(FORGE|FABRIC)\]" "$LOG"

step "nothing else broke (must be ABSENT)"
check_absent "no crash report" "Preparing crash report" "$LOG"
check_absent "no unexpected exception after Done" "Encountered an unexpected exception" "$LOG"
check_absent "no entrypoint failure" "entrypoint of .* failed" "$LOG"

step "negative control: with arbitration off, the duplicate really does happen"
# The assertion that the gate above is measuring something. -Dforbric.crossJarArbitration=off is the documented
# escape hatch, and it takes the nested pass out with it.
boot "$CONTROL" "-Dforbric.crossJarArbitration=off"
check "the control really disabled arbitration" "cross-jar arbitration DISABLED" "$CONTROL"
check "and the library was then claimed twice, or tried to be" \
  "ForbricNestLib\] DUPLICATE registration|ForbricNestLib\] claimed by" "$CONTROL" 2

step "M19 result"
if [ "${FAIL:-0}" -eq 0 ]; then
  echo "[kernel] ✅ M19 GATE GREEN — a library nested by a Fabric mod and a MinecraftForge mod is constructed once"
else
  echo "[kernel] ❌ M19 GATE RED — see $LOG / $CONTROL"
  exit 1
fi
