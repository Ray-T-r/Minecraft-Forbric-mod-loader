#!/usr/bin/env bash
# M31 — with ZERO mods, a Forbric world must be the world vanilla generates from the same seed.
#
# Everything else in this directory asks whether Forbric can RUN something. This asks whether it changed
# something nobody asked it to change. Nothing did ask, until 2026-09-21: the merged base computed
# nextDouble() in float (`l2f; fmul; f2d` where vanilla has `l2d; dmul`), which displaces every Perlin
# octave's origin, and no gate could see it because no gate had ever compared a Forbric world to a vanilla one.
#
# WHAT IS ASSERTED AND WHY IT IS ONLY TWO FACETS. Vanilla does not reproduce ITSELF at the block level: features
# that read a neighbouring chunk (dripstone, sculk) resolve by whichever chunk the worker pool finished first,
# and two runs of unmodified vanilla on this seed differ in 274 of their 400 fully generated chunks. So blocks,
# heightmaps and block entities are printed as evidence and asserted on by nobody. Biomes and structure starts
# carry no such noise — biomes are written at the `biomes` status from the climate sampler alone, before any
# feature runs — and there BOTH vanilla against itself and Forbric against itself agree on all 1764 chunks.
# The mob a dungeon spawner was built with is the same kind of fact and is asserted too: it caught the kernel
# drawing it through NeoForge's weighted data map (nextInt(400)) where vanilla draws nextInt(4) — the same
# distribution, a different mob on the same seed, and all six dungeons in this area disagreed.
#
# TEETH (recorded 2026-09-21): M31_UNFIXED=1 runs the Forbric arm with -Dforbric.randomSourcePrecision=off,
# which puts the float-rounded draw back. The biome check then reports 11 differing chunks and this gate is RED.
# GATE-PARALLEL: rundirs=parity-vanilla,parity-forbric mem=3500
set -uo pipefail
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

LOG_V="$BUILD/gate-m31-vanilla.log"
LOG_F="$BUILD/gate-m31-forbric.log"
REPORT="$BUILD/gate-m31-parity.txt"
DIR_V="$KERNEL/run/parity-vanilla"
DIR_F="$KERNEL/run/parity-forbric"
REGION="world/dimensions/minecraft/overworld/region"
# The square both arms force-load: blocks -128..127, i.e. chunks -8..7. 256 chunks is the per-command maximum.
FORCELOAD="forceload add -128 -128 127 127"
GENERATE_SECONDS="${M31_GENERATE_SECONDS:-150}"
mkdir -p "$BUILD"

MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
if [ ! -f "${VANILLA_JAR:-$MC/versions/26.2/26.2.jar}" ]; then
  echo "[kernel] FATAL: no vanilla 26.2 jar under $MC — this gate's control arm IS vanilla; there is nothing to"
  echo "[kernel]        compare against without it. Set VANILLA_JAR or MC_DIR."
  exit 3
fi

# One server.properties, written once and copied, so the two arms differ in nothing but their port. Writing it
# twice is how a parity gate ends up comparing two different worlds and calling the kernel guilty.
stage() {
  local dir="$1" port="$2"
  reap_stale_server "$dir"
  rm -rf "$dir/world" "$dir/logs" "$dir/.forbric-kernel"
  mkdir -p "$dir/mods"          # genuinely zero-mod on the Forbric side
  echo "eula=true" > "$dir/eula.txt"
  printf 'level-seed=forbrickernel\nlevel-name=world\nserver-port=%s\nonline-mode=false\nview-distance=6\nmax-tick-time=-1\nsync-chunk-writes=true\n' \
    "$port" > "$dir/server.properties"
}

# Boot, wait for Done, force-load the square, flush, stop. <log> <rundir> <command…>
boot() {
  local log="$1" dir="$2"; shift 2
  : > "$log"
  (
    for i in $(seq 1 300); do
      grep -aqE 'Done \(' "$log" && break
      grep -aqE 'Failed to start the minecraft server' "$log" && break
      sleep 1
    done
    sleep 5
    echo "$FORCELOAD"
    sleep "$GENERATE_SECONDS"
    echo save-all flush
    sleep 20
    echo stop
  ) | RUNDIR="$dir" "$@" > "$log" 2>&1 &
  local pid=$!
  record_server_pid "$dir" "$pid"
  await_server "$pid" "$log" $((360 + GENERATE_SECONDS)) 60
  rm -f "$dir/.forbric-gate.pid"
}

step "generate the same seed twice: once as pure vanilla, once through the kernel with zero mods"
stage "$DIR_V" "$GATE_PORT"
stage "$DIR_F" "$((GATE_PORT + 1))"
if ! diff <(grep -v '^server-port=' "$DIR_V/server.properties") <(grep -v '^server-port=' "$DIR_F/server.properties") >/dev/null; then
  echo "[kernel] FAIL the two arms were staged with different settings — nothing below would mean anything"; FAIL=1
