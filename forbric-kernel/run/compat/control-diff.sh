#!/usr/bin/env bash
# Is this symptom Forbric's, or would it happen on a native loader too?
#
# WHY THIS EXISTS. "Is this Forbric's fault" has been answered by argument, not by experiment. One adversarial
# round rejected 24 of 56 root causes; the single time a native Fabric instance was built to compare against, it
# turned "the mod is broken" into "the kernel froze the registries too early" in one run. Attribution accuracy
# is a product problem in its own right — a wrong verdict costs a day either way, and the mod author gets the
# bug report either way.
#
# So: boot the SAME Fabric mods twice, once on a native Fabric server and once on Forbric, and say which log the
# symptom appears in. Four verdicts, and three of them stop an investigation before it starts:
#
#   FORBRIC-ONLY   the kernel's, and the only one worth debugging here
#   BOTH           upstream's or the mod's own; a native loader does it too
#   NATIVE-ONLY    the kernel is masking something; rare and worth a second look
#   NEITHER        not reproduced by this mod set — the repro is wrong, not the verdict
#
# Fabric mods only, by construction: a native Fabric server cannot load the other two ecosystems, and a
# comparison whose arms run different mods answers nothing.
#
# Usage: ./control-diff.sh <symptom-ERE> [<mod.jar> ...]
#        (with no jars, fabric-api plus Macaw's Bridges — enough to register content and sync a registry)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/../.." && pwd)"
. "$KERNEL/run/lib.sh"

SYMPTOM="${1:-}"
[ -n "$SYMPTOM" ] || { echo "usage: ./control-diff.sh <symptom-ERE> [<mod.jar> ...]" >&2; exit 2; }
shift || true

PORT="${CONTROL_PORT:-25807}"
DL="$RUN_OLD/downloads"
LAUNCHER="$DL/fabric-server-26.2/fabric-server-mc26.2-loader0.19.5-launcher1.1.2.jar"
MODS=("$@")
if [ "${#MODS[@]}" -eq 0 ]; then
  MODS=("$DL/fabric-26.2/fabric-api-0.154.0+26.2.jar" "$DL/fabric-26.2/mcw-bridges-3.1.2-mc26.2fabric.jar")
fi
for f in "$LAUNCHER" "${MODS[@]}"; do
  [ -f "$f" ] || { echo "[control] SKIP-FATAL: missing $f" >&2; exit 3; }
done

NAT="$KERNEL/run/control-native"
FOR="$KERNEL/run/control-forbric"
NLOG="$BUILD/control-native.log"
FLOG="$BUILD/control-forbric.log"
mkdir -p "$BUILD"
kernel_jar

stage() {
  local dir="$1" port="$2"
  reap_stale_server "$dir"
  stash_downloads "$dir" control .fabric
  rm -rf "$dir"
  mkdir -p "$dir/mods"
  restore_downloads "$dir" control .fabric
  cp "${MODS[@]}" "$dir/mods/"
  printf 'eula=true\n' > "$dir/eula.txt"
  printf 'server-port=%s\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft\\:flat\nmax-tick-time=-1\nview-distance=6\npause-when-empty-seconds=0\n' \
    "$port" > "$dir/server.properties"
}

# Boot, wait for Done or a failure to start, stop. Both arms get the same treatment; anything else and the
# comparison is between two different experiments.
boot() {
  local log="$1" dir="$2"; shift 2
  : > "$log"
  (
    for i in $(seq 1 240); do
      grep -aqE 'Done \(' "$log" && break
      grep -aqE 'Failed to start the minecraft server|Exception in thread "main"' "$log" && break
      sleep 1
    done
    sleep 8
    echo stop
  ) | "$@" > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$dir" "$pid"
  await_server "$pid" "$log" 300 45
  rm -f "$dir/.forbric-gate.pid"
}

step "arm 1: a native Fabric server with $(printf '%s ' "${MODS[@]##*/}")"
stage "$NAT" "$PORT"
( cd "$NAT" && boot "$NLOG" "$NAT" java -Xmx2G -jar "$LAUNCHER" --nogui )

step "arm 2: Forbric, same mods, same properties"
stage "$FOR" "$((PORT + 1))"
RUNDIR="$FOR" boot "$FLOG" "$FOR" "$KERNEL/run/launch-kernel-server.sh"

step "verdict"
# Prove both arms ran before comparing them. An arm that never started makes "the symptom is absent" a
# statement about a server that does not exist, which is the shape of every false attribution this exists to
# prevent.
# `|| true` and then a default, NOT `|| echo 0`: grep prints its count AND exits 1 when there are no matches,
# so `|| echo 0` appends a second line and the variable holds "0\n0", which every numeric test then rejects.
# The first run of this script reported NEITHER for a pattern the Forbric arm logs on every boot.
NAT_RAN=$(grep -acE 'Done \(' "$NLOG" 2>/dev/null || true); NAT_RAN=${NAT_RAN:-0}
FOR_RAN=$(grep -acE 'Done \(' "$FLOG" 2>/dev/null || true); FOR_RAN=${FOR_RAN:-0}
echo "[control] native reached Done: ${NAT_RAN:-0}; Forbric reached Done: ${FOR_RAN:-0}"
if [ "${NAT_RAN:-0}" -eq 0 ] || [ "${FOR_RAN:-0}" -eq 0 ]; then
  echo "[control] ❌ INCONCLUSIVE — an arm did not start, so neither presence nor absence means anything"
  echo "[control]    native $NLOG / forbric $FLOG"
  exit 1
fi

NAT_HIT=$(grep -acE "$SYMPTOM" "$NLOG" 2>/dev/null || true); NAT_HIT=${NAT_HIT:-0}
FOR_HIT=$(grep -acE "$SYMPTOM" "$FLOG" 2>/dev/null || true); FOR_HIT=${FOR_HIT:-0}
echo "[control] /$SYMPTOM/ — native: ${NAT_HIT:-0}, Forbric: ${FOR_HIT:-0}"
if   [ "${FOR_HIT:-0}" -gt 0 ] && [ "${NAT_HIT:-0}" -eq 0 ]; then VERDICT="FORBRIC-ONLY — this one is the kernel's"
elif [ "${FOR_HIT:-0}" -gt 0 ] && [ "${NAT_HIT:-0}" -gt 0 ]; then VERDICT="BOTH — a native Fabric server does it too; not the kernel's"
elif [ "${FOR_HIT:-0}" -eq 0 ] && [ "${NAT_HIT:-0}" -gt 0 ]; then VERDICT="NATIVE-ONLY — the kernel is masking it; worth a second look"
else VERDICT="NEITHER — this mod set does not reproduce it; fix the repro before the verdict"
fi
echo "[control] $VERDICT"
echo "[control] native $NLOG / forbric $FLOG"
exit 0
