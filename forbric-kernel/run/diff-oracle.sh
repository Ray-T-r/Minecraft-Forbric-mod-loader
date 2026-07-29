#!/usr/bin/env bash
# Differential oracle for kernel mod discovery.
#
# Rather than diff the kernel's discovery against the OLD system's discovery (which runs the SAME ported
# ForbricModDiscoverer code — a tautology), this cross-validates the kernel's parser against an INDEPENDENT
# ground-truth extractor (a separate Python reader of fabric.mod.json / mods.toml / neoforge.mods.toml). If the
# two independent parsers agree on the (ecosystem, id) set for a mods/ dir, discovery is proven correct.
#
# usage: diff-oracle.sh <mods-dir> [<mods-dir> ...]
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

[ "$#" -ge 1 ] || { echo "usage: diff-oracle.sh <mods-dir> [<mods-dir> ...]"; exit 2; }

kernel_classpath
rc=0
for dir in "$@"; do
  [ -d "$dir" ] || { echo "[oracle] SKIP (no dir): $dir"; continue; }
  out="$BUILD/scan/$(basename "$dir")-$(basename "$(dirname "$dir")").json"
  mkdir -p "$(dirname "$out")"
  kernel_scan "$dir" "$out" >/dev/null

  python3 - "$dir" "$out" <<'PY'
import json, re, sys, zipfile, pathlib
mods_dir, kernel_json = sys.argv[1], sys.argv[2]

# --- independent ground-truth extraction (no night-config, no kernel code) ---
truth = set()  # (ecosystem, id)
def toml_modids(text):
    # independent, but table-context-aware: only `modId = "..."` lines whose enclosing array-table is [[mods]]
    # count as declared mods. modId under [[dependencies.*]] is a dependency reference, not a mod.
    ids, in_mods = [], False
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("[["):
            in_mods = s.startswith("[[mods]]")
            continue
        if s.startswith("["):          # any other table header ends the [[mods]] entry
            in_mods = False
            continue
        if in_mods:
            m = re.match(r'modId\s*=\s*"([^"]+)"', s)
            if m:
                ids.append(m.group(1))
    return ids
for jar in sorted(pathlib.Path(mods_dir).glob("*.jar")):
    try:
        z = zipfile.ZipFile(jar)
    except Exception:
        continue
    names = set(z.namelist())
    if "fabric.mod.json" in names:
        try:
            j = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"))
            if j.get("id"):
                truth.add(("FABRIC", j["id"]))
        except Exception:
            pass
    for path, eco in (("META-INF/mods.toml", "FORGE"), ("META-INF/neoforge.mods.toml", "NEOFORGE")):
        if path in names:
            for mid in toml_modids(z.read(path).decode("utf-8", "replace")):
                truth.add((eco, mid))

# --- kernel's answer ---
k = json.loads(pathlib.Path(kernel_json).read_text())
kernel = {(m["ecosystem"], m["id"]) for m in k["mods"]}

missing = truth - kernel     # ground truth found it, kernel missed it
spurious = kernel - truth     # kernel invented it
name = pathlib.Path(mods_dir).name
if not missing and not spurious:
    print(f"[oracle] PASS {name}: {len(kernel)} mods, kernel ≡ independent ground truth")
    sys.exit(0)
if missing:
    print(f"[oracle] FAIL {name}: kernel MISSED {sorted(missing)}")
if spurious:
    print(f"[oracle] FAIL {name}: kernel SPURIOUS {sorted(spurious)}")
sys.exit(1)
PY
  [ $? -eq 0 ] || rc=1
done
exit $rc
