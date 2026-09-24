#!/usr/bin/env bash
# M41 — every event the kernel forwards between NeoForge's bus and MinecraftForge's, checked by one rule set.
#
# The other event gates prove one repaired path each. This one asks the buses themselves: with
# -Dforbric.eventChainAudit, both families' dispatch reports entry and exit, and any post the kernel makes on one
# bus inside the other bus's dispatch is a forward (EventChainAudit). For every such pair: each forward exactly once
# per NeoForge listener (no bridge installed twice, no one-to-one forward that sometimes posts twice), a cancel on
# the inner bus carried back to the outer event, and no failure inside the forward.
#
#   1. breadth — the 97-mod client enters a world, drives a container screen with the mouse, and leaves: every
#      tick, level, entity, tracking, lifecycle and screen-mouse bridge must be seen forwarding, and nothing may
#      violate the rules above.
#   2. cancel — a dedicated server with a NeoForge mod whose MinecraftForge listener vetoes one tagged entity
#      joining the level: the entity must stay out, an untagged one must get in, and the audit must see the veto
#      carried back from MinecraftForge's event to NeoForge's.
#   3. negative control — the same server with -Dforbric.unifiedEvents=off: the audit must see no forward at all
#      and the veto must not take effect, so the rules above are about the bridges and nothing else.
#
# Not covered here: call-site composites (portal, spawner, loot, fuel, tooltips — M35 and friends) and result
# fields other than cancellation.
# GATE-PARALLEL: clone=client-merged-pack:M41_RUNDIR rundirs=server-chain-m41 mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

CLIENT_DIR="${M41_RUNDIR:-$KERNEL/run/client-merged-pack}"
SERVER_DIR="$KERNEL/run/server-chain-m41"
WORLD="${M41_WORLD:-ForbricTest}"
RESULTS="$BUILD/verification/m41-event-chain"
FAIL=0
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

if [ ! -d "$CLIENT_DIR/saves/$WORLD" ] || [ ! -f "$CLIENT_DIR/options.txt" ]; then
  echo "[kernel] SKIP-FATAL: $CLIENT_DIR needs saves/$WORLD and options.txt (the M9 pack)" >&2
  exit 3
fi

kernel_jar
bash "$KERNEL/run/build-chain-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

step "1. breadth: the 97-mod client with the audit on"
rm -f "$CLIENT_DIR/logs/latest.log"; mkdir -p "$CLIENT_DIR/quickPlay"
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=$WORLD -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=260 -Dforbric.clientSmokeScreenMouse=40 -Dforbric.eventChainAudit=$RESULTS/client.json" \
RUNDIR="$CLIENT_DIR" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$CLIENT_DIR/quickPlay/log.json" --quickPlaySingleplayer "$WORLD" > "$RESULTS/client.log" 2>&1 &
CLIENT_PID=$!
echo "[kernel] client pid=$CLIENT_PID (this gate never kills by name — another client may be running)"
for i in $(seq 1 480); do kill -0 "$CLIENT_PID" 2>/dev/null || break; sleep 1; done
if kill -0 "$CLIENT_PID" 2>/dev/null; then
  echo "[kernel] FAIL the client did not finish in 480 s"; FAIL=1
  kill_owned_descendants "$CLIENT_PID"; sleep 5; kill -9 "$CLIENT_PID" 2>/dev/null || true
fi
wait "$CLIENT_PID" 2>/dev/null || true
check "the client left the world cleanly" 'ClientSmoke\] clean disconnect observed' "$CLIENT_DIR/logs/latest.log"
check "the mouse drill drove a container screen" 'ClientSmoke\] opened a container screen' "$CLIENT_DIR/logs/latest.log"
if python3 - "$RESULTS/client.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
v = report['violations']
assert all(v[k] == 0 for k in ('duplicated', 'multiplicityDrift', 'cancelLost', 'innerFailures', 'unbalanced')), v
seen = {}
for pair in report['pairs']:
    assert pair['outer'].startswith('NEO:') and pair['inner'].startswith('FORGE:'), pair
    seen.setdefault(pair['outer'].rsplit('.', 1)[-1], 0)
    seen[pair['outer'].rsplit('.', 1)[-1]] += pair['forwardedOnce']
