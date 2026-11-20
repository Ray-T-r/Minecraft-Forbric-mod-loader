# Stage 4 C6 ACCEPT re-run — lane RUN, the survival soak route (2026-11-20/21)

The owner answered the m34 route with "改生存模式重跑" (D102). Lane m34fix made the soak player a survival player and
repaired F1 and W-2 (`stage4-c6-m34fix.md`); this lane ran the acceptance again on the stage worktree
`suspicious-chaum-b28730` at the new tip **ce423a9d** (HEAD detached, `dirty=0` before every run, branch
`claude/remove-forge-neoforge-fabric-83b024` at the same commit). Nothing was fixed, pushed, tagged or released; `main` is
untouched (511f2582). The only commit is this record. Logs and evidence: `~/Forbric-backups/stage4-notes/c6/m34-survival/run/`
(`<run>` below).

| Step | Result | Counts | When (CST), duration |
|---|---|---|---|
| 1. unit suite | red on the D58 five only | 5993 tests / 0 skipped / 5 failed, 920 classes | 21:52:24–21:56:09, 225 s |
| 2. gates-all `-j 4 --no-cache --skip gate-m34-soak.sh` | **58 GREEN**, m0 RED (D58 line only), **m17 RED (network, ended by me)**, m34 SKIP | 61 RESULT lines; `CAUGHT none` in 60 of 60 logs; 0 `[kernel] SKIP` lines; 0 `cached:` | 21:56:19–23:00:24, 64.0 min |
| 2b. gate-m17 alone, once | **GREEN** | 164 PASS / 0 FAIL, `CAUGHT none` | 23:58:24–00:05:06, 402 s |
| 3. integrationTest | red on `:test`'s D58 five only | `:test` 5993/0/5; `:transferTest` 57/0/0; toolTest 56 + 312 + 94 OK; installerTest 11 classes, 0 failed | 23:01:24–23:06:14, 290 s |
| 4. m34 soak (release, survival player) | **RED after 957 s — not a release soak** | client hung in JourneyMap 6.0.1's own lock cycle at 598 s; watchdog stopped it at 901 s | 23:07:27–23:23:43, 976 s |

**ACCEPT is not met: m34 is RED again.** The cause is not the survival route and not a kernel defect: it is the JourneyMap
6.0.1 self-deadlock this project recorded on 2026-09-24 (reproduced then on native NeoForge with JourneyMap alone). The
run ended before any rain, so it says nothing about a survival player under rain over two hours. I did not run m34 a
second time (the cause is not the environment). Details in section 4.

## Environment (every run)

`<run>/env.sh` = the ACCEPT Mac lane's (`stage4-c6-accept-mac.md`) with the lane name changed: `FORBRIC_OLD=<stage>/.stage4`
(mc=26.2 neoforge=26.2.0.93 nfrt=2.0.18), `FORBRIC_NEOLIVE=<stage>/.stage4/run/neoforge-runtime/forbricneolive.jar`,
`FORBRIC_GATE_CACHE=0`, `GATE_PORT=46800`, `M12/M13/M14/M16/M28/M32_PORT=46801-46806`, `HEAVY_LANE=rerun-mac`, `MC_DIR` and
`M17_SEED_CACHE` unset. JDK Temurin 25.0.3. Pinned build, plain `--offline` from `.stage4/props.txt`, `--no-daemon` on every
Gradle call of mine; heavy commands through `.stage/heavy.sh`, client runs through `.stage/client.sh` first. No zh run
(D88b). `env-*.txt` in `<run>` hold tip, dirty count and variables of each run.