fi
kernel_jar
boot "$LOG_V" "$DIR_V" "$KERNEL/run/launch-vanilla-server.sh"
if [ "${M31_UNFIXED:-0}" = 1 ]; then
  echo "[kernel] M31_UNFIXED=1 — the Forbric arm runs on the float-rounded nextDouble() this gate exists to catch"
  FORBRIC_JVM="-Dforbric.randomSourcePrecision=off" boot "$LOG_F" "$DIR_F" "$KERNEL/run/launch-kernel-server.sh"
else
  boot "$LOG_F" "$DIR_F" "$KERNEL/run/launch-kernel-server.sh"
fi

step "both arms reached Done, generated the square and saved it"
check "vanilla reached Done"            'Done \('                     "$LOG_V"
check "Forbric reached Done"            'Done \('                     "$LOG_F"
# The dimension is not named the same way on both sides — vanilla says "minecraft:overworld", Forbric says
# "Overworld". That is a real difference and a cosmetic one: it is the command's own feedback string, not
# anything the world is made of, and this check is about whether the square was requested at all. The count and
# the corners are what carry the meaning, so they are what is matched.
check "vanilla marked the square force-loaded" 'Marked 256 chunks in .*\[-8, -8\] to \[7, 7\]' "$LOG_V"
check "Forbric marked the square force-loaded" 'Marked 256 chunks in .*\[-8, -8\] to \[7, 7\]' "$LOG_F"
check "vanilla saved"                   'All dimensions are saved'    "$LOG_V"
check "Forbric saved"                   'All dimensions are saved'    "$LOG_F"

step "compare the two saved overworlds"
if [ ! -d "$DIR_V/$REGION" ] || [ ! -d "$DIR_F/$REGION" ]; then
  echo "[kernel] FAIL one of the arms saved no overworld region — nothing to compare"; FAIL=1
else
  python3 "$KERNEL/run/compat/world-parity.py" "$DIR_V/$REGION" "$DIR_F/$REGION" > "$REPORT" 2>&1
  cat "$REPORT"
fi

# `differ <facet>: N` lines, and the counted fields on the `chunks:` / `full:` lines.
field() { sed -nE "s/^$1: ([0-9]+).*/\1/p" "$REPORT" 2>/dev/null | head -1; }
counted() { sed -nE "s/.*[^a-z_]$1=([0-9]+).*/\1/p" "$REPORT" 2>/dev/null | head -1; }

if [ -s "$REPORT" ]; then
  # A comparison over an empty or half-generated pair of worlds reports 0 differences, which is the value that
  # means PASS. Both arms must have produced a real world, and the SAME set of chunks, before any 0 below counts.
  saved_v=$(sed -nE 's/^chunks: a=([0-9]+) .*/\1/p' "$REPORT" | head -1)
  saved_f=$(sed -nE 's/^chunks: a=[0-9]+ b=([0-9]+) .*/\1/p' "$REPORT" | head -1)
  assert_eq "both arms saved the same chunks"      "${saved_v:-0}" "${saved_f:-0}"
  assert_eq "no chunk exists on only one side"     "0 0" "$(counted only_a) $(counted only_b)"
  full_v=$(sed -nE 's/^full: a=([0-9]+) b=[0-9]+.*/\1/p' "$REPORT" | head -1)
  full_f=$(sed -nE 's/^full: a=[0-9]+ b=([0-9]+).*/\1/p' "$REPORT" | head -1)
  assert_eq "both arms fully generated the same number of chunks" "${full_v:-0}" "${full_f:-0}"
  if [ "${full_v:-0}" -lt 256 ]; then
    echo "[kernel] FAIL only ${full_v:-0} chunks reached full status — the square did not finish generating;"
    echo "[kernel]      raise M31_GENERATE_SECONDS (currently $GENERATE_SECONDS) rather than trusting the zeros below"
    FAIL=1
  else
    echo "[kernel] PASS the square generated (${full_v} full chunks on each side)"
  fi

  # The two assertions. Everything else in the report is evidence.
  assert_eq "every chunk carries vanilla's biomes"  "0" "$(field 'differ biomes')"
  assert_eq "every chunk carries vanilla's structure starts" "0" "$(field 'differ structures')"
  assert_eq "every dungeon spawns vanilla's mob"     "0" "$(field 'differ spawner_mobs')"
  echo "[kernel] evidence (not asserted — vanilla does not reproduce itself here):" \
       "heightmaps=$(field 'differ heightmaps') blocks=$(field 'differ blocks') block_entities=$(field 'differ block_entities')"
else
  echo "[kernel] FAIL the comparison produced no report at $REPORT"; FAIL=1
fi

step "M31 result"
if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M31 VANILLA PARITY GATE GREEN — zero mods, same seed: same biomes, same structures as vanilla 26.2"
else
  echo "[kernel] ❌ M31 GATE RED — vanilla $LOG_V / Forbric $LOG_F / report $REPORT"
fi
exit "$FAIL"
