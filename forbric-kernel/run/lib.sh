#!/usr/bin/env bash
# Shared helpers for forbric-kernel gate/oracle scripts. Source this: `. "$(dirname "$0")/lib.sh"`.
set -uo pipefail

KERNEL="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Where the staged game artifacts, the downloaded mod packs and the built canaries live. Fourteen gates read
# from it, and NONE of it is in git -- it is all build output and downloads, so a fresh checkout has none of it.
#
# Overridable because that is what makes a second checkout usable at all. Running two gates at once means two
# working trees (the boot jar is rewritten in place on one path, so one tree cannot serve two live JVMs), and a
# second worktree's own ../forbric-loader is empty. Point FORBRIC_OLD at the real one and nothing needs copying.
OLD="${FORBRIC_OLD:-$KERNEL/../forbric-loader}"
if [ -d "$OLD" ]; then
  OLD="$(cd "$OLD" && pwd)"
else
  # Say so here rather than letting RUN_OLD become "/run" and every gate fail looking in a directory at the
  # filesystem root, which is what used to happen.
  echo "[kernel] FATAL: no staged tree at $OLD — set FORBRIC_OLD to a checkout that has forbric-loader/run/" >&2
  exit 3
fi
RUN_OLD="$OLD/run"
BUILD="$KERNEL/build"
# The NeoForge live canary every gate stages. The stage's copy is built by DevPrepare from whichever tree prepared
# the stage, which other working trees may share read-only; a tree that changes the canary's sources builds its own
# (run/build-neolive-canary.sh) and points FORBRIC_NEOLIVE at it. The file name stays forbricneolive.jar either way:
# gate-m18 asserts the mod resolves its own jar by that name.
NEOLIVE="${FORBRIC_NEOLIVE:-$RUN_OLD/neoforge-runtime/forbricneolive.jar}"
# The NeoForge builds of the real mods the gates used to stage as MinecraftForge jars from $RUN_OLD/downloads/forge-26.2.
# Forbric refuses a MinecraftForge-only jar since Stage 3 of dropping MinecraftForge, so a gate that staged one would
# now stop on the refusal instead of testing what it was written for; each such jar was replaced by the same mod's
# NeoForge 26.2 build from Modrinth (same version where Modrinth has one; sha1s and version ids are recorded in
# run/compat/reports/2026-10-30-forge-removal-baseline/stage3-fixtures.md). Local, like run/carry-on and run/modmenu:
# not in git, linked into a worktree from the main checkout. The universal jars of that directory (FallingTree,
# collective, starterkit), whose NeoForge half is usable, are still read from it.
NEO_BUILDS="${FORBRIC_NEO_BUILDS:-$KERNEL/run/neoforge-builds}"
FAIL=0

# What the kernel caught and carried on from, in every JVM a gate starts. Caught.java appends each failure site to
# $FORBRIC_CAUGHT_FILE the first time it is hit and its count at exit, and the game JVMs inherit the variable, so a
# gate reports them whatever its own checks look at: a gate's checks are about its subject, and a bridge that threw
# on every tick next to a green subject used to be a line in a log nobody opened. FORBRIC_CAUGHT_STRICT=1 makes any
# of them fail the gate.
#
# Only a gate owns a file. Every other script sourcing this (the canary builders, the oracles) leaves an inherited
# variable alone, so a builder a gate calls writes into the gate's file and does not report it a second time.
#
# A gate started BY a gate joins the outer one's file the same way. Six gates (m33, m35, m37, m38, m39, m40) start
# their servers by running themselves again under evidence.py (`gate-mNN.sh --execute…`), and that inner run is a
# gate-* script too. It used to take a file of its own: every JVM it started wrote there, it printed its CAUGHT line
# into the server log the outer gate keeps as evidence, and the outer gate read its own, empty, file and printed
# "CAUGHT none". gate-m39's server hit an NPE in the transfer interop on every run, and the gate said none. The outer
# gate exports FORBRIC_CAUGHT_GATE (name:pid) with its file; an inner run that inherits both, while that pid is still
# alive and the file still exists, writes into it and leaves the report to the gate that owns it. A variable left over
# in an unrelated shell (no live owner, or no file) is not inherited ownership, and the gate takes its own file.
case "$(basename "$0")" in
  gate-*)
    if [ -n "${FORBRIC_CAUGHT_GATE:-}" ] && [ -n "${FORBRIC_CAUGHT_FILE:-}" ] && [ -f "$FORBRIC_CAUGHT_FILE" ] \
        && kill -0 "${FORBRIC_CAUGHT_GATE##*:}" 2>/dev/null; then
      export FORBRIC_CAUGHT_FILE FORBRIC_CAUGHT_GATE
    else
      CAUGHT_OWNER=$$
      mkdir -p "$BUILD/caught"
      FORBRIC_CAUGHT_FILE="$BUILD/caught/$(basename "$0" .sh).$$.tsv"
      FORBRIC_CAUGHT_GATE="$(basename "$0" .sh):$$"
      : > "$FORBRIC_CAUGHT_FILE"
      export FORBRIC_CAUGHT_FILE FORBRIC_CAUGHT_GATE
    fi
    ;;
