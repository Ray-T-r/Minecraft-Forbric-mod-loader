# Stage 4 C6, lane LINK: the link check reseeded in the kernel tree

Lane LINK of C6 (scout `~/Forbric-backups/stage4-notes/c6/scout.json`, items `link-check-reseed-kernel-tree`,
`gate-m0-step4-verdict`, `gate-m0-step6-link-subchecks`, `installer-link-check-off-merge-tools`,
`replace-linkToolTest-linkGateTest`, `release-assets-drop-merge-tools`, critic corrections 11-15, 20, 23). Branch
`s4-c5-c6link` cut from the stage tip `495e48fc`, ports 46840-46859, pinned build (D68). Logs:
`~/Forbric-backups/stage4-notes/c6/link/`.

## Commits

| sha | subject |
|---|---|
| `e624156e` | Reseed the link check in the kernel tree and judge access, final and invokedynamic, so the installer stops shipping the merge tools' checker |
| `50d9111f` | gate-m0 judges the reseeded link check in steps 4 and 6 instead of skipping them, so no C6 SKIP line is left |
| `d16586c4` | Let the link checker's catches rethrow or report through Caught, as every kernel catch must |
| (this file) | the record |

## What changed

- **`net.forbric.kernel.linkcheck.LinkCheck`** (kernel main, ASM core + JDK only). Judges references with JVM semantics
  (JVMS 5.4.3, 5.4.4) in four checks, each switchable for its red controls:
  - EXISTENCE: classes, fields (class, superinterfaces, superclasses), methods (class methods: class and superclasses,
    then maximally specific superinterface methods; interface methods: the interface, `Object`'s public methods, then
    superinterfaces; static and private interface methods not inherited; constructors only on the class named;
    `MethodHandle`/`VarHandle` signature-polymorphic methods match any descriptor; array `clone()` always there).
  - ACCESS: the class referenced (public or same package) and the resolved member from the referring class: private
    (same class or nestmate, Java 11), package-private, protected (same package, or a subclass of the declarer whose
    instance access names a class related to the caller, HotSpot's rule), public; a superclass or interface the class
    cannot access.
  - FINAL: a write to a final field from another class, or (class file >= 53) from another method than the
    initializer; extending a final class; overriding an accessible final method.
  - INDY: every invokedynamic's bootstrap handle, each handle argument (as the instruction its kind stands for), each
    class and method type named, the call site's descriptor classes, and `ldc` of handles, method types and dynamic
    constants.

  Every violation counts in the one number the verdict line ends with, `[link-check] loaded N classes (...); dangling
  references: K`, the line LinkCheckTool and gate-m17 read (critic 13).
- **`linkcheck.game.GameLinkCheck`** (kernel main; it reads the transformers, so it is not in the standalone jar): the
  game a launch assembles. The base and carrier pass through `VanillaMemberTwinInjector` (vanilla references from the
  sidecar, as KernelBoot) and `ForbricMergedBaseCompatTransformer`, so the members they add exist with the access they
  get there (critic 14); plus the kernel's game-side jar (and its Mod Menu API stand-in bins), the game's libraries for
  inheritance, and the helper classes a definitions capture shows the launch generated (names the capture holds that
  no input, library, the boot jar or a `--known` jar provides). It refuses an incomplete view (a twin not landed, a
  class not viewed) with exit 2. `VanillaMemberTwinInjector.rowCount()` was added for that (a file outside the lane's
  list: one public accessor).
- **`:linkCheckJar`** builds `build/link-check/forbric-link-check.jar` (the `linkcheck/*.class` top level and ASM's
  core; the task refuses anything else): 150 KB.
- **Installer.** `bundleLinkCheck`/`buildLinkCheck` replace `buildMergeTools`/`bundleTools`; the checker rides in every
  build, slim included, at `forbric/tools/forbric-link-check.jar` and is unpacked to `.forbric-build/tools/`.
  `LinkCheckTool` keeps its constructor, `linkCheck(jvm, base, carrier)` and the verdict line; its first run judges
  the base's members, the second the carrier's (`--judge`), both scanning both jars. `BuildStamp`'s key is the pins
  alone (critic 12). `releaseAssets` depends on `bundleForbric` only and publishes `kernel=` only.
  `buildMergeTools`, `bundleTools`, `linkBaseline`, `mergeToolsJar` and `loaderDir` are deleted rather than left
  unused: nothing referred to them, and the installer build no longer reads `forbric-loader/` at all.
- **Tests.** `linkGateTest` (hand-kept list of 8 mains) becomes `installerTest`: `InstallerTests` runs every
  `*Test` class with a `main(String[])` under the test classes, each with its own scratch directory, and fails when
  any fails or none ran. `BaseLinkGateTest` is `LinkCheckToolTest` (its 7 sibling calls removed: the runner runs
  them). The kernel's `linkToolTest` becomes `installerTest` (in `check` and `integrationTest`): it builds
  `linkCheckJar` and runs the installer's `installerTest -x buildLinkCheck` (the installer's own way to the jar is a
  nested build of the kernel, which must not run inside a kernel build).
