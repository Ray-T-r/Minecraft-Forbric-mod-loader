# Stage 4 C3, lane "c3b-cni-hedge": how CommonNetworkInteropInjector's connection guard and register sharing were checked

Branch `s4-c3`. Lane commits 0eb8de2a (the anchor census names each idle hedge) and 7b07d377 (deletes
`guardOtherConnectionInitialisation`) on a3791619; review fixes ccf9851b, 3664016c, b6c54b6c and the commit that adds
this file. The orchestrator assigned the item after the critic pass (critic item "CNI `guardOtherConnectionInitialisation`:
three checks, or reword it as a carrier hedge"); it was not in the C3 work list (`c3-repairs.md`). This file keeps the
measured outcomes and the instruments, which otherwise lived only in a session scratchpad.

Names: `$GS` genuine stage (`run/neoforge-patched/patched-mc-neoforge-26.2.jar` + `run/neoforge-runtime/neoforge-runtime.jar`
(NeoForge 26.2.0.93) + `run/neoforge-runtime/forbricneolive.jar`, G below), `$MS` merged stage
(`run/merged-base/patched-mc-merged-26.2.jar`, M below), `$WF` the `s4-c3` worktree's `forbric-kernel`. Heavy commands
went through `.stage/heavy.sh`; `MC_DIR` was never set; ports 46290-46309.

## Results

| candidate | verdict | check 1: symptom on G with the repair off | check 2: whole jar (G + carrier) | check 3: callers on G | commit |
|---|---|---|---|---|---|
| CNI `guardOtherConnectionInitialisation` (HEDGE claim, from 959dfcb5, carrier 26.2.0.38-beta) | DELETE | Defect criterion: NeoForge's other-connection setup (`ClientNetworkRegistry.configureOtherConnection`: `loadDefaultServerConfigs`, filter injection, register send) running more than once per connection. A G client (fabric-api + forbricneolive) joined a pure vanilla 26.2 dedicated server under a JFR method trace: `initializeOtherConnection` 6 entries (three listener sites plus event-loop hops), `runConnectionInitialization` 4, `configureOtherConnection` 1, `ConfigTracker.loadDefaultServerConfigs` 1; 0 "Overwriting non-null config" lines; census 86 of 108 declared, 0 hedges. Merged red: impossible, accepted under D37: M has no `initializedConnection` either (0 of 10973 classes), so the splice returned before editing on M too since 9aabb567 (carrier 26.2.0.88) | G (10964 classes), carrier (2433), forbricneolive: 0 classes name `initializedConnection`. `configureOtherConnection` is declared and called in `ClientNetworkRegistry` only, from `lambda$initializeOtherConnection$1`, the runnable handed to `runConnectionInitialization`; that method takes a per-connection lock, returns if `isConnectionInitialized`, then sets `CONNECTION_INITIALIZED`. Listener call sites: `handleEnabledFeatures` and `handleConfigurationFinished` (`initializeOtherConnection`), `handleCustomPayload` x2 (`initializeNeoForgeConnection`, `initializeOtherConnection`). No MOVE | vanilla-server join above (every NeoForge mod with a default server config; NeoForge's own `NeoForgeServerConfig` is one). Kernel server + kernel client, both fabric-api + forbricneolive: `initializeNeoForgeConnection` 1, `runConnectionInitialization` 1, `initializeOtherConnection` 1 (returns at its `isOther` test), `configureOtherConnection` 0. gate-m14 (Forbric client on a pure Fabric server, the `handleCustomPayload` register path) at 3664016c, after the deletion: PASS/FAIL lines identical (digits stripped) to the C3 verify run at a3791619, including `PASS NeoForge loaded its default server configs once (absent)`; its 4 FAILs are the known vacuous `Forge-wrapped registries` / remap-listener rows (D34, C5); client census 86 of 108, 0 hedges | 7b07d377 |
| CNI `shareMinecraftRegisterWithSuper` (unclaimed) | KEEP (javadoc; new staged pin) | Not deleted, so not measured off. Idle on both bases: `handleCustomPayload` on G and on M is `isConnectionInitialized` / `instanceof MinecraftRegisterPayload` / `aload_0 aload_1 invokespecial ClientCommonPacketListenerImpl.handleCustomPayload` @22 / `invokestatic sendInitialListeningChannels` @26 / `return` @29, so the repair returns 0; in game "already hands minecraft:register to super ahead of NeoForge's reply ... as shipped" | n/a (no deletion) | gate-m14 client log at 3664016c shows the idle line | b6c54b6c |
| anchor census (TransformChain) | EDIT | n/a: tool change. `[Forbric/Anchor]   hedge <id> on <class> found nothing to do; it is kept for: <cost>` once per idle hedge. A G singleplayer client before 7b07d377 named exactly one: `forbric-common-network-interop#guardOtherConnectionInitialisation` on `ClientConfigurationPacketListenerImpl` (109 declared, 1 hedge) | the wording matches none of gate-m1:77-79 / gate-m9:159-161's patterns | `TransformChainAnchorAccountingTest.theCensusNamesEachHedgeThatFoundNothing` red on the old TransformChain (1 of 9 failed) | 0eb8de2a |

