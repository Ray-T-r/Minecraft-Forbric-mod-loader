# Stage 4 C6 wave 2 — lane DOCS: the introductions, this record, the release-notes draft

Branch `s4-c5-c6docs`, cut from the stage tip eba8222c (worktree `.claude/worktrees/s4c5-c6docs`, ports 46960-46979, none
used). Notes and logs: `~/Forbric-backups/stage4-notes/c6/c6docs/`. Harness: D68 (pinned build, plain `--offline` from
`.stage4/props.txt`, no init script, no private Gradle home), every Gradle run through `.stage/heavy.sh`. Nothing was
downloaded, no gate and no zh run: the lane changes documentation and one ignore line, which no gate reads (D87, D88b).
README.md / README.zh-CN.md are not changed (owner default: only in the release commit); their wording is drafted below.

## Worktree

Bootstrap from eba8222c reported the two usual PROBLEMs: `.stage4/minecraft` was the relative link `../.stage/minecraft`
(re-linked to the absolute `suspicious-chaum-b28730/.stage/minecraft`), and `forbric-kernel/build/sweep100-mac`'s source
does not exist (nothing here reads it). `jar` built (`c6docs/jar.log`).

## Commits

| sha | subject |
|---|---|
| `ab086cf3` | Correct both introductions for Stages 1-4, since main is about to carry NeoForge's own base and no MinecraftForge |
| `181c41c2` | the Stage 4 record's C5/C6 sections and corrections, this record with the release-notes draft, and the m49 ignore entry dropped |
| `e097f396` | review fixes in both introductions (F1-F3, F6, F9-F13) |
| (this file) | review fixes in the records, the release-notes draft and the ignore file (F2, F4-F8, F14), and the section "DOCS review fixes" |

## Items

### docs-introduction-stage4-landing — done (owner default: correct every statement Stages 1-4 made false; no renames)

introduction.md went from 2,957 to 2,237 lines; introduction.zh-CN.md keeps step with it (same 57 headings in the same
order). What changed, by section:

- **Header and terminology**: the base is NeoForge 26.2.0.93's patched game; "MinecraftForge is gone" note with the tag
  `tri-eco-26.2-final` and this record as history; ecosystem = Fabric, NeoForge (`LoaderKind.MINECRAFTFORGE` only for the
  refusal); carrier = NeoForge's runtime; new row for the vanilla reference; "Forge family" and "merged base" explained as
  the code's older words.
- **§1-§2**: two loaders, not three; the classpath diagram (`patched-mc-neoforge-26.2.jar`, one carrier,
  `--nativeReference`); runtime source-set count 92 (was 159); §2.3 the three artifacts are now base, carrier, vanilla
  reference; §2.4 (edit splice, merge-loss ratchet) replaced by a short "retired" paragraph.
- **§3**: launch-input check and the old-install refusal (D9); NeoForge discovery with the refusal; 94 chain call sites
  (was 131); no Forge identity seeding; no `KernelHudBridge`; `NeoFatalErrorWindow` (`-Dforbric.fatalErrorWindow`) instead
  of "Forbric has no early window"; §3.3 one trigger per side, `openWindow` gone; §3.4 the registration window as
  `registerNeoForgeContent` runs it now, `NeoParallelDispatch`, `RegistrationStop`, `KernelNeoClientStop`; §3.5 no
  MinecraftForge hold-back, no client resource preload, Fabric entrypoint stops.
- **§4**: MultiLoaderArbiter default NeoForge, Fabric (MinecraftForge half never a candidate, `JarClaim.Refused`);
  nestedDupePreference default; `ModFileScanData` in NeoForge's shape and `KernelModFile.sharedFor` (`-Dforbric.modFilePerJar`);
  presence on NeoForge's `ModList` only.
- **§5**: Fabric entrypoints stop like Fabric Loader's `invokeEntrypoints`; NeoForge construction on the worker pool;
  `finalizeSpawn` → `EventHooks.finalizeMobSpawn`; **§5.3 rewritten as "MinecraftForge — refused"** (the rule, the claim,
  what the player sees, D77's swap losses, the old install); §5.4 networking (`CustomPayloadInteropSeam`,
  `FabricCommonPayloadMixinAdapter`, `RegistryRevertInjector` + `RegistryRemapContracts`; the MinecraftForge
  handleCustomPayload text and `RegistrySyncParityInjector`/`KernelForgeWrapperSync` gone); transfer without
  MinecraftForge capabilities.
- **§6**: 94 call sites, 89 COREMOD registrations, 69 `*Injector` classes; ACCESS reads the carrier's AT; the post-Mixin
  chain (`RegisterEventsSeam` in, `ForgeTransferShapeAudit` out); "Base invariants" list rebuilt from
  `ForbricMergedBaseCompatTransformer`'s current repairs; packs (`CarrierPackIdMigrationInjector`); UI without the HUD
  bridges and the creative-search repair; **new "Fabric adaptations on NeoForge's base" bullet** (featuresPerStep, item
  damage, mob shears, fluid reactions, particle view, Fabric fluid types); air without the MinecraftForge sentences; the
  anchor-census hedge INFO lines; repair-drift.sh needs the sidecar.
