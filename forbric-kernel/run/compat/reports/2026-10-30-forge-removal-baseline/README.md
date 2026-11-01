# Baseline for dropping MinecraftForge: 511f2582, 2026-10-30

Every later stage of the MinecraftForge removal is judged against this record: a check may change only if the stage
meant to change it. Each failure below has a cause and the evidence for it. None is filed as "known red".

## What was run

- Tree: `511f2582` (local main), branch `claude/remove-forge-neoforge-fabric-83b024`.
- Staged root: `tools/dev.py integration` found the stage not ready and re-prepared it through DevPrepare (the CI path:
  `nfrt=2.0.18 result=gameJarNoRecomp`, NeoForge 26.2.0.88, Forge 26.2-65.0.1). The gates ran on a worktree-local copy of
  that stage (`.stage/run`: the five artifact directories cloned, every other `forbric-loader/run` entry linked).
- `python3 tools/dev.py integration`: 6,726 tests, 75 failed.
- `bash forbric-kernel/run/compat/gates-all.sh -j auto --skip gate-m34-soak.sh` with `GATE_PORT=38000`:
  56 GREEN, 3 RED (m0, m54, m56), 1 SKIP (m34, on purpose).

## Unit tests: 75 failed, every one a missing third-party fixture

All 75 messages read `[fixture:third-party] … absent — required by -Dforbric.requireFixtures=all` (one is
`[fixture:opt-in]`). Cause: the owner's disk cleanup of 2026-10-23 deleted the root `build/lb-vfp` and
`build/random100-20260929` directories (recorded in the edit-splice notes: "deleted build/ (lb-vfp, random100 third-party
fixtures) … ~73 third-party tests fail locally"). The worktree's links to them dangled. Two more jars are read from
`<staged>/mods`, which the re-prepared stage does not hold.

| tests | count | missing fixture | restored? |
| --- | ---: | --- | --- |
| AdapterPointSpellingTest | 43 | `ViaFabricPlus-5.0.2.jar` (random100 copy) | yes: Modrinth version `dmLiENU0`, sha1 `d63246f6…` matches the version's published hash |
| ThinnedCallOrdinalsSpellingTest, ThinnedCallOrdinalsTest | 20 | `ViaFabricPlus-5.0.2.jar` (lb-vfp copy) | yes, same jar |
| ReplacedCallRedirectsTest | 1 | `ViaFabricPlus-5.0.2.jar` | yes |
| FabricClientMixinAnchorsTest, MixinTwinRebindTest | 3 | `liquidbounce-0.40.1+26.2-20260925.jar`, `liquidbounce-9889fe2.jar` | **no**: LiquidBounce publishes no release assets on GitHub, is not on Modrinth, and its build API answers 404 |
| MixinRetargetRenamedBodyCorpusTest | 1 | the whole `random100-20260929/downloads` corpus | **no**: the 100 random Modrinth picks were never listed anywhere that survived |
| MergedBaseFrameRecomputerTest | 4 | `InventoryProfilesNext-forge-26.2-2.3.7.jar` in `<staged>/mods` | present in `forbric-loader/run/mods`, not in the DevPrepare stage |
| ConfigApiAbiInjectorTest | 1 | `ShoulderSurfing-Fabric-26.2-5.0.11.jar` in `<staged>/mods` | same |
| SharedFinalSourceActualTest | 2 | opt-in `shared-final-definitions` | opt-in by design |

Relevance to this work: the ViaFabricPlus, LiquidBounce and R3-corpus tests all pin how Fabric mixins land on the
**merged** base ("counts on the merged body"), so they are exactly what the base switch changes. ViaFabricPlus is back
and runs. The LiquidBounce cases and the random100 corpus stay lost; Stage 4 must not rely on them, and the LiquidBounce
draw-gateway seam needs another proof there (the plan already lists it as a new gap to measure).

With ViaFabricPlus restored and the corpus directory present again, `gate-m0`'s own unit run shows what is left:
6,737 tests, 1 failed — `MixinRetargetRenamedBodyCorpusTest`: the 19 distinct moves are exactly the pinned 19, but
44 moves in jars instead of 52. The 8 missing instances are copies carried by corpus jars that no longer exist. Cause:
fixture loss, not R3.

## Gates: 3 RED, all environment

| gate | first verdict | cause | evidence | after fixing the cause |
| --- | --- | --- | --- | --- |
| gate-m0 | RED exit 1 | its unit run, i.e. the corpus test above | `build/gate-m0-test.log`: 1 failed of 6,737 | stays RED until the corpus is replaced; Stage 4 re-pins it on the genuine base anyway |
| gate-m54-carry | RED exit 3 (SKIP-FATAL) | `run/carry-on/carryon-neoforge-26.2-2.11.2.jar` exists only in the main checkout; the worktree had no link | log: `M54 needs …/run/carry-on/carryon-neoforge-26.2-2.11.2.jar` | linked: **GREEN** |
| gate-m56-config-screens | RED exit 3 (SKIP-FATAL) | phase 3 needs `run/modmenu/` (Mod Menu 20.0.3), main checkout only | log: `M56 phase 3 needs a Mod Menu jar for 26.2` | linked: **GREEN**, all four phases |

## The 2026-10-30 nightly (origin/main f7f4ff92), for comparison

The nightly tested an older commit on the main checkout's stage. Its reds that do **not** reproduce on 511f2582:

- `KernelBundledMixinExtrasStagedTest` (2): passes here.
- `gate-m12-multiplayer`, `gate-m22-exitswap`: GREEN in this sweep; repeated runs below.
  Earlier evidence (integ3 verify-gates, 2026-10-26, same tree): m12 went GREEN/GREEN/RED and m15 RED/GREEN/RED when run
  beside other gates; every RED was `check_kept_up`, the server not keeping up under parallel load.

## Repeats of m12 and m22 (sequential, alone)

Alone, one at a time: gate-m12 GREEN ×3, gate-m22 GREEN ×2 (plus GREEN in the parallel sweep). Neither fails on
511f2582 by itself. The nightly keeps only summaries, so its f7f4ff92 logs cannot be read back; m12's known failure mode
is the parallel-load `check_kept_up` above. Stage 1 touches the two network anchors m12 exercises, so m12 is re-run
alone (and three times) after the bump, and a RED there is investigated, not attributed to load without its log.

## Stage 1: NeoForge 26.2.0.93 (commits 8bb2f38c, 01a60bee)

