#!/usr/bin/env bash
# M34: >=7200 seconds of occupied simulation, dimensions/chunks, same-JVM save/reentry and retention checks.
# --control explicitly produces non-release evidence. Inputs are copied and frozen before launch.
set -euo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
kernel_jar
kernel_classpath
printf '%s\n' "$KERNEL_CP" > "$BUILD/m34-boot-classpath.txt"
args=(--kernel "$KERNEL" --staged "$RUN_OLD" --fixture "${M34_FIXTURE:-$KERNEL/run/client-merged-pack}"
      --boot-classpath "$BUILD/m34-boot-classpath.txt")
# The game base and its carrier, as launch-kernel-client.sh takes them (BASE, NEO_RT). MERGED and FORGE_RT are retired
# with the merged base and the MinecraftForge carrier: refused here as the launchers refuse them, rather than ignored.
if [ -n "${MERGED:-}${FORGE_RT:-}" ]; then
  echo "[kernel] FAIL M34: MERGED/FORGE_RT are retired; name the game jar with BASE (there is no MinecraftForge carrier)" >&2
  exit 2
fi
[ -z "${BASE:-}" ] || args+=(--base "$BASE")
[ -z "${NEO_RT:-}" ] || args+=(--neo "$NEO_RT")
# Not `exec`. lib.sh's EXIT trap is what prints this gate's [kernel] CAUGHT line, and an exec'd python replaces the
# shell that owns the trap: m34 was the one gate that never said what the kernel caught, and its file stayed empty
# whether the client caught nothing or nothing was wired. soak-run.py gives the client JVM -- the client and every
# integrated server it opens, one process -- a caught file of its own in the run's evidence, and appends it to this
# gate's file when the client is gone, so the report below covers it.
rc=0
python3 "$KERNEL/run/compat/soak-run.py" "${args[@]}" "$@" || rc=$?
exit "$rc"