- **§7**: a vocabulary note at the top ("merged" now means NeoForge's base as the chain leaves it); the platform rule for
  NeoForge mods; native-only-methods numbers from the current table (636 methods, 12 classes; was 565/424/14 and 18/10/6);
  `MixinFitReport`/`MixinOverlapLint <base.jar>`; `MixinMergedTwin`, `MixinBuilderStubRebind`, `MixinForgeModelDataEdge`,
  `DeliveryBookkeeping` removed from the lists; the screen-draw gateway is `ClientHooks.extractScreen` with D36's
  `registeredMixins`/`additions` rules; the native body of a Fabric mod is read from the vanilla reference.
- **§8 rewritten**: the MinecraftForge forward pass and its audits are history; what remains is the Fabric bridges (loot
  tables, fuel, break experience) and `KernelServerDuties`.
- **§9**: data packs and client packs as they are (stable carrier pack id, the visible parent pack), two condition
  evaluators with D69's Fabric-pack-only leniency (`KernelConditionSources`), aliases via `aliasTargetPresent`, block→item
  native, no MinecraftForge worldgen.
- **§10-§12**: `ForeignType` 49 rows (corrected from 46 under "DOCS review fixes"), one column; the API table without `GameEventBridge`/`NativeEventDelivery`/
  `ForgeLoadingList`; Mods-screen rows without MinecraftForge; policy vs a loading stop.
- **§13-§15 rewritten**: installer 0.4.0-dev, the NeoForge-only pipeline, the link check (`forbric-link-check.jar`, no
  baseline), `GameArtifacts`, Pins (.93), BuildStamp, the profile (`--nativeReference`, three artifacts), `OldInstallCleanup`
  with phase U's measured numbers, releaseAssets/evidence roles, Doctor's 480/470; §14 what forbric-loader still holds;
  §15 package counts.
- **§16**: `installerTest`; staged root from `.dev/staged` / `FORBRIC_OLD` / `-Pforbric.stagedRoot`; the unit-test
  paragraph restated with the merger's suite (5,969 / 0 / 5 at a5708f73; the "counted from source" figures and the 6,737
  runs were the merged base's and are gone); the gate table (m41-m51 row, m15 gone; m24d, m58-m62 added; m0/m1/m3/m4/m17/
  m21/m25/m26/m28/m29/m55/m57 rows rewritten from their headers); the CI bullet (docs-ci-bullet-truth, below); §16.1 native
  comparison against NeoForge .93 and native-controls' arms.
- **§17**: 463 literals, 86 retired; tables and lists without any retired switch; new groups "Fabric adaptations" and
  "Installer"; new switches documented (below).
- **§18**: invariants 3, 8, 10 corrected; 13 and 14 (one delivery per dispatch, the merge-loss ratchet) replaced by "a
  loading failure stops like native" and "MinecraftForge is refused, never loaded".
- **§19**: current state rebuilt: the base bullet; the measurements labelled as merged-base (MOD_TEST_FAILURES, the 27-mod
  round) plus a "Measured for Stage 4" bullet; the MinecraftForge air/drown/event-audit paragraphs and gap bullets
  removed; a "Since Stage 4" list (wrapped streams, hoppers, resource preload, known gaps, D91's open stop items, the
  carrier pack-id warning); versions 0.4.0-dev. **§20** links this record.

Not done here (Stage 6, owner default): section titles ("Mixin on the merged base", "The three ecosystems"), identifier
renames, a rewrite of §7's long adapter prose (covered by the vocabulary note, every deleted class removed from it).

### docs-introduction-c6-lines — done

(a) repair-drift.sh needs the sidecar: §6 last paragraph (and zh). (b) The `LIVING_BREATHE_EVENT`/`LIVING_DROWN_EVENT`
rows: gone with the §8 rewrite (0 hits in both files). (c) gate-m41 and EventChainAudit: gate-m41 0 hits; EventChainAudit
appears once, in §8's past-tense list of what was deleted; the m41-m51 table row is now "m35–m38, m42–m46, m50, m51, m53".
(d) `<merged-base.jar>` → `<base.jar>` (0 hits). (e) `openWindow`, `CreativeSearchTreesInjector`, `KernelCreativeSearch`,
`clientResourcePreload` (now only "that preload is retired"), the lambda static-ness / `net/forbric/loader/impl` retarget,
`ForgeLayerInstance.drawScreen`, the old switch list: 0 present-tense hits. `playPayloadFallThrough` (c5/PLAN.md:381 left it
to Stage 6) is described truthfully as `FabricCommonPayloadMixinAdapter`'s switch.

### docs-new-switches-classes — done

Every class and switch in the item and in critic 4 now appears in both introductions (checked by name, 0 missing):
KernelFluidReactions, KernelItemDamage (`itemDamageHandler`), KernelMobShears (`shearsRelayMobs`),
`nativeCallInlining.registeredMixins`, `operationSeams.additions`, the anchor-census hedge INFO line, KernelFeaturesPerStep
(`featuresPerStepRefresh`), `modFilePerJar`, `neoConditions.fabricPacksOnly`, `particleViewDetach`, KernelPoiStates,
KernelConditionSources, NeoParallelDispatch, KernelNeoClientStop, `fatalErrorWindow`, `fluidReactionNeighbourChange`(+`.head`),
`postMixinFixups.nativeDelegation`, `mixinPrivateDefaults`, `customPayloadInteropSeam`, `lambdaShapeEvidence`,
`neoScanData`, `vanillaMemberTwins`, CarrierPackIdMigrationInjector, KernelCarrierPackIds, RegistrationStop,
RegisterEventsSeam, RegistryRevertInjector, ParticleViewTrackerInjector, VanillaMemberTwinInjector,
MixinPrivateDefaultCollision, LambdaShapeEvidence, CustomPayloadInteropSeam, KernelServerDuties(+Installer),
FabricItemDamageMixinAdapter, FabricCommonPayloadMixinAdapter, LoaderHalfJudge, LoaderKind, JarClaim,
RegistryRemapContracts, FeaturesPerStepRefreshInjector. The `clientSmokeMultipart`(+`Types`) and `clientSmokeTooltipEvent`
switches are recorded as test drivers only (§17 diagnostics row). The "Forbric has no early window" sentence is corrected.

