#!/usr/bin/env bash
# M55 — the creative search: a mod refreshing it the vanilla way reaches the creative screen.
#
# WHY THIS EXISTS. A mod that rebuilds the creative tabs itself and refreshes the search the vanilla way — TCDCommons,
# Better Stats' library, on every join — calls vanilla's two SessionSearchTrees producers, updateCreativeTooltips(Provider,
# List) and updateCreativeTags(List). On the merge this gate was first written against they kept MinecraftForge's bodies,
# which filed their trees in a private map the (NeoForge) creative screen never reads, so every creative search came up
# empty. On NeoForge's own base they are NeoForge's bodies: they delegate to the keyed producers, which file where the
# screen reads. This gate keeps that path checked through the screen's own grid.
#
# WHAT IT JUDGES. The creative screen's own grid: the client-smoke probe opens the real CreativeModeInventoryScreen,
# types into its search box through charTyped and logs how many stacks the grid holds. Nothing the kernel says about
# itself is asserted.
#
#   0. baseline — no mod: what the screen's own rebuild finds for each query. The yardstick, measured rather than
#      written down, so a world or feature-flag change moves both sides at once.
#   1. refreshed — the Fabric canary rebuilt the tabs and refreshed the search the vanilla way: the search tab finds
#      exactly the baseline's stacks for "stone" (minecraft:stone first), a namespaced id and a # tag, twice and again
#      after a language-change rebuild.
#   2. withheld — the same canary with the refresh left out (-Dforbric.creativeSearchCanaryRefresh=off): every one of
#      those searches finds nothing in the screen, in both passes, so the probe can see an empty search (the red control
#      for phase 1).
#
# STAGE 4 OF DROPPING MinecraftForge. The gate also checked the kernel's CreativeSearchTreesInjector, which rewrote
# MinecraftForge's SessionSearchTrees.getSearchTree on the merged base (a reader that joined a future nothing completed,
# so a MinecraftForge caller hung): its rewrite line in phases 0 and 1, and a phase 3 with -Dforbric.creativeSearchTrees=off
# showing the screen did not need it. NeoForge's base has no getSearchTree and Forbric loads no MinecraftForge mod, so the
# repair and its switch are gone (C3), and with them those checks and phase 3. Phases 0-2 were its symptom check: on the
# genuine base with the repair deleted they still find the baseline's stacks after the refresh, and nothing without it.
# RED, measured: M55_EXTRA_JVM=-Dforbric.creativeSearchCanaryRefresh=off turns phase 1's refresh line, its seven grid
# counts and "minecraft:stone first" red.
# GATE-PARALLEL: rundirs=client-creative-search-m55 mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="${M55_RUNDIR:-$KERNEL/run/client-creative-search-m55}"
WORLD=ForbricTest
FIXTURE="${M55_WORLD:-$KERNEL/run/client-merged-pack/saves/$WORLD}"
RESULTS="$BUILD/verification/m55-creative-search"
AT=100
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

[ -d "$FIXTURE" ] || { echo "[kernel] SKIP-FATAL: no world to copy at $FIXTURE" >&2; exit 3; }
kernel_jar
bash "$KERNEL/run/build-creative-search-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_client <phase> <extra jvm flags> [nocanary] — a fresh copy of the world, the Fabric canary alone (or no mod at
# all), one probe run. A flag in <extra> overrides the same flag set here, and M55_EXTRA_JVM (a RED control's switch,
# given to every phase) overrides both: the JVM takes the last.
run_client() {
  local phase="$1" extra="$2" canary="${3:-canary}" pid
  mkdir -p "$RUNDIR"
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/mods" "$RUNDIR/saves" "$RUNDIR/screenshots" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs"
  mkdir -p "$RUNDIR/mods" "$RUNDIR/saves" "$RUNDIR/quickPlay"
  # A clone on APFS, a copy elsewhere: the run writes to the world, and the fixture must not change.
  cp -cR "$FIXTURE" "$RUNDIR/saves/$WORLD" 2>/dev/null || cp -R "$FIXTURE" "$RUNDIR/saves/$WORLD"
  rm -f "$RUNDIR/saves/$WORLD/session.lock"
  case "$canary" in
    nocanary) ;;
    *) cp "$KERNEL/run/canary/forbriccreativesearch.jar" "$RUNDIR/mods/" ;;
  esac
  # Without onboardAccessibility:false a fresh client sits on the first-launch accessibility screen forever; without
  # pauseOnLostFocus:false another window taking focus opens the pause menu, and the probe's screen never gets in front.
  printf 'onboardAccessibility:false\npauseOnLostFocus:false\nlang:en_us\nguiScale:2\nnarrator:0\ntutorialStep:none\n' > "$RUNDIR/options.txt"
  FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 \
