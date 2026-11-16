# Stage 4 C5 — lane THR (NeoForge mod loading on NeoForge's own threads), wave 2d

Branch `s4-c5-thr`, cut from 421c53cc (worktree `.claude/worktrees/s4c5-thr`, ports 46940-46959 from `.stage4/lane-env.sh`).
Notes and logs: `~/Forbric-backups/stage4-notes/c5/thr/w2d/` (paths below are relative to it). Harness: D68 (pinned build, plain
`--offline` from `.stage4/props.txt`, no init script, no private Gradle home); every Gradle run and gate subset through
`.stage/heavy.sh` (gate subsets `--exclusive`, under `.stage/client.sh`). The native A/B (`native-ab/run.sh`) and the probe
servers (`tools/run-server.sh`) call `python3`/`java` directly; whether they were wrapped in `heavy.sh` from outside is not in
the evidence (corrected in "THR review fixes", B-4). No mod jar was downloaded; no Chinese run. Reason for
the lane: D87(c) — VERIFY run 2's C-7 ("NeoForge mod loading" is a `modloading-worker` line natively, a `main` line here).

## Worktree

Bootstrapped with `bootstrap-lane.sh thr 421c53cc…` (LANE_PORT_BASE=46940). Two PROBLEMs: `.stage4/minecraft` was the
orchestrator's relative link `../.stage/minecraft`, dangling in the lane — replaced by the absolute link to
`suspicious-chaum-b28730/.stage/minecraft`, as the brief says; `forbric-kernel/build/sweep100-mac` dangles (its source does not
exist in the main checkout; the open item every lane since wave 2c reports) — the dangling link was removed, nothing reads it.

## Commits (on 421c53cc)

