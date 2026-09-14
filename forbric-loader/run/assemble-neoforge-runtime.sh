#!/usr/bin/env bash
# Assemble the Knot-loaded NeoForge runtime jar that Forbric loads at runtime (NeoForge is LGPL — it is
# fetched/assembled here and supplied at runtime, never committed into the Apache-2.0 loader source).
#
# It merges the NeoForge `-universal` jar (net.neoforged.neoforge.* — the API + the targets of the MC
# binary patches) with the FML/bus/coremod runtime libraries into ONE jar carrying a synthetic library
# `fabric.mod.json` (id "neoforge"), so Knot loads every net.neoforged.* class in its transforming
# classloader, co-located with the (Mojmap-native, patched) game classes. module-info/signatures are
# stripped and META-INF/services entries concatenated — EXCEPT org.spongepowered.asm.service.* (FML's
# mixin service bindings would fight the substrate's already-booted sponge-mixin under Knot). The
# universal jar's META-INF/neoforge.mods.toml is PRESERVED: NeoForge's ModSorter.detectSystemMods
# requires the "neoforge" system mod, and Forbric's genuine discovery builds it over this jar.
#
# Output: $OUT (default run/neoforge-runtime/neoforge-runtime.jar), cached — rebuilt only if missing, so
# DELETE IT when bumping the version below or you will keep shipping the old NeoForge.
#
# Pinned to NeoForge 26.2.0.88. The runtime lib versions below come from ITS userdev config.json; bump
# NF_VERSION and those versions together. (Six of the fourteen moved at this bump: fancymodloader
# loader/earlydisplay 11.0.13 -> 11.0.16, JarJarSelector/JarJarMetadata 0.5.0 -> 0.5.1, maven-artifact
# 3.9.9 -> 3.9.16, plexus-utils 3.5.1 -> 3.6.1. The config still lists exactly 40 entries and the same 14
# survive the "already on the classpath" filter, so nothing was added or dropped.)
#
# WHY .88, AND WHAT IT COST. This was 26.2.0.38-beta, chosen because .40-beta deleted
# net.neoforged.neoforge.client.event.ContainerScreenEvent and .43-beta deleted
# PlayerInteractEvent$EntityInteractSpecific, and the packs' jei / sophisticatedcore / sophisticatedbackpacks
# still referenced them. Two things changed:
#   * .57 and up are stable rather than beta, and the title screen brands the build it is running, so a beta
#     carrier says "beta" to every player.
#   * jei 30.32.0.215 now declares neoforge [26.2.0.67,) — the old pin no longer satisfies JEI at all, which
#     inverts the original argument: staying on .38-beta is what freezes the pack, not moving off it.
# Re-measured rather than assumed, .38-beta -> .88 removes 12 classes and adds 24. Of the 98 jars in
# run/client-merged-pack exactly two referenced anything removed (jei and sophisticatedcore, both
# ContainerScreenEvent); the current builds of all three of those mods reference none of it. Every `neoforge`
# versionRange declared anywhere in the pack is open above and below 26.3.0, so .88 satisfies all 45.
#
# The one thing the class diff caught that no mod would have: NeoForge moved client.gui.ModListScreen to
# client.gui.modlist.ModListScreen. The kernel's mods-button redirect names that class, and a wrong name there
# does not throw — see ForeignTypeCarrierTest, which now checks every ForeignType name against these jars.
#
# When bumping again, re-run both directions:
#     diff <(class list of the old neoforge-runtime.jar) <(class list of the new one)
#   and scan the mods for anything only the old side declares; then check every mod's declared `neoforge`
#   versionRange against the build being moved to, so an under-provisioned mod is named up front instead of
#   failing somewhere far away at runtime.
#
# Two libraries in that config.json are deliberately NOT listed below, because they already come from the parent
# classpath and not from this jar: they are the loader's own dependencies (forbric-loader declares both, and the
# installer stages them as `classpath` libraries), so the parent-loaded copy is the one NeoForge ends up seeing.
# Bumping NeoForge therefore means checking them, not copying them:
#   org.ow2.asm            .88 wants 9.10.1; forbric-loader/gradle.properties is 9.10.1. Match.
#   com.electronwill.night-config
#                          .88 wants 3.9.0; this tree pins 3.8.1. Measured again at this bump, not assumed: the
#                          universal jar plus fancymodloader loader 11.0.16 reference 70 distinct NightConfig
#                          members across 19 classes, and 3.8.1 provides all 70. 3.9.0 removes nothing and adds
#                          8 classes, none referenced. 3.8.1 stays, which also keeps --offline builds working.
#                          Re-run that comparison on the next bump: a NightConfig mismatch here is not a link
#                          error, it is the StampedConfig.valueMap() class of failure that took a whole session
#                          to find last time.
set -euo pipefail

