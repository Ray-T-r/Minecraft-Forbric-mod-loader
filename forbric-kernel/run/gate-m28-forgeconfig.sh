#!/usr/bin/env bash
# M28 — NeoForge COMMON configs load once and the carrier's native file watcher reads a live edit.
# RED control: M28_EXTRA_JVM='-Dforbric.earlyConfigs=off' (no assertion is relaxed).
#
# The NeoForge canary (forbricneolive) registers a COMMON config with probe=11, and the gate edits the file to 73 while
# the server runs. The contract: the kernel's early pass opens COMMON exactly once, and the carrier's own watcher
# (NeoForge's ConfigWatcher) reads a live edit back. With -Dforbric.earlyConfigs=off nothing opens a NeoForge COMMON
# config (NeoForge's registerConfig opens STARTUP only), so no Loading, no file, nothing to edit and no Reloading.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). Both families rode in this gate: the MinecraftForge canary
# (forbriclive) made the same edit on its own COMMON file and was asserted the same way, plus MinecraftForge's own
# forge-common.toml. Forbric now refuses a MinecraftForge-only jar and a dedicated server stops on the refusal, so the
# canary would stop the server before either half could be read. Its half -- the M28_EDIT / M28_WATCH /
# M28_ASSERTIONS blocks and its jar -- is gone (Stage 2.5 had already given the NeoForge canary the same contract, and
# the -Dforbric.earlyConfigs=off control turns the NeoForge half red on its own). Three checks of that block were not
# about MinecraftForge and moved into the NeoForge block unchanged: the server reached Done, it stopped cleanly, and no
# startup or watcher failure. MinecraftForge's OWN config (forge-common.toml) is not the canary's: the carrier
# registers it on every boot, and the kernel opens it early with no MinecraftForge mod aboard, so its loading line, the
# absence of a failed config and the file itself are still asserted (step "the carrier's own COMMON config"). What a
# refused MinecraftForge jar does to a server is gate-m29's.
# GATE-PARALLEL: rundirs=server-forge-config mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="$KERNEL/run/server-forge-config"
LOG="$BUILD/gate-m28-forgeconfig.log"
NEO_WATCH_LOG="$BUILD/gate-m28-neo-config-reload.log"
NEO_CONFIG="$RUNDIR/config/forbricneolive-common.toml"
mkdir -p "$BUILD"
[ -f "$NEOLIVE" ] || { echo "[kernel] FATAL: missing $NEOLIVE; run build-testmods.sh or run/build-neolive-canary.sh" >&2; exit 3; }
kernel_jar

step "start with no config files and hold the dedicated server open"
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR"
mkdir -p "$RUNDIR/mods"
cp "$NEOLIVE" "$RUNDIR/mods/"
printf 'eula=true\n' > "$RUNDIR/eula.txt"
printf 'server-port=%s\nonline-mode=false\nlevel-type=minecraft\\:flat\nlevel-name=ConfigWorld\nmax-tick-time=-1\nview-distance=2\nsync-chunk-writes=false\n' "${M28_PORT:-25608}" > "$RUNDIR/server.properties"
FIFO="$RUNDIR/.stdin"
mkfifo "$FIFO"
cleanup() {
  exec 9>&-
  if [ -n "${SRVPID:-}" ]; then kill_tree "$SRVPID"; fi
  rm -f "$FIFO" "$(_pidfile "$RUNDIR")"
}
trap 'rc=$?; cleanup; _gate_exit "$rc"' EXIT
trap 'exit 130' INT TERM
: > "$NEO_WATCH_LOG"
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

step "edit the NeoForge canary's COMMON file while the server is running"
NEO_EDITED=0
if [ "$READY" -eq 1 ]; then
  # M28_NEO_EDIT_BEGIN — the same mutation on the NeoForge canary's file; only events after it count.
  NEO_BEFORE_LINES=$(wc -l < "$LOG" | tr -d ' ')
  if python3 - "$NEO_CONFIG" <<'PY_NEO_EDIT'
from pathlib import Path
import os, re, sys
path = Path(sys.argv[1])
text = path.read_text()
text, count = re.subn(r'(?m)^(\s*probe\s*=\s*)11(\s*(?:#.*)?)$', r'\g<1>73\2', text)
if count != 1:
    raise SystemExit(f'expected exactly one default probe=11 before the edit, found {count}')
with path.open('w') as output:
    output.write(text)
    output.flush()
    os.fsync(output.fileno())
print('[kernel] changed live NeoForge COMMON probe from 11 to 73')
PY_NEO_EDIT
  then NEO_EDITED=1; else FAIL=1; fi
  # M28_NEO_EDIT_END
fi

