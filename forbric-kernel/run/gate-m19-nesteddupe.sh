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
# Asked from inside the mods, not read off the arbiter's own sentence. "aliased into [FORGE]" would match a line
# this kernel writes about itself and prove only that it wrote it — and it would be wrong to trust here, because
# Decision.aliasesFor(Ecosystem.FORGE) has NO consumer: the identity has to come back to the Forge side through
# ModPresence instead. So the canaries call isLoaded/isModLoaded and the gate reads their answer.
check "the Forge side can still see the library it lost"  "ForbricNestParent\] forge sees forbricnestlib=true"  "$LOG"
check "the Fabric side can see it too"                    "ForbricNestParent\] fabric sees forbricnestlib=true" "$LOG"
check_absent "neither side was told it is absent" "ForbricNestParent\] (forge|fabric) sees forbricnestlib=false" "$LOG"

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

step "the OTHER direction is enforced too (must PASS)"
# With the default preference the Fabric build wins, so only the Forge-side withdrawal is ever exercised — the
# suppression of a losing FABRIC nested jar runs through a different seam (KernelFabricEcosystem.build's register
# loop and its classpath filter) and would stay dead code the gate never touches. -Dforbric.modOwner flips it.
FLIP="$BUILD/gate-m19-flipped.log"
boot "$FLIP" "-Dforbric.modOwner=forbricnestlib=forge"
check "the override reached a nested jar" \
  "nested mod id 'forbricnestlib' is claimed by 2 jars across .* loading forbricnestlib-forge" "$FLIP"
assert_eq "still claimed exactly once" "1" "$(grep -acE '\[ForbricNestLib\] claimed by' "$FLIP")"
check "and by the side the override named" "ForbricNestLib\] claimed by forge" "$FLIP"
check_absent "no duplicate registration either way" "ForbricNestLib\] DUPLICATE registration" "$FLIP"
check_absent "no entrypoint failure either way" "entrypoint of .* failed" "$FLIP"

step "negative control 2: the losing side's visibility really does come from ModPresence"
# Pins the MECHANISM the identity assertion depends on. Decision.aliasesFor(Ecosystem.FORGE) has no consumer, so
# what makes the Forge side still see a library whose jar it lost is the cross-ecosystem presence rewrite — not
# the arbiter's alias. Turn that off and exactly the Forge side must go dark, while Fabric (which owns the
# container) stays true. If both stayed true, the assertion above would be passing for a reason nobody chose.
PRESENCE="$BUILD/gate-m19-nopresence.log"
boot "$PRESENCE" "-Dforbric.crossEcosystemPresence=off"
check "the Forge side loses sight of it without ModPresence" \
  "ForbricNestParent\] forge sees forbricnestlib=false" "$PRESENCE"
check "while the side that owns the container still sees it" \
  "ForbricNestParent\] fabric sees forbricnestlib=true" "$PRESENCE"

step "M19 result"
if [ "${FAIL:-0}" -eq 0 ]; then
  echo "[kernel] ✅ M19 GATE GREEN — a library nested by a Fabric mod and a MinecraftForge mod is constructed once"
else
  echo "[kernel] ❌ M19 GATE RED — see $LOG / $CONTROL / $BUILD/gate-m19-flipped.log / $BUILD/gate-m19-nopresence.log"
  exit 1
fi
