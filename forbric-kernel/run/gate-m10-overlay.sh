#!/usr/bin/env bash
# M10 gate — two CORRECT pack patches must not collide into a pack that cannot be read.
#
# Tectonic's builtin pack ships one pack.mcmeta carrying a vanilla `overlays`, a `fabric:overlays` AND a
# `neoforge:overlays` — one source tree, every platform's section in one file. On a stock instance exactly one of
# those parsers is live. On Forbric all three are, and the two loader patches meet in Pack.readPackMetadata:
#
#   NeoForge's patch:  overlaySet = new ArrayList<>(overlaySet); overlaySet.addAll(neoOverlays);
#   fabric-api's mixin: @ModifyVariable(method="readPackMetadata", at=@At("STORE"), name="overlaySet")
#                       … and its handler always returns List.copyOf(…)
#
# So NeoForge asks an immutable list to addAll and gets UnsupportedOperationException. readPackMetadata catches
# Exception across its whole body, returns null, and Tectonic's RepositorySource hands that null straight to
# PackRepository.discoverAvailable, which NPEs on it. Neither mod is wrong; the collision exists only on a base
# that carries both patches at once. It cost a world load, and the symptom named Tectonic — a mod that is doing
# nothing unusual — so it is worth a gate of its own.
#
# PackOverlayMutabilityInjector rewrites the in-place merge into a copying one WITHOUT adding or removing an
# `ASTORE overlaySet`, so fabric-api's un-ordinal'd mixin still binds to exactly the same site set and the repair
# is invisible to it. NullPackGuardInjector is the second layer: if that anchor ever stops matching, a null pack
# is skipped with a warning instead of taking the repository down.
#
# WHY NOT FOLD THIS INTO M8. M8's mod set is deliberately pure-Fabric builds, and its chunk-gen tripwire is
# calibrated to level-seed=forbrickernel. Tectonic is a NeoForge build that rewrites overworld noise settings, so
# staging it there would both change the terrain the tripwire depends on and pull lithostitched over to the
# NeoForge side of arbitration, silently gutting M8's Fabric half. Different mod set, different gate.
#
# THE SECOND THING THIS GATE HOLDS. Getting Tectonic's pack read is not the same as getting lithostitched's data
# loaded, and this mod set proved they are different problems. The kernel publishes mods into ModList with
# setLoadedMods, which leaves ModList.modFiles EMPTY, so NeoForge's own mod-pack finder — findResourcePacks() over
# getModFiles() — walks an empty list and not one Forge-family mod's data/ is ever read. Fabric builds were never
# affected, because fabric-api serves those from Fabric's mod list, so every gate that checked datapack content
# happened to miss it. Measured: lithostitched's Fabric and NeoForge builds ship byte-identical data/ trees; at this
# seed with this fabric-api the Fabric build reaches Done and the NeoForge build dies generating the first ruined
# portal on an empty lithostitched:template_list. DataPackHookInjector + KernelDataPacks close that.
#
# The teeth are demonstrable, which is the only reason to trust them:
#   M10_EXTRA_JVM="-Dforbric.packRepair=off"    ./run/gate-m10-overlay.sh   # must go RED (the overlay collision)
#   M10_EXTRA_JVM="-Dforbric.modDataPacks=off"  ./run/gate-m10-overlay.sh   # must go RED (the unserved data/)
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m10-overlay-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-overlay"
PACK="$KERNEL/run/client-merged-pack/mods"

step "stage fabric-api + Tectonic + the lithostitched build Tectonic links against"
# fabric-api is not scenery: it supplies the PackMixin that makes the overlay list immutable, so without it the
# collision cannot happen at all. lithostitched must be the NEOFORGE build — Tectonic's constructor touches
# LithostitchedBuiltInRegistries, and the Fabric build creates those registries with FabricRegistryBuilder, which
# writes the root registry from a NeoForge mod's constructor and fails "Registry is already frozen". That is the
# mod set being wrong, not the kernel: on a real NeoForge instance Tectonic gets the NeoForge build too.
pkill -9 -f "KernelServerLaunch" 2>/dev/null; sleep 1
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
for jar in "$PACK/fabric-api-0.155.2+26.2.jar" \
           "$PACK/lithostitched-1.7.13-neoforge-26.2.jar" \
           "$PACK/tectonic-3.0.27-neoforge-26.2.jar"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] WARN absent: $jar"; fi
