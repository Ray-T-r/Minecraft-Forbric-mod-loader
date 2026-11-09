# Stage 4 C5 — lane G-C (client, network, installer gates)

Branch `s4-c5-g-c`, cut from the Phase-0 tip cd6d0fe8. Notes and logs: `~/Forbric-backups/stage4-notes/c5/g-c/`
(the run outputs named below are under `c5/g-c/review/`).

**Wave 2 (2026-11-09/10) supersedes the state below: every gate ran on the genuine stage with the pinned build; see "Wave 2" at the end.**

## State in wave 1: the gates are edited, none has run on the genuine stage

Gradle cannot build offline on this machine. The dependency cache `~/.gradle/caches/modules-2` was recreated
on 2026-11-08 at 20:47 and holds none of the kernel's dependencies. Every lane hit this. The D66 first act was
run twice and failed at `:compileJava` both times ("No cached version of ... available for offline mode"), with
0 tests:

- on the lane tree with the uncommitted work (`c5/g-c/suite0/suite.log`);
- on an APFS clone reverted to exactly cd6d0fe8, after review B-6 (`review/suite-clean/suite.log`).

Every gate builds the kernel through `gradlew --offline` (`kernel_jar`), so no gate can run either. **There are
no PASS/FAIL counts for any of the 13 gates, and no red control has been shown.**

What could be checked without Gradle:

- **javac outside Gradle** (`review/jc.sh`). It compiles all of `src/main/java` at release 21 against the same
  libraries taken from local copies: asm 9.10.1, sponge-mixin, sat4j, tiny-remapper, class-tweaker, mapping-io,
  night-config, log4j-api 2.26.0 and annotations 26.1.0. It then compiles the 19 test classes that read G-C's
  gate files, plus the two JUnit extensions, and runs them with JUnit 5.11.4. Forbric pins 5.9.2, which is not
  on disk.
  - **At the lane tip:** main compiles with 0 errors, and **108/108 tests pass** (`review/contract-fixed.log`).
  - **Same harness on the cd6d0fe8 clone:** 104/108 (`review/contract-clean.log`). The 4 failures read
    `build/classes/java/main`, which the clone's failed Gradle compile had removed. That is a harness artifact,
    not a change in the gates.
  - **What it does not cover:** `compileRuntimeJava` and the other source sets were not compiled. No source in
    them changed.
- **`bash -n`** passes on all 13 gates and `assert.sh`.
- **Python tests:** `test_gate_ports` and `test_lib_guards` both pass, 24 tests (`review/py-gate-ports-guards.log`).

## Commits

| sha | what |
|---|---|
| a1a923a8 | `assert.sh` drops the traditional-Forge construct line, the three EventMux bridge lines, "bridge(s) MISSING" (D34), and the alias line (pack-dependent since Stage 4, review A-7). `AssertCompatTest` now expects 36 (was 42). |
| e84c6b21 | m1 builds its jar with `kernel_jar` (A-4); "game base" prose. |
| 1c781c3a | m12 and m14: the MinecraftForge-wrapper sync checks are replaced by the D48 capture check. The m14 control is now marked TO MEASURE (A-5, B-1). Rundir overrides and guards added. |
| 820ac01b | m17 per D44: three artifacts, one runtime, no MinecraftForge runtime, "game base", two cached artifacts, NeoForge runtime stamp. Also: control N; offline installer build (A-3); D64 SKIP texts corrected (A-1, A-2); "measured" dropped (B-2); KernelBoot comment. |
| 4bee48b0 | m54: the control is re-pointed at fabric-api's "Unknown addon" disconnect and marked TO MEASURE (A-5). Adds `M54_RUNDIR` and the guard. |
| 3f3ba024 | m9: MinecraftForge-only checks deleted. Adds one carrier (`2-neoforge-runtime[^,]*`, A-8/B-4), the alias pin and its absence, the JEI bindings check, the atlas mip check, DupScan (D65, comment A-9), the multipart phase (D65), the guard after the missing-fixture checks (B-8), and recipe entries marked as merged-base or TO MEASURE (A-6, B-3, B-5a). |
| e352818c | m20, m22, m23, m27, m55, m56: guards, with m22/m23/m27 placed after the missing-world check (B-8). m13 prose. |

