# Stage 4 C6 ACCEPT, lane WIN: the C6 installer on the player's Windows machine

Lane WIN of ACCEPT (D98), worktree `s4c5-c6win`, branch `s4-c5-c6win`, cut from the stage tip **df1e5a13**
(= origin `ci/remove-forge-stage2`, CI run 44299805668 green on all six jobs). Owner answer 4 (D93): one player-style
install of the C6 build into the real `.minecraft`, after a fresh backup, and a world entered from a copy of a save.
Nothing was pushed; no tag, no release, no upload; main untouched. No defect was fixed here: each one found is
recorded below with its evidence and an owner. Evidence: `~/Forbric-backups/stage4-notes/c6/accept/win/` (file names
below are relative to it).

## Environment

| | |
|---|---|
| Mac build | tip df1e5a13, dirty=0; JDK 25.0.3; pinned `--offline` with `.stage4/props.txt`, `--no-daemon`, through `.stage/heavy.sh` (it waited for the MAC lane's exclusive gates-all, then ran in slot 1); FORBRIC_OLD = the lane's stamped `.stage4`; GATE_PORT 46900, M12-M32 46901-46906 (no port used: no gate ran here); FORBRIC_GATE_CACHE unset (no gate ran); MC_DIR unset (`build-env.txt`, `F-env.txt`) |
| Installer | `forbric-kernel-installer-0.4.0-dev.jar`, SHA-1 82ce0bc9…; its bundled kernel `forbric-kernel-0.4.0-dev.jar` SHA-1 d6c9bbae… = the kernel jar built from the tree; `forbric/tools/forbric-link-check.jar` 1a67d0ac…; no merge-tools jar (`installer-sha1.txt`, `build.log`) |
| Windows | reached over the LAN (WIN_HOST 192.168.3.140) with the clients in `forbric-kernel/build/sweep90-mac/tools/` (winsh/winfile, still present); Java = the game runtime's `java-runtime-epsilon` 25.0.1; system locale zh-CN (the installer and the game ran in the player's language unless said otherwise); Python 3.14.3 for the launcher shim |
| Scratch on Windows | `C:\ForbricAcceptC6` (installer jar, launcher shim, the isolated copy for push-and-run) and `C:\ForbricAcceptLogs`, both deleted at the end |

## A. State before anything was touched

- The game directory is `C:\Users\Administrator\AppData\Roaming\.minecraft` (`A-mc-toplevel.txt`): 7 006 files,
  2 762 544 992 bytes outside `forbric-backup` (`A-size.txt`), no reparse point inside.
- Versions: `26.2` (vanilla) and `26.2-forbric`. The `26.2-forbric` profile was **not** the plain v0.3.1-beta2 one: on
  2026-10-05 22:01 an earlier session pointed it at a development kernel `net.forbric:forbric-kernel:0.3.1-dev-520d7aad`,
  beside beta2's `forbric-loader`/`forbricruntime` 0.3.1-beta2, `patched-mc-merged:26.2`, `forge-runtime:26.2` and
  `neoforge-runtime:26.2` (`A-profile-current.json`; beta2's original profile is beside it as
  `26.2-forbric.json.bak-beta2-20261005`).
- `libraries\net\forbric` held 28 files: Forbric 0.1.0, 0.2.0, 0.3.0, 0.3.1-beta2 and 0.3.1-dev jars plus eleven
  hand-made `.bak`/`.good` copies from earlier sessions (`A-libs-pcl.txt`). `.forbric-build` held 117 files, among them
  beta2's merged-era products and a NeoForge 26.2.0.93 runtime cache from an earlier development install.
- Instance `versions\26.2-forbric` (PCL version isolation): 14 enabled mods, 8 Fabric and 6 NeoForge, plus two
  `.disabled` jars; **no MinecraftForge-only jar** (`A-mods-ecosystems.txt`, read from each jar's metadata). Saves:
  `新的世界` (195 files, 321.7 MB), `新的世界 (1)`, `新的世界 (2)` (`A-saves.txt`). `options.txt` plays `zh_cn`.
- `forbric-backup` held only `2026-10-05-before-carry-config-search` and `2026-10-05-before-integration` (a kernel jar and
  a profile each). **`forbric-backup\2026-09-26` does not exist** on this machine any more, nor anywhere on drives C-H
  (`A-backups.txt`, `A-search.txt`). The world copy therefore came from the fresh backup (step E).
- PCL2: the PCL2 the player uses is `G:\mc\Plain Craft Launcher 2.exe`; its selected folder is `G:\mc\.minecraft`, which
  holds only Fabric versions. The Forbric `.minecraft` is not in that PCL2's folder list (its `PCL.ini` shows some PCL used
  it before; the instance also has an `.hmcl` folder from 2026-11-14). Nothing in either launcher was changed
  (`A-pcl*.txt`).
