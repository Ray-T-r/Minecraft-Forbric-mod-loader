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
#     MappedRegistry.registerIdMapping. On the merged base seventeen builtin registries were MinecraftForge
#     NamespacedWrappers whose inherited MappedRegistry fields stayed empty, and NeoForge remaps through exactly those
#     fields (fixed then by KernelForgeWrapperSync). Since Stage 4 they are NeoForge's own registries and the wrappers,
#     the sync and its check are gone; what is asserted is that the client took its pre-connection ids before the
#     server's replaced them (KernelRegistryRevert, which the disconnect needs) and that every sync applied.
#
# Each is pinned below by an assertion that fails on the log line it used to produce. Both ends Forbric is what this
# gate exercises; a Forbric client against a PURE Fabric server is gate-m14's job.
# Rundirs: M12_SERVER_RUNDIR / M12_CLIENT_RUNDIR (default run/mp-server, run/mp-client, which the gate wipes).
# GATE-PARALLEL: rundirs=mp-server,mp-client mem=3500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

PORT="${M12_PORT:-25599}"
SRV="${M12_SERVER_RUNDIR:-$KERNEL/run/mp-server}"
CLI="${M12_CLIENT_RUNDIR:-$KERNEL/run/mp-client}"
refuse_shared_rundir "$SRV"
refuse_shared_rundir "$CLI"
SLOG="$BUILD/gate-m12-server.log"
CLOG="$BUILD/gate-m12-client.log"
mkdir -p "$BUILD"

DL="$RUN_OLD/downloads"
# One mod per family, all side=BOTH, chosen so the sync has real work to do: fabric-api carries the registry-sync
# module under test, mcw-bridges registers 303 blocks/items (a registry payload worth syncing), and collective is
# a universal jar so multi-loader arbitration runs on both ends. Since Stage 3 of dropping MinecraftForge mcw-bridges
# is its NeoForge build, same version ($NEO_BUILDS): the MinecraftForge build is refused, and a refusal stops the
# dedicated server. sophisticatedcore (NeoForge) registers a particle type (jukebox_note), which puts a modded entry in
# minecraft:particle_type, so fabric-registry-sync's client remap runs over that registry on the join and again on the
# disconnect. Without one no remap reaches the particle registry and this gate cannot see what it does to the particle
# provider map: with fabric-registry-sync's Int2ObjectMapTracker attached to the kernel's view of NeoForge's
# Identifier-keyed providers, the remap threw from the view's clear() and no client joined a server whose mods register
# particle types (Stage 4 lane K2; -Dforbric.particleViewDetach=off puts that back).
MODS=(
  "$DL/fabric-26.2/fabric-api-0.154.0+26.2.jar"
  "$NEO_BUILDS/mcw-bridges-3.1.2-mc26.2neoforge.jar"
  "$DL/forge-26.2/collective-26.2.0-8.39.jar"
  "$KERNEL/run/client-merged-pack/mods/sophisticatedcore-26.2-1.5.0.2337.jar"
)
# M12_MODS replaces that set wholesale (space-separated paths). The reason it exists: when this gate goes red the
# first question is always "is it the Fabric + NeoForge combination or the plumbing?", and the only way to answer it
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
# The fallback needs onboardAccessibility, not just version. Without it the accessibility onboarding screen
# sits in front of quick-play and waits for a human to click Continue — which is the very thing copying
# options.txt is here to prevent, so a fallback that omits it hands the gate exactly the failure it was written
# to avoid. It only shows up where the merged pack is absent, e.g. a second worktree, which is why it survived.
cp "$KERNEL/run/client-merged-pack/options.txt" "$CLI/options.txt" 2>/dev/null \
  || printf 'version:4903\nonboardAccessibility:false\n' > "$CLI/options.txt"
echo "[kernel] staged: $(ls -1 "$SRV/mods" | paste -sd' ' -)"

step "boot the dedicated server and hold it open"
# A FIFO, not a pipe with a fixed sleep: the server must outlive the client by exactly as long as the client
# takes, which nothing knows in advance.
FIFO="$SRV/.stdin"; rm -f "$FIFO"; mkfifo "$FIFO"
# The PLAY-channel check below had a switch of its own (-Dforbric.fabricPlayChannels, now retired): the kernel no
# longer records those channels itself, Fabric's own c:register receiver does, on the payload as Fabric reads it.
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
  port_was_free "$SLOG"   # a server that lost its port never reaches Done either
  step "M12 result"; echo "[kernel] ❌ M12 GATE RED — see $SLOG"; exit 1
