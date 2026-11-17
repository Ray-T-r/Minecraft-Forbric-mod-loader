# Stage 4 C6 — lane CIPREP: prepare CI for the NeoForge + Fabric tree

Lane CIPREP of C6 wave 2 ("CI / 发布", plan §C6), worktree `s4c5-c6ciprep`, branch `s4-c5-c6ciprep`, cut from the stage
tip **9cd04fc1** (C6 wave 1 merged, D94). Scout items: `ci-delete-merge-loss-ratchet`,
`ci-delete-hidden-carrier-and-noforge-tsv`, `ci-dev-tools-link-step`, `ci-regenerate-skip-tsvs` (the local
pre-measure half), `ci-first-run-checks`; wave-1 hand-offs (build.yml's `linkGateTest`, build-merged-base.sh:143,
critic 8). This lane pushes nothing: the merger pushes to `ci/remove-forge-stage2` and reads the first CI run. No
tag, no release, no download. Evidence: `~/Forbric-backups/stage4-notes/c6/ciprep/`.

## Commits

| commit | what |
|---|---|
| d126640c | build.yml: merge-loss ratchet step and the hidden-carrier pass (+ its summary) deleted, `ci-prepared-noforge.tsv` deleted, development-tools runs `installerTest`, timeout 75 -> 60, comments without Forge / merged base; build.gradle's `forbric.hideForgeCarrier` notice and its `linkToolTest` comment gone; `tools/test_ci_contract.py` fails on a workflow that names anything the tree no longer has |
| 8810e790 | `ci-unstaged.tsv` and `ci-prepared.tsv` re-measured on CI-like clones of the C6 tree |
| (this record) | record |

## Items

### ci-delete-merge-loss-ratchet — done (d126640c)

The kernel-prepared step "Merge-loss ratchet on the prepared inputs" (bootstrap, `build-merged-base.sh` on
`forge-patched/` + `forge-runtime/`, then forbric-loader's `MergedBaseHookLossRatchetTest`) and its comment are gone.
Cover (D89): what it held — the merge tool's platform-call losses on a merged base — no longer exists in the product;
Forbric ships and stages no merged base since Stage 4 (`tools/dev.py` STAGED_FILES names only the NeoForge base,
carrier, canary and sidecar; `DevPrepare.RETIRED` lists `merged-base`, `forge-runtime`, `forge-patched`). On this tree
the step had no Forge inputs and would have failed in `build-merged-base.sh`. The test class and the script stay in
forbric-loader until Stage 5.

**build-merged-base.sh:143** (`evidence.py merge-provenance`, removed by REL): dead after this commit. `git grep` finds
no executor left: the deleted CI step was the only one (the two `launch-*-merged.sh` scripts only name it in messages).
It is still READ as text by `tools/test_pins.py:79-80,96-97` (pin defaults) and forbric-loader's
`LinkBaselineProvenanceTest` (sources, `--input` names), so deleting it now would break those two tests. Left for
Stage 5, which deletes forbric-loader; Stage 5 must also drop test_pins.py's two `build-merged-base.sh` assertions.

### ci-delete-hidden-carrier-and-noforge-tsv — done (d126640c)

Deleted: "Unit tests with the MinecraftForge carrier hidden", "Test summary (MinecraftForge carrier hidden)",
`forbric-kernel/src/test/skip-baseline/ci-prepared-noforge.tsv`, the 75-minute timeout comment. Cover (D89),
check by check: the pass ran `test transferTest skipRatchet` with the same `requireFixtures` as the prepared pass; the
only difference, `-Pforbric.hideForgeCarrier`, has had no effect since 05bb87f5 (build.gradle only warned), so it
re-ran the identical suite on identical inputs. Every test it ran, and its ratchet, is covered by the prepared pass and
`ci-prepared.tsv`. Nothing else read the TSV (git grep: only build.yml and the file itself).

build.gradle's retired-name warning for `forbric.hideForgeCarrier` existed (05bb87f5's message) only "because CI's job
that passes it is C6's to delete"; with the job gone it went too, so the brief's `git grep` is clean. Comments trimmed:
"Mojang/Forge/NeoForge code", "no Mojang, Forge or NeoForge bytes", the kernel-prepared job comment (now: Minecraft
from Mojang, NeoForge's artifacts from its maven, NeoForge's patched game + carrier + vanilla reference built on the
runner), "the merged game reshaped" -> "NeoForge's patched game reshaped". Timeout 60 (the last run, 41094229886 at
cf0498eb, took 31 min including the 9.5-min second pass and the 42 s ratchet step; cold prepare not measured).

### ci-dev-tools-link-step — done (d126640c)

