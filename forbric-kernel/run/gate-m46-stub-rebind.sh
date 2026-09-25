#!/usr/bin/env bash
# M46 — a Fabric mod's injector that Mixin binds to a merge-added delegating stub reaches the body again.
#
# Mixin binds a selector without a descriptor to the FIRST declared method of that name. A carrier that widened a vanilla
# method kept vanilla's signature in front as a stub — Player.getDestroySpeed(BlockState) forwards to NeoForge's
# getDestroySpeed(BlockState, BlockPos), which is what the game calls — so a Fabric mod's injection landed on a method
# nothing calls (architectury's and Collective's break-speed events never fired), and an anchor inside the body was
# simply missing (Fabric API's elytra check reads a field only canGlide(boolean) has). MixinStubRebind moves a
# Fabric mod's injector along carrier-stubs.txt to the body, wrapping a handler that captures the stub's arguments.
# A dedicated server with a Fabric mixin mod compiled against vanilla (canary/stub-rebind/fabric-mixins: a name-only
# RETURN on getDestroySpeed capturing the state, sevenfold on sponge; a name-only HEAD on randomTeleport, cancelling for
# a tagged entity), the unmodified fabric-entity-events-v1, and a NeoForge driver that calls the methods the way the
# merged game does:
#   dirt speed (control), sponge speed through the moved and wrapped injection; an untagged teleport (control), a tagged
#   one cancelled through the moved injection; a language file read through vanilla's rewrite (control) and the mod's
#   prefixed format kept through malilib's @ModifyArgs shape, whose stub passes a non-capturing lambda (a constant) to
#   NeoForge's three-argument body, and an object entry still reaching the component consumer. Fabric API's elytra check has ONE owner: the rebind runs after every
#   specific adapter, so FabricEntityMixinAnchors has already put it at NeoForge's gliding decision (M37 proves the
#   callbacks) and the rebind must leave it alone — moving it too would bind it twice.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. off — -Dforbric.mixinStubRebind=off: exactly the three moved cases fail and the controls hold.
# Not covered here: a client (litematica, Sodium, Iris and the model-loading API's moves are client-side).
# GATE-PARALLEL: rundirs=server-stub-m46 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-stub-m46"
RESULTS="$BUILD/verification/m46-stub-rebind"
FAIL=0
REPAIRED="{'speed.rebound', 'teleport.rebound', 'language.rebound'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-stub-rebind-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
# The probe's config name starts with "forbric", which the kernel reserves for its own never-relaxed configs; it is
# relaxed by name here, as every guest mod's config is, so an unmoved injector soft-skips as malilib's does.
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbricstubdriver.jar" "$KERNEL/run/canary/forbricstubmixins.jar" "$KERNEL"/run/canary/m46-modules/*.jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.stubProbe=$RESULTS/$phase.json -Dforbric.stubPhase=$phase -Dforbric.relaxMixinOverwrites=forbricstubmixins.mixins.json $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 240 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
  cp "$SERVER_DIR/.forbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json" 2>/dev/null || true
}

# judge <phase> <python expression over `failed`> <what>
judge() {
  local phase="$1" rule="$2" what="$3"
  check "$phase: the server started" 'Done \(' "$RESULTS/$phase.log"
  if python3 - "$RESULTS/$phase.json" "$phase" "$rule" <<'PY'
import json, sys
report, phase, rule = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
assert report['phase'] == phase, report['phase']
cases = {c['name']: c for c in report['cases']}
assert len(cases) == 7, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: the Fabric injections reach the carriers' bodies"
run_server positive strict ""
judge positive "not failed" "all 7 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi
check "positive: the probe's getDestroySpeed injection moved (wrapped)" 'forbric\$sevenfoldOnSponge.* now targets net.minecraft.world.entity.player.Player.getDestroySpeed\(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;\)F' "$RESULTS/positive.log"
check "positive: fabric-api's elytra check restored at NeoForge's gliding decision (and its flight tick)" 'restored 2 entity callback anchor\(s\) in net.fabricmc.fabric.mixin.entity.event.elytra.LivingEntityMixin' "$RESULTS/positive.log"
check_absent "positive: the rebind leaves fabric-api's elytra check to that one owner" 'injectElytraCheck now targets' "$RESULTS/positive.log"
check "positive: the probe's randomTeleport injection moved" 'forbric\$pinned now targets net.minecraft.world.entity.LivingEntity.randomTeleport\(DDDZLnet/minecraft/world/item/ItemStack;\)Z' "$RESULTS/positive.log"
check "positive: the probe's language @ModifyArgs moved past the lambda stub" 'forbric\$keepFormat now targets net.minecraft.locale.Language.loadFromJson\(Ljava/io/InputStream;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;\)V' "$RESULTS/positive.log"

step "2. off: the same server with the rebind switched off"
run_server off continue "-Dforbric.mixinStubRebind=off"
judge off "failed == $REPAIRED" "exactly the moved cases fail; the controls hold"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M46 STUB-REBIND GATE GREEN — Fabric injections bound to carrier stubs reach the bodies"
else
  echo "[kernel] ❌ M46 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
