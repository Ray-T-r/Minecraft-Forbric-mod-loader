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
  GameData.postRegisterEvents walked N registr(ies)…"). [Corrected under "K3 review fixes", B-3: that check can no longer fail.]
- K1/K2/K4: [corrected under "K3 review fixes", A-2/B-4: K1 shares KernelLifecycle.java, KernelLifecycleRegistrationWindowTest.java and
  ci-unstaged.tsv; K2/K4 share KernelRuntimeClasses.java and ci-unstaged.tsv; all merge cleanly.] Merge note: KernelRuntimeClasses' KernelNeoRegistries entry loses two Call rows.

## Open items

- (orchestrator / kernel) Native CommonModLoader loads COMMON/CLIENT configs after the registry phase; the kernel loads them
  before RegisterEvent and its comment says the opposite of the javap (census/CENSUS.md item 1).
- (orchestrator / kernel) NewRegistryEvent/ModifyRegistriesEvent are still posted by the kernel (no mod target in the corpora);
  differences from native in census/CENSUS.md item 2.
- (orchestrator / kernel) deliverModBusEvent walks a Map.copyOf, so setup-phase delivery is in hash order, not ModList order
  (census item 3).

## K3 review fixes (2026-11-11)

Reviews: `c5/review-k3-A.md` (A-1..A-4) and `c5/review-k3-B.md` (B-1..B-8). Evidence: `~/Forbric-backups/stage4-notes/c5/k3/review-fixes/`.
Commits on `s4-c5-k3` after 4d9f124a: 62b19d19, 99fac87b, 189134f3, 36d44aad (code and tests), 492af42f (a javadoc), then this record.

### Per finding