Why `shareMinecraftRegisterWithSuper` stays while the guard went: the register repair adapts Fabric to NeoForge (Fabric's
addon waits at the HEAD of the superclass's `handleCustomPayload`, and a NeoForge listener that answers the register and
returns before super starves it in any Forbric client, not only on a merged base); the guard re-fixed a NeoForge bug
NeoForge has fixed and touched nothing Fabric. Both are idle on the shipped carrier.

## The replacement pins and their red checks

`CommonNetworkInteropInjectorTest.neoForgeInitialisesEachClientConnectionOnceItself` (7b07d377, tightened in ccf9851b)
reads, in every `initialize*` method of `ClientNetworkRegistry`, the `invokedynamic` immediately before each
`runConnectionInitialization` call and takes its implementation handle; every listener initialiser must hand the guard
such a runnable, `runConnectionInitialization` must consult `isConnectionInitialized`, and every caller of
`configureOtherConnection` and `configureNeoForgeConnection` must be one of those runnables.
`theBasesConfigurationListenerHandsTheRegisterToSuperBeforeNeoForgesReply` (b6c54b6c) asserts
`shareMinecraftRegisterWithSuper` returns 0 on the staged listener and leaves it unchanged.

Each mutation is a staged root of symlinks into `$GS` with one doctored jar, run as
`-Pforbric.stagedRoot=<root> -Pforbric.requireFixtures=staged cleanTest test --tests '*.CommonNetworkInteropInjectorTest'`:

