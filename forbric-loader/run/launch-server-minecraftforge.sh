#!/usr/bin/env bash
# Boot the MC 26.2 DEDICATED SERVER through the Forbric loader, from the traditional-MinecraftForge-patched,
# Mojmap-named game jar (patched-mc-forge-26.2.jar), loading Forge + Fabric mods together (Mojmap-canonical /
# identity mode). The Forge sibling of run/launch-server-26.2.sh (NeoForge).
#
# 26.2 is Mojmap-native, so Forbric runs identity — no intermediary, no remap (-Dforbric.runtimeNamespace=named).
# The Knot-loaded halves are dropped into mods/:
#   - forbricruntime.jar : the loader's Forge runtime driver (preLaunch entrypoint ForbricMinecraftForgeRuntime).
#   - forge-runtime.jar  : the runtime-supplied Forge runtime (universal + FML/ModLauncher libs), assembled by
#                          run/assemble-minecraftforge-runtime.sh (Forge is loaded at runtime, never bundled).
# The dedicated server returns at the EULA gate BEFORE Bootstrap.bootStrap, so for a headless registration check
# pass FORBRIC_JVM="-Dforbric.headlessRegister=true [-Dforbric.verifyItems=ns:path,...]" — the driver then drives
# Bootstrap + registration itself. We never set eula=true.
#
# Usage: [PATCHED=…] [FORGE_RT=…] [FORBRIC_JVM=…] ./launch-server-minecraftforge.sh [extra java args]
set -euo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"

# Fail fast if the fabric-loader substrate's required local patches drifted or were reverted.
"$HERE/verify-substrate-patches.sh"

PATCHED="${PATCHED:-$HERE/forge-patched/patched-mc-forge-26.2.jar}"
FORGE_RT="${FORGE_RT:-$HERE/forge-runtime/forge-runtime.jar}"
RUNDIR="${RUNDIR:-$PROJECT/run/server-forge-26.2}"
mkdir -p "$RUNDIR/mods"

if [ ! -f "$PATCHED" ]; then echo "patched MC jar not found: $PATCHED (run run/build-patched-forge.sh)" >&2; exit 2; fi

# 1) Build loader core (parent-loaded) + the Knot-loaded runtime module (the Forge driver).
"$PROJECT/gradlew" -q -p "$PROJECT" jar runtimeJar

CORE="$(ls "$PROJECT"/build/libs/forbric-loader-*.jar | head -1)"

# 2) Stage the Knot-loaded Forge infrastructure into mods/ (overwriting prior copies). Test/content mods are
#    placed into mods/ by the caller; these two are always required for a Forge run.
cp "$(ls "$PROJECT"/build/libs/forbricruntime-*.jar | head -1)" "$RUNDIR/mods/forbricruntime.jar"
if [ -f "$FORGE_RT" ]; then cp "$FORGE_RT" "$RUNDIR/mods/forge-runtime.jar";
else echo "[launch] WARN: Forge runtime jar missing ($FORGE_RT) — run run/assemble-minecraftforge-runtime.sh" >&2; fi
# The bridge mod (raw Forge @Mod) opens the Fabric-content window inside the REAL registration span; it is
# only *active* when -Dforbric.fabricMainDeferred=true (real-lifecycle mode), harmless otherwise.
if [ -f "$HERE/forge-runtime/forbric-bridge.jar" ]; then cp "$HERE/forge-runtime/forbric-bridge.jar" "$RUNDIR/mods/forbric-bridge.jar"; fi

# 3) Loader dependency jars (runtime classpath minus the build class/resource dirs — we use the core jar).
DEPS="$("$PROJECT/gradlew" -q -p "$PROJECT" printRuntimeClasspath | tail -1 | tr ':' '\n' | grep -vE "build/(classes|resources)" | paste -sd: -)"

# 4) MC 26.2 library classpath from the vanilla version manifest.
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

# Patched jar LAST so its (Mojmap, Forge-patched) MC classes win over any stale game classes elsewhere.
CP="$CORE:$DEPS:$VANILLA_CP:$PATCHED"

echo "[launch] rundir=$RUNDIR"
echo "[launch] patched MC = $PATCHED"
echo "[launch] mods: $(ls "$RUNDIR/mods" | paste -sd' ' -)"
cd "$RUNDIR"
exec java -Djava.awt.headless=true -Dforbric.runtimeNamespace=named ${FORBRIC_JVM:-} \
  -cp "$CP" net.forbric.loader.impl.launch.ForbricServer \
  --gameDir "$RUNDIR" --nogui "$@"