fi

# Block-state ids over the wire (Stage 4 C5 lane K1). Before anyone joins, the server places a vanilla block, and a
# NeoForge mod's block when mcw-bridges is in the set (the default's), at world spawn, so the client receives them in
# CHUNK data, numbered by the server's blockstate->id map and decoded by the client's -- a block_update after the join
# would hide nothing a chunk shows. A dedicated server with fabric-api numbered the states its registration window
# registered twice (fabric-registry-sync's tracker as each block was registered, then NeoForge's bake of the whole
# registry after them), so every id it sent was its native id plus that count: the pier went out as 35212 against the
# client's 34392 states and the client died on "No value with id"; a vanilla block decoded, silently, as a different
# one. That is why the vanilla read-back is asked of every M12_MODS set, with or without a mod block in it.
PROBE_NEO=""; PROBE_VANILLA="minecraft:diamond_block"; PROBE_AT="2,300,0"; PROBE_PLACED=1
case " ${MODS[*]} " in *mcw-bridges*) PROBE_NEO="mcwbridges:acacia_bridge_pier"; PROBE_AT="0,300,0;2,300,0"; PROBE_PLACED=2 ;; esac
# The spawn chunk is loaded for the placement only (forceload), and let go again, so the session ticks what it did.
echo "setworldspawn 0 100 0" >&9; echo "forceload add -1 -1 1 1" >&9
for i in $(seq 1 30); do grep -aq 'Marked .* to be force loaded\|already marked' "$SLOG" && break; sleep 0.5; done
for i in $(seq 1 10); do
  [ "$(grep -ac 'Changed the block' "$SLOG")" -ge "$PROBE_PLACED" ] && break
  [ -n "$PROBE_NEO" ] && echo "setblock 0 300 0 $PROBE_NEO" >&9
  echo "setblock 2 300 0 $PROBE_VANILLA" >&9; sleep 1
done
echo "forceload remove -1 -1 1 1" >&9
PROBE_JVM="-Dforbric.clientSmokeProbe=$PROBE_AT -Dforbric.clientSmokeProbeTicks=100"

step "connect a real client to 127.0.0.1:$PORT"
FORBRIC_JVM="-Dforbric.clientSmoke=true -Dforbric.clientSmokeWorld=127.0.0.1:$PORT -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140 $PROBE_JVM ${M12_EXTRA_JVM:-}" \
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
stop_tree "$CLIENT_PID"   # the whole tree: lib.sh says why children-only orphans a JVM
wait "$CLIENT_PID" 2>/dev/null
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
# The tree's only performance assertion. Safe to make here and nowhere generic: the login check one line up
# is the denominator — vanilla pauses an empty server, so "no overload warnings" means nothing without it.
check_kept_up "server kept up while the player was on" "$SLOG"

step "the configuration phase and registry sync completed (must PASS)"
# This is the surface no other gate reaches: known-pack negotiation, then registry + tag sync over the wire.
check "client finished configuring"  "ClientSmoke\] joined world via quick-play"        "$CLOG"
check_absent "no registry mismatch"  "Registry (mismatch|remapping failed)|Received unknown|Missing registry"  "$CLOG"
# The particle registry was in that sync (a mod registered a particle type, the denominator for the line above), and the
# particle provider map came through it as NeoForge keeps it: by name, with no int-keyed tracker re-keying the kernel's
# view of it (the absence above is the remap itself not failing). Asked of the default set only: an M12_MODS set chosen
# to split one ecosystem from the other need not carry a particle type, and that is not what such a run is asking.
if [ -z "${M12_MODS:-}" ]; then
  check "a mod registered a particle type, so the remap reaches the particle registry" \
    "Forbric/Lifecycle\] registered content: .*particle_type=[1-9]" "$CLOG"
  check "the remap tracker is not attached to the particle provider view" \
    "Forbric/Particles\] fabric-registry-sync's remap tracker for ParticleEngine.providers is not attached" "$CLOG"
