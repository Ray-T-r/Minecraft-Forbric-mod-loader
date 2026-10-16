#!/usr/bin/env bash
# M55 — the creative search: a mod refreshing it the vanilla way reaches the creative screen, and a MinecraftForge mod
# reading it through MinecraftForge's SessionSearchTrees.getSearchTree gets the search results back.
#
# WHY THIS EXISTS. SessionSearchTrees came out of the byte merge with halves from both families.
#   * Vanilla's two producers, updateCreativeTooltips(Provider, List) and updateCreativeTags(List). On the merge this
#     gate was first written against they kept MinecraftForge's bodies, which file their trees in a private map the
#     (NeoForge) creative screen never reads, so a mod that rebuilds the tabs itself and refreshes the search the
#     vanilla way — TCDCommons, Better Stats' library, on every join — left every creative search empty. Since the hc
#     base they are NeoForge's bodies: they delegate to the keyed producers, which file where the screen reads. Phase 1
#     keeps that path checked through the screen's own grid; it no longer needs the kernel.
#   * MinecraftForge's reader, getSearchTree(Key), still MinecraftForge's body: creativeSearch.getOrDefault(key, EMPTY)
#     .join(), over a map nothing fills, defaulting to a future the merged constructor creates and nothing completes.
#     MinecraftForge's own creative screen reads every search through it, and a MinecraftForge mod that does the same
#     waits forever — on the client thread, the game freezes for good. CreativeSearchTreesInjector rewrites that body
#     to read the trees NeoForge's keyed producers file (KernelCreativeSearch.tree). On the current base that reader is
#     the only thing the repair rewrites, and canary/creative-search/forge calls it the way that screen does.
#
# WHAT IT JUDGES. The creative screen's own grid: the client-smoke probe opens the real CreativeModeInventoryScreen,
# types into its search box through charTyped and logs how many stacks the grid holds. And the MinecraftForge reader's
# own count for the same queries, from getSearchTree(CreativeModeTabSearchRegistry.get{Name,Tag}SearchKey(searchTab))
# .search(query) — asked from the canary's daemon thread with a bound, so a call that never returns is reported and
# neither the client nor this gate waits on it. Not the kernel's log line.
#
#   0. baseline — no mod: what the screen's own rebuild finds for each query. The yardstick, measured rather than
#      written down, so a world or feature-flag change moves both sides at once.
#   1. refreshed — the Fabric canary rebuilt the tabs and refreshed the search the vanilla way: the search tab finds
#      exactly the baseline's stacks for "stone" (minecraft:stone first), a namespaced id and a # tag, twice and again
#      after a language-change rebuild; and the MinecraftForge reader's getSearchTree answers the baseline's count for
#      each of the three.
#   2. withheld — the same canaries with the refresh left out (-Dforbric.creativeSearchCanaryRefresh=off): every one of
#      those searches finds nothing in the screen, in both passes, so the probe can see an empty search; and
#      getSearchTree answers an empty tree for a key nothing was built under, as MinecraftForge's does, instead of
#      waiting.
#   3. unrepaired — phase 1's canaries with the repair off (-Dforbric.creativeSearchTrees=off): getSearchTree, as merged,
#      does not answer within the reader's bound and never answers at all. The repair's negative control.
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