### docs-ci-bullet-truth — done (numbers deferred to CI)

The `- **CI**` bullet now describes build.yml as it is: kernel-prepared prepares NeoForge's game, carrier, vanilla
reference and canary (no Forge, no merged base, no merge report); no merge-loss ratchet sentence; development-tools runs
`installerTest`; no nightly sentence (D85: the job is gone; "nothing runs them on a schedule"); the `build` job is said to
go with forbric-loader in Stage 5. It still quotes the kernel job's command (`./gradlew build
-Pforbric.skipBaseline=ci-unstaged`): `python3 -B -m unittest test_ci_contract` in tools/, 4 tests OK
(`c6docs/ci-contract.log`). The prepared skip count ("781 … 779 … 2") is removed rather than restated: the committed TSVs
are CIPREP's local pre-measure, not CI's, so no CI-measured number exists yet; "about a third" of the unstaged suite
skipping holds on CIPREP's local measure (2,025 of 5,812) and is kept. The next CI runner restates the counts from the
regenerated TSVs if it wants them back.

### record-readme-c5-residue-and-c6 — done

`README.md` (this directory): the Stage 4 heading and intro no longer say C5/C6 have not started; the hopper release-note
line carries m52's measured result (29 PASS / 0 FAIL in the C5 merger's no-cache sweep at 394b8804, read from
`c5/merger/w2d/gates-all-results/gate-m52-hopper-fabric-storage.sh.log`); "Not done yet" is current; new sections "C5"
(lane index, the C5-close numbers, the Stage-0 reconciliation, what stays open) and "C6" (lane index, the wave-2 numbers,
"Open after C6"); "Corrections to this record" (the non-existent `duplicateArbitration` switch at the Stage 3 section, the
stale D31 "still open" line, the C4 "not started" line). The 7 m17 premises are no longer listed as not done (C5 G-C).
m0's C6 SKIP is gone from the tree (LINK), so it is not listed either.

### fluidreactions-mobshears-test-ruling — proposal recorded, owner decides

Recorded in README.md "Ruling owed …": close C3 critic B5 on the existing coverage (weave tests, the adapter's unit tests,
gate-m60/m61/m62 with their switch-off red controls), no new test class. Critic 10 asks for a DECISIONS entry; that file is
the orchestrator's, so this lane did not write one.

### release-notes-draft — drafted below (since v0.3.1-beta2, player wording)

### d77-swap-losses-docs — done in the release-notes draft (owner default: release notes only until #18)

The six rows of `canary/forge-swap/expected-losses.tsv`, all tombstone, are in the draft's "If you switch a mod from its
Forge build" paragraph: the familiar/servant/protected-entity/tombstone capabilities stored in MinecraftForge's ForgeCaps,
the Silent Bond effect (renamed Beyond the Grave Bond in the NeoForge build), the ghostly swim speed (MinecraftForge's own
`forge:swim_speed` attribute); and that switching between the native loaders loses the same. §5.3 of the introductions
says the same for developers.

### refusal-text-swap-advice — unchanged (owner default)

The in-game MinecraftForge refusal text (`DialogLang` `unsupported.forge.detail`, en/zh/zh-TW) keeps its generic advice
("Get the NeoForge or Fabric build of {0} instead"); no swap-loss warning is added, because a per-mod line would have to
match mod names. DialogLang.java is not touched.

### readme-release-text — drafted below (README unchanged)

### gitignore-deleted-gate-rundirs — done

First done by dropping `forbric-kernel/.gitignore`'s `/run/world-events-m49/` entry; **reverted under "DOCS review
fixes" (F8)**: the main checkout has its own copy (`/Users/jerry/Documents/Forbric/forbric-kernel/run/world-events-m49`,
4.9 MB) that would show as untracked once main fast-forwards, so the entry stays, with a comment, until both copies are
deleted. The rundirs of the deleted gates (`run/server-chain-m41`, `server-damage-m47`, `server-events-m48`,
`world-events-m49`) exist in the stage worktree and in the main checkout; this lane did not create them and leaves them
for the orchestrator/owner to delete; the first three are ignored by `/run/server-*/`.

### Hand-offs read

LINK's new names (`LinkCheck`, `GameLinkCheck`, `linkCheckJar`, `installerTest`, `forbric-link-check.jar`, `bundleLinkCheck`)
are in §13/§16; INST/INSTFIX's player drafts are used as INSTFIX corrected them (about 260 MB freed, about 40 MB stays,
480 MB disk; since DLCLEAN C6, 2026-11-18: about 290 MB freed, about 16 MB stays, see `stage4-c6-dlclean.md`); VER's upgrade sentence is used as VER corrected it; critic 5 lines (MergedBaseTool, `:mergeToolsJar`,
`MergedLinkChecker` in the tool list, "packaged link gate", the CI counts) are gone; critic 6 (PROTOCOL.md) was met by
REL and check-w1 C-5 found no further concrete line, so nothing here; the merger's CI results are in the record's C6
section ("CI has not run").

## Checks

All on the final text of both files (`c6docs/checks.txt`):

- Every CamelCase class name in each introduction that exists nowhere in the tree's non-Markdown files: the remaining hits
  are mod or game class names (WorldEdit, TranslationChecker, WindowMixin, ServerAboutToStart), a name pattern
  (`MergedBase…`), and classes named only in past-tense history (`KernelHudLayer`; the eight Fabric adapters §7.4.1 says
  the general mechanisms replaced; §8's list of the deleted forward pass) — the same list in both files.
- Every `forbric.*` switch named in either introduction checked against `ForbricSwitches.RETIRED` (86 names): 0 retired
  switches named (was 21 in English, 21 in Chinese). Every other `forbric.*` token is a live literal, a Gradle property or
  a package name.
- The scout's present-tense list (gate-m41, EventChainAudit, CreativeSearchTreesInjector, KernelCreativeSearch,
  openWindow, LIVING_BREATHE/DROWN rows, `<merged-base.jar>`, merge-loss ratchet, nightly): 0 present-tense hits.
- Numbers: every figure written is copied from a record or log named in the text (suite 5,969/0/5 and the gate results:
  `c6/merge-w2.md`; m52 29/0: the C5 merger's log; phase U 22 / 258.5 MB and ~40 MiB kept: stage4-c6-inst/instfix.md, since DLCLEAN C6 63 / 285.8 MB (a real player's upgrade
  64 / 288.9 MB) and ~16 MiB kept: stage4-c6-dlclean.md;
  Doctor 480/470 and 476,904 KiB: Doctor.java and stage4-c6-inst.md; 94/89/69/463/86/92 and the package counts:
  counted in the tree at eba8222c with the commands in `c6docs/counts.txt`; ForeignType's 49: counted under "DOCS review fixes" (the first commit wrote 46, which no command
  measured); 636/12: native-only-methods.txt's rows;
  the C5-close numbers: D91 and stage4-c5-verify.md run 3; the Stage 3 sweep: this README's Stage 3 section).
- `tools/test_ci_contract.py`: OK (above). No Java test reads either introduction, this README or the ignore file
  (`git grep` over `*.java *.py *.sh`), so no other test reaches what changed; the full suite was still run once, as the
  brief asks (below).

## Suite

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test --continue` in
this worktree with the edits of both commits in place (2026-11-18, 3m46s; `c6docs/suite.log`, XML `c6docs/suite-xml/`, 917
files): **5,969 tests, 0 skipped, 5 failed**, recounted from the XML; the five are `c6/failures-w2.txt`'s by name (the D58
fixtures: FabricClientMixinAnchorsTest ×2, MixinTwinRebindTest ×1, SharedFinalSourceActualTest ×2). 0 new, 0 gone. No idle
Gradle daemon of this lane is left (the one running, 60297, was started at 00:07 by another lane).

