#!/usr/bin/env bash
# Run every checked-in gate. Overlaps the ones that can safely overlap; the scheduling lives in
# gates-parallel.py next door, which explains why a naive fan-out produces verdicts that prove nothing.
#
# The glob is still authoritative: gates-parallel.py discovers gate-m*.sh the same way this script used to, so
# there is no parallel gate list to forget to update. What a gate DOES have to say for itself is one line:
#
#     # GATE-PARALLEL: rundirs=server-kernel mem=1500
#
# rundirs names the run/ directories it owns while it runs (two gates naming the same one never run together),
# and mem is what it costs in MB. A gate without that line runs ALONE, and the run says so on stderr — a new
# gate written by someone who never read this is slow, not silently broken. A gate whose verdict is a timing adds
# `alone` to its line (gate-m12's tick-lateness ceiling; every gate that calls check_kept_up): it then runs with no
# other gate beside it, because a neighbour's CPU shows up in that measurement as the server falling behind.
# Those four words (rundirs=, clone=, mem=, alone) are the whole vocabulary: any other word stops the sweep before a
# gate runs, and prose goes after ` # ` on the same line.
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
CACHE=1
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
    --release) ARGS+=(--release); shift;;
    --only) [ "$#" -ge 2 ] || { echo '--only needs a gate name' >&2; exit 2; }
      ARGS+=(--only "$2"); shift 2;;
    --no-cache) CACHE=0; shift;;
    --help)
      cat <<'USAGE'
gates-all.sh [-j N|auto] [--mem-budget MB] [--skip gate-m12-multiplayer.sh] ... [--only gate-m9-client.sh] ...
             [--no-cache] [--release] [--list]

  -j N           run up to N gates at once (default: auto — one slot per ~2 GB of budget, capped by cores).
                 -j 1 reproduces the old strictly-sequential run.
  --mem-budget   MB the running gates may claim between them (default: ~55% of physical RAM).
  --skip         do not run this gate; it is reported SKIP.
  --only         run only this gate (repeatable); every other gate is SKIP (not selected).
  --no-cache     run every gate even when the same inputs already went GREEN (see the verdict cache below).
  --list         print the gate order and exit.
  --progress     also stream the running commentary to stderr.
  --release      an acceptance run: a --skip'd or EXPECTED_RED gate fails it (exit 1). A gate that did not run,
                 or did not pass, is not a passing gate, however it was declared.

This script's OUTPUT is exactly the RESULT lines, the same bytes as build/gates/summary.txt — a contract
gate-m0 asserts on, so nothing else may be printed to either stream. The running commentary (what started
when, on which slot and port, what each gate cost) goes to build/gates/progress.log instead; `tail -f` it.

The scheduler owns the ports: each concurrent slot gets its own block from 25700 up, and GATE_PORT / M1x_PORT /
M28_PORT / M32_PORT are exported per gate from it. A GATE_PORT set in the environment moves where the blocks start
instead, so two sweeps at once (two working trees) need two different GATE_PORTs.

THE VERDICT CACHE. A gate whose inputs -- every non-prose file in the repository, its own script (a gate that runs
the tests: every gate script and the prose outside run/compat/reports/ too), every jar under run/ except the canaries
the builders write in run/canary/ (their sources are in the repository and the staged root, and are keyed), every
jar under the staged/game directories, the inherited FORBRIC_*/MC_*/JAVA_* environment and the launchers' BASE,
NEO_RT, NATIVE_REF, VANILLA_JAR, VANILLA_JVM (with the jars they name), the JVM -- are identical
to a run that went GREEN is not run again; its RESULT line says `cached:` and when, and its log is that run's log.
The cache lives in git's common directory, so every worktree of this repository shares it (FORBRIC_GATE_CACHE=<dir>
moves it, FORBRIC_GATE_CACHE=0 or --no-cache turns it off). --release never reads it. A hand-edited world in a
fixture is the one input it cannot see: use --no-cache after changing one. Details: run/compat/gate_cache.py.
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
  # ...and resolve the boot classpath once, and hand both to every launch (FORBRIC_PREBUILT_*; the launchers and
  # lib.sh skip their own two Gradle runs while the jar still has this size and mtime). Every gate of the sweep then
  # boots this one jar, even if someone edits the tree mid-sweep.
  PREBUILT_JAR="$(ls "$KERNEL"/build/libs/forbric-kernel-*.jar 2>/dev/null | head -1)"
  PREBUILT_DEPS="$("$KERNEL/gradlew" --offline -q -p "$KERNEL" printBootClasspath 2>/dev/null \
                   | grep -vE 'WARNING|native|Restricted|enable' | tail -1)"
  if [ -n "$PREBUILT_JAR" ] && [ -n "$PREBUILT_DEPS" ]; then
    export FORBRIC_PREBUILT_JAR="$PREBUILT_JAR" FORBRIC_PREBUILT_DEPS="$PREBUILT_DEPS"
    FORBRIC_PREBUILT_STAMP="$(stat -c '%s:%Y' "$PREBUILT_JAR" 2>/dev/null || stat -f '%z:%m' "$PREBUILT_JAR")"
    export FORBRIC_PREBUILT_STAMP
  fi
fi

# The verdict cache: on for a run of the real gates, shared by every worktree of the repository (a lane, the
# merger and VERIFY sweep the same tip from three trees). Off for GatesAllTest's stub gates unless it asks.
CACHE_DIR=""
if [ "$LIST" -eq 0 ] && [ "$CACHE" = 1 ] && [ "${FORBRIC_GATE_CACHE:-}" != 0 ]; then
  if [ -n "${FORBRIC_GATE_CACHE:-}" ]; then
    CACHE_DIR="$FORBRIC_GATE_CACHE"
  elif [ "$RUN" = "$KERNEL/run" ]; then
    common="$(git -C "$KERNEL" rev-parse --git-common-dir 2>/dev/null || true)"
    if [ -n "$common" ]; then CACHE_DIR="$(cd "$KERNEL" && cd "$common" && pwd)/forbric-gate-cache"; fi
  fi
fi

# -u: the progress lines are the only view of a run that now takes minutes with nothing on the terminal,
# and a redirected stdout is block-buffered, so without this they all arrive at the end.
exec python3 -u "$HERE/gates-parallel.py" \
  --run-dir "$RUN" --out-dir "$OUT" --jobs "$JOBS" ${MEM:+--mem-budget "$MEM"} \
  ${CACHE_DIR:+--cache-dir "$CACHE_DIR"} ${ARGS[@]+"${ARGS[@]}"}
