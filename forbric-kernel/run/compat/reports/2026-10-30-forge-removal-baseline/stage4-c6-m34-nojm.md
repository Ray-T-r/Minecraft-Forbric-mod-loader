# Stage 4 C6: the m34 release soak on the pack without JourneyMap (lane M34-NOJM, 2026-11-21)

One run of `run/gate-m34-soak.sh` with the gate's release defaults (7200 s, no `--control`), on a 96-jar fixture: the
default `run/client-merged-pack` minus `journeymap-neoforge-26.2-6.0.1.jar` and nothing else, handed over with
`M34_FIXTURE`. Stage worktree `suspicious-chaum-b28730` at **43efbd92** (HEAD detached, `dirty=0`, = branch
`claude/remove-forge-neoforge-fabric-83b024` = `origin/ci/remove-forge-stage2`). Nothing was fixed, pushed, tagged or
released; no tracked file changed except this record; `main` is untouched (511f2582); the gate's default fixture is
unchanged. Evidence: `~/Forbric-backups/stage4-notes/c6/m34-nojm/` (`<ev>`); the run directory is
`build/soak-runs/514e505e-d6e0-4010-b3f5-97036dedb54a` (`<run>`).

**The owner's ruling R-1 is open.** Whether a pass on the pack without JourneyMap counts for acceptance, with the
full-pack RED (D103: JourneyMap 6.0.1's own lock-order inversion, 598 s) recorded as third-party, has not been ruled.
This run is evidence for that ruling either way. It is not a pass of m34 on its default fixture.

## 中文结论（给 owner）

- **这次跑满了，gate 判 RELEASE_PASS。** 去掉 JourneyMap 的 96 个 jar，生存模式，2 小时（有效模拟 7241.8 秒，145175 tick，
  19 个会话），客户端正常退出，内核没有吞掉任何异常（`CAUGHT none`），没有崩溃报告，没有卡死。
- **第一次在长测里遇到下雨，没有崩。** 第 75.9 分钟开始下雨（游戏时间 163105，存档的雨计时器算出来是 163074），当时玩家正好
  站在主世界的 0 号点，雨直接下在玩家身上，这正是上次创造模式验收崩掉的那个时刻。雨下了约 12.7 分钟，玩家身上有雨的记录
  122 行，全程满血满饥饿度，日志里没有 `bound must be positive`。没有打雷。
- **留存（retention）通过。** 切掉 Unlit Campfire 那个已在原生 NeoForge 上复现过的缓存后，19 个旧服务端回收了 16 个，剩 3 个
  （第 10、14、19 个），堆分析判定这 3 个全部只被模组自己的状态拿着（Xaero 的配置管理器、僵尸和骷髅的渲染状态），和之前
  30 秒下雨对照（s2-rain）里看到的是同三类。
- **它不能说明的：** 默认 97 个 jar 的包仍然没有绿过（JourneyMap 死锁没变）；这次只在 Mac 上跑了一遍；没有打雷、没有"进入
  世界时雨正下在玩家身上"的情况；没有截图，所以雨滴效果是否真的画出来这次没看。
- **一个要说的观察：** 堆内存随会话数线性上涨（每个会话约 0.07 GiB，因为 Unlit Campfire 把每个旧服务端都留着，原生也这样），
  2 小时结束时最高用到 3.76 GiB，上限是 4 GiB。再长的长测能不能撑住，这次没有测。
- 这次算不算验收通过，要等你对 R-1 的裁定。没有推送，main 没动。

---

## 1. What ran, on what

| | |
|---|---|
| Command | `M34_FIXTURE=<fixture> client.sh heavy.sh --exclusive bash run/gate-m34-soak.sh` (no arguments), in its own session, output `<ev>/gate-m34.out` |
| Tip | 43efbd929e8f5124940486c96487daee2247ef7c, `dirty=0` before and after (`<ev>/env-m34.txt`; the manifest's `source.commit`, `dirty false`) |
| Environment | the RUN lane's (`stage4-c6-accept-rerun.md`) with `HEAVY_LANE=m34-nojm` and `M34_FIXTURE` added: `FORBRIC_OLD=<stage>/.stage4`, `FORBRIC_NEOLIVE`, `FORBRIC_GATE_CACHE=0`, `GATE_PORT=46800`, `MC_DIR` / `BASE` / `NEO_RT` / `FORBRIC_JVM` unset; Temurin 25.0.3; no zh run (D88b) |
| When | 2026-11-21 02:15:14 – 04:22:57 CST, 7663 s; client process 7628.3 s (pid 91488) |
| Kernel | `boot.jar` sha256 9cdbf8fb…, `runtime.jar` 0fb62bd9…, base f790e8fa…, carrier ecceeb77… — byte for byte the jars of the failed survival run at ce423a9d (`<ev>/manifest-compare.txt`): lane cifix changed no kernel byte |
| Preconditions | no java / gradle / gate process, no `.stage` lock, empty queues, no listener on 46xxx / 25565, 16 GiB free (`<ev>/preconditions.txt`) |

**The fixture** (`<ev>/make-fixture.sh`, `fixture-make.log`, `fixture-proof.txt`): `cp -c -R` (APFS clone) of
`/Users/jerry/Documents/Forbric/forbric-kernel/run/client-merged-pack` (what the stage's `run/client-merged-pack` link
resolves to) into `build/m34-nojm-fixture` (gitignored), then `rm mods/journeymap-neoforge-26.2-6.0.1.jar`.

- Jar names: 97 against 96; `diff` is the one line `journeymap-neoforge-26.2-6.0.1.jar` (sha256 e98d21f9…).
- sha256 of every file of both trees (1759 against 1758 files): the same one line differs, nothing else.
- `config` 157 files identical, `saves/ForbricTest` 171 files identical, `shaderpacks` 2 identical, `resourcepacks`
  empty in both, `options.txt` identical (38f9ace2…), no `servers.dat` in either. The clone still holds JourneyMap's
  config and data directories, as "nothing else" requires.
- After the run both trees hash as before (`fixture-proof.txt`, last lines); `soak-run.py` attested the source world
  itself.
- Against the earlier runs' manifests: 96 mods = the 97 of the failed survival run (25d029d2) and of ACCEPT's creative
  run (272912f0) minus JourneyMap, no jar with other bytes; source world (171 files) and initial configuration equal to
  both.

**What the gate printed about the save and the player.**

- `[M34] the copied world was saved in clear weather; its rain timer stands at 88032 tick(s), so the first session joins
  into clear weather` (manifest `sourceWorldWeather`: `rainTime 88032`, `thunderTime 70658`, `raining false`,
  `weather.dat` a66d6d69…).
- The gate prints no line about the saved player's game type. What it printed of the player is the end line `[M34]
  player survival; …`; the first `join` row is `gameType survival`, `clientGameType survival`, overworld, y 156.0,
  health 20.0. (`stage4-c6-m34fix.md` read `playerGameType 0` from the save; not re-read here.)

**The retention evidence: stale, measured by the gate.** `build/retention-control/comparison.json` of the failed run
was left in place (03e14047…, 2026-11-20 23:07). The gate said `cannot be used as it is: stale: arm native was measured
on …/run/compat/retention-control.py, which has other bytes now` (lane cifix edited that script), then `measuring it
now`, `took 16 s and exited 0`, `the measurement's Forbric server caught 0 failure site(s)`. The manifest records
`measuredForThisRun: true`, 15.8 s, the new comparison 1fadf83a…; both arms `nativeRetentionReproduced true`,
`inputsUnchanged true`, `campfiresReferencingStoppedServer 1` (native token 02272de6…, Forbric token e0ef0ed3…).

## 2. Verdict

The gate's last lines, as printed (the run directory's path shortened to `<run>`):

```
[M34] the client's kernel caught 0 failure site(s) and carried on: <run>/evidence/caught.tsv
[M34] player survival; rain in 720 of 7124 row(s), on the player in 122 (not known in 0), first on the player at 2026-11-20T19:32:21.559788Z; 0 join(s) into rain
[M34] RELEASE_PASS releaseAccepted=True evidence=<run>/evidence
[kernel] PASS M34 soak-run accepted the run as a release soak (RELEASE_PASS, exit 0)
[kernel] CAUGHT none: no JVM this gate started reported a failure it carried on from
```

rc 0. 1 `[kernel] PASS`, 0 `FAIL`, 0 `SKIP`, `CAUGHT none`. `acceptance.json` (e5b0ef95…): `status RELEASE_PASS`,
`releaseAccepted true`, `exitCode 0`, `activityVerified true`, `activeSeconds 7241.84`, `actualTicks 145175`,
`sessions 19`, `visits [38×6]`, `unloads [38,38,38,38,38,37]`, `reloads [19,19,19,19,19,37]`, `compatibility` STRICT
with `confirmedRequired 0`, `caught.failureSites []`, `telemetrySha256` a2801224… = the sha256 of the file,
`manifest.sha256` d21d61ce… = the manifest, every frozen artifact with its recorded hash afterwards. No crash report,
no `hs_err` file. `caught.tsv` and `native-retention-0.caught.tsv` are empty.

## 3. Numbers

Telemetry (`<ev>/analysis.txt`, `telemetry-report.txt`): 7698 rows, sequence 1..7698 without a gap, span 7572.0 s.

| Kind | Rows | | Kind | Rows |
|---|---|---|---|---|
| start | 1 | | disconnect | 19 |
| join | 19 | | open | 18 |
| sample | 7105 | | gc-request | 48 |
| move | 228 | | idle | 239 |
| save-and-disconnect | 19 | | heap-dump, finish | 1, 1 |

- Sessions: 19 joined, 19 complete (382.1–383.2 s each, median 382.5); moves 228, median 31.65 s apart within a
  session; 145175 ticks; 20 ticks per sample (median); game time 75086 → 220413. No `waiting-for-live-player` row. The
  longest gap between two rows is 8.1 s (start → first join); no row gap reached the stall watcher's 120 s.
- **Game type, all 7124 occupied join/sample rows (none unoccupied, none paused):** server `survival` 7124, client
  `survival` 7124, `clientInWorld` true 7124, `flying` / `mayfly` / `invulnerable` true 7124, `clientFlying` false in
  14 rows (true in 7110), `hudHidden` false 7124. Overworld 2359 rows, Nether 2356, End 2409.
- **Health 20.0 in 7124 rows (minimum 20.0), food 20 in 7124 (minimum 20).** y 160.0 at every probe (156.0 before the
  first move).

**Weather.**

| | |
|---|---|
| First row with `raining` true | sequence 4621, 19:32:21.56 UTC (03:32:21 CST), 4551 s after the first row (75.9 min), 4543 s after the first join; session 12, overworld probe 0, game time 163105 (the timer gave 75042 + 88032 = 163074; the row before, at 163085, is still dry) |
| First row with `rainingAtPlayer` true | the same row: the player had arrived at overworld probe 0 three seconds earlier (client log 03:32:18, End → overworld) and was standing there when the rain began |
| Rows with rain | 720, sessions 12–14; last at 5311 s, first dry row at game time 177809 (about 14 700 ticks; 760 s of wall time, 12.7 min) |
| Rows with rain on the player | 122, all at overworld probe 0, four visits: 29 (session 12), 31 + 31 (session 13), 31 (session 14); unknown (null) 0 |
| Rain, not on the player | overworld probe 1 (124 rows; `isRainingAt` is false at that spot), Nether and End |
| Thunder | 0 rows |
| Joins | 19: the first in the overworld, clear; 18 in the End. Two joins (sessions 13, 14) while it rained in the overworld, both in the End with `rainingAtPlayer` false; `joinsInRain` `[]` |

The moment of ACCEPT's creative crash (D101: last sample at game time 163081, overworld probe 0, rain beginning) is
the moment this survival player stood through: same probe, rain from 163105 on, client alive to the end. The client
log has 0 lines with `bound must be positive`, 0 with `Exception in thread`; the mod's 21 `Could not find any
resources` lines from setup are there as in s2-rain. Watcher 2 (`<ev>/m34-watch2.log`) fired on nothing. No screenshot
was taken in this run, so whether the drop overlay was drawn is not observed here (it was in s2 and s5).

**Retention.** Controller status `REVIEW_REQUIRED` (it always is when a server is still reachable); the verifier's
verdict is RELEASE_PASS by the mod-owned rule.

| | |
|---|---|
| Before the cut | all 19 stopped servers still reachable after the final settle (at every join, every earlier server was still alive: 1, 2, … 18) |
| The Unlit Campfire cut | applied: `de.cech12.unlitcampfire.CommonLoader.CAMPFIRES`, `sizeBefore 1260`, `removedStoppedServerEntries 1260`, `unsupportedEntries 0`; `heapUsed` 2.30 GiB in the last settle rows, 1.18 GiB in the row after the cut and its GC |
| After the cut | 16 gone; **3 still reachable: servers 10, 14 and 19** |
| `HeapPaths --mod-owned` (the gate's own, `retention-paths.txt` 08587b77…) | exit 0, 3 `UNREACHABLE`, 0 `REACHABLE`: every strong path to each of the three runs through state a mod keeps |
| The rule | `residual * 2 < sessions`: 6 < 19; `modOwnedRetention {retained 3, sessions 19}` |

Shortest paths (my own `HeapPaths` run on the same dump after the soak, 4 paths per target,
`<ev>/retention-shortest-paths.txt`): one server through Xaero's `ServerConfigManager.server` (statics
`XaeroMinimapCore.modMain`, `XaeroMinimapServerCore.modMain`, `XaeroMinimap.instance`, `HudMod.INSTANCE`); one through
`ZombieRenderState.transitionEntity` → the `Zombie` → its `ClientLevel` → the old connection → `MinecraftServer$1`;
one through `SkeletonRenderState.transitionEntity` the same way. These are the three root families of the 30-second
rain control `smoke/s2-rain` (lane m34fix), where they held 3 of 3 and made that run a review; over 19 sessions they
hold 3 of 19. Not determined: which of servers 10 and 14 the zombie's and the skeleton's state hold (the dump gives
addresses, not session numbers); that the Xaero one is server 19 is an inference from it being a last-value field.
Session 14 is the one in which the rain ended; session 10 saw no rain.

**Heap and threads** (join/sample rows; GiB = 2^30 bytes; seconds since the first row; `-Xmx4G`):

| Window | This run (survival, 96 jars) | ACCEPT (creative, 97 jars, 272912f0) | Failed survival run (97 jars, 25d029d2) |
|---|---|---|---|
| [0, 600) heap median / max; threads median | 1.93 / 2.84; 70 | 1.66 / 2.24; 82 | 1.84 / 2.60; 86 |
| [600, 4600) | 2.20 / 2.99; 87 | 2.01 / 3.01; 103 | — (ended at 598 s) |
| [0, 4600) | 2.17 / 2.99; 86 | 1.97 / 3.01; 100 | — |
| [4600, end) | 2.76 / 3.76; 101 | — (crashed at 4554 s) | — |
| whole run | 2.42 / 3.76; 96 | — | — |

- Heap after the explicit GC that follows each disconnect (`<ev>/heap-floor.txt`): 1.03, 1.10, 1.16, 1.24, 1.32, 1.40,
  1.45, 1.52, 1.59, 1.66, 1.74 GiB after sessions 1–11, then on to 2.29 after session 19: about 0.07 GiB per session.
  ACCEPT's creative run: 1.04, 1.12, 1.17, 1.24, 1.32, 1.41, 1.45, 1.54, 1.61, 1.68, 1.74 after its 11 sessions. The
  two agree within 0.02 GiB at every session, so the per-session floor does not depend on the player's game type or on
  JourneyMap; it is the stopped servers the Unlit Campfire root keeps (2.30 → 1.18 GiB when it is cut).
- **Observation:** the highest `heapUsed` was 3.76 GiB and the highest committed 3.97 GiB, under a 4 GiB limit, in the
  last half hour. The run never failed an allocation, but a soak much longer than two hours on this pack is not shown
  to fit in 4 GiB (extrapolated from the floor, not measured).
- Threads grow from 53 at the first disconnect to 90 at the last. The whole growth is `Netty Local IO #n` (3 → 39) and
  one `pool-n-thread-n`: three more per session for ten sessions, then one per session, ending with the names `#0`–`#18`
  twice and `#19` once, i.e. two pools of at most 20 on this 10-core machine. ACCEPT's creative run shows the same family (3 → 31 in 11
  sessions). Read from the thread dumps; the pools look bounded at 40, a longer run was not made, and native was not
  compared.
