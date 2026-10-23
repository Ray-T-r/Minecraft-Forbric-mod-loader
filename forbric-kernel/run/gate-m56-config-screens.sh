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
# WHAT IT JUDGES. The kernel's client smoke walks -Dforbric.clientSmokeConfigScreens through the real Mods screen, and
# every input goes through the game's own handlers (KeyboardHandler.keyPress, MouseHandler.onButton with the cursor on
# what is drawn). For each mod it finds the row the open screen's list widget holds for that id; selects it with the
# keyboard — a click on the nearest row whose answer is the opposite one, then the arrow key toward this row — and goes
# back the same way; selects it with a click after that row; reads whether the Config button is visible after each;
# clicks the button and logs the class of the screen in front; then double-clicks the row on a fresh Mods screen. The
# assertions are on those classes, which the canaries themselves define (forbric.mmcfg.*), on the button's own
# `visible`, on the rows the list widget holds in the order it draws them (and a screenshot of that screen, kept in the
# results), on how many rows' Config buttons opened a screen, and on the code source Mod Menu's classes were defined
# with in the game loader. No line the kernel writes about itself is asserted: every check reads what the probe
# observed and logged under [Forbric/ClientSmoke]. The canaries are built from canary/modmenu-config by
# run/build-modmenu-config-canary.sh; none is a third-party jar.
#
#   1. stand-in — no Mod Menu (its main class does not resolve; its API is defined with no code source, no jar the game
#      loader reads has it, and the kernel carries stand-in bytes for it): canaries A and B open their own screens through their own Mod Menu entrypoints; E, which declares
#      none, opens the screen A provides for it; C and D, which A carries inside itself, have rows of their own (C at the
#      top, D under the heading "Forbric Canary Family" it names for itself) and open their screens; the universal
#      canary opens NeoForge's screen; the game's row opens OptionsScreen; the plain canary's Config button stays hidden
#      and a double click on it opens nothing; every other walked row (A to E, the universal canary, the game) also
#      opens its screen on a double click; exactly 7 of the 8 walked rows open a screen through the Config button. The
#      keyboard checks for E and the universal canary start from the plain canary: a mod WITHOUT a config to one WITH
#      one, and back. The NeoForge platform row (NeoForge's own settings, listed on every instance) is the owner's open
#      decision (ModListRows.LOADER_ROWS) and is asserted neither way.
#   2. control — phase 1 with -Dforbric.modMenuStandIn=off. Without the stand-in Mod Menu's API does not resolve and a
#      Fabric mod's Mod Menu entrypoint cannot be read: A, B and E show no Config button and the carried canaries have
#      no row. The game and the NeoForge half are not the stand-in's, so they still open: exactly 2 of the 8.
#      RED (verified): phase 1 run with M56_EXTRA_JVM=-Dforbric.modMenuStandIn=off fails exactly 29 checks of phase 1
#      and no other: the stand-in API line; the count of rows that opened (2, not 7); for each of A, B, C, D and E its
#      two arrow-key checks, its click, its Config button and its double click; and the rows of C and D. The universal
#      canary, the game and the plain canary stay green.
#   3. Mod Menu — Mod Menu installed (M56_MODMENU, default run/modmenu/modmenu-*.jar, e.g. modmenu-20.0.3.jar) with
#      fabric-api (M56_FABRIC_API, default run/modmenu/fabric-api-*.jar, else the client-merged-pack's): both of Mod
#      Menu's classes are defined from its jar. Mod Menu's API -- not its list screen, which this gate never opens --
#      is asked about the universal canary: ModMenu.hasConfigScreen answers true (what its list's Config button reads),
#      its ModMenu.MODS entry's getName/getDescription answer what the canary's fabric.mod.json gives (the canary's
#      neoforge.mods.toml gives another name and description, so reading NeoForge's would fail this), the icon path
#      Fabric's container names is found in that container (the probe's own lookup, the one Mod Menu's icon code does,
#      not Mod Menu's answer), and ModMenu.getConfigScreen builds NeoForge's screen. Forbric's list opens the same 7 of
#      8 as in phase 1; for A and the game, Mod Menu's own factory is what answered, exactly once (a counting stand-in
#      in Mod Menu's own factory map, around the press), and for the universal canary it is not (the kernel asks
#      NeoForge). Then 3b, the same with -Dforbric.modMenuForeignScreens=off, which must take the universal canary's
#      Config button out of Mod Menu again, with the same in-world checks as every other run.
#      Phase 3 is required, as m54 requires its third-party jars: when it is selected (it is by default) and either jar
#      is missing the gate stops with SKIP-FATAL and exit 3, which gates-all reports RED — never GREEN with phase 3
#      silently left out. tools/nightly links run/modmenu from the main checkout. M56_PHASES="standin control" runs a
#      subset on purpose: the result line names the phases that ran, and a subset with no failure exits 3, not 0, so no
#      runner can count it GREEN.
#
# RED (verified by mutation): KernelModListScreen's list told the screen nothing from setSelected and the row's click
# handler told it instead (the code before this gate) — phase 1 fails exactly its 8 "selected with the arrow keys"
# checks (D, A, B, C, E, the universal canary, the game, the plain canary) and nothing else. The "back with the arrow
# keys" checks stay green under that mutation, rightly: the button is still the clicked row's, which is that row's.
# (Measured before the count and B/C/E double-click checks were added; neither depends on the arrow keys.)
#
# Not covered here: Mod Menu's own list screen (ModsScreen) and a real press of its Config button -- phase 3 asks its
# API; a NeoForge or MinecraftForge mod other than the universal canary in Mod Menu; the kernel's log line for a provided
# factory whose id nothing on the instance has; the plugin's 2 s refresh; Tab (only the arrow keys and the mouse are
# driven); a top-level Fabric jar that lost its id to another family's jar (FabricAliasDisplayTest covers that one);
# the NeoForge platform row.
#
# Needs a world to join (the smoke runs in-world): M56_WORLD, default run/client-merged-pack/saves/ForbricTest.
# GATE-PARALLEL: rundirs=client-config-screens-m56 mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="${M56_RUNDIR:-$KERNEL/run/client-config-screens-m56}"
WORLD=ForbricTest
FIXTURE="${M56_WORLD:-$KERNEL/run/client-merged-pack/saves/$WORLD}"
MODMENU="${M56_MODMENU:-$(ls "$KERNEL"/run/modmenu/modmenu-*.jar 2>/dev/null | head -1)}"
FABRIC_API="${M56_FABRIC_API:-$(ls "$KERNEL"/run/modmenu/fabric-api-*.jar "$KERNEL"/run/client-merged-pack/mods/fabric-api-*.jar 2>/dev/null | head -1)}"
PHASES="${M56_PHASES:-standin control modmenu}"
RESULTS="$BUILD/verification/m56-config-screens"
CANARY="$KERNEL/run/canary/modmenu-config"
AT=100
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

