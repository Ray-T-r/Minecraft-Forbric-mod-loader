#!/usr/bin/env bash
# M15 gate — a traditional-Forge (MinecraftForge) mod's own network channel, both directions, over a socket.
#
# WHY THIS EXISTS. gate-m12 proved the configuration handshake and the Fabric/NeoForge registry syncs over a real
# socket, with a Forge mod on board that happens not to send packets. Forge mods that DO send packets — GUI sync,
# config sync on join, entity data — go through SimpleChannel → ForgePayload → the vanilla custom-payload packet,
# and every piece of Forge's plumbing under that lost the byte-merge to NeoForge's: its payload codec
# (ForgeHooks.getCustomPayloadCodec was the fallback in Forge's patched packets), its dispatch
# (ForgeHooks.onCustomPayload survived only in the play-phase server listener), and the per-connection channel
# bookkeeping its channels consult. Until this gate nothing ever sent a Forge packet on Forbric.
#
# The kernel's Forge canary (forbric-loader/run/livemod-src, built into forge-runtime/forbriclive.jar) builds a
# SimpleChannel "forbriclive:net": the server pings the first player it sees on the tick event, the client logs
# the ping and pongs back, the server logs the pong. Both ends run the canary and FallingTree — a real Forge mod
# that syncs its config to the client on join over its own channel — so the gate also asserts that nothing in
# that traffic failed to encode or decode.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). Forbric now refuses a MinecraftForge-only jar, and a
# dedicated server stops on that refusal, so the canary forbriclive can no longer be staged: every check that read its
# lines went with it -- its SimpleChannel's ping and pong and both ends seeing the other's declaration, FML client setup
# delivered to a traditional-Forge mod and what it deferred, and MinecraftForge's branch of the Mods list's Config
# button. No MinecraftForge mod can reach any of those paths now. What stays is the half that never depended on it:
# FallingTree, a real mod that syncs its config to the client on join over its own channel, on both ends of a real
# socket. It is a universal jar; with its MinecraftForge half set aside it loads through its NeoForge half (asserted
# below), so its channel is now NeoForge's payload path, and every encode/decode absence check stays as it was. So do
# the join, the clean leave, the config watchers' shutdown and the absence checks that name MinecraftForge's dispatcher
# and channel declaration: that plumbing still runs with zero MinecraftForge mods. The file keeps its name until Stage 4.
#
# Port 25603, not the default — a developer or another agent session may have a server on 25565.
# GATE-PARALLEL: rundirs=fnet-server,fnet-client mem=3500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

PORT="${M15_PORT:-25603}"
SRV="$KERNEL/run/fnet-server"
CLI="$KERNEL/run/fnet-client"
SLOG="$BUILD/gate-m15-server.log"
CLOG="$BUILD/gate-m15-client.log"
mkdir -p "$BUILD"

DL="$RUN_OLD/downloads"
PLAYER="ForbricKernel"
MODS=(
  "$DL/forge-26.2/FallingTree-26.2-25.jar"
)
if [ -n "${M15_MODS:-}" ]; then read -r -a MODS <<< "$M15_MODS"; fi
for m in "${MODS[@]}"; do
  [ -f "$m" ] || { echo "[kernel] SKIP-FATAL: missing mod $m (forbric-loader/run/build-testmods.sh builds the canary)" >&2; exit 3; }
done

kernel_jar

step "stage the SAME mod set on both ends (port $PORT)"
reap_stale_server "$SRV"
rm -rf "$SRV" "$CLI"
mkdir -p "$SRV/mods" "$CLI/mods" "$CLI/quickPlay"
for m in "${MODS[@]}"; do cp "$m" "$SRV/mods/"; cp "$m" "$CLI/mods/"; done
printf 'eula=true\n' > "$SRV/eula.txt"
printf 'server-port=%s\nonline-mode=false\nlevel-type=minecraft\\:flat\nlevel-name=FnetWorld\nmax-tick-time=-1\nview-distance=6\nspawn-protection=0\nsync-chunk-writes=false\n' "$PORT" > "$SRV/server.properties"
cp "$KERNEL/run/client-merged-pack/options.txt" "$CLI/options.txt" 2>/dev/null \
  || printf 'version:4903\nonboardAccessibility:false\n' > "$CLI/options.txt"
echo "[kernel] staged: $(ls -1 "$SRV/mods" | paste -sd' ' -)"

step "boot the dedicated server and hold it open"
FIFO="$SRV/.stdin"; rm -f "$FIFO"; mkfifo "$FIFO"
FORBRIC_JVM="${M15_EXTRA_JVM:-}" \
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
  port_was_free "$SLOG"   # a server that lost its port never reaches Done either
  step "M15 result"; echo "[kernel] ❌ M15 GATE RED — see $SLOG"; exit 1