## 1. Full unit suite — 5993 / 0 skipped / 5 failed = the D58 five

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test --continue`
(`suite.log`, 920 XML in `suite-xml/`).

- Failures name for name = ACCEPT's `failures-accept.txt` (diff empty, `failures-rerun.txt`): LiquidBounce ×3
  (`FabricClientMixinAnchorsTest` ×2, `MixinTwinRebindTest` ×1) and `SharedFinalSourceActualTest` ×2. Exempt (D58/D87a).
- Per class against ACCEPT's XML (`suite-compare.txt`): 917 classes in common, **0 with different counts**, 0 gone,
  3 new, all from lane m34fix and all green: `boot.ConstructionOrderVerdictTest` 10/0/0, `soak.SoakPlayerPreparationTest`
  7/0/0, `soak.SoakPlayerTest` 7/0/0. 5969 + 24 = 5993.

## 2. No-cache gates-all -j 4 — 58 GREEN, m0 RED (D58), m17 RED from the network

`client.sh heavy.sh --exclusive bash run/compat/gates-all.sh -j 4 --no-cache --skip gate-m34-soak.sh` (`gates-all.out`,
logs `gates/`, counts `gates-counts.txt`).

- 61 RESULT lines: 58 GREEN, `gate-m0.sh RED (exit=1)`, `gate-m17-installer.sh RED (exit=-15)`,
  `gate-m34-soak.sh SKIP (explicit --skip)`. 0 `cached:`.
- gate-m0 18 PASS / 1 FAIL; the FAIL is the D58 line ("unit tests — 3 of 5993 skipped …"). Exempt (D87a).
- `[kernel] CAUGHT none` in all 60 gate logs, no other CAUGHT line; 0 `[kernel] SKIP` lines in the output and in the logs.
- PASS/FAIL per gate equal ACCEPT's `gates-counts.txt` for 59 of 60 gates (m9 163/0, m12 44/0, m16 49/0, m24 198/0,
  m24d 116/0, m56 143/0, m58 208/0, m59 30/0 …); the one difference is m17 (18/17 here, 164/0 at ACCEPT).
- Two Gradle daemons left by the sweep's own gradlew calls were killed afterwards; `run/.gate-clones` (2.0 GB, made by
  this sweep) deleted.

**gate-m17 RED — cause: the network (environment).** M17 runs the installer on its cold path (`M17_SEED_CACHE` unset), which
downloads Mojang's 58.1 MB server jar. In the sweep (gate started 22:09:20):

- First install: `server.jar  transfer of server.jar from piston-data.mojang.com failed after 0 B - retrying (2/3)`, then
  `(3/3)`, then `install failed: transfer of server.jar from piston-data.mojang.com failed after 0 B - offline? Cause:
  java.io.IOException: closed` (gate log lines 628, 1146, 1409; the NeoForge Maven downloads before it had succeeded).
  That is the 17 FAIL lines: no profile, no staged jars, the pinned 17-file download cache one file short (`server.jar`).
- Second install (22:39:28): the same download, alive but at about 6 KB/s — 7 053 312 → 7 241 728 bytes in 30 s after 17
  minutes. My own probes of Mojang's `piston-meta` host around then: 4.6 KB/s, 38 KB/s, then an SSL connect failure. This Mac reaches
  the host through a TUN interface (peer 198.18.x.x); I did not look into or change that.
- The gate has no deadline of its own and gates-parallel none per gate, so the sweep sat behind it. At 22:57 I killed the
  second installer (pid 59132); a third installer run started the same download, so I ended gate-m17 itself (TERM to its
  bash, then its installer). Hence `exit=-15` and a sweep of 64.0 min instead of ACCEPT's 35.9 (m17: 2953 s of it).
  `interventions.txt` in `<run>` has the times.
- **Re-run alone, once** (section 2b): `client.sh heavy.sh --exclusive bash run/gate-m17-installer.sh`, same environment,
  cold path, at 23:58 when my probe of the server-jar URL gave 60–540 KB/s: **GREEN, 164 PASS / 0 FAIL, `CAUGHT none`**,
  402 s (ACCEPT: 272 s). `server.jar 100% 58.1 MB`; one retry of `neoform-runtime-2.0.18-all.jar` from
  maven.neoforged.net, which then succeeded. Both resolved clients reached their outcome after ~69 s and ~72 s; the stall
  watcher (`m17-watch.sh`) took no jstack. Output `gate-m17.rerun.out`.

No other gate was red.

## 3. integrationTest — red on the D58 five only

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) integrationTest --continue` (`integrationTest.log`). BUILD FAILED,
only on `:test`: 5993 / 0 skipped / 5 failed, the same five names. `:transferTest` 57/0/0 (6 classes); `:toolTest` tools 56
OK, run/compat 312 OK, mac 94 OK; `:installerTest` "11 test classes ran, 0 failed". As at ACCEPT, the task's own strict
check did not execute because `:test` failed.