[ -d "$FIXTURE" ] || { echo "[kernel] SKIP-FATAL: no world to copy at $FIXTURE (set M56_WORLD)" >&2; exit 3; }
case " $PHASES " in *" modmenu "*)
  for need in "$MODMENU" "$FABRIC_API"; do
    [ -n "$need" ] && [ -f "$need" ] || { echo "[kernel] SKIP-FATAL: M56 phase 3 needs a Mod Menu jar for 26.2 and a" \
      "fabric-api jar (M56_MODMENU='$MODMENU', M56_FABRIC_API='$FABRIC_API'; default run/modmenu/). Third-party jars this" \
      "tree does not ship; M56_PHASES='standin control' runs without phase 3 on purpose" >&2; exit 3; }
  done ;;
esac
kernel_jar
bash "$KERNEL/run/build-modmenu-config-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# The order matters: the plain canary comes first so a button left visible from a previous row would show.
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
-Dforbric.clientSmokeDisconnectTicks=$((AT + 260)) $extra" \
  RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
    --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$RESULTS/$phase.log" 2>&1 &
  pid=$!
  record_server_pid "$RUNDIR" "$pid"
  echo "[kernel] $phase: client pid=$pid (this gate never kills by name — another client may be running)"
  await_server "$pid" "$RESULTS/$phase.log" 360
  rm -f "$RUNDIR/.forbric-gate.pid"
  cp -R "$RUNDIR/screenshots" "$RESULTS/$phase-screenshots" 2>/dev/null || true
}

