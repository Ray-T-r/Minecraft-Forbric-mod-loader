# Stage 4 C5, lane UPG: W3 (world-snapshot), F1 (D43 carrier pack id), and the review fixes

Branch `s4-c5-upg` from `cd6d0fe8`, worktree `s4c5-upg`, ports 46740-46759. Logs: `~/Forbric-backups/stage4-notes/c5/upg/`
(D67). This record covers the 2026-11-09 run: the 2026-11-02 work committed, then the findings of the two lane reviews
(`c5/review-upg-A.md`, `c5/review-upg-B.md`) fixed or refuted. U1 and U2 were reached in wave 2 part 1, the canaries,
gate-m58, its calibration and the nightly change in wave 2 part 2 (sections at the end); what is still open is under "Open".

## How this was measured (read first)

`~/.gradle/caches` was recreated on 2026-11-08 and lost the kernel's dependencies. No lane may download, so every Gradle
run in this record resolves through an init script that adds a file repository of jars already on disk
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
| A-8 migrate never ran in a game | confirmed; now runs in a unit test against stand-ins (`24fdbe08`); the in-game proof is still gate-m58's installed-name phase | open |
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
   Attribution (`63cbaf0e`, gate-m58 phase `bb`, `m58-bb-5.out`, 30 PASS / 2 FAIL): the NEW kernel writing the same save
   itself and reopening it hits the same crash on both joins, while S2n -> S3n is 0 differences: this tree's own
   multiplayer defect, not an upgrade loss. gate-m12 with the same mod set on its own fresh world (`m12-repro.sh`):
   the client joins and leaves cleanly (31/1, the one FAIL a load warning, "server kept up"; without mcw-furniture
   32/0), so the crash needs the save's placed modded blocks (mcwbridges piers, an mcwfurnitures drawer) in a sent
   chunk. Not diagnosed further here.

## Open (owner named)

- Done in wave 2 part 2: canaries (W2), gate-m58 (W4) with every phase and control C1-C5, W5 calibration incl. the A/A, the
  installed-name phase green at the tip and red at `a0eeba07`, the nightly SLOW_GATES change, m31 re-run.
- **gate-m58 is not green at the tip**: run 4 (all phases then) 141/3, and phase `bb` 30/2; every FAIL is the new client's
  socket join (defect 2 above), which `bb` shows new -> new. Owner: the kernel (orchestrator to assign; client chunk/block
  state sync on the genuine base). m58 goes green when that join works; nothing in the gate needs to change for it. A full
  default run including `bb` in one go was not made (the two runs together cover every phase). Defect 1 (particle remap `clear()`): same owner; when fixed, waystones and
  sophisticatedbackpacks can return to the gate's set.
- The old client was exercised (MP and SP) on the tag's kernel; an old -> old A/A in singleplayer is not run (the server
  A/A is).
- world-snapshot.py's rule table grew (6ce60ac9); SWAP imports the module: same schema, but a diff can now report fewer
  differences (handoff.md, "UPG (wave 2, part 2)").
