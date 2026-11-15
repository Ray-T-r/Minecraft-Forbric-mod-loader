# Stage 4 C5 — lane K3c: a NeoForge registration failure stops the boot, as native NeoForge does (D82)

Lane K3c (wave 2c), branch `s4-c5-k3c`, cut at the stage tip 2c231c9c. Owner ruling D82 ("Stop like native"), which
closes K3's open items B-8 and A-4. Evidence: `~/Forbric-backups/stage4-notes/c5/k3c/w2c/` (javap/, native/, forbric/,
red-unit/, gates/, logs/).

## What native NeoForge 26.2.0.93 does (javap, `w2c/javap/`; measured, `w2c/native/`)

- `ModContainer.acceptEvent` catches a listener's throw, logs "Caught exception during event … dispatch for modid …" and
  throws `ModLoadingException(ModLoadingIssue.error("fml.modloadingissue.errorduringevent", <event class>)
  .withAffectedMod(modInfo).withCause(t))`. It leaves `ModLoader.postEventWrapContainerInModOrder` (no catch in
  `postEventWithWrapInModOrder` / `ModList.forEachModInOrder`), so that registry's dispatch ends at that mod: later mods
  and later priority phases never get it.
- `GameData.postRegisterEvents` catches it per registry (`catch Throwable → aggregate.addSuppressed`), goes on with the
  next registries, then — any suppressed — logs "Failed to register some entries, see suppressed exceptions for details"
  and "Rolling back to VANILLA state", calls `RegistryManager.revertToVanilla()` and throws the aggregate before its tail.
  Anything else thrown inside the per-registry try (a guest handler woven after the dispatch, `new RegisterEvent`,
  `StartupNotificationManager.modLoaderMessage`) takes the same path.
- `CommonModLoader.begin` runs it as the init task "Registry initialization" (`ModLoader.runInitTask` →
  `waitForTask` → `waitForFuture`): the aggregate is not "meaningful" (no message, a RuntimeException), each suppressed
  becomes an issue (a `ModLoadingException`'s own issues; otherwise `fml.modloadingissue.uncaughterror` with arg
  "Registry initialization" and the cause), FATAL "Failed to wait for future Registry initialization, N errors found",
  `cancelLoading` ("ERROR DURING MOD LOADING", `ModList.setLoadedMods(emptyList)`), `throw new ModLoadingException(issues)`.
  `freezeData` and `registriesLoaded = true` never run.
- Dedicated server: `ServerModLoader.load` catches it — `hasErrors = true`, `LanguageHook.loadBuiltinLanguages()`,
  `CrashReportExtender.dumpModLoadingCrashReport(LOGGER, issues, new File("."))` (crash-reports/crash-*-fml.txt) — and
  rethrows; `net.minecraft.server.Main` logs "Failed to start the minecraft server" (FATAL marker) and returns; FML's
  `net.neoforged.fml.startup.Server.main` finds no "Server thread", throws `FatalStartupException("Couldn't find
  Minecraft server thread. Startup likely failed.")`, prints it and `System.exit(1)`.
- Client: `ClientModLoader.begin` has no handler; the client `Main`'s handler (stage "Bootstrap") builds a crash report
  ("Initialization" category), saves it, calls `ClientModLoader.reportFatalError` → `FatalErrorReporting.reportFatalError`
  → `ImmediateWindowHandler.displayFatalErrorAndExit` (the early window, when there is one), else
  `reportFatalError(String)`: a Swing dialog, or headless a tinyfd message box titled "NeoForge - Fatal Startup Error",
  then `System.exit(1)`. There is no LoadingErrorScreen for this case in 26.2 (that screen only shows warnings, from
  `completeModLoading`).