## 4. m34 soak — RED: JourneyMap 6.0.1 deadlocked the client 10 minutes in

`client.sh heavy.sh --exclusive bash run/gate-m34-soak.sh` (`--exclusive` as at ACCEPT; the gate script is `run/`, not
`run/compat/`), `FORBRIC_GATE_CACHE=0`, fixture `run/client-merged-pack` (97 jars). Output `gate-m34.out`; run directory
kept at `forbric-kernel/build/soak-runs/25d029d2-6420-491e-bd2b-d573df29cc76/` (607 MB, gitignored); copies of its small
evidence, the client log, telemetry, the jstack and the JourneyMap disassembly in `<run>/m34/`.

**F1 holds.** `build/retention-control` was moved to `<run>/old-retention-control` first, so the tree had no evidence. The
gate said `native retention evidence for de.cech12.unlitcampfire.CommonLoader.CAMPFIRES cannot be used as it is: absent:
there is no …/build/retention-control/comparison.json`, `measuring it now: python3 run/compat/retention-control.py`,
`took 14 s and exited 0`, `the measurement's Forbric server caught 0 failure site(s)`, and went on to
`[M34] RELEASE SOAK nonce=25d029d2-…`. The manifest records `measuredForThisRun: true`, exit 0, 14.3 s; both arms
`nativeRetentionReproduced: true`. The gate obtained its own prerequisite.

**Attempt 0 was mine and is not a soak.** I first started the gate as a tool background task with a 10-minute timeout
parameter, was not sure the tool would leave it alone, stopped it myself after about a minute (TERM to `soak-run.py`; it
recorded `FAIL … client exited abnormally: -1`, 10.4 s of client) and started it again in a session of its own. The
`build/retention-control` attempt 0 had written was moved away as well, so the real run again started without it. Its run
directory is deleted; `<run>/m34-attempt0/` and `gate-m34.attempt0.out` keep what it wrote.