| sha | what |
|---|---|
| 3b6bbb5e | `NeoParallelDispatch` (a phase on NeoForge's `ModWorkManager.parallelExecutor()`, dependency-waited) + `ModConstructionOrder.edges` |
| 74a465f6 | NeoForge `@Mod` construction (and NeoForge's own mod) on the pool; staged test `NeoModLoadingThreadsStagedTest` (construction) |
| 2efa731c | `KernelNeoSetup.firePhase` (construct event, common/sided setup, IMC x2, load complete) on the pool; the staged test's setup half |
| 76a224cb | `@EventBusSubscriber` classes loaded/initialised on the pool with their mod active; `NewRegistryEvent` posted with each bus's mod active |
| c0bf5129 | each mod's throw handed back through its task (three catches that only stored it removed — `EveryCatchIsVisibleTest`); one mod's classes as one chain |
| b34fd11f | the four new staged checks' ci-unstaged skip rows (measured, D47) |
| (this file) | record |

## 1. What native NeoForge 26.2.0.93 does (measured)

Method: four probe mods (`thra`..`thrd`, generated and compiled against the native .93 image by
`tools/gen.py`; `thrc` declares `thra` required + `ordering="AFTER"`), each logging, at every phase:
`Thread.currentThread()` name and daemon flag, `ModLoadingContext.get().getActiveNamespace()` and the identity of
`ModLoadingContext.get()`, the context class loader (and whether it is the mod's own loader), `FMLLoader.getCurrent()`, the
`LoadingModList` and `ModList` sizes, `ModList.getModContainerById(self)`. Phases: constructor begin/end (300 ms sleep), its
`@EventBusSubscriber` static initialiser, `FMLConstructModEvent` (+ `enqueueWork`), `NewRegistryEvent`, the first `RegisterEvent`,
`FMLCommonSetupEvent` (200 ms sleep; + the subscriber's listener; + `enqueueWork`), `FMLDedicatedServerSetupEvent` /
`FMLClientSetupEvent` (+ `enqueueWork`), `InterModEnqueueEvent` (each sends one IMC to `thrb`), `InterModProcessEvent` (count
received), `FMLLoadCompleteEvent` (+ `enqueueWork`), `ServerStartedEvent`. The native image is an APFS clone of the
orchestrator's `build/native-controls/native/neo` (never `prepare`). Runner: `tools/run-server.sh`. Logs: `runs/`.
FML 11.0.16's `ModLoader`, `ModWorkManager`, `ModLoadingContext`, `FMLModContainer`, `LoadingModList`, `ModSorter`,
`CommonModLoader`, `ClientModLoader`, `RegistryManager` were decompiled (Vineflower 1.12.0 from `~/.neoformruntime`) to read the
mechanism behind the numbers.

Native server (`runs/native-probe.log`; with ten real NeoForge mods beside the probes: `runs/native-real.log`):

| phase | thread | ModLoadingContext names |
|---|---|---|
| `@Mod` constructor | `modloading-worker-0` (daemon) | the mod |
| its `@EventBusSubscriber` `<clinit>` | `modloading-worker-0` | the mod |
| `FMLConstructModEvent` | `modloading-worker-0` | the mod |
| construct `enqueueWork` | `modloading-sync-worker` | the mod |
| `NewRegistryEvent`, `RegisterEvent` | `modloading-sync-worker` (the "Registry initialization" init task) | the mod |
| common setup (+ subscriber listener), sided setup, IMC enqueue, IMC process, load complete | `modloading-worker-0` | the mod |
| their `enqueueWork` | `modloading-sync-worker` | the mod |
| `ServerStartedEvent` | `Server thread` | `minecraft` |
| `NeoForgeMod` ("NeoForge mod loading, version 26.2.0.93") | `modloading-worker-0` | — |

- **Parallel**: `thra`, `thrb`, `thrd` constructors all began at the same millisecond and ended 310 ms later; `thrc` began
  after `thra`'s constructor and construct event finished (4 constructors: 638 ms). Common setup the same (three at once,
  `thrc` after `thra`). The worker threads are distinct (different `ModLoadingContext` identities) but all named
  `modloading-worker-0`: `ModWorkManager.newForkJoinWorkerThread` reads `getPoolIndex()` before the pool registers the thread.
- **Mechanism**: `ModLoader.dispatchParallelTask` — for each container in `ModList.getSortedMods()`,
  `CompletableFuture.allOf(<futures of LoadingModList.getDependencies(mod)>).handleAsync(..., ModWorkManager.parallelExecutor())`,
  setting/clearing `ModLoadingContext.get().setActiveContainer` around the task; a dependency's failure skips the dependent
  (natively the boot then stops). `getDependencies` holds only `ordering` BEFORE/AFTER edges (`ModSorter.addDependency`); a bare
  requirement draws none. The pool is a `ForkJoinPool(maxThreads)` (`fml.toml` `maxThreads`, default -1 = processor count),
  workers inherit the creating thread's context class loader. Deferred work: `runInitTask(..., syncExecutor)`
  (`modloading-sync-worker`, single thread). The registry phase, config loading and the network lock are init tasks on
  `modloading-sync-worker` too. `NewRegistryEvent` is posted through `ModLoader.postEventWrapContainerInModOrder`.
- Client: not measured natively (no native client image; `prepare` is a download). By the decompiled code `ClientModLoader.begin`
  and `finish` call the same `CommonModLoader.begin`/`load`, so the client's thread kinds are the server's, with the render
  thread waiting in `waitForFuture` while it ticks the early display.
- What a mod sees otherwise: context class loader = the game's transforming loader (`TransformingClassLoader` natively,
  `ForbricClassLoader` here; "is my own loader" true on both); `FMLLoader.getCurrent()` non-null on both.

## 2. What Forbric did at 421c53cc (measured)

`runs/forbric-probe-base.log`, `runs/forbric-real-base.log`: every constructor, subscriber `<clinit>`, construct event and setup
listener on `main` (on a client: the thread that runs `Minecraft.<init>`), **one mod after another** (4 constructors: 1234 ms,
`thra`, `thrb`, `thrd`, `thrc`), one `ModLoadingContext` object for all of them; subscriber `<clinit>` and `NewRegistryEvent`
saw `minecraft` as the active mod; "NeoForge mod loading" on `main`. Deferred work already ran on `modloading-sync-worker`
(NeoDeferredWork). The dependency order (thrc after thra) was kept.

## 3. What changed (through NeoForge's own `ModWorkManager`)

`NeoParallelDispatch` runs a phase as `ModLoader.dispatchParallelTask` does — the same executor
(`ModWorkManager.parallelExecutor()`, so the same thread name, daemon flag, pool size and context class loader), a mod waiting
(`allOf(...).handleAsync`) for the mods before it, the active container set and cleared on the worker by the caller's body — but
not `dispatchParallelTask` itself: the kernel's `LoadingModList` has no `modDependencies` (`PassiveSeeder` seeds `Map.of()`), so
NeoForge's method would wait for nothing, and its failure path is lane STOP's area (below). The edges are the kernel's
dependency order's (`ModConstructionOrder.edges`): BEFORE/AFTER as natively plus every required mod, which only removes
schedules — each one left is one native NeoForge can produce. Edges through a mod the phase does not run are followed; a mod
never waits for one listed after it (a declared cycle cannot hang the boot). `-Dforbric.modOrder=name` runs the mods one after
another on the pool. No pool on the carrier = the same schedule on the caller's thread.

Applied to: `@Mod` construction (each class an item; one mod's classes in claimed order), NeoForge's own `NeoForgeMod` (alone,
before the others — a possible native order), `KernelNeoSetup.firePhase` (construct event, common setup, client/server setup,
IMC enqueue, IMC process, load complete), each `@EventBusSubscriber` class's load+init+register (on the pool, its owner's
container active; NeoForge's internal subscribers with NeoForge's container). `NewRegistryEvent` now has each bus's mod active.

After (`runs/forbric-probe-v2.log`, `runs/forbric-real-v4.log`, client `client-probe/latest.log`): the thread and the active mod
of every row of the table in §1 are the same on Forbric except `NewRegistryEvent`/`RegisterEvent`'s thread (`main`, natively
`modloading-sync-worker`). The order inside construction is NOT the same (see "THR review fixes", F2/B-1, and §6): a mod's
constructor, its subscribers' `<clinit>` and its construct event are three kernel-wide passes here, one task per mod natively.
The three constructors without an edge between them run together (4 constructors: 620 ms with the probes alone,
`forbric-probe-v2.log`; 610 ms with the ten real mods beside them, `forbric-real-v4.log`; native 638 / 614; `thrc` after
`thra`); "NeoForge mod
loading" is a `modloading-worker-0` line (also in all three native A/B rounds' Forbric logs). Client (gate-m9's launch on a clone
of client-merged-pack + the probes): the same — constructors, subscribers, construct event, common setup, `FMLClientSetupEvent`
(side=client x4), IMC, load complete on `modloading-worker-0` with the mod active; deferred on `modloading-sync-worker`.

