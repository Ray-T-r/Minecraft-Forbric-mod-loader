#!/usr/bin/env bash
# M13 gate — a Forbric client judged by a real anti-cheat on a real Paper server.
#
# WHY THIS EXISTS. gate-m12 proved a Forbric client can join a Forbric server; it says nothing about what that
# client looks like to a server that is WATCHING it. Anti-cheats ban on behaviour — the movement and combat
# packets a client emits — and the merged base those packets come out of is three patch sets byte-merged into one
# jar (1045 resolved conflicts, plus the kernel's post-merge fixups). Any place that merge nudged movement physics
# off vanilla would read to a prediction-based anti-cheat as "this player moves wrong", and nothing measured that.
#
# This gate does. It boots Paper 26.2 with GrimAC (the strictest open-source prediction anti-cheat, sensitised so
# that EVERY violation prints — see the staging step), connects a Forbric client, and drives it through the
# movement drill in KernelClientSmoke: walk, sprint, sprint-jump, turn, strafe, backpedal, sneak, mine, place
# blocks, swim. Every input goes through the same path a keyboard would, so the packets are the real game's.
#
# POSITIVE CONTROL. The drill ends with one impossible move (six blocks in a tick). A run with zero flags proves
# nothing on its own — the anti-cheat may not be watching — so this gate demands SILENCE before that move and
# at least one flag AFTER it. Both halves are asserted; neither is meaningful alone. The boundary is a marker
# this gate writes INTO the server's own log (`say FORBRIC-CONTROL`) when the drill announces the move, three
# seconds before it makes it: on the first run the flag was filed on the wrong side because Grim printed it
# ~50 ms after the move and a polled line count was a second late. The offset it printed — 5.999 blocks — was
# the control itself, and the whole legitimate drill was silent.
#
# Also the first gate to put a Forbric client on a NON-Forbric server (Paper speaks the vanilla protocol): the
# NeoForge and Fabric halves of the client must both behave as they do against vanilla, i.e. stay quiet.
#
# Port 25601, not the default — a developer or another agent session may have a server on 25565.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

PORT="${M13_PORT:-25601}"
SRV="$KERNEL/run/ac-server"
CLI="$KERNEL/run/ac-client"
SLOG="$BUILD/gate-m13-server.log"
CLOG="$BUILD/gate-m13-client.log"
mkdir -p "$BUILD"

DL="$RUN_OLD/downloads"
PAPER="$DL/paper-26.2/paper-26.2-121.jar"
GRIM="$DL/paper-26.2/grimac-bukkit-2.3.74-61caa53.jar"
PLAYER="ForbricKernel"
# Same client mod set as gate-m12: the question is what the CLIENT looks like on the wire, with real mods loaded.
MODS=(
  "$DL/fabric-26.2/fabric-api-0.154.0+26.2.jar"
  "$DL/forge-26.2/mcw-bridges-3.1.2-mc26.2forge.jar"
  "$DL/forge-26.2/collective-26.2.0-8.39.jar"
)
if [ -n "${M13_MODS:-}" ]; then read -r -a MODS <<< "$M13_MODS"; fi
for f in "$PAPER" "$GRIM" "${MODS[@]}"; do
  [ -f "$f" ] || { echo "[kernel] SKIP-FATAL: missing $f" >&2; exit 3; }
done

kernel_jar

step "stage a Paper + GrimAC server (flat world, survival, port $PORT) and a client"
reap_stale_server "$SRV"
rm -rf "$SRV" "$CLI"
mkdir -p "$SRV/plugins/GrimAC" "$CLI/mods" "$CLI/quickPlay"
cp "$GRIM" "$SRV/plugins/"
# Sensitised Grim, derived from the defaults inside its own jar so nothing of Grim's is committed here: every
# violation alerts to the console at 1 VL (the stock Simulation threshold is 100), verbose on, no update check.
# Anything less and a genuine flag could sit below a threshold and read as silence.
unzip -p "$GRIM" config/en.yml \
  | sed -e '/^verbose:/,/^[a-z]/ s/print-to-console: false/print-to-console: true/' \
        -e 's/^check-for-updates: true/check-for-updates: false/' > "$SRV/plugins/GrimAC/config.yml"
