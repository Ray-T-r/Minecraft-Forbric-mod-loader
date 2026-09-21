#!/usr/bin/env bash
# Boot the MC 26.2 DEDICATED SERVER as PURE VANILLA — no kernel, no merged base, no loader of any kind.
#
# This is the control arm of gate-m31. A parity gate that compares Forbric against a remembered description of
# vanilla is not a comparison; it needs vanilla actually running, on the same machine and the same JVM, so the
# only difference left between the two arms is the thing under test.
#
# The jar is the player's own installed client jar. In 26.2 it carries net.minecraft.server.Main and Mojang
# names, so it IS the dedicated server — nothing is downloaded and no repository artifact is needed. The
# libraries come from the same versions/26.2/26.2.json that launch-kernel-server.sh reads, so both arms link
# against one set of bytes.
#
# Usage: [RUNDIR=…] [VANILLA_JVM=…] ./launch-vanilla-server.sh [extra game args]
set -uo pipefail

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/.." && pwd)"
RUNDIR="${RUNDIR:-$KERNEL/run/parity-vanilla}"
mkdir -p "$RUNDIR"

JAR="${VANILLA_JAR:-$MC/versions/26.2/26.2.jar}"
[ -f "$JAR" ] || { echo "[vanilla-launch] FATAL: no vanilla jar at $JAR (set VANILLA_JAR or MC_DIR)" >&2; exit 3; }

# EULA (dedicated server refuses to start otherwise). Kernel testing only — the user has accepted MC's EULA.
[ -f "$RUNDIR/eula.txt" ] || echo "eula=true" > "$RUNDIR/eula.txt"

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
[ -n "$VANILLA_CP" ] || { echo "[vanilla-launch] FATAL: resolved no libraries from $MC/versions/26.2/26.2.json" >&2; exit 3; }

# jline (server console) is shipped in the MC libraries tree but not listed in 26.2.json's libraries array;
# add it explicitly so the dedicated-server console handler doesn't NoClassDefFound. Same line as the kernel's.
JLINE="$(find "$MC/libraries/org/jline" -name 'jline-*-3.25.1.jar' 2>/dev/null | paste -sd: -)"

echo "[vanilla-launch] rundir=$RUNDIR"
echo "[vanilla-launch] jar=$JAR"
cd "$RUNDIR"
exec java -Djava.awt.headless=true ${VANILLA_JVM:-} \
  -cp "$JAR:$VANILLA_CP${JLINE:+:$JLINE}" net.minecraft.server.Main --nogui "$@"
