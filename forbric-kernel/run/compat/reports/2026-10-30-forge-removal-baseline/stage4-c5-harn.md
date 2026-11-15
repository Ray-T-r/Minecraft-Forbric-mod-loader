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
  line at 3-6 s. Green on both lib.sh versions: the unchanged half. (Review fixes, B-1: that window also passed a helper that
  always adds the settle after the timer, 4.1 s; the case now runs timer 3, settle 3 and accepts 3 s to under 5 s.)
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
| lib.sh of 2c231c9c (control) | 5 | 2 green, **3 red** (12/1, the same FAIL line as VERIFY) | red runs 1 s; green runs 4 s and 2 s (Done came inside the 22 s timer, 4 s and 2 s before its end, so the timer's end gave those ticks; corrected per review B-2) |
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
- `everyGateThatJudgesTickTimingIsDeclaredAlone` (renamed `everyGateWhoseVerdictIsATimingIsDeclaredAlone` and widened to
  `check_kept_up` in the review fixes): any gate whose code (comment lines excluded) reads the tick sampler
  (`Forbric/Tick`, `tickSamplerEvery`) and declares itself must say `alone`; it must find gate-m12. Red with `alone` taken off
  m12's line ("[gate-m12-multiplayer.sh]"). Finds m12 only.
- Tip: 7/0. Intermediate 1e595872 (m12 still undeclared alone, the rule test not yet there): 6/0.
- test_gate_ports + test_lib_guards (python): 24 OK (`py-gate-ports-guards.log`). `/bin/bash -n` of every gate: clean.

**Scheduler replay** (`scheduler-replay/replay.py`, `old.txt`, `new.txt`, `subset-j2-*.txt`): stub gates carrying the real gates'
`# GATE-PARALLEL:` lines and VERIFY block 3's durations (scaled 1/30), through the real gates-parallel.py of each tree.
- -j 4, all gates but m34, budget 9011 MB: at 2c231c9c m12 ran beside m0, m11, m18, m19, m21, m24 in this scaled replay; at
  c291325c beside nothing, at the end. Wall 93.9 s vs 95.9 s scaled. VERIFY's real neighbours of m12 were m0, m18, m19, m21 and
  m24: m11 went GREEN at +229 s and m12 started at +230 s (block 3 progress.log; corrected per review B-3).
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
- A lane editing a gate that reads the tick sampler or calls `check_kept_up` must keep `alone` (GatesAllTest says so); after the
  review fixes that is m12, m15 and m16.

## HARN review fixes (after reviews A and B of wave 2c)

Reviews: `c5/review-harn-w2c-A.md` (adversarial) and `c5/review-harn-w2c-B.md` (completeness). Evidence for this section:
`harn/w2c/review-fixes/` (`ab.out`, `*.xml`, `xml-summary.txt`, `lib.*` variants, `b5-method*.out`, `replay/`, `gates-j2/`,
`suite/`). Every finding was checked against the tree and the evidence before acting on it; none was refuted.

| finding | verdict | fix |
|---|---|---|
| A-F1 (medium) the `alone` rule missed `check_kept_up`; gate-m15 and gate-m16 call it and could run beside other gates | confirmed, and not only latent: introduction.md's gates section measured m15 red on it in two of three side-by-side `-j 5` rounds and m16 red beside the full suites; Merger wave 1 had m12 red on it beside the suite (walls.md); heavy.sh's header already called these gates load-sensitive | 72d9e45b: m15 and m16 declare `alone` (reason in their headers); the GatesAllTest rule, renamed `everyGateWhoseVerdictIsATimingIsDeclaredAlone`, finds a gate by a non-comment `check_kept_up` call as well as by the tick sampler and must find m12 by both; gates-parallel.py and gates-all.sh say so. Option (a) of the review, since (b) would have argued against measured reds |
| A-F2 / B-4 (low) developer docs do not mention `alone` | confirmed | 3f307726: one passage each in forbric-kernel/README.md, README.zh-CN.md, run/compat/PROTOCOL.md. introduction.md and its zh-CN twin describe main (CLAUDE.md): they still say only that a gate without the line runs alone, which stays true; they get `alone` when this reaches main |
| A-F3 (low) upper bound of 10 s in `aBootThatEndsJustBeforeTheTimerStillGetsItsSettle` | confirmed: the line is due at about 5 s (Done first seen at the 2 s step, settle 3); a settle counted twice ends at about 8 s and passed | 61592546: `< 7500` ms |
| A-F4 (nit) leading zeros | confirmed: on bash 3.2.57 `stop_when_ready <log> 1 08` prints "08: value too great for base" and the shell exits 1; "1 01" is read as octal and writes "stop" | b71424f2: the guard refuses a leading zero on stderr, as it refuses a fraction ("0" stays allowed); new case `aTimerOrSettleWithALeadingZeroIsRefusedOutLoud` |
| A-F5 (nit) lib.sh header contradicts itself | confirmed | b71424f2: "With a boot done <settle> or more seconds before the timer ends ..." |
| B-1 (medium, D34) the rewritten first case no longer catches a helper that waits too long | confirmed: with timer 3, settle 1 and a 3-6 s window, a helper that always adds the settle after the timer wrote at 4.1 s and passed | 61592546: timer 3, settle 3, Done in the log from the start, window 3 s to under 5 s |
| B-2 (low, record) m3-hogs green runs misread | confirmed: old runs 1 and 3 had Done at 13:23:09 and 13:24:01, inside the 22 s timer, 4 s and 2 s before its end; the old settle path always waited the full 5 s | corrected in Item 1's table above |
| B-3 (low, record) m11 named among VERIFY's neighbours | confirmed: VERIFY block 3 progress.log has m11 GREEN at +229 s and m12 START at +230 s; m11 sat beside m12 only in the scaled replay | corrected in Item 2's replay paragraph above |
| B-5 (info) the slow-boot case sometimes misses a settle one second short | confirmed as described (pre-existing, widened slightly by 3b9e69a2's python start-up) and fixed although optional | 61592546: Done is stamped by the one python that appends it, just before the write; the line by a python already reading the helper's stdout, just after it arrives |

**Controls** (`ab.sh`, `ab.out`, `*.xml`, `xml-summary.txt`; each a one-line lib.sh change or one header line, through Gradle with
the pinned props, heavy slot 1, 16:10-16:13). Tip: LibStopWhenReadyContractTest 12/0, GatesAllTest 7/0.

| control | red | green |
|---|---|---|
| c1 lib.sh always adds the settle after the timer (`rest=$settle`) | `aBootDoneASettleBefore...` only ("6043 ms") | the other 11 |
| c2 lib.sh counts the settle twice | `aBootDoneASettleBefore...` (6055 ms) and `aBootThatEndsJustBefore...` ("and not much later either (8078 ms)") | the other 10 |
| c3 lib.sh settle one second short | `aBootThatEndsJustBefore...` (2.513 s) and `aBootSlowerThanTheTimer...` (1.036 s) | the other 10 |
| c4 lib.sh of b87bfe63 (no leading-zero guard) | `aTimerOrSettleWithALeadingZeroIsRefusedOutLoud` only: "stop", then "08: value too great for base" | the other 11 |
| c5 gate-m15 without `alone` | `everyGateWhoseVerdictIsATimingIsDeclaredAlone` only: "[gate-m15-forge-network.sh]" | the other 6 |

B-5 method check outside Gradle (`b5.sh`, `b5-method.out`, `b5-method-tip.out`): the slow-boot case's shell, 12 runs per stamping
method against the c3 lib.sh: b87bfe63's stamping red 10 of 12 (the two greens measured 2.026 and 2.045 s), the new stamping red 12
of 12 (1.021-1.054 s). Against the tip lib.sh both methods are green 6 of 6 (new 2.014-2.042 s). What is left of the window is the
helper's own per-poll overhead (a grep, a wc and the sleep's wake-up), milliseconds per step.

**Scheduler replay** (`replay/replay3.py`, a copy of wave 2c's replay that reports m12, m15 and m16; real `# GATE-PARALLEL:` lines,
VERIFY block 3 durations scaled 1/30, -j 4, all gates but m34). b87bfe63: m12 beside nothing; m15 beside m0, m19, m24b, m24c, m25;
m16 beside m0, m24c, m25, m28. Tip 3f307726: m12, m15 and m16 each beside nothing, one after another at the end; progress.log names
all three as declared alone. Wall 95.8 s vs 98.1 s scaled.

**Real sweep, -j 2 subset** (`gates-j2/run.sh`, `gates-all.log`, `gates-all-results/`, `counts.txt`; tip 3f307726, clean tree,
lane block 46640-46659, Fabric canary built first, `client.sh heavy.sh --exclusive bash run/compat/gates-all.sh -j 2 --skip <the
other 55>`, 16:13-16:24). Subset: m0 (the unit suite, the neighbour VERIFY saw beside m12), m3 (lib.sh's helper), m14 (m15's
neighbour in the b87bfe63 replay) and the three alone gates. progress.log: "declared 'alone' (a timing verdict), so each of these
runs with no other gate: gate-m12-multiplayer.sh gate-m15-forge-network.sh gate-m16-forge-handshake.sh"; m0 and m3, then m0 and
m14, ran two wide; m12 started at +550 s when m0 ended, then m15 at +609 s, then m16 at +644 s, each with nothing beside it.

| gate | PASS/FAIL/SKIP | VERIFY run 1 block 2 | timing verdict |
|---|---|---|---|
| m0 | 13/1/1 RED (D58: "unit tests — 3 of 5878 skipped", the LiquidBounce three) | 13/1/1 | |
| m3 | 13/0/0 | 12/1/0 | |
| m12 | 44/0/0 | 44/0/0 | "2 of 240 <= 7"; kept up, 0 overload warnings |
| m14 | 34/0/0 | 34/0/0 | |
| m15 | 22/0/0 | 22/0/0 | kept up, 0 overload warnings |
| m16 | 39/0/0 | 39/0/0 | kept up, 0 overload warnings |

**Suite at 3f307726** (`suite/suite.sh`, `suite.log`, `xml/`, `summary.txt`; the required command through heavy slot 1,
16:24-16:32): **5878 tests, 0 skipped, 5 failed**, name for name the five of `failures-w2b.txt` (LiquidBounce x3,
SharedFinalSourceActualTest x2, D58): 0 new, 0 gone. +1 test against wave 2c = `aTimerOrSettleWithALeadingZeroIsRefusedOutLoud`;
the rule test's rename has no TSV row (it was new in wave 2c), and the new case has no skip path, so no `ci-*.tsv` row (D47).
python test_gate_ports + test_lib_guards 24 OK (`py-gate-ports-guards.log`); `/bin/bash -n` of lib.sh and every gate clean.
After the runs: ports 46640-46659 free, no `yes` and no lane java left, no `.gate-clones`, tree clean; scratch replay dirs deleted.

| sha | what |
|---|---|
| b71424f2 | lib.sh: a leading zero in the timer or settle is refused (F4); header sentence fixed (F5); new contract case |
| 61592546 | LibStopWhenReadyContractTest: first case timer 3 / settle 3 / under 5 s (B-1), 7.5 s bound (F3), stamps without start-ups (B-5) |
| 72d9e45b | gate-m15 and gate-m16 declare `alone`; the GatesAllTest rule covers `check_kept_up` (F1) |
| 3f307726 | `alone` in forbric-kernel/README.md, README.zh-CN.md, run/compat/PROTOCOL.md (F2/B-4) |
| (this file) | record, B-2 and B-3 corrections |

For the merger: files touched by the review fixes are run/lib.sh (guard + comment), run/gate-m15-forge-network.sh and
run/gate-m16-forge-handshake.sh (header only), run/compat/gates-parallel.py and gates-all.sh (comments only), the two test classes,
and the three docs. A sweep now runs three gates alone at its end (m12, m15, m16; each 35-60 s here).

## Open

| item | owner |
|---|---|
| lib.sh whole-gate kill on TERM (`_gate_exit` kill_tree of the gate shell's children + pid file) | orchestrator to assign (lib.sh owner) |
| A real -j 4 sweep with m12 in it (needs a 40-port block; VERIFY run 2 has one) | VERIFY run 2 |
| Other time-based checks not declared alone: await_server's 20 s "still alive after announcing its stop" (m19 went red at -j 7 once, per gates-parallel's comment); not red at -j 4 in VERIFY run 1 | watch (orchestrator) |
| `build/sweep100-mac` link dangles in lane worktrees (main checkout has no such directory) | orchestrator |
| Load from outside the sweep (VERIFY saw a Wine steam.exe at ~265 % CPU) still reaches m12, m15 and m16; the scheduler cannot quiet it | owner / whoever runs VERIFY |
| `# GATE-PARALLEL:` parsing ignores an unknown word, so a misspelt `alone` on a gate the rule does not cover would run in parallel unnoticed (review A, noted, not a wave 2c defect). Refusing unknown words needs a format for gate-m0's trailing prose "(builds and runs the unit suite)", which the parser skips silently today | orchestrator to assign (gates-parallel owner) |
| m12's late-tick ceiling has no native baseline: "alone vs -j 4" fits "Forbric falls behind more readily than native NeoForge under CPU pressure" as well as "the ceiling measures neighbours"; telling them apart needs a native NeoForge server + client session under the same load (review A, out of scope) | orchestrator |
| introduction.md and introduction.zh-CN.md still describe the GATE-PARALLEL line without `alone` (still true as written); add it when this reaches main (CLAUDE.md) | merger to main |
