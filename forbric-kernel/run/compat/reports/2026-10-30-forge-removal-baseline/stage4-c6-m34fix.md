# Stage 4 C6: m34 as a survival soak, and the harness findings F1 and W-2 (lane m34fix)

The owner answered the m34 route with "改生存模式重跑" (D102): make the soak player a survival player and run again. This
record is the fix lane: branch `s4-c5-m34fix`, cut from the stage tip 31bfad16, code commit 8f544dfb. It did **not** run
the 2-hour soak, gates-all or the full suite; the RUN lane does that. Nothing was pushed, `main` was not touched.
Evidence: `/Users/jerry/Forbric-backups/stage4-notes/c6/m34-survival/` (`<ev>` below). `<lane>` is
`.claude/worktrees/s4c5-m34fix`.

| Item | Result |
|---|---|
| A. survival soak player | done; unit pins + verifier check + three real control soaks |
| B. weather and game type in the telemetry | done; and the 4610 s of the acceptance crash is explained (below) |
| C. F1, m34 obtains its retention evidence | done for the evidence; the native server image is still a named manual prerequisite |
| D. W-2, the construction-order line | done in the kernel: the verdict is stated every time, the two patterns are unchanged |
| E. two stale F2 sentences in `stage4-c6-accept-mac.md` | done |
| F. verification in the lane | unit 113/0, toolTest 452/0, 10 red controls red, control soak at the tip CONTROL_PASS |

## A. The soak player is a survival player

**Why it was creative.** `preparePlayer` has set `GameType.CREATIVE` since the controller was added (72660fae). Neither
that commit's message nor its text in `COMPATIBILITY_IMPLEMENTATION.md` / `PROTOCOL.md` gives a reason. What the soak
needs from the player is visible in the state machine: six probes at y=160 in three dimensions, the player alive in every
sample (a dead player earns no active time and the run times out), and standing within 2 blocks of the probe when the
arrival sample is taken. Creative gave "cannot die" and "may fly" for free. No document, script or test said "creative"
(`git grep -i creative` over the soak's Java, `soak-run.py`, `test_soak.py`, `gate-m34-soak.sh`, both introductions,
`PROTOCOL.md`: only the one line of code).

**What changed** (`src/runtime/.../soak/ClientSoakController.java`, `preparePlayer`). The player is made SURVIVAL, and
what the soak needs comes from flags that do not depend on the game type. Each claim was read from the stage's base
(`javap` of `.stage4/run/neoforge-patched/patched-mc-neoforge-26.2.jar`):

