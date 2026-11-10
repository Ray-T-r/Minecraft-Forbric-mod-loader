#!/usr/bin/env bash
# M58 — a world a Forbric from before MinecraftForge's removal wrote still opens, whole, on this one.
#
# WHY THIS EXISTS. Every other gate writes a world with this tree's kernel and reads it back with the same kernel. A
# player's world was written by the old line instead (v0.3.1-beta2 and earlier, and the 0.4.0-dev builds after it): the
# merged base (vanilla + MinecraftForge + NeoForge byte-merged) with two carriers. This tree runs NeoForge's own patched
# game with one carrier. Whether those worlds survive the upgrade can only be asked of a world the OLD kernel really
# wrote, so this gate builds the kernel at tag tri-eco-26.2-final (run/old-kernel.sh, U1), lets it write saves, and opens
# them here. What survives is judged on the files (run/compat/world-snapshot.py, schema forbric-world-snapshot/1, U2),
# not on what a running game says about them, plus what the mods themselves read back through their own APIs.
#
# WHAT IT ASKS, phase by phase (M58_PHASES picks a subset; the default is all of them, in this order):
#   main       the old server writes the main save (canary/world-upgrade/world.commands: vanilla block entities and
#              state-bearing blocks, a chest of component-carrying items, entities, scoreboard/storage/bossbar, real
#              NeoForge mods' blocks and items (D53), and both world-upgrade canaries' attachments, component types and
#              SavedData), and an old client joins over a socket (player.commands, the canaries' player values) -> S2.
#              The new server opens a copy with a new client joining -> S3; and once more -> S4.
#              S2 -> S3 under the `upgrade` rules and S3 -> S4 under `reopen` must show 0 differences; every name S2
#              refers to must be in the new registries (census); each canary reads back every value as written; the
#              first new boot logs exactly one "Missing data pack" line, for the MinecraftForge carrier, and the second
#              none; no other load error names anything.
#   sp         the same save as a singleplayer world: an old client opens it (its own player is written there), then a
#              new client twice -> S2c, S3c, S4c, the same rules.
#   aa         reverse control C3, the A/A: the OLD kernel reopens a copy of S2 with an old client joining -> S2aa, and
#              S2 -> S2aa must be 0 under `reopen` once the merged-only keys are set aside on both sides. Anything the
#              rules would have to hide here is the old line's own noise, not the upgrade's.
#   bb         the B/B, its counterpart on this tree: the NEW kernel writes the same save (world and player scripts, a new
#              client over the socket) and reopens it -> S2n, S3n, 0 under `reopen`. A check red here as well as in main is
#              this tree's own, not the upgrade's.
#   transfer   gate-m33's machines: `prepare` on the old kernel, `reload` on the new one (40 items, 32,417 Fabric fluid
#              units, both families' block entities deserialized).
#   energy     gate-m40's cells the same way (Team Reborn Energy and NeoForge, 40,000 E).
#   forge      a save with MinecraftForge-only content (mcw-bridges' MinecraftForge build, which this tree refuses): the
#              old server writes it, the new one opens it under the continue policy. Everything except mcwbridges must
#              survive (--ignore-namespace mcwbridges -> 0), and the unfiltered diff must be non-empty with every
#              difference naming mcwbridges (reverse control C5); every load error the new boot logs names mcwbridges.
#   installed  F1 / D43: an installed profile runs the carriers under their library file names, so the old kernel is
#              booted with neoforge-runtime-26.2.jar / forge-runtime-interop-26.2.jar and the new one with
#              neoforge-runtime-26.2.0.93.jar. The new boot must rename the old carrier pack id in place: no "Missing
#              data pack forbric/carrier/2-neoforge-runtime-26.2", the kernel's rename line, and the carrier still before
#              every mod pack, which mcw-bridges' data pack (after the carrier on the old side) makes a real
#              question: an "append the new id" stand-in is red on it. Red at the commit before D43 (a0eeba07), green
#              after it.
#   controls   reverse controls C1 (a mutated chest slot and a renamed palette entry on a copy of S2, one new boot: the
#              diff shows exactly those two and the log names the renamed block), C2 (one name dropped from the census:
#              the census goes red naming it) and C4 (no vacuous allowance: every merged-only rule an old save needs
#              fired on S2 -> S3). C3 is `aa`, C5 is part of `forge`. C4 reads phase main's S2 -> S3 diff and C2
#              its census, so each is red in a run without main.
#
# The old side runs the archived tag's own launchers on the tag's merged stage (run/old-kernel.sh); the new side runs this
# tree's launchers on FORBRIC_OLD's genuine stage. Same mod and canary jar bytes on both sides.
#
# EVIDENCE stays outside evidence.py (D53): $BUILD/verification/m58-world-upgrade/ holds every log, snapshot, diff
# (text and forbric-world-snapshot-diff/1 JSON) and m58-manifest.json (old kernel commit and jar sha1, old merged base
# and old carrier sha1s, new kernel jar, genuine base and carrier sha1, this gate's own sha1, canary, script and mod jar
# hashes for every phase that ran, snapshot hashes).
#
# SLOW: about twenty JVMs, old and new, server and client, one after another (never two game JVMs of one side at once
# except a server and the client joining it). Weekday nightlies skip it like the soak (tools/nightly, D53); the Stage 4
# tip run takes it. It starts clients: run it under .stage/client.sh, then .stage/heavy.sh --exclusive.
#
# Knobs: M58_RUNDIR (default run/world-upgrade; refused when it resolves into a shared fixture, D46), M58_RESULTS (the
# evidence directory, wiped at the start), M58_PHASES,
# M58_NEO_MODS (where the real NeoForge mods are read from, default run/client-merged-pack/mods, read only),
# M58_FORGE_BRIDGES (mcw-bridges' MinecraftForge build), FORBRIC_OLD_KERNEL_CACHE / FORBRIC_OLD_KERNEL_ONLINE (old-kernel.sh).
# GATE-PARALLEL: rundirs=world-upgrade mem=4000
set -uo pipefail
GATE_PORT="${GATE_PORT:-25658}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
. "$KERNEL/run/old-kernel.sh"

ROOT="${M58_RUNDIR:-$KERNEL/run/world-upgrade}"
refuse_shared_rundir "$ROOT"
mkdir -p "$ROOT"
ROOT="$(cd "$ROOT" && pwd -P)"
RESULTS="${M58_RESULTS:-$BUILD/verification/m58-world-upgrade}"
SNAP="$RESULTS/snapshots"
LOGS="$RESULTS/logs"
WS="$KERNEL/run/compat/world-snapshot.py"
SCRIPTS="$KERNEL/canary/world-upgrade"
CANARIES="$BUILD/m58-canary"
PHASES=" ${M58_PHASES:-main sp aa bb transfer energy forge installed controls} "
BOX="-2,-2,2,2"
rm -rf "$RESULTS"; mkdir -p "$SNAP" "$LOGS"
want() { case "$PHASES" in *" $1 "*) return 0;; esac; return 1; }

FAPI="${M58_FABRIC_API:-$KERNEL/.dev/api/fabric-api-0.155.2+26.2.jar}"
REBORN="${M40_REBORN_ENERGY:-$KERNEL/.dev/api/energy-5.0.0.jar}"
NEO_MODS="${M58_NEO_MODS:-$KERNEL/run/client-merged-pack/mods}"
FORGE_BRIDGES="${M58_FORGE_BRIDGES:-$RUN_OLD/downloads/forge-26.2/mcw-bridges-3.1.2-mc26.2forge.jar}"
# Real NeoForge mods (D53): small, each in the world through a population command that must succeed (world.commands /
# player.commands name them). mcw-bridges is the NeoForge build the other gates use; the rest come from the merged pack.
# waystones and sophisticatedbackpacks were held out of phase main (D74, a singleplayer phase `held`) while a new client
# died joining a server with them: on fabric-registry-sync's particle remap, which sophisticatedcore's particle type reaches
# (lane K2; balm, shogi and waystones register none, D80), and on the block-state ids of a dedicated server with
# fabric-api (lane K1). Both are fixed, so they are back in main and phase held is gone.
REAL_MODS=(
  "$NEO_BUILDS/mcw-bridges-3.1.2-mc26.2neoforge.jar"
  "$NEO_MODS/mcw-furniture-3.4.2-mc26.2neoforge.jar"
  "$NEO_MODS/ToolBelt-26.2-2.10.0.jar"
  "$NEO_MODS/corpse-neoforge-1.1.17+26.2.jar"
  "$NEO_MODS/waystones-neoforge-26.2-26.2.0.5.jar"
  "$NEO_MODS/sophisticatedbackpacks-26.2-3.26.2.2154.jar"
)
# Their libraries: installed with them, but they put nothing in the world of their own, so write_checks does not ask
# each for a population line (in REAL_MODS each fails "no OK line of world.commands puts <mod> content in the world").
LIB_MODS=(
  "$NEO_MODS/balm-neoforge-26.2-26.2.0.4.jar"
  "$NEO_MODS/shogi-neoforge-26.2-26.2.0.3.jar"
  "$NEO_MODS/sophisticatedcore-26.2-1.5.0.2337.jar"
)
for m in "$FAPI" "${REAL_MODS[@]}" "${LIB_MODS[@]}"; do
  [ -f "$m" ] || { echo "[kernel] SKIP-FATAL: missing mod $m" >&2; exit 3; }
