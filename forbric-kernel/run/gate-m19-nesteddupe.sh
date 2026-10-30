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
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). The contest used to be a Fabric JiJ child against a
# MinecraftForge JarJar child. Forbric now refuses the MinecraftForge parent (a MinecraftForge-only jar), so that pair is
# gone, and with it everything only it ran: the pin that reaches a nested jar, the losing side's ModPresence control,
# and the JarJar range that makes a pin unsatisfiable under strict policy. The NeoForge pair (written in Stage 2.5 as its
# twin, M19_NEO) carries the contest, its negative control and the presence control; the pin and the range/
# unsatisfiable boots now run on that pair (steps after M19_NEO_END). In the Forge pair's place, step 1 proves that a
# refused parent's nested build of the library takes no part at all: under the continue policy the Forge parent is
# refused by name, its forbricnestlib-forge child is never a candidate, and the library is constructed once, by Fabric.
# GATE-PARALLEL: rundirs=server-nesteddupe mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m19-nesteddupe.log"
RUNDIR="$KERNEL/run/server-nesteddupe"
FAB="$KERNEL/run/canary/forbricnestfab.jar"
FORGE="$KERNEL/run/canary/forbricnestforge.jar"
NEOPARENT="$KERNEL/run/canary/forbricnestneo.jar"
mkdir -p "$BUILD"

step "build the three parents"
"$KERNEL/run/build-nested-dupe-canary.sh" >"$BUILD/gate-m19-canary.log" 2>&1
for jar in "$FAB" "$FORGE" "$NEOPARENT"; do
  [ -f "$jar" ] || { echo "[kernel] FAIL canary build (see $BUILD/gate-m19-canary.log): $jar"; exit 1; }
done

# stage <jar>... — a fresh instance holding exactly these parents.
stage() {
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
  rm -f "$RUNDIR/forbric-mods.txt"
  mkdir -p "$RUNDIR/mods"
  cp "$@" "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"
}

boot() { # boot <log> [extra jvm flags]
  local log="$1"; shift
  : > "$log"
  ( stop_when_ready "$log" 30 ) | RUNDIR="$RUNDIR" FORBRIC_JVM="${*:-}" "$KERNEL/run/launch-kernel-server.sh" > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 130
}

step "1. a refused MinecraftForge parent's nested build takes no part in the contest (must PASS)"
stage "$FAB" "$FORGE"
boot "$LOG" "-Dforbric.compatibilityPolicy=continue"
check "the MinecraftForge parent is refused by name" \
  "\[Forbric/Unsupported\] refusing forbricnestforge\.jar \(forbricnestforge\)" "$LOG"
check_absent "its nested build never became a candidate" "forbricnestlib-forge\.jar" "$LOG" '\[Forbric/(Unsupported|MultiLoader|JarJar)\]'
check_absent "so there was no contest over the id" "mod id 'forbricnestlib' claimed by 2 jars" "$LOG"
assert_eq "the canary library was claimed once" "1" "$(grep -acE '\[ForbricNestLib\] claimed by' "$LOG")"
check "by the Fabric build, the only one left" "ForbricNestLib\] claimed by fabric" "$LOG"
check "the Fabric parent came up" "ForbricNestParent\] fabric parent up" "$LOG"
check_absent "nothing of the refused parent ran" "ForbricNestParent\] forge" "$LOG"
check "the server came up under the continue policy" 'Done \(' "$LOG"
check_absent "no crash report" "Preparing crash report" "$LOG"

stage "$FAB" "$NEOPARENT"
# M19_NEO_BEGIN — the same contest on the family that stays.
#
# Until Stage 3 everything above pitted a Fabric JiJ child against a MinecraftForge JarJar child. With that pair gone
# (header), this is the only proof that the arbitration reaches a nested jar of the other ecosystem at all. The
# NeoForge parent nests its own build of the same library through NeoForge's JarJar, exactly as the MinecraftForge
# parent does, so this is the pair that keeps the proof: one construction, both parents up, both sides still seeing
# the library -- and the negative control that the duplicate really happens without arbitration.
step "a Fabric JiJ child against a NeoForge JarJar child: one construction (must PASS)"
NEOPAIR="$BUILD/gate-m19-neo.log"
NEOCONTROL="$BUILD/gate-m19-neo-control.log"
boot "$NEOPAIR"
check "the whole-instance constraints were satisfied" \
  "Forbric/Arbitration\] status=SOLVED;.*confirmed violations=0; unproved contracts=0" "$NEOPAIR"
