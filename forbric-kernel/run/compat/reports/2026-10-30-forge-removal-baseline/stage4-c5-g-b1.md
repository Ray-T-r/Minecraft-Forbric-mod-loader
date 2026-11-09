# Stage 4 C5 — lane G-B1 (events, content and behaviour gates, D3, lib.sh), wave 2

Branch `s4-c5-g-b1`, cut from the stage tip f0cb66b0. Notes, logs and run outputs: `~/Forbric-backups/stage4-notes/c5/g-b1/w2/`
(paths below are relative to it). Harness (D68): the restored Gradle cache, pinned versions, plain `--offline`; no init
script, no private `GRADLE_USER_HOME`. Every gate ran on the genuine stage (`.stage4`, fresh canary sha1 2e74d589) with
the lane's `lane-env.sh` (ports 46640-46659), in bash, through `.stage/heavy.sh`.

## Suite

| run | tree | result |
|---|---|---|
| first act (`suite-first.log`) | APFS clone of the worktree at f0cb66b0 | 5772 tests, 0 skipped, **7** failed: the 5 of `failures-wave1.txt` + `KernelBundledMixinExtrasStagedTest` [1] true / [2] false |
| first-act difference, re-run (`suite-first-mixinextras-rerun.log`) | same clone, `jar` built first | `KernelBundledMixinExtrasStagedTest` 2/2 pass |
| final (`suite-final.log`, XML `suite-final-xml/`, `suite-final-failures.txt`) | APFS clone synced to 3bfad0f6 (every tracked file compared equal), `jar cleanTest test --continue` | 5773 tests, 0 skipped, **5** failed = exactly the names of `failures-wave1.txt`, first 150 message chars equal; 0 new, 0 gone |

The two commits after 3bfad0f6 change only `native-reference.json`, gate-m57's header and this file; no Java test reads either
(`grep -rla 'native-reference.json\|forge-breath-drown' src/test` is empty), and `test_breath_drown` (26 OK) was re-run on them.

The two extra first-act rows are a clone artifact, not a code difference: the test reads the boot jar
`build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar` ("[fixture:third-party] stage the popular compatibility pack and the boot
jar"), which `cleanTest test` does not build and a fresh worktree does not have (the stage worktree had one from its gate
runs). With the jar built, at the same f0cb66b0, both pass. The final run therefore adds `jar` to the plan's command.
+1 test (5772 -> 5773): `LibCaughtContractTest.anEntityAttributeEventThatDidNotFinishIsFatalAndNoOtherHookIs` (new, below).
No test was deleted or renamed, so no `ci-unstaged.tsv` / `ci-prepared.tsv` row changes (D47); the new test has no skip path
(no fixture, no assumption: it runs bash on `run/lib.sh` like the five tests already in its class), so it needs no row.

