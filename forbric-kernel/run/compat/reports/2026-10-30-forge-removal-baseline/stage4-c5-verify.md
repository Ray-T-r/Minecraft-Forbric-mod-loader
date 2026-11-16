# Stage 4 C5 — lane VERIFY: the stage tip measured (run 3 at 394b8804; run 2 at 3822d1af)

Run 3 (394b8804) is the section "Run 3 (wave 2d)" below; this introduction and everything after that section are run 2 as recorded.

Lane VERIFY (wave 3, PLAN "Lane VERIFY"), worktree `s4c5-verify`, branch `s4-c5-verify`, reset to the stage tip
**3822d1af** (claude/remove-forge-neoforge-fabric-83b024 after wave 2c: K3c, HARN, ZH, all fast-forward). VERIFY measures and
fixes nothing: this file is its only tracked edit. Every red below has a cause and an owner; every flaky line has a repeat
count. Numbers are the ones an adversarial recount (`verify2/check.md`, `verify2/check/*-recount.txt`) reproduced; where the
recount corrected a block's claim the corrected statement is the one written here, and the correction is named.

Evidence: `~/Forbric-backups/stage4-notes/c5/verify2/` (block0, block1, block2, block3, block4, block6, block7, check/,
check.md). Run 1's evidence: `~/Forbric-backups/stage4-notes/c5/verify/` and `c5/verify-run1-agent-results.json`.
m34 (the 2-hour soak) is not part of this run: the owner moved it to the very end, at the final tip after C6 (D84).

## Run 3 (wave 2d), stage tip 394b8804, 2026-11-16

VERIFY run 3 measured the wave-2d tip **394b8804** (lanes OPT, THR, STOP, DLG, HARN2 merged into
claude/remove-forge-neoforge-fabric-83b024, fast-forward only). Before the reset `git log <stage>..HEAD` was empty and the tree
clean; HEAD 394b8804 for every measurement; main 511f2582 untouched. Numbers here are the ones the adversarial recount
(`verify3/check.md`, `verify3/check/`) reproduced; where it corrected VERIFY's first write-up (commit a8c1d54b) the corrected
statement is written and the correction named (C-1 to C-5).

### What D87/D88/D89 dropped, and why

The owner's rule (D87, D89): stop running tests that cannot find anything new, not only run them faster.

| dropped from run 3 | why | what stands in its place |
|---|---|---|
| VERIFY's own full suite | D87/D89: the merger ran it at this same tip with nothing changed since | merger, heavy.sh, `-Pforbric.requireFixtures=all`: **5951 tests, 5 failures, 0 errors, 0 skipped** (915 XML recounted), the 5 = failures-w2c's D58 rows by name and message |
| VERIFY's own gates-all | D87: VERIFY reuses the merger's English gates-all at the same tip | merger, `gates-all.sh -j 4 --no-cache --skip gate-m34-soak.sh`, 21:05:30-21:42:33; no "cached:" in its output or any of the 60 gate logs |
| every zh run (suite, gates-all, zh m12) | D88(b): a zh run only changes the language; zh text stays covered by unit tests that force the zh locale; D87(d) is moot | none needed |
| m34 soak | D84: once, at the very end, after C6 | not measured |
| m31 alone and its `M31_UNFIXED=1` red control (C-4: VERIFY did not say it skipped them) | D89: wave 2d changed neither gate-m31's script nor any worldgen class (its 20 main-source files are boot, Fabric loader, two injectors, DialogLang, two api files; lib.sh changed by HARN2), and the merger's no-cache sweep ran m31 | m31 13/0/0 GREEN in the merger's sweep; last red control run 2 (12/1, red on the biome line) |

### Sweep wall time

| sweep | tree | wall |
|---|---|---|
| run 2 block 2, en, before OPT | 3822d1af | 44.4 min |
| lane OPT's cold sweep (D90: 46.4 -> 32.9 min) | 312d152d | 32.9 min |
| merger's cold sweep, this tip | 394b8804 | **37.0 min** (2223 s; gate seconds 6684 vs OPT 5902) |

The 4.1 min over OPT are new coverage (m24 198 checks +80 s, m24d 116 checks and 5 more clients +117 s, m30 +35 s, m0 +36 s for
58 more tests) and placement and host load (m31 placed at +424 s beside m58/m19/m24, 195 -> 373 s; load average 12.9 vs 11.7).
Against run 2 a full sweep is 7.4 min shorter, and no zh sweep follows it (run 2 had a second one).

### Setup (block 0)

Pinned `jar` through heavy.sh, rc 0, kernel sha256 c36c020e (the jar both A/Bs used); `run/build-fabric-canary.sh` rc 0. One
dangling link as before (`forbric-kernel/build/sweep100-mac`).

### Block A: targeted gates, cache off

`client.sh heavy.sh --exclusive bash verify3/A/runA.sh`, 21:50-22:25; lane-env, GATE_PORT 46800, English, MC_DIR/FORBRIC_JVM/
M59_FIXTURE/M31_UNFIXED unset, `FORBRIC_GATE_FRESH=1 FORBRIC_GATE_CACHE=0`; one gate at a time. No verdict in block A is cached.
Counts are `^[kernel] PASS/FAIL/SKIP` lines; all 15 outputs recounted.

