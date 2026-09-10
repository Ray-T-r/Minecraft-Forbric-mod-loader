#!/usr/bin/env bash
# Merged-client regression gate: quick-play into a fixed singleplayer world, wait for the client-ready canary,
# then cleanly disconnect and stop. Guards the tri-in-one protocol/known-pack/registry sync path specifically.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
TRIAGE="$HERE/triage"
SOURCE_RUNDIR="${FORBRIC_CLIENT_SOURCE_RUNDIR:-$PROJECT/run/client-merged}"
SMOKE_WORLD="${FORBRIC_CLIENT_SMOKE_WORLD:-ForbricSmoke}"
SMOKE_TEMPLATE="${FORBRIC_CLIENT_SMOKE_TEMPLATE:-$PROJECT/run/server-26.2-live/world}"
PROFILES="${FORBRIC_CLIENT_SMOKE_PROFILES:-minimal current}"
STAMP="$(date +%Y%m%d-%H%M%S)"
FAIL=0

mkdir -p "$TRIAGE"

step() { printf '\n[client-regress] ==== %s ====\n' "$1"; }
check() { # <what> <pattern> <file>
  local what="$1" pat="$2" file="$3"
  if grep -qE "$pat" "$file" 2>/dev/null; then
    printf '[client-regress] PASS %s\n' "$what"
  else
    printf '[client-regress] FAIL %s\n' "$what"
    FAIL=1
  fi
}
check_absent() { # <what> <pattern> <file>
  local what="$1" pat="$2" file="$3"
  if grep -qE "$pat" "$file" 2>/dev/null; then
    printf '[client-regress] FAIL %s\n' "$what"
    FAIL=1
  else
    printf '[client-regress] PASS %s\n' "$what"
  fi
}

prepare_rundir() { # <profile> <rundir>
  local profile="$1" rundir="$2"
  mkdir -p "$rundir/mods" "$rundir/saves" "$rundir/quickPlay"

  if [ ! -d "$rundir/saves/$SMOKE_WORLD" ]; then
    if [ -d "$SMOKE_TEMPLATE" ]; then
      cp -R "$SMOKE_TEMPLATE" "$rundir/saves/$SMOKE_WORLD"
    elif [ -d "$SOURCE_RUNDIR/saves/Dev" ]; then
      cp -R "$SOURCE_RUNDIR/saves/Dev" "$rundir/saves/$SMOKE_WORLD"
    else
      echo "[client-regress] FAIL no smoke-world template available for $rundir" >&2
      return 1
    fi
  fi

  case "$profile" in
    minimal)
      if [ -f "$SOURCE_RUNDIR/mods/fabric-api-0.154.0+26.2.jar" ]; then
        cp "$SOURCE_RUNDIR/mods/fabric-api-0.154.0+26.2.jar" "$rundir/mods/"
      else
        echo "[client-regress] FAIL minimal profile missing fabric-api jar in $SOURCE_RUNDIR/mods" >&2
        return 1
      fi
      ;;
    current)
      if [ -d "$SOURCE_RUNDIR/mods" ]; then
        cp "$SOURCE_RUNDIR"/mods/*.jar "$rundir/mods/" 2>/dev/null || true
      else
        echo "[client-regress] FAIL current profile missing source mods dir: $SOURCE_RUNDIR/mods" >&2
        return 1
      fi
      ;;
    *)
      echo "[client-regress] FAIL unknown profile: $profile" >&2
      return 1
      ;;
  esac
}

run_profile() { # <profile>
  local profile="$1"
  local rundir="$PROJECT/run/client-merged-smoke-$profile"
  local log="$TRIAGE/merged-client-$STAMP-$profile.log"
  local pidfile="$TRIAGE/.merged-client-$profile.pid"

  step "profile=$profile"
  prepare_rundir "$profile" "$rundir" || { FAIL=1; return; }

  (
    RUNDIR="$rundir" \
    FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$SMOKE_WORLD -Dforbric.clientSmokeReadyTicks=40 -Dforbric.clientSmokeDisconnectTicks=100" \
      "$HERE/launch-client-merged.sh" \
      --quickPlayPath "$rundir/quickPlay/log.json" \
      --quickPlaySingleplayer "$SMOKE_WORLD" > "$log" 2>&1 &
    echo $! > "$pidfile"
  )

  local pid
  pid="$(cat "$pidfile" 2>/dev/null || true)"
  for _ in $(seq 1 180); do
    grep -qE 'clean disconnect observed|RegistryManager.revertToFrozen|holder == null|Timed out while waiting for the client to load chunks|No registration for payload .* refusing to decode|Game crashed!|Mod Loading has failed' "$log" 2>/dev/null && break
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 2
  done
  # Let the client EXIT ON ITS OWN (do NOT kill at +6s) so the post-main window is exercised: a leaked
  # non-daemon thread surfaces here as vanilla's ~15s "Client shutdown from post-main" watchdog crash.
  # Only force-kill if it is still alive well past that window.
  for _ in $(seq 1 20); do # up to ~40s
    if [ -z "$pid" ] || ! kill -0 "$pid" 2>/dev/null; then break; fi
    sleep 2
  done
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    printf '[client-regress] WARN %s did not exit on its own within ~40s (force-killing)\n' "$profile"
    kill -9 "$pid" 2>/dev/null || true
  fi

  check "joined world log" '\[Forbric/ClientSmoke\] joined world via quick-play' "$log"
  check "client-ready log" '\[Forbric/ClientSmoke\] client-ready' "$log"
  check "clean disconnect log" '\[Forbric/ClientSmoke\] clean disconnect observed; stopping client' "$log"
  check "terrain uploaded (in-world render, not white screen)" 'Resizing Chunk Sections UBO' "$log"
  check_absent "no payload decode refusal" 'No registration for payload .* refusing to decode' "$log"
  check_absent "no chunk-load timeout" 'Timed out while waiting for the client to load chunks' "$log"
  check_absent "no revertToFrozen holder crash" 'RegistryManager\.revertToFrozen|holder == null' "$log"
  check_absent "no shutdown watchdog crash (leaked non-daemon thread)" 'Client shutdown from post-main|java\.lang\.Error: Watchdog' "$log"
}

for profile in $PROFILES; do
  run_profile "$profile"
done

step "result"
if [ "$FAIL" -eq 0 ]; then
  echo "[client-regress] ALL GREEN"
else
  echo "[client-regress] FAILURES — see $TRIAGE/merged-client-$STAMP-*.log"
fi
exit "$FAIL"
