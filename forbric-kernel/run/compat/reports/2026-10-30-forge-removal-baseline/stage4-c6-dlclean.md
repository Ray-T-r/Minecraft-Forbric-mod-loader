# Stage 4 C6 — lane DLCLEAN (owner answer 6: the installer clears `.forbric-build/dl`)

Branch `s4-c5-c6dlclean`, cut from the stage tip a3ced60b (worktree `.claude/worktrees/s4c5-c6dlclean`, ports 46880-46899
from `.stage4/lane-env.sh`). Logs: `~/Forbric-backups/stage4-notes/c6/dlclean/` (paths below are relative to it). Harness:
D68 (pinned build, plain `--offline` from `.stage4/props.txt`, no init script, no private Gradle home), every Gradle and
installer run through `.stage/heavy.sh`, gate-m17 as `.stage/client.sh .stage/heavy.sh`. **Nothing was downloaded**: phase
U's beta2 cache was cloned (`cp -c -R`) from the stage worktree's `run/.gate-downloads/m17` (marker = beta2's SHA-1
ae2ff4fa…, checked by the gate), and every m17 run took `M17_SEED_CACHE` = an APFS clone of the stage worktree's
`run/installed/.forbric-build` (dl, tools, NFRT's cache, NeoForm's stamped game) from the merger's cold m17 at a5708f73.
After each run, no file in either install's `dl/` or `nfrt/` was missing from the seed or beta2's caches (0 new files);
the installer printed no "downloading" line. No zh run except the forced-zh unit test (D88b).

Bootstrap: two PROBLEMs, as every lane since wave 2c: `.stage4/minecraft` was the relative `../.stage/minecraft`
(relinked to the absolute `suspicious-chaum-b28730/.stage/minecraft`), and `forbric-kernel/build/sweep100-mac` has no
source in the main checkout (nothing here reads it).

## The ruling

Owner answer 6 (`c6/owner-answers.md`, 2026-11-18): on upgrade the installer deletes files under `.forbric-build/dl` that
no current input of this install names, never a current input, with a red control. Accepted side effect: cached
downloads for another Minecraft version are deleted too. This is the first of the two ways INSTFIX's review (R-7) left
open (`stage4-c6-instfix.md` §6).

## Commits

| sha | what |
|---|---|
| a5dd56ed | the rule (ArtifactBuilder, NeoForgeRuntimeBuilder, OldInstallCleanup, Installer, InstallerText en/zh), OldInstallCleanupTest's cases and controls, gate-m17's checks |
| a70abbf6 | gate-m17: the size info line before phase U printed no size (an awk program built inside nested quotes); no PASS/FAIL line changed |
| bec6bbd3 | gate-m17: the second-install cache check compares names, sizes, inodes and times instead of SHA-1s, so a file fetched again shows |
| (this commit) | this record; `stage4-c6-inst.md`, `stage4-c6-instfix.md`, `stage4-c6-docs.md` and the record README brought up to it; introduction.md / zh |

## What the rule is

- **The inputs, structurally.** `ArtifactBuilder.downloadInputs(mcDir, mcVersion)` names every file the build reads in
  `dl/` at this installer's pins: the NeoForge userdev jar (`userdevFile`), the files the NeoForge runtime is built from,
  and the server jar NFRT is handed (`SERVER_JAR`). The runtime's file names come from
  `NeoForgeRuntimeBuilder.inputFiles(nfa, dlDir, configLibraries)`, which is now the one place the runtime's fetch list is
  made: `build()` fetches exactly that map's files. The libraries are read from the userdev jar's own `config.json`, as
  the build reads them. So the set is what the build fetches, named whether or not this install fetched anything (a
  reinstall served from the cache fetches nothing and still keeps every input). No file or artifact is named by hand,
  MinecraftForge's included.
- **The clean-up.** `OldInstallCleanup.run(..., DownloadInputs)` clears `dl/` last, after `.forbric-build`'s retired names
  and `libraries/net/forbric`, so a failure to read the inputs (it would stop the clean-up with its own line) leaves the
  other two halves done. Every regular file under `dl/` whose path is not an input (compared without letter case, as
  library names are; it can only keep more) is removed and named, after one line said once:
  `[cleanup] clearing the download cache <dl> of every file this install does not use, cached downloads for another
  Minecraft version included; an install that needs one again downloads it again` (zh: `[cleanup] 清理下载缓存 <dl>：本次
  安装用不到的文件都会删除，其中也包括为其他 Minecraft 版本缓存的下载；以后哪次安装需要，会重新下载`). Each removal:
  `[cleanup] removed <file> (a cached download this install does not use)` / `[cleanup] 已删除 <file>（缓存的下载文件，本次安装
  用不到）`. The line is printed only when something there goes.
- **Never a current input**: the inputs are also added to the guard (`isCurrent`), so a rule that ignored them would
  still be stopped there.