unzip -p "$GRIM" punishments/en.yml | sed -E 's/"[0-9]+:[0-9]+ \[alert\]"/"1:1 [alert]"/g' > "$SRV/plugins/GrimAC/punishments.yml"
grep -q 'print-to-console: true' "$SRV/plugins/GrimAC/config.yml" && grep -q '"1:1 \[alert\]"' "$SRV/plugins/GrimAC/punishments.yml" \
  || { echo "[kernel] SKIP-FATAL: Grim's bundled config no longer has the keys this gate sensitises" >&2; exit 3; }
printf 'eula=true\n' > "$SRV/eula.txt"
printf 'server-port=%s\nonline-mode=false\nlevel-type=minecraft\\:flat\nlevel-name=AcWorld\ngamemode=survival\ndifficulty=peaceful\nspawn-monsters=false\nspawn-protection=0\nview-distance=6\nsimulation-distance=6\nenforce-secure-profile=false\nmax-players=5\nmotd=forbric-ac-gate\n' "$PORT" > "$SRV/server.properties"
for m in "${MODS[@]}"; do cp "$m" "$CLI/mods/"; done
cp "$KERNEL/run/client-merged-pack/options.txt" "$CLI/options.txt" 2>/dev/null || printf 'version:4903\n' > "$CLI/options.txt"
echo "[kernel] client mods: $(ls -1 "$CLI/mods" | paste -sd' ' -)"

step "boot Paper and hold it open"
FIFO="$SRV/.stdin"; rm -f "$FIFO"; mkfifo "$FIFO"
( cd "$SRV" && exec java -Xmx2G -jar "$PAPER" --nogui ) < "$FIFO" > "$SLOG" 2>&1 &
SRVPID=$!
record_server_pid "$SRV" "$SRVPID"
exec 9>"$FIFO"
echo "[kernel] server pid=$SRVPID"
READY=0
for i in $(seq 1 240); do
  kill -0 "$SRVPID" 2>/dev/null || { echo "[kernel] server died after ~${i}s"; break; }
  grep -qE 'Done \(' "$SLOG" 2>/dev/null && { READY=1; echo "[kernel] server ready after ~${i}s"; break; }
  sleep 1
done
if [ "$READY" -ne 1 ]; then
  echo "[kernel] FAIL Paper never reached Done"; FAIL=1
  exec 9>&-; kill_tree "$SRVPID"; rm -f "$FIFO" "$(_pidfile "$SRV")"
  step "M13 result"; echo "[kernel] ❌ M13 GATE RED — see $SLOG"; exit 1
fi

step "lay out the arena"
# A flat world puts the surface at y=-60. Spawn near the origin facing +Z, and dig a three-deep pool BEHIND the
# spawn (negative z) — deep enough to submerge, so the swim phase is a real swim and not a wade, and somewhere the
# drill's own walking (all of it at z >= spawn, minus a short backpedal from far ahead) cannot wander into. The
# first placement was ahead and to the side, and a player spawned a few blocks off-centre walked straight into it:
# every "land" phase of that run happened underwater and Grim's silence was about swimming. The gate teleports the
# player in when the drill asks. Force-loaded first: Paper does not keep the spawn chunks loaded, and a fill into
# an unloaded chunk answers "That position is not loaded" and digs nothing — which the position evidence caught
# as a swim phase on dry ground.
for cmd in 'forceload add -16 -32 16 16' 'setworldspawn 0 -60 0' 'fill -3 -63 -20 3 -61 -14 minecraft:water' 'time set day' 'weather clear'; do
  echo "$cmd" >&9; sleep 0.5
done
sleep 2

step "connect the client and run the movement drill"
# Disconnect at world tick 1100: ready at 60, the drill runs ~920 ticks after that (~48 s), then it leaves.
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=127.0.0.1:$PORT -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=1100 -Dforbric.clientSmokeDrill=true -Dforbric.clientSmokeDrillControl=true ${M13_EXTRA_JVM:-}" \
RUNDIR="$CLI" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$CLI/quickPlay/log.json" --quickPlayMultiplayer "127.0.0.1:$PORT" > "$CLOG" 2>&1 &
CLIENT_PID=$!
echo "[kernel] client pid=$CLIENT_PID (killed by pid only — another client may be running)"

