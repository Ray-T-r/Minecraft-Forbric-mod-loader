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

## T-B phase 2 (wording pass, 2026-11-11)

PLAN §2 "Lane T-B" Phase 2, after the wave 2b merge part 1 (`merge-w2b.md`). Branch `s4-c5-t-b`, reset to the stage tip
`28188fac` (it had no commits past `b9154084`). Pinned harness (D68: `.stage4/props.txt`, plain `--offline`, no init
script); heavy runs through `.stage/heavy.sh`; ports 46700 (GATE_PORT) only. Logs: `stage4-notes/c5/t-b/w2b/`.

### Commits

| sha | subject |
|---|---|
| `dfb505ac` | Call the jar the tests read the staged base, not the merged base, since TestFixtures.stagedBase() is NeoForge's patched game on this stage |
| `3771ea1b` | Say what NeoForge's own game does in the stub-rebind, break-and-loot and tooltips canary comments, since each claim was measured there and not on the merged base |
| `7a4323fe` | Make gate-m40's absence checks FAIL on a phase that timed out, since a log cut off by the timeout proves nothing is absent |
| (this record) | Record lane T-B phase 2 |

### The mechanical commit (`dfb505ac`)

`grep -rnaE 'merged[ -](base|game)' forbric-kernel/src/test/java`: **373 lines in 190 files** at `28188fac`, **158 lines
in 100 files** after (155 in 98 after the review fix `2ac8801a`, below). 215 lines in 108 files reworded; wording only (skip and fixture reasons, assertion messages,
javadoc and comments), no code path, assertion, test name or TSV row changed. Rules:

- what a test reads today, in present tense: "staged merged base" -> "staged base", "merged base not staged" -> "base not
  staged", "... merged game required" -> "... staged game required", "the merged game" -> "the patched game";
- three present-tense claims were measured on NeoForge's base first (javap on `.stage4`'s patched jar: ClientLanguage calls
  the three-argument `Language.loadFromJson`, so the two-argument stub is uncalled; `MixinOverloadPinTest`'s staged case
  pins NeoForge's `MinecraftServer` overload first and passes; `MixinTailCaptureNativeTest` reads the launch's native
  references, not an embedded index);
- `KernelModelFormatsTest` had an orphaned javadoc about a MinecraftForge carrier field; it now sits on the NeoForge carrier
  field it describes;