| staged root | doctored | result |
|---|---|---|
| real `$GS/run` | none | 4 tests, 0 skipped, 0 failed (`CommonNetworkInteropInjectorExecutionTest` 4/0 too) |
| Unguard (lane) | carrier: `initializeOtherConnection` calls `configureOtherConnection` instead of `runConnectionInitialization` | red: "handleEnabledFeatures -> initializeOtherConnection: NeoForge's initializeOtherConnection no longer goes through runConnectionInitialization" |
| A (review A) | carrier: `runConnectionInitialization` never consults `isConnectionInitialized` | red: "runConnectionInitialization no longer consults isConnectionInitialized" |
| B (review A) | carrier: the event-loop hop `lambda$initializeOtherConnection$0` calls `configureOtherConnection` | red after ccf9851b: "configureOtherConnection is reached from lambda$initializeOtherConnection$0, which is not a runnable an initialiser hands runConnectionInitialization [lambda$initializeNeoForgeConnection$0, lambda$initializeOtherConnection$1]"; green before it (the review's finding) |
| C | carrier: `initializeNeoForgeConnection` calls `configureNeoForgeConnection` itself first | red: "configureNeoForgeConnection is reached from initializeNeoForgeConnection" |
| D | base: the super call ahead of `sendInitialListeningChannels` removed (the .38 shape) | red: register pin, "expected: <0> but was: <1>" |
| empty root | none | all 4 skip; the four `ci-unstaged.tsv` rows for the class match (3664016c replaced the deleted test's stale row; b6c54b6c added the register pin's) |

## Suite

Lane: 5757 tests, 65 failed, names identical to the a3791619 baseline (`failures-c3.txt`). After the review fixes: see
the "c3b-cni-hedge review fixes" section of the C3 handoff for the recount.

## Owner item (open)

7b07d377 deletes a NeoForge carrier repair, not a merge repair, on the ground that it is dead on both bases and does
none of the things code stays for; the plan's C3 mandate names merge-only code. Either the owner records a decision
that carrier repairs dead on the shipped carrier may go under C3 with the three checks and a strict carrier pin, or
7b07d377 is reverted (it touches three files and reverts cleanly) and the claim comes back with carrier wording. The
same decision settles `shareMinecraftRegisterWithSuper`, kept here until then.

## Commands

Join under a method trace (vanilla join on port 46290, kernel join on 46291; counts with JDK 25
`jfr print --events jdk.MethodTrace client.jfr`):

```bash
TRACE="net.neoforged.neoforge.client.network.registration.ClientNetworkRegistry::configureOtherConnection;\
net.neoforged.neoforge.client.network.registration.ClientNetworkRegistry::initializeOtherConnection;\
net.neoforged.neoforge.client.network.registration.ClientNetworkRegistry::initializeNeoForgeConnection;\
net.neoforged.neoforge.client.network.registration.ClientNetworkRegistry::runConnectionInitialization;\
net.neoforged.fml.config.ConfigTracker::loadDefaultServerConfigs"
# server: run/launch-vanilla-server.sh (VANILLA_JAR=$GS/minecraft/versions/26.2/26.2.jar) or run/launch-kernel-server.sh
FORBRIC_OLD="$GS" RUNDIR="$CLI" \
FORBRIC_JVM="-XX:StartFlightRecording:method-trace=$TRACE,filename=$OUT/client.jfr -Dforbric.clientSmoke=true \
  -Dforbric.clientSmokeWorld=127.0.0.1:$PORT -Dforbric.clientSmokeReadyTicks=60 -Dforbric.clientSmokeDisconnectTicks=140" \
  run/launch-kernel-client.sh --quickPlayPath "$CLI/quickPlay/log.json" --quickPlayMultiplayer "127.0.0.1:$PORT"
```

gate-m14 on G: `FORBRIC_OLD=$GS FORBRIC_NEOLIVE=$GS/run/neoforge-runtime/forbricneolive.jar GATE_PORT=46291 M14_PORT=46290 .stage/heavy.sh bash run/gate-m14-fabric-server.sh`.

Mutations (ASM 9.10.1 tree API; each rewrites one class and is zipped back into a copy of the jar):

```java
// Unguard: in initializeOtherConnection, replace the call to runConnectionInitialization(conn, runnable)
//          with SWAP; POP; INVOKESTATIC configureOtherConnection(ClientConfigurationPacketListener)V
// A: in runConnectionInitialization, replace INVOKESTATIC isConnectionInitialized with POP; ICONST_0
// B: in lambda$initializeOtherConnection$0, rename the call to initializeOtherConnection to configureOtherConnection
// C: prepend ALOAD 0; ALOAD 1; INVOKESTATIC configureNeoForgeConnection(...)V to initializeNeoForgeConnection
// D: in ClientConfigurationPacketListenerImpl.handleCustomPayload(ClientboundCustomPayloadPacket), remove the
//    ALOAD 0; ALOAD 1; INVOKESPECIAL handleCustomPayload that two instructions later reaches sendInitialListeningChannels
```

Whole-jar scan (check 2): `javap -p -c -constants` of every class of the G base, the carrier and forbricneolive, grepped
with `grep -a` for `initializedConnection`, `configureOtherConnection`, `configureNeoForgeConnection`,
`runConnectionInitialization`, `isConnectionInitialized`, `CONNECTION_INITIALIZED`, `loadDefaultServerConfigs` and the
`initialize*Connection` names; hits only in the two configuration listeners, `ClientNetworkRegistry`, `NetworkRegistry`
(server side) and `ConfigTracker`.