- **gate-m0.** Step 4 runs GameLinkCheck (below). Step 6: step 2's XML must show the `linkcheck` test classes ran with
  nothing skipped or failed; the installer's `installerTest` on the checker it carries; `test-bundled-checker.sh`.
  `test-link-gate.py` and `test-bundled-baseline.sh` are deleted (cover below). No `[kernel] SKIP (C6` line is left in
  the tree (`grep -rn 'SKIP (C6'` over `*.sh *.java *.py`: none).

## What is judged (critic 15)

- A reference is judged when its owner is a class of a judged input, or, when the owner is defined nowhere, when its
  package is one a judged input defines (a class missing from a package the game owns is a NoClassDefFoundError).
  Install time: run 1 judges the base's classes, run 2 the carrier's; both scan both jars, so every reference into
  either jar is judged once, in both directions (D10 kept: base + carrier, zero violations). Game check: base,
  carrier, game side and helpers.
- Not judged: owners in the JDK (unless `--judge-runtime`), in `--lib` inputs (the Minecraft libraries), or in a
  package no judged input defines; those count as "out of scope". A resolution or access decision that needs a
  supertype the check cannot read (a library not given) is counted "unresolvable", never guessed. Install time gives
  no libraries: 894 (run 1) and 148 (run 2) unresolvable references on the genuine pair; with the libraries, 0 and 56
  (the carrier's supertypes in NeoForge's own libraries, which are not in the Minecraft libraries directory).
- References read: field and method instructions, `new`/`checkcast`/`instanceof`/`anewarray`, `multianewarray`,
  `ldc` (class, method type, handle, dynamic constant), catch types, superclass and interfaces, overrides of final
  methods, invokedynamic (bootstrap, arguments, call-site descriptor classes).
- Not modelled: descriptor classes of ordinary field and method references (the JVM loads them lazily), the
  verifier's type assignability and its protected-receiver check (JVMS 4.10.1.8), module exports of JDK packages
  (launch flags can open them), IncompatibleClassChangeError mismatches (static vs instance, class vs interface
  owner), annotations. All scanned inputs are taken to share one defining loader, as the game, its carrier and the
  kernel's game side do.
- The game view applies only the first COREMOD pair (twins, compat repairs). The rest of the chain and Mixin depend on
  the mods installed and are not checked statically.

## Measurements

| what | result | evidence |
|---|---|---|
| genuine pair, run judging the base | 13394 classes, 1,243,939 judged, 894 unresolvable, 363,460 out of scope, **0** | `probes/genuine-pair-judge-base.txt` |
| genuine pair, run judging the carrier | 106,993 judged, 148 unresolvable, **0** | `probes/genuine-pair-judge-carrier.txt` |
| pair + Minecraft libraries, JDK owners judged too | 1,717,534 judged, 56 unresolvable, **0** | `probes/genuine-pair-judge-runtime-with-libs.txt` |
| GameLinkCheck CLI with m0's capture (pre-commit, stage's capture) | 13581 scanned, 1,360,650 judged, 56 unresolvable, view 70 classes, twins 9/9, 2 helpers (`net/forbric/kernel/runtime/generated/LootCallbacks$…`, a Mixin `ChiseledBookShelfBlockEntity$Anonymous$…`), **0** | `probes/gamelinkcheck-cli-with-capture.txt` |

So on the genuine stage there is no real IllegalAccessError, NoSuchFieldError or NoSuchMethodError to root-cause:
no baseline exists and none is needed.

## Tests and controls

- `LinkCheckTest` (8 tests, unstaged): 21 flagged cases, each reported alone with its kind and refused by a real JVM
  (classes defined in one loader, `run()` invoked) with the error the check predicts; 6 false-positive controls that
  link and run (nestmate private access, protected through a subclass receiver, `MethodHandle.invoke`/`invokeExact`
  with the JDK judged, static and private interface methods with defaults through superinterfaces, array `clone()`,
  public members with a javac-shaped lambda); `eachCheckIsTheOnlyOneThatSeesItsCases` (critic 23: with any one check
  off, exactly the cases that need it go unreported and every other case is still reported); both directions; out
  of scope; the command line counts every kind in its verdict; the checker's classes name only `java/`, ASM and
  themselves.
