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

1. **m21 dual_forge (owner: to be named by the orchestrator; likely the kernel lane, S).** Forbric keeps
   `forbricm21:predicate/dual_forge.json`, whose `neoforge:conditions` name `forbricm21:no_such_condition`; native
   NeoForge skips it. Cause: `ForbricMergedBaseCompatTransformer.letForeignResourceConditionsThrough` puts
   `KernelNeoConditions.lenient` before `ICondition.CODEC`, and `lenient` reads every unregistered condition type as true
   for every pack. The guiding rule keeps that leniency only where the mod's own ecosystem tolerates it (a Fabric mod's
   data). gate-m8's case is exactly that: Terralith_26.2_v2.6.4.jar carries only `fabric.mod.json`, and its
   `terralith:config` condition is what m8 asserts the leniency for. So narrowing the leniency to packs of Fabric-arbitrated
   mods (keeping the overlay veto, with its own switch) would make m21 and m8 green together; review B-2's "no kernel
   behaviour lets both go green" does not hold. G-A owns neither file, so the check stays red and recorded here.
2. **Kernel blockstate refill (owner: the kernel lane; C3-style three checks).** On the genuine base
   `KernelRegistryContent.rebuildBlockStateIds` never prints: none of the 26 genuine boots of these runs (servers and
   clients) has "rebuilt NeoForge blockstate→id map", and with `-Dforbric.blockStateIdRefill=off` the late Fabric block
   still reads id 64735, as by default. NeoForge's own block callback maps each state as it registers. The refill looks
   merged-base-only; whether it goes is not G-A's call. m26 no longer asserts its line (`5b761e62`).
3. **m0 step 2** is red for as long as the 41 known failures stand (their owners are in failures-close).
4. **Gradle cache (orchestrator).** Every other lane's canonical suite needs the cache back or a ruled stand-in; this
   lane's substitution and its limits are above. Ruling also needed on the wrapper download.
5. **Not done in G-A (owner G-A unless reassigned):** the perJar flip (D42/D55) with its A/B on m21, m9 and a
   client-merged-pack boot, `KernelModFile*` javadocs, `KernelModFilePerJarTest:67`, m21 `:280-365` (the per-jar block)
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
