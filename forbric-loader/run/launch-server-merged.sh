#!/usr/bin/env bash
# Boot the MC 26.2 DEDICATED SERVER through the Forbric loader, from the MERGED patched-Minecraft base
# (patched-mc-merged-26.2.jar — vanilla + BOTH the traditional-MinecraftForge and NeoForge injections; see
# run/build-merged-base.sh), with BOTH runtimes staged so Fabric + traditional-Forge + NeoForge mods can all
# load in ONE instance (the tri-in-one goal). This is the Stage B gate harness — no forge/neo test mods here,
# just the merged base + both runtimes, to prove gates B-3 (boots to Done) and B-4 (dual-runtime coexistence).
#
# Usage: [PATCHED=…] [FORGE_RT=…] [NEO_RT=…] [FORBRIC_JVM=…] ./launch-server-merged.sh [extra java args]
set -euo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"

"$HERE/verify-substrate-patches.sh"

PATCHED="${PATCHED:-$HERE/merged-base/patched-mc-merged-26.2.jar}"
# Cross-runtime-jar interop patch (see run/build-merged-base.sh's RuntimeInteropPatcher step) — falls back to
# the unpatched jar with a warning if the patched copy hasn't been built yet.
FORGE_RT_INTEROP="$HERE/merged-base/forge-runtime-interop.jar"
if [ -f "$FORGE_RT_INTEROP" ]; then FORGE_RT="${FORGE_RT:-$FORGE_RT_INTEROP}";
else FORGE_RT="${FORGE_RT:-$HERE/forge-runtime/forge-runtime.jar}"; echo "[launch] WARN: no interop-patched forge-runtime.jar ($FORGE_RT_INTEROP) — using unpatched" >&2; fi
NEO_RT="${NEO_RT:-$HERE/neoforge-runtime/neoforge-runtime.jar}"
RUNDIR="${RUNDIR:-$PROJECT/run/server-merged}"
mkdir -p "$RUNDIR/mods"

if [ ! -f "$PATCHED" ]; then echo "merged patched MC jar not found: $PATCHED (run run/build-merged-base.sh)" >&2; exit 2; fi

"$PROJECT/gradlew" -q -p "$PROJECT" jar runtimeJar
CORE="$(ls "$PROJECT"/build/libs/forbric-loader-*.jar | head -1)"

cp "$(ls "$PROJECT"/build/libs/forbricruntime-*.jar | head -1)" "$RUNDIR/mods/forbricruntime.jar"
if [ -f "$FORGE_RT" ]; then cp "$FORGE_RT" "$RUNDIR/mods/forge-runtime.jar";
else echo "[launch] WARN: Forge runtime jar missing ($FORGE_RT)" >&2; fi
if [ -f "$NEO_RT" ]; then
  # Tri-in-one: both runtimes join ONE shared JPMS layer (ForbricGameLayer.defineShared) — dedupe the
  # third-party classes NeoForge's runtime shares with Forge's, or the layer resolve throws a split-package
  # ResolutionException.
  "$HERE/dedupe-runtime-overlap.sh" "$RUNDIR/mods/forge-runtime.jar" "$NEO_RT" "$RUNDIR/mods/neoforge-runtime.jar"
else echo "[launch] WARN: NeoForge runtime jar missing ($NEO_RT)" >&2; fi

# The bridge mods (@Mod("forbric") for each family) open the Fabric-content registration window from INSIDE each
# genuine lifecycle's RegisterEvent span — without them, Fabric mods are discovered/wrapped but their "main"
# entrypoints never run (no window opens). The tri-in-one base needs BOTH: Forge's bridge (its window unlocks the
# Forge-wrapped registries for Fabric registration; see ForbricNeoFabricWindow's deferral) and NeoForge's bridge.
FORGE_BRIDGE="${FORGE_BRIDGE:-$HERE/forge-runtime/forbric-bridge.jar}"
NEO_BRIDGE="${NEO_BRIDGE:-$HERE/neoforge-runtime/forbric-bridge-neoforge.jar}"
if [ -f "$FORGE_BRIDGE" ]; then cp "$FORGE_BRIDGE" "$RUNDIR/mods/forbric-bridge.jar";
else echo "[launch] WARN: Forge bridge mod missing ($FORGE_BRIDGE) — Fabric mains won't run" >&2; fi
if [ -f "$NEO_BRIDGE" ]; then cp "$NEO_BRIDGE" "$RUNDIR/mods/forbric-bridge-neoforge.jar";
else echo "[launch] WARN: NeoForge bridge mod missing ($NEO_BRIDGE)" >&2; fi

DEPS="$("$PROJECT/gradlew" -q -p "$PROJECT" printRuntimeClasspath | tail -1 | tr ':' '\n' | grep -vE "build/(classes|resources)" | paste -sd: -)"

VANILLA_CP="$(python3 - "$MC" <<'PY'
import json, os, sys
mc = sys.argv[1]
d = json.load(open(os.path.join(mc, 'versions', '26.2', '26.2.json')))
out = []
for lib in d.get('libraries', []):
    p = lib.get('name', '').split(':')
    if len(p) < 3: continue
    grp, art, ver = p[0].replace('.', '/'), p[1], p[2]
    cls = ('-' + p[3]) if len(p) > 3 else ''
    jar = os.path.join(mc, 'libraries', grp, art, ver, f"{art}-{ver}{cls}.jar")
    if os.path.exists(jar):
        out.append(jar)
print(os.pathsep.join(out))
PY
)"

CP="$CORE:$DEPS:$VANILLA_CP:$PATCHED"

echo "[launch] rundir=$RUNDIR"
echo "[launch] merged patched MC = $PATCHED"
echo "[launch] mods: $(ls "$RUNDIR/mods" | paste -sd' ' -)"
cd "$RUNDIR"
# The merged base always runs BOTH genuine lifecycles, so Fabric "main" entrypoints must be DEFERRED to Forbric's
# registration window (tolerant, registries writable) rather than run by the vanilla hook — else a Fabric mod that
# registers content (game rules, menu networking) aborts server start. -Dforbric.forgeFamily=both selects tri-in-one.
exec java -Djava.awt.headless=true -Dforbric.runtimeNamespace=named \
  -Dforbric.fabricMainDeferred=true -Dforbric.forgeFamily=both ${FORBRIC_JVM:-} \
  -cp "$CP" net.forbric.loader.impl.launch.ForbricServer \
  --gameDir "$RUNDIR" --nogui "$@"
