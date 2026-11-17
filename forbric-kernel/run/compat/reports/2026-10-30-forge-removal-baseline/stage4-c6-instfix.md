# Stage 4 C6 wave 2 — lane INSTFIX (INST's review fixes reviewed, INST's record brought to the tip, check-w1 C-1/C-2/C-4/C-6/C-7, dl/)

Branch `s4-c5-c6instfix`, cut from the stage tip 9cd04fc1 (worktree `.claude/worktrees/s4c5-c6instfix`, ports 46920-46939
from `.stage4/lane-env.sh`). Logs: `~/Forbric-backups/stage4-notes/c6/instfix/` (paths below are relative to it). Harness:
D68 (pinned build, plain `--offline` from `.stage4/props.txt`, no init script, no private Gradle home), every Gradle run and
installer run through `.stage/heavy.sh`, gate-m17 as `.stage/client.sh .stage/heavy.sh`. No download: phase U's beta2 cache
was cloned (`cp -c -R`) from the stage worktree's `run/.gate-downloads/m17` (SHA-1 ae2ff4fa… checked by the gate); m17's
cold first install fetches Mojang/NeoForge files by design, as at every m17 run. No mod jar.

Bootstrap: two PROBLEMs, as every lane since wave 2c: `.stage4/minecraft` was the relative `../.stage/minecraft` (relinked
to the absolute `suspicious-chaum-b28730/.stage/minecraft`), and `forbric-kernel/build/sweep100-mac` has no source in the
main checkout (nothing here reads it).

## Commits (on 9cd04fc1)

