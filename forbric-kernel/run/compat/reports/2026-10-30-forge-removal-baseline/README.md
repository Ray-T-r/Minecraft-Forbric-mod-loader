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