| gate | runs | PASS/FAIL/SKIP | merger w2d sweep | run 2 (3822d1af) |
|---|---|---|---|---|
| m3 | 5 alone | 13/0/0 each (24 s) | 13/0/0 | 13/0/0 x5 |
| m12 | 3 alone | 44/0/0 each; late 3, 2, 1 of 220 ticks (ceiling 7), 0 overloads | 44/0/0 | 44/0 x3 without outside load, red under GTA V |
| m16 | 1 | 49/0/0 | 49/0/0 | 39 (OPT added 10) |
| m24 | 1 | 198/0/0 | 198/0/0 | 67 (STOP added) |
| m24d | 1 | 116/0/0 | 116/0/0 | 32 (STOP + DLG added) |
| m13 / m14 | 1 each | 30/0/0, 34/0/0 | same | same |
| m58 | 1 | 208/0/0 (832 s) | 208/0/0 | 208/0/0 |
| m59 | 1 | 30/0/0 | 30/0/0 | 30/0/0 |

- CAUGHT none in every gate, as in the merger's sweep. Run 2's four CAUGHT sites (Fabric mains in m19/m20/m24, m30's subscriber
  and deferred task) are all stops since lane STOP (D82). A positive control (`verify3/A/caught-control`): the tip jar's
  `Caught.warn` writes its FAILURE and COUNT rows to `FORBRIC_CAUGHT_FILE`. C-5: that proves the writing end only, not that every
  gate sets the file for every JVM it starts and reads it back; "CAUGHT none" is half-supported by it. Low severity.
- Normalised PASS lines equal the merger's sweep in every gate except m24d's two in-game phases. m12 differs from run 2 only in
  HARN2's wording of the kept-up line; m58 only in the known mcwbridges "unordered list attributes" tally. m3: Done about 11 s
  after start, before the 22 s timer — the other branch of `server_ready` than run 2's block 4 saw.
- **m24d common / clientnetwork: exit 1 about 1 s after the crash report (merger: "held"). Cause found (C-1; VERIFY's first
  write-up said "no cause, too fast for a click", which was wrong).** The macOS unified log
  (`verify3/check/m24d-dialog-clicks.unifiedlog.txt`): tinyfd's osascript 28292 took the foreground at 22:03:00.012; a physical
  click on the built-in trackpad (source "Apple Internal Keyboard / Trackpad", with force-feedback actuator events, so not a
  synthetic CGEvent) went down at 22:03:01.604 and up at .700; osascript logged `trackMouse send action on mouseUp` at .705 and
  exited. clientnetwork: osascript 28489 foreground at 22:03:12.372, click 14.076/.172, mouseUp action 14.177. Clipboard and
  input-method activity in the same minutes show a person was using the machine. Not a Forbric defect, and no check was lost:
  these two in-game modes have no check that runs only when held, and the gate accepts `1 held` both ways. The six in-game stops of
  the fixture 20 minutes later all held.
- Host load (10 s samples): load1 median 4.87, max 10.77; nothing outside Forbric above 80 % CPU except single samples
  (duetexpertd 93 %, corespotlightd 148 %); no game, no Wine/CrossOver.

**Staged-stop fixture (lane STOP's register-stop canary and Fabric broken canary).** STOP's own scripts with VERIFY's worktree,
scratch and port 46820. Servers, 17 modes, native arm reused from `c5/stop/w2d/native` (genuine NeoForge 26.2.0.93 and Fabric
Loader 0.19.5 server images):

- Same as native in 15 of 17 modes on exit, world, Done, "Failed to start", NeoForge's failed task and error count, crash report,
  Fabric stage line and the crash report's issue sections. complete: "Complete loading of 4 mods" against 3 (ModList size,
  THR/STOP open); fabric: exit 1 against native 0 (STOP open item 3). Every Forbric crash-*-fml.txt has an empty "Mod List:"
  (STOP A-3, open).
- Same as STOP's own Forbric arm (before the THR merge) in all 17, except that fabric's stage error is printed once now and twice
  then (STOP review fix). THR's worker dispatch changed no stop. The fixture logs carry 935 distinct modloading-worker-0 lines
  (server 551, client 384; C-3: VERIFY's "1834" does not reproduce — 2805 with duplicates across all files, 1907 with m24/m24d's
  logs); the conclusion stands.
- Clients, the 10 modes gate-m24d does not run: equal to STOP's client summary on every column. The 4 begin-path modes' main
  threads sit in NeoForge's early-window `ErrorDisplay.fatal`; the 6 finish-path modes (deferred, sided, regevents, imc, complete,
  network) all held, the render thread in `TinyFileDialogs.tinyfd_messageBox`, as m24d's common and clientnetwork do. Natively that
  error is drawn on the game's own window: eight in-game stops in all now show tinyfd's box instead (merger item 3).
- No ConcurrentModificationException, RejectedExecutionException or deadlock in any fixture log (the one "deadlock" hit is a
  ChunkGuard comment line).

### Block B: the two A/Bs

