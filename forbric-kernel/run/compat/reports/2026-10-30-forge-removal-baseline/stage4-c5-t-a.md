# Stage 4 C5, lane T-A: mixin / client re-pins on NeoForge's own base, with review A/B fixes and wave 2

Lane T-A of the C5 plan (`stage4-notes/c5/PLAN.md` §2 "Lane T-A", as amended by D47, D58, D59, D60 and the critic's
F10/F13/F14), on branch `s4-c5-t-a` from `cd6d0fe8`. Ports 46680-46699 (none used: T-A owns no gate and changed no gate
file, so no gate was run). Notes and logs: `~/Forbric-backups/stage4-notes/c5/t-a/` (D67), review-fix runs under
`t-a/review-fixes/`.

## Commits

| sha | item | subject |
|---|---|---|
| `1bafd92f` | A screen-draw x17 | Re-pin the screen-draw seam tests on NeoForge's own gateway, ClientHooks.extractScreen |
| `692c5a3a` | B InsertedLambda x2 | Give the inserted-lambda shim's real-mod tests the FABRIC reference a launch on NeoForge's base has, and read fusion's Fabric build in place |
| `1202f189` | G corpus x1 | Pin R3's corpus moves to every jar that carries each handler instead of a per-jar count |
| `73c58288` | H apoli x1 (+D58 positive) | Pin apoli's destroyBlock ordinal on NeoForge's own body ... keep the mechanism's real-mod positive on a copy with one exit added ahead |
| `deffb895` | I RegistryLoader x5 (D60) | Re-derive the RegistryLoader companion cases on NeoForge's own loader |
| `0115a3f7` | review A-2 | Compare the bare-name companion's bytes before and after in RegistryLoaderSelectorMappingTest |
| `5c7f8cc6` | found in review-fix measurement | Read RegistryLoaderCompanionBindingTest's native references on first use, so without the staged base its cases skip instead of failing |
| `2846a907` | review A-1 | Keep the RegistryLoader adapter's dead-loader refusals tested on a constructed class |
| `bd0096a0` | review A-5 / B-6a | Rename three RegistryLoader cases that still said "the merged class" and reword MixinTwinRebindTest's javadoc |
| `0d205ae2` | review A-3 | Pin which operand fills the carried partial-tick local in FabricClientMixinAnchorsTest |

Only test sources and the two skip-baseline TSVs change; no kernel code, no gate, no build.gradle.
`compileJava compileRuntimeJava compileTestJava` was run (`--console=plain`, `:compileTestJava` executed) at every one
of the ten commits: all green (`t-a/review-fixes/compile-at-<sha>.log`).

## The harness this lane ran on (not the pinned one)

`props.txt` passes `--offline`, and `~/.gradle/caches/modules-2` was recreated on 2026-11-08 without Forbric's
dependencies (walls.md, T-A). Every number below comes from Gradle with an init script that resolves the other pins from
a `file://` repo of local jars (no download) and **substitutes junit 5.11.4 / platform 1.11.4 and jetbrains annotations
26.0.2** for the pinned 5.9.2 / 1.9.2 / 23.0.0, which are on no local disk (`t-a/review-fixes/offline-local.init.gradle`,
copied from review-s1-A). Calibration (review B-1): the same harness on an APFS clone of the lane tree reset to
`cd6d0fe8` gives **5767 tests, 0 skipped, 41 failed, exactly the 41 names of `c4/failures-close.txt`**, and for all 41
the first 150 message characters match (`t-a/review-fixes/base-cd6d0fe8/`).

## Suite at the tip

`heavy.sh ./gradlew --no-daemon -I offline-local.init.gradle $(cat .stage4/props.txt) -Pforbric.requireFixtures=all
cleanTest test --continue` in `s4c5-t-a/forbric-kernel` at `0d205ae2`, clean tree: **5769 tests, 0 skipped, 15 failed**
(`t-a/review-fixes/tip-0d205ae2/`, list in `c5/t-a/failures-t-a.txt`). Against the base run, through T-A's map (4 renames,
2 deleted merged cases rebuilt as constructed cases): **0 new, 26 gone, 15 kept**, every kept row's first 240 message
characters equal. The 26 gone are all T-A rows (A x17, B x2, G x1, H x1, I x5) and every one passes now. Test count
5767 -> 5769 (-2 deleted, +3 constructed, +1 apoli positive). Kept: LB x3 (below) and 12 rows of other lanes
(featuresPerStep x4, CalleeSwap, FrameRecomputer x4, SkipMarker, SharedFinalSourceActual x2). PLAN's T-A heading says "31 of 41";
its listed items add up to 29 (17 + 3 + 2 + 1 + 1 + 5): 26 resolved, LB x3 kept (D58).

