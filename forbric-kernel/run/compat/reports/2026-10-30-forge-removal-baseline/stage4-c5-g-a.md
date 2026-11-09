# Stage 4 C5, lane G-A: loader, data, config and refusal gates on the genuine base (+ review fixes)

Lane G-A of the C5 plan (`c5/PLAN.md` §2 "Lane G-A", amended by D43-D67), branch `s4-c5-g-a` cut from the stage branch
at `cd6d0fe8`, worktree `s4c5-g-a`, ports 46620-46639 (two gate queues at 46620.. and 46630.., m0 at 46628, native
NeoForge at 46629). Notes and logs: `~/Forbric-backups/stage4-notes/c5/g-a/` (below: `g-a/`). Every run here is on
`.stage4` (`FORBRIC_OLD=<wt>/.stage4`, the lane's own copy of the Phase 0 neolive canary), through `.stage/heavy.sh`
(client gates through `.stage/client.sh` first), bash, `MC_DIR` unset.

## Harness: how anything could be built (2026-11-09)

`~/.gradle/caches` was recreated on 2026-11-08 20:47 and no longer holds the kernel's dependencies, so every `--offline`
build failed (`g-a/suite-first.log`). That first run's `gradlew` also **downloaded the Gradle 9.1.0 distribution**
(`https://services.gradle.org/distributions/gradle-9.1.0-bin.zip`, 18:52-18:53, by the wrapper itself, not by a lane
command: `suite-first.log` lines 2-3, `~/.gradle/wrapper/dists/gradle-9.1.0-bin/.../gradle-9.1.0-bin.zip.ok`). It is
the only download; whether it stays is the orchestrator's ruling (review B-1).

The review-fix round builds through a lane-local `GRADLE_USER_HOME` (`g-a/gradle-home`, `init.d/g-a-offline-local.gradle`,
`org.gradle.daemon=false`, wrapper dists linked read-only) and a file repo (`g-a/localrepo`) of jars already on this disk,
no download:
- at the pinned versions, each byte-identical (sha1) to a copy Forbric itself ships or installed: asm*/sponge-mixin/
  class-tweaker/sat4j/night-config/mapping-io/tiny-remapper (installer libs, `run/installed/libraries`), fastutil
  8.5.18 and guava 33.6.0-jre (same), log4j-api 2.24.3 (surviving cache), mixinextras-fabric 0.5.4 (= the copy nested
  in the 11-02 kernel jar, sha1 5e167154);
- substituted, because no local disk holds them: jetbrains annotations 23.0.0 -> 26.0.2 (compileOnly) and
  junit-jupiter 5.9.2 / platform 1.9.2 -> 5.11.4 / 1.11.4 (test only).

Fidelity: at the lane tip `928e0690` this build recompiled `compileJava` and then found `jar` and `runtimeJar`
UP-TO-DATE against the 2026-11-02 build made with the real cache, i.e. the main and runtime classes are byte-identical
(`g-a/fix/build-tip.log`; the 11-02 jar is kept as `g-a/2026-11-02-surviving/…jar.2026-11-02`, sha1 fb7d9080). Test
results are under junit 5.11.4, not 5.9.2.

## Commits (final shas; history rewritten once in this round, review A-4)

| sha | was | subject |
|---|---|---|
| `e256ca3b` | ad3aeef8 | Point gate-m0 at the genuine base (BASE, no FORGE in the scan, oracle set-aside acceptance, C6 SKIP) |
| `4c71cf23` | 7b97e6b9 | Drop the MinecraftForge-carrier checks from m7, m10, m15, m16, m28; re-pin m10 to the one NeoForge carrier |
| `0e619ea8` | 572e56a0 | Say whose mods NeoForge's isLoaded now answers for; m18 expects that line once (D65) |
| `9a874db4` | 789ec72a | Rewrite gate-m21 for the genuine base (D49) |
| `bf03b724` | c3f7d385 | Remove m25's MinecraftForge worldgen checks and its retired-switch second boot |
| `9767147c` | e5939226 | Re-pin gate-m26 (BASE options seed, no merged-Hud javap, PiP writer asserted, D65) |
| `449d99f9` | 1bcfb6ea | Delete m29's capability lines and boot 4, and m30's refusal boot with its MinecraftForge fixture (D51) |
| `f6014e4d` | 928e0690 | Guard the fixed rundirs of m8, m11, m19, m24/b/c (D46) |
| `5975eea6` | 6d7e5a7a | m0 step-3 judge red control as GateM0CaptureStepTest rows (review B-3) |
| `339f4027` | e1709812 | m21 asserts no setup phase failed to post (review A-3) |
| `ef8cb19f` | df3813d8 | build-attribution-canaries.sh header, ForbricNeoLiveMod reload-probe javadoc (reviews A-5/B-7, B-5) |
| `ba4ff6fa` | 28cdbe0b | m21: measured control counts over @NEOSCAN@/@SEEDED@/@DATAPACKS@, native citation, dual_forge defect (A-1/A-2/B-2/B-3) |
| `4fc5bf12` | 0d4876d6 | m30: where the 7/3/2 counts were measured (A-2/B-3) |
| `5b761e62` | 3f34c1e9 | m26: drop the never-printing refill-line check, write in the publishModList count (A-2/B-3, new finding) |
| `365c8489` | — | m11: the earlyConfigs control's genuine-base count |

The rewrite only moved the deletion of `canary/attribution/forbricforgecanary/**` and the `BlockEvent` stand-in from
ad3aeef8 into 1bcfb6ea (with a body line saying so) and pointed sha references inside messages at the new commits.
The final tree is identical to the pre-rewrite tip (checked with `rev-parse ^{tree}`); it was done with plumbing
(`commit-tree`, `update-ref`), no checkout.

**Compile and fixture build at every commit** (review B-8, `g-a/b8/summary.txt`): each of the 14 commits up to
`5b761e62`, from a `git archive` tree in scratch: `compileJava compileRuntimeJava compileTestJava` BUILD SUCCESSFUL 14/14,
and `build-forge-refusal-fixtures.sh` rc 0 14/14. Before the rewrite the fixture build failed at ad3aeef8 ("error: no
source files … forgecanary did not compile", `g-a/b8/<ad3aeef8>-refusal-fixtures.log`): review A-4 confirmed.
`365c8489` changes one comment in a gate script.

## Suite

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all cleanTest test --continue` in an
APFS clone of the worktree at `5b761e62`: **5769 tests, 41 failed, 0 skipped; the 41 names are exactly
`c4/failures-close.txt` (0 new, 0 gone)** (`g-a/runs/suite.log`, XML `g-a/runs/suite-results/`, names
`stage4-c5-g-a-open-failures.txt` beside this file). The same at `df3813d8`'s tree (`g-a/runs/suite-df3813d8.log`).
Targeted after the m26 change: the 6 test classes that read gate-m0/m21/m26/m30 or the attribution builder, 45/45.

## Gates (genuine stage)

PASS/FAIL are `^\[kernel\] PASS|FAIL` line counts. Logs `g-a/runs/<label>/` (gate.out + the gate's own logs).

| gate | PASS | FAIL | SKIP | note |
|---|---|---|---|---|
| m0 | 13 | 1 | 1 | FAIL = step 2, the unit suite inside the gate (36 failed, 3 skipped of 5769; all within the 41 known failures, owners in failures-close). SKIP = step 4 (C6, D64). Steps 3, 5, 5b, 6 PASS (oracle both sets; 5b misses exactly the 7 mods, 5 refused + 2 set aside). Run in the suite clone at ef8cb19f's tree. |
| m7 | 28 | 0 | 0 | |
| m8 | 20 | 0 | 0 | |
| m10 | 24 | 0 | 0 | |
| m11 | 14 | 0 | 0 | |
| m15 | 22 | 0 | 0 | client |
| m16 | 39 | 0 | 0 | client |
| m18 | 20 | 0 | 0 | |
| m19 | 40 | 0 | 0 | |
| m21 | 57 | 1 | 0 | FAIL = dual_forge, the open defect below |
| m24 / m24b / m24c | 30 / 23 / 25 | 0 | 0 | |
| m25 | 16 | 0 | 0 | |
| m26 | 45 | 0 | 0 | at `5b761e62`'s tree; before that change 45/1 (the refill line, below) |
| m28 | 15 | 0 | 0 | |
| m29 | 38 | 0 | 0 | |
| m30 | 28 | 0 | 0 | |

The default runs used the tree of `ef8cb19f`, or (m24/m24b/m24c) one that differs from it only in m21's comments; m26
was re-run on `5b761e62`'s tree, as were the controls of m8, m10, m11, m16, m25, m28 and m26's publishModList. Commits
after a gate's run change only comments of m21, m30 and m11.

### Red controls measured (2026-11-09)

| control | FAIL lines | which |
|---|---|---|
| m0 step 3, FORGE row | test | GateM0CaptureStepTest: red with a FORGE row and without NeoForge, green on Fabric+NeoForge; mutant (judge without `"FORGE" not in ecos`) turns exactly the FORGE row red |
| m8 `overlayConditions=off` | 2 | the veto and the pack naming |
| m10 `packRepair=off` | 1 | overlay merge repaired |
| m10 `modDataPacks=off` | 9 | the server never comes up (unserved data/: empty-registry lookup, chunk-gen failure, crash report) |
| m11 `earlyConfigs=off` | 6 | early configs, COMMON on a server, Done, config-less getActive x2, dynamic-registry setup, crash report |
| m16 `forbricneolive.clientServerConfig=off` | 4 | exactly the four the header names |
| m16 `M16_NEO_PREWRITE=off` | 5 | exactly the five the header names |
| m21 `neoScanData=off` | 11 (+dual_forge) | 6 reader lines, the count line, both boots' jar counts |
| m21 `seededScanData=off` | 5 (+dual_forge) | 3 LoadingModList lines, both boots' "neither reader failed" |
| m21 `modDataPacks=off` | 1 (+dual_forge) | the data loader ran (over 0 files) |
| m25 `M25_NO_DATA=1` | 5 | the NeoForge modifier, data, probe, structure and marker checks |
| m26 `publishModList=off` | 20 | the twelve registration events and the eight checks that read what they registered (re-run at `5b761e62`) |
| m26 `blockStateIdRefill=off` | 1 | the same refill-line check as the default run; the late block's id is unchanged (below) |
| m28 `earlyConfigs=off` | 7 | the NeoForge COMMON half |
| m30 `loadReportRewrite=off` / `mixinErrorAttribution=off` / `abiAudit=off` | 7 / 3 / 2 | exactly the checks the header names; none in the control boot |

Not measured in this round: the m21 `modFilePerJar` controls (they belong to the perJar flip, not done), m29's strict
exit 78 and refusal en/zh beyond the gate's own default run, m18/m19 control boots beyond their default runs.

### D49: native NeoForge's answers to m21's probes

Native NeoForge 26.2.0.93 dedicated server (APFS clone of the stage's native-controls image, deleted after), with m21's
pack-and-probe block extracted verbatim (lines 83-144 at the lane tip): all 13 answers equal the gate's expectations
(kept, dropped, dual_neo, neo_owned, unowned: `Test passed`; variant, variant_unowned, dual_forge: `Can't find element`,
dual_forge with `Couldn't parse data file … Unknown registry key … neoforge:condition_codecs`; the three features
`Placed`; both recipes `Test passed. Count: 1`). `g-a/d49/native-d49.log`, script `g-a/d49/native-d49.sh`. Review A
measured the same the same day (`c5/review-g-a-A/native-d49.log`). The lane's 2026-11-02 measurement's log was lost (D67).

## Open defects and items for other owners

1. **m21 dual_forge (owner: lane S, D69: fixed by S's `748f142b`; green on the merged tree G-A + S, 54/0, see "G-A
   wave-2 review fixes"; still red on G-A's branch alone, which carries no S commit).** Forbric kept
   `forbricm21:predicate/dual_forge.json`, whose `neoforge:conditions` name `forbricm21:no_such_condition`; native
   NeoForge skips it. Cause: `ForbricMergedBaseCompatTransformer.letForeignResourceConditionsThrough` puts
   `KernelNeoConditions.lenient` before `ICondition.CODEC`, and `lenient` reads every unregistered condition type as true
   for every pack. The guiding rule keeps that leniency only where the mod's own ecosystem tolerates it (a Fabric mod's
   data). gate-m8's case is exactly that: Terralith_26.2_v2.6.4.jar carries only `fabric.mod.json`, and its
   `terralith:config` condition is what m8 asserts the leniency for. So narrowing the leniency to packs of Fabric-arbitrated
   mods (keeping the overlay veto, with its own switch) would make m21 and m8 green together; review B-2's "no kernel
   behaviour lets both go green" does not hold. G-A owns neither file, so the check stays red and recorded here.
2. **Kernel blockstate refill: closed by lane S's `35985d32` (deleted, switch retired), on the evidence below; G-A's
   m26 header follows it in `0ebe1b99`.** On the genuine base
   `KernelRegistryContent.rebuildBlockStateIds` never prints: none of the 26 genuine boots of these runs (servers and
   clients) has "rebuilt NeoForge blockstate→id map", and with `-Dforbric.blockStateIdRefill=off` the late Fabric block
   still reads id 64735, as by default. NeoForge's own block callback maps each state as it registers. The refill looks
   merged-base-only; whether it goes is not G-A's call. m26 no longer asserts its line (`5b761e62`).
3. **m0 step 2** is red on the pinned build because of the three LiquidBounce tests, which skip without their jars (m0
   runs without `requireFixtures`; D58, owner P1). Wave 1's 41 known failures are gone with the pinned build.
4. **Gradle cache: resolved by D68** (the cache is restored and wave 2 builds on the pinned versions); D68 also settles
   the wrapper download asked about in wave 1.
5. **Done in wave 2 (`d7f80b9a`, see "Wave 2"):** the perJar flip (D42/D55) with its A/B on gate-m21 and gate-m9 (the
   client-merged-pack boot), `KernelModFile*` javadocs, `KernelModFilePerJarTest:67`, m21 `:280-365` (the per-jar block)
   and `GateNeoCanaryContractTest:54-61,97`; the m21 `modFilePerJar` controls after the flip.

## Review findings (lens A and B, 2026-11-09)

| finding | outcome |
|---|---|
| A-1 / B-2 dual_forge red, no owner | confirmed (cause verified, native verified). Check kept red, comment names cause and record; owner escalated (item 1). B-2's claim that no behaviour greens both m8 and m21 refuted (Terralith is Fabric-only). |
| A-2 / B-3 claims without measurements | fixed: m21 `ba4ff6fa`, m30 `4fc5bf12`, m26 `5b761e62` (all measured above), m0 comment `5975eea6`; m0 step 6 now measured green (4/4). D49 re-measured by G-A. |
| A-3 m21 replacement absence | fixed `339f4027` (PASS in the m21 run) |
| A-3 m16 optional absences | not done: positive join/leave checks cover a hard handshake failure (m16 39/39, both canary controls red as stated) |
| A-4 bisectability | fixed by the rewrite (fixture build 14/14) |
| A-5 / B-7 builder header | fixed `ef8cb19f`; B-7's m16:49-50 Stage 3 paragraph left as history (reviewer: acceptable) |
| B-1 undisclosed download | confirmed; recorded here and in walls/handoff; ruling asked |
| B-4 surviving 11-02 logs | preserved in `g-a/2026-11-02-surviving/` with SHA1SUMS (m7 f292d64f, m8 8c490e2f, m10 d06695d3, jar fb7d9080); m10 re-run in full: 24/0 |
| B-5 ForbricNeoLiveMod javadoc | fixed `ef8cb19f`, 14 lines kept, file 625 lines; rebuilt canary's content sha1 = Phase 0's `c0013586…` |
| B-6 incomplete lists / notes | fixed: this record, `g-a/failures-g-a.txt`, checks/handoff/walls headings "G-A review fixes"; open list above names each item |
| B-8 compile at every commit | fixed/measured: 14/14 (above) |

## Wave 2 (2026-11-09/10): the perJar flip, and every G-A gate on the pinned build

Branch `s4-c5-g-a` rebased onto the stage tip `f0cb66b0` (fast-forward: the branch was an ancestor). Pinned build (D68):
plain `~/.gradle`, `--offline`, JUnit 5.9.2 / annotations 23.0.0, no init script, no `GRADLE_USER_HOME`; nothing was
downloaded. Logs: `~/Forbric-backups/stage4-notes/c5/g-a/w2/` (below `w2/`), gate runs under `w2/runs/<label>/`
(`gate.out` + the gate's own logs), runner `w2/run-gate.sh`.

### Commits

| sha | subject |
|---|---|
| `d7f80b9a` | Give every NeoForge mod of one jar that jar's one IModFile by default, as FML builds it, now that the game base is NeoForge's own |
| (this record) | Record lane G-A's wave 2 |

### First act (suite at the rebased tip)

`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all cleanTest test --continue` in the
worktree at `f0cb66b0`: **5772 tests, 0 skipped, 5 failed**, exactly the 5 names of `c5/failures-wave1.txt`
(`w2/suite-first.log`, XML `w2/suite-first-xml/`).

### The perJar flip (D42 F6, D55), its A/B and its red controls

`KernelModFile.perJar()` is now on unless `-Dforbric.modFilePerJar=off`; with the switch off the kernel says so once
("every NeoForge mod keeps an IModFile and file info of its own, so a jar of several mods is listed once per mod in the
scan index (-Dforbric.modFilePerJar=off)"). Javadocs of `KernelModFile`, `KernelModFileInfo`, `KernelModInfo`,
`KernelContainers`, `KernelDeclarationReaders` say the new default. `KernelModFilePerJarTest` pins the default (new
`byDefaultTwoModsOfOneJarShareOneFileAndTheIndexListsTheJarOnce`) and the switch-off shape (the old default test, renamed
`withTheSwitchOffEveryModKeeps...`); its other tests run on the default. gate-m21 lost its second boot: the default boot
asserts "lists the canary's jar 1 time(s)" and "found its own @NeoLiveScanned" exactly once, and
`GateNeoCanaryContractTest` follows the one-boot block (incl. a case that the per-mod shape is red on exactly those two
checks). `ci-unstaged.tsv`: the two renamed rows replaced by three (the class's new and renamed names), measured: a
tracked-files-only checkout without a stage skips all six tests of the class with "[fixture:staged] staged carrier
absent" (`w2/ci-unstaged-perjar.log`); `ci-prepared.tsv` has no row for the class (it runs there).

**A/B** (A = the tree before the flip, `f0cb66b0`, in an APFS clone; B = the flipped tree in the worktree; both pinned
build, genuine stage, fresh APFS clones of `client-merged-pack` by real path for m9):

| run | A (one file per mod) | B (one file per jar) | what differs |
|---|---|---|---|
| gate-m21 | 57 / 1 | 53 / 1 | FAIL in both = dual_forge (D69, lane S). 57→53: the second boot's Done check, its two per-jar checks and its "neither reader failed" went; the default's two checks now ask for 1. Default-boot log, as a multiset of normalised lines (`w2/norm.py`): only the canary's scan lines differ (A: "2 file(s), 10 annotation(s)", "lists forbricneolive's jar 2 time(s)" and the ModList reader's three lines twice; B: "1 file(s), 5", "1 time(s)", the reader lines once), besides the port and a night-config instance id. |
| gate-m9 | 163 / 0 | 163 / 0 | none in the gate. Client `latest.log` (`w2/m9-ab-diff.txt`): JEI's lines are the same set (timings differ), Jade 96/96 lines, "JEI's 35 saved key binding(s) ... unchanged" PASS in both. The ERROR 17/13 and "Exception" 38/27 lines differ by network answers (A: SSL/read timeouts to Mojang auth, Realms, update checkers; B: 401s and an expired mod-data cache). The other differences, none from the flip (review B-4 listed the ones this row first left out): splash/chat text; Mods-screen frame counts (37/29); two orders that change per JVM, the 10 datapack registries in "posted datapack-registry declaration" (same set; it follows `KernelModLoader.publishedNeoMods()`, a `Map.copyOf`) and pride's two `[modproperties]` keys; the Tick line's "at twice the budget or worse" count (5 / 8); and two B-only lines, "Mod 'xaeroworldmap' took Ns to run a deferred task" and "Preparing spawn area: 100%". |

Why m9 shows no difference: no NeoForge jar of `client-merged-pack` declares two mod ids (all 97 jars: at most one
`[[mods]]` in `neoforge.mods.toml`; the 12 jars with two `@Mod` classes are main+client pairs of one id each, read from
the annotations with `javap -v`), so one file per mod is already one per jar there. The canary is the only measured
multi-mod jar in these two runs; it is one only because the kernel publishes an `@Mod` its file does not declare, which
native NeoForge refuses (review A-1). A real two-`[[mods]]` jar was booted A/B after the review ("G-A wave-2 review
fixes": wthit + waila). Nothing regressed, so the flip stays on (D42).

**Red controls of the flip** (`w2/mutant-*.log`, `w2/runs/B-m21-*`):

| control | result |
|---|---|
| mutation: `perJar()` back to the old parse (default off) | `KernelModFilePerJarTest` 4 of 6 red (the default test, the shared-file test, the walk-up and the naming test); restored: 6/6 green, files byte-identical to before (`cmp`) |
| mutation: m21's check accepts "[12] time(s)" | `GateNeoCanaryContractTest.m21NeoBlockTellsOneJarFromTheSameJarListedOncePerMod` red, the other 6 green; restored |
| m21 `M21_EXTRA_JVM=-Dforbric.modFilePerJar=off` | 51 / 3: exactly the two per-jar checks ("lists the canary's jar once" got 0 lines saying 1; "found in one entry" exactly 1 got 2) + dual_forge; the kernel's switch-off line printed once |
| m21 `neoScanData=off` | 44 / 10: 9 (six reader lines, the count line, the two per-jar checks) + dual_forge (11 before the flip, when the second boot repeated two) |
| m21 `seededScanData=off` | 49 / 5: 4 (three LoadingModList lines, "neither reader failed") + dual_forge |
| m21 `modDataPacks=off` | 52 / 2: 1 ("the registered data loader ran", got 0) + dual_forge |

m21's header carries these counts (the wave-1 placeholders are gone).

### Every G-A gate on the pinned build (genuine stage, `.stage4`, fresh canary, `MC_DIR` unset, bash)

Server gates and m21 ran in the worktree with the flip applied (the tree of `d7f80b9a`; m21's default run and its
four controls ran before its header comments got the measured counts, a comment-only difference); client gates under
`client.sh` first; m0 in an APFS clone of the worktree (it runs `cleanTest test` in its own tree). Counts are
`^\[kernel\] PASS|FAIL|SKIP` lines of `gate.out`.

| gate | PASS | FAIL | SKIP | note |
|---|---|---|---|---|
| m0 | 13 | 1 | 1 | FAIL = step 2: 3 of 5773 skipped — the three LiquidBounce tests ([fixture:third-party], jars absent; m0 runs without `requireFixtures`, so they skip there and step 2 counts a skip as a failure; D58, owner P1). SharedFinalSourceActualTest passes inside m0 (its step 1b captures the definitions). SKIP = step 4, C6 (D64). Steps 3, 5, 5b, 6 PASS. A first m0 run in a clone without the worktree's `.git` file had a second FAIL ("could not check the installer's bundled link baseline: fatal: not a git repository", `w2/runs/F-m0-nogit/`): harness, the clone's `.git` pointer restored, re-run as above. |
| m7 | 28 | 0 | 0 | |
| m8 | 20 | 0 | 0 | |
| m10 | 24 | 0 | 0 | |
| m11 | 14 | 0 | 0 | |
| m15 | 22 | 0 | 0 | client |
| m16 | 39 | 0 | 0 | client |
| m18 | 20 | 0 | 0 | |
| m19 | 40 | 0 | 0 | |
| m21 | 53 | 1 | 0 | FAIL = dual_forge: D69, lane S narrows `KernelNeoConditions.lenient` and merges before G-A; m21 must be green after G-A's rebase in the merge |
| m24 / m24b / m24c | 30 / 23 / 25 | 0 | 0 | |
| m25 | 16 | 0 | 0 | |
| m26 | 45 | 0 | 0 | client |
| m28 | 15 | 0 | 0 | |
| m29 | 38 | 0 | 0 | |
| m30 | 28 | 0 | 0 | |

Every count equals wave 1's on the substituted harness except m21 (the flip, above). m0's count is wave 1's too (13/1/1);
only the cause of its step-2 FAIL changed: 3 skips instead of 36 failures.

### Red controls wave 1 had not shown

| control | result |
|---|---|
| m29 `M29_POLICY=continue` | 34 / 4: exactly boot 1's four stop checks ("the report says strict decided" got CONTINUE, "strict stopped the launch with the typed policy stop" got exit 0, "and said so", "it never came up"), every refusal and report check green — as the header says |
| m29 `M29_REFUSED=` (only the NeoForge canary in boots 1 and 2) | 16 / 22: 11 red in each of boots 1 and 2 (the three refusal checks, the machine report, three stop checks, the four load-report checks); 5 green in each ("the report says strict decided", "nothing of the refused jars ran", the two Forge-family mixin-config checks, "no crash report"), because nothing is refused and the policy is still strict; the control boot's 6 green. (This row first said "every refusal, report and stop check" red: review B-3; the gate header now has the measured list, `baa8373d`.) |
| m29 default | strict exit 78 and both refusal languages green (38/0): the typed stop, the en and zh load reports |
| m18 control boot (in the gate, `-Dforbric.crossEcosystemPresence=off`) | the switch-off boot answers false where the default boot answers true: "a NeoForge mod is told the Fabric mod is absent", "a Fabric mod is told the NeoForge mod is absent", LoadingModList modFile=false, "0 Fabric for presence", no presence registration — all PASS, while the default boot's "sees" checks PASS |
| m19 control boot (in the gate, arbitration off) | "the control really disabled arbitration" and "the library was then claimed twice, or tried to be" (3) PASS; the presence control ("does NOT hang on ModPresence") PASS |

### Final suite

The same command in an APFS clone of the worktree made around 00:15, before the flip was committed at 01:40 (`d7f80b9a`;
that the clone's tree equals the commit is not shown, review B-6: the saved gate script and `KernelModFile` match it
except m21's header counts). The review-fix suite at the tip `baa8373d` supersedes it ("G-A wave-2 review fixes").
Result: **5773 tests, 0 skipped, 5 failed** — the 5 names of `c5/failures-wave1.txt`, 0 new, 0 gone, every kept row's
first 240 message characters equal (`w2/suite-final.log`, XML `w2/suite-final-xml/`). 5772 → 5773 is
`KernelModFilePerJarTest`'s new default test. List: `stage4-c5-g-a-open-failures.txt` beside this file and
`c5/failures-g-a-w2.txt`.

### Open after wave 2 (as corrected by the review fixes below)

1. m21 dual_forge: fixed by lane S's `748f142b` (D69). On the merged tree G-A + S gate-m21 is **54 / 0** (not 53/0: the
   wave-2 run was 53 PASS + 1 FAIL = 54 checks; reviews A-2/B-1), and gate-m8 20 / 0. The merger re-runs both after
   G-A lands on S.
2. m0 step 2: the three LiquidBounce fixture reds (D58, P1).
3. `KernelRegistryContent.rebuildBlockStateIds`: closed, deleted by lane S in `35985d32`; G-A's m26 header follows in
   `0ebe1b99`.
4. Observation, not a G-A item: `KernelModLoader.publishedNeoMods()` is a `Map.copyOf`, so the order the kernel posts
   per-mod events in changes from JVM to JVM. Native FML's sequential posts (`ModLoader.postEvent`, e.g. the
   datapack-registry declaration) go in its sorted mod order; its setup phases go through `dispatchParallelEvent`
   (javap of the carrier's `ModLoader`), so their native order is not fixed either. No gate reads the order. Owner: the
   orchestrator, to give it to a kernel lane or close it.

## G-A wave-2 review fixes (2026-11-10)

Reviews `c5/review-w2-g-a-A.md` (lens A) and `c5/review-w2-g-a-B.md` (lens B) of `d7f80b9a` + `b5283cba`. Every finding
was re-checked against the logs, the carrier and the S branch before it was acted on. Logs:
`~/Forbric-backups/stage4-notes/c5/g-a/w2r/` (below `w2r/`), runner `w2r/run-gate.sh` (a copy of `w2/run-gate.sh`
writing under `w2r/`). Pinned build (D68), genuine stage, bash, `MC_DIR` unset, nothing downloaded.

### Commits

| sha | subject |
|---|---|
| `0ebe1b99` | Reword m21's dual_forge comments and m26's refill header for lane S's D69 fix and refill deletion, which merge before this branch |
| `baa8373d` | Say that m21's per-jar pair is seen through an @Mod native NeoForge refuses, and give m29's empty-refusal control its measured 11 red checks per boot |
| (this record) | Record lane G-A's wave-2 review fixes |

`0ebe1b99` describes the tree after lane S: on G-A's branch alone the refill still exists and dual_forge is still red.
Both lanes' notes already require S to merge first.

### Per finding

| finding | verdict | what was done / evidence |
|---|---|---|
| A-1 m21's per-jar pair rests on an `@Mod` native NeoForge refuses | confirmed | The canary's `neoforge.mods.toml` has one `[[mods]]` (`forbricneolive`); `ForbricNeoClientOnlyMod` is `@Mod(value="forbricneoclientonly", dist=CLIENT)`. javap of the staged carrier's `FMLJavaModLanguageProvider.validate` (`w2r/FMLJavaModLanguageProvider.javap.txt`): it collects the file's javafml mod ids and reports `fml.modloadingissue.javafml.dangling_entrypoint` for every `@Mod` not among them, with no dist filter. The kernel publishes it ("published 2 NeoForge mod(s) into ModList [forbricneolive, forbricneoclientonly]", not constructed on the server). m21's comment and the `KernelModFile` javadoc now say what the pair measures (`baa8373d`). A real two-`[[mods]]` jar booted A/B (B-5 below). Not done by G-A (not its files): the canary declaring its second mod (canary owner, Phase 0a redo) and the leniency's guiding-rule check (kernel lane): both for the orchestrator. |
| A-2 / B-1 expected m21 count after S is 54/0 | confirmed | B-m21 = 53 PASS + 1 FAIL. Measured: an APFS clone holding `git merge-tree --write-tree s4-c5-g-a s4-c5-s1` (G-A `b5283cba` + S `f333f1f6`, tree `927dda90`): **gate-m21 54 / 0**, the dual_forge probe PASS ("Can't find element 'forbricm21:dual_forge'") (`w2r/runs/M-m21/`), and **gate-m8 20 / 0**, run unconditionally (B-1: D69 names m8) (`w2r/runs/M-m8/`). Handoff and this record say 54/0. |
| A-3 / B-2 S's follow-ups for G-A | confirmed | refill item closed citing S's `35985d32`; m26 header (`:13-23`, `:147`) and m21's D49 comment and RED-controls note reworded (`0ebe1b99`); m0 "41 known failures" corrected (Open defects item 3). |
| B-3 m29 `M29_REFUSED=''` overclaimed | confirmed | `w2/runs/F-m29-refused-none/gate.out`: per refusal boot 11 FAIL, 5 PASS ("the report says strict decided", "nothing of the refused jars ran", the two Forge-family mixin-config checks, "no crash report"); control 6 PASS; 16/22. Header rewritten with the list, dated 2026-11-10 (`baa8373d`); the wave-2 table row corrected. |
| B-4 A/B "differs only" lists incomplete; three A/B runs claimed | confirmed | `w2/m9-ab-diff.txt` also has pride's `[modproperties]` key order (A/B lines 133/218), the Tick count 5/8, and the B-only xaeroworldmap deferred-task and "Preparing spawn area" lines. The m9 row lists them; the javadoc and Open defects item 5 say "gate-m21 and gate-m9 (the client-merged-pack boot)" (`baa8373d`). `d7f80b9a`'s commit message keeps its shorter list (history is not rewritten); this record is the corrected account. |
| B-5 no real multi-mod jar exercised | confirmed, fixed by measurement | `wthit-26.2-neo-20.0.0.jar` (client-popular, on disk: `[[mods]]` wthit + waila) with its dependency `badpackets-neo-0.12.2.jar` and the canary, one dedicated-server boot each way (`w2r/wthit-ab.sh`, port 46628, through `heavy.sh`). Default: `ModList.getAllScanData(): 3 file(s), 405 annotation(s)`; `-Dforbric.modFilePerJar=off`: `5 file(s), 732 annotation(s)` (the wthit jar and the canary jar each listed twice: 732 = 405 + 322 + 5). Both constructed waila and wthit, reached Done and stopped cleanly. Normalised multiset diff (`w2/norm.py`, `w2r/wthit-ab-diff.txt`): only the canary's scan lines, the switch-off line and one config-watcher/night-config pair with instance ids differ. Both boots list waila and wthit in the load report as "partly did not run — compiled against a different MinecraftForge — net.minecraftforge.fluids.FluidStack" (`w2r/wthit-*.load-report.txt`): the same in both modes, so not the flip; a static ABI-audit finding for the kernel lane to judge (not measured whether native NeoForge loads the jar). |
| B-6 stale or soft text | confirmed | Open defects 3 (m0) and 4 (the wrapper download, D68) updated; m0 wording ("count unchanged, cause changed"); the final-suite wording (the clone predates the commit) and the superseding suite below; the `Map.copyOf` owner is now the orchestrator, and the note says native setup phases are parallel (`ModLoader.dispatchParallelEvent`, javap) as review A noted. README.md:586 (RECORD's file) still says the flip is not done: handed to RECORD in `handoff.md`. |

### Re-runs at the tip `baa8373d` (code tip; this record commit changes only this file)

| run | result | note |
|---|---|---|
| compileJava compileRuntimeJava compileTestJava | green | `w2r/compile.log` (compileRuntimeJava executed) |
| full suite (`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all cleanTest test --continue`, APFS clone of the worktree at `baa8373d`) | **5773 tests, 0 skipped, 5 failed** | the 5 names of `c5/failures-wave1.txt`: 0 new, 0 gone; every message's first 240 characters equal to wave 2's (`w2r/suite-final.log`, `w2r/suite-final-xml/`, `w2r/suite-final-failures.txt`) |
| gate-m21 (worktree) | 53 / 1 | FAIL = dual_forge, as expected without S; 54 / 0 on the merged tree (above) |
| gate-m29 (worktree) | 38 / 0 | |
| gate-m26 (worktree, client, `client.sh` first) | 45 / 0 | as in wave 2 |
| gate-m21, gate-m8 (merged G-A + S clone) | 54 / 0, 20 / 0 | above |

A first compile in this round ran from zsh, which passed `.stage4/props.txt` to Gradle as one argument, so
compileRuntimeJava was skipped ("staged game jars absent"); it was re-run from bash (the result above). Nothing else
ran from zsh.

### Open after the review fixes

1. Merger: G-A lands after S; re-run gate-m21 (expect 54 / 0) and gate-m8 (20 / 0).
2. Orchestrator: the canary's undeclared second `@Mod` (Phase 0a owner) and the kernel's leniency that publishes an
   undeclared `@Mod` (kernel lane, guiding rule); the `Map.copyOf` post order; the wthit ABI-audit label.
3. m0 step 2 (D58, P1).
