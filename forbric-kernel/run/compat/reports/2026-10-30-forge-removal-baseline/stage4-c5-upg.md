# Stage 4 C5, lane UPG: W3 (world-snapshot), F1 (D43 carrier pack id), and the review fixes

Branch `s4-c5-upg` (started from `cd6d0fe8` in wave 1, rebased onto the stage tip `f0cb66b0` in wave 2), worktree `s4c5-upg`, ports 46740-46759. Logs: `~/Forbric-backups/stage4-notes/c5/upg/`
(D67). This record covers the 2026-11-09 run: the 2026-11-02 work committed, then the findings of the two lane reviews
(`c5/review-upg-A.md`, `c5/review-upg-B.md`) fixed or refuted. U1 and U2 were reached in wave 2 part 1, the canaries,
gate-m58, its calibration and the nightly change in wave 2 part 2, and the findings of the two wave-2 reviews (sections at
the end); what is still open is under the last section's "Open".

## How the wave-1 sections were measured (read first)

This section applies to "Commits", "Results" and "Review findings" (wave 1) only. Every wave-2 section below used the
pinned build (D68: plain `--offline` with `.stage4/props.txt`, no init script, no private `GRADLE_USER_HOME`), which
retired this route.

`~/.gradle/caches` was recreated on 2026-11-08 and lost the kernel's dependencies. No lane may download, so every wave-1
Gradle run in this record resolves through an init script that adds a file repository of jars already on disk
(`c5/upg/offline-local.init.gradle`, repository `c5/upg/localrepo`, 18 jars, sha1 prefixes in `c5/upg/localrepo.sha1`).
Two substitutions differ from the real build:

- `org.jetbrains:annotations` 23.0.0 -> 26.0.2 (compileOnly; 23.0.0 is nowhere on this disk);
- JUnit Jupiter 5.9.2 -> 5.11.4 and the platform 1.11.4 (the versions the cache holds).

Gates run with `GRADLE_USER_HOME` set to an APFS clone of `~/.gradle` whose `init.d/` holds the same script, because the
gate scripts call `gradlew --offline` themselves (`c5/upg/run-gate.sh`). The real-build re-check is owed once the cache is
restored.

## Commits

| sha | finding | subject |
|---|---|---|
| `cbbd731c` | W3 (2026-11-02 work), B-1 | world-snapshot.py + test + game-written testdata; world-parity.py reads typed NBT |
| `89b640b1` | A-7 | the game-written-bytes test fails, not skips, without its testdata |
| `1142e1ee` | A-2 | non-empty ForgeCaps and a lone non-empty ForgeData are reported as losses under `--rules upgrade` |
| `88943a1a` | B-5 (partly), A-5 | world-snapshot.py's docstring says where it sits and which installs gate-m58 means |
| `96a08593` | A-1 | the nightly test's stand-in refuses an unknown `--skip`; every weekday-skipped gate must exist in the tree |
| `001dcfdb` | F1 / D43 (2026-11-02 work) | one NeoForge carrier pack id; in-place rename of old ids on load (injector + runtime hook + tests) |
| `a26e297a` | A-4 | KernelRuntimeClasses row moved off KernelClientPackSource's comment |
| `3e33e7c4` | A-5 | "Forbric 0.4 or earlier" -> v0.3.1-beta2 or older, or a 0.4.0-dev build that still had MinecraftForge |
| `6a6d8110` | A-6 | a second NeoForge carrier falls back to its file-name id, with a WARN |
| `66640804` | A-3 / D47 | ci-unstaged skip row for `CarrierPackIdMigrationInjectorTest.theGenuineBaseHasTheAnchor()` |
| `24fdbe08` | suite red at `66640804` | an @ExecutesInjector test runs the injector's output (InjectorExecutionCensusTest) |
| `fa1503dc` | suite red since `cbbd731c` | PROTOCOL.md names world-snapshot.py and its test (CompatProtocolTest) |

`compileJava compileRuntimeJava compileTestJava` passed at `001dcfdb`, `3e33e7c4`, and the tip. Between `cbbd731c` and
`fa1503dc` CompatProtocolTest is red, and between `001dcfdb` and `24fdbe08` InjectorExecutionCensusTest is red; both were
found by the suite at `66640804` and fixed in the two last commits.

The D53 nightly change (weekday nights skip gate-m58 too) is NOT committed: gate-m58 does not exist yet, and with it in the
tree every weekday night would stop at "Unknown --skip gate" (A-1). It is kept as
`c5/upg/held/nightly-slow-gates.patch` and lands with or after gate-m58; applied today, the new test fails naming
`gate-m58-world-upgrade.sh`.

## Results