## Items (PLAN T-A rows)

- **A, screen draw x17** (`FabricClientMixinAnchorsTest` x4, `MixinOperationSeamSpellingTest` x12, `OperationCallGraphTest`
  x1): re-pinned on `ClientHooks.extractScreen`. Genuine `Gui.extractRenderState` passes `getGameTimeDeltaTicks` (bci 244)
  to `ClientHooks.extractScreen` (bci 249); vanilla passes the same call straight to the draw; `extractScreen` hands the top
  screen to its second `extractScreenInternal` (ordinal 1) with the partial tick unchanged. Row 2 keeps its refusal on a
  copy of NeoForge's Gui rewritten to pass `getRealtimeDeltaTicks` (expected 0) and gains the genuine positive (expected 1),
  whose wiring is now pinned too: the carried local is fed slot 6, the gateway's float operand (A-3, `0d205ae2`).
- **LB x3 (P1, D58 Stage-0 fixture reds).** One-line causes:
  - `FabricClientMixinAnchorsTest.releasedLiquidBounceDrawsItsMenuWithTheCanvasTheGatewayDraws`: `../build/lb-vfp/jars/liquidbounce-9889fe2.jar` absent (lb-vfp holds only ViaFabricPlus-5.0.2.jar); pinned `GATEWAY_DRAW` is NeoForge's gateway, so a returned jar is read against the genuine shape.
  - `FabricClientMixinAnchorsTest.releasedLiquidBounceBuildDrawsItsMenuWithTheCanvasTheGatewayDraws`: `../build/lb-vfp/jars/liquidbounce-0.40.1+26.2-20260925.jar` absent; same shared constant.
  - `MixinTwinRebindTest.releasedLiquidBounceMovesItsFaceTestAndNothingElse`: the same 0.40.1 jar absent. It does not use
    `GATEWAY_DRAW` and needs no re-pin: NeoForge's own `ModelBlockRenderer` declares both `shouldRenderFace` overloads,
    its five-argument one first and called by `tesselateFlat` and `tesselateAmbientOcclusion`, vanilla's four-argument one
    called by nothing (javap, `t-a/review-fixes/javap-ModelBlockRenderer.txt`), which is the shape the test's synthetic
    cases build. Not run on the genuine base: the jar is absent.
- **B, InsertedLambda x2** (D59 amended): the FABRIC native reference a launch has (the genuine base embeds no
  native-reference index) plus `noteEcosystem(FABRIC)`; fusion's Fabric build read in place,
  `build/sweep80-mac/v020-rounds/r3/mods/fusion-1.3.15a-fabric-mc26.2.jar`, sha1 `e00012f22e80e609838fdd7c9d09f20db7f34d1b`
  (its `SpriteResourceLoaderMixin.class` is byte-identical to the Forge build's, review A). Both pass at the tip (see the
  suite), so the shim has no Fabric defect behind these rows and PLAN's "if still red" branch does not apply.
- **G, corpus x1**: the 19-move `EXPECTED` set kept; the per-jar count replaced by "every jar that carries a pinned handler
  moves it, and only those". At the tip: "691 jars (1237 units), 11598 listed mixins: 44 moves in jars, 19 distinct; on the
  bytes alone 28 distinct". The REFUSED control on `lambda$bootstrap$24` was dropped: it is instruction-identical in vanilla
  and NeoForge, so only the merged base could show it. Red control reproduced (review B-2): see "Red controls".
- **H, apoli x1** (`73c58288`): NeoForge's `destroyBlock` has vanilla's five returns with both booleans in scope at return
  4 (javap, review A/B), so the adapter leaves apoli as compiled (0, bytes unchanged). D58's synthetic positive: a copy of
  NeoForge's body with one veto exit ahead of vanilla's moves ordinal 4 to the last return and counts it once; the switch
  case runs on that same body.