- **Never anything outside `dl/`**: `.forbric-build` and `dl` are each refused, named, when they are a link or resolve
  elsewhere (the libraries half's `redirected` rule); a link inside is never followed or removed; a sub-directory the
  removals empty goes, then its parents up to `dl/`, never `dl/` itself.
- **A failed delete** (on Windows, a file held open) goes through the same `removeFile` as the other halves: named
  (`[cleanup] could not remove …`), counted in the summary, and the install carries on.
- **Who judges the cache**: only an install that built its game files (`Installer.install` passes the inputs when
  `artifactDir == null`). An install given them (`--artifacts`) read nothing in `dl/`, so it passes none and the cache is
  left as it is. `DevPrepare` runs the builder but not the clean-up, so `.dev/minecraft/.forbric-build/dl` (read by
  NativeCoremodParityTest) is not touched.
- **Summary line unchanged**: the count in `[cleanup] removed N file(s) an older Forbric left behind (X MB)` now includes
  the cache's files. For another Minecraft version's downloads "an older Forbric" is loose wording; the cache's own line
  says what goes. Left as is so the gate's existing exact matches stay (recorded, not changed).

## Measured

| what | result | log |
|---|---|---|
| gate-m17 phase U (beta2 install + keep-old profile) | **63 files removed, 285.8 MB** (22 outside `dl/`, 258.5 MB as before; **41 of beta2's 56 cached files, 27.3 MiB**); `dl/` ends as exactly the 17 inputs, each unchanged | m17-final.out, m17-final-logs/gate-m17-upgrade.log |
| of those 41, MinecraftForge's | 10 by the gate's name pattern (forge-*, fml*, its language providers, forgespi), 6.7 MiB; 14 with its tools (binarypatcher, installertools, mergetool ×2), **13.3 MiB** (INSTFIX's "16 jars, 13.4 MiB" was a looser count of the same files, not re-derived); the other 27 are beta2's fixed-name userdev jars (`forge-userdev.jar`, `neoforge-userdev.jar`), NeoForge 26.2.0.88's universal jar and library versions NeoForge 26.2.0.93's runtime does not take (asm, log4j, modlauncher, securemodules, …) | m17-final-logs/gate-m17-upgrade-dl-before.txt, -inputs.txt |
| a real player's upgrade (a clone of the same beta2 install, no keep-old profile, caches seeded as phase U seeds them) | **64 files, 288.9 MB** (INSTFIX measured 23 / 261.6 MB without the cache rule); `.forbric-build` ends 410 860 KiB against this run's fresh install's 394 884 KiB: 17 extra files, 15.6 MiB — NFRT's cache for 26.2.0.88 12.5 MiB and beta2's NFRT work files 3.1 MiB; `dl/` holds nothing extra | player-upgrade.log, player-before-du.txt, player-after-du.txt, player-extra-files.txt |
| second install right after the first | `[cleanup] removed 0 file(s)`; `dl/` identical by name, size, inode and time (17 files): nothing removed, nothing fetched again | m17-final.out |
| first install (seeded) and the offline reinstall | `removed 0 file(s)` each | m17-final-logs/ |

## Red controls

| control | result | evidence |
|---|---|---|
| (a) in-test: inputs ignored by the rule, guard off | red: each of the 6 inputs "a download this install reads was removed"; with the guard on, the same rule removes none | OldInstallCleanupTest `ignoringTheDownloadInputsTurnsItRedWithoutTheGuard` |
| (a) source mutations, each compiled with the test, plain class path | `downloadInputs` without the server jar: exit 1, "removed: server.jar"; without the runtime's files: exit 1, universal/bus/loader removed; rule and guard both ignoring the inputs: exit 1, userdev/universal/bus removed | mutations/a1-*, a2-*, a3-* (red-controls-unit.log, run-mutations.sh, each diff and run log) |
| (b) in-test: a clean-up that does not judge the cache | red on each of the 9 orphans ("a cached download this install does not read was kept") | `notJudgingTheDownloadCacheKeepsItsOrphans` |
| (b) source mutations | cache never judged: exit 1, orphans kept; only top-level files judged: exit 1, `sub/deeper/old-1.jar` kept; no announcement line: exit 1; removals named with the old line: exit 1, "a removal the log does not name"; links in the cache removed: exit 1; a failed delete thrown instead of logged: exit 1, "one failure counted" (the throw stopped the clean-up part-way) | mutations/b1-* … c2-* |
| (c) gate-m17 phase U, clean-up off (the gate's own reverse control) | **63/63** removal checks red, the 41 cache files among them | m17-final.out |
| (c) gate-m17 with mutation b1 (the installer never judges the cache; `m17-control-b1.diff`) | **RED 118 PASS / 45 FAIL**: the 41 cache removals "still there", "removed exactly those 63 files", "said that other versions' downloads go too", ".forbric-build's top level, out/, tools/ and dl/ hold no MinecraftForge or merged product" (got dl/fmlcore…), "dl/ does not hold exactly this install's inputs". The premise check (beta2's cache holds files this install does not read) and the second-install check stay green, as they should | m17-control-b1.out, m17-control-env.txt |
| second-install cache check | premise control on a clone of the install's `dl/`: a no-op listing compares equal; the same bytes copied and renamed into place (what a fetch does) compare different (new inode and time). A SHA-1 listing, the check's first form (a5dd56ed), could not see that | cache-files-premise-control.log |

Not measured: the Installer call site passing the inputs (a mutation passing `null` there) has no unit test (the
installer's tests do not run `Installer.install`); gate-m17 phase U is its check (the b1 gate control above shows the
same outcome as a `null` would: the cache is not judged).

## Tests and gates

| what | environment | result | log |
|---|---|---|---|
| OldInstallCleanupTest, plain class path | en; `-Duser.language=zh -Duser.country=CN` | OK; OK | (console) |
| kernel `installerTest` | heavy.sh, pinned `--offline`, lane-env | **11 classes ran, 0 failed** (ReproducibleRuntimeTest green on the refactored `NeoForgeRuntimeBuilder`) | installerTest.log |
| gate-m17 run 1, 2 (a5dd56ed, a70abbf6) | lane-env (FORBRIC_OLD = the lane's stamped `.stage4`, GATE_PORT 46880, M12–M32 46881–46886, HEAVY_LANE s4c5-c6dlclean), FORBRIC_GATE_CACHE=0, MC_DIR unset, M17_SEED_CACHE = the seed above | GREEN 163/0 each (run 1's info line printed no size, fixed in a70abbf6) | m17-run1.out, m17-run2.out, *-env.txt |
| gate-m17 control (b1) | same | RED 118/45 as above | m17-control-b1.out |
| **gate-m17 final, at bec6bbd3** | same; only `.md` files uncommitted | **GREEN 163 PASS / 0 FAIL, CAUGHT none** (was 119 at a5708f73: +41 cache removals, +1 premise, +1 announcement, +1 second-install cache identity; the "dl/ is untouched" check became "dl/ holds exactly this install's inputs") | m17-final.out, m17-final-env.txt, m17-final-logs/ |
| full kernel suite at a70abbf6 (`-Pforbric.requireFixtures=all jar cleanTest test --continue`) | heavy.sh, pinned `--offline` | **5969 tests / 0 skipped / 5 failed** = `c6/failures-w2.txt`'s five by name (D58), 0 new. No kernel-suite test changed (the installer's tests run under installerTest) | suite.log, suite-count.txt |
| the tests that read gate-m17, after bec6bbd3 | heavy.sh | LaunchInputCheckTest 20/0, LibStopTreeTest 4/0, NativeReferenceLineContractTest 4/0; run/compat test_gate_locale, test_gate_ports, test_gate_cache 53 OK; tools/test_ci_contract 4 OK | gate-readers.log, python-checks.log |

No other gate (D87/D89): only m17 runs the installer. The `user_data` digest step of phase U prints 112 `shasum: … No such
file` lines for player files whose names hold spaces; the merger's run at a5708f73 printed the same 112 (pre-existing,
not this lane's; the player-files check still compares like with like).

## Player wording (drafts updated in place)

`stage4-c6-docs.md` (release-notes draft "Disk space", en + zh, and the README hand-off) now says: an upgrade removes what
the older version left and no longer uses, its old downloads included, about 290 MB from 0.3.1-beta2; about 16 MB of the
older version's build cache stays in `.forbric-build`; another Minecraft version's cached downloads in the same game
folder are removed too and downloaded again by its next install. `stage4-c6-inst.md` and `stage4-c6-instfix.md` carry
dated notes; their older drafts are marked superseded. The 480 MB disk line is unchanged (a cold install is unaffected).

## Open items

- **CI (next run on ci/remove-forge-stage2)**: the Windows legs. `aDownloadThatCannotBeRemovedIsNamed` holds a cached
  download open with a `FileInputStream` on Windows; like INSTFIX's held-open case it has never run on Windows. The
  windows-latest `installerTest` log must show `OldInstallCleanupTest: OK` with no `NOT RUN` suffix.
- **ACCEPT (Windows PCL2 install, owner answer 4)**: the real `.minecraft`'s `dl/` loses every file that is not one of this
  install's inputs, also cached downloads of a Forbric install for another Minecraft version if the player has one.
- **Wording**: the summary line still says "an older Forbric left behind" for cache files of another Minecraft version
  (kept for the gate's exact matches); a later wording pass may change it with the gate.
- **DOCS / release commit**: README's upgrade paragraph from `stage4-c6-docs.md`'s corrected draft.
