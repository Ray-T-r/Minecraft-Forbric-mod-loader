# Stage 4 C6 — merger, wave 2 (VER -> INSTFIX -> CIPREP) and the CI push

Stage branch `claude/remove-forge-neoforge-fabric-83b024`: **9cd04fc1 -> a5708f73** (fast-forward only, no merge commit),
plus this record. main is untouched. Notes and logs: `~/Forbric-backups/stage4-notes/c6/merge-w2.md` and `c6/merger-w2/`.

> **2026-11-18, DOCFIX C6 (check-w2 C-1, C-5):** this record's commit was eba8222c. The stage moved after it: lane DOCS
> fast-forwarded it to 0d310a36, and DOCFIX C6 added one docs/records commit on top. None of them changes code (only `.md`
> files and one ignore line since a5708f73). The push below must name the stage tip at the time of the push
> (`git -C <stage worktree> rev-parse HEAD`), not eba8222c. The active gh account (`rtiags`) has no push right on the
> repository (`"push":false`), so the push needs an account that has it (check-w2 C-1).

## Merge

| lane | branch tip before | rebased onto | tip merged | conflicts | checked before the fast-forward |
|---|---|---|---|---|---|
| VER | 67edcdf3 | (already on 9cd04fc1) | 67edcdf3 | none | nothing re-run: the lane's suite (5969/0/5) ran on this exact tree, record-only commit after it |
| INSTFIX | fa72c951 | 67edcdf3 | 24e0ed42 | none (both lanes edit gate-m17-installer.sh, in different hunks) | `bash -n` on gate-m17; compile; OldReleaseWordingTest 2/0, LaunchInputCheckTest 20/0 (cleanTest, so the source scan really ran); kernel installerTest 11 classes, 0 failed (OldInstallCleanupTest OK) |
| CIPREP | f15dcac4 | 24e0ed42 | 1f29eca5 | none | `tools/` test_ci_contract + test_pins + test_dev 39 OK; build.yml parses; compile; installerTest 11 classes, 0 failed |

Every rebase is patch-identical (`git range-diff`: all `=`). Old -> new SHAs: VER unchanged; INSTFIX fb823db7 -> 12251503,
09c6c4ec -> c91f6f3c, 5750fa52 -> 9405e303, 976f25f6 -> fe13737b, fa72c951 -> 24e0ed42; CIPREP d126640c -> 4c374a09,
8810e790 -> 73c80a2e, 792bd69e -> 5e521e4d, d3c66685 -> 792c0e81, 1825a827 -> 1839ab39, a4d6ff09 -> 400e1064,
f15dcac4 -> 1f29eca5. Lane records cite the old SHAs.

## A red the merge found: gate-m0's locale rules (fixed in a5708f73)

gate-m0 at 1f29eca5 was 17 PASS / 2 FAIL: the D58 line and "evidence, soak, locale and cache tool tests". Two tests of
`run/compat/test_gate_locale.py` failed on INSTFIX's c91f6f3c, which asked gate-m17's installer runs for English with
`-Dforbric.dialogLanguage=en` alone:

- `test_a_language_pin_holds_both_switches_to_a_real_table`: "gate-m17-installer.sh: forbric.dialogLanguage=en: does not
  pin user.language";
- `test_every_language_pin_is_declared`: the pin was not a row of `PINS`.

It is not a merge interaction: INSTFIX's tree alone has it, and the lane never ran gate-m0. a5708f73 keeps both intents:

- `INSTALLER_LANG` is an array, `-Duser.language=en -Dforbric.dialogLanguage=en -Duser.country=US`, expanded as
  `"${INSTALLER_LANG[@]}"` on all seven installer runs. InstallerText reads the dialog switch first and falls back to the
  JVM's locale, so both are pinned.
- `PINS` gains a `gate-m17-installer.sh` row with the reason (the installer, not a game JVM, prints the `[cleanup]` lines
  the gate reads).
- The rule reads pins from the text, so a value right before the array's `)` is read as `en)` (a red, "DialogLang has no
  table "en)""); `-Duser.country=US` therefore stays last. This is a limit of the rule's text reading, and it errs red.

Red controls (`c6/merger-w2/locale-pin/`): the PINS row removed -> `test_every_language_pin_is_declared` red; INSTFIX's
dialog-only pin with the row kept -> both rule tests red; the tip -> `test_gate_locale` 10 OK. gate-m0 at a5708f73: the
tooling line PASS (105 ran, none skipped). gate-m17 at a5708f73: GREEN 119/0 with the array form.

## Suite and gates

Full suite (`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test
--continue`, pinned, plain `--offline`):

| tip | tests | skipped | failed | against failures-w1.txt |
|---|---|---|---|---|
| 1f29eca5 | 5969 | 0 | 5 | the same five by name and message (D58: LiquidBounce x3, SharedFinalSourceActual x2); 0 new, 0 gone |
| a5708f73 | 5969 | 0 | 5 | the same five; 0 new, 0 gone |

+3 tests against wave 1, per class: OldReleaseWordingTest 0 -> 2 and LaunchInputCheckTest 19 -> 20 (both VER).

Gates, sequential, verdict cache off. Environment of every run: FORBRIC_GATE_CACHE=0, FORBRIC_OLD = the stage's
`.stage4`, FORBRIC_NEOLIVE = its `run/neoforge-runtime/forbricneolive.jar`, GATE_PORT 46800 and M12..M32 ports
46801-46806, MC_DIR unset, M17_SEED_CACHE unset (m17's first install was cold and downloaded from Mojang and NeoForge),
through `.stage/heavy.sh`, m17 also through `.stage/client.sh`. Counts are `^[kernel] PASS` / `^[kernel] FAIL` lines.

| gate | tip | result | PASS / FAIL | CAUGHT |
|---|---|---|---|---|
| m0 | 1f29eca5 | red: D58 and the locale rules above | 17 / 2 | none |
| m0 | a5708f73 | red only on D58 ("3 of 5969 skipped"), no `SKIP (C6` line | 18 / 1 | none |
| m10 (VER edited its comment) | a5708f73 | GREEN | 24 / 0 | none |
| m17 (VER, INSTFIX, merger) | a5708f73 | GREEN; phase U reverse control 22/22, removed exactly 22, dl/ 56 files untouched, control N's refusal pattern matches | 119 / 0 | none |

No other gate reads a file these lanes changed. No gates-all (ACCEPT's).

## CI (part 2): not pushed

The push of a5708f73's tree to `ci/remove-forge-stage2` (allowed by owner answer 1, D93) did not happen: the command was
refused by this session's local permission check before it ran, so nothing was sent and the branch still points at
cf0498eb. Consequently no CI run of the C6 tree exists, both skip baselines are still CIPREP's local pre-measure, and
CIPREP's first-run list (build, kernel, kernel-prepared, development-tools x3, Windows `OldInstallCleanupTest: OK` with
no `NOT RUN`) is unread. The owner runs, from the stage worktree:

```
git push --force-with-lease=ci/remove-forge-stage2:cf0498eb origin <stage tip>:refs/heads/ci/remove-forge-stage2
```

then CI is read job by job against stage4-c6-ciprep.md "CI jobs", both TSVs are replaced in one commit by the run's
`skips-actual-ci-unstaged.tsv` (artifact kernel-test-results) and `skips-actual-ci-prepared.tsv` (kernel-prepared-skips),
and a second push must show both ratchets green.
