# Forbric — architecture and internals

English | [简体中文](introduction.zh-CN.md)

For mod and loader developers. This document is precise rather than gentle: it states what Forbric does, in the
order it does it, naming the real types and files. It describes the **`main` branch**, not a release; for what a
release contains and how a player installs it, read the [README](README.md).

> **Which code this describes.** The repository holds two generations. `forbric-kernel/` — the *sovereign
> kernel* — is what `main` ships and what `forbric-kernel-installer/` installs; it is the subject of this document.
> `forbric-loader/` is the first generation (the *weld*: real fabric-loader/Knot as host, with FML and
> FancyModLoader driven alongside it). It no longer runs in an installed instance, but it still builds the tools
> and staged artifacts the kernel is built and tested against — see [§14](#14-what-forbric-loader-is-still-for).

Terminology:

| Term | Meaning here |
| --- | --- |
| **ecosystem** | Fabric, traditional MinecraftForge, NeoForge — `net.forbric.api.Ecosystem.FABRIC` / `FORGE` / `NEOFORGE` |
| **Forge family** | MinecraftForge and NeoForge jointly. Two runtimes, two manifests (`META-INF/mods.toml`, `META-INF/neoforge.mods.toml`), two event buses |
| **merged base** | `patched-mc-merged-26.2.jar`: Minecraft 26.2 carrying both Forge families' patches, byte-merged into one jar; in a method both families patched, both families' edits are kept in one body wherever that can be proved (§2.4) |
| **carrier** | one Forge family's runtime jar (`neoforge-runtime.jar`, `forge-runtime-interop.jar`), loaded as a passive ABI provider — its classes exist, its loader lifecycle never runs |
| **boot side / game side** | code loaded by the system class loader vs. code defined by `ForbricClassLoader` |
| **guest** | anything belonging to a third-party mod (guest mixin, guest jar) |
| **native** | what a mod gets on its own loader: vanilla 26.2 under Fabric Loader for a Fabric mod, MinecraftForge's or NeoForge's own patched 26.2 for theirs. Every repair is judged against it (§16.1) |

---

## 1. The problem

Three loaders assume they own the process. Running their mods together breaks in five independent ways, and
every part of the kernel answers one of them.

1. **Launch ownership.** Fabric starts Knot; MinecraftForge and NeoForge start ModLauncher/BootstrapLauncher with
   a JPMS module layer. Two transforming class loaders means two definitions of every game class.
2. **One class, three patch sets.** Both Forge families patch the *same* `net.minecraft` classes with different
   hooks, and a JVM can hold one version of each class. Something has to put both families' edits into one body
   where it can, decide per method where it cannot — and then live with what the loser's mods expect.
3. **Lifecycles and registration windows.** Each ecosystem has its own phases, its own bus, its own window in
   which registries are writable, and its own idea of when the freeze happens.
4. **Visibility.** Each loader keeps its own mod list. A mod asking its own loader "is Sodium installed?" or "which
   platform am I on?" gets an answer that is true for a single-loader instance and wrong here.
5. **Guest bytecode written for a different game.** A Fabric mixin was written against vanilla bytecode; a
   MinecraftForge mod against MinecraftForge's patched game. On the merged base, anchors have moved, methods have
   been split, fields re-typed, lambdas renumbered, superclasses swapped.

Namespace is *not* on this list for 26.2: the game ships Mojmap names, Forge-family 26.2 mods are Mojmap-compiled,
and so are Fabric's (`KernelMappingResolver`'s javadoc records a constant-pool scan of fabric-api and Jade finding
no intermediary symbols). The kernel runs identity mapping: `TransformContext(…, "named")`, and
`KernelMappingResolver` answers every lookup with its input.

Nor are mod APIs. Forbric does not re-implement the Fabric API, MinecraftForge or NeoForge APIs: a mod calls the
genuine Fabric API mod it installed, and the genuine MinecraftForge/NeoForge classes from the carriers. What the
kernel owns is the part a loader owns — class loading, discovery, the lifecycle, the registration window, and
Fabric Loader's own API (§5.1) — plus the repairs the merge makes necessary. Two of those repairs reproduce
behaviour instead of calling it: NeoForge's coremod rewrites, which the kernel performs itself (§5.2), and events
whose hook lost the merge, which it re-emits (§8).

## 2. Shape of the solution

One JVM, one transforming class loader, one lifecycle, one registry freeze. No genuine loader lifecycle ever
boots: there is no Knot, no ModLauncher, no FancyModLoader discovery, no module layer.

```
system class loader  (BOOT side)
 ├─ forbric-kernel.jar            net.forbric.kernel.{boot,classloading,discovery,fabric,transform,mixin,
 │                                 access,metadata,mapping,interop,ui,util,soak}, net.forbric.api,
 │                                 vendored net.fabricmc.api / net.fabricmc.loader surface
 └─ its dependencies               ASM, sponge-mixin, SAT4J, NightConfig, tiny-remapper, class-tweaker, mapping-io
     │  new ForbricClassLoader(owned, parent)
     ▼
ForbricClassLoader  (GAME side — the only loader that defines game/ecosystem classes)
 owned jars, in this order (KernelOwnedClasspath.compose):
   1. patched-mc-merged-26.2.jar           --gameJar
   2. forge-runtime-interop.jar,            --runtimeJar   (the two carriers)
      neoforge-runtime.jar
   3. Minecraft's own libraries             --libraryPath  (owned: mods mixin into DataFixerUpper & co.)
   4. kernel-bundled: mixinextras-fabric.jar, forbric-kernel-runtime.jar   (extracted to .forbric-kernel/lib/)
   5. guest mod jars: Forge-family, then Fabric, nested jars included; newest version first within one mod id
```

### 2.1 Boot side vs game side

`forbric-kernel.jar` is parent-loaded and names no game type. Everything that references `net.minecraft.*`,
`net.minecraftforge.*`, `net.neoforged.*` or `net.fabricmc.fabric.*` lives in `src/runtime/java`
(package `net.forbric.kernel.runtime` and its `soak`/`transfer` subpackages, 159 files), is compiled `compileOnly` against the staged game artifacts,
packaged as `forbric-kernel-runtime.jar`, nested at `META-INF/jars/` inside the boot jar, extracted at launch by
`KernelBundledJars` and loaded as an owned jar.

The boot side reaches the game side by *string* (`Class.forName`, `getMethod`, ASM owner names).
`KernelRuntimeClasses` is the registry of every such string, and `KernelRuntimeClasses.verify(loader)` loads the
whole seam through the finished pipeline at boot (after the Fabric `preLaunch` entrypoints, §3.2 step 20), so a boot
jar built without its game half, or a renamed game-side method, fails before the game starts rather than in the
middle of a mod's construction. Everything on the game side is compiled: the one class that used to be emitted at
runtime (`KernelHudLayer`, for the Fabric HUD bridge, §8) was retired with that bridge.

### 2.2 `ForbricClassLoader` and the delegation table

`classloading.ForbricClassLoader` is a flat, JPMS-free `URLClassLoader`. `loadClass` consults
`DelegationPolicy`, in order:

- **ALWAYS_PARENT** — the JDK, ASM (`org.objectweb.asm.`), Mixin (`org.spongepowered.asm.`), log4j/slf4j,
  NightConfig (a carrier bundles an old unshaded copy that would otherwise win child-first),
  `net.fabricmc.api.`, `net.fabricmc.loader.api.`, the Fabric Loader internals in `FabricLoaderInternals` (by exact
  name), `net.forbric.api.`, and the kernel's boot packages (`boot`, `classloading`, `transform`, `mixin`, `access`,
  `mapping`, `metadata`, `discovery`, `fabric`, `util`, `interop`). Exactly one copy per JVM.
- **ALWAYS_GAME** — the platform's own packages: `net.minecraft.`, `com.mojang.blaze3d.`, `net.minecraftforge.`,
  `net.neoforged.`, `net.forbric.kernel.runtime.`, `com.llamalad7.mixinextras.` (bundled by the kernel) and Mixin's
  synthetic package. Defined here or not at all. No mod's package is listed, fabric-api's included: its classes are
  defined here by the child-first rule below because the loader owns its jar, and `MixinFit`, which asks this table
  whether a target is the platform's or another mod's, judges a mixin into fabric-api as one into any other mod.
- **otherwise child-first**: defined here if an owned jar has it, else the parent.

`tryDefineGameClass` reads the bytes, runs the pre-Mixin chain (`setTransformer`), then the Mixin stage
(`setMixinTransformer`), then `defineClass` with a real `ProtectionDomain` (the jar as code source — mods such as
JourneyMap and spark locate their own jar through it). Other duties of the loader:

- **Pre-Mixin bytes.** `getPreMixinClassBytes` serves Mixin the post-chain, pre-weave bytes; serving woven bytes
  would make the weaver re-weave its output.
- **Generated classes.** `putGeneratedClass` holds classes synthesized by transformers (class-tweaker enum
  extensions); a `null` read with no generated entry is Mixin's cue to synthesize its own.
- **Rescue jars.** `setRescueJars` — the jars cross-jar arbitration superseded — are consulted *only* when no
  owned jar has the class, so they cannot shadow the winner (§4.3).
- **Jar families.** `setJarFamilies` records which ecosystem each mod jar was arbitrated to; `familyOfClass` /
  `familyOfResource` feed the environment stripper and the loader-probe rewriter.
- **Re-entrant definition.** A guest config plugin constructed during Mixin's first `select()` can load the class
  currently being defined; `define` recovers the already-completed definition instead of failing with a
  duplicate-definition `LinkageError`.
- **Package manifests.** Packages are defined with the owning jar's manifest attributes, because genuine FML reads
  `Package.getImplementationVersion()`.

### 2.3 The three game artifacts

None of them is in the repository or in any Forbric download: each embeds Mojang, MinecraftForge or NeoForge
code. They are built on the machine that runs them — by the installer for players (§13), by
`forbric-loader/run/` scripts for developers (§14).

| Artifact | What it is |
| --- | --- |
| `patched-mc-merged-26.2.jar` | vanilla 26.2 + MinecraftForge-patched 26.2 + NeoForge-patched 26.2, byte-merged by `net.forbric.tools.MergedBaseBuilder`. NeoForge's classes are the base and Forge's are spliced in; in a method both families patched, both families' edits are kept in one body where the edit splice can prove it (§2.4). The report a build writes beside the jar (`merge-conflicts.txt`) reads, for the base the current tools build, `forge=184 neo=10150 MERGED=634` classes, `edit splice: methods=240 runs carried=350 already-present=85 conflicting=256 refused=201` and `CONFLICTS: methods=921 fields=8 STRUCTURAL(superclass/field)=19`. The jar also carries each platform's own classes, for reading only: `META-INF/forbric/native-reference/<FABRIC\|FORGE\|NEOFORGE>/` holds an index of every class of that platform's game by SHA-256 and the bytes of each one whose merged copy differs, never defined (`NativeGameReferences`; the native body of §7.4) |
| `neoforge-runtime.jar` | NeoForge's `-universal` jar plus the libraries its userdev config declares, merged into one jar (`NeoForgeRuntimeBuilder`) |
| `forge-runtime-interop.jar` | MinecraftForge's `-universal` jar plus its runtime libraries (`ForgeRuntimeBuilder`), then patched by `net.forbric.tools.RuntimeInteropPatcher` for interfaces the merge widened on NeoForge's behalf that Forge's own compiled implementations no longer satisfy. Staged under the coordinate `net.forbric:forge-runtime` |

The carriers are *passive*: their classes are defined and their event buses used, but their loaders'
discovery, sorting and lifecycle never run. The kernel supplies only the identity their code queries
(`PassiveSeeder`, §3.2).

### 2.4 The edit splice — both families' edits in one body

Where both Forge families patched one method, the merge used to keep one family's body and record the other's hook
as lost. `EditSplicer` (in `forbric-loader/src/tools`) keeps both edits instead, from a fact the merge could not see
before:

- **Provenance.** Both patched games are recompiled from patched sources and keep their `LineNumberTable`, so every
  instruction carries the *patched* source line it came from. Each family's userdev artifact ships the patches that
  produced those sources (MinecraftForge: 620 `.java.patch` files, plain unified diff; NeoForge: 881, DiffPatch's
  `+_` form). `PatchLineProvenance` walks a patch and turns a patched line back into "vanilla line N" or "added by
  this edit block". So each body splits exactly into the vanilla code the family left alone and the runs of
  instructions its patch added — from the patch author's own record, not inferred from a diff of two compiles.
- **Placement.** The vanilla parts of the two bodies are two compiles of the same statements and align instruction
  by instruction. Each added run of the other family then has a place in the base body: just before the base's
  counterpart of the vanilla instruction that follows it. At an insertion point both families use, MinecraftForge's
  run goes first.
- **What may be carried.** A run is carried only when it starts and ends at a statement boundary (empty operand stack)
  on both sides; every jump out of it lands on its anchor or on an aligned vanilla instruction, nothing jumps into its
  middle, and the exception regions covering it are the same in its own body and where it is inserted; every local
  it reads holds what it holds in its own body (a local of its own gets a fresh slot); every base instruction that
  can reach it has a counterpart that reaches it in its own body; and something in the result reaches it. An
  insertion is carried statement by statement, so a statement the base already inserted at the same point runs once.
- **What is a conflict.** A run that replaced vanilla lines replaces the base's copy only when the base left those
  lines vanilla and none of the base's code feeds them. When the base changed the same lines, identical edits are
  kept once, and different ones are a conflict: the base's edit stands and the report names what was lost and what
  was kept (`both-changed`). When both patches added a member of one name and both edits of the method act on it, or
  both dropped the same vanilla code and put their own in its place, the families reimplemented one feature and
  running both would run it twice: a conflict too (`both-reimplemented`). Runs that belong together go together or
  not at all — the copies of one `finally` block, a begin/end pair on one object, a statement and the one reading what
  it computed, a removal and the moved statement relying on it — and a run whose partner cannot be carried is
  withheld and the method planned again without it.
- **Honesty checks.** Every spliced method is verified (`SimpleVerifier` over the merge's declaration graph, frames
  recomputed); one that fails is refused whole and keeps the arbitration it had before. Then `MergedBaseBuilder`
  checks what only the whole class shows and merges the class again until nothing changes: a carried unit that reads,
  directly or through the methods it calls, a private field that nothing able to run writes is withheld (it would be
  a `NullPointerException` waiting in the merged game); every method handle the class holds into itself must match
  the kind of method it names, and base-side code must capture the base's own lambda — a class whose splices
  introduce such a finding has all of them refused. A lambda both families compile as one vanilla lambda, captured
  at the same vanilla place, stays where the per-method merge put it; a lambda body the realignment displaced, whose
  edit then no longer runs, is written as that family's lost hook (its own row form) instead of disappearing from the
  count. No class, method or mod name decides anything.
- **The report.** The `=== edit splice` section of `merge-conflicts.txt` lists each method `SPLICED` or `NOT SPLICED
  (reason)`, then per run: `+` carried, `=` kept once or a conflict (with the calls lost and kept), `x` refused, with
  why.
- **The loss ratchet.** `LostHookAttribution.platformCensus` counts every occurrence of a platform call a patched game
  makes that the merged base no longer makes in the same caller. The splice took that from 794 to 409 on the
  MinecraftForge side and 16 to 15 on the NeoForge side; the honesty checks then gave back what had been carried
  wrongly (hooks that used to fire with their answer dropped are now counted as lost) and the count stands at 508 and
  15. `MergedBaseHookLossRatchetTest` holds the merge to those ceilings — a merge-tool change may lower them, never
  raise them — and checks that every hook-lost row names a method all three games have and a loss the base still has,
  and that no caller goes unobserved. It reads a base only together with the build record `build-merged-base.sh`
  writes beside it (`patched-mc-merged-26.2.jar.provenance.json`, which names jar and report by hash); CI builds such a
  base from the prepared inputs and runs it (§16).