NF_VERSION="${NF_VERSION:-26.2.0.88}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/neoforge-runtime/neoforge-runtime.jar}"
WORK="${WORK:-$HERE/neoforge-runtime/work}"
BRIDGE_OUT="${BRIDGE_OUT:-$HERE/neoforge-runtime/forbric-bridge-neoforge.jar}"
BRIDGE_SRC="$HERE/bridge-src-neoforge"
NFRT_CACHE="$HOME/.neoformruntime/artifacts"

# The -universal jar is produced/cached by NeoFormRuntime (NFRT) when the patched MC is built; point
# UNIVERSAL at it, or drop it in the nfrt cache. It is NOT fetched here (gradle-module-routed, no bare jar).
UNIVERSAL="${UNIVERSAL:-$NFRT_CACHE/net/neoforged/neoforge/$NF_VERSION/neoforge-$NF_VERSION-universal.jar}"

if [ ! -f "$UNIVERSAL" ]; then echo "universal jar not found: $UNIVERSAL (set UNIVERSAL=...)" >&2; exit 2; fi

mkdir -p "$WORK/dl" "$(dirname "$OUT")"

if [ -f "$OUT" ]; then echo "[assemble] runtime up-to-date: $OUT"; fi

# Runtime libraries NOT already on the MC 26.2 + Forbric classpath (from neoforge userdev config.json).
NEOFORGED="https://maven.neoforged.net/releases"
CENTRAL="https://repo1.maven.org/maven2"
declare -a LIBS=(
 "$NEOFORGED|net/neoforged/fancymodloader/loader/11.0.16/loader-11.0.16.jar"
 "$NEOFORGED|net/neoforged/fancymodloader/earlydisplay/11.0.16/earlydisplay-11.0.16.jar"
 "$NEOFORGED|net/neoforged/bus/8.0.5/bus-8.0.5.jar"
 "$NEOFORGED|net/neoforged/accesstransformers/11.0.2/accesstransformers-11.0.2.jar"
 "$NEOFORGED|net/neoforged/accesstransformers/at-parser/11.0.2/at-parser-11.0.2.jar"
 "$NEOFORGED|net/neoforged/JarJarSelector/0.5.1/JarJarSelector-0.5.1.jar"
 "$NEOFORGED|net/neoforged/JarJarMetadata/0.5.1/JarJarMetadata-0.5.1.jar"
 "$NEOFORGED|net/neoforged/mergetool/2.0.7/mergetool-2.0.7-api.jar"
 "$NEOFORGED|net/neoforged/srgutils/1.0.10/srgutils-1.0.10.jar"
 "$CENTRAL|net/jodah/typetools/0.6.3/typetools-0.6.3.jar"
 "$CENTRAL|net/minecrell/terminalconsoleappender/1.3.0/terminalconsoleappender-1.3.0.jar"
 "$CENTRAL|org/apache/maven/maven-artifact/3.9.16/maven-artifact-3.9.16.jar"
 "$CENTRAL|org/codehaus/plexus/plexus-utils/3.6.1/plexus-utils-3.6.1.jar"
 "$CENTRAL|org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar"
)
if [ ! -f "$OUT" ]; then
echo "[assemble] fetching ${#LIBS[@]} NeoForge runtime libs ..."
for spec in "${LIBS[@]}"; do
  repo="${spec%%|*}"; path="${spec#*|}"; out="$WORK/dl/$(basename "$path")"
  [ -f "$out" ] && continue
  code=$(curl -sS -L -o "$out" -w "%{http_code}" "$repo/$path")
  [ "$code" = "200" ] && [ -s "$out" ] || { echo "  FAIL($code) $path" >&2; exit 3; }
done

