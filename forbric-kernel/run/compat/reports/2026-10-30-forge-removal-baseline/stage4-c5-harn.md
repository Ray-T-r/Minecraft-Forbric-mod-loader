# Stage 4 C5 — lane HARN (gate harness: lib.sh's stop_when_ready, gates-parallel scheduling), wave 2c

Branch `s4-c5-harn`, cut from the stage tip 2c231c9c (worktree `.claude/worktrees/s4c5-harn`, ports 46640-46659 from
`.stage4/lane-env.sh`). Notes and logs: `~/Forbric-backups/stage4-notes/c5/harn/w2c/` (paths below are relative to it).
Harness: D68 (pinned build, plain `--offline` from `.stage4/props.txt`, no init script, no private Gradle home); every
Gradle and game run through `.stage/heavy.sh` (game runs with CPU hogs and the sweep `--exclusive`, client runs under
`.stage/client.sh` first). Nothing was downloaded. The two findings come from VERIFY run 1 (D82): gate-m3 red in both
gates-all sweeps, gate-m12 red in the zh sweep.

## Worktree

An earlier attempt had cut the worktree (D83/D86): on `s4-c5-harn` at 2c231c9c, clean, `.stage4/lane-env.sh` correct (ports
46640-46659, MC_DIR unset), `.stage4/minecraft` already the absolute link to the orchestrator's `.stage/minecraft`, guard refuses
`run/client-merged-pack`, canary sha1 2e74d589. Of the 85 links one dangles: `forbric-kernel/build/sweep100-mac` points at the
main checkout's `forbric-kernel/build/sweep100-mac`, which does not exist (the same link dangles in `s4c5-k3c`). Not re-created:
it is a main-checkout fixture (never change main) and nothing in this lane reads it; the suite below has the same 5 failures as
the stage tip with it dangling. For the orchestrator.

## Commits (on 2c231c9c)

| sha | what |
|---|---|
| 3b9e69a2 | LibStopWhenReadyContractTest: Done's moment is stamped before the line is written (the 1.999 s settle flake) |
| 42b11b10 | lib.sh `server_ready`: the settle counts from the second Done is first seen, whenever it came; whole seconds only; two new contract cases |
| 1e595872 | gates-parallel.py: `alone` on a `# GATE-PARALLEL:` line runs a declared gate with no other gate; gates-all.sh header; GatesAllTest stub sweep |
| c291325c | gate-m12 declares `alone`; GatesAllTest rule: a gate that reads the tick sampler must declare `alone` |
| (this file) | record |

Each code commit was compiled and its tests run on its own tree (`settle/`, `alone/`, `alone/intermediate-1e595872.*`).

## Item 1: gate-m3 and `server_ready` (fixed)

**Cause.** `server_ready <log> <seconds> [settle]` slept `<seconds>`, then: Done already in the log -> return at once; Done not
yet there -> poll, then sleep `<settle>`. A boot that ended in the timer's last second therefore got "stop" in the same second as
its Done. gate-m3 feeds `stop_when_ready "$LOG" 22`, and the canary forbricneolive prints "20 server ticks observed" on its 20th
ServerTickEvent.Post, so the check "mod's ServerTickEvent listener FIRED (game loop)" lost. VERIFY run 1: Done 15:16:25, "Stopping
the server" 15:16:26 (`verify/block2/m3-red`). Latent since the helper came in (3e623937); NeoForge's base only moved the loaded boot
near 22 s.

**Fix (42b11b10).** The timer is now one-second sleeps that also watch the log and note the step Done is first seen. The line
goes out at the later of the timer's end and that step plus `<settle>`. The steps are counted by the sleeps themselves, so both the
timer and the settle can only run long, never short. A boot done a settle or more before the timer still gets the line at the
timer's end, as before. A Done already in the log when the helper is called counts as seen at 0. A non-integer timer or settle is
refused on stderr (it would otherwise make `[ 0 -lt 1.5 ]` an error that skips the timer); no caller passes one (all 18 callers
checked: m2, m2b, m3, m4, m4-canary, m8, m10, m11, m18, m19, m20, m21, m24, m24b, m29, m30, m39, old-kernel.sh).

**Contract tests** (LibStopWhenReadyContractTest, 9 -> 11):
- `aBootDoneASettleBeforeTheTimerEndsGetsTheLineWhenTheTimerEnds` (was `aBootInsideTheTimerGetsTheLineWhenTheTimerEnds`, which
  asserted "no settle when Done was already there", i.e. pinned the defect): Done in the log from the start, timer 3, settle 1 ->
  line at 3-6 s. Green on both lib.sh versions: the unchanged half.
- new `aBootThatEndsJustBeforeTheTimerStillGetsItsSettle`: Done half a second before a 2 s timer, settle 3 -> the line comes >= 3 s
  after Done.
- new `aTimerOrSettleThatIsNotWholeSecondsIsRefusedOutLoud`.

Red control (`settle/old-libsh.*`, `settle/new-libsh.*`): the 11 cases on 2c231c9c's lib.sh -> 2 failed, "the line came
0.5032670497894287 s after a Done that beat the timer, not after the 3 s settle" and the fractional case ("stop"); on 42b11b10's
lib.sh 11/0.

