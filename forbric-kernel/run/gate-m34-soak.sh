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
[ -z "${MERGED:-}" ] || args+=(--merged "$MERGED")
[ -z "${FORGE_RT:-}" ] || args+=(--forge "$FORGE_RT")
[ -z "${NEO_RT:-}" ] || args+=(--neo "$NEO_RT")
# Not `exec`. lib.sh's EXIT trap is what prints this gate's [kernel] CAUGHT line, and an exec'd python replaces the
# shell that owns the trap: m34 was the one gate that never said what the kernel caught, and its file stayed empty
# whether the client caught nothing or nothing was wired. soak-run.py gives the client JVM -- the client and every
# integrated server it opens, one process -- a caught file of its own in the run's evidence, and appends it to this
# gate's file when the client is gone, so the report below covers it.
rc=0
python3 "$KERNEL/run/compat/soak-run.py" "${args[@]}" "$@" || rc=$?
exit "$rc"