## Release notes draft (since v0.3.1-beta2; for the release page and README's "What's new", owner's wording to approve)

Plain words for players (CLAUDE.md README rules). Version written as 0.4.x until the release number is set (owner
answer 3). Only changes a v0.3.1-beta2 player will notice.

### English

> **Forbric 0.4.x — Fabric and NeoForge mods together. Forge mods are no longer supported.**
>
> **The big change.** Forbric now runs Fabric mods and NeoForge mods only, on NeoForge's own version of the game
> (NeoForge 26.2.0.93). Mods made only for Forge no longer load. When you start the game with one in your `mods` folder,
> Forbric names it and tells you which build to get instead; get the NeoForge or Fabric build of that mod and replace the
> Forge one. Many mods ship all three builds, and a single file that contains several builds keeps working: Forbric uses
> its NeoForge or Fabric part. A dedicated server with a Forge-only mod stops at startup and says which mod it is.
>
> **Upgrading from 0.3.x.** Run the new installer once over the same game directory; it replaces the old files. If the
> game ever stops at launch saying the install still carries MinecraftForge, run the installer again the same way.
> **Back up your worlds before you upgrade.** A world still opens when a mod that made it no longer loads, but what a
> Forge-only mod added to it (its blocks, items and creatures) is not there once that mod is gone.
>
> **If you switch a mod from its Forge build to its NeoForge build**, some of what the Forge build saved in your world
> can be lost, as it would be if you switched between the two loaders without Forbric. The one case we know of
> is Tombstone: what it stored on your familiars and servants is lost, and so is your own Tombstone data (knowledge,
> alignment, perks); the Silent Bond effect disappears (the NeoForge build calls it Beyond the Grave Bond), and so does
> the ghostly swim speed. In our tests, Forbric itself lost nothing else in that switch.
>
> **Disk space.** Installing needs about 480 MB of free disk; almost all of it stays, so the next install is fast.
> Installing over an older Forbric now removes what the older version left behind and no longer uses, its old downloads
> included (about 290 MB when upgrading from 0.3.1-beta2). About 16 MB of the older version's build cache stays in the
> `.forbric-build` folder; delete that folder if you want the space back (the next install downloads what it needs
> again). If the same game folder also has Forbric for another Minecraft version, that version's cached downloads are
> removed too, and its next install downloads them again.
>
> **Changed**
> - A mod that crashes while the game is loading now usually stops the game, as NeoForge and Fabric do on their own,
>   instead of carrying on without that mod (or first asking you whether to). In the game client, a NeoForge mod's crash
>   then shows NeoForge's error screen naming the mod, or a small separate message box if the game window is already
>   open; a Fabric mod's crash just closes the game. (Checked on macOS only.)
> - Fabric mods' settings screens open from Forbric's own Mods screen, with no Mod Menu needed.
> - On phone launchers and on macOS, when the warning window cannot open, Forbric asks its question inside the game.
>
> **Fixed**
> - Create Fly with Fabric API crashed at startup.
> - Carry On could not pick anything up.
> - The creative inventory's search found nothing with some mods installed (for example TCDCommons).
> - Breathing: with water breathing, in lava or in a fluid you can breathe in, your air refills as in vanilla Minecraft.

### 中文