done
if want forge && [ ! -f "$FORGE_BRIDGES" ]; then echo "[kernel] SKIP-FATAL: missing mcw-bridges' MinecraftForge build $FORGE_BRIDGES" >&2; exit 3; fi
if want energy && [ ! -f "$REBORN" ]; then echo "[kernel] SKIP-FATAL: missing Team Reborn Energy $REBORN (M40_REBORN_ENERGY)" >&2; exit 3; fi

step "resolve the old kernel (tag tri-eco-26.2-final) and build both kernels and the canaries"
old_kernel_init > "$LOGS/old-kernel-init.log" 2>&1 || { rc=$?; cat "$LOGS/old-kernel-init.log"; exit "$rc"; }
cat "$LOGS/old-kernel-init.log"
kernel_jar
WORLD_UPGRADE_CANARY_OUT="$CANARIES" M58_FABRIC_API="$FAPI" bash "$KERNEL/run/build-world-upgrade-canary.sh" \
  > "$LOGS/canary-build.log" 2>&1 || { cat "$LOGS/canary-build.log"; exit 1; }
tail -n 1 "$LOGS/canary-build.log"
UPGRADE_JARS=("$CANARIES/forbricupgradefabric.jar" "$CANARIES/forbricupgradeneo.jar")
OPTIONS_SEED="$KERNEL/run/client-merged-pack/options.txt"

# --- rundirs ----------------------------------------------------------------------------------------------------------

