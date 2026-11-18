# Stage 4 C6 — lane CIWATCH: the first CI run of the C6 tree, its one red, and the skip baselines from CI

Lane CIWATCH, worktree `s4c5-c6ciwatch`, branch `s4-c5-c6ciwatch`, cut from the stage tip **a3ced60b** (ports
46860-46879; none used). The orchestrator pushed a3ced60b to `ci/remove-forge-stage2`; this lane read the run, fixed its
red and replaced both skip TSVs from its artifacts. Nothing pushed, no tag, no release, main untouched. Downloads: only
this run's artifacts `kernel-test-results` and `kernel-prepared-skips` (gh run download, into scratch). Bootstrap's two
usual PROBLEMs: `.stage4/minecraft` was the relative `../.stage/minecraft` (relinked to the stage worktree's absolute
`.stage/minecraft`), `forbric-kernel/build/sweep100-mac` has no source in the main checkout (nothing here reads it).

## The run

| | |
|---|---|
| run | **44286369477**, workflow `build`, push to `ci/remove-forge-stage2` |
| URL | https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/actions/runs/44286369477 |
| SHA | a3ced60b6462441e1ba4699112c68d9ee92a2ea1 |
| time | 2026-11-18 10:32:27Z – 10:54:11Z |
| conclusion | **failure**: one leg red, development-tools (windows-latest); the other five jobs green |

## Each job against stage4-c6-ciprep.md "CI jobs"