What did not change: how a failure is handled. Each mod's throw (constructor, listener) comes back to the caller's thread and
goes through the exact code that handled it before, in the same order — recorded, logged, withdrawn/DEGRADED; a mod whose
dependency failed still runs. Lane STOP owns what a failure means (D82); its code can rethrow from the same places.

## 4. Tests and red controls

| check | green on the tip | red on |
|---|---|---|
| `NeoModLoadingThreadsStagedTest.eachModIsConstructedOnAModloadingWorkerWithItsOwnContainerActive` (real NeoForge carrier) | yes | 421c53cc: "thrtest_lib is not constructed on the caller's thread … was: <Test worker>" (`red-staged-421c53cc.xml`); construction with the edges dropped (`Map.of()`): red at `NeoModLoadingThreadsStagedTest.java:101`, the `libFirst` assertion (`red-staged-noedges.log`, again after c0bf5129: `red-staged-noedges2.log`; those logs hold file:line only, not the message) |
| `NeoModLoadingThreadsStagedTest.aSetupPhaseReachesEachModOnAModloadingWorkerWithItsOwnContainerActive` | yes | 421c53cc: "thrtest_lib's setup listener does not run on the caller's thread … <Test worker>" |
| `KernelEventSubscribersGenuineBaseTest.theSubscriberClassIsInitialisedOnAModloadingWorker` | yes | 2efa731c's production files: red at `KernelEventSubscribersGenuineBaseTest.java:151`, the not-on-the-caller's-thread assertion (`red-commit4.log`, file:line only) |
| `NeoRegistryCreationPhaseTest.eachBusTakesNewRegistryEventWithItsOwnModActiveAsNatively` | yes | same control: red at `NeoRegistryCreationPhaseTest.java:61`, the set-before/after-post assertion (`red-commit4.log`, file:line only). A bytecode-shape check: stays green when no container is ever made active (review F3); the run check is `newRegistryEventReachesEachBusWithItsOwnModActive` below |
| `NeoParallelDispatchTest` (11) | yes | new class (parallel overlap by latch, dependency waits, through a non-dispatched mod, cycle, throws handed back unwrapped, same-id chain, serial without order, inline without pool) |
| `ModConstructionOrderTest.theEdgesAreTheOnesTheOrderIsBuiltFrom` | yes | new (edges = what `of` sorts by; none with `modOrder=name`) |
| `NeoParallelDispatchTest.aModsOwnRejectedExecutionExceptionIsHandedBackAsItsFailure` / `aPoolThatRefusesTheWorkIsThrownToTheCaller` (review F1) | yes | b40c3331's dispatcher: the mod's own REE escaped `run()` (`review-fix/controls-xml/`) |
| `NeoModLoadingThreadsStagedTest.newRegistryEventReachesEachBusWithItsOwnModActive` (review F3) | yes | `containerOf` returning null (M1): "expected: <thrtest_lib> but was: <minecraft>" (`review-fix/controls-xml/`) |
| `NeoModLoadingThreadsStagedTest.aSetupPhaseReaches…` dependency assertion (review F4a) | yes | `dependencyAfter()` replaced by `Map.of()` (M2): red at line 159 (`review-fix/controls-xml/`) |
| `KernelEventSubscribersGenuineBaseTest.theSubscriberClassIsInitialisedWithItsOwnModActive` (review F4b) | yes | `registerAll` passing no container (M3): "expected: <genuineprobe> but was: <minecraft>" (`review-fix/controls-xml/`) |