# fresh_dir <dir> <mods...> — wipe and recreate a rundir this gate owns, with exactly these mods and an ownership token.
fresh_dir() {
  local dir="$1"; shift
  case "$dir" in "$ROOT"/*) ;; *) echo "[kernel] FAIL refusing to wipe $dir: not under $ROOT"; exit 3;; esac
  reap_stale_server "$dir"
  rm -rf "$dir"; mkdir -p "$dir/mods"
  local m; for m in "$@"; do cp -c "$m" "$dir/mods/" 2>/dev/null || cp "$m" "$dir/mods/"; done
  echo "eula=true" > "$dir/eula.txt"
  # One port (the scheduler's GATE_PORT), a flat void-free world, no structures, nobody kicked for idling or a long tick.
  printf 'level-seed=forbrickernel\nserver-port=%s\nonline-mode=false\nlevel-name=world\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\nmax-tick-time=-1\npause-when-empty-seconds=-1\nsync-chunk-writes=true\n' \
    "$GATE_PORT" > "$dir/server.properties"
  if [ -f "$OPTIONS_SEED" ]; then cp "$OPTIONS_SEED" "$dir/options.txt"
  else printf 'version:4903\nonboardAccessibility:false\n' > "$dir/options.txt"; fi
  printf 'pauseOnLostFocus:false\n' >> "$dir/options.txt"
}
new_token() { python3 -c 'import uuid; print(uuid.uuid4())' > "$1/.m58-owned"; cat "$1/.m58-owned"; }
# clone_world <src-world> <dst-world> — an APFS clone of a save (a real copy elsewhere); the source stays untouched.
clone_world() { rm -rf "$2"; mkdir -p "$(dirname "$2")"; cp -c -R "$1" "$2" 2>/dev/null || cp -R "$1" "$2"; rm -f "$2/session.lock"; }

# --- game JVMs --------------------------------------------------------------------------------------------------------

# canary_jvm <phase> <root> <world> <out> [script properties...] — the world-upgrade canaries' arming properties.
canary_jvm() {
  local phase="$1" root="$2" world="$3" out="$4"; shift 4
  echo "-Dforbric.worldUpgradePhase=$phase -Dforbric.worldUpgradeToken=$(new_token "$root") -Dforbric.worldUpgradeRoot=$root -Dforbric.worldUpgradeWorld=$world -Dforbric.worldUpgradeOutput=$out $*"
}

# start_server <side> <dir> <log> <jvm> [env assignments...] — a server on a FIFO stdin; sets SRV_PID, opens fd 9.
start_server() {
  local side="$1" dir="$2" log="$3" jvm="$4"; shift 4
  local fifo="$dir/.stdin"
  rm -f "$fifo"; mkfifo "$fifo"; : > "$log"
  # The extra assignments (an installed-style NEO_RT/FORGE_RT, a compatibility policy) reach this one launcher only:
  # the new launchers refuse MERGED/FORGE_RT, and old_launch strips MERGED, so nothing leaks from one side to the other.
  (
    [ $# -gt 0 ] && export "$@"
    export RUNDIR="$dir" FORBRIC_JVM="$jvm"
    if [ "$side" = old ]; then old_launch server; else "$KERNEL/run/launch-kernel-server.sh"; fi
  ) < "$fifo" > "$log" 2>&1 &
  SRV_PID=$!
  record_server_pid "$dir" "$SRV_PID"
  exec 9>"$fifo"
}

# wait_log <log> <seconds> <pattern> [gone-pattern] — 0 once the pattern is in the log, 1 on timeout or the gone pattern.
wait_log() {
  local log="$1" limit="$2" pat="$3" gone="${4:-Stopping server|Failed to start the minecraft server|Exception in thread \"main\"|kernel-launch\] FATAL|launch stopped}" i
  for i in $(seq 1 "$limit"); do
    grep -aqE "$pat" "$log" 2>/dev/null && return 0
    grep -aqE "$gone" "$log" 2>/dev/null && return 1
    [ -n "${SRV_PID:-}" ] && ! kill -0 "$SRV_PID" 2>/dev/null && { grep -aqE "$pat" "$log" 2>/dev/null; return $?; }
    sleep 1
  done
  return 1
}

stop_server() {
  local dir="$1" log="$2"
  # In a subshell: a server that already halted itself (the machine probes do) has closed its end, and the SIGPIPE
  # must end the subshell, not the gate.
  ( echo stop >&9 ) 2>/dev/null
  exec 9>&-
  await_server "$SRV_PID" "$log" 180 60
  rm -f "$dir/.stdin" "$(_pidfile "$dir")"
  SRV_PID=""
}

# run_client <side> <dir> <log> <mp|sp> [jvm...] — a client that joins (MP: this gate's server; SP: saves/UpgradeSP),
# stays ~10 s and disconnects (the SP world saves on the way out). Returns 0 on a clean disconnect. <log> gets the
# launcher's output; the game's own log (latest.log, which also carries System.out) is kept beside it as
# <log minus .log>.game.log, and that is the file checks read: the stdout log repeats every game line, so a count
# over the two together would see each line twice.
run_client() {
  local side="$1" dir="$2" log="$3" mode="$4"; shift 4
  local game="$dir/logs/latest.log" jvm pid i outcome=1 args
  rm -f "$game"; mkdir -p "$dir/quickPlay"
  jvm="-Dforbric.clientSmoke=true -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=${M58_DISCONNECT_TICKS:-200} $*"
  if [ "$mode" = mp ]; then
    jvm="$jvm -Dforbric.clientSmokeWorld=127.0.0.1:$GATE_PORT"
    args=(--quickPlayPath "$dir/quickPlay/log.json" --quickPlayMultiplayer "127.0.0.1:$GATE_PORT")
  else
    jvm="$jvm -Dforbric.clientSmokeWorld=UpgradeSP"
    args=(--quickPlayPath "$dir/quickPlay/log.json" --quickPlaySingleplayer UpgradeSP)
  fi
  : > "$log"
  (
    export RUNDIR="$dir" FORBRIC_JVM="$jvm"
    if [ "$side" = old ]; then old_launch client "${args[@]}"; else "$KERNEL/run/launch-kernel-client.sh" "${args[@]}"; fi
  ) < /dev/null > "$log" 2>&1 &
  pid=$!
  for i in $(seq 1 "${M58_CLIENT_LIMIT:-480}"); do
    kill -0 "$pid" 2>/dev/null || break
    if grep -aqE 'ClientSmoke\] clean disconnect observed' "$game" 2>/dev/null; then outcome=0; break; fi
    grep -aqE 'Game crashed|Mod Loading has failed|Network Protocol Error|Failed to connect|Client disconnected with reason' "$game" 2>/dev/null && break
    sleep 1
  done
  # The SP world is saved before the disconnect is observed; let the JVM leave on its own a little, then the whole tree.
  for i in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  stop_tree "$pid" 5
  wait "$pid" 2>/dev/null
  # A client that saw its clean disconnect stops by itself, and can be gone before the loop above looked again.
  grep -aqE 'ClientSmoke\] clean disconnect observed' "$game" 2>/dev/null && outcome=0
  cp "$game" "${log%.log}.game.log" 2>/dev/null || : > "${log%.log}.game.log"
  return "$outcome"
}

# --- checks -----------------------------------------------------------------------------------------------------------

export_snap() {  # export_snap <world> <name>
  if python3 "$WS" export "$1" --out "$SNAP/$2.json" --chunks "$BOX" --label "$2" > "$SNAP/$2.export.txt" 2>&1; then
    echo "[kernel] snapshot $2: $(tail -n 1 "$SNAP/$2.export.txt" | sed 's/^\[snapshot\] exported [^:]*: //')"
  else
    echo "[kernel] FAIL could not export $2 ($SNAP/$2.export.txt)"; FAIL=1
  fi
}

# diff_zero <what> <a> <b> <rules> [ignore-namespace...] — world-snapshot.py diff must report 0 differences.
diff_zero() {
  local what="$1" a="$2" b="$3" rules="$4"; shift 4
  local name="$a-$b.$rules" extra=() ns got
  for ns in "$@"; do extra+=(--ignore-namespace "$ns"); name="$name.no-$ns"; done
  python3 "$WS" diff "$SNAP/$a.json" "$SNAP/$b.json" --rules "$rules" ${extra[@]+"${extra[@]}"} --list 80 --json "$SNAP/$name.diff.json" \
    > "$SNAP/$name.diff.txt" 2>&1
  got="$(grep -aE '^differences: [0-9]+$' "$SNAP/$name.diff.txt" | tail -n 1 | awk '{print $2}')"
  if [ "${got:-x}" = 0 ]; then
    echo "[kernel] PASS $what: $a -> $b under $rules rules, 0 differences ($(grep -aE '^rule ' "$SNAP/$name.diff.txt" | sed 's/^rule //' | paste -sd ';' - | sed 's/;/; /g'))"
  else
    echo "[kernel] FAIL $what: $a -> $b under $rules rules, ${got:-no} differences ($SNAP/$name.diff.txt):"
    grep -aE '^(diff |differences by facet)' "$SNAP/$name.diff.txt" | head -n 25 | sed 's/^/[kernel]   /'
    FAIL=1
  fi
}

# census_ok <snap> <read-dir> — every name the snapshot refers to is in the registries the new kernel's canaries listed.
census_ok() {
  local snap="$1" dir="$2" out="$SNAP/$1.census.txt"
  if python3 "$WS" census "$SNAP/$snap.json" "$dir/neo-read.json" "$dir/fabric-read.json" > "$out" 2>&1; then
    echo "[kernel] PASS every name $snap refers to is registered on the new kernel ($(head -n 1 "$out" | sed 's/^census: //'))"
  else
    echo "[kernel] FAIL names $snap refers to are missing on the new kernel ($out):"; grep -a 'census missing' "$out" | head -n 20 | sed 's/^/[kernel]   /'; FAIL=1
  fi
}

# canary_read <log> <read-dir> <player: yes|no> — both canaries read back every value as written (log line + JSON).
canary_read() {
  local log="$1" dir="$2" player="$3" who files="neo-read.json fabric-read.json" f
  for who in "neo world" "fabric world"; do check "the canary read back the $who values as written" "\[M58Upgrade\] PASS read $who:" "$log"; done
  if [ "$player" = yes ]; then
    for who in "neo player" "fabric player"; do check "the canary read back the $who values as written" "\[M58Upgrade\] PASS read $who:" "$log"; done
    files="$files neo-read-player.json fabric-read-player.json"
  fi
  check_absent "no canary read failed" "\[M58Upgrade\] FAIL" "$log"
  for f in $files; do
    if python3 - "$dir/$f" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8"))
sys.exit(0 if r.get("pass") is True and r.get("phase") == "read" and not r.get("wrong") else 1)
PY
    then echo "[kernel] PASS $f says every value read back as written"
    else echo "[kernel] FAIL $f is missing or reports a wrong value ($dir/$f)"; FAIL=1; fi
  done
}

# mod_namespace <jar> — the first modId a NeoForge/MinecraftForge jar declares: the namespace its content is named by.
mod_namespace() {
  unzip -p "$1" META-INF/neoforge.mods.toml META-INF/mods.toml 2>/dev/null \
    | sed -nE 's/^[[:space:]]*modId[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' | head -n 1
}

# write_checks <log> <who> <player: yes|no> <world-script|-> <player-script> [populated jars...] — the side that wrote
# this save (<who>: "old server", "new server", "old client") wrote everything it was asked to. Every jar listed must
# have at least one OK line of the world script naming its namespace (D53: a mod stays in the set only while its
# population command succeeds); a jar with none is red, not skipped (review w2 UPG A-1). WC_BOX_FORCED=yes: the save is a
# copy of S2, whose writer force-loaded the box, so the canary's forceload must fail with vanilla's "nothing changed" and
# only that way (phase held).
write_checks() {
  local log="$1" who="$2" player="$3" script="$4" pscript="$5" jar ns n except=""; shift 5
  if [ "$script" != - ] && [ "${WC_BOX_FORCED:-}" = yes ]; then
    check_exactly "the export box was force-loaded already (S2's tickets): the $who's forceload changed nothing" "\[M58Upgrade\] FAIL command \(canary\) forceload" "$log" 1
    check "vanilla said the forceload changed nothing" "No chunks were marked for force loading" "$log"
    except='\[M58Upgrade\] FAIL command \(canary\) forceload'
  fi
  if [ "$script" != - ]; then
    [ "${WC_BOX_FORCED:-}" = yes ] || check "the $who force-loaded the export box" "\[M58Upgrade\] OK command \(canary\) forceload" "$log"
    check "the world script $script ran with no failed line" "\[M58Upgrade\] ran [1-9][0-9]* command\(s\) from $script, 0 failed" "$log"
    for jar in "$@"; do
      ns="$(mod_namespace "$jar")"
      if [ -z "$ns" ]; then echo "[kernel] FAIL $(basename "$jar") declares no modId to check its population line by"; FAIL=1; continue; fi
      n=$(grep -acE "\[M58Upgrade\] OK command $script:[0-9]+ .*[ \[{\"]$ns:" "$log" 2>/dev/null || true)
      if [ "${n:-0}" -ge 1 ]; then echo "[kernel] PASS the $ns population commands of $script succeeded (D53) ($n)"
      else echo "[kernel] FAIL no OK line of $script puts $ns content in the world (D53: $(basename "$jar") stays only while one does)"; FAIL=1; fi
    done
  fi
  check "the NeoForge canary wrote its world values" "\[M58Upgrade\] wrote neo world values" "$log"
  check "the Fabric canary wrote its world values" "\[M58Upgrade\] wrote fabric world values" "$log"
  if [ "$player" = yes ]; then
    check "the player script $pscript ran with no failed line" "\[M58Upgrade\] ran [1-9][0-9]* command\(s\) from $pscript, 0 failed" "$log"
    check "the NeoForge canary wrote the player's values" "\[M58Upgrade\] wrote neo player values" "$log"
    check "the Fabric canary wrote the player's values" "\[M58Upgrade\] wrote fabric player values" "$log"
  fi
  check_absent "nothing the $who was asked to write failed" "\[M58Upgrade\] (FAIL|DISARMED)" "$log" "$except"
}

# The load errors a new boot of an upgraded save must not log (raw log; the MinecraftForge carrier's one missing pack is
# counted separately). except = the kernel narrating, and the canaries' own command lines.
LOAD_ERRORS='Unknown block type|Unknown registry key|Tried to load invalid item|Found new data pack|Recoverable errors when loading section|Skipping block entity|Skipping BlockEntity|Skipping Entity|Failed to load|Couldn.t load|version differences that were not resolved'
FORGE_CARRIER_MISSING='Missing data pack forbric/carrier/1-forge-runtime'
new_boot_checks() {  # new_boot_checks <log> <first: yes|no>
  local log="$1" first="$2"
  check_absent "no load error on the new boot" "$LOAD_ERRORS" "$log" '\[Forbric/|\[M58Upgrade\]'
  if [ "$first" = yes ]; then
    check_exactly "the first new boot lost exactly the MinecraftForge carrier's data pack" "$FORGE_CARRIER_MISSING" "$log" 1
  else
    check_exactly "a second new boot finds no data pack missing" "Missing data pack" "$log" 0
  fi
}

# missing_only_forge <log> — every "Missing data pack" line names MinecraftForge's carrier (check_absent needs a file).
missing_only_forge() {
  local log="$1" other
  other=$(grep -aE 'Missing data pack' "$log" | grep -avcE "$FORGE_CARRIER_MISSING" || true)
  if [ "${other:-0}" -eq 0 ]; then echo "[kernel] PASS no data pack other than MinecraftForge's carrier went missing"
  else echo "[kernel] FAIL $other other data pack(s) went missing:"; grep -aE 'Missing data pack' "$log" | grep -avE "$FORGE_CARRIER_MISSING" | head -5 | sed 's/^/[kernel]   /'; FAIL=1; fi
}

boot_checks() {  # boot_checks <log> <side>
  check "the $2 server reached Done" "Done \(" "$1"
  check "the $2 server saved and stopped" "All dimensions are saved" "$1"
  if [ "$2" = old ]; then check "the old launcher booted the tag's merged base" "merged base = .*/merged-base/patched-mc-merged-26\.2\.jar" "$1"
  else check "the new launcher booted NeoForge's patched game" "\[kernel-launch\] base = .*patched-mc-neoforge-26\.2\.jar" "$1"; fi
}