esac

# caught_report — prints what the gate's JVMs caught; returns 1 when FORBRIC_CAUGHT_STRICT=1 and there was any.
caught_report() {
  [ "${CAUGHT_OWNER:-}" = "$$" ] || return 0
  local file="$FORBRIC_CAUGHT_FILE" sites
  sites=$(awk -F'\t' '$1=="FAILURE"{print $3}' "$file" 2>/dev/null | sort -u | wc -l | tr -d ' ')
  if [ "${sites:-0}" -eq 0 ]; then
    printf '[kernel] CAUGHT none: no JVM this gate started reported a failure it carried on from\n'
    return 0
  fi
  printf '[kernel] CAUGHT %s failure site(s) the kernel carried on from (all of them: %s):\n' "$sites" "$file"
  awk -F'\t' '
    $1=="COUNT" { n[$3] += $4 }
    $1=="FAILURE" && !seen[$3]++ { order[++k] = $3; what[$3] = $4 " — " $5 }
    END {
      for (i = 1; i <= k && i <= 30; i++) { w = order[i]; printf "[kernel]   %sx %s: %s\n", (w in n ? n[w] : "?"), w, what[w] }
      if (k > 30) printf "[kernel]   ... and %d more\n", k - 30
    }' "$file"
  # Never carried on from, strict or not: an entity-attribute event that did not finish leaves every living entity of an
  # ecosystem without its attributes, and a base that reads forge:swim_speed for every swimming entity then crashes on
  # the first one in water. A gate in whose JVMs that happened is red whatever its own checks say.
  local fatal
  fatal=$(awk -F'\t' '$1=="FAILURE" && $4 ~ /Forbric\/Attributes/ && $4 ~ /did not finish|could not initialise/ {print $3}' \
    "$file" 2>/dev/null | sort -u | wc -l | tr -d ' ')
  if [ "${fatal:-0}" -gt 0 ]; then
    printf '[kernel] FAIL %s entity-attribute event(s) did not finish — living entities are left without attributes\n' "$fatal"
    return 1
  fi
  if [ "${FORBRIC_CAUGHT_STRICT:-0}" = 1 ]; then
    printf '[kernel] FAIL the kernel carried on from failures, and FORBRIC_CAUGHT_STRICT=1\n'
    return 1
  fi
}

# _gate_exit [status] — the EXIT trap: report, and under strict turn a green exit red. A gate that sets its own EXIT
# trap calls this from it (gate-m28, canary_scratch below), because a second `trap … EXIT` replaces the first.
_gate_exit() {
  local rc="${1:-$?}"
  caught_report || { [ "$rc" -eq 0 ] && rc=1; }
  exit "$rc"
}
trap '_gate_exit $?' EXIT

# The port the single-server gates bind. They share one by default because they are meant to run one at a
# time; give each concurrent run its own GATE_PORT and they stop fighting over 25565.
#
# It has to be written explicitly. Every one of these gates truncates server.properties to a single line, and a
# truncated file makes the server regenerate every default it no longer finds -- including server-port. So the
# port was never chosen, it was whatever vanilla's default happened to be, and two runs collided silently: the
# loser prints "FAILED TO BIND TO PORT" and then still prints "Stopping server", so the clean-shutdown check
# passes and the gate reads like a kernel regression. port_was_free below now says which port it was instead.
GATE_PORT="${GATE_PORT:-25565}"

# Writes the server.properties the single-server gates all wrote by hand, with the port made explicit.
# seed_server_properties <rundir>
seed_server_properties() {
  printf 'level-seed=forbrickernel\nserver-port=%s\n' "$GATE_PORT" > "$1/server.properties"
}

step() { printf '\n[kernel] ==== %s ====\n' "$1"; }