Measured on the native-controls server image (the official installer's, APFS clone, never prepared) with the K3c canary
(`w2c/native/{listener,try,off}`, mods.sha1 beside each):

| mode | lines | crash report | world | exit |
|---|---|---|---|---|
| listener | "reached RegisterEvent for minecraft:item", FMLModContainer "Exception caught during firing event", "Rolling back to VANILLA state", "Failed to wait for future Registry initialization, 1 errors found", "Failed to start the minecraft server" with "- Forbric Register Stop Canary (forbricregisterstop) encountered an error while dispatching the net.neoforged.neoforge.registries.RegisterEvent event", "Couldn't find Minecraft server thread" | crash-*-fml.txt, "Mod loading issue for: forbricregisterstop" | absent | 1 |
| try | the canary's mixin line, "Rolling back…", "Failed to wait…, 1 errors found", "- An uncaught parallel processing error has occurred." with the cause | crash-*-fml.txt | absent | 1 |
| off | "Done (1.347s)!" | none | present | 0 |

## What Forbric did at 2c231c9c

- `KernelLifecycle.onRegisterEvent` (the seam's target for the dispatch call) delivered to every container and
  contained a throwing one (`Caught.warn` "… failed on <registry> — <id>'s remaining content for it is skipped"): that
  mod lost the registry, every other mod got it, no roll-back, the boot carried on (B-8).
- A throw inside the per-registry try outside the delivery (A-4) reached NeoForge's roll-back; `dispatchRegisterEvents`
  turned it into one `Caught.error`, ran the Fabric mains and the window closed and froze the vanilla-only registries.
  Measured on the red run below (try mode, the old kernel): the boot went on, `GameData.freezeData()` threw, then the
  world's datapack registries failed ("Unknown registry key … neoforge:biome_modifier_serializers", "Failed to load
  registries due to errors") and the process exited 0 with no crash-*-fml.txt (`w2c/gates/m24-red-logs/`).

## Fix (c1c2a3ec)

- `onRegisterEvent` delivers with `stopAtFirstFailure`: the first container that throws ends the delivery and its
  failure (NeoForge's `ModLoadingException` from the genuine `acceptEvent`) leaves for `postRegisterEvents`' own catch,
  as NeoForge's fan-out. The kernel still says which mod ended which registry (ERROR, wording kept up to "RegisterEvent
  listener failed", so m9's absence check and compat/assert.sh still match).
- `dispatchRegisterEvents` hands whatever `postRegisterEvents` throws to the new `RegistrationStop.stop`, which logs one
  ERROR naming the roll-back and causes ("— the boot stops here, as native NeoForge stops it"), builds the native exception
  with NeoForge's own `ModLoader.waitForTask("Registry initialization", …, failedFuture(cause))` (after pointing
  `ModLoader.modList`, which only `gatherAndInitializeMods` sets, at the published `ModList`, so `cancelLoading` empties it
  as natively), and throws it. The Fabric step does not run after it.
- `registerNeoForgeContent` lets the stop through (its outer catch otherwise still reports "could not register ecosystem
  content"); its finally neither closes/freezes the window nor runs Fabric's TAIL freeze point once
  `RegistrationStop.stopped()`.
- `onServerModLoading` (the redirect of `ServerModLoader.load`) does what that method's handler does
  (`RegistrationStop.asServerModLoaderDoes`) and rethrows to `Main`; `onClientModLoading` (the redirect of
  `ClientModLoader.begin`) lets it reach the client `Main`'s handler unchanged.
- `KernelServerLaunch` exits 1 after a stop (`RegistrationStop.serverExit`), printing why, as FML's `Server.main` does.
- `RegisterEventsSeam`: javadoc, stand-down WARN and applied INFO say a failing listener stops the boot (gate-m1/m9 and
  compat/assert.sh match `posts RegisterEvent itself, every mixin on it applied: .* [0-9]+ tail step`, unchanged).
- Visible errors: the stop is rethrown, never swallowed; the only new catches are `Caught.expected` (the
  `InvocationTargetException` whose cause is the answer handed on) and `Caught.warn` (NeoForge's ModLoader or the
  ServerModLoader steps unavailable — the boot still stops on the original throw). EveryCatchIsVisibleTest, CaughtTest and
  LibCaughtContractTest green; m24's CAUGHT report is unchanged (only the Fabric broken canary's main).
- No switch: D82 is "native", and an off position would be the retired containment.

## Tests and red controls

- `RegisterEventsSeamWeaveTest` (real Mixin, a stand-in GameData that is .93's method statement for statement):
  `aThrowingRegisterEventListenerStopsTheBootAsNeoForgeDoes` — trace `mixin:head, neoforge:a, first:a, thrower:a, last:a,
  mixin:after:a, neoforge:b, first:b, neoforge:c, …, mixin:after:c, revertToVanilla, waitForTask:Registry initialization`,
  outcome `stopped=ModLoadingException`, no Fabric step, no tail; `aGuestThrowInsideNeoForgesTryStopsTheBootAsNeoForgeDoes`
  (replaces `aGuestThrowInsideNeoForgesTryIsNeoForgesRollBackAndIsNamedSo`, which asserted the boot carried on). The
  fixture's ModContainer throws a `ModLoadingException` as NeoForge's does (only with `-Dfixture.registerevents.listenerThrows`,
  so the no-throw trace now includes `thrower:b`), its ModLoader gained `waitForTask` and the `modList` field.
- `RegistrationStopGenuineCarrierTest` (staged carrier + Minecraft's libraries): the aggregate with a listener's
  `ModLoadingException` and a guest's `IllegalStateException` becomes NeoForge's own `ModLoadingException` with two issues
  (`errorduringevent` unchanged; `uncaughterror` ["Registry initialization"] with the guest's cause), ModLoader's
  `modList` is the published list, NeoForge's loading issues hold both.
- `KernelRegistrationIsolationTest.whatTheRegisterEventDispatchThrowsStopsTheBoot` replaces
  `theRegisterEventDispatchCannotTakeTheRestOfTheWindowWithIt` (which pinned the containment): the handler around
  `postRegisterEvents` calls `RegistrationStop.stop` and throws, the window's catch lets `isStop` through.
- `FabricFreezePointWindowTest`: the walker follows `RegistrationStop.stopped()`; a new assertion — after a stop no way
  out of `registerNeoForgeContent` closes the window or reaches the TAIL half.
- `AssertCompatTest`: the sample seam line follows the new wording.
- Red on 2c231c9c (APFS clone, `src/main` restored to 2c231c9c, `src/test` at c1c2a3ec minus the genuine-carrier test,
  `w2c/red-unit/`, TREE.txt): 18 tests, exactly 4 red — both weave stop tests (old traces: listener
  `posted=3 … last:b, mixin:after:b … kernel:fabric-mains, …tail…`; guest `posted=0 … revertToVanilla, kernel:fabric-mains`),
  the isolation test, and the freeze-point walk (old paths `[close window, TAIL, return|throw]`).
- ci-unstaged.tsv +1 row for RegistrationStopGenuineCarrierTest (fb80a318; measured with an empty staged root: 19 tests,
  1 skipped = that test). It runs in ci-prepared.

## Fixture and gate

- `canary/register-stop` → `run/canary/forbricregisterstop.jar` (2e3b853f, `run/build-register-stop-canary.sh`):
  `-Dforbric.registerstop=listener` (default; its RegisterEvent listener throws on minecraft:item) / `try` (its mixin on
  `GameData.postRegisterEvents`, corelib's shape, throws after minecraft:item's dispatch) / `off`.
- gate-m24 (bf508b32): two stop phases under explicit continue (listener, try) and the clean control now carries the canary
  with `=off`. Checks per stop phase: exit 1, NeoForge's roll-back line, "Failed to wait for future Registry
  initialization, 1 errors found", the kernel's stop line, "Failed to start the minecraft server", the launcher's exit-1
  line, crash-*-fml.txt with "Mod loading has failed" and the cause; absent "Done (", the Fabric live canary's
  onInitialize, "froze the registries NeoForge-first" (present in the control), a world directory. Listener also: the
  kernel names the mod and registry; NeoForge's error names "(forbricregisterstop) encountered an error while dispatching
  the …RegisterEvent event". Try also: "An uncaught parallel processing error has occurred".
  - tip (bf508b32's tree): GREEN, 63 PASS / 0 FAIL (`w2c/gates/m24-draft.out`, phase logs `m24-tip-logs/`).
  - red control: the same gate on the 2c231c9c kernel (the red-unit clone): RED, 37 PASS / 24 FAIL
    (`w2c/gates/m24-red-2c231c9c.out`, logs `m24-red-logs/`): listener reached Done and created a world (killed after
    130 s, no stdin feeder in a stop phase), try rolled back, went on and died later on datapack registries, exit 0.

## Forbric runs of the canary (`w2c/forbric/`)

- dedicated server, listener (`server-listener/`, tree c1c2a3ec + the uncommitted m24 edit): the same lines as native in
  the same order, plus the kernel's two lines; crash-*-fml.txt "Mod loading issue for: forbricregisterstop" (same
  sections as native's); no world; exit 1. try and off: in the m24 run above.
- client, listener (`client-listener/`, tree bf508b32): "Rolling back to VANILLA state", "Failed to wait for future
  Registry initialization, 1 errors found", crash-*-client.txt (Description "Bootstrap", the `ModLoadingException`
  naming the mod and the cause), then `FatalErrorReporting.reportFatalError` → tinyfd → an osascript dialog titled
  "NeoForge - Fatal Startup Error" waiting for OK (jstack: main in `TinyFileDialogs.ntinyfd_messageBox` under
  `ClientModLoader.reportFatalError` under `Main.main`); no `saves/`, no Fabric main, no Minecraft instance. The script
  ended it after 180 s (exit 143). The dialog's text read "INVALID MESSAGE WITH QUOTES": tinyfd rejected NeoForge's
  message (NeoForge replaces only `"` and `'`). The osascript child outlived the killed JVM and was killed by hand.
- No native client run: the native-controls cache holds only the server image, and preparing a client install is not
  allowed here; the client side is compared by javap (above). Natively NeoForge may show the error in its early window
  (`ImmediateWindowHandler` with a provider); Forbric has never had one (pre-existing), so NeoForge's own fallback dialog
  is what shows.

## Fabric side (checked, not changed)

- Native Fabric Loader (0.18.4, javap of `FabricLoaderImpl.invokeEntrypoints`, `w2c/javap/`): every entrypoint of a
  stage is called, failures collected, then `RuntimeException("Could not execute entrypoint stage '%s' due to errors,
  provided by '%s' at '%s'!")` — on a dedicated server that leaves `Hooks.startServer` inside `Main`'s try ("Failed to
  start the minecraft server"); on a client it crashes `Minecraft.<init>`.
- Forbric: a Fabric main that throws is contained per mod (`KernelFabricEcosystem.invokeOne`, Caught) and the
  compatibility policy decides (`CompatibilityDecision.decide`): strict → launch stopped, exit 78 (m24's strict phase);
  ask (default) on a dedicated server, no display → stopped (code path, not measured here); ask on a client → the player
  is asked; continue → the boot carries on (m24's continue phase, m19, m20 rely on it). Not the same code path as D82
  (the mains run from `runFabricMainsInTheWindow`, after NeoForge's roll-back test, with their own catch), so unchanged.
  For the orchestrator: native Fabric stops; Forbric only stops under strict / default-ask on a server.

## Results

- Gates at bf508b32 (gates-all -j 2 in the lane block 46900-46919, client lock + both slots, `w2c/gates/batch/`), PASS/FAIL
  vs VERIFY run 1's 2c231c9c sweep (block 2): m1 19/0 (19/0), m2 29/0 (29/0), m2b 38/0 (38/0), m3 13/0 (12/1, VERIFY's lib.sh
  settle red, HARN's lane), m7-neo 28/0 (28/0), m9 163/0 (163/0), m10 24/0 (24/0), m19 40/0 (40/0), m20 41/0 (41/0),
  m21 54/0 (54/0), m24 63/0 (30/0: +33 new checks), m24b 23/0 (23/0), m24c 25/0 (25/0), m25 16/0 (16/0), m26 49/0 (49/0),
  m30 28/0 (28/0), m51 16/0 (16/0). CAUGHT reports unchanged (m19, m20, m24: the Fabric canaries' mains; m30: EBS + the
  common-setup deferred task; the rest none).
- Suite at bf508b32 (pinned, plain --offline, `-Pforbric.requireFixtures=all jar cleanTest test --continue` through heavy.sh,
  `w2c/logs/suite.log`, XML `w2c/suite-xml/`): 5875 tests / 0 skipped / 5 failed — exactly the five names of
  failures-w2b.txt (D58: LiquidBounce x3, SharedFinalSourceActual x2), `w2c/failures-k3c.txt`. +2 tests against 5873: the new
  weave case (the guest case is replaced, not added) and RegistrationStopGenuineCarrierTest; the isolation test is renamed.

## Gates and tests that relied on containment

- No gate stages a NeoForge mod whose RegisterEvent listener throws (grep of run/ for the contained-listener and roll-back
  wording, the canaries, and VERIFY run 1's CAUGHT reports: no `deliverInModOrder`/`dispatchRegisterEvents` site in any
  gate). gate-m9's `check_absent "no RegisterEvent listener failed"` and gate-m7-neo's `"no registry rollback"` stay
  meaningful: the kernel line still starts "a NeoForge mod's RegisterEvent listener failed", NeoForge's still says
  "Rolling back to VANILLA state", and m24 now shows both present on a stop.
- m19, m20, m24 (continue phase) rely on Fabric main containment, m30 on NeoForge @EventBusSubscriber construction and
  common-setup deferred-task containment: neither is D82's path; unchanged (see open items).
- Tests moved: KernelRegistrationIsolationTest (pinned "the dispatch's failure must stop at dispatchRegisterEvents so the
  window goes on" — the containment D82 retired), RegisterEventsSeamWeaveTest's guest case (pinned "the kernel carries on,
  the Fabric step still runs"), FabricFreezePointWindowTest's walk (a stop path now skips TAIL by design).

## For other lanes / the merger

- Files: KernelLifecycle.java (registerNeoForgeContent's catch/finally, dispatchRegisterEvents, onRegisterEvent,
  deliverInModOrder's new parameter, onServerModLoading/onClientModLoading), KernelServerLaunch.java, RegisterEventsSeam.java
  (comments + two log strings), new RegistrationStop.java; tests listed above; ci-unstaged.tsv +1; run/gate-m24-brokenmod.sh;
  new canary/register-stop + run/build-register-stop-canary.sh (output run/canary/forbricregisterstop.jar, gitignored with the
  other canaries).
- HARN changes lib.sh's server_ready/stop_when_ready; m24's new phases launch with no stdin feeder (as its strict phase) and
  rely on await_server's "Failed to start the minecraft server" match, which HARN does not touch.
- ZH: m24's FORBRIC_JVM appends (keeps a zh locale prefix). The stop lines are NeoForge's own English log lines (logs never
  follow the system language).
- VERIFY run 2: m24 is 63 checks now; a client with a throwing NeoForge listener stops at NeoForge's fatal-error dialog,
  which waits for OK (no gate does that unattended).
- RECORD / docs: a NeoForge mod failing in RegisterEvent now stops the game with NeoForge's error and crash report (user
  visible; release notes "Changed").

## Open items (owner)

1. (orchestrator) The rest of postRegisterEvents' tail is still contained per step by the seam
   (`onRegisterEventsTailStep`, `onRegisterEventsModBusEvent`: attribute events, spawn placements, BlockEntityTypeAddBlocksEvent,
   tab sort, game-rule categories, tooltip appenders). Natively a throw there leaves postRegisterEvents (no roll-back) and
   stops the boot the same way ("Registry initialization"). Outside D82's words (listener / inside the try); RegisterEventsSeamWeaveTest's
   `aTailStepThatThrowsCostsOnlyItself` pins today's containment. Needs a ruling.
2. (orchestrator) Other NeoForge phases the kernel contains where native stops (same posture, not D82's scope): mod
   construction and @EventBusSubscriber wiring (m30 relies on it), FMLConstructModEvent and the setup phases incl. deferred
   work (m30), NewRegistryEvent/ModifyRegistriesEvent posting, RegistrationEvents.init's seven steps
   (RegistrationEventSteps says so in its javadoc).
3. (orchestrator) Fabric: a throwing main entrypoint crashes native Fabric (javap above); Forbric contains it and lets the
   compatibility policy decide (stops only under strict, or default ask on a dedicated server). Different code path; unchanged.
4. (orchestrator / kernel) The dedicated server exits 1 after a registration stop only. Natively FML's Server.main exits 1
   whenever Main returns without a server thread (any "Failed to start the minecraft server", EULA refusal, --initSettings);
   Forbric still exits 0 there. Not changed (gates may depend on it; not measured).
5. (kernel, pre-existing) Forbric has no NeoForge early window, so the client's fatal error goes to NeoForge's tinyfd
   fallback; on this Mac tinyfd rejected NeoForge's text ("INVALID MESSAGE WITH QUOTES"). Native client not run (no native
   client image in the cache; preparing one was out of bounds).