echo "[assemble] merging universal + libs -> $OUT"
# Merge exactly the LIBS above, by name — never everything in $WORK/dl. That directory is a download cache that
# outlives a version bump: after NF 26.2.0.7-beta -> 26.2.0.64 it held BOTH loader-11.0.13.jar and
# loader-11.0.16.jar, and a glob would have layered the stale one in on top (sorted() puts .13 before .16, and
# later wins). It also collects annotations-24.1.0.jar, which the bridge compile below drops there and which has
# no business being inside the runtime jar at all.
LIB_NAMES="$(for spec in "${LIBS[@]}"; do basename "${spec#*|}"; done | paste -sd: -)"
UNIVERSAL="$UNIVERSAL" DL="$WORK/dl" OUT="$OUT" NF_VERSION="$NF_VERSION" LIB_NAMES="$LIB_NAMES" python3 - <<'PY'
import os, zipfile
uni, dl, out = os.environ["UNIVERSAL"], os.environ["DL"], os.environ["OUT"]
nf_version = os.environ["NF_VERSION"]
order = [uni] + [os.path.join(dl, n) for n in os.environ["LIB_NAMES"].split(":") if n]
for jar in order:
    if not os.path.isfile(jar): raise SystemExit(f"[assemble] missing merge input: {jar}")
files, services = {}, {}
def skip(n, is_universal):
    if n.endswith('/'): return True
    if n == 'module-info.class' or n.endswith('/module-info.class'): return True
    if n == 'META-INF/MANIFEST.MF': return True
    # The universal jar's neoforge.mods.toml is the "neoforge" system mod's identity — keep it.
    if n == 'META-INF/neoforge.mods.toml' and not is_universal: return True
    if n == 'META-INF/mods.toml': return True
    # FML's sponge-mixin service bindings must not reach Knot's ServiceLoader (substrate owns mixin).
    if n.startswith('META-INF/services/org.spongepowered.asm.service.'): return True
    if n.startswith('META-INF/jarjar/'): return True
    ext = n.rsplit('.', 1)[-1].upper()
    if n.startswith('META-INF/') and ext in ('SF', 'RSA', 'DSA', 'EC'): return True
    return False
for jar in order:
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            n = info.filename
            if skip(n, jar == uni): continue
            data = z.read(info)
            if n.startswith('META-INF/services/'): services[n] = services.get(n, b'') + data + b'\n'
            else: files[n] = data
fmj = b'{\n  "schemaVersion": 1,\n  "id": "neoforge",\n  "version": "26.2.0",\n  "name": "NeoForge runtime (via Forbric)",\n  "environment": "*"\n}\n'
# FML's JarVersionLookupHandler resolves component versions from the module descriptor's rawVersion or the
# package Implementation-Version (substrate patch 0004 serves the latter under Knot). Without a manifest
# version, LanguageProviderLoader throws "Failed to find implementation version for language provider javafml".
manifest = ("Manifest-Version: 1.0\r\n"
            "Implementation-Title: NeoForge\r\n"
            f"Implementation-Version: {nf_version}\r\n"
            "Automatic-Module-Name: neoforge\r\n\r\n").encode()
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('META-INF/MANIFEST.MF', manifest)
    for n, data in files.items():
        if not n.startswith('META-INF/services/'): z.writestr(n, data)
    for n, data in services.items(): z.writestr(n, data)
    z.writestr('fabric.mod.json', fmj)
print(f"[assemble] wrote {out} ({os.path.getsize(out)/1e6:.2f} MB)")
PY
fi

# --- Forbric NeoForge bridge mod: a genuine raw NeoForge @Mod compiled here against the runtime-supplied ---
# NeoForge (never redistributed). Its RegisterEvent listener opens the Fabric-content window inside NeoForge's
# REAL registration span; it enters the game like any downloaded NeoForge mod (discovered -> layered).
PATCHED="${PATCHED:-$HERE/neoforge-patched/patched-mc-neoforge-26.2.jar}"
if [ ! -f "$BRIDGE_OUT" ] && [ -d "$BRIDGE_SRC" ] && [ -f "$PATCHED" ]; then
  echo "[assemble] compiling forbric NeoForge bridge mod ..."
  BW="$WORK/bridge-classes"; rm -rf "$BW"; mkdir -p "$BW"
  ANNOT="$WORK/dl/annotations-24.1.0.jar"
  [ -f "$ANNOT" ] || curl -sS -L -o "$ANNOT" "$CENTRAL/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar"
  MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
  VLIBS="$(find "$MC/libraries" -name '*.jar' 2>/dev/null | paste -sd: -)"
  javac --release 17 -proc:none -cp "$OUT:$PATCHED:$ANNOT:$VLIBS" -d "$BW" "$(find "$BRIDGE_SRC" -name '*.java' | head -1)"
  mkdir -p "$BW/META-INF"
  cp "$BRIDGE_SRC/META-INF/neoforge.mods.toml" "$BW/META-INF/neoforge.mods.toml"
  (cd "$BW" && jar --create --file "$BRIDGE_OUT" .)
  echo "[assemble] wrote $BRIDGE_OUT"
elif [ ! -f "$PATCHED" ]; then
  echo "[assemble] NOTE: NeoForge-patched MC absent ($PATCHED) — skipping bridge compile (needed for the genuine lifecycle)"
fi
echo "[assemble] done."
