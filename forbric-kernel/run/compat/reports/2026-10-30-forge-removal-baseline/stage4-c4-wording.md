# Stage 4 C4, lane wording-reports (s4-c4, 3f989f97 and 9debd7b4)

Everything C4 owned that was not the census commit: the furnace-remainder report row, the merged-base wording in
`src/main` and `src/runtime`, the C4 rows of the C3 failure list, and this record. Work notes: `s4/c4/wording-reports/`
(scratchpad); per item `s4/c4/checks.md` "wording-reports".

## Items

| item | verdict | evidence | commit |
|---|---|---|---|
| False SUSPECTED furnace-remainder rows on every install with fabric-api | already DONE by lane census-forge's review fixes; re-checked, nothing to add | Root cause (census-forge-fix B-2): fabric-item-api's `AbstractFurnaceBlockEntityMixin` copies the fuel stack at `consumeFuel`'s HEAD (`@Share`) and redirects `Item.getCraftingRemainder()`; NeoForge's `consumeFuel` asks `ItemStack.getCraftingRemainder()` on the unshrunk argument before `shrink`, so the redirect has no anchor and the judge (FinalMixinApplications / MixinFit) reported a loss nothing loses. The fix is in the judge, not the row text: SupersededMixins' `native-furnace-remainder` entry (claim = the mixin's HEAD-copy + redirect shape, witness = the defined `consumeFuel`). Red before: `SupersededFurnaceRemainderStagedTest` keeps the rows SUSPECTED on the staged class with `shrink` moved first, and regress-gate's `superseded-off` phase must show them. Green in this lane's final suite (3 tests) | d3ab1755 |
| Merged-base wording in log and report strings | DONE: about 140 literals in 63 files; every one left is another meaning of "merge" (17, below) | full suite with the edits applied found 21 test methods pinning old text (15 classes), moved in the same commit; every `check`/`check_absent` pattern in `run/*.sh`, `run/lib.sh`, `run/compat/*.py` read for the old fragments: none pins a changed line; gate-m52's step text follows its hopper line | 3f989f97 |
| Merged-base wording in javadoc and comments | MOSTLY DONE: 635 comment lines in 136 files (comment lines only, checked mechanically) and 2 named test lines; 94 present-tense lines left, listed | `stage4-c4-wording-left.txt` (LEFT 94, HISTORY 196 not proof-read, LEGIT 97) | 9debd7b4 |
| C4 and C4+C5 rows of `c3/failures-c3b.txt` (18 + 6) | 23 of 24 were already resolved by lane census-forge; the 24th, `MergedBaseCalleeSwapTest.everyAdmittedSwapIsEitherRewrittenOrExplained`, has its C4 half (the FORGE skip) done and stays with C5 | its genuine-base admitted swap `ServerPlayer.teleport unsetRemoved -> revive` needs a rewrite-or-explain decision (census-forge handoff "-> C5"); this lane adds no evidence beyond that | — |
| Stage 4 README, C4 part | DONE | README "### C4 (both lanes): outcome, suite at the C4 tip, open items" | record commit |

## How the wording was chosen

- In the guest-mixin adapters "merged" meant "the body as the game has it", opposite "native" (the mod's own platform's
  body). The codebase already calls that "current" (`CurrentBodyOrdinals`, "native/current operand origins"), so
  "the merged body/method/class/call/local" became "the current ..."; "the merged game" became "the game".
