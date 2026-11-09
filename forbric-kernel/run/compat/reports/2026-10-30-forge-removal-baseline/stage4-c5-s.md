# Stage 4 C5, lane S (wave 2): what S1 left open, plus the kernel items handed to S

Lane S of the C5 plan (`~/Forbric-backups/stage4-notes/c5/PLAN.md` §2 "Lane S", D63 split; this is wave 2 after S1's
`stage4-c5-s1.md`), branch `s4-c5-s1`, rebased (fast-forward) onto the stage tip `f0cb66b0`. Ports 46720-46739. Pinned
build (D68: plain `--offline`, JUnit 5.9.2, no init script). Logs and tools: `~/Forbric-backups/stage4-notes/c5/s/w2/`.

## Commits

| sha | subject | item |
|---|---|---|
| `29787aa8` | Count only NeoForge's package as an ecosystem re-typing in AccessCensus, since neither the base nor the carrier names a MinecraftForge class any more | 9 |
| `d536fc07` | Pin the registry-loader adapter's dead-loader binding clause with a constructed mixin, since no test reached it and NeoForge's class never does | 8 |
| `be68e288` | Measure NeoForge's payload lookup with no protocol and no flow, so -Dforbric.customPayloadInteropSeam=off stays offered on its base | 5 |
| `7d506522` | Check that every game-side kernel class has a live caller on NeoForge's base, by reading the chain's own output for the calls it writes | 2 |
| `35985d32` | Delete the kernel's blockstate-id refill, since NeoForge's own block bake maps every registered block at each freeze and the refill never ran | 7 |
| `748f142b` | Tolerate an unregistered NeoForge condition type only in a Fabric mod's own pack, so a world datapack's file is skipped as native NeoForge skips it | 6 (D69) |
| (this file) | the record | |

Items 1, 3 and 4 are measurements with no code change (below).

## First act

Full suite at `f0cb66b0` in this worktree (`suite-first.log`, XML `suite-first-xml/`): **5772 tests / 0 skipped / 5 failed**,
exactly the 5 names of `c5/failures-wave1.txt` (LiquidBounce x3, SharedFinalSourceActualTest x2; D58 fixture reds).

## Per item

### 1. featuresPerStep (D52, 64695aef): the server-boot symptom run

Fixture found on disk (no download): `build/purefabric-2026-10-03/mods/BetterSpringToLife-1.3-Fabric.jar` (sha1
`4c7bac26`), whose `BSTLBiomeFeatures` calls `BiomeModifications.addFeature` for forest, taiga, jungle and birch biomes
from its main entrypoint (javap). Scanned candidates (`tools/findbiome.py`): also oreoverhaul (needs terrablender),
kaleidoscope_end (needs kaleidoscope_cookery), naturalist, andonium, survivalistessentials. Boot: a dedicated server with
fabric-api 0.155.2, BetterSpringToLife and the lane's `forbricneolive.jar` (2 biome modifiers, 1 structure modifier),
fixed seed (`tools/fps-boot.sh`, logs `item1/`):

| boot | `did not apply` WARN | what the log says |
|---|---|---|
| default | **0** | `a mod replaced a chunk generator's feature list with its own supplier (com.google.common.base.Suppliers$NonSerializableMemoizingSupplier; ...); NeoForge's modifier pass rebuilt it`, then `applied NeoForge's 2 biome modifier(s) and 1 structure modifier(s)` |
| `-Dforbric.featuresPerStepRefresh=off` | **1** | `NeoForge's biome/structure modifiers did not apply — 2 biome and 1 structure modifier(s) ...` with `ClassCastException: Suppliers$NonSerializableMemoizingSupplier cannot be cast to ... Lazy at ChunkGenerator.refreshFeaturesPerStep(ChunkGenerator.java:104)` |

Both boots reached Done and stopped cleanly (`exit 0`). So the adaptation is needed on a real Fabric feature mod, and its
switch reproduces the symptom.

### 2. KernelRuntimeClasses liveness (70 entries; 71 after D69)

`RuntimeSeamLivenessStagedTest` (new; ChainAnchorCensusStagedTest's replay now also records the
`net/forbric/kernel/runtime/` classes each changed class names). Every registry entry must be a chain target on the
server or client side, or listed with the caller it has. Result (table `runtime-seams.tsv`, one row per entry, 0 NONE):