Stage rebuilt through DevPrepare at the new pins. Against the .88 stage, by entry CRC: the merged base, the
MinecraftForge runtime, its interop jar and both patched jars are identical; `neoforge-runtime.jar` differs in 18
entries. `patched-mc-neoforge-26.2.jar` is byte-identical (sha1 `67bbf24f…`, NFRT `gameJarNoRecomp`).

- Unit suite: 6,737 tests, 7 failed — the baseline's lost fixtures (LiquidBounce ×3, R3 corpus ×1, opt-in ×2) and
  one pinned expectation (`MixinFitReportManifestTest` used 26.2.0.90 as "newer than the table's game"; now .94).
  The two IPN/ShoulderSurfing staged tests pass, because the worktree stage links `forbric-loader/run/mods`.
- Gates (`-j auto`): 58 GREEN, gate-m0 RED for the same corpus test as the baseline, m34 skipped. gate-m12 alone
  ×3: GREEN. No gate log contains NeoForge's new ad-hoc channel refusal (`neoforge.network.adhoc_channels.too_many`).
- Link check: 7 known, 0 new; provenance re-recorded for this build.
- Native NeoForge 26.2.0.93 control installed from the official installer (upstream SHA-1 verified).
- sponge-mixin 0.17.4 / MixinExtras 0.5.5 tried and reverted: six weave tests in three suites change verdict and
  0.17.4 adds compatibility level 17004; recorded in `Pins.java`.

## Stage 2: preparation on the merged base (lanes merged at 559f0d2a, two fix rounds, tip below)