- Where the merge was the cause ("the merge re-typed/widened/lost"), the cause on this base is NeoForge's patched game:
  "the base ..." or "NeoForge ...". "merge-added stub" became "carrier-added stub" (the stub is a NeoForge patch's).
- "left X as merged" (a transformer's decline) became "left X as the base has it".
- History stays history ("on the merged base of Forbric 0.4 and earlier ..."); paragraphs about the old merge tool
  (`merge-conflicts.txt`, `MergedBaseBuilder`'s PASS 4b) say "the old merge" or are kept as written.
- Not touched, on purpose: other meanings of "merge" (Mixin merging a handler into its target, `@MixinMerged`, the DupeId
  merge report, class-tweaker merging, merged mappings, overlay merging, frame merges, the `client-merged-pack` fixture
  name); identifiers and log tags (`ForbricMergedBaseCompatTransformer`, `MergedBase*`, `[Forbric/MergedBaseCompat]`,
  transformer names `forbric-merged-base-*`, the census format word `merged-only`) — renamed in Stage 6 or never.
  "Forge-family" wording and "froze the registries NeoForge-first" kept (D32, Stage 6 rule).

## Facts checked while rewording (javap on `.stage4/run/neoforge-patched/patched-mc-neoforge-26.2.jar`)

- `SimpleJsonResourceReloadListener` has one `lambda$scanDirectory$0(Identifier,Identifier,Map,Optional)`; an empty
  Optional is what it skips. KernelFabricConditions' warning no longer says "the merge left two" lambdas.
- `ParticleResources` declares one `providers` (NeoForge's `Map<Identifier,?>`); vanilla's int-keyed one is
  VanillaMemberTwinInjector's twin, which CMT's `giveTheVanillaParticleMapAViewOfTheLiveOne` then wires.
- `GuiRenderer` has no vanilla `pictureInPictureRenderers`; the PiP bridge and KernelForgePipRenderers fill the twin
  from renderers guest mixins add to the constructor's list (described so now, not as "MinecraftForge's event").
- `KeyMapping` keeps vanilla's `ALL` and NeoForge's `KeyMappingLookup MAP`, no vanilla-typed MAP.
- `BlockState` implements only `IBlockStateExtension` (CMT's two-defaults paragraph now reads as history).

## Suite at the C4 tip (9debd7b4)

`.stage4`, `-Pforbric.requireFixtures=all`, `cleanTest test --continue`: **5,762 tests, 0 skipped, 42 failed**, name for
name identical to the census lane's review-fix tip 38bed6fb (0 new, 0 gone); against the C3 tip's 65: 0 new, 23 gone,
42 kept with the same message prefix. List with cause and owner: `stage4-c4-wording-open-failures.txt` (C5 35, C5 tool 1,
P1 6). An intermediate run (log strings changed, pins not yet) failed 63: the same 42 plus 21 pins, all moved.

## Gates

m1, m46, m52 GREEN; m9 165 PASS / 21 FAIL, the same 21 lines as the census lane's (C5); at 9debd7b4, clean tree, ports 46420-46424, m9 on an APFS clone of `client-merged-pack`. Logs: `s4/c4/wording-reports/gates/`. The m9 boot log has no merged-base wording left; its one "merged" line is Mixin merging a private method.

## Open, with owner

- C5: `MergedBaseCalleeSwapTest` (above); ~80 test-tree messages and javadoc still say "staged merged base" (fixture
  reasons such as `FixturePolicyExtensionTest`'s pinned `"[fixture:staged] merged base"` are a test-harness string; re-pin
  with the test rewrites); gate prose and labels that still say "merged" (e.g. gate-m1 "kernel loaded merged base",
  gate-m10's banner, gate-m45's PartEntity label, comments in m9/m29/m35/m47) — wording only, C5's gate rewrites.
- Stage 6 (naming): the 94 LEFT comment lines in `stage4-c4-wording-left.txt`; class, field and parameter names
  (`ForbricMergedBaseCompatTransformer`, `MergedBaseUncalledMethods`, `MergedBaseFieldDrift`, `MergedBaseAnonymousDrift`,
  `KernelForgePipRenderers`, `LocalRanges.mergedGameClass`, the `merged`/`mergedHost` locals and parameters); the log tag
  `[Forbric/MergedBaseCompat]` (gates grep it).
- Owner question (found, not changed, not wording): KernelModelFormats' optional-loader arm existed because the merged
  cuboid deserializer's MinecraftForge half read a `"loader"` object as a string; whether NeoForge's own deserializer
  needs it is unmeasured (possible C3-style deletion candidate). KernelClientSmoke still drills "a traditional-Forge
  mod's listener" (prose kept; the drill's purpose on NeoForge's base is C5's call). VanillaDamageReadInjector's javadoc
  still describes "MinecraftForge's flow" (the census lane already handed this on with VanillaDamageReadInjectorTest).
- The 196 HISTORY lines were classified by a word test, not read one by one; a few may still be present tense.
