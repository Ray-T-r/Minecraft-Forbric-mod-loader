#!/usr/bin/env bash
# Assemble the Knot-loaded traditional-MinecraftForge runtime jar that Forbric loads at runtime (Forge is LGPL —
# it is fetched/assembled here and supplied at runtime, never committed into the Apache-2.0 loader source).
#
# It merges the Forge `-universal` jar (net.minecraftforge.* — the API + the targets of the MC binary patches)
# with the FML/ModLauncher/eventbus/securemodules runtime libraries into ONE jar carrying a synthetic library
# `fabric.mod.json` (id "forge"), so Knot loads every net.minecraftforge.* class in its transforming classloader,
# co-located with the (Mojmap-native, Forge-patched) game classes. module-info/signatures/mods.toml are stripped
# and META-INF/services entries concatenated.
#
# Mixin is DELIBERATELY EXCLUDED here: Forge ships org.spongepowered:mixin:0.8.7, which would collide with Fabric's
# sponge-mixin fork already on the Knot classpath. Forge-mod mixin support is wired separately (Stage 5).
#
# Output: $OUT (default run/forge-runtime/forge-runtime.jar), cached — rebuilt only if missing.
# Pinned to Forge 26.2-65.0.1 (lib versions below come from its userdev config.json); bump FORGE_VERSION + the
# versions together for a different Forge build.
set -euo pipefail

FORGE_VERSION="${FORGE_VERSION:-26.2-65.0.1}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-$HERE/forge-runtime/forge-runtime.jar}"
WORK="${WORK:-$HERE/forge-runtime/work}"
BRIDGE_OUT="${BRIDGE_OUT:-$HERE/forge-runtime/forbric-bridge.jar}"
BRIDGE_SRC="$HERE/bridge-src"
PATCHED="${PATCHED:-$HERE/forge-patched/patched-mc-forge-26.2.jar}"
FORGEMVN=https://maven.minecraftforge.net
CENTRAL=https://repo1.maven.org/maven2

if [ -f "$OUT" ] && [ -f "$BRIDGE_OUT" ]; then echo "[assemble] up-to-date: $OUT + $BRIDGE_OUT"; exit 0; fi
mkdir -p "$WORK/dl" "$(dirname "$OUT")"

if [ ! -f "$OUT" ]; then

# universal jar (net.minecraftforge.* only) + the runtime libs NOT bundled in it. From userdev config.json,
# minus what already lives on the MC 26.2 + Forbric classpath (asm/guava/log4j/gson/slf4j) and minus mixin.
declare -a GAVS=(
 "net.minecraftforge:forge:$FORGE_VERSION:universal"
 "net.minecraftforge:fmlcore:$FORGE_VERSION"
 "net.minecraftforge:fmlloader:$FORGE_VERSION"
 "net.minecraftforge:fmlearlydisplay:$FORGE_VERSION"
 "net.minecraftforge:javafmllanguage:$FORGE_VERSION"
 "net.minecraftforge:lowcodelanguage:$FORGE_VERSION"
 "net.minecraftforge:mclanguage:$FORGE_VERSION"
 "net.minecraftforge:forge-transformers:$FORGE_VERSION"
 "net.minecraftforge:modlauncher:10.2.4"
 "net.minecraftforge:securemodules:2.2.24"
 "net.minecraftforge:bootstrap:2.1.8"
 "net.minecraftforge:bootstrap-api:2.1.8"
 "net.minecraftforge:eventbus:7.0.1"
 "net.minecraftforge:accesstransformers:8.2.2"
 "net.minecraftforge:forgespi:8.0.0"
 "net.minecraftforge:coremods-api:5.3.0"
 "net.minecraftforge:mergetool-api:1.0"
 "net.minecraftforge:JarJarFileSystems:0.4.2"
 "net.minecraftforge:JarJarSelector:0.4.2"
 "net.minecraftforge:JarJarMetadata:0.4.2"
 "net.minecraftforge:roimfs:1.0.0"
 "net.minecraftforge:unsafe:0.9.2"
 "com.electronwill.night-config:toml:3.7.4"
 "com.electronwill.night-config:core:3.7.4"
 "org.apache.maven:maven-artifact:3.8.8"
 "net.minecrell:terminalconsoleappender:1.2.0"
 "org.jspecify:jspecify:1.0.0"
 "com.google.guava:failureaccess:1.0.3"
)

echo "[assemble] fetching ${#GAVS[@]} Forge runtime artifacts ..."
for gav in "${GAVS[@]}"; do
  g="${gav%%:*}"; rest="${gav#*:}"; a="${rest%%:*}"; rest2="${rest#*:}"; v="${rest2%%:*}"
  cls=""; case "$gav" in *:*:*:*) cls="-${gav##*:}";; esac
  gp="${g//.//}"
  rel="$gp/$a/$v/$a-$v$cls.jar"
  out="$WORK/dl/$a-$v$cls.jar"
  [ -f "$out" ] && continue
  code=$(curl -sS -L -o "$out" -w "%{http_code}" "$FORGEMVN/$rel")
  if [ "$code" != "200" ] || [ ! -s "$out" ]; then code=$(curl -sS -L -o "$out" -w "%{http_code}" "$CENTRAL/$rel"); fi
  [ "$code" = "200" ] && [ -s "$out" ] || { echo "  FAIL($code) $gav" >&2; rm -f "$out"; exit 3; }
done