ESC='s/[.$]/\\&/g'
re() { printf '%s' "$1" | sed "$ESC"; }
opposite() { [ "$1" = shown ] && echo hidden || echo shown; }
# opens <phase> <id> <kind> <screen class> — clicking Config on id's row put that class in front of the player.
opens() {
  check "$1: $2 has a Config button, and it opens $(basename "${4//.//}")" \
    "ClientSmoke\] config screen of $2 \($3\) through the Config button: $(re "$4")\$" "$RESULTS/$1.log"
}
# doubled <phase> <id> <kind> <screen class> — a double click on id's row (through MouseHandler) opened that class.
doubled() {
  check "$1: a double click on $2's row opens $(basename "${4//.//}")" \
    "ClientSmoke\] config screen of $2 \($3\) through a double click on its row: $(re "$4")\$" "$RESULTS/$1.log"
}
# keyboard <phase> <id> <kind> <shown|hidden> [from-id] — arrow keys from the nearest row with the opposite answer onto
# id's row, and back: the button shows id's answer there and the other row's answer after coming back.
keyboard() {
  local from="${5:-[A-Za-z0-9_.-]+}" other; other=$(opposite "$4")
  check "$1: selected with the arrow keys from ${5:-a row whose button is $other}, $2's Config button is $4" \
    "ClientSmoke\] config screen of $2 \($3\): selected with the keyboard, [0-9]+ x (DOWN|UP) from $from \(where the Config button was $other\), the Config button is $4\$" \
    "$RESULTS/$1.log"
  check "$1: and back with the arrow keys, the Config button is $other again" \
    "ClientSmoke\] config screen of $2 \($3\): back with the keyboard, [0-9]+ x (DOWN|UP) to $from, the Config button is $other\$" \
    "$RESULTS/$1.log"
}
clicked() {
  check "$1: selected with a click, $2's Config button is $4" \
    "ClientSmoke\] config screen of $2 \($3\): selected with a click after [A-Za-z0-9_.-]+, the Config button is $4\$" \
    "$RESULTS/$1.log"
}
hidden() {
  check "$1: $2's Config button is hidden" "ClientSmoke\] config screen of $2 \($3\): the Config button is hidden" "$RESULTS/$1.log"
  check "$1: and a double click on its row opens nothing" \
    "ClientSmoke\] config screen of $2 \($3\): a double click on its row left the Mods screen in front" "$RESULTS/$1.log"
}
norow() {
  check "$1: $2 has no row" "ClientSmoke\] config screen of $2: the Mods screen has no row for it" "$RESULTS/$1.log"
}
# row <phase> <what> <pattern of one or more consecutive items in the rows the screen's list widget holds>
ROWS='ClientSmoke\] config screens: the Mods screen \(net\.forbric\.kernel\.runtime\.KernelModListScreen\) holds [0-9]+ row\(s\): \['
row() {
  check "$1: $2" "$ROWS(.*, )?$3(, .*)?\]" "$RESULTS/$1.log"
}
no_row() {
  check_absent "$1: $2" "$ROWS(.*, )?$3(, .*)?\]" "$RESULTS/$1.log"
}
# modmenu_classes <phase> <label> <pattern: what the API does> <pattern: what the main class does>
modmenu_classes() {
  check "$1: $2" \
    "ClientSmoke\] Mod Menu's classes: its API com\.terraformersmc\.modmenu\.api\.ModMenuApi $3; its main class com\.terraformersmc\.modmenu\.ModMenu $4;" \
    "$RESULTS/$1.log"
}
# common <phase> <how many of the 8 walked rows open a screen through the Config button>
common() {
  check "$1: the client entered the world" 'ClientSmoke\] client-ready after' "$RESULTS/$1.log"
  check "$1: the walk ran to its end" 'ClientSmoke\] config screens: [0-9]+ of 8 opened through the Config button' "$RESULTS/$1.log"
  check "$1: exactly $2 of the 8 rows opened a screen through the Config button" \
    "ClientSmoke\] config screens: $2 of 8 opened through the Config button" "$RESULTS/$1.log"
  check "$1: the Mods screen's rows were read from its list widget" "$ROWS" "$RESULTS/$1.log"
  if [ -s "$RESULTS/$1-screenshots/forbric-config-mods-screen.png" ]; then
    echo "[kernel] PASS $1: the Mods screen was photographed ($RESULTS/$1-screenshots/forbric-config-mods-screen.png)"
  else
    echo "[kernel] FAIL $1: no screenshot of the Mods screen in $RESULTS/$1-screenshots"; FAIL=1
  fi
  check_absent "$1: no row was selected with nothing to contrast it with" 'selected with the keyboard after nothing else' "$RESULTS/$1.log"
  check_absent "$1: every arrow key landed where it was sent" 'ClientSmoke\] config screen of .* left .* selected$' "$RESULTS/$1.log"
  check_absent "$1: no shown Config button left the Mods screen in front when pressed" 'ClientSmoke\] config screen of .*: pressing Config left' "$RESULTS/$1.log"
  check_absent "$1: the walk did not stop" 'ClientSmoke\] config screens: the walk stopped' "$RESULTS/$1.log"
  check_absent "$1: no crash report" 'Preparing crash report' "$RESULTS/$1.log"
}

RAN=""
for phase in $PHASES; do case "$phase" in
standin)
step "1. stand-in: no Mod Menu — every config screen opens through Forbric's own Config button"
run_client standin "${M56_EXTRA_JVM:-}"
RAN="$RAN standin"
common standin 7
modmenu_classes standin "Mod Menu's API has no code source, no jar has it, the kernel carries stand-in bytes; its main class does not resolve" \
  "is defined from no jar \(no code source\); no jar the game loader reads has it; the kernel's stand-in bytes for it are in [^;]+" "does not resolve"
keyboard standin forbricmmcfgd FABRIC shown
for id in forbricmmcfga forbricmmcfgb forbricmmcfgc; do keyboard standin "$id" FABRIC shown; done
keyboard standin forbricmmcfge FABRIC shown forbricmmplain
for id in forbricmmcfga forbricmmcfgb forbricmmcfgc forbricmmcfgd forbricmmcfge; do clicked standin "$id" FABRIC shown; done
opens standin forbricmmcfga FABRIC forbric.mmcfg.a.CanaryAScreen
opens standin forbricmmcfgb FABRIC forbric.mmcfg.b.CanaryBScreen
opens standin forbricmmcfge FABRIC forbric.mmcfg.a.CanaryEScreen
opens standin forbricmmcfgc FABRIC forbric.mmcfg.c.CanaryCScreen
opens standin forbricmmcfgd FABRIC forbric.mmcfg.d.CanaryDScreen
doubled standin forbricmmcfga FABRIC forbric.mmcfg.a.CanaryAScreen
doubled standin forbricmmcfgb FABRIC forbric.mmcfg.b.CanaryBScreen
doubled standin forbricmmcfge FABRIC forbric.mmcfg.a.CanaryEScreen
doubled standin forbricmmcfgc FABRIC forbric.mmcfg.c.CanaryCScreen
doubled standin forbricmmcfgd FABRIC forbric.mmcfg.d.CanaryDScreen
row standin "a mod carried inside another, naming no parent, is a row of its own at the top" 'MOD:forbricmmcfgc'
row standin "a carried mod naming a parent that is no mod is drawn under that heading" 'GROUP:forbric-canary-family, >MOD:forbricmmcfgd'
keyboard standin forbricmmuni NEOFORGE shown forbricmmplain
clicked standin forbricmmuni NEOFORGE shown
opens standin forbricmmuni NEOFORGE forbric.mmcfg.uni.UniNeoScreen
doubled standin forbricmmuni NEOFORGE forbric.mmcfg.uni.UniNeoScreen
row standin "the game has a row" 'GAME:minecraft'
keyboard standin minecraft GAME shown
clicked standin minecraft GAME shown
opens standin minecraft GAME net.minecraft.client.gui.screens.options.OptionsScreen
doubled standin minecraft GAME net.minecraft.client.gui.screens.options.OptionsScreen
keyboard standin forbricmmplain FABRIC hidden
clicked standin forbricmmplain FABRIC hidden
hidden standin forbricmmplain FABRIC
;;
control)
step "2. control: the stand-in switched off — the Fabric canaries lose their Config buttons, the rest do not"
run_client control "-Dforbric.modMenuStandIn=off"
RAN="$RAN control"
common control 2
modmenu_classes control "neither Mod Menu's API nor its main class resolves" "does not resolve" "does not resolve"
for id in forbricmmcfga forbricmmcfgb forbricmmcfge; do keyboard control "$id" FABRIC hidden; clicked control "$id" FABRIC hidden; hidden control "$id" FABRIC; done
for id in forbricmmcfgc forbricmmcfgd; do norow control "$id"; done
no_row control "a carried canary has no row without a config screen" 'MOD:forbricmmcfgc'
no_row control "and no heading is made for one" 'GROUP:forbric-canary-family'
opens control forbricmmuni NEOFORGE forbric.mmcfg.uni.UniNeoScreen
opens control minecraft GAME net.minecraft.client.gui.screens.options.OptionsScreen
check_absent "control: no Fabric canary's screen opened" 'through (the Config button|a double click on its row): forbric\.mmcfg\.[abcde]\.' "$RESULTS/control.log"
;;
modmenu)
step "3. Mod Menu installed: Mod Menu's API offers the universal canary's NeoForge screen, and Forbric's list still works"
MMJAR="$(re "$(basename "$MODMENU")")"
run_client modmenu "-Dforbric.clientSmokeModMenuAsk=forbricmmuni,forbricmmcfga" "$MODMENU" "$FABRIC_API"
RAN="$RAN modmenu"
common modmenu 7
modmenu_classes modmenu "Mod Menu's API and main class are both defined from $(basename "$MODMENU")" "is defined from $MMJAR" "is defined from $MMJAR"
check "modmenu: Mod Menu's API has the universal canary's NeoForge screen and its fabric.mod.json name and description; the icon it names is in its container" \
  'ClientSmoke\] Mod Menu: forbricmmuni hasConfigScreen=true, its list names it "Forbric Universal Config Canary", describes it "gate-m56: one jar for Fabric and NeoForge; NeoForge wins it, and its config screen is NeoForge.s\.", icon assets/forbricmmuni/icon\.png \(found in its container=true\), getConfigScreen builds forbric\.mmcfg\.uni\.UniNeoScreen$' \
  "$RESULTS/modmenu.log"
