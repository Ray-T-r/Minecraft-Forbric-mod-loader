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
exec python3 "$KERNEL/run/compat/soak-run.py" "${args[@]}" "$@"