**Verdict.** `[M34] FAIL releaseAccepted=False`, `[kernel] FAIL M34 soak-run stopped with exit 1`, `[kernel] CAUGHT none`
(the client's kernel caught 0 failure sites). `acceptance.json`: `status FAIL`, `exitCode 75`, `processSeconds 957.4`,
`detail "client exited abnormally: 75 (client thread did not return to the soak controller for 302 seconds (in client
tick))"`. `controller-result.json`: `watchdog true`, `sessions 1`, `joins 2`, `actualTicks 11431`, `visits [3,3,3,3,3,3]`.
No crash report. The run never reached the retention check, so the retention verdict lane m34fix could not predict is
still open.

**Root cause: a lock-order inversion inside JourneyMap 6.0.1's `MapRenderer`; no Forbric frame is involved.**

- The last telemetry row before the hang is `move` sequence 597 at 15:18:38.170 UTC (23:18:38): session 2, from probe 5
  (the End) to probe 0 (the overworld) — the third End→overworld change of the run. `client.log` at 23:18:38: `Reloading
  pipeline on dimension change: minecraft:the_end => minecraft:overworld`, `Minimap updated server level id … overworld`,
  then nothing from the Render thread; the integrated server kept ticking (`[Forbric/Tick]` lines, mean 49.98 ms).
- My watcher took `jstack -l` after 181 s without telemetry (`m34-jstack-75926-idle180-450254.txt`, excerpt
  `m34/deadlock.txt`): **"Found one Java-level deadlock"**.
  - `Render thread`: `MapRenderer.loadInMemoryRegions(MapRenderer.java:199)` ← `updateTiles:852` ← `MiniMap.drawMap` ←
    `UIManager.drawMiniMap` ← `HudOverlayHandler.onRenderOverlay` ← `NeoForgeHudOverlayEvents.postGuiOverlay` ← NeoForge's
    `GuiLayerManager.render`. Holds `SynchronizedSortedMap` 0x…702081b6e0, waits for 0x…703cfe19b8 in
    `Collections$SynchronizedMap.keySet`.
  - `Worker-Main-2`: `MapRenderer.sortRegions(MapRenderer.java:235)` ← `updateGrid:247` ← `lambda$center$0:919` (a
    `CompletableFuture.runAsync` on `Util.backgroundExecutor()`). Holds 0x…703cfe19b8 in `SynchronizedMap.putAll`, waits
    for 0x…702081b6e0 in `TreeMap.putAll` → `SynchronizedMap.size`.
  - The soak's own `threads-watchdog.txt` shows the same two stacks.
- The bytecode of the run's frozen jar (`journeymap-neoforge-26.2-6.0.1.jar`, sha256 e98d21f9…; `javap -l -c`,
  `m34/JourneyMap-MapRenderer.javap-l.txt`) forces it. `sortRegions` reads the `regions` field (old map), stores a new
  `Collections.synchronizedSortedMap(new TreeMap)` into the field (pc 65) and calls `new.putAll(old)` (pc 73, line 235):
  it holds the new map's lock and needs the old one's. `loadInMemoryRegions` reads the field (pc 35), `monitorenter`s
  that map (pc 41), then **reads the field again** (pc 47) and calls `keySet()` on it (pc 50, line 199): if the worker
  swapped the field in between, it holds the old lock and needs the new one's. `updateTiles` starts the asynchronous
  `updateGrid` and calls `loadInMemoryRegions` in the same call, so the two run side by side whenever the minimap
  re-centres on another region. The stack's line numbers are the jar's own line table. Neither `HudOverlayHandler` nor
  `UIManager` refers to a game type, so the minimap path is the same for a creative and a survival player.
- **It is a known defect of that mod, measured on native NeoForge.** `COMPATIBILITY_IMPLEMENTATION.md` ("Final
  acceptance on the merged candidate", 2026-09-24): M34 on the 97-jar pack was RED twice from this deadlock (96 and 79
  minutes, "same two frames"), the two-hour RELEASE_PASS was taken on the pack **without JourneyMap** (96 jars), and "M34
  on the full 97-jar pack stays RED until JourneyMap fixes it". The same jar (sha256 e98d21f9…) deadlocked on the
  official NeoForge 26.2.0.88 client with JourneyMap alone after ~36 min of 5-second teleports
  (`forbric-kernel/build/journeymap-native/evidence/RESULT.md`; that world was creative). `stage4-c5-g-b2.md` met it
  again on a 2-minute control and named it for the release soak. Not measured on native 26.2.0.93; this lane ran no
  native client.
- **How often here.** This run: the 19th probe move. No deadlock in ACCEPT's creative soak (139 moves, 77 min, ended by
  the EnhancedVisuals crash) nor in the six survival runs whose telemetry is still in lane m34fix's worktree (36, 36, 36, 36, 18 and 2 moves). The 2026-09-24 runs
  took 79–96 minutes. It is a timing race; ten minutes was early, two hours without it would be lucky.

**Telemetry of the run** (`m34/telemetry-report.txt`, from `telemetry.jsonl`, 598 rows: 2 `join`, 560 `sample`, 19 `move`;
`conditions` in `acceptance.json` agrees):

- **Game type over time:** `survival` on the server and on the client in 562 of 562 occupied `join`/`sample` rows, from
  the first join (11 s, game time 75086, overworld) to the last sample (598 s, game time 86530, the End). `mayfly`,
  `invulnerable`, `flying` true in 562/562; `clientFlying` false in 1 row (the first join row) and true in 561;
  `hudHidden` false and `clientInWorld` true in 562/562; health 20.0 and food 20 in every row; y 160.0 at every probe.
  Rows per dimension: overworld 189, Nether 186, End 187.
- **Weather changes:** none. `raining` and `thundering` false in 562/562 rows; `rainingAtPlayer` false in 562, unknown
  in 0.
