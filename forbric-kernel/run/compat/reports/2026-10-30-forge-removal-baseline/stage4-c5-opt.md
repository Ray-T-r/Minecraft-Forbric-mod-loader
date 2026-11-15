# Stage 4 C5 — lane OPT (verification time: opt/verify taken in, Chinese runs dropped, tests that cannot find anything cut), wave 2d

Branch `s4-c5-opt`, cut from the stage tip b2be55fe (worktree `.claude/worktrees/s4c5-opt`, ports 46680-46699 from
`.stage4/lane-env.sh`; the two sweeps of item 3 on 46800-46839). Notes and logs: `~/Forbric-backups/stage4-notes/c5/opt/w2d/`
(paths below are relative to it). Harness: D68 (pinned build, plain `--offline` from `.stage4/props.txt`, no init script, no
private Gradle home); every Gradle and game run through `.stage/heavy.sh` (sweeps `--exclusive`, under `.stage/client.sh`).
Nothing was downloaded. Reason for the lane: D87, D88 (a, b) and D89 (the owner: "主要是省去不必要的测试节省时间").

## Worktree

An earlier attempt had cut it and cherry-picked bdb11e6a as 630fa662 (clean tree, kept as it was). Every bootstrap link
resolves except `forbric-kernel/build/sweep100-mac`, whose source does not exist in the main checkout either (the open item
every lane since wave 2c reports; nothing here reads it).

## Commits (on b2be55fe)

| sha | what |
|---|---|
| 630fa662 | opt/verify's bdb11e6a, cherry-picked (verdict cache, build once per sweep, longest first, 4 test forks, gate-m15 removed) |
| fa48ecb8 | verdict cache: key the JVM the launchers start (not FORBRIC_JAVA), hash build/compat-inputs and build/purefabric-2026-10-03, timing gates cached by a stated rule, FORBRIC_GATE_FRESH for an acceptance or a sweep without a cache |
| 94cb22cb | gate-m0 reserves 3500 MB (its suite now runs four worker JVMs) |
| 8dd8b04e | gate-m3 and gate-m60's driver use the sweep's prebuilt jar (the last per-gate Gradle runs) |
| a7684e94 | gate-m15's last traces: M15_PORT, gate-m16's header, both introductions' gates table |
| f85b74e4 | zh_CN + English unit tests for every component that reads the system language; DependencyDialogMain.language() |
| 6ef7f098 | no Chinese run in routine verification; GATE-TAGS / --only-tag removed; tiers stop asking for a suite before a sweep |
| 5aefc7bc | gate-m29's Chinese boot removed (SUBSUMED) |
| eae9eddb | gate-m37 and gate-m38 declared `GATE-PARALLEL: mem=2000` (undeclared, they ran alone in every sweep) |
| 64bc571b | gate-m17's two absence checks with no producer removed (DEAD) |
| 4e45d992 | lib.sh control-arm cache; gate-m31 keeps its pure-vanilla arm for identical inputs |
| 8b8adbd1 | PROTOCOL.md names the control-arm cache and test_control_arm.py (CompatProtocolTest) |
| 467c7f80 | verdict cache: run/canary (the builders' output) out of the key, the staged canary sources in; gate-m14 builds its own canary |
| 312d152d | both introductions: 61 gates, 60 declared |
| (this file) | record |

## 1. opt/verify, measured

The cherry-pick is 630fa662; I read its message and every hunk. Each claim below was measured on this branch.

### (a) four test forks

| run (tree 630fa662, `-Pforbric.requireFixtures=all jar cleanTest test --continue`) | wall | tests | failures |
|---|---|---|---|
| default (4 forks), run 1 | 254 s | 5887, 0 skipped | 5: LiquidBounce x3, SharedFinalSourceActual x2 (D58) |
| `-Pforbric.testForks=1` | 486 s | 5887, 0 skipped | the same 5 |
| default, run 2 | 244 s | 5887 | the same 5 |
| default, run 3 | 245 s | 5887 | the same 5 |

`suite-a/cmp.py` compares every testcase name and every failing name across the four XML sets: identical (`suite-a/times.txt`,
`suite-a/*-xml`). Summed class time is 393-405 s forked against 310 s in one fork: classes that build a JVM-wide memo of a
large input (TestFixtures.twinnedBase, the staged base's indexes) build it once per fork instead of once (CarrierHelperCensusTest
2.9 s alone, 9.9 s forked). The wall time halves anyway (486 s to ~248 s). The commit message's 13m58s -> 9m27s was gate-m0's
whole run, not the suite.

Shared fixed paths: PreMixinView's `base.jar.partial` was the one opt/verify fixed. Hunted further: every other writer of a
fixed file in tests or in the code they drive is per process or content-addressed (VanillaReference's `.<pid>.part`,
KernelBundledJars and NestedCandidateInventory `createTempFile` + atomic move, KernelLoadReport's temp report + atomic move,
LibCaughtContractTest's per-UUID tag, lib.sh's `$BUILD/caught/<gate>.<pid>.tsv`). DefinedClassEvidence's `<hash>.part` is
content-addressed and written only by gate-m0's one capture JVM. No fixed port in a test (GatesAllTest's 25599 is a stub
gate's expectation, nothing binds it). Three forked runs gave the same failure set; no order or shared-state flake seen.

Memory: every java process of the forked suite together peaked at 3.4 GB resident in the two forked runs whose whole-java
total was sampled (3426 and 3435 MB; `suite-a/javamem.txt` starts after the first forked run had ended, which has worker
RSS only, 2908 MB) with the four workers at 2.9-3.1 GB in all three; one fork peaked at 2.7 GB (sampled after its first
minutes; workers alone 2.1 GB over the whole run). heavy.sh's two-slot model still fits: a sweep takes both slots (`--exclusive`), so a
standalone suite never runs beside one; two lanes' forked suites side by side are ~7 GB of 16 GB on 8 workers for 10 cores.
Inside a sweep, gate-m0's suite runs beside other gates on the memory it declares, which was 2000 MB for 3.4 GB actual:
94cb22cb declares 3500. The cold sweep's own peak is in item 3.

### (b) the verdict cache

Real gate-m1 runs through gates-all with a private cache (`cache-controls/summary.txt`, `summary2.txt`):

| run | inputs | answered | |
|---|---|---|---|
| r1 | base | REAL GREEN 28 s | |
| r2 | same | CACHED 2 s | its log is byte-identical to r1's (`cmp`) and the RESULT line says `cached: same inputs went GREEN at <time>` |
| r3 | a comment appended to KernelLoadReport.java | REAL | "inputs changed: sources: ...KernelLoadReport.java" |
| r4 | reverted | CACHED | |
| r5 | a comment appended to gate-m1.sh | REAL | |
| r6 | a jar added under .stage4/run | REAL | "jars: <repo>/.stage4/run/opt-control/extra.jar" |
| r7 | FORBRIC_JVM=-Dforbric.optCacheControl=1 | REAL | |
| d  | JAVA_HOME = zulu 25.0.2 JRE | REAL (RED exit 3: the JRE cannot build the jar) | "env: JAVA_HOME=...; jvm: /usr/bin/java ..." |
| r9 | --no-cache | REAL | |
| r10 | --release | REAL (rc 1: the release run fails on the --only skips, as it must) | |
| r11 | base again | CACHED | |

Two holes found and fixed (fa48ecb8). The key took the JVM from FORBRIC_JAVA or JAVA_HOME, while every launcher runs a bare
`java` from PATH and none reads FORBRIC_JAVA, so a PATH that found another JDK reused the first JDK's GREEN; it now runs the
java the gate's PATH finds with the gate's JAVA_HOME. And gate-m60/m61/m62 stage their default mods from
`build/compat-inputs` and `build/purefabric-2026-10-03`, which no root hashed; both are hashed now, and a test holds every
`$BUILD/<dir>/*.jar` a gate reads to that list. Each new test was red on its control (old java lookup; no build/ roots;
compat-inputs off the list) and green on the tip.

A third hole showed only in item 3 and is fixed in 467c7f80: the key counted the jars in run/canary, which only the canary
builders write (from sources the key already has), so in a fresh worktree the first sweep built 55 of them and the second
sweep, with nothing changed, re-ran every gate. run/canary is out of the key; the two builders that compile sources from the
staged root have those sources hashed; gate-m14, the one gate that staged a canary it did not build, builds it (old script
with the jar removed: SKIP-FATAL exit 3; new: 34/0); a test holds every gate to building what it stages from run/canary.
Each of the three tests was red on its control.

Timing gates: **cached like any other, by rule** (test `test_a_timing_gate_is_answered_from_the_cache_like_any_other`, red
when `alone` gates are excluded from lookup). A timing gate's inputs fix the code that has to keep up; a second run of the same
inputs adds only the host's load, which this project already reads as no evidence about the kernel until the boot times say
otherwise; a RED is never kept, so a loaded run that went red runs again.

### (c) build once per sweep

Gradle JVMs counted with `ps` every 0.2 s during a two-gate sweep (m1, m3, `--no-cache`; `cache-controls/summary3*.txt`):
**3** before 8dd8b04e (2 jar, 1 printBootClasspath): the sweep's own two plus a bare `gradlew jar` in gate-m3 (which also
booted a stale jar when the build failed). **2** after. With the jar's mtime moved while gate-m1 ran: **5**, gate-m3's
kernel_jar and launcher falling back to building as they must. gate-m60's driver now skips its own build on the same stamp
(not counted separately; m60 5/0 in every sweep).