- **I, RegistryLoader x5** (`deffb895`, D60): `:78` fixed, `:97`/`:138` (the merged class's dead-loader copies) deleted,
  `:143` and `RegistryLoaderSelectorMappingTest:67` re-derived. **D60's method:** D60 says "from the mixin's own handler
  descriptors against NeoForge's two live load overloads". The companion is a `CallbackInfoReturnable`-only `@Inject`
  (and in SelectorMapping a one-argument `@ModifyArg`), whose descriptor cannot tell the overloads apart, so the answer is
  derived from what the bare selector binds in each class plus who calls `supplyAsync` (javap,
  `t-a/review-fixes/javap-RegistryDataLoader.txt`): NeoForge declares, in order, `load(ResourceManager,List,List,Executor)`,
  `load(ResourceManager,List,List,Executor,List<PendingTags>)`, `load(Map,ResourceProvider,List,List,Executor)` and the
  private `load(LoaderFactory,List,List,Executor,boolean)` (three public overloads, not two, plus the widened private one);
  vanilla declares `load(ResourceManager,...)`, `load(Map,...)` and the private `load(LoaderFactory,List,List,Executor)`.
  The first `load` is the resource-manager entry in both, and only the private loader calls `supplyAsync` in both. So a
  bare `load` companion binds the same entry natively and on NeoForge: "the wrap moves, the companion stays as written".
  The adapter does exactly that, so there is **no O4 question** and no adapter change. The tests assert both bindings from
  the real jars as premises.

## Review fixes (lens A and B, 2026-11-09)

| finding | outcome | sha / evidence |
|---|---|---|
| A-1 adapter refusals untested | fixed: three constructed cases (NeoForge's loader + vanilla's private loader and its lambda, code and all) | `2846a907`; mutation below |
| A-2 SelectorMapping checks only `method` | fixed: bytes of the companion (annotations + body) before/after | `0115a3f7` |
| A-3 partial-tick positive pins only a count | fixed: `handedToTheBridge == [0,8,9,10,6]` | `0d205ae2` |
| A-4 claims/TSV rows unmeasured | fixed: suite at base and tip, both CI profiles measured for the touched classes (below) | logs in `t-a/review-fixes/` |
| A-5 merged wording in own files | fixed for the three RegistryLoader test names (+ their ci-unstaged rows) and MixinTwinRebindTest's javadoc; `MERGED` constants and `Draw.merged()` left for Stage 6 (PLAN §8 "identifiers"); `"in the merged method"` display names left (9 ci-unstaged rows, reason rewording is T-B phase 2) | `bd0096a0` |
| B-1 first act not the D66 baseline | fixed: baseline on a clone at `cd6d0fe8` = exactly the 41 names; tip compared through the rename/delete map below | `base-cd6d0fe8/`, `tip-0d205ae2/` |
| B-2 commit claims without logs; per-commit compile | re-measured: B x2 and G x1 pass at the tip; corpus mutation reproduced; compile green at all ten commits | below |
| B-3 notes / resume patch | fixed: `c5/t-a/resume-uncommitted.patch` saved before any edit; handoff.md, checks.md, walls.md sections written | notes |
| B-4 TSV rows by inference | fixed: rows for every new/renamed test are the ones a local profile run produced (below) | `skips-actual-ci-*.tsv` |
| B-5 noforge TSV stale row | handed to C6 (below); file untouched (PLAN §3) | — |
| B-6 (a)-(d) plan duties | (a) as A-5; (b) red-control table below; (c) LB causes above; (d) D60 method above | — |
| B-7 harness remedy | confirmed: the pinned junit 5.9.2 / annotations 23.0.0 are on no local disk, so only a substituted classpath runs offline; the real fix needs a one-time online resolve (orchestrator/owner) | walls.md |

**A-1 detail.** Mutation on an APFS clone (`t-a/review-fixes/m3-mutant.log`): with `deadCallees.contains(bound)`,
`onlyDeadReach(...)` (`FabricRegistryLoaderMixinAdapter.java:128`) and `matched.size()>1&&selector.single()` (`:140`)
removed, `aCompanionAClassWouldBindToTheDeadLoadersLambdaLeavesTheMixinAlone` and
`withoutTheNativeClassABareNameTheDeadLoaderSharesLeavesTheMixinAlone` go red (20 cases, 2 failed). With only
`deadCallees.contains(bound)` removed (`m3b-mutant.log`) all 20 stay green: on this class a companion bound to the dead
loader is always also bound to "another method" than the one it was written for (the dead loader is neither the written
entry nor a widening of it by the carrier's appended boolean), so the first clause already refuses it. That clause was
equally redundant on the merged base (the deleted merged case took the same first clause). It is left for an owner/S2
decision (C3-style deletion or a constructed shape that reaches it); T-A does not delete adapter code.

**Found while measuring (new, `5c7f8cc6`).** `NativeCallTestEvidence.staged()` (since `7b29c893`) reads the staged base's
launch sources and aborts when the base is absent. `RegistryLoaderCompanionBindingTest` called it from a static field
initializer, so without a staged base every case FAILED (ExceptionInInitializerError / NoClassDefFoundError) instead of
skipping: the ci-unstaged job would go red on it. Made lazy; measured before/after (10 failed -> 10 skipped).
**Not T-A's files, same static field, same failure expected in the unstaged profile, not measured:**
`MergedBaseGateSpellingTest`, `AdapterPointSpellingTest`, `FabricFreezeHookSpellingTest` -> orchestrator (owner to
assign; the C5-close CI run would show them).

## Red controls

| control | state | recipe / red line |
|---|---|---|
| constructed partial-tick difference (expected 0) | in the test (`aLocalWhoseOperandTheCarrierComputesDifferentlyIsRefused`) | review A M1: removing `carrierTick.name="getRealtimeDeltaTicks"` turns it red at "the carrier's partial tick is not the source's ==> expected: <0> but was: <1>" |
| corpus structural pin on a mutated handler | reproduced (not committed as a test) | in `MixinRetargetRenamedBodyCorpusTest`'s unit loop, before `noteEcosystem`, set `HudMixin.wrapAirBar`'s `method` to `["tick"]` for the unit starting `fabric-api-0.155.2`: prints `MUTATION M2 fabric-api-0.155.2+26.2.jar!fabric-rendering-v1-25.3.1+6988455e9e.jar was [extractPlayerHealth]`, "43 moves in jars, 19 distinct", and fails at "every jar that carries a pinned handler moves it, and only those" (`t-a/review-fixes/m2-corpus-mutant.log`) |
| RegistryLoader dead-loader refusals | in the tests (`2846a907`) | mutation M3 above |
| weave scenarios red with switch `=off` | done in wave 2 | "Wave 2" below |

## TSV rows (D47), measured

Two local runs of the five touched classes on an APFS clone (`t-a/review-fixes/profile-*.log`):
- **ci-unstaged shape**: no staged root (`-Pforbric.stagedRoot=<clone>/forbric-loader/run` with its links removed), no
  Minecraft libraries, no FORBRIC_OLD/MC_DIR: 44 tests, 32 skipped, 0 failed. Against the committed `ci-unstaged.tsv`
  for those classes: 0 new, 0 stale (after adding the three A-1 rows).
- **ci-prepared shape**: the genuine stage props, `requireFixtures=staged,game-side,mc-libraries,java-25`, every
  third-party fixture link removed: 44 tests, 7 skipped, 0 failed; equal to the committed `ci-prepared.tsv` rows for those
  classes (0 new, 0 stale).
Rows added: H's two names in both TSVs; I's two re-derived names in ci-unstaged; A-1's three constructed cases and A-5's
three renames in ci-unstaged, with the reason the run produced. Rows dropped by exact name: the old apoli name (both),
the two deleted merged cases, the old names of the renamed/re-derived cases (ci-unstaged). Sibling rows' reasons are left
for T-B phase 2. `ci-prepared-noforge.tsv:681` still lists `MixinReturnOrdinalsTest.apolisLastReturnOfDestroyBlockIsTheMergedLastReturn()`
and lacks the two new names: **C6** (PLAN §3: that file is untouched in C5).

## Not done in wave 1 (all closed in wave 2, below)

- Weave scenarios for `FabricItemDamageMixinAdapter`, `MixinFluidReactionAdapter.neighbourChangeOnly`, ShearsRelay
  `MOB_ROWS`, each with a switch-off red control.
- `WeaveCoverageCensusTest`: `FabricCommonPayloadMixinAdapter` and `MixinPrivateDefaultCollision` still claimed WOVEN with
  only plain unit tests.
- `MixinSharedResultTransportTest`: the two vacuous rows (D34 spirit).

## Wave 2 (2026-11-09): the items wave 1 left open

Branch `s4-c5-t-a` rebased onto the stage tip `f0cb66b0` (fast-forward: the branch was an ancestor; wave 1's commits are
the stage's `a4862df1` and below). Harness per D68: the restored Gradle cache, pinned versions, plain `--offline` from
`props.txt`; no init script, no private GRADLE_USER_HOME. Ports 46680-46699 unused: T-A owns no gate (PLAN §2, §9), so no
gate was run. Notes and logs: `~/Forbric-backups/stage4-notes/c5/t-a/w2/`.

### Commits

| sha | item | subject (shortened) |
|---|---|---|
| `2d6e8d77` | owed weave scenarios: FabricItemDamage, ShearsRelay MOB_ROWS | Weave fabric-item-api's hookDamage and BCLib's sheep and snow golem shears wraps through the real Mixin |
| `f1f32d6a` | census WOVEN claims | Give FabricCommonPayloadMixinAdapter and MixinPrivateDefaultCollision the real-weave scenarios the census claimed |
| `aa551185` | static `staged()` fields | Read the staged native references of the three spelling tests on first use |
| `9b96c4cc` | MixinSharedResultTransportTest vacuity | Check the unperturbed crafting group is transported before the three remaining rejection tests |
| (the commit that adds this section) | this record | Record lane T-A's C5 wave 2 |

Only test sources, weave fixtures and this record change: no kernel code, gate, build.gradle, TestFixtures or TSV.
`compileJava compileRuntimeJava compileTestJava` green on the whole wave-2 edit set before the first commit
(`w2/compile2.log`; that tree is the tip's) and at the tip (the suite below compiles it); the earlier commits hold subsets of
it whose new test files nothing else references.

### First act

At `f0cb66b0` (an APFS clone of the lane worktree with the lane's uncommitted wave-2 edits reverted, so the run saw the
rebased tip exactly; the queued run in the worktree was stopped before it took a slot, because by then the worktree
held edits): `heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all cleanTest test
--continue` -> **5772 tests, 0 skipped, 5 failed**, name for name the 5 rows of `c5/failures-wave1.txt` (0 new, 0 gone;
`w2/suite-first/`).

### Items

- **Weave scenario, FabricItemDamageMixinAdapter** (`2d6e8d77`, `FabricItemDamageMixinAdapterWeaveTest`, fixture
  `weave/itemdamage/`). fabric-item-api-v1 14.3.0's `hookDamage` in shape (javap of the jar in fabric-api 0.155.2: the
  call's operands, the `Operation`, then `@Local(argsOnly = true)` entity and slot) on an `ItemStack` whose
  `hurtAndBreak(int, LivingEntity, EquipmentSlot)` calls NeoForge's `hurtAndBreak(int, ServerLevel, LivingEntity, Consumer)`
  with the entity (javap of the genuine base, bci 33) and whose `ServerPlayer` overload delegates there.
  Probe (an everlasting tool held by a mob and by a player, a plain tool held by a mob), as printed by the two child runs:
  moved `mob=handler:1,damage:0,base:zombie player=handler:1,damage:0,base:steve plain=handler:0,damage:1,base:zombie`;
  `-Dforbric.itemDamageHandler=off` `mob=handler:0,damage:1,... player=handler:0,damage:1,...` and one unbound
  `#hookDamage(` finding. `base:zombie` in the moved run shows `KernelItemDamage.relay` hands NeoForge's call the entity
  the handler was given null for.
- **Weave scenario, MixinShearsRelay MOB_ROWS** (`2d6e8d77`, `MixinShearsRelayMobsWeaveTest`, fixture
  `weave/shearsrelaymobs/`). BCLib 26.201.2's `SheepMixin` / `SnowGolemMixin` in shape (javap: `@WrapOperation(method =
  "mobInteract", at = ItemStack.is(Object))`) on a sheep and a snow golem whose `mobInteract` reads the held stack and
  asks nothing (javap of the genuine base: `getItemInHand; invokespecial Animal.mobInteract` / `getstatic PASS`), plus a
  `ShearsItem` whose entity entry tests `IShearable` (the premise `MixinShearsRelay.shearsEntry` reads). The probe uses
  each item in `Player.interactOn`'s order and names who sheared. Relayed: `sheep: shears=item tagged=mob stick=pass
  golem: shears=item tagged=mob stick=pass`; `-Dforbric.shearsRelayMobs=off`: `tagged=pass` on both, two unbound
  `#bclib_isShears(` findings.
- **neighbourChangeOnly** (no commit needed): the c3 critic's item (`c3/critic.md:293`, written at `a3791619`) was met
  after it was written: `6c3e96f6` (TAIL and reaction point: `CarpetFluidMixinAdapterWeaveTest`, `MixinFluidReactionWeaveTest`,
  both with `-Dforbric.fluidReactionNeighbourChange=off`) and `8462a705` (HEAD: `FluidPairMixinAdaptersWeaveTest` with
  `...NeighbourChange.head=off`), both descendants of `a3791619`, on fixtures with the genuine `LiquidBlock` shape
  (`onPlace` asks `shouldSpreadLiquid`, `neighborChanged` asks `FluidInteractionRegistry.canInteract`). Proven by a
  mutation instead of a new test: M-nc (`neighbourChangeOnly` returns 0 at entry) on an APFS clone turns **8 of the 12**
  cases of the three classes red (every wrap-side case and every `theControlFlips…`; the four off-side or oracle cases stay
  green), restored 12/12 green (`w2/mutation-nc/`, `w2/mutation-nc-restored/`).
- **WeaveCoverageCensusTest WOVEN claims** (`f1f32d6a`). The census said `FabricCommonPayloadMixinAdapter` and
  `MixinPrivateDefaultCollision` were WOVEN on `FabricCommonPayloadMixinAdapterTest` / `MixinPrivateDefaultCollisionTest`,
  which only read ClassNodes. Both now have a real-weave scenario, so the claim is true rather than moved to NOT_WOVEN_YET:
  - `FabricCommonPayloadMixinAdapterWeaveTest` (fixture `weave/commonpayload/`): fabric-networking-api's
    `handleCustomPayloadReceivedAsync` in shape (javap, fabric-api 0.155.2: one `instanceof ServerConfigurationNetworkAddon`,
    else `new IllegalStateException("Unknown addon")`) with a play listener whose override calls super, as NeoForge's
    does. Adapted: `configuration.fabric=fabric configuration.neoforge=neoforge play.neoforge=neoforge`;
    `-Dforbric.playPayloadFallThrough=off`: `play.neoforge=disconnect(Unknown addon)`, the other two unchanged.
  - `MixinPrivateDefaultCollisionWeaveTest` (fixture `weave/privatedefault/`): fabric-entity-events' private
    `LivingEntity self()` on a `LivingEntity` implementing an `ILivingEntityExtension` that defaults `self()`. Renamed:
    `tick=fabric:axolotl neoforge=neoforge:axolotl`; `-Dforbric.mixinPrivateDefaults=off`: `neoforge=AbstractMethodError`
    (the guest handler runs either way).
  `MixinShearsRelay`'s row now lists both of its switches, and `FabricItemDamageMixinAdapter` moved from NOT_WOVEN_YET to
  WOVEN, so NOT_WOVEN_YET is empty. Census line at the tip: `weave coverage: config-time 1/1, getClassNode 81/81, post-Mixin 6/6`.
- **MixinSharedResultTransportTest, the 2 vacuous rows** (`9b96c4cc`). The two rows c3's review named (A3/B6: the
  furnace-fed `usedOldInput…` and `ambiguousResultCall…`) were already re-pinned in C3 by `54a4bce4` (an ancestor of the
  critic tip, so map-misc row 85 was stale). Measured rather than assumed: mutation M-tr (`MixinSharedResultTransport.adapt`
  returns 0) on the genuine stage leaves **4 of 8 green** with the test file at `f0cb66b0`: the furnace pin and three more
  rejection tests (`aProducerSharingAnotherValueIsNotAPassThrough`, `transformedProducerReceiverOrAdditionalGroupSideEffectIsNotAccepted`,
  `closedGroupRequiresTheSameStackSourceAndNoPathBypassingItsProducer`), which assert only `adapt == 0` after a
  perturbation. Each now first asserts the unperturbed crafting group is transported (`adapt == 2`). With the new file
  M-tr leaves **1 of 8 green**: `theFurnaceSnapshotHasNoRegionToMoveToOnNeoForgesBase`, which pins that nothing moves on
  NeoForge's furnace (its premise, `ee1aed96`; it goes red only if that group were transported, so it is not vacuous for
  what it states). Restored: 8/8 (`w2/mutation-tr-*/`). Nothing deleted.
- **Static `NativeCallTestEvidence.staged()` fields** (`aa551185`). Measured in a ci-unstaged-shaped run (APFS clone with
  no staged root, no Minecraft libraries, no `.dev/api`, every fixture link removed; `-Pforbric.stagedRoot=<clone>/forbric-loader/run
  -Pforbric.mcLibraries=/nonexistent/...`): before, `MergedBaseGateSpellingTest` 7/7, `AdapterPointSpellingTest` 6/6
  (container `initializationError`) and `FabricFreezeHookSpellingTest` 8/8 **failed** (`ExceptionInInitializerError`, then
  `NoClassDefFoundError: Could not initialize class …`), with `RegistryLoaderCompanionBindingTest` 10/10 skipped as the
  control. After the lazy accessor (same shape as wave 1's `5c7f8cc6`): 7/7, 293/293 and 8/8 skipped, 0 failed; the skipped
  names equal `ci-unstaged.tsv`'s rows for each class exactly (7, 274 distinct, 8: 0 missing, 0 extra), so **no TSV change**.
  The same run shows the four new weave tests, the census and the transport test in that shape: the weave tests and the
  census run and pass (0 skipped), the transport test skips 8/8, equal to its 8 TSV rows (`w2/unstaged-before/`,
  `w2/unstaged-after/`). The new tests need no fixture of any kind, so they add no row to either TSV (D47).

### Red controls (wave 2)

| control | result |
|---|---|
| FabricItemDamage weave, `-Dforbric.itemDamageHandler=off` | handler 0 / damage 1 for mob and player, one unbound finding; the moved run's predicate is false on it (`theControlFlipsEveryMoveAssertion`) |
| ShearsRelay MOB_ROWS weave, `-Dforbric.shearsRelayMobs=off` | `tagged=pass` on sheep and golem, two unbound findings; `theControlFlipsEveryRelayAssertion` |
| CommonPayload weave, `-Dforbric.playPayloadFallThrough=off` | `play.neoforge=disconnect(Unknown addon)`; `theControlFlipsEveryAssertion` |
| PrivateDefault weave, `-Dforbric.mixinPrivateDefaults=off` | `neoforge=AbstractMethodError`, woven `LivingEntity` declares `self()`; `theControlFlipsEveryAssertion` |
| neighbourChangeOnly, mutation M-nc | 8/12 red, restored 12/12 |
| transport vacuity, mutation M-tr | before 4/8 green, after 1/8 green (the furnace premise), restored 8/8 |
| lazy staged fields, ci-unstaged shape | before 21 failed, after 0 failed / 308 skipped = TSV |

### Suite at the wave-2 tip

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all cleanTest test --continue` in
`s4c5-t-a/forbric-kernel` at `9b96c4cc`, clean tree, pinned build (D68): **5784 tests, 0 skipped, 5 failed**
(`w2/suite-final/`). Against `c5/failures-wave1.txt`: **0 new, 0 gone, 5 kept** (LB x3, SharedFinalSourceActualTest x2:
the Stage-0 fixture reds D58 keeps). 5772 -> 5784 = the four new weave classes, 3 cases each. The four probe pairs above
and the census line are the ones those child runs printed (stdout in the suite's XML). List: `c5/failures-t-a-w2.txt`.

### Gates

None. T-A owns no gate file (PLAN §2 "Lane T-A", §9 coverage: the 60 gates are G-A/G-B1/G-B2/G-C's) and changed none;
no gate was started, so there are no `[kernel] PASS`/`FAIL` counts to report.

### Open after wave 2

| item | owner |
|---|---|
| LB x3: `../build/lb-vfp/jars/liquidbounce-{9889fe2,0.40.1+26.2-20260925}.jar` absent (D58 Stage-0 fixture reds) | P1 / owner (jars not on disk; not fetched, D68) |
| SharedFinalSourceActualTest x2: opt-in fixture `.stage4/run/shared-final-definitions` absent (D58) | owner |
| `FabricRegistryLoaderMixinAdapter.java:128` clause `deadCallees.contains(bound)` unreachable on NeoForge's class (wave 1, M3b) | owner / S2 |
| `ci-prepared-noforge.tsv:681` stale apoli row (+ wave 1's two names) | C6 |
| `MERGED` constants, `Draw.merged()`, "in the merged method" display names, "the staged merged base" fixture texts in the three spelling tests | Stage 6 / T-B phase 2 |
| Unit tests for KernelFluidReactions / KernelMobShears (c3 critic B5, "need a live Level/registry"): the runtime halves now run inside the weave scenarios (`KernelMobShears` in `MixinShearsRelayMobsWeaveTest`; `KernelFluidReactions` already in `MixinFluidReactionWeaveTest`), not in a unit test of their own | not assigned in C5 (recorded only) |
