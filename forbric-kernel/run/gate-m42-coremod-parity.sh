#!/usr/bin/env bash
# M42 — what NeoForge's own coremods do to the game, done by the kernel.
#
# NeoForge's coremods (FML transformers) do not run under Forbric, and the kernel does their work (NativeCoremodParity).
# A dedicated server with a probe mod drives the game paths a player's actions reach and records each verdict
# (canary/coremod-parity), 21 cases:
#   flower pots — vanilla (plant, take, pick empty and full, a non-plant), a NeoForge-declared pot (plant, take, pick), a
#   plant declared only through addPlant (a no-op on NeoForge: never potted), NeoForge's getFullPot;
#   spawn finalization of a counting probe mob — from EntityType.spawn and /summon NeoForge's FinalizeSpawnEvent once and
#   exactly one finalization carrying its data, a NeoForge cancel (no finalization, mob still spawned), a NeoForge veto
#   (no mob); a zombie drowning into a drowned, heard by the probe's own LivingConversionEvent.Post listener;
#   a NeoForge biome modifier (temperature, water colour, and what the network codec sends a client); a NeoForge
#   structure modifier; a Fabric weather change through the unmodified fabric-biome-api, which must survive NeoForge's
#   pass; and a biome climate replaced after the pass, which must win over it as it would on Fabric.
#
#   1. positive — STRICT, every case passes, zero confirmed required findings.
#   2. off — -Dforbric.coremodParity=off: every repaired case fails; only the two Fabric climates (read raw), drown.neo
#      (NeoForge's own Zombie conversion lambdas post the event) and pot.neoforge.api (NeoForge's own getFullPot) hold.
#   3. rebase-off — -Dforbric.biomeRebase=off: everything passes except the two Fabric climates, so the pass
#      bookkeeping (rebase and pass-time record) is what keeps them.
#   4. withhold-conversion — -Dforbric.coremodParityWithholdConversionPost=true takes the probe's own NeoForge
#      LivingConversionEvent.Post listener away: exactly drown.neo fails, so that case can fail (no repair is involved).
# Not covered here: the trial spawner and natural or structure spawns (no such spawn runs in this world), and a rendered
# client.
#
# STAGE 4 OF DROPPING MinecraftForge (what changed and why). On the merged base this gate also judged MinecraftForge's
# identical coremods: MinecraftForge's spawn events and cancel, a NeoForge veto a MinecraftForge listener cleared, and
# the liquid block's getter (fluid.water), whose own phase 2b (-Dforbric.liquidBlockFluid=off, retired) read
# LiquidBlock's constructors out of the merged base's bytecode. Those cases left the probe with the MinecraftForge
# carrier (22 -> 21) and phase 2b and the base read went with them (D3). drown.neo and pot.neoforge.api hold with the
# repairs off because NeoForge's own body answers them on its own base (measured in C3).
# GATE-PARALLEL: rundirs=server-coremod-m42 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-coremod-m42"
RESULTS="$BUILD/verification/m42-coremod-parity"
FAIL=0
refuse_shared_rundir "$SERVER_DIR"
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-coremod-parity-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs"
  mkdir -p "$SERVER_DIR/mods"
  cp "$KERNEL/run/canary/forbriccoremodparity.jar" "$KERNEL"/run/canary/m42-modules/*.jar "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.coremodProbe=$RESULTS/$phase.json -Dforbric.coremodPhase=$phase $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 240 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
  cp "$SERVER_DIR/.forbric-kernel/compatibility-report.json" "$RESULTS/$phase-compatibility.json" 2>/dev/null || true
}

# judge <phase> <python expression over `failed`, the set of failed case names>
judge() {
  local phase="$1" rule="$2" what="$3"
  check "$phase: the server started and stopped" 'Done \(' "$RESULTS/$phase.log"
  if python3 - "$RESULTS/$phase.json" "$phase" "$rule" <<'PY'
import json, sys
report, phase, rule = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
assert report['phase'] == phase, report['phase']
cases = {c['name']: c for c in report['cases']}
assert len(cases) == 21, sorted(cases)
failed = {name for name, c in cases.items() if not c['pass']}
for name in sorted(failed): print(f"[kernel]   {phase}: {name} failed — {cases[name]['detail'][:240]}")
assert eval(rule, {'failed': failed, 'cases': cases}), (phase, sorted(failed))
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: every coremod path behaves as on the native loaders"
run_server positive strict ""
judge positive "not failed" "all 21 cases pass"
if python3 -c "import json,sys; r=json.load(open(sys.argv[1])); sys.exit(0 if r['confirmedRequired']==0 else 1)" "$RESULTS/positive-compatibility.json" 2>/dev/null
then echo "[kernel] PASS positive: zero confirmed required findings under STRICT"
else echo "[kernel] FAIL positive: STRICT report missing or has confirmed required findings"; FAIL=1; fi

step "2. off: the same server with the repairs switched off"
run_server off continue "-Dforbric.coremodParity=off"
judge off "failed == set(cases) - {'biome.fabric.keep', 'biome.late.keep', 'drown.neo', 'pot.neoforge.api'}" "every repaired case fails; only the raw Fabric climates and NeoForge's own drown.neo and getFullPot hold"

step "3. rebase-off: NeoForge's pass from the biome's original info"
run_server rebase-off continue "-Dforbric.biomeRebase=off"
judge rebase-off "failed == {'biome.fabric.keep', 'biome.late.keep'}" "only the Fabric climates are lost"

step "4. withhold-conversion: the conversion case without its listener"
run_server withhold-conversion continue "-Dforbric.coremodParityWithholdConversionPost=true"
judge withhold-conversion "failed == {'drown.neo'}" "only drown.neo fails: the case hears NeoForge's own conversion Post"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M42 COREMOD PARITY GATE GREEN — pots, finalization, conversion and modifiers behave natively, each by its repair"
else
  echo "[kernel] ❌ M42 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
