#!/usr/bin/env bash
# M20 gate — an unmet hard dependency reaches the PLAYER, not just the log.
#
# WHY THIS EXISTS. The kernel has always detected this and always written one WARN about it. On a real pack that
# WARN said "biomesoplenty requires terrablender >=26.2.0.0.1 — not installed", and forty seconds later the game
# died on "Failed to load registries due to errors" with 455 unknown block ids — an error naming neither mod. One
# line in ten thousand is not a report; the player never saw it and could not have connected the two.
#
# So the same finding is now put in front of them before the window opens. This gate asserts BOTH halves of that,
# because each is a different way to get it wrong:
#   * on a CLIENT it must reach them
#   * on a dedicated SERVER it must NOT — a server blocked on a dialog nobody can see is strictly worse than the
#     log line it replaces, and every gate in this repo runs unattended
#
# The canary is a jar with nothing in it but a manifest declaring a dependency on a mod that does not exist. No
# code, nothing to compile: the thing under test is the audit and the dialog, and a canary with classes in it
# would only add ways for the gate to fail for an unrelated reason.
#
# THE SECOND CANARY covers the opposite mistake: a requirement the dialog must NOT report. The same library is
# spelled differently by each ecosystem -- Fabric "cloth-config", NeoForge "cloth_config" -- so under Forbric a
# NeoForge mod's requirement reads as NOT INSTALLED with the Fabric build sitting in the same mods folder. That is
# the one thing a dialog whose job is to be believed must never do. RED control, verified:
#   M20_EXTRA_JVM='-Dforbric.crossEcosystemIds=off' -> "forbriccrosseco ... requires forbric_dep_canary >=1.0.0 —
#   not installed" is back and the cross-spelling INFO line is gone (4 red). Two of those four are collateral and
#   say so: the false accusation takes the client's finding count from 1 to 2, so the two assertions that name
#   "1 finding(s)" stop matching. That IS the switch working -- it puts back a finding that is not real.
#
# HOW A DIALOG IS TESTED WITH NOBODY TO CLICK IT: -Dforbric.dependencyDialog=dryRun runs the real path — writes
# the real report, forks the real child JVM, reads the real exit code — with AWT disabled inside the child, so it
# finds it cannot draw and answers "launch anyway". Everything but the pixels.
# GATE-PARALLEL: rundirs=server-depdialog,client-depdialog mem=3000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SLOG="$BUILD/gate-m20-server.log"
CLOG="$BUILD/gate-m20-client.log"
OFFLOG="$BUILD/gate-m20-off.log"
SRV="$KERNEL/run/server-depdialog"
CLI="$KERNEL/run/client-depdialog"
WORK="$BUILD/depdialog-canary"
mkdir -p "$BUILD"

step "build the canary: a manifest declaring a dependency on a mod that does not exist"
kernel_jar
rm -rf "$WORK"; mkdir -p "$WORK"
cat > "$WORK/fabric.mod.json" <<'JSON'
{
  "schemaVersion": 1,
  "id": "forbricdepcanary",
  "version": "1.0.0",
  "name": "Forbric Dependency Canary",
  "description": "Declares a hard dependency on a mod that is not installed, so the audit has something true to report.",
  "license": "Apache-2.0",
  "environment": "*",
  "depends": {
    "forbricnosuchmod": ">=1.0.0"
  }
}
JSON
(cd "$WORK" && jar --create --file "$BUILD/forbricdepcanary.jar" .) || { echo "[kernel] FAIL canary jar"; exit 1; }
echo "[kernel] built forbricdepcanary.jar (depends on forbricnosuchmod >=1.0.0, which nothing provides)"

# Second canary: requires the first one under the OTHER ecosystem's spelling of the same id. Nothing named
# "forbric_dep_canary" is installed; "forbricdepcanary" is, and they are one mod.
CROSS="$BUILD/crosseco-canary"
rm -rf "$CROSS"; mkdir -p "$CROSS"
cat > "$CROSS/fabric.mod.json" <<'JSON'
{
  "schemaVersion": 1,
  "id": "forbriccrosseco",
  "version": "1.0.0",
  "name": "Forbric Cross-Ecosystem Spelling Canary",
  "description": "Requires the dependency canary under the underscored spelling the Forge families would use.",
  "license": "Apache-2.0",
  "environment": "*",
  "depends": {
    "forbric_dep_canary": ">=1.0.0"
  }
}
JSON
(cd "$CROSS" && jar --create --file "$BUILD/forbriccrosseco.jar" .) || { echo "[kernel] FAIL cross canary jar"; exit 1; }
echo "[kernel] built forbriccrosseco.jar (requires forbric_dep_canary, which is installed as forbricdepcanary)"

step "stage it on a server and a client"
reap_stale_server "$SRV"
rm -rf "$SRV" "$CLI"
mkdir -p "$SRV/mods" "$CLI/mods"
cp "$BUILD/forbricdepcanary.jar" "$SRV/mods/"
cp "$BUILD/forbricdepcanary.jar" "$CLI/mods/"
cp "$BUILD/forbriccrosseco.jar" "$CLI/mods/"
seed_server_properties "$SRV"
cp "$KERNEL/run/client-merged-pack/options.txt" "$CLI/options.txt" 2>/dev/null || printf 'version:4903\n' > "$CLI/options.txt"

