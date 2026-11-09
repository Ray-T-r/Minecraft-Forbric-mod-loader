#!/usr/bin/env bash
# M60: three Fabric-on-NeoForge adaptations measured in dedicated servers, each with its switch off as a red control --
# run/compat/regress-gate.py (canary/regress) wired into the gate list (D61).
#
# fabric-item-api's CustomDamageHandler (FabricItemDamageMixinAdapter, -Dforbric.itemDamageHandler), BCLib's tag shears
# on sheep and snow golems (MixinShearsRelay's mob rows, -Dforbric.shearsRelayMobs) and fabric-item-api's stack-aware
# furnace remainder, which NeoForge's consumeFuel already honours (SupersededMixins resolves its report rows,
# -Dforbric.supersededMixins). The driver runs four servers on GATE_PORT..GATE_PORT+3: the fixed run must pass all 15
# cases with the watched mixins unsuspected, and each switch-off run must fail exactly its cases (item-damage-off the two
# damage.* handler cases, shears-mobs-off the three tag-shears cases, superseded-off none) and show its mixin suspected
# again. The driver raises on anything else; this gate reads its per-phase lines and the lost-port marker of every server.
#
# Inputs (each overridable, none downloaded): fabric-api 0.155.2+26.2, BCLib 26.201.2 and its dependencies WorldWeaver
# 26.201.2 and WunderLib 26.201.0, from the compatibility inputs this tree's build/ links to.
# GATE-PARALLEL: mem=2500
set -uo pipefail
GATE_PORT="${GATE_PORT:-25650}"
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
FAPI="${M60_FABRIC_API:-$KERNEL/run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar}"
BCLIB="${M60_BCLIB:-$BUILD/compat-inputs/sweep90/mods/bclib-26.201.2.jar}"
WORLDWEAVER="${M60_WORLDWEAVER:-$BUILD/compat-inputs/sweep90/mods/worldweaver-26.201.2.jar}"
WUNDERLIB="${M60_WUNDERLIB:-$BUILD/purefabric-2026-10-03/mods/wunderlib-26.201.0.jar}"
RESULTS="$BUILD/verification/m60-regress"
OUT="$RESULTS/run-$(date +%Y%m%d-%H%M%S)-$$"
LOG="$OUT.log"
mkdir -p "$RESULTS"

step "inputs"
for input in "$FAPI" "$BCLIB" "$WORLDWEAVER" "$WUNDERLIB"; do
  if [ -f "$input" ]; then echo "[kernel] PASS input present: $input"
  else echo "[kernel] FAIL input missing: $input (set M60_FABRIC_API / M60_BCLIB / M60_WORLDWEAVER / M60_WUNDERLIB)"; exit 1; fi
done

step "regress-gate.py: fixed, item-damage-off, shears-mobs-off, superseded-off"
rc=0
python3 "$KERNEL/run/compat/regress-gate.py" --fabric-api "$FAPI" --bclib "$BCLIB" --mod "$WORLDWEAVER" --mod "$WUNDERLIB" \
  --staged-root "$RUN_OLD" --port "$GATE_PORT" --output "$OUT" > "$LOG" 2>&1 || rc=$?
cat "$LOG"
for phase in fixed item-damage-off shears-mobs-off superseded-off; do port_was_free "$OUT/$phase/console.log"; done
if [ "$rc" -eq 0 ]; then echo "[kernel] PASS the driver accepted every phase (exit 0)"
else echo "[kernel] FAIL the driver stopped with exit $rc — see $LOG"; FAIL=1; fi
check_exactly "fixed: every case passed" "^fixed: 15/15 cases passed$" "$LOG" 1
check_exactly "item-damage-off failed exactly the two damage handler cases" "^item-damage-off: 13/15 cases passed, failed exactly \['damage\.mob', 'damage\.player'\]$" "$LOG" 1
check_exactly "shears-mobs-off failed exactly the three tag-shears cases" "^shears-mobs-off: 12/15 cases passed, failed exactly \['shears\.sheep\.ownEntry\.fabric', 'shears\.sheep\.tag', 'shears\.snowGolem\.tag'\]$" "$LOG" 1
check_exactly "superseded-off failed no case (its control is the furnace mixin's report rows)" "^superseded-off: 15/15 cases passed$" "$LOG" 1
check "the driver's verdict" "^Regress gate passed: " "$LOG"

if [ "$FAIL" -eq 0 ]; then
  echo "[kernel] ✅ M60 REGRESS GATE GREEN — 15/15 fixed, each switch-off run red on exactly its cases"
else
  echo "[kernel] ❌ M60 GATE RED — $OUT"
fi
exit "$FAIL"
