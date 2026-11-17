# Stage 4 C6 wave 3 — lane DOCS: the introductions, this record, the release-notes draft

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
| (this file) | the Stage 4 record's C5/C6 sections and corrections, this record with the release-notes draft, and the m49 ignore entry dropped |

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
- **§10-§12**: `ForeignType` 46 rows, one column; the API table without `GameEventBridge`/`NativeEventDelivery`/
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

`forbric-kernel/.gitignore`'s `/run/world-events-m49/` entry is dropped (gate-m49 is deleted). The stage worktree's
untracked rundirs of the deleted gates (`run/server-chain-m41`, `server-damage-m47`, `server-events-m48`,
`world-events-m49`, the last 5.1 MB) are left for the orchestrator to delete (rule: no folder this lane did not create).
After the merge, `git status` in the stage worktree shows `forbric-kernel/run/world-events-m49/` as untracked until then;
the other three stay ignored by `/run/server-*/`.

### Hand-offs read

LINK's new names (`LinkCheck`, `GameLinkCheck`, `linkCheckJar`, `installerTest`, `forbric-link-check.jar`, `bundleLinkCheck`)
are in §13/§16; INST/INSTFIX's player drafts are used as INSTFIX corrected them (about 260 MB freed, about 40 MB stays,
480 MB disk); VER's upgrade sentence is used as VER corrected it; critic 5 lines (MergedBaseTool, `:mergeToolsJar`,
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
  `c6/merge-w2.md`; m52 29/0: the C5 merger's log; phase U 22 / 258.5 MB and ~40 MiB kept: stage4-c6-inst/instfix.md;
  Doctor 480/470 and 476,904 KiB: Doctor.java and stage4-c6-inst.md; 94/89/69/463/86/92/46 and the package counts:
  counted in the tree at eba8222c with the commands in `c6docs/counts.txt`; 636/12: native-only-methods.txt's rows;
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
> game ever stops at launch saying the install still carries MinecraftForge, run the installer again the same way. Worlds
> saved by earlier Forbric versions open on this one.
>
> **If you switch a mod from its Forge build to its NeoForge build**, some of what the Forge build saved in your world
> can be lost, exactly as it would be if you switched between the two loaders without Forbric. The one case we know of
> is Tombstone: what it stored on your familiars and servants is lost, and so is your own Tombstone data (knowledge,
> alignment, perks); the Silent Bond effect disappears (the NeoForge build calls it Beyond the Grave Bond), and so does
> the ghostly swim speed. Forbric itself loses nothing else in that switch.
>
> **Disk space.** Installing needs about 480 MB of free disk; almost all of it stays, so the next install is fast.
> Installing over an older Forbric now removes most of what the older version left behind (about 260 MB when upgrading
> from 0.3.1-beta2). About 40 MB of old downloads stay in the `.forbric-build` folder; delete that folder if you want the
> space back (the next install downloads what it needs again).
>
> **Changed**
> - A mod that crashes while the game is loading now stops the game with an error screen, as it would on NeoForge, instead
>   of the game starting without it.
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
> MinecraftForge，再用同样的方式安装一次即可。旧版 Forbric 存下的世界在这个版本上都能打开。
>
> **如果把某个 mod 从 Forge 版换成 NeoForge 版**，Forge 版存在世界里的一部分东西可能会丢，和不用 Forbric、直接在两个加载器之间
> 换版本时一样。目前已知的只有 Tombstone：它存在你的使魔和仆从身上的数据会丢，你自己的 Tombstone 数据（知识、阵营、天赋）也会丢；
> “沉默羁绊”效果会消失（NeoForge 版叫“超越坟墓的羁绊”），幽灵形态的游泳速度也会没有。除此之外，Forbric 在这次替换中不会多丢任何东西。
>
> **磁盘空间。** 安装需要约 480 MB 的可用磁盘空间；装完后这些几乎都会保留，下次安装因此会很快。在旧版 Forbric 上安装新版本时，
> 安装器会自动删掉旧版留下、已经没有版本在用的大部分文件（从 0.3.1-beta2 升级约 260 MB）。旧版的约 40 MB 下载文件会留在
> `.forbric-build` 文件夹里；想要回这部分空间，可以删掉这个文件夹（下次安装会重新下载需要的文件）。
>
> **改动**
> - 游戏加载时崩溃的 mod 现在会让游戏停下并显示错误界面，和在 NeoForge 上一样，而不是游戏照样启动、只是少了这个 mod。
> - Fabric mod 的设置界面可以从 Forbric 自己的 Mods 界面打开，不需要 Mod Menu。
> - 在手机启动器和 macOS 上，警告窗口打不开时，Forbric 会在游戏里提问。
>
> **修复**
> - 装了 Fabric API 时，Create Fly 启动就崩溃。
> - Carry On 什么都搬不起来。
> - 装了某些 mod（例如 TCDCommons）时，创造模式物品栏的搜索什么都搜不到。
> - 呼吸：有水下呼吸、在岩浆里或在能呼吸的流体里时，空气值像原版那样回升。

