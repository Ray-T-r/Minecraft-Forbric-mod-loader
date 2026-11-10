# Stage 4 C5 — lane K2: fabric-registry-sync's remap tracker on the particle provider view

Branch `s4-c5-k2` from lane S's tip `0daa5b0b`. Evidence: `~/Forbric-backups/stage4-notes/c5/k2/`.

## Symptom

A Forbric client cannot join a Forbric dedicated server over a socket when both run fabric-api and a mod that
registers particle types: `Registry remapping failed!` / `java.lang.UnsupportedOperationException at
Int2ObjectMap.clear` from `Int2ObjectMapTracker.onRemap` ← `MappedRegistry.remap` ← `ClientRegistrySyncHandler.apply`
(UPG found it in gate-m58, D74/D75).

Smallest reproduction (`k2/repro.sh`, gate-m12's harness with `M12_MODS`; ports 46880/46881):

| run | mods (both ends) | m12 | remap error | probe |
|---|---|---|---|---|
| `repro-balm-before` (0daa5b0b) | fabric-api 0.155.2, balm | 32 / 0 | 0 | 0 modded particle types — balm registers none, so the particle registry is not in Fabric's sync |
| `repro-sc-before` (0daa5b0b) | + sophisticatedcore | 20 / 12 | 2 | `sophisticatedcore:jukebox_note` id 125, pre-join only; never joined |

The particle-registering mod is sophisticatedcore (`jukebox_note`); balm, shogi and waystones register none.

## Root cause

1. On NeoForge's base `ParticleResources.providers` is `Map<Identifier, ParticleProvider<?>>`. The vanilla-typed
   `Int2ObjectMap` twin that fabric-api reads holds the kernel's view of it (`KernelParticleProviders.IntKeyedView`):
   it has no int keys of its own. Every key is `PARTICLE_TYPE.getId(type)` as of the call.
2. fabric-registry-sync 7.1.0's client `ParticleResourcesMixin` (`@Inject <init> RETURN`) calls
   `Int2ObjectMapTracker.register(PARTICLE_TYPE, "ParticleEngine.providers", this.providers)`, which attaches it to the
   view.
3. `Int2ObjectMapTracker.onRemap` (javap, offsets 0–237) copies the map (`new Int2ObjectOpenHashMap(mappers)`) and calls
   `mappers.clear()`. It then puts each value back under `getRawIdChangeMap().getOrDefault(key)`. That only works
   if the map's keys are still the OLD ids when the event fires.
4. `MappedRegistryMixin.remap` rebuilds `byId`/`toId` first (offsets 798–1041). It fires `RegistryIdRemapCallback`
   after that (1044–1065), and `RemapStateImpl.<init>` reads `registry.byId(newId)`. So when the event fires, the
   view already answers the new ids.
5. Result: `clear()` throws (it is unsupported, and it would empty NeoForge's own map). A `clear()` that worked would
   be wrong too: the tracker would read new ids as old ones and re-key them a second time. Measured with a mutation
   that makes `clear()` real: `balm:a answers … expected <provider of balm:a> but was <provider of
   sophisticatedcore:c>` (`k2/clear-mutation.failures`).

Why it is new in Stage 4 (the pre-Stage-4 arm). On the merged base the particle registry was a MinecraftForge
`NamespacedWrapper`. Fabric's remap was staged into `GameData.injectSnapshot`, and
`KernelForgeWrapperSync.announceRemapToFabric` (cf0498eb) fired `onRemap` only when an id actually moved
(`if (changed == 0) return;`). Same mod set on both ends, nothing moved, the tracker never ran. UPG's old client
(merged base, kernel 187143a1) joined with these mods with no remap error (`c5/upg/w2/p2/m58-main-2.evidence/logs/s2-server.client.game.log`, UPG's run, not
one of K2's:
"flushing the Forge-wrapped registries' staged ids", no `Registry remapping failed`). On the genuine base the registry is
NeoForge's own `MappedRegistry`, and fabric's `remap` fires the event on every sync. The defect was latent before:
a moved id, or the disconnect `unmap` (open-defects audit 2026-10-02), would have hit the same `clear()`.

Native arms:
- Native NeoForge has no tracker. Its map is keyed by name (genuine base `ParticleResources`:
  `private final Map<Identifier, ParticleProvider<?>> providers`, `getProviders()` returns that map), so a remap
  leaves it correct with no work.
- Native Fabric re-keys a raw `Int2ObjectOpenHashMap`. The staged test runs fabric-registry-sync's real tracker over
  one (`aRawIdMapIsStillReKeyedOnRemapAsOnNativeFabric`). Afterwards every provider is under its type's new id. The
  view gives the same answer with no work (`theViewAnswersTheNewIdsBeforeTheRemapEventFires`).

What would falsify this. If the view were stale when the event fires (still answering old ids), the tracker would be
right and the view would need a real `clear()`. `theViewAnswersTheNewIdsBeforeTheRemapEventFires` shows the premise only
on the test's own model of `remap` (it rebuilds the ids first because the test says so). The game's order is pinned on
the real bytes by `theRealRemapRebuildsTheIdsTheViewReadsBeforeItFiresTheEvent` (review fix, below).

## Fix (`8acc7f5f`)

The tracker is not attached to the view, because there is nothing for it to do.
`ParticleViewTrackerInjector` (COREMOD, both sides) inserts this at the head of fabric-registry-sync's
`Int2ObjectMapTracker.register(Registry, String, Int2ObjectMap)`:

    if (KernelParticleProviders.followsTheRegistry(map, name)) return;

The guard decides by the map object (`map instanceof IntKeyedView`), never by a mod or an id. A raw-id map handed to
the same method is tracked exactly as fabric-registry-sync tracks it. `IntKeyedView.clear()` stays unsupported. The
view now takes its id table as two functions, bound to the particle registry in the game, so a staged test can remap
it without a bootstrapped game. Other changes in the commit:
- `KernelRuntimeClasses` lists the new call.
- `KernelBoot` registers the injector next to `ForbricMergedBaseCompatTransformer`, which installs the view.
- The switch is `-Dforbric.particleViewDetach=off` (attaches the tracker as written). It is new and not a rename or a
  retirement, so it has no `ForbricSwitches` row (lane S1's rule).

No kernel repair becomes unnecessary: the view (`giveTheVanillaParticleMapAViewOfTheLiveOne`) is still needed for
fabric-api's direct `getfield`. No C3 deletion.

## Tests and red controls

`ParticleViewRemapStagedTest` has 6 tests on the staged base. They use fabric-api 0.155.2's nested
fabric-registry-sync 7.1.0 and fabric-api-base. The registry is a proxy for `Registry` + `ListenableRegistry`, with
fabric's own `EventFactory` events, so `register` attaches the tracker as it does in the game. A remap reassigns
the ids first and then fires the event with every old → new id, as `MappedRegistry.remap` does.

| test | what it shows |
|---|---|
| `aRawIdMapIsStillReKeyedOnRemapAsOnNativeFabric` | native Fabric arm, through the rewritten tracker |
| `theTrackerAsWrittenThrowsOnTheViewsClear` | the defect, on the tracker bytes as shipped |
| `afterARemapEveryProviderAnswersUnderItsTypesNewIdAndNeoForgesMapIsIntact` | the fix: every provider is under its new id, and NeoForge's map equals its pre-remap copy |
| `theViewAnswersTheNewIdsBeforeTheRemapEventFires` | the premise, and the falsification check |
| `switchedOffTheTrackerIsAttachedAsWritten`, `onlyTheTrackersRegisterGetsTheGuardAndASecondPassStandsDown` | shape and switch |

Red controls:
- Scratch clone, `followsTheRegistry` mutated to `return false` (the old behaviour): exactly
  `afterARemap…Intact` goes red, with `UndeclaredThrowableException` caused by `UnsupportedOperationException`
  (`k2/red-mutation.*`).
- `clear()` mutated to clear: `afterARemap…` red, providers on the wrong types (above), and `theTrackerAsWritten…`
  red (`k2/clear-mutation.*`).
- At runtime, `-Dforbric.particleViewDetach=off` on the sophisticatedcore set brings the symptom back: m12 20/12,
  2× `Registry remapping failed` (`k2/repro-sc-switchoff.*`).

## Acceptance

| run (fix tree) | mods | m12 | remap error | probe after the join |
|---|---|---|---|---|
| `repro-sc-after` | fabric-api, balm, sophisticatedcore | 32 / 0 | 0 | `jukebox_note` id 125: int view non-null, NeoForge map non-null, same object; 126 providers |
| `accept-after` | fabric-api, balm, shogi, waystones, sophisticatedcore, sophisticatedbackpacks | 27 / 5 | 0 | same as above; joined (`joined world via quick-play`) |

In `accept-after` the session then dies on the first chunk: `IllegalArgumentException: No value with id 37849` at
`LinearPalette.read`. That is the same stack as UPG's `43766`, so it is K1's block-state id defect (D75), not the
particle remap. Its 5 FAILs are that crash (no protocol error, survived simulation, left cleanly, watchers, no client
crash). In these live runs the server and the client gave the type the same id. Ids that actually move: the staged
test, and review A's live run `c5/review-k2-A/rv-fix.*` (below).

Suite (`jar cleanTest test --continue`, pinned `--offline`): baseline at 0daa5b0b was 5789 / 0 skipped / 5 failed.
The fix tree is 5795 / 0 / 5, with the same 5 names as `failures-wave1.txt` and 0 new.

Gates (fix tree; counts are `^[kernel] PASS` / `^[kernel] FAIL` lines; references from S's and the merger's records):

| gate | fix tree | reference | note |
|---|---|---|---|
| m12 (client lock, `--exclusive`, default mods) | 32 / 0 | 32 / 0 (S) | |
| m26 (client lock) | 45 / 0 | 45 / 0 (S) | |
| m9 (client lock, APFS clone of client-merged-pack) | 163 / 0 | 163 / 0 (merger, wave 1) | the client log shows the tracker not attached; it carries no particle-type mod whose ids move |

Ports 46880–46899 were free at the end. The scratch clones were deleted; the extracted jars and sources under the
session scratchpad's `k2/` were not, until the review fixes (B-6).

## Sibling trackers (fabric-api 0.155.2, every caller of `impl/registry/sync/trackers/*`)

| tracker | attached by, to | verdict |
|---|---|---|
| `Int2ObjectMapTracker` | client `ParticleResourcesMixin`, to `ParticleResources.providers` | this defect; fixed |
| `IdMapperTracker` | nobody in 0.155.2 (block/item colour providers no longer use it) | nothing attached. A third-party caller passes a real `IdMapper` and gets native behaviour |
| `StateIdTracker` | `BootstrapMixin`, to `Block.BLOCK_STATE_REGISTRY` and `Fluid.FLUID_STATE_REGISTRY` | real storage keyed by raw ids (NeoForge's `ClearableObjectIntIdentityMap`), not a view, so not this class. Whether it and NeoForge's own sync leave the client's state ids equal to the server's is K1's |
| `BlockItemTracker`, `BlockInitTracker` | `BootstrapMixin` / `MainMixin` | entry-added / post-freeze, no int-keyed remap. N/A |
| `FabricEntityDataRegistryImpl` remap listener | its own handler registry | N/A |
| kernel views | `KernelKeyMappingMap` (name-keyed, no tracker); no other `Int2ObjectMap` view in `src/runtime` | N/A |

## For other lanes

- **UPG (gate-m58, D74).** The particle remap no longer blocks waystones (+ balm, shogi) or sophisticatedbackpacks
  (+ sophisticatedcore). To bring them back:
  - move `HELD_POPULATED` and the rest of `HELD_MODS` into `REAL_MODS`;
  - move `held.commands` / `held-player.commands` into `world.commands` / `player.commands`;
  - delete phase `held`, `held*.commands` and the "Measured out" comment.

  Do this only after K1 lands. With these mods phase main's socket joins still die on K1's chunk crash
  (`accept-after`, `No value with id 37849`).
- **K1.** After this fix, `ClientRegistrySyncHandler.apply` runs through every registry for particle-mod sets. Before,
  it stopped at the particle registry. Run K1's acceptance with this mod set too (`k2/repro.sh` with the four extra
  jars reproduces 37849).
- **Merger.** One hunk in `KernelBoot` and one in `KernelRuntimeClasses`. The rest is new files plus
  `KernelParticleProviders` (K2's).

## Open items

- K1: block-state id desync on a socket join with waystones/sophisticatedbackpacks (owner K1).
- UPG: return the held mods to phase main after K1 (owner UPG).

## K2 review fixes (2026-11-11)

Reviews `c5/review-k2-A.md` (adversarial) and `c5/review-k2-B.md` (completeness). Evidence: `c5/k2/review/`, plus the new
`c5/k2/{ws,sb}-after.*`. Each finding was checked again before acting on it.

| finding | verdict | commit / evidence |
|---|---|---|
| A-1 the premise test cannot fail against the game | confirmed, fixed | `1c0b1ede`: `theRealRemapRebuildsTheIdsTheViewReadsBeforeItFiresTheEvent` |
| A-2 no live run checked providers after ids moved | confirmed, fixed in the record | review A's `rv-fix.*` cited (below); the disconnect half is still unmeasured |
| B-1 no ci-unstaged rows for the new tests | confirmed, fixed | `36bcba32`: 7 rows, measured |
| B-2 no in-repo gate runs the fix; handoff incomplete | confirmed, fixed | `5ca22051`: gate-m12 carries a particle type; UPG handoff completed |
| B-3 "only after K1" for the waystones group not measured | confirmed, measured | `ws-after` 32/0, `sb-after` 32/0 |
| B-4 KernelBoot comment overclaims | confirmed, fixed | `1c0b1ede` |
| B-5 evidence path incomplete | confirmed, fixed | this record, Root cause |
| B-6 scratch files left behind | confirmed, fixed | the session scratchpad's `k2/` deleted |

**A-1.** The javap reading confirmed it (fabric-registry-sync 7.1.0 `MappedRegistryMixin.remap`: `byId.clear`/`toId.clear` at
799–811, then `byId.set`/`toId.put` in the loop up to 1041, then `fabric_getRemapEvent` at 1045 and `onRemap` at 1065). The
new test reads the real bytes. It reads `MappedRegistryMixin` from the pinned fabric-api's nested fabric-registry-sync, and
`MappedRegistry` from the genuine base. It asserts four things:
- every `getfield byId|toId` in `remap(Object2IntMap, RemapMode)` comes before the `fabric_getRemapEvent` call;
- there is a `set` on byId and a `put` on toId before that call;
- the base's `getId(T)` reads only `toId`, and `byId(int)` reads only `byId`;
- the mixin's `byId`/`toId` have the base's descriptors.

A copy of `remap` with the event call moved to the head is judged out of order. That is the test's own red control, inside
the test. The premise test's javadoc now says it holds on the test's model. 7/7 pass (`review/focused-1.log`).

**A-2.** Review A's `c5/review-k2-A/rv-fix.*` was run on this fix tree with fabric-api 0.155.2 and a scratch probe (`k2rv`).
The probe registers three particle types in reverse order on the client, so the server's sync moves two of them (client
125/126/127, server 127/126/125). Recounted here:
- m12 32 / 0;
- 0 `Registry remapping failed` / `UnsupportedOperationException`;
- `K2RV post … ok=3/3` after the ids moved: each provider under its type's new id, the same object in the view and in
  NeoForge's map.

Not measured: provider placement after the disconnect remaps back. That run's evidence is only that the error is absent and
the disconnect is clean.

**B-1.** Measured on a plain `git clone` of `1c0b1ede` in scratch, with no `.dev` and no `run/client-merged-pack`, so there
is no fabric-api anywhere, as on the CI runner. All of stagedRoot, mcLibraries, fabricApi and rebornEnergy pointed nowhere.
Result: 7 tests, 7 skipped without a message, the class's `@BeforeAll` abort (`review/unstaged-measure.log`, `unstaged-xml/`).
A first clone made with `cp` kept the merged pack's symlink, so fabric-api was found there; two tests ran and five skipped on
`[fixture:staged]`. That is the wrong profile, so it was discarded.

The whole ci-unstaged ratchet (`build -Pforbric.skipBaseline=ci-unstaged`) also ran on that clone, at `36bcba32`
(`review/unstaged-ratchet.log`):
- ParticleViewRemapStagedTest is in neither of its lists, so its seven rows match.
- The ratchet still fails on this machine: 12 new skips, all other lanes' tests from the base (MixinFluidReactionHeadTest ×5,
  KernelItemDamageTest ×2, AxeStripCallbacksInjectorTest, ForeignFluidTypeInjectorTest, GenuineNativeReferencesStagedTest,
  ItemUseOnInjectorTest, NativeCoremodParityTest).
- It also reports 296 rows that no longer skip, plus 78 failed tests. A local machine is not the CI runner, so none of these
  is a CI result.

No ci-prepared or ci-prepared-noforge row: that profile prepares the pinned fabric-api and the stage. With
`requireFixtures=all` on the genuine stage all seven run and pass. This is reasoned from the profile and was not run in it.

**B-2.**
- gate-m12's default set gains `sophisticatedcore-26.2-1.5.0.2337.jar`, from the merged pack that gate-m58 also reads.
- Two checks, asked of the default set only:
  - a mod registered a particle type (`[Forbric/Lifecycle] registered content: …particle_type=N`), which is the denominator
    for the existing `no registry mismatch` absence;
  - the kernel reported the tracker not attached.
- `M12_MODS` runs skip them, because a set chosen to split one ecosystem from the other need not carry a particle type.

| run | m12 |
|---|---|
| new default set | 34 / 0 (`review/m12-new-default.*`) |
| same, `-Dforbric.particleViewDetach=off` | 21 / 13: `Registry remapping failed` ×2, never configured, the not-attached check red (`review/m12-new-default-switchoff.*`) |

m12's reference count moves from 32 to 34.

**B-3.** One run each, on gate-m12's harness with fabric-api 0.155.2 + balm (repro.sh always carries balm):

| run | added mods | m12 | modded particle types | `No value with id` |
|---|---|---|---|---|
| `ws-after` | shogi, waystones | 32 / 0 | 0 | 0 |
| `sb-after` | sophisticatedcore, sophisticatedbackpacks | 32 / 0 | 1 (`jukebox_note`) | 0 |
| `accept-after` (earlier) | all four | 27 / 5 | 1 | K1's `37849` |

What this shows:
- The particle remap never blocked the waystones group: it has no particle type, and balm registers none. D74's premise
  for that group and gate-m58's "Measured out" sentence ("balm and sophisticatedcore register particle types") are wrong
  about balm.
- Each group joins a fresh world on its own. Only all six together died on K1's chunk crash, in one run.
- Whether either group joins phase main's populated world is not shown. That world also carries mcw-furniture's blocks, and
  K1's crash is in block-state ids, so the m12 result cannot be carried over.

**Suite and gates after the review fixes** (tip `5ca22051`, then the record):

| run | result |
|---|---|
| full suite (`jar cleanTest test --continue`, pinned props, `requireFixtures=all`) | 5796 tests / 0 skipped / 5 failed, the same 5 names as failures-wave1.txt, 0 new (`review/suite.log`, `suite-xml/`); +1 test is the new pin |
| ParticleViewRemapStagedTest | 7 / 0 / 0 |
| gate-m12, new default set | 34 / 0 |
| gate-m12, switch off | 21 / 13 (red control) |

The kernel's code changed only in a comment, so m26 and m9 (45/0, 163/0 on `8acc7f5f`) were not rerun. Ports 46880–46899
were free at the end. The scratch clones are deleted.
