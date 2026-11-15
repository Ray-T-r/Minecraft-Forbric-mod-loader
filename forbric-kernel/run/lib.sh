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
  # Never carried on from, strict or not: NeoForge's entity-attribute events not finishing. CommonHooks.modifyAttributes
  # posts EntityAttributeCreationEvent and then EntityAttributeModificationEvent, which is how NeoForge's own attributes
  # (neoforge:swim_speed, nametag_distance) reach every living entity; one that did not finish leaves them without, and
  # the first living entity in water then crashes. A gate in whose JVMs that happened is red whatever its own checks
  # say. Three kernel lines say it happened (KernelLifecycle.postAttributeEvents): DefaultAttributes could not initialise
  # before the events ("[Forbric/Attributes] ... could not initialise", the walk then fails too), the hook itself threw
  # ("[Forbric/Lifecycle] net.neoforged.neoforge.common.CommonHooks.modifyAttributes() THREW"), or the hook is not there
  # ("[Forbric/Lifecycle] net.neoforged.neoforge.common.CommonHooks has no modifyAttributes()": the events never ran).
  # Until C5 the pattern was "[Forbric/Attributes]" with "did not finish|could not initialise": it matched the settle
  # line (restored by c10779e3) but neither line of the hook, which is the one that runs the events.
  # Known gap: Caught.java writes a site's FIRST failure to the file, and modifyAttributes shares its site with the
  # other hooks KernelLifecycle.invokeGameDataOn calls. GameData's unfreezeData and vanillaSnapshot run through it
  # before modifyAttributes; if one of them threw first, its line is the one recorded and a later modifyAttributes
  # failure is not seen here (closing it needs a site of its own in the kernel).
  local fatal
  fatal=$(awk -F'\t' '$1=="FAILURE" && (($4 ~ /\[Forbric\/Attributes\]/ && $4 ~ /could not initialise/) || $4 ~ /CommonHooks\.modifyAttributes\(\) THREW|CommonHooks has no modifyAttributes\(\)/) {print $3}' \
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
# run/vanilla-reference-jar.sh, an installed profile through its own library. The base (NeoForge's patched game) embeds
# no index, so the sidecar is the only FABRIC source: a launch without it still boots, with no FABRIC references, and
# only this line tells a launch path that dropped the flag from one that kept it. gate-m1 and gate-m17 assert it.
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
# check_forge_family_configs_are_neoforge <what> <file> — the kernel's "[Forbric/Mixin] mixin configs:" line is there, and
# the Forge-family count it prints equals its NeoForge count: every Forge-family mixin config the boot registered is
# NeoForge's. A regex on the line alone only proves the line exists; grep here is ugrep on the dev machine, which has no
# backreferences, so the two numbers are compared in the shell.
check_forge_family_configs_are_neoforge() {
  local what="$1" file="$2" line family neo
  if ! readable "$file"; then
    printf '[kernel] FAIL %s (no log to read: %s)\n' "$what" "$file"; FAIL=1; return
  fi
  line=$(grep -aE '\[Forbric/Mixin\] mixin configs: [0-9]+ Fabric \+ [0-9]+ Forge-family \([0-9]+ NeoForge\) ' "$file" 2>/dev/null | tail -n 1)
  family=$(printf '%s\n' "$line" | sed -nE 's/.* \+ ([0-9]+) Forge-family \(([0-9]+) NeoForge\) .*/\1/p')
  neo=$(printf '%s\n' "$line" | sed -nE 's/.* \+ ([0-9]+) Forge-family \(([0-9]+) NeoForge\) .*/\2/p')
  if [ -z "$family" ] || [ -z "$neo" ]; then printf '[kernel] FAIL %s (no mixin configs line)\n' "$what"; FAIL=1
  elif [ "$family" = "$neo" ]; then printf '[kernel] PASS %s (%s Forge-family, %s NeoForge)\n' "$what" "$family" "$neo"
  else printf '[kernel] FAIL %s (%s Forge-family but %s NeoForge)\n' "$what" "$family" "$neo"; FAIL=1; fi
}

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

# control_arm_dir <arm> <key-file> — where a CONTROL ARM's output for exactly these inputs is kept, or nothing when the
# cache is off.
#
# A control arm is a game run whose inputs hold nothing of this tree. gate-m31's pure-vanilla server is the vanilla jar,
# its libraries, the seed, the JVM and the gate's own feeder; the kernel is not in it. The facets the gate asserts on
# (biomes, structure starts, the dungeon mob) reproduce exactly from vanilla to vanilla, so booting it again on every
# run re-derives the same answer and can find nothing about the kernel (lane OPT, D89): about three minutes of game time
# per run. The key file lists every input of the arm, one per line; its sha256 names the directory, beside the gate
# verdicts in git's common directory (gate_cache.py), so every worktree shares it. The arm runs when nothing is kept for
# its key, and its gate stores the output only after the arm's own checks passed.
#
# Off -- the arm always runs -- when FORBRIC_GATE_CACHE=0, or FORBRIC_GATE_FRESH=1, which gates-all exports for
# --no-cache and --release: an acceptance measures every arm afresh.
control_arm_dir() {
  local arm="$1" keyfile="$2" root common key
  if [ "${FORBRIC_GATE_CACHE:-}" = 0 ] || [ "${FORBRIC_GATE_FRESH:-}" = 1 ]; then return 0; fi
  if [ -n "${FORBRIC_GATE_CACHE:-}" ]; then
    root="$FORBRIC_GATE_CACHE/arms"
  else
    common="$(git -C "$KERNEL" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" || return 0
    [ -n "$common" ] || return 0
    root="$common/forbric-gate-cache/arms"
  fi
  key="$(shasum -a 256 "$keyfile" 2>/dev/null | cut -c1-32)"
  [ -n "$key" ] || return 0
  printf '%s/%s/%s\n' "$root" "$arm" "$key"
}

# control_arm_store <dir> <file-or-dir>... — keeps a passed arm's output under <dir>, whole or not at all: copied into a
# sibling first and renamed into place, with a "complete" stamp a reader requires.
control_arm_store() {
  local dir="$1" part item; shift
  [ -n "$dir" ] || return 0
  part="$dir.part.$$"
  rm -rf "$part"; mkdir -p "$part" || return 0
  for item in "$@"; do cp -c -R "$item" "$part/" 2>/dev/null || cp -R "$item" "$part/" || { rm -rf "$part"; return 0; }; done
  date '+%Y-%m-%dT%H:%M:%S' > "$part/complete"
  rm -rf "$dir"; mv "$part" "$dir" 2>/dev/null || rm -rf "$part"
}

# Rebuild the kernel jar, FAILING LOUDLY. A swallowed build error leaves a stale jar in build/libs and every
# gate downstream then silently reports on code that is not the code in the tree.
kernel_jar() {
  # Prebuilt by the sweep and not rebuilt since (see launch-kernel-server.sh): nothing to do.
  if [ -n "${FORBRIC_PREBUILT_JAR:-}" ] && [ "$(stat -c '%s:%Y' "$FORBRIC_PREBUILT_JAR" 2>/dev/null \
       || stat -f '%z:%m' "$FORBRIC_PREBUILT_JAR" 2>/dev/null)" = "${FORBRIC_PREBUILT_STAMP:-}" ]; then
    return 0
  fi
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
  if [ -n "${FORBRIC_PREBUILT_DEPS:-}" ] && [ "$(stat -c '%s:%Y' "$jar" 2>/dev/null \
       || stat -f '%z:%m' "$jar" 2>/dev/null)" = "${FORBRIC_PREBUILT_STAMP:-}" ]; then
    cp="$FORBRIC_PREBUILT_DEPS"
  else
    cp=$("$KERNEL/gradlew" --offline -q -p "$KERNEL" printBootClasspath 2>/dev/null \
          | grep -vE 'WARNING|native|Restricted|enable' | tail -1)
  fi
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
# outlasted the timer (measured: gate-m3 with a 28 s boot against its 22 s timer). With a boot done <settle> or more
# seconds before the timer ends this writes the line at the same moment the timer did; a later boot, inside the timer's
# last <settle> seconds or past it, moves it to <settle> (default 5) seconds after "Done (", so what the gate wanted to
# see run after the boot still gets some ticks.
#
# THE SETTLE COUNTS FROM DONE, WHENEVER DONE CAME. Until C5 the timer was a bare sleep, and a Done already in the log
# when it ran out was taken as "the boot was inside the timer", with no settle at all. A boot that ended in the timer's
# last second therefore got the line in the same second as its Done: gate-m3 went red under load (VERIFY run 1, Done
# 15:16:25, "Stopping the server" 15:16:26, the canary's 20-tick ServerTickEvent line never printed), latent since the
# helper was written. So the timer watches the log too and notes the second Done is first seen; the line goes out at
# the later of the timer's end and that second plus <settle>. A boot that ended at least <settle> seconds before the
# timer still gets the line when the timer ends, as before. Seconds are counted by the one-second sleeps themselves,
# so every count runs late, never early: the timer is never shorter than <seconds> and the settle never shorter than
# <settle>. A Done that is already in the log when this is called has no known moment and is taken as seen at 0.
# Both counts are plain decimals: a leading zero is refused with the fraction, since bash arithmetic reads "010" as
# octal 8 and "08" as an error that ends the whole shell (in a gate, the feeder dies and no line is ever written).
server_ready() {
  local log="$1" seconds="$2" settle="${3:-5}" cap="${STOP_READY_CAP:-150}" stall="${STOP_READY_STALL:-60}"
  local elapsed=0 seen=-1 waited=0 still=0 size last=-1 rest
  case "$seconds:$settle" in *[!0-9:]*|:*|*:|0[0-9]*|*:0[0-9]*) echo "server_ready: <seconds> and <settle> are whole seconds written without a leading zero, got '$seconds' '$settle'" >&2; return 1;; esac
  while [ "$elapsed" -lt "$seconds" ]; do
    if [ "$seen" -lt 0 ] && grep -aq 'Done (' "$log" 2>/dev/null; then seen=$elapsed; fi
    sleep 1; elapsed=$((elapsed + 1))
  done
  while [ "$seen" -lt 0 ]; do
    if grep -aq 'Done (' "$log" 2>/dev/null; then seen=$elapsed; break; fi
    # Gone or going: a line written now would only meet a closed pipe (and a pipeline under pipefail would report it).
    # The game's own stop and crash lines, every "launch stopped" the kernel prints when it refuses a launch, the
    # launcher's refusal, and a JVM that died before the game started.
    grep -aqE 'Stopping server|Failed to start the minecraft server|Encountered an unexpected exception|launch stopped|\[kernel-launch\] FATAL|Exception in thread "main"|Error occurred during initialization of VM|Could not find or load main class' \
      "$log" 2>/dev/null && return 1
    [ "$waited" -ge "$cap" ] && return 1
    size=$(wc -c < "$log" 2>/dev/null || echo 0)
    if [ "$size" = "$last" ]; then still=$((still + 1)); else still=0; last=$size; fi
    [ "$still" -ge "$stall" ] && return 1
    sleep 1; waited=$((waited + 1)); elapsed=$((elapsed + 1))
  done
  rest=$((seen + settle - elapsed))
  [ "$rest" -le 0 ] || sleep "$rest"
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

# --- shared fixtures ----------------------------------------------------------------------------------------
# WHY THIS EXISTS. A worktree links the big client packs and the downloaded-mod directories to the main checkout's
# (run/client-merged-pack -> /Users/.../forbric-kernel/run/client-merged-pack), and the gates wipe and write their
# rundirs. A gate pointed at one of those links (RUNDIR=run/client-merged-pack, a fixed M*_RUNDIR default, or a scratch
# path that resolves into one) rewrites mods/, options.txt and the world of a fixture every other worktree and the main
# checkout read -- and nothing names it: the next run of any gate there just sees a different pack. Stage 4 lost a run
# to exactly this (D39). So a gate with a fixed or default rundir asks first:
#
#   refuse_shared_rundir "$RUNDIR"
#
# It refuses, with a FAIL line and exit 3, a rundir whose real path equals or lies inside one of the shared fixtures
# below (this worktree's link or directory of that name, the main checkout's, and a fixture's own home outside every
# checkout -- forge-swap's is $FORBRIC_FORGE_SWAP, default ~/Forbric-backups/forge-swap, D54 -- because a worktree cut
# before that fixture existed has no run/forge-swap link to find it by), and a rundir that is spelled inside this
# worktree's run/ but resolves outside it through a symlink. A clone (`cp -c -R` of the fixture's real path) or a
# directory the gate creates is accepted. The rundir need not exist yet; a dangling link is refused.
SHARED_FIXTURES="client-merged-pack client-popular client-kernel client-neo-pack carry-on modmenu neoforge-builds forge-swap"

# _guard_fixture_home <name> -- where a shared fixture lives when it is in no checkout at all (empty for the others)
_guard_fixture_home() {
  case "$1" in
    forge-swap)
      if [ -n "${FORBRIC_FORGE_SWAP:-}" ]; then _guard_abs "$FORBRIC_FORGE_SWAP"
      elif [ -n "${HOME:-}" ]; then _guard_abs "$HOME/Forbric-backups/forge-swap"; fi ;;
  esac
}

# _guard_abs <path> -- absolute and lexically normalised (. and .. folded, no symlink resolved)
_guard_abs() {
  local p="$1" out="" part
  case "$p" in /*) ;; *) p="$PWD/$p" ;; esac
  local IFS=/
  for part in $p; do
    case "$part" in
      ''|.) ;;
      ..) out="${out%/*}" ;;
      *) out="$out/$part" ;;
    esac
  done
  printf '%s\n' "${out:-/}"
}

# _guard_real <path> -- the physical path; the part that does not exist yet is appended to its nearest existing parent.
# Prints nothing (status 1) for a dangling symlink on the way.
_guard_real() {
  local p rest="" base
  p="$(_guard_abs "$1")"
  while [ ! -e "$p" ]; do
    [ -L "$p" ] && return 1
    [ "$p" = / ] && break
    rest="/${p##*/}$rest"
    p="$(dirname "$p")"
  done
  base="$(cd "$p" 2>/dev/null && pwd -P)" || return 1
  [ "$base" = / ] && base=""
  printf '%s%s\n' "$base" "$rest"
}