### (d) longest first and HARN's rules

`alone` gates still start only with nothing running and block every other start (`can_start`, unchanged by the sort);
GatesAllTest 7/7 (it reads every real gate's declaration and fails a timing gate without `alone`), test_gate_ports 11/11,
test_gate_cache's ordering test. The cold sweep's progress log (item 3) shows m12 and m16 running alone.

### (e) gate-m15

Measured on b2be55fe: each of m15's 22 checks is in gate-m16 with the same name, kind, pattern and log; one differs only in
the post-Done file it reads (its own gate's). **That was not the whole of m15** (review lens A, B1, fixed in 6922f9bc): its
client also ran `-Dforbric.clientSmokeModsScreen=80`, which m16's did not, so the screens it opened on a multiplayer
connection had no gate after the removal; see "OPT review fixes". m16 stages m15's one mod (FallingTree) plus the NeoForge canary on both ends.
Removed in a7684e94: `M15_PORT` (no gate reads it), gate-m16's header, both introductions' gates table; measurements that
name m15 now say it was removed. Historical records under reports/ are left as written.

## 2. No Chinese run (D88b)

run/README.md's tiers, PROTOCOL.md and both kernel READMEs no longer ask for a zh suite or a zh sweep, and say why.
`# GATE-TAGS:` / `--only-tag` had one user, the zh subset, so it is gone (nine tag lines, the scheduler's parsing, two tests);
`--only` stays. test_gate_locale (FORBRIC_JVM reaches every game JVM) stays. No script existed only for zh sweeps.

Chinese text in the normal suite (green on the tip; each new test red on the controls in its row -- two for four of them, one
for DialogLangTest, nine in all; `zh-controls/summary.txt`; the two rows added by the review fixes, with their controls, are
in "OPT review fixes"):

| component | reads | test (zh_CN and English, locale restored) | red controls |
|---|---|---|---|
| KernelLoadReport | Locale.getDefault | KernelLoadReportTest.theFileItselfAsksTheSystemWhichLanguage (existing) | (lane ZH) |
| MergeReport | Locale.getDefault | MergeReportTest (existing) | (lane ZH) |
| DialogLang | Locale.getDefault (ofSystem) | DialogLangTest.withNoSwitchTheSystemLanguageDecides (new); the table tests (existing) | ofSystem reads Locale.ROOT |
| CrashAttribution | Locale.getDefault | CrashAttributionTest.theWholePathWritesInTheSystemLanguage (new) | chinese() false; zh guess line in English |
| DisabledMods | Locale.getDefault | DisabledModsTest.aNewFileExplainsItselfInTheSystemLanguage (new) | Locale.ROOT; zh header in English |
| MetadataFailures | DialogLang.ofSystem | MinecraftForgeRefusalTest.theFindingFollowsTheSystemLanguageWhenNoLanguageIsNamed (new); the switch test and the load-report render, now also no English wording (existing) | ofSystem reads Locale.ROOT; zh detail in English |
| DependencyDialogMain | DialogLang.ofSystem, three windows, now one `language()` | DependencyDialogTest.theChildSpeaksTheSystemLanguage (new) | language() returns EN; zh title.deps in English |
| KernelCompatibilityScreen (src/runtime) | DialogLang.ofSystem | KernelCompatibilityPromptsTest.theScreenSpeaksTheSystemLanguage (review fixes, ead41483) | ofSystem reads Locale.ROOT; zh compat.title in English |
| InstallerText (installer) | Locale.getDefault | InstallerWindowInputTest, the system-locale block (review fixes, ead41483); its switch block was already there | chinese() ignores the locale; (zh title in English: red on the switch block first) |

None of the five new Java tests of f85b74e4 reads a fixture (synthetic jars, committed resources), so they owe no
skip-baseline row (D47). That was decided by reading the classes, not by running the ci-unstaged profile; the review
fixes' new game-side test was measured in that profile and has its row (7f8e2447). test_gate_cache.py and
test_control_arm.py run under toolTest with no game inputs, and since the review fixes in gate-m0 step 6.

## 4. Tests that cannot find anything (D89)

Method. Every shell check of every gate (`check`, `check_absent`, `check_exactly`, `check_kept_up`, `assert_eq`; 1187 text call sites at b2be55fe, 1165 after opt/verify removed m15 (630fa662), 1159 at 6ebc73e1, when this audit's
tables were written; the counts in its tables were taken at 1161, after the m29 zh boot went)
was extracted and indexed by kind + pattern (+ count) across all gates (`audit/checks.py`, `audit/xref.py`, `audit/per.py`); a gate is a
SUBSUMED candidate only if every one of its checks is made elsewhere on the same or a superset of mods. Every absence check of
a kernel message (pattern containing `Forbric`) was grepped for a producer in the kernel, installer, forbric-loader and canary
sources; the 14 with no literal hit were checked by hand (format strings with `%s`, staged canary sources) and all but m17's
two have a producer. Gates that boot the same setup were compared for a shared boot. Test classes over 2 s were read for
whether they still measure the genuine base. Nothing was deleted for being slow, flaky or red.

Done:

| what | verdict | covered by / evidence | time saved per cold sweep (serial) |
|---|---|---|---|
| gate-m15 | SUBSUMED | gate-m16, check by check (1e) | ~22 s, all of it wall: m15 was `alone` |
| gate-m29's zh boot | SUBSUMED | 12 of its 16 checks are boot 1's on the same jars and policy; the 4 Chinese ones are MinecraftForgeRefusalTest + KernelLoadReportTest on the boot's one render site (KernelLoadReport.java:163). Tip 22/0 in 26 s; M29_POLICY=continue 18/4; M29_REFUSED='' 11/11 (`m29/summary.txt`) | ~21 s |
| gate-m31's vanilla arm | DEAD re-run (a control whose inputs hold nothing of the tree), now kept per input key | first run 13/0 369 s keeps it; next 13/0 188 s; M31_UNFIXED=1 on the kept arm RED 12/1, biomes want 0 got 11 (`m31/summary.txt`) | ~181 s once the arm exists |
| gate-m17: "MinecraftForge and NeoForge mods all go in", "bundled (merged|game) base" | DEAD (0 producers) | git grep: one javadoc mention; the positive line beside them and InstallerWindowInputTest hold the wording | 0 (greps) |
| gate-m37, gate-m38 | KEEP, but declared parallel-safe | own uuid rundirs, scheduler port, own evidence dirs; m35-m38 at -j 4 all GREEN with w2c's counts (`m37m38/`) | ~67 s of wall that ran alone |
| gate-m14 | KEEP, fixed | staged a canary another gate had built; SKIP-FATAL in a tree where none had (467c7f80) | 0 (robustness under the cache) |
| a full unit suite before a sweep | SUBSUMED (process) | gate-m0 runs the same suite (plus the opt-in fixture and the transfer suite, red on any skip) | one suite, ~245 s, per lane/VERIFY round |

Every gate (prior serial seconds from gate-durations.tsv; "unique" = check patterns made by no other gate):

| Gate | prior s | cold s (8b8adbd1 sweep, 2026-11-16) | shell checks | unique | verdict | reason / evidence |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| gate-m34-soak | 7200 | - | 0 | 0 | out of scope (D84) | the soak, once at the final tip |
| gate-m0 | 926 | 405 | 4 | 4 | KEEP | builds; the unit suite and the transfer suite (red on any skip); the scan, the oracle and its control; the tool tests. 405 s now (four forks), was 926 s. The separate suite before a sweep was the duplicate, not this |
| gate-m58-world-upgrade | 861 | 1067 | 32 | 26 | KEEP; old-kernel arms a candidate for the arm cache (not done) | ~364 s of its ~810 s are old-kernel JVMs; their inputs are the archived kernel and stage plus this tree's canaries and scripts |
| gate-m31-vanilla-parity | 379 | 189 | 12 | 8 | KEEP; its vanilla arm a DEAD re-run, now kept per input key | the only vanilla A/B; 189 s with the kept arm (369 s without) |
| gate-m19-nesteddupe | 225 | 227 | 40 | 33 | KEEP | 33 of 40 shell checks made by no other gate; one library, nested by two ecosystems' mods, is initialised ONCE. |
| gate-m17-installer | 223 | 149 | 41 | 36 | KEEP; 2 absence checks DEAD and removed | the only installed-profile gate; the two absences had 0 producers |
| gate-m56-config-screens | 207 | 206 | 28 | 26 | KEEP | 26 of 28 shell checks made by no other gate; a Fabric mod's config screen opens from Forbric's own Mods screen with no Mod Menu installed, throug |
| gate-m57-forge-breath-drown | 198 | 195 | 4 | 3 | KEEP | 3 of 4 shell checks made by no other gate (its driver asserts the rest); breathing, drowning and liquids on NeoForge's patched game, judged against native NeoForge. |
| gate-m52-hopper-fabric-storage | 196 | 183 | 17 | 16 | KEEP | 16 of 17 shell checks made by no other gate; hoppers move items into and out of Fabric item storages NeoForge's hopper cannot see. |
| gate-m40-energy | 153 | 111 | 17 | 17 | KEEP | 17 of 17 shell checks made by no other gate; block energy between Team Reborn Energy (Fabric) and NeoForge cells, read by Fabric (Reborn) and Neo |
| gate-m9-client | 134 | 122 | 147 | 114 | KEEP | 114 of 147 shell checks made by no other gate; the CLIENT half. A Fabric + NeoForge instance must reach a rendered world and leave it cleanly. |
| gate-m23-elytra | 131 | 95 | 2 | 1 | KEEP | its own drill (elytra, 40 ticks) on the 97-jar clone; in m9's session it would change m9's world |
| gate-m36-mixin-outcome | 129 | 87 | 0 | 0 | KEEP | 0 of 0 shell checks made by no other gate (its driver asserts the rest); real Mixin silently skipped injection and safe late dedicated-server decisions. |
| gate-m20-depdialog | 128 | 102 | 48 | 36 | KEEP | 36 of 48 shell checks made by no other gate; an unmet hard dependency reaches the PLAYER, not just the log. |
| gate-m22-exitswap | 128 | 104 | 4 | 3 | KEEP | destroys the boot jar mid-session; cannot share a session |
| gate-m60-regress | 127 | 90 | 5 | 5 | KEEP | 5 of 5 shell checks made by no other gate (its driver asserts the rest); three Fabric-on-NeoForge adaptations measured in dedicated servers, each with its switch off as a re |
| gate-m45-everyday-actions | 122 | 95 | 9 | 8 | KEEP | 8 of 9 shell checks made by no other gate; the things every player does: craft, smelt, brew, swim, meet the Ender Dragon. Each of them threw on |
| gate-m33-transfer | 119 | 90 | 17 | 16 | KEEP | 16 of 17 shell checks made by no other gate; public world lookup routing between Fabric and NeoForge, both directed transfers, invalidation, and  |
| gate-m59-forge-swap | 113 | 108 | 26 | 26 | KEEP | 26 of 26 shell checks made by no other gate; a world written with a mod's MinecraftForge build loses, when the player swaps in the same mod's Neo |
| gate-m24-brokenmod | 109 | 95 | 44 | 16 | KEEP | 16 of 44 shell checks made by no other gate; expected-failure policy gate — continuation is explicit and failure evidence never turns green. |
| gate-m13-anticheat | 106 | 98 | 28 | 17 | KEEP | 17 of 28 shell checks made by no other gate; a Forbric client judged by a real anti-cheat on a real Paper server. |
| gate-m27-frame | 106 | 107 | 7 | 4 | MERGEABLE into m9 (not done) | boots the same 97-jar clone as m9; its 4 unique checks could ride m9's session; m9's ~20 recorded controls would need re-running |
| gate-m54-carry | 97 | 110 | 5 | 4 | KEEP | 4 of 5 shell checks made by no other gate (its driver asserts the rest); a NeoForge mod's play-phase packet to the server is heard: Carry On picks a chest up and puts it bac |
| gate-m10-overlay | 93 | 93 | 24 | 10 | KEEP | 10 of 24 shell checks made by no other gate; two CORRECT pack patches must not collide into a pack that cannot be read. |
| gate-m42-coremod-parity | 93 | 58 | 1 | 0 | KEEP | 0 of 1 shell checks made by no other gate (its driver asserts the rest); what NeoForge's own coremods do to the game, done by the kernel. |
| gate-m32-savedrop | 89 | 87 | 9 | 4 | KEEP | 4 of 9 shell checks made by no other gate; a world written by a set of mods still opens when one of them is GONE. |
| gate-m61-fluid-head | 89 | 54 | 3 | 3 | KEEP | 3 of 3 shell checks made by no other gate (its driver asserts the rest); fabric-block-api's and Create Fly's cancellable HEAD handlers on vanilla's LiquidBlock.shouldSpreadL |
| gate-m11-serverconfig | 84 | 83 | 14 | 9 | KEEP | 9 of 14 shell checks made by no other gate; a DEDICATED server brings a config-driven mod stack up to Done. |
| gate-m50-load-predicates | 84 | 65 | 2 | 1 | KEEP | 1 of 2 shell checks made by no other gate (its driver asserts the rest); lithostitched's load predicates are read again with its Fabric build installed. |
| gate-m55-creative-search | 79 | 100 | 10 | 5 | KEEP | 5 of 10 shell checks made by no other gate; the creative search: a mod refreshing it the vanilla way reaches the creative screen. |
| gate-m51-tooltips | 77 | 65 | 10 | 5 | KEEP | 5 of 10 shell checks made by no other gate; item tooltips show their component lines again, and NeoForge's appenders and Fabric's component tool |
| gate-m24d-registerstop-client | 75 | 53 | 16 | 2 | KEEP | 2 of 16 shell checks made by no other gate; on a CLIENT, a NeoForge registration failure stops the boot as native NeoForge 26.2.0.93 does (owner |
| gate-m30-attribution | 72 | 69 | 25 | 16 | KEEP | 16 of 25 shell checks made by no other gate; a mod that loses PART of itself is named, with the reason, on every surface a player has. |
| gate-m35-behavior | 70 | 62 | 0 | 0 | KEEP | 0 of 0 shell checks made by no other gate (its driver asserts the rest); actual game callers in a fresh world (NeoForge's portal, spawner-finalize and item-finish events thr |
| gate-m18-presence | 69 | 65 | 20 | 16 | KEEP | 16 of 20 shell checks made by no other gate; the ecosystems can SEE each other. |
| gate-m24b-badmetadata | 68 | 68 | 21 | 10 | KEEP | 10 of 21 shell checks made by no other gate; unreadable-metadata gate — one jar's broken manifest costs that jar, and is named. |
| gate-m26-forgeclient | 64 | 56 | 36 | 23 | KEEP | 23 of 36 shell checks made by no other gate; the client event buses must receive the game's real registration events, and a refused MinecraftForg |
| gate-m8-packmeta | 64 | 64 | 20 | 9 | KEEP | 9 of 20 shell checks made by no other gate; a multiloader mod's pack.mcmeta must not delete its own resource pack on an instance that loads Fabr |
| gate-m14-fabric-server | 63 | 55 | 32 | 16 | KEEP (now builds its own canary) | the only pure-Fabric-server gate; went SKIP-FATAL in a tree where no other gate had built the canary (467c7f80) |
| gate-m25-worldgen | 62 | 62 | 15 | 13 | KEEP | 13 of 15 shell checks made by no other gate; NeoForge's biome and structure modifiers leave the NeoForge canary's blocks in saved overworld chunk |
| gate-m62-carpet | 58 | 62 | 4 | 4 | KEEP | 4 of 4 shell checks made by no other gate (its driver asserts the rest); Carpet 26.2+v260616's own behaviour on NeoForge's base -- run/compat/carpet-gate.py (canary/carpet)  |
| gate-m12-multiplayer | 57 | 52 | 41 | 20 | KEEP; MERGEABLE with m16 (not done) | see m16 |
| gate-m2b | 50 | 45 | 39 | 23 | KEEP | 23 of 39 shell checks made by no other gate; the FULL Fabric ecosystem runs on the sovereign kernel: fabric-api (43 modules, 58 mixin configs, |
| gate-m4 | 49 | 31 | 31 | 18 | KEEP | 18 of 31 shell checks made by no other gate; Fabric and NeoForge REAL third-party mods (not synthetic canaries) live SIMULTANEOUSLY in ONE dedica |
| gate-m39-transfer-core | 48 | 42 | 4 | 2 | KEEP | 2 of 4 shell checks made by no other gate (its driver asserts the rest); the transfer engine suite (required), then the actual final watchdog replacement proof on a real ker |
| gate-m29-forgecaps | 47 | 29 | 21 | 13 | KEEP; its zh boot SUBSUMED and removed | the only server-side refusal boot; 22 checks now |
| gate-m46-stub-rebind | 47 | 33 | 6 | 5 | KEEP | 5 of 6 shell checks made by no other gate; a Fabric mod's injector that Mixin binds to a carrier-added delegating stub reaches the body again. |
| gate-m24c-disabled | 44 | 34 | 21 | 9 | KEEP | 9 of 21 shell checks made by no other gate; switched-off gate — a jar listed in forbric-disabled.txt stays in mods/ and is loaded by nobody. |
| gate-m43-break-and-loot | 44 | 40 | 1 | 0 | KEEP | 0 of 1 shell checks made by no other gate (its driver asserts the rest); Fabric's PlayerBlockBreakEvents.AFTER, experience from mining, and loot pool conditions, on NeoForge |
| gate-m21-forgesetup | 41 | 40 | 29 | 23 | KEEP | 23 of 29 shell checks made by no other gate; modFilePerJar). The MinecraftForge recipes that stood here (forgeConditions, forgeConditionFunnel, |
| gate-m37-entity-callbacks | 41 | 66 | 0 | 0 | KEEP (undeclared: ran alone; now mem=2000) | own uuid rundirs, scheduler port |
| gate-m44-interaction-events | 41 | 42 | 5 | 4 | KEEP | 4 of 5 shell checks made by no other gate (its driver asserts the rest); using an item on a block and picking a block, as Fabric's and NeoForge's events see them on NeoForge |
| gate-m4-canary | 37 | 33 | 22 | 7 | KEEP | 7 of 22 shell checks made by no other gate; (synthetic) — Fabric + NeoForge mods run SIMULTANEOUSLY in ONE instance on NeoForge's patched game, |
| gate-m7-neo | 36 | 22 | 28 | 14 | KEEP | 14 of 28 shell checks made by no other gate; REAL, PURE NeoForge mods (jars that carry ONLY neoforge.mods.toml) run on the kernel. |
| gate-m16-forge-handshake | 35 | 32 | 37 | 15 | KEEP; MERGEABLE with m12 (not done) | both are alone client+server socket gates; a merge saves one alone boot (~32 s wall) but changes m12's timing inputs |
| gate-m53-widened-new | 33 | 50 | 4 | 3 | KEEP | 3 of 4 shell checks made by no other gate (its driver asserts the rest); fabric-particles puts the ground block on a mob's landing dust and on sprint dust again. |
| gate-m2 | 31 | 26 | 28 | 18 | MERGEABLE into m4-canary (not done) | m4-canary stages the same Fabric canary; 18 of 28 checks unique, all printed by that canary |
| gate-m3 | 29 | 27 | 13 | 4 | MERGEABLE into m4-canary (not done) | m4-canary stages the same NeoForge canary; 4 of 13 unique |
| gate-m38-enchantment-contracts | 26 | 49 | 0 | 0 | KEEP (undeclared: ran alone; now mem=2000) | own uuid rundirs, scheduler port |
| gate-m1 | 23 | 24 | 18 | 4 | MERGEABLE into m3 (not done) | same rundir and boot shape as m3; 4 of 18 checks unique; zero-mod Done/save is also m31's Forbric arm |
| gate-m28-forgeconfig | 16 | 22 | 12 | 9 | KEEP | 9 of 12 shell checks made by no other gate; NeoForge COMMON configs load once and the carrier's native file watcher reads a live edit. |

MERGEABLE candidates not merged here, with what a merge would need: m27 into m9 (~106 s serial; m9's ~20 recorded controls
re-run, plus m27's two), m1 into m3 and m2/m3 into m4-canary (~83 s serial; m1/m2 have no recorded control, so every moved
check needs a new one), m12 with m16 (~35 s of alone wall; changes m12's timing inputs, both gates' controls re-run). Biggest
remaining candidate of the same kind as m31's arm: gate-m58's old-kernel JVMs (~364 s of its ~810 s), whose inputs are the
archived tag's kernel and stage plus this tree's canaries and scripts; caching them means keying on all of those and carrying
the arming tokens, not done.

Unit-test classes over 2 s (single-fork times; forked in brackets): all KEEP. Each is a census or a contract that reads the
genuine base, the carrier or the packs as they are now, or drives a harness script with real timers; none re-proves a
property of the merged base (the `MergedBase*` names predate Stage 4 and run against the staged genuine base).

| class | s (1 fork) | s (4 forks) | tests | what it measures (its own javadoc) | verdict |
| --- | ---: | ---: | ---: | --- | --- |
| mixin.MixinFitLivenessCensusStagedTest | 69.2 | 95.3 | 5 | every guest mixin of the five compatibility packs judged with MixinFit liveness on and off against the staged base; pins the verdict changes | KEEP |
| boot.LibStopWhenReadyContractTest | 26.7 | 27.6 | 12 | Drives run/lib.sh's server_ready and stop_when_ready, the stdin a gate pipes into its server. | KEEP |
| mixin.UncalledMethodCensusTest | 20.2 | 26.8 | 1 | Re-derives uncalled-methods.txt — every method of the base, as Mixin sees it after the vanilla twins (TestFixtures#twinnedBase()), that an ecosystem's | KEEP |
| mixin.MixinRetargetRenamedBodyCorpusTest | 15.6 | 18.5 | 1 | MixinRetarget R3 planned over every mixin of the local packs and sweeps against the staged base; pins which injectors move | KEEP |
| compat.GatesAllTest | 9.4 | 9.1 | 7 | gates-all/gates-parallel against stub gates, and every real gate's declarations (alone for timing gates) | KEEP |
| compat.GateM0CaptureStepTest | 9.3 | 9.3 | 7 | gate-m0's step 1b, the capture SharedFinalSourceActualTest replays, run as the gate runs it but against a stubbed server: | KEEP |
| transform.RuntimeSeamLivenessStagedTest | 7.2 | 6.7 | 5 | Every game-side kernel class KernelRuntimeClasses lists has a live caller on NeoForge's own base and carrier (plan C5, lane S item 2): | KEEP |
| interop.ExitGuardParkedOrphansTest | 6.7 | 6.7 | 6 | The exit guard ends a JVM that only parked non-daemon threads hold open, whatever those threads are. | KEEP |
| mixin.MixinRetargetReplacedCallTest | 6.2 | 9.2 | 9 | MixinRetarget R7 on the staged base's StructureTemplate (the javadoc's "merged" predates Stage 4; 0 skips, it reads the genuine base) | KEEP |
| ui.DependencyDialogTest | 5.3 | 8.0 | 47 | the unmet-dependency/confirmation/isolation windows: text, layout, the forked child's fail-open exit codes | KEEP |
| boot.LibStopTreeTest | 4.7 | 4.6 | 4 | Drives run/lib.sh's stop_tree on the process shape gate-m17 starts its client in: | KEEP |
| mixin.weave.MixinOperationSeamTransportWeaveTest | 4.4 | 7.7 | 2 | a real Mixin/MixinExtras weave of the operation-seam transport, its mechanism off as the control (child JVMs) | KEEP |
| mixin.weave.MixinAbsorbedCallbackTransportWeaveTest | 4.0 | 6.4 | 2 | a real weave of the absorbed-callback transport, mechanism off as the control (child JVMs) | KEEP |
| compat.WindowsDriversTest | 4.0 | 5.1 | 18 | the Windows push-and-run drivers (still the owner's Windows test path) | KEEP |
| mixin.PostMixinFixupsAliasTargetTest | 3.6 | 3.9 | 6 | fabric-registry-sync's "alias target doesn't exist" warning still reads the registry's own map and stays quiet for NeoForge's alias-first order; its c | KEEP |
| transform.ChainAnchorCensusStagedTest | 3.6 | 3.4 | 6 | Every transformer KernelBoot.launch registers, replayed over NeoForge's whole base and carrier on each side, lands each of its REQUIRED anchors (decis | KEEP |
| transform.DeclarationReaderCensusTest | 3.3 | 3.2 | 4 | Where DeclarationReaderModListInjector fires on real bytecode: | KEEP |
| boot.LibPortContractTest | 3.2 | 3.2 | 7 | Drives run/lib.sh's port_was_free, alone and through both ways await_server returns. | KEEP |
| mixin.CarrierHelperCensusTest | 2.9 | 9.9 | 1 | Re-derives carrier-helpers.txt — every call a method made in an ecosystem's own jar that the base (as Mixin sees it after the vanilla twins, TestFixtu | KEEP |
| boot.GateNeoCanaryContractTest | 2.8 | 4.0 | 8 | Runs the NeoForge canary's blocks of gate-m21 and gate-m26 against synthetic logs, without launching a game. | KEEP |
| mixin.NativeOnlyMethodsCensusTest | 2.7 | 2.9 | 7 | Re-derives native-only-methods.txt — for each platform a mod can be native to (vanilla 26.2 for Fabric, NeoForge's patched 26.2 for NeoForge), every m | KEEP |
| transform.RepairDriftCensusStagedTest | 2.4 | 2.4 | 4 | Replays the repair ledger against a candidate game build, and proves the replay can come back red. | KEEP |
| transform.MergedBaseCalleeSwapTest | 2.3 | 2.8 | 6 | The descriptor-identical callee swaps between stock 26.2 and the staged base, pinned; the safety test that says none of them may be rewritten at the c | KEEP |
| mixin.weave.PostMixinCodecReturnArbitrationWeaveTest | 2.2 | 2.2 | 3 | real weave of the codec-return arbitration, control off | KEEP |
| mixin.weave.MixinCrossHostPredicateIslandWeaveTest | 2.1 | 2.2 | 1 | real weave of the cross-host predicate island, control off | KEEP |

Shared large inputs: MixinFitLivenessCensusStagedTest (69 s, 95 s forked), UncalledMethodCensusTest, MixinRetargetRenamed-
BodyCorpusTest and CarrierHelperCensusTest each build the twinned base or read the packs through a per-JVM memo, so with four
forks some of it is built more than once (summed class time 405 s forked vs 310 s). An on-disk cache keyed by the base's sha
would remove that; not done (the wall time is set by the longest fork, MixinFitLiveness alone).

## 3. Measured result

All at the code tip 312d152d unless said otherwise (`final/`).

| measurement | before | after |
|---|---|---|
| full unit suite, standalone (`-Pforbric.requireFixtures=all jar cleanTest test --continue`) | 486 s in one JVM (630fa662) | **247 s** (5892 tests, 0 skipped, the 5 D58 failures; `final/suite-time.txt`) |
| gate-m0 inside a sweep (the suite + transfer suite + capture boot + oracle) | 926 s (prior) | 388 s cold (405 s in the 8b8adbd1 sweep), 284 s alone in the warm sweep |
| cold gates-all -j 4 (`--skip gate-m34-soak.sh`, GATE_PORT 46800, heavy --exclusive, client lock) | **46.4 min** (merger w2c) | **32.9 min** (1977 s; 3.0x over the 98.4 min of gate time) |
| immediate second gates-all, nothing changed | (a full re-run) | **4.8 min** (287 s): 59 of 60 gates from the cache, "inputs changed since the last run -- none" |

Cold sweep verdicts: 59 GREEN, gate-m0 RED on D58 alone ("3 of 5892 skipped", D87a), gate-m34 SKIP. Per-gate `[kernel]`
PASS/FAIL/SKIP counts against `merger/w2c/gates-all-counts.txt` (`final/compare-cold2.txt`): equal for every gate except the
three this lane changed on purpose: gate-m15 gone (22 checks, all made by m16), gate-m17 49 -> 47 (the two DEAD absences),
gate-m29 38 -> 22 (the zh boot). No gate started beside m12 or m16 (checked over the progress log). Sum of all java RSS peaked
at 5.2 GB; the host's swap was already 4.0 GB before the first run and reached 6.7 GB.

The second sweep: the one gate that ran is gate-m0, because it is RED under D58 and the cache never keeps a RED. Until the D58
fixture exists every sweep re-runs m0's suite (284 s here, alone); with it, a repeat sweep is the fingerprint (~2 s) plus
reading 60 cached logs.

The first measurement pair, at 8b8adbd1, is why 467c7f80 exists: cold 33.2 min with the same counts, and then the second
sweep re-ran all 60 gates (32.4 min), because the cold sweep had built 55 canary jars in run/canary that the key counted
as inputs (`final/gates-warm/progress.log`: "inputs changed ... jars: run/canary/forbricabicanary.jar ... and 50 more").

Where the 13.5 minutes went (cold, serial seconds from the prior to this run): gate-m0 926 -> 405 (four forks); gate-m31
379 -> 189 (kept vanilla arm); gate-m15 removed (~22 s, all of it alone); gate-m29 47 -> 29; gate-m37 and gate-m38 no longer
alone (~67 s of single-gate wall); every launch skips its two Gradle runs (build once); longest first starts m58 and m0
at 0 s instead of near the end. gate-m58 itself took 1067 s in the first cold sweep, more than its 861 s prior (host load),
and it now sets the floor of the sweep with m0 beside it.

Totals per verification round (one lane sweep + merger + VERIFY on the same tip, the D87 shape), from the numbers measured
here (corrected in the review fixes): before, 3 suites (8.1 min each in one JVM) + 3 sweeps (46.4 min) + 1 zh suite + 1 zh
sweep ~ 3.6 h; now, the one standalone suite a lane still runs (4.1 min), one cold sweep (32.9 min) and two repeat sweeps
of the same tip (4.8 min each while D58 keeps m0 red) ~ 47 min, no zh run. Since the review fixes a commit that touches a
gate script or prose outside reports/ re-runs gate-m0 and gate-m39 in the repeat sweeps too (~7 min); a record commit
under reports/ does not.

## Open items

1. gate-m0 stays RED under D58 and is re-run by every sweep (~5-7 min). Either the LiquidBounce fixture appears or the
   orchestrator decides how D87a's exemption should reach the cache; no change made here.
2. MERGEABLE candidates not merged (table above): m27 into m9 (~106 s serial), m1/m2/m3 into m3/m4-canary (~83 s serial),
   m12 with m16 (~32 s of alone wall). Each needs the absorbed and the absorbing gate's recorded controls re-run.
3. gate-m58's old-kernel JVMs (~364 s of its ~810 s) are fixed-input work like m31's vanilla arm; a control-arm cache for
   them must key the archived kernel and stage, this tree's canaries and scripts, and the arming tokens. Not done.
4. Memory budget: at the start of a sweep only m0 (3500) + m58 (4000) + m19 (1500) fit the 9011 MB budget, so the fourth
   slot waits. Measured sum of java RSS stayed at or under 5.4 GB in all four sweeps, but macOS RSS leaves out compressed
   pages and swap grew 4.0 -> 6.7 GB, so no declaration was lowered.
5. Forked suite: classes that build the twinned base or read the packs through a per-JVM memo build it once per fork
   (summed class time 393-405 s vs 310 s). An on-disk cache keyed by the base's sha would cut that CPU; the wall time is set
   by MixinFitLivenessCensusStagedTest (95 s forked).
6. (closed in the review fixes) KernelCompatibilityScreen and InstallerText have their system-locale tests.
7. build/sweep100-mac still dangles in lane worktrees (source absent in the main checkout); nothing here reads it.
8. regress-gate.py's skip of its own Gradle run on the prebuilt stamp was not counted separately; gate-m60 was GREEN (5/0)
   in all four sweeps.
9. The control-arm cache and the verdict cache share git's common directory: m31's kept arm (2026-11-16T01:23:30) is now
   reused by every worktree with the same inputs, which is the intent; `--no-cache`/`--release` boot it afresh.

## OPT review fixes (2026-11-16)

Reviews: `c5/review-opt-w2d-A.md` (lens A, adversarial) and `c5/review-opt-w2d-B.md` (lens B, completeness). Every finding
was checked against the code before it was acted on; none was refuted. Evidence: `c5/opt/w2d/review-fix/`.

| finding | verdict | fix | shown |
|---|---|---|---|
| A-B1: m15 was not a subset of m16 -- its client ran `-Dforbric.clientSmokeModsScreen=80`; no gate opened those screens on a multiplayer connection after it went | confirmed (m15's script at b2be55fe line 107; m16's client line had no switch) | 6922f9bc: m16's client takes the switch; 10 new checks: multiplayer session (window title), both mods buttons open the unified list and did not fail, the Mods screen opened, rendered frames and did not throw, the pack screen listed its rows and opened | tip 49/0 GREEN, check_kept_up held (`m16-tip.log`); the review's mutation (KernelModListScreen.extractRenderState throws without a single-player server, `m16-mutation.diff`) 44/5 RED incl. "the Mods screen drew no frames" and "no client crash (present x4)" (`m16-mut.log`); the review measured m16 without the switch 39/0 GREEN on the same mutation |
| A-B2: gate-m0's key missed the gate scripts and the prose its tests read | confirmed | 60a2cd9f: a `# GATE-CACHE: tests` gate keys the test sources, every gate script and the prose outside run/compat/reports/ (CACHE_VERSION 5); records stay out, and a test holds that no test such a gate runs reads one (test_fabric_ab does read one; m0 does not run it) | real tree's files (`m0key-real.txt`): renaming a gate-m13 check changes m0's and m13's keys, not m1's; editing PROTOCOL.md changes m0's only; a record changes none. Controls (`py-controls.txt`): key on test sources only, records keyed as prose (after 1cc05609 -- the first version of the test was blind: its tree ignored run/compat/), m0 naming test_fabric_ab -- each RED |
| A-M1: BASE, NEO_RT, NATIVE_REF, VANILLA_JAR, VANILLA_JVM not in the key | confirmed; the scan also found MERGED, FORGE_RT, STOP_READY_CAP, STOP_READY_STALL, OLD_BOOT_LIMIT | 60a2cd9f: `_ENV_INPUTS` keyed, the jar each of BASE/NEO_RT/NATIVE_REF/VANILLA_JAR names keyed by content; `_ENV_NOT_INPUT` = GATE_PORT, RUNDIR, HOME with the reason; a structural test holds every `${X:-…}` a run/ script takes from its environment to one of them | controls RED: inputs not kept, jar content not keyed, BASE off the list (the structural test) |
| A-m1 / B-B2: test_gate_cache and test_control_arm in no routine run; `python3` by name | confirmed | 60a2cd9f: gate-m0 step 6 runs both; the stub sweeps drop inherited FORBRIC_GATE_* (inside a --release sweep FORBRIC_GATE_FRESH=1 would otherwise reach them: control RED) and start sys.executable | the six modules of step 6, 101 tests, OK (also with FORBRIC_GATE_FRESH=1 set). The CI OS matrix (ubuntu/windows) was **not** run: nothing is pushed from a lane; open item |
| A-m2 / B-B1: no zh test for KernelCompatibilityScreen and InstallerText | confirmed | ead41483: KernelCompatibilityPromptsTest.theScreenSpeaksTheSystemLanguage (literal zh and en title, message, buttons; no switch); InstallerWindowInputTest's system-locale block; 7f8e2447: the screen test's ci-unstaged row, measured in that shape (all 15 of its class skip on [fixture:game-side]; `unstaged-rows.txt`) | `java-controls.txt`: lookup reads Locale.ROOT RED, zh title in English RED (both at the new assertion); `installer-controls.txt`: chinese() ignoring the locale RED at the new block |
| B-B3: m29's replacement assertion (MinecraftForgeRefusalTest:145) never red | confirmed | control run, no code change | zh `report.unsupported.what` with the English beside the Chinese: RED at MinecraftForgeRefusalTest.java:145, the positive assertion before it still green (`jctl-report-english-beside-chinese.log`) |
| B-B4: "every new test red on two controls" | confirmed (DialogLangTest had one) | wording in §2 here; the commit message f85b74e4 stays as written | -- |
| B-B5: numbers | confirmed, all four | §3/§4 relabelled and restated (check-site counts by tree; cold column = 8b8adbd1 sweep, m0 388 s at 312d152d; the round estimate from 486 s/247 s; memory "two of three"); gate-m0.sh's mem comment likewise | -- |
| B-B6: stale cache docs | confirmed | 60a2cd9f: gate_cache.py docstring, gates-all --help, PROTOCOL.md (and its "Three things" with four bullets, the misplaced test_gate_cache sentence), both kernel READMEs | -- |
| B-B7: evidence and hand-off gaps | confirmed | review B's re-run of the eleven w2d Python controls copied to `review-fix/w2d-python-controls-rerun-by-review-B.txt`; every open item has an owner in handoff.md; D47 wording in §2. The scratch copies ctl/, ctl2/, armctl/, m15/ were left in place (no folder deleted) | -- |

Runs at the end (code tip 7f8e2447 for the suite; gate-m0 at 2a63ed0f, which adds only its own comment; this record after):
- full suite `-Pforbric.requireFixtures=all jar cleanTest test --continue`: 5893 tests, 0 skipped, 5 failed, the five
  D58 rows of failures-w2c.txt, 0 new (5892 + theScreenSpeaksTheSystemLanguage), 243 s (`suite-summary.txt`).
- gate-m16 at 6922f9bc: 49/0 GREEN (above).
- gate-m0 at 2a63ed0f: 13 PASS, 1 FAIL, 1 SKIP, the same as w2c: the FAIL is D58 alone ("3 of 5893 skipped", D87a), the SKIP
  the C6 link check; step 6 now "evidence, soak, locale and cache tool tests (101 ran, none skipped)"; 308 s (`m0.log`,
  `m0-tooling.log`). No other gate's script or inputs changed (docs and the cache are read by gates-all, not by a gate).

Open after the review fixes (each with an owner in handoff.md):
1. The CI OS matrix (ubuntu, windows) for test_gate_cache/test_control_arm: measured on macOS only; test_control_arm runs
   lib.sh under PATH=/usr/bin:/bin and `cp -c`. Whoever pushes the ci/ branch at C5 close (orchestrator, D47).
2. Items 1-5 and 7-9 of "Open items" above stand; item 6 is closed.
3. The w2d scratch copies ctl/, ctl2/, armctl/, m15/ in the session scratchpad were not deleted (no folder is deleted in
   this run); they hold no evidence the record relies on.
