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
| **merged base** | `patched-mc-merged-26.2.jar`: Minecraft 26.2 carrying both Forge families' patches, byte-merged into one jar |
| **carrier** | one Forge family's runtime jar (`neoforge-runtime.jar`, `forge-runtime-interop.jar`), loaded as a passive ABI provider — its classes exist, its loader lifecycle never runs |
| **boot side / game side** | code loaded by the system class loader vs. code defined by `ForbricClassLoader` |
| **guest** | anything belonging to a third-party mod (guest mixin, guest jar) |

---

## 1. The problem

Three loaders assume they own the process. Running their mods together breaks in five independent ways, and
every part of the kernel answers one of them.

1. **Launch ownership.** Fabric starts Knot; MinecraftForge and NeoForge start ModLauncher/BootstrapLauncher with
   a JPMS module layer. Two transforming class loaders means two definitions of every game class.
2. **One class, three patch sets.** Both Forge families patch the *same* `net.minecraft` classes with different
   hooks, and a JVM can hold one version of each class. Something has to decide, per class and per method,
   whose body survives — and then live with what the loser's mods expect.
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
(package `net.forbric.kernel.runtime` and its `soak`/`transfer` subpackages, 130 files), is compiled `compileOnly` against the staged game artifacts,
packaged as `forbric-kernel-runtime.jar`, nested at `META-INF/jars/` inside the boot jar, extracted at launch by
`KernelBundledJars` and loaded as an owned jar.

The boot side reaches the game side by *string* (`Class.forName`, `getMethod`, ASM owner names).
`KernelRuntimeClasses` is the registry of every such string, and `KernelRuntimeClasses.verify(loader)` loads the
whole seam through the finished pipeline at boot, so a boot jar built without its game half, or a renamed
game-side method, fails at the top of the log rather than in the middle of a mod's construction.
One game-side class, `net.forbric.kernel.runtime.KernelHudLayer`, is still emitted at runtime with an ASM
`ClassWriter` (by `KernelHudBridge`); everything else on the game side is compiled.

### 2.2 `ForbricClassLoader` and the delegation table

`classloading.ForbricClassLoader` is a flat, JPMS-free `URLClassLoader`. `loadClass` consults
`DelegationPolicy`, in order:

- **ALWAYS_PARENT** — the JDK, ASM (`org.objectweb.asm.`), Mixin (`org.spongepowered.asm.`), log4j/slf4j,
  NightConfig (a carrier bundles an old unshaded copy that would otherwise win child-first),
  `net.fabricmc.api.`, `net.fabricmc.loader.api.`, the Fabric Loader internals in `FabricLoaderInternals` (by exact
  name), `net.forbric.api.`, and the kernel's boot packages (`boot`, `classloading`, `transform`, `mixin`, `access`,
  `mapping`, `metadata`, `discovery`, `fabric`, `util`, `interop`). Exactly one copy per JVM.
- **ALWAYS_GAME** — `net.minecraft.`, `com.mojang.blaze3d.`, `net.minecraftforge.`, `net.neoforged.`,
  `net.fabricmc.fabric.`, `net.forbric.kernel.runtime.`, `com.llamalad7.mixinextras.` and Mixin's synthetic
  package. Defined here or not at all.
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
| `patched-mc-merged-26.2.jar` | vanilla 26.2 + MinecraftForge-patched 26.2 + NeoForge-patched 26.2, byte-merged by `net.forbric.tools.MergedBaseBuilder`. NeoForge's classes are the base and Forge's are spliced in; the committed report `forbric-loader/run/merged-base/merge-conflicts.txt` reads `forge=193 neo=10163 MERGED=612` classes and `CONFLICTS: methods=1000 fields=8 STRUCTURAL(superclass/field)=15` |
| `neoforge-runtime.jar` | NeoForge's `-universal` jar plus the libraries its userdev config declares, merged into one jar (`NeoForgeRuntimeBuilder`) |
| `forge-runtime-interop.jar` | MinecraftForge's `-universal` jar plus its runtime libraries (`ForgeRuntimeBuilder`), then patched by `net.forbric.tools.RuntimeInteropPatcher` for interfaces the merge widened on NeoForge's behalf that Forge's own compiled implementations no longer satisfy. Staged under the coordinate `net.forbric:forge-runtime` |