NEO_RELOADED=0
if [ "$NEO_EDITED" -eq 1 ]; then
  # M28_NEO_WATCH_BEGIN — only events appended AFTER the NeoForge edit can satisfy the watcher observation.
  for i in $(seq 1 40); do
    kill -0 "$SRVPID" 2>/dev/null || break
    tail -n "+$((NEO_BEFORE_LINES + 1))" "$LOG" > "$NEO_WATCH_LOG"
    if grep -qaE 'ForbricNeoLive/CFG\] RELOADING forbricneolive-common\.toml: probe=73 loaded=true([[:space:]]|$)' "$NEO_WATCH_LOG"; then
      NEO_RELOADED=1
      break
    fi
    sleep 1
  done
  # M28_NEO_WATCH_END
fi

step "stop the server after the bounded native watcher observation"
if kill -0 "$SRVPID" 2>/dev/null; then printf 'stop\n' >&9; fi
exec 9>&-
await_server "$SRVPID" "$LOG" 90
SRVPID=

step "the carrier's own COMMON config still loads early with no MinecraftForge mod (must PASS)"
check "Forge COMMON configs were applied" 'loaded MinecraftForge configs \(COMMON\): applied [1-9][0-9]*, already loaded [0-9]+, failed 0 from ' "$LOG"
check_absent "no Forge config failed" 'loaded MinecraftForge configs \([^)]*\): .*failed [1-9]' "$LOG"
if [ -s "$RUNDIR/config/forge-common.toml" ]; then echo "[kernel] PASS config/forge-common.toml was created"
else echo "[kernel] FAIL config/forge-common.toml was not created"; FAIL=1; fi

# M28_NEO_ASSERTIONS_BEGIN — run against log/file fixtures as well as the live gate's authoritative stdout log.
step "NeoForge COMMON loading, one Loading event, and real watcher readback"
assert_eq "the dedicated server reached Done" 1 "$READY"
assert_eq "the gate changed the live NeoForge COMMON file" 1 "$NEO_EDITED"
assert_eq "NeoForge's native Reloading read back 73 within 40 seconds" 1 "$NEO_RELOADED"
check "the kernel opened the NeoForge COMMON configs" 'loaded NeoForge configs \(COMMON\) from .* opened [1-9][0-9]* of [1-9][0-9]* config\(s\)' "$LOG"
check_absent "no NeoForge config failed to open" 'could not open forbricneolive.s .* config' "$LOG"
check "the NeoForge COMMON default was read from the loaded spec" 'ForbricNeoLive/CFG\] LOADING forbricneolive-common\.toml: probe=11 loaded=true([[:space:]]|$)' "$LOG"
assert_eq "NeoForge COMMON Loading fired exactly once" 1 "$(grep -acE 'ForbricNeoLive/CFG\] LOADING forbricneolive-common\.toml:' "$LOG" || true)"
check "a new native Reloading event read the changed NeoForge value" 'ForbricNeoLive/CFG\] RELOADING forbricneolive-common\.toml: probe=73 loaded=true([[:space:]]|$)' "$NEO_WATCH_LOG"
if [ -s "$NEO_CONFIG" ]; then echo "[kernel] PASS config/forbricneolive-common.toml was created"
else echo "[kernel] FAIL config/forbricneolive-common.toml was not created"; FAIL=1; fi
if python3 - "$NEO_CONFIG" <<'PY_NEO_READBACK'
from pathlib import Path
import sys, tomllib
value = tomllib.loads(Path(sys.argv[1]).read_text()).get('probe')
if type(value) is not int or value != 73:
    raise SystemExit(f'NeoForge COMMON file readback was {value!r}, expected 73')
print('[kernel] PASS NeoForge COMMON file still contains probe=73')
PY_NEO_READBACK
then :; else FAIL=1; fi
if [ ! -e "$RUNDIR/config/forbricneolive-client.toml" ]; then echo "[kernel] PASS no NeoForge CLIENT file on the dedicated server"
else echo "[kernel] FAIL dedicated server opened a NeoForge CLIENT config"; FAIL=1; fi
check_absent "no NeoForge CLIENT Loading on the dedicated server" 'ForbricNeoLive/CFG\] LOADING forbricneolive-client\.toml:' "$LOG"
check_absent "no NeoForge config opened twice" 'Opening a config that was already loaded' "$LOG"
check "server stopped cleanly" 'All dimensions are saved' "$LOG"
check_absent "no startup or watcher failure" 'Preparing crash report|Encountered an unexpected exception|Exception caught by file watcher|Failed to (load|reload) config' "$LOG"
# M28_NEO_ASSERTIONS_END

step "M28 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M28 CONFIG GATE GREEN — NeoForge COMMON loaded once and the native watcher read back the live edit"
else
  echo "[kernel] M28 CONFIG GATE RED — see $LOG and $NEO_WATCH_LOG"
fi
exit "$FAIL"
