#!/usr/bin/env bash
# M18 gate — the three ecosystems can SEE each other.
#
# WHY THIS EXISTS. Every mod that integrates with another mod starts by asking its own loader "is X installed",
# and branches on the answer. In this instance each loader keeps its own list, so that question used to be
# answered per-family: a NeoForge mod was told a Fabric Sodium was absent, and a Fabric mod was told a NeoForge
# JEI was absent. The damage is not a missing name in a menu — it is the branch. Physics Mod asks exactly this
# (LoadingModList.getModFileById("sodium")), was told no while Sodium really had replaced the chunk pipeline, and
# went on rendering its debris and ragdolls into a path Sodium no longer runs: loaded, mixins applied, no error
# anywhere, and nothing on screen.
#
# So this gate asserts the ANSWER, from inside real mods, in both directions, in one instance — and runs the same
# instance again with -Dforbric.crossEcosystemPresence=off as the negative control, because an assertion that
# something is true is worth little unless the run that should make it false does.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m18-presence.log"
CONTROL="$BUILD/gate-m18-control.log"
RUNDIR="$KERNEL/run/server-presence"
FABRIC="$KERNEL/run/canary/forbricfabriclive.jar"
FORGE="$RUN_OLD/forge-runtime/forbriclive.jar"
NEO="$RUN_OLD/neoforge-runtime/forbricneolive.jar"
mkdir -p "$BUILD"

step "stage one canary per ecosystem"
"$KERNEL/run/build-fabric-canary.sh" >"$BUILD/gate-m18-canary.log" 2>&1
[ -f "$FABRIC" ] || { echo "[kernel] FAIL Fabric canary build (see $BUILD/gate-m18-canary.log)"; exit 1; }

reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
miss=0
for jar in "$FABRIC" "$FORGE" "$NEO"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] MISSING: $jar"; miss=1; fi
done
[ "$miss" -eq 0 ] || { echo "[kernel] FAIL a canary jar is missing"; exit 1; }
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

step "boot all three ecosystems in one instance"
boot "$LOG"

step "every ecosystem's mods reach every ecosystem's list (must PASS)"
check "the NeoForge list carries the Fabric mods for presence" \
  "seeded NeoForge LoadingModList with [0-9]+ mod\(s\) \([0-9]+ Forge-family, [1-9][0-9]* Fabric for presence\)" "$LOG"
check "the Fabric side carries the Forge-family mods" \
  "[1-9][0-9]* Forge-family mod\(s\) registered for presence only" "$LOG"
check "both families' isLoaded gained the cross-ecosystem answer" \
  "ModList.isLoaded now answers for the other ecosystems" "$LOG" 2

step "and the mods themselves get the right answer (must PASS — this is the whole point)"
# Asked through the three APIs a real mod actually uses: FabricLoader.isModLoaded, ModList.isLoaded (the Forge
# families' presence check) and LoadingModList.getModFileById (what Physics Mod reads).
check "a Fabric mod sees the MinecraftForge mod"  "\[ForbricFabricLive\] foreign .*forbriclive=true"    "$LOG"
check "a Fabric mod sees the NeoForge mod"        "\[ForbricFabricLive\] foreign .*forbricneolive=true" "$LOG"
check "a NeoForge mod sees the Fabric mod (ModList)" \
  "\[ForbricNeoLive\] foreign forbricfabriclive isLoaded=true" "$LOG"
check "a NeoForge mod sees the Fabric mod (LoadingModList)" \
  "\[ForbricNeoLive\] foreign forbricfabriclive .*modFile=true" "$LOG"
check "a MinecraftForge mod sees the Fabric mod"  "\[ForbricLive\] foreign forbricfabriclive isLoaded=true" "$LOG"

# A3: ModList.get().getModFileById(MODID).getFile() — the lookup a mod makes about ITSELF. The kernel filled that
# map for the NeoForge baseline alone, so every kernel-loaded mod got null and the next dereference NPE'd.
check "a NeoForge mod can resolve its OWN mod file by id" \
  "\[ForbricNeoLive\] getModFileById\(self\) answered, file=forbricneolive.jar id=forbricneolive type=MOD" "$LOG"
check_absent "getModFileById did not answer null for a loaded mod" \
  "\[ForbricNeoLive\] getModFileById\(self\) (returned NULL|FAILED)" "$LOG"

# A4: the SPI objects were built from an id and a jar path, so every mod reported version 0.0 and its id as its
# display name. The values are asserted literally — the mod declares both in its own metadata file, and a
# regression here reads as "0.0"/"forbricneolive", which is exactly what a count-free pattern would still match.
check "a mod's own version and display name come from its metadata" \
  "\[ForbricNeoLive\] own metadata: name=Forbric NeoForge Live Canary version=1.0.0" "$LOG"

# A16: classes were defined with NO protection domain, so getCodeSource() answered null for every mod. A mod that
# ships data beside its own classes reads that to find its own jar (JourneyMap, spark, Sodium's startup checks),
# and a null is an NPE on the mod's own line. exists=true is the point: a code source naming a file that is not
# there would satisfy a weaker pattern.
check "a mod can find the jar it was loaded from" \
  "\[ForbricNeoLive\] own code source resolves to a real file: forbricneolive.jar exists=true" "$LOG"

step "nothing quietly broken by the wider lists (must be ABSENT)"
check_absent "no NoClassDefFound"       "NoClassDefFoundError"        "$LOG"
check_absent "no entrypoint failed"     "entrypoint of .* failed"     "$LOG"
check_absent "no genuine FancyModLoader" "gatherAndInitializeMods"    "$LOG"
check "server still reached Done"       "Done \("                     "$LOG"

step "negative control: the same instance with -Dforbric.crossEcosystemPresence=off"
# Without this the assertions above could be passing for a reason that has nothing to do with the registry —
# e.g. a canary printing a hard-coded true, or the ids colliding inside one family's own list.
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/.forbric-kernel" 2>/dev/null
boot "$CONTROL" "-Dforbric.crossEcosystemPresence=off"

check "the control booted"                       "Done \("                                        "$CONTROL"
check "a Fabric mod is told the Forge mod is absent"  "\[ForbricFabricLive\] foreign .*forbriclive=false"  "$CONTROL"
check "a NeoForge mod is told the Fabric mod is absent" \
  "\[ForbricNeoLive\] foreign forbricfabriclive isLoaded=false" "$CONTROL"
# Not check_absent on the phrase: the line is still printed, with a zero in it. The zero is the assertion.
check "and no Fabric mod is seeded into the NeoForge list" \
  "Forge-family, 0 Fabric for presence\)" "$CONTROL"
check_absent "and the Fabric side registers none of theirs either" \
  "Forge-family mod\(s\) registered for presence only" "$CONTROL"

step "M18 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M18 PRESENCE GATE GREEN — a mod of any ecosystem gets the truth about the other two, and the switch still turns it off"
else
  echo "[kernel] ❌ M18 GATE RED — run $LOG / control $CONTROL"
fi
exit "$FAIL"