- No game or launcher process was running.

## B. Fresh backup (done first; nothing else happened before it was verified)

`robocopy` of the whole `.minecraft`, `forbric-backup` excluded, to `.minecraft\forbric-backup\2026-11-20-before-c6`:
7 006 files copied, 0 failed (`win-logs/backup-robocopy.log`). Verified (`B-verify.txt`):

| | files | bytes | directories |
|---|---|---|---|
| source (outside forbric-backup) | 7 006 | 2 762 544 992 | 1 025 |
| backup | 7 006 | 2 762 544 992 | 1 025 |

Every file's relative path, size and modification time compared equal (0 differences); the SHA-1 of the 512 files
under `saves`, `mods`, `config` and `libraries\net\forbric` compared equal. Re-counted at the end: still 7 006 files,
2 762 544 992 bytes (`G-final-diff.tsv`).

## C. INSTFIX §7

- **§7 A (CI)**: run 44299805668, job development-tools (windows-latest) 134786293728: `OldInstallCleanupTest: OK` with no
  `NOT RUN`, `11 test classes ran, 0 failed` (`C-ci-run.txt`, `C-ci-windows-job.log` lines 357-372). So **§7 C (the
  scratch junction leg) is not needed and was not run**; `C:\ForbricJunctionTest` was never created (checked absent at the
  end).
- **§7 B (held-open delete, on the real install)**: B1, the old profile was started (as PCL would, see E) and left at its
  menu; it held its libraries open. B2, the C6 installer ran into `%MC%` (`D-install-1.log`): exit 0, six
  `[cleanup] 无法删除 …（java.nio.file.FileSystemException … 另一个程序正在使用此文件…）` lines, one per held jar (the 0.3.1-dev
  kernel, beta2's loader and runtime, `forge-runtime`, `neoforge-runtime:26.2`, `patched-mc-merged`), and the summary
  `[cleanup] 已删除 80 个旧版 Forbric 留下或本次安装用不到的文件（352.9 MB）；6 个无法删除，已保留`. B3, the game was closed
  (WM_CLOSE; it logged `Stopping!` and exited 0) and the installer run again (`D-install-2.log`): exit 0, those six
  removed by name, `已删除 6 个…（50.3 MB）`, no `无法删除` line. The held-open leg holds on the real machine.
- **§7 D (PCL version isolation)**: no JSON in `versions\26.2-forbric` failed to parse, so no `skipped` line was
  expected and none appeared; the instance's own files were not touched by the clean-up (only `libraries\net\forbric`
  and `.forbric-build` lost files, `D-diff-after-install.tsv`).

## D. The install and its clean-up

