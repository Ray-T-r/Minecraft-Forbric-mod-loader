# Stage 4 C5 — lane STOP: a mod that throws while the game loads stops the boot as its own loader does (D82 extended by D87)

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
- complete: same except "Complete loading of 4 mods" vs "3 mods" — the Forbric run staged the NeoForge live canary too
  (4 NeoForge containers), the native run the register-stop canary alone. Staging, not behaviour.
- off: both reach Done, exit 0, world created.
- fabric: same stage error naming forbricbrokencanary, "Failed to start the minecraft server", no world, no Done; exit 1 on
  Forbric, 0 on native Fabric. Kept: Forbric's server launcher exits 1 after any stop, as FML's launcher does for the
  NeoForge side (open item 3).

Clients (no native client image, compared by javap as K3c): every mode once on a Forbric client (`w2d/forbric/client-*`,
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
  three-canary boot; not re-measured (open item 6).
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
- `RegistrationStop` (public now) and `KernelLifecycle.onNeoClientSetup` / `fireSetupPhase` carry no thread placement.

## Open items (owner)

1. (orchestrator) Not modelled: natively a mod depending on a failed one skips the task (`DependentFutureFailedException`);
   Forbric still gives it the phase, so its own failure could add a second issue. The boot stops either way.
2. (orchestrator) Still contained: a Fabric `preLaunch` entrypoint (marks the mod FAILED; native Fabric crashes), a throwing
   Fabric injector at the moved freeze point (`fabricFreezePoint`), and NeoForge's "Config loading" task (a
   `ModConfigEvent.Loading` listener) — not measured here.
3. (orchestrator / owner) A Fabric stage stop on a dedicated server exits 1 (native Fabric 0); kept as FML's launcher rule
   for every stop. K3c open item 4 (other Main returns without a server thread exit 0) unchanged.
4. (orchestrator) `KernelModLoader`'s withdrawal of a failed constructor's container and the DEGRADED marks of failed
   phases still run before the stop; on the throw path they no longer change the outcome. A cleanup, not done here.
5. (kernel) The client's DataPackRegistryEvent declaration runs inside `Minecraft.<init>` (after the Fabric client
   entrypoints, or at finish); a throw there now stops via Main's "Initializing game" handler or the finish handler, where
   native NeoForge would stop in the begin path. Not measured on a client.
6. (orchestrator) gate-m30's header red demonstrations (loadReportRewrite/mixinErrorAttribution/abiAudit switches, 7/3/2
   FAILs) were measured with three canaries in one boot; with the mixin canary alone the numbers change and were not
   re-measured.
7. (owner, D87b, another lane) the macOS fatal-error box text (F2/B-6) — every client stop here showed it.
8. RECORD / release notes "Changed": a mod that throws while the game loads (NeoForge: any phase; Fabric: a main/client/
   server entrypoint) now stops the game with its loader's error, under every compatibility policy; Forbric's crash analysis
   names it, also on a client for NeoForge's "uncaught parallel processing error".