check "modmenu: and A's own, from A's entrypoint, through Mod Menu's API" \
  'ClientSmoke\] Mod Menu: forbricmmcfga hasConfigScreen=true, its list names it "Forbric Config Canary A", .* getConfigScreen builds forbric\.mmcfg\.a\.CanaryAScreen$' \
  "$RESULTS/modmenu.log"
opens modmenu forbricmmcfga FABRIC forbric.mmcfg.a.CanaryAScreen
opens modmenu forbricmmcfgc FABRIC forbric.mmcfg.c.CanaryCScreen
opens modmenu forbricmmuni NEOFORGE forbric.mmcfg.uni.UniNeoScreen
opens modmenu minecraft GAME net.minecraft.client.gui.screens.options.OptionsScreen
check "modmenu: the game's Config button went once through the factory Mod Menu itself provides for minecraft" \
  'ClientSmoke\] config screen of minecraft \(GAME\): the factory Mod Menu holds for it \(com\.terraformersmc\.modmenu\.[^)]*\) answered 1 time\(s\)$' \
  "$RESULTS/modmenu.log"
check "modmenu: A's went once through the factory Mod Menu holds for it, from A's entrypoint" \
  'ClientSmoke\] config screen of forbricmmcfga \(FABRIC\): the factory Mod Menu holds for it \(forbric\.mmcfg\.a\.[^)]*\) answered 1 time\(s\)$' \
  "$RESULTS/modmenu.log"