# server_round <side> <dir> <label> <phase> <client-dir|-> [canary script properties...] — one server boot, an optional
# client joining it over the socket, a clean stop. Logs: $LOGS/<label>.server.log and .client.log; canary JSON: $RESULTS/<label>.
server_round() {
  local side="$1" dir="$2" label="$3" phase="$4" cli="$5"; shift 5
  local log="$LOGS/$label.server.log" clog="$LOGS/$label.client.log" out="$RESULTS/$label" jvm settled
  mkdir -p "$out"
  jvm="$(canary_jvm "$phase" "$dir" "$dir/world" "$out" "$@")"
  start_server "$side" "$dir" "$log" "$jvm"
  if ! wait_log "$log" 480 'Done \('; then
    echo "[kernel] FAIL the $side server of $label never reached Done ($log)"; FAIL=1
    stop_server "$dir" "$log"; return 1
  fi
  if [ "$phase" = write ]; then settled='\[M58Upgrade\] (wrote|FAIL wrote) fabric world values'
  else settled='\[M58Upgrade\] (PASS|FAIL) read fabric world'; fi
  wait_log "$log" 120 "$settled" || echo "[kernel] FAIL $label: the canaries never reached their tick-${phase} line ($log)"
  wait_log "$log" 30 "$(echo "$settled" | sed 's/fabric/neo/')" >/dev/null
  if [ "$cli" != - ]; then
    if run_client "$side" "$cli" "$clog" mp; then echo "[kernel] PASS the $side client joined $label over the socket and left cleanly"
    else echo "[kernel] FAIL the $side client of $label did not join and leave cleanly ($clog)"; FAIL=1; fi
    wait_log "$log" 30 'left the game' >/dev/null
  fi
  stop_server "$dir" "$log"
  boot_checks "$log" "$side"
}

# sp_round <side> <dir> <label> <phase> [canary script properties...] — a client opens saves/UpgradeSP and saves it.
sp_round() {
  local side="$1" dir="$2" label="$3" phase="$4"; shift 4
  local log="$LOGS/$label.client.log" out="$RESULTS/$label" jvm
  mkdir -p "$out"
  jvm="$(canary_jvm "$phase" "$dir" "$dir/saves/UpgradeSP" "$out" "$@")"
  if run_client "$side" "$dir" "$log" sp $jvm; then echo "[kernel] PASS the $side client opened UpgradeSP ($label), saved it and left cleanly"
  else echo "[kernel] FAIL the $side client did not open and save UpgradeSP ($label; $log)"; FAIL=1; fi
}

MAIN_MODS=("$FAPI" "${UPGRADE_JARS[@]}" "${REAL_MODS[@]}" "${LIB_MODS[@]}")
WRITE_SCRIPTS=(-Dforbric.worldUpgradeCommands="$SCRIPTS/world.commands" -Dforbric.worldUpgradePlayerCommands="$SCRIPTS/player.commands")
PRISTINE="$ROOT/pristine"

# --- main save: server + client ---------------------------------------------------------------------------------------
if want main; then
  step "old kernel writes the main save: old server + an old client joining over the socket (S2)"
  fresh_dir "$ROOT/main-old" "${MAIN_MODS[@]}"
  fresh_dir "$ROOT/cli-old" "${MAIN_MODS[@]}"
  server_round old "$ROOT/main-old" s2-server write "$ROOT/cli-old" "${WRITE_SCRIPTS[@]}"
  write_checks "$LOGS/s2-server.server.log" "old server" yes world.commands player.commands "${REAL_MODS[@]}"
  rm -rf "$PRISTINE/main"; mkdir -p "$PRISTINE"; clone_world "$ROOT/main-old/world" "$PRISTINE/main"
  export_snap "$PRISTINE/main" S2

  step "new kernel opens the old save with a new client joining (S3), and once more (S4)"
  fresh_dir "$ROOT/main-new" "${MAIN_MODS[@]}"
  fresh_dir "$ROOT/cli-new" "${MAIN_MODS[@]}"
  clone_world "$PRISTINE/main" "$ROOT/main-new/world"
  server_round new "$ROOT/main-new" s3-server read "$ROOT/cli-new"
  canary_read "$LOGS/s3-server.server.log" "$RESULTS/s3-server" yes
  new_boot_checks "$LOGS/s3-server.server.log" yes
  missing_only_forge "$LOGS/s3-server.server.log"
  export_snap "$ROOT/main-new/world" S3
  diff_zero "the upgrade kept the save (server)" S2 S3 upgrade
  census_ok S2 "$RESULTS/s3-server"
  server_round new "$ROOT/main-new" s4-server read "$ROOT/cli-new"
  canary_read "$LOGS/s4-server.server.log" "$RESULTS/s4-server" yes
  new_boot_checks "$LOGS/s4-server.server.log" no
  export_snap "$ROOT/main-new/world" S4
  diff_zero "a second new open changed nothing (server)" S3 S4 reopen
fi

need_pristine() {
  [ -f "$PRISTINE/main/level.dat" ] && [ -f "$SNAP/S2.json" ] && return 0
  if [ -f "$PRISTINE/main/level.dat" ]; then export_snap "$PRISTINE/main" S2; return 0; fi
  echo "[kernel] FAIL phase $1 needs the old main save ($PRISTINE/main): run phase main first"; FAIL=1; return 1
}

