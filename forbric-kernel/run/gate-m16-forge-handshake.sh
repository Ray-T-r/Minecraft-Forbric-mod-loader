#!/usr/bin/env bash
# M16 gate — MinecraftForge's own login/configuration handshake, over a socket.
#
# WHY THIS EXISTS. m15 proved a Forge mod's PLAY-phase packets cross the wire. Everything that happens BEFORE that
# — Forge telling each end what the other is running, and pushing the server's per-world SERVER configs to the
# client — is a separate pipeline, and three of its links lost the byte-merge to NeoForge: the client never
# installed Forge's per-connection packet handler (Connection.channelActive lost the activation call), the server
# never posted Forge's gather event (its configuration body is NeoForge's), and the surviving task dispatch used
# the vanilla overload that Forge's own tasks refuse. So on Forbric every Forge mod saw a vanilla peer with an
# empty mod list, and a server config a mod set in its world never reached the client.
#
# WHAT THIS PROVES. The gate writes a NON-DEFAULT value into the server's world config before the server boots.
# The client ships the same mod with the same default. If the client ends up reading the server's value, Forge's
# configuration-phase sync ran end to end; if the handshake is off, it reads its own default — which is exactly
# what the negative control (-Dforbric.forgeHandshake=off) asserts.
#
# Port 25604, not the default — a developer or another agent session may have a server on 25565.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

PORT="${M16_PORT:-25604}"
SRV="$KERNEL/run/fhs-server"
CLI="$KERNEL/run/fhs-client"
SLOG="$BUILD/gate-m16-server.log"
CLOG="$BUILD/gate-m16-client.log"
mkdir -p "$BUILD"

DL="$RUN_OLD/downloads"
PLAYER="ForbricKernel"
MODS=(
  "$RUN_OLD/forge-runtime/forbriclive.jar"
  "$DL/forge-26.2/FallingTree-26.2-25.jar"
)
if [ -n "${M16_MODS:-}" ]; then read -r -a MODS <<< "$M16_MODS"; fi
for m in "${MODS[@]}"; do
  [ -f "$m" ] || { echo "[kernel] SKIP-FATAL: missing mod $m (forbric-loader/run/build-testmods.sh builds the canary)" >&2; exit 3; }
done

kernel_jar

step "stage the SAME mod set on both ends (port $PORT)"
reap_stale_server "$SRV"
rm -rf "$SRV" "$CLI"
mkdir -p "$SRV/mods" "$CLI/mods" "$CLI/quickPlay"
for m in "${MODS[@]}"; do cp "$m" "$SRV/mods/"; cp "$m" "$CLI/mods/"; done
# The value the client must end up seeing. Written into the world BEFORE the server boots, so Forge's own
# per-world config load picks it up and its sync is the only way it can reach the client.
mkdir -p "$SRV/FhsWorld/serverconfig"
printf '# pre-written by gate-m16 BEFORE the server booted: the client must see THIS value, not its own default\ngreeting = "from-the-gate"\n' > "$SRV/FhsWorld/serverconfig/forbriclive-server.toml"
printf 'eula=true\n' > "$SRV/eula.txt"
printf 'server-port=%s\nonline-mode=false\nlevel-type=minecraft\\:flat\nlevel-name=FhsWorld\nmax-tick-time=-1\nview-distance=6\nspawn-protection=0\nsync-chunk-writes=false\n' "$PORT" > "$SRV/server.properties"
cp "$KERNEL/run/client-merged-pack/options.txt" "$CLI/options.txt" 2>/dev/null || printf 'version:4903\n' > "$CLI/options.txt"
echo "[kernel] staged: $(ls -1 "$SRV/mods" | paste -sd' ' -)"

step "boot the dedicated server and hold it open"
FIFO="$SRV/.stdin"; rm -f "$FIFO"; mkfifo "$FIFO"
FORBRIC_JVM="${M16_EXTRA_JVM:-}" \
RUNDIR="$SRV" "$KERNEL/run/launch-kernel-server.sh" < "$FIFO" > "$SLOG" 2>&1 &
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
  echo "[kernel] FAIL server never reached Done"; FAIL=1
  exec 9>&-; kill_tree "$SRVPID"; rm -f "$FIFO" "$(_pidfile "$SRV")"
  step "M16 result"; echo "[kernel] ❌ M16 GATE RED — see $SLOG"; exit 1
fi

step "connect a real client and let the canary ping it"
# Ready at 60, the server pings within a second of seeing the player, the pong comes straight back; 200 ticks is
# ample and keeps the run short.
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=127.0.0.1:$PORT -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=200 ${M16_EXTRA_JVM:-}" \
RUNDIR="$CLI" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$CLI/quickPlay/log.json" --quickPlayMultiplayer "127.0.0.1:$PORT" > "$CLOG" 2>&1 &
CLIENT_PID=$!
echo "[kernel] client pid=$CLIENT_PID (killed by pid only — another client may be running)"

CGAME="$CLI/logs/latest.log"
for i in $(seq 1 300); do
  kill -0 "$CLIENT_PID" 2>/dev/null || { echo "[kernel] client exited on its own after ~${i}s"; break; }
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

echo "[kernel] the canary's network lines:"
grep -aE 'ForbricLive/(NET|CFG|HS)' "$SLOG" "$CLOG" | sed 's#^.*gate-m16-\(server\|client\).log:#[kernel]   \1: #' | cut -c1-200 | sort -u

step "a real client joined a real server, with Forge mods on both ends (must PASS)"
check "client dialled the address"    "Connecting to 127.0.0.1"                           "$CLOG"
check "server accepted the login"     "logged in with entity id"                          "$SLOG"
check "client finished configuring"   "ClientSmoke\] joined world via quick-play"         "$CLOG"
check "the canary built its channel on the server" "ForbricLive/NET\] channel forbriclive:net built" "$SLOG"
check "the canary built its channel on the client" "ForbricLive/NET\] channel forbriclive:net built" "$CLOG"

