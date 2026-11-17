# Stage 4 C6 — lane INST (installer clean-up, gate-m17 phase U, reproducible NeoForge runtime, Doctor's disk numbers)

Branch `s4-c5-c6inst`, cut from the stage tip 495e48fc (worktree `.claude/worktrees/s4c5-c6inst`, ports 46880-46899 from
`.stage4/lane-env.sh`). Notes and logs: `~/Forbric-backups/stage4-notes/c6/inst/` (paths below are relative to it). Harness:
D68 (pinned build, plain `--offline` from `.stage4/props.txt`, no init script, no private Gradle home); every Gradle and game
run through `.stage/heavy.sh`, gate-m17 under `.stage/client.sh`. No zh run (D88b); the new user-visible lines have a
forced-zh unit test. Download (owner answer 2, `c6/owner-answers.md`): the public v0.3.1-beta2 installer jar, once, and
what that installer itself fetched to lay out its install (Forge 26.2-65.0.1, NeoForge 26.2.0.88, NFRT 2.0.18 and Mojang's
26.2 artifacts, from their Mavens and Mojang). No mod jar.

## Worktree

Bootstrap reported two PROBLEMs: `.stage4/minecraft` was the relative link `../.stage/minecraft` (relinked to the absolute
`suspicious-chaum-b28730/.stage/minecraft`), and `forbric-kernel/build/sweep100-mac` has no source in the main checkout (the
open item every lane since wave 2c reports; nothing here reads it).

## Commits (on 495e48fc)

| sha | what |
|---|---|
| 353f9f9f | OldInstallCleanup + its call at the end of Installer.install + InstallerText `[cleanup]` lines (en/zh) + OldInstallCleanupTest |
| 9244099f | Zips.FIXED_TIME on every NeoForge runtime entry + ReproducibleRuntimeTest (byte equality, carrier equivalence) |
| a6423cc5 | gate-m17: phase U on the real v0.3.1-beta2 install with its reverse control; clean-up checks on every install; build step and tools phase follow the checker the installer carries; `/run/installed-upgrade` ignored |
| 9bee7f76 | Doctor PEAK_MB 480 / RESIDENT_MB 470 from the cold measurement, javadoc |
| (this file) | record |

## Items

### installer-cleanup-D6 — done

`OldInstallCleanup` runs at the end of every install, after the profile is written (one hunk in `Installer.install`), and
never fails the install.