> **Forbric 0.4.x —— Fabric 和 NeoForge mod 一起玩。不再支持 Forge mod。**
>
> **最大的变化。** Forbric 现在只运行 Fabric mod 和 NeoForge mod，用的是 NeoForge 自己的游戏版本（NeoForge 26.2.0.93）。只为
> Forge 做的 mod 不再加载。如果 `mods` 文件夹里有这样的 mod，启动时 Forbric 会点出它的名字，并告诉你该换哪个版本：去下载这个
> mod 的 NeoForge 版或 Fabric 版，替换掉 Forge 版。很多 mod 三个版本都有；一个文件里同时装着几个版本的 mod 照样能用，Forbric
> 会用它的 NeoForge 或 Fabric 部分。专用服务器里有只支持 Forge 的 mod 时，会在启动时停下并说明是哪个 mod。
>
> **从 0.3.x 升级。** 用新安装器对同一个游戏目录安装一次，它会替换旧文件。如果游戏启动时停下并提示这个安装仍带有
> MinecraftForge，再用同样的方式安装一次即可。**升级前请先备份你的世界。** 做过这个世界的某个 mod 不再加载时，世界照样能打开，
> 但只支持 Forge 的 mod 加进世界里的东西（它的方块、物品和生物）在这个 mod 没了之后就不在了。
>
> **如果把某个 mod 从 Forge 版换成 NeoForge 版**，Forge 版存在世界里的一部分东西可能会丢，就像不用 Forbric、直接在两个加载器之间
> 换版本时那样。目前已知的只有 Tombstone：它存在你的使魔和仆从身上的数据会丢，你自己的 Tombstone 数据（知识、阵营、天赋）也会丢；
> “沉默羁绊”效果会消失（NeoForge 版叫“超越坟墓的羁绊”），幽灵形态的游泳速度也会没有。在我们的测试中，除此之外 Forbric 在这次替换中没有多丢任何东西。
>
> **磁盘空间。** 安装需要约 480 MB 的可用磁盘空间；装完后这些几乎都会保留，下次安装因此会很快。在旧版 Forbric 上安装新版本时，
> 安装器会自动删掉旧版留下、已经用不到的文件，包括旧的下载文件（从 0.3.1-beta2 升级约 290 MB）。旧版约 16 MB 的构建缓存会留在
> `.forbric-build` 文件夹里；想要回这部分空间，可以删掉这个文件夹（下次安装会重新下载需要的文件）。如果同一个游戏文件夹里还装着
> 另一个 Minecraft 版本的 Forbric，那个版本缓存的下载文件也会被删掉，下次安装它时会重新下载。
>
> **改动**
> - 游戏加载时崩溃的 mod，现在通常会让游戏停下（NeoForge 和 Fabric 单独使用时也是这样），而不是不带这个 mod 继续启动（或先问你
>   要不要继续）。在游戏客户端里，这时 NeoForge mod 会显示 NeoForge 的错误界面并写出是哪个 mod；如果游戏窗口已经打开，则会弹出一个
>   单独的小提示框。Fabric mod 则是直接关闭游戏。（目前只在 macOS 上验证过。）
> - Fabric mod 的设置界面可以从 Forbric 自己的 Mods 界面打开，不需要 Mod Menu。
> - 在手机启动器和 macOS 上，警告窗口打不开时，Forbric 会在游戏里提问。
>
> **修复**
> - 装了 Fabric API 时，Create Fly 启动就崩溃。
> - Carry On 什么都搬不起来。
> - 装了某些 mod（例如 TCDCommons）时，创造模式物品栏的搜索什么都搜不到。
> - 呼吸：有水下呼吸、在岩浆里或在能呼吸的流体里时，空气值像原版那样回升。

