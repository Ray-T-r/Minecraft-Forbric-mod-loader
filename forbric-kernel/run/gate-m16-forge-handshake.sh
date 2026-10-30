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
# THE NEOFORGE HALF. The NeoForge canary (forbricneolive) rides along on both ends and carries the same two contracts
# in its own family's shape: a per-world SERVER config whose pre-written value must reach the client (NeoForge's
# SyncConfig configuration task and ConfigFilePayload, not MinecraftForge's handshake), and a CLIENT config that loads
# once on the client and never on the dedicated server. These are the checks that outlive the MinecraftForge canary.
# Their negative control is not -Dforbric.forgeHandshake=off -- that switch takes out MinecraftForge's handshake only:
# measured 2026-10-30, it turns exactly ten MinecraftForge checks red and none of the NeoForge ones, which is the
# evidence this half does not lean on MinecraftForge. NeoForge's sync rests on NeoForge's own configuration tasks, and
# the kernel's whole part in it is wiring NeoForge's internal @EventBusSubscriber classes (KernelEventSubscribers.
# registerNeoForgeInternal). Unwiring just net.neoforged.neoforge.network.ConfigurationInitialization was measured too:
# it is not a clean control, because NeoForge's two halves are coupled -- the task that collects the configs never
# runs, and ConfigSync's login listener then crashes the server ("configsToSync should contain an entry for player")
# the moment the client joins. So no KERNEL switch leaves the client unsynced with everything else up. The live negative
# controls are on the canary and the gate instead, and both were run (2026-10-30):
#   M16_EXTRA_JVM='-Dforbricneolive.clientServerConfig=off'  the client does not register its SERVER config (the server
#       ignores the flag), so NeoForge's ConfigFilePayload arrives with nothing to land in and the client reads
#       <unloaded> in the world. Server, payload and join all stay up. RED: exactly "the client was handed the server's
#       config", "the client reads the server's value", "the client is not on its own default" (on <unloaded>) and "the
#       client registered its SERVER config" -- the receiving half of the sync, taken away alone.
#   M16_NEO_PREWRITE=off  the gate does not pre-write the world's forbricneolive-server.toml, so the server holds the
#       default and syncs THAT (the client logs "RELOADING ... greeting=default loaded=true path=null"). RED: exactly
#       "the SERVER config came from the world" (with no world file NeoForge keeps it in config/), "and it holds the
#       pre-written value", "the client was handed the server's config", "the client reads the server's value" and "the
#       client is not on its own default" (on default) -- the client's value follows the server's world file, and
#       check_absent's default alternative matches the real line.
# The sending half (SyncConfig itself) has no control that leaves the server up, as measured above; the synced shape
# (path=null) is what ties the client's value to the payload rather than to a file of its own.
#
# STAGE 3 OF DROPPING MinecraftForge (what changed and why). Until Stage 3 the MinecraftForge canary (forbriclive) rode
# on both ends and carried most of this gate: MinecraftForge's own handshake (a MODDED peer, the channel and mod lists,
# the per-world SERVER config pushed through its configuration-phase sync, its CLIENT config loading once), its
# LoadingModList published before Mixin, FMLEnvironment.dist decided before the first guest mixin plugin, and its play
# channel's ping/pong. Forbric now refuses a MinecraftForge-only jar, and a dedicated server stops on that refusal, so
# none of that can run: those checks and the jar are gone (the M16_CLIENT_CONFIG block with them). The NeoForge half
# below -- written in Stage 2.5 to outlive them -- is unchanged, and so are the generic checks: the join, the absence of
# every encode/decode, configuration-task, registry-sync and channel-list failure, and the clean leave. The absence
# checks that name MinecraftForge's dispatcher and channel declaration stay too: its plumbing still runs with zero
# MinecraftForge mods, and a failure in it would still break the connection. FallingTree stays on both ends: it is a
# universal jar, and with its MinecraftForge half set aside it now loads through its NeoForge half, which this gate
# asserts. The -Dforbric.forgeHandshake=off control below took out MinecraftForge's handshake only; with nothing
# MinecraftForge-shaped left on the wire it has no check left to turn red.
#
# Port 25604, not the default — a developer or another agent session may have a server on 25565.
# GATE-PARALLEL: rundirs=fhs-server,fhs-client mem=3500
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
  "$DL/forge-26.2/FallingTree-26.2-25.jar"
  "$NEOLIVE"
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
# The value the client must end up seeing. Written into the world BEFORE the server boots, so NeoForge's own
# per-world config load picks it up and its sync is the only way it can reach the client.
mkdir -p "$SRV/FhsWorld/serverconfig"
# M16_NEO_PREWRITE=off is the negative control in the header: the server then holds the default, and so must the client.
if [ "${M16_NEO_PREWRITE:-on}" != off ]; then
  printf '# pre-written by gate-m16 BEFORE the server booted: the client must see THIS value, not its own default\ngreeting = "from-the-gate"\n' > "$SRV/FhsWorld/serverconfig/forbricneolive-server.toml"