# The kernel's one boot line saying where FABRIC's native references come from (NativeReferenceSources.describe). Every
# launch path hands it the vanilla-reference sidecar (--nativeReference): launch-kernel-{client,server}.sh through
# run/vanilla-reference-jar.sh, an installed profile through its own library. On the merged base a launch without it
# still works -- the base embeds the same index -- so only this line tells a launch path that dropped the flag from one
# that kept it, and only the sidecar exists on a base that is not the merge. gate-m1 and gate-m17 assert it.
NATIVE_REFS_FROM_SIDECAR='Forbric/Mixin\] native references: FABRIC from the vanilla-reference sidecar'

# Is there anything here to read at all? A log the run never wrote, or wrote empty, is not evidence either way,
# and the two helpers below must not pretend otherwise.
#
# This existed as a hole for check_absent's whole history: `grep -c` on a missing file prints nothing and exits
# 1, `|| true` swallowed the exit code, and `${got:-0}` turned the empty output into 0 — which is exactly the
# value that means PASS. gate-m9-client.sh alone has ~30 absence checks, and its `cat "$GAMELOG" >> "$LOG"` is
# `|| true`, so a run that produced no game log disarmed all thirty of them silently. There is no legitimate
# caller: every one of the 206 call sites reads a log its own run produced.
readable() {
  [ -s "$1" ]
}

# -a on every grep below, and it is not cosmetic. A single NUL byte anywhere in a log makes grep treat the whole
# file as binary: `grep -c` then prints NOTHING rather than a count, `${got:-0}` turns that into 0, and every
# absence check silently passes while every positive one silently fails. Mod output puts NUL bytes in these logs
# often enough that this project has a note about it elsewhere in the tree.

# check <what> <grep-pattern> <file> [required-count]
check() {
  local what="$1" pat="$2" file="$3" want="${4:-1}" got
  if ! readable "$file"; then
    printf '[kernel] FAIL %s (no log to read: %s)\n' "$what" "$file"; FAIL=1; return
  fi
  got=$(grep -acE "$pat" "$file" 2>/dev/null || true)
  if [ "${got:-0}" -ge "$want" ]; then printf '[kernel] PASS %s (%s)\n' "$what" "$got"
  else printf '[kernel] FAIL %s (want>=%s got %s)\n' "$what" "$want" "${got:-0}"; FAIL=1; fi
}

# check_absent <what> <grep-pattern> <file> [except-pattern] — fails if the pattern appears at all.
#
# A missing or empty log FAILS here rather than passing. "The pattern is not in the log" and "there is no log"
# are different answers, and only the first one is the one this assertion is making.
#
# `except` drops lines before counting, and exists for one specific way these assertions go wrong: the kernel
# NARRATES the bugs it repairs, so a message explaining that something "died on IncompatibleClassChangeError"
# is matched by an assertion looking for the word "Incompatible" in a rejection. gate-m12 went red on exactly
# that — its own success message, matched by its own failure pattern — and the answer is not to reword the
# explanation, because then the next explanation does it again. A line the kernel wrote about itself is not the
# game doing the thing. Pass `except` as a pattern identifying that narration, e.g. '\[Forbric/'.
check_absent() {
  local what="$1" pat="$2" file="$3" except="${4:-}" got
  if ! readable "$file"; then
    printf '[kernel] FAIL %s (no log to read: %s)\n' "$what" "$file"; FAIL=1; return
  fi
  if [ -n "$except" ]; then
    got=$(grep -avE "$except" "$file" 2>/dev/null | grep -acE "$pat" 2>/dev/null || true)
  else
    got=$(grep -acE "$pat" "$file" 2>/dev/null || true)
  fi
  if [ "${got:-0}" -eq 0 ]; then printf '[kernel] PASS %s (absent)\n' "$what"
  else printf '[kernel] FAIL %s (present x%s)\n' "$what" "$got"; FAIL=1; fi
}

# check_exactly <what> <grep-pattern> <file> <count> — fails unless the pattern appears exactly that many times.
#
# For events a mod must hear once per occurrence. "At least once" is what check asserts, and it stays green when the
# kernel forwards an event the merged game already delivered natively: every MinecraftForge server lifecycle event
# reached the canary twice, its SERVER config loaded twice, and every gate that looked passed.
check_exactly() {
  local what="$1" pat="$2" file="$3" want="$4" got
  if ! readable "$file"; then
    printf '[kernel] FAIL %s (no log to read: %s)\n' "$what" "$file"; FAIL=1; return
  fi
  got=$(grep -acE "$pat" "$file" 2>/dev/null || true)
  if [ "${got:-0}" -eq "$want" ]; then printf '[kernel] PASS %s (%s)\n' "$what" "$got"
  else printf '[kernel] FAIL %s (want exactly %s got %s)\n' "$what" "$want" "${got:-0}"; FAIL=1; fi
}