The carriers are *passive*: their classes are defined and their event buses used, but their loaders'
discovery, sorting and lifecycle never run. The kernel supplies only the identity their code queries
(`PassiveSeeder`, §3.2).

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
11. **Transform chain** — 91 `chain.register(…)` call sites (§6).
12. `loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, ctx))`; the context class loader
    becomes the game loader;
    `KernelLifecycle`, `KernelHudBridge`, `LootTableEventDispatch` are bound; `KernelLoadReport` and
    `CrashAttribution` get the run directory (and their shutdown hooks).
13. **Loader identity, before Mixin** — `PassiveSeeder.seedNeoForgePaths`, `seedNeoForgeLoader`,
    `seedForgeFmlLoader`, `publishForgeLoadingList`. This must precede the first class that passes through the
    Mixin transformer, because Mixin constructs every guest `IMixinConfigPlugin` then, and plugins read
    `FMLPaths`/`FMLLoader` in their `<clinit>`. MinecraftForge's `FMLEnvironment` caches `dist` into
    `static final` fields in a one-shot `<clinit>`; whoever touches it first decides it forever.
14. **Mixin** — Fabric configs first, Forge-family configs appended (§7.1); `MixinConfigOwners.publish` before
    registration; `KernelMixinBootstrap.init`.
15. `PassiveSeeder.reportDependencies()` — after Mixin, because half of what it reports (mixins meant for another
    mod that did not attach) is recorded while Mixin parses configs.
16. `KernelRuntimeClasses.verify(loader)` — the kernel's own game side, through the finished pipeline.
17. `PassiveSeeder.seedAll` — NeoForge `FMLLoader`, `ModList`, paths; MinecraftForge identity (idempotent).
18. Audit reports (among them `MixinOverlapLint`, §7.6), `KernelLoadReport.writeEvidence()`, then
    `CompatibilityDecision.requireContinuation(isClient)` — the pre-game decision point (§12.4).
