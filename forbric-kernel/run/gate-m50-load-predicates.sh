#!/usr/bin/env bash
# M50 — lithostitched's load predicates are read again with its Fabric build installed.
#
# lithostitched (Fabric build) skips a registry entry whose "predicate" fails, from an @Inject on vanilla's
# Decoder.parse in RegistryLoadTask.PendingRegistration.loadFromResource. NeoForge's merged body decodes through
# ConditionalOps' Codec.parse — the same method, which Mixin's owner match did not see — so every gated entry loaded:
# datapack and mod content meant only for when another mod is present (Tectonic's compat modifiers) always applied,
# giving unwanted worldgen or a registry error that blocks the world. MixinSubtypeOwnerRetarget moves the anchor to
# that one Codec.parse. A dedicated server with the unmodified lithostitched Fabric jar (and fabric-api, which it uses
# undeclared) and a NeoForge mod whose data has
# three configured features: gated by a failing predicate (an absent mod), gated by a holding one (the mod itself),
# and plain (canary/load-predicates).
#
#   1. positive — the failing one is skipped, the other two load.
#   2. off — -Dforbric.mixinSubtypeOwner=off: the failing one loads too.
# GATE-PARALLEL: rundirs=server-predicates-m50 mem=2000
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SERVER_DIR="$KERNEL/run/server-predicates-m50"
RESULTS="$BUILD/verification/m50-load-predicates"
FAIL=0
rm -rf "$RESULTS"; mkdir -p "$RESULTS"

kernel_jar
bash "$KERNEL/run/build-load-predicates-canary.sh" > "$RESULTS/build.log" 2>&1 || { cat "$RESULTS/build.log"; exit 1; }

# run_server <phase> <policy> <extra jvm flags>
run_server() {
  local phase="$1" policy="$2" extra="$3" pid
  mkdir -p "$SERVER_DIR"
  reap_stale_server "$SERVER_DIR"
  rm -rf "$SERVER_DIR/world" "$SERVER_DIR/mods" "$SERVER_DIR/.forbric-kernel" "$SERVER_DIR/logs" "$SERVER_DIR/config"
  mkdir -p "$SERVER_DIR/mods"
  # lithostitched's Fabric build uses fabric-api's registry builder without declaring it, so fabric-api goes in too.
  cp "$KERNEL/run/canary/forbricpredicates.jar" "$KERNEL/run/client-merged-pack/mods/lithostitched-1.7.13-fabric-26.2.jar" \
    "$KERNEL/run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar" "$SERVER_DIR/mods/"
  echo "eula=true" > "$SERVER_DIR/eula.txt"
  seed_server_properties "$SERVER_DIR"
  printf 'level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nmax-tick-time=-1\npause-when-empty-seconds=0\nonline-mode=false\n' >> "$SERVER_DIR/server.properties"
  RUNDIR="$SERVER_DIR" FORBRIC_COMPAT_POLICY="$policy" \
    FORBRIC_JVM="-Dforbric.predicateProbe=$RESULTS/$phase.json -Dforbric.predicatePhase=$phase $extra" \
    "$KERNEL/run/launch-kernel-server.sh" < /dev/null > "$RESULTS/$phase.log" 2>&1 &
  pid=$!; record_server_pid "$SERVER_DIR" "$pid"
  await_server "$pid" "$RESULTS/$phase.log" 240 30
  rm -f "$SERVER_DIR/.forbric-gate.pid"
}

# judge <phase> <gated expected present?> <what>
judge() {
  local phase="$1" gated="$2" what="$3"
  check "$phase: the server started" 'Done \(' "$RESULTS/$phase.log"
  if python3 - "$RESULTS/$phase.json" "$phase" "$gated" <<'PY'
import json, sys
report, phase, gated = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3] == 'true'
print(f"[kernel]   {phase}: {report}")
assert report['phase'] == phase and report['gated'] is gated and report['kept'] is True and report['plain'] is True, report
PY
  then echo "[kernel] PASS $phase: $what"
  else echo "[kernel] FAIL $phase: $what (see $RESULTS/$phase.json)"; FAIL=1; fi
}

step "1. positive: a failing lithostitched predicate keeps its entry out"
run_server positive continue ""
judge positive false "the failing predicate's entry is skipped; the holding one and the plain one load"
check "positive: the predicate check moved to Codec.parse" 'RegistryLoadTaskMixin: loadFromResource → Codec.parse' "$RESULTS/positive.log"

step "2. off: the same server with the retarget switched off"
run_server off continue "-Dforbric.mixinSubtypeOwner=off"
judge off true "the failing predicate's entry loads anyway"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M50 LOAD PREDICATES GATE GREEN — lithostitched's Fabric build keeps gated entries out"
else
  echo "[kernel] ❌ M50 GATE RED — inspect $RESULTS"
fi
exit "$FAIL"
