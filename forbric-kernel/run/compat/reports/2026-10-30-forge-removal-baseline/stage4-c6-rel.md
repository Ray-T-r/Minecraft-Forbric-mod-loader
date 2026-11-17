# Stage 4 C6 — lane REL: release evidence, push-and-run, native controls

Lane REL of C6 ("CI / 发布", plan §C6), worktree `s4c5-c6rel`, branch `s4-c5-c6rel`, cut from the stage tip **495e48fc**
(D92: the D91 native-parity items stay open and do not block C6). Scout items: `evidence-release-roles-trim`,
`push-and-run-profile-rewrite`, `native-controls-without-forge-image`, `mac-testdata-crash-logs`; completeness-critic
corrections 2, 6, 7 and 25. Nothing was pushed; no tag, no release, no upload; the Windows machine was not touched.
Evidence (logs, red controls, the rehearsal): `~/Forbric-backups/stage4-notes/c6/rel/`.

## Commits

| commit | what |
|---|---|
| 283670d2 | evidence.py: the six release roles; STAGED_BUILD_INPUTS binds neo-patched, neo-runtime and the sidecar; sidecar provenance; release-check refuses a non-release role; tools/test_pins.py re-anchored |
| 667e35c8 | push-and-run rewrites only game arguments, refuses a profile its installer did not write; stage defaults to `.dev/staged` |
| d35cecf6 | native-controls build/run per family (Fabric, NeoForge), no MinecraftForge canary, GATE_PORT, no forbric-loader stage fallback (also retention-control.py) |
| 9815e794 | PROTOCOL.md: release, profile, native-control paragraphs and the stale merged-era gate prose |
| b672a5b9 | review fixes: the Forbric control arm boots only a stage stamped by this tree's pins; retention-control resolves it first; the MinecraftForge install's users named |
| c0ac64c6 | review fixes: NeoForge control builds without the Fabric API jar; per-family build records kept only while they still describe the files |
| 6ab2a9fc | review fixes: a release requires the staged base and carrier stamped as built from the pinned NeoForge; verify's release re-check tested |
| 962ac3bd | review fixes: an old profile is refused before the kernel build and leaves no evidence directory; the old installer named by what it staged |

## Items

### evidence-release-roles-trim — done (283670d2)

- `RELEASE_ROLES` = vanilla, neo-patched, neo-runtime, kernel, kernel-runtime, sidecar. `PINS` = minecraft 26.2, neoforge
  26.2.0.93 (the `forge` pin is gone). `MINECRAFT_ROLES` = vanilla, neo-patched; `FORGE_ROLES` gone.
- Gone: `write_merge_provenance`, `merged_provenance`, `MERGE_TOOL_SOURCES`, `PROVENANCE_INPUTS`, the release requirement
  for merged provenance and the `merge-provenance` subcommand.
- New: `sidecar_provenance` — a release requires the sidecar's `META-INF/forbric/vanilla-reference.properties` to record the
  attested neo-patched's and vanilla's SHA-1s (the rule run/vanilla-reference-jar.sh applies at launch). The manifest
  carries `sidecarProvenance`; `verify` re-checks it for a release manifest.
- `release_check` refuses a `--publish` role that is not a release role by name (`merge-tools: not a release role`).
- `staged_root` resolves as build.gradle:31-34 does without `-Pforbric.stagedRoot`: `$FORBRIC_OLD/run`, else
  `forbric-kernel/.dev/staged/run` when it holds the base, else `forbric-loader/run` (Stage 5 removes that last fallback in
  build.gradle and here together).
- tools/test_pins.py: the MinecraftForge oracle pin is anchored on native-controls.py `VERSIONS["forge"]` (D32) and now
  also holds native-controls' installer URL and `native_command` copies to it; a new test keeps a `"forge":` pin out of
  evidence.py; the NeoForge test also holds `native_command`'s NeoForge copy.
- Tests: test_evidence 26 OK (24 at 495e48fc: the two merged-provenance tests gone, four added, two rewritten). tools/test_pins 6 OK.

**Critic 2 (STAGED_BUILD_INPUTS must bind neo-patched and the sidecar) — done.** `STAGED_BUILD_INPUTS` = neo-patched
(`neoforge-patched/patched-mc-neoforge-26.2.jar`), neo-runtime, sidecar (`vanilla-reference/vanilla-reference-26.2.jar`).
Red controls (each red on the mutated copy, green on the tip; `red/evidence/`, script `red/red-evidence.sh`):

