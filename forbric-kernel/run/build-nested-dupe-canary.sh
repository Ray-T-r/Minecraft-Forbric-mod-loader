#!/usr/bin/env bash
# Builds the pair of canary mods gate-m19-nesteddupe.sh needs: a Fabric mod and a MinecraftForge mod that each
# JiJ-nest their own build of ONE library, both declaring the mod id `forbricnestlib`.
#
# That is the shape no gate pack had. Every pack's nested duplicates are same-family (one fabric-api-base nested
# by five Fabric mods), which both loaders already deduplicate on their own. What nobody was checking is the SAME
# id claimed by two ECOSYSTEMS — each loader only ever deduplicates within its own family — and that is what made
# Xaero's xaerolib construct twice on a real 26.2 pack.
#
# The library carries a one-shot registry that throws on a second registration, the way xaerolib's config channel
# does, so the gate proves the BEHAVIOUR and not only the arbitration log line.
#
# Output: run/canary/forbricnestfab.jar   (contains META-INF/jars/forbricnestlib-fabric.jar)
#         run/canary/forbricnestforge.jar (contains META-INF/jarjar/forbricnestlib-forge.jar)
#         run/canary/forbricnestneo.jar   (contains META-INF/jarjar/forbricnestlib-neoforge.jar)
#
# The NeoForge parent is the same shape on the family that stays: the MinecraftForge pair proves the cross-ecosystem
# contest only while MinecraftForge exists, and a NeoForge JarJar child contesting a Fabric JiJ child is what remains
# of it afterwards. So only the MinecraftForge pair needs the MinecraftForge runtime: without it staged (a stage
# prepared with no MinecraftForge carrier, the genuine NeoForge base) the Fabric and NeoForge pair still build, and
# the MinecraftForge parent is not built at all -- nor left behind from an earlier build, so nothing stages a stale
# one.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SRC="$KERNEL/canary/nesteddupe"
OUT="$KERNEL/run/canary"
canary_scratch nesteddupe
FORGE_RT="$OLD/run/merged-base/forge-runtime-interop.jar"
[ -f "$FORGE_RT" ] || FORGE_RT="$OLD/run/forge-runtime/forge-runtime.jar"
# Only while it is staged; the NeoForge and Fabric pair need none of it.
[ -f "$FORGE_RT" ] || FORGE_RT=""
NEO_RT="$OLD/run/neoforge-runtime/neoforge-runtime.jar"

step "prerequisites"
[ -f "$NEO_RT" ] || { echo "[kernel] FAIL neoforge runtime absent: $NEO_RT"; exit 1; }
mkdir -p "$BUILD"
kernel_jar
KERNEL_JAR="$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar"
[ -f "$KERNEL_JAR" ] || { echo "[kernel] FAIL kernel jar not built"; exit 1; }
if [ -n "$FORGE_RT" ]; then
  echo "[kernel] neoforge + forge runtime + kernel jar present"
else
  echo "[kernel] neoforge runtime + kernel jar present; no MinecraftForge runtime staged, so the MinecraftForge parent is not built"
fi

mkdir -p "$WORK"/{libfab,libforge,libneo,parfab,parforge,parneo} "$OUT"

# The registry class is compiled into BOTH library jars, byte-identical, exactly as a real multiloader library
# ships its shared half twice. On a classpath carrying both, one copy is defined (first URL wins) and both
# bootstraps reach it — which is how the real duplicate registration happened.
step "compile the shared library, once per platform"
javac -nowarn -proc:none --release 21 -cp "$KERNEL_JAR" -d "$WORK/libfab" \
      "$SRC/lib/src/forbric/nestlib/NestLibRegistry.java" \
      "$SRC/lib/src/forbric/nestlib/NestLibFabric.java" 2>&1 | grep -v '^Note:' || true
[ -f "$WORK/libfab/forbric/nestlib/NestLibFabric.class" ] || { echo "[kernel] FAIL fabric lib did not compile"; exit 1; }
if [ -n "$FORGE_RT" ]; then
  javac -nowarn -proc:none --release 21 -cp "$FORGE_RT" -d "$WORK/libforge" \
        "$SRC/lib/src/forbric/nestlib/NestLibRegistry.java" \
        "$SRC/lib/src/forbric/nestlib/NestLibForge.java" 2>&1 | grep -v '^Note:' || true
  [ -f "$WORK/libforge/forbric/nestlib/NestLibForge.class" ] || { echo "[kernel] FAIL forge lib did not compile"; exit 1; }
fi
javac -nowarn -proc:none --release 21 -cp "$NEO_RT" -d "$WORK/libneo" \
      "$SRC/lib/src/forbric/nestlib/NestLibRegistry.java" \
      "$SRC/lib/src/forbric/nestlib/NestLibNeoForge.java" 2>&1 | grep -v '^Note:' || true
[ -f "$WORK/libneo/forbric/nestlib/NestLibNeoForge.class" ] || { echo "[kernel] FAIL neoforge lib did not compile"; exit 1; }

cp "$SRC/lib/fabric.mod.json" "$WORK/libfab/"
(cd "$WORK/libfab"   && jar --create --file "$WORK/forbricnestlib-fabric.jar" .) || exit 1
if [ -n "$FORGE_RT" ]; then
  mkdir -p "$WORK/libforge/META-INF" && cp "$SRC/lib/mods.toml" "$WORK/libforge/META-INF/"
  (cd "$WORK/libforge" && jar --create --file "$WORK/forbricnestlib-forge.jar" .) || exit 1