| kind | n | entries |
|---|---|---|
| CHAIN (the chain writes the call into a class of NeoForge's base or carrier) | 37 | measured; the TSV names the calling classes |
| BOOT (reflective, boot-side) | 11 | KernelContainers, KernelDataPackSource, KernelClientPackSource, KernelRegistryContent, KernelForeignShimKeys, KernelConfigLoad, KernelNeoSetup, KernelNeoRegistries, KernelScanData, KernelNeoDataMapWatch, KernelModConfigScreens |
| GUEST (an adapter writes the call into a guest mod's class) | 15 | KernelWrapOperations, KernelDispatch, KernelShears, KernelMobShears, KernelFluidReactions, KernelItemDamage, KernelFabricTagViews, KernelTagFacadeSources, KernelFluidPredicateSeams, KernelFluidPredicateIslands, KernelSourceDecodeScopes, KernelBlockStateModelFormats, KernelConfigApiBridge, KernelDeclarationReaders, KernelFmlTransformerView |
| MOD_INSTALLED | 5 | ClientSoakController (soak launch), TransferIssues (transfer bridge), RebornEnergyBridge (Team Reborn Energy), KernelCreativePager + KernelCreativePagerScreen (fabric-creative-tab-api, client) |
| GENERATED / PACKED | 1 / 1 | LootCallbacks$ / KernelModMenuPlugin |

The table is the 70 entries at `7d506522`. At the item-7 deletion the registry dropped one call (not an entry); at D69 it gained `KernelConditionSources`, a CHAIN target (`Resource.open()`); the final suite runs the test over 71 and it passes.
(Superseded by the tip table in "S wave-2 review fixes": KernelModConfigScreens is RUNTIME, ClientSoakController DRILL.)
Runtime evidence for the boot-side rows on a server: the item-1 default boot ran with `-Xlog:class+init`; 58 runtime
classes were initialised (`item1/initialised-server-default.txt`), every server-side BOOT row among them
(KernelContainers, KernelDataPackSource, KernelRegistryContent, KernelConfigLoad, KernelNeoSetup, KernelNeoRegistries,
KernelScanData, KernelNeoDataMapWatch). The client-side BOOT rows (KernelClientPackSource, KernelForeignShimKeys,
KernelModConfigScreens) were not class-init measured in this lane (no client boot with -Xlog).
Red checks: a registry name nothing calls is named (constructed); the replay with FeaturesPerStepRefreshInjector silenced
leaves exactly KernelFeaturesPerStep unexplained; a fake `CLASSES.put` added to KernelRuntimeClasses made
`everyRegisteredGameSideClassHasALiveCaller` red (`item2-red.log`), restored green. No dead entry found: nothing deleted.

### 3. AbiLinkAudit D21/D23 re-measure

`tools/AbiRemeasure.java` runs the kernel's own `AbiLinkAudit.audit` (built classes at the tip) over the 160 universal
jars of `stage3-sweep.tsv` that declare both NEOFORGE and FORGE and are not refused (all 160 opened, the two nested
shogi-api jars included), each against base + carrier + the jar + its nested jars (`abi-remeasure.out`):
- all 160 carry `META-INF/mods.toml`, so the boot's per-jar set-aside applies to each; **0 jars have findings** as at boot;
- judging every reference (no set-aside), 31 jars would have findings: what D21's set-aside exists for;
- classes naming an in-scope `net/minecraftforge/` class: 144, **all on a Forge-named path** (path heuristic: a class path
  containing `neoforge` → NeoForge half, else containing `forge` → Forge half, `fabric` → Fabric, else common); no
  NeoForge-, Fabric- or common-path class names one. So the per-jar skip hides no NeoForge/Fabric-half reference in the
  corpus; AbiLinkAudit is not changed (it does not judge a set-aside half; the D21 edit is in place).

### 4. D23 record (review B-7: re-run before stating a count)

`tools/d23.py` re-run, output saved FIRST at `c5/s/w2/d23.out`: over the same 160 jars (all opened), **0 classes carry
both** `Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;` and `Lnet/neoforged/fml/common/EventBusSubscriber;`;
2 classes carry MinecraftForge's alone, 7 NeoForge's alone. D23 (accept native NeoForge behaviour) has no instance in the
corpus; no boot needed.

### 5. D63: `customPayloadInteropSeam=off` null tolerance

Measured on the staged carrier (`item5/probe.out`, and now `NeoForgeLookupWithoutContextStagedTest`):
`NetworkRegistry.getCodec(id, null, null)` does not throw. `minecraft:register` and `neoforge:register` (built-in
payloads) return their codec; an unregistered id returns null after NeoForge's own `ERROR A packet was received while
not in the configuration or play phase.` and a stack dump (javap: `PAYLOAD_REGISTRATIONS` is an `ImmutableMap`, whose
`containsKey(null)` is false; the `flow` comparison is by identity). NeoForge's `$1.findCodec` then asks the caller's
fallback. **The switch stays offered**; its cost off is the cross-ecosystem choice the seam makes plus one ERROR per
unregistered-id lookup, never a crash. (stage2-measurements.md's "not measured" is answered here.)

### 6. D69: the condition leniency narrowed to Fabric mods' packs

Root cause (G-A): `KernelNeoConditions.lenient`, put before `ICondition.CODEC` by
`ForbricMergedBaseCompatTransformer.letForeignResourceConditionsThrough`, read an unregistered condition type as true
for every pack, so Forbric kept gate-m21's world-datapack file `dual_forge.json` that native NeoForge 26.2.0.93 skips
(G-A's native run, `c5/g-a/d49/native-d49.log`: `Couldn't parse data file ... Unknown registry key ...
neoforge:condition_codecs`).
Fix: a new repair `tellTheConditionsWhichPackAFileIsReadFrom` puts `aload_0; getfield source; invokestatic
KernelConditionSources.reading(Object)V` at the head of `Resource.open()`; every reader the base runs NeoForge's
evaluator in opens the file through it and decodes on the same thread. `lenient` now tolerates an unregistered type in a
data file only when that pack implements fabric-resource-loader's `ModPackResources` (or v0's `ModResourcePack`), i.e. a
Fabric mod's own pack; otherwise it hands the input to NeoForge's strict codec (native: the file is skipped) and logs
`[Forbric/Conditions] resource condition '<type>' in pack '<id>' is not in NeoForge's condition registry, and that pack
is not a Fabric mod's: NeoForge's evaluator decides ...` once per type and pack. Overlay entries (pack.mcmeta) are
unchanged: still vetoed in every pack (`forbric.overlayConditions`). Switch `-Dforbric.neoConditions.fabricPacksOnly=off`
restores the leniency for every pack (D69's red control).
Measurements: gate-m21 **58 PASS / 0 FAIL** (was 57/1 at the stage tip): the dual_forge probe answers `Can't find element
'forbricm21:dual_forge'`, after the kernel's `resource condition 'forbricm21:no_such_condition' in pack 'file/forbricm21' ...
NeoForge's evaluator decides` line and NeoForge's own `Couldn't parse data file 'forbricm21:dual_forge' ... Unknown registry
key in ResourceKey[minecraft:root / neoforge:condition_codecs]: forbricm21:no_such_condition` — the same line native NeoForge
prints for it (G-A's `c5/g-a/d49/native-d49.log`: the native A/B). gate-m8 **20 / 0** (the claim that this shows Terralith's data files still tolerated is withdrawn: `terralith:config` is only
in pack.mcmeta overlays; see "S wave-2 review fixes", A-3). Red control
`M21_EXTRA_JVM=-Dforbric.neoConditions.fabricPacksOnly=off`: m21 **57 / 1**, exactly "a file whose neoforge:conditions name
an unknown type is skipped, as native NeoForge does" (answered `Test passed`). Unit: KernelConditionKillSwitchesTest
`outsideAFabricModsPackTheStrictCodecDecides` (no file opened → strict; world pack → strict; Fabric pack → tolerated; switch
off → tolerated), MergedBaseForeignConditionsTest `resourceOpenTellsTheLeniencyWhichPackTheFileIsFrom`; the two existing
fixtures (kill-switch, overlay) now record a Fabric pack first. G-A's m21 is not edited (the check already expects native's
answer); only its comment still calls dual_forge an open defect (Open). Logs `gates/m21-d69*.out`, `gates/m8-d69*`.

### 7. KernelRegistryContent.rebuildBlockStateIds: deleted after the three C3 checks

- symptom with the repair gone: gate-m26 on the deletion tree 45 PASS / 0 FAIL; the late Fabric block reads
  `id=64735` and NeoForge's `id=64733`, the late block's id equal to G-A's default (repair-present) run; gate-m12 (multiplayer over a socket, the path a missing state id breaks with "Can't find id for Block{...}" at the first block_update) at the lane tip 32 / 0 (alone, `--exclusive`, `gates/m12-final.out`). Before the deletion no boot
  printed the rebuild line or either of its warnings (G-A's 26 genuine boots; this lane's 2 item-1 server boots, with 55
  mod blocks);
- whole-jar search: the base and carrier fill `BlockCallbacks.BLOCKSTATE_TO_ID_MAP` only in `BlockCallbacks.onBake`,
  which iterates the whole block registry (javap: `Registry.iterator`, every state `add`; it does not clear first, see
  "S wave-2 review fixes", A-4);
  `GameData.freezeData` freezes every `MappedRegistry`, and `MappedRegistry.freeze` runs the bake callbacks. The merged
  base's cause (MinecraftForge's `GameData$BlockCallbacks` and its second registration wave in `Minecraft.<init>`) has
  no class here: 0 classes of base + carrier name `net/minecraftforge` (`item9-minecraftforge-in-base.out`);
