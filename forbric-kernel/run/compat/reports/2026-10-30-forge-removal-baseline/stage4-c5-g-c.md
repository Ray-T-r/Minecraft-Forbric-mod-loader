# Stage 4 C5 — lane G-C (client, network, installer gates)

Branch `s4-c5-g-c`, cut from the Phase-0 tip cd6d0fe8. Notes and logs: `~/Forbric-backups/stage4-notes/c5/g-c/`
(the run outputs named below are under `c5/g-c/review/`).

## State: the gates are edited, none has run on the genuine stage

Gradle cannot build offline on this machine. The dependency cache `~/.gradle/caches/modules-2` was recreated
on 2026-11-08 at 20:47 and holds none of the kernel's dependencies. Every lane hit this. The D66 first act was
run twice and failed at `:compileJava` both times ("No cached version of ... available for offline mode"), with
0 tests:

- on the lane tree with the uncommitted work (`c5/g-c/suite0/suite.log`);
- on an APFS clone reverted to exactly cd6d0fe8, after review B-6 (`review/suite-clean/suite.log`).

Every gate builds the kernel through `gradlew --offline` (`kernel_jar`), so no gate can run either. **There are
no PASS/FAIL counts for any of the 13 gates, and no red control has been shown.**

What could be checked without Gradle:

- **javac outside Gradle** (`review/jc.sh`). It compiles all of `src/main/java` at release 21 against the same
  libraries taken from local copies: asm 9.10.1, sponge-mixin, sat4j, tiny-remapper, class-tweaker, mapping-io,
  night-config, log4j-api 2.26.0 and annotations 26.1.0. It then compiles the 19 test classes that read G-C's
  gate files, plus the two JUnit extensions, and runs them with JUnit 5.11.4. Forbric pins 5.9.2, which is not
  on disk.
  - **At the lane tip:** main compiles with 0 errors, and **108/108 tests pass** (`review/contract-fixed.log`).
  - **Same harness on the cd6d0fe8 clone:** 104/108 (`review/contract-clean.log`). The 4 failures read
    `build/classes/java/main`, which the clone's failed Gradle compile had removed. That is a harness artifact,
    not a change in the gates.
  - **What it does not cover:** `compileRuntimeJava` and the other source sets were not compiled. No source in
    them changed.
- **`bash -n`** passes on all 13 gates and `assert.sh`.
- **Python tests:** `test_gate_ports` and `test_lib_guards` both pass, 24 tests (`review/py-gate-ports-guards.log`).

## Commits

| sha | what |
|---|---|
| a1a923a8 | `assert.sh` drops the traditional-Forge construct line, the three EventMux bridge lines, "bridge(s) MISSING" (D34), and the alias line (pack-dependent since Stage 4, review A-7). `AssertCompatTest` now expects 36 (was 42). |
| e84c6b21 | m1 builds its jar with `kernel_jar` (A-4); "game base" prose. |
| 1c781c3a | m12 and m14: the MinecraftForge-wrapper sync checks are replaced by the D48 capture check. The m14 control is now marked TO MEASURE (A-5, B-1). Rundir overrides and guards added. |
| 820ac01b | m17 per D44: three artifacts, one runtime, no MinecraftForge runtime, "game base", two cached artifacts, NeoForge runtime stamp. Also: control N; offline installer build (A-3); D64 SKIP texts corrected (A-1, A-2); "measured" dropped (B-2); KernelBoot comment. |
| 4bee48b0 | m54: the control is re-pointed at fabric-api's "Unknown addon" disconnect and marked TO MEASURE (A-5). Adds `M54_RUNDIR` and the guard. |
| 3f3ba024 | m9: MinecraftForge-only checks deleted. Adds one carrier (`2-neoforge-runtime[^,]*`, A-8/B-4), the alias pin and its absence, the JEI bindings check, the atlas mip check, DupScan (D65, comment A-9), the multipart phase (D65), the guard after the missing-fixture checks (B-8), and recipe entries marked as merged-base or TO MEASURE (A-6, B-3, B-5a). |
| e352818c | m20, m22, m23, m27, m55, m56: guards, with m22/m23/m27 placed after the missing-world check (B-8). m13 prose. |

No test is deleted, renamed or added, so no TSV rows are owed (D47).

## Measured checks (outside the game)

- **`assert.sh` on `AssertCompatTest`'s GREEN fixture:**
  - Current script: rc 0, 36 PASS (`review/assert-green-after.out`).
  - cd6d0fe8's script on the same fixture: 37 PASS and 5 FAIL. The 5 are exactly the deleted lines
    (`review/assert-green-base-script.out`).
- **Mutation:** re-adding the alias line to `assert.sh` turns `AssertCompatTest` red, with 2 of 5 tests failing
  (`review/mutation-assert-alias-back.log`).
- **m1 against the broken cache:** exit 3, "kernel jar build failed — refusing to run against a stale jar"
  (`review/m1-broken-cache.out`). The stale 2026-11-02 jar was still in `build/libs` and was not booted.