check "modmenu: the universal canary's did not (Mod Menu holds the kernel plugin's factory for it; Forbric's list asks NeoForge)" \
  'ClientSmoke\] config screen of forbricmmuni \(NEOFORGE\): the factory Mod Menu holds for it \(net\.forbric\.kernel\.runtime\.modmenu\.KernelModMenuPlugin[^)]*\) answered 0 time\(s\)$' \
  "$RESULTS/modmenu.log"
step "3b. Mod Menu, the kernel's offer switched off — the universal canary loses its Config button in Mod Menu (negative control)"
run_client modmenuoff "-Dforbric.clientSmokeModMenuAsk=forbricmmuni -Dforbric.modMenuForeignScreens=off" "$MODMENU" "$FABRIC_API"
common modmenuoff 7
check "modmenuoff: Mod Menu has no config screen for the universal canary" \
  'ClientSmoke\] Mod Menu: forbricmmuni hasConfigScreen=false' "$RESULTS/modmenuoff.log"
opens modmenuoff forbricmmuni NEOFORGE forbric.mmcfg.uni.UniNeoScreen
;;
*) echo "[kernel] FAIL unknown phase '$phase' in M56_PHASES"; FAIL=1 ;;
esac; done

step "M56 result"
RAN="${RAN# }"
# One phase at a time: a single pattern over the three shares the spaces between them and never matches.
WHAT="all three phases"
for need in standin control modmenu; do
  case " $RAN " in *" $need "*) ;; *) WHAT="ONLY the phases [$RAN] (M56_PHASES): not the whole gate" ;; esac
done
if [ "$FAIL" -ne 0 ]; then
  echo "[kernel] ❌ M56 GATE RED ($WHAT) — inspect $RESULTS"
  exit 1
fi
if [ "$WHAT" != "all three phases" ]; then
  # Not GREEN: every runner reads exit 0 as GREEN (gates-parallel.py passes the environment through, so a leftover
  # M56_PHASES would otherwise turn a partial run into a green gate).
  echo "[kernel] M56 PARTIAL ($WHAT) — no check failed, but the gate did not run; exit 3"
  exit 3
fi
echo "[kernel] ✅ M56 CONFIG SCREENS GATE GREEN ($WHAT) — Fabric mods' config screens open from Forbric's own Mods screen without Mod Menu, carried mods and the game included"
exit 0
