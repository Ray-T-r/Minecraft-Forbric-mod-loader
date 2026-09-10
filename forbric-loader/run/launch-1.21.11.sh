#!/usr/bin/env bash
# Launch Minecraft 1.21.11 through the Forbric loader, using an existing vanilla 1.21.11 install plus the
# Fabric intermediary + natives from a 1.21.11-Fabric install.
#
# Forbric ships as TWO jars (see README "Architecture note"):
#   - forbric-loader-*.jar   : the loader core, on the classpath (parent-loaded).
#   - forbricruntime-*.jar    : the NeoForge API + registration bridge + game mixins, dropped into mods/ so
#                               Knot loads it in its transforming classloader (where intermediary game classes live).
# The loader core MUST be on the classpath as a JAR (not as split build/classes + build/resources dirs), or the
# builtin `fabricloader` mod is not found in production mode.
#
# Prereqs:
#   - A vanilla 1.21.11 profile and a 1.21.11-Fabric profile installed (for intermediary + natives).
#   - Forge mods to load must already be remapped+wrapped into mods/ (see the repo's build harness), or set
#     -Dforbric.intermediary/-Dforbric.mojmap/-Dforbric.gameJar to have Forbric auto-remap them.
set -euo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"

GAME="$MC/versions/1.21.11/1.21.11.jar"
INTER="$MC/libraries/net/fabricmc/intermediary/1.21.11/intermediary-1.21.11.jar"
NATIVES="${NATIVES_DIR:-$MC/versions/1.21.11-Fabric/natives-macos-arm64}"
ASSETS="$MC/assets"

# 1) Build both jars; put the runtime module into mods/ (Knot-loaded), keep the core jar for the classpath.
"$PROJECT/gradlew" -q -p "$PROJECT" jar runtimeJar
CORE="$(ls "$PROJECT"/build/libs/forbric-loader-*.jar | head -1)"
cp "$(ls "$PROJECT"/build/libs/forbricruntime-*.jar | head -1)" "$HERE/mods/forbricruntime.jar"

# 2) Loader dependency jars (the runtime classpath, minus the build class/resource dirs — we use the core jar).
#    MixinExtras is NOT here: it's JiJ-nested into forbricruntime.jar (Knot-loaded) by the build, so its
#    runtime-generated LocalRef classes resolve against the game classes. (build.gradle: nestedMods + runtimeJar.)
DEPS="$("$PROJECT/gradlew" -q -p "$PROJECT" printRuntimeClasspath | tail -1 | tr ':' '\n' | grep -vE "build/(classes|resources)" | paste -sd: -)"

# 3) Vanilla 1.21.11 library classpath, resolved from the version manifest.
VANILLA_CP="$(python3 - "$MC" <<'PY'
import json, os, sys
mc = sys.argv[1]
d = json.load(open(os.path.join(mc, 'versions', '1.21.11', '1.21.11.json')))
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

CP="$CORE:$DEPS:$INTER:$GAME:$VANILLA_CP"

exec java -XstartOnFirstThread -Djava.awt.headless=true -Djava.library.path="$NATIVES" \
  -cp "$CP" net.forbric.loader.impl.launch.ForbricClient \
  --version 1.21.11 --gameDir "$HERE" --assetsDir "$ASSETS" --assetIndex 32 \
  --accessToken 0 --username ForbricDev --uuid 00000000000000000000000000000000 \
  --userType legacy --versionType release
