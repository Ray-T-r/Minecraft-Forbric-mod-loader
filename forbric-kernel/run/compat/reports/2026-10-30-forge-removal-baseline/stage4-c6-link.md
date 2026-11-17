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
| Installer version (scout lists `installer-version-vs-old-install-message` in LINK): not bumped, per the brief | owner (question 4) |
| gate-m17 re-points and the merge-tools tools-jar clean-up | INST |
| build.yml and the TSV rows | CI |
| RELEASE_ROLES `merge-tools`; push-and-run docstring | REL |
| Install time judges without the Minecraft libraries (894 + 148 references unresolvable through library supertypes). The supplied-set check runs before the base version is read, so the library paths are not known there | LINK/INST, a later round if wanted |
| The game check sees the first COREMOD pair only; the rest of the chain and Mixin are mod-dependent and unchecked statically. Helpers come from a dedicated-server capture with fabric-api only (client-only helpers are not captured) | design limit, recorded |
| `forbric-loader/`'s MergedLinkChecker, test-link-check.sh and link baseline stay until Stage 5 deletes the tree; nothing outside it uses them once INST re-points m17 | Stage 5 |
| Bootstrap reported `forbric-kernel/build/sweep100-mac` dangling (its target is missing in the main checkout too) | harness / orchestrator |