fi

step "connect a real client and let FallingTree sync its config"
# Ready at 60; FallingTree's config sync goes out on join, so 200 ticks is ample and keeps the run short.
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=127.0.0.1:$PORT -Dforbric.clientSmokeModsScreen=80 -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=200 ${M15_EXTRA_JVM:-}" \
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
stop_tree "$CLIENT_PID"   # the whole tree: lib.sh says why children-only orphans a JVM
wait "$CLIENT_PID" 2>/dev/null
cat "$CGAME" >> "$CLOG" 2>/dev/null || true

step "stop the server"
echo "stop" >&9
exec 9>&-
await_server "$SRVPID" "$SLOG" 90
rm -f "$FIFO" "$(_pidfile "$SRV")"

step "a real client joined a real server, with the same mods on both ends (must PASS)"
check "client dialled the address"    "Connecting to 127.0.0.1"                           "$CLOG"
check "server accepted the login"     "logged in with entity id"                          "$SLOG"
check_kept_up "server kept up while the player was on" "$SLOG"
check "client finished configuring"   "ClientSmoke\] joined world via quick-play"         "$CLOG"
check_absent "nothing was refused on the server" "\[Forbric/Unsupported\] refusing" "$SLOG"
check_absent "nothing was refused on the client" "\[Forbric/Unsupported\] refusing" "$CLOG"

step "FallingTree, a universal jar, loads through its NeoForge half on both ends (must PASS)"
check "server: its MinecraftForge half is set aside" "FallingTree-26\.2-25\.jar: its MinecraftForge half is set aside" "$SLOG"
check "server: it loads as NeoForge only" "FallingTree-26\.2-25\.jar declares 3 loaders — loading it as NEOFORGE only" "$SLOG"
check "client: its MinecraftForge half is set aside" "FallingTree-26\.2-25\.jar: its MinecraftForge half is set aside" "$CLOG"
check "client: it loads as NeoForge only" "FallingTree-26\.2-25\.jar declares 3 loaders — loading it as NEOFORGE only" "$CLOG"

step "nothing in a real mod's traffic fails to encode, decode or dispatch (must be ABSENT)"
check_absent "every payload encodes (server)"  "Failed to encode packet"                  "$SLOG"
check_absent "every payload encodes (client)"  "Failed to encode packet"                  "$CLOG"
check_absent "every payload decodes"  "Failed to decode packet|Failed decoding custom payload|Unknown custom packet" "$CLOG"
check_absent "no Forge dispatch error (server)" "dispatcher threw|no MinecraftForge handler took" "$SLOG"
check_absent "no Forge dispatch error (client)" "dispatcher threw|no MinecraftForge handler took" "$CLOG"
check_absent "client declared its Forge channels" "could not declare MinecraftForge's channels" "$CLOG"
check_absent "server declared its Forge channels" "could not declare MinecraftForge's channels" "$SLOG"
step "it played and left cleanly (must PASS)"
check "survived real simulation"     "ClientSmoke\] client-ready after"                 "$CLOG"
check "left cleanly"                 "ClientSmoke\] clean disconnect observed"          "$CLOG"
check "the client stopped its config file-watchers at close" "Forbric/Shutdown\\] stopped [1-9][0-9]* config file-watcher" "$CLOG"
check "the server stopped its config file-watchers at exit" "Forbric/Shutdown\\] stopped [1-9][0-9]* config file-watcher" "$SLOG"
# The last argument: the kernel narrates the bugs it repairs, and one of those explanations contains
# "IncompatibleClassChangeError". Without the exclusion this gate's own success message matches its
# own failure pattern.
check_absent "server did not reject the client" "This server requires|Incompatible|mismatch" "$SLOG" '\[Forbric/'
check_absent "no client crash"       "Preparing crash report"                           "$CLOG"
check_absent "no server crash"       "Preparing crash report"                           "$SLOG"
awk '/Done \(/{d=1} d' "$SLOG" > "$BUILD/gate-m15-postdone.log"
check_absent "no post-Done exception" "Encountered an unexpected exception"             "$BUILD/gate-m15-postdone.log"
check_absent "no thread leaked past main" "Client shutdown from post-main"              "$CLOG"

step "M15 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M15 GATE GREEN — FallingTree on its NeoForge half, on both ends of a real socket: the client joined, played and left with nothing in its traffic failing"
else
  echo "[kernel] ❌ M15 GATE RED — server $SLOG / client $CLOG"
fi
exit "$FAIL"