**gate-m3 under ten `yes` CPU hogs**, heavy `--exclusive`, GATE_PORT 46640 (`m3-hogs/run.sh`, `summary.txt`, `*.out`,
`*-boot-*.log`; the old arm swaps 2c231c9c's lib.sh in and the script restores HEAD's):

| arm | runs | result | Done -> "Stopping the server" |
|---|---|---|---|
| lib.sh of 2c231c9c (control) | 5 | 2 green, **3 red** (12/1, the same FAIL line as VERIFY) | red runs 1 s; green runs 4 s and 2 s (boot past the timer, settle path) |
| lib.sh of 42b11b10 | 5 | **5 green** (13/0 each) | 5, 5, 6, 5, 6 s; "20 server ticks observed" in every run |

Example (new run 1): Done 13:25:19, 20 ticks 13:25:20, stop 13:25:24. Old run 2: Done 13:23:37, stop 13:23:38, no ticks line.

## Item 1b: LibStopWhenReadyContractTest's settle check lost under load (fixed, different mechanism)

Not the lib.sh defect. The slow-boot case stamped Done's moment with a python started *after* the append, and the stop's moment
with another python after `stop_when_ready` returned; when the first start-up was slower than the second, the measured gap fell
below a settle lib.sh had given in full (1.999 s, G-C wave 2b). 3b9e69a2 stamps before the append, so the stamp can only be early.
Control (`flake/ctl.sh`, `ctl.out`, unchanged lib.sh, Done stamp slowed by 0.3 s): old order measures 1.717 and 1.752 s, new order
2.775 s twice. The new case of item 1 uses the same order.

## Item 1c: kill the whole gate on TERM (recorded, not fixed: a different mechanism)

G-B2's hand-off (lib.sh's `_gate_exit` should `kill_tree` every child of the gate shell and remove the recorded pid file, because
heavy.sh forwards TERM to the gate pid alone and 52 gates leave a background JVM, m39 a foreground evidence.py chain). That is signal
handling on exit, not the readiness timing above, so per the brief it is recorded only. Nothing in this lane touched `_gate_exit`.
Open, owner: orchestrator to assign (lib.sh owner). The measurements stand in `stage4-c5-g-b2.md` (wave 2b review fixes, A-2).

## Item 2: gate-m12 at -j 4 (scheduled alone; ceiling unchanged)

**Mechanism.** m12's tick-lateness check counts server ticks at twice the budget from the client's dial to the leave against a
derived ceiling (7 for 220 ticks). VERIFY run 1: red at -j 4 in the zh sweep (11 of 220) beside m0's unit suite and the boots of
m18, m19, m21, m24 (the same neighbours as the en sweep, 5 of 260 there); alone 1-3 late in 5 of 5; under ten `yes` hogs alone red
in one run of two. The ceiling measures host contention as well as the server, so a neighbour's CPU reads as the server falling
behind. Not raised (D34).

**Fix.** `alone` (no value) on a `# GATE-PARALLEL:` line makes a declared gate exclusive, the scheduler's existing mode for an
undeclared gate: it starts only when nothing runs and nothing starts beside it; rundirs and mem still parse; `alone=<x>` is refused;
progress.log names the alone gates apart from the undeclared ones (1e595872). gate-m12 declares it, with the reason in its header
(c291325c). Exclusive gates start when the queue has drained, so in a sweep m12 now runs at the end, alone (about one minute more
wall time; m37 and m38, undeclared, already ran that way).

**Tests** (GatesAllTest 5 -> 7, `alone/`):
- `aGateDeclaredAloneRunsWithNoOtherGateBesideIt`: five stub gates at -j 4 stamp when they ran; the `alone` one shares no moment
  with the others, which overlap each other (>= 3 pairs). Red on 2c231c9c's gates-parallel.py: "gate-m0 ran beside gate-m12, which
  is declared alone: gate-m0 0.00-2.02, gate-m1 0.00-2.02, gate-m12 0.00-1.02, gate-m13 0.01-2.03, gate-m14 1.27-2.30".
- `everyGateThatJudgesTickTimingIsDeclaredAlone`: any gate whose code (comment lines excluded) reads the tick sampler
  (`Forbric/Tick`, `tickSamplerEvery`) and declares itself must say `alone`; it must find gate-m12. Red with `alone` taken off
  m12's line ("[gate-m12-multiplayer.sh]"). Finds m12 only.
- Tip: 7/0. Intermediate 1e595872 (m12 still undeclared alone, the rule test not yet there): 6/0.
- test_gate_ports + test_lib_guards (python): 24 OK (`py-gate-ports-guards.log`). `/bin/bash -n` of every gate: clean.

