#!/usr/bin/env bash
# M29 — MinecraftForge's capability system on the merged base: attach through AttachCapabilitiesEvent, read back
# through getCapability on block entities / entities / levels / chunks / item stacks, invalidate on removal, persist
# under "ForgeCaps", and initialise ForgeCapabilities at all (its tokens need Forge's launch plugin).
# Boot 1 (shim ON) asserts nine canary lines + the kernel's composition lines and the absence of every error
# signature; boot 2 (-Dforbric.forgeCapabilities=off) is the RED demonstration and the attribution proof: the
# attached-handler line is absent and load-report.txt names forbriclive DEGRADED.
# RED control for boot 1: M29_EXTRA_JVM='-Dforbric.forgeCapabilities=off' (every CAPS line goes red).
# GATE-PARALLEL: rundirs=server-forgecaps mem=1500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

RUNDIR="$KERNEL/run/server-forgecaps"
LOG="$BUILD/gate-m29-forgecaps.log"
OFF_LOG="$BUILD/gate-m29-forgecaps-off.log"
mkdir -p "$BUILD"
for jar in "$RUN_OLD/forge-runtime/forbriclive.jar" "$RUN_OLD/neoforge-runtime/forbricneolive.jar"; do
  [ -f "$jar" ] || { echo "[kernel] FATAL: missing $jar; run build-testmods.sh" >&2; exit 3; }
done
kernel_jar

boot() { # <log> <extra jvm>
  local log="$1" extra="${2:-}"
  reap_stale_server "$RUNDIR"
  rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" "$RUNDIR/logs" "$RUNDIR/config"
  mkdir -p "$RUNDIR/mods"
  cp "$RUN_OLD/forge-runtime/forbriclive.jar" "$RUN_OLD/neoforge-runtime/forbricneolive.jar" "$RUNDIR/mods/"
  seed_server_properties "$RUNDIR"
  printf 'online-mode=false\nlevel-type=minecraft\\:flat\nmax-tick-time=-1\nview-distance=2\n' >> "$RUNDIR/server.properties"
  : > "$log"
  ( for i in $(seq 1 180); do grep -aqE 'Done \(' "$log" && break; sleep 1; done; sleep 8; echo stop ) \
    | FORBRIC_JVM="${M29_EXTRA_JVM:-} $extra" RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$RUNDIR" "$pid"
  await_server "$pid" "$log" 240 30
  rm -f "$RUNDIR/.forbric-gate.pid"
}

step "boot 1: the capability shim is on"
boot "$LOG"
check "server reached Done" 'Done \(' "$LOG"
check "the roots were composed"        'Capabilities\] composed MinecraftForge capabilities into net\.minecraft\.world\.entity\.Entity' "$LOG"
check "block entities were composed"   'Capabilities\] composed MinecraftForge capabilities into net\.minecraft\.world\.level\.block\.entity\.BlockEntity' "$LOG"
check "levels were composed"           'Capabilities\] composed MinecraftForge capabilities into net\.minecraft\.world\.level\.Level' "$LOG"
check "ServerLevel gathers again"      'Capabilities\] ServerLevel\.<init> gathers MinecraftForge level capabilities again' "$LOG"
check "LevelChunk writes its provider" 'Capabilities\] LevelChunk\.<init> writes Forge.s own capability provider again' "$LOG"
check "the ITEM_HANDLER token got its getType" 'Capabilities\] capability token net\.minecraftforge\.common\.capabilities\.ForgeCapabilities\$4 given its getType' "$LOG"
check "injectCapabilities ran"         'Capabilities\] ran MinecraftForge.s injectCapabilities' "$LOG"
check "the audit reports the composed feature, not a gap" 'Capabilities\] [1-9][0-9]* mod jar\(s\) use MinecraftForge.s capability system — composed into' "$LOG"

step "boot 1: twelve canary probes"
check "AttachCapabilitiesEvent delivered"      'ForbricLive/CAPS\] AttachCapabilitiesEvent\.BlockEntities RECEIVED for BellBlockEntity' "$LOG"
check "attached handler read back"             'ForbricLive/CAPS\] attached handler present=true slots=1' "$LOG"
check "vanilla chest handler (Forge override)" 'ForbricLive/CAPS\] vanilla chest handler slots=27' "$LOG"
check "Level getCapability found the attached storage" 'ForbricLive/CAPS\] Level getCapability answered: present=true' "$LOG"
check "LevelChunk getCapability answered"      'ForbricLive/CAPS\] LevelChunk getCapability answered: present=(true|false)' "$LOG"
check "ItemStack lookup answered"              'ForbricLive/CAPS\] ItemStack lookup answered: present=(true|false)' "$LOG"
check "LazyOptional invalidated on setRemoved" 'ForbricLive/CAPS\] LazyOptional invalidated on setRemoved: true' "$LOG"
check "ForgeCaps round-trip"                   'ForbricLive/CAPS\] ForgeCaps round-trip: key=true count=7' "$LOG"
check "ServerLevel dispatcher present"         'ForbricLive/CAPS\] dispatcher present=true' "$LOG"
# E7: the merge dropped MinecraftForge's constructor initializers for LivingEntity.handlers, furnace.handlers and
# the chiseled bookshelf's itemHandler while keeping every reader. Found by gate-m9: the first mob death crashed the
# integrated server in invalidateCaps ("this.handlers" is null). RED with -Dforbric.forgeCapabilities=off.
check "the lost initializers were replayed"   'Capabilities\] net\.minecraft\.world\.entity\.LivingEntity\.<init> assigns handlers again' "$LOG"
check "a living entity answers ITEM_HANDLER and survives remove()" 'ForbricLive/CAPS\] living entity equipment handler present=true remove\(\) invalidated without error=true' "$LOG"
check "a furnace answers the sided ask"       'ForbricLive/CAPS\] furnace sided handler slots=[1-9]' "$LOG"
check_absent "no probe failure"     'ForbricLive/CAPS\] probe FAILED' "$LOG"
# Thrown exceptions print as java.lang.X: the kernel's own stub line mentions "AbstractMethodError" in prose.
check_absent "no capability error signature" 'java\.lang\.(ExceptionInInitializerError|AbstractMethodError)|NoSuchMethodError.*Caps|NullPointerException.*capProvider|This will be implemented by a transformer|which the merged game does not carry' "$LOG"
check "server stopped cleanly" 'All dimensions are saved' "$LOG"

step "boot 2: -Dforbric.forgeCapabilities=off names the mod that loses the feature"
boot "$OFF_LOG" "-Dforbric.forgeCapabilities=off"
check "the kernel says it is off" 'forgeCapabilities=off — MinecraftForge capabilities are not composed' "$OFF_LOG"
check_absent "no attached handler without the shim" 'ForbricLive/CAPS\] attached handler present=true' "$OFF_LOG"
check "the audit names the mod as inert" 'Capabilities\] .* will find it inert .* Marked DEGRADED: \[.*forbriclive' "$OFF_LOG"
check "forbriclive is named in the load report" 'forbriclive' "$RUNDIR/.forbric-kernel/load-report.txt"
check "the control server still reached Done" 'Done \(' "$OFF_LOG"

step "M29 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] M29 FORGE-CAPABILITIES GATE GREEN — attached, read back, invalidated, persisted; and named when off"
else
  echo "[kernel] M29 FORGE-CAPABILITIES GATE RED — see $LOG and $OFF_LOG"
fi
exit "$FAIL"