| control | mutation | result |
|---|---|---|
| M1 | `check_staged_build_inputs` skips neo-patched | test_release_rejects_a_candidate… FAILED (failures=1) |
| M2 | it skips the sidecar | same test FAILED (failures=1) |
| M3 | `sidecar` dropped from RELEASE_ROLES | test_the_release_roles… FAILED (failures=1) |
| M4 | release-check judges every role | test_release_check_binds… FAILED (failures=4) |
| M5 | sidecar provenance not judged | test_release_requires_the_sidecar… FAILED (failures=3) |
| M6 | `.dev/staged` never chosen | test_the_staged_root_resolves… FAILED (failures=1) |
| real files | `evidence.py run --release` with neo-patched = the vanilla 26.2 jar, the stage's real base in FORBRIC_OLD | rc=1 `attests other jars than the build tests: neo-patched: … (f790e8fa9e35), attested 40896ee9f1e2` |
| real files | the same run without `--artifact sidecar=` | rc=1 `release evidence missing artifacts: sidecar` |
| real files | `release-check --publish merge-tools=…` | rc=1 `merge-tools: not a release role` |

test_pins red controls (`red/test_pins/`): VERSIONS forge 65.0.2 → red; installer URL 65.0.0 → red; a `"forge"` pin put
back in evidence.py → red; tip green.

### Local dry rehearsal of the release flow (no upload) — done, `rehearsal/steps.txt`

In a throwaway `git clone --shared` of the lane tip (deleted afterwards), FORBRIC_OLD = the lane's `.stage4`:

