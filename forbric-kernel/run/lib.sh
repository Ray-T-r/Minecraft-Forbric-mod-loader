#!/usr/bin/env bash
# Shared helpers for forbric-kernel gate/oracle scripts. Source this: `. "$(dirname "$0")/lib.sh"`.
set -uo pipefail

KERNEL="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OLD="$(cd "$KERNEL/../forbric-loader" && pwd)"
RUN_OLD="$OLD/run"
BUILD="$KERNEL/build"
FAIL=0

step() { printf '\n[kernel] ==== %s ====\n' "$1"; }

# check <what> <grep-pattern> <file> [required-count]
check() {
  local what="$1" pat="$2" file="$3" want="${4:-1}" got
  got=$(grep -cE "$pat" "$file" 2>/dev/null || true)
  if [ "${got:-0}" -ge "$want" ]; then printf '[kernel] PASS %s (%s)\n' "$what" "$got"
  else printf '[kernel] FAIL %s (want>=%s got %s)\n' "$what" "$want" "${got:-0}"; FAIL=1; fi
}

# check_absent <what> <grep-pattern> <file> — fails if the pattern appears at all.
check_absent() {
  local what="$1" pat="$2" file="$3" got
  got=$(grep -cE "$pat" "$file" 2>/dev/null || true)
  if [ "${got:-0}" -eq 0 ]; then printf '[kernel] PASS %s (absent)\n' "$what"
  else printf '[kernel] FAIL %s (present x%s)\n' "$what" "$got"; FAIL=1; fi
}

# assert_eq <what> <expected> <actual>
assert_eq() {
  if [ "$2" = "$3" ]; then printf '[kernel] PASS %s (%s)\n' "$1" "$3"
  else printf '[kernel] FAIL %s (want %s got %s)\n' "$1" "$2" "$3"; FAIL=1; fi
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

kill_tree() {
  local root="$1" pid
  case "$root" in ''|*[!0-9]*) return 0;; esac
  for pid in $(pgrep -P "$root" 2>/dev/null) "$root"; do kill -9 "$pid" 2>/dev/null; done
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

# await_server <pid> <log> [limit-seconds] [grace-seconds] — wait for a clean stop, then guarantee it is gone.
# Bounded on purpose: a hung server costs the timeout, not the afternoon.
await_server() {
  local pid="$1" log="$2" limit="${3:-90}" grace="${4:-20}" i
  for i in $(seq 1 "$limit"); do
    kill -0 "$pid" 2>/dev/null || { wait "$pid" 2>/dev/null; return 0; }
    grep -qE 'Stopping server|Failed to start the minecraft server' "$log" 2>/dev/null && break
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
  kill_tree "$pid"
  wait "$pid" 2>/dev/null
}