# _guard_within <path> <dir> -- the path is the dir or lies inside it
_guard_within() { [ "$1" = "$2" ] || [ "${1#"$2"/}" != "$1" ]; }

# The main checkout's kernel: FORBRIC_MAIN_KERNEL, else the parent of the repository's common .git directory.
_guard_main_kernel() {
  if [ -n "${FORBRIC_MAIN_KERNEL:-}" ]; then printf '%s\n' "$FORBRIC_MAIN_KERNEL"; return; fi
  local common
  common="$(git -C "$KERNEL" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" || return 0
  [ -n "$common" ] && printf '%s/forbric-kernel\n' "$(dirname "$common")"
}

refuse_shared_rundir() {
  local dir="${1:-}" logical real run_logical run_real main name candidate fixture_real r why=""
  if [ -z "$dir" ]; then
    echo "[kernel] FAIL refusing rundir '': refuse_shared_rundir was given no rundir (an unset rundir variable?)"
    exit 3
  fi
  logical="$(_guard_abs "$dir")"
  if ! real="$(_guard_real "$dir")"; then
    echo "[kernel] FAIL refusing rundir $dir: it is (or passes through) a dangling symlink"
    exit 3
  fi
  run_logical="$(_guard_abs "$KERNEL/run")"
  run_real="$(_guard_real "$KERNEL/run")"
  main="$(_guard_main_kernel)"
  local roots="$run_logical $run_real"
  [ -n "$main" ] && roots="$roots $(_guard_abs "$main/run") $(_guard_real "$main/run")"
  for name in $SHARED_FIXTURES; do
    for candidate in $(for r in $roots; do printf '%s/%s\n' "$r" "$name"; done; _guard_fixture_home "$name"); do
      if _guard_within "$logical" "$candidate"; then
        why="it is the shared fixture run/$name ($candidate)"
      elif [ -e "$candidate" ] && fixture_real="$(_guard_real "$candidate")" && _guard_within "$real" "$fixture_real"; then
        why="it resolves to $real, inside the shared fixture run/$name ($fixture_real)"
      fi
      [ -n "$why" ] && break 2
    done
  done
  if [ -z "$why" ] && { _guard_within "$logical" "$run_logical" || _guard_within "$logical" "$run_real"; } \
      && ! _guard_within "$real" "$run_real"; then
    why="it is spelled inside $run_logical but resolves to $real, outside it, through a symlink"
  fi
  if [ -n "$why" ]; then
    echo "[kernel] FAIL refusing rundir $dir: $why. Clone the fixture by its real path (cp -c -R \"\$(cd <link> && pwd -P)\" <scratch>) and point the gate's rundir variable at the clone"
    exit 3
  fi
  return 0
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