19. `KernelFabricEcosystem.runPreLaunch()` — Fabric `preLaunch` entrypoints, after Mixin, before any game class.
20. Load the entry class (`net.minecraft.server.dedicated.DedicatedServer` / `…client.gui.screens.TitleScreen`
    is the census landmark; the invoked class is the game's `Main`). If `LifecycleHookInjector` did not find its
    trigger, **the kernel refuses to boot** (`missedRequiredExcision()`).
21. `Main.main(gameArgs)` — vanilla boot, with the genuine loader trigger redirected.

### 3.3 The redirected trigger

NeoForge won the merge of both entry points. `transform.LifecycleHookInjector` retargets (owner + name, same
descriptor) one `invokestatic` in each:

| Side | Genuine call in the merged base | Now calls |
| --- | --- | --- |
| server | `net.neoforged.neoforge.server.loading.ServerModLoader.load(Z)V` in `net.minecraft.server.Main.main`, after `Bootstrap.bootStrap()` | `KernelLifecycle.onServerModLoading(boolean)`, followed by Fabric's `Hooks.startServer(null, null)` marker (`-Dforbric.fabricHooks=off` omits it) |
| client | `net.neoforged.neoforge.client.loading.ClientModLoader.begin()V` in `net.minecraft.client.main.Main.main`, after `Bootstrap.validate()`, before `new Minecraft` | `KernelLifecycle.onClientModLoading()` |

The client's later calls into both families' `ClientModLoader` (`finish`, `completeModLoading`) are stubbed by
`MethodBodyNeuter`; `setupModResourcePacks` is redirected to `KernelLifecycle.onClientResourcePacks` instead
(§9.2).

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
  5. NeoForge `GameData.vanillaSnapshot`, then **unfreeze**; post `NewRegistryEvent`;
  6. fire `RegisterEvent` per registry on every bus, in NeoForge's registration order; then the MinecraftForge
     baseline (`KernelForgeBaseline.register`);
  7. open `minecraft:root` and run Fabric `main` entrypoints (on the client only when
     `-Dforbric.fabricMainInConstructor=off`; see §3.5);
  8. attribute events, spawn placements, `BlockEntityTypeAddBlocksEvent`, modded game-rule categories, NeoForge's
     tooltip appenders;
  9. `closeRegistrationWindow` (in a `finally`): link block→item, **freeze**, rebuild NeoForge's blockstate→id map,
     re-sort NeoForge's creative tabs.
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
  Fabric's registry freeze point: with fabric-registry-sync installed, a Fabric mod's `@Inject` at the HEAD or TAIL of
  `BuiltInRegistries.freeze()`, or just before or after `bootStrap()`'s call of it, runs there
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
  `viafabric-mc26-2`. Only what a Fabric mod declares in its `jars` is judged. A NeoForge/MinecraftForge mod's nested
  jar is not, even if all it carries is a `fabric.mod.json`, because Fabric Loader never opens a jar without one. A
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
- The mod-facing API is vendored under its own names: `src/main/java/net/fabricmc/api` (7 files) and
  `src/main/java/net/fabricmc/loader` (30 files: `loader.api.*` plus the narrow internals mods actually link against
  — `FabricLoaderImpl`, `ModContainerImpl`, `EntrypointStorage`, `Hooks`, `FabricLauncher`/`FabricLauncherBase`,
  `DefaultLanguageAdapter`, `StringUtil`, legacy `net.fabricmc.loader.FabricLoader`). `FabricLoaderInternals`
  pins those internals to the parent by exact name; `-Dforbric.fabricImpl=off` withholds them. The API level
  reported is `KernelFabricEcosystem.FABRIC_LOADER_API_LEVEL = "0.19.3"`.
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
  runs them.

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
  `CapabilityUseAudit` lists the jars that use MinecraftForge capabilities and marks them DEGRADED if dispatch is off
  or the composition did not land on every root type.
- **Enum extensions** — `ForgeEnumExtensionInjector` drives MinecraftForge's own processor (unconditional; it
  declines everything unless Forge's mod list holds more than two mods).
- **Configs** — `KernelForgeConfigLoad` opens genuine MinecraftForge configs one at a time, keeping Forge's reader,
  events, save and file watcher; the watchers are stopped on exit by `interop.ClientShutdown`
  (`ExitHookInjector`: `Minecraft.close` on the client, `DedicatedServer.onServerExit` on the server).

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
- **Item/fluid/energy transfer** — `KernelTransferInterop` + `runtime/transfer/` bridge Fabric's transfer API,
  NeoForge's `ResourceHandler` and MinecraftForge capabilities, and Team Reborn Energy when installed
  (`-Dforbric.transferBridge=off`, `-Dforbric.hopperFabricStorage=off`). Active only when the relevant APIs are
  present, checked by resource.

## 6. The transform pipeline

`transform.TransformPhase` fixes the order: `RAW_PATCH`, `DEOBF_REMAP`, `ENV_STRIP`, `ACCESS`, `COREMOD`,
`FABRIC_BUILTIN`, `MIXIN`. `TransformChain` runs the chain phases (`RAW_PATCH` … `FABRIC_BUILTIN`); within a phase,
`predepends` topologically, then `sortIndex`, then registration order. `MIXIN` is terminal and cannot be registered
into the chain. On 26.2 nothing is registered in `RAW_PATCH` or `DEOBF_REMAP`.

What `KernelBoot` registers (91 call sites; some conditional):

| Phase | Registered |
| --- | --- |
| `ENV_STRIP` | `EnvironmentStripTransformer` |
| `ACCESS` | `ClassTweakerTransformer` (Fabric), `access.AccessTransformer` built from every mod jar's **and both carriers'** `META-INF/accesstransformer*.cfg` — the carriers' ATs matter because where the merge kept the other family's body it kept that body's access (`MenuScreens.register` came out private) |
| `COREMOD` | 86 registrations: the loader-probe rewriter, the Mixin-weaver slot (`FmlContextLoaderRewriter`, `ModuleClassLoaderInitInjector`), `GuestMixinPluginGuard`, the lifecycle redirect, `ForbricMergedBaseCompatTransformer`, the capability composition, and most of the 70 `*Injector` classes in `transform/`, each repairing one named seam the merge broke or landing one bridge (§8) |
| `FABRIC_BUILTIN` | `RestoredAccessTransformer` (re-applies access to members COREMOD restored), `MergedBaseFrameRecomputer` (recomputes a guest class's stack-map frames when they name a superclass the merge removed) |

The post-Mixin stage (`KernelMixinBootstrap`) is a fixed composition:

```
Mixin (via MixinWeaverSlot) → NativeCoremodParity → PostMixinFixups → InterfaceDefaultConflictRepair
                           → ForgeTransferShapeAudit.certify
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
- **UI** — `ModsButtonRedirector` (both families' pause-menu lambdas open `KernelModListScreen`),
  `HudElementBridgeInjector`, `CreativePagerBridgeInjector`, `EarlyKeyMappingRegistrationInjector`,
  `CreativeSearchTreesInjector`. The last is a producer/consumer split: the merged `SessionSearchTrees` kept
  MinecraftForge's bodies for vanilla's `updateCreativeTooltips(Provider, List)` / `updateCreativeTags(List)` (and
  for `getSearchTree`), which keep the trees in a private map, while the creative screen is NeoForge's and reads
  `CreativeModeTabSearchRegistry`. The screen only rebuilds its own trees when `CreativeModeTabs.tryRebuildTabContents`
  reports a change, so a mod that rebuilds the tabs itself and refreshes the search the vanilla way (TCDCommons, on
  every join) left every creative search empty. The three bodies now call `KernelCreativeSearch`, which goes
  through NeoForge's keyed methods for every tab with a search bar; `-Dforbric.creativeSearchTrees=off`.
  `-Dforbric.clientSmokeCreativeSearch=<tick>[,query…]` types into the real creative screen and logs the grid.
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
and plugins constructed — on the first class through the transformer, which is `KernelRuntimeClasses.verify`.

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
3. **Per-mixin drops** — the union of `KernelGuestMixinAdapter.unfitMixins` (below), the hand list
   `MergedBaseMixinCompat.SUPPRESSED_MIXINS`, and `-Dforbric.suppressMixins=config:Mixin,…`, minus
   `-Dforbric.keepMixins`. Dropping a mixin also drops every mixin that depends on an interface it contributed.

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
`MergedBaseUncalledMethods`, `-Dforbric.mixinFit.liveness=off`) unless an installed mod calls it; and a name-only
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
and no place in the `PARTIAL` count. `MixinFitReport` runs the same judgement offline:
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
piece, and `-Dforbric.mixinRetarget.renameCensus.uncalled=off` keeps every injector out of the `UNCALLED` body), and per-surface Fabric adapters
(`FabricBlockBreakMixinAdapter`, `FabricEntityMixinAnchors`, `FabricClientMixinAnchors` — which also stands any Fabric
`@Inject` just before or after `Gui.extractRenderState`'s screen draw at NeoForge's `ClientHooks.extractScreen`, where the
merged body draws the screen; LiquidBounce draws its whole browser menu there — `FabricEnchantmentMixinAdapter`,
`FabricMiningMixinAdapter`, `FabricSoundMixinAdapter`, `FabricServerLanguageMixinAdapter`), and `ReplacedCallRedirects`: a
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
contract is known (never a `@ModifyVariable`), and only where every `INVOKE`/`FIELD` point is held as often by both
bodies. NeoForge gave `ModelBlockRenderer.shouldRenderFace` the block's own position and declared it before vanilla's,
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

### 7.5 Attribution

`MixinConfigOwners` maps each config to its mod before registration, so Mixin's own failures name the mod (and
`-Dforbric.mixinModIdDecoration` puts the mod id into generated handler names). `KernelMixinErrorHandler` puts
prepare/apply failures on the mod's row without changing Mixin's decision. `FinalMixinApplications` observes each
defined class after all stages — zero references to a handler prove it did not attach. `SupersededMixins` keeps a
failure off the mod's row when a named kernel repair does *everything* that mixin did; `PluginDeclinedMixins` when
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
(§3.2 step 18) it reads each config as `ForbricMixinService` served it to Mixin, after the kernel's drops, and
records one `SUSPECTED` finding per mod and conflict, id `mixin-overlap:<owner>.<name><desc>[@<at>]`, the detail
naming the other mod; it logs counts and elapsed ms (`-Dforbric.mixinOverlapLint=off` skips it). When a crash
stack passes through a method with a recorded conflict, `CrashAttribution` names both mods. Offline:
`MixinOverlapLint <merged-base.jar> <mods-dir> [--json out]` (jars found recursively).

## 8. Event bridges

On the merged base the two Forge families' hooks competed for the same call sites and one won; the loser's hook
is dead code, so that family's listeners sit on a bus nobody posts to. A MinecraftForge mod needs an instance of
exactly `net.minecraftforge.…Event`, so re-emission is intrinsic.

- **Inventory** — `net.forbric.api.GameEventBridge` enumerates 96 bridges, each with the event, its install pass
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
  (`LootTableEventBridgeInjector` + `LootTableEventDispatch` for `LootTableEvents`, `KernelHudBridge` for
  `HudElementRegistry`, `FabricFuelValuesInjector`, tooltip and block-break adapters).
- **Audits** — `HookCallSiteCensus` (which hooks of `ForgeEventFactory`/`ForgeEventFactoryClient`/NeoForge
  `ClientHooks` the merged base still calls), `DeadEventAudit` + `ForgeBusSubscriptions` (who listens to a dead
  event, including subscriptions an annotation scan cannot see), `DeadHookWorklist` (the join, ordered by how many
  installed jars notice), `EventChainAudit` (every cross-bus post, checked by one rule set; gate-m41).

## 9. Datapacks, resources and data

### 9.1 Server data

A genuine loader turns every mod jar into a pack by walking `ModList`'s mod files. The kernel publishes mods into
`ModList` with `modFiles` empty, so that walk finds nothing. `DataPackHookInjector` →
`KernelLifecycle.onServerDataPacks` → `KernelDataPacks` / `runtime.KernelDataPackSource` serve each Forge-family
jar's `data/` — and both carriers' (`c:` convention tags, `neoforge:` damage types and data maps exist only there).
Metadata is read through NeoForge's `ResourcePackLoader.readWithOptionalMeta`, keeping root-pack overlays. Where
the carriers ship the same file, tags are additive and NeoForge wins last-wins files. Fabric mods' data is served
by fabric-api's own resource loader as on Fabric. `KernelPackFinders` lets a MinecraftForge mod add its own pack
finder (`-Dforbric.modDataPacks=off` disables the Forge-family packs).

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
  `runtime.KernelFabricConditions` (`fabric:load_conditions`; fabric-api's own two mixins cannot fit),
  `KernelNeoConditions`, `KernelForgeConditions` — each keeps one dialect from failing another's files.
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
| `ForeignType` | 58 rows mapping one Forge-family concept to its MinecraftForge and NeoForge class names — names only; per-family divergence stays data |
| `DiscoveredMod`, `UnifiedDependency`, `VersionPredicate`, `ModIds` | the mod and dependency model |
| `ModPresence`, `ModCatalog` | "is X running", and the list a player sees with status `OK` / `DEGRADED` / `FAILED` |
| `GameEventBridge`, `EventBridges` | the bridge inventory and its verification |
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
the real child with AWT disabled — what gates assert on). The child times out after 10 minutes. The confirmation's
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

The decision is asked at the pre-game boundary (§3.2 step 18), again at the end of loading (§3.4 step 3b on the
server, `fireClientSetupLifecycle` on the client), and at `ServerStartedEvent` on a dedicated server (a required
failure during world loading halts the server normally). A refusal throws `CompatibilityDecision.LaunchStopped`;
`CompatibilityLaunchBoundary` turns it into exit code **78** and prints the report path; inside
`Minecraft.<init>` it leaves through `SilentInitException`, so it is not reported as a crash. Findings that appear after boot never fork Swing or exit the JVM: on the server they are
consumed at the completed-tick boundary (`LateServerCompatibility`), on the client on a render-thread tick by a
native Minecraft screen (`KernelCompatibilityPrompts`, `KernelCompatibilityScreen`). An installed profile passes no
JVM arguments, so players get `ask`; `run/launch-kernel-{client,server}.sh` default to `strict` (and the client
script to `-Dforbric.dependencyDialog=off`).

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
               └───────────────────────────────┘                    ├→ patched-mc-merged
neoforge userdev ─┬→ neoforge-runtime ──────────────────────────────┤
                  └→ NFRT → patched-mc-neoforge ────────────────────┘
vanilla 26.2.jar ───────────────────────────────────────────────────┘
forge-runtime ────────────────────────────────→ forge-runtime-interop   (what is staged)
```

- `ForgeRuntimeBuilder`, `PatchedMcBuilder` (Forge's `installertools`/`mergetool`/`binarypatcher` as child JVMs,
  Forge's `AccessTransformerEngine` in-process), `NeoForgeRuntimeBuilder`, `NfrtRunner` (NeoFormRuntime, result
  `gameJarNoRecomp` — binary patches, no decompiler, no `javac`).
- `MergedBaseTool` unpacks `forbric-merge-tools.jar` from the installer's resources and runs
  `net.forbric.tools.MergedBaseBuilder` (`-Xmx4g`), `RuntimeInteropPatcher`, then `MergedLinkChecker` against the
  packaged reviewed baseline. **An install fails unless the link check reports `new 0`.**
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

- **The merge tools.** `forbric-loader/src/tools/java/net/forbric/tools/` — `MergedBaseBuilder`,
  `MergedLinkChecker`, `RuntimeInteropPatcher`, plus `AdditiveMethodMerger`, `MergeabilityCensus`,
  `LostHookAttribution`, `EffectiveHookEvidence` — built by `:mergeToolsJar` into `forbric-merge-tools-0.1.0.jar`,
  which the installer ships and runs. The reviewed link baseline is
  `forbric-loader/src/test/resources/merge/link-check-baseline.txt`.
- **The developer pipeline.** `run/build-patched-forge.sh`, `assemble-minecraftforge-runtime.sh`,
  `assemble-neoforge-runtime.sh`, `build-merged-base.sh`, `check-merged-links.sh` produce the staged artifacts
  under `forbric-loader/run/{merged-base,forge-runtime,neoforge-runtime}/` that the kernel's game side compiles
  against and every gate runs on. `FORBRIC_OLD` (or `-Pforbric.stagedRoot`) points another worktree at them. The
  committed `run/merged-base/merge-conflicts.txt` is the merge's report.
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
│   ├── src/main/java/net/forbric/api/          the unified API (15 files)
│   ├── src/main/java/net/forbric/kernel/       boot (73), transform (99), mixin (50), fabric (12),
│   │                                           metadata (11), access (7), classloading (5), discovery (4),
│   │                                           interop (5), ui (5), util (5), mapping (3), soak (2)
│   ├── src/main/java/net/fabricmc/             vendored Fabric API surface (37 files)
│   ├── src/main/resources/                     Mixin service registrations; mixin census tables
│   ├── src/runtime/                            GAME side, net.forbric.kernel.runtime (+ soak/, transfer/)
│   ├── src/test/, src/transferTest/            unit tests; transfer-engine tests
│   ├── canary/                                 canary mods the gates build and load
│   └── run/                                    gate-m*.sh, launch-kernel-{client,server}.sh, lib.sh,
│                                               diff-oracle.sh, mixin-inventory.sh, merge-packs.sh,
│                                               build-*-canary.sh, compat/ (sweep and evidence tooling)
│
├── forbric-kernel-installer/      THE INSTALLER — src/main/java/net/forbric/installer/kernel/, packaging/
│
├── forbric-loader/                first generation; merge tools + artifact pipeline (§14)
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
compatibility pack or real-instance gate has run.

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
- **Unit tests.** Counted from source, not run: `src/test` has 2 624 `@Test` methods and 2 `@ParameterizedTest`
  methods (two cases each) in 438 `*Test.java` files; `src/transferTest` has 61 `@Test` methods in 4 files
  (annotations at line start, `grep` over tracked files). Many tests read the staged jars; gate-m0 fails on
  *any* skipped test, because a skip there means the tests did not look at the real base.
- **Gates.** 58 scripts, `forbric-kernel/run/gate-m*.sh`, each asserting on the real logs and files of a real
  instance, most with named negative controls (a `-D…=off` or input removal that must turn exactly the named
  checks red). `run/compat/gates-all.sh` discovers them by glob; `gates-parallel.py` overlaps them using each
  gate's `# GATE-PARALLEL: rundirs=… mem=…` line (55 of 58 carry one; a gate without it runs alone), giving each
  slot its own port block.

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
| m22, m23 | quitting survives a replaced kernel jar; elytra flight |
| m24, m24b, m30 | a failing mod, a mod whose metadata cannot be read, and a partly failing mod, attributed on every surface |
| m24c | a jar listed in `forbric-disabled.txt` is loaded by nobody and named in the load report; a server only logs the crash-suspects offer |
| m25, m31, m32 | both biome-modifier pipelines; zero-mod worldgen parity with vanilla; a save opens with a mod removed |
| m33, m39, m40, m52 | item/fluid/energy transfer across ecosystems; hoppers into Fabric storages |
| m34 | ≥ 7200 s occupied simulation soak with retention checks |
| m35–m38, m41–m51, m53 | per-surface behaviour: mixin outcome, entity callbacks, enchantments, event chain, coremod parity, block break and loot, interaction, everyday actions, stub rebind, damage/server/world events, load predicates, tooltips, widened `NEW` anchors |
| m54 | a NeoForge mod's play-phase packet reaches the server: Carry On, with fabric-api installed, carries a chest and a pig through real key and mouse input; the same run with the repair off must pick nothing up (third-party jars: `M54_CARRYON`, `M54_FABRIC_API`) |
| m55 | the creative inventory's search finds items after a Fabric mod refreshed the search trees the vanilla way, judged from the screen's own grid, with a switched-off negative control |

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
  The 136 tests that still skip all need third-party mod packs that are not in this repository, held to
  `ci-prepared.tsv` the same way. Job `development-tools` runs
  `tools/dev.py tool-test` and the packaged link gate on Windows, Linux and macOS. kernel-prepared then runs four
  real dedicated-server gates on the same files: m1, m36, m46 and m53, whose mods are canaries built from this
  repository. The other gates (client, third-party packs, soak) need the developer's Mac: `tools/nightly/` runs
  them there every night from launchd (02:30, the soak on Sundays), commits each night's summary to the branch
  `ci-results` and sets the commit status `nightly/dev-mac` on the tested commit.

## 17. System properties

Set with `-D` on the JVM. The installed profile sets none. There are about 250 distinct `forbric.*` names in
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
| `forbric.debug` | enable `ForbricLog.debug` lines |

**Arbitration and order**

| Property | Effect |
| --- | --- |
| `forbric.multiLoaderPreference` | per-jar ecosystem order, default `neoforge,minecraftforge,fabric` |
| `forbric.dupeIdPreference`, `forbric.nestedDupePreference` | cross-jar order for top-level / nested duplicates |
| `forbric.modOwner` | `id=loader,…` pins; also `<rundir>/forbric-mods.txt` |
| `forbric.crossJarArbitration` | `off`: two jars with one id both load |
| `forbric.nestedRequirements` | `off`: a nested Fabric mod whose `minecraft`/`java` range excludes this game loads anyway; `off:<id>,…`: only those ids do |
| `forbric.arbitrationMaxNodes` | selector work bound (default 100 000, capped at 1 000 000) |
| `forbric.modOrder` | `name`: file-name construction order |
| `forbric.fabricOrder` | `off`: Fabric mods follow the topological order instead of mod-id order |
| `forbric.fabricMainInConstructor` | `off`: client Fabric `main` entrypoints run in the pre-`Minecraft` window |
| `forbric.loaderProbes`, `forbric.crossEcosystemPresence` | `off`: platform probes / presence answer as a single loader would |

**Mixin**

| Property | Effect |
| --- | --- |
| `forbric.relaxGuestMixins` | `off`: guest configs are not relaxed |
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

**Diagnostics and test drivers**

| Property | Effect |
| --- | --- |
| `forbric.clientSmoke` (+ `clientSmokeWorld`, `clientSmokeReadyTicks`, `clientSmokeDisconnectTicks`, …) | unattended client run: enter a world, live, leave, exit |
| `forbric.eventChainAudit` | report file for the cross-bus audit |
| `forbric.definedClassEvidence` | directory for a content-addressed record of every defined class |
| `forbric.traceClassDefine` | csv of binary names; log a stack the first time each is defined |
| `forbric.tickSampler` | `off`: no server tick-time sampling |

**Selected repair switches** — `forbric.commonNetworkInterop`, `forbric.playPayloadFallThrough`, `forbric.chunkExecutorGuard`,
`forbric.forgeCapabilities`, `forbric.forgeWorldgen`, `forbric.transferBridge`, `forbric.hopperFabricStorage`,
`forbric.clientResourcePreload`, `forbric.earlyConfigs`, `forbric.fabricHooks`, `forbric.fabricImpl`,
`forbric.kernelBundledFirst`, `forbric.modDataPacks`, `forbric.modMenuStandIn`. `forbric.kernel.registryRedirect=true` enables an
experimental registry-wrapper redirect.

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

## 19. Current state and known boundaries

- **Minecraft 26.2 only**, Mojmap identity namespace. There is no remapping step: a jar compiled against another
  namespace is not translated (`kernel/mapping/` is carried over from the weld and is not on the boot path).
- **The merged base is NeoForge's game with MinecraftForge spliced in.** Where both patched a method, one body
  survived (1 000 method conflicts in the committed report); what the loser's mods lose is repaired case by case —
  transformers, adapters, bridges — and what is not repaired is reported by `DeadEventAudit`,
  `HookCallSiteCensus`, `FieldDriftAudit`, `AbiLinkAudit`, `CapabilityUseAudit`. Structural conflicts (two real
  superclasses for `Entity`) have no bytecode-level resolution; MinecraftForge capabilities are composed back in by
  transformer.
- **`PARTIAL` mixins apply by default** — half-application is kept, visible, in preference to dropping working
  hooks.
- **One class, one copy.** When two ecosystems' builds of a mod compete, one wins; the losing ecosystem sees a
  presence alias, not the mod's own platform glue.
- **Measured, not promised.** `MOD_TEST_FAILURES.md` records a per-mod test (each jar alone with its required
  dependencies, into a world, screenshot, exit) on three fresh random Modrinth sets against the current `main`
  code: 89.0 % loaded without failure lines on average (91.8 % reached the world; 79.1 % with nothing reported
  DEGRADED in the load report), against 80.5 % for release v0.2.0 on the same jars.
- **Versions.** `forbric-kernel/build.gradle` says `0.1.0-SNAPSHOT`; the installer is `0.3.1-beta2`. `net.forbric.api`
  is internal and changes without notice.

## 20. Further reading

- [`forbric-kernel/README.md`](forbric-kernel/README.md) — the kernel's own summary and gate notes
- [`forbric-kernel/run/compat/PROTOCOL.md`](forbric-kernel/run/compat/PROTOCOL.md) — the compatibility sweep procedure
- [`forbric-loader/README.md`](forbric-loader/README.md), [`forbric-loader/run/README.md`](forbric-loader/run/README.md) — the first generation and the artifact pipeline
- [`forbric-loader/CREDITS.md`](forbric-loader/CREDITS.md), [`forbric-loader/MAPPINGS.md`](forbric-loader/MAPPINGS.md) — the clean-room boundary and mapping position
- The class javadoc. Almost every class under `net.forbric.kernel` opens with the failure it exists for.

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.