fi
mkdir -p "$WORK/libneo/META-INF" && cp "$SRC/lib/neoforge.mods.toml" "$WORK/libneo/META-INF/"
(cd "$WORK/libneo"   && jar --create --file "$WORK/forbricnestlib-neoforge.jar" .) || exit 1
echo "[kernel] built the $([ -n "$FORGE_RT" ] && echo 'Fabric, MinecraftForge and NeoForge' || echo 'Fabric and NeoForge') builds of forbricnestlib"

step "compile the two parents and nest their own build"
javac -nowarn -proc:none --release 21 -cp "$KERNEL_JAR" -d "$WORK/parfab" \
      "$SRC/parentfabric/src/forbric/nestparent/NestParentFabric.java" 2>&1 | grep -v '^Note:' || true
[ -f "$WORK/parfab/forbric/nestparent/NestParentFabric.class" ] || { echo "[kernel] FAIL fabric parent did not compile"; exit 1; }
if [ -n "$FORGE_RT" ]; then
  javac -nowarn -proc:none --release 21 -cp "$FORGE_RT" -d "$WORK/parforge" \
        "$SRC/parentforge/src/forbric/nestparent/NestParentForge.java" 2>&1 | grep -v '^Note:' || true
  [ -f "$WORK/parforge/forbric/nestparent/NestParentForge.class" ] || { echo "[kernel] FAIL forge parent did not compile"; exit 1; }
fi
javac -nowarn -proc:none --release 21 -cp "$NEO_RT" -d "$WORK/parneo" \
      "$SRC/parentneoforge/src/forbric/nestparent/NestParentNeoForge.java" 2>&1 | grep -v '^Note:' || true
[ -f "$WORK/parneo/forbric/nestparent/NestParentNeoForge.class" ] || { echo "[kernel] FAIL neoforge parent did not compile"; exit 1; }

cp "$SRC/parentfabric/fabric.mod.json" "$WORK/parfab/"
mkdir -p "$WORK/parfab/META-INF/jars"
cp "$WORK/forbricnestlib-fabric.jar" "$WORK/parfab/META-INF/jars/"
# No META-INF/jarjar/metadata.json here, on purpose: real Fabric JiJ parents (xaerominimap-fabric) ship none, and
# the MinecraftForge parent names its own platform artifact (forbricnestlib-forge, as xaerolib-forge-26.2 does).
# The gate must hold on that real shape: any in-range build of the same mod id meets the Forge coordinate.

if [ -n "$FORGE_RT" ]; then
  mkdir -p "$WORK/parforge/META-INF/jarjar"
  cp "$SRC/parentforge/mods.toml" "$WORK/parforge/META-INF/"
  # Both the metadata.json Forge's JarJarSelector reads and the jar itself: the kernel's extractor reads the former
  # and takes the latter, so a pair that disagrees would extract nothing and the gate would pass for a wrong reason.
  cp "$SRC/parentforge/metadata.json" "$WORK/parforge/META-INF/jarjar/"
  cp "$WORK/forbricnestlib-forge.jar" "$WORK/parforge/META-INF/jarjar/"
fi

# The NeoForge parent the same way: JarJar's metadata.json names its own platform artifact, and the jar beside it.
mkdir -p "$WORK/parneo/META-INF/jarjar"
cp "$SRC/parentneoforge/neoforge.mods.toml" "$WORK/parneo/META-INF/"
cp "$SRC/parentneoforge/metadata.json" "$WORK/parneo/META-INF/jarjar/"
cp "$WORK/forbricnestlib-neoforge.jar" "$WORK/parneo/META-INF/jarjar/"

(cd "$WORK/parfab"   && jar --create --file "$WORK/forbricnestfab.jar" .)   || exit 1
(cd "$WORK/parneo"   && jar --create --file "$WORK/forbricnestneo.jar" .)   || exit 1
publish_canary "$WORK/forbricnestfab.jar"   "$OUT/forbricnestfab.jar"   || exit 1
publish_canary "$WORK/forbricnestneo.jar"   "$OUT/forbricnestneo.jar"   || exit 1
if [ -n "$FORGE_RT" ]; then
  (cd "$WORK/parforge" && jar --create --file "$WORK/forbricnestforge.jar" .) || exit 1
  publish_canary "$WORK/forbricnestforge.jar" "$OUT/forbricnestforge.jar" || exit 1
else
  rm -f "$OUT/forbricnestforge.jar"
fi

step "result"
echo "[kernel] ✅ built $OUT/forbricnestfab.jar + $OUT/forbricnestneo.jar$([ -n "$FORGE_RT" ] && echo " + $OUT/forbricnestforge.jar")"
unzip -l "$OUT/forbricnestfab.jar"   | grep -E 'jars/|fabric.mod.json'
unzip -l "$OUT/forbricnestneo.jar"   | grep -E 'jarjar/|neoforge.mods.toml'
if [ -n "$FORGE_RT" ]; then unzip -l "$OUT/forbricnestforge.jar" | grep -E 'jarjar/|mods.toml'; fi