| job | result | what the log shows, against the first-run list |
|---|---|---|
| build (ubuntu, JDK 21) — critic 8 | **green** (34 s) | it really ran: `bootstrap: cloning … fabric-loader.git at 0.19.3`, `8 patch(es) applied`, `[substrate-patches] OK - all 8 fabric-loader patches match the committed references (base 0.19.3)`; `cd forbric-loader && ./gradlew build` executed `:test` and `:check` (LinkBaselineProvenanceTest compiled; the log prints no per-test count) and `BUILD SUCCESSFUL in 28s`; artifact `forbric-jars` uploaded (2 files). |
| kernel (ubuntu, JDK 21.0.13) | **green** (10 m 20 s) | `test: 5815 tests, 2032 skipped, 0 failed` (CIPREP expected about 5812 / 2032; the +3 are VER's OldReleaseWordingTest 2 and LaunchInputCheckTest +1, check-w2); transferTest skipped (no staged jars, as designed); **skipRatchet `skip baseline ci-unstaged.tsv: 2010 line(s), unchanged`** (0 new, 0 stale); toolTest: tools 56 OK, run/compat 292 OK, mac 94 OK (includes test_ci_contract); installerTest `11 test classes ran, 0 failed`. Check (b): LibGateExitTest 6/0/0 and LibKeptUpContractTest 13/0/0 in the run's XML (bash 5). Artifacts kernel-test-results and forbric-kernel-jar uploaded. |
| kernel-prepared (ubuntu, JDK 25.0.5) | **green** (21 m 40 s, timeout 60) | cache miss (cold, key `dev-inputs-Linux-4b9347cb…`), "Prepare the game files (no assets)" OK (10:32:38–10:34:08); `test: 5936 tests, 753 skipped, 0 failed` (CIPREP expected about 5933 / 753); `transferTest: 57 tests, 0 skipped, 0 failed`; **skipRatchet `skip baseline ci-prepared.tsv: 732 line(s), unchanged`**; server gates `gate-m1 GREEN in 40s`, `gate-m36-mixin-outcome GREEN in 209s`, `gate-m46-stub-rebind GREEN in 69s`, `gate-m53-widened-new GREEN in 68s` (check (c); MC_DIR set by CI, R-c); "Refuse to publish game-derived files" passed; artifact kernel-prepared-skips (1 file); cache saved. No step named merge-loss or hidden-carrier. R-a: the cold prepare took 1.5 min. |
| development-tools (ubuntu) | **green** | tool-test: tools 56 OK, run/compat 292 OK, mac 94 OK — check (a): test_gate_cache and test_control_arm OK; installerTest 11/0 with `OldInstallCleanupTest: OK`. |
| development-tools (macOS) | **green** | the same: 56 / 292 / 94 OK; installerTest 11/0, `OldInstallCleanupTest: OK`. |
| development-tools (windows-latest) | **RED** | "Portable development and evidence tool tests": tools 56 OK (1 skipped, the Windows-only skip already there), then run/compat **292 tests, FAILED (failures=28)**; the mac suite did not run (dev.py stops at the first failing discovery). "Installer tests with the packaged link check" was **skipped**, so **`OldInstallCleanupTest: OK` is NOT in this run on Windows**: INSTFIX's junction case and held-open delete (§7 A) have still never run. Check (a) failed on Windows. |

Check (d) (a fresh clone builds `jar` before `test`, KernelBundledMixinExtrasStagedTest) stays recorded as not exercisable
on CI (it skips as third-party there; the row is in both TSVs).

## The red: 28 run/compat tool tests on Windows (fixed in 0399cb20)

The previous CI run (41094229886 at cf0498eb, 2026-10-30) ran 145 run/compat tests on windows-latest, all OK. Since then
Stage 4 added or extended five modules that had never run on Windows (test_control_arm 2026-11-16, test_forge_swap
2026-11-10, test_gate_cache 2026-11-15, test_lib_guards 2026-11-02, one new test_native_controls case from C6 REL). The
28 failures (from `gh api …/jobs/134739978832/logs`), by cause:

| cause | tests | evidence | fix |
|---|---|---|---|
| the bash gate harness: `bash` on windows-latest is WSL's launcher with no distribution ("Windows Subsystem for Linux has no installed distributions", rc 1, UTF-16 output); gates-parallel.py also registers `signal.SIGHUP` (`AttributeError: module 'signal' has no attribute 'SIGHUP'`) | test_lib_guards 13, test_control_arm 3, test_gate_cache SweepTest 8 | the log | these classes skip on Windows, as tools/test_dev.py's POSIX-launcher test already does (`os.name == 'nt' or not shutil.which('bash')`): lib.sh and every gate are bash and run on macOS and Linux only |
| stub JDKs written as `#!/bin/sh` scripts and joined into PATH with `:` | test_gate_cache KeyTest `test_the_jvm_the_launchers_start_counts_and_forbric_java_does_not` 1 | equal keys for both stubs | skips on Windows (the launchers it stands for are POSIX scripts too) |
| Windows path spelling | test_gate_cache `test_this_trees_own_path_does_not_count` 1 | the test built `f'{here}/x'` while the cache replaces `str(Path(repo))` (`\` on Windows) | test builds both paths through Path |
| | test_forge_swap `test_a_mods_own_files_are_kept_changed_or_lost` 1 | `forge-swap.py world_files` keyed files by `str(relative path)`: `packedup\\backpacks\\inventory0.nbt` | **product fix**: `.as_posix()`, so the rows read the same on every OS (gate-m59's world-file check greps `packedup/backpacks/`) |
| | test_native_controls `test_build_of_the_neoforge_control_needs_no_other_image` 1 | the message names `…\absent\fabric-api-0.155.2+26.2.jar`, the regex had `absent/` | regex accepts `/` or `\` |

Why it passed locally: every local and lane run is macOS (POSIX `bash`, `/` separators, SIGHUP exists), and the Linux
and macOS CI legs pass for the same reason. Nothing before this run executed these modules on Windows.

Local checks (macOS; no Windows host was used):
- run/compat suite with the fix: `Ran 292 tests … OK`, 0 skipped (the skip conditions are false off Windows, so nothing
  is hidden on POSIX); tools suite 56 OK; mac suite OK.
- The three path cases replayed with `PureWindowsPath` and the exact CI message (scratch `winrepro.py`): env_digest equal
  — old test form False, new True; world_files key — old `packedup\\backpacks\\inventory0.nbt`, new
  `packedup/backpacks/inventory0.nbt`; regex — old no match, new match (and still matches the POSIX spelling).
- Not reproducible here: the skips themselves and the windows-latest leg as a whole. The next CI run on
  `ci/remove-forge-stage2` is their proof, and with the tool tests green the installerTest step runs on Windows for the
  first time (INSTFIX §7 A: expect `OldInstallCleanupTest: OK` with no `NOT RUN`).
- Not changed: gates-parallel.py's SIGHUP registration (the sweep runs bash gates and is POSIX-only; it is now untested on
  Windows by design, not by accident).

## Skip baselines (dca0a0a8)

`ci-unstaged.tsv` and `ci-prepared.tsv` replaced in one commit by the run's artifacts (`skips-actual-ci-unstaged.tsv` from
kernel-test-results, `skips-actual-ci-prepared.tsv` from kernel-prepared-skips): every row exactly as CI wrote it, the
first four header lines kept, CIPREP's measurement note replaced by one naming the run, job and JDK.

Diff against the committed local measurement (CIPREP, macOS JDK 25 + 7 / 3 kept rows):

| | committed | CI | keys only in committed | keys only in CI | rows whose reason differs |
|---|---|---|---|---|---|
| ci-unstaged | 2010 | 2010 | 0 | 0 | 10 |
| ci-prepared | 732 | 732 | 0 | 0 | 0 |

The ratchet compares the first three columns, so both ratchets were already "unchanged". The 10 unstaged rows differ only
in the reason:
- 6 (FlowerPotRepairInjectorTest.vanillasClassIsNotThisRepairs, ItemUseOnInjectorTest.vanillasBodyKeepsItsCallAndOnlyGainsTheBridge,
  RegistryDirectoryOwnerInjectorTest × 4): the committed reason was cut short (92 instead of 128 characters for the flower
  pot row). junit_report.py cuts a reason at 200 characters; CIPREP's long scratch Minecraft path was cut there, then
  rewritten to CI's shorter `~/.minecraft`, which left the reason short. CI writes the whole reason.
- 4 (NeoForgeBaseClientPartTrackingStagedTest × 3, NeoForgeBaseRecordOptionalsStagedTest × 1): `[fixture:staged] … patched-mc-neoforge-26.2.jar absent`
  locally, `[fixture:java-25] the base is class-file 69, which only Java 25 links` on CI. The kernel job runs JDK 21, and
  these tests check the JDK before they look for the staged base; the pre-measure ran JDK 25.

The 7 unstaged and 3 prepared rows CIPREP kept from the last CI artifact (4 `[fixture:java-25]` weave tests, 3 Fabric
Loader 0.19.3 rows) are all in CI's measurement. Skipped counts vs rows: 2032 skipped for 2010 rows and 753 for 732 rows,
the same relation as CIPREP's measurement (2025 for 2010) and exactly the counts CIPREP predicted for CI.

Check: `tools/junit_report.py ratchet` replayed on the run's own JUnit XML (kernel-test-results/test-results, `--executed
test`) against the new ci-unstaged.tsv: `2010 line(s), unchanged`. No red control is possible for this commit: it changes
only reasons and comments, which the ratchet does not compare (the old file replays "unchanged" too). The prepared
artifact carries no XML, so ci-prepared.tsv was checked by row equality only. The introduction's "about a third" holds
(2032 of 5815); no number in the docs names the row counts.

## Tests in this lane

| run | environment | result |
|---|---|---|
| run/compat tool tests (`python3 -B -m unittest discover -s run/compat`) | macOS, Python 3.14.3, at 0399cb20 | 292 / OK / 0 skipped |
| tools/ and run/compat/mac tool tests | same | 56 OK; OK |
| ratchet replay on CI's XML | junit_report.py, new TSV and old TSV | both `2010 line(s), unchanged` |
| full suite (`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test --continue`) at dca0a0a8 | lane-env (FORBRIC_OLD = the lane's `.stage4`, GATE_PORT 46860), FORBRIC_GATE_CACHE=0, MC_DIR unset, JDK 25.0.3, pinned `--offline`, clean tree | **5969 tests / 0 skipped / 5 failed**, the D58 five by name (LiquidBounce × 3, SharedFinalSourceActualTest × 2), 4 m 43 s |

No gate run (D87/D89): no gate script or gate input changed; gate-m0 runs some run/compat modules, whose skips are inactive
on macOS. No zh run (no user-visible text changed). Build outputs of the suite deleted afterwards.

## Open items

- **Orchestrator: push again** (`ci/remove-forge-stage2`, the stage tip after this lane merges) for the green run: the
  windows-latest leg must show run/compat OK, then installerTest `11 test classes ran, 0 failed` with
  **`OldInstallCleanupTest: OK` and no `NOT RUN`** (INSTFIX §7 A; if a leg says NOT RUN, §7 C goes to ACCEPT); both
  ratchets "unchanged" against the new TSVs.
- The Windows mac-suite (94 tests) did not run in this run; run/compat/mac is unchanged since cf0498eb, where it passed on
  Windows.
- Disk: 2.3 GiB free at the end of this lane (was about 8 GiB at C6 start); this lane's own build outputs are deleted.
