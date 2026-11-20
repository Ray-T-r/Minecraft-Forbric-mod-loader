# Stage 4 C6: three harness findings of the acceptance re-run's check (lane cifix)

The check of the acceptance re-run (`stage4-c6-accept-rerun.md`; its findings are in
`~/Forbric-backups/stage4-notes/c6/m34-survival/check-rerun.md`) predicted two CI reds from reading (C-2, C-3) and found
a gate that prints another run's PASS lines (R-3). This lane fixed the three. Branch `s4-c5-cifix`, cut from the stage
tip 7d87e4b7; code commits 8ba24bca (C-2, C-3), c44037e8 (R-3), ae922bf3 (the protocol sentence for the new test), and
14bf179d, the repair after two reviews (the last section; it corrects two sentences of R-3 and changes the soak's stop
path and gate-m17's phase U again). Harness and tests only: nothing under `src/main` or `src/runtime` changed, so the suite and gates-all measured at
ce423a9d still describe the kernel. Nothing was pushed, `main` was not touched. Evidence (`<ev>` below):
`/Users/jerry/Forbric-backups/stage4-notes/c6/cifix/`.

| Item | Result |
|---|---|
| C-2, POSIX-only signals in the soak's stop path | fixed; measured on macOS; **not measured on Windows** (the machine was unreachable for the whole lane) |
| C-3, seven tests missing from `ci-unstaged.tsv` | fixed; the red and the fix measured on a clean clone without game files |
| R-3, gate-m17 reads an earlier run's files | fixed; a network-free red control before and after, then gate-m17 for real: 164 PASS / 0 FAIL |
| Review: 15 findings, none blocker or major | 9 repaired, corrected or documented; 4 confirmed; 2 left with reasons; gate-m17 again 164 PASS / 0 FAIL; Windows still not measured |

## C-2. The stop path named signals Windows does not have

The `development-tools` job runs `python tools/dev.py tool-test` on windows-latest, and that runs `unittest discover -s
run/compat`. Commit a69b8643 added code that only exists on POSIX:

- `soak-run.py` and `retention-control.py` read `signal.SIGHUP`; Windows has none, and only `ValueError` was caught.
- `test_soak.py` sent SIGTERM to its own process with `os.kill`, which on Windows is `TerminateProcess` on the test
  runner; one test patched `os.killpg` without `create=True`.
- (found here) `test_soak.py` read `run/launch-kernel-server.sh` in the locale's encoding. cp1252 decodes it, GBK does
  not, so a Chinese-locale Windows fails where CI's would not. It is read as UTF-8 now.

What changed:

- `soak-run.py stop_signals()` takes each of SIGTERM, SIGHUP, SIGBREAK only where the `signal` module has it: SIGTERM and
  SIGHUP on macOS and Linux, exactly as before; SIGTERM and SIGBREAK on Windows. `retention-control.py STOP_SIGNALS` is
  the same list, and a test holds the two together.
- `signal_owned_group(process, force=False)` still stops the client through its process group (SIGTERM, or SIGKILL when
  forced). Where this Python has no `os.killpg` it ends the tree with `taskkill /F /T`, the path
  `tools/nightly/run_nightly.py` and `run/compat/win/common.py` already use. The choice is made by whether `os.killpg`
  exists, not by the system's name, so the older tests that model the POSIX group API on Windows keep working.
- `test_soak.py` delivers a stop signal the way the system does (`os.kill` on POSIX, `signal.raise_signal` on Windows)
  and does it for every stop signal, not only SIGTERM. The one behaviour that cannot exist on Windows, a SIGTERM
  delivered to a child that then cleans up, is skipped there with that reason; its portable half (terminate, thirty
  seconds, kill only then, the interruption let through, handlers restored) is a test of its own on a modelled process.
  Two new tests in all, none skipped off Windows.
- The soak and its measurement stay macOS/Linux-only (bash launchers; `native-controls.py` stops a server through its
  process group, older code this lane did not touch). Only their self-tests run on Windows.

Measured (macOS):

| What | Before (7d87e4b7) | After (ae922bf3) |
|---|---|---|
| toolTest: tools / run/compat / run/compat/mac | 56 / 312 / 94, all OK | 56 / 322 / 94, all OK (+2 `test_soak`, +8 `test_gate_own_files`) |
| `test_soak` in a Python without `signal.SIGHUP`, `signal.SIGKILL`, `os.killpg` (`<ev>/winlike.py`) | 3 errors, each `AttributeError: module 'signal' has no attribute 'SIGHUP'` | 1 failure, the simulation's own: the test expects SIGHUP's name when `os.name` is `posix` |
| mutations of the fix (`<ev>/c2-red.log`) | — | unmutated copy green; 7 of 7 mutations red, six on the tests that name them, the seventh (the handlers not installed) by the signal ending the test runner |

Not measured: **Windows.** 192.168.3.140 did not answer (ARP incomplete, ports closed, no relay on 127.0.0.1) at the
start, in the middle and at the end of the lane, so the discovery was run there neither before nor after the fix. The
"Windows-like Python" row is a simulation on macOS: it shows the code no longer names what Windows lacks, and nothing
about paths, encodings, `os.kill` or `signal.raise_signal(SIGBREAK)` on a real Windows. The next CI run's
windows-latest leg, or one run of `python -m unittest discover -s run/compat -p "test_*.py"` from `forbric-kernel` on
the Windows machine, is the measurement.

## C-3. Seven new tests skip without game files

The `kernel` job runs `./gradlew build -Pforbric.skipBaseline=ci-unstaged` and fails when a test skips that
`ci-unstaged.tsv` does not list. `SoakPlayerPreparationTest`'s seven tests need the compiled game side.

Measured on a clean clone (`git clone --shared`, no stage, `-Pforbric.mcLibraries` pointing nowhere, JDK 21.0.12, the
job's own recipe reduced to `test skipRatchet`; `<ev>/c3-unstaged.sh`):

| Tree | test | skipRatchet |
|---|---|---|
| 7d87e4b7 | 5839 tests, 2036 skipped, 0 failed | **7 new skips** (the seven `SoakPlayerPreparationTest` tests), 3 lines that no longer skip |
| ae922bf3 | 5839 tests, 2036 skipped, 0 failed | **0 new skips**, the same 3 lines |

`ConstructionOrderVerdictTest` (10) and `SoakPlayerTest` (7) ran without game files. The seven rows were added as the
run wrote them; the file's header says they come from this local run and not from CI, and that the first CI run's
artifact replaces them.

The three lines "that no longer skip" are this host's, not the tree's: `TestFixtures.fabricLoaderJar()` also looks in
the local launcher's libraries, which a runner does not have (CIPREP met the same three). They stay in the baseline. So
"the ratchet is green on a runner" is by analogy with CI run 44286369477, where those three skipped; what is measured
here is that no new skip is left.

`ci-prepared.tsv` needs nothing: the `kernel-prepared` job's command on a clean clone with the stage's game files and no
third-party packs (`<ev>/c3-prepared.sh`) reported 5960 tests, 745 skipped, 0 failed, 57 transfer tests, and **0 new
skips**; all 24 tests of the three new classes ran. (It also listed 8 lines that no longer skip, again this host's:
the three Fabric Loader ones and five tests whose third-party jars this stage has.)

The ratchet itself was not changed.

## R-3. gate-m17 passed on files an earlier run left

In the acceptance sweep gate-m17's first install failed on a download. No profile was written, so no command resolved
and no client started, and the gate still printed 18 PASS lines: 16 from `build/gate-m17-command.txt` and
`build/gate-m17-client.log` of an earlier, green run, and 2 saying what a profile that did not exist "does not also
name". `lib.sh`'s `check` fails on a missing log; it cannot tell this run's log from the last run's.

What changed:

- `lib.sh discard_earlier_run <prefix> [keep-name ...]` removes every `build/<prefix>*` an earlier run left. It keeps the
  names it is given, and the file this run's own output is going to (told by `fstat` of the shell's stdout and stderr:
  macOS answers `stat /dev/stdout` with another inode, so `-ef` cannot tell).
- gate-m17 calls it before it runs anything, for `gate-m17-`, keeping only the one-time log of phase U's old install
  (nothing reads it after the step that writes it). A check whose input this run did not produce now fails with "no
  log to read".
- The two "does not also name" lines fail when there is no profile.
- The gate's other inputs were read for the same pattern: both install directories are wiped by the gate, the old-install
  stand-ins and the bundled checker are rebuilt each run, every other `build/` file it reads has the prefix. Phase U's
  cached beta2 install is kept on purpose and is held by its marker's SHA-1.
- `run/compat/test_gate_own_files.py` runs the real `lib.sh` over planted files, with the unguarded state as its own
  control, and reads gate-m17 as text: the call comes before anything runs, and every `build/` name in the gate has the
  prefix the call removes.

Measured:

| What | Before the fix | After the fix |
|---|---|---|
| gate-m17 with every download failing at once (a dead proxy through `JAVA_TOOL_OPTIONS`), over the files the stage's last green run left (`<ev>/r3-control.sh`) | 38 PASS / 128 FAIL, rc 1; the 18 lines above among the PASS; the two stale files' SHA-256 unchanged by the run | 20 PASS / 146 FAIL, rc 1; exactly those 18 lines gone from PASS, none added; each now a FAIL naming the missing file |
| mutations of the fix (`<ev>/r3-red.log`) | — | unmutated copy green; 10 of 10 red |
| gate-m17 for real, cold, no verdict cache, at c44037e8 (`<ev>/gate-m17.real.out`) | (164 / 0 at ce423a9d) | **164 PASS / 0 FAIL**, `CAUGHT none`, rc 0, 366 s; 18 `gate-m17-*` files were in `build/` at its start (counted before only: see "Corrections" in the last section) |

The "after" control ran on the working tree before the commit; `lib.sh` and the gate were not edited between that run
and c44037e8. The real run is at c44037e8; ae922bf3 after it changes `PROTOCOL.md` only.

In the control's phase U a client did start, before and after: the failed upgrade leaves beta2's profile, which
resolves and boots beta2's kernel. The gate is red there on "it runs this kernel, not beta2's". (This paragraph first
said those PASS lines were that run's own. Eleven were not: review finding H-1, repaired in the last section, after
which no client starts there.)