fi
check_absent "no unknown payload"    "Unknown custom packet|Unregistered payload|Payload may not be sent"      "$CLOG"
check_absent "no protocol error"     "Network Protocol Error|Incompatible client|Outdated (client|server)"     "$CLOG"
# The second bug this gate found: the server could not ENCODE neoforge:recipe_content, because NeoForge's own
# payload types had never registered on any dedicated server (its container was missing from ModList, so the
# registration event fanned out to mods only). Singleplayer never encodes, so no other gate could see it.
check_absent "every payload encodes"  "Failed to encode packet"                          "$SLOG"
check_absent "every payload decodes"  "Failed to decode packet|Failed decoding custom payload" "$CLOG"
check "NeoForge's own payloads registered on the server" "NeoForge's own included: yes" "$SLOG"
# The fourth (the header's): NeoForge's registry sync applies the server's ids on the client. The kernel takes the
# client's own ids first, because it owns the freeze and NeoForge's snapshot of them is never taken, so the disconnect
# can put them back; the capture line is that path reporting in, and the absence is the sync itself not failing.
# (The check that stood here read "Forge-wrapped registries followed the server's ids", KernelForgeWrapperSync's line,
# deleted with the MinecraftForge wrappers in Stage 4.)
check "the client's pre-connection ids were captured before the sync" \
  "Forbric/RegistrySync\] captured the pre-connection ids of [1-9][0-9]* synced registr\(ies\)" "$CLOG"
check_absent "client applied every registry sync" "Failed to sync registries|Failed to handle registry sync"  "$CLOG"

# Fabric declares a client's PLAY receivers during CONFIGURATION, through c:register — not through
# minecraft:register, which carries only the current phase's. When the connection's play-channel list stayed empty,
# ServerPlayNetworking.canSend answered false for every Fabric PLAY channel, for the whole session, and Cardinal
# Components DISCONNECTS rather than skipping: joining a world ended with "This server requires Apoli: Legacy and
# Cardinal Components API". Fabric's own c:register receiver now gets that payload, as Fabric reads it, and records
# them; this line is the kernel handing it over with the PLAY channels in it.
check "the client's Fabric PLAY channels are recorded on the connection" \
  "Forbric/Net\] handed [1-9][0-9]* Fabric PLAY channel\(s\) the client declared during configuration to Fabric.s own c:register receiver" "$SLOG"

# The channel census, in the two-sentence shape the other censuses use: it ran (a denominator), and what it
# found. The set it counts is the one that kicked a player out of a world a second after joining — a payload
# type with no channel declared behind it, which the peer answers by disconnecting rather than skipping.
# "not judged" is in the line on purpose: this census watches Fabric's declaration path and not NeoForge's
# out-of-band one, and the first live run announced nine of NeoForge's own channels as undeclared on a
# connection that negotiated perfectly.
check "the channel census ran" "channel census: registered \{" "$SLOG"
check "no channel has a payload type and nothing declaring it" "registered-but-never-declared: 0" "$SLOG"

# Partially applied guest mixins: the state MixinFit's own javadoc calls worse than either extreme, because the
# mod keeps the handlers that bound and loses the rest with no error at either end. Each one has always been
# logged on its own line — ninety of them on a client boot of a THREE-mod set — and nothing totalled them, so
# nothing could notice the number moving.
#
# A ceiling, not an equality: what is healthy depends on the mod set, and this number is supposed to go DOWN.
check "the partial-application census ran" "guest mixin\(s\) apply only partially on this base" "$SLOG"
PARTIAL_MIXINS="$(grep -aoE 'Forbric/Mixin\] [0-9]+ guest mixin\(s\) apply only partially' "$SLOG" | grep -oE '[0-9]+' | tail -1)"
if [ -n "${PARTIAL_MIXINS:-}" ] && [ "$PARTIAL_MIXINS" -le "${M12_PARTIAL_CEILING:-30}" ]; then
  echo "[kernel] PASS partially applied guest mixins within the ceiling ($PARTIAL_MIXINS <= ${M12_PARTIAL_CEILING:-30})"
else
  echo "[kernel] FAIL partially applied guest mixins: ${PARTIAL_MIXINS:-none found} (ceiling ${M12_PARTIAL_CEILING:-30})"
  FAIL=1
fi