# run_client <phase> <extra jvm flags> [nocanary] — a fresh copy of the world, the two canaries alone (or no mod at
# all), one probe run. A flag in <extra> overrides the same flag set here: the JVM takes the last.
run_client() {
  local phase="$1" extra="$2" canary="${3:-canary}" pid
  mkdir -p "$RUNDIR"
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/mods" "$RUNDIR/saves" "$RUNDIR/screenshots" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs"
  mkdir -p "$RUNDIR/mods" "$RUNDIR/saves" "$RUNDIR/quickPlay"
  # A clone on APFS, a copy elsewhere: the run writes to the world, and the fixture must not change.
  cp -cR "$FIXTURE" "$RUNDIR/saves/$WORLD" 2>/dev/null || cp -R "$FIXTURE" "$RUNDIR/saves/$WORLD"
  rm -f "$RUNDIR/saves/$WORLD/session.lock"
  [ "$canary" = nocanary ] || cp "$KERNEL/run/canary/forbriccreativesearch.jar" "$KERNEL/run/canary/forbriccreativesearchforge.jar" "$RUNDIR/mods/"
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

# forge <log> <query> — the MinecraftForge reader's count from getSearchTree, or "none" if it never answered.
forge() {
  local n
  n=$(grep -aoE "ForbricCreativeSearchForge\] getSearchTree '$2' -> [0-9]+ item" "$1" 2>/dev/null | tail -1 | grep -oE -- '-> [0-9]+' | grep -oE '[0-9]+')
  echo "${n:-none}"
}

# expect_forge <phase> <query> <zero|N> — N is the baseline's count for the query, which must be nonzero.
expect_forge() {
  local phase="$1" query="$2" want="$3" got
  got=$(forge "$RESULTS/$phase.log" "$query")
  case "$want:$got" in
    *:none) echo "[kernel] FAIL $phase: MinecraftForge's getSearchTree never answered '$query'"; FAIL=1 ;;
    zero:0) echo "[kernel] PASS $phase: getSearchTree '$query' answered an empty tree, as MinecraftForge's does for a key nothing was built under" ;;
    zero:*) echo "[kernel] FAIL $phase: getSearchTree '$query' found $got stack(s) where nothing was built"; FAIL=1 ;;
    none:*|0:*) echo "[kernel] FAIL $phase: the baseline has no count for '$query' to compare with"; FAIL=1 ;;
    *) if [ "$got" = "$want" ]; then echo "[kernel] PASS $phase: getSearchTree '$query' found $got stack(s), as the screen's baseline does"
       else echo "[kernel] FAIL $phase: getSearchTree '$query' found $got stack(s), the screen's baseline $want"; FAIL=1; fi ;;
  esac
}

QUERIES=(stone minecraft:oak '#planks')

step "0. baseline: no mod — what the creative screen's own rebuild finds"
run_client baseline "" nocanary
check "baseline: the client entered the world" 'ClientSmoke\] client-ready after' "$RESULTS/baseline.log"
# base <query> — the baseline's count (bash 3.2 has no associative arrays, so it is read back from the log).
base() { grid "$RESULTS/baseline.log" "pass 2" "$1"; }
for query in "${QUERIES[@]}"; do echo "[kernel] baseline: '$query' -> $(base "$query") stack(s)"; done

step "1. refreshed: a mod refreshed the creative search the vanilla way; the screen and MinecraftForge's reader find the baseline's items"
run_client refreshed ""
check "refreshed: the client entered the world" 'ClientSmoke\] client-ready after' "$RESULTS/refreshed.log"
check "refreshed: the repair rewrote MinecraftForge's reader" \
  '\[Forbric/CreativeSearch\] net\.minecraft\.client\.multiplayer\.SessionSearchTrees\.getSearchTree now reads' "$RESULTS/refreshed.log"
check "refreshed: the canary rebuilt the tabs and refreshed the search the vanilla way" \
  'ForbricCreativeSearchCanary\] rebuilt the creative tabs \(changed=true\) and refreshed' "$RESULTS/refreshed.log"
check "refreshed: the creative screen opened in creative mode" \
  'creative search: opened CreativeModeInventoryScreen \(client creative=true\)' "$RESULTS/refreshed.log"
for label in "pass 1" "pass 2"; do
  for query in "${QUERIES[@]}"; do expect_grid refreshed "$label" "$query" "$(base "$query")"; done
done
expect_grid refreshed "after the language rebuild" stone "$(base stone)"
check "refreshed: 'stone' lists minecraft:stone first" "creative search pass 2: 'stone' -> [0-9]+ item\(s\) \[minecraft:stone," "$RESULTS/refreshed.log"
check "refreshed: the MinecraftForge reader asked getSearchTree" \
  'ForbricCreativeSearchForge\] asking SessionSearchTrees\.getSearchTree' "$RESULTS/refreshed.log"
