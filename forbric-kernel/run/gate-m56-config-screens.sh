#!/usr/bin/env bash
# M56 — a Fabric mod's config screen opens from Forbric's own Mods screen with no Mod Menu installed, through the
# Config button a player presses; and the other ways a config screen used to be unreachable are reachable.
#
# WHY THIS EXISTS. Fabric has no config-screen API. A Fabric mod says it has settings in exactly one place, a "modmenu"
# entrypoint implementing Mod Menu's ModMenuApi, and without Mod Menu that class cannot even link. The kernel stands in
# for the API (ModMenuApiStandIn) and reads those entrypoints the way Mod Menu does (ModMenuConfigFactories), so the
# kernel's Mods screen can show a Config button for them. That was measured by hand once and by no gate. The same
# measurement found what else had no way in: a mod carried inside another jar with settings of its own had no row at
# all, the game's own options were the one config Mod Menu offers that this list did not, a keyboard-moved selection
# left the Config button showing the previous row's answer, and Mod Menu's own list (when it is installed) showed a
# universal jar NeoForge won as a nameless row with no Config button.
#
# WHAT IT JUDGES. The kernel's client smoke walks -Dforbric.clientSmokeConfigScreens through the real Mods screen: it
# finds each mod's row by the name the row shows, selects it with the keyboard (the list's own setFocused, which arrow
# keys and Tab come through) and then with a click -- each time right after selecting a row whose answer is the
# opposite one -- reads whether the Config button is visible, presses it, and logs the class of the screen in front.
# The assertions are on those classes, which the canaries themselves define (forbric.mmcfg.*), and on where each row
# sits in the list the screen draws. The canaries are built from canary/modmenu-config by
# run/build-modmenu-config-canary.sh; none is a third-party jar.
#
#   1. stand-in — no Mod Menu: canaries A and B open their own screens through their own Mod Menu entrypoints; E,
#      which declares none, opens the screen A provides for it; C and D, which A carries inside itself, have rows of
#      their own (C at the top, D under the heading "Forbric Canary Family" it names for itself) and open their
#      screens; the universal canary opens NeoForge's screen; the game's row opens OptionsScreen; the plain canary's
#      Config button stays hidden; and every one of those answers is the same whether the row was reached by keyboard
#      or by mouse.
#   2. control — phase 1 with -Dforbric.modMenuStandIn=off. Without the stand-in a Fabric mod's Mod Menu entrypoint
#      cannot be read: A, B and E show no Config button and the carried canaries have no row. The game and the NeoForge
#      half are not the stand-in's, so they still open. RED (verified): phase 1 run with M56_EXTRA_JVM set to the same
#      switch fails exactly the stand-in checks of phase 1 ("standin: forbricmmcfga ... opens its own screen" and the
#      rows of C and D).
#   3. Mod Menu — only with M56_MODMENU (a Mod Menu jar for 26.2, e.g. modmenu-20.0.3.jar) and M56_FABRIC_API (Mod Menu
#      needs fabric-api): Mod Menu itself answers hasConfigScreen=true for the universal canary, names it as its own
#      fabric.mod.json does, and builds NeoForge's screen; Forbric's list still opens A's screen and the game's. Then
#      the same with -Dforbric.modMenuForeignScreens=off, which must take the universal canary's Config button out of
#      Mod Menu again. Without the two jars this phase is reported SKIP and the gate does not fail on it.
#
# Needs a world to join (the smoke runs in-world): M56_WORLD, default run/client-merged-pack/saves/ForbricTest.
# GATE-PARALLEL: rundirs=client-config-screens-m56 mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="${M56_RUNDIR:-$KERNEL/run/client-config-screens-m56}"
WORLD=ForbricTest
FIXTURE="${M56_WORLD:-$KERNEL/run/client-merged-pack/saves/$WORLD}"
MODMENU="${M56_MODMENU:-$(ls "$KERNEL"/run/modmenu/modmenu-*.jar 2>/dev/null | head -1)}"
FABRIC_API="${M56_FABRIC_API:-$(ls "$KERNEL"/run/client-merged-pack/mods/fabric-api-*.jar 2>/dev/null | head -1)}"
PHASES="${M56_PHASES:-standin control modmenu}"
RESULTS="$BUILD/verification/m56-config-screens"
CANARY="$KERNEL/run/canary/modmenu-config"
AT=100
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

[ -d "$FIXTURE" ] || { echo "[kernel] SKIP-FATAL: no world to copy at $FIXTURE (set M56_WORLD)" >&2; exit 3; }
kernel_jar
bash "$KERNEL/run/build-modmenu-config-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# The order matters: each config canary follows a row with the opposite answer somewhere in the list, and the plain
# canary comes first so a button left visible from a previous row would show.
WALK=forbricmmplain,forbricmmcfga,forbricmmcfgb,forbricmmcfgc,forbricmmcfgd,forbricmmcfge,forbricmmuni,minecraft
CANARIES="forbricmmcfga forbricmmcfgb forbricmmcfge forbricmmplain forbricmmuni"