| check | result |
|---|---|
| full suite at `66640804` (`cleanTest test --continue`, `-Pforbric.requireFixtures=all`) | 5774 tests, 0 skipped, 43 failed: the 41 of `c4/failures-close.txt` + CompatProtocolTest + InjectorExecutionCensusTest (both new, both fixed above) |
| full suite at `fa1503dc` (tip) | **5775 tests, 0 skipped, 41 failed: the same 41 names, 0 new, 0 gone**; every failure message equal to failures-close.txt's over its first 240 characters (one differs only in a newline folded to a space) |
| test count 5767 -> 5775 | +8, all passing: KernelDataPacksTest +3, InstalledCarrierPackIdTest 1, CarrierPackIdMigrationInjectorTest 4 |
| gate-m31 (`GATE_PORT=46740`) | 13 PASS / 0 FAIL |
| gate-m31 `M31_UNFIXED=1` | 12 PASS / 1 FAIL: `every chunk carries vanilla's biomes (want 0 got 11)`, the recorded teeth |
| `test_world_snapshot.py` | 22/22 (20 before A-2's two tests) |
| `test_world_parity.py` | 4/4 |
| `tools/test_run_nightly.py` | 9/9 |
| ci-unstaged measurement (stagedRoot = a missing directory, no FORBRIC_OLD) | the only skip among the D43 classes is `theGenuineBaseHasTheAnchor()`; the execution test runs there |

Mutation red checks (each restored afterwards): `carrierPackId(jar, taken)` without the fallback -> only
`aSecondNeoForgeCarrierKeepsItsFileNameId` fails; `KernelCarrierPackIds.migrate` returning its input ->
`anOldWorldsSelectionReachesTheBodyRenamed` fails on the rename assertion; A-2's two tests failed before the fix (empty
diff); A-7's test fails with `testdata/world-snapshot/` removed from a copy.

In m31's Forbric arm the carrier is served as `forbric/carrier/2-neoforge-runtime`, the id a staged tree had before D43 as
well (its jar is `neoforge-runtime.jar`), so staged gates see no change of id.

## Review findings

| finding | verdict | where |
|---|---|---|
| A-1 nightly skips a gate that does not exist | confirmed, fixed | `96a08593`; nightly change held |
| A-2 ForgeCaps / lone ForgeData dropped silently | confirmed, fixed | `1142e1ee` |
| A-3 missing ci-unstaged row | confirmed, fixed (measured locally; ci-prepared has no `[fixture:staged]` rows and needs none) | `66640804` |
| A-4 misplaced comment | confirmed, fixed | `a26e297a` |
| A-5 "Forbric 0.4" | confirmed, fixed in the lane's new text; older committed lines that say "0.4 and earlier" about the merged base were left alone | `3e33e7c4`, `88943a1a` |
| A-6 two NeoForge carriers share one id | confirmed, fixed | `6a6d8110` |
| A-7 test skips without testdata | confirmed, fixed | `89b640b1` |
| A-8 migrate never ran in a game | confirmed; now runs in a unit test against stand-ins (`24fdbe08`); the in-game proof is gate-m58's installed-name phase: 14/0 at the part-2 tip, 10/4 at `a0eeba07` (wave 2 part 2), with a mod pack after the carrier since the wave-2 review fixes | closed in wave 2 |
| B-1 Python half was not blocked | confirmed, committed | `cbbd731c`..`96a08593` |
| B-2 the proposed refill command is not enough | confirmed: the old tag 187143a1 needs `org.joml:joml:1.10.8` (cache has 1.10.9) and JUnit 5.9.2 is not cached | handoff |
| B-3 "fixing it means downloading" | confirmed wrong: the file-repo route above builds and tests offline; only annotations 23.0.0 is missing, and it is compileOnly | this record |
| B-4 no durable copy of the uncommitted work | confirmed, fixed: `c5/upg/backup-2026-11-09/` (tracked.diff + untracked.tgz) before any change | notes |
| B-5 PROTOCOL.md is G-B2's file | partly refuted: CompatProtocolTest requires every script under run/compat to be named in PROTOCOL.md, so moving the text out turned it red. Kept a three-line mention (`fa1503dc`); the full paragraph is `c5/upg/held/protocol-world-snapshot.patch` for G-B2 | `88943a1a`, `fa1503dc` |
| B-6 open items without an owner | confirmed; listed under Open with UPG as owner | handoff |
| B-7 old kernel already built once | confirmed: `build/old-kernel/187143a1…/` has `.archived`, `.built`, `build.log` (2026-11-02 23:12); its boot classpath points into the wiped cache | handoff |
| B-8 recovery partly measurable | confirmed: the recovered copies of files that also survive in the worktree were byte-identical; both recovered shell scripts pass `bash -n` | handoff |
| B-9 checks.md missing | fixed: `c5/checks.md` has a Lane UPG section | notes |

## Wave 2, part 1 (2026-11-09): rebase, pinned-build re-check, U1, U2

