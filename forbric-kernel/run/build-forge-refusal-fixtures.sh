#!/usr/bin/env bash
# Builds the MinecraftForge-only jars the refusal gates stage, without MinecraftForge:
#   run/canary/forge-refused/forbriclive.jar         gate-m26, gate-m29   the plain case: a mods.toml, its @Mod and its
#                                                                         mixin config
#   run/canary/forge-refused/forbricnestforge.jar    gate-m19             a MinecraftForge parent JarJar-nesting its own build
#                                                                         of forbricnestlib
#   run/canary/forge-refused/forbricbreathforge.jar  gate-m57             a Forge liquid, bucket and breathe/drown listeners
#
# WHY STUBS. Forbric refuses a jar whose only usable loader half is MinecraftForge's (LoaderHalfJudge), and that refusal
# stays a product behaviour after MinecraftForge itself leaves the stage: a player's old mods folder still holds such jars.
# The gates that prove it used to compile their fixtures against the real MinecraftForge carrier (forge-runtime.jar and
# its patched game), which a stage prepared without MinecraftForge does not have. The judge never loads a class: it reads
# the manifests and the class bytes. So the fixtures compile against canary/forge-refusal-stubs/api — the few
# net.minecraftforge types their sources name, with the real signatures, retention and element defaults — and against
# canary/forge-refusal-stubs/patched-vanilla, the two constructors MinecraftForge patched into vanilla that the breath
# canary calls. Neither is ever in a fixture jar (checked below), and every fixture keeps its own source and manifest:
#   forbricnestforge, forbricbreathforge                      their sources in canary/nesteddupe and
#                                                             canary/forge-breath-drown, unchanged;
#   forbriclive                                               canary/forge-refusal-stubs/forbriclive: the live canary's
#                                                             mods.toml and forbriclive.mixins.json byte for byte, its
#                                                             @Mod entry, and the mixin config plugin whose static
#                                                             initialiser prints [ForbricLive/PLUGIN] the moment Mixin
#                                                             is handed the config — what gate-m26 and gate-m29's
#                                                             "nothing of the refused jar ran" checks look for. The
#                                                             canary's own source (forbric-loader/run/livemod-src)
#                                                             probes a hundred MinecraftForge types nothing runs any more.
#
# The rest of each classpath is NeoForge's patched game and runtime (the runtime only completes vanilla's supertypes:
# NeoForge's patched Fluid implements its IFluidExtension) and the Minecraft libraries. A fixture that ends up naming
# NeoForge is not a MinecraftForge-only jar any more, so that is a build failure too.
#
# A failed build exits non-zero and leaves the previous jars in place (outputs are only ever replaced by an atomic
# rename, because gates that run in parallel read them while another gate rebuilds them), so every caller checks the
# exit code instead of only testing that the jar exists.
#
# Only forbricbreathforge (gate-m57's refused phase) uses the event, fluid and registry stand-ins and patched-vanilla.
# Stage 4 deletes that phase and gate-m30's refusal boot (D32: gate-m26 and gate-m29 cover the refusal). gate-m30's
# went in C5 with its fixture forbricforgecanary, its step here and the one stand-in only it used (BlockEvent); whoever
# deletes gate-m57's phase removes forbricbreathforge's step here and the stand-ins only it uses.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

STUBS="$KERNEL/canary/forge-refusal-stubs"
OUT="$KERNEL/run/canary/forge-refused"
canary_scratch forge-refusal
NEO_MC="$RUN_OLD/neoforge-patched/patched-mc-neoforge-26.2.jar"
NEO_RT="$RUN_OLD/neoforge-runtime/neoforge-runtime.jar"
MC_DIR="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
# The libraries 26.2 itself lists, as build-breath-drown-canary.sh reads them: every jar under libraries/ would put an
# older Gson ahead of the one the breath canary's core is written against.
VLIBS="$(python3 - "$MC_DIR" <<'PY'
import json, pathlib, sys
mc = pathlib.Path(sys.argv[1])
try:
    libraries = json.loads((mc / 'versions/26.2/26.2.json').read_text())['libraries']
except (OSError, ValueError, KeyError):
    libraries = []
paths = [mc / 'libraries' / e['downloads']['artifact']['path'] for e in libraries
         if e.get('downloads', {}).get('artifact', {}).get('path')]