# merged_calls <class> <method> <ref>... — does the merged base this run boots make one of these calls (or
# constructions) in <class>.<method>, any overload, or in a lambda that method captures? 0 yes, 1 no, 2 unreadable.
# Reads $MERGED, else the staged base. Grammar and the reason only CAPTURED lambdas count are in merged_calls.py: a
# merge that keeps both families' compiles of a lambda can leave the losing body behind with nothing calling it.
# A leading --every asks whether EACH overload makes it (a constructor handing over to this(...) left out).
merged_calls() {
  local every=""
  if [ "${1:-}" = "--every" ]; then every="--every"; shift; fi
  python3 "$KERNEL/run/merged_calls.py" $every "${MERGED:-$RUN_OLD/merged-base/patched-mc-merged-26.2.jar}" "$@"
}

# merged_delivers <case> [--every] <class> <method> <ref>... — for a gate whose off phase expects exactly the repaired cases
# to fail: when the merged base makes the call that decides <case> itself, append 'case' to $MERGED_NATIVE (a
# python set body), because with the kernel's repair switched off the base still delivers it. FAILS when the base
# cannot be read: an expectation computed from a base nobody read is no expectation.
MERGED_NATIVE=""
merged_delivers() {
  local name="$1" site; shift
  if [ "${1:-}" = "--every" ]; then site="$2.$3"; else site="$1.$2"; fi
  merged_calls "$@"
  case $? in
    0) MERGED_NATIVE="$MERGED_NATIVE'$name', " ;;
    1) ;;
    *) printf '[kernel] FAIL cannot read %s from the merged base, so whether it delivers %s is unknown\n' "$site" "$name"; FAIL=1 ;;
  esac
}

# check_kept_up <what> <server-log> — the one performance assertion this project has.
#
# WHY IT IS TWO ASSERTIONS. "Can't keep up! Is the server overloaded?" is vanilla's own line, and it is the only
# performance signal anywhere in this tree: there is no tick-time, frame-time, TPS or memory assertion in any
# gate, so a mod whose entire value is a number (Sodium, Lithium, FerriteCore) proves nothing here by loading.
# Absence of that line is worth asserting -- but only after proving the server had anything to keep up WITH.
#
# The denominator is a player session. Vanilla pauses an empty dedicated server (pause-when-empty-seconds
# defaults to 60), so on a gate where nobody joins, "no overload warnings" is a statement about a JVM that
# stopped ticking -- the exact shape of a green that means nothing. `logged in with entity id` is the server's
# own line and a client never prints it, so it cannot be satisfied by the wrong log.
check_kept_up() {
  local what="$1" file="$2" joined behind worst
  if ! readable "$file"; then
    printf '[kernel] FAIL %s (no log to read: %s)\n' "$what" "$file"; FAIL=1; return
  fi
  joined=$(grep -acE "logged in with entity id" "$file" 2>/dev/null || true)
  if [ "${joined:-0}" -eq 0 ]; then
    printf '[kernel] FAIL %s (no player session in this log, so the server never had to keep up)\n' "$what"
    FAIL=1; return
  fi
  behind=$(grep -acE "Can.t keep up" "$file" 2>/dev/null || true)
  if [ "${behind:-0}" -eq 0 ]; then
    printf '[kernel] PASS %s (%s player session(s), 0 overload warnings)\n' "$what" "$joined"
  else
    worst=$(grep -aoE "Running [0-9]+ms or [0-9]+ ticks behind" "$file" 2>/dev/null | sort -t' ' -k2 -n | tail -1)
    printf '[kernel] FAIL %s (%s overload warning(s); worst: %s)\n' "$what" "$behind" "${worst:-unparsed}"
    FAIL=1
  fi
}

# assert_eq <what> <expected> <actual>
assert_eq() {
  if [ "$2" = "$3" ]; then printf '[kernel] PASS %s (%s)\n' "$1" "$3"
  else printf '[kernel] FAIL %s (want %s got %s)\n' "$1" "$2" "$3"; FAIL=1; fi
}