Rebased onto the stage tip `f0cb66b0` (wave 1 merged) without conflicts; the 13 commits above changed sha (old -> new:
cbbd731c 778c9aae, 89b640b1 e133f256, 1142e1ee f8c3bee5, 88943a1a 2e7216ac, 96a08593 a0eeba07, 001dcfdb 44ff933c,
a26e297a c58030b6, 3e33e7c4 d5fad1ad, 6a6d8110 6b3dcde2, 66640804 3db686a0, 24fdbe08 43cf5347, fa1503dc 932f9a6b,
93f6be55 1ac8011d). Everything below is the PINNED build (D68): plain `--offline` with `.stage4/props.txt`, no init
script, no private Gradle home. Logs `~/Forbric-backups/stage4-notes/c5/upg/w2/`.

| sha | milestone | subject |
|---|---|---|
| `01168925` | U2 | world-snapshot.py's file, diff output and import API frozen as `forbric-world-snapshot/1`; `diff --json`; exporter check |
| `c8588083` | U1 | `run/old-kernel.sh` (build / boot-flat / server / client; checked `.built`; offline-first) + OldKernelScriptTest |

| check | result |
|---|---|
| full suite at `c8588083` (`cleanTest test --continue`, `-Pforbric.requireFixtures=all`, pinned) | **5784 tests, 0 skipped, 5 failed: exactly the 5 names of `c5/failures-wave1.txt`** (LiquidBounce x3, SharedFinalSourceActualTest x2, D58). 5772 + 8 (D43 tests) + 4 (OldKernelScriptTest) = 5784 |
| gate-m31 (`GATE_PORT=46740`, pinned) | 13 PASS / 0 FAIL |
| gate-m31 `M31_UNFIXED=1` (pinned) | 12 PASS / 1 FAIL: `every chunk carries vanilla's biomes (want 0 got 11)`, the recorded teeth |
| `test_world_snapshot.py` | 27/27 (22 + FrozenSchema 5) |
| `test_world_parity.py` / `tools/test_run_nightly.py` | 4/4 / 9/9 |
| U1 `old-kernel.sh build`, offline, fresh archive | stops: `SKIP-FATAL: … needs Gradle dependencies this machine's cache does not hold; resolve them once with FORBRIC_OLD_KERNEL_ONLINE=1` (org.joml:joml:1.10.8 not cached) |
| U1 `FORBRIC_OLD_KERNEL_ONLINE=1 old-kernel.sh build` | built in 12 s; old kernel jar sha1 `aae280b5b3d76a0abe4af0550db16e7a9bf869a2`, merged base sha1 `ca98f14791645a512013ed97a7482d42b473a4a4` (tarball stage); a second `build` reuses the tree with no build |
| U1 `heavy.sh old-kernel.sh boot-flat build/old-kernel-smoke/u1-flat` | **5 PASS / 0 FAIL**: tag's merged base booted, both carriers owned (`forge-runtime-interop.jar, neoforge-runtime.jar`), `Done (0.588s)`, `All dimensions are saved`, world exported by world-snapshot.py (blocks=25600 … saved_data=25) with `minecraft:flat` in `data/minecraft/world_gen_settings.dat`. First run 4/1: the check read level.dat, where 26.2 no longer keeps the generator |

Red checks: FrozenSchema red on four mutated copies of world-snapshot.py (extra meta key, renamed report key, exporter
check removed, Heightmaps no longer skipped); OldKernelScriptTest with `_old_built_ok` trusting any existing marker ->
exactly `aMarkerNamingAVanishedDependencyIsRebuilt` and `theEmptyMarkerOfTheFirstVersionIsRebuilt` red (2 of 4).

The 2026-11-02 cache tree (`build/old-kernel/187143a1…`, empty `.built`, classpath into the wiped cache) was moved aside
and deleted; the tree above is a fresh archive. The commands SWAP uses, the cache path, the schema version and the known
limits are in `c5/handoff.md`, section "UPG U1/U2 (wave 2)".

## Wave 2, part 2 (2026-11-10): canaries, gate-m58, W5 calibration, reverse controls, nightly

Pinned build (D68), stage `.stage4`, ports 46740-46759, game JVMs through `.stage/client.sh` + `.stage/heavy.sh --exclusive`.
Logs and evidence copies: `~/Forbric-backups/stage4-notes/c5/upg/w2/p2/` (`m58-*.out`, `m58-*.evidence/`).

| sha | item | subject |
|---|---|---|
| `37cc189d` | W2 | `canary/world-upgrade/{common,fabric,neo}` + three scripts + README, `run/build-world-upgrade-canary.sh` (from the D67 recovery copies, changed for 26.2: PermissionSet sources, registry game-rule names, result callbacks) |
| `6ce60ac9` | W5 | world-snapshot.py diff rules for the resave noise the A/A measured (chunk tickets, recipeBook string lists, warden tick counter, XpSeed 0 -> drawn once, session stats may only grow); schema/report/API unchanged |
| `9c70886b` | W4 | `run/gate-m58-world-upgrade.sh` (phases main sp aa transfer energy forge installed controls) + GateArgv test |
| `63cbaf0e` | W5 | phase `bb` (new -> new), in the default set: attributes a red check to this tree instead of the upgrade |
| `bc020d49` | D53 | the held nightly change: SLOW_GATES = soak + m58, weekday skip, Sunday --release |