- Median heap in the first ten minutes is higher here than in either 97-jar run (1.93 against 1.66 and 1.84) while the
  post-GC floors agree. Not attributed.

**Caught lines, crash reports, log.** `[kernel] CAUGHT none`; both caught files empty; no crash report. The client log
(9643 lines) has 2748 ERROR lines of the kinds the two earlier runs' logs have (2590 `Detected unsafe terrain read during
worldgen`, against 2611 and 2576 there; 95 `Failed to parse resource conditions … waystones`, which grow with the
sessions; 37 `Block-attached entity at invalid position`, 37 and 37 there; authentication and Realms failures of an
offline account). One line is new and follows from the
fixture: `The following mods have version differences that were not resolved: commonnetworking … journeymap …
journeymap_api (… -> MISSING)`, a warning about the save's recorded mod list (the world was saved with JourneyMap).
The server tick log at 03:32: `mean 49.97ms`, `at twice the budget or worse 22 (0.0%)` of 88 300 ticks.

## 4. What this run shows, and what it does not

Shows (measured, once, on this Mac, at 43efbd92):

- On 96 of the default fixture's 97 jars a survival-player release soak completes: 7241.8 s of occupied simulation,
  19 same-JVM save/reopen cycles, all six probes visited 38 times, client exit 0, no kernel catch, strict policy.
- A survival player meets rain inside a release soak, onset on the player included, and nothing happens: the open
  question of D103 ("no rain inside a release soak").
- The two-hour retention verdict that could not be predicted (m34fix, "Open after the repair") came out as a pass
  under the existing rule: 3 of 19 held, all mod-owned, the same three roots as s2-rain.
- The gate's own evidence step works on the "stale" path in a real release run (it had run for real only on "absent").

Does not show:

- **Anything about the default 97-jar fixture.** JourneyMap 6.0.1's lock-order inversion is untouched, and no
  RELEASE_PASS on the default fixture exists. `build/verification/m34-soak/last-release.json` now points at this run:
  it is a pass on `M34_FIXTURE` (the manifest's `fixture` names `build/m34-nojm-fixture`), not on the default.
- Thunder (0 rows), a join into rain on the player (cannot arise on this fixture: every later join is in the End),
  damage refusal (nothing is known to have attacked the player; health never moved), hunger beyond "food stayed 20".
- That the rain overlay was drawn (no screenshot this time).
- Repeatability (one run), a zh locale (D88b), Windows, native NeoForge (no native client ran).
- A soak longer than two hours (see the heap observation).

## 5. Deviations, interventions, what was not done

- `caffeinate -dims -w <script pid>` ran beside the gate (a guard against sleep at night; the gate does not have one).
- Polling was every 9–10 minutes (the tool's limit per wait), reading the watcher logs only. Besides that, while the
  soak ran: one read of its telemetry file at 03:34 (a few MB, to see the first rain rows), reads of older records, and
  a dry run of `<ev>/analyse.py` on the two older telemetry files at about 02:17 (under a second of Python). No other
  client, Gradle or game process was started; no keyboard or mouse tool was used.
- The stall watcher (`m34-watch.sh`: `jstack -l` at 120 s and 240 s of idle telemetry) took no dump: telemetry was
  never idle that long. Watcher 2 (`m34-watch2.sh`: one `jstack` plus the log tail on `bound must be positive`,
  `Exception in thread`, a crash report or an `hs_err` file) fired on nothing.
- After the soak: `HeapPaths` once on the run's heap dump for the shortest paths (through `heavy.sh`, one slot, 25 s);
  one idle Gradle daemon left by the gate's own kernel-jar build (pid 91317, alive and idle through the soak) killed
  (`<ev>/interventions.txt`).
- `build/retention-control` was not moved aside; the gate judged it stale and measured. Its two older arm directories
  are still there (281 MB in all).
- Not done: no second run, no control run, no native run, no screenshots, no mapping of servers 10 / 14 to their
  holders, no investigation of the higher early-heap median. Nothing was fixed; no threshold, gate or fixture default
  was changed.

Kept (gitignored): `<run>` (2.4 GB, of which the heap dump `evidence/retained-after-native-release.hprof` is 1.95 GB
and can be deleted once nobody wants another path query), `build/m34-nojm-fixture` (an APFS clone; 501 MB by name,
shared blocks), `build/retention-control`, the two older run directories. 13 GiB free afterwards. No process, lock,
queue entry or listener is left.
