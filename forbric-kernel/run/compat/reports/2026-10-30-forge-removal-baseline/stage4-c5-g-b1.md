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
LootTableEventDispatch, GatesAll: 95/0/0. Python: `test_breath_drown` 26 OK, `test_gate_ports` 11 OK, `test_lib_guards` 13
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
| m4 | 0/1 (abort) | **32 / 0** | aborted on "cannot read MinecraftServer.tickServer from the merged base". Gone: the tick probe, the traditional-Forge baseline, the tick step (1:1 forwards or stand-down), the "all N GAME_BUS bridge(s)" line, the `unifiedEvents=off` control (D29), two absences with no producer (D34). The map's probe copy was 40/5; the 8 PASS lines that went are the vacuous absences and the control's lines, the 5 FAIL lines the Forge ones |
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
| m57 | 18/0 | **12 / 0** (r1, .88 reference), ****12 / 0** (r2, .93 reference, HEAD 715dfee0)** (r2, .93 reference) | refused phase (D51) and the EventMux absence |

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
| lib.sh | stage-tip lib.sh (mutation) | bash: modifyAttributes THREW line -> rc 0, no FAIL line; JUnit with the old awk pattern in a clone: `anEntityAttributeEventThatDidNotFinishIsFatalAndNoOtherHookIs` FAILED (`red-libcaught-mutated.log`); restored -> 6/6 (`targeted-1.log`) |

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
  base `ItemStack.useOn` posts UseItemOnBlockEvent and calls `CommonHooks.onPlaceItemIntoWorld` at bci 46 on the server
  side (`evidence/javap-ItemStack-useOn.txt`, base sha1 67bbf24f); every placement case of m44 positive runs through that
  native call (12/12); the merged-base red was a BlockSnapshot ClassCastException at the first placement (C3 evidence c').
- **D62/F19.** KernelClientSmoke: javadoc only. No gate arms `-Dforbric.clientSmokeScreenMouse` now; MouseTweaks-neoforge is
  in client-merged-pack and client-neo-pack (a re-arm is an owner item, O3).
- **lib.sh attribute safety net (c2/handoff.md:487, C2 runtime review A2).** The fatal awk matched only the deleted
  KernelForgeAttributes' lines; NeoForge's `CommonHooks.modifyAttributes() THREW` could not turn a gate red. Now it matches
  that line and the restored settle warning ("[Forbric/Attributes] ... could not initialise"); "did not finish" (no writer
  left) is dropped. Known gap, named in the comment: Caught.java writes a site's first failure only and `modifyAttributes`
  shares `invokeGameDataOn`'s site with GameData's `unfreezeData` / `vanillaSnapshot`, which run first.
- **m57 reference (c1/handoff.md:942, F21).** Producer: `breath-drown.py reference` (not native-controls.py, so no FORBRIC_OLD
  rewrite). Images: neo = the stage worktree's native-controls image (26.2.0.93), forge = the main checkout's (26.2-65.0.1),
  read-only, each run an APFS clone. Three agreeing runs per loader (`m57ref/regen.log`, per-run server logs and results in `m57ref/runs/`): all 106 rows identical to the .88 reference; only the neo loader (.88 -> .93) and installation digest changed, and both families now carry `jarEntries` digests. canarySources unchanged (check-sources PASS). m57 re-run on it: 12/0.
- **breath-drown.py (D32).** Keeps its native-MinecraftForge half (`reference` still builds and runs the forge canary; the
  water-breathing row's air is judged against it). Only the `refused` phase went.

## Open items

| item | owner |
|---|---|
| `modifyAttributes` shares a Caught site with GameData's unfreeze/snapshot, so a failure there first hides it from lib.sh's net; closing it needs a site of its own in KernelLifecycle | kernel lane (S2) |
| `.gitignore:20` `/run/world-events-m49/` and the four deleted gates' untracked rundirs in the stage worktree | RECORD / C6 |
| introduction.md:1538,1749 and introduction.zh-CN.md:392,415 still describe gate-m41 / EventChainAudit | C6 (docs) |
| README.md:303 names `merged_delivers` (history) | RECORD |
| m44 `relocOff` split (optional) | not done |
| re-arming the screen-mouse drill in m9 with MouseTweaks as witness | owner (O3, D62) |