No test is deleted, renamed or added, so no TSV rows are owed (D47).

## Measured checks (outside the game)

- **`assert.sh` on `AssertCompatTest`'s GREEN fixture:**
  - Current script: rc 0, 36 PASS (`review/assert-green-after.out`).
  - cd6d0fe8's script on the same fixture: 37 PASS and 5 FAIL. The 5 are exactly the deleted lines
    (`review/assert-green-base-script.out`).
- **Mutation:** re-adding the alias line to `assert.sh` turns `AssertCompatTest` red, with 2 of 5 tests failing
  (`review/mutation-assert-alias-back.log`).
- **m1 against the broken cache:** exit 3, "kernel jar build failed — refusing to run against a stale jar"
  (`review/m1-broken-cache.out`). The stale 2026-11-02 jar was still in `build/libs` and was not booted.
- **m9 rundir guard:**
  - Default rundir with the fixture link present: exit 3, "FAIL refusing rundir … it is the shared fixture
    run/client-merged-pack" (`review/m9-guard-default.out`).
  - Default rundir with the fixture absent: exit 3, the gate's own "SKIP-FATAL: no world at …"
    (`review/guard-unstaged-m9-client.out`). m27's absent case was checked the same way.

## Review findings (G-C review fixes, 2026-11-09)

| finding | outcome |
|---|---|
| A-1 m17 merge-tool SKIP reason false; hazard live | **Confirmed, reported against D64, not re-pointed** (820ac01b corrects the text). `forbric-kernel-installer/build.gradle:34-36,80-81` still bundles the tools jar. `LinkCheckTool.unpackTools` (`:139-152`) overwrites it unconditionally. `ArtifactBuilder.java:106` runs the link check on every build path. The lane's installer jar lists `forbric/tools/forbric-merge-tools.jar` at 589971 B. Proposed re-point: tamper with `.forbric-build/tools/forbric-merge-tools.jar`, reinstall, compare SHA-1 with the bundled one. Orchestrator ruling needed. |
| A-2 m17 offline phase partly checkable; "measured" claim | **Confirmed.** Only the removed-library sub-step lost its premise. A cached reinstall returns before NFRT (`NfrtRunner.run` checks `BuildStamp.isFresh` first). The SKIP text says so and the phase is reported against D64. "measured" was dropped from the header (820ac01b). |
| A-3 m17 builds the installer online | **Fixed** in 820ac01b. Loader `mergeToolsJar`, then the installer with `--offline --no-daemon -x buildForbricJars -x buildMergeTools`. `kernel_jar` already built the kernel. Not run, because m17 stops at `kernel_jar` today. |
| A-4 m1 swallows a failed build | **Fixed** in e84c6b21 and measured (above). |
| A-5 control outcomes stated as measured (m14, m54) | **Fixed:** both are now TO MEASURE (1c781c3a, 4bee48b0). |
| A-6 m9 recipe pointers to absent entries | **Fixed** in 3f3ba024. The four entries were added with expected reds, `lootModifierIndex`'s reason was restored, and every entry is marked merged-base or TO MEASURE. |
| A-7 `assert.sh` alias line is pack-dependent | **Fixed** in a1a923a8: the line is deleted and the count is 36. The genuine `MappedRegistry` (javap) has no `warn` call, so `askTheRegistryWhetherAnAliasTargetExists` returns early without fabric-registry-sync's mixin. |
| A-8 carrier id depends on D43 | **Fixed** in 3f3ba024 (`[^,]*`). `KernelDataPacks.carrierPackId` is still rank + file name. |
| A-9 DupScan comment overstates coverage | **Fixed** in 3f3ba024. `KernelMixinBootstrap` runs six passes after `transformClassBytes`. |
| A-10 m14 lost the client-only-entry survival check | **Not done (optional).** It needs a new smoke log line plus a run to pin it, and nothing can run. Open item. |
| B-1 report misdescribed the m14 header | **Confirmed.** The header said "exactly one red (note_block)", not 32/5. Both are corrected: the header now says TO MEASURE and cites PLAN's 32/5 as the old-gate planning run. |
| B-2 m17 "measured" claim | Same as A-2. **Fixed.** |
| B-3 m9 pointers | Same as A-6. **Fixed.** |
| B-4 carrier-order dependency | Same as A-8. **Fixed.** No hand-off to UPG is needed now; the loose match works before and after D43. |
| B-5 PLAN items not listed | **Listed** under Open below. |
| B-6 first act on a dirty tree | **Re-run** on a clean cd6d0fe8 clone: same wall, 0 tests (`review/suite-clean/suite.log`). |
| B-7 no checks.md entry or log | **Fixed:** `c5/checks.md` has a G-C heading, and the logs are under `c5/g-c/review/`. |
| B-8 m22/m23 not "wording only"; default rundir now refused | **Confirmed and changed.** The guard now runs after the missing-fixture check (m9, m22, m23, m27): unstaged gives the gate's own SKIP-FATAL/FATAL, staged default gives "FAIL refusing rundir", both exit 3. |

