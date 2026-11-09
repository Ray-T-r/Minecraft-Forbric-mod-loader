# Stage 4 C5 — lane G-B2 (transfer, world and soak gates, compat drivers), wave 2

Branch `s4-c5-g-b2`, cut from the stage tip f0cb66b0 (S1, T-A, T-B, G-A, G-C merged). Notes and logs:
`~/Forbric-backups/stage4-notes/c5/g-b2/w2/` (gate outputs in `gates/`, one `<label>.out` per run and `summary.txt`;
evidence copies in `evidence/`). Harness: D68, the pinned build with plain `--offline` from `.stage4/props.txt`, every
Gradle and game run through `.stage/heavy.sh` (client runs under `.stage/client.sh` first), ports 46760-46779 from
`.stage4/lane-env.sh`. Nothing was downloaded.

## First act (D66 suite at f0cb66b0)

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all cleanTest test --continue` in the
fresh lane worktree: **5772 tests, 0 skipped, 7 failed** (`suite-first.log` only: `suite-first-xml/` holds the 2-test rerun after `jar`,
which overwrote build/test-results, not this run's XML; corrected after review B-4). Five are exactly
`failures-wave1.txt` (LB x3, SharedFinalSourceActualTest x2, D58). The two others are
`KernelBundledMixinExtrasStagedTest.[1] true` / `[2] false`: "[fixture:third-party] stage the popular compatibility
pack and the boot jar ... build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar". The popular pack is there (through the
`build/compat-inputs` link); the boot jar is not, because a fresh worktree has never run `jar` and `cleanTest test` does
not build it (the stage worktree had one from earlier gate runs). After `jar` the class passes 2/2
(`suite-first-bundledmixinextras-after-jar.log`). A harness ordering difference, not a code one; the end suite runs
after the gates, so build/libs exists there.

## Items

| item | outcome | evidence |
|---|---|---|
| plumbing re-key `merged/forge` -> `base/neo` | DONE: m33, m35, m36, m37, m38, m39, m40 read the build record's `base`/`neo` (m39 the stage defaults), export them as the launcher's `BASE`/`NEO_RT` and attest them as `neo-patched`/`neo-runtime` (existing roles, no `evidence.py` edit) | d20d74d8, 4cec54cd, 38f34512, c702365b, c2fc3c6c; every gate below GREEN |
| m33 wording + vacuous check | DONE: the refused-face check now names Fabric's generic Container view (what `BlockTransferBridge.neoFallback` refuses for a NeoForge owner); TransferWorldProbe comments/failure text/PASS line reworded; Forge InvWrapper absence check deleted (D34 below) | d20d74d8; m33 22/0 |
| m35: 11->7, delete portal-off/spawner-off/item-off, withhold phase (D48) | DONE: probe phase `withhold` (listeners count but withhold their answer); measured red set exactly item-result, portal-neo-veto, spawner-data, spawner-neo-cancel-finalize, spawner-neo-veto-spawn, spawner-nonempty-input; portal-allow passes = D48's set, now measured. After review: each red's reason pinned, `spawn-position-off` native A/B phase added; spawner-nonempty-input's red is probe-side only (see review fixes) | 38f34512, cc10eb35; m35 3/0 |
| m36/m37/m38 | DONE: re-keyed; `[kernel] PASS/FAIL M3x` lines; m37 off/guard-off and m38 off re-measured (below) | c702365b |
| m39 (D32, critic #195, D65 BASE rename) | DONE: Forge scenarios gone; the suite (57 of 57) stays required; watchdog proof on `WatchdogCanary` + fabric-api on a strict non-Forge server; results var `BASE` -> `RESULTS` | c2fc3c6c; m39 6/0 |
| m40 expectations + H3 | DONE: counts per the probe (2 families/2 cells/2 consumers/4 routes/40,000 E; noreborn 1/1/1/0/20,000 with SKIP lines); old Forge-consumer counts deleted, not reworded; `ForgeEnergyAdapters` class check re-pointed; `$RESULTS` cleared at start | 4cec54cd; m40 36/0; H3 control below |
| m34: fail instead of launching without `--nativeReference` | DONE: `vanilla_reference` raises `SystemExit`; flag always passed; test_soak +1 case (28/28), red with the old fallback put back; gate prints a countable verdict line | d1cfbcb8, 6cb3cb0f; failure path measured; short control run measured, REVIEW_REQUIRED (below) |
| m31 + `M31_UNFIXED=1` | DONE (no edit): GREEN 13/0; unfixed RED 12/1 on exactly "every chunk carries vanilla's biomes (want 0 got 11)", the 11 chunks the gate's TEETH note recorded | gates/m31.out, m31-unfixed.out |
| m32 | no edit needed: GREEN 10/0 (M32_PORT from the lane block) | gates/m32.out |
| D61 wrappers m60/m61/m62 | DONE: GATE_PORT, `# GATE-PARALLEL:` line, inputs from the `build/` fixture links with env overrides (m60's fabric-api from `.dev/api`, else the client-merged-pack copy, since cdae9449; review B-7), `port_was_free` on every server, exact per-phase lines; `test_gate_ports` 11/11 OK | e5f2f24e; m60 10/0, m61 6/0, m62 6/0 |
| menu-codec-gate.py | DONE: baseline phase and `MC_DIR` removed; stays manual | a4c04f49; run: fixed 3/3, "Menu codec gate passed" |
| fluid-parity-gate.py | DONE: GATE_PORT, `--unfixed` and the native-Forge column dropped, `canary/fluid-interactions/forge` deleted; stays manual | f5e5ad33 (the canary's two files were deleted in d20d74d8, the m33 commit, not f5e5ad33 as its message says; between the two, `--mods` would compile a canary that is gone; review B-3); plain mode PASS; `--mods` PASS against native NeoForge 26.2.0.93 (review fixes) |
| carpet-gate.py:66 | already done in C1 (aaacdeef took the Forge interop jar off its classpath); this lane adds `--port` for m62 and rewords the `--staged-root` help | 578083d3 |
| c2me `build.py:20` | already done (610fb9ab compiles against `BASE`/`NEO_RT`, not the merged base); nothing left | `git show cf0498eb:` vs tip |
| fluid-head-gate.py `:47` help | DONE: no longer names the retired `nativeEntryRelay` | 578083d3 |
| PROTOCOL.md rows | DONE: hook-worklist sentence (old :50), fluid-head `--off` (old :105 area), m39/m40 rows (old :524-536), soak, fluid-parity, menu-codec, carpet `--port`, m60-m62. PROFILE_REWRITES (old :196) left for C6 | 02e4ae0a |
| UPG's held `protocol-world-snapshot.patch` | LEFT: `world-snapshot.py` does not exist on this branch, so the paragraph would document a missing tool; UPG's own branch carries its three-line mention (fa1503dc) and merges after G-B2 | — |
| TransferWorldProbe comments + PASS line | DONE (with m33) | d20d74d8 |

## Gates on the genuine stage (`[kernel] PASS` / `[kernel] FAIL` lines)

| gate | PASS / FAIL | note |
|---|---|---|
| m31 | 13 / 0 | biomes 0, structures 0, spawner mobs 0 differ; heightmaps 8, blocks 232, block entities 18 are evidence only (vanilla does not reproduce itself) |
| m31 `M31_UNFIXED=1` | 12 / 1 | the red control: the one FAIL is "every chunk carries vanilla's biomes (want 0 got 11)" |
| m32 | 10 / 0 | |
| m33 | 22 / 0 | bridge-off negative red as required |
| m34 | 0 / 1 (control run, REVIEW_REQUIRED) and 0 / 1 (no-sidecar failure path, by design) | the 2-hour release soak is VERIFY's |
| m35 | 2 / 0 (3 / 0 after the review fixes) | positive 7/7; withhold red on exactly its six (after review: also spawn-position-off 7/7, reasons pinned) |
| m36 | 6 / 0 | required-strict, required-continue, optional, declined, widened, widened-off |
| m37 | 3 / 0 | positive, off, guard-off |
| m38 | 2 / 0 | positive, off |
| m39 | 6 / 0 | transfer suite 57 ran of 57 |
| m40 | 36 / 0 | (the final "GREEN" banner text was changed from "eight" to "four" routes after this run; nothing asserted changed) |
| m60 | 10 / 0 | servers on 46760-46763 |
| m61 | 6 / 0 | servers on 46760-46761 |
| m62 | 6 / 0 | servers on 46760-46762 |

**m34** (client lock + both heavy slots), `--control --seconds 120 --sessions 2 --dwell-ticks 20 --settle-seconds 10`:
- on `client-merged-pack` (97 jars): the soak watchdog stopped it, "client thread did not return to the soak controller
  for 302 seconds"; the thread dump is JourneyMap 6.0.1's own lock cycle (Render thread in
  `MapRenderer.loadInMemoryRegions` blocked on a `SynchronizedMap`, Worker-Main-8 in `MapRenderer.sortRegions` ->
  `TreeMap.putAll` blocked on the other one), the self-deadlock recorded on 2026-09-24 (merged base, M34 RED twice from
  it; the 2-hour release soak then ran without JourneyMap). Evidence `c5/g-b2/w2/m34-control-journeymap/`.
