#!/usr/bin/env bash
# M28 — Forge COMMON configs load once and the carrier's native file watcher reads a live edit.
# RED control: M28_EXTRA_JVM='-Dforbric.earlyConfigs=off' (no assertion is relaxed).
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="$KERNEL/run/server-forge-config"
LOG="$BUILD/gate-m28-forgeconfig.log"
WATCH_LOG="$BUILD/gate-m28-config-reload.log"
FORGE="$RUN_OLD/forge-runtime/forbriclive.jar"
CONFIG="$RUNDIR/config/forbriclive-common.toml"
mkdir -p "$BUILD"
[ -f "$FORGE" ] || { echo "[kernel] FATAL: missing $FORGE; run build-testmods.sh" >&2; exit 3; }
kernel_jar

step "start with no Forge config files and hold the dedicated server open"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR"
mkdir -p "$RUNDIR/mods"
cp "$FORGE" "$RUNDIR/mods/"
printf 'eula=true\n' > "$RUNDIR/eula.txt"
printf 'server-port=%s\nonline-mode=false\nlevel-type=minecraft\\:flat\nlevel-name=ConfigWorld\nmax-tick-time=-1\nview-distance=2\nsync-chunk-writes=false\n' "${M28_PORT:-25608}" > "$RUNDIR/server.properties"
FIFO="$RUNDIR/.stdin"
mkfifo "$FIFO"
cleanup() {
  exec 9>&-
  if [ -n "${SRVPID:-}" ]; then kill_tree "$SRVPID"; fi
  rm -f "$FIFO" "$(_pidfile "$RUNDIR")"
}
trap cleanup EXIT
trap 'exit 130' INT TERM
: > "$WATCH_LOG"
FORBRIC_JVM="${M28_EXTRA_JVM:-}" RUNDIR="$RUNDIR" \
  "$KERNEL/run/launch-kernel-server.sh" < "$FIFO" > "$LOG" 2>&1 &
SRVPID=$!
record_server_pid "$RUNDIR" "$SRVPID"
exec 9>"$FIFO"
READY=0
for i in $(seq 1 240); do
  kill -0 "$SRVPID" 2>/dev/null || break
  if grep -qaE 'Done \(' "$LOG"; then READY=1; break; fi
  sleep 1
done

step "edit the actual COMMON file while the server is running"
EDITED=0
if [ "$READY" -eq 1 ]; then
  # M28_EDIT_BEGIN — offline contracts execute this exact mutation, with a real temporary TOML file.
  BEFORE_LINES=$(wc -l < "$LOG" | tr -d ' ')
  if python3 - "$CONFIG" <<'PY_EDIT'
from pathlib import Path
import os, re, sys
path = Path(sys.argv[1])
text = path.read_text()
text, count = re.subn(r'(?m)^(\s*probe\s*=\s*)11(\s*(?:#.*)?)$', r'\g<1>73\2', text)
if count != 1:
    raise SystemExit(f'expected exactly one default probe=11 before the edit, found {count}')
# Write the existing file in place so Forge/NightConfig observes the ordinary file modification event.
with path.open('w') as output:
    output.write(text)
    output.flush()
    os.fsync(output.fileno())
print('[kernel] changed live COMMON probe from 11 to 73')
PY_EDIT
  then EDITED=1; else FAIL=1; fi
  # M28_EDIT_END
fi

RELOADED=0
if [ "$EDITED" -eq 1 ]; then
  # M28_WATCH_BEGIN — only events appended AFTER the live edit can satisfy the watcher observation.
  for i in $(seq 1 40); do
    kill -0 "$SRVPID" 2>/dev/null || break
    tail -n "+$((BEFORE_LINES + 1))" "$LOG" > "$WATCH_LOG"
    if grep -qaE 'ForbricLive/CFG\] RELOADING forbriclive-common\.toml: probe=73 loaded=true([[:space:]]|$)' "$WATCH_LOG"; then
      RELOADED=1
      break
    fi
    sleep 1
  done
  # M28_WATCH_END
fi

step "stop the server after the bounded native watcher observation"
if kill -0 "$SRVPID" 2>/dev/null; then printf 'stop\n' >&9; fi
exec 9>&-
await_server "$SRVPID" "$LOG" 90
SRVPID=

# M28_ASSERTIONS_BEGIN — run against log/file fixtures as well as the live gate's authoritative stdout log.
step "COMMON loading, one Loading event, and real watcher readback"
assert_eq "the dedicated server reached Done" 1 "$READY"
assert_eq "the gate changed the live COMMON file" 1 "$EDITED"
assert_eq "native Reloading read back 73 within 40 seconds" 1 "$RELOADED"
check "Forge COMMON configs were applied" 'loaded MinecraftForge configs \(COMMON\): applied [1-9][0-9]*, already loaded [0-9]+, failed 0 from ' "$LOG"
check_absent "no Forge config failed" 'loaded MinecraftForge configs \([^)]*\): .*failed [1-9]' "$LOG"
check "the COMMON default was read from the loaded spec" 'ForbricLive/CFG\] LOADING forbriclive-common\.toml: probe=11 loaded=true([[:space:]]|$)' "$LOG"
assert_eq "COMMON Loading fired exactly once" 1 "$(grep -acE 'ForbricLive/CFG\] LOADING forbriclive-common\.toml:' "$LOG" || true)"
check "a new native Reloading event read the changed value" 'ForbricLive/CFG\] RELOADING forbriclive-common\.toml: probe=73 loaded=true([[:space:]]|$)' "$WATCH_LOG"
for name in forbriclive-common.toml forge-common.toml; do
  if [ -s "$RUNDIR/config/$name" ]; then echo "[kernel] PASS config/$name was created"
  else echo "[kernel] FAIL config/$name was not created"; FAIL=1; fi
done
if python3 - "$CONFIG" <<'PY_READBACK'
from pathlib import Path
import sys, tomllib
value = tomllib.loads(Path(sys.argv[1]).read_text()).get('probe')
if type(value) is not int or value != 73:
    raise SystemExit(f'COMMON file readback was {value!r}, expected 73')
print('[kernel] PASS COMMON file still contains probe=73')
PY_READBACK
then :; else FAIL=1; fi
if [ ! -e "$RUNDIR/config/forbriclive-client.toml" ]; then echo "[kernel] PASS no CLIENT file on the dedicated server"
else echo "[kernel] FAIL dedicated server opened a CLIENT config"; FAIL=1; fi
check_absent "no CLIENT Loading on the dedicated server" 'ForbricLive/CFG\] LOADING forbriclive-client\.toml:' "$LOG"
check "server stopped cleanly" 'All dimensions are saved' "$LOG"
check_absent "no startup or watcher failure" 'Preparing crash report|Encountered an unexpected exception|Exception caught by file watcher|Failed to (load|reload) config' "$LOG"
# M28_ASSERTIONS_END

step "M28 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M28 FORGE-CONFIG GATE GREEN — COMMON loaded once and the native watcher read back the live edit"
else
  echo "[kernel] M28 FORGE-CONFIG GATE RED — see $LOG and $WATCH_LOG"
fi
exit "$FAIL"