CGAME="$CLI/logs/latest.log"
GAVE=0; TELEPORTED=0; MARKED=0
for i in $(seq 1 300); do
  kill -0 "$CLIENT_PID" 2>/dev/null || { echo "[kernel] client exited on its own after ~${i}s"; break; }
  # React to the drill's markers: something to place, a pool to swim in, and the line before the control.
  if [ "$GAVE" -eq 0 ] && grep -q 'ClientSmoke\] client-ready' "$CGAME" 2>/dev/null; then
    echo "give $PLAYER minecraft:cobblestone 64" >&9; GAVE=1; echo "[kernel] gave cobblestone at ~${i}s"
  fi
  if [ "$TELEPORTED" -eq 0 ] && grep -q 'drill phase swim-wait' "$CGAME" 2>/dev/null; then
    echo "tp $PLAYER 0 -60 -17" >&9; TELEPORTED=1; echo "[kernel] teleported into the pool at ~${i}s"
  fi
  if [ "$MARKED" -eq 0 ] && grep -q 'drill phase control-wait' "$CGAME" 2>/dev/null; then
    echo "say FORBRIC-CONTROL" >&9; MARKED=1; echo "[kernel] wrote the control marker into the server log at ~${i}s"
  fi
  grep -qE 'ClientSmoke\] clean disconnect observed|Game crashed|Mod Loading has failed|Network Protocol Error|Failed to connect|Client disconnected with reason' \
       "$CGAME" 2>/dev/null && { echo "[kernel] outcome reached after ~${i}s"; break; }
  sleep 1
done

step "let vanilla's post-main watchdog speak before killing anything"
for i in $(seq 1 25); do kill -0 "$CLIENT_PID" 2>/dev/null || break; sleep 1; done
for pid in $(pgrep -P "$CLIENT_PID" 2>/dev/null) "$CLIENT_PID"; do kill "$pid" 2>/dev/null; done
sleep 2
for pid in $(pgrep -P "$CLIENT_PID" 2>/dev/null) "$CLIENT_PID"; do kill -9 "$pid" 2>/dev/null; done
cat "$CGAME" >> "$CLOG" 2>/dev/null || true

step "stop the server"
sleep 2   # let Grim print anything still queued for the control move
echo "stop" >&9
exec 9>&-
await_server "$SRVPID" "$SLOG" 90
rm -f "$FIFO" "$(_pidfile "$SRV")"

# Grim's console alert reads "<prefix> <player> failed <Check> (x<vl>) <verbose>"; verbose lines use the same verb.
FLAG_RE="$PLAYER failed"
CONTROL_LINE=$(grep -an 'FORBRIC-CONTROL' "$SLOG" | head -1 | cut -d: -f1)
[ -n "$CONTROL_LINE" ] || CONTROL_LINE=$(wc -l < "$SLOG" | tr -d ' ')
head -n "$CONTROL_LINE" "$SLOG" > "$BUILD/gate-m13-before-control.log"
tail -n +"$((CONTROL_LINE + 1))" "$SLOG" > "$BUILD/gate-m13-after-control.log"
echo "[kernel] Grim flags BEFORE the control ($(grep -ac "$FLAG_RE" "$BUILD/gate-m13-before-control.log")):"
grep -a "$FLAG_RE" "$BUILD/gate-m13-before-control.log" | sed 's/^/[kernel]   /' | cut -c1-200 | head -20
echo "[kernel] Grim flags AFTER the control ($(grep -ac "$FLAG_RE" "$BUILD/gate-m13-after-control.log")):"
grep -a "$FLAG_RE" "$BUILD/gate-m13-after-control.log" | sed 's/^/[kernel]   /' | cut -c1-200 | head -10