| Need | Given by | Read from |
|---|---|---|
| not hurt (mobs, starving, drowning, suffocating) | `Entity.setInvulnerable(true)` | `Entity.isInvulnerableToBase`: `invulnerable && !source.is(BYPASSES_INVULNERABILITY)`; that tag is `out_of_world` and `generic_kill` only (the base's `data/minecraft/tags/damage_type/bypasses_invulnerability.json`) |
| not hunted | the same flag | `LivingEntity.canBeSeenAsEnemy` = `!isInvulnerable() && canBeSeenByAnyone()` |
| not falling | `setNoGravity(true)`; `mayfly` + `flying` so the client treats the hover as flight | `ServerGamePacketListenerImpl.getMaximumFlyingTicks` is `Integer.MAX_VALUE` below gravity 1e-5; `MinecraftServer.allowFlight()` returns `true` |
| abilities survive the game type | `setGameMode` first, the abilities after it, `onUpdateAbilities` last | `GameType.updatePlayerAbilities` writes `mayfly=false`, `flying=false`, `invulnerable=false` for survival; `ServerPlayerGameMode.setGameModeForPlayer` calls it on a change and when a saved player is loaded |

`setGameMode` is asked only when the type differs; a type that will not change (NeoForge's `onChangeGameType` lets a
listener veto or replace it) throws, which fails the soak on the next tick instead of soaking another kind of player.
The method names no mod and detects none; its comment states the structural reason (a soak drives the game as a
survival player's client does, whole HUD drawn, not as a creative one whose client leaves those paths out).

**The verifier holds it.** `soak-run.py validate_trace` refuses a run with an occupied `join`/`sample` row whose
`gameType` is not `survival` (`require_soak_player`): a creative row, any other type, and a row with no `gameType`
(older controller) all refuse.

**Not moved by mobs.** Nothing new was added for this. Position only matters in the one sample after a move; an
invulnerable player is not targeted, exactly as a creative one was not. Mobs can still push a player they walk into, in
either game type.

## B. Weather and game type in the telemetry

Every `join`, `sample` and `waiting-for-live-player` row now also carries, from the server thread: `raining`,
`thundering` (the overworld's), `rainingAtPlayer` (`player.level().isRainingAt(blockPosition)`), `gameType`, `flying`,
`mayfly`, `invulnerable`, `health`, `food`, `y`; and from the client when the row is written: `clientGameType`,
`clientFlying`, `hudHidden`. Nothing in the controller decides by them. `soak-run.py` writes a `conditions` summary into
`acceptance.json` for every run that wrote telemetry, a crashed one included (game types seen, rows with rain and with
rain on the player, the first of each, joins into rain, lowest health, last row), and prints one `[M34] player …; rain
in N of M row(s) …` line. The weather is not forced or changed.

**What the fixture save says at the first join.** In 26.2 the weather is not in `level.dat` (its `Data` has no
raining/rainTime/clearWeatherTime key; `GameType` 0, `Time` 75042, difficulty easy). It is in
`saves/ForbricTest/data/minecraft/weather.dat` (sha256 a66d6d69…): `raining 0`, `thundering 0`, `rain_time 88032`,
`clear_weather_time 0`, `thunder_time 70658`; `game_rules.dat` has `minecraft:advance_weather 1`. The saved player is
survival (`playerGameType 0`), on the ground at (-0.5, 156, 1.5) in the overworld, health 20, food 20, `Invulnerable 0`,
no `NoGravity`. So the first join is into clear weather, as a survival player.

**D101's residual, explained.** Rain is due when `rain_time` reaches 0: game time 75042 + 88032 = **163074**. The
acceptance soak's world source had this same `weather.dat` (its manifest, sha a66d6d69…). Its last four samples are game
time 163019, 163040, 163061 and 163081, all at probe 0 in the overworld, the last at 18:21:15.48 UTC, and the client
logged the exception at 18:21:16. The crash came 4610 s in because that is when the copied world's rain was scheduled,
with the player in the overworld under it. With the fixture unchanged the rerun's rain starts at the same game time.

**Joins into rain.** A session ends after the last probe, which is in the End, so every later join is in the End, where
it does not rain (`rainingAtPlayer` false at both such joins of the rain run below, `raining` true). Only the first join
is in the overworld, and the fixture's weather is clear then. `joinsInRain` in `conditions` would show an exception.

## C. F1: m34 obtains its retention evidence

**Why m34 needs it.** The pack holds Unlit Campfire, whose static cache keeps stopped servers reachable on native
NeoForge too. `native-retention.json` registers that root; a release soak may cut it after measuring only if
`build/retention-control/comparison.json` shows it reproduced on native NeoForge and on Forbric with the same jar. That
file is a gitignored build output which only a hand-run of `retention-control.py` wrote. No decision covers it (D82 only
records a killed m34; the other mentions are C6 reviews of the script's stage resolution).

**What changed** (`run/compat/soak-run.py`, `retention-control.py`, `gate-m34-soak.sh` comment, `PROTOCOL.md`):

- `native_evidence_problem` says why evidence cannot back this soak, or None. It is usable when it says what the
  registry claims, when every file each arm's `inputs.json` records still has the recorded bytes (mods, carriers, the
  kernel jar in `build/libs`, the canary sources, `retention-control.py`, the native image's `installation.json`), and
  when the Forbric arm booted the kernel jar, base and carrier the soak has just frozen (by SHA-256).
- `ensure_native_evidence` (release runs only, after the jars are frozen, before any client starts) uses current
  evidence as it is; otherwise it prints why, runs the registry's own `reproduce` command, prints the seconds and exit
  code, and checks the result the same way. A failed measurement, or one that leaves evidence that still does not
  describe the run, stops the soak with the reproduction's last line in the message. Nothing is skipped; nothing stale
  is accepted. The manifest's record says whether the evidence was measured for this run.
- `retention-control.py` records `installation.json` in the native arm's inputs, and stops before compiling when the
  native image or the mod jar is missing, naming `native-controls.py prepare --family neo`.

**Not automated: the native server image.** `prepare --family neo` runs the official installer, which downloads. I
tried it with the network blocked (installer and checksum copied from the stage, proxies pointed at a dead port): it
fails at `maven.neoforged.net` (`<ev>/prepare-offline.log`). A measurement should not go to the network on its own, so
a tree without `build/native-controls/native/neo` still needs that one command once; the gate now says so in its first
second instead of failing in javac.

**Real runs in the lane** (`<ev>/f1/f1-run.log`, each step calls `ensure_native_evidence` with the lane's kernel jar and
the stage's base and carrier):

| Step | State | Outcome |
|---|---|---|
| 0 | no image, no evidence | refused in 0 s: `[retention-control] FAIL no native NeoForge 26.2.0.93 server image at … run … prepare --family neo` |
| 1 | image cloned from the stage (`cp -c -R`, no download), evidence absent | measured: 18 s, exit 0, both arms reproduced |
| 2 | again | `is current`, nothing run |
| 3 | one recorded file +1 byte | `stale: arm forbric was measured on …, which has other bytes now`; measured again, 18 s |
| 4 | again | current |
| 5 | the soak claims another kernel jar | measured again (18 s), still not that jar: refused, `did not boot the kernel jar this soak launches` |
| 6 | the real jars | current |

Each measurement leaves about 140 MB of run directories under `build/retention-control/`; nothing prunes them.

## D. W-2: the construction-order line

`KernelModLoader.orderByDependency` wrote `[Forbric/Order] construction order is dependency order …` only when the sort
moved a class. With nothing to reorder it wrote nothing, and so did ordering switched off or failed, so
`run/compat/assert.sh:26` (and gate-m9's check, same pattern) could not tell a healthy small pack from a broken one.

**The invariant** both checks mean is "the dependency order was applied to construction". **Decision: the kernel states
its verdict every time**; the two patterns are unchanged. Accepting absence in the assertion would have made it pass
for ordering that never ran.

| Case | Line | Satisfies the two patterns |
|---|---|---|
| applied, a class moved | `… is dependency order, not jar-file order — … : [ids]` (as before) | yes |
| applied, nothing to move | `… is dependency order: the N @Mod class(es) were found in it already, so none moved …` (new) | yes |
| `-Dforbric.modOrder=name` | `… is the order the mods were found in: dependency ordering is off …` (new) | no |
| ordering failed | the existing `could not order mods by dependency` warning, no verdict | no |
| nothing to construct, or no order published | no line | no |

The off-switch is tested before the sort: with ordering off the sort returns the list unchanged, which would otherwise
read as "nothing to move". Every tracked file was searched for the wording (`git grep`): the only readers are
`assert.sh:26` and `gate-m9-client.sh` (same pattern; gate-m9 also measures two real dependency pairs) and
`AssertCompatTest`'s fixture line. These are log lines, not localized text, so no forced-zh test applies.

## E. `stage4-c6-accept-mac.md`

The "Native comparison" bullet and the "No native client A/B for F2" sentence now point at `stage4-c6-ev-native.md`
(three small replacements; nothing else in the file changed).

## F. Verification

**Unit tests** (pinned build, `--offline --no-daemon`, `<ev>/unit-and-tooltest.log`): 113 tests, 0 skipped, 0 failed over
the affected classes (`ConstructionOrderVerdictTest` 8 new, `SoakPlayerPreparationTest` 4 new, `AssertCompatTest`,
`ModConstructionOrderTest`, `FabricLoadOrderTest`, `KernelModLoader*`, `KernelSoakHooksTest`, `SoakStateMachineTest`,
`RuntimeSeamLivenessStagedTest`, `NoRawAssumptionsTest`). `ConstructionOrderVerdictTest` decides each case with the two
scripts' own patterns through `grep -acE`. `SoakPlayerPreparationTest` reads the compiled controller: SURVIVAL is the
only game type named, `setGameMode` precedes the ability writes and `onUpdateAbilities` follows them, the entity is made
invulnerable and gravity-free, and every key of `soak-run.py`'s `CONDITION_KEYS` is a constant the controller writes.

**toolTest** (same log): 56 + 302 + 94 tests, OK. `test_soak.py` went from 28 to 38 tests: the survival refusal, the
conditions summary, a cut last telemetry line, evidence absent / stale (8 files × changed and removed) / measured on
other jars / current, measurement failing in four ways, the reproduction command, and `launch()` wiring for a release
and a control run.

**`bash -n`**: `gate-m34-soak.sh`, `gate-m9-client.sh`, `compat/assert.sh` OK (`<ev>/bash-n.log`).

**Red controls** (`<ev>/red-controls/`): 6 mutations of `soak-run.py` in a scratch copy and 4 of the Java in the tree
(restored, `cmp` and `git diff` identical). Each went red on the named test: no survival check; no recorded-file check;
no launched-jars check; no re-check after measuring; measuring although current; a control run measuring; CREATIVE
instead of SURVIVAL; the ability writes and `onUpdateAbilities` removed; silent when nothing moves; off-switch not
tested first.

**Control soaks** (`gate-m34-soak.sh --control --seconds 30 --dwell-ticks 100 --settle-seconds 10`, through
`client.sh heavy.sh --exclusive`; a watcher pressed F2 in the client every 6 s and captured the window; `<ev>/smoke/`):

| Run | Tree, world | Verdict | Player and weather (all `join`/`sample` rows) |
|---|---|---|---|
| s1-clear | dirty tree, fixture world, 2 sessions | FAIL, `source changed during the measured run` — **my mistake**: I edited `test_soak.py` while it ran. Its 150 rows are otherwise as s3 | survival 150/150, health 20.0, no rain |
| s2-rain | dirty tree, a clone of the fixture world with `rain_time` 150, 3 sessions, heap dump | REVIEW_REQUIRED (retention, below); activity verified, 4511 ticks, **no crash**, caught 0 | survival 225/225; rain in 217 rows, on the player in 32 (probe 0, all three sessions); first at game time 75212, 9 s after the join; health 20.0 |
| s3-tip-clear | **8f544dfb, clean**, fixture world, 3 sessions, heap dump | **CONTROL_PASS**, `[kernel] CAUGHT none` | survival 225/225, client survival 225/225, `flying` 225/225, `clientFlying` 224/225 (false in one join row, written before the abilities packet arrived), `mayfly` and `invulnerable` 225/225, health 20.0 and food 20 throughout, y = 160.0 at every probe in all three dimensions, `hudHidden` false 225/225 |

- **HUD drawn:** the game's own F2 screenshots show ten hearts, the hunger bar, the experience bar and the hotbar in the
  overworld, the Nether and the End (40 per run in s2 and s3, e.g. `s1-clear/shots/2026-11-20_20.04.19.png`).
- **Rain after join, no crash:** s2 only. The player was in the overworld at probe 0 when rain began; the drop overlay
  is visible with the HUD (`s2-rain/shots/2026-11-20_20.10.20.png`, session 2 `…20.11.15.png`). The log has the mod's 21
  `Could not find any resources` lines from setup and no `bound must be positive`. Sessions 2 and 3 joined in the End
  with rain on and went back under it.
- **Takes no damage:** shown only as health 20.0 in every row with `invulnerable` true. Nothing in these runs is known to
  have tried to hurt the player, so the refusal of damage itself rests on the bytecode in section A.

**Retention in s2 (for the RUN lane).** After the Unlit Campfire cut all three servers were still reachable, so 3 of 3
is not "most collected" and the run is a review. `HeapPaths --mod-owned`: 3 UNREACHABLE, 0 REACHABLE (every path runs
through state a mod keeps). The shortest paths (`s2-rain/retention-shortest-paths.txt`): one server through Xaero's
`ServerConfigManager.server`; one each through `ZombieRenderState` and `SkeletonRenderState`, field
`EntityRenderState.transitionEntity` → the entity → its `ClientLevel` → the old connection. That field is added by the
TRansition library nested in two mods' jars (`EntityRenderStateMixin`). It is last-value state per renderer, not per
session. In the two clear runs only the last server stayed (s3: 1 of 3, mod-owned, pass). I did not test whether the
difference is the rain (zombies and skeletons on the surface) or chance, nor whether a creative player shows the same.

## Decisions taken in the lane

1. Survival is enforced, not only set: the controller fails when the type will not change, and the verifier refuses
   rows that are not survival.
2. No food top-up, no position pinning, no peaceful difficulty: invulnerability covers starving, and the rest would be
   soak-only behaviour a player's game does not have.
3. F1 lives in `soak-run.py`, where the decision "this run leans on the evidence" already is (release runs only), so a
   direct `soak-run.py` call behaves like the gate.
4. Freshness is by bytes of everything recorded, the kernel jar included. A rebuilt kernel costs one 18 s measurement.
5. The native image is not installed automatically (it downloads).
6. W-2 is fixed in the kernel, not in the assertion.
7. `preparePlayer` is pinned by a bytecode test, because the controller cannot run off-game.

## Not done, and open points

- No 2-hour soak, no gates-all, no full suite, no zh run (D88b), no CI. gate-m9 was not run against the new line; its
  pattern is exercised by the unit test only.
- A release run of the gate itself was not started (it would be the 2-hour soak). The evidence step was run for real
  through a driver (`<ev>/tools/f1-ensure.py`) and its place in `launch()` is covered by a unit test with a fake client.
- `native-controls.py prepare --family neo` was not run with the network; the image used here is a clone of the stage's.
- The 2-hour rerun's retention verdict is not predicted by these runs (see s2).
- Whether mobs push the hovering player off a probe before the arrival sample was not provoked; s2 and s3 reached all 36
  probes each.
- The lane's own evidence is stale again by design: the kernel jar changed after it was measured (6b66006b… → 1eab3c39…),
  so a release run here measures once more.
- Left in the lane (gitignored): `build/native-controls/native/neo` (clone), `build/retention-control/` (141 MB),
  the s2 and s3 run directories (1.3 GB, heap dumps deleted). No process, lock or port is left; no Gradle daemon.
