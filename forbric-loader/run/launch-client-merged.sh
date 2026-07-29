#!/usr/bin/env bash
# Launch the MC 26.2 CLIENT through the Forbric loader on the TRI-IN-ONE MERGED patched-Minecraft base
# (patched-mc-merged-26.2.jar — vanilla + BOTH the traditional-MinecraftForge and NeoForge injections; see
# run/build-merged-base.sh), with BOTH runtimes + BOTH bridge mods staged so Fabric + traditional-Forge +
# NeoForge mods can all load in ONE client instance.
#
# This is the client twin of run/launch-server-merged.sh. On the merged client, NeoForge won the byte-merge for
# the client mod-loading path (Main -> Neo begin(); Minecraft.<init> -> Neo setupModResourcePacks + finish; Gui ->
# Neo completeModLoading), so NeoForge + Fabric mods load through NeoForge's genuine client lifecycle. Forge's own
# client begin() was dropped by the merge and is restored by ForbricClientDualLifecycleMixin (fires after Neo's
# finish() in Minecraft.<init>); the merged ctor's surviving Forge completeModLoading() then finalizes it.
#
# Prereqs: a vanilla 26.2 install (jar/json/natives/assets), run/build-merged-base.sh output (merged jar +
# forge-runtime-interop.jar), run/assemble-*-runtime.sh outputs + both bridge jars. Content mods -> $RUNDIR/mods.
#
# Usage: [PATCHED=…] [FORGE_RT=…] [NEO_RT=…] [RUNDIR=…] [FORBRIC_JVM=…] ./launch-client-merged.sh [extra args]
set -euo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"

"$HERE/verify-substrate-patches.sh"

PATCHED="${PATCHED:-$HERE/merged-base/patched-mc-merged-26.2.jar}"
# Cross-runtime-jar interop patch (see run/build-merged-base.sh's RuntimeInteropPatcher step) — falls back to the
# unpatched jar with a warning if the patched copy hasn't been built yet.
FORGE_RT_INTEROP="$HERE/merged-base/forge-runtime-interop.jar"
if [ -f "$FORGE_RT_INTEROP" ]; then FORGE_RT="${FORGE_RT:-$FORGE_RT_INTEROP}";
else FORGE_RT="${FORGE_RT:-$HERE/forge-runtime/forge-runtime.jar}"; echo "[launch] WARN: no interop-patched forge-runtime.jar ($FORGE_RT_INTEROP) — using unpatched" >&2; fi
NEO_RT="${NEO_RT:-$HERE/neoforge-runtime/neoforge-runtime.jar}"
RUNDIR="${RUNDIR:-$PROJECT/run/client-merged}"
NATIVES="${NATIVES_DIR:-$MC/versions/26.2/26.2-natives}"
ASSETS="$MC/assets"
mkdir -p "$RUNDIR/mods"

if [ ! -f "$PATCHED" ]; then echo "merged patched MC jar not found: $PATCHED (run run/build-merged-base.sh)" >&2; exit 2; fi

# 1) Build loader core (parent-loaded, classpath JAR) + the Knot-loaded runtime module.
"$PROJECT/gradlew" -q -p "$PROJECT" jar runtimeJar
CORE="$(ls "$PROJECT"/build/libs/forbric-loader-*.jar | head -1)"

# 2) Stage the Knot-loaded infrastructure: forbricruntime + BOTH ecosystem runtimes (deduped) + BOTH bridge mods.
cp "$(ls "$PROJECT"/build/libs/forbricruntime-*.jar | head -1)" "$RUNDIR/mods/forbricruntime.jar"
if [ -f "$FORGE_RT" ]; then cp "$FORGE_RT" "$RUNDIR/mods/forge-runtime.jar";
else echo "[launch] WARN: Forge runtime jar missing ($FORGE_RT)" >&2; fi
if [ -f "$NEO_RT" ]; then
  # Tri-in-one: both runtimes join ONE shared JPMS layer — dedupe the third-party classes NeoForge's runtime shares
  # with Forge's, or the layer resolve throws a split-package ResolutionException.
  "$HERE/dedupe-runtime-overlap.sh" "$RUNDIR/mods/forge-runtime.jar" "$NEO_RT" "$RUNDIR/mods/neoforge-runtime.jar"
else echo "[launch] WARN: NeoForge runtime jar missing ($NEO_RT)" >&2; fi

# The bridge mods open the Fabric-content registration window from INSIDE each genuine lifecycle. On the client the
# Fabric mains run via the Neo family window (patch 0005 -> ForbricNeoFabricWindow.runClientInitUnlocked), so the
# Forge bridge's window is redundant-but-harmless (ForbricFabricMains.runOnce guards it); stage both to mirror the
# server and to keep each lifecycle's canary/bridge present.
FORGE_BRIDGE="${FORGE_BRIDGE:-$HERE/forge-runtime/forbric-bridge.jar}"
NEO_BRIDGE="${NEO_BRIDGE:-$HERE/neoforge-runtime/forbric-bridge-neoforge.jar}"
if [ -f "$FORGE_BRIDGE" ]; then cp "$FORGE_BRIDGE" "$RUNDIR/mods/forbric-bridge.jar";
else echo "[launch] WARN: Forge bridge mod missing ($FORGE_BRIDGE)" >&2; fi
if [ -f "$NEO_BRIDGE" ]; then cp "$NEO_BRIDGE" "$RUNDIR/mods/forbric-bridge-neoforge.jar";
else echo "[launch] WARN: NeoForge bridge mod missing ($NEO_BRIDGE)" >&2; fi

# 3) Loader dependency jars.
DEPS="$("$PROJECT/gradlew" -q -p "$PROJECT" printRuntimeClasspath | tail -1 | tr ':' '\n' | grep -vE "build/(classes|resources)" | paste -sd: -)"

# 4) Vanilla 26.2 libraries + asset index from the version manifest.
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
ASSET_INDEX="$(python3 -c "import json;print(json.load(open('$MC/versions/26.2/26.2.json'))['assetIndex']['id'])")"

# Merged patched jar LAST so its (Mojmap, Forge+Neo-patched) MC classes win.
CP="$CORE:$DEPS:$VANILLA_CP:$PATCHED"

echo "[launch] rundir=$RUNDIR  assetIndex=$ASSET_INDEX"
echo "[launch] merged patched MC = $PATCHED"
echo "[launch] mods: $(ls "$RUNDIR/mods" | paste -sd' ' -)"
cd "$RUNDIR"
# The merged base always runs BOTH genuine lifecycles, so Fabric "main" entrypoints must be DEFERRED to Forbric's
# registration window rather than run by the vanilla hook. -Dforbric.forgeFamily=both selects tri-in-one.
exec java -XstartOnFirstThread -Djava.awt.headless=true -Djava.library.path="$NATIVES" \
  -Dforbric.runtimeNamespace=named -Dforbric.fabricMainDeferred=true -Dforbric.forgeFamily=both ${FORBRIC_JVM:-} \
  -cp "$CP" net.forbric.loader.impl.launch.ForbricClient \
  --version 26.2-forbric --gameDir "$RUNDIR" --assetsDir "$ASSETS" --assetIndex "$ASSET_INDEX" \
  --accessToken 0 --username ForbricDev --uuid 00000000000000000000000000000000 \
  --userType legacy --versionType release "$@"
