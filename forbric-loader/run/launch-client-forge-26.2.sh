#!/usr/bin/env bash
# Launch the MC 26.2 CLIENT through the Forbric loader on the traditional-MinecraftForge-patched, Mojmap-named
# game jar — Fabric mods + real Forge mods together, REAL lifecycle: the patched Minecraft constructor calls
# Forge's own ClientModLoader (construct -> config -> registries -> setups -> complete); the Forbric bridge mod
# opens the Fabric-content window inside the genuine registration span (-Dforbric.fabricMainDeferred=true).
#
# Prereqs: a vanilla 26.2 install (jar/json/natives/assets), run/build-patched-forge.sh and
# run/assemble-minecraftforge-runtime.sh outputs. Content mods go into $RUNDIR/mods (raw Forge jars fine).
#
# Usage: [PATCHED=…] [FORGE_RT=…] [RUNDIR=…] [FORBRIC_JVM=…] ./launch-client-forge-26.2.sh [extra args]
set -euo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"

"$HERE/verify-substrate-patches.sh"

PATCHED="${PATCHED:-$HERE/forge-patched/patched-mc-forge-26.2.jar}"
FORGE_RT="${FORGE_RT:-$HERE/forge-runtime/forge-runtime.jar}"
RUNDIR="${RUNDIR:-$PROJECT/run/client-forge-26.2}"
NATIVES="${NATIVES_DIR:-$MC/versions/26.2/26.2-natives}"
ASSETS="$MC/assets"
mkdir -p "$RUNDIR/mods"

if [ ! -f "$PATCHED" ]; then echo "patched MC jar not found: $PATCHED (run run/build-patched-forge.sh)" >&2; exit 2; fi

# 1) Build loader core (parent-loaded, classpath JAR) + the Knot-loaded runtime module.
"$PROJECT/gradlew" -q -p "$PROJECT" jar runtimeJar
CORE="$(ls "$PROJECT"/build/libs/forbric-loader-*.jar | head -1)"

# 2) Stage the Knot-loaded Forge infrastructure (content mods are placed in mods/ by the caller).
cp "$(ls "$PROJECT"/build/libs/forbricruntime-*.jar | head -1)" "$RUNDIR/mods/forbricruntime.jar"
cp "$FORGE_RT" "$RUNDIR/mods/forge-runtime.jar"
[ -f "$HERE/forge-runtime/forbric-bridge.jar" ] && cp "$HERE/forge-runtime/forbric-bridge.jar" "$RUNDIR/mods/forbric-bridge.jar"

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

# Patched jar LAST so its (Mojmap, Forge-patched) MC classes win.
CP="$CORE:$DEPS:$VANILLA_CP:$PATCHED"

echo "[launch] rundir=$RUNDIR  assetIndex=$ASSET_INDEX"
echo "[launch] mods: $(ls "$RUNDIR/mods" | paste -sd' ' -)"
cd "$RUNDIR"
exec java -XstartOnFirstThread -Djava.awt.headless=true -Djava.library.path="$NATIVES" \
  -Dforbric.runtimeNamespace=named -Dforbric.fabricMainDeferred=true ${FORBRIC_JVM:-} \
  -cp "$CP" net.forbric.loader.impl.launch.ForbricClient \
  --version 26.2-forbric --gameDir "$RUNDIR" --assetsDir "$ASSETS" --assetIndex "$ASSET_INDEX" \
  --accessToken 0 --username ForbricDev --uuid 00000000000000000000000000000000 \
  --userType legacy --versionType release "$@"