# strip_ansi <raw-log> <plain-log> — preserve raw evidence and make a separate CSI-free assertion view.
# Paper's console can insert color escapes between a player name and "failed" even when stdout is a file.
strip_ansi() {
  python3 - "$1" "$2" <<'PY'
from pathlib import Path
import re, sys
source, destination = map(Path, sys.argv[1:])
if source.resolve() == destination.resolve():
    sys.exit('strip_ansi requires a separate output file; the raw log must be preserved')
data = source.read_bytes()
if not data:
    sys.exit('strip_ansi cannot produce an assertion view from an empty log')
destination.write_bytes(re.sub(rb'\x1b\[[0-?]*[ -/]*[@-~]', b'', data))
PY
}

# Rebuild the kernel jar, FAILING LOUDLY. A swallowed build error leaves a stale jar in build/libs and every
# gate downstream then silently reports on code that is not the code in the tree.
kernel_jar() {
  if ! "$KERNEL/gradlew" --offline -q -p "$KERNEL" jar >"$BUILD/kernel-jar.log" 2>&1; then
    echo "[kernel] FATAL: kernel jar build failed — refusing to run against a stale jar" >&2
    grep -vE 'WARNING: |native-access|Restricted method|--enable-native' "$BUILD/kernel-jar.log" >&2
    exit 3
  fi
}

# Build (offline) the boot-side classpath once and cache it. Sets $KERNEL_CP.
kernel_classpath() {
  local jar="$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar"
  if [ ! -f "$jar" ]; then
    kernel_jar
  fi
  local cp
  cp=$("$KERNEL/gradlew" --offline -q -p "$KERNEL" printBootClasspath 2>/dev/null \
        | grep -vE 'WARNING|native|Restricted|enable' | tail -1)
  KERNEL_CP="$jar:$cp"
}

# kernel_scan <mods-dir> <out-json>
kernel_scan() {
  [ -n "${KERNEL_CP:-}" ] || kernel_classpath
  java -cp "$KERNEL_CP" net.forbric.kernel.boot.Main --scan --mods "$1" --report "$2" \
       2>&1 | grep -vE 'WARNING|native|Restricted|enable' || true
}

filter_noise() { grep -vE 'WARNING: |native-access|Restricted method|--enable-native'; }

# --- server process lifecycle -------------------------------------------------------------------------------
# WHY BY PID AND NEVER BY NAME. `pkill -f KernelServerLaunch` has never matched anything on this machine: the
# launcher's -cp runs to tens of thousands of characters and the main class sits past the range pgrep/pkill can
# inspect. So every gate's `pgrep -f ... || break` broke on its FIRST iteration and every `pkill -9 -f ...` was
# a no-op. The gates passed anyway — but only because a healthy server reaches "Stopping server" by itself. A
# server that HUNG hung the gate with it, forever, on `wait "$BOOTPID"`. (Measured: 17 minutes before someone
# noticed.) Kill the tree by pid instead, and leave the pid behind so an interrupted run can be reaped later.
#
# A name match would also be actively unsafe here: another session may have its own Minecraft running, and a
# broad pattern is exactly how you kill someone else's game. Every kill below goes through a pid this gate
# itself recorded.

_pidfile() { echo "${1:-$KERNEL/run}/.forbric-gate.pid"; }

# The WHOLE tree, depth first. Children only is not a tree: a gate that records a subshell (M24's boot, anything
# piping a stdin feeder into launch-kernel-server.sh) has the java server two or three levels down, and killing the
# subshell and its direct children left that server running, re-parented to launchd, still holding the port —
# measured twice, each time failing every later gate that wanted port 25710 with "Address already in use".
kill_tree() {
  local root="$1" child
  case "$root" in ''|*[!0-9]*) return 0;; esac
  kill -STOP "$root" 2>/dev/null   # it cannot spawn a new child while its children are being collected
  for child in $(pgrep -P "$root" 2>/dev/null); do kill_tree "$child"; done
  kill -9 "$root" 2>/dev/null
}