# run_client <phase> <extra jvm flags> [extra mod jar...] — a fresh copy of the world, the canaries, one walk.
run_client() {
  local phase="$1" extra="$2" pid jar; shift 2
  mkdir -p "$RUNDIR"
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/mods" "$RUNDIR/saves" "$RUNDIR/screenshots" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs" "$RUNDIR/config"
  mkdir -p "$RUNDIR/mods" "$RUNDIR/saves" "$RUNDIR/quickPlay"
  cp -cR "$FIXTURE" "$RUNDIR/saves/$WORLD" 2>/dev/null || cp -R "$FIXTURE" "$RUNDIR/saves/$WORLD"
  rm -f "$RUNDIR/saves/$WORLD/session.lock"
  for jar in $CANARIES; do cp "$CANARY/$jar.jar" "$RUNDIR/mods/"; done
  for jar in "$@"; do cp "$jar" "$RUNDIR/mods/"; done
  printf 'onboardAccessibility:false\npauseOnLostFocus:false\nlang:en_us\nguiScale:2\nnarrator:0\ntutorialStep:none\n' > "$RUNDIR/options.txt"
  FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 \
-Dforbric.clientSmokeConfigScreens=$WALK -Dforbric.clientSmokeConfigScreensAt=$AT \
-Dforbric.clientSmokeDisconnectTicks=$((AT + 220)) $extra" \
  RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
    --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$RESULTS/$phase.log" 2>&1 &
  pid=$!
  record_server_pid "$RUNDIR" "$pid"
  echo "[kernel] $phase: client pid=$pid (this gate never kills by name — another client may be running)"
  await_server "$pid" "$RESULTS/$phase.log" 320
  rm -f "$RUNDIR/.forbric-gate.pid"
  cp -R "$RUNDIR/screenshots" "$RESULTS/$phase-screenshots" 2>/dev/null || true
}

ESC='s/[.$]/\\&/g'
# opens <phase> <id> <kind> <screen class> — pressing Config on id's row put that class in front of the player.
opens() {
  check "$1: $2 has a Config button, and it opens $(basename "${4//.//}")" \
    "ClientSmoke\] config screen of $2 \($3\) through the Config button: $(printf '%s' "$4" | sed "$ESC")\$" "$RESULTS/$1.log"
}
# keyboard <phase> <id> <kind> <shown|hidden> — the button after a keyboard selection that followed the opposite answer.
keyboard() {
  check "$1: selected with the keyboard, $2's Config button is $4" \
    "ClientSmoke\] config screen of $2 \($3\): selected with the keyboard after [A-Za-z0-9_.-]+, the Config button is $4" \
    "$RESULTS/$1.log"
}
hidden() {
  check "$1: $2's Config button is hidden" "ClientSmoke\] config screen of $2 \($3\): the Config button is hidden" "$RESULTS/$1.log"
}
norow() {
  check "$1: $2 has no row" "ClientSmoke\] config screen of $2: the Mods screen has no row for it" "$RESULTS/$1.log"
}
# row <phase> <what> <pattern of one or more consecutive items in the screen's row list>
row() {
  check "$1: $2" "ClientSmoke\] config screens: the Mods screen lists [0-9]+ row\(s\): \[(.*, )?$3(, .*)?\]" "$RESULTS/$1.log"
}
no_row() {
  check_absent "$1: $2" "ClientSmoke\] config screens: the Mods screen lists [0-9]+ row\(s\): \[(.*, )?$3(, .*)?\]" "$RESULTS/$1.log"
}
common() {
  check "$1: the client entered the world" 'ClientSmoke\] client-ready after' "$RESULTS/$1.log"
  check "$1: the walk ran to its end" 'ClientSmoke\] config screens: [0-9]+ of 8 opened through the Config button' "$RESULTS/$1.log"
  check_absent "$1: no row was selected with nothing to contrast it with" 'selected with the keyboard after nothing else' "$RESULTS/$1.log"
  check_absent "$1: no config screen threw" 'has a Config button, but its config screen threw|config screens: the walk stopped' "$RESULTS/$1.log"
  check_absent "$1: no crash report" 'Preparing crash report' "$RESULTS/$1.log"
}

for phase in $PHASES; do case "$phase" in
standin)
step "1. stand-in: no Mod Menu — every config screen opens through Forbric's own Config button"
run_client standin "${M56_EXTRA_JVM:-}"
common standin
check "standin: Forbric stands in for Mod Menu's API" '\[Forbric/ModConfig\] Mod Menu is not installed — Forbric stands in for its API' "$RESULTS/standin.log"
for id in forbricmmcfga forbricmmcfgb forbricmmcfgc forbricmmcfgd forbricmmcfge; do keyboard standin "$id" FABRIC shown; done
opens standin forbricmmcfga FABRIC forbric.mmcfg.a.CanaryAScreen
opens standin forbricmmcfgb FABRIC forbric.mmcfg.b.CanaryBScreen
opens standin forbricmmcfge FABRIC forbric.mmcfg.a.CanaryEScreen
opens standin forbricmmcfgc FABRIC forbric.mmcfg.c.CanaryCScreen
opens standin forbricmmcfgd FABRIC forbric.mmcfg.d.CanaryDScreen
row standin "a mod carried inside another, naming no parent, is a row of its own" 'MOD:forbricmmcfgc'
row standin "a carried mod naming a parent that is no mod sits under that heading" 'GROUP:forbric-canary-family, >MOD:forbricmmcfgd'
keyboard standin forbricmmuni NEOFORGE shown
opens standin forbricmmuni NEOFORGE forbric.mmcfg.uni.UniNeoScreen
row standin "the game has a row" 'GAME:minecraft'
keyboard standin minecraft GAME shown
opens standin minecraft GAME net.minecraft.client.gui.screens.options.OptionsScreen
keyboard standin forbricmmplain FABRIC hidden
hidden standin forbricmmplain FABRIC
;;
control)
step "2. control: the stand-in switched off — the Fabric canaries lose their Config buttons, the rest do not"
run_client control "-Dforbric.modMenuStandIn=off"
common control
check "control: the switch is off" '\[Forbric/ModConfig\] -Dforbric\.modMenuStandIn=off' "$RESULTS/control.log"
for id in forbricmmcfga forbricmmcfgb forbricmmcfge; do keyboard control "$id" FABRIC hidden; hidden control "$id" FABRIC; done
for id in forbricmmcfgc forbricmmcfgd; do norow control "$id"; done
no_row control "a carried canary has no row without a config screen" 'MOD:forbricmmcfgc'
no_row control "and no heading is made for one" 'GROUP:forbric-canary-family'
opens control forbricmmuni NEOFORGE forbric.mmcfg.uni.UniNeoScreen
opens control minecraft GAME net.minecraft.client.gui.screens.options.OptionsScreen
check_absent "control: no Fabric canary's screen opened" 'through the Config button: forbric\.mmcfg\.[abcde]\.' "$RESULTS/control.log"
;;
modmenu)
if [ -z "$MODMENU" ] || [ ! -f "$MODMENU" ] || [ -z "$FABRIC_API" ] || [ ! -f "$FABRIC_API" ]; then
  echo "[kernel] SKIP 3. Mod Menu: needs a Mod Menu jar (M56_MODMENU) and a fabric-api jar (M56_FABRIC_API), third-party jars this tree does not ship"
  continue
