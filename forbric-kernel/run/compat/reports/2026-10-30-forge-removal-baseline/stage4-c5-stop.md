# Stage 4 C5 — lane STOP: a mod that throws while the game loads stops the boot as its own loader does (D82 extended by D87)

Scope, as corrected by the review fixes (end of this file): every NeoForge mod-loading phase except "Config loading", and the
Fabric main/client/server stages; NeoForge's "Config loading" listeners and Fabric's preLaunch are still contained (open
item 2). "D87" in this lane's code and gates means the brief's "D82 extended by D87"; DECISIONS.md's D87 does not list it
(review B-9, for the orchestrator).

Lane STOP (wave 2d), branch `s4-c5-stop`, cut at 421c53cc (the stage tip after OPT). Owner ruling D82 ("stop like native",
K3c did NeoForge's registration) extended to every phase by D87, as the orchestrator's brief states it. Evidence:
`~/Forbric-backups/stage4-notes/c5/stop/w2d/` (javap/, forbric/, native/, server-compare.tsv, client-summary.txt, red-unit/,
gates/, logs/, tools/).

## What native does (javap of 26.2.0.93, `w2d/javap/`; fabric-loader source in the tree)

- NeoForge runs mod loading as `ModLoader` init tasks: `gatherAndInitializeMods` → "Mod Construction" (each mod's
  `FMLModContainer.constructMod` — constructor, `AutomaticEventSubscriber.inject` — then `FMLConstructModEvent`, in parallel,
  dependents of a failed mod skipped), its "Mod Construction: Deferred Queue"; `CommonModLoader.begin` → "Registry
  initialization" (`RegistryManager.postNewRegistryEvent`: NewRegistryEvent and DataPackRegistryEvent.NewRegistry through
  `postEventWrapContainerInModOrder`, the fill, ModifyRegistriesEvent through `postEvent`; `GameData.postRegisterEvents`
  incl. its tail; `freezeData`), "Config loading"; `CommonModLoader.load` → "Common setup", "Sided setup" (each a
  `dispatchParallelEvent` with its "<phase>: Deferred Queue"), "Registration events" (`RegistrationEvents.init`), "Enqueue
  IMC", "Process IMC", "Complete loading of %d mods", "Network registry lock"; the client's `finish` adds "Client network
  registry lock".
- A listener's throw becomes, in `ModContainer.acceptEvent`, `ModLoadingException(errorduringevent)` naming the mod; a
  constructor or subscriber class `failedtoloadmod` naming the mod; anything else is collected raw. When the task ends,
  `waitForFuture` builds the `ModLoadingException` the boot ends on (per failure an issue: the mod's own, or `uncaughterror`
  with the task name and the cause), logs FATAL "Failed to wait for future <task>, N errors found", empties `ModList`.
- Dedicated server: `ServerModLoader.load` writes `crash-*-fml.txt` and rethrows; `Main` logs "Failed to start the minecraft
  server"; FML's `Server.main` exits 1. Client: `begin` (construction, registry) → `Main`'s handler ("Bootstrap" crash report,
  `ClientModLoader.reportFatalError`); `finish` (every later task) → its own handler (`CrashReport.forThrowable(e, "stage")`,
  category "Finish mod loading", `reportFatalError`).
- Fabric Loader (`FabricLoaderImpl.invokeEntrypoints`): every entrypoint of a stage runs, then `RuntimeException("Could not
  execute entrypoint stage '<key>' due to errors, provided by '<mod>' at '<definition>'!")`, first failure the cause, later
  ones suppressed (`ExceptionUtil.gatherExceptions`). Server: `Main` logs "Failed to start the minecraft server" and returns
  (native Fabric server exit 0, measured). Client: leaves `Minecraft.<init>` for `Main`'s "Initializing game" handler.

## What Forbric did at 421c53cc, and the fix (commits)

