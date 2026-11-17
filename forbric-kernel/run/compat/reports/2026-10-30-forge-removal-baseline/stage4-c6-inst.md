# Stage 4 C6 — lane INST (installer clean-up, gate-m17 phase U, reproducible NeoForge runtime, Doctor's disk numbers)

> **Read with the INSTFIX record.** This file was written at c4199989 and brought up to the stage tip in C6 wave 2 by lane
> INSTFIX (`stage4-c6-instfix.md`, which also reviews the three review-fix commits and adds its own fixes). INST's commits
> were rebased by the C6 wave-1 merger; the SHAs below are the stage's, with the lane's own in brackets
> (`~/Forbric-backups/stage4-notes/c6/merger/sha-map.txt`).

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

## Commits (stage SHAs; the lane's before the merger's rebase in brackets)

| sha | what |
|---|---|
| d89e1442 (353f9f9f) | OldInstallCleanup + its call at the end of Installer.install + InstallerText `[cleanup]` lines (en/zh) + OldInstallCleanupTest. Its subject says "an upgrade stops keeping ... MinecraftForge's files"; about 13 MiB of MinecraftForge's downloads stay in `dl/` (below). The body says so; the subject cannot be changed without rewriting the stage's history (review B 12) |
| 524803f4 (9244099f) | Zips.FIXED_TIME on every NeoForge runtime entry + ReproducibleRuntimeTest (byte equality, carrier equivalence) |
| 7fff8274 (a6423cc5) | gate-m17: phase U on the real v0.3.1-beta2 install with its reverse control; clean-up checks on every install; build step and tools phase re-pointed; `/run/installed-upgrade` ignored |
| d612a00f (9bee7f76) | Doctor PEAK_MB 480 / RESIDENT_MB 470 from the cold measurement, javadoc |
| f5e2ab2e (c4199989) | this record (first version) |
| c27cc306 (8c0e0773) | review fixes A F1/F2, B 1/3/5/6/7: every spelling of a library keeps it, junctions are links, only `versions/<id>/<id>.json` can stop the library half, BOM accepted, a "stopped part-way" line, v0.1.0's work tree retired, the test pins English (rules below) |
| f0f7bc66 (3427f8aa) | review A F3 / B 8: Doctor's javadoc says what was measured (no number changes) |
| 79cd0f6b (9a5e8c11) | review B 2/4, A F8: phase U's keep-old names only the old kernel, a non-profile JSON is planted, the refusal check keys on the `[Forbric/Install]` tag, a failed layout reddens phase U alone |
| 1be0fc66 (merger) | review A F7: m17 requires exactly `forbric/tools/forbric-link-check.jar`; the dual (merge-tools / link-check) form is gone |

