# Stage 4 C6 — merger of CIWATCH and DLCLEAN (2026-11-18)

Stage branch `claude/remove-forge-neoforge-fabric-83b024` (worktree `suspicious-chaum-b28730`): **a3ced60b -> 662fdafb**
(fast-forward only, no merge commit), then this record. main untouched, nothing pushed, no tag, no release, no worktree or
branch deleted. Notes, logs and the SHA map: `~/Forbric-backups/stage4-notes/c6/merge-w3.md`, `c6/merger-w3/`.

## Merge

1. **CIWATCH** (`s4-c5-c6ciwatch`, tip ce48136d, cut from a3ced60b = the stage tip). Done, no blocking finding. No rebase
   needed. Re-run in its worktree before the fast-forward: tools/ 56 OK, run/compat 292 OK, run/compat/mac 94 OK (macOS;
   the new Windows skips are false here), and the lane's PureWindowsPath replay of the three path reds (old form fails, new
   form passes, all three). Fast-forward -> ce48136d.
2. **DLCLEAN** (`s4-c5-c6dlclean`, tip 847edd96 on a3ced60b; review findings F1-F4 and both nits fixed, the FIFO nit
   refuted). Rebased in its worktree onto ce48136d: no conflict (the lanes share no file), range-diff 8/8 `=`, tip
   **662fdafb**. Re-run on the rebased tree: kernel `installerTest` 11 classes, 0 failed (`OldInstallCleanupTest: OK`, no
   NOT RUN on macOS); OldInstallCleanupTest on a plain class path OK in en and under forced zh; `bash -n` gate-m17 OK;
   test_gate_locale / test_gate_ports / test_gate_cache 53 OK; test_ci_contract 4 OK. Fast-forward -> 662fdafb.

Both intents kept: CIWATCH makes the Windows tool-test step pass so that CI's next step, `installerTest`, runs on
windows-latest; DLCLEAN's two Windows-only legs (held-open download, junctioned `dl`) live in that step.

## Verification at 662fdafb

Environment of every run: FORBRIC_GATE_CACHE=0, FORBRIC_OLD=`<stage>/.stage4`,
FORBRIC_NEOLIVE=`<stage>/.stage4/run/neoforge-runtime/forbricneolive.jar`, GATE_PORT 46800, M12/M13/M14/M16/M28/M32_PORT
46801-46806, HEAVY_LANE=merger-c6w3, MC_DIR unset, JDK 25.0.3, clean tree; heavy.sh, m17 as `client.sh heavy.sh`.

| run | result |
|---|---|
| full suite (`--no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test --continue`, 3m37s) | **5,969 tests / 0 skipped / 5 failed**, the D58 five by name and message; per class identical to a5708f73 (917 XML): 0 new, 0 gone |
| gate-m0 | **18 PASS / 1 FAIL**, red only on D58 ("3 of 5969 skipped"); step 4's link checks PASS; "evidence, soak, locale and cache tool tests (105 ran, none skipped)"; no `SKIP (C6`; CAUGHT none |
| gate-m17 (DLCLEAN's gate) | **GREEN 164 PASS / 0 FAIL**, CAUGHT none. Seeded (`M17_SEED_CACHE` = an APFS clone of the stage's `run/installed/.forbric-build`, dl/ 17 files), so nothing was downloaded: first install removed 0 files and its dl/ is exactly the 17 pinned names; the second install leaves dl/ identical by name, size, inode and time; phase U removes 63 files / 285.8 MB, reverse control 63/63, dl/ ends as the 17 inputs; both clients entered the world and left cleanly |

Not run, with the reason (D87/D89): gate-m59 — CIWATCH's `forge-swap.py` change (`str(rel)` -> `rel.as_posix()`) is the
identity on POSIX, so m59 on macOS cannot see it; its proof is CI's windows-latest leg. No other gate reads a changed file.
No gates-all, no zh run (DLCLEAN's new zh line has its forced-zh unit test).

## Kernel unit tests and the CI skip baselines

Between a3ced60b (the SHA CI run 44286369477 measured) and 662fdafb no file under `forbric-kernel/src` or
`forbric-kernel/build.gradle` changed except the two TSVs themselves; the suite's per-class counts are identical. DLCLEAN's
test is an installer `main()` program run by `installerTest`, outside the kernel suite; CIWATCH's skips are Python
`unittest` skips in `toolTest`, which no TSV lists. So neither lane changed the set of kernel unit tests or their skip
behaviour in `ci-unstaged` or `ci-prepared`, and the CI-taken TSVs (dca0a0a8) should match the next run row for row. The
known variance stays: four unstaged rows (NeoForgeBaseClientPartTrackingStagedTest x3,
NeoForgeBaseRecordOptionalsStagedTest) carry the `[fixture:java-25]` reason on JDK 21; the ratchet compares keys, not
reasons.

## Open

- **Orchestrator: push 662fdafb (or the tip at push time) to `ci/remove-forge-stage2`.** The windows-latest leg must show
  run/compat OK, then `installerTest` 11/0 with `OldInstallCleanupTest: OK` and no NOT RUN: the first run anywhere of
  INSTFIX's junctioned `libraries`, the held-open delete, and DLCLEAN's junctioned `dl` (the only test of the `dl` step's
  safety role). Both ratchets must stay unchanged. The Windows mac suite (94) did not run in 44286369477.
- Everything else in the README's "Open after C6" list stands.
