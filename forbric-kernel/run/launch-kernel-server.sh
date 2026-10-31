#!/usr/bin/env bash
# Boot the MC 26.2 DEDICATED SERVER through the SOVEREIGN KERNEL (no Knot, no genuine FML/FancyModLoader lifecycle)
# from NeoForge's patched game, with the NeoForge runtime jar as the PASSIVE ABI carrier. M1 goal: reach Done.
#
# Parent -cp: kernel boot jar + kernel deps + MC 26.2 libraries (parent-loaded).
# Owned (transform-loaded by ForbricClassLoader): the base + neoforge-runtime.
#
# Usage: [RUNDIR=…] [FORBRIC_JVM=…] ./launch-kernel-server.sh [extra game args]
set -uo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/.." && pwd)"
# MERGED and FORGE_RT named the merged base and the MinecraftForge carrier, both retired with MinecraftForge (Stage 4).
# A caller still setting them means a base or carrier it expects to be launched, and ignoring it would launch the staged
# base instead and report on that, so stop. The game jar is BASE; there is no second carrier.
if [ -n "${MERGED:-}${FORGE_RT:-}" ]; then
  echo "[kernel-launch] FATAL: MERGED/FORGE_RT are retired: the game jar is BASE (NeoForge's patched game) and NEO_RT" \
    "the one carrier; there is no MinecraftForge carrier any more (MERGED=${MERGED:-} FORGE_RT=${FORGE_RT:-})" >&2
  exit 2
fi
# Same FORBRIC_OLD knob lib.sh uses: one variable points a second working tree at the staged artifacts
# instead of needing BASE and NEO_RT set individually.
OLD="$(cd "${FORBRIC_OLD:-$KERNEL/../forbric-loader}" && pwd)"
STAGE="$OLD/run"

BASE="${BASE:-$STAGE/neoforge-patched/patched-mc-neoforge-26.2.jar}"
NEO_RT="${NEO_RT:-$STAGE/neoforge-runtime/neoforge-runtime.jar}"
# The vanilla reference the kernel reads beside the base (--nativeReference): staged by prepare, else built once into
# build/ by run/vanilla-reference-jar.sh. The base embeds no index of its own, so without one the kernel has no FABRIC
# references and says so in its "native references:" line; NATIVE_REF=none launches without it on purpose.
NATIVE_REF="${NATIVE_REF:-}"
RUNDIR="${RUNDIR:-$KERNEL/run/server-kernel}"
mkdir -p "$RUNDIR"

[ -f "$BASE" ] || { echo "game base not found: $BASE (run python3 tools/dev.py prepare)" >&2; exit 2; }

# EULA (dedicated server refuses to start otherwise). Kernel testing only — the user has accepted MC's EULA.
[ -f "$RUNDIR/eula.txt" ] || echo "eula=true" > "$RUNDIR/eula.txt"

# Build the boot jar. FAIL LOUDLY: a swallowed build error here silently launches a STALE jar, and every gate
# downstream then reports on code that is not the code in the tree.
if ! "$KERNEL/gradlew" --offline -q -p "$KERNEL" jar >/tmp/forbric-kernel-jar.log 2>&1; then
  echo "[kernel-launch] FATAL: kernel jar build failed — refusing to launch a stale jar" >&2
  grep -vE 'WARNING: |native-access|Restricted method|--enable-native' /tmp/forbric-kernel-jar.log >&2
  exit 3
fi
BOOT_JAR="$(ls "$KERNEL"/build/libs/forbric-kernel-*.jar | head -1)"
BOOT_DEPS="$("$KERNEL/gradlew" --offline -q -p "$KERNEL" printBootClasspath 2>/dev/null | grep -vE 'WARNING|native|Restricted|enable' | tail -1)"

# MC 26.2 libraries (parent-loaded), resolved from the Mojang install's version json.
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

# jline (server console) is shipped in the MC libraries tree but not listed in 26.2.json's libraries array;
# add it explicitly so the dedicated-server console handler doesn't NoClassDefFound.
JLINE="$(find "$MC/libraries/org/jline" -name 'jline-*-3.25.1.jar' 2>/dev/null | paste -sd: -)"


# Game root metadata (version.json) on the PARENT -cp, as a resources-only jar. Mods that ask
# getSystemClassLoader() for it — CustomSkinLoader's bootstrap picks its bytecode patch variant by the protocol
# version it finds there — get null under Forbric otherwise, because the base belongs to
# ForbricClassLoader. See run/game-metadata-jar.sh for why this must never carry class files.
META_JAR="$("$HERE/game-metadata-jar.sh" "$BASE" 2>/dev/null)" || META_JAR=""
if [ -z "$NATIVE_REF" ]; then
  # Exit 6 is a refusal (an explicitly named sidecar that records another base): stop rather than launch with none,
  # which on a base without an embedded index (this one) would cost every FABRIC reference, not only the ones it
  # cannot verify.
  NATIVE_REF="$(MC_DIR="$MC" "$HERE/vanilla-reference-jar.sh" "$BASE")" || { rc=$?; [ "$rc" = 6 ] && exit 6; NATIVE_REF=""; }
fi
[ "$NATIVE_REF" = none ] && NATIVE_REF=""
CP="$BOOT_JAR:$BOOT_DEPS:$VANILLA_CP${JLINE:+:$JLINE}${META_JAR:+:$META_JAR}"

# Guest Fabric mixins are written against VANILLA bytecode; the base is vanilla patched by NeoForge, so an
# injection anchor a mixin expects may have moved. The KERNEL now relaxes EVERY discovered guest mod's mixin
# configs by default (ForbricMixinService.setGuestConfigs), turning such a failure into a soft skip instead of a
# fatal MixinApplyError — no launcher-side glob needed. Add more with -Dforbric.relaxMixinOverwrites, or get
# strict Mixin behaviour back for debugging with -Dforbric.relaxGuestMixins=off.

echo "[kernel-launch] rundir=$RUNDIR"
echo "[kernel-launch] base = $BASE"
echo "[kernel-launch] vanilla reference = ${NATIVE_REF:-none}"
echo "[kernel-launch] owned carrier: $(basename "$NEO_RT")"
cd "$RUNDIR"

# The MC libraries go BOTH on the parent -cp and to the kernel as owned jars (--libraryPath): mods mixin into
# them (fabric-dimension-api-v1 targets DataFixerUpper's TaggedChoice), so the transforming loader must define
# them. This is what Fabric's Knot does with the whole game classpath.
# Developer runs use strict compatibility decisions; installed profiles retain the product default (ask).
exec java -Djava.awt.headless=true -Dforbric.compatibilityPolicy="${FORBRIC_COMPAT_POLICY:-strict}" ${FORBRIC_JVM:-} \
  -cp "$CP" net.forbric.kernel.boot.KernelServerLaunch \
  --gameJar "$BASE" --runtimeJar "$NEO_RT" ${NATIVE_REF:+--nativeReference "$NATIVE_REF"} \
  --libraryPath "$VANILLA_CP${JLINE:+:$JLINE}" \
  -- --gameDir "$RUNDIR" --nogui "$@"
