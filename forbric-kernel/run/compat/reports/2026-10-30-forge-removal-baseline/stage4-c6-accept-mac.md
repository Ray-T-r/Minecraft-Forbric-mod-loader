# Stage 4 C6 ACCEPT — lane MAC (2026-11-20)

Lane MAC of ACCEPT (D98, owner "开始验收"), in the stage worktree `suspicious-chaum-b28730` itself, branch
`claude/remove-forge-neoforge-fabric-83b024` at **df1e5a13** (= origin `ci/remove-forge-stage2`, CI run 44299805668 green on
all 6 jobs). Nothing was fixed, pushed, tagged or uploaded; no draft release; main untouched (local 511f2582, origin
f7f4ff92). The only commit is this record. Logs: `~/Forbric-backups/stage4-notes/c6/accept/mac/`.

## Environment (every run)

`env.sh` in the log folder: `FORBRIC_OLD=<stage>/.stage4` (both stamps `mc=26.2 neoforge=26.2.0.93 nfrt=2.0.18`),
`FORBRIC_NEOLIVE=<stage>/.stage4/run/neoforge-runtime/forbricneolive.jar`, `FORBRIC_GATE_CACHE=0`, `GATE_PORT=46800`,
`M12/M13/M14/M16/M28/M32_PORT=46801-46806` (gates-all assigns its own blocks 46800-46839 per slot), `HEAVY_LANE=accept-mac`,
`MC_DIR` unset, `M17_SEED_CACHE` unset. JDK Temurin 25.0.3+9. Tip df1e5a13, dirty=0 at the start of every run
(`env-*.txt`). Pinned build, plain `--offline` from `.stage4/props.txt`, no init script, no private GRADLE_USER_HOME,
`--no-daemon` on every top-level Gradle call of mine; heavy commands through `.stage/heavy.sh`, client runs through
`.stage/client.sh` first. No zh run (D88b). No mod jar or GitHub asset downloaded.