for query in "${QUERIES[@]}"; do expect_forge refreshed "$query" "$(base "$query")"; done
check "refreshed: getSearchTree 'stone' lists minecraft:stone first" \
  "ForbricCreativeSearchForge\] getSearchTree 'stone' -> [0-9]+ item\(s\) \[minecraft:stone," "$RESULTS/refreshed.log"
check_absent "refreshed: getSearchTree answered within its bound" 'ForbricCreativeSearchForge\] getSearchTree (did not return|threw)' "$RESULTS/refreshed.log"
check_absent "refreshed: no crash report" 'Preparing crash report' "$RESULTS/refreshed.log"

step "2. withheld: the same rebuild with the refresh left out finds nothing, and getSearchTree still answers"
run_client withheld "-Dforbric.creativeSearchCanaryRefresh=off"
check "withheld: the canary rebuilt the tabs and left the refresh out" \
  'ForbricCreativeSearchCanary\] rebuilt the creative tabs \(changed=true\) and withheld the creative search refresh' "$RESULTS/withheld.log"
check "withheld: the creative screen opened in creative mode" \
  'creative search: opened CreativeModeInventoryScreen \(client creative=true\)' "$RESULTS/withheld.log"
for label in "pass 1" "pass 2"; do
  for query in "${QUERIES[@]}"; do expect_grid withheld "$label" "$query" zero; done
done
for query in "${QUERIES[@]}"; do expect_forge withheld "$query" zero; done
check_absent "withheld: getSearchTree answered within its bound" 'ForbricCreativeSearchForge\] getSearchTree (did not return|threw)' "$RESULTS/withheld.log"
check_absent "withheld: no crash report" 'Preparing crash report' "$RESULTS/withheld.log"

step "3. unrepaired: phase 1 with the repair off — MinecraftForge's getSearchTree, as merged, never answers (negative control)"
# Long enough after the reader asks (twenty ticks in) for its bound to run out while the client is still in the world.
run_client unrepaired "-Dforbric.creativeSearchTrees=off -Dforbric.clientSmokeDisconnectTicks=$((AT + 500))"
check "unrepaired: the switch is off" '\[Forbric/CreativeSearch\] -Dforbric\.creativeSearchTrees=off' "$RESULTS/unrepaired.log"
check_absent "unrepaired: the repair did not rewrite the reader" '\[Forbric/CreativeSearch\] .*getSearchTree now reads' "$RESULTS/unrepaired.log"
check "unrepaired: the canary refreshed the search the vanilla way, as in phase 1" \
  'ForbricCreativeSearchCanary\] rebuilt the creative tabs \(changed=true\) and refreshed' "$RESULTS/unrepaired.log"
check "unrepaired: the MinecraftForge reader asked getSearchTree" \
  'ForbricCreativeSearchForge\] asking SessionSearchTrees\.getSearchTree' "$RESULTS/unrepaired.log"
check "unrepaired: getSearchTree did not answer 'stone' within the reader's bound" \
  "ForbricCreativeSearchForge\] getSearchTree did not return within [0-9]+ ms \(still waiting on 'stone'\)" "$RESULTS/unrepaired.log"
check_absent "unrepaired: getSearchTree never answered at all" "ForbricCreativeSearchForge\] getSearchTree ('|threw)" "$RESULTS/unrepaired.log"
check "unrepaired: the client stayed in the world past the bound and left on its own" 'ClientSmoke\] requesting clean disconnect after' "$RESULTS/unrepaired.log"
check_absent "unrepaired: no crash report" 'Preparing crash report' "$RESULTS/unrepaired.log"

step "M55 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M55 CREATIVE SEARCH GATE GREEN — the creative search finds items after a vanilla-way refresh, and MinecraftForge's getSearchTree answers"
else
  echo "[kernel] ❌ M55 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
