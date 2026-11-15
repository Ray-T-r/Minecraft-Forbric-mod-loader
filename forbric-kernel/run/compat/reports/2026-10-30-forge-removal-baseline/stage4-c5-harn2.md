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

Each code commit compiled and had its tests run on its own tree.

## Item 1 (C-1): check_kept_up only while a player is on (fixed)

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
the subshell and the foreground child), so the descendant trees are the whole gate. A detached Gradle daemon is not the gate's and is
left alone. `$?` in the trap is 0 after a TERM, so the trap cannot tell a kill from a normal end; it stops leftovers on every exit
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
  421c53cc's lib.sh with the tip scheduler -> the sweep case red; the tip lib.sh with 421c53cc's scheduler -> the sweep case red.
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
- A gate that means to leave a process running past its own exit now has it stopped (and the log says so). None of m1, m12, m16 left
  one on a normal run.
- gate-m0's script changed, so the verdict cache re-runs m0 (and every gate reading lib.sh is re-keyed by lib.sh's change).

## Open items

- `build/sweep100-mac` dangles in lane worktrees -> orchestrator (since wave 2c).
- A gate that traps TERM itself (m28, m33, m35, m40: `trap 'exit 1xx' ... TERM`) runs that trap only after its foreground command
  returns; under a sweep TERM the scheduler then kills it after 20 s with its tree. Under heavy.sh alone nothing bounds that wait.
  Not measured on those gates -> lib.sh owner, if it matters.
- The scheduler's group/session is unchanged (gates still share it); a process a gate detached before the TERM (re-parented already)
  is not reached by either fix. None found in the gates; not measured.
- No gates-all at this tip (not in the brief); the next VERIFY's sweep is the first to run every gate on the new lib.sh exit path.