Existing checks the work had to keep: `KernelRegistrationIsolationTest` and `FabricFreezePointWindowTest` read
`registerNeoForgeContent`'s bytecode (the subscriber call guarded on its own, no explicit throw before the dispatch) and went red
on a first version (a lambda and an explicit rethrow); `KernelEventSubscribersArbitrationTest` / `KernelEventSubscribersTest`
pin `registerAll`'s own body — so the pool hop is inside `wireNeoSubscriber`, and the baseline goes through
`NeoParallelDispatch.runOne`. `KernelEventSubscribersGenuineBaseTest`'s loader got night-config on its parent (a launch has it on
the boot classpath; `FMLConfig`, which `ModWorkManager.parallelExecutor()` reads, needs it). `EveryCatchIsVisibleTest` caught three
catches that only stored a throw (fixed in c0bf5129). No check was weakened.

## 5. Runs

- Boot package: 1041/0/0/0 at c0bf5129 (with `EveryCatchIsVisibleTest`); `test-boot5.log`.
- Real NeoForge mods, server, Forbric before vs after (balm, waystones+shogi, cookingforblockheads, collective, sophisticatedcore
  + backpacks, geckolib, clumps, corpse, trashslot + the probes; `runs/forbric-real-base.log` vs `-v4.log`): WARN/ERROR/CAUGHT/
  Exception lines identical (19 = 19, `diff` empty), the same phase reports (construct/common/server setup/load complete to 17
  NeoForge mods, 14 `@EventBusSubscriber` classes), Done. Native with the same set: Done (`runs/native-real.log`).