| finding | outcome | commit / evidence |
|---|---|---|
| A-1 no test follows the lambda that carries the server's Fabric mains | fixed | 62b19d19: `RegistrationWindowFabricStep` resolves the `LambdaMetafactory` handle of the argument built at the one `dispatchRegisterEvents` call in `registerNeoForgeContent` and returns what the lambda calls. FabricMainEntrypointWindowTest and FabricFreezePointWindowTest require exactly `runFabricMainsInTheWindow`. Red control M5 (`() -> {}`): 2 red (`mutations/M5-fabric-step-dropped.*`). m2/m2b/m4/m4-canary run (table below). |
| A-2 / B-4 "no K1/K2/K4 file is touched" is false | fixed (record) | Shared files, from the real merge bases: K1 (`s4-c5-k1`, base 7aaebbec) — `KernelLifecycle.java`, `KernelLifecycleRegistrationWindowTest.java`, `ci-unstaged.tsv`; K2 (e37b0cf0) and K4 (fca37afe), base 0daa5b0b — `KernelRuntimeClasses.java`, `ci-unstaged.tsv`. `git merge-tree` with each: 0 conflicts. K1's item for K3 ("keep clearBlockStateIds(cl) immediately before freeze(cl)") is kept: K3 does not touch `closeRegistrationWindow`, and in the K1+K3 merge the call is right before `freeze` (line 966/968). K1+K3 measured on a clone (K1 at 1c92c1c4): KernelLifecycleRegistrationWindowTest, FabricFreezePointWindowTest, FabricMainEntrypointWindowTest, BlockStateId*, RegisterEventsSeam*, KernelRegistrationIsolationTest, EveryCatchIsVisibleTest, NeoPostRegisterTailCensusTest: 44/44 green (`k1k3-targeted.*`, `k1k3-TREE.txt`). The full suite on K1+K3 is the merger's. |
| A-3 the tail's `ModLoader.postEvent(BlockEntityTypeAddBlocksEvent)` in hash order | fixed | 99fac87b: `onRegisterEventsModBusEvent` → `deliverInModOrder(cl, event, false, …)` (ModList order, phase by phase, no active container: javap of .93 `ModLoader.postEvent` → `ModList.forEachModInOrder`). The weave test pins the order neoforge, first, thrower, last. Red control MA3 (back to `postModBusEvent`): red, order came out neoforge, last, thrower, first (`mutations/MA3-*`). A hash order can match by chance (1 in 6 for three mods); it did not here. |
| A-4 NeoForge's roll-back reported as "could not fire RegisterEvent" | fixed (message); fatal-or-not left to the orchestrator | 99fac87b + 36d44aad: `registerEventFailure(t)` recognises NeoForge's aggregate (a `RuntimeException` built in `postRegisterEvents`, carrying suppressed failures) and the ERROR says every registry was rolled back to vanilla (`RegistryManager.revertToVanilla`), the tail did not run, native NeoForge stops at its loading-error screen here, and lists each cause. One top-level `Caught.error` (EveryCatchIsVisibleTest went red on the first form, with two `Caught` calls under an `if`; 36d44aad). The old message is now ERROR as well. New weave case `aGuestThrowInsideNeoForgesTryIsNeoForgesRollBackAndIsNamedSo`: corelib's shape throwing on registry b → all three registries dispatched, one `revertToVanilla`, the Fabric step once, no tail step, the new message with its cause. Red control MA4: red. |
| B-1 acceptance and red controls ran on an uncommitted draft | fixed (re-run on committed trees) | Every run below names its tree (`tree.txt` / `TREE.txt` next to it). Server and client with packedup + corelib + configlib on 36d44aad, clean: server Done, `PackedUp.container` = BaseContainerType, `basicbackpack` set, `GameData.registerEvent` set, seam line "6 tail step(s)", "x106 on 4 bus(es)" (`repro/after-server`); client: menu type set, "joined world via quick-play: K3", "clean disconnect observed", "Stopping!" (`repro/after-client`). Unfixed client on a clone whose `forbric-kernel/src` is byte-equal to 7aaebbec (`repro/PRE-TREE.txt`): `PackedUp.container = null`, "Container screen registered with null menu type!", crash report (`repro/before-client`). Red controls re-run on an APFS clone of 36d44aad (diffs saved, `mutations/*.diff`, runner `mutations/run-unit.sh`): no-call → 4 red (all weave cases; `tail` empty); no-seam → 4 red. A first round on 189134f3 is discarded (`mutations/round1-189134f3-discarded/WHY.txt`: the mutation script was run once by hand on that clone while it ran). |
| B-2 gates the change reaches were not run or not listed | fixed | Run on the tip (table below): m1, m2, m2b, m3, m4, m4-canary, m7-neo, m14, m12, m13, m55, m9, m58. Not run here, left to the merger's gates-all: m10, m21, m25, m26, m51 (run at d375ad0f, each equal to gates-all) and every other gate. |
| B-3 m9's "NeoForge registration order" can no longer fail; no gate checks the seam | fixed in m1; m9 left to G-C | 189134f3: gate-m1 adds `check` on the seam's applied line ("GameData.postRegisterEvents posts RegisterEvent itself, every mixin on it applied: … N tail step") and `check_absent` on "is not the shape the kernel knows". Red control: m1 on a clone with the seam never applied (`mutations/gate-m1-no-seam.diff`) → 17 PASS / 2 FAIL, exactly the two new checks (`gates/mut-no-seam/m1.out`); the other 17 pass without the seam, which is the reviewer's point. m9's check is now a presence check of the genuine method's line, not "No check changed" as the record said — G-C to decide whether to keep it. |
| B-5 SWAP hand-off incomplete, UPG/S not addressed | fixed (record) | See "For other lanes" below. |
| B-6 test-count wording, TSV measurement | fixed (record) | d375ad0f: 7 new test methods and 1 renamed (`KernelRegistrationIsolationTest.fireRegisterEventsCannotTakeTheRestOfTheWindowWithIt` → `theRegisterEventDispatchCannotTakeTheRestOfTheWindowWithIt`); net +6. This round: +1 (the guest-throw weave case), suite 5821 → 5822. The 3 ci-unstaged rows were measured with a `--tests` filter and `-Pforbric.stagedRoot=<absent>` in a clone, not through `-Pforbric.skipBaseline=ci-unstaged`; the new weave case needs no row (it does not read the staged root, like the three weave cases before it). |
| B-7 the kernel's creative-tab re-sort after K3 | measured; javadoc corrected (492af42f); removal left open | K3 probe on a dedicated server, packedup + corelib + configlib, at Done: tip `DEFAULT_TABS` 4 / `SORTED_TABS` 11; tip with `sortNeoCreativeTabs` a no-op (`mutations/resort-off.diff`) 4 / 11; native NeoForge 26.2.0.93 4 / 11 (`repro/after-server`, `repro/resort-off-server`, `repro/native-server`). So on a server NeoForge's own tail sort is enough here. Not measured: a client, where Fabric mains run in `Minecraft.<init>`, after the window. Not deleted: open item. |
| B-8 a throwing RegisterEvent listener costs only that registry; native rolls back and stops | not changed; raised | Pre-existing (the kernel contained it the same way before K3). The guiding rule keeps a leniency only where the mod's own ecosystem tolerates the case, and NeoForge does not. Native behaviour means roll-back plus stop, and the kernel has no loading-error screen, so this needs an orchestrator ruling (open item). |