# --- C4: no vacuous allowance -----------------------------------------------------------------------------------------
if want controls && ! want main; then
  echo "[kernel] FAIL reverse control C4 needs phase main's S2 -> S3 diff of this run (M58_PHASES has controls without main)"; FAIL=1
elif want controls && ! [ -f "$SNAP/S2-S3.upgrade.diff.json" ]; then
  echo "[kernel] FAIL reverse control C4 has no S2 -> S3 diff to read ($SNAP/S2-S3.upgrade.diff.json): phase main did not get that far"; FAIL=1
elif want controls && need_pristine controls; then
  step "reverse control C4: every merged-only allowance an old save needs fired on S2 -> S3, and none fired on S3 -> S4"
  python3 - "$SNAP/S2.json" "$SNAP/S2-S3.upgrade.diff.json" "$SNAP/S3-S4.reopen.diff.json" <<'PY' || FAIL=1
import json, re, sys
s2 = json.load(open(sys.argv[1], encoding="utf-8")); up = json.load(open(sys.argv[2], encoding="utf-8"))
re_ = json.load(open(sys.argv[3], encoding="utf-8")) if len(sys.argv) > 3 else {"rules_used": {}}
used = up["rules_used"]; bad = 0
def holders(key):
    n = 0
    for e in s2["entities"].values(): n += key in e["nbt"][1]
    for p in s2["players"].values(): n += bool(p) and key in p[1]
    for b in s2["block_entities"].values(): n += key in b[1]
    return n
for key in ("ForgeCaps", "ForgeData", "CanUpdate"):
    n = holders(key)
    fired = used.get("drop merged-only " + key, 0)
    if n and not fired:
        print("[kernel] FAIL S2 has %d holder(s) of %s but the upgrade rule for it never fired (a vacuous allowance)" % (n, key)); bad = 1
    else:
        print("[kernel] PASS %s: %d holder(s) in S2, the upgrade rule fired %d time(s)" % (key, n, fired))
enabled = [v for v in (s2.get("level") or ["compound", {}])[1].get("Data", ["compound", {}])[1].get("DataPacks", ["compound", {}])[1].get("Enabled", ["list", "string", []])[2]]
forge = [p for p in enabled if p.startswith("forbric/carrier/1-forge-runtime")]
fired = used.get("drop MinecraftForge carrier pack", 0)
if len(forge) != fired:
    print("[kernel] FAIL S2 enables %d MinecraftForge carrier pack(s) %s but the rule dropped %d" % (len(forge), forge, fired)); bad = 1
else:
    print("[kernel] PASS the MinecraftForge carrier rule dropped exactly the %d pack(s) S2 enables %s" % (fired, forge))
merged = sorted(k for k in re_.get("rules_used", {}) if k.startswith("drop merged-only") or k.startswith("drop MinecraftForge") or k == "forge SavedData gone")
if merged:
    print("[kernel] FAIL a reopen of an upgraded save still needed merged-only allowances: %s" % merged); bad = 1
else:
    print("[kernel] PASS a reopen of the upgraded save needed no merged-only allowance")
sys.exit(bad)
PY
fi

