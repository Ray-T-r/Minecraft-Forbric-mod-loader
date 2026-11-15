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

## K3b (wave 2b, 2026-11-11)

Lane K3b, branch `s4-c5-k3` reset to the stage tip b9154084. Evidence: `~/Forbric-backups/stage4-notes/c5/k3b/w2b/`
(tabs/, census/, gates/, suite-baseline/, suite-tip/, mut-*.diff/log/xml, probe/). Every Forbric arm below ran from an APFS
clone whose tree is named next to it: `tip-wt` = b9154084 byte-equal (src diffed), `resort-wt` = b9154084 + the re-sort
mutation (`resort-off.diff`, `resort-wt-vs-tip.txt`), `fix-wt` = e785444a.

### 1. B-7: the creative-tab re-sort in closeRegistrationWindow — redundant there, the defect moved; re-anchored (e785444a)

Fixture: fabric-api 0.155.2 + mcw-stairs 1.0.2 **Fabric** (its `main` registers `mcwstairs:mcwstairs`) + mcw-bridges 3.1.2
**NeoForge** (`mcwbridges:mcwbridges`), jars already on disk (sha1 in each arm's `mods.sha1`). Probe agent
`probe/k3btabs.jar` (source beside it) reads, every 2 s, without loading or initialising anything: NeoForge's
`CreativeModeTabRegistry.SORTED_TABS` (the only list the creative screen's strip reads) as keys, `DEFAULT_TABS` size, and
the `CREATIVE_MODE_TAB` registry. `tabs-run.sh sp|mp`: a client quick-played into a copy of client-merged-pack's ForbricTest,
or a dedicated Forbric server with the same jars plus a client joining it over a socket.

| arm | dedicated server SORTED / DEFAULT | client after its Fabric mains | client in the world |
|---|---|---|---|
| native NeoForge 26.2.0.93 server, mcw-bridges only (`tabs/native-neo-server`) | 11 (vanilla 10 + mcwbridges) / 4 | — | — |
| tip, SP (`tabs/sp-on`) | — | 11, no mcwstairs | 12 (+mcwstairs) only once the integrated server's reload ran (07:39:07, the second it started) |
| re-sort off, SP (`tabs/sp-off`) | — | 11 | 12, same moment |
| tip, MP (`tabs/mp-on`) | 12 (mcwbridges, mcwstairs) / 4 | 11 | **11 — mcwstairs never in the strip** |
| re-sort off, MP (`tabs/mp-off`) | 12 / 4 | 11 | **11** |
| fix e785444a, MP (`tabs/mp-fix`) | 12 / 4 | **12** (sorted at the window's close, "11 -> 12") | 12 |
| fix e785444a, SP (`tabs/sp-fix`) | — | **12** | 12 (the reload gives the same list) |

The plan's C3 three checks, for the sort in `closeRegistrationWindow`:

1. **Symptom check** (its symptom: a window-registered tab searchable but out of `SORTED_TABS`): with it removed, the dedicated
   server ends with the same 12/4 and the client with the same 11/4 then 12/4 as with it, SP and MP; every tip arm's own log
   says `re-sorted NeoForge creative tabs N -> N` with the same list (server 12 -> 12, client 11 -> 11). The native NeoForge
   server has the NeoForge tab in its tail sort (11/4). So at that point it is redundant: NeoForge's own
   `GameData.postRegisterEvents` tail (K3) sorts after everything the window registers, the server's Fabric mains included.
2. **Whole-jar search** for the same pattern, a tab registered after NeoForge's last `sortTabs`: in the genuine base + carrier
   `CreativeModeTabRegistry.sortTabs` is called from `GameData.postRegisterEvents` only, and the recalculation from
   `NeoForgeEventHandler.onResourceReload` (`AddServerReloadListenersEvent`: a server's datapack reload, so an integrated
   server too, never a client on a remote server) (javap). The kernel has one more registration point after the tail: a
   client's Fabric mains and client entrypoints, in `Minecraft.<init>` (`onClientEntrypoints`). Hit: the mcwstairs rows above
   — searchable but not in the strip until an integrated server's reload, and for good on a multiplayer join, on the tip and
   with the re-sort off alike. Native Fabric shows the tab from the start (fabric-creative-tab-api's own pager lays out every
   registered tab; on Forbric that pager is pinned off and NeoForge's is the only one). The defect moved, so per the plan the
   repair is re-anchored, not deleted: `closeClientEntrypointWindow` sorts after its freeze; `closeRegistrationWindow` no
   longer sorts. On a dedicated server nothing reads `SORTED_TABS` but the reload's recalculation, and the tail sort already
   holds every tab there.
3. **Caller check**: the measured arms are the callers (a real Fabric mod's tab, a real NeoForge mod's tab); after the fix the
   client has the list an integrated server's reload gives (12, same order) before any world, on MP as on SP, `DEFAULT_TABS`
   4. m55 (creative screen, search) and m9 / m26 (clients) re-run below.

Switch: none existed for the re-sort, so nothing goes to `ForbricSwitches.RETIRED`. `-Dforbric.tabProbe` keeps its place.
Check: `KernelLifecycleRegistrationWindowTest.theCreativeTabsAreSortedWhereTheClientsLastRegistrationWindowCloses` — the
client window's close sorts, after its freeze, and it is the only place the kernel sorts. Red control: the sort taken out of
`closeClientEntrypointWindow` (`mut-no-client-sort.diff`) → that test red (`mut-no-client-sort.log/.xml`, 1 of 8). Runtime red
control: `tabs/mp-on` and `tabs/mp-off` (no sort at that close) leave mcwstairs out of the strip on a join; `tabs/mp-fix` has it.
No gate carries a Fabric mod with a creative tab over a socket (m12's set has none, m14's canary registers none); adding one
to a shared fixture is left to the owners of those gates (open item 3).

### 2. Setup-phase mod-bus delivery in ModList order (25fbc88f)

What walked hash order: `KernelModLoader.publishedNeoMods()` was a `Map.copyOf` (iteration order salted per JVM), and every
setup phase walks it — `KernelNeoSetup.firePhase` for FMLConstructModEvent, common setup, the sided setup, both IMC phases
and load complete — and so does `deliverModBusEvent` (its one live caller, `RegisterTooltipAppendersEvent` from
`KernelNeoTooltips`). (K3's census item 3 named `deliverModBusEvent` for the setup phases; the setup phases go through
`firePhase`, over the same map.) Native (javap of .93): `CommonModLoader.load` → `ModLoader.dispatchParallelEvent` →
`dispatchParallelTask` over `ModList.getSortedMods()`, each container's task waiting for its dependencies' futures, then
`acceptEvent` with that container active; `ModLoader.postEvent` → each `EventPriority`, `ModList` order. The kernel publishes
ModList from a `LinkedHashMap` in dependency order (`orderByDependency`), so the map is now that same map, read-only
(`KernelModLoader.published`): walking it is walking `ModList.getSortedMods()` minus the presence aliases, one container at
a time — an order native NeoForge can produce, and one that keeps its guarantee that a dependency finishes first. K3's
`deliverInModOrder` reads ModList directly; it already fitted the RegisterEvent and BlockEntityTypeAddBlocksEvent paths and
is unchanged. `deliverModBusEvent` now walks the same order through the map (baseline first, then ModList order, phase by
phase); it was left in place rather than folded into `deliverInModOrder`, to keep the shape tests that pin it.

Test: `KernelModLoaderPublishedOrderTest` (2): the map keeps the order it is given (unit), and on the staged carrier after
`constructMods` of an eight-mod jar `publishedNeoMods()` walks `ModList.getSortedMods()` (staged). Red control: `published`
back to `Map.copyOf` (`mut-hash-order.diff`) → 2/2 red (`mut-hash-order.log/.xml`: ModList a..h, the map f, e, d, c, b, a, h, …).
TSV (cfb39a49): measured with the staged root absent and no requireFixtures (`ci-measure.log`, the two TEST-*.xml beside it),
the staged case skips with "[fixture:staged] staged runtime classes/carrier absent: …/neoforge-runtime/neoforge-runtime.jar"
and the unit case and the new KernelLifecycleRegistrationWindowTest case run; one ci-unstaged row, with the profile's
default root as `KernelModLoaderClasslessTest`'s rows have it.

### 3. The two unchanged K3 items, with a census

- **COMMON/CLIENT configs "load before the registry phase"**: not so at this tip. `loadEarlyConfigs` is step 2c, after
  `registerNeoForgeContent` returns; the logs show "loaded NeoForge configs" after "fired RegisterEvent" and the tail's
  creative-tab sort, on a server (`c5/k3/repro/after-server/server.log` lines 183→193) and a client
  (`c5/k3/repro/after-client/client.log` 194→205), as native `CommonModLoader.begin` does ("Registry initialization", then
  "Config loading"). What said otherwise were two stale comments inside `registerNeoForgeContent` (they described a config load
  in the window that `git log -S` finds in no commit since the baseline; the call has been at step 2c since 44e8eeea at
  least) — corrected in 66acdc78, no code change. One remaining difference, not fixed: the kernel loads COMMON then CLIENT,
  native CLIENT then COMMON (`lambda$begin$1`). Census: 20 NeoForge jars in the corpora register both types
  (`census/registry-events.tsv`, kind `client_and_common`); a mod would see the order only if one type's
  `ModConfigEvent.Loading` listener read the other type's values — not shown for any of them (not analysed per mod).
- **NewRegistryEvent / ModifyRegistriesEvent kernel-posted**: census `census/registry_events_scan.py` over run/client-*,
  run/neoforge-builds, build/compat-inputs, build/sweep80/90/100-mac and forge-swap/mods-neo (nested jars included,
  sha1-deduplicated): 1747 jars, 397 NeoForge. Listeners of NewRegistryEvent in 12 distinct mods (PuzzlesLib, TerraBlender,
  architectury ×2, bagus_lib ×2, balm ×2, framework, fzzy_config, nuit, tofucraft, tombstone, whitenoise); of
  ModifyRegistriesEvent, PuzzlesLib only (its generic event-invoker table). The two kernel differences K3 recorded are (a)
  NewRegistryEvent posted without an active container, (b) ModifyRegistriesEvent not posted to NeoForge's own container (the
  kernel calls `NeoForgeRegistriesSetup.modifyRegistries` itself). (a) is observable only to a listener that asks
  `ModLoadingContext` for the active container: no listener class does, and in those jars only PuzzlesLib calls
  `getActiveContainer` at all (`NeoForgeModContainerHelper.getOptionalModEventBus`, reached from its constructor, not from its
  NewRegistryEvent lambda, which only calls `NewRegistryEvent.register`; javap, `census/listener-jars-ctx.txt`). (b) touches no
  mod. So no mod in the corpora observes either; not changed.

### Results

- Suite, baseline at b9154084 (`suite-baseline/`, clone `tip-wt`): 5858 tests / 0 skipped / 5 failed. Tip e785444a (`suite-tip/`, clone `suite-wt`):
  **5861 / 0 / 5**, the same five names as failures-k.txt (+3: KernelModLoaderPublishedOrderTest 2, KernelLifecycleRegistrationWindowTest 1); 0 new.
- Gates on the lane tip e785444a, in the K3 worktree (ports 46900-46919), one at a time, `^[kernel] PASS/FAIL` counts
  (`gates/tip-server/counts.txt`, `gates/tip-client/counts.txt`); m9 on an APFS clone of client-merged-pack by real path:

  | gate | PASS / FAIL | b9154084 gates-all |
  |---|---|---|
  | m1 | 19 / 0 | 19/0 |
  | m2 | 29 / 0 | 29/0 |
  | m2b | 38 / 0 | 38/0 |
  | m4 | 33 / 0 | 33/0 |
  | m4-canary | 22 / 0 | 22/0 |
  | m7-neo | 28 / 0 | 28/0 |
  | m10-overlay | 24 / 0 | 24/0 |
  | m21-forgesetup | 54 / 0 | 54/0 |
  | m25-worldgen | 16 / 0 | 16/0 |
  | m26-forgeclient | 45 / 0 | 45/0 |
  | m55-creative-search | 23 / 0 | 23/0 (each phase's client now logs "re-sorted NeoForge creative tabs 10 -> 10" at the window's close) |
  | m9-client | 163 / 0 | 163/0 |

  The gates ran on e785444a's code (cfb39a49 adds a TSV row only). m2, m2b, m4, m4-canary, m7-neo, m9, m21 post the setup
  phases (m21 drains each phase's own queue in order); no gate asserts a setup order between two mods, which is what the
  unit test pins. m14 (not in this lane's list) was not run.

### For other lanes

- Nothing to change for G-C, G-B2, UPG or SWAP. Shared file with K1/K3 history: `KernelLifecycle.java`
  (`closeRegistrationWindow` loses its sort block; `clearBlockStateIds` right before `freeze` is kept in both closes, as K1 needs).
- T-B phase 2: one new test class, `KernelModLoaderPublishedOrderTest` (2 tests, one ci-unstaged row, cfb39a49); the new
  `KernelLifecycleRegistrationWindowTest` case needs no staged root. Suite count +3. The ci-prepared-noforge profile was not
  measured (its classless rows still name the Forge carrier; T-B's).

### Open items

1. (orchestrator, unchanged) K3 B-8/A-4: a throwing RegisterEvent listener is contained; native NeoForge rolls back and stops.
   Not touched here.
2. (orchestrator / kernel) COMMON then CLIENT config load order vs native CLIENT then COMMON; no affected mod shown.
3. (G-C / gate owners) No gate joins a dedicated server with a Fabric mod that has a creative tab, so the client-window sort is
   pinned by the unit shape test and these measured arms only. A Fabric creative tab on the m12 or m14 client (fixture or
   canary) plus a smoke read of `SORTED_TABS` at the join would make it a gate check.

## K3b wave 2b review fixes (2026-11-11)

Reviews `c5/review-k3b-w2b-A.md` (A-1..A-3) and `c5/review-k3b-w2b-B.md` (B-1..B-7). Branch `s4-c5-k3`, on a4981568:
8db54acf, c80e5c84, 07b16ecb, c7b18b7b, fcf90fda, 61b9945b, and this record. Evidence:
`~/Forbric-backups/stage4-notes/c5/k3b/w2b/review-fixes/` (mut-*.diff/log/xml/summary, tip-xml/, ci-measure.log + ci-xml/,
native-src/, gates/{tip,tip2,mut-common-first}/, suite/, suite-c7b18b7b/). Nine findings: seven fixed, two answered in
this record (B-5, B-6); none refuted.

### Per finding

- **A-1 fixed (8db54acf).** Verified first: `published(neo, classless)` and the ModList publish put every mod with no `@Mod`
  class after every constructed mod, whatever the dependencies said (code read at a4981568; K3b's 25fbc88f did not touch
  the ModList order). The m9 pack shows it: the in-tree client-merged-pack log of an earlier m9 run (2026-11-01, the same
  publish code) has ModList `…, lambdynlights, stellarity, lambdynlights_api, spruceui, shogi_api, apollib,
  yumi_mc_core`, while lambdynlights' `neoforge.mods.toml` requires lambdynlights_api and spruceui. The fix applies the guiding rule (option (a) of the review):
  `dependencyOrder()` (`ModConstructionOrder.of` over the published mod metadata) is computed once. It orders the `@Mod`
  classes' construction, as before. `published(constructed, classless, order)` now places every container, declared-only
  ones included, in that order, in `publishedNeoMods()` and in ModList alike. With no metadata, or under
  `-Dforbric.modOrder=name`, nothing changes. On the withdrawal re-publish the same order is applied. Measured at the new
  tip (m9, `gates/tip/m9-client.latest.log`): `…, lambdynlights_api, … spruceui, lambdynlights, yumi_mc_core` (yumi_mc_core
  is a presence alias; aliases still go last and take no setup phase). This changes the order in which setup phases,
  `RegisterEvent` (deliverInModOrder reads ModList) and `DataPackRegistryEvent.NewRegistry` reach the declared-only mods.
  Tests: `KernelModLoaderPublishedOrderTest.aModWithNoModClassStandsInTheDependencyOrder` (unit) and
  `aDeclaredOnlyLibraryIsPublishedBeforeTheModThatRequiresIt` (staged). In the staged case one jar declares `ordertest_dep`
  (an `@Mod`, which requires `ordertest_lib`), `ordertest_free` (an `@Mod`) and `ordertest_lib` (no class), and the test
  expects free, lib, dep in the map and in ModList. Red controls:
  - the call site back to the old two-argument `published` (`mut-a1-callsite.*`): the staged case is red, "expected
    [ordertest_free, ordertest_lib, ordertest_dep] but was [ordertest_free, ordertest_dep, ordertest_lib]";
  - `published` ignoring the order (`mut-a1-ignore-order.*`): 2/4 red, the unit and the staged case.

  Not covered by the review's suggested shape: no staged case fires a setup phase at a classless mod with a subscriber. The
  map that `firePhase` walks is the one pinned, and `walkingThePublishedModsIsWalkingModList` already pins that walk.
  Remaining native-order difference: `NewRegistryEvent` goes to `registrationBuses(mods, classless)`, so constructed mods
  first, then the declared-only ones. Native NeoForge posts it in ModList order (`RegistryManager.postNewRegistryEvent` ->
  `ModLoader.postEventWrapContainerInModOrder`, `native-src/neoforge-config-and-registry-order.txt`). Not changed (open
  item 3).
- **A-2 fixed (c80e5c84, comments only).** All three were confirmed wrong at a4981568:
  - `openLateConfigs`' javadoc and `KernelConfigLoad.openLate`'s said "before mod content registration"; the early pass
    runs at step 2c, after it.
  - Step 3b2 said the early pass ran before `FMLConstructModEvent`. That event fires inside `registerNeoForgeContent`
    (`fireSetupPhase(... FML_CONSTRUCT_MOD_EVENT ...)`), before step 2c, so a config registered from it is loaded by the
    early pass. Only common setup and later phases are late.
- **A-3 fixed (07b16ecb; fcf90fda).** javap of the carrier, `CommonModLoader.lambda$begin$1`: `if dist == CLIENT:
  loadConfigs(CLIENT)`, then `loadConfigs(COMMON)` (`native-src/`). The type list moved into `KernelLifecycle.earlyConfigTypes`,
  now `CLIENT, COMMON` on the client and `COMMON` on a server.
  - New test `KernelLifecycleEarlyConfigOrderTest`: a literal case, plus a staged case that reads the `ModConfig.Type`
    constants of the one `CommonModLoader` method that calls `ConfigTracker.loadConfigs` and compares the kernel's list with
    them.
  - `KernelLifecycleLateConfigTest.theEarlyPassDoesNotNameStartup` read only `loadEarlyConfigs`' constants, which would
    have gone vacuous. It now reads both methods, asserts that `loadEarlyConfigs` calls `earlyConfigTypes`, and calls it per
    side.
  - Red controls (`mut-a3-*`):
    - COMMON first: EarlyConfigOrderTest 2/2 red.
    - STARTUP added: EarlyConfigOrderTest 2/2 and the STARTUP check red.
    - A literal list in `loadEarlyConfigs` that bypasses `earlyConfigTypes`: the STARTUP check red.
  - Two checks spelled the old order and went red at 07b16ecb: gate-m9's "the early pass loads both boot-time config
    types" grepped `loaded NeoForge configs (COMMON+CLIENT)` (m9 162/1), and `KernelLifecycleConfigPassesTest` was red in
    2 cases (suite at c7b18b7b: 5865/0/7). fcf90fda makes both expect `CLIENT+COMMON`. The m9 check now also guards the
    order:
    - tip 61b9945b: 163/0 (`gates/tip2`);
    - an APFS clone with only `earlyConfigTypes` back to COMMON first (`mut-m9-common-first.diff`): 162/1, red on exactly
      that check (`gates/mut-common-first`).

    Every client log at the tip says `loaded NeoForge configs (CLIENT+COMMON)`: m26 ×2, m55's three phases, m9. Servers
    say `(COMMON)`, unchanged.
- **B-1 fixed (61b9945b + this record).** "Native Fabric shows them from the start" was not measured. It is replaced by what
  was read from source (`native-src/vanilla-CreativeModeTabs.txt`, `native-src/fabric-pager.txt`):
  - Vanilla 26.2 `CreativeModeTabs.tabs()` streams `BuiltInRegistries.CREATIVE_MODE_TAB` on every call (`streamAllTabs`).
  - fabric-creative-tab-api-v1 5.0.14's pager (`CreativeModeInventoryScreenMixin.getTabsOnPage` / `hasAdditionalPages` /
    `hasGroupForPage`, `FabricCreativeGuiComponents`) reads `tabs()`.
  - So natively a registered Fabric tab is in the list the strip reads, and there is no sorted copy to miss. Where Fabric
    assigns a page (`CreativeModeTabsMixin.paginateTabs`) was not traced.

  No native Fabric client arm and no native NeoForge client arm were run: the only native arm is the NeoForge dedicated
  server. The e785444a commit body keeps the sentence; this record and the corrected code comment carry the correction.
- **B-2 fixed (61b9945b + this record).** The tab contents and the search tab were not measured. Every arm's probe prints
  `ITEMS …=0`, because display items are built only when the screen opens. "Searchable" in e785444a's body and in §1
  check 2 above means only "in the `CREATIVE_MODE_TAB` registry", which the probe did read. The code comment now says that.
- **B-3 fixed (this record, handoff).** A third walker of `publishedNeoMods()` changed order with 25fbc88f:
  `registerDataPackRegistries` posts `DataPackRegistryEvent.NewRegistry` per mod over it, now in ModList order. Native
  posts it through `ModLoader.postEventWrapContainerInModOrder` (javap, same file), so this is native-equivalent. The
  `published` javadoc now names it (8db54acf).
- **B-4 fixed (evidence).** The lane's m55 run at e785444a left its three per-phase client logs in
  `build/verification/m55-creative-search/`. They are now copied to `c5/k3b/w2b/gates/tip-client/m55-phase-logs/`
  (PROVENANCE.txt; `resort-lines.txt`: baseline, refreshed and withheld each log `re-sorted NeoForge creative tabs
  10 -> 10`), and the last-phase run-dir log is beside them. So "each phase" in the gate table above holds. This run's m55
  phase logs are in `review-fixes/gates/tip/m55-phase-logs/` (each: `10 -> 10`, `CLIENT+COMMON`).
- **B-5 answered (this record).** The C3 caller check's evidence is in this record, not in e785444a's body. The new symptom
  check (probe `k3btabs.jar` on `SORTED_TABS`) was shown red on the genuine base, not on cf0498eb: `tabs/mp-on` and
  `tabs/mp-off` (no sort at the client window's close) leave mcwstairs out of the strip on a join, and `tabs/mp-fix` has
  it. That stands in for the merged-base validation because the defect it detects exists only after the C3 deletion
  point moved: on cf0498eb the re-sort in `closeRegistrationWindow` and NeoForge's tail sort both ran, and neither covers
  `Minecraft.<init>`. The question there is whether the probe sees a late tab, and the genuine-base mp-on arm shows it
  does.
- **B-6 answered (this record, handoff).** Open item 3 (no gate joins a server with a Fabric creative tab) is now assigned
  to **the orchestrator**, likely as a later G-C item: add a Fabric creative tab to the m12 or m14 client (fixture or
  canary), plus a smoke read of `SORTED_TABS` at the join. For the merger: merge-k.md's open rows "B-7" and "K3
  unchanged items" are resolved by K3b. The config-order part is now fixed (A-3). B-8/A-4 stays open.
- **B-7 fixed.** `/private/tmp/claude-501/k3bscratch/` (one draft, record-k3b.md) is deleted.

### Results at the tip

- **Targeted, at the worktree before commit** (`review-fixes/tip-xml/`): KernelModLoaderPublishedOrderTest 4/4,
  KernelLifecycleEarlyConfigOrderTest 2/2, KernelLifecycleLateConfigTest 6/6, KernelModLoaderClasslessTest 8/8,
  KernelLifecycleRegistrationWindowTest 8/8, ModConstructionOrderTest 19/19.
- **ci-unstaged** (APFS clone at 07b16ecb's tree, staged root pointed nowhere, no requireFixtures, `ci-measure.log`,
  `ci-xml/`):
  - `aDeclaredOnlyLibraryIsPublishedBeforeTheModThatRequiresIt` skips "[fixture:staged] staged runtime classes/carrier
    absent".
  - `theClientsOrderIsTheCarriersOwn` skips "[fixture:staged] staged NeoForge carrier absent".
  - Every other new case runs.
  - Two rows were added in sort order (c7b18b7b); no existing row was changed.
- **Suite** (`suite.sh` on an APFS clone, src identical to the worktree at 61b9945b, `review-fixes/suite/`): **5865 tests /
  0 skipped / 5 failed**, the five names of failures-k.txt, so 0 new. Name diff against the lane's e785444a run: 0 gone, 4
  new (the two new PublishedOrder cases, the two EarlyConfigOrder cases). The intermediate run at c7b18b7b
  (`suite-c7b18b7b/`) was 5865/0/7: the two KernelLifecycleConfigPassesTest reds fixed by fcf90fda.
- **Gates**, in the K3 worktree on 07b16ecb's code (m9 also at 61b9945b), one at a time, counting `^[kernel] PASS/FAIL`.
  Gates whose reach changed: classless mods present (m9, m10-overlay), client config order (m26, m55, m9), config gates
  (m2, m7-neo).

  | gate | PASS / FAIL | e785444a |
  |---|---|---|
  | m10-overlay | 24 / 0 | 24/0 |
  | m2 | 29 / 0 | 29/0 |
  | m7-neo | 28 / 0 | 28/0 |
  | m26-forgeclient | 45 / 0 | 45/0 |
  | m55-creative-search | 23 / 0 | 23/0 |
  | m9-client at 07b16ecb | 162 / 1 (the config-order check, fixed by fcf90fda) | 163/0 |
  | m9-client at 61b9945b | **163 / 0** | 163/0 |
  | m9-client, COMMON first (control) | 162 / 1 | — |

  m1, m2b, m4, m4-canary, m21 and m25 were not re-run. They are servers (A-3 does not reach them), and none of their
  run-dir logs lists a declared-only NeoForge mod (the kernel's "declare no @Mod class" line): server-fabric-api,
  server-tri, server-tri-real, server-forgesetup and server-worldgen-canaries from the lane's e785444a runs, and
  server-kernel from this run's m2. Only client-merged-pack (m9) and server-overlay (m10) have one. So A-1 reorders
  nothing there. 61b9945b is comment-only, so the 07b16ecb runs stand for it.

### For other lanes (replaces the K3b list above where they differ)

- **Merger.** Changed files beyond K3b's:
  - `KernelModLoader.java`: `dependencyOrder`, three-argument `published`, construction and publish order.
  - `KernelLifecycle.java`: `earlyConfigTypes`, three comments; the creative-tab sort comment.
  - `KernelConfigLoad.java`: a comment.
  - `gate-m9-client.sh`: one check's pattern, now `CLIENT+COMMON`.
  - Tests: KernelModLoaderPublishedOrderTest, KernelLifecycleLateConfigTest, KernelLifecycleConfigPassesTest, and the new
    KernelLifecycleEarlyConfigOrderTest.
  - `ci-unstaged.tsv`: +2 rows, so +3 with K3b's.
  - Suite count: +4 over K3b, so 5858 -> 5865.

  Mark merge-k's "B-7" and "K3 unchanged items" rows resolved. B-8/A-4 stays open.
- **G-C:** gate-m9 changed in one check (pattern only); no overlap with G-C's m9 seam pair. Nothing else.
- **G-B2, UPG, SWAP:** nothing.
- **T-B phase 2:** one new test class (2 cases) and two more PublishedOrder cases, with their ci-unstaged rows. The
  ci-prepared-noforge profile was not measured.

### Open items

1. (orchestrator, unchanged) K3 B-8/A-4: a throwing RegisterEvent listener is contained, where native NeoForge rolls
   back and stops.
2. (orchestrator; G-C as a later item) No gate joins a dedicated server with a Fabric creative tab: B-6 above.
3. (orchestrator / kernel, new, low) `NewRegistryEvent` reaches the declared-only mods after every constructed mod
   (`registrationBuses`), where native NeoForge walks ModList. No declared-only jar the review scanned has any subscriber
   (review A, `scanlist.py`).
4. (orchestrator, optional) A native Fabric client arm (fabric-api + mcw-stairs) and a native NeoForge client arm were not
   run. B-1's native-Fabric statement rests on the javap read above.

## Resolved later

B-8/A-4 (the open item above): owner ruling D82 "stop like native", implemented by lane K3c (stage4-c5-k3c.md): a
throwing RegisterEvent listener or any throw inside NeoForge's per-registry try now stops the boot as native NeoForge does.