step "the anti-cheat was watching a real, modded Forbric client (must PASS)"
check "Grim enabled"                  "Enabling GrimAC"                                   "$SLOG"
check "client dialled Paper"          "Connecting to 127.0.0.1"                           "$CLOG"
check "Paper accepted the join"       "$PLAYER joined the game"                           "$SLOG"
check "client entered the world"      "ClientSmoke\] joined world via quick-play"         "$CLOG"
check_absent "no protocol error against a vanilla-protocol server" "Network Protocol Error|Failed to decode packet|Unknown custom packet|Client disconnected with reason: Internal" "$CLOG"

step "the drill ran end to end (must PASS)"
check "cobblestone in hand"           "Gave 64"                                           "$SLOG"
check "drill reached the swim phase"  "drill phase swim-wait"                             "$CLOG"
check "teleported into the pool"      "Teleported $PLAYER"                                "$SLOG"
check "drill completed"               "drill complete after"                              "$CLOG"
check "the control move was made"     "drill control: moving the player"                  "$CLOG"
check "the control marker is in the server log" "FORBRIC-CONTROL"                          "$SLOG"
# The drill's own evidence that it moved: silence from Grim means nothing if the player stood still. The player
# faces +Z, so sixty ticks of walking must have carried it several blocks along z between the "walk" and "sprint"
# phase lines; the swim phase must be wet; sprinting must have actually engaged.
zof() { grep -a "drill phase $1 at" "$CGAME" | sed -E 's/.*pos=\([^ ]+ [^ ]+ ([-0-9.]+)\).*/\1/' | head -1; }
Z0=$(zof walk); Z1=$(zof sprint)
echo "[kernel] z at the start of walk: ${Z0:-?}, at the start of sprint: ${Z1:-?}"
if awk -v a="${Z0:-0}" -v b="${Z1:-0}" 'BEGIN{exit !(b - a > 5)}'; then echo "[kernel] PASS the walk phase covered ground ($Z0 -> $Z1)"; else echo "[kernel] FAIL the walk phase covered ground (${Z0:-?} -> ${Z1:-?})"; FAIL=1; fi
check "the pool was dug"              "Successfully filled"                               "$SLOG"
check "the land phases were on land"  "drill phase (sprint|sprint-jump|turn|strafe|backpedal|sneak-walk|mine|place) at .*inWater=false" "$CGAME" 8
# Holding the button down is what puts a block into the level's break-progress map, and that map is what the render
# frame turns into a breaking overlay — the one path on the merged base where the game asked MinecraftForge for a
# model-data manager the merge had left it without. Zero here means the frame that used to crash was never drawn.
check "the drill actually broke ground" "ClientSmoke\] mining: [1-9][0-9]* block\(s\) showing break progress" "$CGAME"
check "the swim phase was in water"   "drill phase swim at .*inWater=true"                "$CGAME"
check "sprinting actually engaged"    "drill phase sprint-jump at .*sprinting=true"       "$CGAME"

step "Grim's verdict: silent through the drill, loud at the control (must PASS)"
assert_eq "no Grim flag before the control" "0" "$(grep -ac "$FLAG_RE" "$BUILD/gate-m13-before-control.log")"
check "Grim flagged the control move" "$FLAG_RE"                                          "$BUILD/gate-m13-after-control.log"
check_absent "not kicked before the control" "$PLAYER lost connection"                   "$BUILD/gate-m13-before-control.log"

step "neither side broke (must be ABSENT)"
check "left cleanly"                  "ClientSmoke\] clean disconnect observed"          "$CLOG"
check "the client stopped its config file-watchers at close" "Forbric/Shutdown\\] stopped [0-9]+ config file-watcher" "$CLOG"
check_absent "NeoForge loaded its default server configs once" "Overwriting non-null config" "$CLOG"
check_absent "no client crash"        "Preparing crash report"                            "$CLOG"
check_absent "no server crash"        "Preparing crash report|Encountered an unexpected exception" "$SLOG"
check_absent "no thread leaked past main" "Client shutdown from post-main"                "$CLOG"

step "M13 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M13 GATE GREEN — GrimAC watched a Forbric client walk, sprint, jump, swim, fight and build, and had nothing to say until told to"
else
  echo "[kernel] ❌ M13 GATE RED — server $SLOG / client $CLOG"
fi
exit "$FAIL"
