#!/usr/bin/env bash
# M14 gate — a Forbric client on a PURE Fabric server, with a mod set that differs from the server's.
#
# WHY THIS EXISTS. gate-m12 syncs registries between two Forbric ends, where NeoForge's registry sync runs and
# the kernel applies it to the seventeen MinecraftForge-wrapped registries. Against a pure Fabric server only
# fabric-api's sync runs, and its remap was a silent no-op on those wrappers: it rewrites MappedRegistry fields the
# wrapper never fills. With identical mod sets that costs nothing — the ids already agree — so the only way to see
# it is a client whose registry order DIFFERS from the server's. This gate builds that difference on purpose and
# then asks the one question that matters: does a block the server places decode to the same block on the client?
#
# THE DIFFERENCE. Both ends run fabric-api + Macaw's Bridges (303 blocks/items). The client ALSO carries the
# kernel's Fabric canary, which registers one block and one item before any other Fabric mod's, so every mcwbridges
# block and item on the client is one id further along than on the server. The gate places a bridge block and
# GIVES the player a bridge item, and the client reads both back by name. The item is the sharp one: an item id off
# by one is a different item, full stop. A block-STATE id off by one usually lands on a neighbouring state of the
# same block — which is how the first negative-control run read the right block name without any remap at all —
# so the block probe reads back the full state, and the vanilla block next to it is the control that must ALWAYS
# match (vanilla ids agree with or without a remap).
#
# The remap has to have actually moved something, and lost nothing: the kernel's own summary line is asserted for
# "N id(s) moved" with N > 0 and "0 entr(ies) lost" — the canary block is client-only, and Forge's loadIds must keep
# it (after the server's ids) rather than drop it. It did drop it on the first run; the kernel now places local-only
# entries after the server's ids itself, which is fabric-api's REMOTE-mode rule.
#
# WHAT THE NEGATIVE CONTROL SHOWED (M14_EXTRA_JVM=-Dforbric.forgeWrapperSync=off, the old behaviour minus the
# crash): the server gave the player an andesite bridge and the client held a MOSSY COBBLESTONE bridge — the item
# one id along — and the placed block read back with the wrong connection state. With the remap: 303 ids moved,
# 0 lost, 34393 block states re-numbered, and both read back as themselves.
#
# Port 25602, not the default — a developer or another agent session may have a server on 25565.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

PORT="${M14_PORT:-25602}"
SRV="$KERNEL/run/fab-server"
CLI="$KERNEL/run/fab-client"
SLOG="$BUILD/gate-m14-server.log"
CLOG="$BUILD/gate-m14-client.log"
mkdir -p "$BUILD"

DL="$RUN_OLD/downloads"
LAUNCHER="$DL/fabric-server-26.2/fabric-server-mc26.2-loader0.19.5-launcher1.1.2.jar"
FABRIC_API="$DL/fabric-26.2/fabric-api-0.154.0+26.2.jar"
SHARED="$DL/fabric-26.2/mcw-bridges-3.1.2-mc26.2fabric.jar"
CANARY="$KERNEL/run/canary/forbricfabriclive.jar"
PLAYER="ForbricKernel"
# A block the shared mod registers, and a vanilla control. Placed by the server, read back by the client.
MOD_BLOCK="mcwbridges:andesite_bridge"
MOD_ITEM="mcwbridges:andesite_bridge"
VANILLA_BLOCK="minecraft:diamond_block"
for f in "$LAUNCHER" "$FABRIC_API" "$SHARED" "$CANARY"; do
  [ -f "$f" ] || { echo "[kernel] SKIP-FATAL: missing $f (run/build-fabric-canary.sh builds the canary)" >&2; exit 3; }
done

kernel_jar

