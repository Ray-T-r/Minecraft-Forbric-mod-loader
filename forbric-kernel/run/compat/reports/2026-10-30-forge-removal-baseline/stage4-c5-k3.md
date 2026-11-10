# Stage 4 C5 — lane K3: mods' mixins on NeoForge's GameData.postRegisterEvents never ran

Lane K3 (D75), branch `s4-c5-k3`, cut at the wave-2 stage tip 7aaebbec. Evidence: `~/Forbric-backups/stage4-notes/c5/k3/`
(repro/, javap/, census/, gates/, baseline/ and after/ suites, mutation-*.log/xml).

## Symptom

packedup 1.1.1 + supermartijn642corelib 1.1.24a + supermartijn642configlib 1.1.8 (NeoForge builds, the SWAP fixture's
`mods-neo`, sha1 in `repro/*/mods.sha1`) on Forbric at 7aaebbec:

- client: dies at startup, `java.lang.RuntimeException: Container screen registered with null menu type!`
  (`repro/before-client/`, crash report, unfixed tree on an APFS clone);
- dedicated server: boots, but every corelib `@RegistryEntryAcceptor` field stays null — `PackedUp.container = null`,
  `PackedUp.basicbackpack = null` (`repro/before-server/`).

Reproduction scripts: `repro/server-corelib.sh` (server, probe agent), `repro/client-corelib.sh` (client quick-played into a
world the server script keeps), smallest set = those three jars.

## Root cause