1. kernel jar built as the installer's `buildForbricJars` builds it (`./gradlew jar`) — rc 0.
2. `evidence.py run --release` with the six roles from the real stage, acceptance command `true` (a stand-in: the real
   one is `gates-all.sh --release`, lane ACCEPT's) — rc 0, inputs unchanged. The `true` stand-in exercises the flow
   only: it says nothing about whether a real release acceptance can pass today, and it cannot (D58: gate-m0 stays red
   without the LiquidBounce jars, which owner answer 2 / D93 keeps out, so `gates-all.sh --release` is red).
3. `releaseAssets` with LINK's change not yet in — **rc 1**: `merge-tools: not a release role`. This is the expected
   coordination point: releaseAssets still passes `--publish merge-tools=` and depends on buildMergeTools until LINK's
   `release-assets-drop-merge-tools` lands (LINK owns forbric-kernel-installer/build.gradle). REL and LINK must both be
   merged before ACCEPT's rehearsal.
4. LINK's change applied in the clone only (dependsOn without buildMergeTools, `--publish kernel=` only), committed there,
   evidence re-captured — rc 0; `releaseAssets -PreleaseTag=v0.0.0-c6rel-rehearsal` — rc 0: `release check passed: 1
   manifest(s), 6 role(s), 1 published file(s) match`, 15 jars + manifest (forbric-kernel 0.4.0-dev and its 14 libraries;
   no game artifact, no merge tools).
5. `jar -Pslim -PreleasePin=build/release/forbric-release.properties` — rc 0; the slim jar carries the pin
   releaseAssets wrote (byte-equal), no bundled manifest.

Stopped there: no `gh release create`, no upload, no install from a release. Owner answer 1 (D93) makes that the end
of the rehearsal for C6 as a whole: no draft tag and no draft release either. Observation for LINK/ACCEPT: the published
kernel asset is named by the installer version (`forbric-kernel-0.4.0-dev.jar`), so the version bump to 0.4.x
(owner answer 3, D93) changes the asset name too.

### push-and-run-profile-rewrite — done (667e35c8)

- `with_genuine_base` and `with_native_reference` (the library swap the plan forbids) are gone. `with_game_arguments`
  sets `--gameJar`/`--runtimeJar`/`--nativeReference` to the paths of the base/carrier/sidecar libraries the profile names,
  one value per flag, keeps `--libraryPath` and every other argument, and changes no library. The dry run prints
  `PROFILE_REWRITES game arguments …` or `PROFILE_REWRITES none (…)`.
- A profile this tree's installer did not write is refused (exit 2) before anything is stopped or staged: it names a
  retired artifact (patched-mc-merged, forge-runtime, patched-mc-forge, forbric-loader, forbricruntime), or lacks, repeats
  or versions differently one of the three game artifacts. The line ends with D9's advice: run the new installer again
  with the same game directory.
- `STAGED` = `$FORBRIC_OLD/run`, else `forbric-kernel/.dev/staged/run`; never forbric-loader/run.
- version-json-sync.py and push-and-run.sh: no change needed (the sync only writes sha1/size into named libraries).
- PushAndRunTest: **23 tests, 0 failures** (21 before; the profile test was rewritten, 2 added: the dry run refuses an old
  profile before naming any upload; the default stage). Red control (`red/push-and-run/`): `old_profile_problems`
  returning `[]` → 2 of 23 red (the two refusal tests); tip 23/0.
- Dry runs on real profiles (`dry-run/`): a profile the stage-tip installer wrote (gate-m17's install, 2026-11-16) →
  rc 0, `PROFILE_REWRITES none`; the same with its game arguments reversed and `--width 854` added → rc 0, only a
  game-argument rewrite; real profiles from Forbric 0.2.0 and 0.1.0 → rc 2 with the refusal. The 495e48fc code on the
  same old profiles printed `PROFILE_REWRITES drop library … library net.forbric:patched-mc-neoforge…` (the swap).
- For lane ACCEPT (windows-pcl2-real-install): push-and-run now refuses the old profile in the Windows `.minecraft`; the
  C6 installer has to run there first (owner answer 4 / D93 allows that install, after a fresh `.minecraft` backup).

### native-controls-without-forge-image — done (d35cecf6)

- `CONTROL_FAMILIES = ("fabric", "neo")`: `build` and `run` take `--family all|fabric|neo`; `build` compiles only the
  families asked for, each against its own image; build-inputs.json keeps another family's entry only while the canary
  sources are unchanged. `VERSIONS["forge"]` and the MinecraftForge install path stay for the swap oracle (D32,
  `prepare --family forge` only; `prepare` "all" = fabric+neo). canary/native-controls/forge/ deleted.
- Ports: `control_port()` = `GATE_PORT`, else an OS ephemeral port (was 25651 + index, +10 for Forbric); recorded in
  inputs.json. retention-control.py's two arms use it too (were 25681/25682).
- **Critic 7 — done for native-controls.py:40 and retention-control.py:34**: without FORBRIC_OLD both take
  `forbric-kernel/.dev/staged` (as build.gradle) and otherwise refuse by name; never forbric-loader/. (Review A F1 showed the
  `.dev` fallback was taken by presence and still booted an unstamped 26.2.0.88 carrier; since b672a5b9 a stage must carry
  this tree's build stamps, see "REL review fixes".) **native-controls.py:96**
  is not a stage but the Fabric server launcher's download cache (`forbric-loader/run/downloads/fabric-server-26.2/`, its only
  copy on this machine): `prepare --family fabric` now names the missing launcher/cache instead of failing inside zipfile;
  moving that jar out of forbric-loader/ is Stage 5's fixture move (handoff).
- test_native_controls: 32 OK (28 at 495e48fc; the forbric-loader fallback test replaced, four added: ControlFamilies
  ×3, ControlPort). Red control (`red/native-controls/`): the new tests against 495e48fc's native-controls.py → 5 red
  (failures=2, errors=3); tip 10/10 green. Stub-image control: 495e48fc's build on a NeoForge-only stub image raised
  `RuntimeError('prepare fabric before compiling native APIs')`; the tip built `['neo']`.
- Real run (`native-controls-real/`), with the prepared Fabric and NeoForge images APFS-cloned from s4c5-verify (no
  MinecraftForge image; clones deleted afterwards): `build` rc 0 (fabric, neo); `run --engine native` fabric pass, neo
  pass; `run --engine forbric` fabric pass, neo pass (FORBRIC_OLD = the lane's .stage4); `compare` fabric MATCHED_PASS,
  neo MATCHED_PASS; every server on port 46860 (the lane's GATE_PORT), free afterwards.
- gate-m59 (runs `native-controls.py swap --engine forbric --port $GATE_PORT`): **30 PASS / 0 FAIL**, rc 0 (D81: 30/0).

### mac-testdata-crash-logs — decision: keep unchanged

console-crash.txt:7,9,17 and chloride-cwb-crash.txt:9-10 name `patched-mc-merged-26.2.jar` because they are verbatim crash
reports, read by mac/test_ddmin.py as parser input. The parser does not key on the jar name (`grep patched-mc
mac/*.py`: none), so the name is neither a claim nor a dependency; editing it would change test inputs, not correct a
statement. mac suite 94 OK.

### Critic 6 — PROTOCOL.md stale prose — done (9815e794)

field-drift.py's "vanilla and merged" (:45), gate-m25's "Forge half expected red until workstream D" and m26's born-red
history (:526-532, rewritten to what the gates check now; no gate carries `# EXPECTED: RED until` today, counted 0),
gate-m28's "Forge's own COMMON files" (:540), the Forbric arm's forbric-loader fallback (:595-600), plus the release-capture
paragraph (:570-590), the push-and-run steps (:232-245) and the native-controls paragraph.

### Critic 25 — REL's checks — met: the staged-base binding red control is M1 and the real-file run above.

## Tests and gates at the lane tip (9815e794)

- Full suite (`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test
  --continue`): **5953 tests, 5 failures, 0 errors, 0 skipped** (914 XML); the 5 = c5/failures-w2d.txt's D58 rows by name;
  +2 tests = PushAndRunTest's two new ones. 0 new failures.
- run/compat `unittest discover`: 288 OK; mac: 94 OK; tools/test_pins: 6 OK.
- gate-m0 step 6's exact tool-test command (test_evidence test_soak test_world_parity test_gate_locale test_gate_cache
  test_control_arm): 103 ran, OK, none skipped. gate-m0 itself was not run (D87/D89): its other steps read nothing REL
  changed, and its unit-suite step is the full suite above.
- gate-m59: 30/0. Native controls real run: 2 × MATCHED_PASS.
- No CI skip TSV rows: the two new JUnit tests use no fixture and cannot skip.

## Open, with owners

- **LINK**: releaseAssets must stop passing `--publish merge-tools=` and depending on buildMergeTools (its
  `release-assets-drop-merge-tools`); until then releaseAssets fails on the refusal above. Merge both before ACCEPT.
- **CI**: forbric-loader/run/build-merged-base.sh:143 calls the removed `merge-provenance` subcommand; its only automated
  caller is build.yml's merge-loss ratchet step, which CI's `ci-delete-merge-loss-ratchet` deletes. A hand run of
  build-merged-base.sh between C6 and Stage 5 now ends in an argparse error after building.
- **Stage 5**: evidence.py `staged_root`'s forbric-loader/run fallback goes with build.gradle's; the official Fabric server
  launcher (`forbric-loader/run/downloads/fabric-server-26.2/fabric-server-mc26.2-loader0.19.5-launcher1.1.2.jar`) moves
  with the fixtures before forbric-loader/ is deleted (native-controls `prepare --family fabric` reads it); tools/test_pins.py
  still reads build-merged-base.sh's userdev defaults (scout not_c6).
- **Everyone running native-controls, retention-control or release evidence**: since b672a5b9 / 6ab2a9fc an unstamped stage
  is refused (the main checkout's `.dev/staged`, which every worktree's `.dev` links to, is unstamped 26.2.0.88): set
  `FORBRIC_OLD` to a stamped stage such as a lane's `.stage4`, or re-run `tools/dev.py prepare` in the main checkout (not
  done here: it would write the main checkout's files).
- **ACCEPT**: the release rehearsal on the merged tree, stopping before any upload: no draft tag and no draft release
  (owner answer 1, D93). `evidence.py run --release -- gates-all.sh --release` is red there by D58 (owner answer 2: the
  LiquidBounce jars stay out, so gate-m0 stays red; D87a), so the release flow can be exercised but not passed until
  D58 is lifted. The Windows PCL2 install (owner answer 4, D93): new installer first, since push-and-run refuses an old
  profile.

## REL review fixes (reviews A and B of 148e3308)

Both reviews were checked finding by finding against the code and real files. Every finding held; none was refuted.
Logs: `~/Forbric-backups/stage4-notes/c6/rel/review-fixes/`.

| finding | verdict | fix |
|---|---|---|
| A-F1 (critic 7 not met in substance: the `.dev` fallback booted the main checkout's unstamped 26.2.0.88 carrier) | confirmed on real files: in this worktree `.dev` → the main checkout's, no `.pins` beside base or carrier | b672a5b9 |
| A-F2 / B-2 (`prepare` no longer installs MinecraftForge, `breath-drown.py reference` still needs it) | confirmed | b672a5b9 |
| B-7 (retention-control refused late; the change never exercised) | confirmed | b672a5b9 |
| A-F3 / B-5 (`build --family neo` still needed the Fabric API jar) | confirmed | c0ac64c6 |
| A-F8 (`build-inputs.json` mixed provenance) | confirmed | c0ac64c6 |
| A-F5 (release evidence did not tie the base to NeoForge 26.2.0.93) | confirmed: the .88 stage's base and `.stage4`'s are byte-identical (SHA-1 67bbf24f); the base's `version.json` names no NeoForge | 6ab2a9fc |
| A-F6 / B-4 (`verify`'s sidecar re-check untested) | confirmed (mutation E2 green before) | 6ab2a9fc |
| A-F7 / B-6 (a refused real run left its evidence directory) | confirmed | 962ac3bd |
| A-F4 / B-3 ("Forbric 0.4 or earlier" wording against owner answer 3) | confirmed | 962ac3bd |
| B-1 (ACCEPT hand-off against owner answers 1 and 2; `true` stand-in) | confirmed | this record |
| B-8 (record evidence gaps) | confirmed | this record (below) |

### What changed

- **Stage provenance (A-F1).** `native-controls.forbric_stage()` takes `FORBRIC_OLD`, else the prepared `.dev/staged`,
  and now accepts either only when the build stamps beside its base and carrier begin with the installer's
  `Pins.stamp()` (`tools/dev.py`'s `stale_stamps`, loaded from this checkout; the rule push-and-run applies). Otherwise
  it refuses, naming each stale stamp. On real files: without `FORBRIC_OLD` this worktree is refused
  (`the prepared stage …/forbric-kernel/.dev/staged is not a stage this tree's pins built: run/…patched-mc-neoforge-26.2.jar.pins,
  run/…neoforge-runtime.jar.pins missing …`); `FORBRIC_OLD=.stage4` is accepted (`F1-real-files.txt`). The "never .88"
  sentences in the docstring, the README and PROTOCOL.md are now true and say how.
- **retention-control (B-7).** It resolves the stage before it compiles or runs anything. Run on real files without
  `FORBRIC_OLD`: rc 1 with the stamp refusal, before javac (`B7-retention-no-stage.txt`). The success path (both arms with
  a stamped stage) was not run: it needs the Unlit Campfire jar under `run/client-merged-pack/`.
- **MinecraftForge install users (A-F2, B-2).** The docstring, the README, PROTOCOL.md (:189 and the native-controls
  paragraph) and `canary/forge-breath-drown/README.md` name `breath-drown.py reference` beside the swap oracle;
  breath-drown's missing-image hint says `prepare --family <family>`.
- **NeoForge build without Fabric API (A-F3, B-5).** The jar is read, copied and recorded only when Fabric is built; a
  Fabric build without it names the missing jar first. Real run: `NATIVE_CONTROL_CACHE=<empty dir> build --family neo`
  rc 0 (families `['neo']`, no `fabricApi` key), then `run --engine native --family neo` passed on port 46860
  (`F3-real-neo-build-no-fapi.txt`; the NeoForge image was an APFS clone from s4c5-verify, deleted afterwards).
- **build-inputs.json provenance (A-F8).** Each family entry records its own `javac` and, for Fabric, `fabricApi`; no
  top-level values. An earlier entry is kept only while the canary sources are current and every file it records still
  has the recorded bytes. The Fabric run checks the Fabric API jar it copies against the record.
- **Base tied to the pinned NeoForge (A-F5).** A capture records `stagedBuildStamps` (the `.pins` beside the staged base
  and carrier); a release requires both to say `mc=26.2` and `neoforge=26.2.0.93`; `verify` fails when a stamp changes
  after capture; a release manifest without them does not verify. On real files `.stage4` passes and the main checkout's
  `.dev/staged` is refused with both stamps named (`F5-real-stamps.txt`). The stamp is DevPrepare's record of how the
  base was built, not a reading of the base's bytes: the base carries no NeoForge version to read.
- **verify's re-check (A-F6, B-4).** New test: a non-release capture over a sidecar measured against another base (and,
  separately, over an unstamped carrier), flipped to `"release": true`, must not verify.
- **Refused real run (A-F7, B-6).** The profile is judged right after it is fetched and before the kernel build; a run
  that stops before staging anything (refused profile, failed fetch, failed build) removes the `version.json` and the
  directory it created (an existing empty directory is left as found).
- **Wording (A-F4, B-3).** The refusal line, the `RETIRED_ARTIFACTS` comment, PROTOCOL.md :45 and :249 and the
  PushAndRunTest comment name the old installer by what it staged ("one that staged the merged base, before
  MinecraftForge was dropped"), with no version; the test's new-installer fixture uses kernel 0.4.0. Not REL's: the
  pre-existing texts owner answer 3 lists (GameArtifacts, LaunchInputCheck, DialogLang and zh) stay with the later
  rewording lane.

### Red controls (each red on the mutation, green on the tip)

| control | mutation | result |
|---|---|---|
| F1 | `forbric_stage` without the stamp check (148e3308's behaviour) | test_a_stage_this_trees_pins_did_not_build… FAILED (failures=1) |
| F3/F8 | b672a5b9's `compile_canaries` and `run_control` under the new tests | 3 errors: neo build FileNotFoundError on the absent Fabric API jar (×2), the Fabric run went past a changed Fabric API jar |
| F8 | `still_built` not applied | test_a_later_build_keeps… FAILED (failures=1) |
| E2 (A-F6) | `verify` skips the sidecar re-check | test_verify_judges_a_release_manifests… FAILED (failures=1) |
| S1 | capture skips the stamp check | test_release_requires_the_staged_base_and_carrier… FAILED (failures=4) |
| S2 | `verify` skips the stamp check | test_verify_judges… FAILED (failures=1) |
| S3 | `verify` ignores a changed stamp | test_release_requires_the_staged_base_and_carrier… FAILED (failures=1) |
| S4 | the stamp's `neoforge=` not judged | same test FAILED (failures=2) |
| F7a | 962ac3bd's parent `push-and-run.py` under the new tests | PushAndRunTest 24 tests, 1 failure (aRefusedRealRun…: `version.json` left) |
| F7b | the clean-up call removed | 24 tests, 2 failures (the refused run and the failed build both leave the directory) |

Logs: `review-fixes/F1-red-no-stamp-check.log`, `F3F8-red-old-compile.log`, `F8-red-no-still-built.log`,
`red-evidence/`, `F7-red-old-order.log`, `F7-red-no-discard.log`.

### Record gaps (B-8)

- mac suite: 94 OK, now logged (`review-fixes/mac-py.log`).
- Where the dry-run profiles came from (no download by REL; `review-fixes/dry-run/PROVENANCE.txt`): 0.1.0 = the main
  checkout's `forbric-kernel/build/sweep80-mac/backup-old-install/26.2-forbric.json`; 0.2.0 = the main checkout's
  `forbric-kernel/run/installed/versions/26.2-forbric/26.2-forbric.json`; v0.3.1-beta2 (the release on the Windows
  machine) = the install lane INST laid out from its owner-allowed download (`s4c5-c6inst/.../.gate-downloads/m17/
  v0.3.1-beta2-install/versions/26.2-forbric/26.2-forbric.json`), read only here.
- Dry runs at the new tip (`review-fixes/dry-run/`): stage-tip installer's profile rc 0 `PROFILE_REWRITES none`; reordered
  rc 0, game arguments only; 0.1.0, 0.2.0 and **v0.3.1-beta2** rc 2 with the refusal (beta2: `it names
  net.forbric:forbric-loader:0.3.1-beta2; … patched-mc-merged:26.2 …`).
- The native-controls red control of 148e3308 had 3 of its 5 reds as AttributeErrors (functions absent in 495e48fc's
  code); the behavioural reds for this round are the mutations above.

### Tests and gates at the new tip

- Full suite (same command as above, at 962ac3bd's tree): **5954 tests, 5 failures, 0 errors, 0 skipped** (914 XML); the
  5 = c5/failures-w2d.txt's D58 rows by name; +1 test = PushAndRunTest's `aRefusedRealRunBuildsNothingAndLeavesNoEvidenceDirectory`.
  0 new failures (`review-fixes/suite.log`, `failures-rel-review.txt`).
- PushAndRunTest 24/0; run/compat `unittest discover` 292 OK (+2 test_native_controls, +2 test_evidence); mac 94 OK;
  tools/test_pins 6 OK; gate-m0 step 6's tool-test command 105 ran, OK, none skipped.
- gate-m59 (`swap --engine forbric` boots `forbric_stage()`): **30 PASS / 0 FAIL**, GREEN, CAUGHT none, run as
  `client.sh heavy.sh` on port block 46860. A first attempt was started without `client.sh` while lane INST held the
  client lock for gate-m17; it was killed within about a minute of its client starting and is not counted
  (`gate-m59.aborted-no-client-lock.out`). INST's m17 run in that window overlapped with that client.
- gate-m36 (evidence.py `run` per phase; its manifests now carry `stagedBuildStamps` = `.stage4`'s 26.2.0.93 stamps):
  **6 PASS / 0 FAIL**, rc 0 (C5 baseline 6/0).
- No CI skip TSV rows: the new tests use no fixture.

## Player-facing wording

None of REL's items changes what a player sees; no draft wording.
