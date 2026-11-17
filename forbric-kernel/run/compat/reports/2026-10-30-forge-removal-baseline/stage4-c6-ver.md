# Stage 4 C6 — lane VER: "Forbric 0.4 or earlier" is no longer true (wave 2)

Lane VER, branch `s4-c5-c6ver` (worktree `.claude/worktrees/s4c5-c6ver`), cut from the stage tip `9cd04fc1`. Nothing
pushed; main untouched. Ports 46900-46919. Notes: `~/Forbric-backups/stage4-notes/c6/{checks,handoff,walls}.md` under
"VER C6 w2"; logs in `~/Forbric-backups/stage4-notes/c6/c6ver/`.

## Why

Owner answer 3 (2026-11-17, D93): the first NeoForge + Fabric release is **0.4.x**. The merged base and the
MinecraftForge carrier came from every release up to v0.3.1-beta2 and from the 0.4.0-dev builds before Stage 4. So every
text that called the MinecraftForge-carrying Forbric "0.4 or earlier" (or "≤ 0.4", "as Forbric 0.4 did") tells a 0.4.x
player that their own version is the old one. Worst case: the old-install refusal, which a 0.4.x player only ever reads
about an older install.

## The wording

"**Forbric 0.3.x or earlier (or a 0.4.0-dev build that still had MinecraftForge)**" where a player reads it, and
"Forbric 0.3.x and earlier" in comments.

- Not "before 0.4.0" / "older than 0.4.0": Forbric's releases carry suffixes (v0.3.1-beta, v0.3.1-beta2), and if the first
  0.4 release is 0.4.0-beta it sorts before 0.4.0, so "older than 0.4.0" would include it.
- "0.3.x or earlier" covers v0.1.0, v0.2.0, v0.3.0, v0.3.1-beta, v0.3.1-beta2. The parenthesis names the only other source
  of a merged install: a 0.4.0-dev build from main before the removal. That matches the phrase UPG used in 3e33e7c4 for the
  carrier pack id ("v0.3.1-beta2 or older, or a 0.4.0-dev build that still had MinecraftForge").
- The refusal's advice is unchanged (owner default): "run the new installer again with the same game directory".
- No mod is named.

## What changed

Commits on `s4-c5-c6ver`:

| commit | what |
|---|---|
| `16c28a44` | user-visible texts + their tests + gate-m17 control N + the record's two current-behaviour lines |
| `cefbae32` | 39 comment phrases in 29 files say 0.3.x; new guard `OldReleaseWordingTest` |
| (record commit) | this file |

User-visible strings (every one changed):

| where | seen by | before | after | languages |
|---|---|---|---|---|
| `LaunchInputCheck.OLD_INSTALL` (kernel) | `latest.log`, the refusal's problem line | "this game was installed by Forbric 0.4 or earlier, still carries MinecraftForge (…)" | "this game was installed by Forbric 0.3.x or earlier (or a 0.4.0-dev build that still had MinecraftForge), still carries MinecraftForge (…)" | English only: a log line (see "Language" below) |
| `LaunchInputCheck.OLD_INSTALL_SWITCH_OFF` (kernel, was an inline literal) | `latest.log` with `-Dforbric.launchInputCheck=off` | "…does not apply to an install made by Forbric 0.4 or earlier: …" | "…by Forbric 0.3.x or earlier: …" | English only: a log line |
| `KernelNeoConditions.reportNative` (kernel runtime) | `latest.log` INFO, once per type and pack | "(-D…=off keeps it, as Forbric 0.4 did)" | "(…, as Forbric 0.3.x did)" — v0.3.0 to v0.3.1-beta2 had the all-packs leniency (commit 226d777b is in all three, not in v0.2.0) | English only: a log line |
| `InstallerText.oldMergedBase()` (was `GameArtifacts.OLD_INSTALL`) | installer window / console, a supplied merged base | "It is the merged game base Forbric 0.4 or earlier built; …" | "It is the merged game base Forbric 0.3.x or earlier (or a 0.4.0-dev build that still had MinecraftForge) built; …" | en + zh |
| `InstallerText.oldMergedBaseInDirectory()` (was an inline literal in `GameArtifacts.refusal`) | installer window / console, a merged base found by its old file name | "This directory holds …, the merged game base Forbric 0.4 or earlier built; …" | "…the merged game base Forbric 0.3.x or earlier (or a 0.4.0-dev build that still had MinecraftForge) built; …" | en + zh |

