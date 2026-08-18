#!/usr/bin/env bash
# M9 gate — the CLIENT half. A tri-ecosystem instance must reach a rendered world and leave it cleanly.
#
# Every client fix in this kernel was verified by launching the game and reading the log by hand, so none of them
# was protected against the next change. This gate is that protection: it drives a real client into a real world
# with -Dforbric.clientSmoke, then asserts the absence of each failure that has actually cost a world load here.
# Those check_absent lines are the point of the gate — they are a list of bugs, each one paid for.
#
# WHY IT KILLS BY PID. The other gates pkill on the launcher class name, which is fine for a server. It is NOT
# fine here: a developer (or a second agent session) may have their own Minecraft client open, and a name-matched
# kill would take it down with no warning and no way to tell whose it was. This gate kills the process tree it
# started and nothing else.
#
# The window between disconnect and exit is deliberate. Vanilla's own watchdog logs "Client shutdown from
# post-main" ~15s after main returns if a non-daemon thread is still alive, which is how a leaked mod thread
# announces itself — so the gate waits for the process to end on its own rather than killing it at the disconnect.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="${M9_RUNDIR:-$KERNEL/run/client-merged-pack}"
WORLD="${M9_WORLD:-ForbricTest}"
LOG="$BUILD/gate-m9-client-boot.log"
mkdir -p "$BUILD"

if [ ! -d "$RUNDIR/saves/$WORLD" ]; then
  echo "[kernel] SKIP-FATAL: no world at $RUNDIR/saves/$WORLD — this gate needs a pre-generated save" >&2
  exit 3
fi
if [ ! -f "$RUNDIR/options.txt" ]; then
  # Without it the accessibility onboarding screen sits in front of --quickPlaySingleplayer and nothing ever loads.
  echo "[kernel] SKIP-FATAL: no $RUNDIR/options.txt — quick-play would be blocked by the onboarding screen" >&2
  exit 3
fi

kernel_jar
mkdir -p "$RUNDIR/quickPlay"
rm -f "$RUNDIR/logs/latest.log"
: > "$LOG"

step "launch the client into $WORLD via quick-play ($(ls -1 "$RUNDIR/mods"/*.jar 2>/dev/null | wc -l | tr -d ' ') mods, no compatibility flags)"
# M9_EXTRA_JVM is how the gate's teeth are demonstrated: switch a fix off and this must go RED. Verified with
# -Dforbric.pruneDuplicateLambdas=off, which brings back StubException and the failed world load.
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140 ${M9_EXTRA_JVM:-}" \
RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$RUNDIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$LOG" 2>&1 &
CLIENT_PID=$!
echo "[kernel] client pid=$CLIENT_PID (this gate never kills by name — another client may be running)"

# The game log is the one with the mod chatter in it; the launcher log carries the JVM's own output.
GAMELOG="$RUNDIR/logs/latest.log"
for i in $(seq 1 400); do
  kill -0 "$CLIENT_PID" 2>/dev/null || { echo "[kernel] client exited on its own after ~${i}s"; break; }
  grep -qE 'ClientSmoke\] clean disconnect observed|Game crashed|Mod Loading has failed|Failed to load level data|Network Protocol Error' \
       "$GAMELOG" 2>/dev/null && { echo "[kernel] outcome reached after ~${i}s"; break; }
  sleep 1
done

step "let vanilla's post-main watchdog speak before killing anything"
for i in $(seq 1 25); do
  kill -0 "$CLIENT_PID" 2>/dev/null || break
  sleep 1
done

# ONLY this gate's own process tree.
for pid in $(pgrep -P "$CLIENT_PID" 2>/dev/null) "$CLIENT_PID"; do kill "$pid" 2>/dev/null; done
sleep 2
for pid in $(pgrep -P "$CLIENT_PID" 2>/dev/null) "$CLIENT_PID"; do kill -9 "$pid" 2>/dev/null; done

# The launcher log already carries the console appender, so this mostly duplicates it — deliberately, because
# the file appender is the only place some detail lands. Duplication cannot change a verdict: check is >=1
# and check_absent is ==0, so the counts printed below may read double and mean nothing by it.
cat "$GAMELOG" >> "$LOG" 2>/dev/null || true

step "the client entered a world and left it cleanly (must PASS)"
check "smoke controller armed"        "ClientSmoke\] armed on Minecraft.tick"      "$LOG"
check "joined a world"                "ClientSmoke\] joined world via quick-play"  "$LOG"
check "survived real simulation"      "ClientSmoke\] client-ready after"           "$LOG"
check "left the world cleanly"        "ClientSmoke\] clean disconnect observed"    "$LOG"
check "server side really ran"        "joined the game"                            "$LOG"
check "datapacks fully loaded"        "Loaded [0-9]+ advancements"                 "$LOG"

step "every failure that has cost a world load here (must be ABSENT)"
# Each of these is a bug that actually happened on this pack; the wording is the log's, not ours to change lightly.
check_absent "JEI found its plugins"        "plugins must not be empty"                        "$LOG"
check_absent "no plugin name unloadable"    "Failed to load: [a-z0-9_]+/"                      "$LOG"
check_absent "no null pack reached the repo" "streamSelfAndChildren.* because .pack. is null"  "$LOG"
check_absent "no pack metadata read failed" "Failed to read pack .* metadata"                  "$LOG"
check_absent "no entity missing attributes" "has no attributes"                                "$LOG"
check_absent "render-layer latch is set"    "Render layers can only be set"                    "$LOG"
check_absent "no skipped-element leak"      "StubException"                                    "$LOG"
check_absent "no duplicate registry key"    "Duplicate key ResourceKey"                        "$LOG"
# NOT a bare "Unknown registry key": a save carries chunk sections referencing content the CURRENT mod set no
# longer has, and vanilla reports those as "Recoverable errors when loading section" — 1214 of them here,
# every one a terralith biome from before that mod was dropped. That is a property of the save. The shape
# that matters is a datapack ELEMENT failing to parse, which is what an unregistered modifier type produced.
check_absent "no datapack element unparseable" "Failed to parse .* from pack"                 "$LOG"
check_absent "join negotiation succeeded"   "Network Protocol Error"                           "$LOG"
check_absent "no registry load failure"     "Failed to load registries due to errors"          "$LOG"
check_absent "no crash report"              "Preparing crash report"                           "$LOG"

step "nothing leaked past main"
# Vanilla logs this ~15s after main returns when a non-daemon thread is still alive — a leaked mod thread.
check_absent "no thread leaked past main"   "Client shutdown from post-main"                   "$LOG"

step "M9 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M9 CLIENT GATE GREEN — tri-ecosystem client entered a world and left it cleanly"
else
  echo "[kernel] ❌ M9 CLIENT GATE RED — see $LOG"
fi
exit "$FAIL"