# stop_tree <pid> [grace-seconds] — the same whole tree, asked first: TERM to every process in it, up to <grace>
# seconds (default 2) for them to go, then SIGKILL for whatever is left. For a client a gate is done with, whose JVM
# should get to run its shutdown hooks (Caught's per-site counts are written there).
#
# The clients had kept the children-only form kill_tree replaced for servers. Where the launcher execs java that is
# the JVM itself and it worked; gate-m17 runs the resolved command as `tr … | xargs -0 env … java` in a subshell, so
# java is the subshell's grandchild. TERM reached the subshell, tr and xargs, and the client lived on re-parented to
# launchd with its window open. Measured on a loaded run: the gate gave up on it, printed "CAUGHT none" and exited;
# two minutes later the orphan hit two failures (the gate's last step had reinstalled the libraries under it) and
# wrote them into the gate's caught file, reached its world 14 s after that, and quit. A JVM that outlives its gate
# is reported by nobody.
#
# The tree is frozen (STOP, parents before children, as kill_tree does) before anything is signalled, so a process
# killed first cannot orphan a child the snapshot has not seen, and none can start a new one in between.
stop_tree() {
  local root="$1" grace="${2:-2}" pids pid alive i
  case "$root" in ''|*[!0-9]*) return 0;; esac
  pids="$(_freeze_tree "$root")"
  [ -n "$pids" ] || return 0
  for pid in $pids; do kill -TERM "$pid" 2>/dev/null; done
  for pid in $pids; do kill -CONT "$pid" 2>/dev/null; done
  for i in $(seq 1 $((grace * 4))); do
    alive=""
    for pid in $pids; do _running "$pid" && alive="$alive $pid"; done
    [ -n "$alive" ] || return 0
    sleep 0.25
  done
  for pid in $pids; do _running "$pid" && kill_tree "$pid"; done
  return 0
}
# _freeze_tree <pid> — STOP <pid>, then each child's tree; prints every pid stopped, parents first.
_freeze_tree() {
  local root="$1" child
  kill -STOP "$root" 2>/dev/null || return 0
  echo "$root"
  for child in $(pgrep -P "$root" 2>/dev/null); do _freeze_tree "$child"; done
}
# _running <pid> — alive and not a zombie (an exited child the gate has not waited for yet still answers kill -0).
_running() {
  local state
  state="$(ps -o stat= -p "$1" 2>/dev/null)" || return 1
  case "$state" in ''|Z*) return 1;; esac
  return 0
}

# reap_stale_server <rundir> — clean up a server left running by an INTERRUPTED earlier run of this same gate.
# Replaces the old blanket pkill pre-clean, which could never have found anything anyway.
reap_stale_server() {
  local pf; pf="$(_pidfile "${1:-}")"
  [ -f "$pf" ] || return 0
  local stale; stale="$(cat "$pf" 2>/dev/null)"
  rm -f "$pf"
  case "$stale" in ''|*[!0-9]*) return 0;; esac
  if kill -0 "$stale" 2>/dev/null; then
    echo "[kernel] reaping server $stale left behind by an interrupted run"
    kill_tree "$stale"
    sleep 1
  fi
}

record_server_pid() { echo "$2" > "$(_pidfile "$1")"; }

# server_ready <server-log> <seconds> [settle-seconds] — return once <seconds> have passed AND the server has printed
# "Done (", so a console line written now is one it can run; return 1 instead when the log says the server is gone
# or going without ever getting there, when the log has not grown for STOP_READY_STALL (default 60) seconds, or after
# STOP_READY_CAP (default 150) more seconds without either.
#
# The give-ups matter because a gate waits for its whole pipeline: await_server's `wait` on the server's pid returns
# only when the feeder on the other end of the pipe has finished too, so a feeder still polling for a Done that a
# refused launch (the policy stops gate-m19 and gate-m24 provoke on purpose) will never print would hold the gate
# for the full cap. Every way the kernel, the launcher or the game ends a boot early says so in the log, and those
# lines end the wait at once; the stall is for a death nothing announced.
# stop_when_ready <server-log> <seconds> [settle-seconds] — server_ready, then "stop": what a gate pipes into its
# server's stdin in place of `sleep <seconds>; echo stop`.
#
# The dedicated server's console thread builds a command's source when it READS the line, not when it runs it:
# DedicatedServer$1 calls createCommandSourceStack(), which takes findRespawnDimension() and so overworld() as it is
# at that moment. initServer starts that thread before loadLevel, so a line read during the boot gets a source with no
# level, and running it on the first tick throws in Commands.executeCommandInContext (context.getLevel().getGameRules()).
# Vanilla 26.2, MinecraftForge's and NeoForge's bodies and the merged one are the same instructions there; the server
# prints "An unexpected error occurred while trying to execute that command" and runs on. A gate that fed "stop" on a
# fixed timer therefore went red as "never announced a stop" whenever the machine was loaded enough that the boot
# outlasted the timer (measured: gate-m3 with a 28 s boot against its 22 s timer). With a boot inside the timer this
# writes the line at the same moment the timer did; only a slower boot moves it, to <settle> (default 5) seconds after
# "Done (", so what the gate wanted to see run after the boot still gets some ticks.
server_ready() {
  local log="$1" seconds="$2" settle="${3:-5}" cap="${STOP_READY_CAP:-150}" stall="${STOP_READY_STALL:-60}"
  local waited=0 still=0 size last=-1
  sleep "$seconds"
  until grep -aq 'Done (' "$log" 2>/dev/null; do
    # Gone or going: a line written now would only meet a closed pipe (and a pipeline under pipefail would report it).
    # The game's own stop and crash lines, every "launch stopped" the kernel prints when it refuses a launch, the
    # launcher's refusal, and a JVM that died before the game started.
    grep -aqE 'Stopping server|Failed to start the minecraft server|Encountered an unexpected exception|launch stopped|\[kernel-launch\] FATAL|Exception in thread "main"|Error occurred during initialization of VM|Could not find or load main class' \
      "$log" 2>/dev/null && return 1
    [ "$waited" -ge "$cap" ] && return 1
    size=$(wc -c < "$log" 2>/dev/null || echo 0)
    if [ "$size" = "$last" ]; then still=$((still + 1)); else still=0; last=$size; fi
    [ "$still" -ge "$stall" ] && return 1
    sleep 1; waited=$((waited + 1))
  done
  [ "$waited" -eq 0 ] || sleep "$settle"
  return 0
}
stop_when_ready() {
  server_ready "$@" && echo stop
  return 0
}

