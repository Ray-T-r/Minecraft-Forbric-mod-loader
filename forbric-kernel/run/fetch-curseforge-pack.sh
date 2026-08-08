#!/usr/bin/env bash
# Materialise a CurseForge modpack zip into a Forbric rundir.
#
# A CurseForge pack zip contains NO mods — only manifest.json (projectID/fileID pairs), modlist.html and
# overrides/. The mods must be fetched from CurseForge's own endpoint, which needs no API key:
#
#   https://www.curseforge.com/api/v1/mods/<projectID>/files/<fileID>/download
#     -> 307 edge.forgecdn.net -> 302 mediafilez.forgecdn.net/files/<first4>/<rest>/<Name>.jar -> 200
#
# TWO TRAPS, both of which have bitten this project:
#
#  1. NONE of those three responses carries a Content-Disposition header. `curl -OJ` therefore falls back to the
#     URL's last path segment, which is the literal word "download" — so every file is written to ONE file and
#     they overwrite each other. Observed: 66 reported successes, one file on disk. The real filename exists only
#     in the FINAL redirect URL's path, percent-encoded. Hence the HEAD pass below.
#
#  2. CurseForge answers rate-limits and dead file IDs with an HTML error page at HTTP 200. A 3 KB "<html>" named
#     *.jar lands in mods/, is silently ignored by discovery (no loader manifest), and the mod is simply absent
#     with no log line anywhere. Every artifact is therefore validated as a real archive before it counts.
#
# Jars are classified STRUCTURALLY, by what manifest they carry, never by filename — a pack's manifest freely
# mixes mods with Iris shaderpacks, and a shaderpack dropped into mods/ is ignored in silence.
#
# Usage: fetch-curseforge-pack.sh <pack.zip> <rundir>
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

PACK="${1:?usage: fetch-curseforge-pack.sh <pack.zip> <rundir>}"
RUNDIR="${2:?usage: fetch-curseforge-pack.sh <pack.zip> <rundir>}"
WORK="$BUILD/packfetch"
DL="$WORK/dl"

[ -f "$PACK" ] || { echo "[kernel] FATAL: no such pack zip: $PACK" >&2; exit 2; }

# Clear the unpacked pack but KEEP dl/ — downloads are the slow part and are re-validated individually below,
# so a re-run after a staging fix costs nothing.
find "$WORK" -mindepth 1 -maxdepth 1 ! -name dl -exec rm -rf {} + 2>/dev/null
mkdir -p "$DL" "$RUNDIR/mods" "$RUNDIR/config" "$RUNDIR/shaderpacks"
rm -rf "$RUNDIR/_unclassified"

step "unpack $(basename "$PACK")"
# ditto, not unzip: CurseForge/Modrinth overrides routinely carry CJK filenames, and unzip assumes CP437 when the
# UTF-8 flag is unset, mangling them. python's zipfile is the portable twin.
ditto -xk "$PACK" "$WORK" 2>/dev/null \
  || python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$PACK" "$WORK" \
  || { echo "[kernel] FATAL: could not unpack $PACK" >&2; exit 2; }
[ -f "$WORK/manifest.json" ] || { echo "[kernel] FATAL: no manifest.json — is this a CurseForge pack?" >&2; exit 2; }

python3 - "$WORK/manifest.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
mc = m.get("minecraft", {})
print("[kernel] pack:    %s %s" % (m.get("name"), m.get("version")))
print("[kernel] mc:      %s" % mc.get("version"))
print("[kernel] loaders: %s" % ", ".join(l.get("id", "?") for l in mc.get("modLoaders", [])))
print("[kernel] files:   %d" % len(m.get("files", [])))
PY

TOTAL=$(python3 -c "import json;print(len(json.load(open('$WORK/manifest.json'))['files']))")

step "download $TOTAL file(s)"
python3 -c "
import json
for f in json.load(open('$WORK/manifest.json'))['files']:
    print('%s\t%s' % (f['projectID'], f['fileID']))
" > "$WORK/files.tsv"