else
  echo "[kernel] M16_NEO_PREWRITE=off: the NeoForge SERVER config is NOT pre-written (negative control)"
fi
printf 'eula=true\n' > "$SRV/eula.txt"
printf 'server-port=%s\nonline-mode=false\nlevel-type=minecraft\\:flat\nlevel-name=FhsWorld\nmax-tick-time=-1\nview-distance=6\nspawn-protection=0\nsync-chunk-writes=false\n' "$PORT" > "$SRV/server.properties"
cp "$KERNEL/run/client-merged-pack/options.txt" "$CLI/options.txt" 2>/dev/null \
  || printf 'version:4903\nonboardAccessibility:false\n' > "$CLI/options.txt"
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
  port_was_free "$SLOG"   # a server that lost its port never reaches Done either
  step "M16 result"; echo "[kernel] ❌ M16 GATE RED — see $SLOG"; exit 1
fi

step "connect a real client and let it play"
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
stop_tree "$CLIENT_PID"   # the whole tree: lib.sh says why children-only orphans a JVM
wait "$CLIENT_PID" 2>/dev/null
cat "$CGAME" >> "$CLOG" 2>/dev/null || true

step "stop the server"
echo "stop" >&9
exec 9>&-
await_server "$SRVPID" "$SLOG" 90
rm -f "$FIFO" "$(_pidfile "$SRV")"

echo "[kernel] the canary's network lines:"
grep -aE 'ForbricNeoLive/CFG' "$SLOG" "$CLOG" | sed 's#^.*gate-m16-\(server\|client\).log:#[kernel]   \1: #' | cut -c1-200 | sort -u

step "a real client joined a real server, with the same NeoForge mods on both ends (must PASS)"
check "client dialled the address"    "Connecting to 127.0.0.1"                           "$CLOG"
check "server accepted the login"     "logged in with entity id"                          "$SLOG"
check_kept_up "server kept up while the player was on" "$SLOG"
check "client finished configuring"   "ClientSmoke\] joined world via quick-play"         "$CLOG"
check_absent "nothing was refused on the server" "\[Forbric/Unsupported\] refusing" "$SLOG"
check_absent "nothing was refused on the client" "\[Forbric/Unsupported\] refusing" "$CLOG"

step "FallingTree, a universal jar, loads through its NeoForge half on both ends (must PASS)"
# All three manifests, and MinecraftForge's half is set aside (Stage 3); NeoForge wins over Fabric by preference.
check "server: its MinecraftForge half is set aside" "FallingTree-26\.2-25\.jar: its MinecraftForge half is set aside" "$SLOG"
check "server: it loads as NeoForge only" "FallingTree-26\.2-25\.jar declares 3 loaders — loading it as NEOFORGE only" "$SLOG"
check "client: its MinecraftForge half is set aside" "FallingTree-26\.2-25\.jar: its MinecraftForge half is set aside" "$CLOG"
check "client: it loads as NeoForge only" "FallingTree-26\.2-25\.jar declares 3 loaders — loading it as NEOFORGE only" "$CLOG"

# M16_NEO_CONFIG_BEGIN — the NeoForge canary's config contracts (they were the MinecraftForge checks' twins until Stage 3).
step "the server read its per-world NeoForge config (must PASS)"
check "NeoForge: the SERVER config came from the world" "ForbricNeoLive/CFG\] LOADING forbricneolive-server.toml: .*path=.*FhsWorld/serverconfig" "$SLOG"
check "NeoForge: and it holds the pre-written value"    "ForbricNeoLive/CFG\] LOADING forbricneolive-server.toml: greeting=from-the-gate loaded=true" "$SLOG"
check_exactly "NeoForge: the per-world SERVER config loads once on the server" "ForbricNeoLive/CFG\] LOADING forbricneolive-server\.toml" "$SLOG" 1