What each line rests on (for the reviewer, not for players): the Forge refusal (Stage 3, §5.3); the server stop (exit 78,
gate-m29); the upgrade sentence (stage4-c6-ver.md, as VER corrected it after its review F1); the backup line and what a Forge-only mod's content does (gate-m32: a world
written with a mod opens with that mod gone — m32 asserts the reduced server starts and stops, not what became of the
mod's blocks; that an absent mod's content is not there is the game's own behaviour, not measured by a gate here; the
first draft's "Worlds saved by earlier Forbric versions open on this one" was withdrawn after review F4: gate-m58's worlds
were written by the kernel at `tri-eco-26.2-final`, later than v0.3.1-beta2, and phase U's world is the client fixture's
ForbricTest copied into beta2's layout, which beta2 never wrote, so no world saved by a released version has been opened
on this tree); the "in our tests" of the Tombstone line (gate-m59 drills three mods, D54; three of expected-losses.tsv's
six rows are `NO NATIVE ROW`, judged from the mod's code); the Tombstone losses (expected-losses.tsv, D77; the knowledge/alignment/perks reading of `cap_tombstone`
and `cap_protected_entity` is the TSV's own "why" column); disk (Doctor.java, stage4-c6-inst.md, stage4-c6-instfix.md §8);
stop like native (D82, D87e, gate-m24/m24d; reworded by DOCFIX C6, check-w2 C-3, and again after its review, see the end of this file); config screens without Mod Menu (merge 0ec42139, gate-m56); asking in game
(1aa478ea, #56/#57); Create Fly crash (41df26d9, #52; on NeoForge's base gate-m61 boots Create Fly with fabric-api in
two dedicated servers); Carry On (gate-m54, `FabricCommonPayloadMixinAdapter` on NeoForge's base); creative search
(TCDCommons, gate-m55); breathing (merge 07eb4ce0, gate-m57 against native NeoForge). Each landed after v0.3.1-beta2
(checked with `git merge-base --is-ancestor` against 5e735a31) or is new in Stage 4.

Dropped from the draft after checking: the LiquidBounce/ViaFabricPlus fixes (merge fed9b64d, after beta2) — their fixture
tests are lost on this machine (D58), so nothing measured them on NeoForge's base; BCLib's tag shears — they worked in
v0.3.1-beta2 and broke only during Stage 4 development (D35), so a player sees no change; the load report's language
(#42 pinned a behaviour, its date does not show the behaviour itself is new).

Hoppers (plan §C6 lists "hoppers follow NeoForge's semantics" among the changes for the release notes): no player line,
pending the owner. The one difference a Fabric player could meet, a hopper feeding or emptying a Fabric storage, gate-m52
measures working (29 PASS / 0 FAIL at 394b8804); no other hopper difference from v0.3.1-beta2 was measured either way, so
there is nothing measured to tell a player. If the owner wants the line anyway: "Hoppers now work the way they do on
NeoForge." / "漏斗现在按 NeoForge 的方式工作。"

Left out on purpose: developer-only items (log wording, switches, CI, the link check, gates), merged-base fixes that never
reached a release and no longer exist (MinecraftForge field getters, Forge fluid types, the edit splice), and anything not
measured. Not claimed: that any particular Forge-only mod has a NeoForge or Fabric build.

## README / README.zh-CN draft for the release commit (not applied)

Line references are README.md at eba8222c. Each replaces the line(s) named; the zh file gets the same at its matching
lines (README.zh-CN.md:7, :13, :29, :73, :110 per the scout).

- :5 headline: "**One Minecraft instance that runs Fabric mods and NeoForge mods at the same time.**" / zh
  "**一个能同时运行 Fabric mod 和 NeoForge mod 的 Minecraft 实例。**" (the full product description at :5-34, :54, :93, :112,
  :127, :133, :221, :227, :253-259 says Forge throughout; the release commit rewrites it — critic 29; plan Stage 6 owns the
  titles if no release ships first).
- :7 "Version 0.3.0" → the release's 0.4.x number.
- :50 disk line → INSTFIX §8: "**An internet connection**, and about 480 MB of free disk. Almost all of it stays
  afterwards: it is what makes the next install fast." / zh "**网络连接**，以及约 480 MB 的可用磁盘空间。装完后这些几乎都会保留，下次安装因此会很快。"
- :66-68, :107 installer file names → the release's jar name.
- :93 "Minecraft's, Forge's and NeoForge's own files" → "Minecraft's and NeoForge's own files" / zh likewise.
- :112 "Fabric, Forge and NeoForge mods all go in the same `mods` folder." → "Fabric and NeoForge mods both go in the same
  `mods` folder. Mods made only for Forge do not load: use their NeoForge or Fabric build." / zh "Fabric 和 NeoForge mod 都放进同一个
  `mods` 文件夹。只为 Forge 做的 mod 不会加载：请换用它们的 NeoForge 或 Fabric 版。"
- :164-172 "Updating and uninstalling": the update paragraph → VER's upgrade sentence (draft above, "Upgrading from 0.3.x");
  after the uninstall paragraph add the upgrade paragraph of the release-notes draft above as DLCLEAN C6 corrected it
  (about 290 MB removed, old downloads included; about 16 MB of build cache stays in `.forbric-build`; another Minecraft
  version's cached downloads go too).
- :174 "What's new in 0.3.0" → "What's new in 0.4.x" with the release-notes draft above.
- :240 "A known crash: the NeoForge build of Sodium …": check against the release (the fix e5dc5d86 is already in
  v0.3.1-beta2), and drop it if it no longer happens.
- :245 "This is a research project at version 0.3.0" → the release number.

## Open items

- **Owner**: the B5 ruling (proposal above, recorded in README.md); approve or change the release-notes and README drafts;
  the release commit applies them (README unchanged in this wave).
- **Orchestrator/owner**: delete the rundirs of the deleted gates m41/m47/m48/m49 in the stage worktree and in the main
  checkout (`forbric-kernel/run/world-events-m49` 4.9 MB there), then drop the `/run/world-events-m49/` ignore entry; record the B5 ruling in DECISIONS.md if the
  owner takes it (critic 10).
- **Next CI runner**: if the CI bullet should carry counts again, restate them from the regenerated TSVs (the bullet now
  names none).
- **Stage 6**: the introductions' titles and identifier vocabulary (§7's "merged" prose under its note; "The three
  ecosystems"; "Forge family"), forbric-kernel/README*, canary READMEs, the full README rewrite if no release ships first.
- **Not verified here**: the release-notes "Fixed" lines are taken from the commits and gates named, not re-played as a
  player would; LiquidBounce/ViaFabricPlus in particular have no runnable fixture on this machine (D58).

## DOCS review fixes

Review: `c6/review-c6docs.md` (14 findings). Each was checked against the tree, the logs or the records before it was
acted on; all 14 were confirmed, none refuted. Evidence: `c6/c6docs/review-fixes/` and `c6/c6docs/counts.txt` (tail).

| finding | verdict | fix |
|---|---|---|
| F1 the 0.3.x refusal | confirmed (stage4-c6-ver.md "Draft player-facing wording": an old profile names `net.forbric:forbric-kernel:0.3.1-beta2` and launches that kernel) | e097f396: §5.3, §3.2 step 0 and invariant 14 in en and zh say the new kernel refuses to launch over an old install's base, and that a 0.3.x profile keeps running its own kernel until the installer is rerun |
| F2 ForeignType "46 rows" | confirmed: 49 constants at the tip, and counts.txt had no command for it | e097f396 (49 in both introductions); this commit: the record's two mentions; the command and its result appended to counts.txt |
| F3 zh §2.1 / §7.3 out of step | confirmed: zh said 159 files and `net.minecraftforge.*`; zh §7.3 said `forge`/`neoforge` and "合并造成的" | e097f396; the reviewer's `enzh.py` rerun: §2.1 and §7.3 no longer differ, every other section reports what it reported before the fix |
| F4 "worlds saved by earlier versions open" | confirmed: gate-m58's worlds come from `tri-eco-26.2-final` (187143a1, 2026-10-30; beta2 5e735a31 is its ancestor), and phase U's world is the client fixture's ForbricTest copied in (gate-m17-installer.sh `SRC_RUNDIR`, `cp -cR "$SRC_RUNDIR/saves"`) | release-notes draft en and zh: the claim withdrawn; a backup line and what a Forge-only mod's content does, resting on gate-m32 (a world opens with a mod gone) and stated as the game's own behaviour, not gate-measured; the "rests on" note rewritten |
| F5 hopper line | confirmed: plan §C6 names it, the draft neither had it nor said why | the draft records it as left out pending the owner (gate-m52 29/0 at 394b8804 measures the Fabric-storage case; nothing else measured), with an optional en/zh line for the owner |
| F6 "exactly as" / "nothing else" | confirmed: 3 of expected-losses.tsv's 6 rows are `NO NATIVE ROW (judged by the mod's code)`; m59 drills three mods (D54) | draft en/zh: "as it would be", "In our tests"; §5.3 in e097f396 says which three rows are judged from code |
| F7 Open after C6 incomplete | confirmed: INSTFIX R-5 (stage4-c6-instfix.md:274, :294) and merge-w2.md's harness-owner items were missing | README "Open after C6": both added, plus the deleted gates' rundirs |
| F8 main checkout's m49 rundir | confirmed: `/Users/jerry/Documents/Forbric/forbric-kernel/run/world-events-m49` (4.9 MB) exists and only that ignore line covers it (`git check-ignore -v`) | the `/run/world-events-m49/` entry is restored with a comment (gate-m49 deleted in 4ce446ff) until both copies are deleted; the gitignore item and Open items above say so |
| F9 gate-m1 "65 of 99" | confirmed: the m1 run at 9cd04fc1 logged `71 of 107 … 0 hedge(s)` (stage `run/server-kernel/logs/latest.log`, 23:06, the C6 wave-1 merger's gate-m1.out 19/0; line copied into counts.txt) | e097f396 cites that line and its tip |
| F10 "the link baseline is gone" | confirmed: `forbric-loader/src/test/resources/merge/link-check-baseline.txt` and `.provenance.json` are tracked; `LinkBaselineProvenanceTest` reads them | e097f396: "nothing reads the link baseline any more except its own provenance test", and both files are in §14's Stage 5 leftovers |
| F11 "§5.2" for KernelServerDutiesInstaller | confirmed: §5.2 does not mention it; "Server duties" in §8 does | e097f396: points at "Server duties" below |
| F12 render / diagram | confirmed | e097f396: blank line before "Since Stage 4" (en, zh); `linkcheck` in the §2 diagram (en, zh) |
| F13 invariant 13 vs §19 / D87(e) | confirmed | e097f396: invariant 13 says a NeoForge loading failure stops as native and a throwing Fabric `main` stops with exit 1 where native carries on (D87(e)), open in §19; §19 states the native exit 0 instead of calling it unknown |
| F14 B5 proposal's reason | confirmed: `KernelItemDamageTest` exists; both weave tests add the production source (`MixinFluidReactionWeaveTest` line 60, `MixinShearsRelayMobsWeaveTest` line 64) | README "Ruling owed": the cost argument withdrawn, the coverage argument stated; the owner still decides |

Checks after the fixes (no gate: nothing a gate reads changed — two introductions, three records, one ignore line):
- identifier check (the reviewer's `tokens.py` on introduction.md): the same unresolved set as the review's
  `tokens-en.txt`, nothing new;
- `tools/test_ci_contract.py`: 4 OK (`c6docs/review-fixes/ci-contract.log`);
- full suite once, at e097f396 plus this commit's files in the tree (`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt)
  -Pforbric.requireFixtures=all jar cleanTest test --continue`, 2026-11-18 02:16-02:20, 3m33s, `c6docs/review-fixes/suite.log`,
  XML `c6docs/review-fixes/suite-xml/`, 917 files): **5,969 tests, 0 skipped, 5 failed**, the five of failures-w1.txt /
  failures-w2.txt by name (the D58 fixtures). 0 new, 0 gone. No ports used; Gradle daemon 60297 is not this lane's.

## DOCFIX C6 (2026-11-18): corrections after check-w2

Branch `s4-c5-c6docfix`, cut from 0d310a36. Documentation and records only; no code, no gate, no suite.

- **Title.** This file said "C6 wave 3". DOCS ran in C6 wave 2 (check-w2 C-5).
- **Release-notes line on loading crashes (check-w2 C-3).** The draft said such a mod "stops the game with an error
  screen, as it would on NeoForge". That was not true for what a player sees at the stage tip, so the line was rewritten
  (en and zh) and kept, because the change is real and players will notice it. What the new line rests on:
  - "In most cases": NeoForge's "Config loading" listeners, Fabric `preLaunch` and a throwing Fabric injector at the moved
    freeze point are still contained (stage4-c5-stop.md, open item 2; not re-measured here).
  - "Before the game window opens, NeoForge's own error screen names the mod": DLG 063e7b9a (NeoFatalErrorWindow). The
    screenshots match native NeoForge byte for byte, and gate-m24d asserts it for listener, try, construct and datapackreg.
  - "After that, a small separate message box": NeoFatalErrorWindow answers IN_GAME and NeoForge takes its message-box
    fallback (stage4-c5-verify.md run 3: the six finish-path fixture modes plus m24d common/clientnetwork; D91/D92 open
    item). On macOS the box is tinyfd's osascript dialog. In m24d's common and clientnetwork phases it showed the mod's
    name (`c5/merger/w2d/gates-all-results/gate-m24d-registerstop-client.sh.log` :105/:124; corrected by review R-8: this
    said "the mod's name and the error", but the logged command line is cut after "encountered an error while dispat",
    so the error text is not evidenced). A message with quotes
    can turn the box into "INVALID MESSAGE WITH QUOTES": that was seen in the pre-window phases before DLG, and the line
    makes no claim about the box's text either way.
  - "A Fabric mod that crashes as it starts closes the game with a crash report": the client's Fabric stop is
    "Initializing game", exit 255, crash report written, with no box and no early window (stage4-c5-stop.md; m24d
    "fabric" phase).
  - "As NeoForge and Fabric do on their own": for NeoForge, D82/D87e. For Fabric, native Fabric Loader also ends the
    stage with its error. A native Fabric server starts no world and exits 0, where Forbric exits 1. A native client
    leaves `Minecraft.<init>` for Main's handler (stage4-c5-stop.md "What native does": the server was measured, the
    client was read from fabric-loader's source). D87(e) and the orchestrator brief said native "carries on (exit 0)".
    That is true of the exit code only, so the introductions' three "carries on" sentences (en and zh) were corrected
    in the same commit.
  - "Checked on macOS so far": DLG did not run Windows or Linux (stage4-c5-dlg.md, "Not measured").
- **Introductions.** "NeoForge's error screen on a client" (§12.4) now names both client cases. "no phase contains it"
  (invariant 13) now names the two phases still contained. The C-4 wording on the link baseline is in the same commit.
- **Record README.** The THR/STOP/DLG row now says the same.

### DOCFIX C6 review fixes (2026-11-18, review-docfix R-1..R-9)

Same branch, on 070597c5. Documentation and records only; no code, no gate, no suite.

- **Release-notes line, shortened and narrowed (R-2..R-5).** The line is now three sentences (en and zh). What changed
  and what each clause rests on:
  - "instead of carrying on without that mod (or first asking you whether to)" (R-2): v0.3.1-beta2 did not simply start
    without the mod. a4e3c017 (an ancestor of beta2 5e735a31, `merge-base --is-ancestor`) made a throwing `@Mod`
    constructor and a failed Fabric main/client/server entrypoint confirmed-required findings (beta2's
    InitializationFailureProjectionTest: `initialization:constructor`, `initialization:entrypoint:main`), so an
    installed profile (`ask`) asked the player, and where nobody could be asked the launch stopped (exit 78). Listener
    failures did carry on. Beta2 itself was not run here.
  - "usually" now covers the whole line, as "in most cases" did; the three still-contained phases are the same as above.
  - "In the game client, … a small separate message box if the game window is already open" (R-4): the line no longer
    says the box shows the error. The box's text is evidenced only by m24d's cut command line (the mod's name), and
    tinyfd can show "INVALID MESSAGE WITH QUOTES" instead.
  - "a Fabric mod's crash just closes the game" (R-3): the crash report is dropped. It was measured on the client only
    (m24d fabric, exit 255); the server's Fabric stop writes no NeoForge crash file and no record shows a crash report
    there. A crash in `preLaunch` is contained, which the first sentence's "usually" covers.
  - "(Checked on macOS only.)": as before (DLG "Not measured: Windows and Linux").
  - Length (R-5) is the owner's call; the shorter wording is the review's, adjusted for a server reader ("In the game
    client") and for R-3.
- **Introductions (R-1, R-7).** §3.4 (en and zh) said every client stop shows NeoForge's fatal-error window and "Nothing
  is contained". It now says the window is shown while the client has no game window, that after that NeoForge falls
  back to a message box, that a Fabric stage stop exits 1 on a dedicated server (native 0), that a Fabric stage stop
  writes no NeoForge crash file (Main logs it on a server; a client ends with Minecraft's crash report), and it names the three phases still contained: NeoForge's "Config loading"
  listeners, a Fabric `preLaunch` entrypoint and a throwing Fabric injector at the moved freeze point. Code at the tip:
  `KernelFabricEcosystem.runPreLaunch` (:541-548) catches the throw, marks the mod FAILED and goes on;
  `KernelLifecycle.fabricFreezePoint` (:2553-2556) logs it with `Caught.warn` and goes on. §3.5 narrows the stopping
  entrypoints to `main`/`client`, says the freeze-point injector is contained, and says `KernelNeoClientStop`'s result
  is the message-box fallback. §5 and §12.4 narrow "every entrypoint" to `main`/`client`/`server` and §5 says what a
  throwing `preLaunch` does. Invariant 13 and the record README's THR/STOP/DLG row now name all three contained phases
  (R-7), and §19's open list adds them.
- **Link baseline (R-6).** "no script, gate or CI step calls the two scripts" was false: `run/test-link-check.sh` calls
  `check-merged-links.sh` five times (:132-:163), each with its own `LINK_BASELINE`. It now says no gate or CI step
  does, names test-link-check.sh, and says nothing runs it (`git grep test-link-check` outside .md: only the file
  itself). build.gradle:259's comment naming the deleted `buildMergeTools` is left for Stage 5 (a code file, not docs).
- **Record (R-8).** The m24d sentence above is corrected in place, marked as such.
- **D87(e) (R-9).** DECISIONS.md is outside the repository; the annotation is the orchestrator's.