done
printf 'level-seed=forbrickernel\n' > "$RUNDIR/server.properties"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot the merged base under the kernel (no compatibility flags)"
: > "$LOG"
( sleep 90; echo stop ) | FORBRIC_JVM="${M10_EXTRA_JVM:-}" RUNDIR="$RUNDIR" \
  "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
# Wait on the PID, not on `pgrep -f KernelServerLaunch`. On macOS pgrep/pkill only inspect the first slice of a
# command line, and this launcher's -cp runs to tens of thousands of characters, so the main class name sits past
# it: the name match never hits. The other server gates get away with it because the server reaches "Stopping
# server" on its own; a server that HANGS — which is exactly what a chunk-gen failure does — left this gate
# waiting forever the first time it went red. Kill the tree we started, by pid.
for i in $(seq 1 240); do
  kill -0 "$BOOTPID" 2>/dev/null || break
  grep -qE 'Stopping server|Failed to start the minecraft server' "$LOG" 2>/dev/null && break
  sleep 1
done
sleep 3
for pid in $(pgrep -P "$BOOTPID" 2>/dev/null) "$BOOTPID"; do kill -9 "$pid" 2>/dev/null; done
wait "$BOOTPID" 2>/dev/null

step "the two patches were reconciled rather than left to collide (must PASS)"
# The repair has to have FIRED. If the anchor ever moves, the injector warns and passes the class through, and
# every absence below would still be green while Tectonic's terrain had quietly gone missing.
check        "overlay merge repaired"          "Forbric/PackRepair\] .*merges overlays without mutating in place" "$LOG"
check_absent "anchor still matches"            "no longer matches the overlay-merge shape" "$LOG"
check        "the unparseable section fail-softs" "Forbric/PackMeta\] pack.mcmeta section .fabric:overlays. would not parse" "$LOG"

step "Tectonic's pack was read, not dropped (must PASS)"
check_absent "no immutable-overlay collision" "UnsupportedOperationException" "$LOG"
check_absent "no pack metadata read failed"   "Failed to read pack .* metadata" "$LOG"
check_absent "no null pack reached the repo"  "streamSelfAndChildren.* because .pack. is null" "$LOG"
check_absent "no pack was skipped as null"    "Forbric/PackRepair\] a RepositorySource emitted a null pack" "$LOG"

step "the Forge-family mods' own data/ reached the server datapack repository (must PASS)"
check        "mod datapacks served"           "Forbric/DataPacks\] served [0-9]+ mod datapack" "$LOG"
check_absent "hook anchor still matches"      "no longer exists — Forge-family mods" "$LOG"
# lithostitched's own mixin redirects vanilla's ruined-portal template selection into TemplateLists.getRandom, which
# calls Optional.get() on the lithostitched:template_list registry. The registry is declared either way; only the
# JSON that FILLS it comes from the mod's data/. So this is the assertion that the data actually arrived.
check_absent "no empty-registry lookup"       "NoSuchElementException: No value present" "$LOG"
check_absent "no unbound holder"              "Trying to access unbound value" "$LOG"

step "the mods actually ran and the server works (must PASS)"
check "tectonic constructed"                  "constructed @Mod tectonic" "$LOG"
check "lithostitched constructed"             "constructed @Mod lithostitched" "$LOG"
check "vanilla datapack fully loaded"         "Loaded 1585 recipes" "$LOG"
check "server reached Done"                   "Done \(" "$LOG"
check "server ticked + shut down cleanly"     "Stopping server" "$LOG"

step "nothing was quietly broken (must be ABSENT)"
check_absent "no registry load failure"       "Failed to load registries due to errors" "$LOG"
check_absent "no chunk-gen failure"           "Exception generating new chunk" "$LOG"
check_absent "no crash report"                "ReportedException" "$LOG"
check_absent "no Fabric entrypoint failed"    "entrypoint of .* failed" "$LOG"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m10-overlay-postdone.log"
check_absent "no post-Done exception"         "Encountered an unexpected exception" "$BUILD/gate-m10-overlay-postdone.log"

step "M10 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M10 GATE GREEN — a pack carrying both loaders' overlays survives the merged base"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