print(':'.join(str(p) for p in paths if p.is_file()))
PY
)"

step "prerequisites"
for f in "$NEO_MC" "$NEO_RT"; do
  [ -f "$f" ] || { echo "[kernel] FAIL missing: $f"; exit 1; }
done
[ -n "$VLIBS" ] || { echo "[kernel] FAIL no 26.2 libraries under $MC_DIR (versions/26.2/26.2.json)"; exit 1; }
# forbriclive's mixin config plugin implements Mixin's IMixinConfigPlugin, whose methods take ASM's ClassNode. Found as
# build-attribution-canaries.sh finds Mixin: the launcher's libraries first, then Gradle's cache.
library() { # library <launcher libraries subdir> <gradle cache group/artifact>
  local found
  found="$(find "$MC_DIR/libraries/$1" -name '*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)"
  [ -n "$found" ] || found="$(find "$HOME/.gradle/caches/modules-2/files-2.1/$2" -name '*.jar' ! -name '*sources*' 2>/dev/null | sort | tail -1)"
  printf '%s' "$found"
}
MIXIN="$(library net/fabricmc/sponge-mixin net.fabricmc/sponge-mixin)"
ASM="$(library org/ow2/asm/asm org.ow2.asm/asm)"
ASM_TREE="$(library org/ow2/asm/asm-tree org.ow2.asm/asm-tree)"
for f in "$MIXIN" "$ASM" "$ASM_TREE"; do
  [ -n "$f" ] || { echo "[kernel] FAIL sponge-mixin, asm or asm-tree jar not found"; exit 1; }
done
mkdir -p "$WORK/stubs" "$OUT"

# javac_quiet <javac args>... — javac without its "Note:" lines, keeping its exit code.
javac_quiet() {
  local out rc
  out="$(javac -nowarn -proc:none --release 21 "$@" 2>&1)"; rc=$?
  grep -v '^Note:' <<< "$out" | grep -v '^$'
  return "$rc"
}

step "compile the MinecraftForge stand-ins"
javac_quiet -cp "$NEO_MC:$NEO_RT:$VLIBS" -d "$WORK/stubs" $(find "$STUBS/api/src" "$STUBS/patched-vanilla/src" -name '*.java') \
  || { echo "[kernel] FAIL the stubs did not compile"; exit 1; }
# Stubs first: javac takes the first class of a name on the classpath, which is how patched-vanilla's LiquidBlock and
# BucketItem hide NeoForge's.
CP="$WORK/stubs:$NEO_MC:$NEO_RT:$VLIBS"

# compile <classes dir> <source>... — fails the build on a compile error, never ships a half-compiled fixture.
# EXTRA_CP, when set, goes after the common classpath.
compile() {
  local classes="$1"; shift
  mkdir -p "$classes"
  javac_quiet -cp "$CP${EXTRA_CP:+:$EXTRA_CP}" -d "$classes" "$@" \
    || { echo "[kernel] FAIL $(basename "$classes") did not compile"; exit 1; }
}