The Chinese installer sentences:

- 它是 Forbric 0.3.x 或更早版本（或仍带 MinecraftForge 的 0.4.0-dev 开发构建）生成的合并游戏底包，仍带有 MinecraftForge 的补丁。本安装器使用 NeoForge 修补过的 Minecraft，并且会自己构建它。
- 这个目录里有 <路径>，它是 Forbric 0.3.x 或更早版本（或仍带 MinecraftForge 的 0.4.0-dev 开发构建）生成的合并游戏底包，仍带有 MinecraftForge 的补丁；本安装器使用的是 NeoForge 修补过的 Minecraft。

Gate and record:

- gate-m17 control N: the two checks on the refusal follow the new wording (`gate-m17-installer.sh:511,516`), and its header
  and step label say 0.3.x. Phase U's refusal check (`UP_REFUSED`, keyed on the `[Forbric/Install]` tag since wave 1) is
  unchanged. It still has to match control N's refused launch; see the m17 run below.
- `reports/2026-10-30-forge-removal-baseline/README.md`: the C1 summary line and the release-notes draft line (both state
  current behaviour). The dated lane records (stage4-c1.md, c4-wording-left.txt, c5-*.md/tsv, c6-link.md, c6-rel.md) quote
  the old phrase as history and stay as written.

Comments (cefbae32): "the merged base of Forbric 0.4 and earlier" -> "… 0.3.x and earlier", "Forbric &le; 0.4" ->
"Forbric &le; 0.3.x", "Forbric <= 0.4" -> "Forbric <= 0.3.x". 39 phrases: 25 kernel main/runtime/test files, the
installer's `VanillaReference`, and gate-m9, gate-m10 and vanilla-reference-jar.sh. In the same commit as the user-visible
texts: `LinkCheckToolTest` (1) and gate-m17's header and step label (2). Comments say only "0.3.x and earlier"; the
0.4.0-dev builds are named only where a player reads the text. Nothing in these comments claims more than "the merged
base existed in those releases".

## Language

- **Installer:** the installer's rule (InstallerText javadoc) is that every sentence it changes exists in English and
  Chinese. So the two old-base sentences moved into `InstallerText` with Chinese twins, and the class javadoc names them.
  The rest of that refusal ("Built artifacts: cannot find …", "Developers: …") is unchanged and stays English, as it was.