# await_server <pid> <log> [limit-seconds] [grace-seconds] — wait for a clean stop, then guarantee it is gone.
# Bounded on purpose: a hung server costs the timeout, not the afternoon.
await_server() {
  local pid="$1" log="$2" limit="${3:-90}" grace="${4:-20}" i announced=0
  for i in $(seq 1 "$limit"); do
    kill -0 "$pid" 2>/dev/null || { wait "$pid" 2>/dev/null; port_was_free "$log"; return 0; }
    grep -qE 'Stopping server|Failed to start the minecraft server' "$log" 2>/dev/null && { announced=1; break; }
    sleep 1
  done
  # "Stopping server" is printed when shutdown BEGINS, not when it ends — saving chunks and flushing the log come
  # after it, and the gates assert on lines from that tail ("All dimensions are saved"). So let a server that has
  # announced its stop leave on its own terms, and only reach for the hammer if it cannot do so inside the grace
  # window. A flat sleep here would be a bet on how long a save takes.
  for i in $(seq 1 "$grace"); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    # A dedicated server has no System.exit: once "Stopping server" is out, only a leaked non-daemon thread keeps
    # the JVM alive. That is a defect, not a slow save — count it.
    # Only after an announced stop: a server that never announced one within the limit (a "stop" it never read, a
    # boot slower than the limit) is a different failure, and saying "leaked thread" sent a reader the wrong way.
    if [ "$announced" -eq 1 ]; then
      echo "[kernel] FAIL server still alive ${grace}s after announcing its stop — killing it (a leaked non-daemon thread looks exactly like this)"
    else
      echo "[kernel] FAIL server never announced a stop within ${limit}s — killing it (a stop it never read, or a boot slower than that)"
    fi
    FAIL=1
  fi
  kill_tree "$pid"
  wait "$pid" 2>/dev/null
  port_was_free "$log"
}

# port_was_free <server-log> — fail, naming the port, when the server never got it.
#
# A server that loses its port prints "FAILED TO BIND TO PORT", writes a crash report, and then still prints
# "Stopping server" and "All dimensions are saved". Measured on gate-m1 with its port held by another process:
# both shutdown checks passed, and the gate went red on "server reached Done" and on a missing post-Done log --
# which reads as the kernel failing to boot, with nothing in the gate's output about a port. The run says nothing
# about the kernel at all, so say what it does say. Silent when the port was free, like the leaked-thread check
# above; await_server runs it, and a gate that starts its server some other way calls it on that server's log.
port_was_free() {
  local file="$1" bound
  grep -aq 'FAILED TO BIND TO PORT' "$file" 2>/dev/null || return 0
  bound=$(grep -aoE 'Starting Minecraft server on [^[:space:]]+' "$file" 2>/dev/null | tail -1) || true
  bound="${bound#Starting Minecraft server on }"
  printf '[kernel] FAIL the server never got its port (%s): another process holds it, so this run says nothing about the kernel\n' \
    "${bound:-the log does not say which}"
  FAIL=1
}

