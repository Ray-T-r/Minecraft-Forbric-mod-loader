# Stage 4 C5 — lane HARN2 (gate harness: check_kept_up's window, GATE-PARALLEL vocabulary, stopping a killed gate), wave 2d

Branch `s4-c5-harn2`, cut from the stage tip 421c53cc (worktree `.claude/worktrees/s4c5-harn2`, ports 46640-46659 from
`.stage4/lane-env.sh`). Notes and logs: `~/Forbric-backups/stage4-notes/c5/harn2/w2d/` (paths below are relative to it).
Harness: D68 (pinned build, plain `--offline` from `.stage4/props.txt`, no init script, no private Gradle home); every Gradle and
game run through `.stage/heavy.sh` (the m12/m16 sweep `--exclusive` under `.stage/client.sh`). Nothing was downloaded, no zh run
(D88b). Per D87 only the touched tests and gates ran, the full suite once at the end; no gates-all.

## Worktree

Bootstrap reported 2 PROBLEMs. `.stage4/minecraft` was the relative `../.stage/minecraft` (dangling in a lane tree): re-linked to the
absolute `suspicious-chaum-b28730/.stage/minecraft`. `forbric-kernel/build/sweep100-mac` dangles (the main checkout has no such
directory): not re-created, nothing here reads it -- open since wave 2c, orchestrator. `jar` built (`jar.log`).

## Commits (on 421c53cc)

| sha | what |
|---|---|
| 983860d6 | lib.sh `check_kept_up` counts "Can't keep up!" only inside player sessions; LibKeptUpContractTest +4 |
| b8151378 | gates-parallel.py refuses an unknown GATE-PARALLEL word (prose after ` # `); gate-m0's note moved; GatesAllTest +1, its alone rule reads the same words; README en/zh, PROTOCOL.md, gates-all.sh |
| 3bf36580 | lib.sh `_gate_exit` stops what the gate started; gates-parallel.py passes TERM/INT/HUP on to its running gates; new LibGateExitTest (3), GatesAllTest +1 |
| (this file) | record |
| 96ffd2fb, 9248eb01, 2368974a | review fixes (below) |

Each code commit compiled and had its tests run on its own tree.

## Item 1 (C-1): check_kept_up only while a player is on (fixed)

*The join..leave window below was replaced in the review fixes (A-1): it now runs from the client's dial to the end of the log.*

**Cause.** `check_kept_up` counted every "Can't keep up" in the server log; gate-m12 and gate-m16 print "server kept up while the
player was on". VERIFY run 2 block 4's m12-2 went red on one warning 17 s before the join, during the client's boot beside the server
(verify2/check.md C-1).

**Fix (983860d6).** A session opens at the server's `logged in with entity id` and closes at `<name> left the game`; sessions are
counted (two overlapping players keep it open until the last leaves); a session with no leave runs to the end of the log (a missing
leave can only count more). Warnings outside every session are printed (`[kernel] N overload warning(s) with no player on ...; not
asserted`), not asserted; the worst named is the worst inside a session. `lost connection` does not close a session: vanilla prints
it just before `left the game` for the same leave.

**Tests** (LibKeptUpContractTest 6 -> 10): before-join/after-leave not counted; in-session red and the worst named from inside;
no leave runs to the end; separate vs overlapping sessions.
- 421c53cc's lib.sh: 4 of 10 fail (`c1-control.log`); tip 10/10 (`c1-tip.log`).
- Mutants: closing also on `lost connection` -> `aSessionWithNoLeaveRunsToTheEndOfTheLog` red (`c1-mut-lostconn.log`); a flag
  instead of a count -> `eachSessionIsAWindowOfItsOwn` red (`c1-mut-boolean.log`).
- Replay on VERIFY run 2's five real m12 server logs, 421c53cc vs tip (`c1-real-logs.txt`): block4 m12-2 RED -> GREEN (its one
  warning 21:51:15, join 21:51:31); block4 m12-1, block3 m12-2-en, block3 m12-red stay RED (warning inside the session);
  block3 m12-3-zh stays RED with 1 in-session warning (21:23:38) instead of 2, the 21:22:19 one now printed as outside.

**Gates, -j 4** (`sweep-j4/`, `sweep-j4.out`; `client.sh heavy.sh --exclusive bash run/compat/gates-all.sh -j 4 --only gate-m12...
--only gate-m16... --no-cache`, lane env, GATE_PORT 46640): both `alone`, each ran by itself on slot 0.