required = ['ServerTickEvent$Pre', 'ServerTickEvent$Post', 'LevelTickEvent$Pre', 'LevelTickEvent$Post',
            'PlayerTickEvent$Pre', 'PlayerTickEvent$Post', 'ClientTickEvent$Pre', 'ClientTickEvent$Post',
            'RenderFrameEvent$Pre', 'RenderFrameEvent$Post', 'EntityJoinLevelEvent', 'LevelEvent$Load',
            'LevelEvent$Save', 'LevelEvent$Unload', 'PlayerEvent$StartTracking', 'PlayerEvent$StopTracking',
            'PlayerEvent$PlayerLoggedInEvent', 'RegisterCommandsEvent', 'ServerAboutToStartEvent', 'ServerStartingEvent',
            'ServerStartedEvent', 'ServerStoppingEvent', 'ServerStoppedEvent', 'ScreenEvent$MouseButtonPressed$Pre',
            'ScreenEvent$MouseButtonReleased$Pre', 'ScreenEvent$MouseDragged$Pre', 'ScreenEvent$MouseScrolled$Post']
missing = [name for name in required if seen.get(name, 0) < 1]
assert not missing, ('bridges never seen forwarding', missing, sorted(seen))
print(f"[kernel] {len(report['pairs'])} forwarding pairs, {len(required)} required bridges seen, violations {v}; "
      f"{len(report['incidental'])} incidental pair(s) not judged")
PY
then echo "[kernel] PASS every forward in the client session was exactly once, carried and clean"
else echo "[kernel] FAIL the client session's event chains (see $RESULTS/client.json)"; FAIL=1; fi

# run_server <phase> <extra jvm flags>
run_server() {
  local phase="$1" extra="$2" pid
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricchainprobe.jar" "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  ( sleep 200; echo stop ) | RUNDIR="$SERVER_DIR" \
    FORBRIC_JVM="-Dforbric.eventChainAudit=$RESULTS/$phase.json -Dforbric.chainProbe.output=$RESULTS/$phase-probe.json $extra" \
    "$KERNEL/run/launch-kernel-server.sh" > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 240 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
}

step "2. cancel: a MinecraftForge veto on a NeoForge entity join"
run_server positive ""
check "the veto probe ran" '\[M41Chain\] RESULT' "$RESULTS/positive.log"
if python3 - "$RESULTS/positive.json" "$RESULTS/positive-probe.json" <<'PY'
import json, sys
report, probe = json.load(open(sys.argv[1])), json.load(open(sys.argv[2]))
# addFreshEntity's answer is the outcome: an accepted entity is not always found by UUID the same tick.
assert probe['forgeVetoes'] >= 1 and probe['vetoedAdded'] is False and probe['controlAdded'] is True, probe
v = report['violations']
assert all(v[k] == 0 for k in ('duplicated', 'multiplicityDrift', 'cancelLost', 'innerFailures', 'unbalanced')), v
join = [p for p in report['pairs'] if p['outer'].endswith('.EntityJoinLevelEvent') and p['inner'].endswith('.EntityJoinLevelEvent')]
assert len(join) == 1, report['pairs']
assert join[0]['innerCancelled'] >= 1 and join[0]['cancelCarried'] >= 1 and join[0]['cancelLost'] == 0, join[0]
print(f"[kernel] veto carried {join[0]['cancelCarried']}x; the tagged entity stayed out, the control got in")
PY
then echo "[kernel] PASS a MinecraftForge cancel reached NeoForge's event and the world"
else echo "[kernel] FAIL the veto did not travel the chain (see $RESULTS/positive*.json)"; FAIL=1; fi

step "3. negative control: the same server with the bridges off"
run_server bridges-off "-Dforbric.unifiedEvents=off"
if python3 - "$RESULTS/bridges-off.json" "$RESULTS/bridges-off-probe.json" <<'PY'
import json, sys
report, probe = json.load(open(sys.argv[1])), json.load(open(sys.argv[2]))
assert report['pairs'] == [], ('forwards seen with the bridges off', report['pairs'])
assert probe['forgeVetoes'] == 0 and probe['vetoedAdded'] is True, probe
print('[kernel] no forwards and no veto without the bridges')
PY
then echo "[kernel] PASS the audit sees no forward, and the veto no effect, without the bridges"
else echo "[kernel] FAIL the negative control (see $RESULTS/bridges-off*.json)"; FAIL=1; fi

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M41 EVENT CHAIN GATE GREEN — every cross-bus forward once, cancels carried, none without the bridges"
else
  echo "[kernel] ❌ M41 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