Targeted (`targeted-1.log`, HEAD 3bfad0f6, compile x3 green): the 15 classes that read lib.sh or G-B1's gate files —
LibCaught/LibPort/LibStopTree/LibKeptUp/LibStopWhenReady, GateNeoCanary, GateForgeConfig, AntiCheatGateLog, M20Outcome,
NativeReferenceLine, KernelMixinErrorHandler, GateGenuineFabricLoader, KernelEventSubscribersGenuineBase,
LootTableEventDispatch, GatesAll: 95/0/0. (KernelLifecycleAttributeEvents and GateM0CaptureStep also name lib.sh and were
missed from this list; both passed in the final suite and in the review fixes' targeted run.) Python: `test_breath_drown` 26 OK, `test_gate_ports` 11 OK, `test_lib_guards` 13
OK, `test_evidence` 24 OK.

## Commits

| sha | what |
|---|---|
| 7488f13a | delete gate-m41, m47, m48, m49, their four builders and `canary/{event-chain,damage-events,server-events,world-events}` (D50); KernelClientSmoke screen-mouse drill javadoc reworded (D62/F19, no re-arm) |
| fda5737e | m2, m2b, m3, m4, m4-canary rebaselined; m4's merged-base tick probe gone, boot stops on `Done (` |
| 7b0196ee | m42: 21 cases, new off rule, phase 2b and the merged-base liquid read gone |
| 0190bf7d | D3: `run/merged_calls.py`, lib.sh `merged_calls` / `merged_delivers` / `MERGED_NATIVE` deleted |
| 054aa960 | lib.sh attribute safety net matches NeoForge's lines; new LibCaughtContractTest case |
| 95069dcc | m45: PartEntity bridge checks and `forgePartTracking` gone |
| a8b0e8cf | m44, m46, m50-m53 headers; m44 records why no phase can exercise `routePlaceItemHookToNeoForge` (D65) |
| ef089bae | m57: refused phase (D51) and EventMux absence gone; breath-drown.py stops judging a `refused` phase |
| 3bfad0f6 | m43: 17 cases, REPAIRED', off switches, `loot.json.forgeFalse` expects native NeoForge's answer (D49) |
| 715dfee0 | m57 native reference regenerated on NeoForge 26.2.0.93 (and MinecraftForge 65.0.1, D32) |
| (this file) | record |

Every gate G-B1 keeps now calls `refuse_shared_rundir` on its rundir before wiping it (D46). `git grep -n
'merged_calls\|merged_delivers\|MERGED_NATIVE' forbric-kernel/run` finds only the baseline record README (:303, history,
RECORD's file).

## Gates on the genuine stage (`gates/r1/`, HEAD 3bfad0f6; m57 again in `gates/r2/` after the reference)

Before = the C4 verify / C5 map numbers on the genuine stage (map-gates §2), not re-measured here.

| gate | before | now PASS / FAIL | what changed and why |
|---|---|---|---|
| m2 | 29/0 | **29 / 0** | prose; guard |
| m2b | 38/0 | **38 / 0** | prose; guard |
| m3 | 13/2 | **13 / 0** | the traditional-Forge baseline and MinecraftForge RegisterEvent checks (both FAIL: their writer is gone with the carrier) |
| m4 | 0/1 (abort) | **32 / 0** (33 / 0 after the review's tick check, r3) | aborted on "cannot read MinecraftServer.tickServer from the merged base". Gone: the tick probe, the traditional-Forge baseline, the tick step (1:1 forwards or stand-down), the "all N GAME_BUS bridge(s)" line, the `unifiedEvents=off` control (D29), two absences with no producer (D34). The map's probe copy was 40/5; the 8 PASS lines that went are the vacuous absences and the control's lines, the 5 FAIL lines the Forge ones |
| m4-canary | 23/1 | **22 / 0** | traditional-Forge baseline (FAIL) and the vacuous "no tick forward failure" (PASS) |
| m41, m47, m48, m49 | 0/1 each (builder "canary retired") | deleted | below |
| m42 | 5/9 | **9 / 0** | `len(cases) == 21`; off = all but {biome.fabric.keep, biome.late.keep, drown.neo, pot.neoforge.api}; phase 2b deleted |
| m43 | 3/2 | **5 / 0** | 17 cases; off switches; REPAIRED'; forgeFalse per D49 |
| m44 | 9/0 | **9 / 0** | header; D65 reason |
| m45 | 17/2 | **17 / 0** | the two PART_BRIDGE checks (the only reds) and `forgePartTracking` |
| m46 | 10/0 | **10 / 0** | header |
| m50 | 5/0 | **5 / 0** | header |
| m51 | 16/0 | **16 / 0** | header |
| m52 | 29/0 | **29 / 0** | header (D56: these five phases are the hopper parity check) |
| m53 | 8/0 | **8 / 0** | header |
| m57 | 18/0 | **12 / 0** (r1, .88 reference); **12 / 0** (r2, .93 reference, HEAD 715dfee0) | refused phase (D51) and the EventMux absence |

Every gate printed `CAUGHT none`. No SKIP line in any of them. No FAIL remains.

### Red controls (all inside the gates' own phases, run as part of the counts above; each exact set shown by the judge)

| gate | control | red set measured (exactly) |
|---|---|---|
| m42 | `coremodParity=off` | 17: every case except biome.fabric.keep, biome.late.keep, drown.neo, pot.neoforge.api |
| m42 | `biomeRebase=off` | {biome.fabric.keep, biome.late.keep} |
| m42 | withhold-conversion | {drown.neo} |
| m43 | `carrierHelperResults=off uncalledValueRelay=off` | {break.survival.plain, break.survival.blockEntity, break.creative, break.nested, xp.fabricReplace, xp.fabricCancel} |
| m44 | `itemUseOn=off mixinRelocatedCall=off wrapOperationShim=off` | {use.place.fabric, use.place.neoAfter, use.fabricOverrides, use.direct, pick.block.vanilla, pick.block.fabric, pick.block.empty, pick.block.includeData} |
| m45 | off (`defaultConflictRepair foreignFluidTypes fabricFuel`) | the 13 of REPAIRED; the three dragon cases hold |
| m45 | withhold | {fuel.neoEvent, fuel.neoEventFurnace, fuel.fabricInNeoEvent} |
| m45 | own-clauses-off | {fire.fabricWater} |
| m46 | `mixinStubRebind=off` | {speed.rebound, teleport.rebound, language.rebound} |
| m50 | `mixinSubtypeOwner=off` | the failing predicate's entry loads |
| m51 | bridge-off / appenders-off | the Fabric cases / the vanilla, NeoForge and Fabric cases |
| m52 | fallback / off / bridge-off / lithium | all 24 pass / exactly the 11 unslotted and block-only cases / all 24 / all 24 |
| m53 | `mixinAtWidenNew=off` | {fall.fabric, sprint.fabric} |
| m57 | `vanillaAirRefill=off` | the two vanilla-refill rows, each for the phase's cause |
| lib.sh | stage-tip lib.sh (mutation) | bash: modifyAttributes THREW line -> rc 0, no FAIL line (this first bash run kept no log; re-run with a log in the review fixes below); JUnit with the old awk pattern in a clone: `anEntityAttributeEventThatDidNotFinishIsFatalAndNoOtherHookIs` FAILED (`red-libcaught-mutated.log`); restored -> 6/6 (`targeted-1.log`) |

Optional, not done: m44's `relocOff`-alone split (PLAN marks it optional).

## Items

- **D50, deleted gates.** m41 (EventChainAudit's Neo->Forge chain pairs), m47 (Forge damage seams), m48 (Forge brewing,
  server-event forwards), m49 (world-event forwards, NeoConversionPost) only judged the deleted forwards; their builders
  exited "canary retired" since C2. Why vacuous on the genuine base: every probe listens on MinecraftForge's buses, which no
  stage carries. What covers the paths now: Fabric's damage adaptation regress-gate `item-damage-off`; experience m43
  `xp.fabric*`; drown.neo m42 (cd6c5864). m47's NeoForge damage-pipeline controls and m48's experience-drop cases are dropped,
  not re-homed (D50). The orchestrator worktree still holds their untracked rundirs (`run/server-chain-m41`,
  `server-damage-m47`, `server-events-m48`, `world-events-m49`); `.gitignore:20` `/run/world-events-m49/` is left so that dir
  does not show as untracked there (INFRA's file; RECORD/C6 to drop both).
- **D3.** Deleted after m4 and m42 stopped calling the helpers (their own commits come first).
- **D46.** Guard calls in all 15 kept gates.
- **D49.** Native NeoForge 26.2.0.93 with the probe's loot table (`d49/native-m43-loot.{sh,log}`; table sha1 7d09b674): the
  chest gets stone, dirt, diamond, gold_ingot — both `forge:condition` pools kept, `neoforge:conditions` never dropped. The
  probe now expects dirt; m43 positive 17/17.
- **D51.** m57 phase 2 deleted although it passed (the C4 verify run had it green).
- **D56.** m52's five phases green as above: fabric-transfer's own callbacks move every item in positive (the fallback stood
  down), the fallback alone moves them with the callbacks off, exactly the unslotted/block-only cases fail with both off,
  bridge-off and lithium green.
- **D65 (F11 #44/#61).** No m44 phase: `routePlaceItemHookToNeoForge` (CMT #30) was deleted in C3 (87014cb1). On the genuine
  base `ItemStack.useOn` posts UseItemOnBlockEvent's ITEM_AFTER_BLOCK phase (bci 8; ITEM_BEFORE_BLOCK is
  `onItemUseFirst`'s) and calls `CommonHooks.onPlaceItemIntoWorld` at bci 46 on the server side
  (`evidence/javap-ItemStack-useOn.txt`, base sha1 67bbf24f); the four placement cases of m44 positive (use.place.block,
  use.place.fabric, use.place.neoAfter, use.direct) run through that native call and pass (m44 positive passed 12/12
  cases in all); the merged-base red was a BlockSnapshot ClassCastException at the first placement (C3 evidence c').
  a8b0e8cf's message says the header names bci 46 and javap; it did not until 11ef48f8 (review fixes).
- **D62/F19.** KernelClientSmoke: javadoc only. No gate arms `-Dforbric.clientSmokeScreenMouse` now; MouseTweaks-neoforge is
  in client-merged-pack and client-neo-pack (a re-arm is an owner item, O3).
- **lib.sh attribute safety net (c2/handoff.md:487, C2 runtime review A2).** Corrected by the review (A-2): the old
  fatal awk was half-live, not vacuous. At f0cb66b0 it matched the restored settle warning ("[Forbric/Attributes]
  DefaultAttributes could not initialise", c10779e3) but neither line of the hook that runs the events, so NeoForge's
  `CommonHooks.modifyAttributes() THREW` could not turn a gate red (054aa960's subject and body overstate it). Now it
  matches the settle line, the THREW line and (32b4df97) `CommonHooks has no modifyAttributes()`; "did not finish" (no
  writer left) is dropped. Known gap, named in the comment: Caught.java writes a site's first failure only and `modifyAttributes`
  shares `invokeGameDataOn`'s site with GameData's `unfreezeData` / `vanillaSnapshot`, which run first.
- **m57 reference (c1/handoff.md:942, F21).** Producer: `breath-drown.py reference` (not native-controls.py, so no FORBRIC_OLD
  rewrite). D66 names `NATIVE_CONTROL_CACHE`; the substitution of `M57_NATIVE` is deliberate, since `breath-drown.py` reads
  only `M57_NATIVE` and never calls native-controls.py (`m57ref/regen.sh`). Images: neo =
  `/Users/jerry/Documents/Forbric/.claude/worktrees/suspicious-chaum-b28730/forbric-kernel/build/native-controls/native/neo`
  (26.2.0.93), forge = `/Users/jerry/Documents/Forbric/forbric-kernel/build/native-controls/native/forge` (26.2-65.0.1),
  read-only, linked from a scratch `M57_NATIVE` dir, each run an APFS clone. Three agreeing runs per loader (`m57ref/regen.log`, per-run server logs and results in `m57ref/runs/`): all 106 rows identical to the .88 reference; only the neo loader (.88 -> .93) and installation digest changed, and both families now carry `jarEntries` digests. canarySources unchanged (check-sources PASS). m57 re-run on it: 12/0.
- **breath-drown.py (D32).** Keeps its native-MinecraftForge half (`reference` still builds and runs the forge canary; the
  water-breathing row's air is judged against it). Only the `refused` phase went.

## Open items

| item | owner |
|---|---|
| `modifyAttributes` shares a Caught site with GameData's unfreeze/snapshot, so a failure there first hides it from lib.sh's net; closing it needs a site of its own in KernelLifecycle | S1 (KernelLifecycle's kernel lane; S2 is owner-gated and not in this run, D63), else the orchestrator's README "Not done yet" |
| `.gitignore:20` `/run/world-events-m49/` and the four deleted gates' untracked rundirs in the stage worktree | RECORD / C6 |
| introduction.md:1538,1749,2298 and introduction.zh-CN.md:392,415,654 still describe gate-m41 / EventChainAudit, and the two gate tables (:2298 / :654) still list m41–m51's "event chain" and "damage/server/world events" coverage; COMPATIBILITY_IMPLEMENTATION.md's M41/M47–M49 lines (:706, 865, 898, 1087, 1094-1101, 1116) are dated history and stay | C6 (docs) |
| README.md:303 names `merged_delivers` (history) | RECORD |
| m44 `relocOff` split (optional) | not done |
| re-arming the screen-mouse drill in m9 with MouseTweaks as witness | owner (O3, D62) |

## G-B1 wave-2 review fixes (2026-11-10)

Reviews: `c5/review-w2-g-b1-A.md`, `c5/review-w2-g-b1-B.md`. Logs: `review-fixes/` and `gates/r3/`.

| finding | outcome |
|---|---|
| A-1 m4 no longer shows the real-mod server ticked | fixed 6ac4b2c3: m4 checks "Stopping the server\|commands.stop.stopping" as m2/m2b/m3 do; r3 33/0, the check PASS (1) |
| A-2 the old lib.sh pattern was half-live, not vacuous | confirmed (bash replay below) and fixed 32b4df97: lib.sh comment corrected; the pattern also takes `CommonHooks has no modifyAttributes()` (the events never ran); LibCaughtContractTest's attribute case has that row (6/6). 054aa960's subject/body overstate it; history not rewritten, corrected here |
| A-3 / B-3 m44 header: useOn posts only ITEM_AFTER_BLOCK | confirmed (javap of 67bbf24f: useOn bci 8 ITEM_AFTER_BLOCK, bci 46 onPlaceItemIntoWorld; onItemUseFirst bci 8 ITEM_BEFORE_BLOCK) and fixed 11ef48f8; record's "(12/12)" and a8b0e8cf's message corrected above |
| B-1 Caught-site gap handed to S2, which D63 keeps out of this run | fixed in Open items: S1, else the orchestrator's README "Not done yet" |
| B-2 C6 docs hand-off incomplete | fixed in Open items: introduction.md:2298 and zh-CN:654 added; COMPATIBILITY_IMPLEMENTATION.md's M41/M47-M49 lines classified as dated history |
| B-4 stale merged/refused prose | fixed 11ef48f8 for build-breath-drown-canary.sh:8 (false since ef089bae), breath-drown.py prose and build-stub-rebind-canary.sh:4. The canary-source comments (OreTallyMixin:14, BreakAndLoot:111, StubDriver:38,45,52,66, PlayerSpeedMixin:13, PinnedTeleportMixin:10, LanguageFormatMixin:16, TooltipsProbe:39) are listed as residue below, not reworded: each states what the merged base did, rewording needs each claim re-measured on NeoForge's game and a canary rebuild + m43/m46/m51 re-run |
| B-5 garbled m57 row | fixed above |
| B-6 lib.sh bash red control had no log; two lib.sh-reading test classes missing from the targeted list | fixed: `review-fixes/red-libsh-bash.log` (script `review-fixes/redctl.sh`); targeted list note above |
| B-7 M57_NATIVE vs NATIVE_CONTROL_CACHE | fixed above: the substitution is stated as deliberate and both images named by path |

lib.sh red control (`review-fixes/red-libsh-bash.log`; one Caught FAILURE line through a `gate-*` script per run):

| lib.sh at | settle line | THREW | has no modifyAttributes() | SpawnPlacements THREW |
|---|---|---|---|---|
| f0cb66b0 (stage tip) | rc 1, FAIL | rc 0 | rc 0 | rc 0 |
| d7126d05 (lane tip before review) | rc 1, FAIL | rc 1, FAIL | rc 0 | rc 0 |
| 32b4df97 | rc 1, FAIL | rc 1, FAIL | rc 1, FAIL | rc 0 |

Re-runs, on the genuine stage at 11ef48f8:
- Targeted (`review-fixes/targeted-2.log`): the 21 test classes that name lib.sh, a changed gate, a changed builder or
  breath-drown.py: 146 / 0 failed / 0 skipped.
- Python: test_breath_drown 26, test_gate_ports 11, test_lib_guards 13, test_evidence 24, all OK.
- Gates (`gates/r3/`): m4 33/0, m44 9/0, m46 10/0, m57 12/0; each `CAUGHT none`, GATE GREEN, no SKIP; each red phase
  (m44 off, m46 off, m57 vanilla-refill-off: exactly the two vanilla-refill rows) held. The other 11 gates did not re-run:
  their files did not change, and lib.sh's change is the fatal awk alone, which only a kernel attribute failure line reaches
  (none in any r1/r3 log: all `CAUGHT none`).
- Full suite (`review-fixes/suite-final.log`, XML `review-fixes/suite-final-xml/`, `review-fixes/suite-final-failures.txt`),
  APFS clone of the worktree at 11ef48f8, `jar cleanTest test --continue`: 5773 tests, 0 skipped, 5 failed = exactly the
  names of `failures-wave1.txt`; 0 new, 0 gone. A first attempt in a clone without `forbric-kernel/build` lost the
  fixtures there (599 failed) and is void (`review-fixes/suite-void-nobuild.log`).

Residue (RECORD / Stage 6): the "merged base/game" comments in `canary/break-and-loot`, `canary/stub-rebind` and
`canary/tooltips` named in B-4, outside RECORD's residue grep (`K/run`, `K/src/test`).
