#!/usr/bin/env bash
# M27 — the 97-jar pack must produce a nonblack frame from THIS launch.
# RED demonstrations:
#   M27_SHOT_TICKS= ./gate-m27-frame.sh          # no screenshot requested; old PNGs must not satisfy this
#   M27_FRAME=<black.png> ./gate-m27-frame.sh    # the shared pixel verdict must report BLACK
# M27_FRAME is an explicit diagnostic override only; normal runs always select a PNG newer than launch.
# GATE-PARALLEL: rundirs=client-merged-pack mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="${M27_RUNDIR:-$KERNEL/run/client-merged-pack}"
WORLD="${M27_WORLD:-ForbricTest}"
# The '-' default preserves an explicitly empty setting, which is the no-screenshot negative control.
SHOT_TICKS="${M27_SHOT_TICKS-100}"
LOG="$BUILD/gate-m27-frame.log"
FRAME_LOG="$BUILD/gate-m27-frame-verdict.log"
STARTED="$BUILD/gate-m27-started.ns"
mkdir -p "$BUILD"

[ -f "$RUNDIR/saves/$WORLD/level.dat" ] || { echo "[kernel] FATAL: no saved world at $RUNDIR/saves/$WORLD" >&2; exit 3; }
[ -f "$RUNDIR/options.txt" ] || { echo "[kernel] FATAL: no options.txt; onboarding blocks quick-play" >&2; exit 3; }
MODS=$(find "$RUNDIR/mods" -maxdepth 1 -name '*.jar' -type f 2>/dev/null | wc -l | tr -d ' ')
assert_eq "the full 97-jar pack is staged" 97 "$MODS"
[ "$FAIL" -eq 0 ] || exit "$FAIL"
kernel_jar
reap_stale_server "$RUNDIR"
mkdir -p "$RUNDIR/quickPlay" "$RUNDIR/screenshots"
rm -f "$RUNDIR/logs/latest.log"
: > "$LOG"
python3 - "$STARTED" <<'PY' || exit 3
from pathlib import Path
import sys, time
Path(sys.argv[1]).write_text(str(time.time_ns()))
PY

step "capture a frame at world tick ${SHOT_TICKS:-<disabled>}"
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeScreenshots=$SHOT_TICKS -Dforbric.clientSmokeDisconnectTicks=160 ${M27_EXTRA_JVM:-}" \
RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$LOG" 2>&1 &
CLIENT_PID=$!
record_server_pid "$RUNDIR" "$CLIENT_PID"
await_server "$CLIENT_PID" "$LOG" 400 30
rm -f "$RUNDIR/.forbric-gate.pid"
cat "$RUNDIR/logs/latest.log" >> "$LOG" 2>/dev/null || true
check "joined world" 'ClientSmoke\] joined world via quick-play' "$LOG"
check "simulation survived" 'ClientSmoke\] client-ready after' "$LOG"
check "requested an in-game screenshot" 'ClientSmoke\] screenshot requested at world tick [0-9]+' "$LOG"
check "clean disconnect" 'ClientSmoke\] clean disconnect observed' "$LOG"
check_absent "screenshot capture did not throw" 'could not take a screenshot' "$LOG"

# FRAME_SELECTION_BEGIN — also executed against old/new fixture files by GateFrameContractTest.
FRAME=$(python3 - "$RUNDIR/screenshots" "$STARTED" <<'PY'
from pathlib import Path
import sys
started = int(Path(sys.argv[2]).read_text())
images = [p for p in Path(sys.argv[1]).glob('*.png')
          if p.stat().st_mtime_ns > started and p.stat().st_size > 0]
if images:
    print(max(images, key=lambda p: (p.stat().st_mtime_ns, p.name)))
else:
    print('No PNG newer than this launch; stale screenshots are not evidence.', file=sys.stderr)
    sys.exit(1)
PY
) || FAIL=1
# FRAME_SELECTION_END
if [ -n "${M27_FRAME:-}" ]; then
  FRAME="$M27_FRAME"
  echo "[kernel] diagnostic frame override: $FRAME"
fi
if [ -n "$FRAME" ] && [ -f "$FRAME" ]; then
  python3 "$KERNEL/run/compat/frame-verdict.py" "$FRAME" > "$FRAME_LOG" 2>&1 || FAIL=1
  cat "$FRAME_LOG"
  check "the captured frame contains drawing" '(^|[[:space:]])verdict=DREW([[:space:]]|$)' "$FRAME_LOG"
else
  echo "[kernel] FAIL no fresh screenshot to inspect"; FAIL=1
fi
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M27 FRAME GATE GREEN — $FRAME"
else
  echo "[kernel] M27 FRAME GATE RED — see $LOG and $FRAME_LOG"
fi
exit "$FAIL"
