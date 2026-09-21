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
# Note on coverage: at level-seed=forbrickernel the "no chunk-gen failure" assertion HAD real teeth — verified when
# this gate was written by running it with -Dforbric.packMetadataFailSoft=off, which reproduced 6 dropped packs
# and the chunk-gen exception. Since the NeoForge condition leniency landed the neoforge:overlays section PARSES,
# so fail-soft no longer fires here and that switch no longer reproduces the crash (re-run 2026-09-20: only the
# two config-dependent checks move). The seed stays fixed for the chunk-gen assertion (it needs a ruined portal in
# the generated region); the pack assertions are seed-independent and are what gates the fix.
#
# M8_EXTRA_JVM is how this gate's other teeth are demonstrated. -Dforbric.neoConditions=off: Terralith's data files
# carry `neoforge:conditions` of type terralith:config (registered only on Fabric), so NeoForge's strict codec errors
# on every one. Observed: 'the unknown condition type was tolerated' and 'a foreign skip marker was converted' go
# RED (the strict error propagates instead of the kernel's tolerance lines); the server still reaches Done, because
# PackMetadataFailSoft and the Fabric-dialect section carry the same packs through — the RED is the two lines.
# -Dforbric.overlayConditions=off: the veto and the NOT-mounted checks go RED (recorded below the checks).
# GATE-PARALLEL: rundirs=server-packmeta mem=2000
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
seed_server_properties "$RUNDIR"
# Terralith's own config (schema: ConfigState / ConfigState$Modules), pinned so the gate cannot drift with a
# default flip: vanilla_stone_gen=false is the module under test; intro_message=false is the positive control (its
# `disable.intro_message` overlay is gated by the SAME condition type, inverted, and must mount through the Fabric
# section, which Terralith registers the condition for); recipe_changes stays false because its overlay changes
# the recipe count the 'vanilla datapack fully loaded' check pins.
mkdir -p "$RUNDIR/config"
cat > "$RUNDIR/config/terralith.json" <<'JSON'
{"config_version":1,"modules":{"custom_structures":true,"fog_tweaks":true,"intro_message":false,"skylands":true,"terrain_slabs":true,"vanilla_stone_gen":false,"recipe_changes":false}}
JSON
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot the merged base under the kernel (no compatibility flags)"
: > "$LOG"
( sleep 60; echo stop ) | FORBRIC_JVM="${M8_EXTRA_JVM:-}" RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 180

step "no pack was dropped over a section its loader cannot parse (must PASS)"
check_absent "no pack metadata read failed"   "Failed to read pack .* metadata" "$LOG"
# This used to assert that fail-soft FIRED on the neoforge:overlays section, and that assertion was pinning a
# bug rather than an invariant: the section could not parse because its condition type was unknown to NeoForge,
# and fail-soft dropping it was the symptom. Since the condition leniency landed, the section parses, so
# fail-soft correctly never fires here. What is asserted instead is the mechanism that now handles it.
check        "the unknown condition type was tolerated, not fatal" \
  "Forbric/Conditions\] resource condition 'terralith:config' is not in NeoForge" "$LOG"
# A guest mixin's half-applied pair leaves a bare Object where the merged reader casts to Optional; unrepaired
# that is a ClassCastException and the server never starts. The conversion USED to be asserted to happen here,
# but the only data files with fabric:load_conditions in this set sit under Terralith's condition-gated overlays,
# which the overlay veto now keeps out unless their module is on — so the trigger is gone from this gate (the
# unit tests carry the conversion) and only the cast half is kept below.
# A pack.mcmeta OVERLAY gated by a condition NeoForge cannot judge (terralith:config is registered only on Fabric)
# is VETOED through NeoForge's own drop path instead of mounted: with vanilla_stone_gen=false, the six placed-feature
# overrides under enable.vanilla_stone_gen must NOT enter the world, the pack must be named, and an overlay whose
# Fabric condition says yes must still mount. RED with M8_EXTRA_JVM=-Dforbric.overlayConditions=off (the veto and
# the NOT-mounted line disappear; enable.vanilla_stone_gen mounts as before).
check        "an unjudgeable overlay condition VETOES" \
  "overlay directory 'enable.vanilla_stone_gen' is gated by condition type 'terralith:config'.*NOT mounted" "$LOG"
check        "and the pack is named" \
  "pack 'terralith':.*NOT mounted: \[.*enable.vanilla_stone_gen" "$LOG"
check        "an overlay the Fabric section judges TRUE still mounts" \
  "pack 'terralith': [1-9][0-9]* overlay\(s\) mounted \[.*disable.intro_message" "$LOG"
check_absent "the old mount-anyway warning is gone" "ignoring it MOUNTS the overlay" "$LOG"
check_absent "nothing was cast to Optional and failed" "cannot be cast to class java.util.Optional" "$LOG"
check        "lithostitched's data loaded"    "lithostitched" "$LOG"
check        "terralith's data loaded"        "terralith" "$LOG"

step "the mods actually ran and the server works (must PASS)"
check "both mods' entrypoints invoked"        "invoked main entrypoint of (lithostitched|terralith)" "$LOG" 2
check "vanilla datapack fully loaded"         "Loaded 1585 recipes" "$LOG"
check "server reached Done"                   "Done \(" "$LOG"
# "Stopping the server" is the /stop command's OWN feedback (commands.stop.stopping in en_us), and the console
# queue is drained only by tickConnection(), which runs only inside tickServer() — so that line cannot exist
# unless the tick loop ran and was still running when this gate fed it "stop" on stdin. Bare "Stopping server"
# is stopServer(), which runServer() reaches on EVERY exit path including ones that never ticked at all (see
# the GATE_PORT note in lib.sh): evidence that shutdown began, not that the server ticked and not that it
# finished — await_server is what fails a server that cannot finish. The alternation covers a merged base that
# lost en_us and renders the raw key. Dedicated-server gates only: an integrated server prints the bare line
# and never the command's, so this pair must not be copied into a client gate.
check "server ticked (the stop command ran)"  "Stopping the server|commands\.stop\.stopping" "$LOG"
check "shutdown began"                        "Stopping server" "$LOG"

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
