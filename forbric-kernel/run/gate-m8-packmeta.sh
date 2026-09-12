#!/usr/bin/env bash
# M8 gate — a multiloader mod's pack.mcmeta must not delete its own resource pack on a tri-ecosystem instance.
#
# lithostitched and Terralith are FABRIC-ONLY builds whose pack.mcmeta ships both a fabric:overlays and a
# neoforge:overlays section (one source tree, every platform's section in one file). On stock Fabric the NeoForge
# section is never read. On Forbric all three parsers are live, so NeoForge's reads it, tries to resolve
# "lithostitched:breaks_seed_parity" in the neoforge:condition_codecs registry — where the Fabric build never
# registered it — and throws. Pack.readPackMetadata catches Exception across its whole body and returns null, so
# that ONE optional section silently dropped BOTH packs: lithostitched's template_list data never loaded,
# TemplateLists.getRandom called Optional.get() on an empty registry, and ruined-portal chunk generation killed the
# client about twelve seconds after the player joined.
#
# PackMetadataFailSoftInjector + KernelPackMetadata make an unparseable namespaced section read as ABSENT, which is
# exactly what a loader without a parser for it does. This gate is server-side because the crash was: the pack read
# and the worldgen that depended on it both run on the integrated server.
#
# Note on coverage: at level-seed=forbrickernel the "no chunk-gen failure" assertion has REAL teeth — verified by
# running this gate with -Dforbric.packMetadataFailSoft=off, which reproduces 6 dropped packs and the chunk-gen
# exception. Change the seed and that stops being true (it needs a ruined portal in the generated region), so keep
# the seed fixed; the four pack assertions above it are seed-independent and are what primarily gates the fix.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m8-packmeta-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-packmeta"
MODS="$KERNEL/run/client-kernel/mods"

step "stage fabric-api + the two multiloader-mcmeta mods"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
for jar in "$MODS/fabric-api-0.155.2+26.2.jar" \
           "$MODS/lithostitched-1.7.13-fabric-26.2.jar" \
           "$MODS/Terralith_26.2_v2.6.4.jar"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] WARN absent: $jar"; fi
done
# Fixed seed so the terrain — and therefore the tripwire's coverage — is reproducible run to run.
printf 'level-seed=forbrickernel\n' > "$RUNDIR/server.properties"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot the merged base under the kernel (no compatibility flags)"
: > "$LOG"
( sleep 60; echo stop ) | RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 180

step "no pack was dropped over a section its loader cannot parse (must PASS)"
check_absent "no pack metadata read failed"   "Failed to read pack .* metadata" "$LOG"
check        "fail-soft fired on the NeoForge section" "Forbric/PackMeta.*neoforge:overlays" "$LOG"
check        "lithostitched's data loaded"    "lithostitched" "$LOG"
check        "terralith's data loaded"        "terralith" "$LOG"

step "the mods actually ran and the server works (must PASS)"
check "both mods' entrypoints invoked"        "invoked main entrypoint of (lithostitched|terralith)" "$LOG" 2
check "vanilla datapack fully loaded"         "Loaded 1585 recipes" "$LOG"
check "server reached Done"                   "Done \(" "$LOG"
check "server ticked + shut down cleanly"     "Stopping server" "$LOG"

step "nothing was quietly broken (must be ABSENT)"
# The crash this gate exists for. A tripwire — see the header note on its coverage.
check_absent "no chunk-gen failure"           "Exception generating new chunk" "$LOG"
check_absent "no empty-registry lookup"       "NoSuchElementException: No value present" "$LOG"
check_absent "no crash report"                "ReportedException" "$LOG"
check_absent "no empty dynamic registries"    "Registry must be non-empty" "$LOG"
check_absent "no Fabric entrypoint failed"    "entrypoint of .* failed" "$LOG"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m8-packmeta-postdone.log"
check_absent "no post-Done exception"         "Encountered an unexpected exception" "$BUILD/gate-m8-packmeta-postdone.log"

step "M8 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M8 GATE GREEN — a multiloader pack.mcmeta no longer costs the mod its whole resource pack"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