- Gates at 76a224cb, `gates-all.sh -j 2` on the 14 named (`gates1/`): all GREEN, PASS/FAIL = m1 19/0, m2 29/0, m2b 38/0,
  m7-neo 28/0, m9 163/0, m10 24/0, m13 30/0, m19 40/0, m20 41/0, m24 67/0, m24c 25/0, m24d 32/0, m30 28/0, m58 208/0 — each
  equal to wave 2c's count (merge-w2c.md); `[kernel] CAUGHT` non-none m19 1, m20 1, m24 1, m30 2, as in w2c.
- After c0bf5129 (failure transport changed): m7-neo, m9, m24, m30 again (`gates2/`): all GREEN, m7-neo 28/0, m9 163/0, m24 67/0, m30 28/0, CAUGHT as before (m24 1, m30 2).
- gate-m9 with the four probes added to its pack (`client-probe/`): GREEN 163/0; the probes' client lines in §3.
- Native NeoForge A/B (`native-controls run/compare --family neo`, the tool's own canary build via VERIFY's `build-neo.py`) at
  76a224cb: MATCHED_PASS 3/3 (`native-ab/at-76a224cb/`); at b34fd11f (code = c0bf5129) one round, MATCHED_PASS (`native-ab/`). The Forbric arm's "NeoForge mod loading" and "constructed @Mod nativecontrolneo" are `modloading-worker-0` lines.
- Full suite at b34fd11f (code = c0bf5129; `suite2.log`, `suite2-summary.txt`): 5909 tests, 0 skipped, 5 failed = the five D58 rows of failures-w2c.txt by name (LiquidBounce x3, SharedFinalSourceActual x2); 0 new. +16 tests over OPT's 5893. (A first full run at 76a224cb, `suite.log`, had one more red, EveryCatchIsVisibleTest — fixed in c0bf5129.)
- ci-unstaged skips measured on a tracked-files-only copy (`unstaged-rows.txt`): the four new staged tests skip on [fixture:staged]; rows added (b34fd11f).

## 6. Differences left, and why

- **Construction is three passes, not one task per mod** (found by review THR wave 2d, F2/B-1; already so at 421c53cc).
  Natively one mod's task is constructor -> its `@EventBusSubscriber` classes -> its `FMLConstructModEvent`, and a mod declared
  AFTER it starts only once that whole task ended. Here every constructor runs first (in parallel, dependency-waited), then each
  subscriber class is loaded one after another (each `runOne` on the pool, the caller waiting), then the construct event goes to
  every mod. So a dependent is constructed before the mod it depends on has run its subscribers' `<clinit>` and its construct
  event (`runs/native-probe.log`: thra subscriber 4789605, construct event 4789606, thrc begins 4789608; `forbric-probe-v2.log`:
  thrc begins 5316854, thra subscriber 5317163, construct event 5317173; the client the same, `client-probe/latest.log`), and
  subscriber `<clinit>`s are serial where natively each runs on its own mod's worker. A mod whose constructor relies on state its
  dependency sets up in a subscriber `<clinit>` or its construct event works natively and not here. Aligning it means doing a
  mod's subscribers and construct event inside its construction task; `KernelRegistrationIsolationTest`,
  `FabricFreezePointWindowTest`, `KernelEventSubscribersArbitrationTest`/`KernelEventSubscribersTest` pin
  `registerNeoForgeContent`'s and `registerAll`'s current shape, classless NeoForge mods need a task of their own, and lane STOP
  conflicts with exactly these statements (B-5). Not done in this lane -> orchestrator / owner.
- **The registry phase's thread.** Natively `NewRegistryEvent`, `RegisterEvent` and `freezeData` run as the init task
  "Registry initialization" on `modloading-sync-worker`; here on the caller's thread. The kernel's window
  (`registerNeoForgeContent` → `GameData.postRegisterEvents` → RegisterEventsSeam) also runs the Fabric mains, which native Fabric
  runs on the game's own thread. Moving it means splitting one call across two threads; not done. -> owner, if it matters.