- scope: files no other C5 lane changed (PLAN), plus T-B's own phase-1 files, plus `MergedBaseFeaturesPerStepTest`'s skip
  reason (S1 changed the file in C5 but did not reword it, so it is not "reworded by its owner"; its two other hits are
  history). Nine other residue files were changed by another C5 lane (the TSV's owner column, `ChainAnchorCensusStagedTest`
  included: S's, see below); they hold only history lines and were left alone.

Residue (155 lines in 98 files after `2ac8801a`; per line with its class in `stage4-c5-t-b-wording-left.tsv` beside this file):

| class | lines | files | what, and its fate |
|---|---|---|---|
| keep-guard | 15 | 1 | `StagedJarLiteralGuardTest`'s retired spellings (`merged-base/…`), kept by PLAN |
| keep-concept | 15 | 6 | a Forbric <= 0.4 merged base as an input the code still meets: the old-install refusal (`LaunchInputCheckTest` 6), an older installer's profile (`PushAndRunTest` 5), an embedded index (`TestFixtures`, `NativeGameReferencesTest`, `LootSourceCallbacksSpellingTest`), and a synthetic `merged-base.jar` file name for an unowned jar (`EnvironmentStripTransformerTest`). Keep |
| stage6-name | 9 | 8 | named after production code that keeps the name: `ForbricMergedBaseCompatTransformer`'s claim ids `forbric-merged-base-compat#…` (6), "the merged-base repair" / "merged-base compat edit" (2), `run/compat/field-drift.py`'s output line "vanilla classes absent from merged base" (1). Stage 6, with the code |
| stage6-measure | 1 | 1 | `DeclarationReaderCensusTest:29` names "the merged base and both carriers … MinecraftForge's handshake" in present tense; a word swap would assert an unmeasured list of NeoForge's `ModList` readers. Stage 6, with a measurement |
| history-fixture | 20 | 18 | a synthetic fixture or a shipped table laid out after the merged base or its builder (weave fixtures, `PackRepairInjectorsTest`'s shape, `OptionalPartnerInjector*` tables). True as provenance; keep, Stage 6 if the fixtures are re-derived |
| history | 95 | 71 | what the merged base did or had: past tense, "On the merged base …", dated measurements, contrasts with NeoForge's base. Keep |

Outside the regex (not counted, not changed): "merged classes" / "merged jar" / "merged side" in 14 test lines; RECORD's
`stage4-c5-wording-left.txt` grep covers `net/minecraftforge|MinecraftForge|merged base` over `K/run` + `K/src/test`.

### Leftovers handed to this pass

- **G-B1 B-4** (canary-source comments, `3771ea1b`): 10 lines in 7 files of `canary/break-and-loot`, `canary/stub-rebind`,
  `canary/tooltips`. G-B1 left them because each claim needed re-measuring on NeoForge's game; measured with javap on the
  `.stage4` patched jar before rewording: nothing invokes `Block.tryDropExperience`; `BlockBehaviour` calls
  `Player.getDestroySpeed(BlockState, BlockPos)`, nothing calls the one-argument form, which forwards with a null pos and is
  declared first; `LivingEntity.randomTeleport(double,double,double,boolean)` forwards to the `ItemStack` overload, which
  `TeleportRandomlyConsumeEffect` (chorus fruit) calls, and is declared first; `Language.loadFromJson(InputStream,
  BiConsumer)` passes a no-op component consumer to the three-argument body, which `ClientLanguage` calls. Every edit stays
  inside its line. Proof the canaries need no rebuild: all five source sets compiled with `javac -g --release 21` at
  `dfb505ac` and `3771ea1b` give 11 classes, 11 byte-identical (`c5/t-b/w2b/canary-bytes-cmp.py`). Still in `canary/`
  and outside this list: 10 lines in 8 files (behavior, transfer, world-upgrade, menu-codec READMEs, EnergyWorldProbe,
  creative-search, attribution `fabric.mod.json`, ForbricFabricLive), owned by G-B2/UPG/G-C/G-A; for RECORD.
  Ownership: G-B1's record (`stage4-c5-g-b1.md` :167, :194) and `merge-wave2.md`:173 had given this residue to RECORD /
  Stage 6 with a canary rebuild and an m43/m46/m51 re-run. This pass took it with the byte-identity proof above in place of
  the rebuild and re-run (the review confirmed it by a second method: no token outside comments changed), so RECORD
  strikes these 10 lines from its residue list.
- **T-A B-2** (`ci-unstaged.tsv` reason column of the 15 `MergedBaseGateSpellingTest` / `FabricFreezeHookSpellingTest`
  rows naming the old jar): not reworded. The ratchet compares the first three columns only and the reason column is
  rewritten wholesale by the regeneration RECORD owns (D47; PLAN §3 has nobody rewrite reasons before this pass, but no
  rule gives it the regeneration). For RECORD: at `7a4323fe` the reason column names the merged base or merged game in
  395 of 1980 `ci-unstaged.tsv` rows (282 of them the `merged-base/` path), 2 of 727 `ci-prepared.tsv` rows, and 29 of 895
  `ci-prepared-noforge.tsv` rows (16 of them the `merged-base/` path, the `[fixture:forge-carrier]
  …/staged-noforge/run/merged-base/forge-runtime-interop.jar` rows; C6). `dfb505ac` changed the skip messages of many of those tests, so the regenerated
  reasons will differ from the committed ones in more than the 15 rows.
- **K3b's new tests in the prepared / noforge profiles** (merge-w2b open item): `forbric.hideForgeCarrier` is retired
  (build.gradle warns and runs the ordinary suite), so the noforge profile is the prepared one. The six K3b/G-C classes
  (`KernelLifecycleEarlyConfigOrderTest`, `KernelModLoaderPublishedOrderTest`, `KernelLifecycleConfigPassesTest`,
  `KernelLifecycleLateConfigTest`, `KernelLifecycleRegistrationWindowTest`, `KeyModifierSplitStagedTest`) under
  `-Pforbric.hideForgeCarrier -Pforbric.requireFixtures=staged,game-side,mc-libraries,java-25` on `.stage4`: 28 tests, 0
  skipped, 0 failed (`k3b-prepared-noforge.log`). Their only fixtures are STAGED and GAME_SIDE, which both profiles
  require, so they cannot skip there: no `ci-prepared.tsv` or `ci-prepared-noforge.tsv` row is needed.
- **G-C's `forbric.keyModifierSuffix` RETIRED row and ci-unstaged swap**: verified, nothing to change.
  `ForbricSwitches.RETIRED` has the row; `ci-unstaged.tsv` has `KeyModifierSplitStagedTest`'s census row and no
  `MergedBaseKeyModifierSuffixTest` row; neither other TSV names the deleted test. Counts for RECORD: RETIRED +1,
  ci-unstaged -1/+1; `AssertCompatTest` pins 37.
- **`ChainAnchorCensusStagedTest`'s three merged-base literals** (:83, :151, :175; S handed them back to this pass,
  handoff.md :279 and :461): answered. Each says what the merged base "had lost" against NeoForge's restored call sites,
  which is history; kept. Owner column: S (`7d506522`, `6a75fcd5`, `e0844870`), T-B phase 1 also changed the file.
- **m40's PASS lines in a timed-out run** (`7a4323fe`, G-B2's file, assigned here by merge-w2b as "(wording)"): a behaviour
  change in G-B2's gate, not wording: on a timed-out phase eight checks that printed PASS now print FAIL; see below.

