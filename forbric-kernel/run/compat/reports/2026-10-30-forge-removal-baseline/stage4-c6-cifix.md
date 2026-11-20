# Stage 4 C6: three harness findings of the acceptance re-run's check (lane cifix)

The check of the acceptance re-run (`stage4-c6-accept-rerun.md`; its findings are in
`~/Forbric-backups/stage4-notes/c6/m34-survival/check-rerun.md`) predicted two CI reds from reading (C-2, C-3) and found
a gate that prints another run's PASS lines (R-3). This lane fixed the three. Branch `s4-c5-cifix`, cut from the stage
tip 7d87e4b7; code commits 8ba24bca (C-2, C-3), c44037e8 (R-3), ae922bf3 (the protocol sentence for the new test).
Harness and tests only: nothing under `src/main` or `src/runtime` changed, so the suite and gates-all measured at
ce423a9d still describe the kernel. Nothing was pushed, `main` was not touched. Evidence (`<ev>` below):
`/Users/jerry/Forbric-backups/stage4-notes/c6/cifix/`.

| Item | Result |
|---|---|
| C-2, POSIX-only signals in the soak's stop path | fixed; measured on macOS; **not measured on Windows** (the machine was unreachable for the whole lane) |
| C-3, seven tests missing from `ci-unstaged.tsv` | fixed; the red and the fix measured on a clean clone without game files |
| R-3, gate-m17 reads an earlier run's files | fixed; a network-free red control before and after, then gate-m17 for real: 164 PASS / 0 FAIL |

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
| gate-m17 for real, cold, no verdict cache, at c44037e8 (`<ev>/gate-m17.real.out`) | (164 / 0 at ce423a9d) | **164 PASS / 0 FAIL**, `CAUGHT none`, rc 0, 366 s; the 18 `gate-m17-*` files in `build/` at its start were removed by it |

The "after" control ran on the working tree before the commit; `lib.sh` and the gate were not edited between that run
and c44037e8. The real run is at c44037e8; ae922bf3 after it changes `PROTOCOL.md` only.

In the control's phase U a client did start, before and after: the failed upgrade leaves beta2's profile, which
resolves and boots beta2's kernel. Those PASS lines are that run's own, and the gate is red there on "it runs this
kernel, not beta2's".

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