### gate-m58, all phases (run 4, `m58-full-4.out`): **141 PASS / 3 FAIL**

| phase | result |
|---|---|
| main: old server + old client write S2 | every world.commands / player.commands line OK (mcwbridges 4, mcwfurnitures 3, toolbelt 2, corpse 1), both canaries wrote world and player values |
| main: S2 -> S3 `upgrade` / S3 -> S4 `reopen` | **0 / 0 differences**; census of S2 against the new registries 0 missing (attachment_type 10, block 16, block_entity_type 8, data_component_type 10, entity_type 3, item 17); all four canary reads PASS (world + player, JSON pass); first new boot exactly one `Missing data pack forbric/carrier/1-forge-runtime-interop`, second none; no load error |
| main: the new client's socket join (s3, s4) | **FAIL x2**: the client drops the connection on a chunk packet, `IllegalArgumentException: No value with id 43766` in `LinearPalette.read` (a block-state id the client does not have); the player logs in (its file is read and rewritten, so the S3/S4 comparisons stand) |
| sp: old client SP -> S2c, new client SP twice -> S3c, S4c | 0 / 0 differences, census 0 missing, canary reads PASS, one Forge-carrier missing pack then none |
| aa (C3): old kernel reopens S2 with an old client | **0 differences** under `reopen` with the merged-only keys set aside on both sides (CanUpdate 8, ForgeData 2) |
| transfer | old `prepare` PASS (4+4 routes, 40 items, 32,417 units), new `reload` PASS (40, 32,417), no load error |
| energy | old `prepare` PASS (2 families, 2 cells, 40,000 E), new `reload` PASS (40,000 E) |
| forge (C5) | refusal line for `mcw-bridges-3.1.2-mc26.2forge.jar (mcwbridges)`; canary reads PASS; `--ignore-namespace mcwbridges` 0 differences; unfiltered 4, all naming mcwbridges (blocks 2, the chest's items, the `forbric/data/mcwbridges` pack); every load error / missing pack names mcwbridges or the Forge carrier |
| installed (D43) | old world enables `forbric/carrier/2-neoforge-runtime-26.2`; new boot logs the rename, no missing/new carrier pack; list renamed in place: `[vanilla, fabric-convention-tags-v2, mod_data, forbric/carrier/2-neoforge-runtime]` |
| C1 | exactly two differences: `blocks /minecraft:overworld 2 -60 4` and the chest's `Items[7]/count`; the boot names `acacia_bridge_piex`. Its client join hit the same chunk-packet crash (**FAIL x1**, the third) |
| C2 | census `--drop block=mcwbridges:oak_bridge_pier` -> exit 1, `census missing: 1` naming it |
| C4 | ForgeCaps 0 holders / 0 fired, ForgeData 1/1, CanUpdate 4/4, Forge carrier pack 1/1; the reopen needed no merged-only allowance |

**Installed-name phase at the commit before D43** (`a0eeba07`, a `git archive` in scratch with this gate copied in,
`m58-installed-pre-d43-a0eeba07.out`): **10 PASS / 4 FAIL**, exactly the four D43 checks: the new world enables
`forbric/carrier/2-neoforge-runtime-26.2.0.93`, no rename line, `Missing data pack forbric/carrier/2-neoforge-runtime-26.2`,
`Found new data pack forbric/carrier/2-neoforge-runtime-26.2.0.93`. At the tip the same phase is 14/0 (`m58-installed-1.out`).

### W5 calibration (what the first runs measured, and what changed)

| run | P/F | what it taught |
|---|---|---|
| installed-1 | 14/0 | — |
| machines-forge-1 | 25/2 | the forced-chunk tickets come back in another order -> `tickets` unordered |
| main-1 | 7/33 | waystones needs shogi (NoClassDefFoundError `ShogiStreamCodecs`, strict stop) |
| main-2 | 48/15 | `clear @s` fails on an empty inventory, `xp set … points` above the bar is refused; new client: particle remap crash (below); a boot nobody joins leaves the old player file (C1 now joins) |
| full-3 | 130/14 | A/A shows the session noise (recipeBook order, warden ticks, XpSeed 0 -> drawn, session stats): measured old -> old, so rules, not allowances for the upgrade; the SP save must hold only the host's files; a client that leaves before the loop looks again |
| full-4 | 141/3 | above |

Old-side key set (S2): no ForgeCaps anywhere, ForgeData only on the player (= NeoForgeData, the canary's persistent int),
CanUpdate on the three entities and the player, `forbric/carrier/1-forge-runtime-interop` in DataPacks.Enabled, no
`data/forge/` file, `fml.LoadingModList` equal old -> new (no Forge row to drop), no "version differences" line.

### Suite, gate-m31, python tests (end of part 2)

| check | result |
|---|---|
| full suite at `bc020d49` (pinned, `cleanTest test --continue -Pforbric.requireFixtures=all`) | **5784 tests, 0 skipped, 5 failed: exactly the 5 names of `c5/failures-wave1.txt`** (D58), 0 new; `c5/failures-upg.txt`, XML `c5/upg/w2/p2/xml/` |
| gate-m31 / `M31_UNFIXED=1` (`GATE_PORT=46752`) | 13 PASS / 0 FAIL; 12 PASS / 1 FAIL (`every chunk carries vanilla's biomes (want 0 got 11)`) |
| `test_world_snapshot.py` / `test_world_parity.py` / `test_gate_ports.py` / `tools/test_run_nightly.py` | 30/30 / 4/4 / 11/11 / 9/9 |
| red checks | GateArgv red on a gate copy spelling `--palettes`; each new snapshot rule removed -> its test red; the nightly test red with gate-m58 removed (archive of HEAD); m58 installed-name phase red at `a0eeba07` |

### Two defects gate-m58 found (not upgrade losses; owner: kernel / orchestrator, not UPG)

1. **New-kernel client cannot join a server whose mods register particle types** (balm, sophisticatedcore): fabric-registry-sync
   remaps `minecraft:particle_type` on the join and `Int2ObjectMapTracker.onRemap` calls `clear()` on
   `KernelParticleProviders.IntKeyedView`, which does not implement it -> `UnsupportedOperationException`, "Registry
   remapping failed!" (`m58-main-2.evidence/logs/s3-server.client.game.log`). The old client joined the same server. Not
   fixed here (not UPG's file; the right fix is an adaptation decision: the view is keyed by Identifier, so a remap needs no
   rewrite at all, and a naive `clear()` + re-put through the registry would scramble providers). gate-m58 dropped
   waystones and sophisticatedbackpacks (+ libs) from its set for it, with the reason in the gate.
2. **New-kernel client drops the connection on the first chunk packet** with gate-m58's set (fabric-api 0.155.2, the two
   canaries, mcw-bridges, mcw-furniture, ToolBelt, corpse): `No value with id 43766` (block-state id) in `LinearPalette.read`,
   on new -> new too (s4, c1; also in run 3). The old client joined the old server with the same set in every run.
   Attribution (`63cbaf0e`, gate-m58 phase `bb`, `m58-bb-5.out`, 28 PASS / 2 FAIL; 30/2 in `2913ae06`'s message and in
   this record before the wave-2 review was a miscount): the NEW kernel writing the same save
   itself and reopening it hits the same crash on both joins, while S2n -> S3n is 0 differences: this tree's own
   multiplayer defect, not an upgrade loss. gate-m12 with the same mod set on its own fresh world (`m12-repro.sh`):
   the client joins and leaves cleanly (31/1, the one FAIL m12's "server kept up" check: one tick-lag overload warning,
   2094 ms / 41 ticks behind; without mcw-furniture 32/0). That the crash needs the save's placed modded blocks was
   inferred from these two runs, which differ in more than the blocks; the wave-2 review fixes measured it (below).

## Open at the end of part 2 (owner named; superseded by "Wave 2 review fixes" below)

- Done in wave 2 part 2: canaries (W2), gate-m58 (W4) with every phase and control C1-C5, W5 calibration incl. the A/A, the
  installed-name phase green at the tip and red at `a0eeba07`, the nightly SLOW_GATES change, m31 re-run.
- **gate-m58 is not green at the tip**: run 4 (all phases then) 141/3, and phase `bb` 28/2; every FAIL is the new client's
  socket join (defect 2 above), which `bb` shows new -> new. Owner: the kernel (orchestrator to assign; client chunk/block
  state sync on the genuine base). m58 goes green when that join works; nothing in the gate needs to change for it. A full
  default run including `bb` in one go was not made (the two runs together cover every phase). Defect 1 (particle remap `clear()`): same owner; when fixed, waystones and
  sophisticatedbackpacks can return to the gate's set.
- The old client was exercised (MP and SP) on the tag's kernel; an old -> old A/A in singleplayer is not run (the server
  A/A is).