| gate | PASS/FAIL | check_kept_up line |
|---|---|---|
| m12 | 44/0 GREEN (53 s) | PASS ... (1 player session(s), 0 overload warnings while a player was on); tick lateness 1/440 before the dial, 0/60 handshake, 1/160 session |
| m16 | 49/0 GREEN | PASS ... (1 player session(s), 0 overload warnings while a player was on) |

Both judged the session (the login check passed, the denominator is there). Neither printed an outside-session warning in this run,
so the GREEN is not the new window's doing; the window's effect on a real log is the replay above.

## Item 2: GATE-PARALLEL rejects unknown words (fixed)

**Cause.** `Gate._parse` read `rundirs=`, `clone=`, `mem=`, `alone` and skipped anything else silently: a misspelt `alon` or
`rundir=` ran a gate beside others with nothing said; gate-m0's `(builds and runs the unit suite)` was five ignored words.

**Fix (b8151378).** Comment syntax first: a `#` at the start of the declaration body or after whitespace runs to the end of the line
(`# GATE-PARALLEL: rundirs=server-m0-definitions mem=3500  # builds and runs the unit suite`). Then any other word, a key given twice,
an empty `rundirs=`, a non-integer `mem=`, `alone=<anything>` and a second declaration line stop the sweep before any gate starts
(SystemExit naming the gate and the word). GatesAllTest's "timing gates say alone" rule strips the comment the same way.
All 61 real gates parse (`c2-real-gates.txt`: the tip parser lists 61; with 421c53cc's gate-m0 line it stops on `'(builds'`, which
GatesAllTest's real-glob listing would catch).

**Tests.** `aDeclarationWordTheSchedulerDoesNotKnowStopsTheSweep`: seven wrong lines each exit non-zero, name gate and word, and no
stub gate ran; a line whose comment says `alone` runs GREEN and is not run alone. 421c53cc's gates-parallel.py: red (the `alon` sweep
ran, `c2-control.log`); on that parser the comment `# not alone here, this is prose` set alone=True (`c2-real-gates.txt`). Tip:
GatesAllTest 8/8 (`c2-tip2.log`), python test_gate_ports/test_gate_locale/test_gate_cache/test_control_arm 56/56 (`c2-py.log`).

## Item 3: a killed gate stops what it started (fixed; descendants, not the process group)

**Cause.** heavy.sh forwards TERM to the gate pid; bash runs the EXIT trap (`_gate_exit`) on an untrapped TERM at once, even with a
foreground child, but the trap only reported, so the server/client/evidence.py were re-parented to launchd (G-B2 wave 2b, walls.md).

**Why not the process group (the brief's wording).** Measured: a gate's pgid is its launcher's (heavy.sh runs it with `cmd &` in a
non-interactive shell; gates-parallel.py with a plain Popen) -- the probe script's children all carried the calling shell's pgid. A
group kill from the gate would kill heavy.sh, the scheduler and the gates running beside it. Inside the EXIT trap every process the
gate started is still its descendant (bash re-parents them only after it exits; `pgrep -P $$` in the trap listed the background child,
the subshell and the foreground child), so the descendant trees are the whole gate. A detached Gradle daemon is not the gate's and is left alone (once its Gradle
client has exited; review fixes, B-6). `$?` in the trap is 0 after a TERM, so the trap cannot tell a kill from a normal end; it stops leftovers on every exit
(anything running then is an orphan from that moment), before the caught report, and prints what it stopped.

**Fix (3bf36580).**
- lib.sh `_stop_leftovers`, called first in `_gate_exit`: freeze each child's tree (STOP, parents first), TERM all, CONT all, 2 s grace,
  `kill_tree` what is left; `[kernel] the gate left N process(es) running at its exit; stopping them: <pid cmd>...` (not a FAIL).
  Subshells and command substitutions do not run the EXIT trap (checked on bash 3.2.57), so it runs in the main gate shell only.
