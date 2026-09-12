#!/usr/bin/env bash
# M2b gate — the FULL Fabric ecosystem runs on the sovereign kernel: fabric-api (43 modules, 58 mixin configs,
# 33 access wideners) plus a real third-party Fabric mod, on the merged 3-ABI base, with NO Fabric Loader.
#
# What this exercises beyond M2a: the kernel IS the Mixin service (ForbricMixinService), MixinExtras is bootstrapped
# game-side, class tweakers (access wideners) are applied before Mixin, and the MC libraries are owned by the
# transforming loader so mods can mixin into them (fabric-dimension-api-v1 injects an interface into DataFixerUpper).
#
# NO compatibility flags are passed. The merged-base incompatibilities are shipped defaults in
# MergedBaseMixinCompat, so an installed instance boots as-is. Each entry there costs a real feature and is
# documented at its declaration; `run/mixin-inventory.sh` rediscovers the list from scratch.
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m2b-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-fabric-api"
MODS="$RUN_OLD/server-merged/mods"
CANARY="$KERNEL/run/canary/forbricfabriclive.jar"

step "stage fabric-api + Jade + the Fabric canary"
"$KERNEL/run/build-fabric-canary.sh" >"$BUILD/gate-m2b-canary.log" 2>&1
[ -f "$CANARY" ] || { echo "[kernel] FAIL canary build (see $BUILD/gate-m2b-canary.log)"; exit 1; }

reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
for jar in "$MODS/fabric-api-0.154.0+26.2.jar" "$MODS/Jade-mc26.2-Fabric-26.2.9.jar"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] WARN absent: $jar"; fi
done
cp "$CANARY" "$RUNDIR/mods/"
printf 'level-seed=forbrickernel\n' > "$RUNDIR/server.properties"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot the merged base under the kernel (no compatibility flags)"
: > "$LOG"
( sleep 40; echo stop ) | RUNDIR="$RUNDIR" "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 150

step "the Fabric substrate came up natively (must PASS)"
check "fabric-api + Jade + canary discovered"  "discovered [0-9]{2,} Fabric mod\(s\)" "$LOG"
check "Mixin up, kernel is the service"        "Mixin up on the sovereign kernel" "$LOG"
check "MixinExtras initialized game-side"      "MixinExtras [0-9.]+ initialized \(game-side\)" "$LOG"
check "access wideners merged + applied"       "merged [1-9][0-9]* class tweaker\(s\).*target class" "$LOG"
check "MC libraries owned by the game loader"  "[0-9]{2,} MC library jar\(s\)" "$LOG"

step "mods actually ran (must PASS)"
check "Fabric main + side entrypoints invoked" "invoked [1-9][0-9]* Fabric main entrypoint\(s\) \+ [1-9][0-9]* server" "$LOG"
check "canary main entrypoint"                 "\[ForbricFabricLive\] onInitialize \(Fabric main entrypoint\)" "$LOG"
check "canary server entrypoint + content kept" "onInitializeServer .*registered content survives=true" "$LOG"
check "JiJ nested mod initialized"             "\[ForbricFabricLib\] JiJ nested mod initialized" "$LOG"
check "Jade (real third-party mod) loaded"     "invoked main entrypoint of jade" "$LOG"

step "the server actually works (must PASS)"
check "vanilla datapack fully loaded"          "Loaded 1585 recipes" "$LOG"
check "server reached Done"                    "Done \(" "$LOG"
check "server ticked + shut down cleanly"      "Stopping server" "$LOG"

step "nothing was quietly broken (must be ABSENT)"
check_absent "no empty dynamic registries"     "Registry must be non-empty" "$LOG"
check_absent "no Fabric entrypoint failed"     "entrypoint of .* failed" "$LOG"
check_absent "no fatal mixin error"            "MixinTransformerError|InjectionError" "$LOG"
check_absent "no Tags not bound"               "Tags not bound" "$LOG"
check_absent "no genuine Fabric Loader"        "FabricLoaderImpl|KnotClassLoader" "$LOG"
check_absent "no genuine FancyModLoader"       "gatherAndInitializeMods|dispatchParallelEvent" "$LOG"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m2b-postdone.log"
check_absent "no post-Done exception"          "Encountered an unexpected exception" "$BUILD/gate-m2b-postdone.log"

step "known, documented merged-base concessions (informational)"
echo "[kernel] suppressed mixins: $(grep -c 'suppressed mixin' "$LOG")"
grep -oE 'suppressed mixin [A-Za-z0-9_.$]+ from [a-z0-9.-]+' "$LOG" | sed 's/^/[kernel]   /' | sort -u
echo "[kernel] disabled configs:  $(grep -c 'DISABLED by' "$LOG")"
grep -oE 'mixin config [a-z0-9.-]+ DISABLED' "$LOG" | sed 's/^/[kernel]   /' | sort -u
echo "[kernel] soft-skipped mixins (could not apply, rest of their config did):"
grep -oE 'failed [a-z0-9.-]+\.mixins\.json:[A-Za-z0-9_.$]+' "$LOG" | sed 's/^failed /[kernel]   /' | sort -u

step "M2b result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M2b GATE GREEN — full fabric-api + a real third-party Fabric mod run on the sovereign kernel"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
