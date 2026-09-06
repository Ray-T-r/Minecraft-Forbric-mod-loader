#!/usr/bin/env bash
# M12 gate — a REAL client on a REAL socket to a REAL dedicated server.
#
# WHY THIS EXISTS. Until this gate, twelve gates covered two shapes and neither was multiplayer: eleven booted a
# dedicated server nobody ever connected to, and gate-m9 drove a client through --quickPlaySingleplayer, which
# negotiates over an in-memory connection to the integrated server. Modpacks are played on servers, and the whole
# client/server handshake — the configuration phase, known-pack negotiation, registry and tag sync, the custom
# payload channels every mod registers — ran in no test at all.
#
# That gap had a name. fabric-resource-loader's SynchronizeRegistriesTaskMixin was restored from a years-old pin
# after measuring that nothing regressed; the honest caveat written down at the time was that every gate
# negotiates over memory, so the case the mixin EXISTS for — a remote client and server comparing pack sets — was
# still unexercised. This is that exercise.
#
# Deliberately NOT port 25565. A developer, or another agent session, may have a server on the default port; a
# gate that silently connects to someone else's world would be both wrong and hard to notice.
#
# THIS GATE ARRIVED RED AND EARNED ITS KEEP: four bugs that eleven green gates could not see, because none of them
# ever connected a client over a socket and singleplayer negotiates in memory (no configuration-phase handshake,
# no registry sync, no packet ever encoded). In the order they fell:
#
#  1. The server kicked its own client: "This server requires Fabric Loader and Fabric API installed on your
#     client!" NeoForge's patched ClientConfigurationPacketListenerImpl.handleCustomPayload answers the server's
#     minecraft:register itself and RETURNS, never reaching the superclass where Fabric's dispatch hook lives — so the
#     client declared NeoForge's channels only, and Fabric's ServerConfigurationNetworkAddon treats the FIRST register
#     as the complete declaration (SENT -> RECEIVED -> startConfiguration -> configureClient -> canSend false -> kick).
#     Fix: CommonNetworkInteropInjector splices the super call in BEFORE NeoForge's reply, so Fabric declares first.
#  2. The server could not ENCODE neoforge:recipe_content (ClassCastException to DiscardedPayload): NeoForge's own
#     payload types had never registered on ANY dedicated server the kernel booted — NetworkRegistry.setup() fans
#     RegisterPayloadHandlersEvent out over ModList, and the baseline container was only put there on the client.
#     Fix: publishModBusDelivery runs on both sides; the setup line now reports what actually registered.
#  3. ClientNetworkRegistry.setup() ran on the server too and, once NeoForge's payloads existed, correctly failed
#     "missing client-side handlers". Fix: client-only, as genuine NeoForge has it.
#  4. The client died applying NeoForge's frozen-registry snapshot: NullPointerException "holder is null" in
#     MappedRegistry.registerIdMapping. Seventeen builtin registries are MinecraftForge NamespacedWrappers whose
#     inherited MappedRegistry fields stay empty; NeoForge remaps through exactly those fields. Fix:
#     RegistrySyncParityInjector gives the wrapper NeoForge's clear/registerIdMapping contract and applies the staged
#     ids through Forge's own GameData.injectSnapshot (KernelForgeWrapperSync).
#
# Each is pinned below by an assertion that fails on the log line it used to produce. Known gap, documented in
# KernelForgeWrapperSync: fabric-api's own remap is a no-op on those seventeen wrappers, so a Forbric client on a
# PURE Fabric server does not get them remapped — both ends Forbric is what this gate exercises.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

PORT="${M12_PORT:-25599}"
SRV="$KERNEL/run/mp-server"
CLI="$KERNEL/run/mp-client"
SLOG="$BUILD/gate-m12-server.log"
CLOG="$BUILD/gate-m12-client.log"
mkdir -p "$BUILD"

DL="$RUN_OLD/downloads"
# One mod per family, all side=BOTH, chosen so the sync has real work to do: fabric-api carries the registry-sync
# module under test, mcw-bridges registers 303 blocks/items (a registry payload worth syncing), and collective is
# a universal jar so multi-loader arbitration runs on both ends.
MODS=(
  "$DL/fabric-26.2/fabric-api-0.154.0+26.2.jar"
  "$DL/forge-26.2/mcw-bridges-3.1.2-mc26.2forge.jar"
  "$DL/forge-26.2/collective-26.2.0-8.39.jar"
)
# M12_MODS replaces that set wholesale (space-separated paths). The reason it exists: when this gate goes red the
# first question is always "is it the tri-ecosystem combination or the plumbing?", and the only way to answer it
# is to run the same gate with one ecosystem's jars and compare.
if [ -n "${M12_MODS:-}" ]; then read -r -a MODS <<< "$M12_MODS"; fi
for m in "${MODS[@]}"; do
  [ -f "$m" ] || { echo "[kernel] SKIP-FATAL: missing mod $m" >&2; exit 3; }
done

kernel_jar

step "stage the SAME mod set on both ends (port $PORT, not the default)"
reap_stale_server "$SRV"
rm -rf "$SRV" "$CLI"
mkdir -p "$SRV/mods" "$CLI/mods" "$CLI/quickPlay"
for m in "${MODS[@]}"; do cp "$m" "$SRV/mods/"; cp "$m" "$CLI/mods/"; done
printf 'eula=true\n' > "$SRV/eula.txt"
printf 'server-port=%s\nonline-mode=false\nlevel-name=MpWorld\nmax-tick-time=-1\nview-distance=6\nspawn-protection=0\nsync-chunk-writes=false\n' "$PORT" > "$SRV/server.properties"
# Without options.txt the accessibility onboarding screen sits in front of quick-play and nothing ever connects.
cp "$KERNEL/run/client-merged-pack/options.txt" "$CLI/options.txt" 2>/dev/null || printf 'version:4903\n' > "$CLI/options.txt"
echo "[kernel] staged: $(ls -1 "$SRV/mods" | paste -sd' ' -)"