corelib's `GameDataMixin` (`@ModifyVariable(at = STORE)` on the `RegisterEvent` local and `@Inject(at = INVOKE
ModLoader.postEventWrapContainerInModOrder, shift = AFTER)` calling `RegistryEntryAcceptor$Handler.onRegisterEvent`) is woven
into `net.neoforged.neoforge.registries.GameData.postRegisterEvents` — and the kernel never called that method. It posted
RegisterEvent itself (`KernelNeoRegistries.fireRegisterEvents`, per bus, in an order it sorted from
`GameData.getRegistrationOrder()`) and copied the method's tail into `KernelLifecycle.registerNeoForgeContent`.

Differential (probe agent `probe-agent/k3probe.jar`, same three jars, reads static fields without initialising anything):

| arm | GameData.registerEvent (mixin field) | Handler.FIELDS | PackedUp.container |
|---|---|---|---|
| Forbric 7aaebbec (`repro/before-server`) | null (woven method never ran; export in `before-server/mixin.out` shows the handlers woven) | 2 registries collected | **null** |
| native NeoForge 26.2.0.93 (`repro/native-server`, the official installer's server image) | set | 2 registries collected | BaseContainerType |
| pre-Stage-4 merged base, cf0498eb (`repro/pre-stage4-server`, APFS clone, `.stage`) | null | 2 registries collected | **null** — not a Stage-4 regression |
| Forbric with the fix (`repro/after-server`) | set | 2 registries collected | BaseContainerType |

Falsification check: if corelib's scan had found nothing (an empty `ModList.getAllScanData()`), the fields would stay null
with the mixin running. `Handler.FIELDS` holds both registries' entries on every arm (gathered from `FMLConstructModEvent`,
which the kernel posts), so the only missing step is the woven method running. The flip: the fix (calling the genuine
method) turns null into the menu type on the server and the crash into a clean client run; the mutation below turns it back.

Native shape (javap of .93, `javap/`): `CommonModLoader.begin` → "Registry initialization" =
`RegistryManager.postNewRegistryEvent; GameData.unfreezeData; GameData.postRegisterEvents; GameData.freezeData;
registriesLoaded = true`. `postRegisterEvents`: for each id of `getRegistrationOrder()` (attribute, data_component_type,
particle_type, vanilla's order, then every other root key sorted) build `RegisterEvent(key, registry)`,
`StartupNotificationManager.modLoaderMessage`, `ModLoader.postEventWrapContainerInModOrder(event)` (for each
`EventPriority`, each `ModList` container in order, that container active) inside a per-registry `catch (Throwable)` that
collects suppressed failures; if any: log, `RegistryManager.revertToVanilla()`, throw. Then the tail:
`CommonHooks.modifyAttributes`, `SpawnPlacements.fireSpawnPlacementEvent`, `ModLoader.postEvent(new
BlockEntityTypeAddBlocksEvent())`, `CreativeModeTabRegistry.sortTabs`, `GameRuleCategory.registerModdedCategories`,
`ItemTooltipHandler.init`.

Why it cannot be dropped in unchanged: (1) the kernel's mod set is not FML's — NeoForge's baseline container joins ModList
only after the window (`publishModBusDelivery`), and its own fan-out rethrows a listener's failure as a
`ModLoadingException`, which ends that registry for every later mod and then rolls every registry back to vanilla (the kernel
has no loading-error screen to stop at); (2) on a dedicated server the Fabric main entrypoints run inside the window, after the
dispatch and before the tail (the attribute events must see Fabric's entities). Neither needs the method re-implemented.

## Fix (d375ad0f)

- `KernelLifecycle.dispatchRegisterEvents` calls `GameData.postRegisterEvents()` (the class defined first, so Mixin and the
  seam have run). Never throws; the Fabric step runs once whatever happens.
- `RegisterEventsSeam` (post-Mixin step in `KernelMixinBootstrap`, after `PostMixinFixups`): on the woven method, after every
  mixin bound to its call sites —
  - the one `ModLoader.postEventWrapContainerInModOrder` → `KernelLifecycle.onRegisterEvent`: same delivery (priority phase by
    phase, NeoForge's baseline then every NeoForge mod in ModList order, receiving container active), a throwing mod reported
    with the old wording ("a NeoForge mod's RegisterEvent listener failed on …") and skipped for that registry only;
  - at the tail's start (the `IFLE` target of the `getSuppressed().length` test) → `onRegisterEventsDispatched`: the window's
    Fabric step (`runFabricMainsInTheWindow`: root open, server mains, Fabric's HEAD freeze point, root closed);
  - each no-argument static tail step → `onRegisterEventsTailStep(Class, String)` (modifyAttributes through
    `postAttributeEvents`, its own catch site and the DefaultAttributes initialisation; `ItemTooltipHandler.init` through
    `KernelNeoTooltips.init` behind `-Dforbric.neoTooltipAppenders`; the rest through `invokeStaticOn`), `ModLoader.postEvent`
    → `onRegisterEventsModBusEvent` (per-container, as before);
  - calls a mixin already redirected, and the class's own methods, are left alone; another method shape is left as NeoForge
    ships it (WARN), and the kernel then publishes the baseline into ModList before calling it.
- Deleted: `KernelNeoRegistries.collect/collectHolder/collectRoot/inRegistrationOrder/fireRegisterEvents`,
  `KernelLifecycle.inNeoForgeRegistrationOrder/fireRegisterEvents`, the copied tail. `-Dforbric.neoRegistrationOrder` goes to
  `ForbricSwitches.RETIRED` (no kernel order left). No new switch (the seam has no off position that is not "NeoForge as
  shipped"; WeaveCoverageCensus row `RegisterEventsSeam` says so).
- C3 three checks for the deleted sort/dispatch: symptom — m9 "no unbound data component" and "no RegisterEvent listener
  failed" (see gates) plus the registry dump below; whole-jar search — `census/` (only postRegisterEvents has a mod target among
  the lifecycle methods the kernel skips or re-implements); caller check — m9 (client-merged-pack, waystones' items) and the
  client-neo-pack dump.

## Tests and red controls

- `RegisterEventsSeamWeaveTest` (real Mixin through WeaveHarness; a stand-in GameData that is .93's method statement for
  statement): a HEAD injector runs exactly once per boot, corelib's shape once per registry right after each registry's
  dispatch, a throwing mod costs only its registry, the Fabric step once at the loop's end, the tail once in NeoForge's order,
  a throwing tail step costs only itself. Red controls on an APFS clone (`mutation-no-call.*`): the kernel not calling the
  method → 3/3 red, trace `posted=0 kernel:fabric-mains` (no mixin line at all = the defect). Seam not applied
  (`mutation-no-seam.*`) → 3/3 red, trace shows NeoForge's own fan-out: `thrower`'s failure on b skips `last:b` and corelib's
  after-handler for b, then `revertToVanilla`.
- `RegisterEventsSeamStagedTest` (genuine .93 GameData from the staged carrier): routed calls, Fabric step position, roll-back
  untouched, ASM verifier on the edited method, a woven handler stays right after the dispatch, an unknown shape is left alone.
- Updated to the new structure: FabricFreezePointWindowTest, FabricMainEntrypointWindowTest, KernelLifecycleRegistrationWindowTest,
  KernelRegistrationIsolationTest, NeoPostRegisterTailCensusTest (now: every tail step is routed by the seam),
  WeaveCoverageCensusTest. ci-unstaged.tsv: +3 rows for RegisterEventsSeamStagedTest (measured with an absent staged root).

## Acceptance

- packedup + corelib + configlib: dedicated server Done, `PackedUp.container`/`basicbackpack` non-null (`repro/after-server`);
  client to title and into a world, `PackedUp.container` non-null, clean disconnect and stop (`repro/after-client`, smoke log:
  "joined world via quick-play", "clean disconnect observed").
- Registration order, client-neo-pack (62 jars, same sha1s) on a dedicated server, dumped at Done by the probe agent
  (`repro/dump-{before,before2,after,after2}/registries.tsv`, 12423 entries, 114 registries each): the (registry, id) sets
  are identical in all four runs, and so is every registry's namespace order (`repro/ns-order.py`: none differs). Numeric ids
  inside a mod differ — also between the two runs of the UNCHANGED tree (before vs before2: 100 lines, 17 lithostitched/worldgen
  registries), because those mods register from identity-hashed collections (cookingforblockheads' `supportedColors` is a
  `HashSet<DyeColor>`, javap). So "identical" holds for everything the dispatch decides; per-mod order is the mods' own
  run-to-run order. RegisterEvent now goes to 72 containers (was 62 buses): the 10 extra are mods whose only `@Mod` is
  client-only (shouldersurfing, sodium, iris, …), which native NeoForge also keeps in ModList and posts to. 114 registries
  posted (was 115): the root holds 114 registries (the dump), and the kernel's holder sweep also posted for the one
  `Registry` field that is not in the root — by elimination the root registry itself (`BuiltInRegistries.REGISTRY`); NeoForge's
  order does not list it.
- Suite at d375ad0f (pinned, plain --offline, `jar cleanTest test --continue`): 5821 tests / 0 skipped / 5 failed — exactly
  the five names of failures-wave2.txt (+6 tests: 3 weave, 3 staged). Baseline at 7aaebbec: 5815/0/5, same names.
- Gates at d375ad0f, one at a time in the K3 worktree (port block 46900-46919; m9 on an APFS clone of client-merged-pack by
  real path), `^[kernel] PASS/FAIL` counts — every one equals the 7aaebbec gates-all count:

  | gate | PASS | FAIL | 7aaebbec |
  |---|---|---|---|
  | m7-neo | 28 | 0 | 28/0 |
  | m1 | 17 | 0 | 17/0 |
  | m3 | 13 | 0 | 13/0 |
  | m10-overlay | 24 | 0 | 24/0 |
  | m21-forgesetup | 54 | 0 | 54/0 |
  | m25-worldgen | 16 | 0 | 16/0 |
  | m51-tooltips | 16 | 0 | 16/0 |
  | m26-forgeclient | 45 | 0 | 45/0 |
  | m9-client | 163 | 0 | 163/0 |

  m1/m3 pin "fired RegisterEvent xN on M bus(es)", m9 "NeoForge registration order", "no unbound data component", "no
  RegisterEvent listener failed", "no entity missing attributes"; m51's appenders-off control still reaches the switch.

## For other lanes

- SWAP (W8, D77): the corelib precondition is met by this branch. Re-make the fixture with a filled packedup bag
  (`forge-swap-make-old.sh`, MANIFEST `packedup-bag`), run the NEW client pass with packedup/corelib/configlib in mods/ (they
  were left out of newclient3), and re-derive classify's candidates before pinning gate-m59 + expected-losses.tsv.
- G-C (m9 owner): this branch edits m9's header only (the `-Dforbric.neoRegistrationOrder` recipe line is retired, the check
  "NeoForge registration order" now matches the line "fired RegisterEvent in NeoForge's registration order — NeoForge's own
  GameData.postRegisterEvents walked N registr(ies)…"). No check changed.
- K1/K2/K4: no shared file. KernelLifecycle/KernelNeoRegistries are K3's; K4's ForbricMergedBaseCompatTransformer and K2's
  KernelParticleProviders are untouched. Merge note: KernelRuntimeClasses' KernelNeoRegistries entry loses two Call rows.

## Open items

- (orchestrator / kernel) Native CommonModLoader loads COMMON/CLIENT configs after the registry phase; the kernel loads them
  before RegisterEvent and its comment says the opposite of the javap (census/CENSUS.md item 1).
- (orchestrator / kernel) NewRegistryEvent/ModifyRegistriesEvent are still posted by the kernel (no mod target in the corpora);
  differences from native in census/CENSUS.md item 2.
- (orchestrator / kernel) deliverModBusEvent walks a Map.copyOf, so setup-phase delivery is in hash order, not ModList order
  (census item 3).