# publish <staged jar> — checks the jar is what a MinecraftForge-only build is, then installs it into $OUT. The jar and
# every jar nested in it are unpacked first, and an unpack that fails fails the build: a check that could not look
# must not pass.
publish() {
  local jar="$1" name scan nested root rc
  name="$(basename "$jar")"
  scan="$WORK/scan/${name%.jar}"
  rm -rf "$scan"; mkdir -p "$scan"
  unzip -q "$jar" -d "$scan" || { echo "[kernel] FAIL $name is not a zip"; exit 1; }
  while IFS= read -r nested; do
    unzip -q "$nested" -d "${nested%.jar}.x" || { echo "[kernel] FAIL ${nested#"$scan"/} in $name is not a zip"; exit 1; }
  done < <(find "$scan" -type f -name '*.jar')
  # No stand-in in the jar or in any jar nested in it: each unpacked root is checked for the two packages they live in.
  while IFS= read -r root; do
    for pkg in net/minecraftforge net/minecraft; do
      if [ -e "$root/$pkg" ]; then
        echo "[kernel] FAIL a stub leaked into $name: ${root#"$WORK/scan/"}/$pkg"; exit 1
      fi
    done
  done < <(printf '%s\n' "$scan"; find "$scan" -type d -name '*.x')
  [ -f "$scan/META-INF/mods.toml" ] || { echo "[kernel] FAIL $name has no META-INF/mods.toml"; exit 1; }
  if [ -e "$scan/fabric.mod.json" ] || [ -e "$scan/META-INF/neoforge.mods.toml" ]; then
    echo "[kernel] FAIL $name declares a Fabric or NeoForge half"; exit 1
  fi
  # The judge's own test for a class naming NeoForge, on every class of the jar and of the jars nested in it.
  [ -n "$(find "$scan" -name '*.class' -print -quit)" ] || { echo "[kernel] FAIL $name has no classes to check"; exit 1; }
  grep -rq --include='*.class' 'net/neoforged/' "$scan"; rc=$?
  case "$rc" in
    0) echo "[kernel] FAIL $name names NeoForge, so it is no longer a MinecraftForge-only build"; exit 1 ;;
    1) ;;
    *) echo "[kernel] FAIL could not scan $name for NeoForge names (grep exit $rc)"; exit 1 ;;
  esac
  publish_canary "$jar" "$OUT/$name" || exit 1
  echo "[kernel] built $OUT/$name"
}

step "forbriclive"
EXTRA_CP="$MIXIN:$ASM:$ASM_TREE" compile "$WORK/live" $(find "$STUBS/forbriclive/src" -name '*.java')
mkdir -p "$WORK/live/META-INF" && cp "$STUBS/forbriclive/META-INF/mods.toml" "$WORK/live/META-INF/"
cp "$STUBS/forbriclive/forbriclive.mixins.json" "$WORK/live/"
(cd "$WORK/live" && jar --create --file "$WORK/forbriclive.jar" .) || exit 1
publish "$WORK/forbriclive.jar"

step "forbricnestforge, nesting the MinecraftForge build of forbricnestlib"
NEST="$KERNEL/canary/nesteddupe"
compile "$WORK/nestlib" "$NEST/lib/src/forbric/nestlib/NestLibRegistry.java" "$NEST/lib/src/forbric/nestlib/NestLibForge.java"
mkdir -p "$WORK/nestlib/META-INF" && cp "$NEST/lib/mods.toml" "$WORK/nestlib/META-INF/"
(cd "$WORK/nestlib" && jar --create --file "$WORK/forbricnestlib-forge.jar" .) || exit 1
compile "$WORK/nestparent" "$NEST/parentforge/src/forbric/nestparent/NestParentForge.java"
# Both the metadata.json MinecraftForge's JarJarSelector reads and the jar itself, as build-nested-dupe-canary.sh nests
# its Fabric and NeoForge children.
mkdir -p "$WORK/nestparent/META-INF/jarjar"
cp "$NEST/parentforge/mods.toml" "$WORK/nestparent/META-INF/"
cp "$NEST/parentforge/metadata.json" "$WORK/forbricnestlib-forge.jar" "$WORK/nestparent/META-INF/jarjar/"
(cd "$WORK/nestparent" && jar --create --file "$WORK/forbricnestforge.jar" .) || exit 1
publish "$WORK/forbricnestforge.jar"

step "forbricbreathforge"
BREATH="$KERNEL/canary/forge-breath-drown"
# Its copy of the shared core under its own package, as build-breath-drown-canary.sh does for each family.
mkdir -p "$WORK/breathsrc/forbric/breathdrown/forge"
sed 's/PKG/forge/g' "$BREATH/common/src/Core.java" > "$WORK/breathsrc/forbric/breathdrown/forge/Core.java"
compile "$WORK/breath" $(find "$BREATH/forge/src" -name '*.java') "$WORK/breathsrc/forbric/breathdrown/forge/Core.java"
mkdir -p "$WORK/breath/META-INF" && cp "$BREATH/forge/META-INF/mods.toml" "$WORK/breath/META-INF/"
(cd "$WORK/breath" && jar --create --no-manifest --file "$WORK/forbricbreathforge.jar" .) || exit 1
publish "$WORK/forbricbreathforge.jar"

step "result"
echo "[kernel] ✅ built forbriclive.jar, forbricnestforge.jar, forbricbreathforge.jar in $OUT"