development-tools (ubuntu, windows, macOS): step "Installer tests with the packaged link check (no bootstrap)" runs
`./forbric-kernel-installer/gradlew -p forbric-kernel-installer installerTest` (LINK's replacement; it builds the
kernel's `linkCheckJar` through `buildLinkCheck`, which uses `gradlew.bat` on Windows). Comments at the kernel job and
the job header say what runs. Locally (macOS, fresh clone, `--offline --no-daemon`): `[installer-test] 11 test classes
ran, 0 failed` (`devtools-installerTest.log`).

### CI contract check (brief) — done (d126640c), red control shown

`tools/test_ci_contract.py` gains `stale_references(workflow)` and two tests. Structural, no list of retired names:
each Gradle wrapper call's project directory follows the step's `cd` (a `( cd … )` subshell holds for its line only)
and `-p`; every task (and `-x` task) must be registered in that project's build.gradle, be a Java-plugin task, a
source-set task or `clean<Task>`; every `-P` property must be read by that build.gradle; a `forbric.skipBaseline`
profile must have its `src/test/skip-baseline/<profile>.tsv`; each `python <script>.py <command>` must name a command
the script spells; every repository path the file names (comments and cache keys too; `for … in …` gate loops
expanded; `build/` and `.dev/` outputs excluded) must exist. It runs on all three OSes through `dev.py tool-test`.

| control | result |
|---|---|
| tip build.yml | 4 tests OK |
| R1: 9cd04fc1's build.yml on the tip tree | FAIL, exactly 4 lines: `-Pforbric.hideForgeCarrier` read by nothing; profile `ci-prepared-noforge` has no TSV; installer task `linkGateTest` does not exist; `ci-prepared-noforge.tsv` missing (`red/R1-old-build-yml.log`) |
| R2: six mutations of the checker (task / property / path / python-command check off, loop expansion off, subshell cd persisting) | each turns the self-test red (`red/R2-mutations.log`) |

YAML: parses (`yaml.safe_load`, PyYAML 6.0.3); jobs build, kernel, kernel-prepared, development-tools. actionlint is
not installed here (not run).

`git grep` for the brief's names outside history records (`run/compat/reports/`): `ci-prepared-noforge`,
`hideForgeCarrier`, `linkGateTest`, `linkToolTest`: none. `MergedBaseHookLossRatchetTest`: forbric-loader's own test
file (Stage 5) and introduction.md:199,2153 (DOCS, see below).

### ci-regenerate-skip-tsvs — local pre-measure done (8810e790); CI half is the merger's

Neither TSV had been regenerated since Stage 3. **Against this tree the committed files would have failed both
ratchets**: unstaged 12 new skips (MixinFluidReactionHeadTest x5, KernelItemDamageTest x2,
AxeStripCallbacksInjectorTest, ForeignFluidTypeInjectorTest, GenuineNativeReferencesStagedTest, ItemUseOnInjectorTest,
NativeCoremodParityTest) (`ratchet-old-unstaged.log`); prepared 5 new (MixinFluidReactionHeadTest x5)
(`ratchet-old-prepared.log`). No wave lane had added rows for them (D47).

Recipe (each file's header, `-Pforbric.writeSkipBaseline`), each on a fresh `git clone --shared` of the lane branch in
scratch, `--offline --no-daemon`, JDK 25.0.3, macOS, FORBRIC_OLD and MC_DIR unset, through heavy.sh:

- **ci-unstaged**: `./gradlew build -Pforbric.skipBaseline=ci-unstaged -Pforbric.writeSkipBaseline
  -Pforbric.mcLibraries=<missing dir>/libraries --continue` (no `.dev`, no fixtures, no staged root: the default
  `../forbric-loader/run` has no jars in a clone; the missing MC dir stands in for a runner's absent `~/.minecraft`).
  BUILD SUCCESSFUL (test, toolTest, installerTest 11 classes, skipRatchet --write): test 5812 tests, 2025 skipped,
  0 failed (`unstaged-measure.log`).
- **ci-prepared**: `.dev` laid out as `dev.py prepare --no-assets` lays it out, from APFS copies: the .stage4 base +
  stamp + game-metadata jar, carrier + stamp, vanilla reference; `.dev/minecraft/{libraries,versions}`; `.dev/api`
  fabric-api 0.155.2 + energy 5.0.0; the canary built from this tree with `dev.stage_canaries`. Then the job's exact
  command plus `-Pforbric.writeSkipBaseline --continue`: test 5933 tests, 750 skipped, 1 failed; transferTest 57/0/0
  (`prepared-measure.log`). The one failure, NativeCoremodParityTest.theTargetListIsNeoForges, needs NeoForge's
  universal jar in `.dev/minecraft/.forbric-build/dl`, which DevPrepare downloads on CI and my layout lacked: with
  `neoforge-26.2.0.93-universal.jar` copied in, the class passes 5/0/0 (`prepared-nativecoremod-with-dl.log`). It is
  the only test that reads that directory, so no other row depends on it.

Rows the host cannot reproduce are kept from the last CI artifact (said in each file's header): unstaged — four
`[fixture:java-25]` rows (MixinAbsorbedCallbackTransportWeaveTest x2, MixinOperationSeamTransportWeaveTest x2: the
kernel job runs JDK 21; every other JAVA_25 requirement sits behind a staged fixture) and three Fabric Loader 0.19.3
rows (FabricLoaderApiSurfaceTest, FabricMixinCompatTest x2: `TestFixtures.fabricLoaderJar` also looks in
`~/Library/Application Support/minecraft`, which holds that jar here); prepared — the same three Fabric Loader rows.
The scratch MC path in 10 unstaged reasons is written as CI's `~/.minecraft`. Result: ci-unstaged 2010 rows,
ci-prepared 732 rows. Check: the measured actuals against the committed files = 0 new skips; the only "no longer
skips" lines are exactly the kept rows (`ratchet-check-*.log`). Reasons now name the NeoForge base (426 unstaged and
9 prepared reasons reworded by the tests since Stage 3). Scout expectations checked: NativeOnlyMethodsCensusTest x7
and MixinFitLivenessCensusStagedTest x5 still skip unstaged, MixinFitLiveness x1 still skips prepared (kept, as
measured); the C4 rows (ChainAnchorCensusStagedTest, SupersededFurnaceRemainderStagedTest) are present.

**Not reproducible here, for the merger to read off CI**: JDK 21 and Linux for the unstaged profile; a real
`dev.py prepare` (fresh NFRT build, its own `.forbric-build/dl`). The merger replaces both files with the first run's
`skips-actual-ci-unstaged.tsv` (artifact kernel-test-results) and `skips-actual-ci-prepared.tsv` (kernel-prepared-skips)
in one commit, then pushes again for a green ratchet (D47, owner answer 1).

### ci-first-run-checks — pre-measured where possible; each is read off the first CI run

| check | local pre-measure | read on CI |
|---|---|---|
| (a) development-tools ubuntu/windows for test_gate_cache, test_control_arm | macOS only (toolTest inside the unstaged build: OK) | both legs green, those two modules OK |
| (b) HARN2's bash-5 exit path and reap age check | not possible (bash 3.2 here) | kernel job's LibGateExitTest / LibKeptUpContractTest pass; kernel-prepared's four gates exit cleanly |
| (c) server gates m1/m36/m46/m53 on a CI-prepared stage | the step's own loop on the CI-like `.dev/staged`, lane ports (GATE_PORT 46940..), FORBRIC_GATE_CACHE unset (no cache: the gates do not read it), **MC_DIR unset** (lane rule; CI sets `.dev/minecraft`), JDK 25: m1 GREEN 19/0, m36 GREEN 6/0, m46 GREEN 10/0, m53 GREEN 8/0, all CAUGHT none (`ci-gates/`) | `gates-prepared.txt` in the step summary: four GREEN |
| (d) a fresh clone builds `jar` before `test` (KernelBundledMixinExtrasStagedTest) | in the prepared clone the job's `jar test …` order held; the test itself skips there as third-party (it also needs the popular pack), so CI cannot exercise it either | recorded as not exercisable on CI |
| critic 8: the `build` job | `./bootstrap.sh --check` on an APFS copy of the main checkout's substrate: 8 patches present, verify OK; `cd forbric-loader && ./gradlew --offline build` could NOT run: the offline cache lacks forbric-loader's compile dependencies (slf4j-api 2.0.16) and the lane may not go online. LinkBaselineProvenanceTest's assertions (the stage changed the provenance JSON and build-merged-base.sh) checked statically: all hold (`linkbaseline-provenance-static.log`) | build job green |

## CI jobs and what each must show green on the first run (for the merger)

1. **build** (ubuntu-latest, JDK 21) — Bootstrap substrate (clones FabricMC/fabric-loader at 0.19.3, applies 8 patches,
   verify-substrate-patches OK), `cd forbric-loader && ./gradlew build` BUILD SUCCESSFUL (its tests include
   LinkBaselineProvenanceTest), artifact `forbric-jars` uploaded. Stage 5 deletes this job.
2. **kernel** (ubuntu-latest, JDK 21) — `./gradlew build -Pforbric.skipBaseline=ci-unstaged` BUILD SUCCESSFUL: test 0
   failed (about 5812 tests, about 2032 skipped expected: 2025 here + the 7 kept rows), toolTest OK (tools/, run/compat,
   mac; includes the extended test_ci_contract), installerTest "11 test classes ran, 0 failed", skipRatchet "matches"
   (0 new, 0 stale). If it reports differences, they are the CI measurement: take
   `skips-actual-ci-unstaged.tsv` from `kernel-test-results`. Artifacts kernel-test-results and forbric-kernel-jar.
3. **kernel-prepared** (ubuntu-latest, JDK 25, timeout 60) — cache restore (first run: cold, the key hashes
   Pins.java + tools/dev.py), "Prepare the game files (no assets)" OK; "Unit and transfer tests" BUILD SUCCESSFUL: test
   0 failed (about 5933 tests, about 753 skipped: 750 here + the 3 kept rows), transferTest 57 tests 0 skipped (`requireTransfer`), skipRatchet
   0 new / 0 stale against ci-prepared.tsv (else take `skips-actual-ci-prepared.tsv` from `kernel-prepared-skips`);
   "Server gates on the prepared files": m1, m36-mixin-outcome, m46-stub-rebind, m53-widened-new all GREEN; "Refuse
   to publish game-derived files" passes; artifact kernel-prepared-skips. No step named merge-loss or hidden-carrier.
4. **development-tools** (ubuntu-latest, windows-latest, macos-latest; JDK 25) — "Portable development and evidence
   tool tests" (`python tools/dev.py tool-test`) OK on all three (scout check (a): test_gate_cache and
   test_control_arm on ubuntu and windows); "Installer tests with the packaged link check (no bootstrap)"
   "11 test classes ran, 0 failed" on all three (Windows: the nested `gradlew.bat linkCheckJar`).

All six legs green, with the TSVs from the run committed and a second push showing both ratchets green, is CI green
for landing.

## Tests and gates in this lane

| run | environment | result |
|---|---|---|
| full suite at 8810e790 (`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test --continue`) | lane worktree, lane-env, pinned --offline | 5966 tests / 0 skipped / 5 failed = c6/failures-w1.txt by name (D58 five) (`suite.log`, `failures-ciprep.txt`) |
| test_ci_contract | tools/, python3 | 4 OK; red R1/R2 above |
| ci-unstaged measure | clean clone, see above | BUILD SUCCESSFUL; 5812 / 2025 / 0 |
| ci-prepared measure | clean clone + CI-like .dev | 5933 / 750 / 1 (setup artifact, passes with the dl jar); transfer 57/0/0 |
| installer installerTest (dev-tools step) | clean clone | 11 classes, 0 failed |
| m1, m36, m46, m53 (kernel-prepared's loop) | CI-like stage, ports 46940+, cache unset, MC_DIR unset | 19/0, 6/0, 10/0, 8/0; CAUGHT none |

No gates-all (D87). No zh run (no user-visible text changed).

## Open items

- **Merger**: push, read the first run against the job list above, replace both TSVs with the CI artifacts in one
  commit, push again for green ratchets; the `build` job's forbric-loader build is not pre-measured.
- **DOCS** — the `- **CI**` bullet at introduction.md:2308 / introduction.zh-CN.md:661, false since this lane: kernel-prepared's
  prepare description ("Forge and NeoForge from their own mavens, the merged base and carriers", "the merge report"),
  "781 … 779 … 2" (now 732 rows: 730 third-party, 2 opt-in, before CI's own measurement), the sentence on
  `build-merged-base.sh` and the merge-loss ratchet ("the only place it runs"), development-tools "and the packaged
  link gate" (now `installerTest`), and the nightly sentence (`tools/nightly/`, `ci-results`; the job was removed by
  D85). The kernel command is unchanged, so test_ci_contract's bullet check still holds. Also introduction.md:199-201,
  2153-2158 (MergedBaseHookLossRatchetTest, build-merged-base.sh) describe a merge Stage 4 removed. Draft for the
  prepared sentence: "Job `kernel-prepared` (JDK 25) first builds the game files on the runner with `tools/dev.py
  prepare --no-assets` (Minecraft from Mojang, NeoForge's artifacts from its maven; NeoForge's patched game, the
  NeoForge carrier, the vanilla reference and the NeoForge canary built there; only upstream downloads are cached and
  nothing derived is uploaded)". "About a third" (about 2032 of 5812) still holds.
- **Stage 5**: delete build-merged-base.sh with forbric-loader and test_pins.py:79-80,96-97 with it; the `build` job.
- **Player-facing wording**: none; CI is not user-visible.