### gate-m40 (`7a4323fe`)

A timed-out phase's log stops where the gate killed it, so seven `check_absent` lines and one count of zero ("no route
ran") printed PASS on it. `run_phase` records the phases that time out; `only_if_finished` prints those eight as FAIL
for such a phase and runs them unchanged otherwise. Runs on `.stage4`, GATE_PORT 46700, counted as `^[kernel] PASS` /
`^[kernel] FAIL` lines:

| gate | M40_TIMEOUT | result |
|---|---|---|
| unchanged (`28188fac`'s file, run as an untracked copy) | 25 | GREEN 36/0 (every phase finished within 25 s; `m40-orig-green-t25.log`) |
| unchanged | 6 | 8 PASS / 32 FAIL: the four phases timed out; the eight PASS lines are exactly the absence/zero checks (`m40-timeout-before.log`) |
| `7a4323fe` | 6 | **0 PASS / 40 FAIL**, the eight now "phase X timed out, so its log proves no absence" (`m40-timeout-after.log`) |
| `7a4323fe` | default (240) | **GREEN 36/0**, the same 36 PASS lines as the unchanged gate's green run, CAUGHT none (`m40-green-after.log`) |

Port 46700 free after each run; no JVM left.

### Suite and tests

- Full suite, `heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test
  --continue` in this worktree: **5873 tests, 0 skipped, 5 failed** = exactly the five names of `c5/failures-k.txt`
  (0 new, 0 gone; `suite.log`, `suite-xml/`). 5873 = 5858 + K3b 7 + G-C 8, as merge-w2b predicted. Four test files got
  their last wording edits after that run compiled (`MergedBaseFeaturesPerStepTest` skip reason, `MixinOverloadPinTest`,
  `MixinTailCaptureNativeTest`, `MixinFitLivenessTest` javadoc); those four classes re-run on the committed text: 38/0/0
  (`late-edits.log`).
- `run/compat` `python3 -m unittest discover` (with the m40 change): 237 OK.
- TSVs: none touched (no test added, deleted or renamed).

### Review fixes (`review-t-b2.md`, 2026-11-11)

| finding | outcome |
|---|---|
| B2-1 three present-tense premises filed as history | **Fixed** (`2ac8801a`), reworded after measuring on `.stage4` rather than refiled. `ChunkGenerator.featuresPerStep` is declared once in `patched-mc-neoforge-26.2.jar`, descriptor `Ljava/util/function/Supplier;` (javap); and every gate-m9 client-boot log on the genuine base in the C5 notes (11 runs: k1, k3b, g-a, g-c, review-g-c) logs `replayed 1 previously unmatched directive(s)` beside `(23 AT, 0 AW)`, so `AccessCensusGateM9ContractTest:20`'s "the replay count is 0" was false there, not only unmeasured. The javadoc now says the count is no invariant, which is why the gate asserts the outcome. `MergedBasePipelineDriftTest:58/:86`: NeoForge's `XoroshiroRandomSource.nextDouble()` is `l2f; ldc 1.110223E-16f; fmul` where vanilla 26.2 has `l2d; ldc2_w; dmul`, and its `Goat.finalizeSpawn` is `ldc 0.1f; fcmpg` where vanilla has `f2d; ldc2_w 0.10000000149011612; dcmpg`; both lines now name NeoForge's patched game. Comments only, every line in place; the three rows leave the residue (158 -> 155 lines, history 98 -> 95) |
| B2-2 owner column and two counts | **Fixed** in this record and the TSV: `ChainAnchorCensusStagedTest`'s three rows name S; the "nine files" sentence now counts the nine left alone without `MergedBaseFeaturesPerStepTest`; ci-prepared-noforge's `merged-base/` path count is 16 of 29 (recounted) |
| B2-3 scope (a) `MergedBaseFeaturesPerStepTest`, (b) canary comments, (c) gate-m40 | **No code change; recorded for the merger.** (a) kept, wording only, one literal in S1's file: the merger may drop that hunk. (b) ownership and RECORD's strike noted above. (c) m40 is recorded above as a behaviour change in G-B2's file (stricter; D34 shown) |

Suite after `2ac8801a` (`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar
cleanTest test --continue`, run to the end): **5873 tests, 0 skipped, 5 failed** = exactly the five names of `c5/failures-k.txt` (0 new, 0 gone);
`AccessCensusGateM9ContractTest` 4/0/0 and `MergedBasePipelineDriftTest` 1/0/0 on the committed text
(`c5/t-b/w2b/review-fix/suite.log`, `suite-xml/`).

### Open items

| item | owner |
|---|---|
| TSV reason columns (395 / 2 / 29 rows above) and the skip messages `dfb505ac` changed: regenerate | RECORD (D47) / C6 for noforge |
| `DeclarationReaderCensusTest:29` (present tense, MinecraftForge's handshake): re-measure NeoForge's `ModList` readers, then reword | Stage 6 |
| 9 stage6-name lines: rename with `ForbricMergedBaseCompatTransformer` and `field-drift.py` | Stage 6 |
| 10 canary lines outside G-B1's list (behavior, transfer, world-upgrade, menu-codec READMEs, EnergyWorldProbe, creative-search, attribution, ForbricFabricLive) | RECORD residue list / owners |
| "merged classes" / "merged jar" wording outside the regex (14 test lines) | RECORD's wording-left grep / Stage 6 |
| Name the directive gate-m9's pack replays on NeoForge's base (`replayed 1`; `AccessCensus` logs only the count), and check gate-m9's comment that the biome widener "matches first time" against it | Stage 6 (access census) |
| Strike the 10 G-B1 canary lines `3771ea1b` reworded from RECORD's residue list | RECORD |
| Merger: `7a4323fe` changes G-B2's gate-m40 behaviour (stricter); `dfb505ac` edits one skip reason in S1's `MergedBaseFeaturesPerStepTest` | merger |