- world-snapshot.py's rule table grew (6ce60ac9); SWAP imports the module: same schema, but a diff can now report fewer
  differences (handoff.md, "UPG (wave 2, part 2)").

## Wave 2 review fixes (2026-11-10)

The two reviews of parts 1 and 2 (`c5/review-w2-upg-A.md`, lens A; `c5/review-w2-upg-B.md`, lens B), each finding checked
against the logs and fixed or refuted. Pinned build (D68). Logs, red checks and gate evidence:
`~/Forbric-backups/stage4-notes/c5/upg/w2/rf/` (each gate run's `<label>.runner.txt` names the tree, HEAD and the sha1 of
the gate file it ran; `<label>.evidence/` is the gate's own evidence directory).

| finding | verdict | commit / evidence |
|---|---|---|
| A-1 D53 population check cannot go red | confirmed (review A's mutated log: FAIL=0) and fixed: each populated jar's modId must have an OK line of the world script, else FAIL. Red: same mutated log -> 2 FAILs naming toolbelt and corpse; unmutated log green (`a1-redcheck.out`) | `188d8d5b` |
| A-2 / B-4 waystones, sophisticatedbackpacks left D53's set without a ruling | confirmed. Ruling asked of the orchestrator (handoff). Meanwhile they are back in the gate through phase `held` (singleplayer copy of S2: an old client writes them, a new client opens it twice), where fabric-registry-sync sends nothing to the host, so the particle remap that kills the socket join does not run. Their population lines are checked as in A-1; the canary README and world.commands say why they are held | `00f15bd5`; `held-1.out`, `full-rf-1.out` |
| A-3 installed phase cannot tell an in-place rename from an append | confirmed and fixed: both installed rundirs hold mcw-bridges' NeoForge build, whose `forbric/data/mcwbridges` follows the carrier on the old side; the phase FAILs if no mod pack follows the carrier. Red: a scratch clone whose `migrateCarrierIds` drops the renamed id and appends it -> 14/2, exactly the two order checks (`installed-append-standin-red.out`); green at the tip (`full-rf-1.out`) | `188d8d5b` |
| A-4 / B-1 counts and labels | confirmed: `m58-bb-5.out` is 28 PASS / 2 FAIL (30/2 in `2913ae06`'s message, this record, handoff and checks); the m12 FAIL is "server kept up", one tick-lag overload warning (2094 ms / 41 ticks behind), not a load warning; bb's write checks said "the old server". Record, handoff and checks corrected; write_checks names the writing side | `188d8d5b`, this commit, notes |
| A-5 C4 silent in a subset run | confirmed and fixed: FAIL when controls run without main or without its S2 -> S3 diff. `a5-redcheck.out`: `2913ae06`'s block prints nothing for `M58_PHASES="controls"`, this one FAIL=1 | `188d8d5b` |
| A-6 recipe-list order allowed everywhere | confirmed and fixed: sorted only inside `players/<uuid>/recipeBook`. New test red with 6ce60ac9's everywhere-sort put back (`a6-redcheck.out`); part 2's snapshots re-diffed offline under the narrowed rule: S2->S3, S3->S4, S2c->S3c, S3c->S4c, S2f->S3f (mcwbridges ignored), A/A and S2n->S3n all still 0 (`a6-offline-rediff.txt`) | `ae06560d` |
| B-2 UPG's 03:52 notes under S's headings | confirmed; moved under "UPG wave-2 review fixes" in handoff.md and checks.md with the corrected counts | notes |
| B-3 G-B2's PROTOCOL paragraph never landed | confirmed and landed, updated for part 2 (frozen schema, stats, `--json`, `census`, noise only once old -> old shows it); replaces wave 1's three lines only | `a5634afb` |
| B-5 manifest leaves inputs out | confirmed and fixed: `inputs` now hashes this gate, the command scripts, the old stage's two carriers and, per phase run, the Forge mcw-bridges build, Team Reborn Energy, the machine canaries, the installed-name carriers and mcw-bridges (23 inputs in `full-rf-1`, none missing) | `188d8d5b` |
| B-6 record opening out of date | confirmed: the read-first section now scopes itself to the wave-1 rows, the branch line names the rebase onto `f0cb66b0`, A-8 is closed | this commit |
| B-7 "needs placed modded blocks" was inferred; S's block-state fix not mentioned | confirmed, and measured (below): S's fix does not cure it, and the placed blocks are what it needs | this record |
| B-8 no m58 result from the committed gate | confirmed for part 2 (every part-2 run used the gate before `9c70886b`/`63cbaf0e` committed it; their failure texts match the committed text). `full-rf-1` ran the committed gate of `00f15bd5` (sha1 `9a5bea7bd6e5`, in the runner file and in the manifest) | `188d8d5b` |

### B-7, measured: the new client's chunk crash on a merge preview with lane S

Merge preview = `git merge-tree` of this branch's `2913ae06` and lane S's `0daa5b0b` (clean, tree `5591b5a0`), checked out
by `git archive` into an APFS clone of this worktree in scratch, kernel jar rebuilt there (contains S's
`clearBlockStateIds`, `835ec80c`, which makes a client number each block state once). Phase `bb` only (the old-kernel
resolve was stubbed in the clone's gate copy, which has no `.git`; bb never boots the old kernel). Files: `rf/preview-s-*`.

| run | world script | result | client crash |
|---|---|---|---|
| `preview-s-bb-2` | as committed | 28/2 | both joins, `No value with id 43766` |
| `preview-s-bb-nosetblock-3` | no `setblock … mcwbridges/mcwfurnitures` (items in the chest and the corpse stay) | 28/2, the 2 = the drawer's emerald line, which needs the drawer | **none: both new-client joins pass** |
| `preview-s-bb-bridgesonly-4` | the two bridge piers placed, no drawer | 28/2 | both joins, id 43766 |
| `preview-s-bb-draweronly-5` | the drawer placed, no piers | 28/2 | both joins, id 45108 |

So: S's client-side fix does not cure it, and the crash needs a modded block placed in a chunk the server sends, either
mod's. Both ids are above the 42954 block states both sides report ("initialised 42954 block state cache(s)" in the client
and the dedicated server's logs), so the dedicated server's palette carries an id the client's id map cannot hold: a
lead for the kernel lane, not diagnosed further here.

### gate-m58, all default phases at `00f15bd5` (`full-rf-1.out`, committed gate): **211 PASS / 5 FAIL**

The 5 FAILs are the new client's socket joins (s3, s4, bb-write, bb-read, c1), every one `No value with id 43766`. Everything
else is green: S2 -> S3 / S3 -> S4 0 / 0, census 0 missing, canary reads PASS; sp 0 / 0; **held** S2h -> S3h 0 under
upgrade (ForgeData dropped 3x after the ForgeData = NeoForgeData check, the host's `BalmData.WaystonesData` now in
NeoForgeData), S3h -> S4h 0, census 0 missing, waystones and sophisticatedbackpacks population OK, no remap error on the
singleplayer join; A/A 0 (CanUpdate 8, ForgeData 2 set aside); B/B 0; transfer and energy reloads PASS; forge 0 with
mcwbridges ignored, C5 4 differences all naming it; installed: `[vanilla, fabric-convention-tags-v2, mod_data,
forbric/carrier/2-neoforge-runtime, forbric/data/mcwbridges]`, renamed in place with the mod pack still after it; C1
exactly its two mutations, C2 red on exactly the dropped name, C4 ForgeCaps 0/0, ForgeData 1/1, CanUpdate 4/4, Forge
carrier pack 1/1. Run time 946 s.

### Suite, gate-m31, python tests (end of the review fixes, at `00f15bd5`)

| check | result |
|---|---|
| full suite (pinned, plain `--offline` + `.stage4/props.txt`, `-Pforbric.requireFixtures=all cleanTest test --continue`, no init script) | **5784 tests, 0 skipped, 5 failed, 0 errors: exactly the 5 names of `c5/failures-wave1.txt`** (D58), 0 new; `c5/failures-upg.txt` rewritten, XML `c5/upg/w2/rf/xml/`. No Java file changed in the review fixes and no test was added or deleted in Java, so no TSV row (D47) |
| gate-m31 / `M31_UNFIXED=1` (`GATE_PORT=46752`) | 13 PASS / 0 FAIL; 12 PASS / 1 FAIL (`every chunk carries vanilla's biomes (want 0 got 11)`), as before |
| `test_world_snapshot.py` / `test_world_parity.py` / `test_gate_ports.py` / `tools/test_run_nightly.py` | 31/31 / 4/4 / 11/11 / 9/9 |
| gate-m58 | 211/5 above (all default phases, committed gate) |

The record commit after `00f15bd5` changes only this file, so the suite and gates were not re-run after it.

### Open (owner named)

- **gate-m58 is not green**: 211/5, all five the new client's socket join, `No value with id 43766`. Owner: a kernel lane
  (orchestrator to assign). Measured lead: a placed mcwbridges or mcwfurnitures block in a sent chunk is what it needs,
  lane S's `835ec80c` does not cure it, and the ids the dedicated server sends (43766, 45108) are above the 42954 states
  both sides count. Nothing in the gate needs to change once the join works.
- **Ruling owed (orchestrator), D53 / A-2 / B-4**: waystones and sophisticatedbackpacks are kept by phase `held`
  (singleplayer) instead of phase main, because the new client cannot join a server that registers particle types
  (`KernelParticleProviders.IntKeyedView.clear()`; owner: the same kernel lane). Either accept `held` as their D53 arm, or
  rule that they return to main once that defect is fixed (then `held` is deleted with the scripts `held*.commands`).
- For SWAP (imports world-snapshot.py): after `ae06560d` a diff can report MORE differences than under `6ce60ac9` when a
  string list named `recipes` / `toBeDisplayed` sits outside a player's recipeBook; schema and API unchanged.
- For the merger: PROTOCOL.md (`a5634afb`) replaces only the three `world-snapshot.py` lines; SWAP has two other hunks
  in the same file.
- The old client was exercised (MP and SP) on the tag's kernel; an old -> old A/A in singleplayer is still not run
  (owner UPG, optional).

## Wave 2b (2026-11-11): D74, waystones and sophisticatedbackpacks back in phase main

Branch `s4-c5-upg`, reset to the stage tip `b9154084` (K1-K4 merged). Pinned build (D68), port block 46740-46759.
Evidence: `c5/upg/w2b/`. Nothing pushed, main untouched, nothing downloaded (every mod jar was already in
`run/client-merged-pack/mods`).

### Commits

| commit | what |
|---|---|
| `876b051b` | K1's measured patch `c5/k1/rf/upg-d74-held-to-main.patch`: `REAL_MODS` += waystones, sophisticatedbackpacks (each still needs its own OK population line); new `LIB_MODS` = balm, shogi, sophisticatedcore, installed (`MAIN_MODS`) and file-checked, not asked for a population line; `held*.commands` lines into `world.commands` / `player.commands` unchanged, both held files deleted; phase `held` deleted (step, default `PHASES`, missing-file loop, header paragraph, manifest inputs). The gate comment and `world.commands` header that said balm registers a particle type, and the README's held-files entry, corrected (D80: only sophisticatedcore does) |
| `89d9e735` | `new_client_checks <game log> <mp\|sp>` after every new client (`server_round` with a client, `sp_round`): K2's failure absent (`Registry remapping failed\|UnsupportedOperationException`), K1's absent (`No value with id`), K2's presence line (`[Forbric/Particles] fabric-registry-sync's remap tracker for ParticleEngine.providers is not attached`), and over the socket gate-m12's denominator (`registered content: .*particle_type=[1-9]`, sophisticatedcore). Old clients predate both fixes and are not asked. Phase main's header paragraph says so |
| `0d135d87` | `write_checks`' `WC_BOX_FORCED` branch deleted (only phase held wrote into an already force-loaded copy); every remaining writer (s2-server, bb-write, s2-forge) writes a fresh world, so the forceload OK check is unconditional and the no-FAIL check has no exception again |

### gate-m58, all default phases at `0d135d87` (`m58-tip.log`, evidence `m58-tip.evidence/`): **205 PASS / 0 FAIL**, CAUGHT none

Phases `main sp aa bb transfer energy forge installed controls`. 205 = K1's 179 for the patch alone + 26 new-client checks
(s3-server, s4-server, bb-write, bb-read, c1-server: 4 each; s3-sp, s4-sp: 3 each). waystones (2) and sophisticatedbackpacks
(1) population lines OK on the old server and on the new (bb) server; S2 -> S3 / S3 -> S4 0 / 0 (server), S2c -> S3c /
S3c -> S4c 0 / 0 (singleplayer), B/B S2n -> S3n 0, A/A 0, forge 0 with mcwbridges ignored and C5 4 differences all
naming it, C1/C2/C4 PASS. S2 -> S3's rules used include `drop merged-only ForgeData: 3` and `drop merged-only
CanUpdate: 4`.

### Red controls: the new absent-checks fire (scratch clones of `0d135d87`, `M58_PHASES=main`, deleted afterwards)

| control | mutation | result | the new checks |
|---|---|---|---|
| K2's fix off (`ctl-k2.log`, `ctl-k2.evidence/`, mutated file `ctl-k2.ParticleViewTrackerInjector.java`) | `ParticleViewTrackerInjector.enabled()` returns false (the tracker is attached as written) | **44 / 16** | s3 and s4: `the new client's registry remap did not fail` FAIL (present x3: `Registry remapping failed!`, `java.lang.UnsupportedOperationException`), `the remap tracker is not attached …` FAIL (got 0); the denominator and the block-state check PASS. The rest of the 16 follow from the client dying: join FAIL, player reads FAIL, S2 -> S3 3 / S3 -> S4 2 differences (the player's) |
| K1's fix off (`ctl-k1.log`, `ctl-k1.evidence/`, mutated file `ctl-k1.KernelLifecycle.java`) | `closeRegistrationWindow` no longer calls `clearBlockStateIds(cl)` before the freeze | **56 / 4** + CAUGHT 4x K1's own invariant (`next id 61734, 47050 state(s) mapped … 14684 stale`) | s3 and s4: `the new client mapped every block-state id it was sent` FAIL (present x1, `No value with id 47862`) and the join FAIL; the particle checks PASS |

Each control is red on exactly the check that guards its fix, and green on the other fix's checks.

### Other gates, suite, python tests (at `0d135d87`)

| check | result |
|---|---|
| gate-m31 (`m31-tip.log`) | 13 PASS / 0 FAIL |
| gate-m12 (`m12-tip.log`, under client.sh + `--exclusive`) | 43 PASS / 0 FAIL (= the merger's 43 after K1+K2) |
| `test_world_snapshot.py` / `test_world_parity.py` / `test_gate_ports.py` / `test_lib_guards.py` / `tools/test_run_nightly.py` | 31/31 / 4/4 / 11/11 / 13/13 / 9/9 OK |
| full suite (pinned, plain `--offline` + `.stage4/props.txt`, `-Pforbric.requireFixtures=all jar cleanTest test --continue`, `suite.log`, XML `suite-xml/`) | **5858 tests, 0 skipped, 5 failed, 0 errors: exactly the 5 names of `c5/failures-k.txt`** (D58), 0 new, 0 gone |

No Java file changed and no test was added or deleted, so no TSV row (D47).

### Open (owner named)

- The wave-2 open items "gate-m58 is not green" and "ruling owed, D53 / A-2 / B-4" are closed: K1 and K2 fixed the joins and
  D74 is carried out above.
- Unchanged from wave 2: an old -> old A/A in singleplayer is still not run (owner UPG, optional).
