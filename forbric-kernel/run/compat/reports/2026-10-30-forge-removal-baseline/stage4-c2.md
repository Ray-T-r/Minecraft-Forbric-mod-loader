# Stage 4 C2: MinecraftForge's code deleted (s4-c2, cf0498eb..00a5e585)

C2 deleted the code that existed only for MinecraftForge, on the merged base (`.stage`, which still carries the
MinecraftForge carrier, so deleting compiles). It was preceded by P1, the refusal marker the deletions needed (D1). 69
commits on top of the Stage 3 tip cf0498eb: **651 files changed, 4,109 insertions, 86,437 deletions**. Every lane was
reviewed twice (A and B) and followed by a review-fix round; a completeness critic read the tip. This file keeps the
outcomes; the working notes lived in a session scratchpad that is not kept.

## Lanes and commits

| lane | commits | diff | what went |
|---|---|---|---|
| P1 (merged at 9e320e3e) | 6562ef13, a446196b, 650a004e, ff95608e, 6ffa74c3, b91ddda6, 1684194b, 184e81c9, a04e1e38, 2cc52c29, 138c9f7c | 78 files, +1,611 / −427 | behaviour-neutral preparation: a refused jar gets a claim of its own instead of `Ecosystem.FORGE` (D1; `ownerOf` throws for a refused jar, null = plain library); `RegistryRemapContracts` extracted (D5); `VanillaDamageReadInjector` keeps its own descriptors; the NeoForge carrier's datapack id pinned at `forbric/carrier/2-neoforge-runtime` (D2); the refusal fixtures build against MinecraftForge stand-ins |
| events | 8d91ae40, e8936505, a317fce2, 158fd1a5 | 149 files, +211 / −27,700 | the NeoForge-to-MinecraftForge event forward pass (`GameEventMultiplexer`, 21 `KernelGame*Events`, `GameEventBridge`, `EventBridges`, `DeadEventAudit`, `ForgeBusSubscriptions`, `DeadHookWorklist`, `EventChainAudit` + injector, the NativeDualEventInjector family), the client-init relay (`KernelForgeClientInit` + `KernelClientHookMixinAnchors`, restoring NeoForge's own `initClientHooks` / `onRegisterParticleProviders` calls), and `HookCallSiteCensus` / `CarrierCallGraph` / `MergedBaseLoadTimeView` (D24); NeoForge's resource-reload hook no longer redirected |
| transform | 3c72ff50, 7321b2c3, 1f3d64f7, 67530cb2, d9b7ab8e, f3b67a2d, f86a741c, 685cbadf, a9acd6f0; review fixes 3064ef9f, 4cdd8363, dd5da2f2, 6e8ff7d0, b3564c6d | 266 files, +927 / −37,906 | the registry-wrapper rewrites (`KernelForgeWrapperSync` and kin), capability composition and its plugins, fluid and particle-group repairs (incl. `FluidInteractionsInjector`, D15), the spawner/getter repairs (finalizeSpawn now goes to NeoForge's own `EventHooks.finalizeMobSpawn`, D14/D22), the MinecraftForge client repairs (model data, HUD overlay tree, block tints, creative tabs, options, pack-screen filter), damage seams, loot-pool conditions, worldgen, spawn placement, attributes, brewing, 30 repairs inside `ForbricMergedBaseCompatTransformer`, the Forge branches of 14 mixed transformers |
| runtime | 7619d32f, 7097ed6d, 9f6b758c, 73661e2a, 02f147a5, 93b2a6fc, 26f281f0; review fixes c10779e3, 3a68ddbe, add7e239, 1d1221ad | 112 files, +571 / −11,452 | MinecraftForge's runtime halves: fluid-type views, client consumers, condition evaluator, ingredient codec, capabilities, attributes, spawn placement, worldgen, configs; block transfer collapsed to Fabric ↔ NeoForge (plus Team Reborn Energy); the ModifiableDataViews quartet (forced out by the compile; its C3 checks were collected in C3, ca371ff0) |
| boot | f7cb3c19, 33db643c, 3540450d, 04e56943, 0cf2fae5, 826183e1, dbd1b90f, c3e6981b, 9ec3a1c1; review fixes f7e94f27, 8ed722e3, aef774f5, 29d05126, eecc882e, 281bbb84 | 73 files, +558 / −7,908 | MinecraftForge's mod construction, loading state, registry gates and pre-Mixin seeds; KernelBoot names no MinecraftForge class; `ForgeModRemapper` (no caller, D27); `AbiLinkAudit` stops judging a universal jar's set-aside Forge half (D21) |
| interop-misc | 1cad0069, fd2fb438, c56a5243, 2aec9ab1, d4337a57, f064e399, 36f034a6, a9c45e9a, 09f06fed, ae414ea8; review fixes a427db15, cc6dec1e, 00a5e585 | 47 files, +333 / −1,146 | `PayloadInterop`'s MinecraftForge codec, dispatch and login handshake; the class loader's FORGE family; universal-jar discovery through Fabric and NeoForge manifests only; MixinShearsRelay's three mob rows (they followed MinecraftForge's ToolActions call; C3 brought the mob shears back for BCLib under D35) |

Switches read only by deleted code went into `ForbricSwitches.RETIRED` (68 entries at the C2 tip, D29 list complete,
`released-switches.txt` covered); `forbric.earlyConfigs` stays because it governs the kept NeoForge config pass.

## Suite (merged stage, `-Pforbric.requireFixtures=all`, task `test`)

| tip | tests | failed | the failures |
|---|---:|---:|---|
| P1 | — | 6 | the lost fixtures of Stages 0-3: LiquidBounce ×3 (`FabricClientMixinAnchorsTest` ×2, `MixinTwinRebindTest`), the R3 corpus (`MixinRetargetRenamedBodyCorpusTest`, 44 moves instead of 52), opt-in `shared-final-definitions` ×2 |
| 158fd1a5 events | 6558 | 6 | identical to P1 |
| b3564c6d transform | 6072 | 14 | P1's six + eight expected-until-C1 (below) |
| 1d1221ad runtime | 5978 | 14 | the same 14 |
| 281bbb84 boot | 5905 | 14 | the same 14 |
| **00a5e585 interop-misc (C2 tip)** | **5857** | **14** | the same 14; transferTest 57/57 at 09f06fed |

The eight expected-until-C1 failures, each because the merged base's own bytecode names a MinecraftForge member the lane
stopped rewriting (javap of `.stage/run/merged-base/patched-mc-merged-26.2.jar` against G):
- `LifecycleHookInjectorTest.excisesServerModLoaderFromRealMain`, `FabricHooksCallTest.onTheRealMainTheMarkerFollowsTheWindowOnItsOwnPath`:
  merged `Main.main` offset 396 `invokestatic net/minecraftforge/server/loading/ServerModLoader.load:()V`; G's `Main.main`
  has only NeoForge's `ServerModLoader.load:(Z)V` (offset 339).
- `ModsButtonRedirectorTest` ×3, `ModsButtonRedirectorExecutionTest.theStagedPauseMenuBuildsOneModsButton`: merged
  `PauseScreen.lambda$createPauseMenu$11` does `new net/minecraftforge/client/gui/ModListScreen`; G's PauseScreen builds
  none (NeoForge's is in the carrier's `ModsButton`).
- `MergedBaseLeftNullFieldsTest`: merged `WeightedVariants` declares `private final BlockStateModel first`, written only by
  the deleted `giveMinecraftForgesParticleLookupItsFirstVariant`; G has no such field.
- `MergedBaseNoUnwrittenDuplicateFieldTest`: merged `KeyMapping` declares two `MAP`s (one
  `net/minecraftforge/client/settings/KeyMappingLookup`) and `ReloadableServerResources` two `context` fields; G has one each.

After C1 all eight are green (or deleted as merge audits: `MergedBaseLeftNullFieldsTest`), as predicted.

No gate ran in C2: gates are C5's. Each lane listed every gate check its deletions turn red or vacuous (D34), and C1/C3
measured them on G (see the README's Stage 4 section).

## D28 (Flashback config read)

No gap on NeoForge, so nothing was re-pointed: NeoForge 26.2.0.93's `ConfigSync.syncAllConfigs` returns at its head on a
memory connection (`getConnection` -> `Connection.isMemoryConnection` -> `ifeq` / `return`, javap offsets 0-14 of
`neoforge-runtime.jar`) before any `Files.readAllBytes`, and a Flashback 0.41.1 replay (`ReplayServer extends
IntegratedServer`) connects over `Connection.connectToLocalServer(startMemoryChannel())`.

## Completeness critic at 00a5e585

Universe: 2,912 tracked files under `src/` and `run/`; `grep -a -nE 'minecraftforge|Ecosystem\.FORGE\b|…'` gave 2,291 hits in
409 files after dropping NeoForge false positives. Classes: must-not-delete / shared 80 files; C1 36; C3 candidate 54; C4
98; C5 gate/test 120; doc/historical 20; **missed C2: 1** — `src/test/resources/weave/absorbeddamage/net/minecraftforge/common/ForgeHooks.java`,
a three-line stand-in no test reads (it served the deleted ForgeDamageSeamsInjector half). It is still in the tree at the
C3 tip: C4/C5 delete it with the other MinecraftForge test stand-ins.

## C3 candidates C2 touched (obligations C3 discharged)

- Forced out by the compile, checks owed in C3: the ModifiableDataViews quartet (runtime lane, 26f281f0) — checks recorded
  in C3 commit ca371ff0. Deleted in C2 by decision: `PackScreenHiddenFilterInjector` (D33; symptom check on G: exactly one
  kernel pack row, still selected — gate-m9 passes), the `HookCallSiteCensus` family (D24; the evidence pass ran in C3 from
  the cf0498eb worktree: over G + carrier, 13,397 classes and 437 NeoForge hooks with a call site, the compat transformer of
  73a4314c takes exactly 2 call sites, both intended KEEPs — `MonsterRoomHooks.getRandomMonsterRoomMob` (#10, D20) and
  `ServerLifecycleHooks.runModifiers` (#15); at cf0498eb it took 7, the other five by MinecraftForge claims C2 deleted).
- Kept for C3 with obligations: `KernelConversions`, `KernelItemTooltips`, `CreativeSearchTreesInjector`, the flower pot
  repair's MinecraftForge half, `LocalRanges`, `ClientPartTrackingInjector`, D25's three repairs. All have C3 verdicts.
