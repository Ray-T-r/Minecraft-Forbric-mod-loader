# Stage 4 C4: census tables from NeoForge's own base, `Ecosystem.FORGE` removed (lane census-forge)

Commit `3d8364b4` on `s4-c4` (one commit: the regenerated tables, the generator and parser edits and the enum removal),
on top of the C3 tip `daa94207`. Stage: `.stage4` (genuine base `neoforge-patched/patched-mc-neoforge-26.2.jar`, sha1
`67bbf24f…`, `.pins` = `mc=26.2 neoforge=26.2.0.93 nfrt=2.0.18 result=gameJarNoRecomp`, byte-identical to an
installer-built base per C1), NeoForge carrier 26.2.0.93, vanilla client 26.2.

## What was regenerated, and from which bytes (D31)

The generators run as JUnit tests that rewrite `src/main/resources/<table>` when `FORBRIC_WRITE_<X>` is set. They were
run with writers on until `git diff` stopped changing (twins after uncalled), then again after the last kernel change
(the uncalled census reads kernel classes as callers), then in verify mode in the full suite.

A new fixture, `TestFixtures.twinnedBase()` (`transform/PreMixinView.twinnedBase()`), writes the base as one jar with
every class passed through `VanillaMemberTwinInjector` (the first COREMOD transformer KernelBoot registers) and asserts
that all nine twin rows landed, so a missing vanilla reference cannot silently produce a table without the twins.

| table | bytes | evidence for the choice |
|---|---|---|
| uncalled-methods.txt | post-twin | Its consumers (MixinFit liveness, MixinTwinRebind, UncalledValueRelay, …) read Mixin's post-chain view. Raw vs post-twin: +4 rows, exactly the twin members NeoForge's game no longer calls (`Hud.extractHotbarAndDecorations`, which the census asserts, `ParticleResources.getProviders`, `AxeItem.evaluateNewBlockState`, `CropBlock.getGrowthSpeed`). Raw fails the census's own `Hud` assertion. |
| carrier-twins.txt | post-twin (reads the shipped uncalled table) | follows uncalled |
| carrier-stubs.txt | rows post-twin, `neo=` column raw | Raw vs post-twin: one row, AxeItem's `evaluateNewBlockState` (the DELEGATE twin). Its `neo=` is read off NeoForge's own class: `overload-body`. |
| carrier-renames.txt | post-twin | identical to raw (0 diff lines) |
| carrier-helpers.txt | post-twin | identical to raw |
| vanilla-early-returns.txt | twin, then ForbricMergedBaseCompatTransformer | KernelBoot's order; identical to compat-only on raw |
| lambda-permutations.txt | raw | identical post-twin |
| native-only-methods.txt | **raw** (D31 default) | NativeAbsentTargets is asked about the raw class and the raw members digest. A post-twin run drops exactly the seven twin members (5 VANILLA_BODY, 2 DELEGATE) and digests `b4f9ecca…`, which the runtime digest check would refuse. Raw keeps a FABRIC row for every twin member, so a twin that stands down (no sidecar) leaves a reported loss, not a silent "native lacks it too". |

The NEOFORGE reference arm stays (D31): it is the raw base. No table has a NEOFORGE row today; only a twin could make
one (carrier-stubs' `neo=` column records what NeoForge's own class has).

## Tables before and after

| table | merged base | genuine | rows naming FORGE (before → after; recounted by the review-fix round, see below) |
|---|---:|---:|---|
| carrier-renames | 389 | 335 | 275 → 0 (FABRIC,FORGE and FORGE) |
| carrier-helpers | 37 | 35 | 23 → 0 |
| carrier-stubs | 79 | 74 | 79 `forge=` columns → 0 |
| carrier-twins | 7 | 5 | 5 → 0 |
| lambda-permutations | 7 | 7 | — |
| native-only-methods | 1001 | 659 | 393 forge rows + `platform forge` → 0; base digest `080a89b5…` (merged) → `d033f1ac…` (raw genuine); neoforge 0 rows, 0 merged-only |
| uncalled-methods | 262 | 86 | 232 → 0; no `!` (silenced) site any more |
| vanilla-early-returns | 3271 | 3276 | — |