step "stage a pure Fabric server (fabric-loader 0.19.5 + fabric-api + Macaw's Bridges) and a Forbric client with one mod more"
reap_stale_server "$SRV"
# fabric's ServerLauncher keeps the vanilla server jar and its libraries under .fabric/ (see lib.sh).
stash_downloads "$SRV" fab-server .fabric
rm -rf "$SRV" "$CLI"
mkdir -p "$SRV/mods" "$CLI/mods" "$CLI/quickPlay"
restore_downloads "$SRV" fab-server .fabric
cp "$FABRIC_API" "$SHARED" "$SRV/mods/"
cp "$FABRIC_API" "$SHARED" "$CANARY" "$CLI/mods/"
printf 'eula=true\n' > "$SRV/eula.txt"
printf 'server-port=%s\nonline-mode=false\nlevel-type=minecraft\\:flat\nlevel-name=FabWorld\ngamemode=survival\ndifficulty=peaceful\nspawn-monsters=false\nspawn-protection=0\nview-distance=6\nsimulation-distance=6\nenforce-secure-profile=false\nmax-players=5\nmotd=forbric-fabric-gate\n' "$PORT" > "$SRV/server.properties"
cp "$KERNEL/run/client-merged-pack/options.txt" "$CLI/options.txt" 2>/dev/null || printf 'version:4903\n' > "$CLI/options.txt"
echo "[kernel] server mods: $(ls -1 "$SRV/mods" | paste -sd' ' -)"
echo "[kernel] client mods: $(ls -1 "$CLI/mods" | paste -sd' ' -)"

step "boot the Fabric server and hold it open"
FIFO="$SRV/.stdin"; rm -f "$FIFO"; mkfifo "$FIFO"
# The launcher jar downloads the vanilla server and Fabric's libraries on first run, into the rundir.
( cd "$SRV" && exec java -Xmx2G -jar "$LAUNCHER" --nogui ) < "$FIFO" > "$SLOG" 2>&1 &
SRVPID=$!
record_server_pid "$SRV" "$SRVPID"
exec 9>"$FIFO"
echo "[kernel] server pid=$SRVPID"
READY=0
for i in $(seq 1 300); do
  kill -0 "$SRVPID" 2>/dev/null || { echo "[kernel] server died after ~${i}s"; break; }
  grep -qE 'Done \(' "$SLOG" 2>/dev/null && { READY=1; echo "[kernel] server ready after ~${i}s"; break; }
  sleep 1
done
if [ "$READY" -ne 1 ]; then
  echo "[kernel] FAIL the Fabric server never reached Done"; FAIL=1
  exec 9>&-; kill_tree "$SRVPID"; rm -f "$FIFO" "$(_pidfile "$SRV")"
  step "M14 result"; echo "[kernel] ❌ M14 GATE RED — see $SLOG"; exit 1
fi
# Spawn at the origin; the probe blocks go a few blocks up and along +Z, where the player cannot stand on them.
for cmd in 'forceload add -16 -16 16 16' 'setworldspawn 0 -60 0' 'time set day' 'weather clear'; do
  echo "$cmd" >&9; sleep 0.5
done

step "connect the client, let the server place the two probe blocks, read them back"
# The probe reads at world tick 160; the blocks are placed as soon as the client reports it joined (well before).
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=127.0.0.1:$PORT -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=260 -Dforbric.clientSmokeProbe=0,-57,4;0,-57,6 -Dforbric.clientSmokeProbeIds=item:mcwbridges:andesite_bridge ${M14_EXTRA_JVM:-}" \
RUNDIR="$CLI" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$CLI/quickPlay/log.json" --quickPlayMultiplayer "127.0.0.1:$PORT" > "$CLOG" 2>&1 &
CLIENT_PID=$!
echo "[kernel] client pid=$CLIENT_PID (killed by pid only — another client may be running)"

CGAME="$CLI/logs/latest.log"
PLACED=0
for i in $(seq 1 300); do
  kill -0 "$CLIENT_PID" 2>/dev/null || { echo "[kernel] client exited on its own after ~${i}s"; break; }
  if [ "$PLACED" -eq 0 ] && grep -q 'ClientSmoke\] joined world' "$CGAME" 2>/dev/null; then
    echo "setblock 0 -57 4 $MOD_BLOCK" >&9; sleep 0.3
    echo "setblock 0 -57 6 $VANILLA_BLOCK" >&9; sleep 0.3
    echo "give $PLAYER $MOD_ITEM 1" >&9
    PLACED=1; echo "[kernel] placed $MOD_BLOCK and $VANILLA_BLOCK, gave $MOD_ITEM at ~${i}s"
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
echo "stop" >&9
exec 9>&-
await_server "$SRVPID" "$SLOG" 90
rm -f "$FIFO" "$(_pidfile "$SRV")"

echo "[kernel] the client's read-back:"
grep -a 'ClientSmoke\] block at\|ClientSmoke\] hotbar' "$CGAME" | sed 's/^/[kernel]   /' | cut -c1-220
grep -a 'Forbric/RegistrySync\] .* followed the server' "$CGAME" | sed 's/^/[kernel]   /' | cut -c1-260