**Pure-Fabric A/B (D57).** `fabric-ab.py` per-mod --jobs 2, confirm, packs native-pass / matched-pass / all, summary, under
`heavy.sh --exclusive`, 22:25-23:01, every step rc 0. Data `forbric-kernel/build/purefabric-2026-10-03` in the main checkout (the
brief's `build/purefabric-2026-10-03` at the repo root does not exist; run 2 used this one too). Native arm: the 10-03 runs.jsonl
(sha256 74af1020), launcher f1d2bafd, all 142 native rows cached, no native session started. Forbric: **132 launches** (C-2:
VERIFY wrote 129) = 130 per-mod + pack native-pass + pack all; pack matched-pass reused native-pass's result (5adde350).

- per-mod: **126 MATCHED_PASS / 4 BOTH_FAIL / 0 FORBRIC_ONLY / 0 NATIVE_ONLY**; the MATCHED_PASS set equals the 2026-10-11
  report's, the 4 BOTH_FAIL are the same names. Bar met; no c3-merged-ro rerun needed.
- native-pass and matched-pass packs (159 jars): MATCHED_PASS, Forbric game time 1..1216, native 1..1212. Full pack (165 jars):
  BOTH_FAIL, as on 10-11.
- The Forbric signatures of the 4 BOTH_FAIL and the full pack changed from `POLICY_STOP:<mod>:initialization:entrypoint:main`
  (exit 78) to `EXIT:1`: since STOP (D82) a throwing Fabric main stops the boot with Fabric Loader's stage error ("exiting with
  status 1, as NeoForge's own launcher does"). Same mods named: andonium, creepertweaks, gmm, beilin-data-portability; the full
  pack's suppressed errors are beilin-entry-control's and gmm's. Native exits 0 there, Forbric 1 (STOP open item 3).

**Native NeoForge A/B.** `native-controls` run/compare --family neo, 3 rounds, on the .93 image (`diff -r -q` against the stage
worktree's rc 0, no prepare, nothing downloaded); canary built through the tool's compile_canaries (the tool's `build` still needs a
Forge image). **MATCHED_PASS 3/3**, 11/11 checks true on each arm, rc 0. Log diff 47 lines each round (run 2: 49): NeoForge's
"mod loading" line and the canary's constructor now run on modloading-worker as natively (THR); the ConfigApi false WARN of run 2
is gone (DLG; WARN lines Forbric 8, native 5, run 2 9). The rest is run 2's recorded list (D38 [STDOUT], MixinExtras 0.5.4 vs
0.5.5, carrier datapack, no fml.toml, no FML mod-list banner).

### Acceptance (PLAN "Lane VERIFY")

| clause | verdict | evidence |
|---|---|---|
| suite failures = only the Stage-0 fixture rows (LB x3, shared-final x2) | **met** | merger at 394b8804: 5951 / 5 failed / 0 error / 0 skipped, names and messages = failures-w2c |
| every gate GREEN, m0 allowed only its D58 red (D87a) | **met** for the 60 gates run; **m34 not measured** (D84) | merger no-cache sweep: 59 GREEN; m0's single FAIL "3 of 5951 skipped" = the 3 LiquidBounce tests (0 failed in its XML); nothing cached; CAUGHT none everywhere; block A's 15 repeats all green |
| pure-Fabric A/B: 0 FORBRIC_ONLY vs 2026-10-11 (D57) | **met** | 126/4/0/0 name for name; packs as 10-11; tip jar c36c020e |
| native NeoForge A/B | **met** | 3/3 MATCHED_PASS, 11/11 checks each arm; tip jar c36c020e |

### Reds and open items, each with its owner

No red outside the exempt one.

| item | cause | owner |
|---|---|---|
| m0 red (merger sweep) and the suite's 5 failures | D58 Stage-0 fixtures absent (LiquidBounce jars; shared-final definitions); exempt by D87(a) | P1/D58 |
| m24d puts real system dialogs on the owner's desktop and takes focus (8 clients); one click changes the outcome: in-game modes cannot tell "box clicked away" from "no box", early-window modes turn 3 running-client checks into SKIP while the gate stays GREEN (C-1 a-c) | gate design, not weakened in this wave | orchestrator, with the existing m24d focus item |
| in-game stops (m24d common/clientnetwork + 6 fixture modes) show NeoForge's tinyfd box; natively the error screen is drawn on the game window | merger item 3 | orchestrator / owner |
| crash report "Mod List:" empty; "Complete loading of 4 mods" vs 3 | STOP A-3; THR/STOP ModList size | orchestrator assigns (STOP/THR follow-up) |
| a Fabric main stop exits 1, native 0 (fixture fabric mode; the 4 pure-Fabric BOTH_FAIL + full pack) | STOP open item 3 | orchestrator assigns (STOP follow-up) |
| THR F2/B-1 (construction as three kernel passes, registration on the caller thread, ModList during construction); downstream listeners still run after a dependency failure; native client not measured | merger items 1-2 | orchestrator |
| CAUGHT positive control covers only the writing end (C-5) | no check that every gate JVM gets and reads FORBRIC_CAUGHT_FILE | orchestrator decides (none needed if HARN's unit tests cover the reading end) |
| native-controls `build` still requires the Forge family | tool written for three families | orchestrator / C6 |
| `build/sweep100-mac` dangles; a Gradle daemon outlives a sweep (merger killed 73449) | known | orchestrator |
| m34 soak | not run | final tip after C6 (D84) |

### Evidence

`~/Forbric-backups/stage4-notes/c5/verify3/`: `block0/`; `A/` (summary-clean.txt, compare-lines.txt, load-summary.txt,
m3-timeline.txt, stop-fixture/server-compare3.tsv, stop-fixture/client-summary.txt, caught-control/, m24-logs/, m24d-logs/,
m58-evidence/, m59-evidence/, tools/); `B/fabric/` (report/summary.json, load-summary.txt); `B/neo/` (compare-1..3.out,
logdiff-1..3.txt); `check.md` and `check/` (recount-gates-all.txt, m24d-dialog-clicks.unifiedlog.txt). The merger's: `c5/merge-w2d.md`,
`c5/failures-w2d.txt`, `c5/merger/w2d/gates-all-counts.txt`, `c5/merger/w2d/sha-map.txt`.

### Cleanup

No VERIFY java or osascript left; ports 46800-46839 and 25651-25663 free; the only Gradle daemon (45088, 19:01) predates the run;
scratch deleted. Kept by design: 5 failed Fabric instances and 6 neo instances under `build/native-controls/instances`.

The sections below are run 2 (3822d1af) as recorded then.

## Run 1 (2c231c9c) and why it was superseded

VERIFY run 1 (wf_0ca07bf0-9f1, 2026-11-11) measured the wave-2b tip 2c231c9c:

| block | result at 2c231c9c |
|---|---|
| suite en / zh | 5873/0/5 each = failures-w2b (D58's LiquidBounce x3 + SharedFinalSourceActual x2) |
| gates-all en, -j 4 | 60 gates: 58 green, m0 red (D58), **m3 red 12/1** ("ServerTickEvent listener FIRED … want>=1 got 0") |
| gates-all zh | 57 green: m0, m3, **m12 red 43/1** (11 of 220 late, ceiling 7); zh reached only 16 gates' game JVMs |
| targeted | m12 x3 alone 44/0; m13 30/0, m14 34/0, m31 13/0, M31_UNFIXED=1 12/1 (biomes, by design), m58 208/0, m59 30/0 |
| m34 | started, killed mid-run when the tip had to change |

Its findings became wave 2c: lib.sh `server_ready` sent `stop` with no settle when Done was already in the log at the 22 s
timer (m3; latent since 3e623937) and m12 was scheduled beside m0's unit suite (lane HARN); `FORBRIC_JVM` reached only 16 of 59
gates' game JVMs, so "gates-all in zh" had not tested zh (lane ZH); and the owner's ruling D82 "Stop like native" on a throwing
RegisterEvent listener (lane K3c). Run 1 was stopped because those lanes change the tip; none of its results stand for 3822d1af.
Run 2 is the full re-run at the new tip (D86 relaunch after the dead wf_73f2e982-14e).

## Setup (block 0)

- `git reset --hard claude/remove-forge-neoforge-fabric-83b024` on s4-c5-verify (no commits of its own were on it): HEAD
  3822d1af, clean. The reflog shows HEAD at 3822d1af from 19:08:48 to the end of every block (recount). main 511f2582, untouched.
- `.stage4/minecraft` still points at the stage worktree's absolute path (run 1's re-link); 86 links, one dangling:
  `forbric-kernel/build/sweep100-mac` (points into a main-checkout directory that no longer exists; no test reads it — tests use
  `sweep100-mac-network`; already recorded, owner orchestrator).
- Staged root identical to the stage worktree's by sha: base 67bbf24f, carrier 0e3ce8d9, forbricneolive 2e74d589 (Phase 0),
  vanilla-reference e3b8ac35. `run/forge-swap` -> `~/Forbric-backups/forge-swap` (D81).
- lane-env: GATE_PORT=46800, the seven per-gate ports 46801-46807, MC_DIR unset. 62 gate scripts pass `bash -n`.
- `jar` (heavy.sh, pinned props, `--offline`): BUILD SUCCESSFUL. `run/build-fabric-canary.sh`: rc 0. (The canary jar's sha1
  differs on every build — ac54ca1d, fef3dade, 6d6f5881 in blocks 0/2/3; contents not compared.)

## Unit suite (block 1)

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test --continue`; zh with
`-I c5/verify/block1/lang-zh.gradle` (user.language=zh, user.country=CN on Test tasks).

| run | time | tests / skipped / failed | XML files | against failures-w2c |
|---|---|---|---|---|
| en | 19:09-19:18 | 5887 / 0 / 5 | 907 | 0 new, 0 gone, messages equal to 200 chars |
| zh | 19:18-19:26 | 5887 / 0 / 5 | 907 | 0 new, 0 gone, messages equal to 200 chars |

- The 5 are D58's recorded Stage-0 fixture reds (owner P1/D58): `FabricClientMixinAnchorsTest` x2 and
  `MixinTwinRebindTest` x1 (LiquidBounce jars absent), `SharedFinalSourceActualTest` x2 (opt-in fixture absent). The recount saw
  the two SharedFinalSourceActual tests **green** inside m0's run, where m0 generates the definitions — so they are red in the
  suite only for the missing fixture.
- en = zh name for name and message for message. zh took effect: 2219 CJK characters in the zh XML against 46 in en.
- Per class, the en counts equal the merger's `merger/w2c/suite-tip-xml` in all 907 classes (5887 = 5873 + K3c 9 + HARN 5).

## gates-all, en (block 2)

`client.sh heavy.sh --exclusive bash run/compat/gates-all.sh -j 4 --skip gate-m34-soak.sh`, 19:27:50-20:12:16 (44.4 min),
exit 1; FORBRIC_JVM, MC_DIR, M59_FIXTURE unset; JVM locale en/US. Counts are `^\[kernel\] PASS/FAIL/SKIP` lines only.

- 61 gates: **60 green, m0 red 13/1/1**, m34 skipped. **PASS 1759 / FAIL 1 / SKIP 1**, every row equal to the merger's
  `merger/w2c/gates-all-counts.txt` (the comparison script reproduces the merger's file from the merger's own results first).
  CAUGHT: m19 1, m20 1, m24 1, m30 2 (= merger).
- m0's FAIL: "unit tests — 3 of 5887 skipped" (gate-m0-test.log: 5887 tests, 3 skipped, 0 failed) = the three LiquidBounce
  tests; its SKIP is D64's C6 link check. Cause D58, owner P1/D58 — the same as the merger and run 1.
- Against run 1: m24 30 -> 67 and the new m24d 32/0 (K3c); m3 12/1 -> 13/0 (see m3 below). Every other row unchanged.
- Normalised PASS-line text against the merger differs in two places, neither a red:
  - **m24d try phase** took the gate's other accepted ending (gate-m24d-registerstop-client.sh lines 104/105): here NeoForge's
    fatal-error box stayed up until the gate's TERM after 10 s ("held"); in the merger's run the client left with exit 1 ~4 s after
    its crash report (the box returned). The game logs agree up to the crash report. What returned the merger's box is **not
    known**: its parallel neighbours were the same five gates in both runs (m45, m50, m51, m52, m53), so scheduling does not explain
    it, osascript's stderr was not recorded, and "desktop input" is a guess (recount C-6). The gate accepts both; no owner.
  - **m58 mcwbridges**: "unordered list attributes: N" varies run to run (merger 1, block 2 absent, block 3 2, block 4 1) with
    "0 differences" every time — the upgrade rule absorbs a list-order difference that comes from resaving. Known; not a defect.
- m12 (alone, `alone` scheduling from HARN): 5 of 220 late ticks (ceiling 7), merger 1. m15, m16 alone: green, 0 overloads.

## gates-all, zh (block 3)

Lane ZH's recipe: `export FORBRIC_JVM="-Duser.language=zh -Duser.country=CN -Dforbric.dialogLanguage=zh_cn"`, the same command,
-j 4, 20:15:37-21:06:13, exit 1; a sampler recorded for every game JVM its gate (parent chain), the last `-Duser.language` and the
last `-Dforbric.dialogLanguage` (or the JAVA_TOOL_OPTIONS value).

- **PASS 1757 / FAIL 3 / SKIP 1**: 59 green, m0 red 13/1/1 (= en, D58), **m12 red 42/2**. Every other row and CAUGHT = en.
- zh reached every gate's Forbric game JVMs except the declared fixed-English launches: m26's refusal launch (en/en, its dialog
  child none/en) and m29's first boot (en/en; boots 2-3 zh/zh_cn); both are pinned in the gate sources (m26:244, m29:174) and
  declared to `test_gate_locale`. Every other non-zh row is not a Forbric game: m13's Paper and m14's native Fabric reference
  servers, MergedLinkChecker (m17), and 11 JVMs no gate owns (6 JUnit-forked DependencyDialogMain children and 1 kernel_scan in m0,
  3 Gradle verifyRebornEnergy, 1 MergedLinkChecker). m17's installed game shows as "zh(JAVA_TOOL_OPTIONS)/none" only because the
  sampler's regex cannot read the dialog switch there; its game log shows
  `Picked up JAVA_TOOL_OPTIONS: -Duser.language=zh -Duser.country=CN -Dforbric.dialogLanguage=zh_cn`.
- Side evidence: of the 21 load reports written during the sweep, 20 are Chinese (129-257 CJK characters); the one with none is
  m26's fixed-English launch.
- Normalised lines against block 2: only m12 (the red) and m58's absorbed list-attribute tally (2 here) differ.

**m12 red (42/2)**: "server kept up while the player was on (1 overload warning(s); worst: Running 2215ms or 44 ticks behind)"
and "server ticks late from the client's dial to the leave: 31 of 240 at twice the budget (ceiling 7)" (29 of 100 while the
player was on). Cause: outside load. The owner started GTA V under CrossOver during the sweep (Steam 20:54:08, PlayGTAV 20:59:58,
GTA5_Enhanced.exe 21:00:46); m12 ran alone: server Done 21:00:09, player joined 21:00:57, stall 21:01:01, inside the session.
Repeats alone under GTA (GTA mean 257 %CPU, peak 370 %): zh 44/0; en 42/2 (2040 ms stall, 34 of 300 late, ceiling 9); zh 42/2
(worst 3407 ms, 30 of 300). The same failure in en under the same load: not the locale, not a Forbric regression. Five repeats
were planned; three were run so the client windows would stop interrupting the owner's game.
- Correction (recount C-5): block 3 said every gate after Steam started ran slower and listed m58 (1032 s vs 861 s), but m58 ran
  20:42:30-20:59:48 and 11.5 of its 17 minutes were before Steam started; no load sampler ran during the sweep. m58 is green, so the
  verdict is unaffected, but its slowdown is not attributed.

## Targeted repeats (block 4)

`client.sh heavy.sh --exclusive bash c5/verify2/block4/run.sh`, 21:29:53-22:12:23, en, GATE_PORT=46800, a 5 s load sampler;
`run-extra.sh` 22:13:24-22:15:26 for two more m12 after GTA exited. GTA V ran until 21:51:05 (160-330 %CPU, load 12-20); no other
lane's java ran during the block (another session's m16 held the client lock until 21:29:50).

| gate | P/F/S | time | GTA running | run 1 |
|---|---|---|---|---|
| m3 #1-#5 | 13/0/0 x5 | 33-45 s | yes | — |
| m31 | 13/0/0 | 416 s | yes | 13/0, 371 s |
| m31 `M31_UNFIXED=1` | 12/1/0, rc 1 | 413 s | yes | 12/1, same line |
| m12-1 | 42/2/0 | 144 s | yes | 44/0 |
| m12-2 | 43/1/0 | 114 s | exiting | 44/0 |
| m12-3 | 44/0/0 | 56 s | no | 44/0 |
| m12-4, m12-5 (extra) | 44/0/0 x2 | 62 s, 60 s | no | — |
| m13 | 30/0/0 | 96 s | no | 30/0, 96 s |
| m14 | 34/0/0 | 52 s | no | 34/0, 52 s |
| m58 | 208/0/0 | 872 s | no | 208/0, 880 s |
| m59 | 30/0/0 | 102 s | no | 30/0, 108 s |

- CAUGHT none in every run. Normalised lines: m3 x5, m13, m14, m31, m59 equal blocks 2/3, the merger and run 1's block 4; the
  unfixed m31 equals run 1's; m12-3/4/5 equal block 2, the merger and run 1; m58 differs only in the absorbed list-attribute tally.
- **M31_UNFIXED=1** is the control, red as designed on exactly one line: "every chunk carries vanilla's biomes (want 0 got 11)";
  structure starts and dungeon mobs 0 on both arms.
- **m58**: 0 differences, all 9 phases. **m59**: S0 = the MANIFEST's 660f98e5; fixture-MANIFEST byte-identical to
  `~/Forbric-backups/forge-swap/MANIFEST`; inputs name `forge-swap/old-config`.
- **m3**: 7 of 7 green at this tip (block 2, block 3, block 4 x5); in block 2 and block 4 the stop went out 5-6 s after Done. **Correction (recount
  C-2):** block 4 claimed these five runs fell into the old defect's window and so proved HARN's fix. They did not: the old
  `server_ready` only went wrong when Done was already in the log, less than about a second old, at the moment the 22 s timer
  ended. In block 4 Done came ~31 s after the first game line (after the timer — the polling branch the old code already handled
  right); in block 2 Done came ~4 s before the timer. Run 2 never hit the window, so it cannot tell the fixed helper from the old
  one. The fix's evidence is HARN's red/green control (stage4-c5-harn.md, Item 1) and `LibStopWhenReadyContractTest` (12 cases,
  green in block 1 en and zh).
- **m12-1** (42/2): a 2000 ms stall at 21:49:41 inside the session (21:49:35-21:49:44) while GTA ran; 38 of 300 late (ceiling 9).
  Outside load.
- **m12-2** (43/1): **not outside load** (recount C-1; block 4 had attributed it to GTA). Its only FAIL is "server kept up while
  the player was on (1 overload warning(s); worst: Running 3120ms …)", but the one overload is at 21:51:15, 17 s *before* the
  player joined (21:51:32); in the session 4 of 240 ticks were late (green). GTA was already gone from the 21:51:05/10/15 samples;
  at 21:51:10 java was at 335 % — the client booting beside the server. lib.sh's `check_kept_up` counts "Can't keep up" over the
  **whole** server log (it only checks that a session exists), while m12, m15 and m16 print it as "while the player was on". The
  red is that mismatch plus the client-boot contention the gate itself says is "not asserted". `LibKeptUpContractTest` covers
  "is there a session" and "is there an overload", not the window. (block 3's m12-3-zh also had a pre-join overload, but a second
  one inside its session, so it is red either way.)
- **m12 with a quiet host**: 3 of 3 green (3, 1, 3 late of 220-240); with block 2, 4 of 4 quiet-host alone runs green at this
  tip — **all in en** (run 1's three alone runs at 2c231c9c were green too, under a Wine steam.exe). No zh m12 at this tip ran on
  a quiet host (recount C-4).
- **K3c register-stop canary** (K3c's own fixture scripts, client timeout 60 s): the server listener exits 1 within 24 s, no
  world, crash-*-fml.txt "Mod loading issue for: forbricregisterstop"; with times, hashes, UUIDs normalised every
  WARN/ERROR/FATAL and Forbric Lifecycle/Boot line equals K3c's server-listener run ("Rolling back to VANILLA", "Failed to wait for
  future Registry initialization, 1 errors found", "Failed to start the minecraft server", the kernel's exit-1 line). The client
  listener: crash report "Bootstrap" naming forbricregisterstop and its IllegalStateException, main thread in
  `tinyfd_messageBox` (jstack), box titled "NeoForge - Fatal Startup Error" with the text "INVALID MESSAGE WITH QUOTES", no saves/,
  no Fabric main — line for line K3c's run. Exit 1 here vs 143 in K3c's: this script closes the box's osascript child first, the
  box returns, NeoForge's `reportFatalError` returns and the client exits 1 itself (the same mechanism as m24d's two endings).

## Pure-Fabric A/B (block 6, D57)

`fabric-ab.py --data …/purefabric-2026-10-03 --out block6/ab`: per-mod --jobs 2, confirm, pack (native-pass, matched-pass, all),
summary; 22:20:40-22:57:43, every step rc 0; en. Native arm reused, never re-run: `runs.jsonl` (sha256 74af1020) copied from the
10-03 data; native launcher sha256 f1d2bafd = the 10-03 and 10-11 identity. Kernel jar cbf288b5 from both build paths (pinned
`jar`, and kernel_jar()'s own `gradlew --offline -q jar`); all 133 Forbric session records carry it (132 distinct keys).

- per-mod, 130 subjects: **MATCHED_PASS 126 / BOTH_FAIL 4 / FORBRIC_ONLY 0 / NATIVE_ONLY 0**. MATCHED set equal by name to the
  2026-10-11 report; the 4 BOTH_FAIL (andonium, beilin-data-portability, creepertweaks, gmm) are native FAILED_TO_START (EXIT:0)
  and Forbric POLICY_STOP with 10-11's signatures word for word. confirm: nothing to re-run.
- pack native-pass (126 subjects, 159 jars, 1200 ticks): DONE on both, Forbric gametime 1..1218. pack matched-pass: same jar set,
  DONE (cache). pack all (165 jars): BOTH_FAIL, Forbric `POLICY_STOP:beilin-data-portability…,beilin-entry-control…,gmm…` = 10-11.
- D57 bar met; no FORBRIC_ONLY subject, so no c3-merged-ro re-run. No crash report, no stray JVM after stop; load 1-min median 7.2.
- The native Fabric image was gone from the main checkout's `build/native-controls` (map-misc :392 is stale). Rebuilt into the
  verify worktree's `build/native-controls/native/fabric` (103 MB) with `native-controls.py prepare --family fabric`, which only
  copies (launcher from `.stage4/run/downloads`, libraries from `run/fab-server`) and returns before any download.

## Native NeoForge A/B (block 7)

On the worktree's .93 image (`cp -c` of the stage worktree's in run 1; `diff -r -q` rc 0; universal c9ce1b3a, patched server
98c74616, installation.json 32ce21b9, upstream SHA-1 97205829; main's .88 never used, nothing prepared or downloaded). 23:01:00-23:01:52:
`jar` (cbf288b5), the neo canary built, then 3 rounds of `run --engine native`, `run --engine forbric`, `compare`.

- **3 of 3 MATCHED_PASS**, all 11 checks true on both arms, sameModHashesSeedActions=true; lifecycle 1/1/1, ticks 20-23, actions
  3/3, seed 8035262, observedBlock nativecontrolneo:probe on both. Native wall 5.5-5.7 s (Done 0.19-0.21 s), Forbric 10.3 s (Done
  0.38-0.41 s; the wall includes the launcher's own gradle-jar check).
- Deviation: the tool's `build` compiles fabric, forge and neo canaries and refuses without every native image; no Forge image
  exists any more (main's `build/native-controls` is gone, `prepare forge` downloads). `block7/build-neo.py` calls the tool's own
  `compile_canaries()` with VERSIONS narrowed to neo for the compile only (nativecontrolneo-1.0.0.jar d05906c0); run and compare are
  the tool's CLI. The tool's ports are fixed (native 25653, Forbric 25663), outside VERIFY's block; free before and after.
- Forbric-only differences, each with its reason: canary println lines carry `[STDOUT]` (D38 kept wrapStreams); MixinExtras 0.5.4
  vs native 0.5.5 (recorded: baseline README :82, introduction :634); level.dat DataPacks.Enabled adds
  `forbric/carrier/2-neoforge-runtime` and a new world logs "Found new data pack …, loading it automatically" (carrier pack design,
  UPG record, D43; this canary has no data/, so data priority between a NeoForge mod and neoforge is **not** measured here); no
  `config/fml.toml` (the kernel never loads it); no FML "Mod List" banner (Forbric's ModLoader lines instead); 212 more
  `[Forbric/…]` lines, 4 of them WARN, identical across rounds.
- **Not listed by block 7 (recount C-7):** natively "NeoForge mod loading, version …" is logged on `[modloading-worker/INFO]`, in
  Forbric on `[main/INFO]` — NeoForge mod construction runs on FML's worker thread natively and on the main thread in Forbric. Not
  found in the c5 notes; this canary cannot show an effect. Needs a ruling.
- **False WARN (new, pre-Stage-4, log only):** every NeoForge boot logs `[Forbric/ConfigApi] net.forbric.kernel.runtime.KernelConfigApiBridge
  constructs ConfigurationScreen from a mod id directly in 1 place(s) — … no safe constructor rewrite was proved`. In
  `ConfigApiAbiInjector.routeTheConfigScreenThroughTheBridge` the NEW-DUP-`<init>` scan stops at the first
  `INVOKESPECIAL ConfigurationScreen.<init>` of any descriptor and assigns `constructor` only for the by-id one (lines 197-207), so
  NeoForge's own `(ModContainer, Screen[, Function4])` constructor also counts as `direct++`. javap of the bridge: NEW, DUP,
  aload_0, containerFor, aload_1, invokespecial `(ModContainer;Screen)V`, no branch. Balm's NeoForge classes
  (NeoForgeBalmClientRuntime, NeoForgeBalmConfigScreenProviders) trip it the same way (315-325 lines for Balm, 1672 for the bridge
  across the c5 logs); UPG's old-kernel log `c5/upg/w2/u1-old-boot.log` already has it. No bytes change (only rerouted>0 rewrites);
  the wrong sentence names every NeoForge mod that builds its config screen the native way.

## Acceptance (PLAN "Lane VERIFY")

| clause | verdict | evidence |
|---|---|---|
| suite failures = only the recorded Stage-0 fixture rows (LB x3, shared-final x2) | **met** | en and zh 5887/0/5, names and messages = failures-w2c; shared-final x2 green in m0 where the fixture exists |
| every gate GREEN | **not met as written** | en: m0 red (D58 only). zh: m0 red (D58), m12 red 42/2 (outside load). m12 quiet-host 4 of 4 green at this tip, all en; no quiet-host zh m12 (C-4); m12's kept-up check has a scope defect (C-1). Whether m0's D58 red is exempt needs the orchestrator's word (as in D80/D81) |
| m34 soak | **not measured** | moved to the very end, after C6 (D84) |
| pure-Fabric A/B: 0 FORBRIC_ONLY vs 2026-10-11 (D57) | **met** | 126/4/0/0, per-mod and signatures equal to 10-11; native arm from cache (f1d2bafd) |
| native NeoForge A/B | **met for the canary** | 3/3 MATCHED_PASS on .93; build step went around the tool's Forge requirement; C-7 thread difference awaits a ruling; data priority not measured |

## Reds and open items, each with its owner

| item | cause | owner |
|---|---|---|
| m0 red 13/1/1 (en, zh) and suite 5 fails | D58 Stage-0 fixtures absent (LiquidBounce jars; shared-final definitions) | P1/D58; orchestrator to confirm the exemption for "every gate GREEN" |
| m12 red: zh gates-all 42/2, block 3 repeats en 42/2 + zh 42/2, block 4 m12-1 42/2 | outside load (owner's GTA V under CrossOver), same failure in en | existing orchestrator row: m12's ceiling vs outside load, no native NeoForge baseline |
| m12-2 red 43/1 (C-1) | `check_kept_up` counts overloads over the whole log while the line says "while the player was on"; the overload was 17 s before the join | lib.sh's owner (PLAN §3: INFRA -> G-B1), orchestrator assigns; scope the count to join..leave or change the wording, red on a control / green on the tip (D34) |
| no quiet-host zh m12 (C-4) | every zh m12 ran under GTA | VERIFY (one alone zh m12 when the owner's machine is quiet), orchestrator schedules |
| ConfigApi false WARN (C-8) | scan counts any `ConfigurationScreen.<init>`, not only the by-id one | kernel, orchestrator assigns (visible-errors class); its test must be red on 3822d1af |
| mod construction thread (C-7) | native `modloading-worker`, Forbric `main` | orchestrator: accepted design or an owner |
| F2/B-6 "INVALID MESSAGE WITH QUOTES" in the macOS register-stop box | open from K3c/merger | owner / orchestrator ruling |
| native-controls `build`/VERSIONS/README still require the Forge family; fixed ports 25651+index (+10) outside GATE_PORT | tool written for three families | orchestrator / C6 |
| native Fabric image missing from main's checkout; rebuilt (copy only) in the verify worktree, 103 MB; failed instances kept (Fabric 353 MB, neo 3 x 137 MB + 3 x 3.7 MB) | tool keeps failed/run instances by design | orchestrator (cleanup decision) |
| `build/sweep100-mac` dangles in lane worktrees | target gone from main's checkout | orchestrator (already recorded) |
| ZH sampler regex misses a dialog switch inside JAVA_TOOL_OPTIONS; m0's kernel_scan and JUnit children carry no FORBRIC_JVM | evidence script limit; not game launches (the suite's zh is block 1's) | information for the orchestrator / ZH |
| m24d try phase: held vs exit 1 (C-6) | unknown what returned the merger's box | none (both endings accepted) |
| m58 list-attribute tally varies | resave order absorbed by the upgrade rule | none |
| m34 soak | not run | final tip after C6 (D84) |

Carried from the wave-2c merger's list and not measured here: the client try-mode crash report has no stack; stages where native
NeoForge stops and Forbric still contains (postRegisterEvents' tail steps, construct/setup, the Fabric main); `DialogLang` ignores
a bare `zh`; shared Gradle daemons outlive a sweep; the lane port block fits two slots; GATE-PARALLEL ignores unknown words;
introduction.md (en + cn) to gain `alone` and "a registration failure stops as native" at the merge into main; HARN's and ZH's
records cite pre-rebase shas (map in `merger/w2c/sha-map.txt`).

## Not run by VERIFY in run 2

m34 (D84); integrationTest; the python discover suites (the merger's 247 OK at this tip stands as the merger's measurement);
linkGateTest; repair-drift; the boot ladder.

## Cleanup

Ports 46800-46839 and 25651-25663 free; no VERIFY java or osascript left (Gradle daemons 72348 and 90404 predate the run and were
left alone); `run/.gate-clones` (2.0 GB, blocks 2-3) and every scratch directory deleted; worktree clean apart from this file.