# NeoForge's build wins here where the Fabric build won against MinecraftForge's: the nested preference puts NeoForge
# first (measured 2026-10-30), so the side that loses its jar and must still see the library is the FABRIC one.
check "both nested candidates participated and the NeoForge build won" \
  "mod id 'forbricnestlib' claimed by 2 jars.*loading forbricnestlib-neoforge.jar \(NEOFORGE\)" "$NEOPAIR"
check "exactly one nested jar was withdrawn" \
  "cross-jar arbitration \(whole-instance\): 1 duplicate mod id\(s\), 1 jar\(s\) suppressed" "$NEOPAIR"
assert_eq "the canary library was claimed once" "1" "$(grep -acE '\[ForbricNestLib\] claimed by' "$NEOPAIR")"
check "by the build that won" "ForbricNestLib\] claimed by neoforge" "$NEOPAIR"
check_absent "no duplicate registration" "ForbricNestLib\] DUPLICATE registration" "$NEOPAIR"
check "the Fabric parent came up"   "ForbricNestParent\] fabric parent up"   "$NEOPAIR"
check "the NeoForge parent came up" "ForbricNestParent\] neoforge parent up" "$NEOPAIR"
check "the NeoForge side can still see the library" "ForbricNestParent\] neoforge sees forbricnestlib=true" "$NEOPAIR"
check "the Fabric side can see it too"              "ForbricNestParent\] fabric sees forbricnestlib=true"   "$NEOPAIR"
check_absent "neither side was told it is absent" "ForbricNestParent\] (neoforge|fabric) sees forbricnestlib=false" "$NEOPAIR"
check_absent "no crash report" "Preparing crash report" "$NEOPAIR"
check_absent "no entrypoint failure" "entrypoint of .* failed" "$NEOPAIR"

step "negative control: with arbitration off, the NeoForge pair duplicates too"
boot "$NEOCONTROL" "-Dforbric.crossJarArbitration=off"
check "the control really disabled arbitration" "cross-jar arbitration DISABLED" "$NEOCONTROL"
check "and the library was then claimed twice, or tried to be" \
  "ForbricNestLib\] DUPLICATE registration|ForbricNestLib\] claimed by" "$NEOCONTROL" 2

step "the Fabric side's sight of the NeoForge-owned library does NOT hang on ModPresence"
# The opposite of the MinecraftForge pair's control 2, and measured, not assumed (2026-10-30): there the losing side
# went dark without ModPresence because Decision.aliasesFor(Ecosystem.FORGE) has no consumer. Here the losing side is
# Fabric, and the arbiter's "aliased into [FABRIC]" DOES have one -- the Fabric loader registers the alias -- so with
# ModPresence off both sides must still see the library. A Fabric side that went dark here would be a lost alias that
# the presence rewrite had been hiding.
NEOPRESENCE="$BUILD/gate-m19-neo-nopresence.log"
boot "$NEOPRESENCE" "-Dforbric.crossEcosystemPresence=off"
check "the arbiter aliased the NeoForge-owned id into Fabric" \
  "mod id 'forbricnestlib' claimed by 2 jars.*loading forbricnestlib-neoforge.jar \(NEOFORGE\), aliased into \[FABRIC\]" "$NEOPRESENCE"
check "the Fabric side still sees it, through that alias" \
  "ForbricNestParent\] fabric sees forbricnestlib=true" "$NEOPRESENCE"
check "and the side that owns the container still sees it" \
  "ForbricNestParent\] neoforge sees forbricnestlib=true" "$NEOPRESENCE"
# M19_NEO_END