- **What `ModList` / `LoadingModList` hold during construction** (not a thread matter): natively 6 (minecraft, neoforge + the
  4 probes) from the first constructor on; here on the server 4, and `ModList` 5 from common setup on (`forbric-probe-v2.log`);
  on the client (gate-m9's pack + the probes, `client-probe/thr-lines.txt`) `LoadingModList` 178 and `ModList` 78 during
  construction, 79 from setup on. -> orchestrator, to the lane that owns ModList publication.
- **The client was measured on Forbric only**: no native client image (`prepare` downloads); the client claim rests on the
  decompiled `ClientModLoader` taking the same `CommonModLoader` paths. -> VERIFY (a native client run, if an image is staged)
  or the owner (accept the decompiled-code argument).
- **A mod whose dependency failed** still runs here; natively it is skipped and the boot stops. Unchanged on purpose (lane STOP).
- **`fml.toml`'s `maxThreads`** is read only where `FMLConfig` was loaded (the client's seeded config); a server without
  `fml.toml` uses the default (processor count), as natively without the file.
- **Risk that comes with matching native**: mods and the kernel code they call now run concurrently during construction and
  setup, as on NeoForge. Mods are written for that; any kernel bridge a mod calls from a constructor or setup listener that keeps
  unsynchronised static state can now race. None showed in the runs above; a race would be intermittent.

## 7. For other lanes