# Tick times. The only performance number this loader has: nothing in the tree measured tick time, frame time,
# TPS or memory, so a mod whose whole value is a number proved nothing here by loading, and a report that the
# loader is slow had nothing to agree or disagree with.
#
# The MEAN is not the assertion — a server keeping up holds 50ms exactly, by sleeping off what the tick did not
# use. The tail is: an interval at twice the budget means there was no sleep left to give back. (An older note here
# gave a live baseline of 1 in 1800. C5 wave 2 measured 21, 10, 4, 16, 3 and 16 over the sampler's 700 ticks, six runs
# alone on NeoForge's base, with 0 in the first 100 ticks in the three runs whose server log was kept. Ticks 100-700 hold
# the client's boot and its join, and the sampler cannot separate the two. The ceiling of 20 sits inside that spread;
# whether to count after the join or move the ceiling is open.)
check "the tick sampler reported" "Forbric/Tick\] [0-9]+ tick\(s\): mean " "$SLOG"
LATE_TICKS="$(grep -aoE 'at twice the budget or worse [0-9]+' "$SLOG" | grep -oE '[0-9]+' | tail -1)"
if [ -n "${LATE_TICKS:-}" ] && [ "$LATE_TICKS" -le "${M12_LATE_CEILING:-20}" ]; then
  echo "[kernel] PASS server ticks within the lateness ceiling ($LATE_TICKS <= ${M12_LATE_CEILING:-20})"
else
  echo "[kernel] FAIL server ticks late: ${LATE_TICKS:-none found} at twice the budget (ceiling ${M12_LATE_CEILING:-20})"
  FAIL=1
fi
check_absent "nobody was told the server requires a mod they have" \
  "This server requires" "$SLOG"

step "each end numbers every block state once, and the blocks placed before the join decode as themselves (must PASS)"
# RED, measured 2026-11-11 (c5/k1/gates): 28 PASS / 9 FAIL at the stage tip 7aaebbec, before lane K1's fix, and the same
# 28 / 9 with only the fix's clear taken out of KernelLifecycle.closeRegistrationWindow: the client dies on the chunk
# ("No value with id 35212", Network Protocol Error), so the read-back and the protocol, play-on, clean-leave, watcher and
# crash checks go red; with the fix 37 / 0. The two "numbered once" lines are the kernel's own check after each freeze it
# drives (KernelLifecycle.verifyBlockStateIds, K1's review A-3): they hold for every M12_MODS set, block in view or not,
# and a server that numbered twice prints a CAUGHT "is not one id per block state" instead (red control in
# c5/k1/review-fixes).
check "the server numbered every block state once" \
  "Forbric/Lifecycle\] block-state ids after the registration window freeze: [0-9]+ states, each numbered once" "$SLOG"
check "the client numbered every block state once" \
  "Forbric/Lifecycle\] block-state ids after the client entrypoint window freeze: [0-9]+ states, each numbered once" "$CLOG"
check_absent "no blockstate->id map off by a numbering on the server" "blockstate->id map is not one id per block state" "$SLOG"
check_absent "no blockstate->id map off by a numbering on the client" "blockstate->id map is not one id per block state" "$CLOG"
check "the server placed the blocks before the join" "Changed the block" "$SLOG" "$PROBE_PLACED"
check "the client read them back" "ClientSmoke\] block at \([02] 300 0\)" "$CLOG" "$PROBE_PLACED"
check_absent "no block-state id the client cannot map" "No value with id" "$CLOG"
if [ -n "$PROBE_NEO" ]; then
  check "the NeoForge mod's block reads back as itself" "block at \(0 300 0\) is $PROBE_NEO " "$CLOG"
else
  echo "[kernel] (no mcw-bridges in this M12_MODS set: the vanilla block is read back alone)"
fi
check "the vanilla control reads back as itself" "block at \(2 300 0\) is $PROBE_VANILLA " "$CLOG"

step "it played and left cleanly (must PASS)"
check "survived real simulation"     "ClientSmoke\] client-ready after"                 "$CLOG"
check "left cleanly"                 "ClientSmoke\] clean disconnect observed"          "$CLOG"
check "the client stopped its config file-watchers at close" "Forbric/Shutdown\\] stopped [1-9][0-9]* config file-watcher" "$CLOG"
check "the server stopped its config file-watchers at exit" "Forbric/Shutdown\\] stopped [1-9][0-9]* config file-watcher" "$SLOG"
check "server saw the disconnect"    "lost connection|left the game"                    "$SLOG"
# The line above is satisfied by a KICK as well as by a goodbye, so it cannot stand alone. This is the one that
# actually says the handshake succeeded — and it is the one currently RED (see the header).
# The last argument: the kernel narrates the bugs it repairs, and one of those explanations contains
# "IncompatibleClassChangeError". Its own success message was matching its own failure pattern.
check_absent "server did not reject the client" "This server requires|Incompatible|Connection closed - mismatched" "$SLOG" '\[Forbric/'

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