- `.forbric-build`: a closed list of names the installers from v0.3.0 to tri-eco-26.2-final wrote and this one never reads
  (taken from those tags' ArtifactBuilder / MergedBaseTool / PatchedMcBuilder / ForgeTool): `out/patched-mc-merged-*.jar`
  (+`.pins`), `out/merge-conflicts.txt`, `out/forge-runtime.jar` (+`.pins`), `out/forge-runtime-interop.jar` (+`.pins`),
  `out/patched-mc-forge-*.jar` (+`.pins`), `server-main.jar`, `clean.jar`, `patched-subset.jar`, `patched-full.jar`,
  `patched-at.jar`, `joined.lzma`, `ats/`. `tools/forbric-merge-tools.jar` only when the installer carries no
  `/forbric/tools/forbric-merge-tools.jar` (so on this branch it stays, since this installer still runs its checker from
  it; once LINK's installer lands it goes — gate-m17's tools phase asserts both sides).
- `libraries/net/forbric/**`: a directory holding files of its own (a library version) is removed only when no
  `versions/<id>/*.json`, or the profile it `inheritsFrom`, names anything inside it — a `net.forbric` coordinate or a
  `net/forbric/...` path in any string of the profile (libraries, arguments). Every old product is covered, not only the
  Forge/merged ones (critic 3): old `neoforge-runtime/<mc>`, old `forbric-kernel/<version>`, forbric-loader, forbricruntime,
  the old vanilla references. Nothing outside `net/forbric` is a candidate.
- Never touched: what this install built or staged (a guard, besides the rules), `dl/`, NFRT's caches, other groups.
  Symbolic links are neither followed nor removed; a `libraries` reached through a link is left whole. An unreadable profile
  stops the library half. A delete that fails is named and the install carries on. `-Dforbric.installerCleanup=off` turns it
  off (phase U's reverse control). Lines start `[cleanup] ` and go through `InstallerText` with Chinese variants.
- Evidence: `OldInstallCleanupTest` OK (new-installer-tests.log, against the gradle-built classes with the dev-tools jar on
  the class path, and in-process from a plain class path); gate-m17 phase U (below).

### gate-m17-phase-U — done, on the real v0.3.1-beta2 install

The owner's answer arrived during the lane. Run 1 (m17-run1.out, 112 PASS / 1 FAIL — the FAIL was this gate's own
leftover check matching NFRT's `nfrt-work/*/ats` directory, fixed) used the fallback the brief allowed: the tri-eco-26.2-final
layout from `~/Forbric-backups/2026-10-30-tri-eco-26.2-final/staged-artifacts.tar.gz` (SHA-1 checked), with beta2's files as
stand-ins; it removed 23 files (134.1 MB) and booted. After the answer, phase U uses the real thing:

- `run/.gate-downloads/m17/forbric-kernel-installer-0.3.1-beta2.jar`, fetched once from the release
  (`https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/download/v0.3.1-beta2/forbric-kernel-installer-0.3.1-beta2.jar`),
  **SHA-1 ae2ff4faa691665d4768218b97820d7ef9e11ce9**, 8 542 333 bytes, bundled (carries its own payload). The gate refetches
  only when the cached file's SHA-1 differs and refuses any other bytes (exit 3).
- That jar run once into an empty directory (`run/.gate-downloads/m17/v0.3.1-beta2-install`, marker `.done` = the jar's
  SHA-1; beta2-install.log, 101-file manifest beta2-install-manifest.txt). It built Forge + NeoForge 26.2.0.88 + the merged
  base (its link check: 24 known dangling, 0 new) and wrote its profile. Phase U clones that directory every run.
- Phase U adds a `keep-old` profile naming `net.forbric:patched-mc-merged:26.2` and `net.forbric:forbric-kernel:0.3.1-beta2`
  (critic 24), copies the player's files from the pack, and seeds what this installer downloads that beta2's caches lack from
  the first install of the same run (never over a file beta2 left): runs 2 and 3 lost a cold download to TLS failures from
  piston-data.mojang.com and maven.neoforged.net (m17-run2-install-network-failure.log, m17-run3-upgrade-off-network-failure.log).
- Reverse control first: the same installer with `-Dforbric.installerCleanup=off` exits 0, says it is off, rebuilds both
  stale artifacts, and every one of the 20 removal checks is red. Then the real install over the same directory: each of the
  20 files gone and named in its log, summary `removed 20 file(s) ... (224.2 MB)`, none failed, the two keep-old files kept,
  this install's 10 files there, no MinecraftForge or merged product in `.forbric-build` (top level, `out/`, `tools/`), `dl/`
  untouched (56 beta2 files byte-identical), `libraries/net/forbric` exactly this install's 4 + the keep-old 2, the player's
  401 files byte-identical, the profile rewritten (one `--gameJar` patched-mc-neoforge, one `--runtimeJar`, one
  `--nativeReference`, no merged/forge-runtime/forbric-loader/forbricruntime), and the upgraded directory boots this kernel
  into the world and leaves cleanly.
- Also D53's "v0.3.1-beta2 profile layout" (control N already covers the refusal of old arguments).

### gate-m17-tools-and-offline-phases — kept and re-pointed (not deleted), with the reason

D44 had C6 delete the merge-tool and offline phases; D70 (later) found both premises live, and the cover check (D89) finds
none: no unit test drives `LinkCheckTool`'s unpack-over-a-stale-copy (git grep `unpackTools`/`forbric-merge-tools` in the
installer tests: none), and nothing else runs an install with the network denied. Deleting them would drop live checks
(D34). So both stay, re-pointed to follow whichever checker the installer carries:

- build step: asks the installer's build file — `buildLinkCheck` (LINK) builds the kernel's `linkCheckJar` and excludes
  `buildLinkCheck`; `buildMergeTools` (this tree) builds the loader's `mergeToolsJar` and excludes `buildMergeTools`; neither
  is exit 3. This is LINK's `m17-scratch-repoints.diff` made to work on both sides of LINK's merge.
- tools phase: the one jar under `forbric/tools/` (it FAILs on none or several); its unpacked copy is spoiled and must come
  back as the bundled bytes. When it is not the merge tools, an older install's `forbric-merge-tools.jar` is planted and must
  be cleaned away, named in the log, as the only removal.
- offline phase: unchanged (its `[link-check] ... dangling references: 0` regex is LINK's verdict line, kept by LINK), plus
  "the clean-up removed nothing".
- m17 prints no `[kernel] SKIP (C6` line (it printed none at 495e48fc either; `grep -c 'SKIP (C6' gate-m17-installer.sh` = 0).
- NativeReferenceLineContractTest still finds `"$NATIVE_REFS_FROM_SIDECAR"` in m17 (no edit needed).

### doctor-peak-resident — done

Measured on gate-m17's cold first install (nothing seeded), sampling `.forbric-build` and `libraries/net/forbric` with
`du -sk` every 0.5 s (doctor-samples-run3.txt; run 1's 1 s sampler, doctor-samples-run1.txt, agrees on `.forbric-build`):
peak 477 968 KiB, final 476 904 KiB (`.forbric-build` 405 104: NFRT cache 255 228, dl 73 540, out 67 488, tools 5 708,
nfrt-work 3 140; `net/forbric` 71 800), plus the kernel's 14 third-party jars (~3.6 MiB, from the bundled manifest). So peak ≈
resident ≈ 470 MiB: nothing is deleted at the end of a build. Doctor: `PEAK_MB = 480`, `RESIDENT_MB = 470` (MiB, as du
counts), javadoc rewritten. The old 730/190 were estimates from the merged-base era; what the 190 counted is not recorded.
A NeoForge-only install alone keeps more than 190, mostly NFRT's cache. An upgrade from beta2 ends with `.forbric-build`
about 36 MiB larger than a cold install (439 956 vs 402 772 KiB in run 5): beta2's downloads in `dl/`, which the clean-up
never touches (about 14 MiB of them are MinecraftForge's own jars), and NFRT's cache entries for 26.2.0.88.

### neoforge-runtime-reproducible-bytes — done

`Zips.writeJar` (only `NeoForgeRuntimeBuilder` uses it) writes every entry at `Zips.FIXED_TIME` (2000-01-01, as
VanillaReference). `ReproducibleRuntimeTest`: two builds 2.1 s apart byte-equal and every entry at the fixed time; red with
`setTimeLocal` removed (red-controls-unit.log: "not byte-equal", and its comparison found nothing but times).

Carrier equivalence (critic 18; carrier-equivalence.log): from the stage's NeoForge 26.2.0.93 download cache (an APFS clone,
59 files before and after: nothing downloaded) the runtime built with the installer classes of 495e48fc and with this
branch's are equivalent apart from entry times (names and order, method, CRC, sizes, stored bytes, contents, extra fields,
comments); two builds here are byte-identical (aa023af2…); the staged `.stage4/run/neoforge-runtime/neoforge-runtime.jar`
(0e3ce8d9…, 3581 entries) is equivalent to the new build apart from times as well. Nothing else changes, so a re-prepared
stage changes only the carrier's SHA-1, and the native NeoForge A/B need not be re-run for this change.

## Critic corrections

| # | result | evidence |
|---|---|---|
| 3 | every unreferenced product under `libraries/net/forbric/**`, `versions/*/*.json` + `inheritsFrom`, never another group | OldInstallCleanupTest (7 orphans incl. `neoforge-runtime/26.2`, `forbric-kernel/0.3.0`, the old vanilla reference; 3 other-group jars kept); phase U (beta2's neoforge-runtime/26.2, forbric-loader, forbricruntime, forge-runtime gone; exact remaining set) |
| 18 | carrier entries and bytes identical apart from times; nothing else changes | carrier-equivalence.log; ReproducibleRuntimeTest |
| 19 | out/ + stamps, dl/, current libraries untouched (guard + red control); failed delete logged, install goes on; InstallerText with zh + forced-zh test | OldInstallCleanupTest (control "guard off, rule reaching out/*" red on 5 outputs; failed-delete case; zh case); m17: every reinstall "removed 0 file(s)"; phase U dl/ 56 files unchanged |
| 24 | an old `forbric-kernel/<old>` named by a second profile survives | OldInstallCleanupTest (`forbric-kernel/0.3.1-beta2`); phase U keep-old |

## Tests and gates

| what | result | log |
|---|---|---|
| OldInstallCleanupTest, ReproducibleRuntimeTest (this branch's classes, dev-tools jar on the class path) | OK, OK | new-installer-tests.log |
| installer `linkGateTest` (its 8 classes; `--artifacts` installs exercise the clean-up hunk) | BUILD SUCCESSFUL, 9 PASS lines | linkGateTest.log |
| LINK's `installerTest` on the LINK preview | 10 classes, 0 failed | installerTest-linkpreview.log |
| run/compat test_gate_locale / test_gate_ports / test_gate_cache | 10 / 11 / 32 OK | (console) |
| gate-m17 run 1 (tri-eco fallback layout) | 112 PASS / 1 FAIL (the gate's own leftover check matched NFRT's nfrt-work/*/ats; fixed) | m17-run1.out |
| gate-m17 run 2, run 3 | RED on cold downloads (TLS failures from piston-data.mojang.com, maven.neoforged.net); run 2's phase U all PASS | m17-run2.out, m17-run3.out |
| gate-m17 run 4 (beta2 phase U, seeded caches) | **GREEN 113 PASS / 0 FAIL**, CAUGHT none | m17-run4.out |
| gate-m17 run 5 (final gate text, this branch) | **GREEN 113 PASS / 0 FAIL**, CAUGHT none | m17-run5.out |
| gate-m17 run 6 (final gate text + LINK preview) | **GREEN 116 PASS / 0 FAIL**, CAUGHT none | m17-run6-linkpreview.out |
| full kernel suite at 9bee7f76 (`-Pforbric.requireFixtures=all jar cleanTest test --continue`, 3m55s) | 5951 tests / 0 skipped / 5 failed = failures-w2d.txt's D58 five by name, 0 new | suite.log, suite-failures.txt |

No other gate was run (D87): only m17 reads the installer, and m0's installer link check is LINK's.

## Red controls

| control | result |
|---|---|
| Zips without `setTimeLocal` | ReproducibleRuntimeTest red (two builds differ; comparison: only times) |
| clean-up ignoring the profiles (in-test, and by source mutation) | red: the 3 named old libraries removed |
| guard off + a rule reaching `out/*` (in-test, and by source mutation) | red: 5 current outputs and stamps removed |
| links followed (mutation) | red |
| `net/forbric` root widened to `libraries` (mutation) | red (other groups removed) |
| removal not logged (mutation) | red |
| failed delete thrown instead of logged (mutation) | red |
| gate-m17 phase U, clean-up switched off | all 20 removal checks red (in every green run) |

red-controls-unit.log has the mutation runs (each: exit 1 and the first assertion).

## Open items

- **LINK / merger**: `OldInstallCleanupTest` and `ReproducibleRuntimeTest` are `main(String[])` programs taking a scratch
  directory, which LINK's `InstallerTests` runner discovers by name; on this branch nothing runs them in a build (the
  installer's `linkGateTest` lists its classes). After the merge, `installerTest` must report 10 classes (LINK's 8 + these 2).
- **LINK**: `forbric-kernel-installer/build.gradle`'s releaseAssets comment ("game artifacts do not reproduce byte for byte
  (zip entry times)") is now half false for the runtime (critic 20).
- **Merger**: nothing to re-point in m17 after LINK. Measured: LINK's tip 666ad56b's diff (record excluded) applied
  uncommitted on this branch's tip, gate-m17 GREEN 116 PASS / 0 FAIL (m17-run6-linkpreview.out: the tools phase spoils
  forbric-link-check.jar and the planted forbric-merge-tools.jar is cleaned away as the one removal; phase U removes 21
  files incl. beta2's merge tools), and LINK's `installerTest` ran 10 classes, 0 failed, these two among them
  (installerTest-linkpreview.log); then reverted (`git apply -R`, clean tree). LINK's `m17-scratch-repoints.diff` is not
  needed: its two re-points are in a6423cc5 in a form that works before and after the merge.
- **Owner/orchestrator**: an upgraded install keeps beta2's downloads in `.forbric-build/dl/`, about 14 MiB of them
  MinecraftForge's own (forge-*-universal/userdev, fmlcore, fmlloader, its language providers, binarypatcher,
  installertools, mergetool), because critic 19 says dl/ is never touched. Removing them would need a rule naming
  MinecraftForge's artifacts in dl/; the plan's "Forge userdev/installer" item is left open for that ruling.
- **Owner/DOCS**: README's disk numbers and its uninstall paragraph (drafts below); no README change in this wave.
- **ACCEPT (Windows)**: the clean-up runs on the real `.minecraft` there and deletes old `libraries/net/forbric/*` (critic 33).
- NFRT's `nfrt-work` keeps one timestamped directory per step and run (~3 MB a build); not this lane's, recorded only.

## Player-facing wording (drafts only; README is not changed in this wave)

README.md requirements line, today "about 730 MB of free disk while it works (about 190 MB is kept afterwards)":

> - **An internet connection**, and about 480 MB of free disk. Almost all of it stays afterwards: it is what makes the next
>   install fast.

README.zh-CN.md, today "安装过程中约 730 MB 的可用磁盘空间（完成后约保留 190 MB）":

> - **网络连接**，以及约 480 MB 的可用磁盘空间。装完后这些几乎都会保留，下次安装因此会很快。

README.md uninstall/upgrade, add after the uninstall paragraph:

> Installing a new version over an older Forbric removes the older version's leftover files by itself, so you no longer need
> to delete anything by hand to get that space back. Files another installed version still uses are kept.

README.zh-CN.md:

> 在旧版 Forbric 上安装新版本时，安装器会自己删掉旧版留下、已经没有版本在用的文件，不用再手动删除来腾出空间。其他已安装版本还在用的文件会保留。

Release notes "Fixed/Changed" line (en / zh):

> The installer now cleans up what older Forbric versions left behind (about 220 MB on an install from 0.3.1-beta2).
> 安装器现在会清理旧版 Forbric 留下的文件（从 0.3.1-beta2 升级时约 220 MB）。