- Lane STOP: a failure is handled after the phase, on the caller's thread, in the walk order, at the same statements as before
  (`KernelModLoader` construction loop, `KernelNeoSetup.firePhase`'s loop). To stop like native, rethrow there; to skip a mod
  whose dependency failed, as natively, `NeoParallelDispatch` would need a skip mode (the futures already carry the failure).
- Logs: constructor/setup/subscriber lines are now `modloading-worker-0` lines and interleave between mods; a gate that greps
  their text is unaffected (all 14 counts equal), one that relied on their relative order between two mods would not be.

## THR review fixes

Reviews `c5/review-thr-w2d-A.md` (F1-F4) and `c5/review-thr-w2d-B.md` (B-1..B-6) of 421c53cc..b40c3331. Evidence:
`~/Forbric-backups/stage4-notes/c5/thr/w2d/review-fix/`.

| finding | outcome |
|---|---|
| F1 a mod's own `RejectedExecutionException` ended the whole phase | fixed 051b0c53: each task records that its body began; only a refusal before any body began is thrown to the caller, a mod's own throw of that type comes back at its index. `NeoParallelDispatchTest.aModsOwnRejectedExecutionExceptionIsHandedBackAsItsFailure` (red on b40c3331's dispatcher: the REE escaped `run()`), `aPoolThatRefusesTheWorkIsThrownToTheCaller` (a shut-down pool still reaches the caller, no body ran) |
| F2 / B-1 "same as native except the registry thread" was not true: construction is three passes | confirmed from the lane's logs (numbers in §6); not a regression (421c53cc the same). Recorded in §3 and §6 with what aligning it touches; not changed here -> orchestrator / owner |
| F3 the NewRegistryEvent active-mod test only read bytecode | fixed 8fa9510b: `NeoModLoadingThreadsStagedTest.newRegistryEventReachesEachBusWithItsOwnModActive` posts it through `KernelNeoRegistries` on the real carrier and reads `ModLoadingContext` in each listener, and that nothing is left active after. Red with `containerOf` returning null (M1): "thrtest_lib's NewRegistryEvent listener runs with its own mod active … expected: <thrtest_lib> but was: <minecraft>"; the bytecode test stays green under M1, as the review said, and is kept (its native premise check still holds). The staged loader has no game jar, so `fill()` fails after delivery: `@ExpectCaught("NewRegistryEvent.fill failed")` |
| F4a setup's dependency wait unchecked | fixed 8fa9510b: the staged setup test publishes `thrtest_dep` requires `thrtest_lib` and asserts the dependent's listener starts only after the required mod's (100 ms) listener finished. Red with `dependencyAfter()` replaced by `Map.of()` (M2): "a mod takes the phase only after the mod it requires has finished taking it … expected: <true> but was: <false>" |
| F4b subscriber `<clinit>` active mod unchecked | fixed 8fa9510b: `KernelEventSubscribersGenuineBaseTest.theSubscriberClassIsInitialisedWithItsOwnModActive` publishes a genuine `FMLModContainer` (the kernel's `KernelContainers`, owned by the loader as in a launch) for the subscriber's mod and reads the active namespace in its `<clinit>`. Red with `registerAll` passing no container (M3): "expected: <genuineprobe> but was: <minecraft>" |
| B-2 red-control texts not in the cited logs | confirmed: `red-commit4.log` / `red-staged-noedges*.log` hold only file:line. Read against b40c3331's tests: `NeoModLoadingThreadsStagedTest.java:101` = the `libFirst` assertion, `NeoRegistryCreationPhaseTest.java:61` = the set-before/after-post assertion, `KernelEventSubscribersGenuineBaseTest.java:151` = the not-on-the-caller's-thread assertion. The new controls' XML is kept (`review-fix/controls-xml/`) |
| B-3 owners and client ModList numbers | §6 now names VERIFY / owner for the native client and gives the client's 178 / 78 -> 79 |
| B-4 A/B ports and heavy.sh | the A/B used `native-controls.py`'s own ports 25651-25663 (outside 46940-46959; free before and after, `native-ab/ports-*.txt`); the opening sentence corrected (no `heavy.sh` call inside `native-ab/run.sh` or `tools/run-server.sh`) |
| B-5 STOP merge conflicts | a merge note, not a defect: `KernelModLoader`, `KernelNeoRegistries`, `KernelNeoSetup` conflict with s4-c5-stop; STOP's stops belong at the caller-thread statements §7 names, then `NeoModLoadingThreadsStagedTest`, `NeoParallelDispatchTest`, m24, m24d, m30 |
| B-6 610 ms source; blank line; scratch files | 610 ms is `forbric-real-v4.log` (probes + 10 mods), 620 ms the probes alone (§3 corrected); blank line removed 95811f9f; the lane's loose scratch files deleted; folders left, since no folder is deleted in this run: `thrprobe/` (152K), `fml2/` (84K; `fml/` is gone) and the fix run's own tracked-files copy `unstaged-thr-rf/` (77M) in the shared scratchpad |

Controls: all four mutations applied together on 8fa9510b (`review-fix/controls.diff`): exactly the four new checks red with the
messages above, the other 17 of the five classes green (`controls.log`, `controls-xml/`); restored with `git checkout`.

Also: 12aadc81 adds the two new staged tests' ci-unstaged rows (measured on the tracked-files-only copy: both skip on
[fixture:staged] with their siblings' reasons, `review-fix/unstaged-rows.txt`, 19 tests / 6 skipped / 0 failed); §4 now cites
red controls by file:line where the logs hold no message (B-2).

Runs after the fixes:
- Touched tests on 8fa9510b (`review-fix/tests2.log`): BUILD SUCCESSFUL; with M1+M2+M3 and the old dispatcher: 21 tests, 4 red,
  exactly the four new checks (`controls.log`).
- Gates whose reach changed (051b0c53 is the only production change): m7-neo, m24, m30 at 95811f9f, `gates-all.sh -j 2`
  (`review-fix/gates/`): all GREEN (exit=0, not cached), PASS/FAIL m7-neo 28/0, m24 67/0, m30 28/0 = w2c; CAUGHT none / 1 / 2 =
  w2c. The test-only and record commits change no gate's input.
- Full suite at 12aadc81 (`review-fix/suite3.log`, `suite3-summary.txt`): 5913 tests, 0 skipped, 5 failed = the five D58 rows
  of failures-w2c.txt by name; 0 new. +4 over 5909 (the four new checks).

Open after the fixes: F2/B-1 (construction as three passes) -> orchestrator / owner; the registry phase's thread -> owner;
ModList contents during construction -> orchestrator; dependents of a failed mod -> lane STOP; native client unmeasured ->
VERIFY / owner; the STOP merge conflicts (B-5) -> merger.