step "NeoForge's configuration-phase sync delivered the server's config to the client (must PASS)"
# What the client holds in a world, read out of the spec a hundred ticks in: the server's value arrives only through
# NeoForge's SyncConfig task, and without it the client reads its own default.
# path=null is the synced shape: ConfigTracker.acceptSyncedConfig builds the client's copy from the payload's bytes,
# with no file behind it, so the value cannot have come from a file of the client's own.
check "NeoForge: the client was handed the server's config" "ForbricNeoLive/CFG\] (LOADING|RELOADING) forbricneolive-server.toml: greeting=from-the-gate loaded=true path=null" "$CLOG"
check "NeoForge: the client reads the server's value"     "ForbricNeoLive/CFG\] greeting seen on the client in a world: from-the-gate" "$CLOG"
check_absent "NeoForge: the client is not on its own default" "ForbricNeoLive/CFG\] greeting seen on the client in a world: (default|<unloaded>)" "$CLOG"
# The canary's own negative control (header) left on would make this run prove the opposite of what it says.
check_absent "NeoForge: the client registered its SERVER config" "ForbricNeoLive/CFG\] SERVER config NOT registered on this client" "$CLOG"

step "NeoForge CLIENT config loads once on the client and never on the dedicated server"
# Counted in CGAME, not CLOG: since System.out was routed into log4j each canary print reaches CLOG twice (the stdout
# redirect and latest.log's appended copy), and latest.log holds it once per occurrence.
check "NeoForge: CLIENT default was read from the loaded spec" 'ForbricNeoLive/CFG\] LOADING forbricneolive-client\.toml: probe=17 loaded=true([[:space:]]|$)' "$CLOG"
assert_eq "NeoForge: CLIENT Loading fired exactly once" 1 "$(grep -acE 'ForbricNeoLive/CFG\] LOADING forbricneolive-client\.toml:' "$CGAME" || true)"
if [ -s "$CLI/config/forbricneolive-client.toml" ]; then echo "[kernel] PASS NeoForge CLIENT file was created on the client"
else echo "[kernel] FAIL NeoForge CLIENT file was not created on the client"; FAIL=1; fi
if [ ! -e "$SRV/config/forbricneolive-client.toml" ]; then echo "[kernel] PASS no NeoForge CLIENT file on the dedicated server"
else echo "[kernel] FAIL dedicated server opened a NeoForge CLIENT config"; FAIL=1; fi
check_absent "NeoForge: no CLIENT Loading on the dedicated server" 'ForbricNeoLive/CFG\] LOADING forbricneolive-client\.toml:' "$SLOG"

step "a NeoForge mod's mixin config plugin asks the side before any mod exists, and gets it (must PASS)"
check "NeoForge: the plugin saw the dedicated server" "ForbricNeoLive/PLUGIN\] prepareConfigs saw dist=DEDICATED_SERVER" "$SLOG"
check "NeoForge: the plugin saw the client"           "ForbricNeoLive/PLUGIN\] prepareConfigs saw dist=CLIENT" "$CLOG"
check_absent "NeoForge: and never failed to ask (server)" "ForbricNeoLive/PLUGIN\] prepareConfigs saw dist=(null|FAILED)" "$SLOG"
check_absent "NeoForge: and never failed to ask (client)" "ForbricNeoLive/PLUGIN\] prepareConfigs saw dist=(null|FAILED)" "$CLOG"
# M16_NEO_CONFIG_END

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
check "the client stopped its config file-watchers at close" "Forbric/Shutdown\\] stopped [1-9][0-9]* config file-watcher" "$CLOG"
check "the server stopped its config file-watchers at exit" "Forbric/Shutdown\\] stopped [1-9][0-9]* config file-watcher" "$SLOG"
# The last argument: the kernel narrates the bugs it repairs, and one of those explanations contains
# "IncompatibleClassChangeError". Without the exclusion this gate's own success message matches its
# own failure pattern.
check_absent "server did not reject the client" "This server requires|Incompatible|mismatch" "$SLOG" '\[Forbric/'
check_absent "no client crash"       "Preparing crash report"                           "$CLOG"
check_absent "no server crash"       "Preparing crash report"                           "$SLOG"
awk '/Done \(/{d=1} d' "$SLOG" > "$BUILD/gate-m16-postdone.log"
check_absent "no post-Done exception" "Encountered an unexpected exception"             "$BUILD/gate-m16-postdone.log"
check_absent "no thread leaked past main" "Client shutdown from post-main"              "$CLOG"

step "M16 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M16 GATE GREEN — NeoForge's configuration-phase sync ran over the socket: the server's per-world config reached the client, and nothing in the handshake failed"
else
  echo "[kernel] ❌ M16 GATE RED — server $SLOG / client $CLOG"
fi
exit "$FAIL"
