# Stage 4 C3, lane "regress-server": the genuine-base Fabric losses on a server, adapted or measured not lost

Branch `s4-c3`. Lane commits 95a2f1de, b2b3e240, a162172a, ee1aed96 on ca371ff0; review fixes bdf7524a, e3da7384,
54a4bce4, 3f02c16d, 7ff223f9, e3259814. This lane deleted nothing. It took the server-side items C1 recorded as Fabric
losses on the genuine base (c1 walls W5, Q8 and the (d) rows of the C1 failure list) and either adapted them or measured
them as not lost. Each fix is Fabric-on-NeoForge adaptation code with its own switch; DECISIONS D35 (BCLib shears) and D36
(hookDamage, Carpet neighbour change) accept them as adaptations, not losses. This file was written by the Stage 4 record
lane from the lane's commit messages, checks rows and logs, which otherwise lived only in a session scratchpad.

Names: G = genuine stage (`run/neoforge-patched/patched-mc-neoforge-26.2.jar` + `run/neoforge-runtime/neoforge-runtime.jar`,
NeoForge 26.2.0.93); "native" = vanilla 26.2 (`.stage4/minecraft/versions/26.2/26.2.jar`) plus the Fabric mod's own
bytecode, and for Carpet the canary's recorded native Fabric 0.19.5 expectations. Heavy commands went through
`.stage/heavy.sh`; `MC_DIR` was never set; ports 46181-46183 for the gates.

## Results