| sha | subject |
|---|---|
| fb823db7 | Keep a library a profile names in a comma-joined list, by a directory above it, or in another letter case |
| 09c6c4ec | gate-m17 asks its installer for English, re-measures the tools phase's red on the kernel's checker, and says what phase U's leftover check reads |
| 5750fa52 | the record: this file, and `stage4-c6-inst.md` brought up to the stage tip (C-1 and the merger's list) |
| 976f25f6 | (review fixes) Keep a library a profile names with net/forbric itself in another letter case, by colon-joined coordinates, or in a sub-folder of its version directory |
| (this commit) | (review fixes) this file's "INSTFIX review fixes" section, §3/§6/§7 corrected, `stage4-c6-inst.md`'s two stale points |

## 1. Review of INST's review-fix commits (c27cc306, f0f7bc66, 79cd0f6b; lane SHAs 8c0e0773, 3427f8aa, 9a5e8c11)

Read in full as a fresh adversarial reviewer, then probed with the real classes (`probe/`, a `collect()` probe on the tip's
installer classes). Verdicts:

| rule (commit) | verdict | evidence |
|---|---|---|
| coordinates anywhere, with sub-group / classifier / `@ext` (c27cc306) | holds | test cases A/G of review A; probe |
| paths anywhere, stopping at `$` (c27cc306) | **incomplete — fixed (fb823db7)**: a path list joined by a character the pattern does not stop at ran on over the next path, so only the first library counted. `-DmergeModules=net/forbric/d/1/d-1.jar,net/forbric/e/1/e-1.jar` (NeoForge's own profile uses comma lists in `-DmergeModules`/`-DignoreList`) kept `d` and deleted `e`; the same with `|`. | probe output (case 2: one reference for two paths); red control below |
| — (not in any commit) | **missing — fixed (fb823db7)**: a profile naming a directory above a library's version directory (`-p ${library_directory}/net/forbric/modules`) kept nothing under it | red control below |
| — (not in any commit) | **missing — fixed (fb823db7; completed by 976f25f6, review R-1: the patterns themselves matched only a lower-case `net/forbric` / `net.forbric`)**: the comparison was case-sensitive. On Windows and macOS a launcher finds `net/forbric/Cased/1/Cased-1.jar` at `net/forbric/cased/1/…`; the clean-up deleted it. `isProfile` was case-sensitive too, so an unreadable `versions/Upper/upper.json` was skipped instead of stopping the library half. Both now ignore case (only ever keeps more) | red control below |
| junction / "other" = link; `toRealPath` per step (c27cc306) | holds by reading (JDK: a junction is `isOther()` and a directory, never `isSymbolicLink()`; the walk skips it; every unreadable or redirected case resolves to keeping). **Never run on Windows** (C-2, section 7) | code; macOS symlink leg in OldInstallCleanupTest |
| only `versions/<id>/<id>.json` (and an inherited parent) stops the library half; other unparsable JSON skipped and named (c27cc306) | holds (case-insensitive since fb823db7) | OldInstallCleanupTest; m17 phase U "skipped, and named" |
| BOM accepted (c27cc306) | holds | OldInstallCleanupTest |
| v0.1.0's `.forbric-build/<mc>-<forge>/` retired (c27cc306) | holds: v0.1.0's installer wrote `.forbric-build/" + mcVersion + "-" + forgeVersion` (`git show v0.1.0:forbric-installer/…/Installer.java:304`); this installer's own top-level names are `dl`, `nfrt`, `nfrt-work`, `out`, `tools`, none of which the glob `[0-9]*.[0-9]*-[0-9]*.[0-9]*` matches; a match holding a current file is kept whole (guard) | code; OldInstallCleanupTest RETIRED_BUILD |
| "stopped part-way" line, not counted as a failed file (c27cc306) | holds | OldInstallCleanupTest `anUnexpectedFailureStopsAndSaysSo`; m17 phase U check_absent |
| test pins English and restores the caller's language and switch (c27cc306) | holds: OldInstallCleanupTest OK under `-Duser.language=zh -Duser.country=CN` (`unit-zh-locale.log`) | |
| failed delete held open on Windows (c27cc306) | holds by reading (`FileInputStream` opens without `FILE_SHARE_DELETE`); never run on Windows (C-2) | |
| Doctor javadoc (f0f7bc66) | holds: matches doctor-samples-run3.txt (0.5 s, last sample 476 904 KiB, the ~249/~3/~72/~66/~70/~6/~4 parts) | |
| keep-old names only the old kernel; merged base in UP_GONE (79cd0f6b) | holds | m17 below: 22 removal checks, merged base among them |
| non-profile JSON planted (79cd0f6b) | holds | m17 "skipped, and named (1)" |
| refusal check on `[Forbric/Install]`, tied to control N in the same run (79cd0f6b) | holds; the tie is guarded by `[ "$PY_RC" -eq 0 ]`, and when PY_RC ≠ 0 the gate is already red on "the profile resolved like a launcher would" | m17 "the refusal pattern matches control N's refused launch (2)" |
| lazy beta2 layout, failure reddens phase U alone (79cd0f6b) | holds by reading (`lay_out_beta2` sets `BETA2_FAIL`; phase U FAILs on it; nothing else reads it). INST's rf2 control showed it red only on phase U | c6/inst/review-fixes/m17-rf2-no-old-install.out |

Red controls for fb823db7 (`red-controls-spellings.log`), each a source mutation compiled with the test, run as a plain
class path:

| control | result |
|---|---|
| the stage tip 9cd04fc1's `OldInstallCleanup` with the new test | exit 1: e, g, modules/m, cased, UpDisk removed though named |
| overlapping scan off (`from = path.end()`) | exit 1: e, g removed |
| ancestor rule off | exit 1: modules/m removed |
| unit not lower-cased (re-run after adding a mixed-case directory on disk) | exit 1: UpDisk removed |
| referenced path not lower-cased | exit 1: cased removed |
| `isProfile` case-sensitive | exit 1: "a profile in another letter case is a profile" |
| tip of this branch | OK (and OK under a zh JVM locale) |

## 2. INST's record brought to the stage tip (C-1 and the merger's list)

`stage4-c6-inst.md` is rewritten in place where it was stale, with a pointer to this file at its top:

| point | now in the record |
|---|---|
| merge-tools dual form (C-1) | gone: the installer carries only `forbric-link-check.jar`, m17 requires exactly that (1be0fc66); the clean-up item and the tools-phase section say so |
| phase U figures (C-1, review B 2) | 22 files / 258.5 MB at the tip; a real beta2 player (no keep-old profile) **23 files / 261.6 MB**, measured here on a clone of the same beta2 install (`player-upgrade.log`) — the review's "~262 MB" estimate holds |
| m17 count, installerTest classes (C-1) | 119/0 at the tip; 11 classes |
| Doctor's peak (C-1, review A F3) | 476 904 KiB = the last sample, every half second, as the javadoc |
| each review-fix rule (C-1) | described one by one under installer-cleanup-D6, with the commit and review finding |
| player drafts overclaim (A F4 / B 11) | marked superseded; corrected drafts in section 8 here |
| release-note size (B2) | re-measured: 23 files, 261.6 MB on a real beta2 upgrade |
| critic 20 (B9) | done by LINK (666ad56b) |
| `.gitignore`, `InstallerText` outside owns_files (B10) | declared under Commits |
| no D47 rows; linkGateTest-log claim (B13) | "no rows due" stated; the `--artifacts` claim withdrawn (the log has no `[cleanup]` line) |
| subject of d89e1442 overclaims (B12) | recorded (not rewritable) |

What an upgrade keeps (A F4 / B 11, re-measured on the real-player layout, `player-extra-files.txt`): `.forbric-build` ends
**about 40 MiB** larger than a cold install (438 896 vs 398 024 KiB by du): 58 files a cold install does not have, 42.9
MiB by size — `dl/` 27.3 MiB of beta2's downloads (16 MinecraftForge jars, 13.4 MiB; the rest NeoForge .88's universal and
userdev and old library versions), NFRT's cache for NeoForge .88 12.5 MiB, beta2's NFRT work files 3.1 MiB.

## 3. C-4: m17's tools-phase RED note, re-measured on forbric-link-check.jar

(Re-measured by the INSTFIX review fixes, R-6, with the evidence the first measurement lacked: `review-fixes/c4/`.)

- **Setup.** The installer jar m17 built at 976f25f6 (`tip.jar`, SHA-1 696b8805…) and a copy with only `LinkCheckTool`
  replaced (`mut.jar`). The replacement's `unpackTools` returns a copy that is already there, which is the old behaviour;
  the source is `LinkCheckTool-mutated.java` and the diff is `mutation.diff`. Each ran with `-Dforbric.dialogLanguage=en`
  through `heavy.sh` on its own APFS clone of that m17 run's `run/installed`.
- **Before each run.** `.forbric-build/tools/forbric-link-check.jar` was spoiled as the phase spoils it. Before spoiling it
  was the bundled entry, SHA-1 1a67d0ac…; after spoiling it was be919653… ("not a jar at all").
- **Mutated (`mut.log`).** Exit **1** with:
  - `[link-check] produced no summary line (exit 1)`;
  - `install failed: the game base and the NeoForge runtime do not link (references into the game base, exit 1)`;
  - `Error: Could not find or load main class net.forbric.kernel.linkcheck.LinkCheck`.

  The spoiled file stays (be919653…).
- **Unchanged (`tip.log`).** Exit **0**. Both link checks report `dangling references: 0` (1 243 939 and 106 993 references
  judged), followed by `Installed.`. The file is the bundled bytes again (1a67d0ac…).

The gate's comment (09c6c4ec) says exactly this, so it stays as it is. The first measurement (`c4-mut.log`, `c4-tip.log`)
had only the exit codes and the installer output, and its mutation source was not kept.

## 4. C-6: m17's `[cleanup]` checks and the installer JVM's language

Every run of the installer the gate built now passes `-Dforbric.dialogLanguage=en` (`INSTALLER_LANG`, 7 invocations; the
beta2 installer run is untouched, the gate reads only its `Installed.` line). Chosen over re-keying the checks: the
`[cleanup]` tag is the same in both languages but every assertion reads the sentence (removed/kept/skipped/summary), and the
summary's number is already formatted with `Locale.ROOT`.

- **Green with a Chinese default locale forced on the installer** (the single forced-zh m17 run): a scratch `java` shim
  first on PATH added `-Duser.language=zh -Duser.country=CN` to every run of `…/build/libs/forbric-kernel-installer-*.jar`
  and passed every other java command through (`m17-zh-shim-invocations.log`: the 7 installer runs). Every `[cleanup]` check
  passed: reinstall "removed 0 file(s)" ×2, tools phase "names it, and nothing else" and "one file removed in all", phase U
  reverse control 22/22 red, "removed exactly those 22 files", "none failed", "did not stop part-way", "skipped, and named",
  each of the 22 "removed, and named in the log" (`m17-zh.out`, `m17-zh-logs/`). That run ended **RED 117 / 2**: phase U's
  client joined the world and its render thread stopped logging one second later (`m17-zh-logs/gate-m17-upgrade-client.log`:
  last Render-thread line 23:49:19, the server ticked on to the 300 s timeout), so "it entered the world" and "it left
  cleanly" failed. Not a language effect as far as anything shows — the clean-up's output and the profile checks were green,
  the shim touches no client JVM, and the plain re-run below booted the same phase green — but its cause is **not found**;
  recorded as an unexplained one-off.
- **Red control**: the same installer jar under the same zh locale without the property, on a clone of m17's install:
  `[cleanup] 已删除旧版 Forbric 留下的 0 个文件（0.0 MB）`, and the gate's check "the clean-up removed none of this install's own
  files" matches 0 lines (FAIL); with the property it matches 1 (PASS) (`c6-zh-red-control.log`).

## 5. C-7: phase U's "no MinecraftForge or merged product" line

Decided after the dl/ ruling (section 6: no ruling, dl/ stays). The check reads `.forbric-build`'s top level, `out/` and
`tools/`; the line now says exactly that: ".forbric-build's top level, out/ and tools/ hold no MinecraftForge or merged
product (dl/ and NFRT's caches are not judged here)". dl/ is judged separately as unchanged (56 files).

## 6. dl/ (owner)

`c6/owner-answers.md` has no dl/ section (re-read before (5) and again before this record). So beta2's MinecraftForge
downloads stay in `dl/` (16 jars, 13.4 MiB, list in `player-extra-files.txt`), the record and the player draft say so, and
C-7's line is narrowed instead of extended. If the owner later rules "remove", a structural rule that names no
MinecraftForge artifact: remove a file directly under `.forbric-build/dl` that no input of the current install names (the
installer knows its inputs: the NeoForge userdev, universal and library jars it resolves and the server jar), never one this
install read, with a red control (a current input kept) — and phase U's `dl/ is untouched` check becomes "exactly those
files gone, every current input kept". (Review R-7: as drafted, the rule would also remove the cached inputs of a Forbric
install for another Minecraft version in the same `.minecraft`. That costs only a re-download on that version's next
install. The other way is to keep the inputs of every installed Forbric profile's version. The owner's ruling should pick
one of the two.)

## 7. C-2: Windows-only paths, for CI and ACCEPT

(Rewritten by the INSTFIX review fixes, R-3 and R-4. The earlier version junctioned a scratch install's `libraries` to the
player's real `%MC%\libraries` and planted files in `%MC%`. If the guard under test were broken, that clean-up would have
deleted real `net\forbric` libraries that other real profiles use. It also went beyond owner answer 4. Nothing below writes
into `%MC%` except the one install that answer allows.)

Two Windows-only paths are in the code since c27cc306: the junction guard and the failed delete of a file a running game
holds open. Neither has run on Windows yet.

**A. CI runs both first (R-4).** CIPREP's `build.yml` (d126640c on the stage) runs `installerTest` in the
`development-tools` matrix, which includes `windows-latest`. Since 976f25f6, OldInstallCleanupTest on Windows:
- holds a library open with a `FileInputStream` (no `FILE_SHARE_DELETE`) and requires one named `could not remove` line,
  with the run carrying on;
- makes a real directory junction with `cmd /c mklink /J` (no privilege needed) from a scratch install's `libraries` to a
  scratch target that holds a library the install's profiles do not name. It requires that library to stay and the
  junction to be named in a `[cleanup] left` line, then removes the junction with `rmdir`.

On the first push of ci/remove-forge-stage2, the merger reads the windows-latest `installerTest` log:
- `OldInstallCleanupTest: OK` with **no** `NOT RUN` suffix: both legs ran and held. That is C-2's first Windows evidence.
- a `NOT RUN` suffix names the leg that could not run (mklink failed, with its output). That leg goes to ACCEPT, step C.
- red: if the assertion names the junction or the held-open file, the guard is broken on Windows (a defect, not a test
  problem). If it is a `FileSystemException` from `createSymbolicLink`, the runner refused to create symbolic links (the
  shared test layout makes some). That is a test-environment red, never measured, and the fix is in the test.

**B. ACCEPT, on the real `.minecraft` (owner answer 4: back up `.minecraft` afresh first).** The one allowed PCL2-style
install is also the held-open leg, when the player's `.minecraft` still has the v0.3.1-beta2 install:
1. Start beta2's version in PCL2 and leave it at the title screen. It holds its `libraries\net\forbric\…` jars open.
2. Run the C6 installer into `%MC%`. Expect exit 0, one `[cleanup] could not remove …` line per held jar, and a summary that
   ends `; N could not be removed and were left in place`.
3. Close the game and run the installer again (a re-run of the same install, not a second install). Expect those N to go
   and no `could not` line.

Step 3 is a second run of the installer into `%MC%`. The orchestrator confirms it is inside owner answer 4 before ACCEPT
runs it; if not, ACCEPT skips step 3 and the held-open evidence is CI's alone. Without a beta2 install in `%MC%`, B is just
the normal install, and the held-open leg rests on CI.

**C. ACCEPT, junction leg: only if CI's run says `NOT RUN` for it, and entirely in scratch.** Never junction to, or plant
anything in, `%MC%`. In PowerShell, with `$inst` the C6 installer jar and `$T = 'C:\ForbricJunctionTest'`:

```powershell
$T = 'C:\ForbricJunctionTest'
New-Item -ItemType Directory -Force "$T\A\versions\sentinel", "$T\A\libraries\net\forbric\junction-sentinel\1",
  "$T\B\versions\26.2", "$T\B2\versions\26.2" | Out-Null
Set-Content -Encoding ascii "$T\A\versions\sentinel\sentinel.json" '{"id":"sentinel","libraries":[{"name":"net.forbric:junction-sentinel:1"}]}'
Set-Content "$T\A\libraries\net\forbric\junction-sentinel\1\junction-sentinel-1.jar" 'sentinel'
foreach ($i in 'B','B2') { Copy-Item "$env:APPDATA\.minecraft\versions\26.2\26.2.json","$env:APPDATA\.minecraft\versions\26.2\26.2.jar" "$T\$i\versions\26.2\" }
cmd /c mklink /J "$T\B\libraries" "$T\A\libraries"
java -jar $inst --dir "$T\B" --mc 26.2
```

- Reading from `%MC%` (the copies above) is harmless. Use `%MC%`'s real location if it is not `%APPDATA%\.minecraft`.
- The B install is a cold install into scratch, so it downloads what an install needs (about 480 MB, in `$T`).
- Expect exit 0, a line `[cleanup] left C:\ForbricJunctionTest\B\libraries in place: it is a link (a symbolic link or a
  junction), …`, and `A\libraries\net\forbric\junction-sentinel\1\junction-sentinel-1.jar` still there. B's current
  libraries land in `A\libraries` through the junction; that is scratch.
- **Control:** make `$T\B2\libraries` a plain copy instead (`robocopy "$T\A\libraries" "$T\B2\libraries" /E`) and install
  into B2. B2's profiles do not name the sentinel, so the copy's sentinel jar must be removed, by name. That shows the leg
  would see a broken guard.
- One run each of two variants is worth it: the junction at `B\libraries\net\forbric` (to `A\libraries\net\forbric`), and
  `B\.forbric-build` as a junction to a scratch folder holding a retired name such as `clean.jar` (it must stay).
- **Clean-up order matters:** first `cmd /c rmdir "$T\B\libraries"`, which removes the junction only (never `rmdir /s` on a
  junction). Only then `Remove-Item -Recurse -Force $T`, since an older PowerShell's recursive delete follows a junction
  into its target.

**D. PCL2's version isolation** (part of the B install): `%MC%\versions\<id>\` is the game directory. Any JSON there that
does not parse must show as `[cleanup] skipped …` and never stop the clean-up.

On Windows, the unit test can also be run by hand, from a checkout of the C6 tip, in PowerShell (a `**` glob does not
expand in cmd or PowerShell):
`javac -d oict ((Get-ChildItem -Recurse -Filter *.java forbric-kernel-installer\src\main\java).FullName) forbric-kernel-installer\src\test\java\net\forbric\installer\kernel\OldInstallCleanupTest.java`
then `java -cp oict net.forbric.installer.kernel.OldInstallCleanupTest C:\temp\oict`. Expect `OldInstallCleanupTest: OK`
with no `NOT RUN` suffix.

## 8. Player-facing wording (drafts; README / README.zh-CN are not changed in this wave)

Disk requirement (unchanged from INST's draft, still true): "**An internet connection**, and about 480 MB of free disk. Almost
all of it stays afterwards: it is what makes the next install fast." / "**网络连接**，以及约 480 MB 的可用磁盘空间。装完后这些几乎都会保留，下次安装因此会很快。"

Upgrade paragraph (replaces INST's, which said nothing needs deleting by hand):

> Installing a new version over an older Forbric removes most of what the older version left behind (about 260 MB when
> upgrading from 0.3.1-beta2). About 40 MB of the older version's downloads stay in the `.forbric-build` folder; you can
> delete that folder to get the space back (the next install downloads what it needs again). Files another installed
> version still uses are kept.

> 在旧版 Forbric 上安装新版本时，安装器会自动删掉旧版留下、已经没有版本在用的大部分文件（从 0.3.1-beta2 升级约 260 MB）。旧版的约
> 40 MB 下载文件会留在 `.forbric-build` 文件夹里；想要回这部分空间，可以删掉这个文件夹（下次安装会重新下载需要的文件）。其他已安装版本还在用的文件会保留。

Release notes line:

> The installer now cleans up most of what older Forbric versions left behind (about 260 MB on an upgrade from 0.3.1-beta2).
> 安装器现在会清理旧版 Forbric 留下的大部分文件（从 0.3.1-beta2 升级时约 260 MB）。

## Tests and gates

| what | environment | result | log |
|---|---|---|---|
| OldInstallCleanupTest (plain class path, this branch) | en; and `-Duser.language=zh -Duser.country=CN` | OK; OK | (console), unit-zh-locale.log |
| kernel `installerTest` (heavy.sh, pinned `--offline`) | | **11 classes, 0 failed** (OldInstallCleanupTest, ReproducibleRuntimeTest among them) | installerTest.log |
| gate-m17, forced-zh installer (shim), tree 9cd04fc1 + this lane's uncommitted edits = fb823db7/09c6c4ec's content | `source .stage4/lane-env.sh` (FORBRIC_OLD=<lane>/.stage4, GATE_PORT 46920, M12–M32 ports 46921–46926), FORBRIC_GATE_CACHE=0, MC_DIR unset, client.sh + heavy.sh, cold first install | RED **117 PASS / 2 FAIL** (phase U's client stalled after joining, section 4); every `[cleanup]` check PASS; CAUGHT none | m17-zh.out, m17-zh-env.txt, m17-zh-logs/ |
| gate-m17 plain, same tree | same, no shim | **GREEN 119 PASS / 0 FAIL**, CAUGHT none; phase U reverse control 22/22 red, removed exactly 22, 401 player files identical, booted | m17-run2.out, m17-run2-env.txt |
| full kernel suite at 09c6c4ec (`-Pforbric.requireFixtures=all jar cleanTest test --continue`) | heavy.sh, pinned `--offline` | **5966 tests / 0 skipped / 5 failed** = failures-w1.txt's five by name (LiquidBounce ×3 fixture absent, SharedFinalSourceActual ×2 opt-in fixture absent; D58, D87a), 0 new, same count as the tip (no kernel-suite test changed), 3m51s | suite.log, suite-failures.txt |

No other gate (D87/D89): only m17 runs the installer; the clean-up and the gate are this lane's only code.

## Red controls

| control | result | log |
|---|---|---|
| fb823db7's five rules, each taken out; the stage tip's clean-up | each red on its own case (section 1) | red-controls-spellings.log |
| C-6: installer under a zh locale without the pin | the gate's `[cleanup]` check matches 0 (FAIL); with the pin 1 | c6-zh-red-control.log |
| C-4: unpack that keeps an existing file | install exit 1, no link-check summary | c4-mut.log |
| m17 phase U, clean-up off (the gate's own reverse control) | 22/22 removal checks red | m17-run2.out |

## Open items

(Updated by the INSTFIX review fixes.)

- **Owner:** dl/. Beta2's 16 MinecraftForge jars (13.4 MiB) stay after an upgrade. The rule is drafted in §6, and the
  ruling should also say whether another Minecraft version's cached inputs may go (R-7).
- **Merger / CI (R-4):** at the first push of ci/remove-forge-stage2, read the windows-latest `installerTest` log as §7 A
  says. Expect `OldInstallCleanupTest: OK` with no `NOT RUN` suffix. It is the first run anywhere of the held-open delete
  and the junction case. The merger should also know that `gate-m17-installer.sh` is shared with VER (merge-tree clean).
- **Orchestrator:** confirm whether §7 B3's re-run into `%MC%` is inside owner answer 4.
- **ACCEPT:** §7 B (the real install, held-open leg), §7 C only if CI reports the junction leg `NOT RUN` (in scratch only),
  and §7 D.
- **ACCEPT (R-5):** phase U's client stalled once after joining the world (forced-zh run). It has been green three times
  since (lane re-run, the review's run, this lane's review-fix run with a `jstack` watcher that had nothing to catch). The
  cause is not found, and it may be a kernel defect. ACCEPT watches m17 and gates-all for it; walls.md has the entry. A
  harness change making m17 dump the client's threads on its own timeout is left to a later harness lane.
- **DOCS:** the drafts of §8 (README, release notes). The release-note figure is 260 MB / 23 files (real player), not
  INST's 220.
- **VER:** no `[cleanup]` line names a version. Phase U's refusal check keys on `[Forbric/Install]`, so the "0.4 or
  earlier" reword cannot blind it (control N must still match the same pattern).

## INSTFIX review fixes (C6 wave 2, review `c6/review-c6instfix.md`)

I re-read `c6/owner-answers.md` before deciding the fixes and again before writing this section. It still has no dl/
section. Logs are under `instfix/review-fixes/`.

| finding | verdict | what was done |
|---|---|---|
| R-1 `net/forbric` / `net.forbric` themselves only in lower case | **confirmed**: the review's probe, re-run on 976f25f6's classes against the tip's, keeps S1/S2 now (`probe-fix.log`; the tip's result is the review's `probe/probe-tip.log`) | **fixed, 976f25f6**: both patterns `CASE_INSENSITIVE`; test cases `Net/Forbric/upperprefix/…` and `NET.FORBRIC:uppercoord:1` |
| R-2 a sub-directory of a named version directory emptied | **confirmed** (probe S3) | **fixed, 976f25f6**: a unit that holds a file a profile names is a version directory in use, and every unit below it is kept. The review's caution holds: no bare prefix counts, so an artifact directory that holds a file (`meta/maven-metadata.xml`) keeps no unnamed version below it (`meta/2`). Also fixed S4 (colon-joined coordinates: the coordinate scan resumes one character after each match, as the path scan does). S8–S10 (space in a path, `./`, a letter right before `net/`) stay as recorded: unrealistic, and fixing them would loosen what counts as a name. |
| R-3 §7's junction steps aim at the real `.minecraft` | **confirmed** | **fixed in this record**: §7 rewritten. The junction leg runs entirely in `C:\ForbricJunctionTest` (A as the target, B junctioned, B2 the plain-copy control), with PowerShell commands. Nothing is planted in `%MC%`. The real `%MC%` gets only the one allowed install; its re-run in step B3 waits on the orchestrator's confirmation. The `**` glob is replaced by a PowerShell line. |
| R-4 CI's Windows job runs the held-open leg first | **confirmed**: CIPREP's `build.yml` (`s4-c5-c6ciprep`) has `development-tools` on `[ubuntu-latest, windows-latest, macos-latest]` and runs `installerTest` there | **fixed, 976f25f6 + §7 A**: OldInstallCleanupTest gains a Windows-only junction case (`cmd /c mklink /J`, NOT RUN with mklink's output if it fails), so CI covers both Windows legs. The merger reads the windows-latest log as §7 A says. **Not run here** (macOS): neither the junction case nor its red control has run anywhere. The first evidence is CI's. |
| R-5 the forced-zh run's phase U stall has no owner | **confirmed as an open item** | walls.md now names ACCEPT as the owner, watching m17 and gates-all. This lane's m17 re-run had a `jstack` watcher on phase U's client (see below). A harness change that makes m17 dump the client's threads on its own timeout is left to a later harness lane, as the review says. |
| R-6 record errors | **confirmed**, each one | `stage4-c6-inst.md`: "20" → 22, and the tools-phase bullets are marked "at c4199989, superseded". §3 here and the m17 comment: re-measured with the evidence saved (see C-4 below). checks.md: the first case-sensitivity mutation was green until the UpDisk case was added, so "each red" holds of the final test only. |
| R-7 info | **accepted** | Shared file: VER also edits `gate-m17-installer.sh` and `InstallerText.java`. `git merge-tree` of this tip with `s4-c5-c6ver` (67edcdf3) and with `s4-c5-c6ciprep` (f15dcac4) is clean; the merger should know the file is shared (handoff). The language pin also protects the older modsFolder check. The beta2 installer run (m17:94) stays unpinned: it reads only `^Installed\.`, and only on a cold cache re-layout. The §6 dl/ draft now says it would also drop another Minecraft version's Forbric inputs. |

Red controls for 976f25f6 (`red-controls.log`, the mutation diffs in `mutations/`). Each is a source mutation compiled with
the new test and run on a plain class path:

| control | result |
|---|---|
| 5750fa52's `OldInstallCleanup` with the new test | exit 1: upperprefix, uppercoord, i/2, withnatives/1/natives removed though named |
| path pattern case-sensitive again (m1) | exit 1: upperprefix removed |
| coordinate pattern case-sensitive again (m2) | exit 1: uppercoord removed |
| coordinate scan resumes at the match's end (m3) | exit 1: i/2 removed |
| sub-directories of a named version directory not kept (m4) | exit 1: withnatives/1/natives removed |
| an ancestor unit counts by the prefix rule instead of by a named file (m5, the over-keep the review warned of) | exit 1: "an unnamed sibling still goes: net/forbric/meta/2/meta-2.jar" |
| 976f25f6 | OK, and OK under `-Duser.language=zh -Duser.country=CN` |
| the Windows junction case | **not run** (macOS); CI's windows-latest `installerTest` is its first run |

Tests and gates of the review fixes (all on 976f25f6, clean tree except this record):

| what | environment | result | log |
|---|---|---|---|
| OldInstallCleanupTest, plain class path | en; `-Duser.language=zh -Duser.country=CN` | OK; OK | red-controls.log (last two lines) |
| the review's probe on 976f25f6 | plain class path | S1–S4 kept; S5–S7, S11 as before; S8–S10 still removed (recorded, not fixed) | probe-fix.log |
| kernel `installerTest` | heavy.sh, pinned `--offline` | **11 classes ran, 0 failed**, no `NOT RUN` | installerTest.log |
| gate-m17 | `source .stage4/lane-env.sh` (FORBRIC_OLD=<lane>/.stage4, GATE_PORT 46920, M12–M32 ports 46921–46926), FORBRIC_GATE_CACHE=0, MC_DIR unset, `client.sh heavy.sh`, cold first install, plain PATH, a `jstack` watcher on phase U's client (150 s / 250 s) | **GREEN 119 PASS / 0 FAIL**, CAUGHT none. Phase U: reverse control 22/22 red, removed exactly 22, dl/ 56 files untouched, 401 player files identical, both clients `client-ready after 60 world tick(s)`. The watcher took no `jstack` because the client never ran 150 s | m17/m17.out, m17/env.txt, m17/gate-m17-upgrade-client.log |
| C-4 re-measure | see §3 | mutated exit 1, spoiled file stays; unchanged exit 0, bundled bytes back | c4/ |
| full kernel suite (`-Pforbric.requireFixtures=all jar cleanTest test --continue`) | heavy.sh, pinned `--offline` | **5966 tests / 0 skipped / 5 failed**: the five of failures-w1.txt by name (D58), 0 new. No kernel-suite test changed (the installer's tests run under installerTest) | suite.log, suite-failures.txt, suite-tree.txt |

No other gate: only m17 runs the installer, and the installer clean-up and its test are the only code changed.
