# Stage 4 C3, lane "c3b-fluid-reaction": how MixinFluidReactionAdapter's merged-base halves were checked

Branch `s4-c3`. Lane commits 6c3e96f6 (deletes `afterUnhandled` and `flowingReaction`) and 3c88e7e1 (the neighbour-change
wrap only for a mixin known to be a Fabric mod's) on ab1bd684, and the commit that adds this file. The orchestrator
assigned the item after the critic pass (critic item 2: "MixinFluidReactionAdapter's merged-only halves are deferred to C4.
They are dead on G, and a three-check plan exists, but nobody has run it"; plan in the C3 handoff, "-> C4
(MixinFluidReactionAdapter, review B1)"). This file keeps the measured outcomes and the instruments, which otherwise lived
only in a session scratchpad.

Names: G = genuine stage (`run/neoforge-patched/patched-mc-neoforge-26.2.jar` + `run/neoforge-runtime/neoforge-runtime.jar`
(NeoForge 26.2.0.93) + `run/neoforge-runtime/forbricneolive.jar`); M = merged stage (`run/merged-base/patched-mc-merged-26.2.jar`
+ both carriers), driven from a read-only worktree at cf0498eb. Heavy commands went through `.stage/heavy.sh`; `MC_DIR`
was never set; the Carpet gate binds port 0.

## Results

| path | verdict | check 1: symptom on G with the path removed; merged red | check 2: whole jar (G + carrier), the path's own criterion | check 3: callers on G | commit |
|---|---|---|---|---|---|
| `afterUnhandled` (TAIL handler on `shouldSpreadLiquid` wrapped into the registry call of BOTH `onPlace` and `neighborChanged`) | DELETE | Symptom: Carpet's renewable blackstone (TAIL) does not run. `carpet-gate.py` on G with the deletion: baseline 21/34 (exactly the CARPET set), fixed 34/34 STRICT, neighbour-off 32/34 (exactly `fluid.blackstone.neighbor`, `fluid.deepslate.neighbor`). Fixed console: 0 `unhandledFluidReaction`, 0 `flowingFluidReaction`, 4 "now also runs on a neighbour change", 2 `forbric$neighbourFluidReaction` lines, the same counts as the run before the deletion (e3259814). Merged red: cf0498eb's `repair()` with `return 0` ahead of both calls, M stage: "Fixed run failed: cases={'fluid.deepslate.true', 'fluid.blackstone.true', 'fluid.blackstone.neighbor', 'fluid.deepslate.neighbor'}"; positive control, the same tree unmodified: baseline 12/27, fixed 27/27, with `forbric$unhandledFluidReaction$onPlace` / `$neighborChanged` and `forbric$flowingFluidReaction` in the console | LiquidBlock subclasses whose `onPlace` AND `neighborChanged` each call a `*/FluidInteractionRegistry.canInteract(Level, BlockPos)`: G 0 of 13396 classes; M 1 (`LiquidBlock`: MinecraftForge's registry in `onPlace`, NeoForge's in `neighborChanged`). G has one registry class (NeoForge's), one `canInteract` caller (`LiquidBlock.neighborChanged`) and one `shouldSpreadLiquid` caller (`LiquidBlock.onPlace`); vanilla calls `shouldSpreadLiquid` in both hosts and no registry. No MOVE | 1119 distinct mod archives scanned (nested jars included): five mods name `shouldSpreadLiquid`; only Carpet's two mixins have a TAIL or an `isSource`-point handler (fabric-block-api and Create Fly: HEAD; better-nether: INVOKE `setBlockAndUpdate` with captured locals). Carpet on G: the fixed run above, 34/34 | 6c3e96f6 |
| `flowingReaction` (reaction-point handler moved whole into every `REGISTRIES` entry's `canInteract`) | DELETE | Symptom: Carpet's renewable deepslate (INVOKE `FluidState.isSource`) does not run. Same runs as above (`fluid.deepslate.*` pass on G; fail on M with the path off) | LiquidBlock subclasses whose `onPlace` calls a registry and not `shouldSpreadLiquid` (the path's own precondition): G 0; M 1. It also required every `REGISTRIES` entry to resolve, and G, its carrier and forbricneolive hold no `net/minecraftforge/` class | as above | 6c3e96f6 |
| `neighbourChangeOnly` (95a2f1de) | KEEP, EDIT (stand-down) | The adaptation Carpet's rules need on G (neighbour-off is the gate's red control). It stood down only for `Ecosystem.NEOFORGE`, so a mixin of no recorded family was wrapped too; it now requires `Ecosystem.FABRIC`. Red with the old condition: `CarpetMixinAdapterTest.onlyAFabricMixinGetsTheNeighbourChangeWrap` "LiquidBlock_renewableBlackstoneMixin null ==> expected: <0> but was: <1>"; green after | criterion: `neighborChanged` asks a registry and not `shouldSpreadLiquid`: G 1 (`LiquidBlock`), the one site it wraps | Carpet gate on G after the edit: 21/34, 34/34, 32/34, the same 4 + 2 log lines | 3c88e7e1 |
| `REGISTRIES`' `ForeignType.FLUID_INTERACTION_REGISTRY.internal(Ecosystem.FORGE)` entry | KEEP (left to C4) | n/a | no class of G, carrier or forbricneolive in `net/minecraftforge/`, so the entry matches nothing; only `registryCalls` reads it now | n/a | — |

Removed with the two paths: `retainOriginal` (and `MixinFluidReactionRetainTest`), the static-relocation helpers, and the
`Inject` arm of the annotation helper. No switch retires: both paths stood down on `-Dforbric.playerWorldCallbacks`,
which the other player/world repairs keep.

## Tests

`CarpetFluidMixinAdapterWeaveTest` and `MixinFluidReactionWeaveTest` asserted the two merged rewrites on fixtures with a
MinecraftForge registry stand-in. Their fixtures now have NeoForge's shape (`onPlace` -> `shouldSpreadLiquid`,
`neighborChanged` -> NeoForge's registry), the MinecraftForge stand-ins are gone, and they weave `neighbourChangeOnly`'s
TAIL wrap and reaction-point wrap through real Mixin, the second with the production `KernelFluidReactions` compiled in and
a source-lava neighbour-change cell that the rule turns to magma (the registry's obsidian with
`-Dforbric.fluidReactionNeighbourChange=off`, each test's control). `WeaveCoverageCensusTest` names that switch for the
adapter.

## Instruments

- Carpet gate: `heavy.sh python3 run/compat/carpet-gate.py --carpet build/compat-inputs/carpet/mods/fabric-carpet-26.2+v260616.jar --staged-root <stage>/run --output <dir>` with `FORBRIC_OLD=<stage>`.
- The whole-jar scan (ASM 9.10.1 on the classpath, `java -cp asm.jar:asm-tree.jar FluidScan.java <jars...>`; entries ending
  `.class` or `.class.bin`, the latter for the vanilla reference jar):

```java
// For every class: callers of */FluidInteractionRegistry.canInteract and of LiquidBlock.shouldSpreadLiquid(Level,BlockPos,BlockState)Z;
// for every LiquidBlock subclass, per onPlace / neighborChanged: #shouldSpreadLiquid calls (s) and #registry canInteract calls (r).
// AFTER_UNHANDLED: onPlace.r>=1 && neighborChanged.r>=1.  FLOWING: onPlace.r>=1 && onPlace.s==0.  NEIGHBOUR: neighborChanged.r>=1 && neighborChanged.s==0.
```

- The mod scan: every `.jar` under `build/compat-inputs`, `run/client-merged-pack/mods`, `build/sweep90-mac`,
  `build/purefabric-2026-10-03` and `.dev/api`, recursing into `META-INF/jars`, deduplicated by SHA-1; a class outside
  `net/minecraft/` whose bytes contain `shouldSpreadLiquid`; then each hit's injector annotations read with ASM.

## Suite

Full suite at 3c88e7e1 on G: 5759 tests, 65 failed, names identical to the a3791619 baseline (`failures-c3.txt`): 0 new,
0 gone. +1 over ab1bd684: `MixinFluidReactionRetainTest` (1) deleted, `MixinFluidReactionWeaveTest` 2 -> 3,
`CarpetMixinAdapterTest.onlyAFabricMixinGetsTheNeighbourChangeWrap` new.

## Open (not this lane's to fix)

`NativeEntryRelay` re-asks `shouldSpreadLiquid` only where "nothing in the merged class calls it". On G `onPlace` calls it,
so the relay plans nothing for `LiquidBlock`, and a Fabric HEAD handler on `shouldSpreadLiquid` (fabric-block-api's flow
veto, Create Fly's fluid reaction) runs on placement only, never on a neighbour change, where vanilla runs it too. The
woven `LiquidBlock` a G server exported with Create Fly loaded (lane c3b-useon-relocation review A, m44 `create.mixin.out`)
shows it: `onPlace` calls `shouldSpreadLiquid` @4, `neighborChanged` calls only NeoForge's `canInteract` @2, and
`handler$zcd000$create$shouldSpreadLiquid` is merged into `shouldSpreadLiquid` alone. Bytecode only; the behaviour was not
measured in game. `FluidPairMixinAdaptersWeaveTest` still weaves the merged two-registry fixture.
