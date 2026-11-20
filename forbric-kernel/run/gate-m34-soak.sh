#!/usr/bin/env bash
# M34: >=7200 seconds of occupied simulation, dimensions/chunks, same-JVM save/reentry and retention checks.
# --control explicitly produces non-release evidence. Inputs are copied and frozen before launch.
#
# What a release soak needs besides this tree and its stage, and where it comes from:
#   - the native reproduction of every reviewed retention root in the pack (run/compat/native-retention.json, today
#     build/retention-control/comparison.json for Unlit Campfire). soak-run.py obtains it: evidence that describes the
#     files on disk and the jars just frozen is used as it is; absent or stale evidence is measured again there
#     (run/compat/retention-control.py: one native NeoForge server, then one Forbric server; 18 s when measured) and its
#     "[M34] ... took N s" lines are in this gate's output. It used to come only from a hand-run of that script, so the
#     gate failed in 4 s in every fresh worktree (C6 ACCEPT finding F1).
#   - the native NeoForge server image that measurement boots (build/native-controls/native/neo). Nothing here installs
#     it, because the official installer downloads: without it retention-control.py stops at once and names the one
#     command, `python3 run/compat/native-controls.py prepare --family neo`.
# The soak's player is a survival player (ClientSoakController.preparePlayer says why); soak-run.py refuses a run whose
# telemetry says otherwise, and puts what the rows say of the player and the weather into acceptance.json.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
# MERGED and FORGE_RT are retired with the merged base and the MinecraftForge carrier: refused here as the launchers
# refuse them, rather than ignored, and before the kernel is built, as the launchers refuse before building anything.
if [ -n "${MERGED:-}${FORGE_RT:-}" ]; then
  echo "[kernel] FAIL M34: MERGED/FORGE_RT are retired; name the game jar with BASE (there is no MinecraftForge carrier)" >&2
  exit 2
fi
kernel_jar
kernel_classpath
printf '%s\n' "$KERNEL_CP" > "$BUILD/m34-boot-classpath.txt"
args=(--kernel "$KERNEL" --staged "$RUN_OLD" --fixture "${M34_FIXTURE:-$KERNEL/run/client-merged-pack}"
      --boot-classpath "$BUILD/m34-boot-classpath.txt")
# The game base and its carrier, as launch-kernel-client.sh takes them (BASE, NEO_RT).
[ -z "${BASE:-}" ] || args+=(--base "$BASE")
[ -z "${NEO_RT:-}" ] || args+=(--neo "$NEO_RT")
# Not `exec`. lib.sh's EXIT trap is what prints this gate's [kernel] CAUGHT line, and an exec'd python replaces the
# shell that owns the trap: m34 was the one gate that never said what the kernel caught, and its file stayed empty
# whether the client caught nothing or nothing was wired. soak-run.py gives the client JVM -- the client and every
# integrated server it opens, one process -- a caught file of its own in the run's evidence, and appends it to this
# gate's file when the client is gone, so the report below covers it.
#
# soak-run.py's stdout is also kept, because its exit code alone does not say what it accepted: it exits 0 for a
# --control run's CONTROL_PASS as well as for RELEASE_PASS. Only RELEASE_PASS (releaseAccepted=True) is m34's acceptance
# and earns the countable PASS line; a control pass gets a line no `^[kernel] PASS` count picks up.
VERDICT="$BUILD/m34-soak-run.out"
rm -f "$VERDICT"
rc=0
python3 "$KERNEL/run/compat/soak-run.py" "${args[@]}" "$@" | tee "$VERDICT" || rc=$?
if [ "$rc" -eq 0 ] && grep -aq '^\[M34\] RELEASE_PASS releaseAccepted=True evidence=' "$VERDICT"; then
  echo "[kernel] PASS M34 soak-run accepted the run as a release soak (RELEASE_PASS, exit 0)"
elif [ "$rc" -eq 0 ] && grep -aq '^\[M34\] CONTROL_PASS releaseAccepted=False evidence=' "$VERDICT"; then
  echo "[kernel] CONTROL-ONLY M34 a --control run passed (CONTROL_PASS); a control run is never release acceptance, so it is not counted"
elif [ "$rc" -eq 0 ]; then
  echo "[kernel] FAIL M34 soak-run exited 0 without a RELEASE_PASS or CONTROL_PASS verdict line"; rc=1
else
  echo "[kernel] FAIL M34 soak-run stopped with exit $rc"
fi
exit "$rc"