- on an APFS clone of the same pack without `journeymap-neoforge-26.2-6.0.1.jar` (96 jars): the client ran with
  `--nativeReference` (its log: "native references: FABRIC from the vanilla-reference sidecar vanilla-reference.jar,
  NEOFORGE from the base's own bytes"), activity verified (127.6 s active, 2701 ticks, 5 sessions, client exit 0), 0
  caught failure sites, and the verdict is **REVIEW_REQUIRED**: after the Unlit Campfire `CAMPFIRES` cut (249 entries
  removed) servers 2-4 were gone but servers 1 and 5 were still reachable. soak-run.py exits 1 on REVIEW_REQUIRED, so the
  gate prints `[kernel] FAIL M34 soak-run stopped with exit 1`. This is the retention review the soak is built to raise
  (a heap-dump review decides it on a release run); it is not a CONTROL_PASS and it is not claimed as one. Evidence
  `c5/g-b2/w2/m34-control-nojm/`.
- no-sidecar failure path (final gate): `[M34] FATAL: no vanilla reference ...` then `[kernel] FAIL M34 soak-run
  stopped with exit 1`, no client started.

No SKIP line. FAILs left: the m31 control run (by design), m34's no-sidecar path (by design) and m34's control run
(REVIEW_REQUIRED, see Open). Compat tools (not gates): menu-codec fixed 3/3; fluid-parity
plain mode PASS (vanilla vs zero-mod kernel, every score and block equal).

## Red controls

- **m31** `M31_UNFIXED=1` (`-Dforbric.randomSourcePrecision=off`): RED, 11 biome chunks differ, exactly the recorded TEETH.
- **m33** negative (bridge off): RED at a foreign public lookup, never claimed success, bridge never connected (inside the 22/0).
- **m35** withhold: RED on exactly the six D48 cases, portal-allow green; every case saw its NeoForge event once.
- **m37** off: 16 of 19 fail (all but glide-tick-native, bed-custom-native, bed-custom-handled); guard-off: exactly
  glide-custom, glide-deny, glide-tick. Both equal the gate's written sets (re-measured on the genuine base).
- **m38** off: all 16 cases fail, fabricLanguage false, confirmedRequired > 0 (re-measured).
- **m40** noreborn (no Reborn class loaded; the class log shows EnergyWorldProbe and TransferPrecedence loaded,
  LiveTransferEndpoints not, measured) and negative (bridge off) inside the 36/0.
- **m40 H3**: after the green run, re-run with `M40_TIMEOUT=1` (every phase killed after 1 s, so nothing new is
  written). With the start-of-gate clear removed (mutation): 8 PASS / 32 FAIL, among the PASSes "no Team Reborn Energy
  class and no Reborn half of the bridge was loaded (absent)" read off the stale `noreborn-classes.log`. With the clear
  (the committed gate): 3 PASS / 37 FAIL, that check and both class-log presence checks FAIL with "no log to read".
  (The 3 PASSes left are absence checks on this run's own partly written prepare/reload logs, not stale files.)
- **m60** item-damage-off / shears-mobs-off / superseded-off: exactly their cases (and the watched mixins suspected
  again, asserted by the driver). **m61** head-off 3/6, no wrap. **m62** baseline 21/34, neighbour-off 32/34.
- **Wrappers themselves**: m60 with `M60_BCLIB` missing and m61 with `M61_CREATE` missing: 1 FAIL, exit 1; m62 handed
  fabric-api as the Carpet jar: the driver stops (exit 2), 1 PASS / 5 FAIL, exit 1.
- **soak-run**: the new `test_no_vanilla_reference_stops_the_soak_instead_of_launching_without_one` FAILs with the old
  "launching without" fallback put back, passes with the fix (28/28). Gate-level failure path: `gate-m34-soak.sh
  --control ... --base <base copy with one extra entry> --minecraft <dir without 26.2.jar>` stops with "[M34] FATAL: no
  vanilla reference for ... (exit 3): [vanilla-reference] vanilla jar not found" before any client starts.

## Checks deleted (D34)

| check | why vacuous on the genuine base | what covers the path now |
|---|---|---|
| m33 `check_absent` FORGE_HANDLER_NOT_ROLLBACK_SAFE on `net.minecraftforge.items.wrapper.InvWrapper` | no `net.minecraftforge` class on the base or carrier, nothing composes an InvWrapper; could only pass | the probe's refused-face query on the NeoForge cabinet (NeoForge must get nothing: Fabric's generic Container view refused), inside m33's positive PASS |
| m35 portal-off / spawner-off / item-off phases and the `spawner-finalize-input` finding assert | their switches (`portalSpawn`, `spawnerFinalize`, `unifiedEvents`) are retired with deleted code (D22/D29); only warn | m35 withhold premise control |
| m35 javap read of the merged base's portal-hook order, the MinecraftForge carrier requirement | no merged base, no Forge portal hook (`BaseFireBlock.onPlace` asks only `EventHooks.onTrySpawnPortal`) | portal cases judge NeoForge's event asked exactly once |
| m39 "fourteen storage scenarios" (`TransferCanary 15/15`) | ForgeTransferCanary/ForgeTransferGameScenarios deleted; launcher refuses MERGED/FORGE_RT (D32) | transfer engine suite (57 required tests) + m33/m40 world runs; watchdog proof on its own canary |
| m40 Forge-consumer route counts, "Forge LazyOptional", `ForgeEnergyAdapters` class-load check (old :220/:222) | the probe has no Forge consumer since aa9aa9a9; ForgeEnergyAdapters deleted | per-consumer route counts for the Fabric and NeoForge consumers; class-log presence of the probe and TransferPrecedence |
| menu-codec baseline (`wrapperEntryEvents=off`) | switch retired; the baseline passed all three checks | positive strict run; nothing on NeoForge's base is switched off there |
| fluid-parity `--unfixed`, native-Forge column, `canary/fluid-interactions/forge` | `fluidInteractions` retired (D15); Forbric refuses MinecraftForge-only mods | plain vanilla-vs-kernel mode; NeoForge-only `--mods` |

## Not done / open

- ~~fluid-parity `--mods` not measured here~~ — wrong: a 26.2.0.93 image was already on disk under the stage worktree's
  `build/native-controls/native/neo` (installed 2026-10-30); the ".88 only" sentence looked only at the main checkout.
  Measured in the review fixes: PASS (below). No hand-off to SWAP/VERIFY.
- **m34 2-hour release soak**: VERIFY (one run). Two things it will meet, both measured here on a 2-minute control:
  JourneyMap 6.0.1's MapRenderer self-deadlock on the full `client-merged-pack` (a mod defect known since 2026-09-24;
  the earlier release soak ran without JourneyMap), and a retention REVIEW_REQUIRED (servers 1 and 5 of 5 still
  reachable after the Unlit Campfire cut). Whether the retention clears with heap-dump review on a release run, or is
  a Forbric-on-the-genuine-base change, is not established here: no native comparison was run. Owner: VERIFY (soak),
  escalate to the orchestrator if the release run's heap paths are not mod-owned.
