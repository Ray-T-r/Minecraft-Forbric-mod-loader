# Stage 4 C5, lane S1: featuresPerStep (D52) and the S1 review fixes

Lane S1 of the C5 plan (notes `~/Forbric-backups/stage4-notes/c5/PLAN.md` §2 "Lane S", split by D63), branch `s4-c5-s1`
from `cd6d0fe8`. Ports 46720-46739 (none used: no gate ran). Logs: `~/Forbric-backups/stage4-notes/c5/s1/review-fix/`.
Item 1 is done; items 2-4 and the D63 `customPayloadInteropSeam=off` measurement are not started (see "Open").

## Commits

| sha | subject |
|---|---|
| `64695aef` | Rebuild a feature list a Fabric mod replaced instead of casting it to NeoForge's Lazy, so NeoForge's structure modifiers still apply |
| `26630d01` | Credit FeaturesPerStepRefreshStagedTest with executing FeaturesPerStepRefreshInjector, since the injector census failed on it as undeclared |
| (this file) | the record |

## Item 1: the featuresPerStep symptom check (PLAN S item 1, D52)

On NeoForge's own base `ChunkGenerator` has one field `private final Supplier featuresPerStep`, filled with `Lazy.of(...)`
by the constructor, and `refreshFeaturesPerStep()` is `aload_0; getfield featuresPerStep; checkcast
net/neoforged/neoforge/common/util/Lazy; invokevirtual Lazy.invalidate()V; return` (javap on the staged base).
`ServerLifecycleHooks.runModifiers` calls it on every level stem's generator between the biome and the structure
modifiers. fabric-biome-api 18.0.6 (fabric-api 0.155.2) widens the field and, when a Fabric modifier adds or removes a
feature, `BiomeModificationImpl.finalizeWorldGen` writes a Guava `Suppliers.memoize` there.

**Symptom: red on the genuine base.** `FeaturesPerStepRefreshStagedTest.onNeoForgesOwnBaseTheRefreshThrowsOnTheSupplierFabricWrites`
allocates the staged base's `ChunkGenerator` (widened exactly as fabric-biome-api's class tweaker widens it), writes a
Guava memoized supplier and calls `refreshFeaturesPerStep()`: `ClassCastException` at the base's `checkcast`. On a server
this is caught by `KernelNeoWorldgen.applyBiomeAndStructureModifiers` and no NeoForge structure modifier applies.

**Adaptation (D52, Fabric-on-NeoForge):** `FeaturesPerStepRefreshInjector` (COREMOD phase, one REQUIRED anchor on
`ChunkGenerator`, all-or-nothing on the reviewed five-instruction shape, declines by name otherwise) replaces
`checkcast; invokevirtual` with `aload_0; invokestatic KernelFeaturesPerStep.refresh(Supplier, ChunkGenerator)`.
The runtime helper invalidates a NeoForge `Lazy` exactly as the base does; any other supplier is replaced by a new
`Lazy` of the constructor's computation (`FeatureSorter.buildFeaturesPerStep` over the biome source's possible biomes and
the generation settings getter). It never throws (`Caught.warn` if the field cannot be written). Native NeoForge never
puts anything but its own `Lazy` there, so on a NeoForge-only server the behaviour is the base's.

### Adaptations

| switch | what `=off` does | ledger |
|---|---|---|
| `-Dforbric.featuresPerStepRefresh=off` | the injector is not registered (KernelBoot) and declares no anchor; the base's cast stays, so the symptom returns | not in `released-switches.txt` (that file lists only names main at 3965a9f5 declared) and not in `ForbricSwitches` (RENAMED/RETIRED only); ForbricSwitchesTest 17/17 |

### Tests

`FeaturesPerStepRefreshStagedTest` (5, all on the staged base, `@ExecutesInjector(FeaturesPerStepRefreshInjector.class)`):
- the red case above;
- `theRewrittenRefreshRebuildsTheSupplierFabricWrote`: one biome whose generation settings place a known `PlacedFeature`
  in step 0; after the rewritten refresh the field holds a NeoForge `Lazy` whose list is exactly `[[first]]`; then a second
  biome placing `second` is added and the refresh invalidates the same `Lazy` in place, which now yields `{first, second}`;
- a NeoForge `Lazy` is still only invalidated (same object, recomputed on the next read);
- the reviewed shape is rewritten once, a second pass stands down, another shape is left whole;
- `=off` leaves the bytes and declares no anchor.

Red controls (helper mutated in place, test class re-run, helper restored; logs `mutant-empty.log`, `mutant-once.log`):
- the helper writes `Lazy.of(() -> List.of())`: `theRewrittenRefreshRebuildsTheSupplierFabricWrote` fails at the first list
  assertion (line 94);
- the helper computes the list once and wraps the result: the same test fails at the post-change assertion (line 102).

`MergedBaseFeaturesPerStepTest` trimmed per PLAN: `theFieldCarriesVanillasDescriptorAndOnlyThatOne` re-pinned to `[Supplier]`
(was failing in failures-close, now passes), `theFieldIsAlsoWriteableFromAnotherClass` and
`aSecondPassLeavesTheRepairedClassAlone` kept; the three cases that read the merged-base builder's members (the
`ClearableLazy` view, `forbric$providerCell$*`, the `$forbricProvider$` carrier, the INSTANCEOF guard) deleted.

