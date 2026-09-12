#!/usr/bin/env bash
# M11 — a DEDICATED server brings a config-driven mod stack up to Done.
#
# WHAT THIS GATE IS FOR. Every other server gate boots mods that never read their own config during world setup,
# and the client gates run an INTEGRATED server, which took a different path. So the kernel shipped for months
# with STARTUP and COMMON configs loaded on the client only — the call site literally read `if (client)`, on the
# reasoning that it left the proven server path alone. What it left alone was a dedicated server with no config
# loaded at all.
#
# Loading a spec is also what posts ModConfigEvent.Loading, and that event is how a config framework layered on
# NeoForge learns a mod's config now has values. Balm sets its active config from exactly that listener, so with
# the event never fired Waystones' getActive() — Objects.requireNonNull(...) — threw NPE out of
# setupDynamicRegistries and the server died before Done. Nothing in the crash named a config.
#
# The mod set is the minimum that reproduces it: waystones needs balm (config framework), and balm's own stack
# needs shogi + cookingforblockheads present to construct. fabric-api is here because the failure was first seen
# on a mixed pack and this keeps the gate honest about that.
#
# TEETH: -Dforbric.earlyConfigs=off turns this gate RED (measured: getActive NPE x4, Done never reached).
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m11-serverconfig-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-config"

step "stage a config-driven NeoForge mod stack (waystones + balm) on a DEDICATED server"
kernel_jar
reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
for j in fabric-api-0.155.2+26.2.jar balm-neoforge-26.2-26.2.0.4.jar shogi-neoforge-26.2-26.2.0.3.jar \
         cookingforblockheads-neoforge-26.2-26.2.0.2.jar waystones-neoforge-26.2-26.2.0.5.jar; do
  src="$KERNEL/run/client-merged-pack/mods/$j"
  [ -f "$src" ] || { echo "[kernel] FATAL: missing $j — this gate needs the merged pack staged" >&2; exit 3; }
  cp "$src" "$RUNDIR/mods/"
done
echo "[kernel] staged: $(ls "$RUNDIR/mods" | paste -sd' ' -)"
seed_server_properties "$RUNDIR"

step "boot to Done and stop cleanly"
: > "$LOG"
( sleep 80; echo stop ) | FORBRIC_JVM="${M11_EXTRA_JVM:-}" RUNDIR="$RUNDIR" \
  "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 180

step "the config lifecycle ran on the server side (must PASS)"
# STARTUP+COMMON and NOT CLIENT: CLIENT is client-only, and SERVER is per-world and belongs to
# ServerLifecycleHooks.handleServerAboutToStart, which the kernel does not excise (gate-m7-neo asserts that one).
check "early configs loaded"          "Forbric/Lifecycle\] loaded NeoForge configs" "$LOG"
TYPES=$(grep -oE 'loaded NeoForge configs \([A-Z+]+\)' "$LOG" | head -1 | grep -oE '\([A-Z+]+\)' | tr -d '()')
assert_eq "config types loaded on a server" "STARTUP+COMMON" "${TYPES:-none}"

step "the config-driven mod stack came up (must PASS)"
check "waystones constructed"         "constructed @Mod waystones"                  "$LOG"
check "balm constructed"              "constructed @Mod balm"                       "$LOG"
check "server reached Done"           "Done \("                                     "$LOG"
check "clean shutdown"                "All dimensions are saved"                    "$LOG"

step "nothing read a config that was never loaded (must be ABSENT)"
# The exact shape of the original crash. getActive() is Objects.requireNonNull(Balm.config().getActiveConfig(..)),
# so a null active config surfaces as an NPE naming neither the config nor the mod that owns it.
check_absent "no config-less getActive"   "WaystonesConfig.getActive"               "$LOG"
check_absent "no dynamic-registry setup failure" "setupDynamicRegistries"           "$LOG"
check_absent "no unloaded-spec read"      "Cannot get config value before config is loaded" "$LOG"
check_absent "server did not fail to start" "Failed to start the minecraft server"  "$LOG"
check_absent "no crash report"            "Preparing crash report"                  "$LOG"

step "M11 result"
if [ "$FAIL" = 0 ]; then
  echo "[kernel] ✅ M11 SERVER-CONFIG GATE GREEN — a dedicated server loads STARTUP+COMMON configs, so a mod that reads its own config during world setup finds it"
else
  echo "[kernel] ❌ M11 SERVER-CONFIG GATE RED — see $LOG"
fi
exit "$FAIL"