- **Did it rain while the player was connected:** no.
- **Was the first join into rain:** no. `[M34] the copied world was saved in clear weather; its rain timer stands at
  88032 tick(s)`; first join row `raining false`, `rainingAtPlayer false`. The second join (405 s, server 2, in the End)
  was also dry. Rain was due at game time 163074 (lane m34fix, section B); the last sample is at 86530.
- The soak's summary line: `[M34] player survival; rain in 0 of 562 row(s), on the player in 0 (not known in 0), first on
  the player at no row; 0 join(s) into rain`.

**What this run does and does not show.** It shows the survival player is prepared and held as designed for 10 minutes
of a release-shaped soak on the full pack (one complete session and most of a second, all three dimensions, HUD drawn),
and that the gate finds its retention evidence by itself. **It never rained during the run**, so it does not show that the
survival route survives rain in a two-hour run; the lane's short smokes (s2, s5) and the native A/B
(`stage4-c6-ev-native.md`) are still the only rain evidence. It also says nothing about retention over many sessions.

## 5. Clean-up

No game, gate, Gradle or watcher process left; no listener on 46800–46899; no `.stage` lock, empty client queue;
`run/.gate-clones` removed; the Gradle daemons left by the sweep's and the gates' own gradlew calls were killed (two after the sweep, the
others after the m34 runs and the m17 re-run). Kept: the soak run directory (above), `build/retention-control` as the soak
measured it, `<run>/old-retention-control` (the moved-away evidence, 141 MB). Deleted, all mine: attempt 0's run
directory (546 MB) and the arm directories of the evidence it had written. Free disk: 20 GiB before, 18 GiB after
(`disk-before.txt`, `disk-after.txt`).

## Defects and findings

**R-1 (m34 RED; a third-party mod's defect, known and native; owner decides the route).** JourneyMap 6.0.1 deadlocks its
own render thread against its own background task (above). Under "不准对 mod 特判" the kernel has nothing to fix and must
not key on the mod. What m34 can prove with this jar in its default fixture is a matter of luck; the 2026-09-24 acceptance
answered it by soaking the pack without JourneyMap and recording the full-pack RED. No Stage 4 decision says which form
of m34 ACCEPT requires. Owner: the owner (route), then a run lane. Not fixed, not re-run.

**R-2 (environment; no owner).** gate-m17 red in the sweep from the route to `piston-data.mojang.com` (0-byte transfers,
then ~6 KB/s); green alone 59 minutes later.

**R-3 (harness, minor; owner: a harness lane).** In that red m17 run, 16 of its 18 PASS lines were stale: when the profile
cannot be resolved no client is launched, and the 7 command-line checks and 9 "the installed version runs the game" checks
then read `build/gate-m17-command.txt` and `build/gate-m17-client.log` left by the previous run (mtimes 2026-11-20
00:28:48 and 00:30:18, ACCEPT's sweep) — "it entered the world (2)" was printed for a game that never started. The gate
was RED anyway through its 17 FAIL lines, so no verdict was wrong; the files are not removed at the gate's start.

**R-4 (harness, observation).** Neither gate-m17 nor gates-parallel bounds a gate's time; a download that trickles holds a
whole sweep (here 49 minutes until I ended the gate). The installer itself never gives up on a transfer that is still
moving.

**Otherwise no defect found.** Suite and integrationTest are red only on D58; the sweep's other 58 gates are GREEN with
ACCEPT's counts; F1 is closed in a real release start.

## Not done by this lane

- No second m34 run, on the default fixture or on the pack without JourneyMap; so no two-hour survival verdict, no rain
  inside a release soak, no retention verdict.
- No native client run of any kind; R-1's native half rests on the 2026-09-24 measurement (NeoForge 26.2.0.88).
- No fix of anything; no push, no CI at ce423a9d, no tag or release, no zh run, no Windows run, no release rehearsal
  (not in this lane's task).
- The m17 red inside the sweep was ended by me, not left to finish; its `exit=-15` is my signal.
- `stage4-c6-accept-mac.md`'s statements about m34 were not touched.