## New FABRIC rows, by kind (each reviewed)

A new FABRIC row means a Fabric mod's injector is now moved (renames, helpers, twins, stubs) or reported as never
running (uncalled) where it was not on the merged base. Every class of them is the adaptation now being measured
against NeoForge's base instead of the merge:

- **A body NeoForge moved into a method of its own** (carrier-renames, +10): `EntityType#trackDeltas()Z ->
  defaultTrackDeltasSupplier()Z`, one row per entity type the body names (BAT, END_CRYSTAL, … WITHER), kind RENAME.
  javap: genuine `trackDeltas()` calls the `trackDeltasSupplier` predicate; vanilla's list of entity types is in the
  private `defaultTrackDeltasSupplier()`. On the merged base the name was ambiguous with MinecraftForge's
  `defaultVelocitySupplier`, so no row was written.
- **A vanilla signature NeoForge's game stopped calling, beside a NeoForge overload its callers call** (carrier-twins
  +2, uncalled +2): `RegistryFriendlyByteBuf#decorator(RegistryAccess)` → `(RegistryAccess, ConnectionType)` (callers:
  both configuration listeners' `handleConfigurationFinished`), `BrewingStandMenu$PotionSlot#mayPlaceItem(ItemStack)` →
  `(PotionBrewing, ItemStack)` (callers: `quickMoveStack`, `mayPlace`). On the merged base MinecraftForge's own code
  still called the vanilla signatures.
- **Vanilla methods only NeoForge's replaced path skips** (uncalled +6): `SessionSearchTrees#creativeNameSearch()`,
  `creativeTagSearch()`, `updateCreativeTags(List)`, `updateCreativeTooltips(…)` — javap: genuine
  `CreativeModeInventoryScreen` asks the keyed overloads through `CreativeModeTabSearchRegistry.getNameSearchKey` /
  `getTagSearchKey`; on the merged base the deleted creative-search repair (C3) called the vanilla ones.
  `FaceBakery#bakeQuad(ModelBaker$Interner, …)` and `ItemModelGenerator#bakeExtrudedSprite(…)`: NeoForge's callers
  call its overloads. None of the eight has an uncalled site in the game (empty last column).
- **The twins themselves** (uncalled +4 relative to a raw run, carrier-stubs +1): see the D31 table.

Rows lost (FABRIC): the MinecraftForge renames (`OldMinecartBehavior#moveAlongTrack -> moveMinecartOnRail` ×10;
genuine has only `moveAlongTrack`, vanilla's body), two renumbered lambdas (`Minecraft lambda$new$6`, genuine keeps
vanilla's name; `GuiGraphicsExtractor lambda$item$2`), the helper row of the same minecart method, the
`LivingEntity#travelInFluid` twin, and 76 uncalled rows — 71 lambdas of the merge's bodies plus `dumpValue`, the 4-arg
`RegistryDataLoader#load`, `travelInFluid` and two `EventLoopGroupHolder` helpers; those absent from the raw genuine base
appear as native-only rows instead, the EventLoopGroupHolder pair is called again (native-only +79 FABRIC: 66 lambdas,
the 7 twin members, `MultiPartModel/WeightedVariants#collectParts`, `UnbakedCuboidGeometry#bake`,
`RegistriesDatapackGenerator#dumpValue`, `RegistryDataLoader#load` 4-arg, `LootPool#<init>`). vanilla-early-returns
gained 5 methods with a folded guard (ChestRenderer.getChestMaterial, LevelExtractor.extractBlockOutline,
Bootstrap.bootStrap, PackDetector.detectPackResources, Spider.canBeAffected).

## Pins re-pinned with the tables

- `MergedBaseAnonymousDrift.CAPTURE_ONLY`: −`Options$3`, −`TagAppender$1`, −`ServerLoginPacketListenerImpl$1`,
  +`CustomPacketPayload$1`; RELOCATED and RESHAPED unchanged. Kept: MixinFit, KernelGuestMixinAdapter and
  MixinAnonymousRetarget still read it. Its one log string now says "reshaped by NeoForge's patches".
- `MixinFitLivenessCensusStagedTest` (local, `build/compat-inputs` present; CI skips the pack census): `EXPECTED` 10 → 9
  (fabric-registry-sync's RegistryDataLoaderMixin left — vanilla's private 4-arg `RegistryDataLoader.load` is not in
  NeoForge's base, a native-only row; balm's FabricCropBlockMixin reads FIT with liveness off, `getGrowthSpeed(Block,…)`
  being a twin), `EXPECTED_IN_PACKS` 30 → 25, `FABRIC_API_LOST` 61 → 61 (−2 TagsProvider `lambda$run$5`, which
  resolve on NeoForge's base; +fabric-item-api's hurtAndBreak wrap and +fabric-registry-sync's 4-arg `load` target, both
  adapted at run time by FabricItemDamageMixinAdapter / FabricRegistryLoaderMixinAdapter, which the census does not
  replay). Its name-took-over case now stands on NeoForge's StructureTemplate, which has no `placeEntities` at all
  (`addEntitiesToWorld`): the plain miss for an unowned mixin, R7's move for a Fabric one; its raw-ABI case on
  fabric-content-registries' HudMixin.
- The lambda-decision resources were already deleted with DuplicateLambdaPruneInjector (C3, 4175d4bc).
- Re-pinned on rows the genuine tables have: MergedBaseGuestScan(Staged)Test (createCookie, ServerExplosion.hurtEntities,
  ModelBlockRenderer.shouldRenderFace instead of travelInFluid, RegistryDataLoader.load and removeBlock),
  MixinStubRebindSpellingTest (AxeItem's twin row instead of PackDetector's MinecraftForge stub),
  NativeOnlyMethodsCensusTest's loss cases (Entity.playMuffledStepSound / playCombinationStepSounds,
  FireBlock.checkBurnOut for Fabric; NeoForge has no row because the base is its game).

## `Ecosystem.FORGE` removed

- `Ecosystem`: two constants. `parse("forge"|"minecraftforge")` is null; `namesMinecraftForge(raw)` is asked first at
  every knob and the player's override file (D1, warnings unchanged); `configId()` = `familyId()`.
- Refusal on its own marker: arbitration and findings already used `LoaderKind`/`JarClaim` (P1). The catalogue no longer
  borrows FORGE for a refused jar: `ModCatalog.Entry.refused(loader, spelling, …)` has no ecosystem, is counted apart
  (`refusedCount()`), and the Mods screen tags it "MinecraftForge"; the load report keeps `"ecosystem":"FORGE"` for it
  (the `LoaderKind` spelling, as finding evidence keeps `family=FORGE`) plus `"refused":true`.
- `MultiLoaderArbiter.declaredBy` lists loadable ecosystems; `loadersOf` (every manifest, `LoaderKind`) serves the
  universal-jar set and carrier provenance, which keep their meaning.
- `ForeignType` lost its MinecraftForge column and the four rows nothing read (furnace burn time, living conversion,
  key-mapping lookup, part entity). ConfigApiAbiInjector names the config API port's own `IConfigSpec` copy as a literal.
- `MixinStubRebind.Row` has one column (`neo`); unknown tokens such as an old `forge=` are ignored. The switch
  `forbric.mixinStubRebind.forgeFamily` keeps its name (D31) and gates NeoForge moves only.
- `NativeReferenceSources`: a base whose Block implements a MinecraftForge interface has no own ecosystem.
- `KernelDataPacks`: no MinecraftForge carrier rank; NeoForge's carrier keeps rank 2 and `2-neoforge-runtime` (D2).
- Log lines: `[Forbric/Mixin] mixin configs: … (N NeoForge) — …`, `[Forbric/Catalog] … N Fabric, N NeoForge,
  N refused — …`, and the native-references line no longer says `FORGE none`. gate-m4's catalogue regex and gate-m26/
  gate-m29's mixin-config checks changed with them.
- Tests: Ecosystem.FORGE samples became NEOFORGE or FABRIC; MinecraftForge-only legs and stand-ins went (the
  ForgeLayeredDraw HUD tree in MixinFitLivenessTest, the forge weave runs of MixinNativeTail/StubRebind/PlatformIdentity
  /LibraryPlugin, KeyActionExits' and ReplacedCallRedirects' MinecraftForge legs, NestedDuplicateModArbiter's two
  xaerolib MinecraftForge cases, the MinecraftForge carrier's data-pack rank, KernelModLoaderWithdrawal's FORGE clause).

## Acceptance checks (census-c4.md §5)

1. No data row of the eight tables names FORGE; the enum is gone; `compileJava compileRuntimeJava compileTestJava
   compileTransferTestJava compileModMenuApiJava compileModMenuPluginJava` green. PASS.
2. Row-parse guards: renames, helpers, twins and uncalled generators assert `Row.parse` on every shipped row. PASS.
3. The twinned base asserts 9 of 9 twin rows. PASS.
4. Rows that must exist: carrier-stubs' gate rows (Player.getDestroySpeed, LivingEntity.randomTeleport,
   Language.loadFromJson / parseTranslations, ClientLanguage.appendFrom, LivingEntity.canGlide, SimpleContainer and
   BaseContainerBlockEntity setItem, asserted by CarrierStubCensusTest) and AxeItem `neo=overload-body`; uncalled
   `Hud#extractHotbarAndDecorations` and `Hud#extractPlayerHealth`; a FABRIC native-only row for every twin member. PASS.
