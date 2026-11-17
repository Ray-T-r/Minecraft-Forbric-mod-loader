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
   one is `gates-all.sh --release`, lane ACCEPT's) — rc 0, inputs unchanged.
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

Stopped there: no `gh release create`, no upload, no install from a release. Observation for LINK/ACCEPT: the published
kernel asset is named by the installer version (`forbric-kernel-0.4.0-dev.jar`), so owner question 4's version bump
changes the asset name too.

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
  C6 installer has to run there first (owner question 9 covers that install and what its clean-up deletes).

### native-controls-without-forge-image — done (d35cecf6)

- `CONTROL_FAMILIES = ("fabric", "neo")`: `build` and `run` take `--family all|fabric|neo`; `build` compiles only the
  families asked for, each against its own image; build-inputs.json keeps another family's entry only while the canary
  sources are unchanged. `VERSIONS["forge"]` and the MinecraftForge install path stay for the swap oracle (D32,
  `prepare --family forge` only; `prepare` "all" = fabric+neo). canary/native-controls/forge/ deleted.
- Ports: `control_port()` = `GATE_PORT`, else an OS ephemeral port (was 25651 + index, +10 for Forbric); recorded in
  inputs.json. retention-control.py's two arms use it too (were 25681/25682).
- **Critic 7 — done for native-controls.py:40 and retention-control.py:34**: without FORBRIC_OLD both take
  `forbric-kernel/.dev/staged` (as build.gradle) and otherwise refuse by name; never forbric-loader/. **native-controls.py:96**
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
- **ACCEPT**: the real rehearsal (gates-all --release, draft tag, upload, slim install) and the Windows PCL2 install
  (new installer first; push-and-run refuses an old profile).

## Player-facing wording

None of REL's items changes what a player sees; no draft wording.