## 1. Full unit suite — 5969 / 0 skipped / 5 failed = the D58 five

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test --continue`,
00:10:05-00:13:41 (3m36s). 917 XML (`suite-xml/`), log `suite.log`.

- Failures name for name = `c6/failures-w2.txt` (`failures-accept.txt`, diff empty): LiquidBounce ×3
  (FabricClientMixinAnchorsTest ×2, MixinTwinRebindTest ×1, third-party fixture absent) and SharedFinalSourceActualTest ×2
  (opt-in fixture absent). Exempt per D58/D87a, owner answer 2.
- Per class against merger-w3's 662fdafb XML: 917 = 917 classes, 0 new, 0 gone, 0 classes with different counts
  (`suite-compare.txt`).

## 2. No-cache gates-all -j 4 — 59 GREEN, m0 RED on D58 only, m34 skipped

Wrapping: `evidence.py run --release` refuses any acceptance command that holds `--skip` ("a release acceptance runs every
gate; --skip makes it a sweep, not an acceptance", measured in step 4 as R1), so the wrap would have changed what runs
(m34 in, against D84). gates-all therefore ran plainly and the evidence wrap separately (step 4).

`client.sh heavy.sh --exclusive bash run/compat/gates-all.sh -j 4 --no-cache --skip gate-m34-soak.sh`, 00:14:42-00:50:37
(**35.9 min**; C5 acceptance at 394b8804: 37.0 min). Output `gates-all.out` (= summary.txt), logs `gates/`, per-gate counts
`gates-counts.txt`.

- 61 RESULT lines: **59 GREEN**, `gate-m0.sh RED (exit=1)`, `gate-m34-soak.sh SKIP (explicit --skip)`. 0 `cached:`.
- gate-m0 **18 PASS / 1 FAIL**; the FAIL is D58 ("unit tests — 3 of 5969 skipped …"). Exempt (D87a).
- `[kernel] CAUGHT none` in all 60 gate logs; no other CAUGHT line.
- `[kernel] SKIP (C6` lines: **0** in the output and in all 60 logs; no `[kernel] SKIP` line of any kind.
- Counts of note: m9 163/0, m17 164/0, m24 198/0, m24d 116/0, m56 143/0, m58 208/0, m59 30/0, m12 44/0, m16 49/0.
- **m17 / INSTFIX R-5 (phase U client stall): not seen.** A watcher (`m17-watch.sh`, log `m17-watch.log`) was ready to
  `jstack` any m17 resolved client alive past 150 s; none was. m17's two resolved clients reached their outcome after
  ~88 s and ~93 s (phase U's included). No jstack taken because nothing stalled.
- m24d: GREEN 116/0, so no exit-1 outcome to judge (D91).
- No gate other than m0 was red, so no single-gate re-run was needed.
- Two Gradle daemons were left by the sweep's own gradlew calls (gates-all's prebuild at 00:14:42 and one at 00:21:11, both
  `currentDir=` this worktree; none existed before): killed after the run (`daemons-*-gates.txt`). Known harness item.
- `run/.gate-clones` (2.5 GB, created by this sweep) deleted afterwards.

## 3. integrationTest — red on the D58 five only

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) integrationTest --continue`, 00:51:34-00:56:14 (4m40s), log
`integrationTest.log`. BUILD FAILED, and only on `:test`:

- `:test` 5969 tests / 0 skipped / 5 failed — the same five names as step 1 (diff empty). This red set **equals D58**.
- `:transferTest` 57 / 0 skipped / 0 failed (XML `integrationTest-xml/`).
- `:toolTest` tools 56 OK, run/compat 292 OK, mac 94 OK (none failed).
- `:installerTest` "11 test classes ran, 0 failed" (OldInstallCleanupTest OK, ReproducibleRuntimeTest OK).
- The `integrationTest` task's own strict check never executed, because its dependency `:test` failed; with `:test` red the
  task cannot be green while D58 stands (owner answer 2).

## 4. Release rehearsal up to (not including) any upload

Script `rehearsal/rehearsal.sh`, 00:56:36-01:02:33 (6.0 min), every step in `rehearsal/steps.txt`. Run on the stage worktree
itself (the merged tree df1e5a13, clean), so this is also the re-run of REL's releaseAssets rehearsal on the merged tree
that check-w2 said was missing. FORBRIC_OLD = the stage's stamped `.stage4`. Artifacts: vanilla
`.stage4/minecraft/versions/26.2/26.2.jar`, neo-patched/neo-runtime/sidecar from `.stage4/run`, kernel
`forbric-kernel-0.1.0-SNAPSHOT.jar` (sha1 d6c9bbae…) and kernel-runtime (def7fe0a…) built as buildForbricJars builds them
(`./gradlew --no-daemon jar`), mods = an empty directory (as REL).

| run | acceptance command | result |
|---|---|---|
| R1 | step 2's command under `--release` (`gates-all.sh -j 4 --release --no-cache --skip gate-m34-soak.sh`) | rc 1 before capture: `a release acceptance runs every gate; --skip makes it a sweep, not an acceptance` |
| R2 | the real release acceptance `gates-all.sh -j 4 --release`, restricted to the D58 gate with `--only gate-m0.sh` (the other 59 gates are step 2; m34 is step 5) | rc 1: `command exit=1, inputs unchanged=True`, m0 **RED** 18/1 on D58 (CAUGHT none), 60 unpassed gates (every other gate SKIP "not selected", which `--release` counts as unpassed); `commandPassed=false`; 291 s. `evidence.py verify` of its manifest: unchanged |
| R3 | REL's stand-in `true` | rc 0, inputs unchanged. A stand-in: it says nothing about acceptance |

- `releaseAssets -PreleaseTag=v0.0.0-c6accept-rehearsal -PreleaseEvidence=r2` → **rc 1**: `release check failed:
  r2-release-m0.json: its acceptance command did not pass`. This is what the release flow reports while D58 stands.
- `releaseAssets … -PreleaseEvidence=r3` → rc 0: `release check passed: 1 manifest(s), 6 role(s), 1 published file(s)
  match`, 15 jars + manifest (forbric-kernel 0.4.0-dev and its 14 libraries; no game artifact, no merge tools —
  `assets-listing.txt`, `assets-sha1.txt`). The published `forbric-kernel-0.4.0-dev.jar` is byte-identical to the attested
  kernel jar. LINK's releaseAssets change is in the tree, so REL's step-3 refusal (`merge-tools: not a release role`) is gone.
- Slim pinned installer `jar -Pslim -PreleasePin=build/release/forbric-release.properties` → rc 0; sha1 8dc079e5…,
  282 222 bytes; 0 bundled manifest entries; its `forbric-release.properties` is byte-equal to the pin releaseAssets wrote
  (repo = the public GitHub repo, tag v0.0.0-c6accept-rehearsal, manifestSha256 6b1cbb56…).
- **Install from the local assets into a scratch .minecraft**: the slim jar, unchanged, with `--mirror
  http://127.0.0.1:46890/` and a local server (`rehearsal/asset-server.py`) answering `…/releases/download/<tag>/<file>`
  from the releaseAssets output, into an empty scratch directory, `--mc 26.2`, English pinned → **rc 0 in 48 s**,
  "Installed. In your launcher, pick the version "26.2-forbric"". The installer said `Forbric jars : GitHub release
  v0.0.0-c6accept-rehearsal (manifest pinned) via http://127.0.0.1:46890/`; the server served exactly the manifest and the
  kernel jar (2 × 200, 0 × 404; the 14 third-party jars came from Maven as designed). Cold path: Mojang's 26.2 jar, NeoForge's
  userdev/NeoForm/runtime artifacts and installertools were downloaded (D68). Both link checks: `dangling references: 0`.
  `[cleanup] removed 0 file(s)`. Installed `net.forbric` jars: forbric-kernel 0.4.0-dev (sha1 d6c9bbae… = the attested
  kernel), patched-mc-neoforge 26.2-26.2.0.93 (67bbf24f… = `.stage4`'s base), neoforge-runtime 26.2.0.93, vanilla-reference
  26.2-26.2.0.93; profile `26.2-forbric` inherits 26.2, mainClass `net.forbric.kernel.boot.KernelClientLaunch`. 505 MB;
  deleted afterwards.
- Stopped there: no `gh release create`, no upload, no tag, no draft release (owner answers 1 and 2). `build/release` deleted
  and the normal installer jar rebuilt (`restore-installer-jar.log`). Two Gradle daemons from R2's own gradlew calls killed.

## 5. m34 soak

Run as its gate script runs it: `client.sh heavy.sh --exclusive bash run/gate-m34-soak.sh` (fixture
`run/client-merged-pack`, the stage's `.stage4`), environment `env-m34.txt`. **Result: RED, not a release soak.**

- **Attempt 1, 01:03:16-01:03:20 (4 s): FAIL before any client started** — `[M34] FAIL native retention evidence missing
  for de.cech12.unlitcampfire.CommonLoader.CAMPFIRES: run python3 run/compat/retention-control.py`
  (`gate-m34.attempt1.out`). soak-run.py requires `build/retention-control/comparison.json` (named by
  `run/compat/native-retention.json`) because the fixture holds that Unlit Campfire jar; that file is a gitignored build
  output which nothing in the gate or gates-all writes, and this worktree had none (finding F1).
- To get the soak to run at all I ran the producer the message names, once: `heavy.sh python3
  run/compat/retention-control.py` (GATE_PORT 46800, FORBRIC_OLD = `.stage4`, the stage's existing NeoForge 26.2.0.93
  server image from 2026-10-30, no download), 16 s, rc 0: both arms `nativeRetentionReproduced: true`
  (`campfiresReferencingStoppedServer` 1 on native and on Forbric), `sameModHashes: true`
  (`retention-control.log`, `retention-control/`).
- **Attempt 2, 01:04:22-02:21:18 (4616 s = 76.9 min of the ≥ 7200 s soak): FAIL** — `[M34] FAIL releaseAccepted=False`,
  `[kernel] FAIL M34 soak-run stopped with exit 1`, `[kernel] CAUGHT none`; the client's kernel caught 0 failure sites
  (`caught.tsv` empty). `acceptance.json`: `status FAIL`, `detail "client exited abnormally: 143"`, processSeconds 4611.6;
  11 world sessions (disconnect thread dumps 1-11) before the crash. Evidence copied to `m34/` (acceptance.json, crash
  report, client.log.gz, telemetry.jsonl.gz); the run directory stays at
  `forbric-kernel/build/soak-runs/272912f0-417c-4c86-9d2b-7691991012c2/` (621 MB, gitignored) for anyone reproducing.
- The client crashed at JVM uptime 4610 s on the Render thread with `java.lang.IllegalArgumentException: bound must be
  positive` at `java.util.Random.nextInt` ← `EVManagerClient.addParticlesFadeOut:221` ← `RainHandler.tick:70`
  (EnhancedVisuals 1.8.29 NeoForge, via CreativeCore's client-tick listener) — the first time the player stood in rain.
  Root cause and native comparison under finding F2.

## Defects and findings

**F1 (harness; owner: orchestrator / a later harness lane). gate-m34 cannot run in a tree without a hand-made
`build/retention-control/comparison.json`.** `soak-run.py verify_native_evidence` reads the path
`run/compat/native-retention.json` names for every reviewed root whose mod jar is in the run; for the m34 fixture that is
Unlit Campfire, so the gate stops in 4 s with FAIL unless someone ran `run/compat/retention-control.py` earlier in that
worktree. Neither gate-m34 nor gates-all produces it, and the file is in gitignored `build/`, so a fresh worktree or a
cleaned build always fails m34 this way. Evidence: `gate-m34.attempt1.out`; after one run of the producer the gate went on
to soak (attempt 2). Not a kernel defect; not fixed here.

**F2 (m34 red; a mod defect that native NeoForge 26.2.0.93 has by its own load order — owner decides how m34 treats it).**
- **Symptom.** EnhancedVisuals' rain overlay calls `random.nextInt(ct.getVariantAmount())` and the drop visual has 0
  variants, so the first rain the soak walks into kills the client (crash report `m34/crash-2026-11-20_02.21.17-client.txt`,
  client.log from line 7670 at 02:21:16, where NeoForge's event bus logs the exception and its cause chain, one second before the crash report).
- **Why 0 variants.** *(Corrected after check A-1 and the native A/B, `stage4-c6-ev-native.md`.)* EnhancedVisuals loads its
  textures in two places: `EVClient.init()` (its client setup) calls
  `VisualTypeClient.loadResources(Minecraft.getInstance().getResourceManager())` for every visual type, and `EVRenderer.render`
  (a `RenderGuiEvent.Post` listener through CreativeCore) reloads them when `EVRenderer.reloadResources` is set, which its reload
  listener `EVClient$1.apply` does at every resource reload. That HUD-frame reload is gated by `EVClient.shouldRender()`, false
  for spectators and for creative players unless `doEffectsInCreative=true`; the soak player is creative and the fixture has
  `doEffectsInCreative=false`, while `RainHandler.tick` ignores game mode, so the variants stay at 0 (javap of the fixture jar,
  sha1 2f55bcf5…). The native client A/B since run (20 runs) reproduces the crash on native NeoForge 26.2.0.93 identically. In
  the soak's client.log the kernel posts common setup and client setup at 01:05:18-01:05:20 ("HELLO FROM CLIENT SETUP"),
  EnhancedVisuals logs `Could not find any resources for '<type>'!` 21 times (all 20 types, `drop` among them) on
  modloading-worker-0 at 01:05:18, and the first `Reloading ResourceManager: vanilla, …, forbric/EnhancedVisuals…` comes at
  01:05:20, after setup. CreativeCore's "Could not load default style" is the same mechanism.
- **Native comparison (by bytecode here; measured since, `stage4-c6-ev-native.md`).** Genuine NeoForge 26.2.0.93 does the same: in the stage's
  patched `Minecraft.<init>` `ClientModLoader.finish()` is at insn 3081 and the first `ReloadableResourceManager.createReload`
  at 3155; `finish()` calls `CommonModLoader.load`, which dispatches the setup events (`ModLoader.dispatchParallelEvent`)
  — so on native NeoForge client setup also runs before any pack is in the resource manager. This is the measurement C3
  recorded when it deleted the client resource preload (b7dd2f64, D16; stage2-measurements.md "preloadClientResources",
  stage4-c3-runtime-injectors.md row 30: "the only new lines are EnhancedVisuals' 20 'Could not find any resources' …
  native NeoForge behaviour"). What C3 did not record: those 20 warnings are not harmless — the first rain crashes the
  client. This lane did not run a native NeoForge client; the native A/B run afterwards did (`stage4-c6-ev-native.md`: 20
  runs, native and Forbric agree in all 12 arms), so "native crashes the same way" is now a measurement.
- **Consequence for acceptance.** Under the guiding rule this is native behaviour, not a Forbric defect, but it is a
  player-visible regression against the merged-base builds, whose preload gave the resource manager its packs before mod
  setup (8c58428d), so EnhancedVisuals found its textures there (C3: the 20 warnings are new after b7dd2f64). And m34 cannot pass at this tip whenever the overworld rains on the
  player inside the soak window (here at 76 min), so m34's verdict now depends on the weather. Owner decides: accept native
  parity and change m34's fixture/route (e.g. without EnhancedVisuals, or rain-free), or restore a resource preload as a
  NeoForge-side leniency, or measure a native client first. Not fixed here.

**Otherwise no defect found.** Suite, gates-all and integrationTest are red only on D58 (exempt, D87a / owner answer 2).
INSTFIX R-5 (m17 phase U client stall) did not recur. The release flow refuses to publish without a passed acceptance
(R2), and passes end-to-end to a pinned slim installer that installs from the released assets (R3 stand-in).

## Not done by this lane

- No full `gates-all.sh --release` with m34 inside (it would have re-run step 2's 59 gates and m34; R2 shows the verdict
  it must give while D58 stands). No native client A/B for F2 in this lane (run afterwards: `stage4-c6-ev-native.md`). No re-run of m34 (2 h, and its outcome is decided by F2).
- The Windows half of ACCEPT (lane WIN) is not in this record.

## Clean-up

Every process I started is gone: the m17 watcher, the asset server, all game JVMs; the six Gradle daemons my runs'
own un-flagged gradlew calls left (gates-all 2, R2 2, m34 attempt 1 and 2 one each; none existed before) were killed. Ports 46800-46899 free.
`.stage` holds no lock. Deleted: `run/.gate-clones` (2.5 GB), the scratch install (505 MB), `forbric-kernel-installer/build/release`,
the attempt-1 soak copy (315 MB). Kept (gitignored): `build/retention-control/` (141 MB, m34's input now), the attempt-2 soak run
directory (621 MB, F2's evidence), `build/gates/` (R2's m0-only sweep; step 2's logs are in `gates/`), `run/installed*` (m17).
The normal installer jar was rebuilt after the slim one. Worktree clean before this record; the only commit is this file.
Disk 28 → 24 GiB free.