`KernelRuntimeClasses` gains `net.forbric.kernel.runtime.KernelFeaturesPerStep` (COMPILED, no seam calls: it is reached
only from the rewritten call site). The registry now has 70 entries.

Skip TSVs (D47): `ci-unstaged.tsv` drops the three deleted cases' rows and adds the five new tests, measured locally with
`-Pforbric.stagedRoot=/nonexistent-s1/run` (all five skip as `[fixture:staged] NeoForge's staged base absent`, the first
fixture each test reads; CI's Java 21 reaches the same check first). `ci-prepared.tsv`: no row (all five run with
`-Pforbric.requireFixtures=all`). `ci-prepared-noforge.tsv:863` still names the deleted `readsAreRetargetedAtSupplierGet()`;
left for C6 (PLAN §3: that TSV is untouched in C5; D47: it stays C6).

Not done: PLAN item 1's server boot (fabric-api + a Fabric feature-adding mod + the neolive structure modifier, grep the
WARN). No Fabric feature-adding mod was found locally, and gates cannot build their jar (below). The staged unit test is
the symptom check map-tests D allows ("staged unit test ... or a gate boot").

## Harness (no download; a recorded deviation)

`~/.gradle/caches/modules-2` was re-created on 2026-11-08 20:47 by another project; Forbric's `--offline` builds failed
at `:compileJava`. By 2026-11-09 19:09 an online resolve by someone other than S1 had put most dependencies back. Every
Gradle run in this record passed `-I offline-local.init.gradle` (notes dir, never in the repo):
- mixinextras-fabric 0.5.4 and fastutil 8.5.18 from a local file repo; both jars' sha1 equal the pre-wipe cache folder
  names (5e167154..., a6cff377...) in a Nov 5 Gradle worker classpath;
- jetbrains annotations 23.0.0 -> 26.0.2 and error_prone_annotations 2.47.0 -> 2.48.0 (annotation-only; neither pinned jar
  is on disk);
- **JUnit 5.9.2 -> 5.11.4, platform 1.11.4** (the pinned jars are on no disk). This changes the test harness. Lane T-B
  calibrated the same substitutions on `cd6d0fe8`: 5767 tests / 0 skipped / 41 failed with exactly failures-close's 41
  names; here every kept failure's first 240 message characters equal failures-close's.

Gates were not run: `run/lib.sh` `kernel_jar`/`kernel_classpath` call `gradlew --offline` with no init script, so every
gate stops at the jar build (annotations 23.0.0). S1 changed no gate file.

## Suite

`heavy.sh ./gradlew -I <init> $(cat .stage4/props.txt) -Pforbric.requireFixtures=all cleanTest test --continue`.

| tip | tests | skipped | failed | vs failures-close (41) |
|---|---|---|---|---|
| `64695aef` | 5769 | 0 | 38 | 1 new (`InjectorExecutionCensusTest.everyInjectorIsExecutedOrListedWithAReason`: "undeclared: FeaturesPerStepRefreshInjector", fixed by `26630d01`), 4 gone, 37 kept (240-char message prefixes: 0 differ) |
| `26630d01` | 5769 | 0 | 37 | **0 new**, 4 gone, 37 kept (240-char message prefixes: 0 differ) |

Gone (4): the three deleted `MergedBaseFeaturesPerStepTest` cases and the re-pinned
`theFieldCarriesVanillasDescriptorAndOnlyThatOne` (passes). Test count: 5767 - 3 deleted + 5 new = 5769.

## Review fixes (review-s1-A, review-s1-B)

| finding | outcome |
|---|---|
| A-1 / B-1 (jars "on no disk", "needs a download") | confirmed in part; notes corrected (walls.md, "S1 review fixes"); suite run with the init script above |
| A-2 (rebuild test passes for an emptied list) | confirmed; fixed in `64695aef`, both mutants red |
| A-3 / B-3 (TSV rows) | confirmed for ci-unstaged (fixed in `64695aef`); ci-prepared needs none; the noforge row is C6's (PLAN §3, D47) |
| A-4 / B-4 (switch ledger row) | confirmed: not owed; switch listed under adaptations here |
| B-2 (saved diff lacked the untracked files, stale draft) | confirmed; the code is now committed; the stale drafts are moved to `c5/s1/stale-2026-11-02/` |
| B-5 (edits never compiled or suite-run) | confirmed; compile triple, the four touched classes and the full suite now run (above) |
| B-6 (no record, no checks entry) | confirmed; this file and `c5/checks.md` "S1 review fixes" |
| B-7 (d23.out lost) | confirmed; handoff says item 4 must re-run the d23 scan and save it before stating 0 instances |
| B-8 (G-A/G-B1 finals wait on S1) | confirmed; in handoff.md |

## Open

- Item 1 server boot (needs a Fabric feature-adding mod fixture and gates that build).
- Item 2 KernelRuntimeClasses liveness (70 entries), item 3 AbiLinkAudit D21/D23 re-measure, item 4 D23 record (re-run
  d23), D63 `customPayloadInteropSeam=off` null-tolerance measurement.
- ci-prepared-noforge.tsv:863 stale row (C6).
- G-A's and G-B1's final runs wait for `64695aef` + `26630d01` to be merged (PLAN §4).