step "boot the dedicated server and hold it open"
# A FIFO, not a pipe with a fixed sleep: the server must outlive the client by exactly as long as the client
# takes, which nothing knows in advance.
FIFO="$SRV/.stdin"; rm -f "$FIFO"; mkfifo "$FIFO"
# M12_EXTRA_JVM reaches BOTH ends. It did not at first, and that cost a diagnostic round: a probe was added to
# the server's networking path, the run produced zero lines, and "zero" was indistinguishable from "the code
# never ran" when the truth was that the flag had only ever been passed to the client. A knob that silently
# covers half the system under test is worse than no knob.
FORBRIC_JVM="${M12_EXTRA_JVM:-}" \
RUNDIR="$SRV" "$KERNEL/run/launch-kernel-server.sh" < "$FIFO" > "$SLOG" 2>&1 &
SRVPID=$!
record_server_pid "$SRV" "$SRVPID"
exec 9>"$FIFO"          # hold the write end open so the server does not see EOF
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
  step "M12 result"; echo "[kernel] ❌ M12 GATE RED — see $SLOG"; exit 1
fi

step "connect a real client to 127.0.0.1:$PORT"
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=127.0.0.1:$PORT -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140 ${M12_EXTRA_JVM:-}" \
RUNDIR="$CLI" "$KERNEL/run/launch-kernel-client.sh" \
  --quickPlayPath "$CLI/quickPlay/log.json" --quickPlayMultiplayer "127.0.0.1:$PORT" > "$CLOG" 2>&1 &
CLIENT_PID=$!
echo "[kernel] client pid=$CLIENT_PID (killed by pid only — another client may be running)"

CGAME="$CLI/logs/latest.log"
for i in $(seq 1 400); do
  kill -0 "$CLIENT_PID" 2>/dev/null || { echo "[kernel] client exited on its own after ~${i}s"; break; }
  # "Client disconnected with reason" is an outcome too: a kicked client sits on the disconnect screen for the
  # whole 400 s budget otherwise, and nothing that happens there is evidence.
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

step "the connection was a real socket, not the integrated server (must PASS)"
check "client dialled the address"   "Connecting to 127.0.0.1"                         "$CLOG"
check_absent "no integrated server"  "Starting integrated minecraft server"            "$CLOG"
check "server accepted the login"    "logged in with entity id"                        "$SLOG"
check "player joined on the server"  "joined the game"                                 "$SLOG"

step "the configuration phase and registry sync completed (must PASS)"
# This is the surface no other gate reaches: known-pack negotiation, then registry + tag sync over the wire.
check "client finished configuring"  "ClientSmoke\] joined world via quick-play"        "$CLOG"
check_absent "no registry mismatch"  "Registry (mismatch|remapping failed)|Received unknown|Missing registry"  "$CLOG"
check_absent "no unknown payload"    "Unknown custom packet|Unregistered payload|Payload may not be sent"      "$CLOG"
check_absent "no protocol error"     "Network Protocol Error|Incompatible client|Outdated (client|server)"     "$CLOG"
# The second bug this gate found: the server could not ENCODE neoforge:recipe_content, because NeoForge's own
# payload types had never registered on any dedicated server (its container was missing from ModList, so the
# registration event fanned out to mods only). Singleplayer never encodes, so no other gate could see it.
check_absent "every payload encodes"  "Failed to encode packet"                          "$SLOG"
check_absent "every payload decodes"  "Failed to decode packet|Failed decoding custom payload" "$CLOG"
check "NeoForge's own payloads registered on the server" "NeoForge's own included: yes" "$SLOG"
# The third: seventeen builtin registries are MinecraftForge wrappers, and NeoForge's registry sync remapped them
# through MappedRegistry fields the wrapper never fills — NPE, "Failed to sync registries from the server". The
# server's ids now reach them through Forge's own injectSnapshot; this line is that path reporting in.
check "Forge-wrapped registries followed the server's ids" "Forge-wrapped registr.* followed the server's ids" "$CLOG"
check_absent "client applied every registry sync" "Failed to sync registries|Failed to handle registry sync"  "$CLOG"

step "it played and left cleanly (must PASS)"
check "survived real simulation"     "ClientSmoke\] client-ready after"                 "$CLOG"
check "left cleanly"                 "ClientSmoke\] clean disconnect observed"          "$CLOG"
check "server saw the disconnect"    "lost connection|left the game"                    "$SLOG"
# The line above is satisfied by a KICK as well as by a goodbye, so it cannot stand alone. This is the one that
# actually says the handshake succeeded — and it is the one currently RED (see the header).
check_absent "server did not reject the client" "This server requires|Incompatible|Connection closed - mismatched" "$SLOG"

step "neither side broke (must be ABSENT)"
check_absent "no client crash"       "Preparing crash report"                           "$CLOG"
check_absent "no server crash"       "Preparing crash report"                           "$SLOG"
awk '/Done \(/{d=1} d' "$SLOG" > "$BUILD/gate-m12-postdone.log"
check_absent "no post-Done exception" "Encountered an unexpected exception"             "$BUILD/gate-m12-postdone.log"
check_absent "no thread leaked past main" "Client shutdown from post-main"              "$CLOG"

step "M12 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M12 GATE GREEN — a real client negotiated a real socket with a real dedicated server"
else
  echo "[kernel] ❌ M12 GATE RED — server $SLOG / client $CLOG"
fi
exit "$FAIL"
