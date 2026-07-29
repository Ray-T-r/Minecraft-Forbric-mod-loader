#!/usr/bin/env bash
# For a tri-in-one instance (BOTH forge-runtime.jar and neoforge-runtime.jar staged, joining ONE shared JPMS
# game layer — see ForbricGameLayer.defineShared), any THIRD-PARTY class both runtimes independently bundle
# (maven-artifact, terminalconsoleappender, jetbrains/jspecify annotations, …) becomes a SPLIT PACKAGE across
# two modules in the same layer, which java.lang.module.Configuration.resolve() rejects
# (ResolutionException: Module X contains package P, module Y exports package P to X).
#
# Fix: strip every class NEOFORGE-runtime.jar shares with forge-runtime.jar, keeping one copy (Forge's) as the
# sole provider. Produces a deduped COPY — the originals (used standalone by the single-family Stage A
# profiles) are untouched.
#
# Usage: ./dedupe-runtime-overlap.sh <forge-runtime.jar> <neoforge-runtime.jar> <out-deduped-neoforge-runtime.jar>
set -euo pipefail
FORGE="${1:?usage: dedupe-runtime-overlap.sh <forge-runtime.jar> <neoforge-runtime.jar> <out.jar>}"
NEO="${2:?}"
OUT="${3:?}"

FORGE="$FORGE" NEO="$NEO" OUT="$OUT" python3 - <<'PY'
import os, zipfile
forge, neo, out = os.environ["FORGE"], os.environ["NEO"], os.environ["OUT"]

# Split-package is a PACKAGE-level JPMS violation: a module "exports" package P if it has ANY class there.
# Two independently-assembled runtimes rarely agree on the exact class-file SET within a shared third-party
# package (differing lib versions add/drop inner classes), so per-filename dedup can leave a residual class
# that still makes the package non-empty on both sides. Strip by PACKAGE, not by exact file match.
with zipfile.ZipFile(forge) as f:
    forge_pkgs = {e.rsplit('/', 1)[0] for e in f.namelist() if e.endswith('.class')}

kept, dropped = 0, 0
with zipfile.ZipFile(neo) as z, zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as w:
    for info in z.infolist():
        n = info.filename
        if n.endswith('.class') and n.rsplit('/', 1)[0] in forge_pkgs:
            dropped += 1
            continue
        w.writestr(info, z.read(n))
        kept += 1

print(f"[dedupe] {os.path.basename(neo)}: kept {kept} entries, dropped {dropped} class(es) in packages shared with {os.path.basename(forge)} -> {out}")
PY