step "a Forbric client joined a pure Fabric server (must PASS)"
check "it is a real Fabric server"     "Fabric Loader 0.19.5"                              "$SLOG"
check "client dialled the server"      "Connecting to 127.0.0.1"                           "$CLOG"
check "server accepted the join"       "$PLAYER joined the game"                           "$SLOG"
check "client entered the world"       "ClientSmoke\] joined world via quick-play"         "$CLOG"
check_absent "not rejected for its registries" "Received unknown remote registry|Registry remapping failed|Failed to sync" "$CLOG"

step "the difference was real and the remap corrected it (must PASS)"
check "the canary shifted the client's block ids" "ForbricFabricLive\] registered block forbricfabriclive:canary_block" "$CLOG"
check "the canary shifted the client's item ids"  "ForbricFabricLive\] registered item forbricfabriclive:canary_block"  "$CLOG"
check "the probe blocks were placed"   "Changed the block"                                 "$SLOG" 2
check "the probe item was given"       "Gave 1"                                            "$SLOG"
check "Forge-wrapped registries followed the server's ids" "Forge-wrapped registr.* followed the server's ids" "$CGAME"
check "…and some ids actually moved"   "followed the server's ids .* [1-9][0-9]* id\(s\) moved" "$CGAME"
check "…and no entry was lost"         "followed the server's ids .* 0 entr\(ies\) lost"   "$CGAME"
check_absent "no entry reported lost"  "LOST .* in the remap"                              "$CGAME"
check "fabric-api's remap listeners were told" "told fabric-api's remap listeners"         "$CGAME"
check "the shared mod's block reads back as itself" "block at \(0 -57 4\) is $MOD_BLOCK"   "$CGAME"
check "the shared mod's item reads back as itself"  "hotbar slot 0 holds $MOD_ITEM "        "$CGAME"
check "the vanilla control reads back as itself"    "block at \(0 -57 6\) is $VANILLA_BLOCK" "$CGAME"

step "and the disconnect put the ids back (must PASS)"
# The client logs one entry's raw id before connecting, in the world, and after the clean disconnect. A working
# sync moves it; a working revert moves it back — both are asserted, since either alone could be a no-op.
idat() { grep -a "registry id of item mcwbridges:andesite_bridge $1:" "$CGAME" | sed -E 's/.*: ([-0-9a-z]+)$/\1/' | tail -1; }
ID_BEFORE=$(idat "before connecting"); ID_IN=$(idat "in world"); ID_AFTER=$(idat "after disconnect")
echo "[kernel] mcwbridges:andesite_bridge item id — before: ${ID_BEFORE:-?}, in world: ${ID_IN:-?}, after: ${ID_AFTER:-?}"
if [ -n "$ID_BEFORE" ] && [ -n "$ID_IN" ] && [ "$ID_BEFORE" != "$ID_IN" ]; then echo "[kernel] PASS the sync moved the id ($ID_BEFORE -> $ID_IN)"; else echo "[kernel] FAIL the sync moved the id (${ID_BEFORE:-?} -> ${ID_IN:-?})"; FAIL=1; fi
if [ -n "$ID_BEFORE" ] && [ "$ID_BEFORE" = "$ID_AFTER" ]; then echo "[kernel] PASS the disconnect put it back ($ID_AFTER)"; else echo "[kernel] FAIL the disconnect put it back (before ${ID_BEFORE:-?}, after ${ID_AFTER:-?})"; FAIL=1; fi
check "the kernel reverted the synced registries" "reverted .* registr.* to their pre-connection ids" "$CGAME"

step "neither side broke (must be ABSENT)"
check "left cleanly"                   "ClientSmoke\] clean disconnect observed"          "$CLOG"
check "the client stopped its config file-watchers at close" "Forbric/Shutdown\\] stopped [1-9][0-9]* config file-watcher" "$CLOG"
check_absent "NeoForge loaded its default server configs once" "Overwriting non-null config" "$CLOG"
check_absent "no client crash"         "Preparing crash report"                            "$CLOG"
check_absent "no server crash"         "Preparing crash report|Encountered an unexpected exception" "$SLOG"
check_absent "no thread leaked past main" "Client shutdown from post-main"                "$CLOG"

step "M14 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M14 GATE GREEN — on a pure Fabric server with a different mod set, the Forge-wrapped registries followed the server's ids and its blocks decode as its own"
else
  echo "[kernel] ❌ M14 GATE RED — server $SLOG / client $CLOG"
fi
exit "$FAIL"