- gates-parallel.py: the sweep had the same hole one level up (gates-all.sh execs it; Python died of the TERM and its gates ran on
  alone). TERM/INT/HUP are now recorded; the loop stops starting gates, sends each running gate TERM, waits up to 20 s
  (`GATE_TERM_GRACE`; then kills the gate's tree), exits 128+signal, records no verdict and no cache entry.

**Tests** (`c3-*.log`).
- LibGateExitTest (new): TERM with a background child, a subshell grandchild and the foreground command -> all three gone and each
  asked with TERM first; a normal `exit 3` with a child left -> child gone, exit 3 kept; subshell/command substitution ending stops
  nothing.
- GatesAllTest `aTermToTheSweepStopsItsRunningGatesAndWhatTheyStarted`: TERM to gates-all.sh -> the gate's stand-in gone, exit 143,
  progress.log `SIGTERM: stopping gate-m1.sh`, the gate log's stop line.
- Controls: both files at 421c53cc -> 4 of 12 red (both LibGateExitTest kill cases, the sweep TERM case, item 2's case);
  421c53cc's lib.sh with the tip scheduler -> the sweep case red; the tip lib.sh with 421c53cc's scheduler -> the sweep case and
  item 2's declaration case red (2 of 9, `c3-control-oldsched.log`; said "the sweep case" until the review fixes, B-1).
  Tip: Lib* + GatesAllTest 51/51 (`c3-tip.log`).

**Real gate** (`termdemo-tip/`, `termdemo-control421/`; `termdemo.sh`: gate-m1 under heavy.sh, TERM to heavy.sh 8 s after the server
JVM appeared, then look at 1/5/15 s):
- tip: heavy exit 143; the gate log says it stopped 4 processes (the server subshell, the java server, two `sleep 1`); no java of
  this worktree at 1 s, 5 s, 15 s; nothing on 46640.
- 421c53cc lib.sh: heavy exit 143; the java server still running at 1 s and 5 s, listening on 46640 at 5 s; gone by 15 s (the
  transient G-B2 measured; it ended when its stdin feeder closed). The script killed anything left at the end.

## Suite

At 3bf36580 (code-identical to this record's commit): `heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt)
-Pforbric.requireFixtures=all jar cleanTest test --continue`, 4 min 5 s (`suite.log`, `suite-summary.txt`): **5902 tests, 0
skipped, 5 failed** -- name for name the five D58 rows of `c5/failures-w2c.txt` (LiquidBounce x3, SharedFinalSourceActual x2),
**0 new**. 5893 at 421c53cc + LibKeptUpContractTest 4 + GatesAllTest 2 + LibGateExitTest 3 = 5902.

## For other lanes / the merger

- Files: run/lib.sh (check_kept_up, _gate_exit + _stop_leftovers), run/compat/gates-parallel.py (parser, signal handling, loop now a
  nested `schedule()`), run/gate-m0.sh (declaration line), run/compat/gates-all.sh (comment), README.md, README.zh-CN.md,
  run/compat/PROTOCOL.md, GatesAllTest, LibKeptUpContractTest, new LibGateExitTest. No Java outside tests, no TSV row (no new test
  skips: none reads a fixture).
- Any lane adding a `# GATE-PARALLEL:` line: only `rundirs= clone= mem= alone`; notes after ` # `. Anything else stops the sweep.
- A gate that means to leave a process running past its own exit now has it stopped (and the log says so). Neither m12 nor m16 left
  one on a normal run; m1 was only TERM'd here, its normal run is in the review fixes (B-3).
- gate-m0's script changed, so the verdict cache re-runs m0 (and every gate reading lib.sh is re-keyed by lib.sh's change).

## Open items

- `build/sweep100-mac` dangles in lane worktrees -> orchestrator (since wave 2c).
- A gate that traps TERM itself (m28, m33, m35, m40: `trap 'exit 1xx' ... TERM`) runs that trap only after its foreground command
  returns. Rewritten in the review fixes (B-2): their long phases are a background child + `wait`, which a TERM ends at once; only a
  foreground command (m28's polls, a foreground python/grep) delays it, by that command's length; under a sweep the scheduler kills
  after 20 s -> lib.sh owner.
- The scheduler's group/session is unchanged (gates still share it); a process a gate detached before the TERM (re-parented already)
  is not reached by either fix. None found in the gates; not measured.
- No gates-all at this tip (not in the brief); the next VERIFY's sweep is the first to run every gate on the new lib.sh exit path.

## HARN2 review fixes

Reviews: `c5/review-harn2-w2d-A.md` (Lens A) and `c5/review-harn2-w2d-B.md` (Lens B). Evidence for this section:
`~/Forbric-backups/stage4-notes/c5/harn2/w2d/rf/`. Each finding was checked before it was acted on; fixes are three commits on
63e561cc, and the sections above are corrected in place where a finding said they were wrong (marked "review fixes").

| finding | verdict | commit |
|---|---|---|
| A-1 (blocking) check_kept_up's join..leave window misses the configuration phase and the leave's tick | confirmed | 96ffd2fb |
| A-2 the leave match is unanchored (a chat line saying "left the game" closed the window) | confirmed; gone with A-1 (no leave boundary any more) | 96ffd2fb |
| A-3 the pid file outlives its server; the next reap SIGKILLs whatever has the pid | confirmed (run/server-kernel's file named the termdemo's dead server 25686) | 9248eb01 |
| A-4 an empty GATE-PARALLEL line is "declared" | confirmed, and worse than reported (below) | 2368974a |
| A-5 2 s grace is short for a server's save | plausible, not reproduced on a gate; raised to 10 s | 9248eb01 |
| B-1 checks.md "old scheduler 1" | confirmed: 2 red (the sweep case and item 2's case); notes and Item 3 corrected | notes |
| B-2 "nothing bounds the wait" for self-trapping gates is too strong | confirmed by reading m28/m33/m35/m40 against B's probe (0.5 s with a background child + `wait`, 6.1 s behind a 6 s foreground command); open item rewritten | record |
| B-3 "None of m1 ... left one on a normal run" had no m1 normal run | confirmed; m1 now run normally (below): 19/0, nothing left | record |
| B-4 introduction handoff lacks the GATE-PARALLEL rule | confirmed; added to handoff.md's main-merge item | notes |
| B-5 exit path only tested on bash 3.2 | confirmed; stated below, for the C5 close CI | record |
| B-6 "a Gradle daemon is left alone" too broad; merge-w2c's daemon item unaddressed | confirmed (from code); lib.sh comment narrowed; item stays open, orchestrator | 9248eb01 |
| B-7 an interrupted sweep leaves an older summary.txt | confirmed (not a regression); the scheduler removes it at start | 2368974a |
| B-8 "17 s" is 16 s | confirmed; lib.sh and the test say 16 s before the join, 13 s before the dial; 983860d6's message not rewritten | 96ffd2fb |
| B-9 sweep.sh / termdemo.sh were written after their runs | confirmed; their content matches the outputs, they are reconstructions, not snapshots. rf/sweep.sh was written before its run | record |

**A-1 / A-2 (96ffd2fb).** Checked against the base: "logged in" is printed at the end of PlayerList.placeNewPlayer, after the
configuration phase; the overload warning is printed at the top of runServer's loop, before processPacketsAndTick, so it reports the
tick before; removePlayerFromWorld broadcasts "left the game" before PlayerList.remove (logged-out event, player save). So both cut
stretches were real, and the logout tick's warning can come any number of lines after the leave with no marker for that tick's end.
The window is now the client's first "Connecting to" (its second, from the client log both callers already have) to the end of the
server log -- the same opening as gate-m12's tick window; nothing after "Stopping the server" can be a tick warning. Before the dial
is printed, not asserted (C-1 stays fixed). A missing client log, no dial, or a dial stamped before the server's first line by less
than 12 h (a stale client log) counts the whole log; a midnight crossing is handled as in gate-m12. No leave line is read, so A-2
has nothing left to match. Labels: "server kept up from the client's dial on".
- LibKeptUpContractTest 10 -> 13 (`tip2-xml.txt`). On 63e561cc's lib.sh (`control-63e.log`, `control-63e-failures.txt`) the dial-to-join,
  after-leave / chat-line, between-sessions and no-usable-dial cases are red on the verdict (exit 0 where 1 is due); before-dial,
  worst-named and midnight are red only on the new wording (63e561cc already left pre-join out). On 421c53cc's lib.sh
  (`control-421-kept.log`, `control-421-kept-failures.txt`) the before-dial case is red on the verdict (exit 1): C-1's guard holds.
- Real logs (`a1-real-logs.txt`): VERIFY run 2's m12 logs give the same verdicts as wave 2d's window -- block4 m12-2 GREEN (warning
  21:51:15, dial 21:51:28), m12-1 / m12-2-en / m12-red RED, m12-3-zh RED on 21:23:38 with 21:22:19 (before its 21:23:23 dial) printed.

**A-3 (9248eb01).** record_server_pid remembers its file; `_gate_exit` removes each one whose pid no longer runs, after
`_stop_leftovers`. reap_stale_server leaves a process alone when it started more than 2 s after the file was written (`ps -o etime`
against the file's mtime, GNU or BSD stat): a reused pid. Where an age cannot be read it behaves as before. A record_server_pid in a
subshell cannot remember its file; the age check covers it.

**A-5 (9248eb01).** Measured in the notes: 386 gate server logs, "Stopping the server" to the last line 0 s (310), 1 s (75), 2 s (1)
by one-second stamps. A 2 s grace could cut the slowest short; `_stop_leftovers` now waits up to 10 s, ending as soon as everything
is gone, and below the scheduler's 20 s. A knob was tried and dropped: test_gate_cache's rule that every variable lib.sh reads is
keyed or named as no input caught it.
- LibGateExitTest 3 -> 6, each new case red on 63e561cc (`control-63e-failures.txt`): pid file gone after a TERM'd gate (stayed);
  a pid file older than its process does not get it reaped and a real leftover still is (the younger one was reaped); a 4 s shutdown
  after TERM finishes (killed at 2 s).

**A-4, B-7 (2368974a).** A line with no word (empty, or prose only) now stops the sweep. Writing its test found more: the
declaration pattern `^#\s*GATE-PARALLEL:\s*(.*)$` with re.M let `\s*` run over the end of an empty line, so the NEXT line was read
as the declaration (`touch x` came back as the unknown word 'touch'; a following line of the form `rundirs=... mem=...` would have
been taken as the gate's). `[ \t]*` now, in gates-parallel.py and GatesAllTest's alone rule; 61 real gates parse. B-7: summary.txt
is removed when a sweep starts. GatesAllTest: the two wrong lines and the stale-summary assertion are red on 63e561cc's scheduler.

**Gates whose reach changed** (`rf/sweep.sh`, `rf/sweep/`, `rf/sweep.out`; `client.sh heavy.sh --exclusive gates-all.sh -j 4 --only
gate-m1.sh --only gate-m12... --only gate-m16... --no-cache`, lane env): m12 44/0 and m16 49/0, both alone on slot 0, each "PASS server
kept up from the client's dial on (1 player session(s), 0 overload warnings ...)" with the dial found (no whole-log note); m1 19/0
(record_server_pid / reap path, and B-3's normal run). No gate printed "the gate left"; run/server-kernel's stale pid file (25686,
dead) was there before and none of the three rundirs has one after. No pre-dial warning in this run, so its GREEN does not exercise
the window; the window's evidence is the contract tests and the real-log replay.

**Tests and suite.** Lib* + GatesAllTest 57/57 at the tip (`tip2.log`, `tip2-xml.txt`); python test_gate_ports / locale / cache /
control_arm / lib_guards / native_controls / forge_swap / fabric_ab all OK (`py.log`, the cache test re-run after the knob was
dropped). Full suite: at 2368974a, `heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test
--continue`, 6 min 11 s (`rf/suite.log`, `rf/suite-summary.txt`): **5908 tests, 0 skipped, 5 failed**, name for name the five D58 rows of
`c5/failures-w2c.txt` (LiquidBounce x3, SharedFinalSourceActual x2), **0 new**. 5902 at 63e561cc + LibKeptUpContractTest 3 +
LibGateExitTest 3 = 5908 (GatesAllTest's new cases are rows inside existing tests). No gates-all (not in the brief).

**Corrections to the sections above.** Item 3's controls: 421c53cc's scheduler with the tip lib.sh is red on 2 cases (the sweep case
and item 2's declaration case), not 1 (B-1). "None of m1, m12, m16 left one" was an m12/m16 statement; m1's normal run is above (B-3).
The intermediate commits 96ffd2fb and 9248eb01 were not tested on their own trees; the tip was.

**Open (review fixes).**
- bash 3.2.57 only: the exit path, the reap age check (`stat -c` then `stat -f`, `ps -o etime`) and their tests ran here only; CI is
  ubuntu bash 5 and first runs them when C5's close pushes a ci/ branch (B-5) -> orchestrator.
- merge-w2c's "kernel_jar daemon outlives a sweep" is not addressed by this lane; a daemon whose Gradle client is still running at a
  TERM is stopped with the gate, one whose client has exited is not (read from code, not measured) -> orchestrator (B-6).
- Self-trapping gates (m28/m33/m35/m40): their trap waits only for the foreground command; their long phases are background +
  `wait` and exit at once (B's probe: 0.5 s); unbounded only behind a foreground command (m28's polls, a foreground python/grep) under
  heavy.sh alone; under a sweep the scheduler kills after 20 s -> lib.sh owner (B-2).
- A record_server_pid in a subshell still leaves its file; the reap's age check makes it harmless, not absent.