The installer was run from the command line (`java -jar … --dir <%MC%> --mc 26.2`, the gates' form), in the player's
locale; a player double-clicking it would get its window instead. It built the game files on the machine: NeoForge
26.2.0.93 userdev (3.8 MB) and universal (6.8 MB) fetched, NFRT reused the cached 26.2.0.93 steps already in
`.forbric-build`; link check `dangling references: 0` both ways; 21 s in all. It wrote a profile with 18 libraries:
`forbric-kernel:0.4.0-dev` (d6c9bbae…), `patched-mc-neoforge:26.2-26.2.0.93` (67bbf24f…, the same SHA-1 as `.stage4`'s
base), `neoforge-runtime:26.2.0.93` (aa023af2…), `vanilla-reference:26.2-26.2.0.93` (e3b8ac35…), and no retired
coordinate (`D-profile-new.json`).

Clean-up, both runs together, against m17 phase U (installer "MB" = MiB):

| | files | size | in dl/ | outside dl/ |
|---|---|---|---|---|
| this machine (run 1 + run 2) | **86** (80 + 6) | **403.2 MiB** (352.9 + 50.3; 422.8 MB decimal by the backup diff) | 41, 27.3 MiB | 45: 28 in `libraries\net\forbric` (164.4 MiB), 17 in `.forbric-build` (211.6 MiB) |
| m17 phase U (beta2 + keep-old profile) | 63 | 285.8 | 41, 27.3 MiB | 22 |
| DLCLEAN's real-player model (beta2 clone) | 64 | 288.9 | | |

- `dl/` matches m17 exactly: the same 41 files (27.3 MiB) go and it ends with 17 files.
- The extra 22-23 files outside `dl/` are what this machine had beyond a plain beta2 install, each named by no profile:
  Forbric 0.1.0, 0.2.0 and 0.3.0 libraries, the eleven hand-made `.bak`/`.good` copies, the 0.3.1-dev kernel and beta2's
  0.3.1-beta2 kernel. They are all in the fresh backup.
- DLCLEAN's other-version note: no other Minecraft version's Forbric downloads were in `dl/`, so nothing of that kind went.
- What stays: `.forbric-build` ends at 70 files, 420 608 726 bytes (410 751 KiB; DLCLEAN's model 410 860 KiB), with
  `nfrt-work` = beta2's six work files of 2026-10-04 (3.2 MB), the leftover DLCLEAN predicted.

## E. Entering a copy of the player's world

**Launcher.** PCL2 was not driven: the PCL2 on this machine does not list this `.minecraft`, adding it would change the
player's launcher settings, and no remote GUI tool for PCL2 was available here. The profile was launched **as PCL would
from its version JSON** with push-and-run's launcher path (`run/compat/win/forbric-launch.py`: inherited libraries,
`${library_directory}` substitution, the instance as game directory, the runtime Java), which adds
`-Dforbric.compatibilityPolicy=strict` and `-Dforbric.dependencyDialog=off`; the world was entered by quick-play with
`-Dforbric.clientSmoke=true` (ready after 80 ticks, MC's own screenshots at ticks 100 and 300, clean disconnect at 400).
Screens in front of quick-play were answered the way a player would, by key or click in the game window; every image
below is MC's own screenshot (F2 or the smoke's), never a desktop capture.

**World.** `新的世界` (the player's main world, last played 2026-10-04) copied from the fresh backup to
`saves\forbric-c6-accept`: 195 files, 321 653 977 bytes on both sides (`E-world-copy.txt`). The player's three saves
were never opened.

| run | language | what happened | evidence |
|---|---|---|---|
| 1 | zh_cn (player's) | title screen first showed WorldWeaver/BCLib's "Mod Updates" list (Escape). BCLib's "发现了一个不兼容的世界" prompt, defaults (backup + apply fixes) → BCLib's fixer thread threw `IllegalStateException: Rendersystem called from wrong thread` (a Unihex glyph baked off the render thread while its progress screen measured text) → "修复世界时的错误" with only Cancel usable → title screen `forbric-v0.4.0-dev (14 mods)` | `shots/2026-11-20_01.27.22.png` … `01.40.00.png`, `E-run1-latest.log` |
| 2 | en_us (options.txt swapped, restored) | the same prompt, the same throw (now a bitmap glyph) | `shots/01.42.52`-`01.44.12`, `win-logs/2026-11-20-3.log.gz` |
| 3 | en_us | prompt answered with "Apply Fixes" unticked → **joined** (`CompatPlayer joined the game` 01:47:12, `joined world via quick-play: forbric-c6-accept`), `client-ready after 80 world tick(s)`, tooltips and NeoForge tooltip event checked by the smoke, both screenshots drew the world (desert, the player's hotbar, Iris shader sky), `clean disconnect observed; stopping client`, `Stopping!`, launcher `exit=0` | `shots/2026-11-20_01.47.22.png`, `01.47.32.png`, `E-run3-latest.log`, `E-run3-launch.log` |

The player's `options.txt` was copied aside before run 2 and put back after run 3 (SHA-256 6a6eef6f… before and after).
Verdict: **the C6 install enters the player's world** (run 3). Runs 1 and 2 stopped in BCLib's own world-fixer, before
the world loaded (finding O-1).

Mods as the kernel saw them: `14 installed mod(s) … 8 Fabric, 6 NeoForge, 0 refused`; the load report names one mod
that did not finish loading (O-3); `Forbric/Caught` at exit: 2 sites (W-1, W-3).

## F. push-and-run against the new profile

push-and-run removes the instance's `config`, `mods`, `saves`, `logs` … (its CLEAN list) and uploads the Mac-staged
artifacts over the profile's libraries, so it was **not** run against the player's instance (also the standing rule
from 2026-10-05). It ran against an isolated copy `C:\ForbricAcceptC6\mc` (the real install's `libraries`, `assets`,
`runtime`, vanilla `26.2` and the newly written `26.2-forbric.json`, copied after step D), with three jars already on
the Mac (`F-mods.sha1`: fabric-api 0.161.0+26.2, Jade 26.2.8 NeoForge, sodium-neoforge 0.9.1, the player's versions or
close), `--no-build` (the kernel jar is the one built above at df1e5a13, d6c9bbae…; push-and-run's own build would run
Gradle outside heavy.sh), FORBRIC_OLD = the lane's `.stage4`.

- Dry run on the new profile: `PROFILE_REWRITES none (the game arguments already name the staged base, carrier and
  sidecar)` (`F-dry-run.txt`). Dry run on the profile as it was before the install: exit 2, `this profile is not one the
  installer of this tree wrote (it names net.forbric:forbric-loader:0.3.1-beta2; … patched-mc-merged:26.2; …)`
  (`F-dry-run-old-profile.txt`).
- **c6acc-win1**: server PASS (world saved); client never joined, stopped after 300 s of silence, verdict FAIL
  (`F-c6acc-win1/`). The isolated instance had no `options.txt`; a fresh client waits on vanilla's accessibility
  onboarding screen (the documented `onboardAccessibility:false` gotcha). Most likely that; not proven, no screenshot was
  taken before the harness killed it.
- **c6acc-win2** (same, with the player's `options.txt` copied into the isolated instance; the client played en_us and
  put zh_cn back): joined, survived real simulation, left cleanly, drew (`DREW 854x480, 1425 colours`), region PASS
  (529 chunks), compatibility report STRICT with 0 required losses on server and client, no load report, Caught 1 (W-3).
  Verdict **FAIL on one assertion only**, `construction is dependency order` (`F-c6acc-win2/report.md`,
  `assertions.txt`): finding W-2.

## G. How the machine was left

- In place: the C6 install (`26.2-forbric` profile, 0.4.0-dev kernel, NeoForge 26.2.0.93 base/carrier/sidecar); the
  player's 14 mods and three saves unchanged (no difference against the backup); both 2026-10-05 backups and the new
  `forbric-backup\2026-11-20-before-c6` (7 006 files, 2 762 544 992 bytes).
- Kept: the test world `saves\forbric-c6-accept`. It shows in the world list under its level name `新的世界`, like the
  original. BCLib's fixes were not applied to it, so BCLib will ask again when it is opened; it now also holds
  advancement/stat files for the launcher shim's offline player.
- Changed by playing, as any launch changes them: `logs` (MC rotated its old logs), 17 mod config files (written by the
  mods on load), `options.txt` (rewritten by MC on exit, same settings), `usercache.json`, `.mixin.out`,
  `.forbric-kernel` reports, natives re-extracted; NeoForge corrected the merged-era `config\neoforge-common.toml` and
  kept the old one as `neoforge-common-1.toml.bak` (NeoForge's own behaviour).
- Removed (all made by these runs): the launcher shim's `client.args`, `game-metadata.jar`, `quickPlay\`,
  `.forbric-sweep.pid(.lock)`; the eleven screenshots (copies in `shots/`); BCLib's 247 MB backup zip of the test world
  and its new `backups\` folder; the robocopy log beside the backup; `C:\ForbricAcceptC6`, `C:\ForbricAcceptLogs`.
- No process of this lane runs on Windows or the Mac; no Mac port was used; the lane's build outputs were deleted.
  Launcher settings (PCL2, HMCL) were not touched.

Full end-state diff against the backup: `G-final-diff.tsv`.

## Findings

| id | what | root cause / evidence | introduced by C6? | owner |
|---|---|---|---|---|
| **W-1** (kernel, medium) | every client with Better Nether logs a Caught WARN: `GameData.freezeData() THREW … Some intrusive holders were not registered: [Reference{null=entity.betternether.naga_projectile}]`, so NeoForge's freeze stops part-way (at the entity-type registry) | `NetherEntities.<clinit>` (better-nether 26.201.2, javap) builds `NAGA_PROJECTILE` (an intrusive holder) and registers six other entity types; `NAGA_PROJECTILE` itself is registered later by the class's `register()` from BN's Fabric main entrypoint. Under Forbric the class initialiser ran inside NeoForge's registration window (`registered content: betternether: … entity_type=6` at RegisterEvent, 01:18:32), and `KernelLifecycle.closeRegistrationWindow` → `freeze` → `GameData.freezeData` runs before the Fabric mains (`invoked main entrypoint of betternether` 01:18:36). On native Fabric the class initialiser and the registration both happen in the main entrypoint, before the freeze. What makes the class initialise that early is not determined (a `-Xlog:class+init` run is owed). The world still loaded | **no**: the same throw is in the old 0.3.1-dev kernel's run on the merged base (`B1-old-latest.log.gz` lines 642-680) | kernel, after Stage 4 (orchestrator assigns) |
| **W-2** (harness, low) | push-and-run's verdict is FAIL on a healthy run | `run/compat/assert.sh:26` requires `[Forbric/Order] construction order is dependency order`, which `KernelModLoader.orderByDependency` prints only when the dependency order differs from the discovery order; with Jade + sodium nothing is reordered, so the line is absent by design. The same "assert on a conditional log line" trap as earlier gates | no (the check and the line predate C6) | run/compat harness |
| **W-3** (kernel, low) | every Windows client logs a Caught WARN: `[Forbric/Frames] cannot prove a frame repair for com.mojang.text2speech.Narrator … TypeNotPresentException: Type ca/weblite/objc/NSObject not present` | the Narrator class names a macOS-only bridge type that is absent on Windows; the original bytes are kept, so behaviour is unchanged, but the run is not `CAUGHT none`. macOS gates cannot see it | not determined: the old 0.3.1-dev kernel's run on this machine has no such line (`B1-old-latest.log.gz`), but which change since then brought it (the frame recomputer's scope or the Caught reporting) was not traced | kernel |
| O-1 (third party, not attributed) | BCLib's world fixer cannot run on this world: its fixer thread updates a progress screen that measures text, the font bakes a missing glyph, and the GPU upload asserts the render thread (zh_cn and en_us alike); and BCLib calls the world "incompatible" although it was last played with the same BCLib/Better Nether/Better End versions | the stack has only vanilla and BCLib/WunderLib frames; whether native Fabric shows the same (and whether the old kernel failed to store BCLib's world data) needs a native Fabric run with this pack, not done. BCLib 26.201.6 is available | no | owner decides whether a native comparison is wanted |
| O-2 (installer wording, cosmetic) | in a zh locale the command-line output mixes English (`Installed. In your launcher, pick the version …`, the build lines) with Chinese `[cleanup]` and mods lines | `Installer.java:122` and the build lines are literals; only InstallerText is localised. The window a player double-clicks was not checked | no | installer wording (later lane) |
| O-3 (pre-existing) | `fabric-loot-api-v3` (fabric-api 0.161.0) "did not finish loading": the kernel leaves out its `ReloadableServerRegistriesMixin` on this base | load report (`E-run3-load-report.txt`); the same line is in the old kernel's run | no | kernel / compatibility notes |
| O-4 (not investigated) | one hotbar item of the player's world draws as the missing (magenta/black) texture | `shots/2026-11-20_01.47.22.png` slot 7; no model or texture error in the log; the world held data of mods no longer installed (`unknown … data attachment carryon:carry_on_data`) | unknown | owner decides |

## Not done, and why

- **PCL2 GUI**: not driven (see E); the launch reproduced PCL's from the version JSON with the harness's two extra
  switches.
- **A save from `forbric-backup\2026-09-26`**: that folder no longer exists; the copy came from the fresh backup of the
  player's saves, so the original `saves` folder was still never touched.
- **§7 C**: not needed (CI ran the junction leg).
- **A native Fabric comparison** for O-1 and a `-Xlog:class+init` run for W-1: out of this lane's scope (record, do not fix).
