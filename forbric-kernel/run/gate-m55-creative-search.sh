#!/usr/bin/env bash
# M55 — the creative search: a mod refreshing it the vanilla way reaches the creative screen, and the kernel still
# rewrites MinecraftForge's SessionSearchTrees.getSearchTree reader on every client boot of the merged base.
#
# WHY THIS EXISTS. SessionSearchTrees came out of the byte merge with halves from both families.
#   * Vanilla's two producers, updateCreativeTooltips(Provider, List) and updateCreativeTags(List). On the merge this
#     gate was first written against they kept MinecraftForge's bodies, which file their trees in a private map the
#     (NeoForge) creative screen never reads, so a mod that rebuilds the tabs itself and refreshes the search the
#     vanilla way — TCDCommons, Better Stats' library, on every join — left every creative search empty. Since the hc
#     base they are NeoForge's bodies: they delegate to the keyed producers, which file where the screen reads. Phase 1
#     keeps that path checked through the screen's own grid, and phase 3 shows it no longer needs the kernel.
#   * MinecraftForge's reader, getSearchTree(Key), still MinecraftForge's body: creativeSearch.getOrDefault(key, EMPTY)
#     .join(), over a map nothing fills, defaulting to a future the merged constructor creates and nothing completes.
#     MinecraftForge's own creative screen reads every search through it, and a MinecraftForge mod that does the same
#     waits forever — on the client thread, the game freezes for good. CreativeSearchTreesInjector rewrites that body
#     to read the trees NeoForge's keyed producers file (KernelCreativeSearch.tree). On the current base that reader is
#     the only thing the repair rewrites. The rewrite runs on every client boot, MinecraftForge mod or not, because the
#     class is the merged base's; nothing in the merged base or the carrier calls getSearchTree, so only a MinecraftForge
#     mod could ever observe what it answers (see STAGE 3 below).
#
# WHAT IT JUDGES. The creative screen's own grid: the client-smoke probe opens the real CreativeModeInventoryScreen,
# types into its search box through charTyped and logs how many stacks the grid holds. Not the kernel's log line —
# except the repair's own line, which is all of the repair a client with no MinecraftForge mod can show.
#
#   0. baseline — no mod: what the screen's own rebuild finds for each query. The yardstick, measured rather than
#      written down, so a world or feature-flag change moves both sides at once. The repair rewrites getSearchTree here
#      too, with no mod aboard at all.
#   1. refreshed — the Fabric canary rebuilt the tabs and refreshed the search the vanilla way: the search tab finds
#      exactly the baseline's stacks for "stone" (minecraft:stone first), a namespaced id and a # tag, twice and again
#      after a language-change rebuild.
#   2. withheld — the same canary with the refresh left out (-Dforbric.creativeSearchCanaryRefresh=off): every one of
#      those searches finds nothing in the screen, in both passes, so the probe can see an empty search.
#   3. unrepaired — phase 1 with the repair off (-Dforbric.creativeSearchTrees=off): the switch is honoured (its line,
#      and no rewrite line — the negative control for the rewrite checks in phases 0 and 1), and the screen still finds
#      the baseline's stacks after the canary's refresh, so phase 1's result is the merged base's and not the repair's.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). Phases 1-3 staged a second canary beside the Fabric one,
# canary/creative-search/forge (forbriccreativesearchforge.jar), a MinecraftForge mod that called getSearchTree the way
# MinecraftForge's creative screen does, and phase 4 ran it alone with a searchable tab of its own. Forbric now refuses a
# MinecraftForge-only jar, and a client launched straight into a world (quick-play) with a refused jar stops, so none of
# those clients reached the world. The MinecraftForge canary is no longer staged, and every check that read its lines
# is gone: getSearchTree's counts for the three queries in phase 1 (and minecraft:stone first), its empty answer in
# phase 2, the reader answering within its bound, phase 3's hang (the repair's old negative control, which needed a
# MinecraftForge caller to hang), and all of phase 4 — its own tab's name and tag searches through MinecraftForge's
# CreativeModeTabSearchRegistry keys, the key nobody handed out, and the RED mutation of KernelCreativeSearch.tree
# reading the key it was handed. getSearchTree has no other caller, so no NeoForge or Fabric mod can produce those
# lines; what the rewritten body answers stays covered by CreativeSearchTreesInjectorExecutionTest. Kept, because they
# print with no MinecraftForge mod aboard: the repair's rewrite line (now also asserted in the no-mod baseline) and the
# switch's own line. Every check of the Fabric canary and the screen is unchanged, and phase 3 now asserts the screen
# instead of the hang. What a refused MinecraftForge jar does to a client is gate-m26's (phase 2), to a server
# gate-m29's. RED, measured: M55_EXTRA_JVM=-Dforbric.creativeSearchTrees=off turns exactly the two rewrite checks red
# (baseline and refreshed); M55_EXTRA_JVM=-Dforbric.creativeSearchCanaryRefresh=off turns phase 1's refresh line, its
# seven grid counts and "minecraft:stone first" red, and phase 3's refresh line and six grid counts (every grid now 0).
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
check "baseline: the repair rewrote MinecraftForge's reader with no mod aboard" \
  '\[Forbric/CreativeSearch\] net\.minecraft\.client\.multiplayer\.SessionSearchTrees\.getSearchTree now reads' "$RESULTS/baseline.log"
# base <query> — the baseline's count (bash 3.2 has no associative arrays, so it is read back from the log).
base() { grid "$RESULTS/baseline.log" "pass 2" "$1"; }
for query in "${QUERIES[@]}"; do echo "[kernel] baseline: '$query' -> $(base "$query") stack(s)"; done

step "1. refreshed: a mod refreshed the creative search the vanilla way; the screen finds the baseline's items"
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

step "3. unrepaired: phase 1 with the repair off — the switch is honoured, and the screen does not need the repair"
run_client unrepaired "-Dforbric.creativeSearchTrees=off"
check "unrepaired: the switch is off" '\[Forbric/CreativeSearch\] -Dforbric\.creativeSearchTrees=off' "$RESULTS/unrepaired.log"
check_absent "unrepaired: the repair did not rewrite the reader" '\[Forbric/CreativeSearch\] .*getSearchTree now reads' "$RESULTS/unrepaired.log"
check "unrepaired: the canary refreshed the search the vanilla way, as in phase 1" \
  'ForbricCreativeSearchCanary\] rebuilt the creative tabs \(changed=true\) and refreshed' "$RESULTS/unrepaired.log"
check "unrepaired: the creative screen opened in creative mode" \
  'creative search: opened CreativeModeInventoryScreen \(client creative=true\)' "$RESULTS/unrepaired.log"
for label in "pass 1" "pass 2"; do
  for query in "${QUERIES[@]}"; do expect_grid unrepaired "$label" "$query" "$(base "$query")"; done
done
check_absent "unrepaired: no crash report" 'Preparing crash report' "$RESULTS/unrepaired.log"

step "M55 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M55 CREATIVE SEARCH GATE GREEN — the creative search finds items after a vanilla-way refresh, and the kernel still rewrites MinecraftForge's getSearchTree"
else
  echo "[kernel] ❌ M55 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