**Scheduler replay** (`scheduler-replay/replay.py`, `old.txt`, `new.txt`, `subset-j2-*.txt`): stub gates carrying the real gates'
`# GATE-PARALLEL:` lines and VERIFY block 3's durations (scaled 1/30), through the real gates-parallel.py of each tree.
- -j 4, all gates but m34, budget 9011 MB: at 2c231c9c m12 ran beside m0, m11, m18, m19, m21, m24 (VERIFY's neighbours); at
  c291325c beside nothing, at the end. Wall 93.9 s vs 95.9 s scaled.
- -j 2, the subset of the sweep below: at 2c231c9c m12 ran beside m0; at c291325c beside nothing.

**Real sweep.** -j 4 needs 40 ports (stride 10) and this lane has 20 (46660-46679 is lane ZH's), so the sweep ran at -j 2 in the
lane's block on a subset: m0 (the unit suite), m1, m2, m3, m12, m18, m19, m21, m24 -- m3 and m12
plus every gate VERIFY saw beside m12 and the gates sharing m3's rundir (`gates-j2/run.sh`, `gates-all.out`, `gates-all-results/`,
`counts.txt`; `client.sh heavy.sh --exclusive bash run/compat/gates-all.sh -j 2 --skip <the other 52>`, GATE_PORT 46640, Fabric
canary built first, 14:36-14:47). On 2c231c9c's declarations this subset puts m12 beside m0 (replay `subset-j2-old.txt`).

| gate | PASS/FAIL/SKIP | VERIFY run 1 block 2 |
|---|---|---|
| m0 | 13/1/1 RED (D58: "unit tests -- 3 of 5877 skipped", the LiquidBounce three; SKIP = D64's C6 link check) | 13/1/1 |
| m1 | 19/0/0 | 19/0/0 |
| m2 | 29/0/0 | 29/0/0 |
| **m3** | **13/0/0** ("mod's ServerTickEvent listener FIRED (game loop) (1)") | 12/1/0 |
| **m12** | **44/0/0**, ran alone after m0 finished (+619 s to +676 s); "2 of 220 <= 7", handshake 0 of 60 | 44/0/0 (red 43/1 in block 3) |
| m18 | 20/0/0 | 20/0/0 |
| m19 | 40/0/0 | 40/0/0 |
| m21 | 54/0/0 | 54/0/0 |
| m24 | 30/0/0 | 30/0/0 |

progress.log: "declared 'alone' (a timing verdict), so each of these runs with no other gate: gate-m12-multiplayer.sh". Wall 11.3
min. Ports 46640-46659 free afterwards, no `.gate-clones`, no java left.

**Hog control, m12 alone** (ceiling unchanged): `m12-hogs/run.sh` (ten `yes` hogs, `client.sh heavy.sh --exclusive`,
`M12_LATE_CEILING` unset, stop at the first red): run 1 green 44/0 ("1 of 240 <= 7"); run 2 **red 43/1**, the one FAIL "server ticks
late from the client's dial to the leave: 17 of 220 at twice the budget (ceiling 7)" (17 of 140 while the player was on). The
ceiling still fires on a contended host; scheduling only keeps the sweep's own gates from being that contention.

## Suite at c291325c

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test --continue`
(13:30:46-13:40:02, `suite/suite.log`, `suite/xml/`, `suite/summary.txt`): **5877 tests, 0 skipped, 5 failed** = exactly the five
names of `failures-w2b.txt` (LB x3, SharedFinalSourceActualTest x2, D58; their messages are the same fixture-absent lines). 0 new,
0 gone. +4 tests = LibStopWhenReadyContractTest +2, GatesAllTest +2. No test was deleted or renamed except the one rename above
(`aBootInsideTheTimer...` -> `aBootDoneASettleBefore...`), which has no row in either TSV; the new cases have no skip path (no
assumption, no fixture: bash, python3 and the checked-in run/), so no `ci-*.tsv` row (D47).

## For other lanes and the merger

- No Java outside the two test classes, no TSV, no ForbricSwitches change. Files: run/lib.sh, run/compat/gates-parallel.py,
  run/compat/gates-all.sh (comment), run/gate-m12-multiplayer.sh (header), the two tests.
- Every `stop_when_ready` / `server_ready` caller now gets its settle after a Done that lands within `<settle>` seconds of its
  timer: such a run can be up to the settle (5 s default) longer. gate-m21's `server_ready ... && probe_commands` gets it too.
- gates-all (VERIFY run 2): m12 runs alone at the end of the sweep; progress.log says so. heavy.sh's header already lists m12 among
  the `--exclusive` runs.
- A lane editing a gate that reads the tick sampler must keep `alone` (GatesAllTest says so).

## Open

| item | owner |
|---|---|
| lib.sh whole-gate kill on TERM (`_gate_exit` kill_tree of the gate shell's children + pid file) | orchestrator to assign (lib.sh owner) |
| A real -j 4 sweep with m12 in it (needs a 40-port block; VERIFY run 2 has one) | VERIFY run 2 |
| Other time-based checks not declared alone: await_server's 20 s "still alive after announcing its stop" (m19 went red at -j 7 once, per gates-parallel's comment); not red at -j 4 in VERIFY run 1 | watch (orchestrator) |
| `build/sweep100-mac` link dangles in lane worktrees (main checkout has no such directory) | orchestrator |
| Load from outside the sweep (VERIFY saw a Wine steam.exe at ~265 % CPU) still reaches m12; the scheduler cannot quiet it | owner / whoever runs VERIFY |