fail=0; n=0
while IFS=$'\t' read -r pid fid; do
  n=$((n + 1))
  api="https://www.curseforge.com/api/v1/mods/$pid/files/$fid/download"

  # 1. HEAD-follow purely to learn the real filename from the final URL. Nothing is written.
  eff=$(curl -sIL --max-time 60 -o /dev/null -w '%{url_effective}' "$api")
  name=$(python3 - "$eff" <<'PY'
import os, sys, urllib.parse
print(os.path.basename(urllib.parse.unquote(urllib.parse.urlparse(sys.argv[1]).path)))
PY
)
  if [ -z "$name" ] || [ "$name" = "download" ]; then
    printf '[kernel] FAIL %3d/%s  %s/%s: no filename in final URL (%s)\n' "$n" "$TOTAL" "$pid" "$fid" "$eff"
    fail=$((fail + 1)); continue
  fi

  # 2. Re-follow from the API url (the CDN url may be signed) and write to the LEARNED name.
  #    Skip anything already downloaded and still valid, so a re-run after a classification fix is cheap.
  if [ -f "$DL/$name" ] && unzip -l "$DL/$name" >/dev/null 2>&1; then
    printf '[kernel] have %3d/%s  %s\n' "$n" "$TOTAL" "$name"; continue
  fi
  if ! curl -sL --fail --max-time 300 --retry 3 --retry-delay 2 -o "$DL/$name" "$api"; then
    printf '[kernel] FAIL %3d/%s  %s -> transfer error\n' "$n" "$TOTAL" "$name"
    fail=$((fail + 1)); continue
  fi

  # 3. Validate: it must be a real archive, not an HTML error page served at HTTP 200.
  if ! unzip -l "$DL/$name" >/dev/null 2>&1; then
    printf '[kernel] FAIL %3d/%s  %s is not an archive (%s)\n' "$n" "$TOTAL" "$name" \
      "$(head -c 48 "$DL/$name" | tr -d '\0' | tr '\n' ' ')"
    fail=$((fail + 1)); continue
  fi
  sz=$(stat -f%z "$DL/$name" 2>/dev/null || stat -c%s "$DL/$name")
  if [ "${sz:-0}" -lt 1024 ]; then
    printf '[kernel] FAIL %3d/%s  %s is only %s bytes\n' "$n" "$TOTAL" "$name" "$sz"
    fail=$((fail + 1)); continue
  fi
  printf '[kernel] ok   %3d/%s  %s (%s bytes)\n' "$n" "$TOTAL" "$name" "$sz"
done < "$WORK/files.tsv"

if [ "$fail" -ne 0 ]; then
  echo "[kernel] FAIL $fail of $TOTAL download(s) did not produce a valid archive" >&2
  exit 1
fi

step "classify by manifest, not by filename"
mods=0; shaders=0; other=0
for f in "$DL"/*; do
  [ -f "$f" ] || continue
  b=$(basename "$f")
  entries=$(unzip -Z1 "$f" 2>/dev/null)
  # here-strings, NOT `printf … | grep -q`: under `set -o pipefail` a `grep -q` that matches exits immediately,
  # the writer takes SIGPIPE (141), and pipefail reports the whole pipeline as FAILED — so a jar whose manifest
  # appears early in a long listing is classified as having no manifest at all. That bug put spark and all seven
  # Macaw's mods in _unclassified on the first run, silently, while 55 identical-looking jars went through.
  if grep -qxE 'fabric\.mod\.json|META-INF/(neoforge\.)?mods\.toml' <<< "$entries"; then
    cp "$f" "$RUNDIR/mods/$b"; mods=$((mods + 1))
  elif grep -qE '^shaders/' <<< "$entries"; then
    # An Iris shaderpack, not a mod. In mods/ it carries no loader manifest, so discovery ignores it without a
    # word and the user concludes the shaders are broken. Iris scans shaderpacks/ itself.
    cp "$f" "$RUNDIR/shaderpacks/$b"; shaders=$((shaders + 1))
  else
    mkdir -p "$RUNDIR/_unclassified"; cp "$f" "$RUNDIR/_unclassified/$b"; other=$((other + 1))
    echo "[kernel] WARN $b declares no loader manifest and no shaders/ — parked in _unclassified/"
  fi
done
echo "[kernel] mods=$mods shaderpacks=$shaders unclassified=$other"

step "stage overrides"
# overrides/config/** -> <RUNDIR>/config, which is FMLPaths.CONFIGDIR (PassiveSeeder.java). Everything else in
# overrides/ is vanilla's or the mods' own business and drops straight into the rundir root.
if [ -d "$WORK/overrides" ]; then
  for entry in "$WORK/overrides"/*; do
    [ -e "$entry" ] || continue
    name=$(basename "$entry")
    if [ -d "$entry" ]; then ditto "$entry" "$RUNDIR/$name"; else cp "$entry" "$RUNDIR/$name"; fi
    echo "[kernel] staged overrides/$name"
  done
fi

# mods/ is scanned NON-RECURSIVELY, top-level *.jar only — a nested jar is invisible to discovery.
find "$RUNDIR/mods" -mindepth 2 -name '*.jar' -exec echo '[kernel] WARN nested jar is invisible to discovery: {}' \;

step "result"
echo "[kernel] $RUNDIR/mods:        $(ls -1 "$RUNDIR/mods" 2>/dev/null | wc -l | tr -d ' ') jar(s)"
echo "[kernel] $RUNDIR/shaderpacks: $(ls -1 "$RUNDIR/shaderpacks" 2>/dev/null | wc -l | tr -d ' ') pack(s)"
echo "[kernel] $RUNDIR/config:      $(ls -1 "$RUNDIR/config" 2>/dev/null | wc -l | tr -d ' ') entr(ies)"
echo "[kernel] ✅ pack materialised — next: RUNDIR=$RUNDIR $KERNEL/run/launch-kernel-client.sh"
