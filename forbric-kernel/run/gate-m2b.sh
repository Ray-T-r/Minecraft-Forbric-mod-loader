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
#
# Optional reproduction of the real nested-library collision (all existing assertions remain enabled):
# M2B_FABRIC_API=/path/to/fabric-api-0.161.0+26.2.jar \
# M2B_BADPACKETS=/path/to/badpackets-forge-0.12.2.jar run/gate-m2b.sh
# RED: add M2B_EXTRA_JVM=-Dforbric.kernelBundledFirst=off. The nested MixinExtras 0.3.5 then wins over
# the supplied version, so the version/source assertions fail; the newer FAPI also requires EXPRESSION.
# GATE-PARALLEL: rundirs=server-fabric-api mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG="$BUILD/gate-m2b-boot.log"; mkdir -p "$BUILD"
RUNDIR="$KERNEL/run/server-fabric-api"
MODS="$RUN_OLD/server-merged/mods"
CANARY="$KERNEL/run/canary/forbricfabriclive.jar"
FABRIC_API="${M2B_FABRIC_API:-$MODS/fabric-api-0.154.0+26.2.jar}"
MEX_VERSION=$(sed -n 's/^mixin_extras_version[[:space:]]*=[[:space:]]*//p' "$KERNEL/gradle.properties")
MEX_PATTERN=${MEX_VERSION//./\\.}
[ -n "$MEX_VERSION" ] || { echo '[kernel] FAIL MixinExtras version missing from gradle.properties'; exit 1; }
if [ -n "${M2B_FABRIC_API:-}" ] && [ ! -f "$FABRIC_API" ]; then
  echo "[kernel] FAIL requested Fabric API fixture absent: $FABRIC_API"; exit 1
fi
if [ -n "${M2B_BADPACKETS:-}" ] && [ ! -f "$M2B_BADPACKETS" ]; then
  echo "[kernel] FAIL requested badpackets fixture absent: $M2B_BADPACKETS"; exit 1
fi

step "stage fabric-api + Jade + the Fabric canary"
"$KERNEL/run/build-fabric-canary.sh" >"$BUILD/gate-m2b-canary.log" 2>&1
[ -f "$CANARY" ] || { echo "[kernel] FAIL canary build (see $BUILD/gate-m2b-canary.log)"; exit 1; }

reap_stale_server "$RUNDIR"
rm -rf "$RUNDIR/world" "$RUNDIR/mods" "$RUNDIR/.forbric-kernel" 2>/dev/null
mkdir -p "$RUNDIR/mods"
for jar in "$FABRIC_API" "$MODS/Jade-mc26.2-Fabric-26.2.9.jar"; do
  if [ -f "$jar" ]; then cp "$jar" "$RUNDIR/mods/"; else echo "[kernel] WARN absent: $jar"; fi
done
[ -z "${M2B_BADPACKETS:-}" ] || cp "$M2B_BADPACKETS" "$RUNDIR/mods/"
cp "$CANARY" "$RUNDIR/mods/"
seed_server_properties "$RUNDIR"
echo "[kernel] staged: $(ls -1 "$RUNDIR/mods" | tr '\n' ' ')"

step "boot the merged base under the kernel"
: > "$LOG"
( sleep 40; echo stop ) | RUNDIR="$RUNDIR" FORBRIC_JVM="${FORBRIC_JVM:-} ${M2B_EXTRA_JVM:-}" \
  "$KERNEL/run/launch-kernel-server.sh" > "$LOG" 2>&1 &
BOOTPID=$!
record_server_pid "$RUNDIR" "$BOOTPID"
await_server "$BOOTPID" "$LOG" 150

step "the Fabric substrate came up natively (must PASS)"
check "fabric-api + Jade + canary discovered"  "discovered [0-9]{2,} Fabric mod\(s\)" "$LOG"
check "Mixin up, kernel is the service"        "Mixin up on the sovereign kernel" "$LOG"
check "MixinExtras initialized game-side"      "MixinExtras [0-9.]+ initialized \(game-side\)" "$LOG"
check "kernel-supplied MixinExtras version"    "MixinExtras $MEX_PATTERN initialized \(game-side\)" "$LOG"
check "MixinExtras class and config from bundle" \
  'MixinExtras sources: class=[^;]*/\.forbric-kernel/lib/([0-9a-f]{64})/mixinextras-fabric\.jar; config=[^;]*/\.forbric-kernel/lib/\1/mixinextras-fabric\.jar!/mixinextras\.init\.mixins\.json; game-side=true' "$LOG"
if [ -n "${M2B_BADPACKETS:-}" ]; then
  check "old MixinExtras wrapper actually staged" 'extracted nested JarJar library mixinextras-forge-0\.3\.5\.jar from badpackets' "$LOG"
  check "old MixinExtras common child actually staged" 'extracted nested JarJar library MixinExtras-0\.3\.5\.jar from mixinextras-forge-0\.3\.5\.jar' "$LOG"
fi
check "access wideners merged + applied"       "merged [1-9][0-9]* class tweaker\(s\).*target class" "$LOG"
check "MC libraries owned by the game loader"  "[0-9]{2,} MC library jar\(s\)" "$LOG"

step "mods actually ran (must PASS)"
check "Fabric main + side entrypoints invoked" "invoked [1-9][0-9]* Fabric main entrypoint\(s\) \+ [1-9][0-9]* server" "$LOG"
check "canary main entrypoint"                 "\[ForbricFabricLive\] onInitialize \(Fabric main entrypoint\)" "$LOG"
check "canary server entrypoint + content kept" "onInitializeServer .*registered content survives=true" "$LOG"
check "JiJ nested mod initialized"             "\[ForbricFabricLib\] JiJ nested mod initialized" "$LOG"
check "Jade (real third-party mod) loaded"     "invoked main entrypoint of jade" "$LOG"

step "the server actually works (must PASS)"
# F3: fabric-loot-api-v3's LootTableEvents fire from NeoForge's LootTableLoadEvent seam (the mixin is pinned).
# RED with FORBRIC_JVM=-Dforbric.lootBridge=off (no 'offered' line; the audit then names the canary DEGRADED).
check "kernel offered the loot tables to fabric" "Forbric/LootBridge\] offered [1-9][0-9]* loot table" "$LOG"
check "the loot seams were routed"             "Forbric/LootBridge\] routed 1 loot-table load site\(s\) and 1 tag-load site" "$LOG"
# F5: the canary's own listeners, registered like balm-fabric's. Both KINDS of line are asserted: the kernel's
# 'offered' count proves the bridge ran, the canary's lines prove a mod's listener was actually called.
check "the canary registered on LootTableEvents" "ForbricFabricLive\] LootTableEvents listeners registered" "$LOG"
check "LootTableEvents.MODIFY reached the canary" "ForbricFabricLive\] LootTableEvents.MODIFY saw minecraft:blocks/dirt" "$LOG"
check "LootTableEvents.ALL_LOADED fired"         "ForbricFabricLive\] LootTableEvents.ALL_LOADED: [1-9][0-9]* loot table" "$LOG"
# G6: fabric-item-api's tooltip-order scrape reads ItemStack.addDetailsToTooltip's bytecode; the merge renamed the
# body away, so the scrape threw for any mod touching the registry. RED with FORBRIC_JVM=-Dforbric.tooltipOrderScrape=off.
check "fabric-item-api's tooltip order scraped" "ForbricFabricLive\] fabric-item-api tooltip order: ok" "$LOG"
check_absent "…and its scrape found component types" "Found no component types" "$LOG"
# F4: with every restoration on, no installed mod loses a fabric-api surface. With -Dforbric.lootBridge=off the
# audit names the canary ("[Forbric/FabricApi] 1 mod jar(s) use fabric-loot-api-v3's LootTableEvents … forbricfabriclive.jar")
# and .forbric-kernel/load-report.txt lists forbricfabriclive as DEGRADED.
check_absent "no mod is degraded by a fabric-api module loss" "Forbric/FabricApi\]" "$LOG"
check "vanilla datapack fully loaded"          "Loaded 1585 recipes" "$LOG"
check "server reached Done"                    "Done \(" "$LOG"
# "Stopping the server" is the /stop command's OWN feedback (commands.stop.stopping in en_us), and the console
# queue is drained only by tickConnection(), which runs only inside tickServer() — so that line cannot exist
# unless the tick loop ran and was still running when this gate fed it "stop" on stdin. Bare "Stopping server"
# is stopServer(), which runServer() reaches on EVERY exit path including ones that never ticked at all (see
# the GATE_PORT note in lib.sh): evidence that shutdown began, not that the server ticked and not that it
# finished — await_server is what fails a server that cannot finish. The alternation covers a merged base that
# lost en_us and renders the raw key. Dedicated-server gates only: an integrated server prints the bare line
# and never the command's, so this pair must not be copied into a client gate.
check "server ticked (the stop command ran)"   "Stopping the server|commands\.stop\.stopping" "$LOG"
check "shutdown began"                         "Stopping server" "$LOG"

step "nothing was quietly broken (must be ABSENT)"
check_absent "no empty dynamic registries"     "Registry must be non-empty" "$LOG"
check_absent "no Fabric entrypoint failed"     "entrypoint of .* failed" "$LOG"
check_absent "no fatal mixin error"            "MixinTransformerError|InjectionError" "$LOG"
check_absent "MixinExtras EXPRESSION supported" 'MIXINEXTRAS:EXPRESSION is not a valid injection point specifier' "$LOG"
check_absent "no invalid partially applied handler" 'VerifyError' "$LOG"
check_absent "no Tags not bound"               "Tags not bound" "$LOG"
check_absent "no genuine Fabric Loader"        "FabricLoaderImpl|KnotClassLoader" "$LOG"
check_absent "no genuine FancyModLoader"       "gatherAndInitializeMods|dispatchParallelEvent" "$LOG"
awk '/Done \(/{d=1} d' "$LOG" > "$BUILD/gate-m2b-postdone.log"
check_absent "no post-Done exception"          "Encountered an unexpected exception" "$BUILD/gate-m2b-postdone.log"

step "the merged-base concessions are EXACTLY the documented ones (must PASS)"
# These three were printed and asserted on nothing, which made them decoration: anything that suppressed three
# times as many guest mixins printed a bigger number and the gate still went green. No other check here can see
# it either — suppression edits the mixin OUT of the config JSON before Mixin ever reads it
# (ForbricMixinService.getResourceAsStream), so there is nothing left to raise a MixinTransformerError. The
# count IS the detector.
#
# grep -a throughout, deliberately: one NUL byte anywhere in the log makes grep treat it as binary and print
# nothing at all, so an unflagged `grep -c` here yields an EMPTY count rather than a number.
SUPPRESSED=$(grep -aoE 'suppressed mixin .*' "$LOG" | sort -u)
[ -n "$SUPPRESSED" ] && echo "$SUPPRESSED" | sed 's/^/[kernel]   /'
# Exactly the server-side entries of MergedBaseMixinCompat.SUPPRESSED_MIXINS that this mod set reaches. Each one
# costs a real feature and is justified where it is declared. Re-derive the set with run/mixin-inventory.sh
# before changing this number — never bump it to match a new log, which is how a ledger of debts turns into a
# record of whatever happened last.
assert_eq "suppressed mixins are the documented set" 4 "$(printf '%s' "$SUPPRESSED" | grep -c .)"
# DISABLED_CONFIGS ships EMPTY on purpose (a whole-config entry hides which single mixin is at fault) and this
# gate passes no -Dforbric.disableMixinConfigs, so zero is the only correct answer and a count was never the
# right assertion. `.*` and not `[^ ]+`: the Fabric path renders the config through MixinConfigOwners.describe,
# which returns "<mod id> (<config>)" the moment publish() moves ahead of it — a space the tighter pattern
# cannot span, and the assertion would then pass forever on the path it exists to cover.
check_absent "no mixin config disabled wholesale" "mixin config .* DISABLED" "$LOG"
# Soft-skips: an UPPER bound, not an equality. Both of these are known, costed misfits, and landing a fix for
# one must not turn this gate red — but a third appearing is a new silent feature loss. Counted off the same
# de-duplicated list that is printed, because Mixin logs one line per TARGET: one mixin failing on two targets
# would otherwise show two entries and fail with a number nobody can match to them.
SOFTLIST=$(grep -aoE 'failed [^ ]+ from mod [A-Za-z0-9_.-]+' "$LOG" | sed 's/^failed //' | sort -u)
[ -n "$SOFTLIST" ] && echo "$SOFTLIST" | sed 's/^/[kernel]   /'
SOFT=$(printf '%s' "$SOFTLIST" | grep -c .)
if [ "$SOFT" -le 2 ]; then printf '[kernel] PASS soft-skipped mixins within the documented two (%s)\n' "$SOFT"
else printf '[kernel] FAIL soft-skipped mixins grew past the documented two (%s)\n' "$SOFT"; FAIL=1; fi

step "M2b result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M2b GATE GREEN — full fabric-api + a real third-party Fabric mod run on the sovereign kernel"
else
  echo "[kernel] ❌ GATE RED"
fi
exit "$FAIL"