- **m40 banner** text changed after its green run (assertions unchanged); VERIFY's tip run covers it.
- No TSV row changed: G-B2 deleted, renamed or added no Java test (test_soak is a python test).
- Gates edited here have no `refuse_shared_rundir` call: none of m31-m40/m60-m62 has a shared or default fixture
  rundir (D46's list does not name them; m34 copies its fixture into `build/soak-runs/<nonce>`).

## Suite at the lane tip

`cleanTest test` on the pinned build at 6cb3cb0f: **5772 tests, 0 skipped, 5 failed**, exactly the five rows of
`failures-wave1.txt` (0 new, 0 gone; `c5/failures-g-b2-w2.txt`, XML `c5/g-b2/w2/suite-end-xml/`). The two
KernelBundledMixinExtrasStagedTest rows of the first act are gone because build/libs now holds the boot jar (the
gates built it); nothing in G-B2's commits touches them. Targeted after the edits: `net.forbric.kernel.compat.*`,
NativeReferenceLineContractTest, MixinOutcomeWeaveTest, LibCaughtContractTest green; python test_soak 28/28,
test_gate_ports 11/11.

## Commits (on f0cb66b0)

- d20d74d8 Run gate-m33 on the base and carrier its fixtures were built against, and make its refused-face check about Fabric's generic Container view, since there is no MinecraftForge carrier InvWrapper on NeoForge's base
- 4cec54cd Assert what gate-m40's energy probe does on NeoForge's base, and clear its results directory at start so no check can pass on an earlier run's log
- 38f34512 Replace gate-m35's three retired repair-off controls with a withhold premise control, so the seven NeoForge behaviour cases keep a phase that must go red
- c702365b Launch gate-m36, m37 and m38 on the base their canaries recorded, and print [kernel] PASS/FAIL lines for them
- c2fc3c6c Run gate-m39's watchdog proof on its own Fabric canary on a plain kernel server, now that the MinecraftForge transfer canary it rode on is gone
- d1cfbcb8 Stop gate-m34's soak when no vanilla-reference sidecar can be had, instead of soaking a client with no FABRIC references
- a4c04f49 Drop menu-codec-gate's baseline phase and its MC_DIR, since the switch it turned off is retired and the baseline passed every check on NeoForge's base
- f5e5ad33 Give fluid-parity-gate GATE_PORT, and drop its --unfixed switch and its native MinecraftForge column with the MinecraftForge canary
- 578083d3 Let carpet-gate take its server ports from the caller, and stop fluid-head-gate's help naming a retired switch
- e5f2f24e Wire the regress, fluid-head and Carpet compat drivers into the gate list as gate-m60, m61 and m62
- 02e4ae0a Bring PROTOCOL.md's rows for the transfer, energy and soak gates and the compat drivers to what they do on NeoForge's base
- 6cb3cb0f Print a [kernel] PASS/FAIL verdict line from gate-m34, so a soak run's result can be counted like every other gate's
- (this record)

## G-B2 wave-2 review fixes (2026-11-10)

Reviews: `c5/review-w2-g-b2-A.md` (A-1..A-4), `c5/review-w2-g-b2-B.md` (B-1..B-8). Logs `c5/g-b2/w2/review-fixes/`.

| finding | verdict | what was done |
|---|---|---|
| A-1 m35 withhold judged by names only; spawner-nonempty-input red is probe-side | CONFIRMED | cc10eb35: each withhold red's failure text and recorded state pinned; spawner-nonempty-input's failure text in withhold now says "probe-side read-back only" (its game state equals positive's: finalizations 0, inserted, marker 35). It STAYS in the set: removing it changes the set PLAN names under D48, so that item is stopped here and reported to the orchestrator with this evidence (option (a) in the review: make the read-back phase-aware, set becomes five) |
| A-2 / B-2 "no kernel transformer touches these paths" is false | CONFIRMED | cc10eb35: gate header, canary README and WorldProbe name SpawnPositionCallsInjector (BaseSpawner.serverTick); new phase `spawn-position-off` (`-Dforbric.spawnPositionCalls=off`) must pass 7/7 like positive (a native A/B, not a red control), and the gate asserts the BaseSpawner rewrite line is logged in positive/withhold and absent there. 38f34512's message stays as history; this record carries the correction |
| A-3 m34 prints a countable PASS for a `--control` CONTROL_PASS | CONFIRMED | 0a4fe3fc: PASS only on `[M34] RELEASE_PASS releaseAccepted=True`; CONTROL_PASS prints `[kernel] CONTROL-ONLY …` (not counted, exit 0); exit 0 without a verdict line is a FAIL |
| A-4 / B-8 m33 step title names a MinecraftForge canary | CONFIRMED | 4d5a3bc0: "(Fabric and NeoForge)" |
| B-8 PROTOCOL breath-drown row says NeoForge 26.2.0.88 | CONFIRMED | 076f3a1c: 26.2.0.93, breath-drown.py's pin (the evidence.py row's .88 stays C6's) |
| B-1 fluid-parity `--mods` was measurable without a download | CONFIRMED | ran it (below): PASS. "Only .88 on disk" corrected here and in handoff/walls/checks |
| B-3 canary deletion in d20d74d8, credited to f5e5ad33 | CONFIRMED | record corrected (items table); history not rewritten, because the notes, reviews and handoff cite these shas |
| B-4 first-act XML is the 2-test rerun | CONFIRMED | record corrected: the first act is evidenced by suite-first.log only |
| B-5 G-B2 m34 lines under G-A's heading in checks.md | CONFIRMED | moved under `## G-B2 (wave 2)` (marked "moved here … review B-5") |
| B-6 wrappers not shown GREEN in gates-all | CONFIRMED | ran `gates-all.sh -j 1` with every other gate `--skip`ped: m60/m61/m62 GREEN (below) |
| B-7 m60 fabric-api source described wrong | CONFIRMED | cdae9449: default is `.dev/api/fabric-api-0.155.2+26.2.jar`, else the client-merged-pack copy (build.gradle's order; same sha256 d6518c77…); header and PROTOCOL row name each input's source |

Runs (genuine stage, ports 46760+, heavy.sh, D68):
- **m35** at cc10eb35's tree: **3 PASS / 0 FAIL**, GREEN. positive 7/7 (BaseSpawner rewrite logged); spawn-position-off
  7/7, rewrite line absent; withhold red on exactly the six, each for its pinned reason. The first attempt was RED 0/3
  on "the server never got its port (*:46760)": a server JVM leaked by this lane's own m40 H3 control
  (`M40_TIMEOUT=1`, 2026-11-09 22:48) still held *:46760; killed, re-run green (`gates/m35-port-held.out`, walls.md).
- **m35 pins red-checked offline** with the gate's own checker (`m35-check-extracted.py`): the lane's earlier withhold
  JSON (old "written back" text), spawner-data red for "expected exactly one real candidate mob, got 0", item-result
  with no consumer, an off-phase log showing the rewrite, and a positive log without it each fail (rc 1); the new
  evidence passes all three phases.
- **m34**: the verdict block run with a stand-in soak-run: RELEASE_PASS -> PASS, CONTROL_PASS -> CONTROL-ONLY (rc 0),
  REVIEW_REQUIRED exit 1 -> FAIL, exit 0 with no verdict -> FAIL; 6cb3cb0f's block printed PASS on CONTROL_PASS. The
  gate's no-sidecar path end to end: FATAL, `[kernel] FAIL M34 soak-run stopped with exit 1`, no client started
  (`gates/m34-nosidecar-3.out`). The 2-hour soak is still VERIFY's.
- **fluid-parity `--mods`** (f5e5ad33's tool, `--native-controls` = the stage worktree's build/native-controls, copied per
  run, never written; nothing downloaded): **PASS**, exit 0; servers 127.0.0.1:46760 (native NeoForge 26.2.0.93) and
  :46761 (Forbric); both arms registered ['neoforge']; every cell equal (N_place lava, N_neighbour/N_first glowstone,
  V_* obsidian, the `_end` cells too); firings equal (N_neighbour 1/1, N_first 1/1, others 0/0)
  (`fluid-parity-mods-93/summary.json`, consoles kept, server copies deleted).
- **gates-all** (`-j 1`, every other gate skipped): RESULT m60 GREEN 10/0, m61 GREEN 6/0, m62 GREEN 6/0, exit 0; m60
  read fabric-api from `.dev/api` (`gates-all-m60-m62/`).
- **m33** after its step-title change: 22 PASS / 0 FAIL, GREEN (`gates/m33.out`).
- python: test_gate_ports 11/11, test_soak 28/28.
- **Suite** at the review tip (pinned build): 5772 tests, 0 skipped, 5 failed, exactly the five rows of failures-wave1.txt (0 new, 0 gone; `review-fixes/suite-final.log`, `suite-final-xml/`).

Open after the review fixes:
- **-> orchestrator (D48, A-1):** spawner-nonempty-input's withhold red is the probe's own read-back, not game
  behaviour. Keep it (as now, labelled) or make the read-back phase-aware so the set becomes five and amend D48.
- **-> VERIFY:** m34 2-hour release soak (unchanged from above).
- **wall:** the 1-second timeout recipe for m40's H3 control (and the same `kill_owned_descendants` pattern in m35)
  can leave the phase's inner shell and its server JVM running: the descendants are snapshotted once and evidence.py is
  only killed 15 s later, after it may have started them. It only bit under `M40_TIMEOUT=1`; a 240 s timeout finds
  the tree complete. Not fixed here (no finding asked for it); noted in walls.md.

Review-fix commits: 0a4fe3fc (m34), 4d5a3bc0 (m33 title), 076f3a1c (PROTOCOL breath-drown), cdae9449 (m60 inputs),
cc10eb35 (m35), and this record update.
