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
(merged base, kernel 187143a1) joined with these mods with no remap error (`m58-main-2.evidence/logs/s2-server.client.game.log`:
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
right and the view would need a real `clear()`. The staged test checks the opposite directly. With the ids
reassigned and no event fired, the view already answers the new ids, and the old id of `balm:a` answers another
type's provider.

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
crash). In these live runs the server and the client gave the type the same id. Ids that actually move are covered by
the staged test.

Suite (`jar cleanTest test --continue`, pinned `--offline`): baseline at 0daa5b0b was 5789 / 0 skipped / 5 failed.
The fix tree is 5795 / 0 / 5, with the same 5 names as `failures-wave1.txt` and 0 new.

Gates (fix tree; counts are `^[kernel] PASS` / `^[kernel] FAIL` lines; references from S's and the merger's records):

| gate | fix tree | reference | note |
|---|---|---|---|
| m12 (client lock, `--exclusive`, default mods) | 32 / 0 | 32 / 0 (S) | |
| m26 (client lock) | 45 / 0 | 45 / 0 (S) | |
| m9 (client lock, APFS clone of client-merged-pack) | 163 / 0 | 163 / 0 (merger, wave 1) | the client log shows the tracker not attached; it carries no particle-type mod whose ids move |

Ports 46880–46899 were free at the end. The scratch clones were deleted.

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
