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
# Pinned to NeoForge 26.2.0.38-beta. The runtime lib versions below come from ITS userdev config.json; bump
# NF_VERSION and those versions together. (26.2.0.38-beta happens to pin exactly what 26.2.0.7-beta did — the
# only thing that moved between them is neoform 26.2-1 -> 26.2-2, which is an input to the PATCHED-MC build,
# not to this list. Do not infer from that that the list is inert: at 26.2.0.64 six of them move.)
#
# WHY .38-beta AND NOT THE NEWEST. 26.2 goes up to 26.2.0.64, and .57+ are stable rather than beta, so the
# newest is tempting. It is also wrong for this instance, measured rather than guessed:
#   * 26.2.0.40-beta deletes net.neoforged.neoforge.client.event.ContainerScreenEvent (folded into ScreenEvent).
#     jei-26.2-neoforge-30.14.0.87 and sophisticatedcore-26.2-1.4.90.2199 both still reference it.
#   * 26.2.0.43-beta deletes PlayerInteractEvent$EntityInteractSpecific. sophisticatedbackpacks-26.2-3.25.83.2018
#     still references it.
#   * (26.2.0.45-beta..53-beta also move client.gui.ModListScreen to client.gui.modlist; nothing in the packs
#     touches that one.)
# .38-beta is therefore the highest build on which every mod in run/client-merged-pack still LINKS, and it is
# already high enough to satisfy every neoforge versionRange those mods declare — including jei's
# [26.2.0.16-beta,), the one range 26.2.0.7-beta failed. Going past .38 buys nothing until jei and the
# sophisticated* pair publish builds compiled against the new event classes; when they do, re-run the check:
#     diff <(class list of the old neoforge-runtime.jar) <(class list of the new one)
#   and scan the mods for anything that only the old side declares. forbric-kernel/run/gate-m9-client.sh keeps
#   the version audit honest from the other direction (it asserts WHICH mods are under-provisioned).
#
# Two libraries in that config.json are deliberately NOT listed below, because the KERNEL supplies them on the
# parent classpath (both are pinned to ALWAYS_PARENT in DelegationPolicy, so the kernel's copy is the only one
# NeoForge can see) — bumping NeoForge means checking them, not copying them:
#   org.ow2.asm            .38-beta wants 9.9.1, .64 wants 9.10.1; forbric-kernel/gradle.properties is 9.10.1.
#   com.electronwill.night-config
#                          .38-beta wants 3.8.3, .64 wants 3.9.0; the kernel pins 3.8.1. Measured rather than
#                          assumed: of the 70 distinct NightConfig members referenced by the universal jar plus
#                          fancymodloader loader, ZERO are missing from 3.8.1, and 3.9.0 is purely additive over
#                          it (8 new classes — FileWatcher$NamedDaemonThreadFactory, the io.IoUtils family,
#                          TomlVersion — none referenced, none removed). 3.8.1 stays, which also keeps the
#                          kernel's --offline builds working. Re-run that comparison on the next bump: a
#                          NightConfig mismatch here is not a link error, it is the StampedConfig.valueMap()
#                          class of failure that took a whole session to find last time.
set -euo pipefail

NF_VERSION="${NF_VERSION:-26.2.0.38-beta}"
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
 "$NEOFORGED|net/neoforged/fancymodloader/loader/11.0.13/loader-11.0.13.jar"
 "$NEOFORGED|net/neoforged/fancymodloader/earlydisplay/11.0.13/earlydisplay-11.0.13.jar"
 "$NEOFORGED|net/neoforged/bus/8.0.5/bus-8.0.5.jar"
 "$NEOFORGED|net/neoforged/accesstransformers/11.0.2/accesstransformers-11.0.2.jar"
 "$NEOFORGED|net/neoforged/accesstransformers/at-parser/11.0.2/at-parser-11.0.2.jar"
 "$NEOFORGED|net/neoforged/JarJarSelector/0.5.0/JarJarSelector-0.5.0.jar"
 "$NEOFORGED|net/neoforged/JarJarMetadata/0.5.0/JarJarMetadata-0.5.0.jar"
 "$NEOFORGED|net/neoforged/mergetool/2.0.7/mergetool-2.0.7-api.jar"
 "$NEOFORGED|net/neoforged/srgutils/1.0.10/srgutils-1.0.10.jar"
 "$CENTRAL|net/jodah/typetools/0.6.3/typetools-0.6.3.jar"
 "$CENTRAL|net/minecrell/terminalconsoleappender/1.3.0/terminalconsoleappender-1.3.0.jar"
 "$CENTRAL|org/apache/maven/maven-artifact/3.9.9/maven-artifact-3.9.9.jar"
 "$CENTRAL|org/codehaus/plexus/plexus-utils/3.5.1/plexus-utils-3.5.1.jar"
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