# --- canary builds ------------------------------------------------------------------------------------------
# WHY THESE NEED ANY CARE AT ALL. run/canary/ is written by four builder scripts and read by eight gates, and
# SIX of those gates rebuild the same Fabric canary themselves. While the gates ran one at a time that was only
# wasteful. Once they overlap it is two concrete faults:
#
#   * two runs of one builder `rm -rf` each other's scratch directory mid-compile;
#   * `jar --create --file run/canary/x.jar` leaves a partial file on disk for as long as it takes to write it,
#     and build-fabric-canary.sh additionally UNLINKED its output first — so a reader in another gate could
#     find no canary at all, or copy half of one into its mods/ and then fail to load it. Neither failure
#     mentions the canary; both read as a kernel regression.
#
# The two helpers below remove the need for a lock: give each invocation its own scratch, and put the finished
# jar in place with a single rename. Concurrent builders then all produce the same bytes and the last rename
# wins, while every reader sees one complete jar or the other.

# canary_scratch <name> — set $WORK to a scratch directory this invocation owns, and remove it on exit.
# NOT via command substitution: a subshell would take the trap with it and delete the directory immediately.
canary_scratch() {
  WORK="$BUILD/canary-$1.$$"
  rm -rf "$WORK"; mkdir -p "$WORK"
  trap 'rc=$?; rm -rf "$WORK"; _gate_exit "$rc"' EXIT
}

# publish_canary <staged-jar> <destination> — install a FINISHED jar with one rename, so no reader ever sees a
# partially written one. Both paths are under build/, one filesystem, so the rename is atomic.
publish_canary() {
  mkdir -p "$(dirname "$2")"
  mv -f "$1" "$2" || { echo "[kernel] FAIL could not publish $(basename "$2")" >&2; return 1; }
}

# --- download caches ----------------------------------------------------------------------------------------
# WHY THIS EXISTS. gate-m13 and gate-m14 both `rm -rf` their rundir to get a clean world, and that also deletes
# the ~50 MB vanilla server jar their launcher downloaded on the previous run. So these two are the only gates
# that need Mojang / FabricMC to be reachable AND fast at gate time. Measured on this machine over one afternoon,
# that link swings between 30 KB/s and 2.2 MB/s and sometimes refuses TLS outright ("SSL peer shut down
# incorrectly"), which turned both gates red for reasons with nothing to do with the kernel: Paperclip failed the
# hash check on a truncated mojang_*.jar twice, at two different sizes, and fabric-installer timed out mid-read.
#
# Stashing the download outside the rundir keeps the wipe (clean world, clean config) while paying the download
# once. The stash is VERIFIED before it is put back, because the failure that motivated this cached a truncated
# jar and then failed on it every run until it was deleted by hand — a cache that can serve corruption is worse
# than no cache.
GATE_DOWNLOADS="$KERNEL/run/.gate-downloads"

# stash_downloads <rundir> <name> <subpath>... — move caches aside, just before a rundir wipe.
stash_downloads() {
  local rundir="$1" name="$2"; shift 2
  local keep="$GATE_DOWNLOADS/$name" sub
  for sub in "$@"; do
    [ -e "$rundir/$sub" ] || continue
    mkdir -p "$keep/$(dirname "$sub")"
    rm -rf "${keep:?}/$sub"
    mv "$rundir/$sub" "$keep/$sub"
  done
}

# restore_downloads <rundir> <name> <subpath>... — put verified caches back, after the rundir is recreated.
# Any .jar under the stash that is not a readable zip is dropped, so the next run downloads it again.
restore_downloads() {
  local rundir="$1" name="$2"; shift 2
  local keep="$GATE_DOWNLOADS/$name" sub jar
  [ -d "$keep" ] || return 0

  while IFS= read -r jar; do
    [ -n "$jar" ] || continue
    if ! unzip -qt "$jar" >/dev/null 2>&1; then
      echo "[kernel] dropping a corrupt cached download: ${jar##*/} (it will be fetched again)"
      rm -f "$jar"
    fi
  done <<< "$(find "$keep" -name '*.jar' 2>/dev/null)"

  for sub in "$@"; do
    [ -e "$keep/$sub" ] || continue
    mkdir -p "$rundir/$(dirname "$sub")"
    cp -R "$keep/$sub" "$rundir/$sub"
  done
}