| item | verdict | native (expected) | before on G | after on G | switch / red control | commit |
|---|---|---|---|---|---|---|
| Carpet renewable blackstone / deepslate on a neighbour change (21 (d) rows) | REGRESSION-FIX | vanilla `LiquidBlock.onPlace` and `neighborChanged` each call `shouldSpreadLiquid` at offset 4, so Carpet's TAIL and `FluidState.isSource` handlers run on both | carpet canary 25/27: `fluid.blackstone.neighbor` "result=Block{minecraft:lava}[level=0]", `fluid.deepslate.neighbor` "result=Block{minecraft:cobblestone}"; placement cases pass natively (G's onPlace calls `shouldSpreadLiquid`) | `run/compat/carpet-gate.py` on G: baseline 14/27 = exactly the Carpet set, fixed 27/27 STRICT; after the review's neighbour twins 21/34, 34/34, 32/34 | `-Dforbric.fluidReactionNeighbourChange=off`: exactly the two neighbour cases fail | 95a2f1de (+ e3259814) |
| fabric-item-api `ItemStackMixin.hookDamage` | REGRESSION-FIX | vanilla `hurtAndBreak(I, LivingEntity, EquipmentSlot)` calls `hurtAndBreak(I, ServerLevel, ServerPlayer, Consumer)` at offset 51, the call Fabric wraps | probe `damage.mob` / `damage.player` "handler calls=0 damage=1"; report SUSPECTED `ItemStackMixin#hookDamage` | both "handler calls=1 damage=0"; iron-sword controls unchanged; report RESOLVED | `-Dforbric.itemDamageHandler=off`: both fail, the injection warning is back | b2b3e240, e3da7384 |
| BCLib `SheepMixin` / `SnowGolemMixin` (c1 W5) | REGRESSION-FIX (D35) | vanilla `mobInteract` shears on `ItemStack.is(Items.SHEARS)` (Sheep offset 10); BCLib wraps it with `c:tools/shear` | `shears.sheep.tag` "result=Pass[] sheared=false", snow golem keeps its pumpkin; vanilla shears shear (NeoForge's path); report SUSPECTED | 13/13 probe cases, tag shears shear both, vanilla shears once, a stick nothing; report RESOLVED | `-Dforbric.shearsRelayMobs=off`: exactly the tag cases fail | a162172a, 3f02c16d |
| fabric-item-api furnace remainder redirect (5 (d) rows) | NOT LOST (tests re-pinned, no adaptation) | vanilla `consumeFuel` asks `Item.getCraftingRemainder` after `shrink` (offset 18); Fabric redirects it to a HEAD copy of the stack | probe `fuel.remainder.consumeFuel` "1 minecraft:stick asked=1", `fuel.remainder.furnace` "isFuel=true … 1 minecraft:stick", lava bucket → bucket: G's `consumeFuel` asks `ItemStack.getCraftingRemainder` on the unshrunk stack at offset 6, before `shrink` at 12 | unchanged | — | ee1aed96, 54a4bce4 |

## Evidence excerpts

javap, G against vanilla (lane `javap/`):

```
G       Sheep.mobInteract:        0 aload_1 … 2 invokevirtual Player.getItemInHand … 9 invokespecial Animal.mobInteract … 12 areturn
vanilla Sheep.mobInteract:        7 getstatic Items.SHEARS   10 invokevirtual ItemStack.is:(Ljava/lang/Object;)Z
G       ItemStack.hurtAndBreak(I,LivingEntity,EquipmentSlot)  33: invokevirtual hurtAndBreak:(ILServerLevel;LLivingEntity;LConsumer;)V
vanilla ItemStack.hurtAndBreak(I,LivingEntity,EquipmentSlot)  51: invokevirtual hurtAndBreak:(ILServerLevel;LServerPlayer;LConsumer;)V
G       AbstractFurnaceBlockEntity.consumeFuel   6: invokevirtual ItemStack.getCraftingRemainder   12: invokevirtual ItemStack.shrink
vanilla AbstractFurnaceBlockEntity.consumeFuel   7: invokevirtual ItemStack.shrink   18: invokevirtual Item.getCraftingRemainder
G       LiquidBlock.onPlace          4: invokevirtual shouldSpreadLiquid
G       LiquidBlock.neighborChanged  2: invokestatic net/neoforged/neoforge/fluids/FluidInteractionRegistry.canInteract
vanilla LiquidBlock.neighborChanged  4: invokevirtual shouldSpreadLiquid
```

Probe server before the lane (G, `boot/probe-before/console.log`):

```
[regressprobe] damage.mob FAIL no handler calls=0 damage=1
[regressprobe] damage.player FAIL no handler calls=0 damage=1 infinite=false
[regressprobe] fuel.remainder.consumeFuel PASS ok slot after burning one remainder_fuel: 1 minecraft:stick asked=1
[regressprobe] fuel.remainder.furnace PASS ok isFuel=true fuel slot after the first tick: 1 minecraft:stick
[regressprobe] shears.sheep.vanilla PASS ok result=Success[…] sheared=true
[regressprobe] shears.sheep.tag FAIL no result=Pass[] sheared=false
```

After a162172a (`boot/probe-item4/console.log`):

```
[regressprobe] damage.mob PASS ok handler calls=1 damage=0
[regressprobe] damage.player PASS ok handler calls=1 damage=0 infinite=false
[regressprobe] shears.sheep.tag PASS ok result=Success[…] sheared=true
[regressprobe] shears.snowGolem.tag PASS ok result=Success[…] pumpkin before=true after=false
```

Committed gate (7ff223f9, `run/compat/regress-gate.py` + `canary/regress`, review fixes run):

```
fixed: 15/15 cases passed
item-damage-off: 13/15 cases passed, failed exactly ['damage.mob', 'damage.player']
shears-mobs-off: 12/15 cases passed, failed exactly ['shears.sheep.ownEntry.fabric', 'shears.sheep.tag', 'shears.snowGolem.tag']
Regress gate passed
```

Carpet gate (`carpet-gate.py`, lane tip, then after e3259814's seven neighbour twins):

```
baseline: 14/27 behavior checks passed        baseline: 21/34 behavior checks passed
fixed: 27/27 behavior checks passed           fixed: 34/34 behavior checks passed
neighbour-off: 25/27 behavior checks passed   neighbour-off: 32/34 behavior checks passed
```

Boot line of the adaptation: `[Forbric/Mixin] carpet.mixins.LiquidBlock_renewableDeepslateMixin: receiveFluidToDeepslate
now also runs on a neighbour change, at FluidInteractionRegistry's canInteract in neighborChanged`.

## Review fixes

| finding | verdict | evidence | commit |
|---|---|---|---|
| B2 prepared skip baselines stale (6 renamed rows) | FIXED | rows renamed in ci-prepared.tsv + ci-prepared-noforge.tsv | bdf7524a |
| B8 item-damage adapter admitted a mixin of unknown family | FIXED | adapt needs `== FABRIC`; `onlyAFabricMixinIsMoved` red with the old condition, green after | e3da7384 |
| A3/B6 two transport tests vacuous on G (fed the furnace mixin, whose shape is gone) | FIXED (re-pinned to the crafting group, positive control `adapt == 2` before each perturbation) | MixinSharedResultTransportTest 8/8 (the commit message says "9/9": a miscount, the class has 8 tests) | 54a4bce4 |
| A5 mob relay pre-empted a NeoForge item's own entity entry | FIXED | javap `Player.interactOn`: `Entity.interact` at 66 before `ItemStack.interactLivingEntity` at 187; KernelMobShears stands down for non-Fabric items declaring `interactLivingEntity`; gate case `shears.sheep.ownEntry.neo` green, red with the line removed | 3f02c16d |
| A2 no committed behavioural check for hookDamage / mob shears | FIXED | canary/regress + regress-gate.py (above) | 7ff223f9 |
| B5 weave census claimed FabricItemDamageMixinAdapter WOVEN | FIXED (NOT_WOVEN_YET, ClassNode-level test only) | WeaveCoverageCensusTest green | 7ff223f9 |
| A4 neighbour wrap had no negative control | FIXED | 7 `.neighbor` twins in BASE_FLUID; red with blue-ice early end removed (fails `basaltPrecedesDeepslate.neighbor`) and with the lava check removed (fails `flowingWaterStaysWater.neighbor`) | e3259814 |
| B4 PROTOCOL.md carpet paragraph stale | FIXED | | e3259814 |
| A1/B3 furnace remainder: a false SUSPECTED row in every fabric-api report | DEFERRED → C4 (census) / owner | needs a sound "the base does it natively" rule for a `@Share` copy; regress-gate lists the rows as `knownFalseSuspected` | — |
| B1 MixinFluidReactionAdapter's merged-only halves | DEFERRED, later done by lane c3b-fluid-reaction (6c3e96f6) | | — |
| B7 owner record for hookDamage and the Carpet adaptation | DONE by D36 | | — |
| B5 unit tests for KernelFluidReactions / KernelMobShears | DEFERRED → C5 | both need a live Level/registry; covered by the carpet twins and regress-gate | — |

## Known approximations (in the javadocs)

- The deepslate wrap decides "vanilla reaches the point" by vanilla's own walk (`KernelFluidReactions.reachesWaterReaction`),
  so a NeoForge mod's registry interaction for an earlier direction does not pre-empt Carpet's rule.
- A Fabric shears wrap that vetoes real shears on a sheep or snow golem is not carried over (NeoForge's ShearsItem path still
  shears).
- Tag shears on those mobs follow NeoForge's IShearable semantics (PASS when not shearable) rather than vanilla's CONSUME.
- With BCLib, an item in `c:tools/shear` that is not a ShearsItem and declares its own `interactLivingEntity` keeps its own
  entry unless it is a Fabric mod's (then pre-empted, as native Fabric + BCLib does).

## Suite

Full suite on G at ee1aed96: **5737 tests, 65 failed**; against runtime-injectors-fix (5729 / 91): 0 new, **26 gone**,
exactly the lane's (d) rows (CarpetMixinAdapterTest ×4, MixinCallbackContractsTest ×2, MixinCallbackPointSpellingTest ×4,
MixinCallbackSelectorSpellingTest ×8, MixinFluidReactionShapesTest ×3, MixinSharedResultTransportTest ×1,
MixinSharedResultTransportSpellingTest ×4). At e3259814: 5738 tests, 65 failed, the same names. Per-commit
`compileJava compileRuntimeJava compileTestJava` of the four lane commits in a detached worktree: all rc=0; the review-fix
commits compiled from `git archive` snapshots by the C3 critic: all rc=0.

## Commands

```bash
# probe server (lane tools/server.sh <name> <port> [mod jars...], run through .stage/heavy.sh): a scratch rundir
# boot/<name>/run with the jars copied into mods/ (fabric-api 0.155.2, bclib 26.201.2, worldweaver 26.201.2,
# wunderlib 26.201.0, the probe mod), server.properties with level-seed=4242, stdin from a FIFO; one server per switch
FORBRIC_OLD="$GS" RUNDIR="$RUN" FORBRIC_JVM="-Dforbric.itemDamageHandler=off" run/launch-kernel-server.sh < "$FIFO" > console.log
# after "Done (…s)!": "save-all flush", then "stop"; the probe prints one [regressprobe] PASS/FAIL line per case on the server thread
# committed gate
.stage/heavy.sh python3 run/compat/regress-gate.py --fabric-api run/client-merged-pack/mods/fabric-api-0.155.2+26.2.jar \
  --bclib build/compat-inputs/sweep90/mods/bclib-26.201.2.jar --mod build/compat-inputs/sweep90/mods/worldweaver-26.201.2.jar \
  --mod <wunderlib-26.201.0.jar> --port 46181 --output <dir>
.stage/heavy.sh python3 run/compat/carpet-gate.py --carpet build/compat-inputs/carpet/mods/fabric-carpet-26.2+v260616.jar \
  --staged-root "$GS/run" --output <dir>
```

wunderlib 26.201.0 is not in sweep90; the lane used the purefabric-2026-10-03 copy in the main checkout's `build/`.