step "a Forge mod's packet still crosses the wire in both directions (must PASS)"
check "server sent the ping"          "ForbricLive/NET\] PING sent from the server"       "$SLOG"
check "client received the ping"      "ForbricLive/NET\] PING received on the client"     "$CLOG"
check "client sent the pong"          "ForbricLive/NET\] PONG sent from the client"       "$CLOG"
check "server received the pong"      "ForbricLive/NET\] PONG received on the server"     "$SLOG"

step "the server read its per-world Forge config (must PASS)"
check "the SERVER config came from the world" "ForbricLive/CFG\] LOADING forbriclive-server.toml: .*path=.*FhsWorld/serverconfig" "$SLOG"
check "and it holds the pre-written value"    "ForbricLive/CFG\] LOADING forbriclive-server.toml: greeting=from-the-gate" "$SLOG"
check "the server still holds it in play"     "ForbricLive/CFG\] greeting on the server at PING: from-the-gate"          "$SLOG"

step "MinecraftForge's handshake ran, so the client has the server's config and mod list (must PASS)"
check "the client was told the server's config" "ForbricLive/CFG\] RELOADING forbriclive-server.toml: greeting=from-the-gate" "$CLOG"
check "the client reads the server's value"     "ForbricLive/CFG\] greeting seen on the client at PING: from-the-gate"      "$CLOG"
check_absent "the client is not on its own default" "greeting seen on the client at PING: (default|<unloaded>)"             "$CLOG"
check "the client sees a modded peer"           "ForbricLive/HS\] client NetworkContext type=MODDED"                        "$CLOG"
check "the server sees a modded peer"           "ForbricLive/HS\] server NetworkContext type=MODDED"                        "$SLOG"
check "the client learned the server's channels"  "ForbricLive/HS\] client NetworkContext .*remoteChannels=[1-9]"           "$CLOG"
check "the server learned the client's channels"  "ForbricLive/HS\] server NetworkContext .*remoteChannels=[1-9]"           "$SLOG"
# The mod list itself. It is built from traditional Forge's ModList.getMods(), whose contents are derived once at
# class-initialisation time from the LoadingModList the kernel seeds — so an empty seed used to make both ends
# announce that they run no mods at all. Both ends must now name each other's Forge-family mods.
check "the client knows the server's Forge mods" "ForbricLive/HS\] client NetworkContext .*mods=\[[^]]*forbriclive" "$CLOG"
check "the server knows the client's Forge mods" "ForbricLive/HS\] server NetworkContext .*mods=\[[^]]*forbriclive" "$SLOG"
check "the list is the whole Forge family, not just this mod" "ForbricLive/HS\] client NetworkContext .*mods=\[[^]]*fallingtree" "$CLOG"
check_absent "neither end announces an empty mod list" "NetworkContext .*mods=\[\]" "$CLOG"
check "the kernel seeded MinecraftForge's loading list for real" "Seed\] seeded traditional-Forge LoadingModList with [1-9]" "$SLOG"

step "nothing in that handshake failed (must be ABSENT)"
check_absent "every payload encodes (server)"  "Failed to encode packet"                  "$SLOG"
check_absent "every payload encodes (client)"  "Failed to encode packet"                  "$CLOG"
check_absent "every payload decodes"  "Failed to decode packet|Failed decoding custom payload|Unknown custom packet" "$CLOG"
check_absent "no Forge dispatch error (server)" "dispatcher threw|no MinecraftForge handler took" "$SLOG"
check_absent "no Forge dispatch error (client)" "dispatcher threw|no MinecraftForge handler took" "$CLOG"
check_absent "client declared its Forge channels" "could not declare MinecraftForge's channels" "$CLOG"
check_absent "no Forge login rejection"   "Server is still starting|require Forge to be installed|mismatched mod channel list" "$SLOG"
check_absent "no channel-list mismatch"   "mismatched mod channel list|Connection closed - mismatched"                        "$CLOG"
check_absent "no configuration task failed" "Failed to start configuration task|Failed to tick configuration task|This should never be called" "$SLOG"
check_absent "no registry-sync failure"   "Failed to synchronize registry data|Missing registry data for connection"          "$CLOG"

step "it played and left cleanly (must PASS)"
check "survived real simulation"     "ClientSmoke\] client-ready after"                 "$CLOG"
check "left cleanly"                 "ClientSmoke\] clean disconnect observed"          "$CLOG"
check "the client stopped its config file-watchers at close" "Forbric/Shutdown\\] stopped [0-9]+ config file-watcher" "$CLOG"
check "the server stopped its config file-watchers at exit" "Forbric/Shutdown\\] stopped [0-9]+ config file-watcher" "$SLOG"
check_absent "server did not reject the client" "This server requires|Incompatible|mismatch" "$SLOG"
check_absent "no client crash"       "Preparing crash report"                           "$CLOG"
check_absent "no server crash"       "Preparing crash report"                           "$SLOG"
awk '/Done \(/{d=1} d' "$SLOG" > "$BUILD/gate-m16-postdone.log"
check_absent "no post-Done exception" "Encountered an unexpected exception"             "$BUILD/gate-m16-postdone.log"
check_absent "no thread leaked past main" "Client shutdown from post-main"              "$CLOG"

step "M16 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M16 GATE GREEN — MinecraftForge's handshake ran over the socket: both ends report a modded peer, and the server's per-world config reached the client"
else
  echo "[kernel] ❌ M16 GATE RED — server $SLOG / client $CLOG"
fi
exit "$FAIL"
