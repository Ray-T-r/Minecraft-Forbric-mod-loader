#!/usr/bin/env bash
# Differential oracle for kernel mod discovery.
#
# Rather than diff the kernel's discovery against the OLD system's discovery (which runs the SAME ported
# ForbricModDiscoverer code — a tautology), this cross-validates the kernel's parser against an INDEPENDENT
# ground-truth extractor (a separate Python reader of fabric.mod.json / mods.toml / neoforge.mods.toml). If the
# two independent parsers agree on the (ecosystem, id) set for a mods/ dir, discovery is proven correct.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). The kernel now REFUSES a MinecraftForge-only jar (one
# with no usable Fabric or NeoForge half): discovery names it on a "[Forbric/Unsupported] refusing <jar> (<id>)" line
# and reports none of its mods. The ground-truth reader is unchanged -- it still reads every META-INF/mods.toml as
# FORGE, because it must not learn the kernel's policy -- so on a set holding such jars (server-merged: forbric-bridge,
# forbriclive, spark; client-merged: forbric-bridge, xaerominimap) the two now differ by exactly those mods, by design.
# That difference passes only as an ASSERTED one: a (FORGE, id) the kernel did not report is accepted when every jar
# declaring it was refused by name on the kernel's own refusal line, the id that line names is one that jar declares,
# the jar declares no Fabric or NeoForge mod, and the kernel reported nothing from it. Every other difference stays a
# failure: a FORGE mod missing with no refusal naming its jar, any Fabric or NeoForge miss, anything spurious, and a
# refusal of a jar the ground truth does not read as a MinecraftForge-only jar in this directory. The PASS line names
# each refused mod and its jar. A FORGE mod the kernel still reports (the carrier forge-runtime.jar's own mods.toml,
# which sits beside its Fabric half) is compared exactly as before.
#
# ORACLE_CONTROL=unrefused is the negative control (gate-m0 step 5b): the kernel's refusal lines are withheld from the
# comparison, so each refused mod is an unexplained miss again and the oracle must FAIL naming exactly those mods.
#
# usage: diff-oracle.sh <mods-dir> [<mods-dir> ...]
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

[ "$#" -ge 1 ] || { echo "usage: diff-oracle.sh <mods-dir> [<mods-dir> ...]"; exit 2; }

kernel_classpath
rc=0
for dir in "$@"; do
  [ -d "$dir" ] || { echo "[oracle] FAIL required directory missing: $dir"; rc=1; continue; }
  out="$BUILD/scan/$(basename "$dir")-$(basename "$(dirname "$dir")").json"
  mkdir -p "$(dirname "$out")"
  # The scan's own output is kept: it is where the kernel names each jar it refuses.
  kernel_scan "$dir" "$out" >"${out%.json}.log"

  python3 - "$dir" "$out" "${out%.json}.log" "${ORACLE_CONTROL:-}" <<'PY'
import json, re, sys, zipfile, pathlib
mods_dir, kernel_json, kernel_log, control = sys.argv[1:5]

# --- independent ground-truth extraction (no night-config, no kernel code) ---
truth = set()  # (ecosystem, id)
declared_by = {}  # (ecosystem, id) -> {jar file name}
jar_decls = {}    # jar file name -> {(ecosystem, id)}
def declare(eco, mid, jar):
    truth.add((eco, mid))
    declared_by.setdefault((eco, mid), set()).add(jar.name)
    jar_decls.setdefault(jar.name, set()).add((eco, mid))
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
                declare("FABRIC", j["id"], jar)
        except Exception:
            pass
    for path, eco in (("META-INF/mods.toml", "FORGE"), ("META-INF/neoforge.mods.toml", "NEOFORGE")):
        if path in names:
            for mid in toml_modids(z.read(path).decode("utf-8", "replace")):
                declare(eco, mid, jar)

# --- kernel's answer ---
k = json.loads(pathlib.Path(kernel_json).read_text())
kernel = {(m["ecosystem"], m["id"]) for m in k["mods"]}
if not truth or not kernel:
    print(f"[oracle] FAIL empty discovery fixture: {mods_dir}")
    sys.exit(1)

# --- the jars the kernel refused as MinecraftForge-only, read off its own refusal line ---
refused = {}  # jar file name -> the mod id that line names
if control != "unrefused":
    for line in pathlib.Path(kernel_log).read_text(errors="replace").splitlines():
        m = re.search(r"\[Forbric/Unsupported\] refusing (.+?) \(([^()]*)\):", line)
        if m:
            refused[m.group(1)] = m.group(2)
reported_from = {m.get("source") for m in k["mods"]}
# A refusal is sound when the ground truth reads the jar as MinecraftForge-only, declaring the id the line names, and
# the kernel reported nothing from it. Any other refusal is a difference of its own.
unsound = {}
for jar, mid in refused.items():
    decls = jar_decls.get(jar, set())
    if not decls or ("FORGE", mid) not in decls or any(eco != "FORGE" for eco, _ in decls):
        unsound[jar] = f"ground truth reads {sorted(decls) if decls else 'no mod'} in it"
    elif jar in reported_from:
        unsound[jar] = "the kernel reported mods from it"

missing = truth - kernel     # ground truth found it, kernel missed it
spurious = kernel - truth     # kernel invented it
name = "/".join(pathlib.Path(mods_dir).parts[-2:])
# A missing FORGE mod is the refusal, asserted: every jar declaring it was refused by name, soundly.
expected = {key for key in missing if key[0] == "FORGE"
            and all(jar in refused and jar not in unsound for jar in declared_by[key])}
unexplained = missing - expected
if not unexplained and not spurious and not unsound:
    note = ""
    if expected:
        named = ", ".join(f"{mid} ({' '.join(sorted(declared_by[(eco, mid)]))})" for eco, mid in sorted(expected))
        note = f"; {len(expected)} MinecraftForge-only mod(s) absent because the kernel refused them by name: {named}"
    print(f"[oracle] PASS {name}: {len(kernel)} mods, kernel ≡ independent ground truth{note}")
    sys.exit(0)
if unexplained:
    print(f"[oracle] FAIL {name}: kernel MISSED {sorted(unexplained)} (no sound refusal names their jar)")
if spurious:
    print(f"[oracle] FAIL {name}: kernel SPURIOUS {sorted(spurious)}")
for jar in sorted(unsound):
    print(f"[oracle] FAIL {name}: kernel REFUSED {jar} ({refused[jar]}), but {unsound[jar]}")
sys.exit(1)
PY
  [ $? -eq 0 ] || rc=1
done
exit $rc