- `GameLinkStagedTest` (3 tests, staged): the stage links (0, view complete, > 1,000,000 judged); a Hud-shaped caller
  (`invokevirtual Hud.extractHotbarAndDecorations`) on the real staged base is `MEMBER_ACCESS ... (private in Hud)`
  with the twins and `MISSING_METHOD` against the jar alone (critic 14); a carrier with `NeoForge.EVENT_BUS` narrowed
  to private exits 1 in the run judging the carrier and 0 in the run judging the base.
- `LinkCheckToolTest` (installer, through the packaged jar in a child JVM): a valid pair in both runs, a missing
  member of either jar, a carrier member narrowed to package-private (new red, critic 13: "an inaccessible member did
  not count" would fail it), an empty base; the supplied-artifacts path.

JVM agreement (`TEST-net.forbric.kernel.linkcheck.LinkCheckTest.xml`, system-out):

| case | check says | JVM throws |
|---|---|---|
| missing method / interface method | MISSING_METHOD | NoSuchMethodError |
| missing field | MISSING_FIELD | NoSuchFieldError |
| missing class in an owned package | MISSING_CLASS | NoClassDefFoundError |
| private / package-private / protected method, private field | MEMBER_ACCESS | IllegalAccessError |
| Hud-shaped private instance method | MEMBER_ACCESS | IllegalAccessError |
| protected instance method through an unrelated receiver | MEMBER_ACCESS | IllegalAccessError |
| package-private class; inaccessible superclass | CLASS_ACCESS | IllegalAccessError |
| another class's final field (from a method, from the writer's `<clinit>`); own final field outside `<init>` | FINAL | IllegalAccessError |
| extends a final class; overrides a final method | FINAL | IncompatibleClassChangeError |
| bootstrap method missing | INDY_MISSING | NoSuchMethodError |
| lambda to another class's private method | INDY_ACCESS | IllegalAccessError |
| handle to a missing field | INDY_MISSING | NoSuchFieldError |
| handle writing a final field | INDY_FINAL | IllegalAccessError |

Red controls, each in a scratch clone (`mutations/`, every one turns its tests red; the tip is green):

| mutation | red |
|---|---|
| M1 interface calls skipped, as MergedLinkChecker did | 2 (missing interface method, in two tests) |
| M2 private access only from the declaring class | 1 (nestmate control) |
| M3 no signature polymorphism | 1 (MethodHandle control) |
| M4 protected without the receiver rule | 2 (unrelated receiver) |
| M5 no default methods through superinterfaces | 1 (interface control) |
| M6 the verdict counts only missing members (critic 13) | 1 (command line verdict) |
| M7 the game view without the twins (critic 14) | 2 of 3 staged tests (`twins landed 0 of 9`) |
| M8 final writes not judged from another class | 2 (first run 0: the other rule still saw the case; the `<clinit>` case was added, then 2) |

Install-level controls: `slim/` (critic 11, 13). A slim installer (0 payload entries, the checker and the pin present)
built against a local stand-in release (`slim/release.py`, third-party jars pre-staged so only Forbric's jar is
fetched) installs with `--artifacts` (the staged genuine pair): exit 0, both verdict lines (`dangling references: 0`)
logged **before** `fetching manifest` and `staged 15 Forbric and kernel-dependency jar(s)`. The same install with
`NeoForge.EVENT_BUS` narrowed: exit 1, `dangling references: 330` in the carrier run, no profile written.

`test-bundled-checker.sh` (1/3 the bundled `LinkCheck.class` is the tree's and ASM rides with it; 2/3 a checker-source
change reaches the bundle; 3/3 the bundled jar refuses an empty input on its own): ALL GREEN. Red: with the
`inputs.dir(linkCheckSources)` declaration removed in the copy (`controls/`), 2/3 fails ("still the first build's").

## Gates and suites

