#!/usr/bin/env bash
# Run every checked-in gate. Overlaps the ones that can safely overlap; the scheduling lives in
# gates-parallel.py next door, which explains why a naive fan-out produces green runs that prove nothing.
#
# The glob is still authoritative: gates-parallel.py discovers gate-m*.sh the same way this script used to, so
# there is no parallel gate list to forget to update. What a gate DOES have to say for itself is one line:
#
#     # GATE-PARALLEL: rundirs=server-kernel mem=1500
#
# rundirs names the run/ directories it owns while it runs (two gates naming the same one never run together),
# and mem is what it costs in MB. A gate without that line runs ALONE, and the run says so on stderr — a new
# gate written by someone who never read this is slow, not silently broken.
#
# `-j 1` is the old behaviour exactly: same order, same summary.txt, same exit code.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KERNEL="$(cd "$HERE/../.." && pwd)"
RUN="${FORBRIC_GATE_DIR:-$KERNEL/run}"
OUT="${FORBRIC_GATE_RESULTS:-$KERNEL/build/gates}"
JOBS="${FORBRIC_GATE_JOBS:-auto}"
MEM=""
ARGS=()
LIST=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --list) LIST=1; ARGS+=(--list); shift;;
    --skip) [ "$#" -ge 2 ] || { echo '--skip needs a gate name' >&2; exit 2; }
      ARGS+=(--skip "$2"); shift 2;;
    -j|--jobs) [ "$#" -ge 2 ] || { echo '--jobs needs a number, or auto' >&2; exit 2; }
      JOBS="$2"; shift 2;;
    --mem-budget) [ "$#" -ge 2 ] || { echo '--mem-budget needs a size in MB' >&2; exit 2; }
      MEM="$2"; shift 2;;
    --progress) ARGS+=(--progress); shift;;
    --help)
      cat <<'USAGE'
gates-all.sh [-j N|auto] [--mem-budget MB] [--skip gate-m12-multiplayer.sh] ... [--list]

  -j N           run up to N gates at once (default: auto — one slot per ~2 GB of budget, capped by cores).
                 -j 1 reproduces the old strictly-sequential run.
  --mem-budget   MB the running gates may claim between them (default: ~55% of physical RAM).
  --skip         do not run this gate; it is reported SKIP.
  --list         print the gate order and exit.
  --progress     also stream the running commentary to stderr.

This script's OUTPUT is exactly the RESULT lines, the same bytes as build/gates/summary.txt — a contract
gate-m0 asserts on, so nothing else may be printed to either stream. The running commentary (what started
when, on which slot and port, what each gate cost) goes to build/gates/progress.log instead; `tail -f` it.

The scheduler owns the ports: each concurrent slot gets its own block from 25700 up, and GATE_PORT / M1x_PORT /
M28_PORT are exported per gate from it. A GATE_PORT set in the environment is NOT honoured here — run the gate
directly if you need to choose its port.
USAGE
      exit 0;;
    *) echo "Unknown argument: $1" >&2; exit 2;;
  esac
done

# Build the kernel jar ONCE, before anything fans out. Every gate's launcher rebuilds it too and gradle will
# serialise them on the project lock, so without this the first N gates all start by queueing for the same
# build — and a build that reruns while a gate's JVM has the jar open is its own hazard. Failing here also
# fails loudly in one place instead of N.
#
# ONLY for a run of the REAL gates. GatesAllTest drives this script against a directory of stub gates, from
# INSIDE gradle's own test task: a nested gradlew on the same project would sit waiting for a lock the outer
# build is holding, which is a deadlock, not a slow test.
if [ "$LIST" -eq 0 ] && [ "$RUN" = "$KERNEL/run" ]; then
  if ! "$KERNEL/gradlew" --offline -q -p "$KERNEL" jar >"$KERNEL/build/gates-prebuild.log" 2>&1; then
    echo "[gates] FATAL: kernel jar build failed — refusing to run gates against a stale jar" >&2
    grep -vE 'WARNING: |native-access|Restricted method|--enable-native' "$KERNEL/build/gates-prebuild.log" >&2
    exit 3
  fi
fi

# -u: the progress lines are the only view of a run that now takes minutes with nothing on the terminal,
# and a redirected stdout is block-buffered, so without this they all arrive at the end.
exec python3 -u "$HERE/gates-parallel.py" \
  --run-dir "$RUN" --out-dir "$OUT" --jobs "$JOBS" ${MEM:+--mem-budget "$MEM"} ${ARGS[@]+"${ARGS[@]}"}