Files outside the lane's `owns_files` (review B 10, declared here): `forbric-kernel/.gitignore` (+`/run/installed-upgrade`,
the phase U rundir; DOCS owns the file) and `InstallerText.java` (the `[cleanup]` lines, en and zh, critic 19; no lane owns
it, and none of its new lines names a Forbric version, so the VER lane's "0.4 or earlier" reword does not touch them).

## Items

### installer-cleanup-D6 — done

`OldInstallCleanup` runs at the end of every install, after the profile is written (one hunk in `Installer.install`), and
never fails the install.

The rules as they stand at the stage tip (with INSTFIX's additions marked):

- `.forbric-build`: a closed list of names the public installers v0.1.0 to v0.3.1-beta2 and tri-eco-26.2-final wrote and
  this one never reads (taken from those tags' ArtifactBuilder / MergedBaseTool / PatchedMcBuilder / ForgeTool):
  `out/patched-mc-merged-*.jar` (+`.pins`), `out/merge-conflicts.txt`, `out/forge-runtime.jar` (+`.pins`),
  `out/forge-runtime-interop.jar` (+`.pins`), `out/patched-mc-forge-*.jar` (+`.pins`), `server-main.jar`, `clean.jar`,
  `patched-subset.jar`, `patched-full.jar`, `patched-at.jar`, `joined.lzma`, `ats/`, and v0.1.0's MinecraftForge work tree
  `.forbric-build/<mc>-<forge>/` (glob `[0-9]*.[0-9]*-[0-9]*.[0-9]*`; this installer's own top-level names are `dl`, `nfrt`,
  `nfrt-work`, `out`, `tools`; c27cc306, review B 6). `tools/forbric-merge-tools.jar` (and its `.part`) goes when the
  installer carries no `/forbric/tools/forbric-merge-tools.jar`, which since LINK is always: the installer carries only
  `forbric/tools/forbric-link-check.jar`, and m17 (1be0fc66) requires exactly that, so the "both sides" form of the gate
  that the first version of this record described is gone.
- `libraries/net/forbric/**`: a directory holding files of its own (a library version) is removed only when no profile
  names anything inside it — a `net.forbric` coordinate or a `net/forbric/...` path in any string of the profile
  (libraries, arguments). Every old product is covered, not only the Forge/merged ones (critic 3): old
  `neoforge-runtime/<mc>`, old `forbric-kernel/<version>`, forbric-loader, forbricruntime, the old vanilla references.
  Nothing outside `net/forbric` is a candidate. How a profile "names" a library (c27cc306, review A F1; INSTFIX):
  - a coordinate anywhere in a string, with any sub-group, classifier or `@ext` (it counts as the library's version
    directory);
  - a path anywhere in a string, with `/` or `\`, up to `:`, `;`, a quote, white space or `$` (so
    `${classpath_separator}` ends it); **INSTFIX:** every occurrence counts, also one inside an earlier match's run, so a
    list joined by any other character (a comma, as in NeoForge's own `-DmergeModules`, or a bar) keeps each library;
  - **INSTFIX:** a directory above a library's version directory (a module path entry) keeps everything under it;
  - **INSTFIX:** letter case is ignored (Windows and macOS file systems ignore it; a case-sensitive one only keeps more);
    since 976f25f6 (INSTFIX review fixes, R-1) also in `net/forbric` / `net.forbric` themselves;
  - **INSTFIX review fixes (976f25f6, R-2):** a directory below a version directory whose file a profile names (a
    `natives/` folder) is kept with it; coordinates joined by a colon each count.
- Which files are profiles (c27cc306, review B 5): every `versions/<id>/*.json` is read and counts when it parses (it can
  only keep more), plus the profile each `inheritsFrom` names. Only `versions/<id>/<id>.json` (INSTFIX: in any letter case)
  or an inherited parent that cannot be read stops the library half; any other JSON there that does not parse (the game's
  or a mod's, since under PCL2/HMCL version isolation `versions/<id>` is the game directory) is skipped and named
  (`[cleanup] skipped <file> (...)`). A UTF-8 byte-order mark is stripped before parsing.
- Links (c27cc306, review A F2): a symbolic link, or anything the file system reports as "other" (on Windows a junction
  is a directory that is also "other", never a symbolic link to the JDK), is never followed and never removed; the walk
  under `net/forbric` skips such a directory; each step `libraries`, `libraries/net`, `libraries/net/forbric` (and
  `.forbric-build`) is refused, named, when it resolves (`toRealPath`) anywhere other than its own resolved parent plus
  its name (letter case aside). A Minecraft directory under a linked folder is fine (each step is judged against its own
  resolved parent).
- Never touched: what this install built or staged (a guard, besides the rules), `dl/`, NFRT's caches, other groups.
  A delete that fails is named (`[cleanup] could not remove ...`), counted, and the install carries on. Anything
  unexpected stops the clean-up with its own line (`[cleanup] the clean-up stopped part-way (...)`, c27cc306, review B 3),
  leaves the rest, and is not counted as a failed file. `-Dforbric.installerCleanup=off` turns it off (phase U's reverse
  control). Lines start `[cleanup] ` and go through `InstallerText` with Chinese variants; the test pins English for its
  English assertions and gives the caller's language and switch back (review B 1: it failed under a zh JVM locale).
  gate-m17 asks each of its seven installer runs for English with three switches, `INSTALLER_LANG=(-Duser.language=en
  -Dforbric.dialogLanguage=en -Duser.country=US)` (a5708f73, merger wave 2; INSTFIX's first form was the dialog switch
  alone, `stage4-c6-instfix.md` §4). Added 2026-11-18 by DOCFIX C6.
- Evidence: `OldInstallCleanupTest` (in `installerTest`: 11 classes, 0 failed after the merger's rebase,
  `c6/merger/inst-installerTest.log`; INSTFIX's runs in `stage4-c6-instfix.md`); gate-m17 phase U.

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
- Phase U adds a `keep-old` profile naming `net.forbric:forbric-kernel:0.3.1-beta2` only (critic 24; until 79cd0f6b it also
  named beta2's merged base, so the real layout never showed that library going, review B 2), plants an empty
  `versions/keep-old/usercache.json` (a game JSON that must be skipped, named, and must not stop the clean-up), copies the player's files from the pack, and seeds what this installer downloads that beta2's caches lack from
  the first install of the same run (never over a file beta2 left): runs 2 and 3 lost a cold download to TLS failures from
  piston-data.mojang.com and maven.neoforged.net (m17-run2-install-network-failure.log, m17-run3-upgrade-off-network-failure.log).
- Reverse control first: the same installer with `-Dforbric.installerCleanup=off` exits 0, says it is off, rebuilds both
  stale artifacts, and every one of the 22 removal checks is red. Then the real install over the same directory: each of the
  22 files gone and named in its log, summary `removed 22 file(s) ... (258.5 MB)` (stage tip, merger run
  `c6/merger/gates/gate-m17.out` with the stage's `build/gate-m17-upgrade.log`; the first version of this record had 20 files /
  224.2 MB from before 79cd0f6b and LINK), none failed, it did not stop part-way, the planted JSON skipped and named, the
  keep-old kernel kept, this install's 10 files there, no MinecraftForge or merged product in `.forbric-build`'s top level,
  `out/` and `tools/` (INSTFIX narrowed that PASS line to say exactly this: `dl/` still holds beta2's MinecraftForge
  downloads, see below), `dl/` untouched (56 beta2 files byte-identical), `libraries/net/forbric` exactly this install's 4 +
  the keep-old 1, the player's 401 files byte-identical, the profile rewritten (one `--gameJar` patched-mc-neoforge, one `--runtimeJar`, one
  `--nativeReference`, no merged/forge-runtime/forbric-loader/forbricruntime), and the upgraded directory boots this kernel
  into the world and leaves cleanly, not refused: the refusal check keys on the kernel's `[Forbric/Install]` tag (79cd0f6b,
  review B 4) and must match control N's refused launch in the same run, so the VER lane's reword cannot blind it.
- A player's real upgrade has no keep-old profile, so beta2's own kernel goes too. INSTFIX measured it on a clone of the
  same beta2 install with no second profile: **23 files, 261.6 MB** (`c6/instfix/player-upgrade.log`).
- If the beta2 jar cannot be fetched or its install laid out, phase U alone is red and every other phase still runs
  (79cd0f6b, review A F8); the merger copied the cache into the stage worktree, so nothing was fetched again.
- Also D53's "v0.3.1-beta2 profile layout" (control N already covers the refusal of old arguments).

### gate-m17-tools-and-offline-phases — kept and re-pointed (not deleted), with the reason

(At the stage tip: the build step builds the kernel's `linkCheckJar` and excludes the installer's `buildLinkCheck`, and has
no `buildMergeTools` branch (FATAL without `buildLinkCheck`); the tools phase requires exactly
`forbric/tools/forbric-link-check.jar`, plants an older install's `forbric-merge-tools.jar` and requires it cleaned away as
the one removal (merger 1be0fc66, with its red control). The paragraph below is the lane's reasoning at c4199989; its
"both sides of LINK's merge" form no longer exists. The tools phase's RED note was re-measured on forbric-link-check.jar by
INSTFIX.)

D44 had C6 delete the merge-tool and offline phases; D70 (later) found both premises live, and the cover check (D89) finds
none: no unit test drives `LinkCheckTool`'s unpack-over-a-stale-copy (git grep `unpackTools`/`forbric-merge-tools` in the
installer tests: none), and nothing else runs an install with the network denied. Deleting them would drop live checks
(D34). So both stay, re-pointed to follow whichever checker the installer carries:

- build step (at c4199989 only, superseded; the stage tip has the first form alone, see the note above): asked the
  installer's build file — `buildLinkCheck` (LINK) built the kernel's `linkCheckJar` and excluded `buildLinkCheck`;
  `buildMergeTools` (the lane's tree before LINK) built the loader's `mergeToolsJar` and excluded `buildMergeTools`; neither
  was exit 3. This was LINK's `m17-scratch-repoints.diff` made to work on both sides of LINK's merge.
- tools phase (at c4199989; the stage tip requires exactly `forbric-link-check.jar`): the one jar under `forbric/tools/`
  (it FAILed on none or several); its unpacked copy is spoiled and must come back as the bundled bytes. When it was not the
  merge tools, an older install's `forbric-merge-tools.jar` was planted and had to be cleaned away, named in the log, as
  the only removal.
- offline phase: unchanged (its `[link-check] ... dangling references: 0` regex is LINK's verdict line, kept by LINK), plus
  "the clean-up removed nothing".
- m17 prints no `[kernel] SKIP (C6` line (it printed none at 495e48fc either; `grep -c 'SKIP (C6' gate-m17-installer.sh` = 0).
- NativeReferenceLineContractTest still finds `"$NATIVE_REFS_FROM_SIDECAR"` in m17 (no edit needed).

### doctor-peak-resident — done

Measured on gate-m17's cold first install (nothing seeded), sampling `.forbric-build` and `libraries/net/forbric` with
`du -sk` every half second (doctor-samples-run3.txt). The size only grows during an install and nothing is deleted at its
end, so the highest sample is the last one, **476 904 KiB** (`.forbric-build` 405 104: NFRT cache 255 228, dl 73 540, out
67 488, tools 5 708, nfrt-work 3 140; `net/forbric` 71 800), plus the kernel's 14 third-party jars (~3.6 MiB, from the
bundled manifest): about 470 MiB. (The first version of this record said "peak 477 968 KiB": those samples came before the
gate wiped `run/installed` and were the previous run's size, review A F3; Doctor's javadoc, f0f7bc66, says the same as this
paragraph.) A sampler that slow misses only what lives under half a second; downloads are renamed into place, not copied. Doctor: `PEAK_MB = 480`, `RESIDENT_MB = 470` (MiB, as du
counts), javadoc rewritten. The old 730/190 were estimates from the merged-base era; what the 190 counted is not recorded.
A NeoForge-only install alone keeps more than 190, mostly NFRT's cache. An upgrade from beta2 ends with `.forbric-build`
about 40 MiB larger than a cold install (INSTFIX, real-player layout: 438 896 vs 398 024 KiB; 58 files a cold install does
not have, 42.9 MiB by size: `dl/` 27.3 MiB of beta2's downloads, 16 of them MinecraftForge's own jars, 13.4 MiB; NFRT's
cache for 26.2.0.88, 12.5 MiB; beta2's NFRT work files, 3.1 MiB; `c6/instfix/player-extra-files.txt`). The clean-up never
touches `dl/` or NFRT's caches (critic 19), and the owner has not ruled on `dl/` (open item).

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
| installer `linkGateTest` (its 8 classes; since LINK it is `installerTest`) | BUILD SUCCESSFUL, 9 PASS lines. (The first version said its `--artifacts` installs exercise the clean-up hunk; the log has no `[cleanup]` line, so that claim is withdrawn, review B 13) | linkGateTest.log |
| LINK's `installerTest` on the LINK preview | 10 classes, 0 failed | installerTest-linkpreview.log |
| run/compat test_gate_locale / test_gate_ports / test_gate_cache | 10 / 11 / 32 OK | (console) |
| gate-m17 run 1 (tri-eco fallback layout) | 112 PASS / 1 FAIL (the gate's own leftover check matched NFRT's nfrt-work/*/ats; fixed) | m17-run1.out |
| gate-m17 run 2, run 3 | RED on cold downloads (TLS failures from piston-data.mojang.com, maven.neoforged.net); run 2's phase U all PASS | m17-run2.out, m17-run3.out |
| gate-m17 run 4 (beta2 phase U, seeded caches) | **GREEN 113 PASS / 0 FAIL**, CAUGHT none | m17-run4.out |
| gate-m17 run 5 (final gate text, this branch) | **GREEN 113 PASS / 0 FAIL**, CAUGHT none | m17-run5.out |
| gate-m17 run 6 (final gate text + LINK preview) | **GREEN 116 PASS / 0 FAIL**, CAUGHT none | m17-run6-linkpreview.out |
| full kernel suite at 9bee7f76 (`-Pforbric.requireFixtures=all jar cleanTest test --continue`, 3m55s) | 5951 tests / 0 skipped / 5 failed = failures-w2d.txt's D58 five by name, 0 new | suite.log, suite-failures.txt |

No other gate was run (D87): only m17 reads the installer, and m0's installer link check is LINK's.

At the stage tip (merger, `c6/merge-w1.md`): `installerTest` 11 classes, 0 failed (LINK's 9 + these 2); gate-m17 **119 PASS
/ 0 FAIL**, CAUGHT none (1be0fc66 and 9cd04fc1). INST's own review-fix round (c6/inst/review-fixes/): suite 5951/0/5 (the
D58 five), m17 116/0, the no-old-install control red on phase U only, 6 source mutations red.

Skip baselines (D47, review B 13): no rows were due. The installer's tests are `main()` programs run by `installerTest`,
outside the kernel suite whose skips the TSVs list, and no kernel-suite test was added.

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
| gate-m17 phase U, clean-up switched off | all 22 removal checks red (in every green run at the stage tip; 20 at the lane's own c4199989) |

red-controls-unit.log has the mutation runs (each: exit 1 and the first assertion).

## Open items

(State at the stage tip; the first version's items are kept below with their outcome.)

- ~~LINK / merger: installerTest must report 10 classes~~ — done: it reports 11 at the tip (LINK added a ninth class of its
  own), both new tests among them.
- ~~LINK (critic 20): releaseAssets comment half false~~ — done by LINK (666ad56b, unchanged on the stage): the comment no longer
  speaks of zip entry times (review B 9).
- ~~Merger: nothing to re-point in m17 after LINK.~~ The merger did pin the checker (1be0fc66, review A F7). Measured: LINK's tip 666ad56b's diff (record excluded) applied
  uncommitted on this branch's tip, gate-m17 GREEN 116 PASS / 0 FAIL (m17-run6-linkpreview.out: the tools phase spoils
  forbric-link-check.jar and the planted forbric-merge-tools.jar is cleaned away as the one removal; phase U removes 21
  files incl. beta2's merge tools), and LINK's `installerTest` ran 10 classes, 0 failed, these two among them
  (installerTest-linkpreview.log); then reverted (`git apply -R`, clean tree). LINK's `m17-scratch-repoints.diff` is not
  needed: its two re-points are in a6423cc5 in a form that works before and after the merge.
- **Owner/orchestrator**: an upgraded install keeps beta2's downloads in `.forbric-build/dl/`, 16 of them (13.4 MiB)
  MinecraftForge's own (forge-*-universal/userdev, fmlcore, fmlloader, its language providers, binarypatcher,
  installertools, mergetool), because critic 19 says dl/ is never touched. No owner ruling by the end of C6 wave 2
  (`c6/owner-answers.md` has no dl/ section), so they stay; a structural rule is drafted in INSTFIX's record.
- **Owner/DOCS**: README's disk numbers and its uninstall paragraph (drafts below); no README change in this wave.
- **ACCEPT (Windows)**: the clean-up runs on the real `.minecraft` there and deletes old `libraries/net/forbric/*` (critic 33).
  The junction guard and the held-open failed delete have never run on Windows; INSTFIX's record lists the exact steps.
- NFRT's `nfrt-work` keeps one timestamped directory per step and run (~3 MB a build); not this lane's, recorded only.

## Player-facing wording (drafts only; README is not changed in this wave)

> **Superseded.** The upgrade paragraphs and the release-note line below overclaim (an upgrade keeps about 40 MiB of the
> older version's downloads, review A F4 / B 11) and the release-note size was the gate's artificial layout (review B 2).
> The corrected drafts are in `stage4-c6-instfix.md`; the disk-requirement lines below still hold.

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