| place | at 421c53cc | now | commit |
|---|---|---|---|
| generic | RegistrationStop knew only "Registry initialization" | `stop(cl, task, failure)`, `stopAll` (the parallel aggregate), `failedToLoadMod`, `pending`/`stopPending`, `fabric` | 1c767458 |
| setup phases (construct event, common, sided, IMC x2, load complete) | `bus.post` per mod, throw → DEGRADED, boot went on | `ModContainer.acceptEvent` per mod; after every mod had it, `stopAll` under the native task; deferred queue throw → stop under "<task>: Deferred Queue" | 054f44b5 |
| client, phases after the registry | (contained) | `onNeoClientSetup` hands a stop to `KernelNeoClientStop`, `ClientModLoader.finish`'s handler statement for statement | 054f44b5 |
| construction (constructor, @EventBusSubscriber class) | container withdrawn / DEGRADED, boot went on | `failedtoloadmod` recorded `pending` under "Mod Construction"; the construct event stops on them with its own; `stopPending` after construction | ff256301 |
| NewRegistryEvent, its fill, ModifyRegistriesEvent | per bus, contained | through each mod's container; first throw stops "Registry initialization" | 41a6f64c |
| postRegisterEvents' tail (attribute events, spawn placements, BlockEntityTypeAddBlocksEvent, tooltip appenders, other steps) | each step contained | rethrown, so it leaves `postRegisterEvents` and K3c's stop takes it (no roll-back) | 41a6f64c |
| RegistrationEvents.init | steps after a failing one still ran | ends at the first failing step; "Registration events" stop | 41a6f64c, doc 50c2b9c0 |
| NetworkRegistry.setup / ClientNetworkRegistry.setup | WARN | "Network registry lock" / "Client network registry lock" stop | 41a6f64c |
| DataPackRegistryEvent.NewRegistry | per mod, WARN | through each mod's container; throw stops "Registry initialization" | 959d6c9f |
| Fabric main/server/client stages | per entrypoint contained; policy decided (strict/ask-on-server stopped, ask-on-client asked, continue went on) | Fabric Loader's stage error; server: recorded as the stop (no NeoForge wrapping, no crash-*-fml.txt), Main logs it, exit 1; client: leaves Minecraft.<init> for Main | 1250dc7d |
| crash analysis, client uncaught-error stop (K3c open item 6) | the report alone, which has no trace of the cause | also NeoForge's own record of each ERROR issue's cause (`ModLoader.getLoadingErrors()`, rendered by `CrashReportExtender.generateEnhancedStackTrace`) | 400a3b7a |

No switch: D82/D87 is native, an off position would be the retired containment. Visible errors: every new catch rethrows or
is `Caught.expected` (the failure handed on to the stop) / `Caught.warn` (a call that could not be made at all);
EveryCatchIsVisibleTest green.

## Native comparison (servers: every mode, Forbric vs native; `w2d/server-compare.tsv`, `w2d/forbric/server-*`, `w2d/native/*`)

The register-stop canary (95cfc4e2, 0dc9b01d: modes listener, try, construct, constructevent, ebs, newregistry, datapackreg,
tail, common, deferred, sided, regevents, imc, complete, network, off) on a Forbric server and on the native-controls NeoForge
26.2.0.93 server image (APFS clone, never prepared); the Fabric broken canary on Forbric and on the native-controls Fabric
Loader 0.19.5 server image. Compared: exit, world, Done, "Failed to start the minecraft server", NeoForge's task and error
count, crash-*-fml.txt and its issue sections.

- 15 of the 16 register-stop modes (off included): SAME on every column (incl. the issue sections and their wording).
- complete: same except "Complete loading of 4 mods" vs "3 mods". CORRECTED by review A-3: not staging. With the same mods/
  (register-stop alone) Forbric says 2 and native 3: the kernel's ModList holds fewer containers than native's (THR open
  item 2), and the task name reads ModList.size(). And every Forbric crash-*-fml.txt has an empty "Mod List:", where native
  lists the mods (compare.py did not look; review-fix/server-compare-modlist.tsv does). Both -> orchestrator.
- off: both reach Done, exit 0, world created.
- fabric: same stage error naming forbricbrokencanary, "Failed to start the minecraft server", no world, no Done; exit 1 on
  Forbric, 0 on native Fabric. Kept: Forbric's server launcher exits 1 after any stop, as FML's launcher does for the
  NeoForge side (open item 3).