5. gate-m46 GREEN (positive and `-Dforbric.mixinStubRebind=off` control); gate-m9 165 PASS / 21 FAIL, the same 21 lines
   as at 5efabdc5 (all C5), G1 and G4 PASS; gate-m1 GREEN with the sidecar line. gate-m17 not run. gate-m4 cannot run
   on `.stage4` (reads the merged base, C5). gate-m29's refusal phases PASS (both changed checks), its control phases
   fail on retired capability-composition lines (C5). gate-m26 not run (its rundirs are the shared client-forge
   fixtures).
6. CI profiles: not run by the lane; every census test ran (none skipped) under `-Pforbric.requireFixtures=all`.
   The review-fix round ran the census classes under CI's prepared profile (`staged,game-side,mc-libraries,java-25`):
   32 tests, 0 skipped. The skip-baseline rows of the census tests are C5/C6's to regenerate.
7. Raw vs post-twin diffed for every table (D31 table above).

## Suite

`.stage4`, `-Pforbric.requireFixtures=all`: **5755 tests, 45 failed** (C3 tip 5760 / 65). 23 rows gone (every C4 row
and five of six C4+C5 rows); 3 new, all `TwinCapturedLocalsWeaveTest`, whose fixture models the merged base's FORGE-only
`ServerPlayerGameMode#removeBlock` twin row (the mechanism is intact; the fixture moves to a FABRIC twin row in C5).
`MergedBaseCalleeSwapTest` now fails only on its C5 half (a second admitted swap on genuine, `ServerPlayer.teleport
unsetRemoved → revive`). For the 42 rows kept, the failure message prefix equals the C3 record's. Every open failure, with its cause and owner:
`stage4-c4-census-open-failures.txt`. Python tools 52, run/compat
145, run/compat/mac 94: OK.

The gates above ran on the uncommitted tree that became 3d8364b4 (`summary.txt` records "HEAD daa94207 dirty=117"; the
last main-source edit precedes the first gate run).

Corrections from the review-fix round: the "FORGE rows" column first counted every row containing the letters FORGE,
NEOFORGE rows included (renames 310, helpers 24, twins 6, uncalled 243, native-only 395; 1,057 in all); counted by
ecosystem token on daa94207's tables the rows naming FORGE are 275 + 23 + 79 + 5 + 232 + 393 = **1,007**, plus
native-only's `platform forge` line.

## Not done by this lane (all closed or handed on by the review-fix round; README "C4, lane census-forge review fixes")

- D31's "every-chain-transformer anchor census as a test" (the Stage 2.0 `ChainAnchorCensus` tool) is not landed.
- The other C4 hand-offs: merged-base wording in log and report strings (critic-c3b's 392 hits), the false SUSPECTED
  furnace-remainder row, the remaining MinecraftForge stand-ins outside the census tests.
