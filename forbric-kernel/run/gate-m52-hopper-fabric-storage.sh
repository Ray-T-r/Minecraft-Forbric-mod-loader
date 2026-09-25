#!/usr/bin/env bash
# M52 — hoppers move items into and out of Fabric item storages NeoForge's hopper cannot see.
#
# fabric-transfer-api-v1's HopperBlockEntityMixin asks ItemStorage.SIDED after vanilla's getAttachedContainer and
# getSourceContainer; the merged hopper is NeoForge's, which calls neither, so it never attached. NeoForge sees a
# Fabric storage only through the kernel's capability bridge, which exposes slotted storages on block entities: an
# unslotted storage, one on a block with no block entity, or any with the bridge off, never met a hopper.
# HopperFabricStorageInjector runs Fabric's own lookup on NeoForge's two found-nothing branches. A dedicated server
# with the unmodified fabric-api and a Fabric mod (canary/hopper-storage) with three storages: into and out of each
# (one step driven directly — one item, cooldown 8, the face Fabric asks — then left to the world's ticking), a face
# the storage refuses, a hopper locked by redstone, a storage refilled later through Fabric's API, an empty
# block-only storage that must stop the hopper picking up the item lying on it, and vanilla chests.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings, fabric-transfer's hopper mixin superseded.
#   2. off — -Dforbric.hopperFabricStorage=off: exactly the unslotted and block-only moves (and the pickup stop) fail;
#      the slotted storage, refused faces, locked hoppers and vanilla chests hold.
#   3. bridge-off — -Dforbric.transferBridge=off, STRICT: every case passes, the slotted one now through the fallback.
#   4. lithium — positive with lithium-neoforge from the merged pack, whose own hopper mixins land on the same methods:
#      every case passes and nothing fails to verify.
# GATE-PARALLEL: rundirs=server-hopper-m52 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-hopper-m52"
RESULTS="$BUILD/verification/m52-hopper-fabric-storage"
FAIL=0
FAPI="$KERNEL/run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar"
LITHIUM="$(ls "$KERNEL"/run/client-merged-pack/mods/lithium-neoforge-*.jar 2>/dev/null | head -1)"
LOST="{'unslotted.insert.step', 'unslotted.insert.drain', 'unslotted.extract.step', 'unslotted.extract.drain', 'unslotted.refill', 'blockonly.insert.step', 'blockonly.insert.drain', 'blockonly.extract.step', 'blockonly.extract.drain', 'blockonly.refill', 'blockonly.emptyBlocksPickup'}"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-hopper-storage-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags> [extra mod jar]
run_server() {
  local phase="$1" policy="$2" extra="$3" jar="${4:-}" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$FAPI" "$KERNEL/run/canary/forbrichopper.jar" $jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\nspawn-animals=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.hopperProbe=$RESULTS/$phase.json -Dforbric.hopperPhase=$phase $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 300 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
  cp "$SERVER_DIR/.forbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json" 2>/dev/null || true
}

# judge <phase> <python expression over `failed`, `premise`> <what>
judge() {
  local phase="$1" rule="$2" what="$3"
  check "$phase: the server started" 'Done \(' "$RESULTS/$phase.log"
  if python3 - "$RESULTS/$phase.json" "$phase" "$rule" <<'PY'
import json, sys
report, phase, rule = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
assert report['phase'] == phase, report['phase']
cases = {c['name']: c for c in report['cases']}
assert len(cases) == 24, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
premise = report['premise']
print(f"[kernel]   {phase}: premise {premise}")
assert eval(rule, {'failed': failed, 'cases': cases, 'premise': premise}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

# resolved <phase> — fabric-transfer's three hopper rows are RESOLVED and nothing confirmed-required stands.
resolved() {
  local phase="$1"
  if python3 - "$RESULTS/$phase-compatibility.json" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
assert report['confirmedRequired'] == 0, report['confirmedRequired']
findings = report.get('findings') or [f for m in report.get('mods', []) for f in m.get('findings', [])]
rows = [f for f in findings if 'fabric.mixin.transfer.HopperBlockEntityMixin' in f['id']]
assert rows, 'no fabric-transfer hopper rows'
assert all(f['confidence'] == 'RESOLVED' for f in rows), [(f['id'], f['confidence']) for f in rows]
PY
  then echo "[kernel] PASS $phase: zero confirmed required findings; fabric-transfer's hopper rows are resolved"
  else echo "[kernel] FAIL $phase: STRICT report missing, confirmed required findings, or hopper rows unresolved"; FAIL=1; fi
}

step "1. positive: hoppers reach every Fabric storage, NeoForge-visible or not"
run_server positive strict ""
judge positive "not failed and premise['slotted.neoSees'] is True and premise['unslotted.neoSees'] is False and premise['blockonly.neoSees'] is False and premise['unslottedIsSlotted'] is False" "all 24 cases pass; NeoForge sees only the slotted store"
resolved positive
check "positive: the hopper asks Fabric's lookup" 'Hopper\] HopperBlockEntity.ejectItems and suckInItems ask Fabric' "$RESULTS/positive.log"
check "positive: fabric-transfer's hopper mixin is superseded" 'net.fabricmc.fabric.mixin.transfer.HopperBlockEntityMixin is superseded' "$RESULTS/positive.log"
check "positive: an unslotted store went through Fabric's lookup" "Hopper\] a hopper .* Fabric storage net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage through Fabric's own lookup" "$RESULTS/positive.log"
check "positive: a block-only store went through Fabric's lookup" "Hopper\] a hopper .* Fabric storage forbric.hopper.Stores.* through Fabric's own lookup" "$RESULTS/positive.log"
check_absent "positive: no verifier error" 'VerifyError|Exception stopping' "$RESULTS/positive.log"

step "2. off: the same server with the hopper left as merged"
run_server off continue "-Dforbric.hopperFabricStorage=off"
judge off "failed == $LOST" "exactly the unslotted and block-only cases fail; slotted, refused faces, locked hoppers and chests hold"
check_absent "off: nothing asks Fabric's lookup" 'Hopper\] HopperBlockEntity.ejectItems and suckInItems ask Fabric' "$RESULTS/off.log"
check_absent "off: fabric-transfer's hopper mixin is not superseded" 'net.fabricmc.fabric.mixin.transfer.HopperBlockEntityMixin is superseded' "$RESULTS/off.log"

step "3. bridge-off: every Fabric storage through the hopper's own fallback"
run_server bridge-off strict "-Dforbric.transferBridge=off"
judge bridge-off "not failed and premise['slotted.neoSees'] is False" "all 24 cases pass with NeoForge seeing none of the stores"
resolved bridge-off
check "bridge-off: the slotted store went through Fabric's lookup" "Hopper\] a hopper .* Fabric storage forbric.hopper.Stores.* through Fabric's own lookup" "$RESULTS/bridge-off.log"

step "4. lithium: lithium-neoforge's hopper mixins on the same methods"
if [ -z "$LITHIUM" ]; then
  echo "[kernel] FAIL lithium: no lithium-neoforge jar in run/client-merged-pack/mods"; FAIL=1
else
  run_server lithium continue "" "$LITHIUM"
  judge lithium "not failed" "all 24 cases pass beside lithium's hopper mixins"
  check "lithium: lithium is loaded" 'lithium' "$RESULTS/lithium.log"
  check_absent "lithium: no verifier error" 'VerifyError|Exception stopping' "$RESULTS/lithium.log"
fi

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M52 HOPPER GATE GREEN — hoppers move items into and out of every Fabric item storage"
else
  echo "[kernel] ❌ M52 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