Clients (no native client image, compared by javap as K3c): every mode except datapackreg (added later; run by gate-m24d
since the review fixes) once on a Forbric client (`w2d/forbric/client-*`,
`client-summary.txt`; listener, try, construct, common, fabric in gate-m24d). Begin path (construct, constructevent, ebs,
newregistry, tail, listener, try): "Description: Bootstrap", no saves/. Finish path (deferred, sided, regevents, imc,
complete, network, common): "Description: stage" with "-- Finish mod loading --", saves/ present and empty (natively
`LevelStorageSource.<init>` creates it before `ClientModLoader.finish`, javap). Each sat in NeoForge's fatal-error box
("held") and the crash analysis named forbricregisterstop every time. fabric: "Initializing game", exit 255.

## Tests and red controls

- New: NeoSetupPhaseStopStagedTest (4: setup listener, deferred task, NewRegistryEvent, clean control; runs the compiled
  `KernelNeoSetup`/`KernelNeoRegistries` over the real carrier; also reads NeoForge's record for the crash analysis),
  KernelModConstructionStopTest (real `constructMods` with a throwing @Mod), CrashAttributionTest +1.
- Changed (pinned the containment; old reason in their javadoc): RegisterEventsSeamWeaveTest tail case,
  RegistrationEventStepsTest x2, KernelLifecycleAttributeEventsTest x1, InitializationFailureProjectionTest,
  EntrypointResolveFailureTest.
- Red on 421c53cc (clone, src/main+src/runtime at 421c53cc, tests at the tip minus the two classes that use new API;
  `w2d/red-unit/base-421c53cc.summary`): exactly the 8 stop tests red (setup x3, construction, tail, attribute events,
  Fabric x2); every control green. Mutants on the tip (`red-unit/mutants.summary`): RegistrationEventSteps without the
  `break` → both step tests red; CrashAttribution ignoring NeoForge's record → the new crash test red.
- ci-unstaged.tsv +5 rows (43086c5d), measured with an empty staged root.

## Gates (lane block 46900-46919, -j 2, `w2d/gates/run1`, `run2`)

Run 1 at 0dc9b01d: m1 19/0, m2 29/0, m2b 38/0, m7-neo 28/0, m9 163/0, m10 24/0, m19 40/0, m20 41/0, m21 54/0, m24 164/1,
m24b 23/0, m24c 25/0, m24d 62/2, m25 16/0, m26 49/0, m30 38/0. The three FAILs were the new checks' own mistakes, fixed in
b4f83c3a (m24 control's absence matched the EBS summary's "0 skipped because the owning mod did not finish loading"; m24d
"-- Finish mod loading --" read by grep as options; m24d common required no saves/, which natively exists there).
Run 2 at b4f83c3a: m24 165/0, m24d 64/0. Every other gate's inputs unchanged since run 1.

CAUGHT: m19 1 (forbricnestlib's main in the arbitration-off control — that boot now stops on the Fabric stage error; its
checks only read the two claim lines, which still print), m24 3 and m24d 2 (the canaries' constructor, subscriber class and
Fabric main — the failures the stops hand on), m30 1 (the subscriber canary's class).

Red control of the gate changes (the tip's m24, m24d, m30 scripts on the 421c53cc kernel, `w2d/gates/red-421c53cc/`): RED — m24 69 PASS / 91 FAIL
(every one of the 8 new stop boots red: construct 12, ebs 13, tail 10, common 10, deferred 10, regevents 10, network 10,
fabric 7, plus 8 harness lines "server never announced a stop within 130s" and lib.sh's "1 entity-attribute event(s) did
not finish"; K3c's listener/try boots and the control green), m24d 37/27 (construct 9, common 9, fabric 8, and exactly
one check of try: "Forbric's crash analysis names forbricregisterstop" — K3c open item 6; listener and the control green),
m30 25/15 (subscriber 7, abi 6, and 2 harness lines; the mixin boot and the control green). So every check the lane added
or changed is red where it guards something, and green on the tip.

## Gates and tests that relied on containment (old reason → now)

- gate-m24: the Fabric broken canary's continue boot (reached Done, load report named it) and strict boot (policy stop 78)
  → one boot that must stop as native Fabric does; seven NeoForge stop boots added (construct, ebs, tail, common, deferred,
  regevents, network) beside K3c's listener/try.
- gate-m24d: + construct (begin path), common (finish handler), fabric; try now requires the analysis to name the mod.
- gate-m30: forbricsubscribercanary and forbricabicanary rode the main boot to Done → each boots alone and must stop as
  native, named by NeoForge's report and the crash analysis, the kernel's own line (EBS, AbiAudit) still logged; the mixin
  canary keeps the main boot (summary and report now name one mod). The header's red demonstrations were measured on the
  three-canary boot; not re-measured (open item 6) — while the header said they had been (review B-1; re-measured in the
  review fixes).
- gate-m20: its required loss came from the Fabric broken canary's throwing main → an unfit required mixin (the m30 mixin
  canary repacked required, UnfitMixin alone).
- gate-m7-neo, gate-m9, compat/assert.sh: "<mod> failed during <phase>" no longer exists → NeoForge's own "Failed to wait
  for future".
- gate-m19: unchanged (see CAUGHT).

## Commits (s4-c5-stop, on 421c53cc; nothing pushed, main untouched)

1c767458 RegistrationStop for any init task; 054f44b5 setup phases + deferred work + client finish handler; ff256301
construction; 41a6f64c NewRegistryEvent, postRegisterEvents' tail, RegistrationEvents, network locks; 1250dc7d Fabric
entrypoint stages; 400a3b7a crash analysis reads NeoForge's record; 50c2b9c0 RegistrationEventSteps javadoc; 95cfc4e2
register-stop canary modes; fc1a5a69 gate-m24/m24d; 40b019f3 gate-m30/m20; 959d6c9f DataPackRegistryEvent.NewRegistry;
0dc9b01d canary datapackreg mode; b4f83c3a three gate-check fixes; 43086c5d ci-unstaged rows; 18f0915d KernelRuntimeClasses
entry; 9837bcca RuntimeSeamLivenessStagedTest entry; and this record.

## Suite

Final, at 9837bcca (heavy.sh, pinned props, plain --offline, `-Pforbric.requireFixtures=all jar cleanTest test --continue`;
`w2d/logs/suite.log`, XML `w2d/suite-xml/`, `w2d/failures-stop.txt`): **5899 tests, 0 skipped, 5 failed — exactly the five
names of failures-w2c.txt (D58: LiquidBounce x3, SharedFinalSourceActual x2), 0 new.** 5893 (OPT's stage tip) -> 5899:
NeoSetupPhaseStopStagedTest +4, KernelModConstructionStopTest +1, CrashAttributionTest +1 (renamed tests keep their count).
Two earlier full runs found the lane's own misses, fixed and re-run: at 43086c5d KernelRuntimeClassesTest x2 (the new
game-side class unregistered, 18f0915d), at 18f0915d RuntimeSeamLivenessStagedTest x3 (its boot-side caller unnamed,
9837bcca). gate-m1 re-run at 9837bcca (the registry entry is checked at every boot): 19/0 (`w2d/gates/run3`).

## For THR (merges before STOP) — what a rebase onto it touches

- `KernelNeoSetup.firePhase` (per-mod `acceptEvent`, failures collected, `stopAll`, deferred stop) and
  `KernelModLoader.constructMods`' constructor catch (`RegistrationStop.pending`): if THR runs construction or the phases on
  `modloading-worker`, its dispatch replaces the loop, and the stop must stay after every mod's task (as `waitForFuture`),
  on the calling thread. `KernelEventSubscribers.registerAll`'s catch likewise.
- NeoSetupPhaseStopStagedTest asserts the order `[thrower, healthy]` and KernelModConstructionStopTest one construction
  order; under parallel dispatch those become set assertions.
- Also a content conflict (review B-7, `git merge-tree`): `KernelNeoRegistries` — THR sets the active container per bus in
  `postNewRegistryEvent`, STOP routes the same loop through `container.acceptEvent`; keep both. And STOP and DLG both
  change `gate-m24d-registerstop-client.sh` (DLG merges after STOP, so its step resolves it).
- `RegistrationStop` (public now) and `KernelLifecycle.onNeoClientSetup` / `fireSetupPhase` carry no thread placement.

## Open items (owner)

1. (orchestrator) Not modelled: natively a mod depending on a failed one skips the task (`DependentFutureFailedException`);
   Forbric still gives it the phase, so its own failure could add a second issue. The boot stops either way.
2. (orchestrator) Still contained: a Fabric `preLaunch` entrypoint (marks the mod FAILED; native Fabric crashes), a throwing
   Fabric injector at the moved freeze point (`fabricFreezePoint`), and NeoForge's "Config loading" task (a
   `ModConfigEvent.Loading` listener) — not measured here.
3. (orchestrator / owner) A Fabric stage stop on a dedicated server exits 1 (native Fabric 0); kept as FML's launcher rule
   for every stop. K3c open item 4 (other Main returns without a server thread exit 0) unchanged.
4. (orchestrator) `KernelModLoader`'s withdrawal of a failed constructor's container and the FAILED marks of failed
   phases (KernelNeoSetup; this said DEGRADED) still run before the stop; on the throw path they no longer change the
   outcome — except the load report written after the stop, which reads them. Inert on the Fabric side too: the
   CompatibilityFindings projection of a failed main/client/server entrypoint and the policy's ask about it. A cleanup,
   not done here.
5. FIXED in the review fixes (970ee79e): measured by review A (exit 255, "Initializing game", after the Fabric main); the
   client now hands the mods the event in the registry phase and stops in the begin path.
6. FIXED in the review fixes (58400195): re-measured, 7/3/2 on the 49-check gate.
7. (owner, D87b, another lane) the macOS fatal-error box text (F2/B-6) — every client stop here showed it.
8. RECORD / release notes "Changed": a mod that throws while the game loads (NeoForge: any phase; Fabric: a main/client/
   server entrypoint) now stops the game with its loader's error, under every compatibility policy; Forbric's crash analysis
   names it, also on a client for NeoForge's "uncaught parallel processing error".

## STOP review fixes (2026-11-16; reviews `c5/review-stop-w2d-A.md`, `c5/review-stop-w2d-B.md`; evidence `c5/stop/w2d/review-fix/`)

Branch s4-c5-stop 40a71d1a -> the record commit after 58400195. Every finding was checked against code, logs or a run before
acting on it. Nothing merged or pushed; main untouched.

| finding | verdict | what was done | commit |
|---|---|---|---|
| A-1 stop writes a load report with the carry-on note | confirmed (review A's complete-alone report; gate red below) | `KernelLoadReport.render(..., stopped)`: after a stop the advice and note say the game stopped as the mod's loader stops it, both languages; a stopped report with no mod row (gate-m24 regevents, found by the first gate run) says so too | 024abb2f, 947f1d27 |
| A-2 m30 dropped the report checks; the ABI reason lost on FAILED | confirmed (review A's abi-alone report) | `ModCatalog`: a rise to FAILED puts the new reason first and keeps the earlier clauses; a mod whose @EventBusSubscriber cannot register is FAILED (natively its construction fails), not DEGRADED; m30 checks both stops' reports again (subscriber row, deferred task, ABI finding beside it) | 0570b99c, ee1f0fab, 9d5907d0 |
| A-3 "Complete loading of N" and the empty crash Mod List misattributed | confirmed (review A's same-mods runs: Forbric 2, native 3; compare.py rerun with a Mod List column: empty on every Forbric mode, `review-fix/server-compare-modlist.tsv`) | record corrected above; the fix is THR open item 2's (ModList/LoadingModList composition) -> orchestrator | record |
| A-4 client datapackreg stops in "Initializing game" after the Fabric main | confirmed (review A's run; red on 40a71d1a: exit 255, no Bootstrap, Fabric main ran) | step 3a hands the event to the baseline bus and every mod on both sides (`collectDataPackRegistryDeclarations`); only NeoForge's `process()` and the mirroring wait for a client's Fabric mains (they initialise RegistryDataLoader). Now: exit 1/held, "Bootstrap", no Fabric main. m24d gains datapackreg | 970ee79e, 9d5907d0 |
| A-5 "every Fabric stage" overclaims (preLaunch contained) | confirmed | scope note at the top of this record; preLaunch stays open item 2 | record |
| A-6 orphan dispatch failure named "provided by '?' at ''" | confirmed (unit red on 40a71d1a) | the stage error names the losing library and its dispatch site | ee1f0fab |
| A-7 stop sites counted as "carried on from" | confirmed (m24 CAUGHT 3, m30 1 at 40a71d1a) | constructor, @EventBusSubscriber and Fabric entrypoint catches are `Caught.expected` + an ERROR line naming the task that stops on it; m24/m24d/m30 now report CAUGHT none | ee1f0fab |
| B-1 m30 header claims a re-measurement that did not happen | confirmed | re-measured at 947f1d27: loadReportRewrite=off 7 FAIL, mixinErrorAttribution=off 3, abiAudit=off 2, none of the control's (`gates/tip-m30sw-*`) | 58400195 |
| B-2 lib.sh's fatal attribute check matches a line nothing prints | confirmed (grep: no producer of "modifyAttributes() THREW") | matches "modifyAttributes() could not be called"; contract fixture follows | 64692fb7 |
| B-3 "every phase" overclaims (Config loading contained) | confirmed | scope note at the top; Config loading stays open item 2 | record |
| B-4 no staged test / red for datapackreg, client network lock | confirmed | canary mode clientnetwork; m24d datapackreg + clientnetwork phases; m24 server datapackreg boot; DatapackRegistryDeclarationTest pins the early delivery; red on 421c53cc for the boots (below). NewRegistryEvent.fill / ModifyRegistriesEvent still have no fixture (open) | a44bbfb7, 9d5907d0, 970ee79e |
| B-5 "every mode on a client" untrue (datapackreg) | confirmed | record corrected; gate-m24d runs it | record |
| B-6 Fabric stop line prints "%s" and the trace again | confirmed (`server-fabric/latest.log` l.228; compare: stage error x2) | one line, no trace, naming stage and mod; m24 counts Fabric Loader's stage error exactly once | 37ab6d03, 9d5907d0 |
| B-7 handoff misses the KernelNeoRegistries conflict | confirmed (`git merge-tree --write-tree s4-c5-thr s4-c5-stop`: KernelModLoader, KernelNeoRegistries, KernelNeoSetup; with s4-c5-dlg: gate-m24d) | "For THR" and handoff corrected | record |
| B-8 m20's changed check had no red control | done by review A, verified here: m20 with the mixin canary repacked required=false 34/7, all 7 in the required-loss steps (`review-stop-w2d-A/logs2/m20-red.log`) | — | — |
| B-9 "D87" cited for the stop extension; DECISIONS.md D87 has no such item | confirmed | not changed in code (48 citations, all this lane's); orchestrator to add D87(e) "D82 extended to every mod-loading phase and the Fabric entrypoint stages (lane STOP)" or say otherwise | — |
| B-10 notes | confirmed | handoff "18 commits" -> 17; `gates/run2/` m24b/m24c logs are byte copies of run1's (NOTE.txt added there); open item 4 corrected above; the server/client comparison batches ran 05:18-05:29 on the working tree between 400a3b7a and 0dc9b01d (commit times bracket it; the jar was not recorded), server datapackreg at 05:43 after 0dc9b01d | record |

Found by the fix runs, fixed: `RegistrationEventSteps`' finding for a failed step still said "Forbric ran the remaining
registration steps without it" (947f1d27).

### Red controls (D34)

- Unit (clone: src/main+runtime at 40a71d1a, tests at the fix tip except RegistrationStopTest and KernelLoadReportTest at
  40a71d1a, which use new API; `review-fix/red-unit/ctl-40a71d1a.summary`): 88 tests, exactly the 9 new/changed red —
  ArbitratedAwayPlacement (orphan names '?'), DatapackRegistryDeclaration (no early delivery), EntrypointResolveFailure and
  InitializationFailureProjection and KernelModConstructionStop (Caught failures at stop sites), KernelEventSubscribers
  (DEGRADED), KernelModCatalog (reason replaced), LibCaughtContract (old lib.sh pattern misses the line), RegistrationEventSteps
  (stale finding); 79 green. The two classes left at 40a71d1a are shown red by the gates (Fabric line, stop reports).
- Gates, the fix tip's m24/m24d/m30 on the 40a71d1a kernel (`gates/red-40a71d1a`): m24 185/13 (fabric line, stage error x2,
  every present stop report: fabric/construct/ebs/common/deferred carry-on note, regevents no stop wording), m24d 82/4
  (datapackreg exit 255, not Bootstrap, Fabric main ran; fabric line), m30 43/6 (both stop reports; the ABI finding).
- B-4's boots on the 421c53cc kernel (control copy of m24 with only the datapackreg boot and the control; full m24d;
  `gates/red-421c53cc-b4`): m24 datapackreg 9 red (never stops: Done, world, no crash report, no
  analysis) plus the harness's 130 s kill line, the control green (13 PASS); m24d datapackreg 11 red and clientnetwork 8 red
  (neither stops: killed after the sound engine), besides 421c53cc's known construct 9 / common 9 / fabric 8 / try 1, the
  listener phase and the control green.
- Tip 947f1d27: m24 198/0, m24d 86/0, m30 49/0, CAUGHT none in all three (`gates/tip2`). The first tip run (9d5907d0,
  `gates/tip1`) had m24 197/1 (regevents' row-less report, fixed 947f1d27) and m24d broken mid-run by an edit to its own
  script (walls).

### Final

Suite at 58400195 (heavy.sh, pinned props, plain --offline, `-Pforbric.requireFixtures=all jar cleanTest test --continue`;
`review-fix/suite.log`, XML `review-fix/suite-xml/`, `review-fix/failures-stop-rf.txt`): **5902 tests, 0 skipped, 5 failed —
exactly the five D58 names of failures-w2c.txt, 0 new.** 5899 -> 5902: RegistrationStopTest +1, ArbitratedAwayPlacementTest +1,
KernelLoadReportTest +1.

Gates at 58400195, the lane's 16 acceptance gates, -j 2, --no-cache (no cached verdict), lane block 46900-46919
(`review-fix/gates/final`): m1 19/0, m2 29/0, m2b 38/0, m7-neo 28/0, m9 163/0, m10 24/0, m19 40/0, m20 41/0, m21 54/0, m24 198/0,
m24b 23/0, m24c 25/0, m24d 86/0, m25 16/0, m26 49/0, m30 49/0 — all GREEN, CAUGHT none in every one (was m19 1, m24 3, m24d 2,
m30 1). The datapack-delivery move (970ee79e) runs on every client boot, hence the client gates m9/m10/m20/m24d/m26 here.

### Open (after the fixes)

- Server and client both declare datapack registries after the registry window and its freeze (step 3a); natively
  `postNewRegistryEvent` posts DataPackRegistryEvent.NewRegistry before RegisterEvent and the freeze, so on a stop there the
  freeze (and, on a server, the Fabric mains) already ran. Not asserted by m24/m24d (orchestrator).
- NewRegistryEvent.fill and ModifyRegistriesEvent throws: still no fixture (B-4 rest).
- Crash `Mod List:` empty and "Complete loading of N" short (A-3) -> orchestrator / THR item 2.
- D87(e) in DECISIONS.md (B-9) -> orchestrator.
- Merge: THR conflicts KernelModLoader (now also the A-7 catch), KernelNeoRegistries, KernelNeoSetup; DLG conflicts
  gate-m24d (now also the datapackreg/clientnetwork phases).
- Unchanged from the lane: open items 1, 2 (Config loading, preLaunch, freeze-point handlers), 3, 4, 7.