## Open (owner G-C unless stated)

- **Orchestrator:** restore Forbric's dependencies to the Gradle cache. Every item below waits on it.
- First act (suite = 41 names of `failures-close.txt`), then `failures-g-c.txt`.
- All 13 gates on `.stage4`, plus their red controls:
  - m14 control A (D48);
  - m54 control;
  - m9 alias / carrierLanguages / keyModifierSuffix and the 17-recipe batch;
  - m17 control N;
  - m20 `dependencyDialog=off`;
  - m22 `bootJarPreload=off`;
  - m55 withheld;
  - m56 phase controls.
- m12 ×3 alone (overload flake); m13 first run after 0b.
- The m17 M17_SEED_CACHE claim, measured with the network denied.
- **Orchestrator ruling on A-1/A-2 (D64):** re-point m17's two phases in C5, or keep them as SKIP.
- A-10 (optional): canary-block raw-id survival line in m14.
- client-neo-pack sophisticated*/earthmobsmod (c1/handoff.md:1063): a fixture edit that needs the
  orchestrator's OK. Not asked yet.

## Wave 2 (2026-11-09/10): first runs, red controls, the m9 batch

Branch `s4-c5-g-c`, rebased onto the stage tip f0cb66b0 (no-op: the lane tip was the tip). Pinned build, plain
`--offline` from `props.txt` (D68); no init script, no private Gradle home. Logs: `~/Forbric-backups/stage4-notes/c5/g-c/w2/`
(gate outputs under `gates/`, the m9 batch summary in `m9-batch-summary.txt`). Gate counts are `^\[kernel\] PASS` /
`^\[kernel\] FAIL` lines only. Every client gate ran under the client lock, one at a time; the client-merged-pack gates
ran on fresh APFS clones by real path, deleted afterwards.

### Suite

- **First act** at f0cb66b0: 5772 tests, 0 skipped, 5 failed: exactly the five rows of `failures-wave1.txt` (LiquidBounce x3,
  SharedFinalSourceActualTest x2). XML: `w2/suite-first-xml/`.
- **End** at 2e378a77 (same command): 5772 tests, 0 skipped, 5 failed, the same five names; 0 new, 0 gone (XML:
  `w2/suite-final-xml/`, list `c5/failures-g-c-w2.txt`). No test was added, deleted or renamed, so no TSV row (D47).
- The 23 test classes that read G-C's gate files or `assert.sh` (incl. AssertCompatTest, GatesAllTest): 132 / 0 / 0
  after the header edits.

### Gates on the genuine stage

| gate | PASS / FAIL / SKIP | note |
|---|---|---|
| m1 | 17 / 0 / 0 | |
| m9 | 163 / 0 / 0 | base run of the batch |
| m12 | 31/1, 32/0, 32/0, then 32/0, 32/0, 32/0 | six runs alone (`--exclusive`); x1 failed "server ticks late: 21 (ceiling 20)", see below |
| m13 | **24 / 6**, then **26 / 4** alone, **26 / 4** with a block probe | **RED: a real kernel defect, escalated** (below). 30 / 0 without the NeoForge block mod |
| m14 | 33 / 0 / 0 | first run (the Fabric canary had to be built first: `run/canary/forbricfabriclive.jar` was absent) |
| m17 | 49 / 0 / 0 | first run since C1, cold start (NFRT build, downloads allowed by D68); no SKIP line any more (D70) |
| m20 | 41 / 0 / 0 | |
| m22 | 5 / 0 / 0 | |
| m23 | 7 / 0 / 0 | |
| m27 | 7 / 0 / 0 | |
| m54 | 11 / 0 / 0 | |
| m55 | 23 / 0 / 0 | |
| m56 | 143 / 0 / 0 | |