- gate-m17 (INST's file), run once from a scratch copy with the two re-points INST has to make
  (`m17-scratch-repoints.diff`), seeded from the stage's install: **GREEN, 47 PASS / 0 FAIL**. The install-time
  verdict lines and every `check_exactly ... 2` on them are unchanged. The seeded NeoForm game carried an old
  ` tools=` stamp and was rebuilt (`[neoform] building NeoForge's patched Minecraft`, NeoForm data fetched from
  maven.neoforged.net): the one-time rebuild critic 12 asked to record. The second install reused it.
- gate-m0 at d16586c4 (lane env, ports 46840+): **17 PASS / 1 FAIL**, the FAIL is step 2's "3 of 5962 skipped" = the
  three LiquidBounce fixture skips (D58, D87a), nothing else; no `SKIP (C6` line. Step 4: `loaded 64948 classes
  (13581 scanned; 1360706 references judged, 0 unresolvable ..., 311309 out of scope); dangling references: 0`, view
  70 classes, twins 9 of 9, 2 generated helpers from step 1b's capture. Step 6: the linkcheck controls ran in step 2
  (2 classes, 11 tests), `installerTest` (LinkCheckToolTest PASS, 8 classes, 0 failed), test-bundled-checker ALL
  GREEN. (`gate-m0.out`. A first m0 run at 50d9111f was stopped in step 2 once EveryCatchIsVisibleTest's failure was
  known; its verdict is void.)
- Full suite at d16586c4 (`-Pforbric.requireFixtures=all jar cleanTest test --continue`): **5962 tests / 0 skipped /
  5 failed**, name for name `c5/failures-w2d.txt` (LiquidBounce x3, SharedFinalSourceActualTest x2): 0 new, 0 gone.
  5951 + 11 = LinkCheckTest 8 + GameLinkStagedTest 3. (`suite.log`, `suite-xml/`.)
- Independence control (critic: Stage 5 can delete forbric-loader): `git archive` of the tip with `forbric-loader/`
  and `fabric-loader/` removed. Installer `jar` 0, `devToolsJar` 0 (carries the checker), kernel `installerTest` 0
  (8 test classes). Kernel unit tests: 76 failures at 495e48fc and the same 76 plus `EveryCatchIsVisibleTest` at
  50d9111f; that one was this lane's (six silent catches), fixed in d16586c4 and green in the same clone. The 76 are
  lib.sh's `OLD` default and the gate contract tests that source it (`[kernel] FATAL: no staged tree ... forbric-loader`),
  Stage 5's.
- No zh runs (D88b): no new user-visible text in a component that reads the locale.

## Deleted checks and their cover (D89)

| deleted | what it checked | cover now |
|---|---|---|
| gate-m0 step 6 `forbric-loader/run/test-link-check.sh` | the old checker's negative controls | LinkCheckTest + GameLinkStagedTest in step 2, required by step 6 from the XML |
| `run/test-link-gate.py` | (a) the packaged baseline equals the tracked one; (b) javac the 8 mains, place the tools jar as a resource, run BaseLinkGateTest | (a) no baseline exists any more; (b) `installerTest` runs the same mains on the same resource layout (the bundled checker on the classpath), plus any new `*Test` |
| `run/test-bundled-baseline.sh` | the bundled baseline is the tracked one; a baseline-only change reaches the bundle; a stale tools jar is refused | `test-bundled-checker.sh` (the checker is the tree's; a source-only change reaches it; red control above). The baseline has no successor |
| installer `linkGateTest`, kernel `linkToolTest` | the 8 mains | `installerTest` (8 classes, by discovery) |

## For the other lanes

- **INST.** Installer.java and ArtifactBuilder.java are not edited by LINK: the checker rides in every build, so the
  install needs no reordering (the slim run above). gate-m17 needs exactly `m17-scratch-repoints.diff`: the build step
  `(cd "$KERNEL" && ./gradlew --offline --no-daemon -q linkCheckJar) && (cd "$INSTALLER" && ./gradlew --offline
  --no-daemon -q jar -x buildForbricJars -x buildLinkCheck)` (`-x buildMergeTools` now names a task that does not
  exist), and the tools phase reads `forbric/tools/forbric-link-check.jar` and `.forbric-build/tools/forbric-link-check.jar`;
  its header (lines 22-24, 56-57, 478-486) still describes the merge-tools jar and MergedLinkChecker, and its RED note
  should be re-measured on the new jar. Earlier installs leave `.forbric-build/tools/forbric-merge-tools.jar`, which
  nothing writes or reads any more: one more entry for the clean-up list. Phase U should expect the one-time rebuild
  (old stamps end in ` tools=<sha1>`). The clean-up test joins `installerTest` without a build.gradle edit if its
  class name ends in `Test` and it has `main(String[])`. The `releaseAssets` comment no longer claims anything about
  byte reproducibility (critic 20): it says the game artifacts are never published and the link check is their gate,
  which stays true when the runtime becomes reproducible; if INST wants to say more, only what its test measures
  (the NeoForge runtime it builds is byte-equal across builds), not NeoForm's game.
- **CI.** build.yml's development-tools job: `./forbric-kernel-installer/gradlew -p forbric-kernel-installer
  installerTest` instead of `linkGateTest` (ubuntu, windows, macOS); the kernel job's `./gradlew build` reaches it
  through `check` -> `installerTest`. Comments at build.yml :69 and :229. ci-unstaged.tsv gains three rows
  (measured with a missing staged root; ci-prepared gains none, its profile supplies staged, game-side and
  mc-libraries):

  ```
  test	net.forbric.kernel.linkcheck.GameLinkStagedTest	aCallerOfHudsTwinIsAnAccessViolationOnlyWithTheTwins(Path)	[fixture:staged] the genuine base and carrier: $ROOT/forbric-loader/run/neoforge-patched/patched-mc-neoforge-26.2.jar
  test	net.forbric.kernel.linkcheck.GameLinkStagedTest	aCarrierWithOneNarrowedMemberTheBaseUsesDoesNotLink(Path)	[fixture:staged] the genuine base and carrier: $ROOT/forbric-loader/run/neoforge-patched/patched-mc-neoforge-26.2.jar
  test	net.forbric.kernel.linkcheck.GameLinkStagedTest	theGameALaunchAssemblesLinks()	[fixture:staged] the genuine base and carrier: $ROOT/forbric-loader/run/neoforge-patched/patched-mc-neoforge-26.2.jar
  ```
- **REL.** `evidence.py` RELEASE_ROLES still lists `merge-tools`; releaseAssets no longer publishes it. push-and-run.py
  :201's docstring says DevPrepare's stamp is "followed by the tools digest"; it no longer is (its `startswith`
  comparison accepts both forms).
- **DOCS.** introduction.md :2095-2096, :2139, :2149, :2218 (critic 5) and the zh lines; new names: `LinkCheck`,
  `GameLinkCheck`, `:linkCheckJar`, `forbric-link-check.jar`, `installerTest`, `test-bundled-checker.sh`; the
  BuildStamp key change. Player-facing draft for the release notes (not applied, README unchanged):
  "The first install of this version rebuilds Minecraft's game files once, which takes a few minutes and may download
  NeoForge's build data again; later reinstalls reuse them." Developer note: "The installer's link check now also
  stops on members the other jar cannot access, final members written or overridden, and broken invokedynamic
  references, not only on missing ones."

## Not done / open

| item | owner |
|---|---|
| Installer version (scout lists `installer-version-vs-old-install-message` in LINK): not bumped; owner answer 3 settled it (0.4.x, no bump; the "0.4 or earlier" texts go to a later lane) | closed |
| gate-m17 re-points and the merge-tools tools-jar clean-up | INST |
| build.yml and the TSV rows | CI |
| RELEASE_ROLES `merge-tools`; push-and-run docstring | REL |
| Install time judges without the Minecraft libraries (894 + 148 references unresolvable through library supertypes). The supplied-set check runs before the base version is read, so the library paths are not known there | LINK/INST, a later round if wanted |
| The game check sees the first COREMOD pair only; the rest of the chain and Mixin are mod-dependent and unchecked statically. Helpers come from a dedicated-server capture with fabric-api only (client-only helpers are not captured) | design limit, recorded |
| `forbric-loader/`'s MergedLinkChecker, test-link-check.sh and link baseline stay until Stage 5 deletes the tree; nothing outside it uses them once INST re-points m17 | Stage 5 |
| Bootstrap reported `forbric-kernel/build/sweep100-mac` dangling (its target is missing in the main checkout too) | harness / orchestrator |

## LINK review fixes

Two reviews of the tip `666ad56b` (`c6/review-c6link-A.md`, `c6/review-c6link-B.md`). Every finding was checked;
the confirmed ones are fixed in their own commits, nothing was refuted outright. Logs:
`~/Forbric-backups/stage4-notes/c6/link/review-fixes/`.

| sha | subject |
|---|---|
| `54e453ea` | Judge kind changes and nest membership in the link check, and give gate-m0 step 4 the launch's own class path |
| `53f22b13` | Keep the player payload out of installerTest, fail on a *Test that cannot run, and refuse to bundle a stale checker |
| `9228ea1d` | Add GameLinkStagedTest's three skips to the CI unstaged skip baseline, as D47 asks of the lane that adds them |
| (this section) | the record |

### Per finding

| finding | verdict | what was done |
|---|---|---|
| A1: JVM link failures the check passed | confirmed (reviewer's 9 cases re-run, `reviewer-cases/cases-after.out`) | New `Check.KIND`: static instruction on an instance member and the reverse (fields and methods, instructions and method handles), class method reference to an interface and interface method reference to a class (JVMS 5.4.3.3/5.4.3.4 step 1), superclass that is an interface, interface that is a class (JVMS 5.3.5), `new` of an abstract class or interface. Violations `INCOMPATIBLE` / `INDY_INCOMPATIBLE`, counted in the verdict and in the breakdown line (`incompatible N`). Nest hosts are validated as the JVM does since JDK 15 (readable host, same package, host's `NestMembers` lists the member, else the class is its own host). After: fn1, fn2, fn3, fn4, fn6, fn7, fn9 flagged with the JVM's error; fp8 still agrees with the JVM; fn5 (a class missing from a package no judged input defines) stays out of scope by design (below). |
| A1 follow-on: a false positive the nest rule exposed | found while re-measuring | The stage's game view had 4 `MEMBER_ACCESS`: fabric-api's Mixin-synthesised `ChiseledBookShelfBlockEntity$Anonymous$…` reads its target's private `lastInteractedSlot`/`updateState`. The capture's bytes of the target as the launch defined it list that helper in `NestMembers` (Mixin adds it); the view (the first COREMOD pair, not Mixin) does not. GameLinkCheck now takes exactly that attribute from the capture when the captured host lists the helper (`LinkCheck.nestMember`), and prints `[link-check] nest member from the capture: …`. Structural (any helper, any host), never by name. |
| A2 / B1: installerTest built the player payload through a nested online kernel build | confirmed (`installer-dryrun-tip.log` before the fix lists only what follows) | The installer's tests compile in `compileInstallerTests` against `main.output.classesDirs` only; `-m installerTest -x buildLinkCheck` lists `bundleLinkCheck`, `compileJava`, `compileInstallerTests`, `installerTest` and nothing else; the kernel's `installerTest` run shows the nested build doing only those four (`unit-linkcheck-3.log`). The kernel's `installerTest` passes `--offline` to the nested installer build when it is itself offline. |
| A3 / B2: step 4 read the host's whole launcher `libraries` folder | confirmed | Step 4 passes what the step-1b launch put on its class path: `--minecraft "$MC"` (new; the version JSON's exact libraries and jline 3.25.1, resolved as `launch-kernel-server.sh` does, `GameLinkCheck.launchLibraries`) and each entry of the kernel's boot class path as `--lib`. The 0 vs 56 explained: the 56 resolve through the kernel's boot dependencies (night-config, sponge-mixin, …; `probes/game-stage-json-depsonly.txt` 0, `…-kerneljaronly.txt` 56), which the host folder happened to hold from other installs, in several versions. With the launch's class path the count is 0 on any machine that can launch, and a new check asserts it. `GameLinkStagedTest` uses the same version-JSON list instead of the folder. |
| A4: a stale checker was bundled without complaint | confirmed | `:linkCheckJar` writes `Forbric-LinkCheck-Sources` (SHA-256 of `linkcheck/*.java` and the ASM version) into its manifest; `:bundleLinkCheck` computes the same from the tree, refuses a mismatch, and has the sources as inputs so a source change re-runs it. `test-bundled-checker.sh` step 4/4: green on the tip, red with the comparison switched off (`test-bundled-checker-R4-mutation.log`). |
| A5: `InstallerTests` passed over a `*Test` without a runnable main | confirmed | Reported as `[installer-test] NO MAIN <class>` and the run fails; the summary names them. `InstallerTestsRunnerTest` compiles a runnable, a JUnit-style and a package-private-main probe and runs the runner over them; with the old skip restored it fails (`installerTest-R5-mutation.log`). This also covers B7's "a test that loses its public main drops out". |
| A6: the three `ci-unstaged.tsv` rows only in the record | confirmed (D47: the lane adds them) | Added to the file (`9228ea1d`), the rows measured earlier, unchanged. No new test skips in a CI profile: `LinkCheckTest`'s new test needs no fixture. |
| B3: version item listed as open | confirmed | Closed: owner answer 3 (0.4.x, no bump). The "0.4 or earlier" wording belongs to a later lane. |
| B4: REL hand-off stale | confirmed | REL removed `merge-tools` from `RELEASE_ROLES` and `MERGE_TOOL_SOURCES` in `283670d2`; only `push-and-run.py`'s "tools digest" docstring is left (line 205 at REL's `962ac3bd`). |
| B5: m17, slim and mutations ran on an older checker | confirmed | Re-run on the fixed tree (below). The earlier record's "the install-time verdict lines are unchanged" meant their shape (`… ; dangling references: K`, which LinkCheckTool and m17 match); the text in parentheses did change. The slim proof uses the supplied-artifacts (`--artifacts`) path only; the built path is m17's. |
| B6: "the same 76" inferred | confirmed | Re-measured at `9228ea1d` (below). |
| B7: loose step-4/6 lines, no gate-level red | confirmed | The view regex needs at least one twin and at least one helper; red controls for every step-4 line and for step 6's controls-ran line (below). A lost public main is covered by A5. |
| B8: files not named; Stage 5 list | confirmed | `e624156e` also changed one comment each in `JdkLocator.java` (the link-check JVM's purpose) and `VanillaReferenceTest.java` (a reference to the deleted merge tools), and added `VanillaMemberTwinInjector.rowCount()`. The plan's Stage 5 list still names `buildMergeTools`/`bundleTools`/`linkBaseline`, which LINK deleted: for the orchestrator (the plan is not in the tree). |
| B9: "under a second each" | confirmed | Javadoc now "about one to two seconds each" (measured 0.8 s / 0.7 s here, 0.93 s / 1.92 s by review B). |

### What is judged, corrected

- Checks are now EXISTENCE, ACCESS (with nest validation), FINAL, KIND, INDY. "Not modelled" loses the
  IncompatibleClassChangeError mismatches. Still not modelled: a class missing from a package no judged input defines
  (fn5: counted out of scope, because the check cannot tell a library it was not given from a missing package); the
  verifier's type assignability and its protected-receiver check; module exports of JDK packages; annotations.
- The game view takes from Mixin only the nest membership of helpers it synthesised (from the capture). Other members
  Mixin adds to a target are not in the view; a helper referring to one would be reported missing (none today).
- The check JVM resolves JDK supertypes against its own JDK. The installer may run the check on any Java >= 21
  (`JdkLocator.MINIMUM_FEATURE`) while the game's classes are Java 25: not measured (no JDK 21 here), recorded as review A
  left it.

### JVM agreement, the eleven new cases (`TEST-net.forbric.kernel.linkcheck.LinkCheckTest.xml`)

| case | check says | JVM throws |
|---|---|---|
| private access from a class whose nest host does not list it | MEMBER_ACCESS | IllegalAccessError |
| static read of an instance field | INCOMPATIBLE | IncompatibleClassChangeError: Expected static field |
| instance read of a static field | INCOMPATIBLE | IncompatibleClassChangeError: Expected non-static field |
| static call of an instance method | INCOMPATIBLE | IncompatibleClassChangeError: Expected static method |
| instance call of a static method | INCOMPATIBLE | IncompatibleClassChangeError: Expecting non-static method |
| class method reference whose owner is an interface | INCOMPATIBLE | IncompatibleClassChangeError (must be InterfaceMethodref) |
| interface method reference whose owner is a class | INCOMPATIBLE | IncompatibleClassChangeError: Found class, but interface was expected |
| new of an abstract class | INCOMPATIBLE | InstantiationError |
| superclass that is an interface | INCOMPATIBLE | IncompatibleClassChangeError: has interface as super class |
| implements a class | INCOMPATIBLE | IncompatibleClassChangeError: can not implement, because it is not an interface |
| method handle constant calling an instance method as static | INDY_INCOMPATIBLE | IncompatibleClassChangeError: Expected static method |

32 flagged cases in all, each reported alone and refused by the JVM as predicted; with KIND off exactly the KIND cases
go unreported (`eachCheckIsTheOnlyOneThatSeesItsCases`). `aNestMemberTheDefinedHostListsIsANestmate`: a member the
defined host lists is a nestmate for the check and runs in the JVM; one it does not list is still refused.

### Red controls added

| control | result |
|---|---|
| M9 nest host not validated | 3 LinkCheckTest tests red |
| M10 owner kind unchecked | 2 red |
| M11 field static-ness unchecked | 2 red |
| M12 abstract `new` unchecked | 2 red |
| M13 superclass kind unchecked | 2 red |
| M14 GameLinkCheck ignores the capture's nest members | step 4 red: 4 MEMBER_ACCESS on the Mixin helper, verdict FAIL (`mutations/M14-step4.out`) |
| R4 bundling without the stale-checker comparison | `test-bundled-checker.sh` 4/4 FAIL |
| R5 runner skipping a `*Test` without main | `InstallerTestsRunnerTest` FAIL |
| step 4, narrowed carrier (`NeoForge.EVENT_BUS` private) | 331 inaccessible, the verdict line FAIL, the rest PASS (`m0-step4/narrow.out`) |
| step 4, boot class path left out of the libraries | 56 unresolvable, "every reference was judged" FAIL, the rest PASS (`m0-step4/nobootlibs.out`) |
| step 4 view regex on `twins landed 0 of 0` and on `helpers scanned 0` | the new regex matches neither (the old one matched both), BSD grep (`m0-step4/view-regex.out`) |
| step 6 "controls ran in step 2" on no linkcheck XML / on a skipped one | FAIL both, PASS on step 2's real XML (`m0-step6-controls.out`) |

All step-4 controls run step 4's block extracted verbatim from gate-m0.sh (`m0-step4/harness.sh`) on the lane's step-1b
capture.

### Re-measured on the fixed tree (`9228ea1d`)

| what | result | evidence |
|---|---|---|
| genuine pair, run judging the base / the carrier, every check incl. KIND | 1,243,939 and 106,993 judged, **0** and **0** (894 / 148 unresolvable without libraries, as before) | `probes/genuine-pair-judge-{base,carrier}.txt` |
| game view on the stage, the launch's class path | 1,360,706 judged, 0 unresolvable, **0**, twins 9/9, 2 helpers, 1 nest member from the capture | `gate-m0-link.log` |
| slim installer (`--artifacts`), new checker | exit 0, both verdict lines (0, 0) before `fetching manifest`; narrowed carrier exit 1, 330 violations, no profile | `slim/run.txt` |
| gate-m17 (scratch copy, the same two re-points) | **GREEN 47 PASS / 0 FAIL**, CAUGHT none; install-time verdicts 0 and 0. NeoForm's game was rebuilt again (the seed is the stage's install, whose stamp still carries ` tools=`), fetching NeoForge build tools from maven.neoforged.net as in the first run | `gate-m17-scratch.out` |
| gate-m0 (lane env, ports 46840+) | **18 PASS / 1 FAIL**: the FAIL is step 2's 3 LiquidBounce skips (D58), nothing else; step 4 four PASS incl. the new "every reference was judged"; step 6 controls ran (2 classes, 12 tests), installerTest 9 classes 0 failed, test-bundled-checker 4/4 green | `gate-m0.out` |
| full suite (`-Pforbric.requireFixtures=all jar cleanTest test --continue`) | **5963 tests / 0 skipped / 5 failed**, name for name `c5/failures-w2d.txt`: 0 new, 0 gone. +1 = `aNestMemberTheDefinedHostListsIsANestmate` | `suite.log`, `suite-failures.txt` |
| independence: archive of `9228ea1d` without forbric-loader/ and fabric-loader/ | kernel `check`: 76 failures, name for name the 76 at `495e48fc` (lib.sh's forbric-loader default, Stage 5); its installerTest 9 classes 0 failed; installer `jar` 0, `devToolsJar` 0 with the checker | `indep.out`, `indep-tip-failures.txt` |
| installer tests | `installerTest` 9 classes (8 + InstallerTestsRunnerTest), 0 failed | `installerTest-1.log` |

### For the other lanes (changes since the first record)

- **INST.** `m17-scratch-repoints.diff` is unchanged and still enough. A new installer `*Test` must have
  `public static void main(String[])` or `installerTest` fails (NO MAIN); the tests compile against main's classes,
  not its resources. `-x buildLinkCheck` needs a `linkCheckJar` built from the same sources (the re-pointed m17 build
  step builds it first).
- **CI.** Unchanged: call `installerTest` instead of `linkGateTest` in build.yml. The ci-unstaged rows are in the file.
- **REL.** Only push-and-run.py's "tools digest" docstring (line 205 at `962ac3bd`) is left.
- **DOCS.** New names: `Check.KIND` / `INCOMPATIBLE`, `GameLinkCheck --minecraft`, the `Forbric-LinkCheck-Sources`
  manifest attribute, `compileInstallerTests`, `InstallerTestsRunnerTest`; the breakdown line has `incompatible N`.
- **Orchestrator.** The plan's Stage 5 list names `buildMergeTools`/`bundleTools`/`linkBaseline`, already deleted.

### Not done / open after the review round

| item | owner |
|---|---|
| fn5: a class missing from a package no judged input defines is out of scope, not judged | design limit, recorded |
| Mixin's other additions to a target (beyond helpers' nest membership) are not in the game view | design limit, recorded |
| A Java 21 link-check JVM against Java 25 classes: not measured | recorded (review A) |
| Install time judges without the Minecraft libraries (894 + 148 unresolvable) | as in the first record |