- **m9 rundir guard:**
  - Default rundir with the fixture link present: exit 3, "FAIL refusing rundir … it is the shared fixture
    run/client-merged-pack" (`review/m9-guard-default.out`).
  - Default rundir with the fixture absent: exit 3, the gate's own "SKIP-FATAL: no world at …"
    (`review/guard-unstaged-m9-client.out`). m27's absent case was checked the same way.

## Review findings (G-C review fixes, 2026-11-09)

| finding | outcome |
|---|---|
| A-1 m17 merge-tool SKIP reason false; hazard live | **Confirmed, reported against D64, not re-pointed** (820ac01b corrects the text). `forbric-kernel-installer/build.gradle:34-36,80-81` still bundles the tools jar. `LinkCheckTool.unpackTools` (`:139-152`) overwrites it unconditionally. `ArtifactBuilder.java:106` runs the link check on every build path. The lane's installer jar lists `forbric/tools/forbric-merge-tools.jar` at 589971 B. Proposed re-point: tamper with `.forbric-build/tools/forbric-merge-tools.jar`, reinstall, compare SHA-1 with the bundled one. Orchestrator ruling needed. |
| A-2 m17 offline phase partly checkable; "measured" claim | **Confirmed.** Only the removed-library sub-step lost its premise. A cached reinstall returns before NFRT (`NfrtRunner.run` checks `BuildStamp.isFresh` first). The SKIP text says so and the phase is reported against D64. "measured" was dropped from the header (820ac01b). |
| A-3 m17 builds the installer online | **Fixed** in 820ac01b. Loader `mergeToolsJar`, then the installer with `--offline --no-daemon -x buildForbricJars -x buildMergeTools`. `kernel_jar` already built the kernel. Not run, because m17 stops at `kernel_jar` today. |
| A-4 m1 swallows a failed build | **Fixed** in e84c6b21 and measured (above). |
| A-5 control outcomes stated as measured (m14, m54) | **Fixed:** both are now TO MEASURE (1c781c3a, 4bee48b0). |
| A-6 m9 recipe pointers to absent entries | **Fixed** in 3f3ba024. The four entries were added with expected reds, `lootModifierIndex`'s reason was restored, and every entry is marked merged-base or TO MEASURE. |
| A-7 `assert.sh` alias line is pack-dependent | **Fixed** in a1a923a8: the line is deleted and the count is 36. The genuine `MappedRegistry` (javap) has no `warn` call, so `askTheRegistryWhetherAnAliasTargetExists` returns early without fabric-registry-sync's mixin. |
| A-8 carrier id depends on D43 | **Fixed** in 3f3ba024 (`[^,]*`). `KernelDataPacks.carrierPackId` is still rank + file name. |
| A-9 DupScan comment overstates coverage | **Fixed** in 3f3ba024. `KernelMixinBootstrap` runs six passes after `transformClassBytes`. |
| A-10 m14 lost the client-only-entry survival check | **Not done (optional).** It needs a new smoke log line plus a run to pin it, and nothing can run. Open item. |
| B-1 report misdescribed the m14 header | **Confirmed.** The header said "exactly one red (note_block)", not 32/5. Both are corrected: the header now says TO MEASURE and cites PLAN's 32/5 as the old-gate planning run. |
| B-2 m17 "measured" claim | Same as A-2. **Fixed.** |
| B-3 m9 pointers | Same as A-6. **Fixed.** |
| B-4 carrier-order dependency | Same as A-8. **Fixed.** No hand-off to UPG is needed now; the loose match works before and after D43. |
| B-5 PLAN items not listed | **Listed** under Open below. |
| B-6 first act on a dirty tree | **Re-run** on a clean cd6d0fe8 clone: same wall, 0 tests (`review/suite-clean/suite.log`). |
| B-7 no checks.md entry or log | **Fixed:** `c5/checks.md` has a G-C heading, and the logs are under `c5/g-c/review/`. |
| B-8 m22/m23 not "wording only"; default rundir now refused | **Confirmed and changed.** The guard now runs after the missing-fixture check (m9, m22, m23, m27): unstaged gives the gate's own SKIP-FATAL/FATAL, staged default gives "FAIL refusing rundir", both exit 3. |

## Open (owner G-C unless stated)

- **Orchestrator:** restore Forbric's dependencies to the Gradle cache. Every item below waits on it.
- First act (suite = 41 names of `failures-close.txt`), then `failures-g-c.txt`.
- All 13 gates on `.stage4`, plus their red controls:
  - m14 control A (D48);
  - m54 control;
  - m9 alias / carrierLanguages / keyModifierSuffix and the 17-recipe batch;
  - m17 control N;
  - m20 `dependencyDialog=off`;
  - m22 `bootJarPreload=off`;
  - m55 withheld;
  - m56 phase controls.
- m12 ×3 alone (overload flake); m13 first run after 0b.
- The m17 M17_SEED_CACHE claim, measured with the network denied.
- **Orchestrator ruling on A-1/A-2 (D64):** re-point m17's two phases in C5, or keep them as SKIP.
- A-10 (optional): canary-block raw-id survival line in m14.
- client-neo-pack sophisticated*/earthmobsmod (c1/handoff.md:1063): a fixture edit that needs the
  orchestrator's OK. Not asked yet.