### Results on the tip

- Suite at 36d44aad (pinned, plain `--offline`, `jar cleanTest test --continue`, `-Pforbric.requireFixtures=all`): **5822 tests / 0 skipped / 5 failed**, exactly the five names of failures-wave2.txt (`review-fixes/suite/`). The first suite of this round (189134f3) had a sixth, EveryCatchIsVisibleTest, fixed by 36d44aad (`review-fixes/suite-189134f3/`). 492af42f is a javadoc change after the suite.
- Gates, one at a time in the K3 worktree (ports 46900-46907), `^[kernel] PASS/FAIL`; m1 and m2 ran on 36d44aad, m2b's jar build was before 492af42f, every later gate on 492af42f (javadoc only). m9 on an APFS clone of client-merged-pack by real path.

| gate | PASS / FAIL | 7aaebbec gates-all | note |
|---|---|---|---|
| m1 | 19 / 0 | 17/0 | +2 seam checks (189134f3) |
| m2 | 29 / 0 | 29/0 | server Fabric mains (A-1) |
| m2b | 38 / 0 | 38/0 | A-1 |
| m3 | 13 / 0 | 13/0 |  |
| m4 | 33 / 0 | 33/0 | per-mod RegisterEvent content, A-1 |
| m4-canary | 22 / 0 | 22/0 | A-1 |
| m7-neo | 28 / 0 | 28/0 |  |
| m14-fabric-server | 33 / 0 | 33/0 | registry ids across a remap |
| m12-multiplayer | 32 / 0 | 32/0 | --exclusive |
| m13-anticheat | 30 / 0 | 30/0 | --exclusive |
| m55-creative-search | 23 / 0 | 23/0 | creative tabs and search |
| m9-client | 163 / 0 | 163/0 | APFS clone of client-merged-pack |
| m58-world-upgrade | 211 / 5 | 211/5 | --exclusive; the 5 FAILs are the five new-client joins, each log 'No value with id 43766' = K1 (D74/D75), unchanged |

Every count equals gates-all except m1 (+2, the new checks). Ports 46900-46919 free at the end; scratch clones deleted.

### For other lanes

- **SWAP (W8, D77):** what K3 provides is the NeoForge/Forbric side only: with packedup + corelib + configlib in `mods/`, packedup's menu type and items are set on a Forbric server and client (measured above). The Forge side is unchanged and still SWAP's: 9488359f records that `forge-swap-make-old.sh` has never written a filled packedup bag (three attempts; the frozen fixture says `packedup-bag none`). So: (1) get a bag inventory into the old Forge world (open, SWAP); (2) run the new client pass with the three jars in `mods/` (newclient3 left them out); (3) re-derive classify's candidates; (4) then pin gate-m59 and `expected-losses.tsv`.
- **G-C (m9):** m9's "NeoForge registration order" check matches a line printed whenever the genuine method returns, seam or not; it lost its red control (before K3, `-Dforbric.neoRegistrationOrder=off` turned it red). The seam itself is now checked in m1 (red-controlled). Keep the m9 check as a presence check, or drop it.
- **UPG (m58):** the registration path changed under m58; m58 was re-run here (table). Nothing to change unless the merger's K1+K3 m58 differs.
- **S:** nothing.
- **Merger:** shared files as in A-2/B-4. New in this round: `RegistrationWindowFabricStep` (test), two m1 checks (m1 17 → 19 PASS), one weave case. After merging K1 and K3, re-run KernelLifecycleRegistrationWindowTest and FabricFreezePointWindowTest with the full suite.

### Open items (owner: orchestrator / kernel; not changed here)

1. (B-8, A-4) A throwing RegisterEvent listener (contained per mod, per registry) and a throw inside NeoForge's per-registry try (roll-back to vanilla, then the boot continues): native NeoForge rolls back and stops at its loading-error screen in both. Rule needed: keep containing, or stop as native does.
2. (B-7) The kernel's creative-tab re-sort in `closeRegistrationWindow`: redundant on a dedicated server (measured above); a client with Fabric tabs is not measured. Candidate for the C3 three checks.
3. Unchanged from the first record: COMMON/CLIENT configs load before the registry phase (native: after); NewRegistryEvent/ModifyRegistriesEvent are still posted by the kernel; `deliverModBusEvent` (setup-phase events) still walks a `Map.copyOf` in hash order — the tail event no longer does (A-3).
