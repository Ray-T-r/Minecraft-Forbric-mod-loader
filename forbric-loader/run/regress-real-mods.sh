#!/usr/bin/env bash
# Forbric standing regression gate: every substrate/runtime/driver change must pass this before it lands.
#   1. substrate patches intact          4. REAL lifecycle server boot (eula-accepted live rundir):
#   2. unit tests green                     genuine LoadingModList (0 errors), real 3rd-party mods
#   3. headless registration baseline      (Macaw's Bridges, spark, GeckoLib, JourneyMap incl. JiJ),
#      (pre-EULA, both probe items)        bridge window + Fabric main, gameplay TickEvent canary,
#                                          cross-boot registry persistence, clean tick-limited shutdown.
# Optional client stage: set FORBRIC_INCLUDE_CLIENT_SMOKE=1 to run run/regress-merged-client.sh.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$(cd "$HERE/.." && pwd)"
LIVE="$PROJECT/run/server-forge-26.2-live"
TRIAGE="$HERE/triage"
mkdir -p "$TRIAGE"
STAMP="$(date +%Y%m%d-%H%M%S)"
FAIL=0

step() { printf '\n[regress] ==== %s ====\n' "$1"; }
check() { # <what> <grep-pattern> <file> [required-count]
  local what="$1" pat="$2" file="$3" want="${4:-1}" got
  got=$(grep -cE "$pat" "$file" 2>/dev/null || true)
  if [ "${got:-0}" -ge "$want" ]; then printf '[regress] PASS %s (%s)\n' "$what" "$got"
  else printf '[regress] FAIL %s (want>=%s got %s)\n' "$what" "$want" "${got:-0}"; FAIL=1; fi
}

step "substrate patches"
"$HERE/verify-substrate-patches.sh" || FAIL=1

step "unit tests"
"$PROJECT/gradlew" -q -p "$PROJECT" test || FAIL=1

step "headless registration baseline (pre-EULA)"
LOG="$TRIAGE/regress-$STAMP-headless.log"
FORBRIC_JVM="-Dforbric.headlessRegister=true -Dforbric.verifyItems=forbrictest:test_item,forbricfab:fab_item" \
  "$HERE/launch-server-minecraftforge.sh" > "$LOG" 2>&1 || true
check "headless probe items" "Forbric/VERIFY.*= true" "$LOG" 2

step "REAL lifecycle server boot (live rundir, tick-limited)"
if [ ! -f "$LIVE/eula.txt" ]; then
  echo "[regress] SKIP live boot: $LIVE/eula.txt absent (accept the EULA there to enable this stage)"
else
  LOG="$TRIAGE/regress-$STAMP-live.log"
  ( RUNDIR="$LIVE" FORBRIC_JVM="-Dforbric.fabricMainDeferred=true -Dforbric.ticks=60" \
      "$HERE/launch-server-minecraftforge.sh" > "$LOG" 2>&1 & echo $! > "$TRIAGE/.regress.pid" )
  for _ in $(seq 1 150); do
    grep -qE 'tick limit .* reached|Exception in thread|Failed to start|Mod Loading has failed|BUILD FAILED' "$LOG" 2>/dev/null && break
    sleep 2
  done
  sleep 10
  kill "$(cat "$TRIAGE/.regress.pid")" 2>/dev/null || true
  sleep 4

  check "genuine LoadingModList, 0 errors"   "genuine LoadingModList installed: .*0 errors" "$LOG"
  check "server reached Done"                "Done \("                                      "$LOG"
  check "bridge window + Fabric main"        "invoked Fabric main entrypoint"               "$LOG"
  check "gameplay TickEvent canary"          "20 server ticks observed"                     "$LOG"
  check "persistence: probe+real-mod items"  "ForbricLive/VERIFY.*present = true"           "$LOG" 3
  check "clean tick-limited shutdown"        "tick limit .* reached"                        "$LOG"
  check "Forge loot manager ensured"         "ensured Forge's LootModifierManager|LootModifierManager at ReloadableServerResources" "$LOG"
  if grep -qE "Mod Loading has failed|Failed to start the minecraft server" "$LOG"; then
    echo "[regress] FAIL mod-loading failure present"; FAIL=1
  fi
  # The LootModifierManager crash (Forge game-bus reload dispatch gap, fixed by ForgeReloadListenerMixin) must
  # never reappear: breaking a block / a fluid tick would throw this at world-tick time.
  if grep -qE "Can not retrieve LootModifierManager" "$LOG"; then
    echo "[regress] FAIL LootModifierManager crash present (Forge reload-listener regression)"; FAIL=1
  fi
fi

if [ "${FORBRIC_INCLUDE_CLIENT_SMOKE:-0}" = 1 ]; then
  step "MERGED client smoke"
  "$HERE/regress-merged-client.sh" || FAIL=1
else
  echo "[regress] SKIP merged client smoke: set FORBRIC_INCLUDE_CLIENT_SMOKE=1 to enable the GUI quick-play gate"
fi

step "result"
if [ "$FAIL" -eq 0 ]; then echo "[regress] ALL GREEN"; else echo "[regress] FAILURES — see $TRIAGE/regress-$STAMP-*.log"; fi
exit "$FAIL"
