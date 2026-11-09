#!/usr/bin/env bash
# M61: fabric-block-api's and Create Fly's cancellable HEAD handlers on vanilla's LiquidBlock.shouldSpreadLiquid run on a
# neighbour change too, where native Fabric runs them -- run/compat/fluid-head-gate.py (canary/fluidhead) wired into the
# gate list (D61).
#
# NeoForge's onPlace asks vanilla's shouldSpreadLiquid, its neighborChanged asks NeoForge's fluid interaction registry,
# so without MixinFluidReactionAdapter's HEAD wrap (-Dforbric.fluidReactionNeighbourChange.head) the handlers run on
# placement alone. The driver runs two servers on GATE_PORT and GATE_PORT+1: head-off must fail exactly the three
# neighbour-change cases (neighbor.veto, neighbor.veto.reaction, create.neighbor.reaction) and announce no wrap; the fixed
# run (strict) must pass all six and announce the wrap of both mixins. The driver raises on anything else; this gate
# reads its per-phase lines and the lost-port marker of both servers.
#
# Inputs (each overridable, none downloaded): fabric-api 0.161.0+26.2 (fabric-block-api 3.1.0, the first with the veto)
# and Create Fly, from the compatibility inputs this tree's build/ links to.
# GATE-PARALLEL: mem=3500
set -uo pipefail
GATE_PORT="${GATE_PORT:-25656}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
FAPI="${M61_FABRIC_API:-$BUILD/purefabric-2026-10-03/mods/fabric-api-0.161.0+26.2.jar}"
CREATE="${M61_CREATE:-$BUILD/compat-inputs/create-fly/create-fly.jar}"
RESULTS="$BUILD/verification/m61-fluid-head"
OUT="$RESULTS/run-$(date +%Y%m%d-%H%M%S)-$$"
LOG="$OUT.log"
mkdir -p "$RESULTS"

step "inputs"
for input in "$FAPI" "$CREATE"; do
  if [ -f "$input" ]; then echo "[kernel] PASS input present: $input"
  else echo "[kernel] FAIL input missing: $input (set M61_FABRIC_API / M61_CREATE)"; exit 1; fi
done

step "fluid-head-gate.py: head-off, then fixed"
rc=0
GATE_PORT="$GATE_PORT" python3 "$KERNEL/run/compat/fluid-head-gate.py" --fabric-api "$FAPI" --create "$CREATE" \
  --staged-root "$RUN_OLD" --output "$OUT" > "$LOG" 2>&1 || rc=$?
cat "$LOG"
for phase in off fixed; do port_was_free "$OUT/$phase/console.log"; done
if [ "$rc" -eq 0 ]; then echo "[kernel] PASS the driver accepted both runs (exit 0)"
else echo "[kernel] FAIL the driver stopped with exit $rc — see $LOG"; FAIL=1; fi
check_exactly "head-off passed exactly the three placement and un-vetoed cases and wrapped nothing" "^off: 3/6 cases passed, wraps \[\]$" "$LOG" 1
check_exactly "fixed passed all six cases" "^fixed: 6/6 cases passed, wraps " "$LOG" 1
check "the driver's verdict" "^fluid-head gate: PASS$" "$LOG"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M61 FLUID HEAD GATE GREEN — 6/6 fixed, head-off red on exactly the three neighbour-change cases"
else
  echo "[kernel] ❌ M61 GATE RED — $OUT"
fi
exit "$FAIL"
