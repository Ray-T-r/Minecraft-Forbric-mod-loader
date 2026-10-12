#!/usr/bin/env bash
# M55 — the creative inventory's search finds items after a mod refreshed the search the vanilla way.
#
# WHY THIS EXISTS. The merged SessionSearchTrees kept MinecraftForge's bodies for vanilla's two search-tree producers
# (updateCreativeTooltips(Provider, List), updateCreativeTags(List)), which file their trees in a private map, while
# the creative screen is NeoForge's and reads NeoForge's CreativeModeTabSearchRegistry. The screen builds its own
# trees only when CreativeModeTabs.tryRebuildTabContents reports a change, so a mod that rebuilds the tabs itself and
# refreshes the search the vanilla way — TCDCommons, Better Stats' library, on every join — left every creative
# search empty for the session. canary/creative-search does exactly that and nothing else.
#
# WHAT IT JUDGES. The creative screen's own grid: the client-smoke probe opens the real CreativeModeInventoryScreen,
# types into its search box through charTyped and logs how many stacks the grid holds. Not the kernel's log line.
#
#   0. baseline — no canary: what the screen's own rebuild finds for each query. The yardstick, measured rather than
#      written down, so a world or feature-flag change moves both sides at once.
#   1. repaired — the canary's refresh ran, and the search tab finds exactly the baseline's stacks for "stone"
#      (minecraft:stone first), a namespaced id and a # tag, twice and again after a language-change rebuild.
#   2. withheld — the same canary rebuilds the tabs and leaves the refresh out (-Dforbric.creativeSearchCanaryRefresh=off):
#      every one of those searches finds nothing in both passes. The negative control: the screen, finding the tabs
#      unchanged, builds no trees of its own, which is exactly what it met on the merged game when the vanilla-way
#      refresh filed its trees where it does not read. So the probe can see that failure, and phase 1's counts are
#      the refresh reaching the store the screen reads.
#
# The control used to be -Dforbric.creativeSearchTrees=off, the repair switched off. It cannot fail any more: since the
# hc base the merged SessionSearchTrees keeps NeoForge's bodies of vanilla's two producers, which already file in the
# screen's store, and CreativeSearchTreesInjector rewrites only MinecraftForge's own reader, getSearchTree, which the
# screen never calls. Off, the screen found every item -- a control that can only pass proves nothing.
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

# run_client <phase> <extra jvm flags> [nocanary] — a fresh copy of the world, the canary alone (or no mod at all),
# one probe run.
run_client() {
  local phase="$1" extra="$2" canary="${3:-canary}" pid
  mkdir -p "$RUNDIR"
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/mods" "$RUNDIR/saves" "$RUNDIR/screenshots" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs"
  mkdir -p "$RUNDIR/mods" "$RUNDIR/saves" "$RUNDIR/quickPlay"
  # A clone on APFS, a copy elsewhere: the run writes to the world, and the fixture must not change.
  cp -cR "$FIXTURE" "$RUNDIR/saves/$WORLD" 2>/dev/null || cp -R "$FIXTURE" "$RUNDIR/saves/$WORLD"
  rm -f "$RUNDIR/saves/$WORLD/session.lock"
  [ "$canary" = nocanary ] || cp "$KERNEL/run/canary/forbriccreativesearch.jar" "$RUNDIR/mods/"
  # Without onboardAccessibility:false a fresh client sits on the first-launch accessibility screen forever; without
  # pauseOnLostFocus:false another window taking focus opens the pause menu, and the probe's screen never gets in front.
  printf 'onboardAccessibility:false\npauseOnLostFocus:false\nlang:en_us\nguiScale:2\nnarrator:0\ntutorialStep:none\n' > "$RUNDIR/options.txt"
  FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 \
-Dforbric.clientSmokeCreativeSearch=$AT -Dforbric.clientSmokeDisconnectTicks=$((AT + 110)) $extra" \
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

step "1. repaired: a mod refreshed the creative search the vanilla way, and the search tab finds the baseline's items"
run_client repaired ""
check "repaired: the client entered the world" 'ClientSmoke\] client-ready after' "$RESULTS/repaired.log"
check "repaired: the canary rebuilt the tabs and refreshed the search the vanilla way" \
  'ForbricCreativeSearchCanary\] rebuilt the creative tabs \(changed=true\)' "$RESULTS/repaired.log"
check "repaired: the creative screen opened in creative mode" \
  'creative search: opened CreativeModeInventoryScreen \(client creative=true\)' "$RESULTS/repaired.log"
for label in "pass 1" "pass 2"; do
  for query in "${QUERIES[@]}"; do expect_grid repaired "$label" "$query" "$(base "$query")"; done
done
expect_grid repaired "after the language rebuild" stone "$(base stone)"
check "repaired: 'stone' lists minecraft:stone first" "creative search pass 2: 'stone' -> [0-9]+ item\(s\) \[minecraft:stone," "$RESULTS/repaired.log"
check_absent "repaired: no crash report" 'Preparing crash report' "$RESULTS/repaired.log"

step "2. withheld: the same rebuild with the refresh left out finds nothing (negative control)"
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
  echo "[kernel] ✅ M55 CREATIVE SEARCH GATE GREEN — the creative search finds items after a vanilla-way refresh"
else
  echo "[kernel] ❌ M55 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
