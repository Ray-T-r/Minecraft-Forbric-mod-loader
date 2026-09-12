#!/usr/bin/env bash
# Boot the MC 26.2 CLIENT through the SOVEREIGN KERNEL (no Knot, no genuine FML/FancyModLoader lifecycle) from the
# merged 3-ABI base, with the Forge + NeoForge runtime jars as PASSIVE ABI carriers. M5 goal: reach the TITLE screen.
#
# Parent -cp: kernel boot jar + kernel deps + MC 26.2 libraries (incl. LWJGL). Owned (transform-loaded): merged
# base + forge-runtime + neoforge-runtime + the MC libraries (via --libraryPath, so mods can mixin into them).
#
# macOS: -XstartOnFirstThread is MANDATORY (GLFW must own the main thread); the kernel invokes the client Main on
# that same thread, so the window is created on thread 0 as GLFW requires.
#
# Usage: [RUNDIR=…] [FORBRIC_JVM=…] ./launch-kernel-client.sh [extra game args]
set -uo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/.." && pwd)"
OLD="$(cd "$KERNEL/../forbric-loader" && pwd)"
STAGE="$OLD/run"

MERGED="${MERGED:-$STAGE/merged-base/patched-mc-merged-26.2.jar}"
FORGE_RT="${FORGE_RT:-$STAGE/merged-base/forge-runtime-interop.jar}"
[ -f "$FORGE_RT" ] || FORGE_RT="$STAGE/forge-runtime/forge-runtime.jar"
NEO_RT="${NEO_RT:-$STAGE/neoforge-runtime/neoforge-runtime.jar}"
RUNDIR="${RUNDIR:-$KERNEL/run/client-kernel}"
NATIVES="${NATIVES_DIR:-$MC/versions/26.2/26.2-natives}"
ASSETS="$MC/assets"
mkdir -p "$RUNDIR/mods"

[ -f "$MERGED" ] || { echo "merged base not found: $MERGED (run $STAGE/build-merged-base.sh)" >&2; exit 2; }
[ -d "$NATIVES" ] || { echo "LWJGL natives not found: $NATIVES" >&2; exit 2; }

if ! "$KERNEL/gradlew" --offline -q -p "$KERNEL" jar >/tmp/forbric-kernel-jar.log 2>&1; then
  echo "[kernel-launch] FATAL: kernel jar build failed — refusing to launch a stale jar" >&2
  grep -vE 'WARNING: |native-access|Restricted method|--enable-native' /tmp/forbric-kernel-jar.log >&2
  exit 3
fi
BOOT_JAR="$(ls "$KERNEL"/build/libs/forbric-kernel-*.jar | head -1)"
BOOT_DEPS="$("$KERNEL/gradlew" --offline -q -p "$KERNEL" printBootClasspath 2>/dev/null | grep -vE 'WARNING|native|Restricted|enable' | tail -1)"

# MC 26.2 libraries (parent-loaded), resolved from the Mojang install's version json. Includes LWJGL.
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
    if os.path.exists(jar): out.append(jar)
print(os.pathsep.join(out))
PY
)"
ASSET_INDEX="$(python3 -c "import json;print(json.load(open('$MC/versions/26.2/26.2.json'))['assetIndex']['id'])")"


# Game root metadata (version.json) on the PARENT -cp, as a resources-only jar. Mods that ask
# getSystemClassLoader() for it — CustomSkinLoader's bootstrap picks its bytecode patch variant by the protocol
# version it finds there — get null under Forbric otherwise, because the merged base belongs to
# ForbricClassLoader. See run/game-metadata-jar.sh for why this must never carry class files.
META_JAR="$("$HERE/game-metadata-jar.sh" "$MERGED" 2>/dev/null)" || META_JAR=""
CP="$BOOT_JAR:$BOOT_DEPS:$VANILLA_CP${META_JAR:+:$META_JAR}"

# Guest mixins are written against VANILLA bytecode; the merged base is vanilla+Forge+NeoForge byte-merged, so an
# injection anchor a mixin expects may have moved. The KERNEL now relaxes EVERY discovered guest mod's mixin configs
# by default (ForbricMixinService.setGuestConfigs), turning such a failure into a soft skip instead of a fatal
# MixinApplyError — no launcher-side glob needed. Add more with -Dforbric.relaxMixinOverwrites, or get strict Mixin
# behaviour back for debugging with -Dforbric.relaxGuestMixins=off. Merged-base incompatibilities that survive apply
# but break at runtime are shipped defaults in MergedBaseMixinCompat.

echo "[kernel-launch] CLIENT rundir=$RUNDIR  assetIndex=$ASSET_INDEX"
echo "[kernel-launch] merged base = $MERGED"
echo "[kernel-launch] natives = $NATIVES"
echo "[kernel-launch] mods: $(ls "$RUNDIR/mods" 2>/dev/null | paste -sd' ' -)"
cd "$RUNDIR"

exec java -XstartOnFirstThread -Djava.library.path="$NATIVES" ${FORBRIC_JVM:-} \
  -cp "$CP" net.forbric.kernel.boot.KernelClientLaunch \
  --gameJar "$MERGED" --runtimeJar "$FORGE_RT" --runtimeJar "$NEO_RT" \
  --libraryPath "$VANILLA_CP" \
  -- --version 26.2-forbric-kernel --gameDir "$RUNDIR" --assetsDir "$ASSETS" --assetIndex "$ASSET_INDEX" \
  --accessToken 0 --username ForbricKernel --uuid 00000000000000000000000000000000 \
  --userType legacy --versionType release "$@"