What each line rests on (for the reviewer, not for players): the Forge refusal (Stage 3, §5.3); the server stop (exit 78,
gate-m29); the upgrade sentence (stage4-c6-ver.md, as VER corrected it after its review F1); old worlds open (gate-m58
208/0 at the C5 close, with worlds the kernel at `tri-eco-26.2-final` wrote — later than v0.3.1-beta2, so a beta2 world
itself is covered only by phase U's upgraded install entering a world); the Tombstone losses (expected-losses.tsv, D77; the knowledge/alignment/perks reading of `cap_tombstone`
and `cap_protected_entity` is the TSV's own "why" column); disk (Doctor.java, stage4-c6-inst.md, stage4-c6-instfix.md §8);
stop like native (D82, D87e, gate-m24/m24d); config screens without Mod Menu (merge 0ec42139, gate-m56); asking in game
(1aa478ea, #56/#57); Create Fly crash (41df26d9, #52; on NeoForge's base gate-m61 boots Create Fly with fabric-api in
two dedicated servers); Carry On (gate-m54, `FabricCommonPayloadMixinAdapter` on NeoForge's base); creative search
(TCDCommons, gate-m55); breathing (merge 07eb4ce0, gate-m57 against native NeoForge). Each landed after v0.3.1-beta2
(checked with `git merge-base --is-ancestor` against 5e735a31) or is new in Stage 4.

Dropped from the draft after checking: the LiquidBounce/ViaFabricPlus fixes (merge fed9b64d, after beta2) — their fixture
tests are lost on this machine (D58), so nothing measured them on NeoForge's base; BCLib's tag shears — they worked in
v0.3.1-beta2 and broke only during Stage 4 development (D35), so a player sees no change; the load report's language
(#42 pinned a behaviour, its date does not show the behaviour itself is new).

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
  after the uninstall paragraph add INSTFIX §8's upgrade paragraph (about 260 MB removed, about 40 MB stays in
  `.forbric-build`).
- :174 "What's new in 0.3.0" → "What's new in 0.4.x" with the release-notes draft above.
- :240 "A known crash: the NeoForge build of Sodium …": check against the release (the fix e5dc5d86 is already in
  v0.3.1-beta2), and drop it if it no longer happens.
- :245 "This is a research project at version 0.3.0" → the release number.

## Open items

- **Owner**: the B5 ruling (proposal above, recorded in README.md); approve or change the release-notes and README drafts;
  the release commit applies them (README unchanged in this wave).
- **Orchestrator**: delete the stage worktree's untracked rundirs of the deleted gates m41/m47/m48/m49 (5.1 MB for m49),
  which `git status` shows once `/run/world-events-m49/` is no longer ignored; record the B5 ruling in DECISIONS.md if the
  owner takes it (critic 10).
- **Next CI runner**: if the CI bullet should carry counts again, restate them from the regenerated TSVs (the bullet now
  names none).
- **Stage 6**: the introductions' titles and identifier vocabulary (§7's "merged" prose under its note; "The three
  ecosystems"; "Forge family"), forbric-kernel/README*, canary READMEs, the full README rewrite if no release ships first.
- **Not verified here**: the release-notes "Fixed" lines are taken from the commits and gates named, not re-played as a
  player would; LiquidBounce/ViaFabricPlus in particular have no runnable fixture on this machine (D58).
