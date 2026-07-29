#!/usr/bin/env bash
# Boot the MC 26.2 DEDICATED SERVER through the Forbric loader, from the NeoForge-patched, Mojmap-named
# game jar (patched-mc-26.2.jar), loading NeoForge + Fabric mods together (Mojmap-canonical / identity mode).
#
# 26.2 is Mojmap-native (the vanilla jar is already deobfuscated), so Forbric runs identity — no intermediary,
# no remap (-Dforbric.runtimeNamespace=named). The Knot-loaded halves are dropped into mods/:
#   - forbricruntime.jar          : the loader's NeoForge runtime driver (preLaunch entrypoint).
#   - neoforge-runtime.jar        : the runtime-supplied NeoForge runtime (universal + FML/bus libs),
#                                   assembled by run/assemble-neoforge-runtime.sh (NeoForge is loaded at
#                                   runtime, never bundled in loader source).
# The dedicated server returns at the EULA gate BEFORE Bootstrap.bootStrap, so for a headless registration
# check pass FORBRIC_JVM="-Dforbric.headlessRegister=true [-Dforbric.verifyItems=ns:path,...]" — the driver
# then drives Bootstrap + registration itself. We never set eula=true.
#
# Usage: [PATCHED=…] [NFRT_JAR=…] [FORBRIC_JVM=…] ./launch-server-26.2.sh [extra java args]
set -euo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"

PATCHED="${PATCHED:-$HERE/neoforge-patched/patched-mc-neoforge-26.2.jar}"
NFRT_JAR="${NFRT_JAR:-$HERE/neoforge-runtime/neoforge-runtime.jar}"
RUNDIR="${RUNDIR:-$PROJECT/run/server-26.2}"
mkdir -p "$RUNDIR/mods"

if [ ! -f "$PATCHED" ]; then echo "patched MC jar not found: $PATCHED" >&2; exit 2; fi

# 1) Build loader core (parent-loaded) + the Knot-loaded runtime module (the NeoForge driver).
"$PROJECT/gradlew" -q -p "$PROJECT" jar runtimeJar
CORE="$(ls "$PROJECT"/build/libs/forbric-loader-*.jar | head -1)"

# 2) Stage the Knot-loaded NeoForge infrastructure into mods/ (overwriting prior copies). Test/content mods
#    are placed into mods/ by the caller; these two are always required for a NeoForge run.
cp "$(ls "$PROJECT"/build/libs/forbricruntime-*.jar | head -1)" "$RUNDIR/mods/forbricruntime.jar"
if [ -f "$NFRT_JAR" ]; then cp "$NFRT_JAR" "$RUNDIR/mods/neoforge-runtime.jar";
else echo "[launch] WARN: NeoForge runtime jar missing ($NFRT_JAR) — run run/assemble-neoforge-runtime.sh" >&2; fi
# The NeoForge bridge (@Mod("forbric")): opens the Fabric-content window inside NeoForge's genuine
# registration span. Required for the real lifecycle (non-headless) so Fabric mods register at the right time.
BRIDGE_JAR="${BRIDGE_JAR:-$HERE/neoforge-runtime/forbric-bridge-neoforge.jar}"
if [ -f "$BRIDGE_JAR" ]; then cp "$BRIDGE_JAR" "$RUNDIR/mods/forbric-bridge-neoforge.jar"; fi

# 3) Loader dependency jars (runtime classpath minus the build class/resource dirs — we use the core jar).
DEPS="$("$PROJECT/gradlew" -q -p "$PROJECT" printRuntimeClasspath | tail -1 | tr ':' '\n' | grep -vE "build/(classes|resources)" | paste -sd: -)"

# 4) MC 26.2 library classpath from the vanilla version manifest (server reuses the same core libs).
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

# Patched jar LAST so its (Mojmap) MC classes win over any stale game classes elsewhere.
CP="$CORE:$DEPS:$VANILLA_CP:$PATCHED"

echo "[launch] rundir=$RUNDIR"
echo "[launch] patched MC = $PATCHED"
echo "[launch] mods: $(ls "$RUNDIR/mods" | paste -sd' ' -)"
# Run from RUNDIR so the dedicated server's run dir (and the mods/ folder fabric-loader scans) is the
# isolated server-26.2 dir. All classpath entries above are absolute, so the cd is safe.
cd "$RUNDIR"
exec java -Djava.awt.headless=true -Dforbric.runtimeNamespace=named -Dforbric.forgeFamily=neoforge ${FORBRIC_JVM:-} \
  -cp "$CP" net.forbric.loader.impl.launch.ForbricServer \
  --gameDir "$RUNDIR" --nogui "$@"
