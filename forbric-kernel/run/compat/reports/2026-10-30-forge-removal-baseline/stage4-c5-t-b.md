# Stage 4 C5, lane T-B: transform/boot re-pins and stand-ins (Phase 1), with review A/B fixes

Lane T-B of the C5 plan (`stage4-notes/c5/PLAN.md` §2 "Lane T-B", as amended by D47, D65 and the critic's F10/F14), on
branch `s4-c5-t-b` from `cd6d0fe8`. Ports 46700-46719 (none used: no gate file changed, so no gate was run). Notes and
logs: `~/Forbric-backups/stage4-notes/c5/t-b/` (D67).

## Commits

| sha | item | subject |
|---|---|---|
| `2b6b531c` | Phase 1 C/E/F, 4-call-site pin, stand-ins, TSVs | Re-pin T-B's transform and boot tests on NeoForge's own base, so rows C, E and F and the stand-ins check the genuine shape |
| `646f0fad` | review A-3 | Key the restored NeoForge call-site pin by the calling method's descriptor and prove it reads the chain's output, so a twin overload or a census storing the input cannot pass it |
| `e47572fd` | review A-4 | Read fabric-api's own condition-mixin handler in the skip-marker premise, so a fabric-api change that makes it fit NeoForge's lambda fails the test as the comment says |
| `2dddbb57` | measured TSV gap | Add the ci-unstaged row for VanillaDamageReadInjectorTest.livingEntityReadsBeforeArmour, which f3b67a2d renamed without one |
| `14e48a0b` | A-3 follow-up | Correct the restored call-site pin's javadoc to the eighteen Minecraft methods the chain rewrites, as counted, not two dozen |

Only test sources and the two skip-baseline TSVs change; no kernel code, no gate, no build.gradle.

## The harness this lane ran on (not the pinned one)

`props.txt` passes `--offline`, and `~/.gradle/caches/modules-2` was recreated on 2026-11-08 without Forbric's
dependencies, so `./gradlew --offline` stops at `:compileJava` (walls.md, Lane T-B). JUnit 5.9.2 / platform 1.9.2 and
jetbrains annotations 23.0.0 are on no local disk, so no local seed reproduces the pinned classpath. Every suite number
below comes from Gradle with an init script that resolves the other pins from a `file://` repo built from local jars (no
download) and **substitutes junit 5.11.4 / platform 1.11.4 and annotations 26.0.2** (`c5/t-b/review-fixes/offline-local.init.gradle`,
copied from review-s1-A / T-A). Calibration: the same harness on `cd6d0fe8` gives 5767 tests / 0 skipped / 41 failed,
exactly the 41 names of `c4/failures-close.txt`.

## Items

- **Row C** `MergedBaseCalleeSwapTest`: `ADMITTED_NOT_REWRITTEN` explains `ServerPlayer.teleport` `unsetRemoved -> revive`
  (javap on the genuine base: `revive` at bci 203 where vanilla calls `unsetRemoved` at 187; `Entity.revive()` only calls
  `unsetRemoved` and implements `IEntityExtension.revive()`). `everyAdmittedSwapIsEitherRewrittenOrExplained` passes.
- **Row E** `MergedBaseFrameRecomputerTest`: **3 deleted** (`theRealModLinksAgainstTheRealMergedBase`,
  `theRecomputedFrameNamesWhatTheRealHierarchySays`, `theTypeItemStackKeptIsNotTreatedAsLost`), **1 moved**
  (`theConstantPoolSharingWriterIsNotUsed` -> `MergedBaseFrameShapeExecutionTest`, synthetic fixture), **1 new**
  (`theCommonSuperclassComesFromTheStagedHierarchy`, staged). All pass.
- **Row F** `MergedBaseSkipMarkerTest`: the premise is renamed
  `theBasesOnlyScanDirectoryLambdaIsNotTheShapeFabricsHandlerWasWrittenFor` and (A-4) reads fabric-api's handler from the
  pinned fabric-api: the one `@Inject` on `lambda$scanDirectory$0` (`skipData(Map, Identifier, Object, CallbackInfo)`)
  minus `CallbackInfo` must equal vanilla's lambda and differ from the base's only lambda. Passes.
- **4-call-site pin** `ChainAnchorCensusStagedTest.theRestoredNeoForgeCallSitesSurviveTheChain` +
  `aCopyMissingOneRestoredCallSiteIsNamed`: `ClientHooks.onRegisterParticleProviders` / `initClientHooks` in
  `Minecraft.<init>(GameConfig)` (bci 2371/2881), `EventHooks.onResourceReload` in
  `ReloadableServerResources.lambda$loadResources$2` (bci 38), `EventHooks.onTrySpawnPortal` in `BaseFireBlock.onPlace`
  (bci 33): once each on the raw base and once each after the whole chain, both sides; calling method keyed by name and
  descriptor (A-3). Measured on both sides: the chain rewrites `Minecraft` (constructor among 18 changed methods, +6 lambda
  thunks) and `BaseFireBlock` (`onPlace` among 3 methods); `ReloadableServerResources` is byte-identical, so for that call
  the pin only guards a future transformer.
- **KernelServerDutiesGenuineBaseTest**: the merged-base leg `onTheMergedBaseTheProductionPassInstallsEveryDuty` is gone
  (and its ci-unstaged row).
- **Stand-ins / samples**: `VanillaDamageReadInjectorTest` (neutral `org/example/damage/EarlierHooks.onHurt` instead of
  `ForgeHooks.onLivingHurt`, D42 F7), `AccessCensusTest` / `RestoredAccessTransformerTest` (re-sampled on
  `WeightedList$Builder#result`, NeoForge's `Lazy`), `EcosystemVersionsTest`, `StubLocalsCaptureTest`,
  `KernelClientPacksReaderTest` (renamed `aMinecraftForgeModsPackIsReadTheWayMinecraftForgeReadsIt` ->
  `aJarNeoForgeDoesNotOwnIsReadTheVanillaWay`; reads no fixture, no TSV rows either side), `ClientPackHookInjectorTest`,
  `KernelModContainerFactoryTest`, `UntrackedFluidEyeQueryInjectorTest`, `LambdaShapeEvidenceStagedTest` (dead helpers),
  `HopperFabricStorageInjectorTest` (premise wording), `FixturePolicyExtensionTest` (sample text).
- **Not done in this lane yet** (Phase 1 remainder): `KernelTransferInteropTest` sample ids, the LambdaShapeEvidence
  resource tsv, the `build.gradle` test-heap comment. Phase 2 (the `merged[ -](base|game)` wording pass and its residue
  list) is sequenced after the other lanes merge.

## Red checks (mutations in a scratch clone, never in the tree)

| mutation | test | result |
|---|---|---|
| census stores the bytes the chain was handed as `out` | `theRestoredNeoForgeCallSitesSurviveTheChain` | FAILED "the census kept Minecraft as the chain was handed it, not as the chain left it" |
| `BaseFireBlock.onPlace` keyed by a wrong descriptor | both pin tests | FAILED (premise lists `onPlace(...)`: 0 calls; red check: "the copy lost exactly one call" 0) |
| copy of the chain output with one call removed (in-tree red test) | `aCopyMissingOneRestoredCallSiteIsNamed` | passes = names exactly that call |
| fabric-api's handler shape set to the base's lambda | `theBasesOnly...` | FAILED "the base's only lambda$scanDirectory$0 has the shape fabric-api's handler takes" |
| `MergedBaseFrameRecomputer` with a reader-sharing writer (review A) | `theConstantPoolSharingWriterIsNotUsed` | FAILED "byte-identical output means the frames were copied" |

## Skip baselines (D47, F10, F14)

Rows dropped by exact name (both TSVs): ci-prepared `theConstantPoolSharingWriterIsNotUsed`, `theRealModLinks...`,
`theRecomputedFrame...`; ci-unstaged those three plus `theTypeItemStackKept...`, `onTheMergedBaseTheProductionPass...` and
`theMergedClassStillHasTheTwoLambdas...`. Rows added (ci-unstaged), measured by running the 18 touched classes outside
Gradle from a tracked-files-only tree with no stage, no Minecraft install (empty `user.home`) and no fabric-api:
`ChainAnchorCensusStagedTest.theRestoredNeoForgeCallSitesSurviveTheChain` and `aCopyMissingOneRestoredCallSiteIsNamed`
(class-level abort, like their 4 siblings), `MergedBaseFrameRecomputerTest.theCommonSuperclassComesFromTheStagedHierarchy`,
`MergedBaseSkipMarkerTest.theBasesOnly...`, and the pre-existing gap `VanillaDamageReadInjectorTest.livingEntityReadsBeforeArmour`
(renamed by f3b67a2d without a row). After the edits the measured skip set of those 18 classes equals the TSV rows for
them, both ways. The same classes on the stage with `requireFixtures=staged,game-side,mc-libraries,java-25` skip only
`StubLocalsCaptureTest` x4 (third-party, rows already present); no ci-prepared row is added. One local-only difference:
`anUnresolvableHierarchyLeavesTheClassAlone` runs here because `.stage4/run/mods` has the IPN jar CI's `.dev/staged` lacks,
so its ci-prepared row stays. `ci-prepared-noforge.tsv` (C6 under D47) still lists rows 869-872 for the moved/deleted cases.

## Suite

| tree | tests | skipped | failed | vs failures-close.txt |
|---|---|---|---|---|
| `cd6d0fe8` (base clone) | 5767 | 0 | 41 | same 41 names |
| `2dddbb57` (tip clone; `14e48a0b` after it changes a comment only) | 5766 | 0 | 35 | **0 new**, 6 gone (T-B's rows 9, 14-18), 35 kept, message prefixes (240 chars) equal |

Remaining failures: `stage4-notes/c5/failures-t-b.txt`. Gates: none run (no gate script, lib.sh or kernel code changed).

## D65: T-B's red deletions (for RECORD's "red test deleted" table)

The three deleted `MergedBaseFrameRecomputerTest` cases were red at C4 close (failures-close rows for `theRealModLinks...`,
`theRecomputedFrame...`, `theTypeItemStackKept...`). Their fixture is InventoryProfilesNext-**forge**, a MinecraftForge mod
Forbric now refuses, and their pins named MinecraftForge carrier types (`TypeNotPresent
net/minecraftforge/common/capabilities/CapabilityProvider$ItemStacks`), so no genuine-base form of them exists. Shared-path
proof for what they covered: the recomputer's staged-hierarchy resolution is still asserted on NeoForge's base by
`theCommonSuperclassComesFromTheStagedHierarchy` (passes), its "abstain when unprovable" path by
`anUnresolvableHierarchyLeavesTheClassAlone`, and its "recompute, never copy" property by the moved
`theConstantPoolSharingWriterIsNotUsed` with a red check that fails for its stated reason (table above).

## Review findings

| finding | outcome |
|---|---|
| A-1 / B-1 "fill the Gradle cache" cannot work from local files | confirmed: junit 5.9.2/1.9.2 and annotations 23.0.0 are on no disk; notes corrected; the suite ran on the substituted harness above, calibrated against failures-close |
| A-2 / B-3 four new tests without ci-unstaged rows | confirmed and fixed in `2b6b531c` (rows in the same commit as the drops), plus the measured livingEntity gap (`2dddbb57`); B-3's rename is in `KernelClientPacksReaderTest`, not `EcosystemVersionsTest` |
| A-3 pin does not prove it reads the chain's output; name-only key | confirmed and fixed in `646f0fad`; the review's "BaseFireBlock untouched by the chain" was measured wrong (onPlace is rewritten), only ReloadableServerResources is |
| A-4 premise comment claims fabric-api is checked | confirmed and fixed in `e47572fd` (reads fabric-api) |
| B-2 Row E counts | confirmed; 3 deleted / 1 moved / 1 new stated here and in the handoff |
| B-4 Phase 2 + D65 evidence not handed on | confirmed; D65 evidence above, Phase 2 in the handoff |
| B-5 no checks.md | confirmed; `c5/checks.md` created with a Lane T-B section |