step "the OTHER direction is enforced too (must PASS)"
# With the default preference the NeoForge build wins, so only the Fabric-side withdrawal runs above -- the suppression
# of a losing NEOFORGE nested jar runs through a different seam and would stay dead code. -Dforbric.modOwner flips it.
FLIP="$BUILD/gate-m19-flipped.log"
boot "$FLIP" "-Dforbric.modOwner=forbricnestlib=fabric"
check "the override reached a nested jar" \
  "mod id 'forbricnestlib' claimed by 2 jars.*loading forbricnestlib-fabric.jar \(FABRIC\)" "$FLIP"
assert_eq "still claimed exactly once" "1" "$(grep -acE '\[ForbricNestLib\] claimed by' "$FLIP")"
check "and by the side the override named" "ForbricNestLib\] claimed by fabric" "$FLIP"
check "the NeoForge side still sees the library it lost" "ForbricNestParent\] neoforge sees forbricnestlib=true" "$FLIP"
check_absent "no duplicate registration either way" "ForbricNestLib\] DUPLICATE registration" "$FLIP"
check_absent "no entrypoint failure either way" "entrypoint of .* failed" "$FLIP"

step "a JarJar range decides the build; an override that breaks it is refused, not obeyed (must PASS)"
# The NeoForge parent names its own platform artifact (forbricnestlib-neoforge); the Fabric parent JiJ-nests with no
# JarJar metadata. Raise the NeoForge parent's range past the Fabric build's 1.0.0 in the gate-owned copy only: the
# range now excludes the Fabric build, so a pin to it is an unsatisfiable combination that strict policy must refuse
# rather than silently load, and with no pin the range-constrained combination is solved by the NeoForge build.
python3 - "$RUNDIR/mods/forbricnestneo.jar" <<'PY_RANGE'
import json,pathlib,sys,zipfile
path=pathlib.Path(sys.argv[1]);temporary=path.with_suffix('.tmp')
with zipfile.ZipFile(path) as source,zipfile.ZipFile(temporary,'w') as output:
    for entry in source.infolist():
        data=source.read(entry.filename)
        if entry.filename=='META-INF/jarjar/metadata.json':
            metadata=json.loads(data)
            metadata['jars'][0]['version']={'range':'[2.0.0,)','artifactVersion':'2.0.0'}
            data=json.dumps(metadata).encode()
        output.writestr(entry,data)
temporary.replace(path)
PY_RANGE
RANGED="$BUILD/gate-m19-ranged.log"
boot "$RANGED" "-Dforbric.compatibilityPolicy=strict"
check "the range-constrained combination is solved" \
  "Forbric/Arbitration\] status=SOLVED;.*confirmed violations=0; unproved contracts=0" "$RANGED"
check "by the build the range allows" \
  "mod id 'forbricnestlib' claimed by 2 jars.*loading forbricnestlib-neoforge.jar \(NEOFORGE\)" "$RANGED"
assert_eq "still claimed exactly once" "1" "$(grep -acE '\[ForbricNestLib\] claimed by' "$RANGED")"
check "and the server came up under strict policy" 'Done \(' "$RANGED"
CONFLICT="$BUILD/gate-m19-unsatisfiable.log"
boot "$CONFLICT" "-Dforbric.modOwner=forbricnestlib=fabric -Dforbric.compatibilityPolicy=strict"
check "the pinned build outside the range is explicitly unsatisfiable" 'Forbric/Arbitration\] status=UNSATISFIABLE' "$CONFLICT"
check "strict policy refuses the unsatisfied selection" 'launch stopped by compatibility policy|launch stopped: required mod initialization or features are unavailable' "$CONFLICT"
check_absent "a manual preference did not turn the invalid combination into a world" 'Done \(' "$CONFLICT"

step "M19 result"
if [ "${FAIL:-0}" -eq 0 ]; then
  echo "[kernel] ✅ M19 GATE GREEN — a library nested by a Fabric mod and a NeoForge mod is constructed once, a pin and a JarJar range decide between them, and a refused MinecraftForge parent's copy takes no part"
else
  echo "[kernel] ❌ M19 GATE RED — see $LOG / $BUILD/gate-m19-flipped.log / $BUILD/gate-m19-ranged.log / $BUILD/gate-m19-unsatisfiable.log / $BUILD/gate-m19-neo.log / $BUILD/gate-m19-neo-control.log / $BUILD/gate-m19-neo-nopresence.log"
  exit 1
fi