Six lanes (harness, native-refs, relocate, adapters-a, adapters-b, canary), each reviewed adversarially; the reviews'
findings fixed in two rounds of isolated fix lanes (s2fix/*, s2fix2/*), each reviewed again. What was measured, the
deliberate behaviour changes and the Stage 4 obligations are in `stage2-measurements.md`.

**Unit suite, test by test against Stage 1's tip (b37e6ae0)**, same staged root, `integrationTest`:

| | tests | failed | changed against b37e6ae0 |
| --- | ---: | ---: | --- |
| b37e6ae0 | 6,737 | 6 | — (the six lost-fixture failures above; two KernelBundledMixinExtrasStagedTest failures in the first run were a fresh worktree without the boot jar, passing once it was built) |
| Stage 2 tip | 6,851 | 6 | 116 tests added, 2 renamed; one regression found and fixed (below); the same six failures, no other verdict changed |

The regression: `UncalledMethodCensusTest` asked for five rows to leave `uncalled-methods.txt`, because the census read
`VanillaMemberTwinInjector`'s rows (the members it copies where a base lacks them) as names, which made the twinned
members look live. Regenerating the table would have changed what `MixinExecutionPathRetarget`, `UncalledValueRelay` and
`MixinSplitBodyEdges` do on the merged base; the census now reads that injector as copying, not calling (9ad625f6).

**Gates** (`gates-all.sh -j auto --skip gate-m34-soak.sh`, then the reds again): 58 GREEN, gate-m0 RED for the R3 corpus
test only (as in Stages 0 and 1), m34 skipped. The first sweep had ten reds, every one explained:

| gate(s) | cause | outcome |
| --- | --- | --- |
| m35 m36 m37 m38 m43 m46 m53 m55 | environment: the sweep set `MC_DIR` to the stage's Minecraft tree, which has no sponge-mixin; the canary builders read it from `MC_DIR` | without the override: GREEN |
| m17 | gate defect since the version moved to 0.4.0-dev: it took the first `forbric-kernel-installer-*.jar` in build/libs, the stale 0.3.1-beta2 one, so it (Stage 1 included) tested the old installer; the new sidecar checks caught it | gate reads the version from build.gradle (da558b8a): GREEN |
| m0 | `test_soak` lacked the new `native_reference` argument; the installer link gate's source list lacked `VanillaReferenceTest`; the census above; the R3 corpus | first three fixed (f763deab, 9ad625f6); RED for the corpus only |

gate-m21's `-Dforbric.neoScanData=off` control turns exactly eleven checks red (the header said nine; corrected).

**Skip baselines**: `ci-unstaged.tsv`, `ci-prepared.tsv` and the new `ci-prepared-noforge.tsv` (the prepared suite again
with the MinecraftForge carrier hidden, `-Pforbric.hideForgeCarrier`) are CI's own skips-actual lists, from runs of
branch `ci/remove-forge-stage2` (runs 41012697382 and 41017826904; ci-prepared gained two [fixture:third-party] rows, new tests needing local mod packs CI does not have).

**Owner's decision (2026-10-30)**: gate-m30's dead-event row stays on the synthetic MinecraftForge fixture until Stage 4
deletes it with `DeadEventAudit`, instead of moving to a NeoForge canary as the plan said. No public "last with
MinecraftForge" release: Stage 3 starts from the tag `tri-eco-26.2-final` alone.

## Stage 3: MinecraftForge-only jars are refused (no code deleted; tip 3fb99f8e)

Three lanes (core, sweep, gates), each reviewed adversarially, merged at 29fa47e8; then four integration rounds, each
reviewed again, and two fan-outs that converted the remaining unit tests and gates.

**What a player sees.** A jar whose only usable loader half is MinecraftForge's is refused by name, with the build to
get instead, in the player's language (`DialogLang`, 10 tables): a Lost row, a CONFIRMED required finding, the load
report; a client asks, a dedicated server or a strict launch stops (exit 78), `-Dforbric.compatibilityPolicy=continue`
launches anyway. A Forge build beside the NeoForge or Fabric build of the same mod is only a note. A universal jar keeps
loading through its NeoForge half (preferred) or its Fabric half; the MinecraftForge half is set aside and logged.

**The rule** (`LoaderHalfJudge`, bytes only, the same answer for the boot and the sweep): a jar is refused only when
it has a MinecraftForge half and no usable Fabric or NeoForge half. A NeoForge half whose only `@Mod` is
MinecraftForge's is judged by what NeoForge would run of it — its listed mixins and their config's plugin (configs
found wherever the manifest says, nested jars included, read as leniently as Mixin reads them; an unreadable one counts
as none), else its entry class, else its `data/`; `lowcodefml` exempts only the entry. The arbiter gives a jar with no
initializer in any half to the half that ships its mixin config, unless the NeoForge half runs through an
`@EventBusSubscriber` or a NeoForge service. The review rounds found and fixed: a dummy MinecraftForge `@Mod` getting
a mixin/data mod refused; non-root, nested or commented mixin configs clearing a Forge build unchecked; a Forge-bound
plugin never looked at; `lowcodefml` skipping the mixin check; an uninspectable jar read as "no initializer"; a refused
jar's fake `fabric.mod.json` reaching the classpath with `-Dforbric.duplicateArbitration=off`.

**Sweep** (`stage3-sweep.md`, `stage3-sweep.tsv`): 1,953 distinct local jars, both kernels' own arbitration. 90
refused, every one hand-checked, **0 false refusals**; 9 jars move MinecraftForge → NeoForge (datapack wrappers,
SimpleRPC), 1 MinecraftForge → Fabric (qubes-mouse-fix, the half that ships its mixins; it did nothing as NeoForge).
The refused set did not change across the four rule revisions. Its three open questions are decided there.

**Unit suite** (`integrationTest`, same staged root): 6,890 tests, 6 failed — exactly the six lost-fixture failures of
Stages 0–2 (LiquidBounce ×3, the R3 corpus, the two opt-in shared-final tests). 14 tests that staged MinecraftForge-only
fixtures were converted (7 classes, each reviewed): shared parser/selection behaviour is reached through a universal
jar's Forge half or the NeoForge dialect, and each now also asserts the Forge-only variant is refused; no test removed.

**Gates** (`gates-all.sh -j auto --skip gate-m34-soak.sh`): 58 GREEN, gate-m0 RED for the R3 corpus test only (its
oracle steps pass), m34 skipped — the Stage 2 picture. Every gate that staged a MinecraftForge-only canary was converted
(m4 m4-canary m12 m13 m14 m15 m16 m17 m18 m19 m21 m25 m26 m28 m29 m30 m32 m33 m40 m41 m47 m48 m49 m55 m57, and the m0
oracle), each with
a "STAGE 3" header paragraph saying what went and why. The rule throughout: only checks the refused jar alone could
produce go; every kernel rewrite or carrier-plumbing line that still prints with no MinecraftForge mod aboard stays
asserted (two review rounds restored such checks in m16 m21 m25 m26 m28 m29). New proofs: gate-m29 is the server
refusal (en and zh, strict stop, Lost rows) plus the carrier's capability rewrites; gate-m26 phase 2 the client
question; gate-m14 a Forge+Fabric jar loading as Fabric; gate-m19 a refused parent's nested library taking no part; the
m0 oracle accepts a missing MinecraftForge mod only on the kernel's own refusal of its jar (with a negative control).
Red controls re-measured where touched (m21 `forgeScanData=off` turns exactly the restored index check red).

**Skip baselines**: CI run 41079405021 at 3fb99f8e is green on every job. The three TSVs are CI's lists; three tests
whose skip reason came from `Map.of`/`Set.of` iteration order (random per JVM) now read their fixtures sorted — the
Stage 2 lists had matched by chance.

**Fixtures**: `run/neoforge-builds` (new, in the main checkout) holds the twelve NeoForge builds the gates now stage;
`run/client-popular` in this worktree is a clone with its eighteen MinecraftForge jars moved to `forge-refused/` and
their NeoForge builds in `mods/` (`stage3-fixtures.md`). The main checkout's shared `run/client-popular` is unchanged,
so other branches still see the old pack; switching it is a step for whenever this line lands.

**Decisions taken in Stage 3** (each reversible):
- A refused jar nested in a mod that loads stays on the classpath as a library (its `@Mod`, mixins, AT, subscribers and
  packs are skipped), as native NeoForge treats a nested jar it does not read as a mod; a parent may call into it.
- gate-m30's dead-event row: the owner's keep-until-Stage-4 decision could not hold — Stage 3 refuses its synthetic
  MinecraftForge fixture, so its subscriber never registers and the row cannot run. The row went in Stage 3 (not moved
  to NeoForge, which the owner declined); `DeadEventAudit` keeps its unit tests and goes in Stage 4.
- gate-m33's bridge dirty-notification probe and gate-m40's Forge cell are gone with their Forge machines; both gates
  now run Fabric ↔ NeoForge (m40 keeps a Forge *consumer* reading both cells through the carrier).

**Under a Chinese locale** (`-I lang-zh.gradle --rerun --no-build-cache`; gates with
`FORBRIC_JVM="-Duser.language=zh -Duser.country=CN"`): the suite fails the same six tests and nothing else; the gate
sweep has gate-m0 RED for the R3 corpus only and gate-m14 RED once because its pure Fabric server's installer lost an
HTTPS handshake while downloading (`SSLHandshakeException: Remote host terminated the handshake`, before the game
started); rerun alone under zh it is GREEN. Every other gate GREEN.

## Stage 4: the flip to NeoForge's own patched game, C2 + C1 + C3 (tips 00a5e585, 73a4314c, s4-c3); C4-C6 not done yet

Stage 4 is one stack of branches: `s4-c2` (C2: delete MinecraftForge's code, on the merged base) → `s4-c1` (C1: build,
test and launch on NeoForge's patched 26.2 jar, "G", with the NeoForge 26.2.0.93 carrier and the vanilla-reference
sidecar) → `s4-c3` (C3: delete the repairs that existed only because the base merged Forge and NeoForge patches, each
after three checks). Every lane was reviewed adversarially twice (A, B) and followed by a review-fix round; each phase
ended with a completeness critic. Decisions D1-D39 bound every lane. **C4 (census tables regenerated with
`Ecosystem.FORGE`'s removal), C5 (rebaseline: tests, gates, C5b world upgrade, C5c Forge → NeoForge build swap) and C6
(CI, release, installer clean-up) have not started**; this section records the state they start from.

| phase | commits | tip | diff | details |
|---|---|---|---|---|
| C2 (with P1) | cf0498eb..00a5e585, 69 | 00a5e585 | 651 files, +4,109 / −86,437 | `stage4-c2.md` |
| C1 | 00a5e585..73a4314c, 46 | 73a4314c | 237 files, +4,605 / −6,504 | `stage4-c1.md` |
| C3 round 1 | 73a4314c..a3791619, 81 | a3791619 | 240 files, +7,443 / −13,893 | `stage4-c3-{events-hooks,cmt-structural,mixin-side,runtime-injectors,regress-server,regress-client}.md` |
| C3 round 2 | a3791619..4201ec0c, 26 | 4201ec0c, then this record (docs only) | 94 files, +2,153 / −2,036 | `stage4-c3-gaps.md` and `stage4-c3-{useon-relocation,postmixin-fixups,cni-hedge,fluid-reaction}.md` |

### Unit suite at each tip (`-Pforbric.requireFixtures=all`, task `test`; C2 on the merged stage, C1/C3 on `.stage4`)

| tip | tests | failed | what the failures are |
|---|---:|---:|---|
| 00a5e585 (C2) | 5,857 | 14 | the six lost fixtures of Stages 0-3 + eight tests whose subject is the merged base's own MinecraftForge bytecode, all green after C1 (`stage4-c2.md`) |
| 73a4314c (C1) | 5,845 | 124 | (a) merged/MinecraftForge premise 34, (a)→C4 24, (c) a C3 candidate's subject gone on G 34, (d) genuine-base Fabric loss 26, P1 lost fixture 6 |
| a3791619 (C3 round 1) | 5,749 | 65 | 0 new against C1; 59 C1 rows gone, each attributed to the lane that removed it; for all 65 the first 120 normalised characters of the failure message equal C1's |
| 4201ec0c (C3 round 2) | **5,760** | **65** | names identical to a3791619 |

Other suites at a3791619: transferTest --rerun-tasks 57/0, python tools 52 OK, run/compat 145 OK, run/compat/mac 94 OK,
installer linkGateTest 9 PASS and run/test-link-gate.py 9 PASS. The compile set (`compileJava compileRuntimeJava
compileTestJava`) was green at every commit (per-commit runs or `git archive` snapshots, recorded per lane).

### C2 (on the merged base)

P1 first gave a refused jar a claim of its own instead of `Ecosystem.FORGE` (D1) and extracted `RegistryRemapContracts`
(D5). Five lanes then deleted the event forward pass and its audits (events), the registry-wrapper, capability, fluid,
spawner, client, damage, loot, worldgen and merged-transformer repairs (transform), MinecraftForge's runtime halves and
transfer adapters (runtime), MinecraftForge's mod construction and loading state (boot), and PayloadInterop's
MinecraftForge codec and handshake (interop-misc). The critic found one missed MinecraftForge piece, a three-line test
stand-in (`weave/absorbeddamage/net/minecraftforge/common/ForgeHooks.java`), still present (C4/C5). No gate ran in C2.

### C1 (the flip)

The installer builds only NeoForge's patched game (sha1 `67bbf24f…`, link check base-first and carrier-first
`dangling references: 0` over 13,394 classes) and installs `net.forbric:patched-mc-neoforge:26.2-26.2.0.93` with one
`--runtimeJar` and the sidecar; the kernel compiles and tests against G + carrier; launchers run G; an install made by
Forbric 0.4 or earlier is refused with exit 2 (D9). The first genuine boots hit three kernel walls, each fixed with a unit
test: W1 "Unknown addon" one second after joining (122bf712), W2 `AbstractMethodError` on the first axolotl tick from a
guest mixin's private `self()` hiding NeoForge's default (a1221860), W3 fabric-block-api's trapdoor rule not attached
(14fb5fe7). W4 (three client-neo-pack jars NeoForge .93 cannot run natively) is native; W5 (BCLib's tag shears) was
decided by the owner (D35) and fixed in C3.

**Boot ladder at C1** (G, `stage4-c1.md`): zero-mod server, fabric-api + NeoForge canary server, zero-mod client into a
world, the 97-jar client pack (gate-m9 clone), client-neo-pack minus the three natively broken jars, BCLib server,
FancyMenu's NeoForge `initClientHooks` mixin: every step reached Done or client-ready and exited 0.

### C3 (merge repairs, three checks each)

Every candidate of the C3 work list and the four the critic added has a verdict and a commit, with its three checks in
the commit message and in the lane's record file:

| lane | DELETE | KEEP / MOVE / EDIT |
|---|---|---|
| events-hooks | NeoScreenEvents b019efc3, NeoConversionPost 3044c801, tooltip #36 2b63bf22, fuel #56 3f131ccd, place-item hook #30 87014cb1, attributes #37 6a1fcd6d | EDIT ItemUseOnInjector post half 7af6a450 (the relay half adapts Fabric) |
| cmt-structural | lambda handles #1 830f3a46, nested bootstrap #55 660106a5, unwritten Logger #39 caeb1463, empty creative-tab stacks 88a16ff7, KeyMapping.click #6 fa025d43, mip bounds #53 817a6b80, NativeMergeShapeRepair #28/#47/#48 00c9e26b, interop owners 3a6c549a, LootPoolFieldsInjector f8c03154, particle getProvider half 7255ca27 | KEEP wrapStreams #54 39d6ef58 (D38), dungeons #10 b0facd67/f3ee5d16 (D20); MOVE crafting remainder fd86f1aa, own-fluid-type fire clause 33a80ded |
| mixin-side | VirtualGetters dd5b5f23, AncestorComposition pair 065d292d, MixinBuilderStubRebind 03a94761, MixinMergedTwin c5558162, DuplicateLambdaPrune + its LambdaShapeEvidence record 4175d4bc | KEEP LocalRanges d253a14d/36dff316 (the kernel's own tag-tracker rewrite needs it) |
| runtime-injectors | ClientPartTracking a9be1761, CreativeSearch pair 845ecd11, FurnaceTickCalls 27db5912, MergedRecordOptionalDefaults 30afbe37, flower pot MinecraftForge half 22e6b07f, flower pot table refill 4add18ac, data-map fallback 280e84b9, CNI play fall-through branch 4dd8629c, client resource preload b7dd2f64, KernelLifecycle.openWindow 30a330cc; checks recorded for C2's ModifiableDataViews quartet (ca371ff0) and PackScreenHiddenFilterInjector | KEEP FabricCommonPayloadMixinAdapter + `forbric.playPayloadFallThrough`; flower pot constructor edit |
| regress-server | — | adaptations: Carpet neighbour change 95a2f1de, hookDamage b2b3e240/e3da7384, BCLib mob shears a162172a/3f02c16d; furnace remainder measured not lost ee1aed96 |
| regress-client | — | adaptation: fabric-screen-api screen draw beside spruceui/JourneyMap 9369f153 (+ 74146c9e, 4a1b2202) |
| c3b-useon-relocation | — | KEEP MixinRelocatedCall, MixinPlacementTransactionAdapter (wording) |
| c3b-postmixin-fixups | alias rewrite's `keySet()` half 45ec501e, PiP seed 6fc5a910 | KEEP alias-first routing, field-initialiser placement, NativeDeadValueConsumers; EDIT delegated-constructor replay 2fba129f/27f9f4a2 |
| c3b-cni-hedge | CNI `guardOtherConnectionInitialisation` 7b07d377 (owner item) | KEEP `shareMinecraftRegisterWithSuper`; census names idle hedges 0eb8de2a |
| c3b-fluid-reaction | `afterUnhandled` + `flowingReaction` 6c3e96f6, NativeEntryRelay 98cdbd22, REGISTRIES FORGE entry be89707e, orphan `weave/createfluid` 63ea77dc | EDIT `neighbourChangeOnly` Fabric-only 3c88e7e1; MOVE HEAD arm 8462a705 |

Every switch that left the code is in `ForbricSwitches.RETIRED` and `released-switches.txt` (15: builderStubRebind,
clientPartTracking, clientResourcePreload, creativeSearchTrees, furnaceTickCalls, itemTooltipBridge, lootPoolFields,
mergedRecordDefaults, mipmapLowering, mixinMergedTwins, neoConversionPost, neoDataMapFallback, neoScreenEvents,
pruneDuplicateLambdas, nativeEntryRelay); `aliasPresenceParity.deferredRegister` was renamed into `aliasPresenceParity`.
New switches: clientSmokeMultipart(+Types), clientSmokeTooltipEvent, fluidReactionNeighbourChange (+ `.head`),
itemDamageHandler, nativeCallInlining.registeredMixins, operationSeams.additions, shearsRelayMobs,
postMixinFixups.nativeDelegation. No REQUIRED anchor was downgraded to HEDGE (diff grep over 73a4314c..s4-c3;
OwnFluidTypeClauses went from two REQUIRED anchors to one, with a reason). The anchor census reports **0 declines** in every
gate and boot log at a3791619, and since 7b07d377 a G client reports 0 idle hedges (86 of 108 declared).

**Boot ladder at a3791619** (verify lane, G): zero-mod server (Done 1.310 s, exit 0); fabric-api 0.155.2 + forbricneolive
server (`1 Fabric, 1 NeoForge, 0 MinecraftForge`, exit 0); zero-mod client into that world (client-ready after 60 ticks,
KernelModListScreen, exit 0 in ~23 s); client-neo-pack minus earthmobsmod and sophisticatedbackpacks/core (60 jars, STRICT,
exit 0 in ~49 s, report findings identical to C1's 24); BCLib + wunderlib + worldweaver + fabric-api server (exit 0). No
injection warning C1 did not have; s2, s5 and m9 have fewer (the trapdoor, hookDamage and BCLib shears warnings are gone;
left: fabric-item-api's furnace `getCraftingRemainder`, measured not lost, and fabric-loot-api's
ReloadableServerRegistriesMixin, suppressed by name on purpose). Round 2 added: a G client joining a pure vanilla 26.2
server and a G kernel server (clean), and the 97-jar dedicated server before and after the postmixin edits (Done).

### Gates on `.stage4` at a3791619

C1 edited only gate-m34-soak.sh and the carpet and menu-codec compat gates (build inputs); C3 edited gate-m7-neo, m9,
m26, m29, m42, m44, m45, m55 and carpet-gate.py, and added regress-gate.py and fluid-head-gate.py.

`FORBRIC_OLD=.stage4`, a unique `GATE_PORT` each, client fixtures as APFS clones by real path. Gate scripts C5 has not
rewritten yet still assert merged-base or MinecraftForge premises; every red line is classified, none is a defect.

| gate | PASS / FAIL | red lines and class |
|---|---|---|
| m1 | 17 / 0 GREEN | anchor census `65 of 100 … 0 hedge(s)` (C1 had 2 FAIL for the eight C3 declines) |
| m22 | 5 / 0 GREEN | — |
| m31 | 13 / 0 GREEN | 1764 chunks; 0 biome / structure / spawner differences (heightmaps 9, blocks 237, block entities 20 differ: vanilla noise, not asserted) |
| m44 | 9 / 0 GREEN | — (also GREEN at 180b68f3, round 2) |
| m55 | 23 / 0 GREEN | — |
| m7-neo | 28 / 1 | `MinecraftForge baseline intact (10)` greps `registered content: forge: 10 entr`: C5 premise |
| m14 | 33 / 4 | three "Forge-wrapped registries …" lines and "fabric-api's remap listeners were told", printed only by the wrapper sync deleted in 3c72ff50: vacuous (D34, C5); `:208` passes vacuously. Identical at 3664016c |
| m42 | 5 / 9 | every judge asserts `len(cases) == 22`, G has 21 (no `fluid.water`); by hand on the phase JSON: positive ∅, rebase-off exactly {biome.fabric.keep, biome.late.keep}, withhold exactly {drown.neo}; the off rule expects drown.neo and pot.neoforge.api to fail, but both are native on G since their C3 deletions; the four liquid-off lines are vacuous (switch retired in 1f3d64f7); `cannot read LiquidBlock.<init> from the merged base` is `merged_delivers` (D3): all C5 |
| m45 | 17 / 2 | two PartEntity bridge lines grep `net.minecraftforge.entity.PartEntity, served by the FORGE runtime`: C5 premise; all 22 cases pass |
| m54 | 9 / 2 | the repaired drill is GREEN; the control's reader waits for a drill verdict while the control client is disconnected (`Internal Exception: java.lang.IllegalStateException: Unknown addon` ×3 in `gate-m54-control.log`), so it prints `<no verdict>`: the control is red in substance, the reader is C5's |
| m9 | 149 / 21 | EventMux bridge lines ×5 and "the filter is back on the screen": vacuous (D34); carriers 2→1 ×2 (D2), alias parity, loot-modifier index 2→1, MinecraftForge hears ×9, MinecraftForge lang, client commands `forge=true`: C5 premise. "Fabric's screen draw events reach an open screen", C1's one real defect, passes (`beforeExtract 43, afterExtract 43`). Same 149/21 at 95c287df |
| compat: carpet-gate | 21/34, 34/34, 32/34 | baseline = exactly the Carpet set, fixed STRICT, neighbour-off exactly the two neighbour cases (also at 3c88e7e1) |
| compat: regress-gate (new, 7ff223f9) | 15/15 | item-damage-off fails exactly damage.mob/player; shears-mobs-off exactly the three tag-shears cases |
| compat: fluid-head-gate (new, 8462a705) | 6/6 | HEAD-off 3/6, failing exactly the three neighbour cases |
| m12 (at 5efabdc5) | 31 / 1 | `Forge-wrapped registries followed the server's ids`: printed only by `KernelForgeWrapperSync`, deleted in 3c72ff50: vacuous (D34, C5) |

Not run on `.stage4` after their C3 edits: gate-m26 and gate-m29 (`bash -n` only); C5 owns them (it rewrites both). Re-run
at 5efabdc5, after 7b07d377: m1 17/0, m9 149/21 (same lines as a3791619), m22 5/0, m44 9/0, m45 17/2, m54 9/2, m12 31/1,
regress-gate 15/15, fluid-head-gate 6/6, and the five boot-ladder steps exit 0 with the same injection warnings; every
anchor census line reads "0 hedge(s)". The 65 failing test methods by name, with message and owner, are in
`stage4-c3-open-failures.txt`.

### Open unit failures at the C3 tip (65, grouped by cause; each with its owner and failure message)

| tests | n | owner | cause | evidence (failure message) |
|---|---:|---|---|---|
| CarrierHelperCensusTest, CarrierRenameCensusTest, CarrierStubCensusTest, CarrierTwinCensusTest, UncalledMethodCensusTest, VanillaEarlyReturnsCensusTest (`theShippedTableIsExactlyWhatTheArtifactsSay` each) | 6 | C4 | the shipped census tables were generated from the merged base with a MinecraftForge reference arm; C1 re-pinned the inputs to G + carrier, so the derived tables differ; C4 regenerates them in the commit that removes `Ecosystem.FORGE` (D31) | "carrier-helpers.txt must equal what the staged jars say; regenerate with FORBRIC_WRITE_CARRIER_HELPERS=1 after a base rebuild" (and the same for each table) |
| NativeOnlyMethodsCensusTest ×5 | 5 | C4 | the same: the table records the merged base's member digest, and one case reads the FORGE platform arm C1 removed | "the shipped table records the staged base's members ==> expected: <d033f1ac…> but was: <080a89b5…>"; NPE `Map.get(Object)` on `platforms.get(FORGE)` |
| KeyActionExitsTest, MixinTailCaptureNativeTest, ReplacedCallRedirectsTest | 3 | C4 | each asserts a FORGE native reference or a MinecraftForge family case; goes with `Ecosystem.FORGE` | "Forge native reference ==> expected: not <null>"; "FORGE ==> expected: not <null>"; "MinecraftForge's own screen still calls matches(KeyEvent) ==> expected: <1> but was: <0>" |
| MergedBaseAnonymousDriftTest | 1 | C4 | `MergedBaseAnonymousDrift.CAPTURE_ONLY` was derived on the merged base: G drops Options$3, TagAppender$1, ServerLoginPacketListenerImpl$1 (MinecraftForge-made drift) and adds CustomPacketPayload$1; regenerate with the tables | "CAPTURE_ONLY: same methods, different constructor or captures — never flagged" |
| MergedBaseGuestScanStagedTest ×3 | 3 | C4 | the pinned rows (`travelInFluid(Vec3)` never running, removeBlock twin rows) are merged-base uncalled-methods rows absent on G | "the census reads travelInFluid(Vec3) as never running ==> expected: not <null>"; `NoSuchElementException` |
| MixinFitLivenessCensusStagedTest ×4, MergedBaseCalleeSwapTest, MixinStubRebindSpellingTest | 6 | C4+C5 | verdict, lost-anchor and swap pins derived on the merged base (merged pins 15/5 verdict changes; G reads 25/9); the canGlide stub row is merged-only | "liveness census: 10968 guest mixins in 5 packs; 25 change verdict (9 distinct)"; "a swap now passes the safety test …"; "premise: the row moves some family's selector" |
| FabricClientMixinAnchorsTest ×4, MixinOperationSeamSpellingTest ×12, OperationCallGraphTest | 17 | C5 | pinned on the merged gateway `Gui.drawScreen` / `ForgeLayerInstance`; on G the top screen draw goes through `ClientHooks.extractScreen`, which the seam finds; re-pin to the inline (no registered anchor) or the seam with a registered anchor (spruceui) | "expected: <Lnet/minecraft/client/gui/Gui;drawScreen(…)V> but was: <Lnet/neoforged/neoforge/client/ClientHooks;extractScreen(…" |
| MergedBaseFeaturesPerStepTest ×4 | 4 | C5 | G's `ChunkGenerator.featuresPerStep` has only `Supplier`; MinecraftForge's `ClearableLazy` view is gone | "both native ABI views are retained exactly once ==> expected: <[…Supplier;, …ClearableLazy;]> but was: <[…Supplier;]>" |
| MergedBaseFrameRecomputerTest ×4 | 4 | C5 | the fixture mod is InventoryProfilesNext-forge (refused) and the pins name MinecraftForge carrier types; needs a NeoForge build of the fixture | `TypeNotPresentException: Type net/minecraftforge/common/capabilities/CapabilityProvider$ItemStacks not present` |
| RegistryLoaderCompanionBindingTest ×4, RegistryLoaderSelectorMappingTest | 5 | C5 | the merged RegistryDataLoader kept a dead MinecraftForge loader; G does not | "premise: the merge declares the dead loader ==> expected: <true> but was: <false>"; "expected: <0> but was: <1>" |
| InsertedLambdaArgumentShimTest ×2 | 2 | C5 | litematica/fusion handlers followed the merged base's lambda numbering; G's differs (the pruner half of the cause went with 4175d4bc) | "expected: <2> but was: <0>" |
| MergedBaseSkipMarkerTest, MixinReturnOrdinalsTest | 2 | C5 | merged-only shapes: both `lambda$scanDirectory$0` overloads; an extra `destroyBlock` exit | "the merged base no longer carries BOTH lambda$scanDirectory$0 overloads"; "the merged destroyBlock has an exit vanilla lacks: 5" |
| RepairDriftCensusStagedTest.theBuildThisTreeIsCalibratedAgainstMovesNoAnchor | 1 | C5 (tool) | `RepairDriftCensus.replay` registers the compat transformer alone; KernelBoot runs VanillaMemberTwinInjector first, and with it `bridgeOrphanedPipRenderers` and `giveTheVanillaParticleMapAViewOfTheLiveOne` land (their staged tests are green) | "a repair declined on the build this tree is calibrated against ==> expected: <[]> but was: <[Miss[transformer=forbric-merged-base-compat#bridgeOrphanedPipRenderers, …" |
| FabricClientMixinAnchorsTest ×2, MixinTwinRebindTest (LiquidBounce) | 3 | P1 | third-party fixture lost in the 2026-10-23 cleanup (Stage 0) | "[fixture:third-party] ../build/lb-vfp/jars/liquidbounce-0.40.1+26.2-20260925.jar absent — required by -Dforbric.requireFixtures=all" |
| MixinRetargetRenamedBodyCorpusTest | 1 | P1 | the random100 corpus was lost (Stage 0); 19 distinct moves as pinned, 44 instances instead of 52 | "R3 over 691 jars (1237 units), 11987 listed mixins: 44 moves in jars, 19 distinct" |
| SharedFinalSourceActualTest ×2 | 2 | P1 | opt-in fixture `.stage4/run/shared-final-definitions` absent, by design | "[fixture:opt-in] actual shared final definition: …/.stage4/run/shared-final-definitions —" |

Totals: C4 18, C4+C5 6, C5 34, C5 (tool) 1, P1 6.

### Corrections to the commit history (history is not rewritten)

- **7eabdaef.** Six deleting commits of the cmt-structural lane (830f3a46, 660106a5, 88a16ff7, 817a6b80, 3a6c549a, f8c03154)
  and the lane's record commit 32f77fa2 cite "the lane tip 7eabdaef". It is on no branch: `git cat-file -p 7eabdaef` shows
  "WIP c3 crafting remainder test", tree `b78605c1`, parent 90c48ea6 "WIP c3 dungeons reword". The lane's WIP commits were
  rewritten into the published ones before they reached `s4-c3`; f49d8068 has exactly tree `b78605c1`. So every
  measurement those messages label "7eabdaef" was taken on the tree f49d8068 carries (all of the lane's deletions applied).
  The mixin-side lane did the same with its per-commit compile (WIP 90e26e8b..f7cb48f7, the same trees as dd5b5f23..d253a14d).
- **f49d8068** deleted CraftingRemainderConflictTest as "subject gone" without the three checks. The review reversed the
  verdict: the conflict had moved from Item to ItemStack on G, and a NeoForge item's remainder override was no longer
  reached ("the NeoForge jug leaves null" in gate-m45). fd86f1aa re-anchored the repair (MOVE: Item gets a stack-typed
  remainder that asks NeoForge's ItemInstance overload) and brought the test back rewritten (180 lines); 1216e981 added
  the gate-m45 check. f49d8068's message still stands.
- **54a4bce4** says MixinSharedResultTransportTest "9/9"; the class has 8 tests, 8/8 green.
- **C1, 122bf712, a1221860, 14fb5fe7**: at each, WeaveCoverageCensusTest was red one more test than recorded (the new
  adapters landed before their census rows, added in 08ce2ea2). The compile set was green at all four.
- **Merged red checks that were impossible (D37).** Check 1 asks for a red on the merged base with the repair off. For ten
  deletions it could not exist because the repair was already dead there (dead, stood down, satisfied on both bases, or
  covering only a MinecraftForge overload): 3044c801 (NeoConversionPost; a genuine withhold red was added in dbfd7ddc),
  2b63bf22 (tooltip; genuine withhold red), 3f131ccd (fuel; only the claim's own Forge-overload probe goes red on M),
  6a1fcd6d (attributes; nothing can go red on either base), 830f3a46 (lambda handles; inert on both), 00c9e26b
  (NativeMergeShapeRepair; claim replay 0 on M, criterion red only), f8c03154 (LootPool; criterion red only, no game run),
  88a16ff7 (empty creative-tab stacks; by D25's native rule), a9be1761 (ClientPartTracking; the merged body was already
  NeoForge's), 280e84b9 (data-map fallback; never reached on M). D37 accepts these on the genuine symptom check, the
  whole-jar criterion and the caller check; no repair is reinstated to make a red possible. Round 2 cites D37 for two
  more: 6fc5a910 (PiP seed, seeded 0 in the merged m9 log) and 7b07d377 (connection guard; M has no
  `initializedConnection` either since carrier .88).
- **Other evidence limits stated in the commits**: 660106a5 used a JFR count instead of D25's `-Xlog:class+init`; b7dd2f64's
  "native NeoForge logs the same" is inferred from bytecode order (`ClientModLoader.finish` @1337 before `createReload`
  @1368 in `Minecraft.<init>`), not observed on native NeoForge; 845ecd11 and 30afbe37 got their static merged reds later
  (a6f9a621, 50236f2c).
- **Shared client fixture (D39).** Lanes mixin-side (09:44-10:33) and regress-client (15:00-19:22, 459 files) wrote into the
  main checkout's `run/client-merged-pack` through a copied symlink; Bad Packets sat in its `mods/` at most 09:44-10:33.
  `mods/` was verified byte-identical to the 2026-10-30 backup (97 jars, sha1); configs and saves are runtime state the
  backup never held; not restored. The m9 differentials hold because each fix and its controls ran on the same base; every
  later gate clones the fixture by its real path.

### Decisions taken in Stage 4 that change behaviour (D35-D39)

- **D35** (owner): BCLib's tag shears shear sheep and snow golems again (a162172a, `forbric.shearsRelayMobs`). The plan's
  "shears follow NeoForge semantics" rested on the false premise that no Fabric mixin depends on it.
- **D36**: the C3 genuine-base adaptations are accepted, each with its own switch: fabric-item-api's hurtAndBreak wrap onto
  NeoForge's LivingEntity overload (b2b3e240, e3da7384; `forbric.itemDamageHandler`), Carpet's renewable rules on a
  neighbour change (95a2f1de; `forbric.fluidReactionNeighbourChange`), the fabric-screen draw wrap beside
  spruceui/JourneyMap (9369f153 + 74146c9e/4a1b2202; `forbric.nativeCallInlining.registeredMixins`,
  `forbric.operationSeams.additions`), the stack-typed crafting remainder (fd86f1aa) and the own-fluid-type fire clause
  (33a80ded). They restore native Fabric (or NeoForge) behaviour and go into the release notes as adaptations, not losses.
- **D37**: the impossible merged reds above.
- **D38**: `wrapTheStreamsVanillaWraps` stays (39d6ef58): System.out/err are wrapped as vanilla and Fabric wrap them; native
  NeoForge does not.
- **D39**: the shared fixture above.
- **D40**: 7b07d377 stands. The connection-initialisation guard was a NeoForge 26.2.0.38-beta carrier repair, not a merge
  repair, but it had edited nothing on either base since 26.2.0.88 (NeoForge guards that case itself); a carrier test now
  pins NeoForge's own guard and goes red if a carrier loses it (ccf9851b). D37's list also takes 6fc5a910 and 7b07d377.
- **D41**: two more C3b adaptations are accepted like D36: the fluid HEAD arm (8462a705, `forbric.fluidReactionNeighbourChange.head`),
  so fabric-block-api's flow veto and Create Fly's lava reaction run on a neighbour change as natively (two HEAD wraps on
  one cell run in reverse native order, recorded), and the constructor replay that stands down where the mixin's own
  platform already delegates (27f9f4a2, `forbric.postMixinFixups.nativeDelegation`), which is what native Fabric does for
  fabric-permission-api's `storeOriginalSource`.

### Accepted behaviour changes (release notes)

- MinecraftForge mods no longer load; a dedicated server with a MinecraftForge-only jar in `mods/` stops at launch
  (`-Dforbric.compatibilityPolicy=continue` launches anyway); a client asks (Stage 3).
- An install made by Forbric 0.4 or earlier is refused with exit 2 and the message to rerun the new installer on the same
  game directory (D9).
- Hoppers follow NeoForge's semantics (plan; C5's hopper Fabric-storage parity check still to run).
- Shears: **no longer a loss** (D35). BCLib's tag shears shear sheep and snow golems again. Approximations: tag shears on
  those mobs follow NeoForge's IShearable answer (PASS when not shearable) rather than vanilla's CONSUME; a Fabric shears
  wrap that vetoes real shears on them is not carried over; an item in `c:tools/shear` that is not a ShearsItem and has its
  own `interactLivingEntity` keeps its own entry unless it is a Fabric mod's.
- Adaptations that keep Fabric mods working on NeoForge's own base (D36, plus C1's wall fixes): Fabric `CustomDamageHandler`s
  run; Carpet's renewable blackstone and deepslate also form on a neighbour change; fabric-screen-api's screen draw events
  fire beside spruceui and JourneyMap; a NeoForge item's crafting remainder override survives fabric-item-api; a Fabric
  fluid tagged `minecraft:water` no longer puts out fire; play payloads fabric-api declines reach NeoForge
  (`forbric.playPayloadFallThrough`); a guest mixin's private method no longer hides a NeoForge interface default
  (`forbric.mixinPrivateDefaults`). Each has its own switch.
- System.out/err are wrapped as in vanilla, unlike native NeoForge (D38).
- With the client resource preload gone, EnhancedVisuals logs 20 "Could not find any resources" lines and CreativeCore
  "Could not load default style" during client setup; native NeoForge also runs client setup before the first reload
  (inferred from bytecode order, D16).
- A Fabric mixin written for a vanilla lambda that NeoForge reshaped is now bound and runs where it was UNFIT on the merged
  base (mixin-side).

Known gaps that are not Stage 4 regressions (the merged base had them too; follow-up work, not Stage 4 blockers):
better-nether's placement-only `bn_shouldSpreadLiquid` (its handler captures locals inside the method, so the
neighbour-change wrap cannot serve it); a Fabric TAIL `@Inject` on `ItemStack.useOn` most likely never runs server-side,
because NeoForge's server path returns from `CommonHooks.onPlaceItemIntoWorld` before vanilla's tail (bytecode only, not
measured). Minor follow-ups: a ClientSmoke probe that presses a
consumeClick key (#6); whether `LocalRanges`' one site could be written correctly by `NativeTagTrackerSource` itself.

### Not done yet

- **C4**: regenerate the census tables and remove `Ecosystem.FORGE` in one commit (about 22 main/runtime sites at a3791619, one of
  them since removed by be89707e; 167 test references); report and log wording that still names the merged base on G (e.g. "on the merged game", "the forwarding stub
  the merged base keeps"); the false SUSPECTED furnace-remainder report row on every install with fabric-api;
  `ForeignType`'s FORGE column; the MinecraftForge test stand-ins.
- **C5**: every gate line classified above, the 34 + 6 + 1 re-pins, the C1 gate list, gate-m49's retired switches, the
  four-native-call-sites pin, weave scenarios for the new adapters, wiring regress-gate.py and fluid-head-gate.py into the
  gate list, C5b (world upgrade) and C5c (Forge → NeoForge build swap).
- **C6**: CI (merge-loss ratchet, hidden-carrier job, `ci-prepared-noforge.tsv`), release roles, installer clean-up of its
  own old artifacts (D6), docs for the new switches and classes (KernelFluidReactions, KernelItemDamage, KernelMobShears).

### C4, lane census-forge (3d8364b4): census tables regenerated, `Ecosystem.FORGE` removed

One commit on `s4-c4` regenerates the eight census tables from NeoForge's own base and removes `Ecosystem.FORGE`
(the row parsers drop a whole row whose ecosystem no longer parses, so the two cannot be separate). Details, the
raw-vs-post-twin choice per table (D31) and the review of every new FABRIC row by kind: `stage4-c4-census.md`.

| | before (merged base) | after (genuine base) |
|---|---|---|
| carrier-renames / -helpers / -stubs / -twins rows | 389 / 37 / 79 / 7 | 335 / 35 / 74 / 5 |
| native-only-methods rows (base digest) | 1001 (`080a89b5…`) | 659 (`d033f1ac…`, raw) |
| uncalled-methods / vanilla-early-returns rows | 262 / 3271 | 86 / 3276 |
| rows naming FORGE | 1,057 | 0 |
| new FABRIC rows | | renames 10 (EntityType.trackDeltas), twins 2, uncalled 8 |
| unit suite (`.stage4`) | 5,760 / 65 failed (C3 tip) | **5,755 / 45 failed**: 23 C4 rows fixed, 3 new (TwinCapturedLocalsWeaveTest, its fixture models the merged base's FORGE-only twin row; C5) |
| gates | | m1 GREEN, m46 GREEN, m9 165/21 (same 21 C5 lines as at 5efabdc5), m29 refusal phases PASS (control phases: C5) |

Behaviour visible to players: a refused MinecraftForge jar is still listed on the Mods screen under "MinecraftForge",
but counted under no ecosystem ("N refused") and no longer makes NeoForge's own settings row appear; the boot lines
no longer print a MinecraftForge count. Still open after this lane: the every-chain-transformer anchor census test
(D31), the merged-base wording in log and report strings, the false SUSPECTED furnace-remainder row, and the
remaining MinecraftForge test stand-ins (C4); TwinCapturedLocalsWeaveTest's fixture and MergedBaseCalleeSwapTest's
second admitted swap (C5).
