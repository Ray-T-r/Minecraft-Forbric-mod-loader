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