- caller check: the only caller was `KernelLifecycle`, at `closeRegistrationWindow` and `closeClientEntrypointWindow`,
  each directly after `freeze(cl)`.
The rest of that step stays as `completeBlockStateBookkeeping` (state caches, whole-registry passes); switch
`forbric.blockStateIdRefill` → `ForbricSwitches.RETIRED` (still in released-switches.txt, as a released name).
D34: no gate checks the line any more (G-A removed m26's check, 5b761e62); m26's header still describes the refill and
`-Dforbric.blockStateIdRefill=off` as a control — G-A's file, see Open.

### 8. FabricRegistryLoaderMixinAdapter `deadCallees.contains(bound)`: kept, now reached

`RegistryLoaderDeadBindingTest` constructs the shape (compiled against `w(String)`, `w(String,boolean)`; current class
`w(String,boolean)` uncalled and `w(String,boolean,boolean)` called by `host()`; a wrap of host's call plus a bare-name
`"w"` companion). The companion's binding passes the widened-twin rule but lands on the dead loader; the clause leaves
the whole mixin alone (adapt 0, bytes unchanged). Positive: without the companion the wrap moves (adapt 1). Red check:
clause removed → exactly `aCompanionTheCurrentClassBindsToADeadLoaderLeavesTheWholeMixinAlone` red ("expected 0 but
was 1"; `item8-red.log`), the other RegistryLoader tests green; restored green.

### 9. AccessCensus.CARRIER_TYPES and VanillaDamageReadInjector's javadoc

`Lnet/minecraftforge/` removed from CARRIER_TYPES: 0 classes of the base + carrier mention `net/minecraftforge`, so it
could never match. New `AccessCensusTest.onlyNeoForgesOwnPackageCountsAsAnEcosystemReTyping`; red with the prefix put
back (`item9-red.log`). The javadoc's ForgeHooks parenthesis is gone. AbiLinkAudit's and DelegationPolicy's
`net/minecraftforge` rules stay (plan KEEP).

## Skip TSVs (D47)

ci-unstaged.tsv gains rows for the new staged tests (measured with `-Pforbric.stagedRoot=/nonexistent-s/run`,
`unstaged-measure.log`): NeoForgeLookupWithoutContextStagedTest (1, `[fixture:staged] the staged game is absent`),
RuntimeSeamLivenessStagedTest (4, class-level abort). RegistryLoaderDeadBindingTest runs unstaged (pure). KernelConditionKillSwitchesTest `outsideAFabricModsPackTheStrictCodecDecides` and MergedBaseForeignConditionsTest `resourceOpenTellsTheLeniencyWhichPackTheFileIsFrom` get ci-unstaged rows too (measured, `unstaged-measure-d69.log`). ci-prepared: no row (every new staged test runs with the stage present; S1 precedent).
No test deleted or renamed by this lane.

## Suite and gates at the end

Full suite at the lane tip `748f142b` (pinned build, `suite-final.log`, XML `suite-final-xml/`): **5782 tests / 0 skipped
/ 5 failed** — the 5 names of `c5/failures-wave1.txt`, every message's first 240 characters equal the pinned wave-1 XML's;
0 new, 0 gone. +10 tests (AccessCensusTest +1, RegistryLoaderDeadBindingTest +2, NeoForgeLookupWithoutContextStagedTest +1,
RuntimeSeamLivenessStagedTest +4, KernelConditionKillSwitchesTest +1, MergedBaseForeignConditionsTest +1).
`c5/failures-s-w2.txt`.

Gates on the genuine stage (lane env, ports 46720-46739; S owns no gate file; these are the gates its changes reach):

| gate | tree | PASS / FAIL | why run |
|---|---|---|---|
| m26 | `35985d32` (item 7) | 45 / 0 | item 7 symptom check (late block ids) |
| m21 | `748f142b` | 58 / 0 | D69 (dual_forge green) |
| m8 | `748f142b` | 20 / 0 | D69 (Fabric pack still tolerated) |
| m21 red, `-Dforbric.neoConditions.fabricPacksOnly=off` | `748f142b` | 57 / 1 | D69 red control: exactly dual_forge |
| m25 | `748f142b` | 16 / 0 | worldgen (featuresPerStep, conditions) |
| m12 (exclusive, client lock) | `748f142b` | 32 / 0 | item 7 symptom check (block_update over a socket) |

No SKIP line, no FAIL left. Item-1 boots are not a gate: default 0 / off 1 "did not apply" WARN (above).

Red controls shown (mutation or switch off → red; restored → green): item 2 (fake registry entry; silenced injector),
item 6 (switch off → m21 dual_forge only; unit no-file/world-pack cases), item 8 (clause removed → its case only),
item 9 (prefix restored → its case only), item 1 (switch off → the WARN).

## Open

- **G-A (merges after S):** gate-m21's comment above the D49 block (`:211-219`) and its RED-controls note (`:257-258`)
  still call dual_forge "red as an open defect"; m21 is now 58/0 with no edit by S. m26's header (`:13-22`) still describes
  `KernelRegistryContent.rebuildBlockStateIds` and `-Dforbric.blockStateIdRefill=off` as a control: the refill is deleted
  (`35985d32`) and the switch is RETIRED (the kernel now warns it does nothing). Both are wording in G-A's files.
- The client-side BOOT rows of item 2 (KernelClientPackSource, KernelForeignShimKeys, KernelModConfigScreens) have no
  class-init evidence from this lane (no client boot run with `-Xlog:class+init`); their callers are on the client launch
  path (KernelClientPacks, KernelForeignShimContext, KernelClientSmoke) — VERIFY/owner if wanted.
- Item 3's half classification is by class path name (heuristic); a common-path class naming `net/minecraftforge/` would
  have been listed, none was.
- PLAN S item 5 owner-gated items (O2/O3) are S2's, not run (D62/D63).
- ci-prepared-noforge.tsv untouched (C6). stage2-measurements.md still says the seam-off null question is unmeasured; this
  record answers it (RECORD may cross-reference).

## S wave-2 review fixes (2026-11-10)

Reviews `c5/review-w2-s-A.md` (lens A) and `c5/review-w2-s-B.md` (lens B). Every finding was checked against the code, the
base and new runs; logs, probes and tools are in `c5/s/w2r/`. Commits on `s4-c5-s1` after `f333f1f6`:

| commit | findings |
|---|---|
| `36dab5d6` | A-1, A-2, B-1 (test + wording), B-3 (wording), B-8 (MergedBaseForeignConditionsTest:75) |
| `835ec80c` | A-4, A-6 |
| `9cbc9a9c` | B-7 (Caught-site gap G-B1 routed to the kernel lane) |
| `fee13b0f` | A-5, B-6 |
| `3c6a947a` | D47 rows for the new staged tests |
| this commit | record (A-3, B-2, B-4, B-5, B-9, B-10 and the corrections below) |

Each split commit compiles alone (`git archive` of 36dab5d6, 835ec80c, 9cbc9a9c → compileJava compileRuntimeJava
compileTestJava, exit 0, `compile-*.log`).

### Per finding

- **A-1 confirmed, fixed (`36dab5d6`).** `scanDirectoryWithModifier` (javap, `reader-census.out`) opens and parses every
  recipe in a first loop, posts `ModifyRecipeJsonsEvent`, then decodes in a second; at `f333f1f6` a Fabric probe mod's
  recipe naming an unregistered type was judged as pack `forbric/carrier/2-neoforge-runtime` and skipped (`boot-prefix.log`,
  reproducing lens A's `boot-plain.log`). New repair `tellTheConditionsWhichPackEachRecipeCameFrom`: `beginTwoPassScan` at
  the head, `dup; parsed(Object)` after the first loop's `JsonParser.parseReader`, `dup; decoding(Object)` before the second
  loop's `Codec.parse` (left in place for a guest's wrap), `endTwoPassScan` before each return; the one-pass
  `scanDirectory` gets `doneReading` before each return, so no pack stays named to a later decode (the "never cleared"
  half). An element no file produced (added in the event) gets NeoForge's verdict.
- **A-2 confirmed, fixed (`36dab5d6`).** A Fabric pack with a mounted overlay is a `CompositePackResources`; the judged pack
  is now its `primaryPackResources` (reflective, cached, Caught on failure). At `f333f1f6` the overlaid probe's base and
  overlay loot tables were "not a Fabric mod's" and skipped (`boot-prefix.log`); now kept (`boot-fix.log`).
- **A-3 / B-1 confirmed.** m8 never reached the narrowed check: Terralith's `terralith:config` is only in `pack.mcmeta`
  overlay entries (lens B's jar scan; m8's "tolerated" line is the overlay decode). The record's §6 sentence "gate-m8 20/0
  (Terralith's terralith:config still tolerated ...)" is withdrawn, as is the handoff's "m8 green, Fabric packs still
  tolerated". 748f142b's message says the same and cannot be changed without rewriting history; this record supersedes it.
  **Evidence against D69's premise, reported to the orchestrator:** D69 names "Terralith, m8's case" as the Fabric case; it
  is not one. The narrowing itself still stands: Waystones' Fabric jar 26.2.0.12 (on disk, sweep80) carries
  `neoforge:conditions` naming `waystones:force_spawn_in_villages` in five lithostitched worldgen modifiers, a real Fabric
  data-file case. It could not be booted as evidence: its main entrypoint fails on the genuine stage with
  `IllegalStateException: Block{waystones:…_waystone}[…] is defined in more than one PoI type` (same at `f333f1f6`:
  `boot-ws-cont-prefix.log`), and the registry load then fails on its missing features whatever the leniency
  (`boot-ws-cont*.log`) — a separate, pre-existing defect (Open). The runtime evidence for the Fabric path is the probe
  set instead: two Fabric mods built locally, loaded through fabric-resource-loader's real pack classes.
  New staged test `fabricResourceLoadersOwnPackTypesAreRecognisedAndTheBasesAreNot` loads `ModNioPackResources`,
  `ModPackResources` and v0 `ModResourcePack` from the pinned Fabric API's nested jars (recognised) and the base's
  Path/File/Vanilla/Composite packs (not). Claim text and javadoc no longer cite Terralith.
- **A-4 confirmed, fixed (`835ec80c`).** `GameData.unfreezeData` only calls `BaseMappedRegistry.unfreeze(true)` (javap,
  `item7/GameData.javap`), and `IdMapper.add` numbers from `nextId` (`item7/IdMapper.javap`), so the client window's
  second bake appended every state: probe block 64733 on the client, 32366 on a dedicated server (lens A's logs).
  `closeClientEntrypointWindow` now empties the map (`clearBlockStateIds`, the ClearableObjectIntIdentityMap's own
  `clear()`) right before its freeze. Gate result below. Item 7's "the map always agreed" was true by count only (the
  refill checked `size()`); §7's "after onClear" is withdrawn (`onBake` adds without clearing); the late block's 64735
  was the doubled numbering, not a healthy reference value.
- **A-5 / B-6 confirmed, fixed (`fee13b0f`).** KernelModConfigScreens → kind RUNTIME, caller KernelModListScreen (CHAIN
  via ModsButton), checked by new `aRuntimeCallerIsItselfLive`; ClientSoakController → kind DRILL. The §2 table above
  (BOOT 11 incl. KernelModConfigScreens, MOD_INSTALLED 5 incl. ClientSoakController) is superseded by the tip table below.
- **A-6 confirmed, fixed (`835ec80c`):** onClientEntrypoints' javadoc reworded; completeBlockStateBookkeeping's too.
- **B-2 confirmed (unmeasured reach), now measured.** A dedicated-server boot of all 62 jars of `client-neo-pack` (+
  fabric-api 0.155.2, `-Dforbric.compatibilityPolicy=continue`) prints **0** "not a Fabric mod's" lines and 0 "Couldn't
  parse data file" lines, and loads 4467 recipes, the same with `-Dforbric.neoConditions.fabricPacksOnly=off`
  (`boot-neopack*.log`; both reach Done; jei does not finish loading on a dedicated server in both, unrelated). gate-m10
  (Tectonic + lithostitched, NeoForge builds): see the gate table. m9 (client, merged pack) was not run by this lane:
  named for the merger below. Behaviour change for RECORD: D69 makes a data file whose `neoforge:conditions` name a type
  NeoForge's registry lacks load only from a Fabric mod's own pack; from any other pack it is skipped with NeoForge's own
  error, as on native NeoForge; switch `-Dforbric.neoConditions.fabricPacksOnly=off` (not in released-switches; an
  adaptation switch, S1 precedent). The earlier handoff's "adaptation ledger" pointer had no row behind it; this paragraph
  is that row.
- **B-3 confirmed, fixed in wording + census.** `reader-census.out` (javap per reader: open / parse / decode order):
  PendingRegistration.loadFromResource, SimpleJsonResourceReloadListener.scanDirectory (loot via ReloadableServerRegistries,
  advancements, LootModifierManager, AnimationLoader), DataMapLoader.readData, ModelManager's per-model lambda feeding
  ConditionalModelLoader, SpriteSourceList.load open then decode on one thread; scanDirectoryWithModifier (recipes) is
  the two-pass exception (A-1); loadFromNetwork decodes without ConditionalOps; overlay entries have their own scope.
- **B-4 done.** `item7/blockstate-map-users.out` (re-run): only GameData and BlockCallbacks name BLOCKSTATE_TO_ID_MAP, only
  Block and GameData call getBlockStateIDMap; `item7/BlockCallbacks.javap` (onBake: Registry.iterator, every state add).
- **B-5 done.** Tip table saved at `c5/s/w2r/runtime-seams-tip.tsv` (71 rows); CHAIN list below.
- **B-7.** Caught-site gap fixed (`9cbc9a9c`): `postAttributeEvents` and `invokeStaticOn` invoke and report at their own
  catch; `invokeGameDataOn` keeps its own. Client BOOT rows' class-init evidence: not required by PLAN's acceptance (a
  caller per entry); this record states it is not measured (KernelClientPackSource, KernelForeignShimKeys); no owner
  assigned by this lane.
- **B-8.** MergedBaseForeignConditionsTest:75's message reworded (`36dab5d6`). For T-B phase 2: this lane changed
  AccessCensusTest, RegistryLoaderDeadBindingTest, NeoForgeLookupWithoutContextStagedTest, RuntimeSeamLivenessStagedTest,
  KernelConditionKillSwitchesTest, MergedBaseForeignConditionsTest, KernelLifecycleRegistrationWindowTest; ChainAnchorCensus
  StagedTest's three merged-base literals stay handed back.
- **B-9.** S1 record now points here; ci-prepared: reasoned, not run (stated in `3c6a947a`); handoff names the
  RuntimeSeamLivenessStagedTest rule for new registry entries.
- **B-10 not measured.** The one-ERROR-per-unregistered-id cost of `customPayloadInteropSeam=off` is from the item-5 probe;
  whether a live connection with the seam off logs it once per packet was not measured. §5's "its cost off is ... one
  ERROR per unregistered-id lookup" stands as a probe result, not a per-connection one.

### Liveness table at the tip (B-5)

71 entries, 0 NONE: CHAIN 38, BOOT 10, GUEST 15, MOD_INSTALLED 4 (TransferIssues, RebornEnergyBridge, KernelCreativePager,
KernelCreativePagerScreen), RUNTIME 1 (KernelModConfigScreens ← KernelModListScreen), DRILL 1 (ClientSoakController),
GENERATED 1, PACKED 1. CHAIN (caller classes in the TSV): KernelWidenedFields, KernelSnippets, KernelNeoConditions, KernelConditionSources, KernelCreativeTabOrder, KernelFabricFuel, KernelNeoTooltips, KernelModListScreen, KernelParticleProviders, KernelNeoWorldgen, KernelServerReloadNames, KernelCompostables, KernelSpawnPosition, KernelClientReloadNames, KernelForgePipRenderers, KernelAirRefill, KernelBiomeView, KernelFluidTypes, KernelPacketContext, PairedTransactions, KernelLootBridge, KernelFeatureFlags, KernelKeyMappingMap, KernelLootModifiers, KernelSharedPredicateScopes, KernelFabricConditions, KernelPayloadCodecs, KernelFabricHopperStorage, KernelFabricFluidModels, KernelServerDuties, KernelRecipeSync, KernelFeaturesPerStep, KernelLivingDamage, KernelHudContextQuery, KernelModelFormats, BlockTransferBridge, KernelBlockSoundQuery, KernelCompatibilityPrompts.

### Red checks (mutation in a scratch APFS clone, `red/*.log`)

| mutation | red | everything else in the run |
|---|---|---|
| `decoding` names nothing | aRecipeIsJudgedByThePackItWasParsedFromNotTheLastOneOpened | green |
| repair's `decoding` insertion removed | recipesTwoPassScanNamesEachElementsOwnPack | green |
| `primary()` not applied | aCompositePackIsJudgedByItsPrimary | green |
| RUNTIME row names a caller the chain does not call | aRuntimeCallerIsItselfLive | green |
| `clearBlockStateIds` call removed | theClientWindowEmptiesTheBlockStateMapRightBeforeItsFreeze | green |
| modifyAttributes through invokeGameDataOn again | theAttributeEventsAndTheOtherStaticHooksReportAtTheirOwnCatchSites | green |

Restored: all green. Probe boots (`boot.sh`, `run-boots*.sh`): see A-1/A-2.

| boot (dedicated server, fabric-api + probe-a + probe-b + world pack) | recipes | Fabric files kept | world files skipped |
|---|---|---|---|
| tip (`boot-fix.log`) | 1587 | 6/6 (recipe, loot, predicate; composite recipe, loot, overlay loot) | 3/3, NeoForge's own error, predicate "Can't find element" |
| tip, `fabricPacksOnly=off` (`boot-fix-off.log`) | 1588 | 6/6 | 0/3 (all kept) |
| `f333f1f6` (`boot-prefix.log`) | 1585 | 2/6 (probe-a's loot table and predicate; both recipes and the composite's two loot tables skipped) | 3/3 |

### Suite and gates after the fixes

Full suite at `fee13b0f` (the code tip; `3c6a947a` only adds ci-unstaged rows), pinned build, plain `--offline`
(`c5/s/w2r/suite-final.log`, XML `suite-final-xml/`): **5789 tests / 0 skipped / 5 failed**, exactly the 5 names of
`c5/failures-wave1.txt`, first 240 message characters equal to the pinned XML (`c5/pinned/xml`); 0 new, 0 gone. +7 tests:
KernelConditionKillSwitchesTest +3, MergedBaseForeignConditionsTest +1, RuntimeSeamLivenessStagedTest +1,
KernelLifecycleRegistrationWindowTest +2. `c5/failures-s-w2r.txt`.

Gates on the genuine stage (lane env, at `fee13b0f`; no SKIP line in any):

| gate | PASS / FAIL | why |
|---|---|---|
| m21 | 58 / 0 | D69 (dual_forge native) |
| m8 | 20 / 0 | pack.mcmeta overlays (not the Fabric data-file path, see A-3) |
| m10 | 24 / 0 | real NeoForge packs (Tectonic, lithostitched-neoforge) under the narrowed leniency (B-2) |
| m26 (client lock) | 45 / 0 | A-4: NeoForge's probe block `id=32366` on the client, = the dedicated server's; late Fabric block `id=32368` (were 64733 / 64735) |
| m12 (client lock, `--exclusive`) | 32 / 0 | A-4: a client joining a dedicated server with the ids now equal |
| m42 / m43 (stage-tip files) | 5 / 9, 3 / 2 | the pre-G-B1 files (22 merged-base/MinecraftForge cases, "cannot read LiquidBlock.<init> from the merged base"); G-B1 re-pinned both on its branch (7b0196ee, 3bfad0f6); red whatever the kernel does, not counted |
| m42 / m43, G-B1's files on a merge preview (this tip + `f0cb66b0..922267af`, no file in common) | 9 / 0, 5 / 0 | G-B1's handoff: re-run after S; CAUGHT none |

### Open after the review fixes

- **Orchestrator:** D69's premise ("Terralith, m8's case") is wrong (A-3/B-1 above); the narrowing stands on other evidence.
- **Owner / next kernel lane:** Waystones-fabric 26.2.0.12's main entrypoint fails on the genuine stage ("defined in more
  than one PoI type"), also at `f333f1f6`; not root-caused here.
- **Merger:** re-run m21, m8, m10, m26, m12, G-B1's m42/m43 and m9 after S merges (m9 not run by this lane);
  RuntimeSeamLivenessStagedTest after UPG/SWAP merge (their KernelCarrierPackIds entry needs a chain call or an ELSEWHERE row).
- **G-A:** m26 can pin client id == dedicated-server id (32366) instead of `id >= 1`; the m21/m26 wording items above stand.
- B-10 (seam-off ERROR rate on a live connection) and the client BOOT rows' class-init evidence: not measured.
- `KernelLifecycle.openRegistrationWindow`'s javadoc says NeoForge's unfreeze clear-callback empties the blockstate map;
  `unfreezeData` only calls `unfreeze(true)` (javap) — the wording is not corrected here (the main window's behaviour was not
  re-measured), noted for the kernel lane.