The scripts' bodies did not change after these runs; only header comments did (the measured controls written in). m17
ran with its D70 re-point already in place.

Two SKIP lines did occur (review B-4): the first m14 and m14 control-A attempts (`w2/queue2.log`, 22:04 and 22:12) each
ended rc=3 with one `SKIP-FATAL: missing .../run/canary/forbricfabriclive.jar`; their `.out` files were overwritten by the
reruns after `run/build-fabric-canary.sh`. A fresh worktree needs that build before m14 (handoff).

### Red controls (switch off -> red; the same gate without it is the green row above)

| gate | control | measured |
|---|---|---|
| m14 | `-Dforbric.fabricRegistryInitialization=off` (D48 control A) | 32 / 1: exactly "the shared mod's block reads back as itself". Replaces the lost 32/5. The read-back was a VANILLA block (`minecraft:note_block[...]`), which a native list cannot give: see review A-2 below, a second sighting of the m13 defect |
| m54 | `-Dforbric.playPayloadFallThrough=off` (in-gate) | server "ForbricKernel lost connection: Internal Exception: java.lang.IllegalStateException: Unknown addon"; no carry verdict |
| m17 | control N (in-gate) | the old install's arguments: exit 2, the OLD_INSTALL line, no boot; `launchInputCheck=off` exit 2 again |
| m17 | tools-jar phase, mutation (out of gate) | installer built with `unpackTools` keeping an existing file, on a copy of the install with the spoiled tools jar: exit 1, "Could not find or load main class net.forbric.tools.MergedLinkChecker". The real installer on the same copy: exit 0, bundled SHA-1 7ff2cba3 back |
| m17 | offline phase, premise (out of gate) | an EMPTY directory behind the same dead proxy fails at once ("could not reach maven.neoforged.net", ConnectException), so the proxy is a real denial |
| m20 | `-Dforbric.dependencyDialog=off` (in-gate step) | green as a control inside the 41 / 0 run |
| m20 | `-Dforbric.crossEcosystemIds=off` (the header's "re-count") | 36 / 5: the two cross-spelling checks and the continue step's three "1 finding(s)" checks |
| m22 | `-Dforbric.bootJarPreload=off` | 3 / 2: the preload line and "every kernel class resolved" (KernelRegistryRevert, ClientShutdown$GuardOutcome) |
| m55 | withheld phase (in-gate) | green inside 23 / 0 |
| m55 | `M55_EXTRA_JVM=-Dforbric.creativeSearchCanaryRefresh=off` | 14 / 9: phase 1's refresh line, its seven grid counts and "minecraft:stone first", as the header said |
| m56 | control phase and 3b (in-gate) | green inside 143 / 0 |
| m56 | `M56_PHASES=standin M56_EXTRA_JVM=-Dforbric.modMenuStandIn=off` | 32 / 29: exactly the 29 the header names |
| m9 | 24 recipes, one run each | below |

**m9 batch** (each switch on a fresh clone; the base run 163 / 0). "STRICT stop": a required mixin is lost and this STRICT
client stops at ~60 s, so every in-world check goes red with the named ones.

| switch(es) off | red | the named checks |
|---|---|---|
| keyModifierSuffix | 1 | the repair's own line only; see "Evidence against D25" |
| carrierLanguages | 1 | "NeoForge's own screens have their text" (2 -> 1 as planned) |
| modDataPacks | 3 | carrier sits below the mods; loot-modifier scan; conditions no source guard owns |
| aliasPresenceParity | 2 | alias parity line; "no alias warns ..." present x134 (C3's 134) |
| neoRegistrationOrder | 35 | its line; unbound data component x16; RegisterEvent listener failed x8; tags lost x60; no world |
| lootModifierIndex | 2 | neoforge:global_loot_modifiers fails to parse; the scan line |
| blockStateCaches | 1 | every block state's cache is computed |
| registryElementCallbacks | 1 | a closed block-state walk is instrumented |
| splitterPacketContext | 2 | splitter line; "no repair was handed its target and declined" x2 |
| clientSmokeTooltipEvent | 1 | a NeoForge mod can add a line to an item's tooltip |
| fabricMainInConstructor | 43 | STRICT stop at ~65 s (a required entrypoint, `initialization:entrypoint:main`: voxy-extra's main NPEs on `Minecraft.getInstance()`); its two lines, the datapack-declaration pair |
| guestInjectorPruner | 3 | ModelManagerMixin trimmed; fabric-item-api tooltip injectors pruned; providers from appenders |
| mixinRetarget + mixinStubRebind | 49 | STRICT stop; both rebound lines, both half-applied absences, the isAir pair |
| mixinRetarget | 44 | STRICT stop; the isAir pair and its absence, malilib's never-runs pair |
| snippetFunnel | 42 | STRICT stop; its three checks |
| widenedFieldTwins | 42 | STRICT stop; its three checks |
| tooltipOrderScrape | 1 | its line |
| fieldDriftAudit | 1 | its line |
| fabricTooltipBridge | 2 | its two lines |
| mixinStubRebind | 1 | malilib's language format hook reaches the body the game calls |
| mixinRetarget.renameCensus.uncalled | 41 | STRICT stop; malilib's never-runs pair |
| decodeSourceScopes | 2 | its two checks |
| fabricConditions | 1 | conditions no source guard owns are still judged |
| datapackDeclarationAfterFabric | 2 | its two checks |

Every entry the header marked TO MEASURE now carries a measured number (commit below); nothing was deleted.

### m13: a real defect (escalated, not fixed here)

On a vanilla-protocol server (Paper + GrimAC), a Forbric client carrying a NeoForge mod that registers blocks decodes the
server's vanilla block-state ids as that mod's states. Measured with the smoke's block probe under the spawn
(`m13-probe-client.log`): grass at (-2 -61 -3) reads `mcwbridges:iron_bridge[connection=side_s]`, dirt
`mcwbridges:iron_bridge[connection=side_w]`, bedrock `mcwbridges:crimson_log_bridge_middle`. The player has no ground,
falls a tick at a time and is set back by the server: Grim files 107-118 movement flags before the control (118, 107, 107
in the three red runs; the first run's 171 was its total, Timer/TimerLimit flags included, which the gate ignores) (Simulation
.0784 = one tick of gravity, GroundSpoof), the walk covers no ground, the pool is not water, and the control move goes
unflagged because the player is already being set back. The same gate with `M13_MODS` = fabric-api + collective (no
mcw-bridges): **30 / 0**, the probe reads `minecraft:grass_block` (8), `minecraft:dirt` (9), `minecraft:bedrock` (34),
0 flags before the control, 1 after. Native NeoForge appends modded states after vanilla's (its
`BlockCallbacks.onBake` re-adds every block's states in registry order, javap in `w2/javap/BlockCallbacks.txt`), so the
vanilla ids agree on a vanilla server; the green runs of m12 (NeoForge sync) and m14 (Fabric sync) remap the ids and so
cannot show it, but m14's control A (Fabric's state-list tracking off) reads a vanilla block for a mcwbridges state, which
is the same non-native list seen from a pure Fabric server (review A-2 below). **m13 was GREEN at Stage 3 on the merged
base with the same jar** (`stage3-fixtures.md`: mcw-bridges-3.1.2-mc26.2neoforge.jar for m13, and m13 among the gates run
all green), so this is a Stage-4 regression. No native NeoForge client was run against the Paper server; the native
comparison is javap only.
Not root-caused further: candidates are the kernel's registry re-open/re-freeze for the Fabric client entrypoints and
the kernel's own pre-connection snapshot that `RegistryManager.revertToFrozen` restores. Owner: a kernel lane
(orchestrator to assign). m13 stays RED; its assertions are not loosened. The first run (24 / 6, not under `--exclusive`)
had two more reds than the others: "sprinting actually engaged" and "sprinting held on every tick" (the client logged
`drill sprint=false from drill tick 61 ... onGround=false ... food=20`, 0 of 160 ticks); the two `--exclusive` red runs held
sprint on 160 of 160. Not analysed; the player had no ground in all three.

### m12: the late-tick flake, correlated with the client's boot and join (not separated)

Six runs alone under `--exclusive`: ticks at twice the budget 21, 10, 4, 16, 3, 16 (ceiling 20); 0 overload warnings in
all six ("server kept up" never failed this wave). With the machine's load sampled every 2 s (x4-x6): every late tick
falls after the first 100 ticks (x4-x6: 0 late in the first 100 each time; mean over 700 ticks 50.1-51.9 ms), and ticks
100-700 hold both the gate's own client JVM booting and the player's join (x4: joined at 01:20:25, the 700-tick line at
01:20:32; java at 5-6.6 cores 01:20:24-31). The sampler prints only those two lines and the load samples show both JVMs as
`java`, so the client's boot cannot be separated from the server's own join work: **correlated, not root-caused** (review
B-2). Another lane is ruled out by `--exclusive`. The ceiling sits inside that spread. The gate's comment "1 in 1800" was
stale; it now gives these numbers (review fix). Open (owner G-C next wave, a gate-design call): start counting after the
join, or raise the ceiling; not changed.

### m17 (D70) and its seeded cache

- Merge-tool phase -> the link checker's tools jar phase; offline phase -> a dead-proxy reinstall over the full cache
  (exit 0, 2 artifacts up to date, vanilla reference up to date, link check twice). The removed-library sub-step is deleted
  (D34): it existed for the libraries the merge read, and the installer fetches no Minecraft library since C1
  (`MojangDownloader` has no `downloadLibraries`, D13); any other fetch is caught by the dead proxy, whose denial is shown
  by the empty-directory control above.
- M17_SEED_CACHE, measured with the network denied: a directory seeded from the gate's install exactly as the gate seeds
  (dl, tools, NeoForm's stamped game), behind the dead proxy: exit 0, NeoForge runtime built from the seeded downloads,
  NeoForm up to date, both link checks `dangling references: 0`, vanilla reference written (10952 classes), 3 artifacts
  staged.

### Evidence against D25 (dropTheKeyModifierSuffix), reported, nothing changed

With `-Dforbric.keyModifierSuffix=off` only the repair's own line goes red; "options.txt loads with modded modifier
bindings in it" and "JEI's 35 saved key bindings are in options.txt after the run, unchanged" stay green although the
fixture holds `key_key.attack:key.mouse.left:CONTROL_OR_COMMAND`. javap of the genuine `Options.processOptions`
(`w2/javap/Options-processOptions-keymapping.txt`): NeoForge splits the value at `:`, passes `[0]` to
`InputConstants.getKey` and `[1]` to `KeyModifier.valueFromString`, which returns NONE on an unknown name. So on this base
the repaired call never sees a suffixed name; D25 keeps the repair for options.txt from Forbric <= 0.4. A C3-style
three-check is the orchestrator's call.

### D71: client-neo-pack

Before (63 jars): a STRICT client stops before any world ("launch stopped ... policy STRICT"; AbiAudit: sophisticatedbackpacks
3.25.83 names a NeoForge class this instance does not carry, C1 W4). After (62 jars), on a clone first: client-ready after 60 ticks, clean disconnect,
exit 0. `MixinRetargetRenamedBodyCorpusTest` (which reads the pack) passes against the edited pack (run on a scratch clone
of the worktree). Then the fixture itself (`/Users/jerry/Documents/Forbric/forbric-kernel/run/client-neo-pack`, untracked)
was edited, nothing downloaded:

| jar | sha1 | now |
|---|---|---|
| sophisticatedcore-26.2-1.4.90.2199.jar | 62e0302498e3915939e6289db4cc8e7af7afbfdf | moved to `mods-disabled/` |
| sophisticatedbackpacks-26.2-3.25.83.2018.jar | 127c32ce3925f3e506f055d61b5a6055b7aeae1c | moved to `mods-disabled/` |
| earthmobsmod-26.2-30.1.0.jar | 655dfaea1044a855be98c70ed1c65067c39169f1 | moved to `mods-disabled/` (crashes native NeoForge .93 too, C1 W4; no other build on disk) |
| sophisticatedcore-26.2-1.5.0.2337.jar | 971b2b22a7417639bd5ef2d10c3f91da3110c5a0 | added (APFS copy from client-merged-pack, where m9 runs it green) |
| sophisticatedbackpacks-26.2-3.26.2.2154.jar | 5b9faaace66ce8ff3ba8cc8d2bf2200fce3e9eed | added (same) |

Full before/after lists with sha1s (review B-6): `w2/review-fixes/neo-before.sha1` (63 jars) and `neo-after.sha1` (62),
`neo-after-disabled.sha1` (`mods-disabled/`, 4 jars: the three above and AxesAreWeapons, which was there before). The
two lists differ in exactly the five rows above; the other 60 have the same names and sha1s, and their mtimes are all
2026-08-08, long before the edit (the before list was rebuilt from the current files plus `mods-disabled/`, so the
mtimes, not a sha1 taken before the edit, are what show those 60 untouched).

No gate reads client-neo-pack (only lib.sh's shared-fixture list and the corpus test). A NeoForge .93 build of
earthmobsmod would need a download: not done, reported.

### Commits (wave 2)

| sha | what |
|---|---|
| 7cabf8cc | m17 tools-jar and offline phases re-pointed (D70), removed-library step deleted (D34) |
| 3da7caa8 | measured controls written into m14, m54, m22, m20 headers |
| 2e378a77 | m9 recipe list measured; m17 header: seeded-offline and tools-jar red measured |
| (this commit) | this record |

### Open

- m13 RED: the vanilla-server block-state id defect above, a Stage-4 regression (green at Stage 3 with the same jar).
  Owner: kernel lane (orchestrator to assign). Bisect handles: m13 on an APFS clone of c3-merged-ro (D57, merged base,
  expected green) against the stage tip; the C1 base flip in between. Second reproduction on a pure Fabric server: m14
  control A (`w2/gates/m14-ctlA-client.log`, a mcwbridges state read back as `minecraft:note_block`). After the fix,
  re-measure m14 control A's read-back.
- m12 late-tick ceiling vs the gate's own client boot and join (above). Owner G-C, next wave.
- D25 evidence above. Owner: orchestrator.
- A-10 (optional, m14 canary raw-id survival line): not done; owner G-C next wave, after the m13 defect is fixed (the
  block-state list it would sit beside is the defect's subject).
- README "Not done yet" still lists m17's two SKIPs; they are gone (orchestrator's file).

## G-C wave-2 review fixes (2026-11-10)

Reviews: `c5/review-w2-g-c-A.md`, `c5/review-w2-g-c-B.md`. Logs: `c5/g-c/w2/review-fixes/`. Every finding was checked
against the kept logs, the fixture and the tree; none was refuted.

| finding | outcome |
|---|---|
| A-1 m9 fabricMainInConstructor cause (ClickCrystals) | **Confirmed, fixed** (8885dad0). No `click` jar in client-merged-pack; the run's own `.out` has "no crash report (absent)", `confirmed required losses: ['initialization:entrypoint:main']` and the CAUGHT line naming the entrypoint, so no rerun was needed: voxy-extra's main NPEs on `Minecraft.getInstance().gameDirectory`. Header and the wave-2 table row now say STRICT stop and name it; the header's STRICT-stop definition covers a required entrypoint. |
| A-2 m14 control A read-back | **Confirmed** (the reviewer's PLAUSIBLE; the switch cannot explain it): the server is pure Fabric, so its list is vanilla then mcwbridges and the bridge's id lies past the vanilla states. A native client list (NeoForge `BlockCallbacks.onBake`, registry order: vanilla, the canary at raw id 1196, mcwbridges) holds vanilla states only below that range, so the id can land only on a canary or mcwbridges state, synced or not; the client read `minecraft:note_block`. Same non-native list as m13 (there a vanilla id reads as `mcwbridges:iron_bridge`, raw id 1196). **Fixed** (319b632f): the header keeps 32 / 1 and the failing check, calls the read-back the m13 defect's second sighting and asks for a re-measure after its fix; record and handoff hand `w2/gates/m14-ctlA-client.log` to the m13 owner. Cause of the list not established. |
| B-1 m13 is a Stage-4 regression | **Confirmed, fixed in the record/handoff.** `stage3-fixtures.md:31` (mcw-bridges-3.1.2-mc26.2neoforge.jar for m13) and `:86-88` (m13 GREEN at Stage 3); the red runs load that jar. No native NeoForge client ran against Paper (javap only). Bisect handles named in Open. |
| B-2 m12 "root-caused" | **Confirmed, fixed.** The sampler prints at 100 and 700 ticks only; x4 joined at 01:20:25, the 700 line at 01:20:32; both JVMs show as `java` in the load samples. Now "correlated, not separated" in the record, checks.md and handoff, with one owner (G-C next wave); the gate's stale "1 in 1800" comment now gives the measured spread (319b632f, comment only). |
| B-3 "107-171" | **Confirmed, fixed.** Movement flags 118, 107, 107; 171 was the first run's total including Timer/TimerLimit (ignored as host load). Also recorded: the first run's two sprint reds (review A, "not verified"), not analysed. |
| B-4 SKIP lines | **Confirmed, fixed in the record.** `queue2.log`: the first m14 and m14-ctlA attempts ended rc=3, SKIP=1 (canary missing), outputs overwritten. Handoff: build `run/build-fabric-canary.sh` before m14 on a fresh worktree. |
| B-5 m17 mutant not kept | **Confirmed, fixed by re-measuring.** The mutation is now kept as `review-fixes/m17-tools-jar-mutation.diff` (one line in `LinkCheckTool.unpackTools`: return early when the tools jar exists, logging a `[mutation]` marker). Built in a scratch clone with plain `--offline`; marker present in the mutated class (1) and absent in the real one (0). On a copy of the install with a spoiled tools jar: mutated installer exit 1, `[mutation] unpackTools keeps an existing tools jar`, "Could not find or load main class net.forbric.tools.MergedLinkChecker"; real installer exit 0, both link checks `dangling references: 0`, bundled SHA-1 7ff2cba3 back (`m17-mutant-control.log`). |
| B-6 D71 record | **Confirmed, fixed.** Full sha1s in the D71 table and full before/after lists in `review-fixes/neo-*.sha1` (see D71 above). Main-line reader measured: main's `MixinRetargetRenamedBodyCorpusTest` (EXPECTED_IN_JARS = 52) run from an APFS clone of c3-merged-ro (its copy differs from main's only in fixture-path helpers), merged-base stage, plain `--offline`, with only `run/client-neo-pack` swapped: before the edit 691 jars, 44 moves in jars / 19 distinct; after 688 jars, 44 / 19; the move lists are identical (the reports differ only in their header line). Both runs fail the same assertion (52 expected, 44) because the rest of c3's corpus differs from main's (its client-popular is Stage 3's); the moves set assertion passes in both. So the edit changes nothing this test counts; main's own staged base was not run. |
| B-7 A-10 owner, m12 owner | **Fixed** in Open (A-10: G-C next wave, after the m13 fix; m12: G-C next wave everywhere). |
| B-8 m9 inline key-modifier comment | **Confirmed, fixed** (8885dad0): the seed step's and the checks' comments are merged-base history now, waiting on the D25 ruling. |

**Re-runs after the fixes** (pinned build, plain `--offline`, lane-env ports, client lock; logs in `review-fixes/`):

- The 23 test classes that read G-C's gate files or `assert.sh`: 132 tests, 0 skipped, 0 failed (`contract.log`).
- Gates whose files changed (comments only): m14 33 / 0; m12 32 / 0 alone (`--exclusive`; late ticks 10, 0 in the first
  100); m9 163 / 0 on a fresh clone (deleted after). m17 did not change (the mutant is out of gate).
- Full suite at 319b632f (same command as the first act): 5772 tests, 0 skipped, 5 failed, exactly the five rows of
  `failures-wave1.txt` (LiquidBounce x3, SharedFinalSourceActualTest x2); 0 new, 0 gone (`suite-final-xml/`). No test
  added, deleted or renamed, so no TSV row (D47).
- Scratch: the installer clone, the c3-merged-ro clone, the before-pack copy and the m9 clone are deleted.