fi
step "3. Mod Menu installed: Mod Menu's own list has the universal canary's Config button, and Forbric's list still works"
run_client modmenu "-Dforbric.clientSmokeModMenuAsk=forbricmmuni,forbricmmcfga" "$MODMENU" "$FABRIC_API"
common modmenu
check "modmenu: Fabric configs come from the installed Mod Menu" '\[Forbric/ModConfig\] Fabric config screens come from installed Mod Menu' "$RESULTS/modmenu.log"
check "modmenu: Mod Menu offers the universal canary's NeoForge screen, under the name its fabric.mod.json gives" \
  'ClientSmoke\] Mod Menu: forbricmmuni hasConfigScreen=true, its list names it "Forbric Universal Config Canary", getConfigScreen builds forbric\.mmcfg\.uni\.UniNeoScreen' \
  "$RESULTS/modmenu.log"
check "modmenu: and A's own, from A's entrypoint" \
  'ClientSmoke\] Mod Menu: forbricmmcfga hasConfigScreen=true, its list names it "Forbric Config Canary A", getConfigScreen builds forbric\.mmcfg\.a\.CanaryAScreen' \
  "$RESULTS/modmenu.log"
opens modmenu forbricmmcfga FABRIC forbric.mmcfg.a.CanaryAScreen
opens modmenu forbricmmcfgc FABRIC forbric.mmcfg.c.CanaryCScreen
opens modmenu forbricmmuni NEOFORGE forbric.mmcfg.uni.UniNeoScreen
opens modmenu minecraft GAME net.minecraft.client.gui.screens.options.OptionsScreen
step "3b. Mod Menu, the kernel's offer switched off — the universal canary loses its Config button in Mod Menu (negative control)"
run_client modmenuoff "-Dforbric.clientSmokeModMenuAsk=forbricmmuni -Dforbric.modMenuForeignScreens=off" "$MODMENU" "$FABRIC_API"
check "modmenuoff: Mod Menu has no config screen for the universal canary" \
  'ClientSmoke\] Mod Menu: forbricmmuni hasConfigScreen=false' "$RESULTS/modmenuoff.log"
opens modmenuoff forbricmmuni NEOFORGE forbric.mmcfg.uni.UniNeoScreen
;;
*) echo "[kernel] FAIL unknown phase '$phase' in M56_PHASES"; FAIL=1 ;;
esac; done

step "M56 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M56 CONFIG SCREENS GATE GREEN — Fabric mods' config screens open from Forbric's own Mods screen without Mod Menu, carried mods and the game included"
else
  echo "[kernel] ❌ M56 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