## Also run at the tip

- Full unit suite at ae922bf3 (`-Pforbric.requireFixtures=all jar cleanTest test --continue`, the stage's root): 920
  classes, 5993 tests, 0 skipped, 5 failed, the five D58 names. It found one thing toolTest does not:
  `CompatProtocolTest` fails on a `run/compat` script `PROTOCOL.md` does not name, hence ae922bf3.
- `bash -n` of `lib.sh`, the 61 gates and `run/compat/*.sh`: 69 files, all clean.

## Not done, and left recorded

- Windows, as said under C-2.
- No CI run: nothing was pushed. The seven baseline rows and the Windows leg are for the next run on the CI branch.
- gates-all was not re-run. `lib.sh` gained one function that only gate-m17 calls; gate-m17 is the only gate re-run.
- Only gate-m17 was read for stale inputs. Other gates write fixed names in `build/` too and none calls
  `discard_earlier_run`; whether any of them can skip a step and read the last run's file was not audited.
- Not in scope: C-1 (no order verdict when `crossEcosystemPresence=off`), R-4 (no time bound on a gate), m34, JourneyMap.
- The introductions were not edited (they describe `main`). Sentences that state counts: `introduction.md` "reported
  5,969 tests in its JUnit XML, 0 skipped and 5 failed" and its twin in `introduction.zh-CN.md` — the tip has had 5,993
  since lane m34fix; this lane adds no Java test. "About a third of the suite skips without game files" (and its twin)
  still holds: 2,036 of 5,839 here.

## Review and repair (2026-11-21)

Two reviews of the lane (the portability of the stop path; the honesty of the harness changes) returned 15 findings:
no blocker, no major, 6 minor, 9 for the record. Commit 14bf179d repairs what was cheap and safe. Still harness and
tests only: `git diff --stat 7d87e4b7..14bf179d -- forbric-kernel/src/main forbric-kernel/src/runtime` is empty.
Evidence: `<ev>/repair/` (`<rp>` below); the reviewers' own files are `<ev>/review-portability/` and
`<ev>/review-honesty/`.

| Finding | Verdict |
|---|---|
| SIG-1 a second stop signal during the client's thirty seconds left the client running, with no record | reproduced, **fixed** (older than this lane: a69b8643) |
| SIG-2 no test held the kill after the thirty seconds in `launch()` | reproduced, **fixed** |
| SIG-3 a failed real-process test could end the whole discovery with its own SIGTERM | reproduced, **fixed** |
| SIG-4 POSIX behaviour unchanged by the lane | confirmed for one signal (my own runs below); the two residual windows it names are **left**, see below |
| WIN-1 nothing was run on Windows; CI uses Python 3.12, the lane measured on 3.14 | confirmed; **3.12 now measured on macOS**; Windows still **not measured** |
| WIN-2 no POSIX-only call left at import or in an unskipped test | the reviewer's reading, plus one simulation (below) that found nothing against it; not a Windows measurement |
| WIN-3 `PROTOCOL.md` and two docstrings stated the Windows behaviour as fact | **fixed** (said to be unmeasured) |
| H-1 after the fix gate-m17's phase U still printed PASS on files the run did not write | reproduced, **fixed** |
| H-2 files with a space in their names were never hashed in "the player's files are byte-identical" | reproduced, **fixed** (older than this lane) |
| H-3 `discard_earlier_run` removes a `build/<prefix>*` file the output is piped into | reproduced by the reviewer's probe; **documented**, not changed |
| H-4 the seven baseline rows are right; `ci-prepared` needs nothing | confirmed |
| H-5 the text test read only the spelling `$BUILD/name` | reproduced, **fixed** |
| H-6 one sentence above was an inference | **corrected** below |
| H-7 the removal runs before the two missing-fixture exits | **left**, see below |
| H-8 no kernel source, verdict rule or threshold changed by the lane | confirmed; the repair makes three gate-m17 checks stricter (own files, the old profile, the player's files) and none looser |

### What changed

**The soak is interrupted once (SIG-1).** `stop_signals_interrupt` raised `KeyboardInterrupt` for every stop signal.
The first started the stopping of the client (its group told to stop, thirty seconds, killed); a second one landed
inside that and went straight out through `launch()`: exit 130, the wrong message ("stopped outside the client's run"),
no `acceptance.json`, and a client that did not go on SIGTERM still running. Now only the first stop of a block raises;
later ones are counted (`STOPS_SEEN`) and change nothing, because what they ask for is under way and ends within its
thirty seconds. Ctrl-C is in the same count where it would raise at all (Python's own handler in force; a background
job's ignored SIGINT stays ignored), and a block inside another (the measurement's inside `main()`'s) is part of the
outer one. Whether a signal is the first is settled before it is counted: my first draft counted and then asked
whether the count was one, and three signals sent together ran the handler inside itself and raised nothing, so the
soak carried on as if nobody had stopped it (`<rp>/draft-runs/`, never committed). The real-process runs below found
that, and `test_soak` now delivers the second signal at each line of the running handler.

**Tests (SIG-2, SIG-3).** `test_a_stopped_soak_…` runs `launch()`'s stop twice, with a client that goes when told and one
that does not: SIGTERM, a wait of thirty seconds, SIGKILL and an unbounded wait only in the second case, and the FAIL
record either way. It also holds the single interruption (every stop signal and Ctrl-C delivered again after the first,
and inside a nested block), the arrival inside the handler, and the ignored Ctrl-C. The test that sends SIGTERM to its
own process keeps a handler of its own on SIGTERM for its whole length, sends only once the measurement is up and
until the test is over, and quotes the script path.

**gate-m17, phase U (H-1).** `discard_earlier_run` covers `build/`. Phase U's directory is a copy of the released
v0.3.1-beta2 install on purpose, and beta2 has files under four of this install's names and a profile under this
one's. With both installs failing the gate printed PASS for those four, resolved beta2's profile, booted beta2's kernel
and printed seven more PASS lines. Now the digests of the old files are taken before either install runs, and:
- "this install's own file is there" needs a file that differs from the old one, or one whose `.pins` stamp this run
  wrote. The second case is real and green: NeoForge 26.2.0.93 patches the game to the same bytes 26.2.0.88 did
  (67bbf24f… in beta2's install, in this run's fresh install and in the upgrade), so `out/patched-mc-neoforge-26.2.jar`
  comes through an upgrade unchanged, old date included, while its stamp goes from `neoforge=26.2.0.88` to `.93`. A
  rule on the bytes or the date alone would have turned a correct install red.
- a profile that is still the old install's is neither resolved nor launched, so every check after it fails on a file
  this run did not write, and no beta2 client is started.

**gate-m17, the player's files (H-2).** The list went through `xargs`'s word splitting: `shasum` was handed halves of
every name with a space in it, printed "No such file" 112 times into the gate's output, and the comparison passed on
the rest: 401 of this pack's 426 files, 23 mod jars and 2 configs not among them. Names now go NUL-separated, and the
list has to be as long as the number of files, before and after.

**Text (WIN-3, H-1, H-3).** `PROTOCOL.md`, `lib.sh` and gate-m17's header say that the removal covers `build/` only, that
it knows the run's own output file only by a direct redirection (output piped into a `build/<prefix>*` file is
removed with the earlier run's), and that the Windows branches of the stop path have not been run on Windows.
`test_gate_own_files.py` refuses the spellings `${BUILD}/x`, `"$BUILD"/x` and `$KERNEL/build/x` in gate-m17 (H-5) and
reads phase U's two rules and the hashing as text.

### Measured (macOS; all at 14bf179d unless said)

| What | Before the repair (cd874b87) | After |
|---|---|---|
| The real `soak-run.py` as `__main__`, a real stand-in client in its own session, real signals (the reviewer's `stop-real.py`; `<rp>/sig1-*.log`). Two signals 2 s apart, client deaf to SIGTERM: TERM+TERM, HUP+HUP, TERM+INT | exit 130 after 3.5 s, client and its child **alive**, no record, 3 of 3 | exit 1 after 31.3 s, client and child gone, record FAIL / exit code −1, 3 of 3 |
| The same, one signal (TERM, HUP, INT; TERM with a deaf client) | (reviewer: exit 1, gone, recorded) | exit 1 after 1.3 s (31.3 s deaf), gone, recorded, 4 of 4 |
| The same, two to four signals with no pause (TERM+HUP, TERM+INT, HUP+TERM+INT, INT+HUP+TERM, HUP+HUP+TERM+INT, six rounds each; HUP+TERM+INT deaf once) | not run | exit 1, gone, recorded, 31 of 31 |
| `test_soak` with `launch()`'s kill after the thirty seconds removed (SIG-2) | 49 tests OK | red |
| Mutations of the stop path, `<rp>/sig-red.log` | — | unmutated green; 7 of 7 red (three of them by the escaping interruption ending the runner) |
| `test_soak` with `TMPDIR` holding a space, the process kept alive 12 s after the tests (SIG-3) | 1 failure, then the process killed by its own SIGTERM, rc 143 | 49 OK, rc 0; with the path unquoted again as a control: 1 failure, the process survives, rc 0 (`<rp>/draft-runs/`) |
| gate-m17 with every download refused, over the files of a green run (`<rp>/r3-control.sh`) | 20 PASS / 146 FAIL (the lane's "after") | **9 PASS / 157 FAIL**, rc 1, 26 s, no client started. Gone from PASS: the four own-file lines and the seven of the boot; the player's-files line now says 426 |
| Mutations of the gate's text checks, `<rp>/gate-red.log` | — | unmutated green; 9 of 9 red |
| **gate-m17 for real**, cold, no verdict cache (`<rp>/gate-m17.real.out`) | 164 / 0 at c44037e8 | **164 PASS / 0 FAIL**, `CAUGHT none`, rc 0, 291 s; the same 164 lines as the lane's run but for "(426 files)" in place of "(401 files)"; no `shasum:` line (112 before) |
| toolTest (`<rp>/tooltest-mac-repair.log`) | 56 / 322 / 94 | 56 / **325** / 94, all OK (+3 text tests in `test_gate_own_files`; `test_soak` still 49) |
| The discovery CI's `development-tools` job runs, `unittest discover -s run/compat`, on Python 3.14.3 and on **3.12.12** (`<rp>/discover-mac.log`) | — | 325 OK on both |
| `test_soak` with its three `os.name=='nt'` branches taken, in a Python without SIGHUP, SIGKILL, `os.killpg`, `os.setsid` (the reviewer's `ntsim.py`, SIGUSR1 standing in for SIGBREAK; a simulation on macOS) | (reviewer, at cd874b87: 49 OK, 1 skipped) | 49 OK, 1 skipped, on 3.14 and 3.12 (`<rp>/draft-runs/ntsim-after.log`, on the tree before the commit) |
| The whole discovery in a Python without SIGHUP, SIGKILL, SIGSTOP, `os.killpg`/`setsid`/`getpgid`/`fork`, and with `fcntl`, `pwd`, `grp`, `resource`, `termios` unimportable (`<rp>/winlike-discover.py`; `os.name` stays `posix`) | — | 325 run, 0 errors, 1 failure: the test that expects SIGHUP's name when `os.name` is `posix` (the simulation's own) |
| Full unit suite, `-Pforbric.requireFixtures=all jar cleanTest test --continue` | 920 / 5993 / 0 / 5 at ae922bf3 | 920 classes, 5993 tests, 0 skipped, 5 failed: the five D58 names |
| `bash -n` of `lib.sh`, the 61 gates, `run/compat/*.sh` | 69 clean | 69 clean |

The nine PASS lines left in the refused-downloads control are said of what they read: five of the old install and the
state the gate laid out (beta2's profile and cache, the files phase U judges, the reverse control, beta2's kernel that
the keep-old profile names), three absence checks on logs this run's failed installs wrote, and the player's files,
which the gate copied in this run.

### Corrections to the text above

- "the 18 `gate-m17-*` files in `build/` at its start were removed by it" was an inference (H-6): the real run counted
  them before it started and nothing afterwards, and a green run writes every one of those names again. That the
  removal happens is shown by the refused-downloads control (the two stale files are gone after it) and by the unit
  test, not by the real run.
- "Those PASS lines are that run's own" was wrong (H-1): four of them were about files beta2's installer wrote on
  2026-11-17 and seven about beta2's kernel booting from beta2's profile. Commit c44037e8's title, "read only the files
  its own run wrote", held for `build/` only; with 14bf179d it also holds for what phase U says of this install.

### Left, with the reason

- **Windows is still not measured.** 192.168.3.140 did not answer during the repair either (ping, ports 9527/9528, no
  relay on 127.0.0.1), so the discovery was not run there after the Python under `run/compat` changed. What stands in
  for it is the 3.12 run and the simulation above; neither is Windows. The windows-latest leg of the next CI run is the
  measurement. New in this repair and never run on Windows: `signal.raise_signal(SIGINT)` and `sys.settrace` in
  `test_soak`.
- SIG-4's two windows. A stop that lands between `Popen()` returning and the `try` around the client's wait still
  leaves a client (milliseconds; older than the lane; read, not measured). Closing it needs the stop signals blocked
  across the fork, and a JVM that inherits blocked SIGTERM cannot be told to stop, so it is not a cheap change. And
  SIGKILL of the soak leaves the client, as it must.
- `retention-control.py`'s own handler still raises on every stop signal, so a second signal while it stops its server
  can leave that server (the same shape as SIG-1, one level down; read, not measured: a real run needs the native
  image). The soak stops the measurement with one SIGTERM and thirty seconds, which does not go through that path.
- A third signal after the client is gone, while the record is being written, cannot interrupt any more (it is
  counted); the soak then exits with the record. Not a defect, noted because the behaviour changed.
- H-3 is documented, not removed: a pipe gives the function nothing to recognise. No caller pipes a gate's output into
  its own prefix (gates-parallel.py and CI redirect directly, and to other names).
- H-7: the removal stays before the two missing-fixture exits. After such a run an earlier, green run's logs would
  otherwise still sit under the names this gate reports, beside an exit 3; losing them is the smaller harm.
- The `dl/` lists in phase U still go through `xargs` and `read` word splitting. The names there are Maven artifact
  file names, none with a space (17 inputs, 41 of beta2's 56 cached files removed, the same in the lane's run and in
  this one); not changed.
- gates-all was not re-run. Besides gate-m17, `lib.sh` changed in a comment only.