-Dforbric.clientSmokeCreativeSearch=$AT -Dforbric.clientSmokeDisconnectTicks=$((AT + 110)) $extra ${M55_EXTRA_JVM:-}" \
  RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
    --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$RESULTS/$phase.log" 2>&1 &
  pid=$!
  record_server_pid "$RUNDIR" "$pid"
  echo "[kernel] $phase: client pid=$pid (this gate never kills by name — another client may be running)"
  await_server "$pid" "$RESULTS/$phase.log" 320
  rm -f "$RUNDIR/.forbric-gate.pid"
  cp -R "$RUNDIR/screenshots" "$RESULTS/$phase-screenshots" 2>/dev/null || true
}

# grid <log> <label> <query> — how many stacks the grid showed, or "none" if the probe never searched.
grid() {
  local n
  n=$(grep -aoE "creative search $2: '$3' -> [0-9]+ item" "$1" 2>/dev/null | tail -1 | grep -oE -- '-> [0-9]+' | grep -oE '[0-9]+')
  echo "${n:-none}"
}

# expect_grid <phase> <label> <query> <zero|N> — N is the baseline's count for the query, which must be nonzero.
expect_grid() {
  local phase="$1" label="$2" query="$3" want="$4" got
  got=$(grid "$RESULTS/$phase.log" "$label" "$query")
  case "$want:$got" in
    *:none) echo "[kernel] FAIL $phase: the probe never reported '$query' ($label)"; FAIL=1 ;;
    zero:0) echo "[kernel] PASS $phase: '$query' ($label) found nothing, as the control expects" ;;
    zero:*) echo "[kernel] FAIL $phase: '$query' ($label) found $got stack(s) — the canary did not reproduce the bug"; FAIL=1 ;;
    none:*|0:*) echo "[kernel] FAIL $phase: the baseline has no count for '$query' to compare with"; FAIL=1 ;;
    *) if [ "$got" = "$want" ]; then echo "[kernel] PASS $phase: '$query' ($label) found $got stack(s), as the baseline does"
       else echo "[kernel] FAIL $phase: '$query' ($label) found $got stack(s), the baseline $want"; FAIL=1; fi ;;
  esac
}

QUERIES=(stone minecraft:oak '#planks')

step "0. baseline: no mod — what the creative screen's own rebuild finds"
run_client baseline "" nocanary
check "baseline: the client entered the world" 'ClientSmoke\] client-ready after' "$RESULTS/baseline.log"
# base <query> — the baseline's count (bash 3.2 has no associative arrays, so it is read back from the log).
base() { grid "$RESULTS/baseline.log" "pass 2" "$1"; }
for query in "${QUERIES[@]}"; do echo "[kernel] baseline: '$query' -> $(base "$query") stack(s)"; done

step "1. refreshed: a mod refreshed the creative search the vanilla way; the screen finds the baseline's items"
run_client refreshed ""
check "refreshed: the client entered the world" 'ClientSmoke\] client-ready after' "$RESULTS/refreshed.log"
check "refreshed: the canary rebuilt the tabs and refreshed the search the vanilla way" \
  'ForbricCreativeSearchCanary\] rebuilt the creative tabs \(changed=true\) and refreshed' "$RESULTS/refreshed.log"
check "refreshed: the creative screen opened in creative mode" \
  'creative search: opened CreativeModeInventoryScreen \(client creative=true\)' "$RESULTS/refreshed.log"
for label in "pass 1" "pass 2"; do
  for query in "${QUERIES[@]}"; do expect_grid refreshed "$label" "$query" "$(base "$query")"; done
done
expect_grid refreshed "after the language rebuild" stone "$(base stone)"
check "refreshed: 'stone' lists minecraft:stone first" "creative search pass 2: 'stone' -> [0-9]+ item\(s\) \[minecraft:stone," "$RESULTS/refreshed.log"
check_absent "refreshed: nothing aboard was refused" '\[Forbric/Unsupported\] refusing' "$RESULTS/refreshed.log"
check_absent "refreshed: no crash report" 'Preparing crash report' "$RESULTS/refreshed.log"

step "2. withheld: the same rebuild with the refresh left out finds nothing"
run_client withheld "-Dforbric.creativeSearchCanaryRefresh=off"
check "withheld: the canary rebuilt the tabs and left the refresh out" \
  'ForbricCreativeSearchCanary\] rebuilt the creative tabs \(changed=true\) and withheld the creative search refresh' "$RESULTS/withheld.log"
check "withheld: the creative screen opened in creative mode" \
  'creative search: opened CreativeModeInventoryScreen \(client creative=true\)' "$RESULTS/withheld.log"
for label in "pass 1" "pass 2"; do
  for query in "${QUERIES[@]}"; do expect_grid withheld "$label" "$query" zero; done
done
check_absent "withheld: no crash report" 'Preparing crash report' "$RESULTS/withheld.log"

step "M55 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M55 CREATIVE SEARCH GATE GREEN — the creative search finds items after a vanilla-way refresh, and nothing without it"
else
  echo "[kernel] ❌ M55 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