- **A call into one side's delegate twin** (`DelegateTwinRoutes`, the builder's PASS 6). A family sometimes gives a
  vanilla method `C(args)` a mode: its own `C(args, extra)` holds the body, `C(args)` forwards with constants, and one
  caller passes another constant because the same family does the switched-off part in its own version of a caller
  of that caller. Where the other family's `C` won the merge, that caller — taken whole from the first family, which
  the other left vanilla — would skip the kept `C` and every hook in it, and leave the switched-off part to a
  compensation the merge dropped. Such a call is pointed back at `C(args)` when that compensation is gone: no merged
  caller keeps all of the first family's edit of it, and no part of that edit an edit splice carried into it does
  what the mode switches off (each carried call is followed into the void calls its body makes, two deep, on that
  family and its runtime, and compared with the calls the twin makes under a test of the mode parameter the site
  changes, followed through every declaration that parameter is handed to; when that part cannot be read and some of
  the edit was carried, the call is left, for judgment). On 26.2 exactly one call moves: `Block.playerDestroy` called
  MinecraftForge's `dropResources(…, false)` while the merged `ServerPlayerGameMode.destroyBlock` is NeoForge's (which
  leaves the experience to `handleBlockDrops`, where MinecraftForge's popped it itself), so a player's break popped no
  experience and never posted `BlockDropsEvent`. The merged `destroyBlock` carries MinecraftForge's
  `onBlockStartBreak` and `onPlayerDestroyItem` but not its `popExperience`, and the switched-off part is
  `ForgeHooks.dropXpForBlock` and the `popExperience` it makes, so the call now reaches NeoForge's `dropResources`. The
  other 21 candidates are left; each decision and its reason is in the report's `=== delegate-twin routes` section.

Keeping both edits has a cost the kernel pays at run time: an edit of the other family can switch on a mechanism of
that family's loader that never ran before. The merged `Bootstrap` now also runs MinecraftForge's own vanilla snapshot,
which locks MinecraftForge's registry wrappers (the registration window opens that lock too, §3.4); the merged
`Main.main` and `Minecraft.<init>` keep MinecraftForge's own loader calls (redirected or emptied, §3.3); and a method
that now calls both families' hooks posts the MinecraftForge event natively, so every bridge forwarding it must stand
down there (§8.1).

The copy committed at `forbric-loader/run/merged-base/merge-conflicts.txt` is the report written beside the merged
base `forbric-loader/run` holds, which the current tools (edit splice included) built, so a staged root made from that
directory pairs the base with its own report. `LostHookAttributionTest` reads every hook-lost row of it back as
written, without pinning which methods it lists or how many.

## 3. Boot order

### 3.1 Entry

`boot.KernelClientLaunch.main` and `boot.KernelServerLaunch.main` are one line each:

```java
int code = CompatibilityLaunchBoundary.run(() -> KernelBoot.launch(KernelBoot.Side.CLIENT, args));
if (code != 0) System.exit(code);
```

`CompatibilityLaunchBoundary` is the only place a compatibility refusal becomes a process exit (code `78`,
§12.4), and the only place a refused install does (code `2`, §3.2 step 0). Any other throwable leaving the boot is
logged there — message and trace, into `latest.log` — before it is rethrown, because a launcher shows that file and
not stderr. `KernelBoot.launch` consumes `--gameJar`, `--runtimeJar` (repeatable, and one value may carry several jars
joined by the path separator — launchers such as PCL2 keep only the last occurrence of a repeated flag) and
`--libraryPath`; everything else, and everything after `--`, is forwarded to the game's `Main.main`. The dedicated
server rejects `--gameDir`, so `KernelBoot` strips it on that side. The game version is read from the base jar's
`version.json` (fallback `26.2`).

### 3.2 `KernelBoot.launch`, in order

0. **Launch inputs** — `LaunchInputCheck.require(gameJars, runtimeJars)`, by content and before anything is read
   out of them: the base's `Block` must implement an extension interface from both Forge families (the merged base),
   every `--runtimeJar` must carry one family completely (loader SPI, `ModContainer`, `FMLLoader`, `FMLEnvironment`,
   its own `mods.toml`; a jar with only the `mods.toml` is reported as one of that family's mods), and both families
   must be carried by something the launch owns. A failure logs each problem and the fix (rerun the installer with
   *Built artifacts* empty) and stops with exit code `2`; `-Dforbric.launchInputCheck=off` only warns. Issue #13:
   empty "runtime" jars used to pass every later step with an empty answer and die in `KernelRuntimeClasses.verify`
   on stderr, leaving five INFO lines in `latest.log`. The check reads names, not every class; the class javadoc lists
   what it leaves to later steps.
1. **Cross-jar arbitration pre-scan** — `DuplicateModArbiter.arbitrate(mods/, envType)` inventories every root and
   nested candidate and fixes one selection before either ecosystem's discovery runs (§4.3).
2. **Carrier versions** — `EcosystemVersions.record(runtimeJars)`, so a mod whose `versionRange` the carriers
   cannot satisfy is reported as it is discovered.
3. **Forge-family discovery** — every `mods/*.jar` with a Forge-family manifest, then the JarJar children
   (`META-INF/jarjar/`) the arbitration plan selected, taken from the plan's content-addressed extraction under
   `.forbric-kernel/candidates/` (the legacy extractor, writing `.forbric-kernel/jarjar/`, runs only when
   arbitration is off).
4. **Presence** — `ModPresence.publishForgeFamily(…)` before the Fabric side is built, because the Fabric side
   reads it back.
5. **Fabric discovery** — `KernelFabricEcosystem.scan`, then a second arbitration pass over the union of nested
   jars (`DuplicateModArbiter.arbitrateNested`); losers are dropped from both lists.
6. **Mixin config declarations** — Forge-family configs from mod jars, nested mod jars *and the carriers*
   (NeoForge's own `neoforge.mixins.json`).
7. **Fabric ecosystem build** — `KernelFabricEcosystem.build` creates the `FabricLoader` view (§5.1).
8. **Owned classpath** — `KernelOwnedClasspath.compose` (order in §2).
9. **Static audits** over every guest jar, before anything is loaded: `PortingLayerAudit` (Fabric jars shipping
   their own `net.neoforged.*`/`net.minecraftforge.*`), `FabricApiModuleLossAudit`, `FieldDriftAudit`,
   `MergedBaseUncalledMethods.scanGuests`, `AbiLinkAudit` (Forge-family classes named by a jar that exist in no
   carrier, base or installed jar).
10. **Class loader** — `new ForbricClassLoader(owned, bootLoader)`, rescue jars, jar families,
    `LoaderProbePolicy.bindGuestLoader`, `KernelFabricLauncher.install` (a mod's `addToClassPath` lands here).
11. **Transform chain** — 131 `chain.register(…)` call sites (§6).
12. `loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, ctx))`; the context class loader
    becomes the game loader;
    `KernelLifecycle`, `KernelHudBridge`, `LootTableEventDispatch` are bound; `KernelLoadReport` and
    `CrashAttribution` get the run directory (and their shutdown hooks).
13. **Loader identity, before Mixin** — `PassiveSeeder.seedNeoForgePaths`, `seedNeoForgeLoader`,
    `seedForgeFmlLoader`, `publishForgeLoadingList`. This must precede the first class that passes through the
    Mixin transformer, because Mixin constructs every guest `IMixinConfigPlugin` then, and plugins read
    `FMLPaths`/`FMLLoader` in their `<clinit>`. MinecraftForge's `FMLEnvironment` caches `dist` into
    `static final` fields in a one-shot `<clinit>`; whoever touches it first decides it forever. Then
    `KernelFabricEcosystem.constructLanguageAdapters()`: every language adapter a Fabric mod declares is built
    here, as Fabric Loader's `freeze()` builds them before `FabricMixinBootstrap.init` (§5.1).
14. **Mixin** — Fabric configs first, Forge-family configs appended (§7.1); `MixinConfigOwners.publish` before
    registration; `KernelMixinBootstrap.init`.
15. `PassiveSeeder.reportDependencies()` — after Mixin, because half of what it reports (mixins meant for another
    mod that did not attach) is recorded while Mixin parses configs.
16. `PassiveSeeder.seedAll` — NeoForge `FMLLoader`, `ModList`, paths; MinecraftForge identity (idempotent).
17. Audit reports (among them `MixinOverlapLint`, §7.6), `KernelLoadReport.writeEvidence()`, then
    `CompatibilityDecision.requireContinuation(isClient)` — the pre-game decision point (§12.4).
18. `NeoGraphicsBootstrappers.run` — NeoForge's `GraphicsBootstrapper` services (a provider listed in
    `META-INF/services/…earlywindow.GraphicsBootstrapper` of a top-level `mods/` jar loaded as NeoForge, the SPI's own
    rule), run where genuine FML runs them: first, before the game makes its window. Sodium's NeoForge build keeps
    its pre-launch checks, graphics-adapter probe and driver workarounds there. A provider that throws costs its jar
    (ERROR, every mod of that jar FAILED), not the launch; `-Dforbric.graphicsBootstrappers=off`. NeoForge's
    `FMLConfig` says `earlyWindowControl=false`, as a genuine NeoForge with the early window off would: Forbric has
    no early window.
19. `KernelFabricEcosystem.runPreLaunch()` — Fabric `preLaunch` entrypoints, after Mixin, before any game class.
20. `KernelRuntimeClasses.verify(loader)` — the kernel's own game side, through the finished pipeline. After
    `preLaunch`, because proving a class links resolves every type its public methods name and so defined about 40
    Minecraft classes; a mod that registers a mixin config from `preLaunch`, as Fabric allows, then found its targets
    already defined (`MixinTargetAlreadyLoadedException` for Essential).
21. Load the entry class (`net.minecraft.server.dedicated.DedicatedServer` / `…client.gui.screens.TitleScreen`
    is the census landmark; the invoked class is the game's `Main`). If `LifecycleHookInjector` did not find its
    trigger, **the kernel refuses to boot** (`missedRequiredExcision()`).
22. `Main.main(gameArgs)` — vanilla boot, with the genuine loader trigger redirected.

### 3.3 The redirected trigger

NeoForge won the merge of both entry points. `transform.LifecycleHookInjector` retargets (owner + name, same
descriptor) one `invokestatic` in each:

| Side | Genuine call in the merged base | Now calls |
| --- | --- | --- |
| server | `net.neoforged.neoforge.server.loading.ServerModLoader.load(Z)V` in `net.minecraft.server.Main.main`, after `Bootstrap.bootStrap()`; the edit-spliced base also keeps MinecraftForge's `if (!initSettings) ServerModLoader.load()` in front of it | `KernelLifecycle.onServerModLoading(boolean)` / `onServerModLoadingNoArg()`: each form is redirected, the kernel's window opens once per process (`KernelLifecycle.openWindow`), at whichever comes first, followed once by Fabric's `Hooks.startServer(null, null)` marker (`-Dforbric.fabricHooks=off` omits it) |
| client | `net.neoforged.neoforge.client.loading.ClientModLoader.begin()V` in `net.minecraft.client.main.Main.main`, after `Bootstrap.validate()`, before `new Minecraft` | `KernelLifecycle.onClientModLoading()` |

The client's later calls into both families' `ClientModLoader` (`finish`, `completeModLoading`) are stubbed by
`MethodBodyNeuter`; `setupModResourcePacks` is redirected to `KernelLifecycle.onClientResourcePacks` instead
(§9.2). What the edit-spliced base keeps of MinecraftForge's own loader is handled the same way, each where a kept
call would otherwise run it: MinecraftForge's `ClientModLoader.begin(Minecraft, PackRepository,
ReloadableResourceManager)` in `Minecraft.<init>` (its body *is* the MinecraftForge lifecycle) is emptied; its
biome-modifier pass (kept in `initServer`) and its mod-pack finder (kept in `configurePackRepository`) are emptied on
every side, because their work rides inside NeoForge's pass and the kernel's own packs (§9); and its
`ImmediateWindowHandler` is given FML's own no-early-window provider before the game starts, because the kept
`Window.<init>` guard dereferences it.

On the client the same injector also makes `Main.logEarlyException` call `KernelLifecycle.onEarlyStartupFailure`
first. That is vanilla's handler for the first three steps of `Main.main` (detecting the version, building and
running the argument parser): it prints to stderr and `main` exits (249, 252, 251) without throwing, so without the
hook the error that ended the game never reached `latest.log`.

### 3.4 The native registration window — `KernelLifecycle.driveNativeRegistration`

Both sides run the same steps (comments in the source number them):

- **0** seed MinecraftForge's `LoadingModList`; wire Forge's `LogicalSidedProvider` executors; on the client, load
  each carrier's built-in translations (`CarrierLanguages`).
- **1** register NeoForge's baseline registries (`PassiveSeeder.seedNeoForgeRegistries`); apply NeoForge's own
  registry modifications (sync flags, callbacks).
- **2** `registerNeoForgeContent` — the window itself:
  1. construct `NeoForgeMod` on a kernel-made bus and container (`KernelModContainerFactory`);
  2. construct every Forge-family `@Mod` (`KernelModLoader.constructMods`, §5.2/5.3);
  3. register guest `@EventBusSubscriber` classes (`KernelEventSubscribers.registerAll`);
  4. post `FMLConstructModEvent` to both families; inject MinecraftForge capabilities;
  5. NeoForge `GameData.vanillaSnapshot`, then **unfreeze** (`openRegistrationWindow`: every gate between a
     registration call and its registry, whoever closed it — vanilla's `frozen` flag, and MinecraftForge's wrapper
     lock and frozen `ForgeRegistry`, which the edit-spliced `Bootstrap` sets because it also runs MinecraftForge's own
     vanilla snapshot; step 9 puts back what it found closed); post `NewRegistryEvent`;
  6. fire `RegisterEvent` per registry on every bus, in NeoForge's registration order; then the MinecraftForge
     baseline (`KernelForgeBaseline.register`);
  7. open `minecraft:root` and run Fabric `main` entrypoints (on the client only when
     `-Dforbric.fabricMainInConstructor=off`; see §3.5);
  8. attribute events, spawn placements, `BlockEntityTypeAddBlocksEvent`, modded game-rule categories, NeoForge's
     tooltip appenders (`DefaultAttributes` is initialised before MinecraftForge's attribute events walk the
     entity-type registry: a mod that registers entity types from that class's initialiser made the walk throw
     `ConcurrentModificationException`, the event never finished, and no entity got MinecraftForge's attributes);
  9. `closeRegistrationWindow` (in a `finally`): link any block item still unlinked (normally none; §9.4),
     **freeze**, rebuild NeoForge's blockstate→id map, re-sort NeoForge's creative tabs.
- **2a–2c3** verify the REGISTRATION bridges; publish the NeoForge baseline in `ModList`; load STARTUP/COMMON
  (and on the client CLIENT) configs; wire both carriers' own `@EventBusSubscriber` classes; install the client
  reload-listener bridge.
- **3a** declare datapack registries (`DataPackRegistryEvent.NewRegistry`, Fabric dynamic registries mirrored both
  ways) — deferred on the client until after the Fabric entrypoints (`DatapackRegistryDeclaration`).
- **3a2** start the game buses (`startGameBuses`) — before the setup phases, because `IEventBus.post` on a bus
  that has not started returns silently.
- **3b** (server) post the setup lifecycle to both families' guest mods (`fireModSetupLifecycle`): common setup,
  dedicated-server setup, NeoForge's `RegistrationEvents.init()` one step at a time (`RegistrationEventSteps` —
  it posts `RegisterCapabilitiesEvent` and `RegisterDataMapTypesEvent`), IMC enqueue/process, load complete; deferred
  work runs between phases on the thread each family uses (`NeoDeferredWork`; failures read back by
  `DeferredWorkFailures`). Then `load-report.txt` is written and `CompatibilityDecision.requireContinuation` is asked
  again — the end-of-loading decision point. On the client every one of these phases, common setup included, is
  deferred to `onNeoClientSetup` (§3.5), because `Minecraft.getInstance()` is still null here.
- **3b2** open configs registered late (from construction or setup).
- **3c** (server) close NeoForge's payload registration phase (`setupNeoForgeNetwork`). It must come after setup:
  mods register payloads from `FMLCommonSetupEvent`.

There is one registration window and one freeze. The "Tags not bound" wall the weld hit (two ecosystems
refreezing in turn) cannot arise.

### 3.5 Client-only hooks inside `Minecraft.<init>`

Fabric and NeoForge need opposite states in the constructor, so the kernel has two anchors:

- `ClientEntrypointHookInjector` → `KernelLifecycle.onClientEntrypoints()`, before `Options` exists: reopen the
  registries (and MinecraftForge's registry gates), construct MinecraftForge mods that were held back because they
  reached for `Minecraft` too early, run Fabric `main` then `client` entrypoints (Fabric's `Hooks.startClient`
  order), re-close and re-freeze, open late CLIENT configs, then declare datapack registries. Around that freeze is
  Fabric's registry freeze point: where a Fabric mixin defers the freeze (recognised by the redirect of
  `Bootstrap`'s `bootStrap()` call that defers it, whoever declares it — fabric-registry-sync does), a Fabric mod's
  `@Inject` at the HEAD or TAIL of `BuiltInRegistries.freeze()`, or just before or after `bootStrap()`'s call of it, runs there
  (`FabricFreezeHookMixinAdapter`), where native Fabric freezes — after the entrypoints, with `Minecraft.getInstance()`
  set. LiquidBounce builds its creative tabs from such an injector; left in `Bootstrap` it found no client and died.
- `NeoClientSetupHookInjector` at the merged base's `ClientModLoader.finish()` call →
  `KernelLifecycle.onNeoClientSetup()`, after `options` is assigned: verify the CLIENT_INIT bridges, preload the
  client resource manager (MinecraftForge runs mod loading inside the first reload, and its mods expect their
  assets readable at client setup; `-Dforbric.clientResourcePreload=off`), then `fireClientSetupLifecycle`: common
  setup, client setup, `RegistrationEvents.init()`, IMC, load complete — with the registries frozen, as on genuine
  NeoForge — then `load-report.txt` and the end-of-loading decision (`requireClientContinuation`), then late
  configs, then close the payload registration phase.

## 4. Discovery and arbitration

### 4.1 Reading manifests

- `discovery.ForbricModDiscoverer` classifies a jar by descriptor — `fabric.mod.json`, `META-INF/mods.toml`,
  `META-INF/neoforge.mods.toml` — and reports *every* manifest a jar carries. Which one loads is policy (§4.2).
- `metadata.forge.ModsTomlParser` (clean-room; NightConfig for TOML lexing) with `ForgeModsToml`,
  `ForgeModEntry`, `ForgeDependency`; `ForgeVersionRangeTranslator` turns Maven ranges into Fabric-style
  predicates, so `net.forbric.api.UnifiedDependency` has one dialect (`VersionPredicate` evaluates it).
  `UnifiedDependency` also keeps the two axes Fabric has no word for: ordering (`BEFORE`/`AFTER`) and side scope.
- `fabric.FabricModMetadataParser` is the full `fabric.mod.json` v1 reader (entrypoints incl. adapter form, `jars`,
  per-side `mixins`, `accessWidener`, `custom`). `fabric.FabricModDiscovery` follows Fabric JiJ (its extraction
  cache is `.forbric-kernel/jij/`); mods whose `environment` excludes the side are skipped, as on Fabric.
- A nested Fabric mod is resolved as fabric-loader 0.19.5's `ModSolver` resolves it (`fabric.NestedFabricRequirements`,
  applied by `NestedCandidateInventory` and by `FabricModDiscovery` when there is no plan): a `depends` on `minecraft`
  or `java` that excludes the running version, or a `breaks` that includes it, leaves it out, and with it any nested
  mod that hard-depends only on left-out ones or that only they bundle. ViaFabric is the case: it nests
  `viafabric-mc26-1` (`minecraft >=26.1 <=26.1.2`) beside `viafabric-mc26-2`, and native loads only
  `viafabric-mc26-2`. (Fabric's one mod per id, `provides` included, is decided after arbitration: next item.) Only
  what a Fabric mod declares in its `jars` is judged. A NeoForge/MinecraftForge mod's nested jar is not, even if all
  it carries is a `fabric.mod.json`, because Fabric Loader never opens a jar without one. A
  child the parent's `META-INF/jarjar/metadata.json` declares is FML's, which loads it as the parent's library; a jar
  that only sits in `META-INF/jars/` or `META-INF/jarjar/` with no entry in that file is loaded by no native loader,
  and the kernel keeps it because its Forge-family walk has always taken both directories. A jar reached both ways is
  kept, whichever route the walk took first. One departure from native: when a mod the kernel loads hard-requires an
  id and nothing that loads meets the requirement, the left-out copies that meet it are kept, with whatever bundles
  them on the way to a loaded parent, and a WARN `[Forbric/JiJ] nested <id> <version> in <parent> loaded although
  Fabric Loader would leave it out (<reason>): <dependent> requires <id> <range>, and nothing else installed meets
  that` says so. When no installed copy meets it and nothing that loads provides the id at all, every left-out copy
  is kept, as the kernel loaded it before the rule, and the WARN ends `<dependent> requires <id> <range>; no installed
  build meets that, and no other <id> would load` instead. For a mod in `mods/` native refuses to start there; the
  kernel loads such a mod even with a dependency missing, so leaving the provider out would only take that dependency
  away. Every mod that stays left out gets one `[Forbric/JiJ] nested <id> <version> in <parent> left out: <reason>`
  line, including what it bundles: a left-out jar is still opened, so a loaded mod's need for something inside it is
  seen. Jars in `mods/` are never judged; `fabricloader`/`mixinextras` ranges, unreadable ranges and dependencies
  nothing installed meets do not leave anything out. Without a plan (`-Dforbric.crossJarArbitration=off`),
  `FabricModDiscovery` walks `mods/` and Fabric `jars` only, so `KernelBoot.scanFabricMods` hands it what the jars it
  does not read hard-require: the Forge-family mods, and the Fabric manifests of the jars their JarJar carries (no
  Fabric container there, but on the classpath). `-Dforbric.nestedRequirements=off` loads every nested mod again;
  `off:<id>,<id>` exempts only those ids (the kernel does not read Fabric's `config/fabric_loader_dependencies.json`).
- One Fabric mod per id, `provides` included (`fabric.FabricUniqueIds`, on the selection: `DuplicateModArbiter` after
  `ReachableCandidateSelector`, and `FabricModDiscovery` without a plan). Fabric Loader indexes every candidate under
  its id and each id it provides (`ModPrioSorter.sort`) and lets at most one of each load (`UNIQUE_ID`). A jar in
  `mods/` that alone answers to one of its ids is forced (`ROOT_FORCELOAD_SINGLE`); every other candidate is weighted
  `-2^(n - priority)`, so the choice is the lexicographic optimum over `ModPrioSorter`'s order (jars in `mods/` first,
  then by id, then the higher version, with a mod moved ahead of the ones it shares an id with and has the higher
  version against): each mod in turn loads if it can, given the ones before it, a nested one needing a parent that
  loads, every mod a provider for each hard dependency, and one whose hard dependency nothing installed meets switched
  off (`HARD_DEP_NO_CANDIDATE`). The kernel takes the same optimum with a SAT solver. owo-sentinel is the case: mods
  that need owo-lib nest it so that it can ask for owo-lib when it is missing, and it provides `owo` and `owo-lib`;
  next to owo-lib native never loads it, and the kernel loaded both. Of two nested mods that share an id the one
  Fabric orders first usually wins, but a mod ordered before both that needs the other keeps that one: a mod nested in
  it, or one that depends on it. It is decided among what arbitration selected, never before: a build arbitration
  drops (another ecosystem's preferred copy, a newer copy elsewhere, a copy only a losing library bundles) cannot push
  its rival out, and a dependency on the shared id cannot steer which build of a mod arbitration keeps. Each mod that
  loses a rivalry is then excluded, with every other build of its id that loses the same way to a jar in `mods/` (all
  owo-sentinel builds at once), and arbitration runs again only where another build of something left out (a loser,
  or what went with it) is there to take its place: one round for owo-sentinel beside owo-lib, logged `one mod per
  id: decided in <n> arbitration round(s)`. What merely went with a loser is decided afresh each round, and a mod
  that needs a loser stays out in later rounds too. Builds of one mod are never rivals, also when they share only an
  alias (every `mixinextras-fabric` copy provides `com_github_llamalad7_mixinextras`). Only Fabric Loader's own index
  takes part, the Fabric jars in `mods/` and the nested Fabric mods it reaches itself that `off:<id>` does not exempt,
  and their hard dependencies, met by what is in that index, shape the choice as on Fabric. Everything else (a
  Forge-family mod, a Fabric mod a JarJar declaration or a Forge-family walk brought in, an exempted one, a library
  without `fabric.mod.json`) loads whenever a parent of it loads, goes with its parents, never decides a rivalry and
  never meets a dependency for Fabric's choice; its own needs, which Fabric Loader never sees, are met only after
  that choice, by keeping a would-be loser back. The loser is logged `left out: it
  answers to '<id>', as <winner> <version> [in mods/] does, and Fabric Loader loads one mod per id, provides
  included`; what goes with it `left out: only mods left out bundle it`, and a mod whose dependency only the loser met
  `left out: it requires <id> <range>, and every installed build of that is left out`. Where the kernel stays more
  lenient than native: jars in `mods/` all load (native drops one that no id of its own singles out when a rival
  takes its ids, e.g. Indium next to a Sodium that provides `indium`, and refuses to start when two forced jars
  collide); a nested mod whose hard dependency nothing that loads meets is taken as switched off for native's choice,
  and then comes back, with what goes with it, wherever it takes nothing native loads, so it yields every id it shares
  and still loads where nothing contests it; and a Fabric jar in `mods/`, or a mod outside Fabric's index, that needs a
  would-be loser (by its id or one it provides, or a mod nested in it), with nothing else that loads meeting that,
  keeps it back with a `loaded although Fabric Loader would leave it out (<why>): <mod> requires <id> <range>, and
  nothing else that loads meets that` WARN (`it bundles <mod> (...)` for a loser kept for what it bundles), together
  with what bundles it on the way to a parent that loads and what it brings with it (its nested mods, what needs it),
  the winner loading too (for a jar in `mods/` native drops the winning jar in `mods/` instead if no id of its own
  forces it, and refuses to start otherwise; a need from outside Fabric's index native never sees). A nested Fabric
  mod Fabric judges keeps nothing back: like native, it goes itself. The build kept back is the one arbitration chose
  for its id; another installed build that would not need keeping back is not tried, and where the winner is nested
  rather than in `mods/`, each round excludes one build of the loser's id. Not weighed: `recommends` and `breaks`
  between mods, and a mod listing its own id or an alias twice among its `provides` (native never loads it). Without
  a plan nothing arbitrates: copies of one id are weighed one at a time, the first found first (the one
  `KernelFabricLoader.register` keeps), and a copy that loses a rivalry goes, with what only it bundles, and the next
  copy is weighed in its place. The
  `forbric.nestedRequirements` switches apply. On the local corpora (70 `mods/` folders) the only shapes are
  owo-sentinel beside owo-lib and voxy's `dummyprovider` (server-only, providing `sodium`, which is client-only, so
  they never meet).
- `discovery.ModAnnotationScanner` finds `@Mod` classes by bytecode descriptor; `ModFileScanner` builds a full
  `ModFileScanData` (NeoForge and MinecraftForge shapes differ: `EnumHolder` vs `EnumData`) because JEI, Jade,
  Sophisticated Core and Sodium find their plugins through `ModList.getAllScanData()`.
- `net.forbric.api.DiscoveredMod` is the one mod model.

The `--scan` mode (`boot.Main --scan --mods <dir> --report out.json`) runs discovery alone and writes
deterministic JSON; `run/diff-oracle.sh` cross-checks it against an independent Python reader of the same
manifests.

### 4.2 One jar, several manifests — `MultiLoaderArbiter`

A universal jar ships a manifest and glue class per loader. Unarbitrated, all three ecosystems initialise it.
`MultiLoaderArbiter.ownerOf(jar)` picks one by preference — default **NeoForge, MinecraftForge, Fabric**
(`-Dforbric.multiLoaderPreference=…`). Forge families first because their baselines are always present on the
merged base; NeoForge before MinecraftForge because NeoForge won most of the merge. A jar that carries a leftover
manifest without its implementation is not given to that loader. The jar stays on the classpath; only its
identity is decided.

### 4.3 Two jars, one mod id — `DuplicateModArbiter`

Merging a Fabric pack with a NeoForge pack puts two *files* under one id. The loser must come **off** the
classpath (first-URL-wins would otherwise let it shadow the winner and contribute its mixin configs).

- **Inventory** — `NestedCandidateInventory` walks every root and nested jar (depth ≤ 8, zip-bomb byte caps, no
  archive-count cap) into a graph of candidates and parent→child edges; nested jars are extracted, keyed by
  SHA-256, under `.forbric-kernel/candidates/`.
- **Selection** — `ReachableCandidateSelector` builds a whole-instance Boolean model (nested candidates exist only
  through selected parents) and solves it with SAT4J; `JointCandidateSelector` supplies the clauses: hard/soft
  dependencies, `breaks`/`incompatible` exclusions, and symbol contracts from `CandidateContractScanner` (only
  evidence strong enough to constrain selection — e.g. a mixin's required members). The search is bounded by
  work, never by a clock (`CONFLICT_BUDGET`, `VARIABLE_LIMIT`, `-Dforbric.arbitrationMaxNodes`, default 100 000),
  so the same folder selects the same jars on any machine.
- **Preferences** — top-level duplicates: `-Dforbric.dupeIdPreference`, falling back to `multiLoaderPreference`.
  Nested duplicates: `-Dforbric.nestedDupePreference`, default NeoForge, Fabric, MinecraftForge — chosen because a
  multi-loader library's per-loader builds stub out phases their loader lacks, and that order leaves the fewest
  callers talking to an empty method (the javadoc records the two cases that fixed it).
- **Overrides** — `-Dforbric.modOwner=sodium=fabric,…` or `<rundir>/forbric-mods.txt` (`<mod id> = <loader>`, one
  per line; the kernel writes a commented template the first time an instance has duplicates). The command line
  wins over the file.
- **Switched off** — `<rundir>/forbric-disabled.txt` lists jar file names in `mods/` (comments and bad lines as in
  `forbric-mods.txt`). `DisabledMods` keeps those jars out of the scan, so they never become claims, and they go
  into `Decision.suppressedJars` but never `rescueJars`; with `-Dforbric.crossJarArbitration=off` a decision
  holding only them is still cached. `load-report.txt` names them.
- **Residuals** — the losing ecosystem gets a presence-only alias so `isLoaded(id)` still answers
  (`Decision.aliases`); the other ecosystem's build of a mod that did load may lend a missing class as a last
  resort (`rescueJars`); `ArbitratedAwayClasses` measures what the losing build had that the winner lacks.
  `ArbitratedAwayDispatchers` covers the one behaviour such a build takes with it: a custom Fabric entrypoint key
  only the losing Fabric build dispatched (`EntrypointDispatchScan` reads key, type, invoked method and the
  lifecycle phase that reaches it from that build's bytecode) is dispatched by the kernel in its place, and only
  when the winner does not name the key itself. A key reached from `main`, `client` or `server` runs among that
  phase's entrypoints where the losing build's own entrypoint would have run: in Fabric Loader's order, where the
  library's id sorts (`ArbitratedAwayDispatchers.place`). A declared key the losing build holds while one of its
  queries asks for a key or type that is no constant is reported as a SUSPECTED finding instead of being guessed at.
  `ArbitratedAwayEntrypoints` covers the other direction: entrypoints the losing Fabric build offers to *other* mods
  under a key they dispatch (`modmenu` is the usual one) are declared on the library's presence alias, under its own
  id, as natively. Only keys Fabric Loader does not drive itself (never `main`, `client`, `server`, `preLaunch`: the
  winner initialised the library its own way), not one the winner already declares, not the library's own protocol,
  not a key of a framework the losing build requires; and only an entrypoint the winner can satisfy — its classes
  resolve, every member it uses on a class the winner ships exists with that descriptor, and no field it reads of a
  losing-build-only class is written only by code that never runs. What it can run is read method by method from the
  entrypoint's own methods, never through the losing build's lifecycle entry methods, which only Fabric Loader calls
  (`-Dforbric.arbitratedAwayEntrypoints=off`).
  `MergeReport` writes `.forbric-kernel/merge-report.txt` explaining each decision.
- `-Dforbric.crossJarArbitration=off` disables it entirely.

### 4.4 Answering "which loader am I on?" and "is X installed?"

- `LoaderProbePolicy` + `transform.LoaderProbeRewriter` (COREMOD phase) rewrite `Class.forName` call sites in a
  single-loader guest class so a platform probe (`FMLLoader`, `FabricLoader`) answers for the ecosystem that jar
  was arbitrated to (`-Dforbric.loaderProbes=off`).
- `net.forbric.api.ModPresence` is the cross-ecosystem answer. `transform.ForeignModPresenceInjector` ORs it into
  both families' `ModList.isLoaded`; the Fabric side registers every Forge-family mod as a presence-only container
  (identity only — no entrypoints, mixins or assets), so `FabricLoader.isModLoaded` answers; and the Forge-family
  lists are seeded with the Fabric mods (`-Dforbric.crossEcosystemPresence=off` restores single-loader answers).
  `net.forbric.api.ModIds` maps ids the ecosystems spell differently (`cloth-config` / `cloth_config`).
- A mod's own id outranks another mod's `provides` alias for it, whatever order they arrive in —
  `KernelFabricLoader.register`, `ModConstructionOrder` and `ModPresence`'s index all take every real id before any
  alias, as `DuplicateModArbiter` never lets an alias claim an id. owo-sentinel, nested in mods that need owo-lib,
  provides `owo` and depends on nothing installed, so it registered first and owo-lib itself was dropped as a
  "duplicate" (with its `owo-impl` alias). The sentinel itself is now left out next to owo-lib, as Fabric Loader's
  resolver leaves it out (one mod per id, provides included, above); the real-id order still
  decides wherever both stay, two jars in `mods/` or a nested mod kept back.
- `ModConstructionOrder` orders Forge-family construction topologically — a mod after everything it requires or
  declares `AFTER`; ties alphabetical; cycles named, not broken silently (`-Dforbric.modOrder=name` restores
  file-name order). Fabric mods initialise in Fabric Loader's own order, by mod id (`FabricLoadOrder`;
  `-Dforbric.fabricOrder=off` puts them back on the topological order).

## 5. The three ecosystems, driven by the kernel

### 5.1 Fabric — no Fabric Loader code runs

- `fabric.KernelFabricLoader` *is* the `FabricLoader` singleton: a view over kernel state, with its entrypoint
  index frozen before mod code runs. `KernelModContainer` exposes each jar through a zip `FileSystem`;
  `KernelLanguageAdapters` honours `languageAdapters` (fabric-language-kotlin); `KernelObjectShare`,
  `KernelVersion`, `KernelMappingResolver` (identity) complete the API.
- Language adapters are built before Mixin, all of them, named by an entrypoint or not (§3.2 step 13), because
  Fabric Loader's `freeze()` does: Configured Defaults copies a pack's default configs in its adapter's constructor
  and names it from no entrypoint. A constructor that throws fails its mod; it is not run again.
  `-Dforbric.eagerLanguageAdapters=off` builds each on first use instead.
- Entrypoint values resolve as Fabric's `DefaultLanguageAdapter` resolves them (`KernelLanguageAdapters.resolveDefault`,
  one implementation for an adapter-less entrypoint and for `LanguageAdapter.getDefault()`): a class, a static
  field, the one method of that name — static, or an instance method bound to a fresh instance — and `::<init>`,
  the constructor. Where Fabric refuses a value (several methods of one name, a private member) the kernel stays
  lenient: it opens the member, or takes the first static method.
- The mod-facing API is vendored under its own names: `src/main/java/net/fabricmc/api` (7 files) and
  `src/main/java/net/fabricmc/loader` (40 files: `loader.api.*`, `EntrypointException` included and thrown where
  Fabric Loader throws it, plus the narrow internals mods actually link against — `FabricLoaderImpl`,
  `ModContainerImpl`, `EntrypointStorage`, `Hooks`, `FabricLauncher`/`FabricLauncherBase` and their legacy
  `net.fabricmc.loader.launch.common` view, `DefaultLanguageAdapter`, `StringUtil`, legacy
  `net.fabricmc.loader.FabricLoader`, and the metadata surface a mod uses to register a jar it unpacks at run time
  as a Fabric mod: `ModMetadataParser`, `LoaderModMetadata`, `EntrypointMetadata`, `ModCandidateImpl`). A mixin config
  such a mod adds after the boot is held to the guest rules of §7.2 and owned by the mod whose jar declares it.
  `FabricLoaderInternals` pins those internals to the parent by exact name; `-Dforbric.fabricImpl=off` withholds them.
  The API level reported is `KernelFabricEcosystem.FABRIC_LOADER_API_LEVEL = "0.19.3"`. `FabricLoaderApiSurfaceTest`
  holds `net.fabricmc.api` and `net.fabricmc.loader.api` to Fabric Loader 0.19.3's own jar (a local launcher's copy):
  every public class, its supertypes, and every public or protected member by descriptor. The differences it accepts
  are listed in the test (the interval algebra, which no mod in the local corpus calls); a new one is red. As on
  Fabric, `getEntrypoints` builds every entry, leaves a null one out, and then throws one `EntrypointException` naming
  the first failing mod; a container's `getEntrypoint()` throws one for its own entry, except for an Error
  construction itself raised (a class that will not initialise), which leaves as it is. Unlike Fabric, the entries
  are those that can be the asked type: one that cannot is left out rather than failing the key. `getGameInstance()` answers
  the `Minecraft` instance on a client (handed over by `Hooks.startClient` before the entrypoints) and the dedicated
  server from its construction, as Fabric Loader's patch of the server entry does.
- The builtin mods are `minecraft`, `java` and `fabricloader`. As Fabric Loader reads the jars nested in its own jar
  as mods of `fabricloader` (0.19.5 nests MixinExtras), the kernel registers each kernel-bundled game-side jar that
  carries a `fabric.mod.json` as a nested mod of `fabricloader` (`KernelFabricEcosystem.registerLoaderNested`): today
  `mixinextras`, at the kernel's 0.5.4 where Fabric Loader 0.19.5 has 0.5.5. A guest copy of the same id or alias is
  left out, as Fabric Loader leaves it out, and with `-Dforbric.kernelBundledFirst=off` the guest copy answers.
  `util.PlatformIds` is the one list of ids a requirement is never held against: those three, the bundled
  `mixinextras`, and the carriers' `forge`, `neoforge`, `fml` — never an id only some installed mod provides
  (`fabric`, which older fabric-api builds provided, is a mod's like any other).
- fabric-api is a mod like any other. The kernel reaches it only through its public API — `PayloadTypeRegistry`'s
  static accessors (`FabricPayloadRegistries`, read by type: protocol, flow and the map of `TypeAndCodec`s),
  `PacketContext.get()`, `Renderer.get()`/`Renderer.register`, `RegistryEntryAddedCallback.event`,
  `ResourceCondition.CONDITION_CODEC`, a `FuelValueEvents.Context` of its own (`KernelFuelValueContext`) — or by what
  its code does (the transaction engine, a register method's backing map: `PublicApiStorage`), never by the names its
  implementation gives its classes, fields or mixins.
- Entrypoints: `preLaunch` after Mixin (§3.2 step 19); `main` inside the registration window (server) or in
  `Minecraft.<init>` (client, default); `client` in `Minecraft.<init>`; `server` on the dedicated server. Each
  mod's entrypoint is invoked in isolation — one throwing mod is recorded and skipped — with a NeoForge
  `ModContainer` for that mod active (`KernelForeignShimContext`), because a Fabric mod may hold the NeoForge build
  of a multi-loader library.
- Fabric access wideners / class tweakers run in the `ACCESS` phase (`access.ClassTweakerTransformer`);
  `@Environment` stripping in `ENV_STRIP` (`EnvironmentStripTransformer`, only for classes from jars arbitrated to
  Fabric).

### 5.2 NeoForge

- **Identity** — `PassiveSeeder` creates a current `FMLLoader`, `FMLPaths`, a `LoadingModList` and `ModList` built
  from the jars this boot selected — no discovery, no module layer, no sorting.
- **Construction** — `KernelModLoader`: a `BusBuilder` `IEventBus` plus a kernel `ModContainer`
  (`net.forbric.kernel.runtime.KernelModContainer`); constructor arguments filled by type (`IEventBus`, `Dist`,
  `ModContainer`).
- **Buses** — mod-bus events the kernel posts itself go through `KernelLifecycle.postModBusEvent`; setup phases
  through `runtime.KernelNeoSetup`; deferred work runs on the thread NeoForge runs it on (`NeoDeferredWork`).
- **Enum extensions** — `NeoEnumExtensions` feeds every jar's `META-INF/enumextensions.json` to NeoForge's own
  `RuntimeEnumExtender`; `NeoEnumExtensionInjector` is registered near the end of COREMOD (its placement is
  load-bearing: it defines FML classes at that point), and only if some mod declares an extension.
- **Coremods** — NeoForge's coremod jar is never loaded; `transform.NativeCoremodParity` performs its rewrites
  (flower pot `potted`, biome climate/effects, structure settings, `finalizeSpawn`) *after* Mixin, where NeoForge
  runs them. MinecraftForge's own field-to-getter transformer runs at the same place (§5.3).

### 5.3 MinecraftForge

- **Identity** — `PassiveSeeder.seedForgeFmlLoader` (pre-Mixin, §3.2); `ForgeLoadingListHolderInjector` makes
  `LoadingModListImpl$1LazyInit` read the kernel's published list (`net.forbric.api.ForgeLoadingList`) instead of a
  field only the genuine loader fills; `ForgeLauncherInfoInjector` answers `FMLLoader`'s three ModLauncher-backed
  methods; `ForgeBindingsLookupInjector` resolves `Bindings` without FML's module layer; `ForgeSecureJarStandIn`
  gives seeded `ModFile`s a `SecureJar` without ModLauncher.
- **Construction** — `KernelForgeModContext` manufactures the EventBus 7 `BusGroup` + `FMLModContainer` +
  `FMLJavaModLoadingContext` triple every Forge mod constructor takes. Mods that reach for `Minecraft` in the early
  window are deferred to `onClientEntrypoints`, where MinecraftForge constructs its own.
- **Loading state** — `KernelLifecycle.setForgeLoadingState` flips `loadingStateValid`; `publishForgeGatherStates`
  records `VALIDATE … LOAD_REGISTRIES` as completed so `ModLoader.hasCompletedState` tells the truth
  (`-Dforbric.forgeLoadingStates=off`).
- **Capabilities** — the merge put `Entity`/`BlockEntity`/`Level` under NeoForge's attachment hierarchy, so
  `transform.ForgeCapabilityCompositionTransformer` composes MinecraftForge's `CapabilityProvider` into those root
  types and `ForgeCapabilityTokenInjector` drives Forge's `CapabilityTokenSubclass` plugin
  (`-Dforbric.forgeCapabilities=off`). The switch turns off dispatch only: the merged base lists those three roots in
  `required-ancestor-compositions.tsv` and the loader refuses to define one without the composition's proof, so off
  still composes them, with an inert provider that fires no `AttachCapabilitiesEvent` and answers every ask empty.
  Off also registers no Forge block transfer fallback, and the transfer bridge is told so and does not look for one.
  `CapabilityUseAudit` lists the jars that use MinecraftForge capabilities and marks them DEGRADED if dispatch is off
  or the composition did not land on every root type.
- **Enum extensions** — `ForgeEnumExtensionInjector` drives MinecraftForge's own processor (unconditional; it
  declines everything unless Forge's mod list holds more than two mods).
- **Field getters** — MinecraftForge's own `FieldToMethodTransformer` (in `forge-transformers`, a ModLauncher
  transformation service the kernel never loads, like NeoForge's coremod jar) routes reads of `LiquidBlock.fluid` and
  `BucketItem.content` through `getFluid()`. MinecraftForge's `(Supplier, …)` constructors, which the merged base
  keeps, store null in the field and keep the fluid in a supplier, because the fluid is registered after the block or
  item that names it; without the rewrite every MinecraftForge liquid block threw on being placed (`onPlace` reads the
  field) and every MinecraftForge bucket emptied nothing. `NativeCoremodParity` carries MinecraftForge's rules beside
  NeoForge's, per family: MinecraftForge leaves only the getter itself reading the field (its same-descriptor check
  never holds), NeoForge every method shaped like the getter. Two conditions are this base's own: a read is routed
  only while the getter answers for an instance every constructor builds (`answersEveryConstructor`), so vanilla's
  water and lava never lose their fluid to a supplier left unset; and because the merged fields are public, as NeoForge
  declares them, a read from another class (NeoForge's own fluid code, a NeoForge mod) is routed too, judged against
  the owner's raw base class (`readGameClassesFrom`, which `KernelMixinBootstrap` hands the game classes before any
  class is woven, with or without mixin configs). Its flower pot and biome rules are NeoForge's too and already run;
  the one other field on its list, `MobEffectInstance.effect`, is left alone: that getter only returns the field, and
  native NeoForge does not route it. `-Dforbric.forgeFieldGetters=off`; `ForgeFieldGettersExecutionTest` runs the
  rewritten classes.
- **Fluid types** — a MinecraftForge mod's fluid overrides only MinecraftForge's `getFluidType()`. The merged entity
  code is NeoForge's and asks NeoForge's type, which such a fluid never gives, so `KernelFluidTypes` answered from the
  fluid's tags: for an untagged one NeoForge's empty type, air, and a body whose eye was in a fluid built
  `canDrown(true)` breathed there. `KernelForgeFluidTypeViews` now answers first for a fluid whose class has a
  MinecraftForge type of its own (the `getFluidType()` it resolves to is declared below `Fluid`): MinecraftForge's
  empty, water and lava types are NeoForge's own (NeoForge's entity code knows those by identity), any other gets one
  NeoForge `FluidType` view that puts every question NeoForge's entity and world code asks (drowning, swimming,
  pushing, putting out fire, hydration, boats, path types, sounds, `move`) to the MinecraftForge type, with its plain
  values (light, density, temperature, viscosity, rarity, name) copied. Where MinecraftForge's game answers a
  question by the fluid's tags, or never asks it, the view answers as that game does (measured against native
  MinecraftForge 26.2-65.0.1, §19): `isWaterLike` — which NeoForge's entity code reads where vanilla and MinecraftForge
  read `minecraft:water` (in water, eyes under water, a fish's movement, a zombie turning into a drowned) — is whether a
  fluid seen with the type is in that tag; `canSwim` is the type's answer or, in that tag, whether the body is under
  water (MinecraftForge starts a swim on "under water, or eyes in a type it can swim in"; NeoForge asks the type
  alone); and the fall-distance factor is 1, because MinecraftForge's game never shortens a fall by the fluid it passes
  through (only lava's own factor, in lava, which NeoForge's code asks of its lava type). Two NeoForge rules have no
  MinecraftForge counterpart and answer a view as MinecraftForge does (`ForgeFluidWaterRulesInjector` rewrites the
  `canSwimInFluidType` call in each rule's predicate to `KernelForgeFluidTypeViews.underwaterIn` / `shallowIn`, which
  leave every other type to NeoForge): a player is under water in any type it can swim in (`Player.updateIsUnderwater`;
  MinecraftForge: eyes in `minecraft:water` — it drives the underwater sounds, music and sky), and a body in such a
  type, eyes out, is in shallow water (`Entity.isInShallowWater`; MinecraftForge: water only). On the client NeoForge's
  `IClientFluidTypeExtensions.of(FluidType)` asks `KernelForgeFluidClientViews` first (`ForgeFluidViewExtensionsInjector`),
  which gives a view extensions whose fog colour is the MinecraftForge type's own: the merged client asks NeoForge alone
  for the fog colour (`ClientHooks.getFogColor`; MinecraftForge's `ForgeHooksClient.getFogColor` lost its call site), so
  a MinecraftForge fluid's fog kept the sky's colour. The fog distances and the eye overlay the merged client already
  asks MinecraftForge's extensions for itself (`ForgeHooksClient.setupFog`, `ScreenEffectRenderer`'s MinecraftForge
  branch), so those stay NeoForge's defaults on the view and nothing is applied twice. The view is not registered with
  NeoForge. Until MinecraftForge's fluid types are registered such a fluid answers by its tags, uncached.
  `-Dforbric.forgeFluidOwnType=off` goes back to the tags and leaves the extension lookup and both rules as merged.
- **Entity-fluid state** — MinecraftForge's `EntityFluidInteraction.update` records what MinecraftForge's queries
  read: whether the entity touches any fluid, the MinecraftForge type its eyes are in, a height and eye flag per type,
  and trackers by fluid tag. The merge kept NeoForge's `update` and MinecraftForge's queries but no code that writes
  their state, so `getEyeInFluidType()`, `isInFluidType()`, `isInFluidType(BiPredicate, boolean)`, `getFluidTypeHeight`
  and `getMaxHeightFluidType` answered "no fluid" for every entity in every fluid, vanilla water included — and so did
  NeoForge's no-argument `isInFluidType()`, which the merged `Entity` answers with MinecraftForge's body. GeckoLib's
  MinecraftForge build (`isInSwimmableFluid`, through `isInFluidType(BiPredicate)`), the merged client's overlay of a MinecraftForge fluid
  (`ScreenEffectRenderer` asks MinecraftForge's extension of `getEyeInFluidType()`) and `isInWaterOrSwimmable` in a
  MinecraftForge type all read it. `ForgeEntityFluidsInjector` gives the merged class a field holding a
  `KernelForgeEntityFluids.State`, made in its constructor with the tags it tracks; NeoForge's `update` calls `begin`
  first and `cell` right after each cell it reads, so the record is made over the same cells under the same guard, in
  the same order; MinecraftForge's six queries answer from it, as MinecraftForge's loop does (including that an
  all-match fails on a tracker kept from a fluid the entity has left). A Fabric or NeoForge mod's fluid holds the
  kernel's tag-derived MinecraftForge stand-in, whose client extensions describe another fluid, and is left out. The
  same record keeps MinecraftForge's tag trackers for a MinecraftForge mod's fluid: MinecraftForge decides "in water"
  and "in lava" by `minecraft:water` and `minecraft:lava`, NeoForge by its water and lava types (`isInLava()` is
  `isInFluid(LAVA_TYPE)`, a tag query goes through `getFluidTypeByTag`), so NeoForge's `isInFluid`, `isEyeInFluid` and
  `getFluidHeight` of its water or lava type also count a MinecraftForge mod's fluid in that tag. A NeoForge mod's fluid
  is decided as on NeoForge, and vanilla's and a Fabric fluid already have NeoForge's water or lava type. Registered
  before `NativeTagTrackerSourceInjector` (which keeps vanilla's tag trackers beside the type trackers for a Fabric
  fluid behaviour's tags, from the same cells), because that one proves the constructor and `update` it copies from as
  they are after this edit. The record does not ask a fluid already known to hold the kernel's stand-in again (its
  MinecraftForge getter keeps no cache for a stand-in and would derive it from the fluid's tags for every cell of every
  entity in it, every tick). The injector names the queries it matches by name and descriptor together: the
  uncalled-method census (§7.3) reads a kernel string that spells a method's bare name as a possible call of every method
  of that name, and the first version's `"isInFluid"` took EntityFluidInteraction's uncalled `isInFluid(TagKey)` out of
  `uncalled-methods.txt`. NeoForge's no-argument `isInFluidType()` now answers MinecraftForge's way — any fluid cell the
  walk reads, also one whose fluid tops out below the body — where native NeoForge's is `isInAnyFluid()` (a tracker
  with height), which says no there (measured, §19; not changed: one method, two meanings, and the only caller in the
  local corpus is a MinecraftForge mod's). `-Dforbric.forgeEntityFluids=off`.
- **A type the kernel derived claims nothing** — two places where a fluid type the kernel gave a fluid acted as a claim
  that fluid's own loader never makes. First, MinecraftForge and NeoForge each patched `Fluid.entityInside` and
  `BucketItem.playEmptySound` to ask the fluid's type, and the merge kept both clauses side by side; here every fluid
  answers both families — a NeoForge mod's fluid MinecraftForge's question with the kernel's tag-derived stand-in, a
  Fabric mod's both questions with stand-ins. So a NeoForge fluid in `minecraft:water` whose own type says
  `canExtinguish(false)` put out a body's fire (MinecraftForge's water type, its stand-in, says yes), so did a Fabric
  fluid in that tag (vanilla puts fire out only in `WaterFluid`, whose `entityInside` overrides `Fluid`'s empty one), and
  a NeoForge fluid's own bucket sound gave way to water's or lava's. `OwnFluidTypeClausesInjector` finds such methods by
  their shape — calls of both `Fluid.getFluidType()` getters in one body; on today's base exactly those two, which
  `OwnFluidTypeClausesInjectorTest` re-derives over the merged base and both carriers — and follows each getter with
  `KernelFluidTypes.ownForgeType` / `ownNeoType`: the type itself, or that family's empty type, which claims nothing,
  where the kernel's stand-in answered (`forgeTypeIsStandIn`; `neoTypeIsStandIn`, recorded by `foreignNeoLookup` when
  no view of a MinecraftForge type of the fluid's own answers). A MinecraftForge mod's fluid keeps its own type and
  NeoForge's view of it, a NeoForge mod's its own, vanilla's both. `-Dforbric.ownFluidTypeClauses=off`. Second,
  NeoForge's walk gives every non-empty cell's type a tracker; on NeoForge only the empty fluid state has the empty type,
  but the kernel gives that type to a Fabric fluid in no tag (and in no Fabric behaviour's tag), meaning "entities do not
  interact with it", and the walk tracked it all the same: `shouldTravelInFluid` asks `isInFluidType(state)`, so a body
  in such a fluid moved as through lava (the merged `travelInFluid` is MinecraftForge's, which sends every fluid but water
  there) where on Fabric it falls through it as through air. `EmptyFluidTypeTrackerInjector` makes NeoForge's private
  `getTrackerFor(FluidType)` — which only the walk calls, and the walk passes over a cell with no tracker — answer null
  for the empty type, so no native cell is treated differently; MinecraftForge's record of the cell is taken before the
  lookup. `-Dforbric.emptyFluidTypeTracker=off`.
- **Configs** — `KernelForgeConfigLoad` opens genuine MinecraftForge configs one at a time, keeping Forge's reader,
  events, save and file watcher; the watchers are stopped on exit by `interop.ClientShutdown`
  (`ExitHookInjector`: `Minecraft.close` on the client, `DedicatedServer.onServerExit` on the server). A watcher
  that does not confirm its stop within 1.5 s is not a failure; the exit guard keeps sweeping and names any
  non-daemon thread still alive at its deadline.

### 5.4 Cross-ecosystem services that are not events

- **Networking** — `interop.PayloadInterop` selects a custom-payload codec by runtime payload class where Fabric
  API and NeoForge share a vanilla channel id; `CommonNetworkInteropInjector` arbitrates the `c:version` /
  `c:register` channel both claim (`-Dforbric.commonNetworkInterop=off`); `RegistrySyncParityInjector` +
  `KernelForgeWrapperSync` apply NeoForge's registry sync, and fabric-api's when fabric-api is installed, to
  MinecraftForge-wrapped registries through Forge's own `GameData.injectSnapshot`; `KernelRegistryRevert` restores
  pre-connection ids on disconnect; `NetworkChannelCensus` compares registered vs declared channels.
  The merged `ServerGamePacketListenerImpl.handleCustomPayload` is MinecraftForge's override: it asks
  `ForgeHooks.onCustomPayload`, drops the answer and never reaches NeoForge, so every NeoForge mod's play-phase
  packet to the server went nowhere (Carry On's "carry key held" packet among them, which is why it could pick
  nothing up). `CommonNetworkInteropInjector` turns the dropped answer into a branch: a payload MinecraftForge did
  not take and NeoForge registered goes to `NetworkRegistry.handleModdedPayload`. It calls that directly rather than
  `super`, because fabric-api's injection in the super serves only the configuration listener and throws
  `Unknown addon` for the play one (`-Dforbric.playPayloadFallThrough=off`). `KernelClientSmoke`'s
  `-Dforbric.clientSmokeCarry=<tick>` drill drives the whole chain through the game's own key and mouse input.
  Where the edit-spliced common listeners keep MinecraftForge's `ForgeHooks.onCustomPayload` call, it is offered only
  a `ForgePayload` (`PayloadInterop.offerToForge`), as on MinecraftForge, whose codec decodes every channel it owns into
  one; the kernel's own dispatch prologue stands by only where that kept call returns on its answer at the head of the
  method. Fabric's payload receivers are found by the public `PacketSender` they implement and handed the shared
  payload as Fabric's own codec reads it (`WirePayloads`). Each channel declaration reaches fabric-api's addon once and
  a `minecraft:register` naming only channels already declared in the phase is not sent again
  (`-Dforbric.channelDeclarationsOnce=off`); a mirrored channel's version names its origin and phase, never its
  direction, so a client and a server agree on it.
- **Item/fluid/energy transfer** — `KernelTransferInterop` + `runtime/transfer/` bridge Fabric's transfer API,
  NeoForge's `ResourceHandler` and MinecraftForge capabilities, and Team Reborn Energy when installed
  (`-Dforbric.transferBridge=off`, `-Dforbric.hopperFabricStorage=off`). Active only when the relevant APIs are
  present, checked by resource. A transfer finding (a provider the bridge will not drive transactionally) is attributed
  to the mod whose jar holds the class that answers for the provider: its own class, or a dynamic proxy's invocation
  handler, carried across as a `Class` (`TransferIssues.Issue.source`) because a lambda cannot be found by name and a
  proxy was defined from no jar. `util.CodeRoots` reads the jar (null for a proxy, a class with no protection domain, a
  JDK class; a hidden class's is its definer's), and a class from no mod's jar is attributed to `forbric` as an answer,
  not a failure. A proxy class is shared — the JDK defines one per class loader and interface list — so the runtime's
  once-per-finding key and the finding's id carry that class too whenever it is not the provider's own
  (`transfer:<code>:<proxy> via <handler>`); keyed by the proxy's name, the first mod to report took the finding and
  every other mod's handler behind the same interfaces was dropped.

## 6. The transform pipeline

`transform.TransformPhase` fixes the order: `RAW_PATCH`, `DEOBF_REMAP`, `ENV_STRIP`, `ACCESS`, `COREMOD`,
`FABRIC_BUILTIN`, `MIXIN`. `TransformChain` runs the chain phases (`RAW_PATCH` … `FABRIC_BUILTIN`); within a phase,
`predepends` topologically, then `sortIndex`, then registration order. `MIXIN` is terminal and cannot be registered
into the chain. On 26.2 nothing is registered in `RAW_PATCH` or `DEOBF_REMAP`.

What `KernelBoot` registers (131 call sites; some conditional):

| Phase | Registered |
| --- | --- |
| `ENV_STRIP` | `EnvironmentStripTransformer` |
| `ACCESS` | `ClassTweakerTransformer` (Fabric), `access.AccessTransformer` built from every mod jar's **and both carriers'** `META-INF/accesstransformer*.cfg` — the carriers' ATs matter because where the merge kept the other family's body it kept that body's access (`MenuScreens.register` came out private) |
| `COREMOD` | 129 registrations: the loader-probe rewriter, the Mixin-weaver slot (`FmlContextLoaderRewriter`, `ModuleClassLoaderInitInjector`), `GuestMixinPluginGuard`, the lifecycle redirect, `ForbricMergedBaseCompatTransformer`, the capability composition, and most of the 103 `*Injector` classes in `transform/`, each repairing one named seam the merge broke or landing one bridge (§8) |
| `FABRIC_BUILTIN` | `RestoredAccessTransformer` (re-applies access to members COREMOD restored), `MergedBaseFrameRecomputer` (recomputes a guest class's stack-map frames when they name a superclass the merge removed) |

The post-Mixin stage (`KernelMixinBootstrap`) is a fixed composition:

```
Mixin (via MixinWeaverSlot) → NativeCoremodParity → PostMixinFixups → UncalledValueRelay
                           → InterfaceDefaultConflictRepair → ForgeTransferShapeAudit.certify
```

Notable repairs by family (read each class's javadoc for the case that motivated it):

- **Merge invariants** — `ForbricMergedBaseCompatTransformer` (lambda bootstrap handles vs. static-ness, the
  MinecraftForge `getFluidType()` bridge, key-mapping `MAP` initializer and vanilla's `KeyMapping.MAP` back as a view of
  the mappings by key (`KernelKeyMappingMap`; both ecosystems re-type it, and LiquidBounce reads it on every key press in
  a screen), vanilla's `FriendlyByteBuf.writeByte(int)` called again where NeoForge's recompile bound a byte-typed call to
  its extension's `writeByte(byte)`, which only forwards there (14 sites in 10 network `write` methods; ViaFabricPlus'
  ability-flag redirect anchors on vanilla's call; `-Dforbric.vanillaWriteByte=off`), and retargeting the base's baked-in
  calls to `net/forbric/loader/impl/…` onto `net.forbric.kernel.interop`), `DuplicateLambdaPruneInjector`
  (orphaned lambdas a name-only mixin selector would bind to), `WidenedFieldTwinInjector` (vanilla-descriptor
  twins of re-typed fields), `MethodBodyNeuter`.
- **Packs and data** — `DataPackHookInjector`, `ClientPackHookInjector`, `PackMetadataFailSoftInjector`,
  `PackOverlayMutabilityInjector`, `NullPackGuardInjector`, `PackScreenHiddenFilterInjector`,
  `RegistryDirectoryOwnerInjector`, `RegistryAliasParityInjector` (§9).
- **UI** — `ModsButtonRedirector` (both families' pause-menu lambdas open `KernelModListScreen`; where the merge left
  one mods button per family in a menu, the first is kept), `HudElementBridgeInjector`, `ForgeHudPipelineInjector`
  (the HUD stays on one pipeline, NeoForge's layer manager, where the merged `Hud` also kept MinecraftForge's overlay
  tree: that tree's own build and its returning draw are dropped), `CreativePagerBridgeInjector` (the page state is
  always the carrier's; when no Forge-family mod is installed, the guest pager draws its own page controls, as native
  Fabric does, and turns the carrier's page through the Fabric interface), `CreativeTabOrderInjector` (a Fabric mod's
  tabs are paged by the Fabric pager's rule inside NeoForge's one tab order, every other tab where NeoForge puts it;
  `EcosystemTabPlacement`), `EarlyKeyMappingRegistrationInjector`, `CreativeSearchTreesInjector`. The last is a
  producer/consumer split: the merged `SessionSearchTrees` keeps
  MinecraftForge's `getSearchTree(Key)`, which joins a future from a private map nothing fills, defaulting to a future
  nothing completes, while the creative screen is NeoForge's and reads `CreativeModeTabSearchRegistry`. A
  MinecraftForge mod reading a search tree through it (as MinecraftForge's own creative screen does) waited forever.
  An earlier merge also kept MinecraftForge's bodies for vanilla's `updateCreativeTooltips(Provider, List)` /
  `updateCreativeTags(List)`, so a mod that rebuilds the tabs itself and refreshes the search the vanilla way
  (TCDCommons, on every join) left every creative search empty; the current base keeps NeoForge's delegating bodies
  for those two. Whichever bodies still read MinecraftForge's store now call `KernelCreativeSearch`, which goes
  through NeoForge's keyed methods (for a producer, for every tab with a search bar); on the current base that is
  `getSearchTree` alone. `-Dforbric.creativeSearchTrees=off`. gate-m55 calls `getSearchTree` from a MinecraftForge
  canary. `-Dforbric.clientSmokeCreativeSearch=<tick>[,query…]` types into the real creative screen and logs the grid.
- **The native shape back** — repairs that give the merged body back a shape the platform's own body has, so guest
  injectors written against it land and run as natively (§7.4): `VanillaEarlyReturns` (the early `return`s vanilla
  compiled, where a carrier folded a guard into one join or split the body; a split body's last piece gets its
  method's), `LocalRanges` (a local's variable-table range covers its own uses again where the splice put reads in
  front of it), `TailLocals` (a split method's tail holds the primitive locals vanilla's tail holds),
  `LambdaInvocationThunkInjector` (an own virtual method reference materialised as one exact invocation a guest can
  target), `ScopedClearRelayInjector` (a native `field.clear()` back where a carrier removes a computed subset of the
  keys; `ScopedClearView`), and the delivery bookkeeping of §8.1 (`NativeDualEventInjector`), which never moves a game
  instruction.
- **Client rendering and equipment** — `ForgeModelDataFlowTransformer` + `KernelForgeModelData` (MinecraftForge's
  block-model data flows through the merged client, which asks NeoForge's model questions, and its tessellation
  entry's edge is entered wherever a block is tessellated), `GlideFreshEquipmentInjector` (`canGlide` answered from
  the equipment while the carrier's cached gliding attribute has not caught up, as the native method always does).
- **Configs** — `StaleWatchEventInjector` (a config file watch's handler runs only for a change made after the watch
  was registered, so a config the tracker creates in an already-watched directory no longer fires a reload event;
  `StaleWatchGuard`).
- **Air** — `VanillaAirRefillInjector` + `KernelAirRefill` (the owner chose vanilla's rule). The merged `baseTick` is
  NeoForge's and hands the air supply to `CommonHooks.onLivingBreathe`. Where the eye is in a fluid that is not air and
  the body does not drown there — water breathing or conduit power, a drowned, an invulnerable player, lava, a mod's
  fluid it may breathe in — that hook hands its breathe event a refill of zero, so the air stayed where it was. Vanilla
  refills it by 4 a tick up to the maximum everywhere but its water branch, where a body that Breath of the Nautilus
  alone keeps from drowning holds its air. That branch is the eye in a fluid the body can drown in (fabric-api makes a
  Fabric drowning fluid answer vanilla's water question exactly so), and the kernel asks it of the eye's fluid types,
  each answering `FluidType.canDrownIn` for that body — water's type yes, lava's no, a mod's type for itself — never
  through the water tag; lava and a fluid the body breathes in refill, as in vanilla. MinecraftForge's own hook refills
  like vanilla but holds that body's air in any fluid that is not air (`canRefillAir = isAir || the effects refill`).
  The one store of that zero (found by shape: the hook's signature, its one `LivingBreatheEvent(entity, canBreathe,
  consume, refill)`, the zero stored into the refill local right after true into the can-breathe local) becomes
  vanilla's refill. Vanilla also changes the air only below the maximum, where the hook's update for a body that
  breathes, `setAirSupply(min(air + refill, max))`, pulled air above the maximum back to it every tick (native NeoForge
  does too, and MinecraftForge wherever it refills); its `Math.min` becomes `KernelAirRefill.refilledAir`, handed the
  refill the event started from and the one it ended with, which leaves air at or above the maximum where it is unless a
  listener set the refill (a listener that sets the very amount the event started from cannot be told apart, and gets
  vanilla's update). Nothing else changes: the hook still posts `LivingBreatheEvent` once, and a NeoForge listener still
  sets the amounts and decides whether the body breathes, as on NeoForge. MinecraftForge's own `LivingBreatheEvent` is
  posted from inside that one post, by the kernel's NeoForge listener at `LOWEST` (§8), so what a MinecraftForge
  listener changes is on the NeoForge event before `refilledAir` compares the refill: a refill it carries back counts
  as a listener's, and a MinecraftForge listener that changes nothing writes nothing. A MinecraftForge mod's fluid
  answers `canDrownIn` through its own type (§5.3). The cross-host predicate island's protected
  decision (a Fabric fluid that drowns like water, §7.4.1) starts its event from the same refill
  (`KernelAirRefill.waterRefill`), and the island's prefix is executed in the decorated hook by
  `VanillaAirRefillInjectorExecutionTest`. The refill asks its drowning question of the body's fluid interaction rather
  than through `isEyeInFluid`, because the native decision island would take the carrier asking vanilla's own call as
  the call having moved and give up the injectors there (Create's diving gear, fabric-api's drowning fluids);
  `NativeDecisionIslandStagedTest` holds both sides of that. `-Dforbric.vanillaAirRefill=off` (formerly
  `forbric.foreignFluidBreathing`, which gave only a Fabric mod's non-drowning fluid the refill) keeps NeoForge's rule.
- **Instrumentation** — `ClientSmokeTickInjector` (inert unless `-Dforbric.clientSmoke=true`),
  `EventChainAuditInjector` (inert unless `-Dforbric.eventChainAudit=<report>`), `ServerTickSamplerInjector`,
  `CompatibilityPromptTickInjector`, `ServerCompatibilityTickInjector`.
- **Hardening** — `ChunkExecutorGuardInjector` (refuses work offered to a stopped server's chunk executor;
  `-Dforbric.chunkExecutorGuard=off`).

Transformers that promise to land on a class declare it (`AnchorSet`); `AnchorLedger` reports a class that
passed through without being changed (a **Miss**, logged at ERROR immediately) and, at the census landmark,
classes never loaded. `RepairDriftCensus` (`run/compat/repair-drift.sh`) replays the claims against a *candidate*
game build — the question a carrier bump asks.

## 7. Mixin on the merged base

### 7.1 The kernel is the Mixin service

`mixin.ForbricMixinService` is registered through `META-INF/services/org.spongepowered.asm.service.IMixinService`
(with `ForbricMixinServiceBootstrap` and `ForbricGlobalPropertyService`); the Mixin library is Fabric's fork
(`net.fabricmc:sponge-mixin`, `mixin_version` in `gradle.properties`), and MixinExtras is the kernel-bundled
`mixinextras-fabric` (game-side, because its generated `LocalRef` classes must share the game's loader).
`KernelMixinBootstrap.init` binds the service, registers every config, installs `KernelMixinErrorHandler`, installs
the weaver as the last pipeline stage, then moves the environment to `INIT` and `DEFAULT`. Configs are prepared —
and plugins constructed — on the first class that passes through the transformer after `init`, not by `init` itself.

Before the phase moves, each config is decorated as Fabric Loader's `MixinConfigDecorator` decorates it: the owning
mod's id (`KEY_MOD_ID`, so Mixin's own messages name it) and, for a Fabric-owned config, the Mixin compatibility level
Fabric Loader gives that mod (`KEY_COMPATIBILITY`, `FabricMixinCompat`). The level comes from the lowest Fabric Loader
version the mod's `depends`/`breaks` on `fabricloader` accept, compared in Fabric's own version order (a port of
`SemanticVersionImpl.compareTo`, not the kernel's dependency comparator, which orders pre-releases differently) against
Fabric Loader 0.19.3's `FabricMixinVersions` table (the version the kernel reports, and the table that matches the
bundled Mixin's newest level); it decides the order initialisers and injectors are applied in, how locals are
discriminated and where shifted points land. Undecorated, every Fabric mod ran at the newest level. On the local corpora
(997 distinct `fabric.mod.json`) the port agrees with Fabric Loader 0.19.3 itself on every mod, and 323 of the 561 that
carry mixins get an older level than before.

There is no floor. Fabric gives 0.9.2 to a mod whose lowest accepted `fabricloader` is older than 0.12.0, or that
accepts every version downwards (no `fabricloader` dependency, `*`, or only an upper bound): 58 of those 561. Most
declare no dependency on `fabricloader` or no lower bound on it. A few have one below 0.12.0, such as WorldEdit's
`>=0.4.0`. A recount by mod id over the corpus's jar list, which counts differently, finds 620 mods with mixins, 112
of them at 0.9.2: 55 with no dependency, 52 with no lower bound and 5 with a bound below 0.12.0.

With 0.9.2 comes Mixin's legacy local walk (`Locals.getLocalsAt092`). One difference between that walk and 0.10.0's
meets a capture the kernel moved. To read the locals at a label or a line number, both first step forward over
pseudo-instructions. The 0.10.0 walk stops before a frame. The 0.9.2 walk steps up to three nodes and reads the frame
it lands on. `MixinGuiItemCaptureAdapter` moves a
TAIL capture to `INVOKE <the call that ends the body>, AFTER`. Where that call falls straight into the join the tail
sits on, AFTER is the join's label, and the 0.9.2 walk steps onto the join's frame. The path that never stored the
local reaches the join too, so the frame does not declare the local: Mixin refuses the capture as "LVT ... has
incompatible changes" and the feature is gone, skipped with a WARN, or, for `CAPTURE_FAILEXCEPTION`, woven as a method
that throws `InjectionError` each time the point is reached. The adapter softens a capture it moves from
`CAPTURE_FAILEXCEPTION` to `CAPTURE_FAILSOFT` (as `MixinLocalsCapture` does for `CAPTURE_FAILHARD`, under the same
`forbric.localsFailSoft` switch), so where a moved capture still fails it is skipped, never thrown.

`MixinCaptureLanding`, a Mixin extension, fixes that in `preApply`, as Mixin is about to apply the mixins to a class.
It acts only for a mixin below 0.10.0 that Mixin is applying to that class. That includes a merged twin the mixin
reaches through `MixinMergedTwin`, and excludes a mixin its config plugin refused. It finds the call the moved point
binds, read off the mixin as applied and matched as Mixin matches it. Where the node after that call is a
pseudo-instruction, it inserts a `NOP` right after the call. The `NOP` is still inside the local's scope and is not a
pseudo-instruction, so the walk does not step, and it reads the locals the body left. No point type selects a `NOP` by
itself. Another AFTER on the same call now lands on the `NOP` instead of the label, which is the same place in the
code. `Shift.BY` counts every node, so a `NOP` would move a BY point across the call. A method where any applied mixin
shifts a point BY therefore keeps its body, and there the capture is still skipped at 0.9.2, with Mixin's WARN
(while the 0.10.0 floor stood, such a mod was raised to 0.10.0 and the capture held).

The real-weave suite passes in full at 0.9.2, 0.10.0 and 0.17.0. The GUI-item fixture captures at 0.9.2, 0.10.0 and
0.17.1, and only the 0.9.2 weave has a `NOP`. A twin fixture captures in both copies at 0.9.2. With a `by = 2` injector
beside it, that injector stays after the join's label and runs on every path. With a `NOP` it would have moved before
the label, which was measured with the BY check taken out. With `-Dforbric.captureLanding=off`, the 0.9.2 weave skips
the capture again.

Until this, the kernel raised such mods to 0.10.0 (`RELOCATION_FLOOR`). Without that floor, the pure-Fabric server A/B
(130 mods alone, then the 159-jar pack for 1200 ticks) still matches native Fabric: 126 pass on both, the same 4 fail
on both, none fails on Forbric alone. The 127 sessions that reached Mixin include the 12 with configs the floor
raised, and each logs the same Mixin warnings and the same unattached injectors as with the floor. The client
gate (gate-m9, 13 of its configs now at 0.9.2) gives the same result as before. No Fabric mod in the local corpus
that gets 0.9.2 has a TAIL locals capture at all, so the landing is for the shape rather than for a mod seen so far.
On the pure-Fabric server pack (126 mods, 159 jars), weaving at Fabric's own
levels instead of the newest changed only the order of field initialisers in six classes (exported classes, against a
second run at the same levels as the noise floor), and the pack runs to the same end.
Forge-family configs are left as they were: NeoForge's FML decorates its own with a declared `behaviorVersion` and
refuses one below 0.17.1, so every level it can give behaves as the bundled Mixin's newest.
`-Dforbric.mixinCompat=latest` leaves every Fabric config at the newest level.

Order: Fabric configs in Fabric Loader's order, Forge-family configs appended. Mixin orders by priority;
registration order only breaks ties, so the least-proven set becomes the outer wrapper around a known-good stack.
`MixinWeaverSlot` lets a guest replace the weaver the way NeoForge allows (LibJF wraps
`FMLMixinClassProcessor.transformer`).

### 7.2 What happens to a guest config before Mixin reads it

`ForbricMixinService` rewrites each config's JSON:

1. **Whole-config gate** — `MixinConfigPolicy.isDisabled`: the built-in list in `MergedBaseMixinCompat`
   (`-Dforbric.mergedBaseCompat=off` drops it; `-Dforbric.enableMixinConfigs` forces one back),
   plus `-Dforbric.disableMixinConfigs` (csv, trailing `*` glob).
2. **Relaxation** — every config not starting with `forbric` is a guest config and is relaxed:
   `injectors.defaultRequire → 0`, `overwrites.requireAnnotations → false`, `required → false`. Per-injector
   `require`/`expect` still win. `-Dforbric.relaxGuestMixins=off` restores strict behaviour;
   `-Dforbric.relaxMixinOverwrites` relaxes named configs when that is off; `-Dforbric.mixinDiagnostics` keeps
   injection requirements strict (so every misfit is reported) while keeping `required=false`.
   `required=false` only helps where Mixin isolates a failure per mixin, which is inside its own apply loop.
   MixinExtras' late injectors (`@WrapOperation`, `@WrapWithCondition`, `@ModifyExpressionValue`) inject and check
   afterwards, in a target-class extension (`LateInjectionApplicatorExtension.postApply`), and upstream an
   `InvalidMixinException` there hands the class back unwoven: every mod's mixins on it are gone. Two things raise
   one there. In a player's game only one does: a late injector's own checks while injecting (a `@WrapOperation`
   whose handler does not fit the call it bound), at any setting. The other is `mixin.debug.countInjections` (or
   `mixin.debug`), whose `expect` check fails every injector that lands nothing; it is a debug switch the kernel's
   test harness turns on and a launcher does not. `LateInjectionIsolation` sits directly in front of that applicator,
   takes the class's queue and runs it in the same order with Mixin's own rule around each step: an optional mixin's
   failure goes to the class's suppressed list, is reported like any optional mixin that failed, and the queue goes
   on. A required config's failure, a failure that names no mixin, an `Error` (such as `InjectionError`) and anything
   else are thrown as upstream throws them, but not always reached as upstream reaches them: upstream gives up on the
   class at its first failure and never runs the rest of its queue, while here the queue goes on past a failure set
   aside. So a later late injection that fails in a way upstream would not tolerate either — a required config's,
   or an `InjectionError` from a `require`/`allow` left strict (on relaxed guest mixins that takes
   `-Dforbric.mixinDiagnostics` or `-Dforbric.requireFailSoft=off`; a config the kernel does not relax keeps its
   own) — now stops the game where upstream, having already given up on the class, warned and left it unwoven.
   When anything leaves the queue after a failure was set aside, Mixin reports only that, so the ones set aside
   before are logged and carried on it as suppressed exceptions. An injection can also stop half-way (a target
   without a descriptor matching two overloads: the first call is wrapped, the handler check fails at the second).
   Nothing is rolled back — Mixin rolls back nothing of an optional mixin it sets aside, and the instructions an
   injection replaced are shared with every other injection queued on them — and none needs to be for the wrappers:
   `@WrapOperation` and `@WrapWithCondition` check a call before they touch it, and `@WrapOperation` adds the bridge
   a rewritten call needs in the same step. Because that is read from MixinExtras' code rather than enforced, every
   method of the class is analysed (ASM's `BasicVerifier`: stack heights at every join and the basic kind of every
   value, not a full verification) before a failure is set aside, and a class that no longer analyses is handed back
   unwoven with its first failure, upstream's outcome. The analysis runs only after a late failure. Over the 22,250
   classes a full client pack's JVM defined (Mixin's 759 woven ones among them) it found none that does not analyse;
   those are the bytes as finally defined, after Mixin's `ClassWriter` recomputed their frames and maxima, not the
   classes as `postApply` holds them. `@ModifyExpressionValue` has one path that inserts an instruction before it
   checks its handler (a `DUP` behind a `new` it sees as not yet duplicated); on javac's `new` it leaves nothing
   behind, and no MixinExtras 0.5.4 injector is known to change code before its check on javac-compiled code. The
   weave test covers the half-way wrap (kept; both injections on the call it rewrote do what they asked; the class
   verifies), a value modifier whose handler check fails on a `new` inside a conditional (kept, with javac's one
   `DUP` and nothing else), and a class a stand-in leaves inconsistent (handed back). The isolation holds
   MixinExtras' private queue and reads it only in the shape MixinExtras 0.5.4 declares
   (`Map<ITargetClassContext, Map<String, List<Runnable[]>>>`, checked from the field's generic type at install, and
   each entry checked before it is taken); anything else stands it down with one warning, and upstream behaviour
   returns. A guest that bundles a newer, relocated MixinExtras and starts it from its config plugin's `onLoad` takes
   over after the isolation is installed: the kernel's applicator is unregistered and the newer one has its own
   queue, so late injections fail the upstream way again. The isolation notices when Mixin's active extensions change
   and says so once. Measured with lambdynamiclights and `countInjections` on: spruceui's NeoForge `GuiMixin` wraps
   `ClientHooks.extractScreen`, which the merged `Gui.extractRenderState` no longer calls, and `Gui` lost
   fabric-screen-api's `GuiExtensions`, so `Minecraft.<init>` died on the cast; balm's moisture wrap cost `CropBlock`
   every mixin the same way, because the moisture read it wraps in `CropBlock.getGrowthSpeed` is in neither of the
   merged base's two `getGrowthSpeed` overloads (and `randomTick` calls NeoForge's `BlockState` one). With the
   isolation only those wraps are lost. The two lost calls are the merged base's and stay open: spruceui's
   `BEFORE_EXTRACT` / `AFTER_EXTRACT` screen events and balm's moisture hook do not fire, isolated or not, with the
   switch or without. Without `countInjections` (a player's setup) the same packs never reached the isolation.
   `-Dforbric.lateInjectionIsolation=off` restores upstream.
3. **Per-mixin drops** — the union of `KernelGuestMixinAdapter.unfitMixins` (below), the hand list
   `MergedBaseMixinCompat.SUPPRESSED_MIXINS`, and `-Dforbric.suppressMixins=config:Mixin,…`, minus
   `-Dforbric.keepMixins`. Dropping a mixin also drops every mixin that depends on an interface it contributed.

A config is read the way Mixin reads it (`MixinConfigJson`): Gson's lenient parse, so a config with comments (Essential
ships its preprocessor's `//#if` lines) gets every adapter a plain one gets instead of none. A config a mod declares
is one whatever its file is called. A relaxed child's `parent` config is relaxed too, since the child inherits the
injector options it leaves out. A wrapper jar's declared config is dropped silently when the jar it nests carries it.
A config registered after the boot (`Mixins.addConfiguration` from `preLaunch` or a jar added at run time) is relaxed
like one registered at boot and owned by the mod whose jar declares it, and a mixin a config plugin adds is judged
when Mixin first reads it (`-Dforbric.lateMixinJudgment=off`), so the adapters below plan for it as for a listed one.

### 7.3 `MixinFit` — resolution, not provenance

`KernelGuestMixinAdapter` asks `MixinFit.evaluate` to resolve every anchor a guest mixin names — each `@Shadow`,
each injector's target method, each `@At(target=…)` — against the **post-chain** bytes of the merged target:

| Verdict | Meaning | Default action |
| --- | --- | --- |
| `FIT` | every anchor resolves | apply |
| `PARTIAL` | some resolve | **apply** (drop only under `-Dforbric.mixinFit=strict`); always reported |
| `UNFIT` | none resolve | drop |
| `HAZARD` | applies cleanly but shadows a field the merge orphaned | drop |

Refinements: pure accessor/invoker mixins are always kept; anchors satisfied by another mod's mixin
(`ForeignMixinTargets`, `MixinAddedMembers`) count as resolved; `@Group` injectors are judged as a group; an
injector bound only to a merged-base method nothing in the merged game calls is **not** resolved (liveness,
`MergedBaseUncalledMethods`, `-Dforbric.mixinFit.liveness=off`) unless an installed mod calls it. "Nothing calls it"
is a fixpoint over the merged jar, both carriers and the kernel, by one rule set shared with the census that writes
`uncalled-methods.txt`: a call from a method that is itself uncalled, or from code the kernel silences, is no call; a
private method is reached only by a call or handle naming its own class, a static one only by a static call naming
its class or a subclass that does not declare it again, and an override's `super` call of itself is no call; a row
needs the mod's own game to call the method from a place that runs there. The boot scan of the installed mods
(`scanGuests`) reads their calls by the same rules, an `@Invoker` only for the method of its target class with that
name and descriptor, and the row is re-checked on the live bytes. A method whose woven hooks the kernel relays to the
carrier's own place (`UncalledValueRelay`, §7.4) is read as running for the ecosystems its census row names. An injector that stays bound only to such a method
is reported by the final-class check as one that never runs (`CONFIRMED`, not required: it marks the mod's row and
stops no launch). Physics Mod's collapse hook on a private `removeBlock` overload nothing calls (now moved onto the
overload that runs, `MixinTwinRebind`, §7.4) and Create's hook on the static `RedStoneWireBlock.shouldConnectTo`
nothing calls (now moved onto the carrier's decision, `UncalledDecisionEntries`, §7.4) used to read as running. And a name-only
`@Inject` selector is **not** resolved when the method it binds — Mixin takes the first of that name the target
declares — is not one the handler was written for (that method's arguments then the callback its return calls for, or
the callback alone), which Mixin would reject as "Invalid descriptor" (`-Dforbric.mixinFit.handlerFit=off`): the merged
base can put a carrier's overload where vanilla's method was, for a mod of any ecosystem. A selector `MixinOverloadPin`
will spell (§7.4) is judged on the overload it lands on, so the pin and this rule never both act on one injector; a
`@Surrogate` counts only as Mixin looks one up — the handler's name, exactly the bound method's callback descriptor, a
visible annotation. Where the name binds exactly one method, the handler captures no locals and one of its `@At`s is
sure to find a point in that method (`HEAD`; `RETURN`/`TAIL` where it returns; an `INVOKE`, `INVOKE_ASSIGN`, `FIELD` or
`NEW` whose member is there, past its `ordinal`; never with a slice), the miss is a *rejection* (`MixinFit.Rejection`):
Mixin checks the handler at each point it finds and throws at the first, whatever `require` says, and the exception
fails the mixin's application to that class — every injector after it, and the game in a config that stays required.
Where no point is sure, Mixin may find none, inject nothing and throw nothing, so the binding stays an ordinary miss
and its line says so (`-Dforbric.mixinFit.rejectionPoint=off` reads every such binding as a rejection, as before). A
mixin kept with a rejection — `PARTIAL`, kept for its misses on another mod's class, or `UNFIT` and kept because another
mod's mixin targets the class — is therefore never left as it is: the injector is taken out before Mixin reads the
mixin (`GuestInjectorPruner`, §7.4, asking the same rule of the node Mixin receives, with the target's code) when
nothing else in the mixin calls it, it is in no `@Group` and no target binds it as written — the rest of the mixin
applies, and the removal is a `CONFIRMED` finding, required when the author's own count (`require`, else
`defaultRequire`) is at least one — and otherwise the whole mixin is left out like an `UNFIT` one. A mixin a kernel
repair supersedes (`SupersededMixins`) is always left out whole, with a row that asks nothing and resolves once the
repair is seen. A mixin kept by name (`MergedBaseMixinCompat.KEPT_MIXINS`, `-Dforbric.keepMixins`) is handed to Mixin
unjudged. `-Dforbric.guestInjectorPruner.refused=off` keeps such a mixin in front of Mixin as before, and the `PARTIAL`
summary then counts it apart. A `PARTIAL` or `UNFIT` mixin is first offered to `MixinRetarget`, and its plan is taken when the
rewritten mixin misses fewer anchors, is one the adapter keeps, and adds no rejection — an `UNFIT` one's rewrite may
keep none (`MixinRetarget.adopt`). A mixin the kernel leaves out by name is not judged at all, so it gets no verdict line
and no place in the `PARTIAL` count. A `PARTIAL` verdict is read once more on the mixin as it is handed to Mixin,
after every adapter (`KernelGuestMixinAdapter.judgeAsHanded`): an anchor an adapter moved onto a body that runs is
withdrawn from the report with where it landed, and a mixin that now fits is resolved. `MixinFitReport` runs the same judgement offline:
`MixinFitReport <merged-base.jar> <mods-dir> [--verbose]`.

A miss is the merge's only if the mod's own platform had the member: vanilla 26.2 for a Fabric mod, MinecraftForge's
or NeoForge's patched 26.2 for theirs (the config owner's ecosystem, `MixinConfigOwners.ecosystemOf`; a config no
single mod claims is not asked). Those patched games declare methods vanilla does not — `KeyMapping.getKeyModifier()`,
`AxeItem.canPerformAction`, NeoForge's `EnderDragon.getParts()` — and the merge dropped or retyped some of them, so
"vanilla lacks it too" proves nothing for a MinecraftForge or NeoForge mod. An injector whose `method` selectors are
all plain names of methods that platform lacks too, and which nothing requires to inject — no `require` ≥ 1, the
config's original `injectors.defaultRequire` 0, no `@Group`, no `mixin.debug.countInjections` — is one native Mixin
skips without a word while the rest of the mixin applies (sponge-mixin 0.17.x and upstream Mixin 0.8.7 alike:
`TargetSelectors.validate` throws only for a required count; the config's `required` flag only decides whether such an
error is fatal). "Plain" means a name, optionally a descriptor, optionally the target itself as owner: a `@`-dynamic
selector (MixinSquared's `@MixinSquared:Handler` resolves to another mixin's handler), a `+` or `{n,}` quantifier (its
minimum throws whatever `require` says), a dotted owner, a regex and a malformed descriptor are never answered.
`NativeAbsentTargets` counts such an injector neither resolved nor missing and logs it on one info line, so the mixin
is judged on the rest and, with nothing else missing, goes to Mixin whole — no finding, no policy stop. Not Enough
Crashes' `@Inject` into `BlockEntity.populateCrashReport` (26.2 calls it `fillCrashReportCategory`) is the case.
"The platform lacks it" is answered from the merged base's raw bytes plus the shipped difference
`native-only-methods.txt`: per platform, the methods its game declares in vanilla's packages that the merged base does
not (565 for vanilla, 424 for MinecraftForge, 14 for NeoForge) and the classes there only the merged base has (18, 10
and 6). A method the raw class still declares, a class outside `net/minecraft/`/`com/mojang/`, and another mod's class
are never called absent. The rows hold only for the base they were derived from, so the table also records that base's
members digest (every class in vanilla's packages with its methods' names and descriptors, bodies left out); a class
served by a jar whose digest differs is not answered for, and one warning says so. Minecraft's own libraries under
`com/mojang/` (brigadier, DataFixerUpper, authlib and five more) are answered from their own bytes: the kernel loads
them beside the merged base, every platform loads the same jars (vanilla 26.2's version JSON lists them, and
MinecraftForge 65.0.1's and NeoForge 26.2.0.88's launcher profiles inherit that list and add no `com.mojang` library),
so the table records each of those jars' members digest too (`library` lines), and a class served by exactly one of
them is answered like the merged base's; a library jar of another version is not. The rows describe one game per
platform — vanilla 26.2, MinecraftForge 65.0.1's patched 26.2, NeoForge 26.2.0.88's — and the table's `platform` lines
record those versions. A mod whose mandatory `minecraft` range, or `forge`/`neoforge` range for its platform, excludes
that version is not native to that game (its own loader would refuse it there, and the newer game it was built for may
declare the method), so it is not answered for and one info line names the requirement; nor is a mod whose range cannot
be read, or whose manifest the kernel has not published (`ModPresence`). For any of those the miss is the merge's, as
before. `-Dforbric.mixinFit.nativeAbsent=off` counts such an injector as a miss again.

Provenance was the previous rule and was wrong: the merged base *is* NeoForge's patched Minecraft with Forge
spliced in, so "a Forge-family class" describes most of the jar.

### 7.4 Adapters — moving an injector to where the code went

Rather than drop, the kernel moves a guest injector when the merge relocated what it wants. Each adapter is
narrow and table- or proof-driven:

`MixinRetarget` and `MixinStubRebind` (delegating stubs → the overload carrying the body; a stub that first works an
argument out — `Player.doSweepAttack`'s hitbox, `EntityFluidInteraction.update`'s predicate,
`Entity.restituteMovementAfterCollisions`' block position — only where every other caller of the body is a method that
called the stub's signature in vanilla, so `MappedRegistry.register(int, …)`, which NeoForge's registry snapshot calls
directly, keeps fabric-registry-sync's callback off it), `MixinOverloadPin`
(a name-only `@Inject` that Mixin would bind to the other ecosystem's overload, declared first, is pinned to the one
overload its handler fits — only when the first cannot take the handler; otherwise it is explained),
`MixinMergedTwin` (`$forbricneo` renamed anonymous twins), `MixinAnonymousRetarget` + `MergedBaseAnonymousDrift`
(renumbered `Outer$N`), `MixinAtWidenedCall` and
`MixinWrapOperationShim` (calls the carrier widened or reordered; a `@Redirect` follows only a widened static call on a
reviewed `REDIRECTABLE` row, through a wrapper that drops the appended arguments — creativecore's
`RegistryFriendlyByteBuf.decorator`, whose buffers then say `ConnectionType.OTHER`, so NeoForge's connection-aware codecs
use vanilla's wire format on them), `MixinRelocatedCall`, `MixinSubtypeOwnerRetarget` (the same call made through
another owner: a subtype, or the merged type of a field the merge widened — `RangedBowAttackGoal.mob` made
`Monster.lookAt` into `Mob.lookAt` — where the point gets the ordinal of the call through the field and the handler a
guard so it runs only while the field holds vanilla's type), `MixinShearsRelay`, `MixinHandlerShim`, `MixinAtShape`
(`at=[…]` vs `at=…` across Mixin forks), `MixinLocalsCapture` (`CAPTURE_FAILHARD → CAPTURE_FAILSOFT`),
`InsertedLambdaArgumentShim`, `MergedBaseCalleeSwaps` (its `REPLACED` rows: a private vanilla method a carrier replaced
at its one call site, which `MixinRetarget`'s R7 follows — `StructureTemplate.placeEntities` → NeoForge's
`addEntitiesToWorld`, the moved HEAD handler reading vanilla's arguments off the settings; the handler-fit rule above
finds such a selector, the row only moves it),
`MergedBaseAbsorbedCalls`, `CarrierHelpers` (table `carrier-helpers.txt`), `CarrierRenames` (table
`carrier-renames.txt`: a vanilla body a carrier moved, whole or in pieces, into a same-shaped method it added, one row
per call or field access that moved with it and how often the method makes it in the mod's own game — its reference
method: vanilla's for a Fabric mod, the carrier's patched one for a Forge or NeoForge mod (where the counts differ,
the rows are split by ecosystem). `MixinRetarget`'s R3 moves a selector along it only for a mod whose own game still
has the body in place, only when every anchor is one of those calls and binds no more often than that reference method
made it (an `ordinal` only where the counts agree), and only while the merged class still calls the renamed method —
or, on the one row marked `UNCALLED`, only to bind there. That row is NeoForge's `ItemStack.addDetailsToTooltipComponents`:
vanilla's tooltip body, kept private and never called (NeoForge draws tooltips from its own appenders); the census
leaves out every renamed method that is not private or that anything in its class's nest calls. An injector moved
there never runs, and says so: MixinFit reads it as never running, and the final-class check reports it as an injector
that never runs — confirmed, not required — which marks the mod's row and stops no launch. malilib's last tooltip hook
(`onGetTooltipComponentsLast`, required) lands there, and so does trinkets' attribute-line hook when trinkets loads as
a Fabric mod (as a universal jar it loads as NeoForge by default); kept out of it, malilib's hook bound nowhere, a
confirmed required loss that stopped every strict client with malilib (Litematica, MiniHUD, Tweakeroo) and made the
default policy ask. Making such a hook run where NeoForge builds the tooltip lines is not done yet. A
row is a `RENAME` when the renamed method makes every call and field access of the reference method, as often, a
`PIECE` otherwise (NeoForge's `addDetailsToTooltipTail`, the `startSleepInBed` lambda, the four HUD layers); into a
piece moves only a handler that needs nothing but the call: not one that shares a value, captures a local or uses a
slice, and one that takes the method's arguments only when the method hands the piece its own and never stores into
them first. A handler that can cancel (a cancellable `@Inject`, a `@Cancellable` callback) moves into a piece only
when the merged method returns the piece's `Either` lefts — a `LEFT` row, re-checked on the live bytes: NeoForge's
`startSleepInBed` passes its lambda's answer through `EventHooks.canPlayerStartSleeping` and returns it when it names
a problem — and every value the handler can cancel with is an `Either.left(..)`, read through the lambdas and private
helpers of its own mixin. apoli-legacy's avian sleep veto and Fabric API's sleep-direction veto
(`MODIFY_SLEEPING_DIRECTION`) cancel the lambda that way, so NeoForge's `CanPlayerSleepEvent` sees their problem as it
sees vanilla's and `startSleepInBed` returns it. apoli cancels with `Either.left(null)`, which that hook cannot read:
it throws inside `startSleepInBed`. On vanilla's `BedBlock` path both games fail (on apoli's own, `BedBlock` throws on
the null problem's `message()`), but on Forbric a caller that checks for a null problem — another mod's sleeping bag
or bed — fails too, as it did before the census. On a whole `RENAME` a handler that shares a value moves only with
every handler of its mixin that shares it in the same method. On the bytes alone R3 had moved injectors into unrelated
methods of the same shape (text_styles' colour hook onto the shadow colour, ViaFabricPlus' item-use and hotbar-key
hooks into other vanilla methods, goldenpotions' tab icon into another tab's lambda) and into the renamed tooltip body,
where they read as fitting. Those four no longer move by R3: ViaFabricPlus 5.0.2's two are redirects of calls NeoForge
replaced in place, which `ReplacedCallRedirects` moves instead (below). `-Dforbric.mixinRetarget.renameCensus=off` moves on
the bytes alone again, `-Dforbric.mixinRetarget.renameCensus.leftExit=off` keeps every handler that can cancel out of a
piece, and `-Dforbric.mixinRetarget.renameCensus.uncalled=off` keeps every injector out of the `UNCALLED` body), `FabricClientMixinAnchors` (an empty redirect of a call the
carrier widened, found from the source contract), and `MixinOperationSeamTransport`:
a Fabric callback just before or after a call the mod's own game makes in the host method, where the merged host makes it
through an inherited gateway instead (`Gui.extractRenderState`'s screen draw, which the merged `Gui` makes through
`ForgeLayerInstance.drawScreen`), keeps its whole body and runs around that one call inside the gateway. A MixinExtras
`@Local` it takes goes with it only when the mod's own game proves the value: the host's own untouched argument, or the
very slot the call's operand was loaded from, where the carrier passes that operand from the gateway to the call
unchanged; the callback then receives what the merged host hands the gateway. LiquidBounce draws its whole browser menu
there, with the draw's canvas as a `@Local`; `-Dforbric.operationSeams.locals=off` leaves such callbacks where they were
written. And `ReplacedCallRedirects`: a
`@Redirect` of a vanilla call the carrier replaced in place with its own, whose handler only puts a condition around
forwarding the vanilla call, moves onto the carrier's call and forwards that one, along a row that says where the two
stand for each other, which operands carry the same values and why (ViaFabricPlus' hotbar keys —
`KeyMapping.matches` → `isActiveAndMatches`, item use — `ItemStack.isSameItem` → `CommonHooks.canContinueUsing`, with the
handler's own logic keeping vanilla's argument order, and shovel paths — `FLATTENABLES.get` → the state's
`SHOVEL_FLATTEN` modification; only for the families whose own class made the vanilla call; `-Dforbric.replacedCallRedirects=off`), and `MixinTwinRebind`: an injector written for vanilla's signature of a method
the merged base keeps but nothing in the merged game calls moves to the one overload the carrier added in its place,
along a row of `carrier-twins.txt` (`CarrierTwinCensusTest`: vanilla's method is uncalled for the mod's family and is no
carrier stub, the overload takes each of vanilla's parameters — matched one to one by local variable name and type —
and every method that family's own game called vanilla's from calls the overload in the merged base). The handler is
wrapped when it captures vanilla's arguments in other places than the overload has them: the outer takes the
overload's arguments and hands the original vanilla's. It moves only while vanilla's method is still uncalled (no
installed mod references it), only an `@Inject` capturing vanilla's arguments or none or an `@At`-driven kind whose
contract is known (never a `@ModifyVariable`) — or an `@Inject` capturing locals the old way at one `INVOKE` both
methods make exactly once, where each captured local is one the overload holds there with that name and type (read
by the moved handler as MixinExtras `@Local`s; Physics Mod's collapse hook, which captures MinecraftForge's
`removeBlock(pos, canHarvest)` locals before `Block.destroy`, now runs in NeoForge's `removeBlock(pos, state,
canHarvest, tool)`, the one the merged `destroyBlock` calls) — and only where every `INVOKE`/`FIELD` point is held as
often by both bodies. The outer handler takes the moved handler's place in the mixin's declaration order, so handlers
at one point run in the native order. NeoForge gave `ModelBlockRenderer.shouldRenderFace` the block's own position and declared it before vanilla's,
so LiquidBounce's X-Ray face test, selected by name and taking vanilla's four arguments, used to bind NeoForge's
overload, be rejected there and take the whole block-renderer mixin with it; `-Dforbric.mixinTwinRebind=off`. And
`ThinnedCallOrdinals`: where the carrier makes a vanilla call fewer times — it replaced some occurrences with calls of its
own and kept the others — an `@At(INVOKE)` ordinal counted on vanilla's body is re-counted onto the merged occurrence
that is the same call, along a reviewed row that maps each of vanilla's occurrences to the kept one or to none and names
the call each kept occurrence is followed by (checked on both jars, and again on the live method). ViaFabricPlus' 1.12.2
placement hook sits before the third `ItemStack.isEmpty()` of `MultiPlayerGameMode.performUseItemOn`; NeoForge and
MinecraftForge ask `doesSneakBypassUse` of both hand stacks where vanilla asked `isEmpty`, so the merged body keeps only
the third. Only for the families compiled against vanilla's count; `-Dforbric.thinnedCallOrdinals=off`.
`GuestInjectorPruner` (COREMOD) trims individual injectors from a guest mixin
class where the kernel replaces their function, and, at the end of the bytecode provider's adapters, the injectors the
verdict found Mixin would reject outright (§7.3) — each only while the same rule still says so of the node Mixin is about
to receive, so an injector an adapter already moved where it fits stays. Several adapters read shipped tables under
`src/main/resources/net/forbric/kernel/mixin/` (`carrier-helpers.txt`, `carrier-renames.txt`, `carrier-stubs.txt`,
`carrier-twins.txt`, `lambda-permutations.txt`, `uncalled-methods.txt`, `native-only-methods.txt`); `CarrierHelperCensusTest`,
`CarrierRenameCensusTest`, `CarrierTwinCensusTest`, `UncalledMethodCensusTest` and `NativeOnlyMethodsCensusTest` re-derive
`carrier-helpers.txt`, `carrier-renames.txt`, `carrier-twins.txt`, `uncalled-methods.txt` and `native-only-methods.txt`
from the staged jars (`native-only-methods.txt` from the merged base, both patched games and vanilla's own jar) and pin
them. A wrapper that renames a handler's body aside
(`MixinRetarget`'s guard and R7, `MixinAtWidenedCall`'s redirect, `MixinSubtypeOwnerRetarget`'s guard) adds a mark of
the mixin class to the name, so two mixins on one target with the same handler name do not merge into one body.
`MixinFitLivenessCensusStagedTest` also keeps a census of the anchors a Fabric mixin names that resolve on stock 26.2
and not on the merged base as the kernel judges it. Only fabric-api's lines are asserted (a new one fails the build);
any other corpus named in `FORBRIC_ANCHOR_PACKS` is report-only (`build/reports/vanilla-anchor-census.txt`) — nothing
fails on a third-party mod's line, so someone has to read the report.

#### 7.4.1 Native equivalence — general mechanisms instead of special cases

Moving an injector to where the merged base put the code is not yet what the mod gets natively. The injector has to
land on the instruction it lands on in the mod's own game, run on exactly the executions it runs on there, and see
the values it sees there. The mechanisms below do that. They replaced the per-surface Fabric adapters
(`FabricBlockBreakMixinAdapter`, `FabricEntityMixinAnchors`, `FabricEnchantmentMixinAdapter`,
`FabricMiningMixinAdapter`, `FabricSoundMixinAdapter`, `FabricServerLanguageMixinAdapter`,
`FabricSectionCompilerMixinAdapter`, `FabricFluidFlowMixinAdapter`), the fluid-interaction and breathing adapters, the
sound-contract and clear-all-veto rewrites, and every adapter that recognised one mod's mixin, handler or `@Local`
name, one mod's bytecode template or a library's body by SHA-256. None of them is triggered by a name: each compares
two bodies, the merged method as Mixin will receive it and the *native body* — the platform's own bytes of that method,
read from the merged base's `native-reference` directories (§2.3), the platform being the one of the mod's own
ecosystem (`MixinConfigOwners`).

- **Reading two bodies.** `CodeAlignment` maps each native instruction to the merged instruction that is the same step
  of the same program (one a carrier removed or rewrote maps to nothing); `ReturnAlignment` says which merged return
  is which native return; `HandlerFlow` reads what a handler computes by data flow, so a contract holds for the
  handler however it was compiled and fails for one that does anything more (instruction-count templates are gone);
  `OperandStack`; `DeliveryBookkeeping` hands every adapter that decides where a point lands the game's own code,
  without the kernel's delivery bookkeeping (§8.1) in the way; `MergedSourceRenames` compares a transported body under
  the names Mixin gave its lambdas and helpers.
- **Where a point lands.** `NativeOccurrences`/`NativeOccurrencePoint` count a call, field, store or expression point
  over the native body and select only the merged occurrences that are its native ones, however many the carriers'
  code added, dropped or reordered (`AddedCallOccurrences` numbers an unnumbered point the same way);
  `NativeSliceOccurrences` finds a sliced point's call again by its operands and conditions where a carrier moved it
  out of the slice; `MixinReturnOrdinals` counts a `RETURN` ordinal by which way out it is, not by position;
  `MixinShiftedPoints` lands `shift = BY` on the instruction it lands on natively; `JoinedReturnExits` /
  `MixinKeyActionAdapter` handle exits a carrier joined into one hook call before the final return (found also across
  the return block `VanillaEarlyReturns` puts in front of the tail; and a global key — F2, F11 — that the carrier sends
  into its hook where vanilla returned once `handleGlobalKeyPress` answered true keeps the final-return hooks off it).
  A native occurrence whose operand the carrier changed is paired with its merged counterpart by the aligned consumer
  of its value (Mod Menu's constant hook wraps three buttons, as natively, not NeoForge's mods button as a fourth).
- **Where the code went.** `MixinStubRebind` (now also with slices, a state-sharing redirect together with its siblings,
  a head argument modifier together with the head callback it reads, and a `@ModifyVariable` whose slot is pinned by
  the value it holds natively; judged at the Mixin level the mod is woven at), `MixinBuilderStubRebind` (a vanilla
  signature kept as a builder stub), `MixinTwinRebind` (above), `MixinRenamedBodyEdges` (an edge injector on a body a
  carrier renamed also reaches each method that only forwards to it), `MixinSplitBodyEdges` + `SplitBodyRuns` (the
  start and end of a method a carrier split into pieces are the start of the first piece and the end of the last, and
  a cancel at the start skips the later pieces), `MixinForgeModelDataEdge`, `NativeCallInlining` (a carrier helper is
  inlined back where a mod's injector names a call the helper took), `MixinFieldInitialiserPlacement` (a mixin field
  initialiser right after `super()`), `MixinExpressionThunks` (a MixinExtras expression still finds a method reference
  the kernel routed through a thunk). A moved handler keeps its place in the mixin's declaration order.
- **Decisions the carrier replaced.** `CarrierDecisions` (an injector at a vanilla decision moves onto the decision the
  carrier makes in its place, its answer's polarity mapped both ways), `CarrierGuardedPoints` (an `@Inject` the carrier
  put behind a branch of its own moves back ahead of it), `CarrierDefaultChains` (a redirect of a vanilla call the
  carrier now makes through an inherited implementation), `CarrierHelperResults` (an `@Inject` at a vanilla call the
  carrier now makes inside a helper runs on the helper's answer, where that answer is proved to say whether the call
  was made; inside a body the carrier renamed whole, the renamed-body rule takes precedence), `CarrierReturnedCalls`
  (a wrap of a call whose answer was the method's answer), `CarrierMethodReferences` (a redirect inside a native lambda
  the carrier replaced with a method reference; it reads the value the reference folds in by the chain's own rule and
  says why when it declines — fabric-item-api's enchanting check, with Origins Legacy installed),
  `UncalledDecisionEntries` (a `HEAD` injector on a static decision nothing in the merged game calls any more wraps the
  carrier's own decision at each place the native game asked it, handed the native arguments, its cancel the answer
  there — Create's redstone connection on `RedStoneWireBlock.shouldConnectTo`, where NeoForge asks the neighbour's
  `canRedstoneConnectTo`; two mixins' hooks on one such decision become two wraps, so they run in the reverse of the
  native order and a cancel by the first no longer skips the second), `ReplacedDecisionValues` (a `@ModifyExpressionValue` of a vanilla
  decision), `NativeEntryRelay` (a native decision method called again so an injector at its entry runs),
  `NativeDecisionIsland` + `NativeDecisionRelay` (the native method's decisions put back ahead of the carrier hook that
  replaced them, their answers handed on to the carrier's code), `CallWitness` (a handler replayed after a relocated
  call runs only when the call was really reached).
- **Values and locals.** `NativeValueStores` (a `STORE` modifier at the store of the native value where a carrier
  stores another value first), `NativeConsumerStores` (a modifier written for a local's one native store, moved to the
  native read right after it), `NativeDeadValueConsumers` + `NativeValueRelay` (a value a guest changed is handed to
  the carrier's own consumer where the merged method now computes it for nothing), `NativeLocalPins` (a by-type
  `@Local` points at the local its own loader hands it), `NativeLocalMaterialization`, `MixinCapturedLocals` (a
  `locals = CAPTURE_*` capture becomes `@Local`s that read the native locals), `MixinCameraRollAdapter`'s argument
  lists (an argument-list hook moves from the call its slice picks natively to the widened call that plays its role),
  and `MixinDelegationEquivalence` (report-only proof that an argument modifier with nothing to attach to loses
  nothing).
- **A method nothing calls, whose value the carrier computes elsewhere.** `UncalledValueRelay` runs after Mixin, not
  on the mixin: when guest hooks were woven into a vanilla method the census lists as never called, and that method's
  value is one the carrier computes in a place of its own, the woven body is copied into a relay of the same class
  with three calls replaced by the kernel's — the raw draw answers nothing (the carrier already drew), the processing
  answers the carrier's value, the effect records its amount instead of doing it — and the carrier's place runs it.
  Every hook stays where Mixin put it and runs in its order. One contract today: `Block.tryDropExperience`, whose
  value is NeoForge's `BlockDropsEvent` experience (§8). The relay is made only for hooks of an ecosystem whose own
  game calls the method (a NeoForge hook there stays as dead as it is natively), only when the draw hands its value
  straight to the processing, and a hook handed the method's provider gets what the native caller passed (a field of
  the block, a static field, or a static call on constants, read off the caller's native body); anything else stays
  as woven and is said in a WARN, and the final-class check still reports it.
- **Names a rule sets a body aside under.** A rule that keeps a mod's handler body as a plain method under a wrapper
  names it after the handler, the rule and the mixin (`MixinHandlerShim.asideName`; `markAsides` gives every
  `$forbricOriginal` body its mixin's mark once all rules have run). Mixin merges a plain method into the target by
  name: two mixins whose handlers share a name used to share one body, and the second wrapper ran the first mixin's
  ("Method overwrite conflict"; with a cancellable and a non-cancellable hook, the game method threw).

Each has an `-Dforbric.<name>=off` switch (§17) and a weave test that runs a synthetic mod with unrelated names through
the real Mixin and MixinExtras with the switch on and off (`src/test/java/net/forbric/kernel/mixin/weave/`, 164 files).
A mechanism counts as done only when the same mod also passes the rename experiment and the native comparison of §16.1.

### 7.5 Attribution

`MixinConfigOwners` maps each config to its mod before registration, so Mixin's own failures name the mod (and
`-Dforbric.mixinModIdDecoration` puts the mod id into generated handler names). `KernelMixinErrorHandler` puts
prepare/apply failures on the mod's row without changing Mixin's decision. `FinalMixinApplications` observes each
defined class after all stages — zero references to a handler prove it did not attach. `SupersededMixins` keeps a
failure off the mod's row when a named kernel repair does *everything* that mixin did; a mixin removed before Mixin
read its config, whose repair has a witness, is a `SUSPECTED` note until a witness class is defined — `RESOLVED` when
the repair is seen there, `CONFIRMED` when it is not or can no longer be proved (`LootSourceContracts.disproved`) — so
fabric-loot-api-v3 no longer reads "did not finish loading" until the first world loads; `PluginDeclinedMixins` when
the mod's own config plugin would have declined it; `ForeignMixinBreaks` records mixins written to attach to
another mod that did not. `MixinCompatibility` carries one identity for a mixin from preflight to application.

### 7.6 Cross-mod overlaps — `MixinOverlapLint`

`MixinFit` judges one mixin against the base; two mods that each fit can still collide. `MixinOverlapLint` lists
every handler's claim (target method, `@At` call, ordinal) and pairs claims of different mods — a bundled module
counts as its installed jar, and two jars of one mod id as one mod:

| Rule | Pair | Kind |
| --- | --- | --- |
| R1 | two `@Overwrite` of one method — one body survives: the higher priority, or the first at equal priority | conflict |
| R2 | two `@Redirect` of one call, equal or open ordinals — Mixin keeps one | conflict |
| R3 | an `@Overwrite` and another mod's injector in that method | conflict |
| R4 | a `@Redirect` and another mod's `@WrapOperation`/`@ModifyExpressionValue` on one call | note |

A wildcard/regex selector or a bare name on an unreadable target makes no claim; slices are not read. At boot
(§3.2 step 17) it reads each config as `ForbricMixinService` served it to Mixin, after the kernel's drops, and
records one `SUSPECTED` finding per mod and conflict, id `mixin-overlap:<owner>.<name><desc>[@<at>]`, the detail
naming the other mod; it logs counts and elapsed ms (`-Dforbric.mixinOverlapLint=off` skips it). When a crash
stack passes through a method with a recorded conflict, `CrashAttribution` names both mods. Offline:
`MixinOverlapLint <merged-base.jar> <mods-dir> [--json out]` (jars found recursively).

## 8. Event bridges

Where the two Forge families' hooks competed for one call site and the merge kept one, the loser's hook is dead code,
and that family's listeners sit on a bus nobody posts to. A MinecraftForge mod needs an instance of exactly
`net.minecraftforge.…Event`, so re-emission is intrinsic. Where the edit splice kept both hooks (§2.4), the game posts
both events itself, and a bridge must not post the same one again (§8.1).

- **Inventory** — `net.forbric.api.GameEventBridge` enumerates 106 bridges, each with the event, its install pass
  and its **cost** in player terms. Passes: `GAME_BUS` (both sides), `CLIENT_GAME_BUS`, `CLIENT_MOD_BUS`,
  `CLIENT_INIT`, `REGISTRATION`, `CLIENT_HUD`, `ON_DEMAND`.
- **Verification** — `net.forbric.api.EventBridges.verify(pass)` compares achieved against declared and names what
  is missing with its cost; a bridge that fails to install costs a feature and throws nothing, so this is the only
  way it becomes visible.
- **Implementation** — `boot.GameEventMultiplexer` installs the bus bridges; the game-side halves are
  `runtime.KernelGame*Events` (tick, server lifecycle, player, level, world, block, entity, damage, tracking,
  client tick/render/input/network/resource/screen-mouse events) and `KernelGameResultBridges` for the two whose
  MinecraftForge side returns a value. Cancellable events forward the cancellation back. Transformers land the
  bridges that are not bus-to-bus (`ForgeDamageSeamsInjector`, `ForgeCreativeTabsInjector`,
  `ForgeSpawnPlacementsInjector`, `ForgeClientConsumersInjector`, `ForgeBlockTintInjector`,
  `ForgeOverlayNeuterInjector`/`KernelForgeOverlayLayers`, …).
- **The other directions** — NeoForge events the merged body no longer posts (`ItemTooltipEvent` via
  `KernelItemTooltips`, `ScreenEvent.Opening/Closing` via `NeoScreenEventsInjector`, conversion `Post` via
  `NeoConversionPostInjector`); Fabric API events fired from NeoForge's own sites where Fabric's mixin cannot fit
  (`LootTableEventBridgeInjector` + `LootTableEventDispatch` for `LootTableEvents`, `FabricFuelValuesInjector`, the
  tooltip bridge). The merged server builds its fuel table in `populateFuelValues`; the fuel bridge hands that builder
  to the restored `vanillaBurnTimes` facade, which jumps straight to its own `build()`, so a mod's wrap of that build
  (Create's blaze cake, cardboard) adds its fuels once and the client's data-map table carries them. The Fabric half of `KernelHudBridge`, which redrew fabric-api's `HudElementRegistry` roots through
  its implementation classes, is retired: fabric-rendering-v1's own `HudMixin` wraps now land, in the body the kernel
  gives a method reference (`LambdaInvocationThunkInjector`), and the bridge had bridged 0 of 21 roots in every run.
  MinecraftForge's `PlayLevelSoundEvent`s are forwarded from NeoForge's wherever the merged game plays a sound, with the
  answer carried back (`KernelGameSoundEvents`).
- **Experience from a break** — NeoForge computes it in `BlockDropsEvent` and pops it once in `handleBlockDrops`
  (a player's break reaches that since the merge's delegate-twin pass, §2.4); MinecraftForge computes it in its
  `BreakEvent` and popped it in its own `destroyBlock`, which is not the merged one; Fabric mods hook vanilla's
  `Block.tryDropExperience`, which nothing in the merged game calls. `KernelGameBlockEvents` hands a MinecraftForge
  decision (a listener's `setExpToDrop`, or a block whose experience only MinecraftForge's `getExpDrop` answers) to the
  same break's `BlockDropsEvent` at `HIGHEST`, and at `LOWEST` runs the relay of `tryDropExperience`
  (`UncalledValueRelay`, §7.4) for a block whose native `spawnAfterBreak` asked it (the census's callers, through a
  class that only adds to its parent's), handing it the event's value and dropping what it would have popped. So a
  Fabric mod's replaced or cancelled ore experience, or PuzzlesLib's experience event, decides the experience once,
  where NeoForge pops it. Not equal: a MinecraftForge listener reading `getExpToDrop` sees MinecraftForge's own draw,
  not the one NeoForge drops (a listener that sets it decides correctly); a NeoForge listener at `LOWEST` registered
  after the kernel's sees the relayed value, one registered before sees the value before it.
- **Breathing** — the merged `baseTick` hands the air only to NeoForge's hook; MinecraftForge's
  (`ForgeHooks.onLivingBreathe`) has no caller, so a MinecraftForge `LivingBreatheEvent` listener never ran.
  `KernelGameWorldEvents.installBreathe` posts MinecraftForge's event from NeoForge's at `LOWEST`, only while a
  MinecraftForge listener exists (it is every living entity, every tick). MinecraftForge's event has a flag NeoForge's
  has not, `canRefillAir`: where NeoForge's answer refills something it is true; where it refills nothing it is
  MinecraftForge's own rule (the eye in air or a bubble column, or effects other than Breath of the Nautilus alone),
  and the amount offered is what vanilla would refill, unless that rule says it may refill, where NeoForge's nothing
  stands. Only what a MinecraftForge listener changed is written back — whether the body breathes, the air it loses,
  and for a breathing body the air it regains (none where it may not refill) — so an event no MinecraftForge listener
  touched stays exactly as NeoForge's listeners left it (`ForgeBreath.of` and `carryBack`, driven by
  `KernelBreatheBridgeTest`). With the vanilla refill (§6) NeoForge's answer already refills wherever vanilla does, so
  a MinecraftForge listener reads `canRefillAir` true there, also in lava or a breathable fluid with Breath of the
  Nautilus alone, where native MinecraftForge reads false and holds the air. gate-m57 pins that difference in
  MinecraftForge's `canDrown(false)` liquid (the body refills as in open air natively, its MinecraftForge listener
  reads true, and the native reference must still hold the air there), and plays each clause of MinecraftForge's rule
  as the only one that says yes where a NeoForge listener refills nothing: water breathing in water (the effects),
  Breath of the Nautilus alone in open air (the eye in air), and in a bubble column. A NeoForge listener at `LOWEST`
  registered after the kernel's sees the MinecraftForge listeners' changes, one registered before does not. The event
  has a `BRIDGED` row and a `ForeignType` row (`LIVING_BREATHE_EVENT`); the bridge's only switch is the global
  `-Dforbric.unifiedEvents=off`.
- **Drowning** — the same hook posts NeoForge's `LivingDrownEvent`; MinecraftForge's (constructed inline in
  `ForgeHooks.onLivingBreathe`, and by `ForgeEventFactory.onLivingDrown`, which nothing calls natively either) had no
  poster, so a MinecraftForge mod that stops drowning or changes its damage did nothing.
  `KernelGameWorldEvents.installDrown` posts it from NeoForge's at `LOWEST`, only where NeoForge's answer is that the
  body drowns and a MinecraftForge listener exists. That is where MinecraftForge asks natively: its hook posts once the
  air is down to -20, the point at which NeoForge's event starts out drowning (NeoForge posts from air 0, not yet
  drowning, and that is not handed on), always with a drowning body; here the damage and bubbles are NeoForge's
  (vanilla's 2 and 8 unless a NeoForge listener changed them). It is posted with `BUS.post`, as MinecraftForge's hook
  does, because EventBus 7 keeps a cancel in what `post` returns, not on the event (`onLivingDrown` fires it and drops
  the cancel). A cancel is carried back as NeoForge's cancel and nothing else — natively nothing else of a cancelled
  drown event is read; otherwise whether the body drowns and the damage are written only where a MinecraftForge
  listener changed them (`ForgeDrown.of` and `carryBack`, driven on real buses by `KernelDrownBridgeTest`). The bubble
  count is handed to MinecraftForge's listeners and never carried back: MinecraftForge's hook never reads it
  (`ForgeHooks.onLivingBreathe` bc 198–282 has no `getBubbleCount`), while NeoForge's draws six `nextDouble`s from the
  body's random source per bubble (bc 246–368) before it asks the level for a particle, which a server level ignores.
  A written-back count would change that body's random sequence — the pitch of the hurt sound in the same tick, its
  later AI — and, set large, the server thread's work, where natively the change does nothing (gate-m57 judges this in
  game: a MinecraftForge bubble count of 0 or 50 must leave the body's random draws as they are). A NeoForge listener's
  cancel keeps MinecraftForge from being asked, as with every cancellable bridge here. As for breathing, the NeoForge
  listeners that answer before MinecraftForge's are every one above `LOWEST` and every one at `LOWEST` registered
  before the kernel's bridges (a constructor, an `@EventBusSubscriber` class); one added at `LOWEST` later, from a
  setup event say, is asked after MinecraftForge's, and what it sets is what the hook reads (measured, §19). gate-m57
  holds the first half both ways: its NeoForge canary registers a breathe and a drown listener at `LOW` from common
  setup, after the bridges (a bridge above `LOWEST` would answer before them), and one of each at `LOWEST` in its
  constructor, before them (bridges installed before the mods are constructed would answer before those); a tick
  where MinecraftForge's listener answers before any of them fails it, and so does a row where both answered with no
  tick recording which answered first. `BRIDGED`
  and `ForeignType` (`LIVING_DROWN_EVENT`) rows as for breathing; the switch is again only
  `-Dforbric.unifiedEvents=off`. Both bridges, with MinecraftForge's field getters and its fluids' own types (§5.3),
  are measured in game against the native loaders by gate-m57 (§16, results in §19).
- **Audits** — `HookCallSiteCensus` (which hooks of both families' hook classes the merged base still calls, and
  which events are therefore never posted), `DeadEventAudit` + `ForgeBusSubscriptions` (who listens to a dead
  event, including subscriptions an annotation scan cannot see), `DeadHookWorklist` (the join, ordered by how many
  installed jars notice), `EventChainAudit` (every cross-bus post, checked by one rule set; gate-m41).
  `HookCallSiteCensus.all` judges every hook class at once: a hook with no call site in the game still counts as
  reached when a carrier method calls it, but a caller that is itself a hook (a public static, non-synthetic method of
  a censused class) counts only when it is reached in turn, to a fixed point. Counting any carrier caller hid
  MinecraftForge's breathe event behind `ForgeHooks.onLivingBreathe`, which nothing calls. Only a constructed type whose
  supertypes reach its family's event base counts as an event. The kernel's own compiled classes are a fourth source:
  a dead hook they call, or a dead event they construct, is delivered by the kernel. `DeadEventAudit`'s rows are data,
  and `HookCallSiteCensusStagedTest` checks them against that census, over `ForgeEventFactory`,
  `ForgeEventFactoryClient`, `ForgeHooks`, `ForgeHooksClient`, `EventHooks`, `CommonHooks` and `ClientHooks`: no
  `DEAD` row may name an event the base still posts or the kernel delivers, and every event measured as never posted
  needs a `DEAD`, `BRIDGED`, `REPAIRED` or `STAND_DOWN` row. Until 2026-10-24 only the first was checked, so
  MinecraftForge's `LivingDrownEvent`, measured dead, was named nowhere. Only `DEAD` is held both ways: a
  measured-dead event in `BRIDGED`, `REPAIRED` or `STAND_DOWN` passes whether or not anything delivers it, and the
  audit names no mod for any of the three, so an event filed there by mistake is silent again (moving
  `PlayerXpEvent$XpChange` from `DEAD` to each of the three in turn leaves the suite green). Today every such row is one
  the census counts as kernel-delivered, except `GatherLoginConfigurationTasksEvent`, whose hook the kernel calls
  reflectively (`PayloadInterop`); no test requires it. And an event whose native posting sites the merge lost needs no
  row while the census still counts it as posted — through a carrier caller that is not itself a hook, or a call site
  that survived elsewhere; five such MinecraftForge events are listed in §19.
  - *What runs, not what is staged.* The census reads each game class through the merged-base repairs
    (`MergedBaseLoadTimeView` replays `ForbricMergedBaseCompatTransformer`, which writes without computing frames, so
    it needs no running game). A repair can take a hook call away: `routePlaceItemHookToNeoForge` sends
    `ItemStack.useOn`'s `ForgeHooks.onPlaceItemIntoWorld` to NeoForge's `CommonHooks`, so MinecraftForge's
    `BlockEvent$EntityMultiPlaceEvent` (whose one poster is reached only from that call) is never posted at runtime,
    while the staged jar still shows the call; read off the staged bytes, it looked posted and could have no row. It
    has a `DEAD` row now. Three other repairs move a game's hook call into the kernel (both families' `initClientHooks`
    through `KernelForgeClientInit`, NeoForge's server reload through `KernelForgeReload`, MinecraftForge's burn time
    through `KernelFuelValues`), so the eight events they post are `REPAIRED` rows. Other transformers are not
    replayed; those that touch hook calls wrap them or redirect them into the kernel, whose classes are read. A call
    is an `invoke` or a method handle (a method reference such as `ForgeEventFactory::onPreClientTick` reaches its
    hook as surely). Reflective calls stay unseen; MinecraftForge's configuration tasks are a `REPAIRED` row by hand.
    The `PARTIAL` path counts are still read off the staged bytes: of the hooks they count, the repairs move only
    `ForgeEventFactory.getItemBurnTime`, into the kernel, which still calls it.
  - *What the merge lost, not what was never there.* Every family's hooks are also censused against that family's own
    patched game (the `forge-patched` and `neoforge-patched` jars the merge read). An event dead there too has no
    row, and no table may name one: a `DEAD` row would mark a mod DEGRADED for a loss its own loader has, and a bridge
    would post what the native loader never does. MinecraftForge 26.2-65.0.1 itself never posts
    `PotionBrewEvent$Pre`, `EntityTeleportEvent$ChorusFruit`, `ChunkDataEvent$Load` or
    `CustomizeGuiOverlayEvent$DebugText` — each one's only poster has no caller in its patched client or server, its
    installer's jars or its runtime — and those four were `DEAD` rows for one round.
  - *Which side.* A `DEAD` or `PARTIAL` row is judged on a dedicated server only if MinecraftForge posts it there:
    `DeadEventAudit.CLIENT_ONLY` lists the rows every one of whose native posting sites — the methods of the family's
    patched game whose calls reach a poster — carries the `@OnlyIn(Dist.CLIENT)` the families' client/server merge
    puts on what only the client jar has (on the method or its class); the staged test recomputes the set. It
    replaced a package rule, which named a mod DEGRADED on every dedicated server for
    `PlayerInteractEvent$LeftClickEmpty`, a common-package event posted from `Minecraft` alone.
  - *Guests that post.* A hook is public API, and a mod may call it: Tombstone swaps in its own phantom spawner, which
    calls `ForgeEventFactory.onPlayerSpawnPhantom`, so in its pack `PlayerSpawnPhantomsEvent` is posted although the
    merged game never posts it. Before naming a mod for a `DEAD` event, `DeadEventAudit.report` asks the loaded jars
    (`GuestEventPosters`, nested jars included): a class that calls or references a hook constructing the event —
    directly or through another hook, read from the hook classes on the game's class path — or constructs the event
    itself posts it, and the audit says so in one line instead of accusing anyone. Only the constant pools are read
    unless one names a hook class or the event; asked about all 51 `DEAD` rows at once, offline, it took 0.3–0.7 s on
    the four local packs, and at boot it runs only when a mod listens for a `DEAD` event. A guest posts the event on its own paths, not necessarily the game's, so this can
    only make the audit say less. Checked on a dedicated server: a mod whose subscriber listens for
    `PlayerSpawnPhantomsEvent` is named; beside a second mod that only calls `onPlayerSpawnPhantom`, it is not, and the
    line names that mod as the poster. That run is the only check of the boot-time path: `GuestEventPostersTest`
    drives the scan with the hook classes handed to it, and no test covers the call in `KernelEventSubscribers` or how
    it reads the hook classes (unwiring either leaves the suite green).

### 8.1 One delivery per native dispatch — stand-down

A bridge listens to a NeoForge event and posts the MinecraftForge one. A merged method that now calls both families'
hooks on one path posts the MinecraftForge event natively *and* through the bridge. Measured with the event-chain
audit on the first edit-spliced base, every MinecraftForge mod got the server, player, client and render ticks, each
level's load, save and unload, log-in and log-out, system chat, mouse buttons, fog and block overlays twice. Unit tests
and gate checks of the form "at least once" never saw it; the accounting below is per dispatch.

- **Scoping the native delivery.** `transform.NativeDualEventInjector` finds, in each game method, the MinecraftForge
  deliveries (a static call into a MinecraftForge hook whose body constructs and posts a MinecraftForge event, or an
  inline post) and the NeoForge ones, looking only for the MinecraftForge events some bridge re-posts. It brackets the
  NeoForge call in a scope (`net.forbric.api.NativeEventDelivery`) naming the MinecraftForge events delivered natively
  on that path: *proved* when the MinecraftForge delivery dominates the NeoForge one or post-dominates its completion;
  *recorded* when it can run earlier in the same invocation (what the MinecraftForge hook actually constructed is
  committed for that invocation, so a hook that posts only under a condition covers exactly the times it delivered);
  *faithful* when it comes later on a path the caller may bypass. For the last, `NativeDeliveryPaths` aligns the
  merged body with MinecraftForge's own patched body of the same method (read from the native reference) and
  classifies each edge that commits to skipping the call: one MinecraftForge's body skips on too (a shared guard)
  excuses the event; one only the merged game has (an exit NeoForge's patch added) owes it, and the bridge's deferred
  forward is delivered there (`NativeEventDelivery.bypassed`). What a caller records and owes belongs to one invocation
  (`NativeEventDelivery.Invocation`, kept in a local of the caller), so a caller re-entered from its own dispatch
  settles only its own. The cascade is accounted too: what each family's own listeners post inside its dispatch (the
  client-command registration MinecraftForge's own login listener posts) counts as delivered for the bridge of the
  counterpart. Events are matched by identity, never by class, and nothing keys on a caller, event or mod name.
- **The bridge side.** `GameEventMultiplexer.guarded` makes a bridge ask `NativeEventDelivery.covered` with the event it
  was handed and stand down for exactly that event. `DeadEventAudit.STAND_DOWN` lists, per bridge, the MinecraftForge
  events its forward constructs (44 rows), beside the `BRIDGED` rows the dead-event judgement uses; a bridge with
  neither could never stand down. NeoForge's mod-bus dispatcher counts as a post, so the two mod-bus forwards
  (`TextureStitchEvent.Post`, `ModelEvent.BakingCompleted`) stand down too. Repairs that add a MinecraftForge call
  beside a NeoForge one stand down only for a MinecraftForge call on the same object and the same path
  (`NativeCallPairing`), not for its mere presence.
- **Not in the way of mixins.** Each scope or recording opens right before the very hook call or post it is for and
  closes right after it (and on an exception out of it), so a guest `@At("INVOKE")` on that call still finds it, and
  every adapter that decides where a guest point lands reads the method without the bookkeeping
  (`DeliveryBookkeeping`, §7.4.1).
- **Accounting.** `EventChainAudit` records the game method and instruction that posted each event, credits a bridge
  that stood down for the game's own delivery, and counts a forward the game also delivered as delivered twice;
  gate-m41 requires every required bridge's posts, post by post, against MinecraftForge's bus by count, and accounts
  for every stand-down by how it ended. A body that cannot be judged (MinecraftForge's own body unreadable, a NeoForge
  post repeated in a loop, different branches) keeps its forward.

## 9. Datapacks, resources and data

### 9.1 Server data

A genuine loader turns every mod jar into a pack by walking `ModList`'s mod files. The kernel publishes mods into
`ModList` with `modFiles` empty, so that walk finds nothing. `DataPackHookInjector` →
`KernelLifecycle.onServerDataPacks` → `KernelDataPacks` / `runtime.KernelDataPackSource` serve each Forge-family
jar's `data/` — and both carriers' (`c:` convention tags, `neoforge:` damage types and data maps exist only there).
Metadata is read through NeoForge's `ResourcePackLoader.readWithOptionalMeta`, keeping root-pack overlays. Where
the carriers ship the same file, tags are additive and NeoForge wins last-wins files. Fabric mods' data is served
by fabric-api's own resource loader as on Fabric. `KernelPackFinders` lets a MinecraftForge mod add its own pack
finder (`-Dforbric.modDataPacks=off` disables the Forge-family packs). Each served jar declares the known pack its own
platform declares (NeoForge: `KnownPack("neoforge", "mod/<ids>", <versions>)` for every mod file, the carrier
included; MinecraftForge: none), so the NeoForge carrier's data-driven damage type no longer makes every world
experimental and asks the player for a backup.

### 9.2 Client assets

NeoForge's `mod_resources` source is orphaned on the merged base. `ClientPackHookInjector` redirects the body of
`ClientModLoader.setupModResourcePacks(PackRepository)` to `KernelLifecycle.onClientResourcePacks`, where
`KernelClientPacks` adds one pack per ecosystem jar (compatibility forced to `COMPATIBLE`, as both genuine loaders
do) before the first reload. `PackScreenHiddenFilterInjector` keeps those packs out of the resource-pack screen.
The kernel's own assets (the Mods button icon) ride in `forbric-kernel-runtime.jar`.

### 9.3 Pack metadata written for three loaders at once

A multiloader `pack.mcmeta` carries a section per loader, and on Forbric all three parsers read it.
`KernelPackMetadata` + `PackMetadataFailSoftInjector` keep an unparsable foreign section from dropping the whole
pack; `PackOverlayMutabilityInjector` (repair) and `NullPackGuardInjector` (backstop) handle NeoForge's overlay
merge mutating a list fabric-api has frozen (`KernelPackRepair` documents both).

### 9.4 Conditions, registries, data maps, worldgen

- **Resource conditions** — three evaluators, because the merged `RegistryLoadTask` carries both families' patches:
  `runtime.KernelFabricConditions` (`fabric:load_conditions`, judged with fabric-api's public
  `ResourceCondition.CONDITION_CODEC` and `test()`; fabric-api's own two mixins cannot fit), `KernelNeoConditions`,
  `KernelForgeConditions` — each keeps one dialect from failing another's files. `forge:condition` is judged only
  inside MinecraftForge's own two readers (the data-file scan, through `ForgeHooks.readConditional`, and the
  datapack-registry element decoder, through `ConditionCodec.wrap`), where NeoForge's `ConditionalOps` funnel asks it
  again, with what genuine MinecraftForge judges it with: recipes against this reload's tags, a loot pool's condition
  in place, `forge:conditional` variants. A condition type nobody registers is false in a data file, as on
  MinecraftForge; one another ecosystem registers is that ecosystem's to decide; on a registry element, where
  MinecraftForge's answer would lose the world, it loads and the log names the type
  (`-Dforbric.forgeConditionFunnel=off`).
- **Registry directories** — `RegistryDirectoryOwnerInjector` / `KernelRegistryDirectories`: `registryDirPath`
  answers as the owning ecosystem does. **Aliases** — `RegistryAliasParityInjector` / `KernelRegistryAliases`.
  **Reflection** — `RegistryWrapperAccessInjector` makes MinecraftForge's `NamespacedWrapper` and
  `NamespacedDefaultedWrapper` (27 builtin registries on the merged game, block and item among them, plus
  MinecraftForge's own three) public as they load, as the `MappedRegistry` they stand in for is, so a method a mod
  looks up on `registry.getClass()` can be invoked (`-Dforbric.publicRegistryWrappers=off`). `Class.getMethod` also
  resolves the types of every public method of each class it searches (from the runtime class up to the first that
  declares a match; `getMethods` searches them all), so `RegistrySyncParityInjector` adds fabric-api's
  `remap(Object2IntMap, RemapMode)` to the wrapper only when the game loader has those types
  (`RegistrySyncParityInjector.forGameLoader`). Without fabric-api that method named a class that is not there, and
  `getMethods()` and every lookup that reached `NamespacedWrapper` (`getOptional`, `keySet`, …) threw
  `NoClassDefFoundError`.
- **Block → item** — `Block.asItem()` reads `Item.BY_BLOCK`. On MinecraftForge that map is the `block_to_item` map of
  the live (`RegistryManager.ACTIVE`) item registry, which the item add-callback fills through each `BlockItem`'s
  `registerBlocks` as items are added — the builtin ones inside `Bootstrap` — so MinecraftForge's `Items.registerItem`
  does not link them itself. The merged `Items` is MinecraftForge's, but the merged `Item` is NeoForge's and takes
  `BY_BLOCK` from NeoForge's `GameData.getBlockItemMap()`, so the callback filled a map nothing read, and every block
  answered AIR until the registration window closed — after the Fabric `main` entrypoints; `asItem()` caches its first
  answer, so a block asked by then kept AIR. `BlockItemLinkInjector` makes `GameData$ItemCallbacks.onCreate` give the
  live registry NeoForge's map (`KernelBlockItemLinks`; every other stage keeps a map of its own), so every add links
  where `asItem()` looks: the builtin items, a Fabric mod's `Registry.register` and NeoForge's `RegisterEvent` through
  the wrapper, a MinecraftForge mod's `RegisterEvent` and the client's held-back MinecraftForge mods straight into the
  `ForgeRegistry`. An override unlinks the item it replaces, and a `RegistryEntryAddedCallback` listener already sees
  the link. MinecraftForge clears that map and re-adds every entry when it re-syncs the live registry (world close, a
  save's or a server's ids); `onClear` now leaves the shared map alone, which Fabric never clears, and the re-adds
  relink every block item anyway. An entry other code put there by hand survives a re-sync only for a block that no
  registered `BlockItem` claims; a remap of a registered block's item is overwritten by the re-add, as on
  MinecraftForge. The sweep when the window closes
  stays as a net and normally links nothing (`-Dforbric.blockItemLinks=off`).
- **Datapack registries** — `KernelLifecycle.registerDataPackRegistries` posts `DataPackRegistryEvent.NewRegistry`,
  declares MinecraftForge's biome/structure modifier registries and mirrors Fabric dynamic registries both ways.
- **Data maps** — NeoForge data maps are loaded (`KernelNeoDataMapWatch`, `KernelNeoWorldgen`). Every holder-based
  lookup ends in `Holder.Reference.getData`, which asked `key()` and threw `Trying to access unbound value` for a value
  not registered yet; vanilla's oxidation, waxing and stripping maps answer for any block. `UnboundHolderDataInjector`
  makes an unbound holder answer "no data" (a value with no key is in no data map), so NeoForge's hooks fall back to
  vanilla's maps, as a Fabric mod calling them from its initializer expects (`-Dforbric.unboundHolderData=off`). This
  differs from native NeoForge, which throws there at any time. While the value's registry is open the answer is
  silent; once any `frozen` flag of that registry is set (its `freeze()` has run, which throws "Some intrusive holders
  were not registered" for such a value), `util.KernelUnboundHolderData` WARNs once per such value (at most 64, then
  one line), naming it, its registry and the asking stack — the throw native NeoForge would have given is still reported,
  but the lookup still answers "no data". The WARN says the value was not registered when its registry closed, not
  that it never will be: a registry can be unfrozen, and Forbric reopens them for the Fabric client entrypoints.
- **Worldgen** — MinecraftForge biome/structure modifiers ride inside NeoForge's single modifier pass
  (`runtime.KernelForgeWorldgen`; `-Dforbric.forgeWorldgen=off`, with `ForgeWorldgenShippers` naming the mods
  that then lose it). `NativeCoremodParity`, `BiomeInfoRebaseInjector`, `BiomeLateWriteInjector` make the modified
  views read. gate-m31 holds zero-mod worldgen to vanilla's biomes and structure starts at the same seed.

## 10. The unified API — `net.forbric.api`

Parent-pinned (one copy per JVM), and what the kernel and all three compatibility layers speak instead of
translating pairwise:

| Type | Role |
| --- | --- |
| `Ecosystem`, `Side` | the one ecosystem and side vocabulary |
| `ForeignType` | 75 rows mapping one Forge-family concept to its MinecraftForge and NeoForge class names — names only; per-family divergence stays data |
| `DiscoveredMod`, `UnifiedDependency`, `VersionPredicate`, `ModIds` | the mod and dependency model |
| `ModPresence`, `ModCatalog` | "is X running", and the list a player sees with status `OK` / `DEGRADED` / `FAILED` |
| `GameEventBridge`, `EventBridges` | the bridge inventory and its verification |
| `NativeEventDelivery` | which MinecraftForge events the game is delivering itself on the current path, so a bridge stands down (§8.1) |
| `ForgeLoadingList` | what MinecraftForge's `LoadingModList` is built from |
| `CompatibilityFinding`, `CompatibilityFindings` | per-launch evidence ledger (§12) |

It is not a stable API for mods.

## 11. The Mods screen

`runtime.KernelModListScreen` replaces both families' mod list screens (`ModsButtonRedirector`) and reads
`ModCatalog`, which `KernelModCatalog` fills from discovery plus a second read of each jar for description,
authors and logo. Each `ModCatalog.Entry` carries a `status` and a `statusDetail`, and names the mod that bundled
it when it came in nested.
`KernelModConfigScreens` reaches a mod's own config screen.

A Fabric mod's config screen is declared in exactly one place: a `"modmenu"` entrypoint implementing Mod Menu's
`com.terraformersmc.modmenu.api.ModMenuApi`. That interface belongs to the Mod Menu mod, so with Mod Menu absent the
entrypoint class cannot even link. When an installed Mod Menu is found, it is asked, as before. Otherwise
`boot.ModMenuApiStandIn` hands `ForbricClassLoader.putGeneratedClass` a stand-in for the five API types and the
`util.NullScreenFactory` their default returns (Mod Menu 20.0.3's exact public shape, compiled from `src/modmenuApi/java`
and shipped as `.class.bin` resources in the game-side jar). The
loader defines offered bytes only after every owned jar has missed the class, so a real Mod Menu still wins. On the client
only, and only the API package plus that one class: `isModLoaded("modmenu")` stays false and the rest of Mod Menu's
internals stay absent.
`fabric.ModMenuConfigFactories` then reads the entrypoints the way Mod Menu's initializer does: each mod's own factory,
skipped when it is an instance of the class the interface's own default returns (Mod Menu's `instanceof
NullScreenFactory`, so an override that falls back to the default is no Config button that opens nothing, and no screen
is built just to ask), then every entrypoint's `getProvidedConfigScreenFactories()` merged with `putIfAbsent` — again on
every lookup, as Mod Menu does. A broken entrypoint is skipped.
`-Dforbric.modMenuStandIn=off` restores the old behaviour, where a Fabric mod gets a Config button only from an installed
Mod Menu.

The rows come from `ui.ModListRows` (boot side, pure; `KernelModConfigScreens.rows()` feeds it): every installed jar the
catalogue has, plus every config screen a row is the only way to. The plan keeps two installed entries that share an id as
two rows, but the catalogue never hands it two: `ModCatalog.publish` collapses duplicate ids first (first one wins), and
`KernelModCatalog` appends a jar whose metadata could not be read (catalogued under the id its failure names) after every
mod that loaded. So an unreadable jar sharing a loaded jar's id has no row, and its finding is shown on the loaded jar's
row instead (`CompatibilityFindings.project` matches by id) — an open defect: the catalogue's identity is the mod id, not
the jar. A mod a jar carries
inside itself gets a row exactly when it has a config screen of its own (`!installed() && has()`), and sits where Mod
Menu puts it: under the parent it names in `custom.modmenu.parent` (an id, or an object whose name heads a `GROUP` row
when the id is no mod on the instance), followed up to the topmost parent, and at the top when it names none — not under
its carrier, which Mod Menu does not group by and which a library two mods carry does not have. A carried mod with no
config of its own gets a row only as the parent a carried mod with one names, and its detail pane says so. Installed
jars stay where they are whatever they name. Platform rows: the game itself (`minecraft`; its config is whatever the
Fabric protocol answers for that id — Mod Menu's own factory when it is installed — else vanilla's `OptionsScreen`), and
any container a family's registry holds that the catalogue does not and that has a config screen there (on NeoForge,
NeoForge's own settings). Such a loader's own row is listed only when the catalogue (installed and carried mods, each
under the family that won it) holds a mod that loader serves (`ModListRows.serves`): NeoForge's own row, whose Config
opens NeoForge's settings screen, is there when the instance has a NeoForge mod, a MinecraftForge mod (both run on the one
NeoForge-patched base), or a universal jar arbitration gave to either; a pure-Fabric pack, a universal jar Fabric won
included, has no such row. NeoForge itself still runs, and reads its own settings file, under every instance. The row is
named by the container's own display name, and the kernel's NeoForge baseline container (the one `NeoForgeMod` is
constructed with) is now described by the `[[mods]]` entry NeoForge's own `neoforge.mods.toml` declares for itself, read
from the runtime jar (`boot.CarrierOwnMod`): display name `NeoForge`, NeoForge's version and its description, where it
used to answer its id (`neoforge`) and `0.0`, so every reader of `ModList.getModContainerById("neoforge").getModInfo()`
gets what genuine NeoForge answers. With no runtime jar declaring it, the name falls back to the family's own (`NeoForge`).
A kernel-built NeoForge `IModInfo` answers `getDescription()` from the mod's own `[[mods]]` entry for every mod now, not
`""` (with none declared it is still `""`; genuine NeoForge says "MISSING DESCRIPTION" there). The list's `setSelected` tells the screen, so the Config button follows whatever moves the selection; the
arrow keys and the mouse are measured, Tab is not. `ModMenuProtocolExtension` logs, once, provided factories
for ids nothing on the instance has. The other direction, for Mod Menu's own list: `fabricloader` declares the kernel's
own `"modmenu"` entrypoint on a client (`runtime.modmenu.KernelModMenuPlugin`, its own source set compiled against the
stand-in API, `KernelRuntimeClasses.Origin.PACKED`), whose provided factories map every id another family loaded and
Fabric holds as a presence identity to that family's own screen (rebuilt at most every 2 s). It is offered to whatever
reads `"modmenu"` entrypoints — Mod Menu, a fork, the kernel's own stand-in reader (harmless there: the kernel asks the
Fabric protocol about FABRIC entries only) — so Mod Menu's list has a Config button for a universal jar NeoForge won and
for every NeoForge or MinecraftForge mod with a config; `-Dforbric.modMenuForeignScreens=off` offers nothing. A presence
identity whose Fabric build lost is shown as that build's `fabric.mod.json` shows it (name, description, authors,
contact, icon: `KernelModMetadata.presenceShownAs`), where it used to be a nameless, iconless row on Mod Menu: the Fabric
half of a universal jar and a nested loser from discovery, a top-level Fabric jar that lost to another family's jar read
from that jar (discovery never reads a suppressed jar), and the declared icon, if the jar that runs lacks it, from the
jar that declared it (`KernelModContainer.presence(metadata, loadedFrom, shownFrom)`).

What gate-m56 measures (all four phases required; missing Mod Menu or fabric-api is SKIP-FATAL, exit 3; a subset run
through `M56_PHASES` exits 3 too, never 0): with no Mod Menu, canaries opening through the Config button — two Fabric mods'
own entrypoints, one through a factory another mod provides, two carried mods (one at the top, one under the heading it
names, positions read from the rows the open screen's list widget holds, the screen photographed), a universal jar's
NeoForge screen, the game's `OptionsScreen`, and NeoForge's own row — listed because the set has a NeoForge mod, named
`NeoForge`, opening NeoForge's `ConfigurationScreen` — exactly 8 of the 9 walked rows; with the Fabric canaries alone (no
NeoForge or MinecraftForge mod), no NeoForge row in the list widget's rows while the game's row still opens; the plain
canary's button hidden; every selection
and press through `KeyboardHandler.keyPress` / `MouseHandler.onButton` (arrow keys from a row with the opposite answer and
back, including from a mod with no config to two with one, clicks, a double click on every row that has a config); and the
code source Mod Menu's API class was defined with (none, with no jar on the game loader holding it and the kernel's
stand-in bytes present; or not resolving with `-Dforbric.modMenuStandIn=off`, its control). With Mod Menu 20.0.3 installed,
Mod Menu's API answers for the universal canary — `ModMenu.hasConfigScreen` (what its list's Config button reads), the
name and description of its `ModMenu.MODS` entry (the canary's `neoforge.mods.toml` names it differently, so NeoForge's
name would fail), `ModMenu.getConfigScreen` building NeoForge's screen — and the icon path Fabric's container names is
found in that container (the probe's own lookup, the one Mod Menu's icon code does); Mod Menu's own factory answers the
game's and a Fabric canary's Config button exactly once and not the universal canary's; `-Dforbric.modMenuForeignScreens=off`
is the control. Not measured by any gate: Mod Menu's own list screen and a real press of its Config button, a NeoForge or
MinecraftForge mod other than that canary in Mod Menu, the unlisted-factory log, the 2 s refresh, Tab, NeoForge's row for
a set whose only Forge-family mod is a MinecraftForge or a carried one (`ModListRowsTest` only; a universal jar through
the arbiter either way in `LoaderRowCatalogueTest`), and a top-level Fabric jar losing to another family's jar
(`FabricAliasDisplayTest`, with the arbiter's real decision, only).

## 12. Compatibility reporting and policy

### 12.1 Evidence

`net.forbric.api.CompatibilityFinding(id, modId, feature, source, confidence, required, detail, evidence)`;
`Confidence` is `SUSPECTED`, `CONFIRMED` or `RESOLVED`. Only `CONFIRMED && required` can stop a launch;
`RESOLVED` is kept as evidence and never marks a mod. `CompatibilityFindings` is the per-launch ledger shared by
boot code, the game UI and release checks. Producers include the dependency audit (`DependencyAudit`, across
ecosystems — no single resolver runs over the combined set), arbitration, Mixin preflight/apply, missing bridges,
deferred-work failures, the static audits of §3.2, `KernelTransferInterop`, and late server/client findings.

### 12.2 Files — all under `<gameDir>/.forbric-kernel/`

| File | Written |
| --- | --- |
| `load-report.txt` | at the pre-game boundary as evidence, again after the setup lifecycle, again at `ServerStartedEvent` (the world is up; integrated servers post it too) and when late findings arrive (`-Dforbric.loadReportRewrite=off` keeps the first write); a shutdown hook writes it if loading never finishes. In the system language |
| `compatibility-report.json` | beside it, the machine-readable findings |
| `merge-report.txt` | when two jars claimed one mod id (§4.3) |
| `crash-analysis.txt` | after a crash report: which mods the stack points at, including both mods of a mixin overlap in a method on the stack (`CrashAttribution`, §7.6; `-Dforbric.crashAnalysis=off`). The Forge `Suspected Mods:` line depends on a module layer the kernel does not build |
| `crash-suspects.json` | beside it: `{schema:1, report, clash, suspects:[{modId,name,jar,reason,depth}]}`. On the next client launch, before arbitration, `CrashSuspectOffer` offers to start without those jars (for a clash, every side but the first-named), appends them to `<gameDir>/forbric-disabled.txt` on "Start without", and renames the file `crash-suspects.offered.json` whatever the answer. A server, a headless run and `-Dforbric.dependencyDialog=off` only log the lines |

Working directories in the same place: `lib/` (extracted bundled jars), `jij/`, `jarjar/`, `candidates/`.

### 12.3 Dependency dialog

`ui.DependencyDialog` shows unmet hard dependencies and cross-mod mixin breaks to the player. The window is a
**separate JVM** (`DependencyDialogMain`, launched with the kernel jar as its only classpath entry) because on macOS
the game runs with `-XstartOnFirstThread` and AWT cannot share thread one with GLFW. Parent and child share only
the tab-separated file format in `DependencyReport`; strings are in `DialogLang` (system language,
`-Dforbric.dialogLanguage=<code>` forces one). `-Dforbric.dependencyDialog=on` (default) | `off` | `dryRun` (forks
the real child with AWT disabled — what gates assert on). A child started with `-Djava.awt.headless=true` that
cannot draw has given the answer it was started for, and reports no failure; a child that is headless without being
told so (a macOS client outside a window session) still does. The child times out after 10 minutes. The confirmation's
exit code is `0` continue, `4` quit or closed, and anything else (`3` cannot draw, the launcher's own `1`) a window
nobody could answer. The same child
has a third window, `--isolation`: the crash-suspects offer of §12.2, whose exit code `2` means start without them;
anything but its two explicit buttons starts with every mod.

### 12.4 Policy — `-Dforbric.compatibilityPolicy`

`ui.CompatibilityDecision.policy()`:

| Value | A confirmed required loss… |
| --- | --- |
| `ask` (default) | asks the player once in the dialog (the dependency notice folded in); only an explicit Continue approves, and Quit or closing it refuses. On a client whose dialog cannot be shown — no runnable `java.home/bin/java` (Android launchers such as FCL), a child that cannot start or draw, no answer within 10 minutes — the question moves into the game: nothing is approved, and `KernelCompatibilityPrompts` asks on the title screen with the dialog's own buttons, where Quit stops the game. Not approved instead when the launch opens a world straight away (`--quickPlaySingleplayer`/`Multiplayer`/`Realms`: the world would load before the game could ask) or under `-Dforbric.dependencyDialog=off`. A dedicated server has nobody to ask → not approved |
| `continue` | is accepted and recorded; the notice may still be shown |
| `strict` | stops the launch; no window is shown |
| anything else | treated as `strict` (fail closed) |

The decision is asked at the pre-game boundary (§3.2 step 17), again at the end of loading (§3.4 step 3b on the
server, `fireClientSetupLifecycle` on the client), and at `ServerStartedEvent` on a dedicated server (a required
failure during world loading halts the server normally). A refusal throws `CompatibilityDecision.LaunchStopped`;
`CompatibilityLaunchBoundary` turns it into exit code **78** and prints the report path; inside
`Minecraft.<init>` it leaves through `SilentInitException`, so it is not reported as a crash. Findings that appear after boot never fork Swing or exit the JVM: on the server they are
consumed at the completed-tick boundary (`LateServerCompatibility`), on the client on a render-thread tick by a
native Minecraft screen (`KernelCompatibilityPrompts`, `KernelCompatibilityScreen`). An installed profile passes no
JVM arguments, so players get `ask`; `run/launch-kernel-{client,server}.sh` default to `strict` (and the client
script to `-Dforbric.dependencyDialog=off`).

### 12.5 Failures the kernel carried on from — `util.Caught`

The kernel is fail-soft: a repair that does not fit declines, a bridge whose other side is missing stands down, a
mod's listener that throws does not take the other mods' listeners with it. Every one of those is a `catch`, and
every `catch` in `src/main` and `src/runtime` either rethrows or reports to `Caught`, as a statement of its own body
(not under an `if`). `EveryCatchIsVisibleTest` scans the sources and fails on any other catch; there is no allowlist.

- `Caught.warn/error(t, format, args)` — a failure the kernel carried on from. Logged at that level with the
  `[Forbric/Caught]` prefix and the catch site (`Class.method:line`): the full stack trace the first time a site is
  hit, then one line per occurrence. The same message at the same site is printed 20 times
  (`-Dforbric.caught.printedPerSite`) and then counted; a message the site has not said before is always printed (up
  to 100 per site), so one forwarding helper still names every event that failed. Reflection's
  `InvocationTargetException` is unwrapped before printing.
- `Caught.expected(t)` — the exception is the answer: a presence probe for an optional class, a recogniser's "not
  this shape", text a mod wrote that is not a valid value, an exception handed on as a value to code that reports
  it. Counted, logged only under `-Dforbric.debug`. When the cause chain of an "expected" exception holds an
  exception only a bug throws (`NullPointerException`, `ClassCastException`, an index out of bounds outside ASM's
  analysis frames, …) thrown by Forbric's own code, it is reported as a failure instead: ASM's `Analyzer` wraps
  whatever its interpreter threw in the very `AnalyzerException` a recogniser expects. The same holds for
  `Caught.Invariant`, which every registry throws when it already holds a different value under the key it is
  given (`RegistryConflictsAreInvariantsTest` keeps it so): repairs register from inside their "not this shape"
  catch, so an ordinary `IllegalStateException` was declined there silently.

A recogniser that declines by throwing gives the decline an exception type of its own (a private class such as
`Unproven`) and catches that one as `expected`, ahead of the `warn` for everything else: to a single catch, an
`IllegalArgumentException` the recogniser throws on purpose looks the same as one a bug throws.

A summary (`[Forbric/Caught] summary …`, one line per failure site with its count) is logged when loading
finishes and again at JVM exit. With `FORBRIC_CAUGHT_FILE` (or `-Dforbric.caught.file`) naming a file, each failure
site is also appended there on first hit, and its count at exit.

Tests and gates both read it. `CaughtErrorsExtension` (loaded for every unit test through META-INF/services) fails
a test during which the kernel caught a failure, unless the test says it causes it with `@ExpectCaught("<what the
failure says>")`; on a method, every named failure must also happen. `run/lib.sh` gives each gate its own
`build/caught/<gate>.<pid>.tsv`, exports it to every JVM the gate starts, and at exit prints `[kernel] CAUGHT …` with
each site, its message and its count; `FORBRIC_CAUGHT_STRICT=1` turns any of them into a red gate. A gate started by
a gate reports through it: m33, m35, m37, m38, m39 and m40 start their servers by running themselves again under
`evidence.py`, and that inner run inherits the file together with `FORBRIC_CAUGHT_GATE` (the owner's name and pid),
writes into it and prints nothing; a leftover variable whose owner is dead, or a file exported with no owner, is not
inherited (`LibCaughtContractTest`). Each inner run used to take a file of its own, print its CAUGHT line into the
server log the outer gate keeps, and leave the outer gate printing `CAUGHT none` — over an NPE gate-m39's server hit on
every run (main f7f4ff92 too; §5's transfer attribution). gate-m34 waits for `soak-run.py` rather than exec'ing it, so
its EXIT trap still runs; the soak client (one JVM: the client and every integrated server it opens) writes the run's
`evidence/caught.tsv`, which `soak-run.py` appends to the gate's file and records in `acceptance.json`.

A JVM that outlives its gate is reported by nobody, so the gates stop a client with lib.sh's `stop_tree`: the whole
process tree, frozen first (STOP, parents before children), then TERM to every process in it so a JVM runs its
shutdown hooks, and SIGKILL for what is left after a grace (`LibStopTreeTest`). Seven client gates used to signal the
recorded pid and its direct children — the form `kill_tree` had already replaced for servers. Where the launcher execs
java that was the JVM; gate-m17 runs the resolved command as `tr … | xargs -0 env … java` in a subshell, and on a
loaded run its client lived on re-parented to launchd after the gate had printed `CAUGHT none` and exited, then wrote
two failures into the gate's file.

## 13. The installer — `forbric-kernel-installer/`

Pure JDK, no dependencies, bytecode release 17, version `0.3.1-beta2`.

```
java -jar forbric-kernel-installer.jar                     # window (InstallerGui)
java -jar forbric-kernel-installer.jar --dir DIR [options] # headless install
    --mc 26.2  --artifacts DIR  --jdk PATH  --remote  --release TAG  --mirror PREFIX  --offline
java -jar forbric-kernel-installer.jar --doctor [--dir DIR] [--jdk PATH]
```

### 13.1 Building the game artifacts on the player's machine

`ArtifactBuilder.build` runs under `<mcDir>/.forbric-build/`, resuming at the first unfinished step:

```
forge userdev ─┬→ forge-runtime ───────────────┬→ patched-mc-forge ─┐
               ├───────────────────────────────┘                    │
               └→ (source patches: edit provenance) ────────────────┤
neoforge userdev ─┬→ neoforge-runtime ──────────────────────────────┤
                  ├→ NFRT → patched-mc-neoforge ────────────────────┼→ patched-mc-merged
                  └→ (source patches: edit provenance) ─────────────┤
vanilla 26.2.jar ───────────────────────────────────────────────────┤
versions/26.2/26.2.json's libraries (fetched only if a tool runs) ──┘
forge-runtime ────────────────────────────────→ forge-runtime-interop   (what is staged)
```

- `ForgeRuntimeBuilder`, `PatchedMcBuilder` (Forge's `installertools`/`mergetool`/`binarypatcher` as child JVMs,
  Forge's `AccessTransformerEngine` in-process), `NeoForgeRuntimeBuilder`, `NfrtRunner` (NeoFormRuntime, result
  `gameJarNoRecomp` — binary patches, no decompiler, no `javac`).
- `MergedBaseTool` unpacks `forbric-merge-tools.jar` from the installer's resources and runs
  `net.forbric.tools.MergedBaseBuilder` (`-Xmx4g`), `RuntimeInteropPatcher`, then `MergedLinkChecker` against the
  packaged reviewed baseline. **An install fails unless the link check reports `new 0`.** The merge is handed both
  userdev artifacts (their source patches are the edit provenance of §2.4) and exactly the libraries the base
  version's JSON lists for this platform, in classpath order, never a whole `libraries` folder: its field proofs follow
  constructions into them. They are verified and, where missing, downloaded only once the merge or the interop patch
  has found its output stale, so a reinstall served from the cache still works offline.
- `--artifacts DIR` ("Built artifacts (leave empty)" in the window) is for developers only and skips the build.
  `GameArtifacts` takes the three jars from that directory alone and opens each before anything is downloaded or
  written: the merged base must be Minecraft 26.2 whose `net/minecraft/` classes refer to both
  `net/minecraftforge/` and `net/neoforged/`; each runtime must hold its family's core class and its mod loader
  (`FMLLoader`, `IModInfo`), and name the pinned version as its manifest's main `Implementation-Version`
  (`Pins.NEOFORGE`; for MinecraftForge the FML half of `Pins.FORGE`, `65.0.1`); and the MinecraftForge runtime
  must be the interop-patched one, whose `NamespacedWrapper$3` declares `contents()`. Only then the link check,
  which on its own passes any jar that refers to nothing outside itself (issue #13); a supplied set that fails it
  is reported as files that do not fit together, with the same way out.
- `Pins`: `MINECRAFT = "26.2"` (the only supported version), `FORGE = "26.2-65.0.1"`, `NEOFORGE = "26.2.0.88"`,
  `NFRT = "2.0.18"`, `NFRT_RESULT = "gameJarNoRecomp"`, each with its reason in the source. `BuildStamp` keys every
  cached artifact to the pin set, so a pin bump cannot be served from cache.
- `JdkLocator` needs Java ≥ 21 for the build tools (NeoFormRuntime is class-file 65); it tries the running JVM,
  then the launcher's runtimes, then the system, and never downloads a JDK. The game itself needs Java 25.

### 13.2 The profile

`Installer` writes `versions/26.2-forbric/26.2-forbric.json`:

- `inheritsFrom: "26.2"`, `mainClass: net.forbric.kernel.boot.KernelClientLaunch`, no JVM arguments;
- game arguments `--gameJar <merged>`, `--runtimeJar <forge-runtime><sep><neoforge-runtime>` (one flag),
  `--libraryPath <every vanilla library for this platform>`;
- `libraries`: the bundled Forbric jars and the kernel's third-party dependencies (from the kernel's own
  `printBootClasspath`), plus the three game artifacts staged as `net.forbric:patched-mc-merged`,
  `net.forbric:forge-runtime`, `net.forbric:neoforge-runtime`;
- a `forbric` block, metadata only, declaring `net.fabricmc:fabric-loader:0.19.3` so launchers that detect a
  loader by searching the JSON's text treat the instance as modded (and give it its own mods folder).

Mods for all three ecosystems go in `<mcDir>/mods` (or `versions/26.2-forbric/mods` under an isolating launcher).

### 13.3 Where Forbric's own jars come from

The default build bundles them (`bundleForbric` writes `forbric-kernel-libraries.json` with SHA-1s). `-Pslim`
builds carry none and fetch at install time through `RemoteSource`: Forbric's jars from the GitHub release, other
libraries from Maven first. `-PreleasePin` compiles `forbric-release.properties` (tag, repo, `manifestSha256`) into
the jar — the digest of the manifest is the trust anchor, which is why release is two steps (`releaseAssets`,
then the pinned slim build). `releaseAssets` refuses to write assets unless `run/compat/evidence.py release-check`
confirms the kernel and merge-tools jars are byte-identical to the accepted candidate.

### 13.4 `--doctor`

`Doctor.examine` writes nothing, creates nothing, downloads nothing: platform, JVMs found, whether the base
version is installed, pins, which artifacts are present or will be built, expected disk, and a one-line verdict.

## 14. What `forbric-loader/` is still for

- **The merge tools.** `forbric-loader/src/tools/java/net/forbric/tools/` — `MergedBaseBuilder`, `EditSplicer`,
  `PatchLineProvenance`, `MergedLinkChecker`, `RuntimeInteropPatcher`, plus `AdditiveMethodMerger`,
  `MergeabilityCensus`, `LostHookAttribution`, `EffectiveHookEvidence` — built by `:mergeToolsJar` into
  `forbric-merge-tools-0.1.0.jar`, which the installer ships and runs. The reviewed link baseline is
  `forbric-loader/src/test/resources/merge/link-check-baseline.txt`; the merge-loss ratchet is
  `MergedBaseHookLossRatchetTest` (§2.4).
- **The developer pipeline.** `run/build-patched-forge.sh`, `assemble-minecraftforge-runtime.sh`,
  `assemble-neoforge-runtime.sh`, `build-merged-base.sh`, `check-merged-links.sh` produce the staged artifacts
  under `forbric-loader/run/{merged-base,forge-runtime,neoforge-runtime}/` that the kernel's game side compiles
  against and every gate runs on. `FORBRIC_OLD` (or `-Pforbric.stagedRoot`) points another worktree at them.
  `build-merged-base.sh` writes the merge's report (`merge-conflicts.txt`) and a build record
  (`patched-mc-merged-26.2.jar.provenance.json`: tool-source hashes, input hashes, jar and report hashes) beside the
  jar. The committed `run/merged-base/merge-conflicts.txt` is the report of the base the current tools build, the
  one beside it in `run/merged-base/`, and a test fixture (§2.4).
- **Test inputs.** The canary mod sources in `run/livemod-src*`/`testmod-src` (`build-testmods.sh`), and the
  mod sets in its run directories that gate-m0's discovery oracle reads.
- **Installer payload.** The installer's bundle manifest still includes `net.forbric:forbric-loader` and
  `net.forbric:forbricruntime`, so an installed profile lists them as libraries. No kernel code names a
  `net.forbric.loader` class; the only reference is the retargeting in §6.

Its own boot path (Knot host, eight fabric-loader substrate patches applied by `bootstrap.sh`) is not used by the
kernel. The weld's design is documented in `forbric-loader/README.md` and `forbric-loader/run/README.md`.
`forbric-installer/` is the weld's installer and is not the one released.

## 15. Repository layout

```
Forbric/
├── README.md                      player-facing, describes the latest release
├── introduction.md                this document, describes main
├── LICENSE, NOTICE
├── bootstrap.sh                   clones ./fabric-loader for forbric-loader (not needed by the kernel)
├── MOD_TEST_FAILURES.md           per-mod compatibility results (Chinese)
├── .github/workflows/build.yml    CI: job `build` (bootstrap + forbric-loader) and job `kernel`
│
├── forbric-kernel/                THE KERNEL — own Gradle build and wrapper
│   ├── build.gradle               boot jar (release 21), runtimeJar, transferTest, staged-artifact wiring
│   ├── gradle.properties          ASM, sponge-mixin, MixinExtras, SAT4J, NightConfig … versions
│   ├── src/main/java/net/forbric/api/          the unified API (25 files)
│   ├── src/main/java/net/forbric/kernel/       boot (107), transform (155), mixin (166), fabric (15),
│   │                                           metadata (12), access (7), classloading (10), discovery (5),
│   │                                           interop (23), ui (6), util (13), mapping (3), soak (2)
│   ├── src/main/java/net/fabricmc/             vendored Fabric API surface (47 files)
│   ├── src/main/resources/                     Mixin service registrations; mixin census tables
│   ├── src/runtime/                            GAME side, net.forbric.kernel.runtime (+ soak/, transfer/)
│   ├── src/test/, src/transferTest/            unit tests; transfer-engine tests
│   ├── canary/                                 canary mods the gates build and load
│   └── run/                                    gate-m*.sh, launch-kernel-{client,server}.sh, lib.sh,
│                                               diff-oracle.sh, mixin-inventory.sh, merge-packs.sh,
│                                               build-*-canary.sh, compat/ (sweep and evidence tooling,
│                                               rename-mod.py: the rename experiment, §16.1)
│
├── forbric-kernel-installer/      THE INSTALLER — src/main/java/net/forbric/installer/kernel/, packaging/
│
├── forbric-loader/                first generation; merge tools (edit splice) + artifact pipeline (§14)
├── forbric-installer/             the first generation's installer
└── fabric-loader/                 gitignored upstream checkout for forbric-loader
```

## 16. Build and test

The current development entry point is `python3 tools/dev.py client` (Windows: `py tools/dev.py client`).
It prepares isolated game inputs under `forbric-kernel/.dev/` with the installer's artifact pipeline, resolves
libraries/assets and the pinned compile APIs, then builds and launches the current kernel. JDK 25+ and
Python 3.9+ are required. Gradle exposes `prepareDev`, `runClient`, `runServer` and `devDoctor`; preparation
and launch must be separate Gradle invocations because the game-side wiring is configured before tasks run.
See [the development guide](forbric-kernel/run/README.md) for commands and configuration.

`check` also runs development/evidence-tool self-tests and the packaged link gate's synthetic controls.
`integrationTest` requires the staged game and transfer suites and rejects any skipped test; ordinary `test`
still permits absent local fixtures and prints its executed/skipped counts. The complete integration suite
requires its named mod fixtures in addition to the base game; preparing the game is not a claim that every
compatibility pack or real-instance gate has run. A unit test during which the kernel catches a failure and carries
on fails unless it is marked `@ExpectCaught`, and every gate ends with a `[kernel] CAUGHT` line (§12.5).

```sh
cd forbric-kernel
./gradlew --offline jar        # boot jar; nests forbric-kernel-runtime.jar only when the staged artifacts exist
./gradlew --offline test       # unit suite (depends on compileRuntimeJava)
./gradlew --offline check      # + transferTest (real Fabric/NeoForge transaction engines)
./run/gate-m0.sh               # build + suite from the JUnit XML + scan + link check + discovery oracle
java -cp <boot-cp> net.forbric.kernel.boot.Main --scan --mods <dir> --report out.json
```

- **Staged artifacts.** The game side compiles against `forbric-loader/run/merged-base/patched-mc-merged-26.2.jar`,
  `…/forge-runtime/forge-runtime.jar`, `…/neoforge-runtime/neoforge-runtime.jar`, plus brigadier, datafixerupper
  and gson from a local Minecraft install, a fabric-api jar for the transfer modules (`-Pforbric.fabricApi`), and
  Team Reborn Energy 5.0.0 pinned by SHA-256 (`run/energy-api/energy-5.0.0.jar` or `-Pforbric.rebornEnergy`).
  Without the staged jars `compileRuntimeJava` is skipped and `jar` produces a boot jar with no game side — which
  is what CI builds. `KernelRuntimeClasses.verify` is what catches such a jar at launch.
- **Unit tests.** Counted from source: `src/test` has 5 549 `@Test` methods (23 of them spelled
  `@org.junit.jupiter.api.Test`), 57 `@TestFactory` and 6 `@ParameterizedTest` methods in 1 046 `*Test.java` files;
  `src/transferTest` has 76 `@Test` methods in 7 files (annotations at line start after indentation, `git grep` over
  tracked files). A full run (`cleanTest test`, no daemon) on a staged root cloned whole from the developer's
  `forbric-loader/run` — its merged base, runtimes and test fixtures — with this tree's tracked report in it, and the
  third-party mod packs that machine still has, reported 6 660 tests in its JUnit XML, 74 of them skipped: 72 for
  third-party packs the machine no longer has, 2 opt-in. How many run and skip depends on the root and the fixtures
  present (some tests generate one case per fixture; a root cloned from an older copy skipped five more). Many tests
  read the staged jars; gate-m0 fails on *any* skipped test, because a skip there means the tests did not look at the
  real base. The weave tests (`mixin/weave/`) run synthetic mods with unrelated names through the real Mixin and
  MixinExtras, each with its mechanism switched off as the control. Tests must pass on JDK 21 as well as 25 (the CI unstaged job is JDK 21).
- **Gates.** 60 scripts, `forbric-kernel/run/gate-m*.sh`, each asserting on the real logs and files of a real
  instance, most with named negative controls (a `-D…=off` or input removal that must turn exactly the named
  checks red). `run/compat/gates-all.sh` discovers them by glob; `gates-parallel.py` overlaps them using each
  gate's `# GATE-PARALLEL: rundirs=… mem=…` line (57 of 60 carry one; a gate without it runs alone), giving each
  slot its own port block. Their time bounds are wall-clock seconds sized on a machine with cores to spare, and most
  count from the launcher's start, its Gradle build included; gate-m22's client window counts from the JVM's own
  start instead. On a busy machine they measure the machine. Run as two `-j 5` batches side by side, one on a
  tree and one on the commit it branched from, so that both see one load (10 cores; median load average 28–59 per
  round, peaks 40–111), m2b, m3 and m4 went red on both alike in all three rounds: m2b's and m3's servers were still
  short of `Done` 136–140 s and 77–83 s after their first line (one m2b got there at 138 s, too late for its stop),
  against 150 s and 90 s counted from the launch, and m4's own stop feeder stops waiting 150 s after the launch and
  writes `stop` into a boot that has not reached `Done`, where the console drops it ("An unexpected error occurred
  while trying to execute that command"; `server_ready` in `run/lib.sh` exists for exactly this, and m4 does not use
  it). A full `-j 5` run at load averages 44–163 turned m8, m17, m18 and m40 red on the same kind of timeout. The one
  performance assertion, `check_kept_up` (vanilla's "Can't keep up!" while a player is on), measures the machine the
  same way: a loaded server logs it once, 2.0–3.6 s behind, one to six seconds after the player joins. With m12–m16 run
  as the same pair of side-by-side batches (median load averages 23–39), m12 went red on it on both trees in all three
  rounds and m15 on each tree in two of three, and in a `-j 5` batch with the full test suites beside it m12 and m16
  did. A red
  gate from a loaded parallel run says nothing about the kernel until the boot times in its logs have been read. The
  clock can also bite by date: the game's log4j configuration rolls `logs/latest.log` at midnight, eleven gates read
  that file, and m9 went red once because its session crossed midnight and the reload line it looks for went to the
  rolled archive.

| Gate | Asserts |
| --- | --- |
| m0 | build, the whole suite, `--scan`, merged-base link check, discovery oracle, tool self-tests, transfer suite |
| m1, m3 | merged base to `Done` with zero mods and no genuine lifecycle; both Forge-family baselines + a real `@Mod` |
| m2, m2b | a real Fabric mod, then full fabric-api, with no Fabric Loader |
| m4, m4-canary, m7-neo | real mods of all three ecosystems in one server; pure NeoForge jars |
| m8, m10 | multiloader `pack.mcmeta` sections do not delete or break a pack |
| m9, m27 | client into a world and out cleanly with the 97-jar pack; a fresh non-black frame |
| m11 | a config-driven mod stack on a dedicated server |
| m12–m16 | client↔server over a real socket; a Paper anti-cheat; a pure Fabric server; MinecraftForge channels and handshake |
| m17 | the installer's profile, launched the way a launcher does |
| m18, m19, m20 | cross-ecosystem presence; a nested library initialised once; unmet dependency reaches the player |
| m21, m26, m28, m29 | MinecraftForge setup, client registration events, configs + live file watcher, capabilities |
| m22, m23 | quitting survives a replaced kernel jar (the client JVM gets `M22_READY_SECONDS`, 600, from its own start to reach a world; the launcher's Gradle build before it is timed and bounded apart; negative controls: a client JVM stopped as it starts, and a pack whose only world is unreadable); elytra flight |
| m24, m24b, m30 | a failing mod, a mod whose metadata cannot be read, and a partly failing mod, attributed on every surface |
| m24c | a jar listed in `forbric-disabled.txt` is loaded by nobody and named in the load report; a server only logs the crash-suspects offer |
| m25, m31, m32 | both biome-modifier pipelines; zero-mod worldgen parity with vanilla; a save opens with a mod removed |
| m33, m39, m40, m52 | item/fluid/energy transfer across ecosystems; hoppers into Fabric storages |
| m34 | ≥ 7200 s occupied simulation soak with retention checks |
| m35–m38, m41–m51, m53 | per-surface behaviour: mixin outcome, entity callbacks, enchantments, event chain, coremod parity, block break and loot, interaction, everyday actions, stub rebind, damage/server/world events, load predicates, tooltips, widened `NEW` anchors |
| m54 | a NeoForge mod's play-phase packet reaches the server: Carry On, with fabric-api installed, carries a chest and a pig through real key and mouse input; the same run with the repair off must pick nothing up (third-party jars: `M54_CARRYON`, `M54_FABRIC_API`) |
| m55 | the creative inventory's search finds items after a Fabric mod refreshed the search trees the vanilla way, judged from the screen's own grid, with a switched-off negative control |
| m56 | with no Mod Menu, Fabric mods' config screens open through the kernel Mods screen's Config button (own entrypoint, a provided factory, two mods carried inside a jar — one under the heading it names, positions read from the screen's own list widget), as do a universal jar's NeoForge screen, the game's options and NeoForge's own settings (its row named `NeoForge`, listed because the set has a NeoForge mod), all driven through the game's keyboard and mouse handlers (arrow keys, clicks, double clicks); `-Dforbric.modMenuStandIn=off` must take the Fabric canaries' buttons away; with Fabric canaries only, NeoForge's row must be absent. With Mod Menu installed (required: `M56_MODMENU`/`M56_FABRIC_API`, default `run/modmenu/`, else SKIP-FATAL), Mod Menu's API answers for the universal canary with its `fabric.mod.json` name and description and NeoForge's screen (its list screen is not opened), and Mod Menu's own factory answers the game's row once; `-Dforbric.modMenuForeignScreens=off` is that phase's control. Canaries built from `canary/modmenu-config` |
| m57 | MinecraftForge's breathing, drowning, damage and liquids against the native loaders: a dedicated server with a MinecraftForge and a NeoForge canary plays 53 scenarios — each family's breathe and drown listeners alone and both in one pack (NeoForge's answer first, then MinecraftForge's, also from NeoForge listeners at `LOW` registered from common setup, after the kernel's bridges, which a bridge above `LOWEST` would answer before, and at `LOWEST` registered in the NeoForge canary's constructor, before them, which bridges installed before the mods are constructed would answer before), MinecraftForge `@SubscribeEvent` listeners that cancel by returning true, a MinecraftForge bubble count that must leave the body's random draws as they are, Breath of the Nautilus alone in water, in a bubble column and in MinecraftForge's liquid (where MinecraftForge's listeners are told whether the body may refill), each clause of MinecraftForge's refill rule as the only one that says yes where a NeoForge listener refills nothing (water breathing in water, Breath of the Nautilus alone in open air, a bubble column), a pig drowning in each family's own liquid and breathing in a MinecraftForge liquid built `canDrown(false)`, where only its type's `canDrownIn` keeps it from drowning, and with Breath of the Nautilus alone in it, the owner's vanilla refill pinned as a documented difference (it refills as open air does natively, and the reference must still show native MinecraftForge holding the air), the probe's player drowning three times through MinecraftForge's attack, hurt and damage events and NeoForge's incoming damage, and each family's liquid block placed, taken up and poured out with its bucket. Each row's air and health (or a liquid row's result) and the listener views it names are compared with the same jar on the native loader whose answer stands (`canary/forge-breath-drown/native-reference.json`: three runs per native loader that agree on every stored field, regenerated by `run/compat/breath-drown.py reference`); a tick where MinecraftForge's listener answers before a NeoForge one fails, and so does a row where both answered with no tick recording which answered first; the body's random draws are compared only where a row must draw what another row draws, and only once the probe counted them on every sampled tick; the entity events never. `-Dforbric.unifiedEvents=off`, `-Dforbric.forgeFieldGetters=off`, `-Dforbric.forgeFluidOwnType=off` and `-Dforbric.forgeDamageSeams=off` must each fail exactly the rows they decide, each for that switch's cause. Canaries built from `canary/forge-breath-drown` |

- **Compatibility sweeps.** `run/compat/PROTOCOL.md` is the procedure for running random/popular Modrinth sets on
  a Windows machine through the installed profile (`push-and-run.sh`, `win/*.py`, `pick_mods.py`, `evidence.py`),
  plus static tools (`abi-audit.py`, `field-drift.py`, `fapi-usage.py`, `hook-worklist.sh`, `repair-drift.sh`,
  `control-diff.sh` — same Fabric mods on native Fabric vs Forbric).
- **CI** (`.github/workflows/build.yml`): job `build` bootstraps and builds `forbric-loader/`. Job `kernel` (JDK 21,
  no game files) runs `./gradlew build -Pforbric.skipBaseline=ci-unstaged` in `forbric-kernel/`: the boot side
  compiles and the unit tests that need no game files run. About a third of the suite skips without game files,
  and that skipped set must equal `src/test/skip-baseline/ci-unstaged.tsv` line for line (`skipRatchet`,
  `tools/junit_report.py`): a test that starts skipping fails the job, and a line that stops skipping has to be
  deleted. The run page shows tests / executed / skipped with the most common skip reasons, and the JUnit reports
  are uploaded as `kernel-test-results`; regenerate the baseline from that artifact's `skips-actual-ci-unstaged.tsv`
  or with `-Pforbric.writeSkipBaseline`. Job `kernel-prepared` (JDK 25) first builds the game files on the runner
  with `tools/dev.py prepare --no-assets` (Minecraft from Mojang, Forge and NeoForge from their own mavens, the
  merged base and carriers built there, plus the two canary mods and the merge report the unit tests read; only
  upstream downloads are cached and nothing derived is uploaded), then runs the same suite plus `transferTest` with
  `-Pforbric.requireFixtures=staged,game-side,mc-libraries,java-25`: every kind of fixture except third-party mod
  packs is present there, so a skip for any other kind, or an untagged skip, fails the job at the test that skipped.
  The 781 tests that still skip there (counted by name, as the baseline holds them) are held to `ci-prepared.tsv` the
  same way: 779 need third-party mod packs that are not in this repository, 2 are opt-in fixtures. The same job
  builds a merged base from the prepared inputs with `build-merged-base.sh` and runs the merge-loss ratchet on it
  (§2.4), the one place it runs. Job `development-tools` runs `tools/dev.py tool-test` and the packaged link gate on
  Windows, Linux and macOS. kernel-prepared then runs four real dedicated-server gates on the same files: m1, m36,
  m46 and m53, whose mods are canaries built from this repository. The other gates (client, third-party packs, soak)
  need the developer's Mac: `tools/nightly/` runs them there every night from launchd (02:30, the soak on Sundays),
  commits each night's summary to the branch `ci-results` and sets the commit status `nightly/dev-mac` on the tested
  commit.

### 16.1 Judging against the native game, and the rename experiment

Tests and gates show the kernel does what its authors meant. Whether a mod behaves as on its own loader is a separate
measurement, and every repair of a mod's behaviour is held to three proofs:

1. **A synthetic sample** with unrelated names, woven through the real Mixin and MixinExtras with the mechanism on and
   off (the weave tests above).
2. **The rename experiment.** `run/compat/rename-mod.py rename` writes a jar that does what the mod does under other
   names: every class under the mod's root packages moves to another package and class name (references, descriptors,
   signatures, annotations, Mixin targets, MixinExtras definitions, refmaps and class-name strings follow), its mixin
   configs, refmaps and access widener get other file names, and its mod ids become other ids (letters only: Forge's
   Mixin drops a mod id's digits where it names merged members) wherever metadata, manifest, service files and
   jar-in-jar metadata name them. Resource namespaces stay, and so do the strings the mod's code compares names
   against; a probe or addon compiled against the mod moves with it (`--dependent`); a nested jar pinned by name and
   digest is pinned again. `verify` reads the renamed jar back with `javap` and checks it is a pure renaming;
   `compare` puts a run of the renamed jar beside a run of the original — the `-Dmixin.debug.export=true` census, the
   `Mixing … into …` lines, the kernel's `[Forbric/…]` lines and compatibility report, probe files — with the new names
   put back, and lists every difference. A repair passes when what remains is noise a second run of the original shows
   too (timing, spawn point, list order) or follows from the new id's shape (the underscore alias only an id with `-`
   gets). A mod that looks itself up by a literal id is renamed with its id kept. `ModRename.java` is the ASM half.
3. **Native comparison.** The same mods run on native Fabric Loader 0.19.5, native NeoForge 26.2.0.88 and native
   MinecraftForge 26.2-65.0.1, and are compared injector by injector: the instruction each injector lands on (the
   export census, and the instructions around the landing point), whether the method it lands in runs in the game
   (its callers, and the kernel's uncalled table), what in-game probes report (counts per frame, values, screenshots),
   and the warnings. The same instruction, run on the same executions, with the same effect, is equivalent; anything
   else is a named gap, or comes with a proof that no structural trigger can close it. The per-mod harness for this
   lives on the developer machine, not in the repository; `rename-mod.py census`/`compare`,
   `run/compat/control-diff.sh` and `run/compat/breath-drown.py` (gate-m57's native reference: it runs the native
   MinecraftForge and NeoForge servers itself and writes the reference only when all three runs of each agree on every
   stored field, and stops, naming the differences, when they do not) are the parts that are.

The last round's results are in §19.

## 17. System properties

Set with `-D` on the JVM. The installed profile sets none. There are about 440 distinct `forbric.*` names in
`src/main` and `src/runtime`; almost every repair has an `-Dforbric.<name>=off` switch, and turning one off logs a
WARN stating what is lost. Those switches exist for bisecting and for gates' negative controls. The ones a
developer reaches for:

**Policy and reporting**

| Property | Effect |
| --- | --- |
| `forbric.compatibilityPolicy` | `ask` (default), `continue`, `strict`; anything else = `strict` |
| `forbric.dependencyDialog` | `on` (default), `off`, `dryRun` |
| `forbric.dialogLanguage` | force the dialog's language (e.g. `ja`) |
| `forbric.loadReportRewrite` | `off`: first write of `load-report.txt` wins |
| `forbric.crashAnalysis` | `off`: no `crash-analysis.txt` |
| `forbric.debug` | enable `ForbricLog.debug` lines (and the first `Caught.expected` line of each site) |
| `forbric.caught.printedPerSite` | how often the same caught failure is printed at one site before it is only counted (default 20, §12.5) |
| `forbric.caught.file` | also append each caught failure site and its count to this file; `FORBRIC_CAUGHT_FILE` does the same and is what the gates set |

**Arbitration and order**

| Property | Effect |
| --- | --- |
| `forbric.multiLoaderPreference` | per-jar ecosystem order, default `neoforge,minecraftforge,fabric` |
| `forbric.dupeIdPreference`, `forbric.nestedDupePreference` | cross-jar order for top-level / nested duplicates |
| `forbric.modOwner` | `id=loader,…` pins; also `<rundir>/forbric-mods.txt` |
| `forbric.crossJarArbitration` | `off`: two jars with one id both load |
| `forbric.nestedRequirements` | `off`: a nested Fabric mod whose `minecraft`/`java` range excludes this game, or that shares an id (provides included) with a mod Fabric Loader prefers, loads anyway; `off:<id>,…`: only those ids are exempted: they load anyway, and no longer push a rival out |
| `forbric.arbitrationMaxNodes` | selector work bound (default 100 000, capped at 1 000 000) |
| `forbric.modOrder` | `name`: file-name construction order |
| `forbric.fabricOrder` | `off`: Fabric mods follow the topological order instead of mod-id order |
| `forbric.fabricMainInConstructor` | `off`: client Fabric `main` entrypoints run in the pre-`Minecraft` window |
| `forbric.eagerLanguageAdapters` | `off`: a Fabric language adapter is built when an entrypoint first names it, not before Mixin |
| `forbric.loaderProbes`, `forbric.crossEcosystemPresence` | `off`: platform probes / presence answer as a single loader would |

**Mixin**

| Property | Effect |
| --- | --- |
| `forbric.relaxGuestMixins` | `off`: guest configs are not relaxed |
| `forbric.mixinCompat` | `latest`: every Fabric config at Mixin's newest level instead of the level Fabric Loader gives its mod (§7.1) |
| `forbric.captureLanding` | `off`: below 0.10.0, a TAIL capture the kernel moved after a call that falls into the tail's join gets no `NOP` to land on, and Mixin skips it (§7.1) |
| `forbric.lateInjectionIsolation` | `off`: a MixinExtras late injector that fails in an optional mixin hands its whole target class back unwoven, as upstream (§7.2); on, a newer MixinExtras a guest starts after install takes the same path, and the log says so once |
| `forbric.relaxMixinOverwrites` | csv (`*` glob) of configs to relax |
| `forbric.mixinDiagnostics` | keep injection requirements strict to surface every misfit |
| `forbric.mixinFit` | `strict`: also drop `PARTIAL` mixins |
| `forbric.mixinFit.liveness` | `off`: injectors on uncalled methods count as resolved |
| `forbric.mixinFit.nativeAbsent` | `off`: an injector target the mod's own platform lacks too counts as a missing anchor again |
| `forbric.mixinFit.nativeAbsent.base` | `<digest>`: the merged-base members digest `native-only-methods.txt` is trusted for instead of its own (fixture games in tests) |
| `forbric.mixinOverlapLint` | `off`: no cross-mod overlap findings at boot (§7.6) |
| `forbric.guestMixinAdapter` | `off`: no derived drops, only the hand list |
| `forbric.mergedBaseCompat` | `off`: drop the built-in incompatibility lists |
| `forbric.disableMixinConfigs`, `forbric.enableMixinConfigs` | csv of configs to disable / force on |
| `forbric.suppressMixins`, `forbric.keepMixins` | csv of `config:Mixin` to drop / keep |
| `forbric.lateMixinJudgment` | `off`: a mixin a config plugin adds is not judged when Mixin first reads it |

**Native-equivalence mechanisms** (§7.4.1, §6; each `=off` turns one off and logs what is lost):
`forbric.nativeOccurrences`, `forbric.addedCallOccurrences`, `forbric.nativeSliceOccurrences`, `forbric.returnOrdinals`,
`forbric.shiftedPoints`, `forbric.vanillaEarlyReturns`, `forbric.mixinStubRebind`, `forbric.builderStubRebind`,
`forbric.mixinTwinRebind`, `forbric.renamedBodyEdges`, `forbric.splitBodyEdges`, `forbric.forgeModelDataEdge`,
`forbric.nativeCallInlining`, `forbric.mixinFieldInitialisers`, `forbric.expressionThunks`, `forbric.carrierDecisions`,
`forbric.carrierGuardedPoints`, `forbric.carrierDefaultChains`, `forbric.carrierHelperResults`,
`forbric.carrierReturnedCalls`, `forbric.carrierMethodReferences`, `forbric.replacedDecisionValues`,
`forbric.nativeEntryRelay`, `forbric.nativeDecisionIsland`, `forbric.placementCallWitness`, `forbric.nativeValueStores`,
`forbric.nativeConsumerStores`, `forbric.nativeDeadValueConsumers`, `forbric.nativeLocalPins`,
`forbric.nativeLocalMaterialization`, `forbric.capturedLocals`, `forbric.localRanges`, `forbric.tailLocals`,
`forbric.scopedClearRelay`, `forbric.uncalledDecisionEntries`, `forbric.uncalledValueRelay`, `forbric.mixinAsideMarks`.

**Diagnostics and test drivers**

| Property | Effect |
| --- | --- |
| `forbric.clientSmoke` (+ `clientSmokeWorld`, `clientSmokeReadyTicks`, `clientSmokeDisconnectTicks`, …) | unattended client run: enter a world, live, leave, exit |
| `forbric.eventChainAudit` | report file for the cross-bus audit |
| `forbric.definedClassEvidence` | directory for a content-addressed record of every defined class |
| `forbric.traceClassDefine` | csv of binary names; log a stack the first time each is defined |
| `forbric.tickSampler` | `off`: no server tick-time sampling |

**Selected repair switches** — `forbric.arbitratedAwayEntrypoints`, `forbric.graphicsBootstrappers`,
`forbric.channelDeclarationsOnce`, `forbric.forgeConditionFunnel`, `forbric.forgeModelData`,
`forbric.creativeTabEcosystemOrder`, `forbric.glideFreshEquipment`, `forbric.staleWatchGuard`,
`forbric.commonNetworkInterop`, `forbric.playPayloadFallThrough`, `forbric.chunkExecutorGuard`,
`forbric.forgeCapabilities`, `forbric.forgeWorldgen`, `forbric.transferBridge`, `forbric.hopperFabricStorage`,
`forbric.clientResourcePreload`, `forbric.earlyConfigs`, `forbric.fabricHooks`, `forbric.fabricImpl`,
`forbric.kernelBundledFirst`, `forbric.modDataPacks`, `forbric.modMenuStandIn`, `forbric.forgeBreakExperience`,
`forbric.forgeFieldGetters`, `forbric.forgeFluidOwnType`, `forbric.forgeEntityFluids`, `forbric.ownFluidTypeClauses`,
`forbric.emptyFluidTypeTracker` (all five §5.3),
`forbric.supersededMixins`.
`forbric.kernel.registryRedirect=true` enables an experimental registry-wrapper redirect.

## 18. Invariants

Break one and the failure usually surfaces far from the cause.

1. **Boot code names no game type.** It reaches the game side by string, and every such string is in
   `KernelRuntimeClasses`. Anything shared across the boundary is `ALWAYS_PARENT`; anything game-side is
   `ALWAYS_GAME`. One copy of each per JVM.
2. **`MIXIN` is terminal.** Nothing registers into it through `TransformChain`; Mixin is served pre-Mixin bytes;
   the post-Mixin stage order is fixed.
3. **Loader identity exists before the first class reaches the Mixin transformer.** Seeding after that point
   lets a guest plugin's `<clinit>` decide MinecraftForge's `dist` forever.
4. **One registration window, one freeze.** Content registration happens between `unfreeze` and
   `closeRegistrationWindow`; the client reopens once, for its entrypoints, and re-freezes.
5. **The lifecycle trigger is redirected or the kernel does not boot.**
6. **Game buses start before setup phases; the payload phase closes after them.**
7. **Arbitration decides once.** The pre-scan's plan is consumed by both discoveries; later passes verify, never
   re-choose. Selection is bounded by work, not time.
8. **A repair that stands down says so** — `AnchorSet`/`AnchorLedger`, `EventBridges.verify`, a WARN per switch.
9. **Attribution never changes an outcome.** Error handlers and reports record; Mixin's decision and a mod's
   failure are left as they were. `CompatibilityDecision` never exits the JVM; only the launch boundary does.
10. **Nothing carrying Mojang, MinecraftForge or NeoForge bytes is committed or shipped.** The game side links
    against staged jars `compileOnly`; the installer builds them on the player's machine.
11. **No catch is silent.** Every catch rethrows or reports to `Caught` (§12.5), and a unit test that makes the
    kernel carry on from a failure fails unless it says so with `@ExpectCaught`.
12. **No repair keys on a name.** What triggers a repair is a shape — bytecode, descriptors, inheritance and
    interface relations, a contract, whether an event is delivered — never a mod id, a mod's class or package name,
    a jar name, a mixin config, mixin class, handler, `@Local` or `@Share` name, a mod's implementation class
    (fabric-api is a mod: its public API may be called, its implementation is not named), a one-mod bytecode
    template or a body hash. A mod is a sample and a test case, never the condition (§16.1).
13. **One delivery per native dispatch.** A listener gets an event as many times as its own loader would give it:
    a bridge stands down where the game delivers the event itself on that path, and owes it only where a way out
    exists that the mod's own game does not have (§8.1). "At least once" is not a passing check.
14. **The merge never loses more.** The merge-loss ratchet's ceilings only go down (§2.4).

## 19. Current state and known boundaries

- **Minecraft 26.2 only**, Mojmap identity namespace. There is no remapping step: a jar compiled against another
  namespace is not translated (`kernel/mapping/` is carried over from the weld and is not on the boot path).
- **The merged base is NeoForge's game with MinecraftForge spliced in, both edits kept where provable.** 240 methods
  carry both families' edits; 921 both-patched methods still keep one family's body (the edit splice could not prove
  a carry, or found a real conflict), and the merge loses 508 MinecraftForge and 15 NeoForge platform-call
  occurrences, held there by the ratchet (§2.4). What the loser's mods lose is repaired by transformers, guest-mixin
  mechanisms and bridges, and what is not repaired is reported by `DeadEventAudit`, `HookCallSiteCensus`,
  `FieldDriftAudit`, `AbiLinkAudit`, `CapabilityUseAudit`. Structural conflicts (two real superclasses for `Entity`)
  have no bytecode-level resolution; MinecraftForge capabilities are composed back in by transformer.
- **`PARTIAL` mixins apply by default** — half-application is kept, visible, in preference to dropping working
  hooks.
- **One class, one copy.** When two ecosystems' builds of a mod compete, one wins; the losing ecosystem sees a
  presence alias, not the mod's own platform glue.
- **Measured, not promised.** `MOD_TEST_FAILURES.md` records a per-mod test (each jar alone with its required
  dependencies, into a world, screenshot, exit) on three fresh random Modrinth sets against `main` as of 2026-09-26
  (`11ca1ffa`), before the edit splice: 89.0 % loaded without failure lines on average (91.8 % reached the world;
  79.1 % with nothing reported DEGRADED in the load report), against 80.5 % for release v0.2.0 on the same jars. That
  sweep has not been re-run on the edit-spliced base.
- **Native comparison, last round.** 27 mods were measured against their native loaders by the method of §16.1
  (instruction-level landing, liveness, in-game probes, rename experiment): Carpet, Create Fly, C2ME, Lithium (Fabric
  and NeoForge builds), Physics Mod, Sodium (NeoForge), Continuity, Iris, Corpse, Shoulder Surfing with
  CustomSkinLoader, fusion, Do a Barrel Roll, Controlify, Just Enough Characters, SpectreLib, Mod Menu, Forge Config API
  Port, PuzzlesLib, Item Glint Relight, Jade, Essential, fabric-api, Balm, Architectury, Origins Legacy, Better Mount
  HUD. 22 are equivalent; Lithium (Fabric) and Forge Config API Port are not only for reasons proven not closable,
  and Mod Menu, fabric-api and Balm are not for the reasons below. Every rename experiment came out the same as the
  original. The last round re-measured the ten rows below (on a base built by the current tools; the kernel at
  `91dbfa5d`, then the experience relay, the delegate-twin rule and the aside names of this round on top):

  | Mod | Verdict | What is left |
  | --- | --- | --- |
  | Mining experience, no mods | equivalent | a MinecraftForge listener that only reads `getExpToDrop` sees another draw |
  | Carpet | equivalent | — |
  | Create Fly (with fabric-api) | equivalent | fuel, redstone connection and the global keys are native now; report-only: its `FuelValuesMixin` still reads `SUSPECTED`, and a second preflight on the render thread prints `EntityMixin`'s old partial line again |
  | PuzzlesLib (Fabric and NeoForge builds) | equivalent | its experience event is handed the vanilla value and decides once (50 and 100 for two breaks, as natively) |
  | Mod Menu | not equivalent | the update checker announces an update for `fabricloader@0.19.3`; the carrier's branding line |
  | fabric-api 0.161 | not equivalent | datagen's `TagsProviderMixin`; `AFTER` has two static call sites (fires once per break); two proven items |
  | Balm | not equivalent | `SCREEN_OPEN` reads; crop moisture (proven) |
  | Origins Legacy (with fabric-api) | equivalent | — |
  | Architectury | equivalent | — (the global keys measured in game this round) |
  | Physics Mod | equivalent | — |

  Air under a fluid was measured again after that, on dedicated servers against native Fabric (with fabric-api and a
  Fabric test fluid, and with Create Fly too), NeoForge 26.2.0.88 and MinecraftForge 26.2-65.0.1, with Forbric run
  without fabric-api, with it, and with Create Fly. For every body that breathes where its eyes are — water breathing,
  conduit power, a drowned, a turtle helmet, an invulnerable player, lava, a mod's breathable fluid, Create's diving
  gear — Forbric's air now climbs by 4 a tick to 300 tick for tick as on native Fabric. Breath of the Nautilus alone
  holds it in water and in a drowning mod fluid outside the water tag — the Fabric test fluid as on native Fabric, a
  NeoForge fluid type that drowns as on native NeoForge, also with the cross-host predicate island switched off, where
  the hook decides alone — and refills it in lava and in a breathable fluid, as on native Fabric (native NeoForge and
  MinecraftForge hold it in every fluid). Air above the maximum (400 of 300) stays there in water with water breathing,
  in open air and with Breath of the Nautilus alone, as on native Fabric; native NeoForge pulls all three to 300,
  MinecraftForge the first two. Up to the maximum (the in-game listener scenarios start at 200), a NeoForge listener
  that sets the amounts or whether the body breathes gets exactly the curve it gets on NeoForge, and reads vanilla's
  refill (4) as the default where NeoForge hands it 0. Above the maximum the event starts from a refill of 0, and the
  air stays where it is unless a listener sets another refill (§6), where NeoForge pulls it to the maximum: a listener
  that only lets a drowning body breathe leaves 400 of 300 at 400, and so does one that sets the refill to 0 or to any
  negative amount (the event holds it at 0); NeoForge makes both 300 (from executing NeoForge's actual hook, decorated
  and switched off, not measured in game). With MinecraftForge's field getters, its fluids' own types and its breathe
  event added (§5.3, §8), the MinecraftForge server was measured again with a MinecraftForge test fluid that drowns
  and one that does not: 25 of 29 scenarios match native MinecraftForge tick for tick. Among them are the five where a
  MinecraftForge `LivingBreatheEvent` listener sets the refill (to 2 and to 0, and for a drowned), makes a
  water-breathing body drown, or lets a drowning body breathe (before the bridge none of the five listeners ran), and
  five of the six in the MinecraftForge fluids, which could not be measured before because their liquid block threw
  when placed: a body drowning in the drowning one, with water breathing, a drowned in it, Breath of the Nautilus
  alone in it, and a body in the breathable one. The other four are the vanilla rule above: Breath of the Nautilus
  alone refills in lava and in the breathable MinecraftForge fluid, where MinecraftForge holds, and air above the
  maximum stays where it is in water with water breathing and in open air, where MinecraftForge pulls it to 300; in
  the first two a MinecraftForge listener reads `canRefillAir` true where native MinecraftForge reads false. With
  `-Dforbric.forgeFluidOwnType=off` a body breathes in the drowning MinecraftForge fluid again (3 scenarios change). The
  NeoForge-with-fabric-api and Fabric-with-Create runs are identical, scenario for scenario, to the runs before these
  three changes. With the drown bridge (§8), eight drowning scenarios were added — a pig out of air in water, its air
  and health every tick and every MinecraftForge drown event its listener sees: reading only, cancelling, setting the
  body not drowning, setting the damage to 3 and to 0, setting the bubbles to 0 and to 50, and water breathing (no
  event). All eight match native MinecraftForge in air, health, and the number and ticks of the events: one event per
  drowning, handed `(true, 2.0, 8)` at air -20; a cancel or a body not drowning keeps the air falling past -20 with no
  damage, as natively; a changed bubble count changes nothing, as natively. Before the bridge the listener saw no
  event, and the seven scenarios with a listener drowned as with none. The probe measures air, health and events, not
  a body's random draws, which is what a written-back bubble count changed (the bridge's first version carried it
  back), so that rule is pinned by `KernelDrownBridgeTest` on the real buses instead. The 29 breathing scenarios match
  the runs without the bridge, scenario for scenario, and native MinecraftForge but for the four vanilla-rule
  differences above. Until this round the probe started a scenario in the tick it forced its pool's chunk, and three
  scenarios — the first in each new chunk, the first after start among them — spawned their mob into a chunk that did
  not tick entities yet: the mob's first samples show its eye block as water and its eye not in water, because it had
  not ticked. Their air then started changing however many ticks the chunk system took later, native and Forbric
  alike. Over the runs made that way, the pig sinking into the drowning MinecraftForge fluid started a tick earlier in
  three of eight native runs and in one of twenty Forbric runs (one with two more mods loaded); the pig in water
  started a tick later in six of fourteen Forbric runs with the bridge, none of six without it and none of eight native
  runs (one native run was six ticks later); the pig in open air started refilling a tick earlier in two of the
  fourteen. The probe now waits until the pool's chunk ticks entities before a scenario starts — those three wait 2 to 5
  ticks, the rest none — and with that all nine runs made this way (three native, three with the bridge, three without
  it) agree with each other within each group in every scenario, the three with them; with the bridge they differ
  from native MinecraftForge only in the four vanilla-rule scenarios, and from the runs without it only in the seven
  drowning scenarios with a listener. The waits themselves still move (the pig in open air waited 2 ticks in the runs
  without the bridge, 3 in the others): the chunk system's timing differs from build to build and run to run, and the
  probe no longer measures it.

  Then these changes were put in game together, as gate-m57 (§16) and a developer probe built from its canaries
  (`canary/forge-breath-drown`, plus two builds that stay out of the repository: a Fabric build of the same probe that
  records fabric-api's `ALLOW_DAMAGE` and `AFTER_DAMAGE`, fabric-api being a third-party jar, and a NeoForge build
  with two `LOWEST` drown listeners, one registered in its constructor and one from common setup, which only measures
  an order): 53 scenarios (54 in the Fabric build, 55 in the NeoForge one), three runs per arm, and every arm
  identical across its three runs, including the random draws and entity events below. The probe ticks its player as a
  server ticks one — `tick()`, which counts the hurt cooldown down (`LivingEntity.baseTick` leaves that to it for a
  `ServerPlayer`), then `doTick()`; with `doTick()` alone, as the gate's first version did, only the first of three
  drownings got past the cooldown, on every loader alike — and gives it an entity tracker of its own, so the server's
  broadcasts for it are measured as a pig's are. The arms: native MinecraftForge, NeoForge and Fabric with their own
  canary, and Forbric with each canary alone, with the MinecraftForge and NeoForge canaries together (the gate's
  pack), with all three and fabric-api, and with the gate's pack and the NeoForge build. With one family alone Forbric
  matches its native loader in air, health, every listener's view and every liquid row of every scenario that loader
  can run (MinecraftForge 51, NeoForge 47, Fabric 46; the rest use another family's liquid) but where the vanilla
  refill the owner chose (§6) decides — one MinecraftForge and two NeoForge scenarios: a pig with water breathing in
  water refills there, where native NeoForge holds its air; with a NeoForge listener that refills nothing there, the
  air is the same but that listener is offered vanilla's 4 where native NeoForge offers 0; and a pig with Breath of
  the Nautilus alone in MinecraftForge's `canDrown(false)` liquid refills, where native MinecraftForge holds its air
  (below). The only other differences are the drowning path's random draws and entity events (below). In the mixed
  packs each scenario is judged against the native loader whose answer stands, and all 53 pass in all nine runs.
  MinecraftForge's
  listeners answer after NeoForge's in the same tick and are handed NeoForge's answer: a NeoForge damage of 3 or
  bubble count of 0 reaches them, a NeoForge cancel or "not drowning" keeps them from being asked, and a NeoForge
  listener that makes a body drown at air 0 gets them asked from air 0 on (a design choice: no native loader has both
  families). The NeoForge canary's breathe and drown listeners at `LOW`, registered from common setup after the
  kernel's bridges, also answer first in every tick of every run: MinecraftForge's listener is handed the damage of 3
  and the refill of 0 they set, and their cancel keeps it from being asked; so do its listeners at `LOWEST`, registered
  in its constructor before the bridges, whose damage of 3 and refill of 0 MinecraftForge's listener is handed in
  turn. That order holds for every NeoForge
  listener above `LOWEST` and every one at `LOWEST` registered before
  the kernel's bridges, and not for one registered at `LOWEST` later: the NeoForge build's constructor listener sets a
  drowning pig's damage to 5, MinecraftForge's listener is handed 5 and sets 3, and the pig takes 3; its common-setup
  listener is asked after MinecraftForge's, which is handed 2 and sets 3, and the pig takes 5. MinecraftForge
  `@SubscribeEvent` listeners that return true cancel drowning and damage as natively. A MinecraftForge bubble count
  of 0 or 50 leaves the body's random draws exactly as with none, as natively; the gate judges this, since a count
  carried back would make NeoForge's hook draw 0 or 300 numbers. With Breath of the Nautilus alone, MinecraftForge's
  listeners are told the body may not refill in water and in MinecraftForge's liquid and may in a bubble column, and a
  refill of 2 they set in water is not taken while one they set with "may refill" is, as natively; where a NeoForge
  listener refills nothing in a bubble column, MinecraftForge's is offered that nothing, may refill, and its refill of
  2 is taken; the same where a NeoForge listener refills nothing with water breathing in water or with Breath of the
  Nautilus alone in open air (MinecraftForge's rule says yes there by its effects clause and by its eye clause, each
  alone), as natively. A pig drowns in MinecraftForge's own liquid at the native ticks, with the native listener
  events (not the native entity events: as for every drowning here, no entity event 67 is broadcast and the body draws
  NeoForge's 99 steps on the drowning tick where native MinecraftForge's draws 3, below), and in a
  MinecraftForge liquid built `canDrown(false)` it breathes and refills 4 a tick, as natively: only that type's
  `canDrownIn` says so, which NeoForge's view of the type asks. With Breath of the Nautilus alone in that liquid the
  body refills 4 a tick up to 300, as in open air, and MinecraftForge's listener reads `canRefillAir` true while it
  does (false once the air is full), where native MinecraftForge reads false and holds the air at 200: the owner's
  vanilla refill (§6, §8.1), a difference the gate pins (the row must refill as open air does natively, and the
  reference must still show the native hold). The probe's
  player drowns three times, each through MinecraftForge's attack, hurt and damage events with the native amounts (20
  to 18, 16 and 14; a halved hurt halves each, a cancelled damage event keeps the health); a NeoForge incoming-damage
  cancel stops MinecraftForge's hurt and damage but not its attack, which MinecraftForge posts first, at the head of
  `hurtServer`, for the player and for a pig alike; and fabric-api's `ALLOW_DAMAGE` sees and denies those drownings as
  on native Fabric. A MinecraftForge `LiquidBlock` built from a supplier is placed (no exception, a source block),
  taken up with an empty bucket into its own bucket and poured out again, as natively. No defect was found. The gate's
  negative controls each fail exactly their rows, each for its cause: `-Dforbric.unifiedEvents=off` the 43 judged on
  what a MinecraftForge breathe or drown listener heard or was handed, each on that view;
  `-Dforbric.forgeFieldGetters=off` the six in or with MinecraftForge's two liquids, each because its block throws
  `"this.fluid" is null` when placed (its basin does not build, and every scenario in it fails with that cause);
  `-Dforbric.forgeFluidOwnType=off` the three with a body in its drowning liquid, which refills there (in the
  `canDrown(false)` one a body breathes and refills either way, natively too: without its own type that liquid is air
  to NeoForge); `-Dforbric.forgeDamageSeams=off`
  the ten judged on a MinecraftForge Hurt listener, each on a damage-chain view (MinecraftForge's attack is the
  edit-spliced base's own call, so the incoming-damage cancel rows hold). Four mutations of the breathe and drown
  bridges in scratch copies of the tree each turn the gate red: a refill taken where MinecraftForge said the body may
  not refill (the positive phase fails the one row where MinecraftForge's listener sets a refill in water with Breath
  of the Nautilus), MinecraftForge's refill rule always yes (it fails the four Breath of the Nautilus rows in water
  and in MinecraftForge's liquid), its bubble-column clause dropped (both bubble-column rows), and the bubble count
  carried back (both bubble-count rows: their bodies draw 3 and 603 steps on a drowning tick where drown-late's draws
  99). A review then found three changes the gate's first 44 rows let through; each now turns it red: NeoForge's view
  of a MinecraftForge type without its `canDrownIn` (the `canDrown(false)` row fails: the pig loses air and drowns),
  and the breathe and drown bridges registered at `NORMAL` or at `LOW` instead of `LOWEST` (the rows with the
  setup-registered `LOW` listener fail on their values — MinecraftForge handed 2, not that listener's 3, the pig hurt
  by 3, a refill of 0 that stands — and every row with both families on the answering order).
  `KernelBreatheBridgeTest` and `KernelDrownBridgeTest` now drive both priorities on real buses as well (at `NORMAL`
  three of their tests fail, at `LOW` two). The review also passed the bubble-count rows with their random draws
  emptied, or set to -1, on both sides; the judge now takes draws as evidence only where the probe counted them on
  every sampled tick, as it did in every run above. A third review found two more holes, now closed: MinecraftForge's
  refill rule without its effects clause left the gate green (no row had that clause alone saying yes: the water
  breathing row starts at air 5 and never reaches the full air at which NeoForge refills nothing), and the order
  check passed rows whose order the probe had not recorded (every row's order removed: still green). Each clause of
  the rule now decides one row alone, and in scratch copies of the tree the effects clause dropped fails the water
  breathing row, the eye clause dropped the open-air Breath of the Nautilus row, each on its air, under every phase
  but bridges-off; the breathe and drown bridges installed before the mods are constructed (still at `LOWEST`) fail
  both `LOWEST` rows on their values (the pig hurt by 3, a refill of 0 that stands) and every row with both families
  on the answering order; and a row where a NeoForge listener and MinecraftForge's both answered with no recorded
  order fails (the gate's own positive result with every order removed fails all 50 rows that have one). Not
  measured: single player, a rendered client, lava, and a MinecraftForge fluid in the water tag.

  A MinecraftForge mod's fluids were then measured against native MinecraftForge 26.2-65.0.1 on dedicated servers,
  with Biomes O' Plenty's blood and liquid null and five MinecraftForge test fluids (one that drowns and one that does
  not, in no tag; two in `minecraft:water`, one built like water and one whose type answers every entity question
  unlike water; one in `minecraft:lava`), plus water and lava, each through 18 scenarios: a body sinking, floating and
  falling 24 blocks into it, on fire, out of air, a boat, an item, a sprinting player, spread on a platform, two
  sources a cell apart, meeting lava and water three ways each, bucket pick-up and pouring, and pouring in the nether.
  At 5fbf7de1, 36 of the 162 scenario rows differed from native: a fluid in `minecraft:water` or `minecraft:lava` was
  neither water nor lava to NeoForge's entity code (not in water, not under water, no fall reset, no swimming, not in
  lava, other motion), and a fall through any MinecraftForge fluid was shortened by its type's factor (a pig that dies
  landing through Biomes O' Plenty's blood on native MinecraftForge survived it); MinecraftForge's entity-fluid queries
  answered "no fluid" in every row. With the views' answers, the two water rules and MinecraftForge's entity-fluid state
  (§5.3), all 162 rows are identical to native, and so are the MinecraftForge queries' answers in each. World behaviour
  — spread, new sources, obsidian, stone and cobblestone, Biomes O' Plenty's flesh and null block, buckets, the nether —
  was already identical (with `-Dforbric.forgeFluidOwnType=off`, a fluid in `minecraft:water` makes obsidian next to
  lava, which native MinecraftForge does not). The probe's current version (L) adds a sprinting player wading in a pool
  one cell deep (body in, eyes out), a still body held above the top cell's fluid surface in that cell's own block,
  and each player's `Player.wasUnderwater` (what the two water rules set; the client's `LocalPlayer.isUnderWater`
  reads it): the MinecraftForge set is 189 of 189 rows identical to native MinecraftForge, before and after this
  round's additions. The same scenarios with NeoForge test fluids, on native NeoForge 26.2.0.88 and on Forbric: the
  sample rows are identical before and after the first of these changes (149 of 149), but NeoForge's own entity view,
  which the probe prints beside the samples (its no-argument `isInFluidType()`, eye type and tallest type), changed
  in 70 rows: the merged `Entity` answers `isInFluidType()` with MinecraftForge's body, which said no everywhere and
  now says MinecraftForge's "any fluid cell the walk read". Native NeoForge's is `isInAnyFluid()`, a tracker with
  height: the still body above the surface is in no fluid on native NeoForge and in one on Forbric, in all seven
  fluids, vanilla water and lava included; a body in a fluid answers alike. Left so: one JVM method with two meanings,
  nothing in the merged game calls it, and among the 1 976 local jars only a MinecraftForge mod does (Tombstone's AI
  goals), no NeoForge mod. A NeoForge test fluid in `minecraft:water` whose type does not extinguish kept a pig burning
  on native NeoForge and put it out on its first tick on Forbric, before and after the first changes: the merged
  `Fluid.entityInside` also asked MinecraftForge's type, for a NeoForge fluid the kernel's water stand-in (§5.3); with
  each family asked only its own type, that row is native's. 30 rows still differ from native NeoForge: 5 are the
  vanilla air refill (§6, by design), and 25 are motion in NeoForge's own fluids — native NeoForge's `travelInFluid`
  moves a body in a type that is neither water nor lava only through the type's `move`, which these types do not
  implement, so the body does not move at all (a falling pig stops in mid-air at the surface, its fall distance
  halving every tick), while the merged `travelInFluid` is MinecraftForge's, which moves it as through lava. Not
  changed: the merge kept one of two travel rules for one method, and native NeoForge's leaves a body hanging.

  Fabric fluids had not been measured on this branch; they are now, against native Fabric (Loader 0.19.5 with
  fabric-api 0.161.0), with four Fabric test fluids written as Fabric mods write them (a `FlowingFluid` with no
  loader's type: one in no tag, two in `minecraft:water` — one that makes new sources like water, one that does not —
  and one in `minecraft:lava`) plus water and lava, through the same scenarios (129 rows). The base tree 5fbf7de1 and
  this branch's first version were identical in all 129 rows, entity views included, and 10 rows differed from native
  Fabric: both water-tagged fluids put out a burning pig, which vanilla does only in `WaterFluid` (the merged
  `Fluid.entityInside` asked both families' stand-ins), the untagged one moved bodies as through lava where native
  Fabric lets them fall through as through air (five rows; NeoForge's walk tracked the empty type), and a sprinting
  player in water and in both water-tagged fluids starts swimming on its first tick instead of its second. With each
  family asked only its own type and the empty type untracked (§5.3) the first seven rows are native's. The three left
  are the merged game's swim start, NeoForge's and MinecraftForge's alike ("under water, or eyes in a type it can swim
  in", where vanilla's `isUnderWater()` reads the eye flag of the tick before), the same in vanilla water; not changed.
  MinecraftForge's `isInFluidType()` now says yes in a Fabric fluid, as MinecraftForge says for any fluid cell.

  On the client, native MinecraftForge against Forbric with Biomes O' Plenty, each fluid seen from inside and from
  above: at 5fbf7de1 its blood and liquid null had the sky's fog colour and no eye overlay, and a MinecraftForge fluid
  in `minecraft:water` had water's fog but not water's overlay. With the changes, in the run on this round's tree, all
  twelve MinecraftForge and vanilla frames have native's centre colours exactly (animated lava from inside equals the
  second native run, which differs from the first by three steps), and what the client reported in each (eyes in
  water, under water, in shallow water, MinecraftForge's eye type, the camera's fog type) is identical to the earlier
  runs; the earlier runs had two frames within three steps and one where the flying player had moved off its spot
  (no gravity there, so by movement input; not seen in this run). Biomes O' Plenty's fluids draw as the missing
  texture on native MinecraftForge too: 26.2's model set has no model for them and the mod supplies none. The two
  NeoForge test fluids' frames were compared on Forbric alone and are identical to the run before. Not measured: a
  native NeoForge client, a Fabric fluid on the client (no native Fabric client here), the bucket sound of a NeoForge
  fluid with a sound of its own (held by the staged edit test; no probe scenario plays a custom sound), multiplayer,
  Windows and real launchers. Known gaps: the view answers `isWaterLike` and the tag half of `canSwim` per
  MinecraftForge type, not per fluid (a type whose source is in `minecraft:water` and whose flowing form is not counts
  as water in both); it does not forward a MinecraftForge type's positional getters (`getLightLevel(state, level, pos)`
  and the three beside it), its `FluidStack` overloads or `shouldUpdateWhileBoating`; tag trackers a MinecraftForge mod
  registers through `EntityFluidInteraction.register` are not recorded (no jar in the local corpus calls it); and
  NeoForge's `isInFluidType()`, native NeoForge's travel in its own fluids and the swim start, above.

  Tests. Off game no entity, world or fluid state can exist (their classes need the bootstrapped registries), so
  `KernelForgeEntityFluidsTest` drives the record's own steps with what the merged `update` hands them — the body's
  heights and column, each cell's fluid, position and height — over mod fluids compiled as a MinecraftForge mod's are.
  Mutations of the code, a change at a time, against the focused test classes (70 tests): of a reviewer's five
  changes that passed every test in the last round (the eyes' column checked by x alone, a tracker's height
  overwritten instead of kept at its greatest, for the type and for the tag, water-likeness from the first fluid seen
  with a type, MinecraftForge's fog handed a blank colour), each now fails a test. The eight that need a live entity or
  a bootstrapped registry still pass every unit test; until now six of them had only been reasoned to be held by the
  probe. Each was run in game this round, against an unmutated run of the same copy: MinecraftForge's empty eye type
  dropped (36 rows, the probe's own type questions throw), the water type widened by the lava tag (36 rows), the eyes
  read at the feet (26), the swim start counting "in water" for "under water" (1, the sprinting player in a fluid in
  `minecraft:water` whose type is unswimmable), NeoForge's own answer dropped for a non-view type in the underwater rule
  (1 with MinecraftForge's driver — the `wasUnderwater` flag in water — and 4 with NeoForge's) and in the shallow-water
  rule (9, NeoForge's own swimmable fluids, NeoForge's driver), the swim start by type alone (1), NeoForge's three
  queries skipping the tag widening (27). Of this round's own changes, each family's clause left unfiltered for a stand-in — MinecraftForge's (1 row with NeoForge's driver, the fire row; 2 with Fabric's, both fire rows) or NeoForge's (2) — the NeoForge stand-ins left unrecorded (2) and NeoForge's getters left unedited (2) pass every unit test (neither family's empty type can be made off-game) and are caught in game; tracking the empty type again (5 rows, the untagged Fabric fluid) and leaving NeoForge's getters unedited also fail the execution and staged tests, dropping either registration fails the registration test, and the census's bare-name keys put back fail the census test. The record's stand-in pre-check, a performance change, changes no row in game (189 of 189 the same with it removed) and is held by its own unit test. On this round's tree, with `-Dforbric.forgeFluidOwnType=off` 40 of the 189 rows differ from native MinecraftForge (the MinecraftForge fluids go back to their tags: an untagged one is no fluid to NeoForge's entity code, and one in `minecraft:water` makes obsidian next to lava), with `-Dforbric.forgeEntityFluids=off` 27 (every scenario of the three test fluids in `minecraft:water` or `minecraft:lava` with a body in it).

**What is still not native-equivalent.** Proven not closable by any structural trigger (each proof kept):

- Forge Config API Port's own `config/forgeconfigapiport.toml` is never written: the only code that reads or writes it
  is the port's bundled copies of carrier classes (`ConfigTracker`, `ConfigurationScreen$TranslationChecker`), which
  share their names with the NeoForge carrier's and can never be defined. Its three options are read from the
  same-named entries of `fml.toml`.
- With fabric-api, advanced tooltips show extra lines such as `[1 + 6]`: both ecosystems append to one shared tooltip.
- Balm's crop-moisture hook: the carrier replaced the moisture check with a virtual `isFertile` call.
- Lithium's Fabric build: three hopper injectors attach nowhere. NeoForge moved the neighbour notification into
  `markAndNotifyBlock` and looks containers up in three steps (`ContainerOrHandler`) that cannot be split into
  vanilla's two (Lithium's own NeoForge build does not apply them either). Only the hopper container cache, an
  optimisation, is inactive.
- Channel declarations: per join, `REGISTER` fires 5 times on the client and 4 on the server in the configuration
  phase (native once each), 3 times in play (native 2), plus 3 `UNREGISTER`s.

Located, not yet fixed (no impossibility proof):

- Mod Menu: the `fabricloader` container reports version 0.19.3 — the Fabric Loader API level the kernel implements and
  `FabricLoaderApiSurfaceTest` holds it to — where native reports the loader actually run (0.19.5), so Mod Menu's update
  checker announces an update native does not; the loader-nested `mixinextras` is the kernel's 0.5.4 (native 0.5.5),
  and the container's type is `builtin` (native `fabric`). Reporting a version the kernel does not implement would be
  untrue, so this waits for the API itself to reach 0.19.5. The title screen also shows Forbric's own branding
  line, `forbric-v<version> (N mods)` with N the jars in `mods/` (Mod Menu's count also takes in the mods bundled
  inside them), which moves Mod Menu's up one line; the owner chose to keep it.
- Balm's `SCREEN_OPEN` hook (a modifier at each read of `Gui.screen`) attaches at 3 reads where native has 4: the
  carrier keeps the field in a local and reads it once less.
- fabric-api: `PlayerBlockBreakEvents.AFTER` has two static call sites after `Block.destroy` (native one), though it
  fires exactly once per break in every mode measured; the enchanting table's primary-item check goes through
  NeoForge's `isPrimaryItemFor`, proven only for the disjunct it folds (the in-game enchanting probe matches native);
  datagen's `TagsProviderMixin` attaches nowhere (Fabric data generation only).
- Air, the random sequence: a body with an oxygen bonus (Respiration) draws its own random every tick it is alive on a
  server, where vanilla draws only while it drowns. The merged `baseTick` hands NeoForge's breathing hook its consume
  amount up front, `air - decreaseAirSupply(air)`, and `decreaseAirSupply` calls `random.nextDouble()` whenever the
  `OXYGEN_BONUS` attribute is above zero; vanilla calls it only in its drowning branch (native NeoForge and
  MinecraftForge draw as Forbric does). The air values are vanilla's — the amount is used only when the body does not
  breathe — but that entity's later random draws (movement, sounds, particles) are not. It can be closed as a trade-off,
  not built and not measured: around that one call in `baseTick`, keep the state of the entity's random, draw, and put
  the state back, then move the random on as the call did only where the hook uses the amount (the event left the body
  not breathing), which is where vanilla draws. A listener reads the same amount as on NeoForge, and while no listener
  draws from the entity's random during the event the sequence is vanilla's. The costs: a NeoForge listener that does
  draw from it gets the value the amount was drawn from where NeoForge gives it the next one; keeping and restoring the
  state depends on the random source's implementation (the entity's is vanilla's `LegacyRandomSource`, whose
  `nextDouble` moves only its private `seed`); and an override of `decreaseAirSupply` that draws from another source
  still draws every tick.
- Forty-seven MinecraftForge events that MinecraftForge 26.2 posts are never posted here and not bridged. The census
  found them once it had to account for every event it measures as never posted (§8); `DeadEventAudit` names a mod
  waiting on one, marking it DEGRADED, and the merged game posts NeoForge's event (or nothing) in their place. Common:
  `PlayerXpEvent.XpChange` and `.LevelChange`, `ItemTossEvent`, `ItemExpireEvent`, `EntityItemPickupEvent`,
  `PlayerEvent.ItemPickupEvent`, `AnimalTameEvent`, `LivingChangeTargetEvent`, `LivingDestroyBlockEvent`,
  `EntityMobGriefingEvent`, `LivingUseTotemEvent`, `LivingGetProjectileEvent`, `LivingEntityUseItemEvent.Start`,
  `LivingSwapItemsEvent.Hands`, `LivingPackSizeEvent`, `MobSpawnEvent.PositionCheck` and `.SpawnPlacementCheck`,
  `LevelEvent.PotentialSpawns`, `EntityTeleportEvent.EnderPearl` and `.SpreadPlayersCommand`,
  `PlayerSleepInBedEvent`, `SleepingLocationCheckEvent`, `SleepingTimeCheckEvent`, `SleepFinishedTimeEvent`,
  `PlayerSpawnPhantomsEvent`, `PlayerEvent.NameFormat` and `.TabListNameFormat`,
  `PlayerInteractEvent.LeftClickEmpty` (posted natively on the client only), `AlterGroundEvent`,
  `NoteBlockEvent.Play` and `.Change`, and `BlockEvent.EntityMultiPlaceEvent` (a bed or a door; lost to
  `routePlaceItemHookToNeoForge`, see §8). Client: `RenderNameTagEvent`, `RenderAvatarEvent.Pre` and `.Post`,
  `RenderArmEvent`, `RenderTooltipEvent.Pre`, `ScreenEvent.CharacterTyped.Pre` and `.Post`,
  `ScreenEvent.MouseButtonPressed.Post`, `.MouseButtonReleased.Post`, `.MouseDragged.Post`, `.MouseScrolled.Pre`,
  `ScreenEvent.RenderInventoryMobEffects`, `InputEvent.MouseButton.Post`, `PlaySoundEvent`,
  `GatherAtlasMetadataSectionsEvent`. Each row's sentence says what a player loses against native MinecraftForge. No
  NeoForge event is among them. In a pack where a mod posts one itself (Tombstone posts `PlayerSpawnPhantomsEvent`),
  that one is not reported (§8). Four more are never posted here either and are not listed, because MinecraftForge
  26.2 never posts them: `PotionBrewEvent.Pre`, `EntityTeleportEvent.ChorusFruit`, `ChunkDataEvent.Load`,
  `CustomizeGuiOverlayEvent.DebugText`.
- Five more MinecraftForge events lost native posting sites in the merge and no table names them, so a mod waiting on
  one is not marked DEGRADED and nothing is logged. The census counts an event as posted while any path reaches its
  poster, and a carrier caller that is not itself a hook counts outright; `PARTIAL` rows are measured only for
  `ForgeEventFactory` and `ForgeEventFactoryClient`. `PlayerContainerEvent.Open`: native `ServerPlayer` posts it through
  `ForgeEventFactory.onPlayerOpenContainer`; the merged one constructs NeoForge's `Open` at three sites and still calls
  MinecraftForge's close hook, so a MinecraftForge listener is told a vanilla container closed but never that it opened
  (the hook's one remaining caller is `IForgeServerPlayer.openMenu`, which only a MinecraftForge mod's own menus go
  through). `PlayerEvent.HarvestCheck`: native `Player` and `BlockBehaviour` reach it through `doPlayerHarvestCheck` and
  `ForgeHooks.isCorrectToolForDrops`; the merged ones call no such hook, and the merged `Block.canHarvestBlock`,
  which takes the place of MinecraftForge's `IForgeBlock.canHarvestBlock` default, calls NeoForge's.
  `BlockEvent.CropGrowEvent.Pre`: `ForgeHooks.onCropsGrowPre` has ten game call sites natively and two merged (cactus
  and sugar cane; the merged `CropBlock` asks NeoForge's `CommonHooks.canCropGrow`), and `.Post` keeps nine of ten
  (`StemBlock` calls neither). `BlockEvent.FluidPlaceBlockEvent`: the merged `LavaFluid` posts only NeoForge's at its
  three sites; MinecraftForge's own `FluidInteractionRegistry` still calls the hook from a lambda. Read from the
  bytecode (MinecraftForge's patched game against the merged base, the `useOn` repair applied), not measured in game.
- An item used up on a block, under MinecraftForge (the last block of a stack placed, a tool broken by `useOn`):
  natively `ForgeHooks.onPlaceItemIntoWorld` posts `PlayerDestroyItemEvent` when the stack is empty afterwards; the
  same `routePlaceItemHookToNeoForge` leaves that hook uncalled, and NeoForge's hook posts NeoForge's event there, which
  no bridge forwards. The event is posted on its other paths, so it is not `DEAD`, and the `PARTIAL` counts, which
  count the game's call sites of `ForgeEventFactory`, do not show a path lost inside a carrier hook. Read from the
  bytecode, not measured.
- Placing a block that is more than one block, under MinecraftForge: the place bridge forwards every NeoForge
  `EntityPlaceEvent` it sees, and NeoForge's bus hands it the `EntityMultiPlaceEvent` (a subclass) too, so a
  MinecraftForge `EntityPlaceEvent` listener is told of a bed or a door as one placement of its first block. Natively
  MinecraftForge posts only `EntityMultiPlaceEvent` there, on its own bus (EventBus 7 does not hand a subclass event
  to a superclass listener unless it is `InheritableEvent`), so such a listener is not told at all. Both buses'
  dispatch was checked on their real classes (a child event posted, a parent listener on each bus); the placement
  itself was not measured in game.
- Drowning, in every pack: the merged `LivingEntity` hands a drowning body to NeoForge's hook alone, and that hook,
  unlike MinecraftForge's and vanilla's, never broadcasts entity event 67 — so a client shows no burst of drowning
  bubbles — and on the server draws six `nextDouble`s from the body's random source per bubble (48 with the default 8)
  before it asks the level for a particle, which a server level ignores. After every drowning the body's random
  sequence (the pitch of its hurt sound in that tick, its later AI and ambient sounds) is not the one native
  MinecraftForge or Fabric gives it; air, health and every listener's view are unchanged. Native NeoForge does the
  same. Measured with gate-m57's canary (each scenario seeds the body's random source and counts the
  linear-congruential steps from tick to tick; a stand-in in the body's entity tracker records the entity events the
  server sends), three runs per arm: a pig drowning in water moves its random source 3 steps on a drowning tick on
  native MinecraftForge and native Fabric, and 99 — 96 more, 48 `nextDouble`s — on native NeoForge and on Forbric with
  only MinecraftForge mods, only NeoForge mods, only Fabric mods, both Forge families, or all three; the probe's
  player moves 0 steps against 96 on each of its three drownings. Native MinecraftForge and Fabric broadcast event 67
  on each drowning tick, for the pig and for the probe's player (which the probe gives an entity tracker of its own,
  as a player in a level has), native NeoForge and Forbric none. Left as it is: sending the event and dropping the
  draws would take NeoForge packs off native NeoForge, a random-sequence trade-off for the owner to decide. gate-m57
  judges neither (it judges the random draws only where a MinecraftForge bubble count must leave them as they are).
- Experience: a MinecraftForge `BreakEvent` listener that only reads `getExpToDrop` sees MinecraftForge's own draw, not
  the one NeoForge drops (a listener that sets it decides correctly). The relay of `tryDropExperience` (§7.4) declines,
  with a WARN, a hook that reads or changes the raw draw or replaces the `popExperience` call, and runs for a block
  only when what its native caller hands the provider can be read (a field, a static field, a static call on
  constants); a NeoForge `BlockDropsEvent` listener at `LOWEST` registered before the kernel's sees the value before
  the relay. Read from the bytecode, not measured: NeoForge's `handleBlockDrops` calls `spawnAfterBreak(…, false)`, so
  a Fabric mod's own block whose `spawnAfterBreak` override pops experience when `dropXp` is true pops none.
- Two mods' `HEAD` hooks on one static decision nothing calls any more (`UncalledDecisionEntries`) become two wraps of
  the carrier's decision: they run in the reverse of the native order, and a cancel by the first no longer skips the
  second (pinned by `UncalledDecisionTwoMixinsWeaveTest`; no measured mod has two such hooks).
- For developers: a merged base built before the delegate-twin pass (§2.4) still gives no experience from mining, and
  `UncalledMethodCensusTest` and gate-m43 fail on it; rebuild the staged base with the current tools (CI and the
  installer always do).
- Report-only noise, behaviour equivalent: Sodium's `WindowMixin` "partial 3/4", the `MixinOverlap` R3 warning on
  Sodium and fabric-rendering, Continuity's stale "partial (kept)", the contended call-site warning for Shoulder
  Surfing with CustomSkinLoader, Create's `FuelValuesMixin` `SUSPECTED` and the render thread's second preflight of
  `EntityMixin`. The loader-nested `mixinextras.init.mixins.json` (no mixin, only MixinExtras' init) is now applied in
  every pack, as Fabric Loader does, a MinecraftForge-only pack included, where native MinecraftForge has no such
  config.
- Not measured: Controlify's gamepad features (no gamepad); Sodium's NVIDIA/AMD/Intel workaround branches (only Apple
  hardware was available; the code path is the same on both sides); the load point of a Fabric consumer's server
  config under SpectreLib (`ServerAboutToStart` instead of `SERVER_STARTING`, both before the world loads; argued
  from bytecode, no such consumer to run).
- A deliberate difference: genuine NeoForge 26.2.0.88 crashes rendering a Corpse corpse (`NoSuchFieldError
  NeoForgeMod.NAMETAG_DISTANCE`); `ZeroNameTagMigrationInjector` proves the field is only used to set 0 and reads
  `Attributes.NAME_TAG_DISTANCE` instead, so the corpse renders without a name tag.
- **Versions.** `forbric-kernel/build.gradle` says `0.1.0-SNAPSHOT`; the installer is `0.3.1-beta2`. `net.forbric.api`
  is internal and changes without notice.

## 20. Further reading

- [`forbric-kernel/README.md`](forbric-kernel/README.md) — the kernel's own summary and gate notes
- [`forbric-kernel/run/compat/PROTOCOL.md`](forbric-kernel/run/compat/PROTOCOL.md) — the compatibility sweep procedure
- [`forbric-loader/README.md`](forbric-loader/README.md), [`forbric-loader/run/README.md`](forbric-loader/run/README.md) — the first generation and the artifact pipeline
- [`forbric-loader/CREDITS.md`](forbric-loader/CREDITS.md), [`forbric-loader/MAPPINGS.md`](forbric-loader/MAPPINGS.md) — the clean-room boundary and mapping position
- The class javadoc. Almost every class under `net.forbric.kernel` opens with the failure it exists for.

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.