- **Kernel:** the three kernel strings are `latest.log` lines. The kernel never localises log lines (reports and dialogs
  follow the locale; logs don't — `forbric-locale-testing`). gate-m17 control N reads the refusal line, and no dialog shows
  it: `CompatibilityLaunchBoundary.rejected` prints one English stderr line pointing at `latest.log`. Instead of a zh twin,
  a forced-zh test (`LaunchInputCheckTest.theOldInstallIsNamedAsTheReleasesThatHadMinecraftForgeInEveryLocale`: zh default
  locale and `-Dforbric.dialogLanguage=zh`) holds that the line is identical in a Chinese locale. **This departs from the
  brief's "every changed user-visible string: en + zh"**, for the reason above. If a localized in-game or window refusal
  for old installs is wanted, it is new UI (m17's comment already says "English only until a localized refusal exists")
  and belongs to the orchestrator/owner, not to this lane.

## The guard

`forbric-kernel/src/test/java/net/forbric/kernel/OldReleaseWordingTest.java`:

- **What it scans:** the kernel's `src/main`, `src/runtime` and `src/test` Java, the installer's `src/main` and
  `src/test` Java, `run/*.sh`, and `run/compat{,/mac,/win}/*.{sh,py}`. Not the dated records under `run/compat/reports/`.
  At least 1000 files, or it fails as run from the wrong directory.
- **What it flags:**
  - "Forbric 0.4 and|or earlier|older"
  - "Forbric &le;|<=|≤ 0.4"
  - "as|like Forbric 0.4 did"
  - Chinese "0.4 及|或 更早|以前|之前"
- **How it reads:** a line break with its indentation and comment marker becomes one space, and a string concatenation
  (`" + "`, also across lines) becomes nothing. So a phrase split across lines or literals is one phrase.
- **Self-test:** a second test checks each phrase as the source split it (8 samples). It also checks the true statements
  it must leave alone: "0.3.x or earlier (or a 0.4.0-dev build …)", "pre-0.4" Fabric Loader, "C2ME 0.4.x", `0.4F`,
  "Forbric 0.4.0 is the first release …", "0.4.0 之前的 Forbric".
- **Limit:** its inputs outside the kernel's own test sources (installer sources, run scripts) are not declared as
  Gradle inputs. A plain `test` after editing only those files is UP-TO-DATE and does not re-run it; `cleanTest test` (the
  suite command) and CI do re-run it. Recorded, not fixed (it would mean editing kernel build.gradle's test task).

## Other version claims 0.4.x makes false, and what the release commit must touch

Found by `git grep` over the whole tree for "0.4 or/and earlier|older", "≤/<=/&le; 0.4", "as Forbric 0.4 did",
"0.4 及/或 更早|以前|之前", "0.5.0", "v0.5", "0.3.2", "0.4.x", "0.4.0-dev", "next release", "SNAPSHOT",
`Implementation-Version` (`c6ver/version-grep.txt`: 64 single-line hits at 9cd04fc1 outside reports/, none at the tip but the tests' own descriptions):

- **None left** in Java, scripts, gradle, yml, introduction.md / introduction.zh-CN.md, the kernel READMEs, or the
  DialogLang / InstallerText / KernelLoadReport tables (no other locale's text names a Forbric version). Nothing says
  "0.5.0" or "0.3.2" (the scout's 0.5.0 default never landed).
- **The release commit's (not this lane's):** `forbric-kernel-installer/build.gradle:6` `version = '0.4.0-dev'` becomes the
  release version (0.4.x; owner names it). Everything a player sees follows from that one line:
  - the installer jar's name and its `Implementation-Version`, which the installer window shows (`InstallerGui.loaderVersion`);
  - the kernel library coordinate in the profile, `net.forbric:forbric-kernel:<version>` (installer build.gradle:182);
  - from that path, the title-screen / F3 brand `forbric-v<version>` (`ForbricBranding`);
  - gate-m17's `VERSION`, read from build.gradle.
  README.md / README.zh-CN.md change in the same commit (owner default).
- **Left, with reasons:**
  - `forbric-kernel/build.gradle:10` `version = '0.1.0-SNAPSHOT'`: the dev jar's name. ~30 scripts and gates spell
    `forbric-kernel-0.1.0-SNAPSHOT.jar`. A player never sees it, because the installer re-stages the jar under its own
    version. A developer launch from `build/libs` shows `forbric-v0.1.0-SNAPSHOT` on the title screen. That belongs to
    Stage 6 ("版本集中").
  - `ForbricBranding` javadoc's example `forbric-v0.3.1-beta`: a true past example.
  - `OldInstallCleanupTest.KERNEL_NOW` = `…/0.4.0-dev/…`: a synthetic current install. It does not read the build's
    version, so a bump does not break it.
  - The dated records under `run/compat/reports/` (history).

## Draft player-facing wording (README / release page; NOT applied — release commit)

- en: "Upgrading from Forbric 0.3.x or earlier: run the new installer once over the same game directory. Until you do,
  the game stops at launch with a message saying the install still carries MinecraftForge."
- zh: "从 Forbric 0.3.x 或更早版本升级：用新安装器对同一个游戏目录再安装一次。在此之前，游戏启动时会停下，并提示这个安装仍带有 MinecraftForge。"

## Verification

All at the code tip `cefbae32` (record commit only after). Pinned build: `heavy.sh ./gradlew --no-daemon $(cat
.stage4/props.txt)` (plain `--offline`, no init script, D68). Logs: `~/Forbric-backups/stage4-notes/c6/c6ver/`.

**Unit tests (targeted, while changing code):**
- `OldReleaseWordingTest` 2/0.
- `LaunchInputCheckTest` 20/0: 19 before, plus the new forced-zh test.
- `StagedJarLiteralGuardTest` 5/0.
- `EveryCatchIsVisibleTest` 3/0.
- `installerTest`: 11 classes ran, 0 failed. `SuppliedArtifactContentTest` "40 checks", 3 more than before (the Chinese
  half); `LinkCheckToolTest` passes.
- Logs: `targeted-1.log`, `targeted-2.log`. The first run stopped at the installer compile: `LinkCheckToolTest` still named
  the removed `GameArtifacts.OLD_INSTALL`, and now uses `InstallerText.oldMergedBase()`.

**Red controls.** Each control applies one mutation, runs the tests, and reverts with `git checkout`. Diffs and logs are
in `c6ver/red/`.

| control | mutation | result |
|---|---|---|
| R1 | kernel `OLD_INSTALL` back to "Forbric 0.4 or earlier, still carries MinecraftForge (%s)…" | `LaunchInputCheckTest` 6 of 20 red; `OldReleaseWordingTest` red (1 of 2) |
| R4 | two old phrases put back: KernelBoot's comment split across two `//` lines ("Forbric\n// 0.4 and earlier") and KernelNeoConditions' "as Forbric 0.4 did" | `OldReleaseWordingTest` red, naming exactly `KernelBoot.java:516` and `KernelNeoConditions.java:330` (`R4-guard.xml`); `LaunchInputCheckTest` 20/0 green |
| R2 | installer English sentence back to "Forbric 0.4 or earlier built" | `installerTest` red: `SuppliedArtifactContentTest`, 1 of 11 classes |
| R2g | the same mutation, kernel guard | `OldReleaseWordingTest` red, naming `../forbric-kernel-installer/…/InstallerText.java:76` (`R2g-guard.xml`) |
| R3 | Chinese directory line back to "Forbric 0.4 或更早版本" | `installerTest` red: `SuppliedArtifactContentTest` ("the Chinese directory line does not name the old merged base") |
| R6 | `oldMergedBase()` loses its Chinese branch | `installerTest` red: `SuppliedArtifactContentTest` ("the Chinese old-base problem is not the expected sentence") |

After the controls, the tree is clean at `cefbae32`. The guard's own self-test covers its matcher on 8 old and 8 true
phrasings.

**gate-m17**, run as `client.sh heavy.sh bash run/gate-m17-installer.sh`:
- **Result: GREEN, 119 PASS / 0 FAIL / 0 SKIP, `CAUGHT none`, no `cached:` line** (`c6ver/gate-m17.out`). That is the same
  count as the merger's run at 9cd04fc1.
- **Environment** (the first line of the .out records it):
  - `. .stage4/lane-env.sh`: GATE_PORT=46900, M12…M32 ports 46901-46906, FORBRIC_OLD=<lane>/.stage4 (stamped),
    HEAVY_LANE=s4c5-c6ver; MC_DIR unset.
  - `FORBRIC_GATE_CACHE=0`.
  - `M17_SEED_CACHE=<stage worktree>/forbric-kernel/run/installed`: the merger's 9cd04fc1 install, which seeds dl, tools
    and NeoForm's game.
  - Phase U's old install: the stage's `run/.gate-downloads/m17`, APFS-cloned into the lane; beta2 jar sha1
    `ae2ff4fa…`, nothing fetched.
- **Control N:** the refusal names "Forbric 0.3.x or earlier (or a 0.4.0-dev build that still had MinecraftForge)", and
  the switch line says "…does not apply to an install made by Forbric 0.3.x or earlier". The game's own lines are in
  `c6ver/gate-m17-old-refused{,-off}.log`.
- **Phase U:**
  - "the refusal pattern matches control N's refused launch (2)": the tag-keyed pattern still sees the reworded refusal,
    so it is not blind.
  - "it was not refused as an old install (absent)".
  - The reverse control is 22/22 red with the clean-up off.
- **First attempt:** it was killed during its cold first install. server.jar was downloading at about 20 KB/s, so the
  58 MB file needed about 50 minutes. `gate-m17.aborted-cold-download.out` has rc 143 and 0 checks; it is not counted.
  The counted run seeds that install, so its first install did not exercise the installer's own downloads. The
  merger's 9cd04fc1 run did, and nothing in this lane touches download code.

**Full suite** (`-Pforbric.requireFixtures=all jar cleanTest test --continue`, `suite.log`, XML `suite-xml/`, 917 files):
- **5969 tests, 0 skipped, 5 failed.** The failures, `failures-ver.txt`, are name for name the D58 five of
  `c6/failures-w1.txt` (LiquidBounce ×3, SharedFinalSourceActualTest ×2): 0 new, 0 gone.
- +3 tests against 5966: `OldReleaseWordingTest` 2 (new class) and `LaunchInputCheckTest` 19 -> 20.
- It ran alongside the first m17 attempt. No code changed after it.

**Not run:** gates-all, other gates, and native A/B (D87/D89).
- Only gate-m17 reads any changed string. gate-m21 reads the conditions behaviour, not the INFO line's wording: no gate or
  test matches "as Forbric".
- The kernel runtime jar changed by one string constant. The boot path is unchanged.
