#!/usr/bin/env bash
# M62: Carpet 26.2+v260616's own behaviour on NeoForge's base -- run/compat/carpet-gate.py (canary/carpet) wired into
# the gate list (D61).
#
# 34 behaviour checks in three dedicated servers on GATE_PORT..GATE_PORT+2. baseline (-Dforbric.playerWorldCallbacks=off)
# must fail exactly the 13 checks the player/world callback adapters restore (fill shape updates, the renewable
# blackstone and deepslate rules on a neighbour change, both Scarpet events and their native-Fabric order) while
# vanilla's own lava/water reactions pass, and must show Level_fillUpdatesMixin and ServerPlayerGameMode_scarpetEventsMixin
# suspected in the report; fixed (strict) must pass all 34 with no confirmed Carpet loss and none of the five adapted
# mixins left suspected; neighbour-off (-Dforbric.fluidReactionNeighbourChange=off) must fail exactly the two
# neighbour-change rule checks. The driver raises on anything else; this gate reads its per-phase lines and the lost-port
# marker of every server.
#
# Input (overridable, never downloaded): the unmodified fabric-carpet-26.2+v260616.jar from the compatibility inputs
# this tree's build/ links to.
# GATE-PARALLEL: mem=2500
set -uo pipefail
GATE_PORT="${GATE_PORT:-25660}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
CARPET="${M62_CARPET:-$BUILD/compat-inputs/carpet/mods/fabric-carpet-26.2+v260616.jar}"
RESULTS="$BUILD/verification/m62-carpet"
OUT="$RESULTS/run-$(date +%Y%m%d-%H%M%S)-$$"
LOG="$OUT.log"
mkdir -p "$RESULTS"

step "input"
if [ -f "$CARPET" ]; then echo "[kernel] PASS input present: $CARPET"
else echo "[kernel] FAIL input missing: $CARPET (set M62_CARPET)"; exit 1; fi

step "carpet-gate.py: baseline, fixed, neighbour-off"
rc=0
python3 "$KERNEL/run/compat/carpet-gate.py" --carpet "$CARPET" --staged-root "$RUN_OLD" --port "$GATE_PORT" \
  --output "$OUT" > "$LOG" 2>&1 || rc=$?
cat "$LOG"
for phase in baseline fixed neighbour-off; do port_was_free "$OUT/$phase/console.log"; done
if [ "$rc" -eq 0 ]; then echo "[kernel] PASS the driver accepted every run (exit 0)"
else echo "[kernel] FAIL the driver stopped with exit $rc — see $LOG"; FAIL=1; fi
check_exactly "baseline (callback adapters off) failed exactly the 13 Carpet checks" "^baseline: 21/34 behavior checks passed$" "$LOG" 1
check_exactly "fixed passed all 34 checks" "^fixed: 34/34 behavior checks passed$" "$LOG" 1
check_exactly "neighbour-off failed exactly the two neighbour-change rule checks" "^neighbour-off: 32/34 behavior checks passed$" "$LOG" 1
check "the driver's verdict" "^Carpet behavior gate passed: " "$LOG"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M62 CARPET GATE GREEN — 34/34 fixed, baseline 21/34 and neighbour-off 32/34 exactly as predicted"
else
  echo "[kernel] ❌ M62 GATE RED — $OUT"
fi
exit "$FAIL"