step "the dedicated server must report it and must NOT open anything"
: > "$SLOG"
# -Dforbric.debug so the side check's own line prints. Without it the only thing observable on a server is the
# ABSENCE of a fork -- and that absence is also produced by launch-kernel-server.sh's -Djava.awt.headless=true,
# so the assertion would stay green with the side check deleted. Asserting the line names WHICH guard fired.
( sleep 25; echo stop ) | FORBRIC_JVM="-Dforbric.debug=true" \
  RUNDIR="$SRV" "$KERNEL/run/launch-kernel-server.sh" > "$SLOG" 2>&1 &
SPID=$!
record_server_pid "$SRV" "$SPID"
await_server "$SPID" "$SLOG" 130

check "the server still reports the unmet requirement" \
  "Forbric/Deps\] forbricdepcanary .* requires forbricnosuchmod >=1.0.0 — not installed" "$SLOG"
# The guard that matters. launch-kernel-server.sh passes -Djava.awt.headless=true, so BOTH the side check and the
# headless check would stop it; asserting the absence of the fork covers whichever one fired.
check "the SIDE check is what stopped it, by name" \
  "Forbric/Deps\] not the client — the 1 finding\(s\) stay in the log" "$SLOG"
check_absent "and opened no dialog" "Forbric/Deps\] (launching anyway|dryRun|the player chose)" "$SLOG"

step "a client reaches the player — the whole path, with nothing to click"
: > "$CLOG"
FORBRIC_DEP_DIALOG=dryRun FORBRIC_JVM="${M20_EXTRA_JVM:-}" RUNDIR="$CLI" "$KERNEL/run/launch-kernel-client.sh" > "$CLOG" 2>&1 &
CPID=$!
echo "[kernel] client pid=$CPID (killed by pid only — another client may be running)"
CGAME="$CLI/logs/latest.log"
for i in $(seq 1 240); do
  kill -0 "$CPID" 2>/dev/null || { echo "[kernel] client exited on its own after ~${i}s"; break; }
  grep -qaE 'Forbric/Deps\] -Dforbric.dependencyDialog=dryRun|Game crashed|Mod Loading has failed' "$CGAME" 2>/dev/null \
    && { echo "[kernel] outcome reached after ~${i}s"; break; }
  sleep 1
done
kill_tree "$CPID"
cat "$CGAME" >> "$CLOG" 2>/dev/null

check "the client reported the same unmet requirement" \
  "Forbric/Deps\] forbricdepcanary .* requires forbricnosuchmod >=1.0.0 — not installed" "$CLOG"
# Written by DependencyDialog, which is the only layer that knows the child was forked and what it answered --
# the audit that found the requirement cannot establish either.
check "it forked the real dialog child and read its answer" \
  "Forbric/Deps\] -Dforbric.dependencyDialog=dryRun — forked the dialog for 1 finding\(s\)" "$CLOG"
check "and the boot continued, which is what an unanswerable dialog must always mean" \
  "Forbric/Deps\] launching anyway with 1 finding\(s\)" "$CLOG"
check_absent "nothing quit the game on the player's behalf" "Forbric/Deps\] the player chose to quit" "$CLOG"

check "a library spelled the other ecosystem's way is resolved, not accused" \
  "Forbric/Deps\] forbriccrosseco .* requires forbric_dep_canary, which is installed as .*forbricdepcanary.* the same library" "$CLOG"
check_absent "and it is nowhere in the not-installed findings" \
  "forbriccrosseco .* requires forbric_dep_canary >=1.0.0 — not installed" "$CLOG"

step "negative control: with the dialog off, the finding is still reported"
: > "$OFFLOG"
FORBRIC_DEP_DIALOG=off RUNDIR="$CLI" "$KERNEL/run/launch-kernel-client.sh" > "$OFFLOG" 2>&1 &
OPID=$!
for i in $(seq 1 240); do
  kill -0 "$OPID" 2>/dev/null || break
  grep -qaE 'Forbric/Deps\] -Dforbric.dependencyDialog=off|Game crashed' "$CGAME" 2>/dev/null && break
  sleep 1
done
kill_tree "$OPID"
cat "$CGAME" >> "$OFFLOG" 2>/dev/null
check "the switch really turned it off" \
  "Forbric/Deps\] -Dforbric.dependencyDialog=off — 1 finding\(s\) reported in the log only" "$OFFLOG"
check "and the WARN a gate has always been able to grep is unchanged" \
  "Forbric/Deps\] forbricdepcanary .* requires forbricnosuchmod >=1.0.0 — not installed. It is being loaded anyway" "$OFFLOG"
check_absent "no child was forked" "forked the dialog" "$OFFLOG"

step "M20 result"
if [ "${FAIL:-0}" -eq 0 ]; then
  echo "[kernel] ✅ M20 GATE GREEN — an unmet hard dependency reaches the player on a client and nothing on a server"
else
  echo "[kernel] ❌ M20 GATE RED — see $SLOG / $CLOG / $OFFLOG"
  exit 1
fi