# --- singleplayer -----------------------------------------------------------------------------------------------------
if want sp && need_pristine sp; then
  step "the same save as a singleplayer world: an old client opens it (S2c), a new client twice (S3c, S4c)"
  fresh_dir "$ROOT/sp-old" "${MAIN_MODS[@]}"
  clone_world "$PRISTINE/main" "$ROOT/sp-old/saves/UpgradeSP"
  # Only the singleplayer host's own files: the socket player's file is the server arm's subject, and in singleplayer
  # nobody loads it, so it would reach S3c exactly as the old kernel wrote it (merged-only keys and all).
  rm -f "$ROOT/sp-old/saves/UpgradeSP"/players/data/*.dat "$ROOT/sp-old/saves/UpgradeSP"/players/advancements/*.json \
    "$ROOT/sp-old/saves/UpgradeSP"/players/stats/*.json
  sp_round old "$ROOT/sp-old" s2-sp write -Dforbric.worldUpgradePlayerCommands="$SCRIPTS/player.commands"
  write_checks "$LOGS/s2-sp.client.game.log" "old client" yes - player.commands
  rm -rf "$PRISTINE/sp"; clone_world "$ROOT/sp-old/saves/UpgradeSP" "$PRISTINE/sp"
  export_snap "$PRISTINE/sp" S2c
  fresh_dir "$ROOT/sp-new" "${MAIN_MODS[@]}"
  clone_world "$PRISTINE/sp" "$ROOT/sp-new/saves/UpgradeSP"
  sp_round new "$ROOT/sp-new" s3-sp read
  canary_read "$LOGS/s3-sp.client.game.log" "$RESULTS/s3-sp" yes
  new_boot_checks "$LOGS/s3-sp.client.game.log" yes
  missing_only_forge "$LOGS/s3-sp.client.game.log"
  export_snap "$ROOT/sp-new/saves/UpgradeSP" S3c
  diff_zero "the upgrade kept the save (singleplayer)" S2c S3c upgrade
  census_ok S2c "$RESULTS/s3-sp"
  sp_round new "$ROOT/sp-new" s4-sp read
  canary_read "$LOGS/s4-sp.client.game.log" "$RESULTS/s4-sp" yes
  new_boot_checks "$LOGS/s4-sp.client.game.log" no
  export_snap "$ROOT/sp-new/saves/UpgradeSP" S4c
  diff_zero "a second new open changed nothing (singleplayer)" S3c S4c reopen
fi

# --- C3: the A/A ------------------------------------------------------------------------------------------------------
if want aa && need_pristine aa; then
  step "reverse control C3 (A/A): the OLD kernel reopens a copy of S2 with an old client joining; nothing may differ"
  fresh_dir "$ROOT/aa-old" "${MAIN_MODS[@]}"
  fresh_dir "$ROOT/cli-aa" "${MAIN_MODS[@]}"
  clone_world "$PRISTINE/main" "$ROOT/aa-old/world"
  server_round old "$ROOT/aa-old" aa-server read "$ROOT/cli-aa"
  check "the old kernel read back the canaries' values (old -> old)" "\[M58Upgrade\] PASS read neo world:" "$LOGS/aa-server.server.log"
  export_snap "$ROOT/aa-old/world" S2aa
  # The old kernel writes the merged-only keys on both sides, which `reopen` flags as violations: set them aside on both
  # sides (counted) through world-snapshot.py's frozen import API, then diff under `reopen`.
  python3 - "$WS" "$SNAP/S2.json" "$SNAP/S2aa.json" "$SNAP/S2-S2aa.aa.diff.json" <<'PY' > "$SNAP/S2-S2aa.aa.diff.txt" 2>&1
import importlib.util, json, sys
spec = importlib.util.spec_from_file_location("world_snapshot", sys.argv[1]); ws = importlib.util.module_from_spec(spec); spec.loader.exec_module(ws)
a, b = (json.load(open(p, encoding="utf-8")) for p in sys.argv[2:4])
aside = {}
def strip(nbt, keys):
    if isinstance(nbt, list) and nbt and nbt[0] == "compound":
        for k in keys:
            if k in nbt[1]:
                nbt[1].pop(k); aside[k] = aside.get(k, 0) + 1
for s in (a, b):
    for e in s["entities"].values(): strip(e["nbt"], ws.MERGED_ENTITY_KEYS)
    for p in s["players"].values(): strip(p, ws.MERGED_ENTITY_KEYS)
    for be in s["block_entities"].values(): strip(be, ws.MERGED_BE_KEYS)
found, rules = ws.diff(a, b, "reopen")
json.dump(ws.diff_report(found, rules), open(sys.argv[4], "w", encoding="utf-8"), sort_keys=True)
print("set aside on both sides: %s" % (" ".join("%s=%d" % kv for kv in sorted(aside.items())) or "none"))
for name in sorted(rules.used): print("rule %s: %d" % (name, rules.used[name]))
short = lambda v: (json.dumps(v, sort_keys=True) if v is not None else "(absent)")[:160]
for f, p, x, y in found[:80]: print("diff %s %s: %s -> %s" % (f, p, short(x), short(y)))
print("differences: %d" % len(found))
PY
  got="$(grep -aE '^differences: [0-9]+$' "$SNAP/S2-S2aa.aa.diff.txt" | tail -n 1 | awk '{print $2}')"
  if [ "${got:-x}" = 0 ]; then echo "[kernel] PASS A/A: the old kernel reopening its own save changed nothing ($(head -n 1 "$SNAP/S2-S2aa.aa.diff.txt"))"
  else echo "[kernel] FAIL A/A: the old kernel reopening its own save shows ${got:-no} differences ($SNAP/S2-S2aa.aa.diff.txt):"
    grep -aE '^diff ' "$SNAP/S2-S2aa.aa.diff.txt" | head -n 25 | sed 's/^/[kernel]   /'; FAIL=1; fi
fi

# --- B/B: the new kernel against itself ---------------------------------------------------------------------------------
if want bb; then
  step "B/B: the NEW kernel writes the same save (server + new client) and reopens it; a failure here is not the upgrade's"
  fresh_dir "$ROOT/bb-new" "${MAIN_MODS[@]}"
  fresh_dir "$ROOT/cli-bb" "${MAIN_MODS[@]}"
  server_round new "$ROOT/bb-new" bb-write write "$ROOT/cli-bb" "${WRITE_SCRIPTS[@]}"
  write_checks "$LOGS/bb-write.server.log" "new server" yes world.commands player.commands "${REAL_MODS[@]}"
  export_snap "$ROOT/bb-new/world" S2n
  server_round new "$ROOT/bb-new" bb-read read "$ROOT/cli-bb"
  canary_read "$LOGS/bb-read.server.log" "$RESULTS/bb-read" yes
  export_snap "$ROOT/bb-new/world" S3n
  diff_zero "B/B: the new kernel reopening its own save changed nothing" S2n S3n reopen
fi

# --- machines: transfer and energy, prepared on the old kernel, reloaded on the new one -------------------------------
machine_round() {  # machine_round <transfer|energy>
  local kind="$1" prop owned mods tok_old tok_new pass
  if [ "$kind" = transfer ]; then prop=transferCanary; owned=.m33-owned; mods=("$FAPI" "$MACHINES/forbrictransferfabric.jar" "$MACHINES/forbrictransferneo.jar")
  else prop=energyCanary; owned=.energy-owned; mods=("$FAPI" "$MACHINES/forbrictransferfabric.jar" "$MACHINES/forbricenergyneo.jar" "$REBORN" "$MACHINES/forbricenergyfabric.jar"); fi
  local old="$ROOT/$kind-old" new="$ROOT/$kind-new" olog="$LOGS/$kind-prepare.server.log" nlog="$LOGS/$kind-reload.server.log"
  fresh_dir "$old" "${mods[@]}"
  python3 -c 'import uuid; print(uuid.uuid4())' > "$old/$owned"; tok_old="$(cat "$old/$owned")"
  start_server old "$old" "$olog" "-Dforbric.transferBridge=on -Dforbric.$prop""Phase=prepare -Dforbric.$prop""Token=$tok_old -Dforbric.$prop""Root=$old -Dforbric.$prop""Output=$RESULTS/$kind-prepare.json"
  # The probe halts the server itself when it is done; a stop on the console is only the fallback.
  wait_log "$olog" 600 'Stopping server|PASS phase=prepare|FAIL phase=prepare' "Failed to start the minecraft server|kernel-launch\] FATAL" >/dev/null
  stop_server "$old" "$olog"
  if python3 - "$RESULTS/$kind-prepare.json" "$tok_old" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8")); sys.exit(0 if r["pass"] is True and r["runToken"] == sys.argv[2] else 1)
PY
  then echo "[kernel] PASS the old kernel prepared the $kind world ($(tr -d '\n' < "$RESULTS/$kind-prepare.json" | cut -c1-200))"
  else echo "[kernel] FAIL the old kernel did not prepare the $kind world ($RESULTS/$kind-prepare.json, $olog)"; FAIL=1; return; fi
  fresh_dir "$new" "${mods[@]}"
  clone_world "$old/world" "$new/world"
  python3 -c 'import uuid; print(uuid.uuid4())' > "$new/$owned"; tok_new="$(cat "$new/$owned")"
  start_server new "$new" "$nlog" "-Dforbric.transferBridge=on -Dforbric.$prop""Phase=reload -Dforbric.$prop""Token=$tok_new -Dforbric.$prop""Root=$new -Dforbric.$prop""Output=$RESULTS/$kind-reload.json"
  wait_log "$nlog" 600 'Stopping server|PASS phase=reload|FAIL phase=reload' "Failed to start the minecraft server|kernel-launch\] FATAL" >/dev/null
  stop_server "$new" "$nlog"
  if [ "$kind" = transfer ]; then pass='r["items"] == 40 and r["fluidFabricUnits"] == 32417'; else pass='r["energy"] == 40000 and r["cells"] == 2'; fi
  if python3 - "$RESULTS/$kind-reload.json" "$tok_new" "$pass" <<'PY'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8")); sys.exit(0 if r["pass"] is True and r["runToken"] == sys.argv[2] and eval(sys.argv[3]) else 1)
PY
  then echo "[kernel] PASS the new kernel reloaded the old $kind world with every amount kept ($(tr -d '\n' < "$RESULTS/$kind-reload.json" | cut -c1-200))"
  else echo "[kernel] FAIL the new kernel's $kind reload of the old world failed ($RESULTS/$kind-reload.json, $nlog)"; FAIL=1; fi
  missing_only_forge "$nlog"
  check_absent "the $kind reload logged no load error" "$LOAD_ERRORS" "$nlog" '\[Forbric/|\[M3[0-9][A-Za-z]+\]|\[M40Energy\]'
}
if want transfer || want energy; then
  step "build gate-m33/m40's machine canaries (their own output dir)"
  MACHINES="$BUILD/m58-machines"
  TRANSFER_CANARY_ENERGY=1 TRANSFER_CANARY_OUT="$MACHINES" M33_FABRIC_API="$FAPI" M40_REBORN_ENERGY="$REBORN" \
    bash "$KERNEL/run/build-transfer-world-canaries.sh" > "$LOGS/machines-build.log" 2>&1 || { cat "$LOGS/machines-build.log"; FAIL=1; }
  tail -n 1 "$LOGS/machines-build.log"
  if want transfer; then step "transfer: gate-m33's machines prepared on the old kernel, reloaded on the new one"; machine_round transfer; fi
  if want energy; then step "energy: gate-m40's cells prepared on the old kernel, reloaded on the new one"; machine_round energy; fi
fi

# --- MinecraftForge-only content --------------------------------------------------------------------------------------
if want forge; then
  step "a save with MinecraftForge-only content: the old server writes it (S2f), the new one opens it under continue (S3f)"
  FORGE_MODS=("$FAPI" "${UPGRADE_JARS[@]}" "$FORGE_BRIDGES")
  fresh_dir "$ROOT/forge-old" "${FORGE_MODS[@]}"
  server_round old "$ROOT/forge-old" s2-forge write - -Dforbric.worldUpgradeCommands="$SCRIPTS/forge.commands"
  write_checks "$LOGS/s2-forge.server.log" "old server" no forge.commands - "$FORGE_BRIDGES"
  rm -rf "$PRISTINE/forge"; clone_world "$ROOT/forge-old/world" "$PRISTINE/forge"
  export_snap "$PRISTINE/forge" S2f
  fresh_dir "$ROOT/forge-new" "${FORGE_MODS[@]}"
  clone_world "$PRISTINE/forge" "$ROOT/forge-new/world"
  FLOG="$LOGS/s3-forge.server.log"
  jvm="$(canary_jvm read "$ROOT/forge-new" "$ROOT/forge-new/world" "$RESULTS/s3-forge")"
  start_server new "$ROOT/forge-new" "$FLOG" "$jvm" FORBRIC_COMPAT_POLICY=continue
  wait_log "$FLOG" 480 'Done \(' && wait_log "$FLOG" 120 '\[M58Upgrade\] (PASS|FAIL) read fabric world' >/dev/null
  stop_server "$ROOT/forge-new" "$FLOG"
  boot_checks "$FLOG" new
  check "the new kernel refused mcw-bridges' MinecraftForge build" "\[Forbric/Unsupported\] refusing mcw-bridges-3\.1\.2-mc26\.2forge\.jar \(mcwbridges\)" "$FLOG"
  check "the canaries read back their values beside the lost content" "\[M58Upgrade\] PASS read neo world:" "$FLOG"
  check "the canaries read back their values beside the lost content (Fabric)" "\[M58Upgrade\] PASS read fabric world:" "$FLOG"
  other=$(grep -aE "$LOAD_ERRORS|Missing data pack" "$FLOG" | grep -avE '\[Forbric/|\[M58Upgrade\]' | grep -avE "$FORGE_CARRIER_MISSING" | grep -avc mcwbridges || true)
  if [ "${other:-0}" -eq 0 ]; then echo "[kernel] PASS every load error and missing pack the new boot logged names mcwbridges (or MinecraftForge's carrier)"
  else echo "[kernel] FAIL $other load error line(s) name something other than mcwbridges:"; grep -aE "$LOAD_ERRORS|Missing data pack" "$FLOG" | grep -avE '\[Forbric/|\[M58Upgrade\]' | grep -avE "$FORGE_CARRIER_MISSING" | grep -av mcwbridges | head -5 | sed 's/^/[kernel]   /'; FAIL=1; fi
  check_exactly "the new boot lost exactly the MinecraftForge carrier's data pack (besides mcwbridges)" "$FORGE_CARRIER_MISSING" "$FLOG" 1
  export_snap "$ROOT/forge-new/world" S3f
  diff_zero "everything but mcwbridges survived the upgrade" S2f S3f upgrade mcwbridges
  # C5: without the filter the diff is not empty, and every difference names mcwbridges.
  python3 "$WS" diff "$SNAP/S2f.json" "$SNAP/S3f.json" --rules upgrade --list 0 --json "$SNAP/S2f-S3f.upgrade.diff.json" > "$SNAP/S2f-S3f.upgrade.diff.txt" 2>&1
  python3 - "$SNAP/S2f-S3f.upgrade.diff.json" <<'PY' || FAIL=1
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8"))
def names(v): return "mcwbridges" in json.dumps(v)
other = [d for d in r["differences"] if not (names(d["path"]) or names(d["a"]) or names(d["b"]))]
if r["count"] == 0:
    print("[kernel] FAIL reverse control C5: the unfiltered diff of the MinecraftForge-content save is empty (the content never reached the save, or the diff is blind)"); sys.exit(1)
if other:
    print("[kernel] FAIL reverse control C5: %d of %d differences do not name mcwbridges, e.g. %s" % (len(other), r["count"], json.dumps(other[:3])[:400])); sys.exit(1)
print("[kernel] PASS reverse control C5: without the filter %d differences, every one naming mcwbridges (%s)" % (r["count"], " ".join("%s=%d" % kv for kv in sorted(r["by_facet"].items()))))
PY
fi

# --- F1 / D43: installed carrier file names ---------------------------------------------------------------------------
if want installed; then
  step "installed file names (F1, D43): old carriers neoforge-runtime-26.2.jar + forge-runtime-interop-26.2.jar, new carrier neoforge-runtime-26.2.0.93.jar"
  JARS="$ROOT/installed-jars"; rm -rf "$JARS"; mkdir -p "$JARS"
  cp -c "$OLD_KERNEL_STAGE/run/neoforge-runtime/neoforge-runtime.jar" "$JARS/neoforge-runtime-26.2.jar" 2>/dev/null || cp "$OLD_KERNEL_STAGE/run/neoforge-runtime/neoforge-runtime.jar" "$JARS/neoforge-runtime-26.2.jar"
  cp -c "$OLD_KERNEL_STAGE/run/merged-base/forge-runtime-interop.jar" "$JARS/forge-runtime-interop-26.2.jar" 2>/dev/null || cp "$OLD_KERNEL_STAGE/run/merged-base/forge-runtime-interop.jar" "$JARS/forge-runtime-interop-26.2.jar"
  cp -c "$RUN_OLD/neoforge-runtime/neoforge-runtime.jar" "$JARS/neoforge-runtime-26.2.0.93.jar" 2>/dev/null || cp "$RUN_OLD/neoforge-runtime/neoforge-runtime.jar" "$JARS/neoforge-runtime-26.2.0.93.jar"
  # mcw-bridges (NeoForge build) gives both worlds a mod data pack after the carrier, so an in-place rename and a
  # "drop the old id, append the new one" give different lists (review w2 UPG A-3).
  INSTALLED_MODS=("$FAPI" "$NEO_BUILDS/mcw-bridges-3.1.2-mc26.2neoforge.jar")
  fresh_dir "$ROOT/installed-old" "${INSTALLED_MODS[@]}"
  ILOG="$LOGS/installed-old.server.log"; NLOG="$LOGS/installed-new.server.log"
  start_server old "$ROOT/installed-old" "$ILOG" "" NEO_RT="$JARS/neoforge-runtime-26.2.jar" FORGE_RT="$JARS/forge-runtime-interop-26.2.jar"
  wait_log "$ILOG" 480 'Done \(' && sleep 5
  stop_server "$ROOT/installed-old" "$ILOG"
  boot_checks "$ILOG" old
  check "the old launcher owned the carriers under their installed names" "owned carriers: forge-runtime-interop-26\.2\.jar, neoforge-runtime-26\.2\.jar" "$ILOG"
  export_snap "$ROOT/installed-old/world" S2i
  fresh_dir "$ROOT/installed-new" "${INSTALLED_MODS[@]}"
  clone_world "$ROOT/installed-old/world" "$ROOT/installed-new/world"
  start_server new "$ROOT/installed-new" "$NLOG" "" NEO_RT="$JARS/neoforge-runtime-26.2.0.93.jar"
  wait_log "$NLOG" 480 'Done \(' && sleep 5
  stop_server "$ROOT/installed-new" "$NLOG"
  boot_checks "$NLOG" new
  check "the new launcher owned the carrier under its installed name" "owned carrier: neoforge-runtime-26\.2\.0\.93\.jar" "$NLOG"
  export_snap "$ROOT/installed-new/world" S3i
  python3 - "$SNAP/S2i.json" "$SNAP/S3i.json" <<'PY' || FAIL=1
import json, sys
def enabled(p):
    s = json.load(open(p, encoding="utf-8"))
    return s["level"][1]["Data"][1]["DataPacks"][1]["Enabled"][2]
old, new = enabled(sys.argv[1]), enabled(sys.argv[2])
bad = 0
if "forbric/carrier/2-neoforge-runtime-26.2" not in old:
    print("[kernel] FAIL the old world does not name the installed carrier id forbric/carrier/2-neoforge-runtime-26.2 (the phase did not write what an installed profile writes): %s" % old); sys.exit(1)
print("[kernel] PASS the old world names the installed carrier id: %s" % old)
i = old.index("forbric/carrier/2-neoforge-runtime-26.2")
after = [p for p in old[i + 1:] if p.startswith("forbric/data/")]
if not after:
    print("[kernel] FAIL the old world has no mod data pack after the carrier, so an in-place rename and an append would look the same: %s" % old); sys.exit(1)
print("[kernel] PASS the old world has mod data pack(s) after the carrier, so the order tells a rename from an append: %s" % after)
stale = [p for p in new if p.startswith("forbric/carrier/2-neoforge-runtime-")]
if stale or "forbric/carrier/2-neoforge-runtime" not in new:
    print("[kernel] FAIL the new world does not enable the NeoForge carrier as forbric/carrier/2-neoforge-runtime only: %s" % new); bad = 1
else:
    j = new.index("forbric/carrier/2-neoforge-runtime")
    mods = [k for k, p in enumerate(new) if p.startswith("forbric/data/")]
    if mods and j > min(mods):
        print("[kernel] FAIL the NeoForge carrier sorts after a mod pack on the new side: %s" % new); bad = 1
    expect = [p if p != "forbric/carrier/2-neoforge-runtime-26.2" else "forbric/carrier/2-neoforge-runtime" for p in old if not p.startswith("forbric/carrier/1-forge-runtime")]
    if new != expect:
        print("[kernel] FAIL the new pack list is not the old one renamed in place (minus MinecraftForge's carrier): want %s got %s" % (expect, new)); bad = 1
    else:
        print("[kernel] PASS the carrier id was renamed in place, everything else kept in order: %s" % new)
sys.exit(bad)
PY
  check "the kernel renamed the installed carrier pack id (D43)" "\[Forbric/DataPacks\] this world named the NeoForge carrier's data pack by an installed file name; renamed it in place to forbric/carrier/2-neoforge-runtime" "$NLOG"
  check_absent "the NeoForge carrier's old id did not go missing" "Missing data pack forbric/carrier/2-neoforge-runtime" "$NLOG"
  check_absent "the NeoForge carrier was not found as a new pack" "Found new data pack forbric/carrier/2-neoforge-runtime" "$NLOG"
  check_exactly "the new boot lost exactly the MinecraftForge carrier's data pack" "Missing data pack forbric/carrier/1-forge-runtime-interop-26\.2" "$NLOG" 1
fi

# --- C1, C2 -----------------------------------------------------------------------------------------------------------
if want controls && need_pristine controls; then
  step "reverse control C1: a mutated chest slot and a renamed palette entry on a copy of S2 show up as exactly those"
  fresh_dir "$ROOT/c1-new" "${MAIN_MODS[@]}"
  clone_world "$PRISTINE/main" "$ROOT/c1-new/world"
  python3 "$WS" mutate "$ROOT/c1-new/world" --chest-count "minecraft:overworld 0 -60 0" 7 9 \
    --palette mcwbridges:acacia_bridge_pier=mcwbridges:acacia_bridge_piex > "$LOGS/c1-mutate.txt" 2>&1 \
    && echo "[kernel] mutated: $(paste -sd ';' "$LOGS/c1-mutate.txt")" || { echo "[kernel] FAIL C1 could not mutate the copy ($LOGS/c1-mutate.txt)"; FAIL=1; }
  # With a client joining, as on S2 -> S3: a boot nobody joins leaves the old player file as the old kernel wrote it.
  fresh_dir "$ROOT/cli-c1" "${MAIN_MODS[@]}"
  server_round new "$ROOT/c1-new" c1-server read "$ROOT/cli-c1"
  export_snap "$ROOT/c1-new/world" S3m
  python3 "$WS" diff "$SNAP/S2.json" "$SNAP/S3m.json" --rules upgrade --list 40 --json "$SNAP/S2-S3m.upgrade.diff.json" > "$SNAP/S2-S3m.upgrade.diff.txt" 2>&1
  python3 - "$SNAP/S2-S3m.upgrade.diff.json" <<'PY' || FAIL=1
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8"))
got = sorted((d["facet"], d["path"]) for d in r["differences"])
want_slot = [d for d in got if d[0] == "block_entities" and d[1].startswith("/minecraft:overworld 0 -60 0/Items[") and d[1].endswith("]/count")]
want_block = [d for d in got if d[0] == "blocks" and d[1] == "/minecraft:overworld 2 -60 4"]
if len(want_slot) == 1 and len(want_block) == 1 and len(got) == 2:
    print("[kernel] PASS reverse control C1: exactly the two mutations differ: %s" % got); sys.exit(0)
print("[kernel] FAIL reverse control C1: want exactly the chest slot count and the block at 2 -60 4, got %d: %s" % (len(got), got[:12])); sys.exit(1)
PY
  check "reverse control C1: the new boot named the renamed block" "acacia_bridge_piex" "$LOGS/c1-server.server.log"

  step "reverse control C2: a name dropped from the new kernel's census turns the census red, naming it"
  if [ -f "$RESULTS/s3-server/neo-read.json" ]; then
    python3 "$WS" census "$SNAP/S2.json" "$RESULTS/s3-server/neo-read.json" "$RESULTS/s3-server/fabric-read.json" \
      --drop block=mcwbridges:oak_bridge_pier > "$SNAP/S2.census-c2.txt" 2>&1; rc=$?
    if [ "$rc" = 1 ] && grep -aqx 'census missing block mcwbridges:oak_bridge_pier' "$SNAP/S2.census-c2.txt" \
        && grep -aqx 'census missing: 1' "$SNAP/S2.census-c2.txt"; then
      echo "[kernel] PASS reverse control C2: the census went red on exactly the dropped name (exit 1, missing: 1)"
    else echo "[kernel] FAIL reverse control C2: exit $rc, $(tail -n 2 "$SNAP/S2.census-c2.txt" | paste -sd ' ' -)"; FAIL=1; fi
  else echo "[kernel] FAIL reverse control C2 needs the new kernel's census from phase main ($RESULTS/s3-server)"; FAIL=1; fi
fi

# --- manifest ---------------------------------------------------------------------------------------------------------
step "manifest: what wrote and what read these saves"
# Every input a phase of this run read, by sha1 (review w2 UPG B-5/B-8): this gate's own file, the command scripts, the
# old stage's carriers, and the jars of the phases that ran. A path that is not there is recorded as null.
INPUTS=("$KERNEL/run/gate-m58-world-upgrade.sh" "$SCRIPTS"/*.commands "$OLD_KERNEL_STAGE/run/neoforge-runtime/neoforge-runtime.jar"
  "$OLD_KERNEL_STAGE/run/merged-base/forge-runtime-interop.jar")
want forge && INPUTS+=("$FORGE_BRIDGES")
want energy && INPUTS+=("$REBORN")
{ want transfer || want energy; } && INPUTS+=("$MACHINES"/*.jar)
want installed && INPUTS+=("$ROOT/installed-jars"/*.jar "$NEO_BUILDS/mcw-bridges-3.1.2-mc26.2neoforge.jar")
python3 - "$RESULTS/m58-manifest.json" "$OLD_KERNEL_COMMIT" "$OLD_KERNEL_JAR" "$OLD_KERNEL_STAGE/run/merged-base/patched-mc-merged-26.2.jar" \
  "$BUILD/libs/forbric-kernel-0.1.0-SNAPSHOT.jar" "$RUN_OLD/neoforge-patched/patched-mc-neoforge-26.2.jar" \
  "$RUN_OLD/neoforge-runtime/neoforge-runtime.jar" "$CANARIES/m58-build-inputs.json" "$SNAP" "$PHASES" "${MAIN_MODS[@]}" -- "${INPUTS[@]}" <<'PY'
import hashlib, json, pathlib, sys
out, commit, old_jar, merged, new_jar, base, carrier, inputs, snaps, phases, *rest = sys.argv[1:]
cut = rest.index("--")
mods, extra = rest[:cut], rest[cut + 1:]
sha1 = lambda p: hashlib.sha1(pathlib.Path(p).read_bytes()).hexdigest() if pathlib.Path(p).is_file() else None
manifest = {"schema": "forbric-m58-manifest/1", "phases": phases.split(),
            "old": {"ref": "tri-eco-26.2-final", "commit": commit, "kernelJarSha1": sha1(old_jar), "mergedBaseSha1": sha1(merged)},
            "new": {"kernelJarSha1": sha1(new_jar), "baseSha1": sha1(base), "carrierSha1": sha1(carrier)},
            "canaries": json.loads(pathlib.Path(inputs).read_text()) if pathlib.Path(inputs).is_file() else None,
            "mods": {pathlib.Path(m).name: sha1(m) for m in mods},
            "inputs": {str(pathlib.Path(p).resolve()): sha1(p) for p in extra},
            "snapshots": {p.name: sha1(p) for p in sorted(pathlib.Path(snaps).glob("*.json"))}}
pathlib.Path(out).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
gate = next((v for k, v in manifest["inputs"].items() if k.endswith("gate-m58-world-upgrade.sh")), None)
print("[kernel] manifest %s: old %s (jar %s, merged base %s), new jar %s, gate %s, %d input(s)" % (out, commit[:12],
      (manifest["old"]["kernelJarSha1"] or "?")[:12], (manifest["old"]["mergedBaseSha1"] or "?")[:12],
      (manifest["new"]["kernelJarSha1"] or "?")[:12], (gate or "?")[:12], len(manifest["inputs"])))
PY

step "M58 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M58 WORLD UPGRADE GATE GREEN — phases:$PHASES; evidence: $RESULTS"
else
  echo "[kernel] ❌ M58 GATE RED — phases:$PHASES; evidence: $RESULTS"
fi
exit "$FAIL"