echo "[assemble] merging universal + libs -> $OUT"
DL="$WORK/dl" OUT="$OUT" FORGE_VERSION="$FORGE_VERSION" python3 - <<'PY'
import os, glob, zipfile
dl, out = os.environ["DL"], os.environ["OUT"]
forge_version = os.environ["FORGE_VERSION"]
order = sorted(glob.glob(f"{dl}/*.jar"))  # universal sorts in naturally; class set is disjoint
files, services = {}, {}
# Per-PACKAGE manifest sections (Name: some/pkg/) carry the Specification/Implementation versions that
# FML's JarVersionLookupHandler reads via java.lang.Package (ForgeVersion/MCPVersion/language providers).
# Preserve them from every source jar; drop per-FILE digest sections (signing leftovers).
pkg_sections = {}
def harvest_manifest(data):
    text = data.decode('utf-8', 'replace').replace('\r\n', '\n')
    for block in text.split('\n\n'):
        block = block.strip('\n')
        if not block.startswith('Name: '): continue
        name_line = block.split('\n', 1)[0]
        name = name_line[len('Name: '):]
        if name.endswith('/') and name not in pkg_sections:
            pkg_sections[name] = block
def skip(n, is_universal):
    if n.endswith('/'): return True
    if n == 'module-info.class' or n.endswith('/module-info.class'): return True
    if n == 'META-INF/MANIFEST.MF': return True
    # Keep the UNIVERSAL jar's mods.toml: it declares the "forge" system mod that FML's genuine discovery
    # (ModSorter.detectSystemMods) requires. Other jars' mods.toml (fml languages) would collide - drop.
    if n == 'META-INF/mods.toml': return not is_universal
    if n == 'META-INF/neoforge.mods.toml': return True
    if n.startswith('META-INF/jarjar/'): return True
    ext = n.rsplit('.', 1)[-1].upper()
    if n.startswith('META-INF/') and ext in ('SF', 'RSA', 'DSA', 'EC'): return True
    return False
for jar in order:
    is_universal = '-universal' in os.path.basename(jar)
    with zipfile.ZipFile(jar) as z:
        try: harvest_manifest(z.read('META-INF/MANIFEST.MF'))
        except KeyError: pass
        for info in z.infolist():
            n = info.filename
            if skip(n, is_universal): continue
            data = z.read(info)
            if n.startswith('META-INF/services/'): services[n] = services.get(n, b'') + data + b'\n'
            else: files[n] = data
fmj = b'{\n  "schemaVersion": 1,\n  "id": "forge",\n  "version": "26.2.65",\n  "name": "MinecraftForge runtime (via Forbric)",\n  "environment": "*"\n}\n'
# Pinned module name + version: the jar joins Forbric's synthetic GAME module layer (name must be stable,
# not filename-derived). Implementation-Version uses the FML version domain (65.0.1, not 26.2-65.0.1):
# language-provider version checks (mods.toml loaderVersion="[65,)"/"[28,)") compare against it, while the
# "forge" MOD version comes independently from /forge_version.json (${global.forgeVersion}).
fml_version = forge_version.split('-', 1)[1] if '-' in forge_version else forge_version
manifest = ("Manifest-Version: 1.0\r\n"
            # the OFFICIAL universal-jar module name: FML hardcodes layer.findModule("net.minecraftforge.forge")
            # (e.g. ImmediateWindowHandler$DummyProvider.updateModuleReads resolving the NoViz overlay fallback)
            "Automatic-Module-Name: net.minecraftforge.forge\r\n"
            "Implementation-Title: MinecraftForge\r\n"
            f"Implementation-Version: {fml_version}\r\n"
            "Implementation-Vendor: Forbric-assembled (runtime-supplied, not redistributed)\r\n"
            "\r\n").encode()
manifest += ('\r\n'.join(s.replace('\n', '\r\n') + '\r\n' for s in pkg_sections.values()) + '\r\n').encode()
print(f"[assemble] manifest carries {len(pkg_sections)} per-package version sections")
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('META-INF/MANIFEST.MF', manifest)
    for n, data in files.items():
        if not n.startswith('META-INF/services/'): z.writestr(n, data)
    for n, data in services.items(): z.writestr(n, data)
    z.writestr('fabric.mod.json', fmj)
print(f"[assemble] wrote {out} ({os.path.getsize(out)/1e6:.2f} MB)")
PY
fi

# --- Forbric bridge mod: a genuine raw Forge @Mod compiled here against the runtime-supplied Forge -------
# (never redistributed). Its RegisterEvent listener opens the Fabric-content window inside the REAL
# registration span; it enters the game like any downloaded Forge mod (discovered -> wrapped -> layered).
if [ ! -f "$BRIDGE_OUT" ] && [ -d "$BRIDGE_SRC" ]; then
  echo "[assemble] compiling forbric-bridge mod ..."
  BW="$WORK/bridge-classes"; rm -rf "$BW"; mkdir -p "$BW"
  # compile-time only: Forge bytecode references JetBrains annotations; javac needs them resolvable
  ANNOT="$WORK/dl/annotations-24.1.0.jar"
  [ -f "$ANNOT" ] || curl -sS -L -o "$ANNOT" "$CENTRAL/org/jetbrains/annotations/24.1.0/annotations-24.1.0.jar"
  # vanilla libraries (DFU/brigadier/...) from the user's MC install: Forge signatures reference them
  MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
  VLIBS="$(find "$MC/libraries" -name '*.jar' 2>/dev/null | paste -sd: -)"
  javac --release 17 -proc:none -cp "$OUT:$PATCHED:$ANNOT:$VLIBS" -d "$BW" "$(find "$BRIDGE_SRC" -name '*.java' | head -1)"
  mkdir -p "$BW/META-INF"
  cp "$BRIDGE_SRC/META-INF/mods.toml" "$BW/META-INF/mods.toml"
  (cd "$BW" && jar --create --file "$BRIDGE_OUT" .)
  echo "[assemble] wrote $BRIDGE_OUT"
fi
echo "[assemble] done."
