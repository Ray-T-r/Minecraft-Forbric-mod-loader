# Forbric — architecture and internals

English | [简体中文](introduction.zh-CN.md)

For mod and loader developers. This document is precise rather than gentle: it states what Forbric does, in the
order it does it, naming the real types and files. It describes the **`main` branch**, not a release; for what a
release contains and how a player installs it, read the [README](README.md).

> **Which code this describes.** The repository holds two generations. `forbric-kernel/` — the *sovereign
> kernel* — is what `main` ships and what `forbric-kernel-installer/` installs; it is the subject of this document.
> `forbric-loader/` is the first generation (the *weld*: real fabric-loader/Knot as host, with FML and
> FancyModLoader driven alongside it). It no longer runs in an installed instance and no longer builds anything the
> kernel runs on; it still holds test inputs — see [§14](#14-what-forbric-loader-is-still-for).
>
> **MinecraftForge is gone.** Forbric runs Fabric and NeoForge mods on NeoForge 26.2.0.93's own patched game. Until
> Stage 4 of dropping MinecraftForge it also ran traditional MinecraftForge mods, on a *merged base* that carried both
> Forge families' patches in one jar; that base, the tools that built it, the MinecraftForge carrier and every repair
> that existed only for MinecraftForge or only for the merge are deleted. A jar that can only load as a MinecraftForge
> mod is refused by name (§5.3). The last tree with MinecraftForge is the tag `tri-eco-26.2-final`; what Stage 4 removed,
> kept and measured, change by change, is in
> `forbric-kernel/run/compat/reports/2026-10-30-forge-removal-baseline/`. Some identifiers still carry the old names
> (`MergedBase…`, `ForbricMergedBaseCompatTransformer`, "Forge-family"); renaming them is a later step, and where this
> document names one it means the class as it is now.

Terminology:

| Term | Meaning here |
| --- | --- |
| **ecosystem** | Fabric, NeoForge — `net.forbric.api.Ecosystem.FABRIC` / `NEOFORGE`. A jar can also *declare* traditional MinecraftForge (`META-INF/mods.toml`); that is `LoaderKind.MINECRAFTFORGE`, a loader kind with no ecosystem, read only to refuse it (§5.3) |
| **Forge family** | the code's older word for the `mods.toml`-dialect side: NeoForge mods, plus the MinecraftForge manifests the refusal reads. Log lines keep the word ("N Forge-family mod(s)"); every mod it counts is a NeoForge mod |
| **base** | `patched-mc-neoforge-26.2.jar`: Minecraft 26.2 with NeoForge 26.2.0.93's binary patches, built by NeoFormRuntime exactly as NeoForge builds it (§2.3). Called "the merged base" in older text and in some identifiers: until Stage 4 the base was a merge of both Forge families' patched games |
| **carrier** | NeoForge's runtime jar (`neoforge-runtime.jar`), loaded as a passive ABI provider — its classes exist, its loader lifecycle never runs |
| **vanilla reference** | `vanilla-reference-26.2.jar`, a resources-only sidecar recording how vanilla compiled each class the base changed; the Fabric half of §7.4's native body (§2.3) |
| **boot side / game side** | code loaded by the system class loader vs. code defined by `ForbricClassLoader` |
| **guest** | anything belonging to a third-party mod (guest mixin, guest jar) |
| **native** | what a mod gets on its own loader: vanilla 26.2 under Fabric Loader for a Fabric mod, NeoForge's own patched 26.2 for a NeoForge mod. Every repair is judged against it (§16.1) |

---

## 1. The problem

Two loaders assume they own the process. Running their mods together breaks in five independent ways, and
every part of the kernel answers one of them.

1. **Launch ownership.** Fabric starts Knot; NeoForge starts its own launcher with a JPMS module layer. Two
   transforming class loaders means two definitions of every game class.
2. **One class, two shapes.** NeoForge patches `net.minecraft` classes with its hooks, and a JVM can hold one version
   of each class. The game is NeoForge's patched one, so a Fabric mod, written against vanilla, meets methods NeoForge
   reshaped. (Until Stage 4 the problem was larger: MinecraftForge and NeoForge both patched the same classes, and a
   merged base had to put both families' edits into one body.)
3. **Lifecycles and registration windows.** Each ecosystem has its own phases, its own bus, its own window in
   which registries are writable, and its own idea of when the freeze happens.
4. **Visibility.** Each loader keeps its own mod list. A mod asking its own loader "is Sodium installed?" or "which
   platform am I on?" gets an answer that is true for a single-loader instance and wrong here.
5. **Guest bytecode written for a different game.** A Fabric mixin was written against vanilla bytecode. On
   NeoForge's patched game, anchors have moved, methods have been split, fields re-typed, lambdas renumbered.

Namespace is *not* on this list for 26.2: the game ships Mojmap names, NeoForge 26.2 mods are Mojmap-compiled,
and so are Fabric's (`KernelMappingResolver`'s javadoc records a constant-pool scan of fabric-api and Jade finding
no intermediary symbols). The kernel runs identity mapping: `TransformContext(…, "named")`, and
`KernelMappingResolver` answers every lookup with its input.

Nor are mod APIs. Forbric does not re-implement the Fabric API or NeoForge's API: a mod calls the genuine Fabric
API mod it installed, and the genuine NeoForge classes from the carrier. What the kernel owns is the part a loader
owns — class loading, discovery, the lifecycle, the registration window, and Fabric Loader's own API (§5.1) — plus
the adaptations that let Fabric mods run on NeoForge's game. One of them reproduces behaviour instead of calling it:
NeoForge's coremod rewrites, which the kernel performs itself (§5.2).

## 2. Shape of the solution

One JVM, one transforming class loader, one lifecycle, one registry freeze. No genuine loader lifecycle ever
boots: there is no Knot, no ModLauncher, no FancyModLoader discovery, no module layer.

```
system class loader  (BOOT side)
 ├─ forbric-kernel.jar            net.forbric.kernel.{boot,classloading,discovery,fabric,transform,mixin,
 │                                 access,metadata,mapping,interop,ui,util,soak,linkcheck}, net.forbric.api,
 │                                 vendored net.fabricmc.api / net.fabricmc.loader surface
 └─ its dependencies               ASM, sponge-mixin, SAT4J, NightConfig, tiny-remapper, class-tweaker, mapping-io
     │  new ForbricClassLoader(owned, parent)
     ▼
ForbricClassLoader  (GAME side — the only loader that defines game/ecosystem classes)
 owned jars, in this order (KernelOwnedClasspath.compose):
   1. patched-mc-neoforge-26.2.jar         --gameJar
   2. neoforge-runtime.jar                  --runtimeJar   (the carrier)
   3. Minecraft's own libraries             --libraryPath  (owned: mods mixin into DataFixerUpper & co.)
   4. kernel-bundled: mixinextras-fabric.jar, forbric-kernel-runtime.jar   (extracted to .forbric-kernel/lib/)
   5. guest mod jars: NeoForge, then Fabric, nested jars included; newest version first within one mod id
 read, never owned: vanilla-reference-26.2.jar  --nativeReference  (resources only, §2.3)
```

### 2.1 Boot side vs game side

`forbric-kernel.jar` is parent-loaded and names no game type. Everything that references `net.minecraft.*`,
`net.neoforged.*` or `net.fabricmc.fabric.*` lives in the three game-side source sets —
`src/runtime/java` (package `net.forbric.kernel.runtime` and its `soak`/`transfer` subpackages, 92 files),
`src/modmenuPlugin/java` (the kernel's own Mod Menu entrypoint, `net.forbric.kernel.runtime.modmenu`, 1 file) and
`src/modmenuApi/java` (a stand-in of Mod Menu's API, `com.terraformersmc.modmenu`, 6 files) — is compiled against the
staged game artifacts, packaged as `forbric-kernel-runtime.jar` (the Mod Menu stand-in only as resources under
`META-INF/forbric/modmenu-api/`, never as classes, §11), nested at `META-INF/jars/` inside the boot jar, extracted at
launch by `KernelBundledJars` and loaded as an owned jar.

The boot side reaches the game side by *string* (`Class.forName`, `getMethod`, ASM owner names).
`KernelRuntimeClasses` is the registry of every such string, and `KernelRuntimeClasses.verify(loader)` loads the
whole seam through the finished pipeline at boot (after the Fabric `preLaunch` entrypoints, §3.2 step 20), so a boot
jar built without its game half, or a renamed game-side method, fails before the game starts rather than in the
middle of a mod's construction. Everything on the game side is compiled: the one class that used to be emitted at
runtime (`KernelHudLayer`, for the Fabric HUD bridge) was retired with that bridge.

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

None of them is in the repository or in any Forbric download: two embed Mojang's and NeoForge's code, and the third is
measured from Mojang's jar. They are built on the machine that runs them — by the installer for players (§13), by
`tools/dev.py prepare` (the installer's own pipeline, `DevPrepare`) for developers (§16).

| Artifact | What it is |
| --- | --- |
| `patched-mc-neoforge-26.2.jar` | the base: vanilla 26.2 with NeoForge 26.2.0.93's binary patches, produced by NeoFormRuntime (`NfrtRunner`, result `gameJarNoRecomp`: binary patches, no decompiler, no `javac`), exactly the game a native NeoForge instance runs. Staged as `net.forbric:patched-mc-neoforge:26.2-26.2.0.93` |
| `neoforge-runtime.jar` | the carrier: NeoForge's `-universal` jar plus the libraries its userdev config declares, merged into one jar (`NeoForgeRuntimeBuilder`), with fixed zip entry times so two builds are byte-identical |
| `vanilla-reference-26.2.jar` | resources only, no classes (`VanillaReference`): `META-INF/forbric/native-reference/FABRIC/index.tsv` lists every class of the vanilla jar by SHA-256, and a `.class.bin` copy of the vanilla bytes is kept for each class the base changed. It is how the kernel reads the body a Fabric mod was written against (`NativeGameReferences`, the native body of §7.4); it is never defined. It names the SHA-1 of the vanilla jar and base it was measured from, so a stale one is rebuilt. Passed with `--nativeReference` |

The carrier is *passive*: its classes are defined and its event buses used, but NeoForge's discovery, sorting and
lifecycle never run. The kernel supplies only the identity its code queries (`PassiveSeeder`, §3.2).

### 2.4 The edit splice (retired)

Until Stage 4 the base was `patched-mc-merged-26.2.jar`: vanilla, MinecraftForge's and NeoForge's patched games
byte-merged by `MergedBaseBuilder` in `forbric-loader`, with an *edit splice* that kept both families' edits in one
method body where it could prove that safe, and a *merge-loss ratchet* (`MergedBaseHookLossRatchetTest`) that held the
number of platform calls the merge lost to a ceiling. The base is now NeoForge's own patched game, so nothing is
merged, nothing is spliced and no hook is lost to a merge: the merge tools, the ratchet and its CI step are deleted.
Their description is in this document at the tag `tri-eco-26.2-final`.

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
`--libraryPath`, and `--nativeReference` (the vanilla reference, §2.3); everything else, and everything after `--`, is forwarded to the game's `Main.main`. The dedicated
server rejects `--gameDir`, so `KernelBoot` strips it on that side. The game version is read from the base jar's
`version.json` (fallback `26.2`).

### 3.2 `KernelBoot.launch`, in order

0. **Launch inputs** — `LaunchInputCheck.require(gameJars, runtimeJars)`, by content and before anything is read
   out of them: the base's `Block` must implement NeoForge's `IBlockExtension` (NeoForge's patched game), every
   `--runtimeJar` must carry NeoForge completely (loader SPI, `ModContainer`, `FMLLoader`, `FMLEnvironment`, its
   `neoforge.mods.toml`; a jar with only the manifest is reported as a NeoForge mod), and NeoForge must be carried by
   something the launch owns. A failure logs each problem and the fix (rerun the installer with *Built artifacts*
   empty) and stops with exit code `2`; `-Dforbric.launchInputCheck=off` only warns. When this kernel is launched
   over an install made by Forbric 0.3.x or earlier (or by a 0.4.0-dev build from before MinecraftForge was removed),
   that install is recognised first, by any of three plain
   signals — the base's `Block` implements a `net/minecraftforge/` interface, the base embeds
   `META-INF/forbric/native-reference/FORGE/index.tsv`, or a `--runtimeJar` is a complete MinecraftForge carrier — and
   refused with exit `2` and "run the new installer over the same game directory", also with the switch off. Then
   `NativeGameReferences.configure` decides where vanilla's bodies are read from (the `--nativeReference` sidecar) and
   says so in one line. Issue #13:
   empty "runtime" jars used to pass every later step with an empty answer and die in `KernelRuntimeClasses.verify`
   on stderr, leaving five INFO lines in `latest.log`. The check reads names, not every class; the class javadoc lists
   what it leaves to later steps.
1. **Cross-jar arbitration pre-scan** — `DuplicateModArbiter.arbitrate(mods/, envType)` inventories every root and
   nested candidate and fixes one selection before either ecosystem's discovery runs (§4.3).
2. **Carrier version** — `EcosystemVersions.record(runtimeJars)`, so a mod whose `versionRange` the carrier
   cannot satisfy is reported as it is discovered.
3. **NeoForge discovery** (`discoverForgeFamilyModJars`) — every `mods/*.jar` that loads as NeoForge (a jar whose
   only usable half is MinecraftForge's is refused here, §5.3), then the JarJar children
   (`META-INF/jarjar/`) the arbitration plan selected, taken from the plan's content-addressed extraction under
   `.forbric-kernel/candidates/` (the legacy extractor, writing `.forbric-kernel/jarjar/`, runs only when
   arbitration is off).
4. **Presence** — `ModPresence.publishForgeFamily(…)` before the Fabric side is built, because the Fabric side
   reads it back.
5. **Fabric discovery** — `KernelFabricEcosystem.scan`, then a second arbitration pass over the union of nested
   jars (`DuplicateModArbiter.arbitrateNested`); losers are dropped from both lists.
6. **Mixin config declarations** — NeoForge configs from mod jars, nested mod jars *and the carrier*
   (NeoForge's own `neoforge.mixins.json`).
7. **Fabric ecosystem build** — `KernelFabricEcosystem.build` creates the `FabricLoader` view (§5.1).
8. **Owned classpath** — `KernelOwnedClasspath.compose` (order in §2).
9. **Static audits** over every guest jar, before anything is loaded: `PortingLayerAudit` (Fabric jars shipping
   their own `net.neoforged.*`/`net.minecraftforge.*`), `FabricApiModuleLossAudit`, `FieldDriftAudit`,
   `MergedBaseUncalledMethods.scanGuests`, `AbiLinkAudit` (Forge-family classes named by a jar that exist in no
   carrier, base or installed jar).
10. **Class loader** — `new ForbricClassLoader(owned, bootLoader)`, rescue jars, jar families,
    `LoaderProbePolicy.bindGuestLoader`, `KernelFabricLauncher.install` (a mod's `addToClassPath` lands here).
11. **Transform chain** — 94 `chain.register(…)` call sites (§6).
12. `loader.setTransformer((name, bytes) -> chain.applyBeforeMixin(name, bytes, ctx))`; the context class loader
    becomes the game loader;
    `KernelLifecycle` and `LootTableEventDispatch` are bound; `KernelLoadReport` and
    `CrashAttribution` get the run directory (and their shutdown hooks).
13. **Loader identity, before Mixin** — `PassiveSeeder.seedNeoForgePaths`, `seedNeoForgeLoader`. This must precede
    the first class that passes through the Mixin transformer, because Mixin constructs every guest
    `IMixinConfigPlugin` then, and plugins read `FMLPaths`/`FMLLoader` in their `<clinit>`. Then
    `KernelFabricEcosystem.constructLanguageAdapters()`: every language adapter a Fabric mod declares is built
    here, as Fabric Loader's `freeze()` builds them before `FabricMixinBootstrap.init` (§5.1).
14. **Mixin** — Fabric configs first, NeoForge configs appended (§7.1); `MixinConfigOwners.publish` before
    registration; `KernelMixinBootstrap.init`.
15. `PassiveSeeder.reportDependencies()` — after Mixin, because half of what it reports (mixins meant for another
    mod that did not attach) is recorded while Mixin parses configs.
16. `PassiveSeeder.seedAll` — NeoForge `FMLLoader`, `ModList`, paths (idempotent).
17. Audit reports (among them `MixinOverlapLint`, §7.6), `KernelLoadReport.writeEvidence()`, then
    `CompatibilityDecision.requireContinuation(isClient)` — the pre-game decision point (§12.4).
18. `NeoGraphicsBootstrappers.run` — NeoForge's `GraphicsBootstrapper` services (a provider listed in
    `META-INF/services/…earlywindow.GraphicsBootstrapper` of a top-level `mods/` jar loaded as NeoForge, the SPI's own
    rule), run where genuine FML runs them: first, before the game makes its window. Sodium's NeoForge build keeps
    its pre-launch checks, graphics-adapter probe and driver workarounds there. A provider that throws costs its jar
    (ERROR, every mod of that jar FAILED), not the launch; `-Dforbric.graphicsBootstrappers=off`. NeoForge's
    `FMLConfig` says `earlyWindowControl=false`, as a genuine NeoForge with the early window off would: Forbric opens
    no early window at startup. When mod loading stops before the game has a window, `NeoFatalErrorWindow` opens the
    early window NeoForge would have had, only then, so NeoForge's own error screen shows the failure as natively
    (`-Dforbric.fatalErrorWindow=off` leaves NeoForge's message-box fallback; §3.4).
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

The game's two entry points call NeoForge's loader. `transform.LifecycleHookInjector` retargets (owner + name, same
descriptor) one `invokestatic` in each:

| Side | Genuine call in the base | Now calls |
| --- | --- | --- |
| server | `net.neoforged.neoforge.server.loading.ServerModLoader.load(Z)V` in `net.minecraft.server.Main.main`, after `Bootstrap.bootStrap()` | `KernelLifecycle.onServerModLoading(boolean)`, followed by Fabric's `Hooks.startServer(null, null)` marker (`-Dforbric.fabricHooks=off` omits it) |
| client | `net.neoforged.neoforge.client.loading.ClientModLoader.begin()V` in `net.minecraft.client.main.Main.main`, after `Bootstrap.validate()`, before `new Minecraft` | `KernelLifecycle.onClientModLoading()` |

The client's later calls into NeoForge's `ClientModLoader` (`finish`, `completeModLoading`) are stubbed by
`MethodBodyNeuter`; `setupModResourcePacks` is redirected to `KernelLifecycle.onClientResourcePacks` instead
(§9.2). (The merged base also kept MinecraftForge's own loader calls, which had to be emptied one by one; NeoForge's
base has none.)

On the client the same injector also makes `Main.logEarlyException` call `KernelLifecycle.onEarlyStartupFailure`
first. That is vanilla's handler for the first three steps of `Main.main` (detecting the version, building and
running the argument parser): it prints to stderr and `main` exits (249, 252, 251) without throwing, so without the
hook the error that ended the game never reached `latest.log`.

### 3.4 The native registration window — `KernelLifecycle.driveNativeRegistration`

Both sides run the same steps (comments in the source number them):

- **0c** on the client, load the carrier's built-in translations (`CarrierLanguages`).
- **1** register NeoForge's baseline registries (`PassiveSeeder.seedNeoForgeRegistries`); apply NeoForge's own
  registry modifications (sync flags, callbacks).
- **2** `registerNeoForgeContent` — the window itself:
  1. construct `NeoForgeMod` on a kernel-made bus and container (`KernelModContainerFactory`);
  2. construct every NeoForge `@Mod` (`KernelModLoader.constructMods`, §5.2) on NeoForge's own `modloading-worker`
     pool, each mod once the mods it waits for are done (`NeoParallelDispatch`, as `ModLoader.dispatchParallelTask`
     runs it natively);
  3. register guest `@EventBusSubscriber` classes (`KernelEventSubscribers.registerAll`);
  4. post `FMLConstructModEvent`;
  5. **unfreeze** (`openRegistrationWindow`); post `NewRegistryEvent`;
  6. fire `RegisterEvent` per registry through NeoForge's own `GameData.postRegisterEvents`, in NeoForge's
     registration order; the Fabric `main` entrypoints run where its dispatch loop ends, with `minecraft:root` open
     (on the client only when `-Dforbric.fabricMainInConstructor=off`; see §3.5), so the tail steps (attributes, spawn
     placements and the rest NeoForge posts after the loop) see what Fabric mods registered;
  7. `closeRegistrationWindow` (in a `finally`, unless loading stopped): link any block item still unlinked
     (normally none; §9.4), **freeze**, redo the block-state caches and the mods' whole-registry passes for late
     registrations, re-sort NeoForge's creative tabs.

  A mod whose constructor, subscriber class or listener throws in any of these steps stops the boot as native
  NeoForge 26.2.0.93 stops it: `RegistrationStop` builds the `ModLoadingException` with NeoForge's own
  `ModLoader.waitForTask`, it leaves the redirected `ServerModLoader.load` / `ClientModLoader.begin` call, and the game's
  own handlers write the crash report and exit (status 1 on a server; on a client, which has no game window yet,
  NeoForge's fatal-error window). The same holds for every later phase the kernel drives — setup, IMC, load complete,
  the network locks, the Fabric `main`/`client`/`server` entrypoints (`KernelNeoClientStop` is the client's end of a
  stop inside `Minecraft.<init>`). Two things differ from native there: once the client has its game window, NeoForge
  falls back to a message box where native draws the error in the game window, and a Fabric stage stop on a dedicated
  server exits `1` where native Fabric exits `0` (§19). A Fabric stage stop writes no NeoForge crash file: a server's
  Main logs it, and a client ends with Minecraft's crash report (§12.4). Except NeoForge's "Config loading" listeners, a Fabric `preLaunch` entrypoint (§5) and a throwing Fabric
  injector at the moved freeze point (§3.5), which are still contained (§19), nothing is contained, and no world is
  loaded after a stop.
- **2b–2c2** on the client construct `ClientNeoForgeMod`; publish the NeoForge baseline in `ModList`; load
  STARTUP/COMMON (and on the client CLIENT) configs; wire the carrier's own `@EventBusSubscriber` classes.
- **3a** declare datapack registries (`DataPackRegistryEvent.NewRegistry`, Fabric dynamic registries mirrored both
  ways) — deferred on the client until after the Fabric entrypoints (`DatapackRegistryDeclaration`).
- **3a2** start the game buses (`startGameBuses`) — before the setup phases, because `IEventBus.post` on a bus
  that has not started returns silently.
- **3b** (server) post the setup lifecycle to the NeoForge mods (`fireModSetupLifecycle`), each phase on the worker
  pool as in step 2: common setup,
  dedicated-server setup, NeoForge's `RegistrationEvents.init()` one step at a time (`RegistrationEventSteps` —
  it posts `RegisterCapabilitiesEvent` and `RegisterDataMapTypesEvent`), IMC enqueue/process, load complete; deferred
  work runs between phases on NeoForge's `modloading-sync-worker` (`NeoDeferredWork`; failures read back by
  `DeferredWorkFailures`). Then `load-report.txt` is written and `CompatibilityDecision.requireContinuation` is asked
  again — the end-of-loading decision point. On the client every one of these phases, common setup included, is
  deferred to `onNeoClientSetup` (§3.5), because `Minecraft.getInstance()` is still null here.
- **3b2** open configs registered late (from construction or setup).
- **3c** (server) close NeoForge's payload registration phase (`setupNeoForgeNetwork`). It must come after setup:
  mods register payloads from `FMLCommonSetupEvent`.

There is one registration window and one freeze. The "Tags not bound" wall the weld hit (two ecosystems
refreezing in turn) cannot arise. Unlike native, a mod whose required dependency failed still runs its later phases
(natively the boot has already stopped by then).

### 3.5 Client-only hooks inside `Minecraft.<init>`

Fabric and NeoForge need opposite states in the constructor, so the kernel has two anchors:

- `ClientEntrypointHookInjector` → `KernelLifecycle.onClientEntrypoints()`, before `Options` exists: reopen the
  registries, run Fabric `main` then `client` entrypoints (Fabric's `Hooks.startClient` order), re-close and
  re-freeze, open late CLIENT configs, then declare datapack registries. A Fabric `main` or `client` entrypoint that
  throws stops the boot (`RegistrationStop.fabric`), as a NeoForge mod's loading failure does (§3.4). Around that freeze is
  Fabric's registry freeze point: where a Fabric mixin defers the freeze (recognised by the redirect of
  `Bootstrap`'s `bootStrap()` call that defers it, whoever declares it — fabric-registry-sync does), a Fabric mod's
  `@Inject` at the HEAD or TAIL of `BuiltInRegistries.freeze()`, or just before or after `bootStrap()`'s call of it, runs there
  (`FabricFreezeHookMixinAdapter`), where native Fabric freezes — after the entrypoints, with `Minecraft.getInstance()`
  set. LiquidBounce builds its creative tabs from such an injector; left in `Bootstrap` it found no client and died.
  An injector that throws there is still contained: it is logged, the later ones on that hook are skipped, and the
  boot goes on (§19).
- `NeoClientSetupHookInjector` at the base's own `ClientModLoader.finish()` call →
  `KernelLifecycle.onNeoClientSetup()`, after `options` is assigned: `fireClientSetupLifecycle`: common setup, client
  setup, `RegistrationEvents.init()`, IMC, load complete — with the registries frozen, as on genuine NeoForge, and
  before the first resource reload, as natively (the merged base preloaded the client resources here for
  MinecraftForge mods; that preload is retired) — then `load-report.txt` and the end-of-loading decision
  (`requireClientContinuation`), then late configs, then close the payload registration phase. A stop in any of these
  is handed to `KernelNeoClientStop`, which does what `ClientModLoader.finish`'s own handler does natively, except
  that what shows is NeoForge's message-box fallback, where native draws the error in the game window (§19).

## 4. Discovery and arbitration

### 4.1 Reading manifests

- `discovery.ForbricModDiscoverer` classifies a jar by descriptor — `fabric.mod.json`, `META-INF/mods.toml`,
  `META-INF/neoforge.mods.toml` — and reports *every* manifest a jar carries. Which one loads is policy (§4.2); a
  MinecraftForge manifest is read only so the refusal can judge the jar (§5.3).
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
  what a Fabric mod declares in its `jars` is judged. A NeoForge mod's nested jar is not, even if all
  it carries is a `fabric.mod.json`, because Fabric Loader never opens a jar without one. A
  child the parent's `META-INF/jarjar/metadata.json` declares is FML's, which loads it as the parent's library; a jar
  that only sits in `META-INF/jars/` or `META-INF/jarjar/` with no entry in that file is loaded by no native loader,
  and the kernel keeps it because its NeoForge walk has always taken both directories. A jar reached both ways is
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
  does not read hard-require: the NeoForge mods, and the Fabric manifests of the jars their JarJar carries (no
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
  NeoForge mod, a Fabric mod a JarJar declaration or the NeoForge walk brought in, an exempted one, a library
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
  `ModFileScanData` in NeoForge's shape (enum members as `EnumHolder`) because JEI, Jade, Sophisticated Core and
  Sodium find their plugins through `ModList.getAllScanData()`. The NeoForge mods one jar declares share that jar's one
  `ModFile`, as in native FML, so the index lists the jar once (`KernelModFile.sharedFor`; `-Dforbric.modFilePerJar=off`
  gives each mod a file of its own, as before Stage 4).
- `net.forbric.api.DiscoveredMod` is the one mod model.

The `--scan` mode (`boot.Main --scan --mods <dir> --report out.json`) runs discovery alone and writes
deterministic JSON; `run/diff-oracle.sh` cross-checks it against an independent Python reader of the same
manifests.

### 4.2 One jar, several manifests — `MultiLoaderArbiter`

A universal jar ships a manifest and glue class per loader. Unarbitrated, both ecosystems initialise it.
`MultiLoaderArbiter.ownerOf(jar)` picks one by preference — default **NeoForge, Fabric**
(`-Dforbric.multiLoaderPreference=…`; `minecraftforge` there is warned about and ignored). NeoForge first because its
baseline is always present on the base, while a jar's Fabric side usually needs a fabric-api the player may not have.
A jar that carries a leftover manifest without its implementation is not given to that loader. A MinecraftForge half
is never a candidate: a jar with a usable Fabric or NeoForge half is chosen among those alone (a Forge+Fabric jar now
loads as Fabric and says so), and a jar with nothing else is refused (`JarClaim.Refused`, §5.3). The jar stays on the
classpath; only its identity is decided.

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
  Nested duplicates: `-Dforbric.nestedDupePreference`, default NeoForge, Fabric — chosen because a
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
  NeoForge's `ModList.isLoaded`; the Fabric side registers every NeoForge mod as a presence-only container (identity
  only — no entrypoints, mixins or assets), so `FabricLoader.isModLoaded` answers; and NeoForge's lists are seeded with
  the Fabric mods (`-Dforbric.crossEcosystemPresence=off` restores single-loader answers). A refused MinecraftForge
  jar is present to nobody.
  `net.forbric.api.ModIds` maps ids the ecosystems spell differently (`cloth-config` / `cloth_config`).
- A mod's own id outranks another mod's `provides` alias for it, whatever order they arrive in —
  `KernelFabricLoader.register`, `ModConstructionOrder` and `ModPresence`'s index all take every real id before any
  alias, as `DuplicateModArbiter` never lets an alias claim an id. owo-sentinel, nested in mods that need owo-lib,
  provides `owo` and depends on nothing installed, so it registered first and owo-lib itself was dropped as a
  "duplicate" (with its `owo-impl` alias). The sentinel itself is now left out next to owo-lib, as Fabric Loader's
  resolver leaves it out (one mod per id, provides included, above); the real-id order still
  decides wherever both stay, two jars in `mods/` or a nested mod kept back.
- `ModConstructionOrder` orders NeoForge construction topologically — a mod after everything it requires or
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
  `mixinextras`, and the carrier's `neoforge` and `fml`, plus `forge`, kept although no MinecraftForge loads — never an id only some installed mod provides
  (`fabric`, which older fabric-api builds provided, is a mod's like any other).
- fabric-api is a mod like any other. The kernel reaches it only through its public API — `PayloadTypeRegistry`'s
  static accessors (`FabricPayloadRegistries`, read by type: protocol, flow and the map of `TypeAndCodec`s),
  `PacketContext.get()`, `Renderer.get()`/`Renderer.register`, `RegistryEntryAddedCallback.event`,
  `ResourceCondition.CONDITION_CODEC`, a `FuelValueEvents.Context` of its own (`KernelFuelValueContext`) — or by what
  its code does (the transaction engine, a register method's backing map: `PublicApiStorage`), never by the names its
  implementation gives its classes, fields or mixins.
- Entrypoints: `preLaunch` after Mixin (§3.2 step 19); `main` inside the registration window (server) or in
  `Minecraft.<init>` (client, default); `client` in `Minecraft.<init>`; `server` on the dedicated server. Each
  mod's `main`, `client` or `server` entrypoint is invoked as Fabric Loader's `invokeEntrypoints` does — every one
  runs even when an earlier one threw, then the stage throws naming the first failure, and that stops the boot
  (§3.4) — with a NeoForge `ModContainer` for that mod active (`KernelForeignShimContext`), because a Fabric mod may
  hold the NeoForge build of a multi-loader library. A `preLaunch` entrypoint that throws is still contained: it is
  logged, its mod is marked FAILED and the boot goes on (§19).
- Fabric access wideners / class tweakers run in the `ACCESS` phase (`access.ClassTweakerTransformer`);
  `@Environment` stripping in `ENV_STRIP` (`EnvironmentStripTransformer`, only for classes from jars arbitrated to
  Fabric).

### 5.2 NeoForge

- **Identity** — `PassiveSeeder` creates a current `FMLLoader`, `FMLPaths`, a `LoadingModList` and `ModList` built
  from the jars this boot selected — no discovery, no module layer, no sorting.
- **Construction** — `KernelModLoader`: a `BusBuilder` `IEventBus` plus a kernel `ModContainer`
  (`KernelModContainerFactory`); constructor arguments filled by type (`IEventBus`, `Dist`, `ModContainer`). Mods are
  constructed, and every later parallel phase runs, on NeoForge's own `modloading-worker` pool, a mod once the mods it
  waits for are done (`NeoParallelDispatch`, §3.4); a carrier without `ModWorkManager` runs them on the caller's thread.
- **Buses** — mod-bus events the kernel posts itself go through `KernelLifecycle.postModBusEvent`; setup phases
  through `runtime.KernelNeoSetup`; deferred work runs on the thread NeoForge runs it on (`NeoDeferredWork`).
- **Enum extensions** — `NeoEnumExtensions` feeds every jar's `META-INF/enumextensions.json` to NeoForge's own
  `RuntimeEnumExtender`; `NeoEnumExtensionInjector` is registered near the end of COREMOD (its placement is
  load-bearing: it defines FML classes at that point), and only if some mod declares an extension.
- **Coremods** — NeoForge's coremod jar is never loaded; `transform.NativeCoremodParity` performs its rewrites
  (flower pot `potted`, biome climate/effects, structure settings, `finalizeSpawn`) *after* Mixin, where NeoForge
  runs them. `finalizeSpawn` is pointed at NeoForge's own `EventHooks.finalizeMobSpawn`.

### 5.3 MinecraftForge — refused

Forbric no longer loads traditional MinecraftForge mods. Nothing of MinecraftForge's runtime is on the classpath, and
the kernel's MinecraftForge machinery (identity seeding, mod construction, loading states, capabilities, enum
extensions, field getters, fluid types and entity-fluid state, configs, the event forward pass of §8) is deleted. What
is left is the judgement and the refusal:

- **The rule** (`discovery.LoaderHalfJudge`, from the jar's bytes alone, the same answer for the boot and for
  `run/compat/Stage3Sweep.java`): a jar is refused only when it has a MinecraftForge half (`META-INF/mods.toml`) and no
  usable Fabric or NeoForge half. A NeoForge half whose only `@Mod` is MinecraftForge's is judged by what NeoForge
  would run of it — its listed mixins and their config plugin (configs found wherever the manifest says, nested jars
  included, read as leniently as Mixin reads them; an unreadable one counts as none), else its entry class, else its
  `data/`; `lowcodefml` exempts only the entry. A jar with no initializer in any half goes to the half that ships its
  mixin config, unless its NeoForge half runs through an `@EventBusSubscriber` or a NeoForge service.
- **The claim** — `MultiLoaderArbiter` gives a refused jar `JarClaim.Refused` (its loader kind is
  `LoaderKind.MINECRAFTFORGE`, which has no `Ecosystem`): suppressed for every family, so no loader constructs it,
  registers its mixins or reads its access transformer; it is never read as a plain library. A universal jar loads
  through its NeoForge half (preferred) or its Fabric half; its MinecraftForge half is set aside and logged. A refused
  jar nested in a mod that loads stays on the classpath as a library (its `@Mod`, mixins, AT, subscribers and packs
  skipped), as native NeoForge treats a nested jar it does not read as a mod.
- **What the player sees** — the jar is named, with the build to get instead, in the player's language (`DialogLang`,
  10 tables): a Lost row, a CONFIRMED required finding, and the load report, which keeps the jar's ecosystem as
  `FORGE` with `refused` true. It is counted under no ecosystem ("N refused") and listed on the Mods screen under
  "MinecraftForge". A client asks; a dedicated server or a strict launch stops (exit `78`, §12.4);
  `-Dforbric.compatibilityPolicy=continue` launches anyway. A Forge build beside the NeoForge or Fabric build of the
  same mod is only a note. gate-m29 is the server refusal (en and zh, strict stop, Lost rows), gate-m26 phase 2 the
  client's question.
- **Data a MinecraftForge build stored** — swapping a mod's MinecraftForge build for its NeoForge build loses what the
  two builds store differently, as the same swap between the native loaders loses it (for example tombstone's
  `ForgeCaps` and MinecraftForge's own `forge:swim_speed` attribute). Forbric does not migrate it; gate-m59 pins those
  rows (`canary/forge-swap/expected-losses.tsv`) and holds every loss Forbric itself would cause at zero for the mods it
  drills. Three of the file's six rows are measured lost on the native swap too; the other three have no native row
  and are judged from the mod's code (`NO NATIVE ROW`).
- **An old install** — a profile written by Forbric 0.3.x or earlier names its own kernel version
  (v0.3.1-beta2's names `net.forbric:forbric-kernel:0.3.1-beta2`), so it keeps launching that old kernel, which never runs this check; rerunning the new
  installer over the same game directory replaces it, and the installer removes what the old one left behind (§13).
  When the new kernel is handed an old install's game base (a kernel jar swapped in by hand, or two 0.4.0-dev builds
  from either side of the removal sharing the `forbric-kernel:0.4.0-dev` coordinate; gate-m17's control N), the launch
  is refused with exit `2` (§3.2 step 0).

### 5.4 Cross-ecosystem services that are not events

- **Networking** — `interop.PayloadInterop` selects a custom-payload codec by runtime payload class where Fabric
  API and NeoForge share a vanilla channel id, through `CustomPayloadInteropSeam`, which makes NeoForge's
  `CustomPacketPayload` codec ask `PayloadInterop.findCodec` (the splice the merged base carried, done by the kernel
  since NeoForge's own base has none; `-Dforbric.customPayloadInteropSeam=off`); `CommonNetworkInteropInjector`
  arbitrates the `c:version` / `c:register` channel both claim (`-Dforbric.commonNetworkInterop=off`);
  `KernelRegistryRevert` restores pre-connection ids on disconnect (`RegistryRevertInjector` makes NeoForge's own
  `RegistryManager.revertToFrozen()` apply the kernel's snapshot, since the kernel, not `GameData.freezeData`, owns the
  freeze), first calling every undo a mod's mixin added to a
  registry class (fabric-api's `unmap()`, recognised by shape: `RegistryRemapContracts`); `NetworkChannelCensus`
  compares registered vs declared channels. NeoForge's `ServerGamePacketListenerImpl.handleCustomPayload` calls its
  super, where NeoForge's dispatcher lives; fabric-api's handler in that super serves only the configuration listener
  and throws `Unknown addon` for a play one, so the first play payload fabric-api did not take disconnected the
  player. `FabricCommonPayloadMixinAdapter` returns at that handler's head for a play listener, so NeoForge's body runs
  as written (`-Dforbric.playPayloadFallThrough=off`). `KernelClientSmoke`'s `-Dforbric.clientSmokeCarry=<tick>` drill
  (Carry On carrying a chest and a pig) drives the whole chain through the game's own key and mouse input.
  Fabric's payload receivers are found by the public `PacketSender` they implement and handed the shared
  payload as Fabric's own codec reads it (`WirePayloads`). Each channel declaration reaches fabric-api's addon once and
  a `minecraft:register` naming only channels already declared in the phase is not sent again
  (`-Dforbric.channelDeclarationsOnce=off`); a mirrored channel's version names its origin and phase, never its
  direction, so a client and a server agree on it.
- **Item/fluid/energy transfer** — `KernelTransferInterop` + `runtime/transfer/` bridge Fabric's transfer API,
  NeoForge's `ResourceHandler`, and Team Reborn Energy when installed
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

What `KernelBoot` registers (94 call sites; some conditional):

| Phase | Registered |
| --- | --- |
| `ENV_STRIP` | `EnvironmentStripTransformer` |
| `ACCESS` | `ClassTweakerTransformer` (Fabric), `access.AccessTransformer` built from every NeoForge mod jar's **and the carrier's** `META-INF/accesstransformer*.cfg` — the carrier's AT is what the base needs too, as natively |
| `COREMOD` | 89 registrations: the loader-probe rewriter, the Mixin-weaver slot (`FmlContextLoaderRewriter`, `ModuleClassLoaderInitInjector`), `GuestMixinPluginGuard`, the lifecycle redirect, `ForbricMergedBaseCompatTransformer`, `VanillaMemberTwinInjector`, and most of the 69 `*Injector` classes in `transform/`, each repairing one named seam between Fabric and NeoForge's game or restoring one vanilla behaviour |
| `FABRIC_BUILTIN` | `RestoredAccessTransformer` (re-applies access to members COREMOD restored), `MergedBaseFrameRecomputer` (recomputes a guest class's stack-map frames when they name a superclass the base does not have) |

The post-Mixin stage (`KernelMixinBootstrap`) is a fixed composition:

```
Mixin (via MixinWeaverSlot) → NativeCoremodParity → PostMixinFixups → RegisterEventsSeam
                           → UncalledValueRelay → InterfaceDefaultConflictRepair
```

Notable repairs by family (read each class's javadoc for the case that motivated it):

- **Base invariants** — `ForbricMergedBaseCompatTransformer` (the name is the old one; every repair in it is about
  NeoForge's base): vanilla's `KeyMapping.MAP` back as a view of the mappings by key (`KernelKeyMappingMap`; NeoForge
  re-types the field to its `KeyMappingLookup`, and LiquidBounce reads it on every key press in a screen), vanilla's
  `FriendlyByteBuf.writeByte(int)` called again where NeoForge's recompile bound a byte-typed call to its extension's
  `writeByte(byte)`, which only forwards there (14 sites in 10 network `write` methods; ViaFabricPlus' ability-flag
  redirect anchors on vanilla's call; `-Dforbric.vanillaWriteByte=off`); vanilla parity of world generation — the
  random sources' double precision and the folded radian constant the recompile lost, the heightmaps vanilla saves,
  dungeon spawners that do not wait for NeoForge's data map (gate-m31); System.out/err wrapped as vanilla and Fabric
  wrap them (`wrapTheStreamsVanillaWraps`; native NeoForge does not wrap them); the PiP renderer bridge and the
  particle map's view of the live one, which wire `VanillaMemberTwinInjector`'s twins; modded feature flags; the
  reload listeners NeoForge refuses to name; NeoForge's PoI writers pointed at the live table and a Fabric mod mapping
  the PoI states NeoForge mapped for it (`KernelPoiStates`); and the resource-condition seams of §9.4.
  `WidenedFieldTwinInjector` (vanilla-descriptor twins of re-typed fields), `MethodBodyNeuter`.
- **Packs and data** — `DataPackHookInjector`, `ClientPackHookInjector`, `PackMetadataFailSoftInjector`,
  `PackOverlayMutabilityInjector`, `NullPackGuardInjector`, `RegistryDirectoryOwnerInjector`,
  `CarrierPackIdMigrationInjector` (a world saved by an older install names the carrier's pack under an id with the
  carrier's version in it; the selection is rewritten in place to the stable `forbric/carrier/2-neoforge-runtime`
  before vanilla reads it, `KernelCarrierPackIds`, so the world opens with its packs, and vanilla logs one expected
  `Missing data pack` warning for the old id) (§9).
- **UI** — `ModsButtonRedirector` (NeoForge's pause-menu lambda opens `KernelModListScreen`),
  `CreativePagerBridgeInjector` (the page state is always the carrier's; when no NeoForge mod is installed, the guest
  pager draws its own page controls, as native Fabric does, and turns the carrier's page through the Fabric
  interface), `CreativeTabOrderInjector` (a Fabric mod's tabs are paged by the Fabric pager's rule inside NeoForge's one
  tab order, every other tab where NeoForge puts it; `EcosystemTabPlacement`), `EarlyKeyMappingRegistrationInjector`.
  The creative search needs no repair on NeoForge's base (the merged base's `SessionSearchTrees` did; gate-m55 now
  asserts the search natively, judged from the creative screen's own grid).
  `-Dforbric.clientSmokeCreativeSearch=<tick>[,query…]` types into the real creative screen and logs the grid.
- **The native shape back** — repairs that give NeoForge's body back a shape vanilla's body has, so Fabric
  injectors written against it land and run as natively (§7.4): `VanillaEarlyReturns` (the early `return`s vanilla
  compiled, where the carrier folded a guard into one join or split the body; a split body's last piece gets its
  method's), `LocalRanges` (a local's variable-table range covers its own uses again where the kernel's own
  tag-tracker rewrite put reads in front of it), `TailLocals` (a split method's tail holds the primitive locals
  vanilla's tail holds), `LambdaInvocationThunkInjector` (an own virtual method reference materialised as one exact
  invocation a guest can target), `ScopedClearRelayInjector` (a native `field.clear()` back where a carrier removes a
  computed subset of the keys; `ScopedClearView`).
- **Fabric adaptations on NeoForge's base** (each with its own switch, each restoring what the mod does on native
  Fabric): `FeaturesPerStepRefreshInjector` + `KernelFeaturesPerStep` (NeoForge's structure modifiers apply again when
  a Fabric mod adds a worldgen feature: `refreshFeaturesPerStep` rebuilds a supplier that is not NeoForge's own `Lazy`
  instead of casting it; `-Dforbric.featuresPerStepRefresh=off`); `KernelItemDamage` (a Fabric item's
  `CustomDamageHandler` runs on NeoForge's `LivingEntity` overload of `hurtAndBreak`, through
  `FabricItemDamageMixinAdapter`; `-Dforbric.itemDamageHandler=off`); `KernelMobShears` (an item in `c:tools/shear`
  shears sheep and snow golems through NeoForge's `IShearable` answer, as BCLib's tag shears do on Fabric;
  `-Dforbric.shearsRelayMobs=off`); `KernelFluidReactions` (Carpet's renewable blackstone and deepslate, and a Fabric
  flow veto such as fabric-block-api's or Create Fly's lava reaction, run on a neighbour change, where NeoForge moved
  vanilla's question; `-Dforbric.fluidReactionNeighbourChange=off`, its HEAD arm
  `-Dforbric.fluidReactionNeighbourChange.head=off`); `ParticleViewTrackerInjector` (fabric-registry-sync's int-map
  remap tracker is kept off a particle map whose int keys already follow the registry, so a remap no longer clears it;
  `-Dforbric.particleViewDetach=off`). Fluid types for Fabric fluids: a Fabric mod's fluid declares no NeoForge
  `FluidType`, so `ForeignFluidTypeInjector` / `KernelFluidTypes` give it a stand-in derived from its tags (water's
  type for one in `minecraft:water`, NeoForge's empty type for one in no tag that gives it meaning);
  `OwnFluidTypeClausesInjector` keeps that stand-in from claiming what the fluid never says — NeoForge's fire clause in
  `Fluid.entityInside` asks the fluid's own type, so a Fabric fluid in `minecraft:water` no longer puts fire out, as on
  Fabric (`-Dforbric.ownFluidTypeClauses=off`) — and `EmptyFluidTypeTrackerInjector` gives NeoForge's empty type no
  entity tracker, so a body falls through such a fluid as through air, as on Fabric, instead of moving as through lava
  (`-Dforbric.emptyFluidTypeTracker=off`; it is keyed on the type, so a NeoForge fluid typed with the empty type loses
  its tracker too, where native NeoForge holds a body still in it: a measured difference no local jar has a fluid for).
- **Client rendering and equipment** — `GlideFreshEquipmentInjector` (`canGlide` answered from the equipment while
  the carrier's cached gliding attribute has not caught up, as the native method always does).
- **Configs** — `StaleWatchEventInjector` (a config file watch's handler runs only for a change made after the watch
  was registered, so a config the tracker creates in an already-watched directory no longer fires a reload event;
  `StaleWatchGuard`).
- **Air** — `VanillaAirRefillInjector` + `KernelAirRefill` (the owner chose vanilla's rule). NeoForge's `baseTick`
  hands the air supply to `CommonHooks.onLivingBreathe`. Where the eye is in a fluid that is not air and the body does
  not drown there — water breathing or conduit power, a drowned, an invulnerable player, lava, a mod's fluid it may
  breathe in — that hook hands its breathe event a refill of zero, so the air stayed where it was. Vanilla refills it by
  4 a tick up to the maximum everywhere but its water branch, where a body that Breath of the Nautilus alone keeps from
  drowning holds its air. That branch is the eye in a fluid the body can drown in (fabric-api makes a Fabric drowning
  fluid answer vanilla's water question exactly so), and the kernel asks it of the eye's fluid types, each answering
  `FluidType.canDrownIn` for that body — water's type yes, lava's no, a mod's type for itself — never through the
  water tag; lava and a fluid the body breathes in refill, as in vanilla. The one store of that zero (found by shape:
  the hook's signature, its one `LivingBreatheEvent(entity, canBreathe, consume, refill)`, the zero stored into the
  refill local right after true into the can-breathe local) becomes vanilla's refill. Vanilla also changes the air only
  below the maximum, where the hook's update for a body that breathes, `setAirSupply(min(air + refill, max))`, pulled
  air above the maximum back to it every tick (native NeoForge does too); its `Math.min` becomes
  `KernelAirRefill.refilledAir`, handed the refill the event started from and the one it ended with, which leaves air
  at or above the maximum where it is unless a listener set the refill (a listener that sets the very amount the event
  started from cannot be told apart, and gets vanilla's update). Nothing else changes: the hook still posts
  `LivingBreatheEvent` once, and a NeoForge listener still sets the amounts and decides whether the body breathes, as
  on NeoForge. The cross-host predicate island's protected decision (a Fabric fluid that drowns like water, §7.4.1)
  starts its event from the same refill (`KernelAirRefill.waterRefill`), and the island's prefix is executed in the
  decorated hook by `VanillaAirRefillInjectorExecutionTest`. The refill asks its drowning question of the body's fluid
  interaction rather than through `isEyeInFluid`, because the native decision island would take the carrier asking
  vanilla's own call as the call having moved and give up the injectors there (Create's diving gear, fabric-api's
  drowning fluids); `NativeDecisionIslandStagedTest` holds both sides of that. `-Dforbric.vanillaAirRefill=off`
  (formerly `forbric.foreignFluidBreathing`, which gave only a Fabric mod's non-drowning fluid the refill) keeps
  NeoForge's rule.
- **Instrumentation** — `ClientSmokeTickInjector` (inert unless `-Dforbric.clientSmoke=true`),
  `ServerTickSamplerInjector`, `CompatibilityPromptTickInjector`, `ServerCompatibilityTickInjector`.
- **Hardening** — `ChunkExecutorGuardInjector` (refuses work offered to a stopped server's chunk executor;
  `-Dforbric.chunkExecutorGuard=off`).

Transformers that promise to land on a class declare it (`AnchorSet`); `AnchorLedger` reports a class that
passed through without being changed (a **Miss**, logged at ERROR immediately) and, at the census landmark,
classes never loaded: `[Forbric/Anchor] N of M declared repair(s) landed; …, and H hedge(s) correctly found nothing to
do`, followed by one INFO line per hedge naming it and what it is kept for (`hedge <transformer> on <class> found
nothing to do; it is kept for: …`), so a hedge count can be traced to its claim. A hedge is a repair declared as
possibly idle; gate-m1's zero-mod boot on NeoForge's base read `71 of 107 … 0 hedge(s)` at the C6 wave-1 tip (9cd04fc1). `RepairDriftCensus` (`run/compat/repair-drift.sh`) replays the claims against a *candidate*
game build — the question a carrier bump asks. It runs `VanillaMemberTwinInjector` first, as the boot does, so it needs
the vanilla-reference sidecar among its jars (repair-drift.sh passes the staged one); without it the twins stand down
and the replay says so (`H hedge(s) stood down`, each `HEDGED`) instead of printing its all-clear.

## 7. Mixin on the merged base

The title and much of this section's vocabulary are the merged base's: "the merged body", "the merge moved it", class
names such as `MergedBaseUncalledMethods`. On today's base they mean NeoForge's patched game as the kernel's chain
leaves it, and "the merge" means what NeoForge's patches (and its recompile) did to vanilla's bytecode; a Fabric mod's
native body is vanilla's. The mechanisms are the same; where a statement only held while MinecraftForge was merged in,
it is marked as history.

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
It acts only for a mixin below 0.10.0 that Mixin is applying to that class, and excludes a mixin its config plugin
refused. It finds the call the moved point
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
NeoForge configs are left as they were: NeoForge's FML decorates its own with a declared `behaviorVersion` and
refuses one below 0.17.1, so every level it can give behaves as the bundled Mixin's newest.
`-Dforbric.mixinCompat=latest` leaves every Fabric config at the newest level.

Order: Fabric configs in Fabric Loader's order, NeoForge configs appended. Mixin orders by priority;
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
   and says so once. Measured on the merged base with lambdynamiclights and `countInjections` on: spruceui's NeoForge
   `GuiMixin` wraps `ClientHooks.extractScreen`, which the merged `Gui.extractRenderState` no longer called, and `Gui`
   lost fabric-screen-api's `GuiExtensions`, so `Minecraft.<init>` died on the cast; balm's moisture wrap cost
   `CropBlock` every mixin the same way, because the moisture read it wraps in `CropBlock.getGrowthSpeed` was in neither
   of the merged base's two `getGrowthSpeed` overloads. With the isolation only those wraps were lost. On NeoForge's
   base `Gui` draws the screen through `ClientHooks.extractScreen`, and fabric-screen-api's screen draw events fire
   beside spruceui and JourneyMap (§7.4); balm's moisture hook has not been measured again there. Without
   `countInjections` (a player's setup) the same packs never reached the isolation.
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
injector bound only to a base method nothing in the game calls is **not** resolved (liveness,
`MergedBaseUncalledMethods`, `-Dforbric.mixinFit.liveness=off`) unless an installed mod calls it. "Nothing calls it"
is a fixpoint over the base, the carrier and the kernel, by one rule set shared with the census that writes
`uncalled-methods.txt`: a call from a method that is itself uncalled, or from code the kernel silences, is no call; a
private method is reached only by a call or handle naming its own class, a static one only by a static call naming
its class or a subclass that does not declare it again, and an override's `super` call of itself is no call; a row
needs the mod's own game to call the method from a place that runs there. The boot scan of the installed mods
(`scanGuests`) reads their calls by the same rules, an `@Invoker` only for the method of its target class with that
name and descriptor, and the row is re-checked on the live bytes. A method whose woven hooks the kernel relays to the
carrier's own place (`UncalledValueRelay`, §7.4) is read as running for the ecosystems its census row names. An injector that stays bound only to such a method
is reported by the final-class check as one that never runs (`CONFIRMED`, not required: it marks the mod's row and
stops no launch). Create's hook on the static `RedStoneWireBlock.shouldConnectTo` nothing calls (now moved onto the
carrier's decision, `UncalledDecisionEntries`, §7.4) used to read as running, and so did, on the merged base, Physics
Mod's collapse hook on a private `removeBlock` overload nothing called there. And a name-only
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
`MixinFitReport <base.jar> <mods-dir> [--verbose]`.

A miss is the base's only if the mod's own platform had the member: vanilla 26.2 for a Fabric mod, NeoForge's patched
26.2 for a NeoForge mod (the config owner's ecosystem, `MixinConfigOwners.ecosystemOf`; a config no single mod claims is
not asked). The base *is* NeoForge's game, so a NeoForge mod's platform lacks exactly what the base lacks; the question
matters for Fabric mods, whose vanilla declares methods NeoForge's patches dropped or retyped. An injector whose `method` selectors are
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
"The platform lacks it" is answered from the base's raw bytes plus the shipped difference
`native-only-methods.txt`: per platform, the methods its game declares in vanilla's packages that the base does not
(636 for vanilla; none for NeoForge, whose game the base is) and the classes there only the base has (12). The table is
derived from NeoForge's own base (Stage 4 C4; the merged base's table had MinecraftForge and NeoForge rows too). A method the raw class still declares, a class outside `net/minecraft/`/`com/mojang/`, and another mod's class
are never called absent. The rows hold only for the base they were derived from, so the table also records that base's
members digest (every class in vanilla's packages with its methods' names and descriptors, bodies left out); a class
served by a jar whose digest differs is not answered for, and one warning says so. Minecraft's own libraries under
`com/mojang/` (brigadier, DataFixerUpper, authlib and five more) are answered from their own bytes: the kernel loads
them beside the base, both platforms load the same jars (vanilla 26.2's version JSON lists them, and NeoForge
26.2.0.93's launcher profile inherits that list and adds no `com.mojang` library), so the table records each of those
jars' members digest too (`library` lines), and a class served by exactly one of them is answered like the base's; a
library jar of another version is not. The rows describe one game per platform — vanilla 26.2 and NeoForge 26.2.0.93's
— and the table's `platform` lines record those versions. A mod whose mandatory `minecraft` range, or `neoforge` range
for its platform, excludes
that version is not native to that game (its own loader would refuse it there, and the newer game it was built for may
declare the method), so it is not answered for and one info line names the requirement; nor is a mod whose range cannot
be read, or whose manifest the kernel has not published (`ModPresence`). For any of those the miss is the base's, as
before. `-Dforbric.mixinFit.nativeAbsent=off` counts such an injector as a miss again.

Provenance was the rule before that and was wrong: the base is NeoForge's patched Minecraft, so "a Forge-family class"
describes most of the jar.

### 7.4 Adapters — moving an injector to where the code went

Rather than drop, the kernel moves a guest injector when the base relocated what it wants. Each adapter is
narrow and table- or proof-driven:

`MixinRetarget` and `MixinStubRebind` (delegating stubs → the overload carrying the body; a stub that first works an
argument out — `Player.doSweepAttack`'s hitbox, `EntityFluidInteraction.update`'s predicate,
`Entity.restituteMovementAfterCollisions`' block position — only where every other caller of the body is a method that
called the stub's signature in vanilla, so `MappedRegistry.register(int, …)`, which NeoForge's registry snapshot calls
directly, keeps fabric-registry-sync's callback off it), `MixinOverloadPin`
(a name-only `@Inject` that Mixin would bind to the carrier's overload, declared first, is pinned to the one
overload its handler fits — only when the first cannot take the handler; otherwise it is explained),
`MixinAnonymousRetarget` + `MergedBaseAnonymousDrift`
(renumbered `Outer$N`), `MixinAtWidenedCall` and
`MixinWrapOperationShim` (calls the carrier widened or reordered; a `@Redirect` follows only a widened static call on a
reviewed `REDIRECTABLE` row, through a wrapper that drops the appended arguments — creativecore's
`RegistryFriendlyByteBuf.decorator`, whose buffers then say `ConnectionType.OTHER`, so NeoForge's connection-aware codecs
use vanilla's wire format on them), `MixinRelocatedCall`, `MixinSubtypeOwnerRetarget` (the same call made through
another owner: a subtype, or the type of a field the carrier widened — `RangedBowAttackGoal.mob` made
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
method: vanilla's for a Fabric mod, the carrier's patched one for a NeoForge mod (where the counts differ,
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
a Fabric callback just before or after a call the mod's own game makes in the host method, where the base's host makes
it through an inherited gateway instead (`Gui.extractRenderState`'s screen draw, which NeoForge's `Gui` makes through
`ClientHooks.extractScreen`), keeps its whole body and runs around that one call inside the gateway. A gateway another
mod's mixin added to without changing what it computes (spruceui's NeoForge mixin and JourneyMap wrap
`ClientHooks.extractScreen`) still counts as the carrier's (`-Dforbric.operationSeams.additions=off` declines every
changed body, as before). Where `NativeCallInlining` could inline the gateway instead, it decides against every
registered config's mixins, not only those planned so far, so a later config's wrap of the inlined call is seen
(`-Dforbric.nativeCallInlining.registeredMixins=off`): with spruceui installed the call stays and the seam serves
fabric-screen-api's screen draw events. A MixinExtras
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
the base keeps but nothing in the game calls moves to the one overload the carrier added in its place,
along a row of `carrier-twins.txt` (`CarrierTwinCensusTest`: vanilla's method is uncalled for the mod's family and is no
carrier stub, the overload takes each of vanilla's parameters — matched one to one by local variable name and type —
and every method that family's own game called vanilla's from calls the overload in the base). The handler is
wrapped when it captures vanilla's arguments in other places than the overload has them: the outer takes the
overload's arguments and hands the original vanilla's. It moves only while vanilla's method is still uncalled (no
installed mod references it), only an `@Inject` capturing vanilla's arguments or none or an `@At`-driven kind whose
contract is known (never a `@ModifyVariable`) — or an `@Inject` capturing locals the old way at one `INVOKE` both
methods make exactly once, where each captured local is one the overload holds there with that name and type (read
by the moved handler as MixinExtras `@Local`s; no row of NeoForge's table captures a local beyond the arguments,
`TwinCapturedLocalsWeaveTest` holds the rule on a synthetic one) — and only where every `INVOKE`/`FIELD` point is held as
often by both bodies. The outer handler takes the moved handler's place in the mixin's declaration order, so handlers
at one point run in the native order. NeoForge gave `ModelBlockRenderer.shouldRenderFace` the block's own position and declared it before vanilla's,
so LiquidBounce's X-Ray face test, selected by name and taking vanilla's four arguments, used to bind NeoForge's
overload, be rejected there and take the whole block-renderer mixin with it; `-Dforbric.mixinTwinRebind=off`. And
`ThinnedCallOrdinals`: where the carrier makes a vanilla call fewer times — it replaced some occurrences with calls of its
own and kept the others — an `@At(INVOKE)` ordinal counted on vanilla's body is re-counted onto the merged occurrence
that is the same call, along a reviewed row that maps each of vanilla's occurrences to the kept one or to none and names
the call each kept occurrence is followed by (checked on both jars, and again on the live method). ViaFabricPlus' 1.12.2
placement hook sits before the third `ItemStack.isEmpty()` of `MultiPlayerGameMode.performUseItemOn`; NeoForge asks
`doesSneakBypassUse` of both hand stacks where vanilla asked `isEmpty`, so its body keeps only the third. Only for the families compiled against vanilla's count; `-Dforbric.thinnedCallOrdinals=off`.
`GuestInjectorPruner` (COREMOD) trims individual injectors from a guest mixin
class where the kernel replaces their function, and, at the end of the bytecode provider's adapters, the injectors the
verdict found Mixin would reject outright (§7.3) — each only while the same rule still says so of the node Mixin is about
to receive, so an injector an adapter already moved where it fits stays. Several adapters read shipped tables under
`src/main/resources/net/forbric/kernel/mixin/` (`carrier-helpers.txt`, `carrier-renames.txt`, `carrier-stubs.txt`,
`carrier-twins.txt`, `lambda-permutations.txt`, `uncalled-methods.txt`, `native-only-methods.txt`); `CarrierHelperCensusTest`,
`CarrierRenameCensusTest`, `CarrierTwinCensusTest`, `UncalledMethodCensusTest` and `NativeOnlyMethodsCensusTest` re-derive
`carrier-helpers.txt`, `carrier-renames.txt`, `carrier-twins.txt`, `uncalled-methods.txt` and `native-only-methods.txt`
from the staged jars (`native-only-methods.txt` from the base and vanilla's own jar) and pin them; since Stage 4 C4
they are derived from NeoForge's own base and carry no MinecraftForge row. A wrapper that renames a handler's body aside
(`MixinRetarget`'s guard and R7, `MixinAtWidenedCall`'s redirect, `MixinSubtypeOwnerRetarget`'s guard) adds a mark of
the mixin class to the name, so two mixins on one target with the same handler name do not merge into one body.
`MixinFitLivenessCensusStagedTest` also keeps a census of the anchors a Fabric mixin names that resolve on stock 26.2
and not on the base as the kernel judges it. Only fabric-api's lines are asserted (a new one fails the build);
any other corpus named in `FORBRIC_ANCHOR_PACKS` is report-only (`build/reports/vanilla-anchor-census.txt`) — nothing
fails on a third-party mod's line, so someone has to read the report.

#### 7.4.1 Native equivalence — general mechanisms instead of special cases

Moving an injector to where the base put the code is not yet what the mod gets natively. The injector has to
land on the instruction it lands on in the mod's own game, run on exactly the executions it runs on there, and see
the values it sees there. The mechanisms below do that. They replaced the per-surface Fabric adapters
(`FabricBlockBreakMixinAdapter`, `FabricEntityMixinAnchors`, `FabricEnchantmentMixinAdapter`,
`FabricMiningMixinAdapter`, `FabricSoundMixinAdapter`, `FabricServerLanguageMixinAdapter`,
`FabricSectionCompilerMixinAdapter`, `FabricFluidFlowMixinAdapter`), the fluid-interaction and breathing adapters, the
sound-contract and clear-all-veto rewrites, and every adapter that recognised one mod's mixin, handler or `@Local`
name, one mod's bytecode template or a library's body by SHA-256. None of them is triggered by a name: each compares
two bodies, the base's method as Mixin will receive it and the *native body* — the platform's own bytes of that method,
the platform being the one of the mod's own ecosystem (`MixinConfigOwners`). For a Fabric mod that is vanilla's body,
read from the vanilla-reference sidecar (§2.3; the merged base carried these directories inside itself); a NeoForge
mod's own game is the base.

- **Reading two bodies.** `CodeAlignment` maps each native instruction to the merged instruction that is the same step
  of the same program (one a carrier removed or rewrote maps to nothing); `ReturnAlignment` says which merged return
  is which native return; `HandlerFlow` reads what a handler computes by data flow, so a contract holds for the
  handler however it was compiled and fails for one that does anything more (instruction-count templates are gone);
  `OperandStack`; `MergedSourceRenames` compares a transported body under
  the names Mixin gave its lambdas and helpers.
- **Where a point lands.** `NativeOccurrences`/`NativeOccurrencePoint` count a call, field, store or expression point
  over the native body and select only the merged occurrences that are its native ones, however much the carrier's
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
  the value it holds natively; judged at the Mixin level the mod is woven at), `MixinTwinRebind` (above), `MixinRenamedBodyEdges` (an edge injector on a body a
  carrier renamed also reaches each method that only forwards to it), `MixinSplitBodyEdges` + `SplitBodyRuns` (the
  start and end of a method a carrier split into pieces are the start of the first piece and the end of the last, and
  a cancel at the start skips the later pieces), `NativeCallInlining` (a carrier helper is
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
  value is NeoForge's `BlockDropsEvent` experience. The relay is made only for hooks of an ecosystem whose own
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
`MixinOverlapLint <base.jar> <mods-dir> [--json out]` (jars found recursively).

## 8. Event bridges

On the merged base most of this section was MinecraftForge's: where the two Forge families' hooks competed for one call
site and the merge kept one, the loser's listeners sat on a bus nobody posted to, and the kernel re-emitted 106 events
for them (`GameEventMultiplexer`, the `KernelGame*Events` classes, the `Forge*Injector` seams, the one-delivery
stand-down and its audits `HookCallSiteCensus`, `CarrierCallGraph`, `DeadEventAudit`, `MergedBaseLoadTimeView` and
`EventChainAudit`). All of it went with MinecraftForge in Stage 4: on NeoForge's base every NeoForge hook is where
NeoForge put it and NeoForge posts its own events, and the C3 pass that removed the bridges NeoForge itself had lost on
the merged base (screen opening/closing, conversion `Post`, item tooltips, fuel, attributes) checked each against the
genuine base first. What is left bridges Fabric API, whose mixins were written for vanilla, to the place NeoForge's
game does the work:

- **Loot tables** — `LootTableEventBridgeInjector` + `LootTableEventDispatch`: fabric-loot-api's
  `ReloadableServerRegistriesMixin` cannot fit NeoForge's reshaped loading lambda, so the two calls that bracket loot
  loading go through the kernel, which calls NeoForge's own hook first and then fires `LootTableEvents` (REPLACE,
  MODIFY, ALL_LOADED) through fabric-loot-api's public surface, from the point NeoForge's `LootTableLoadEvent` fires.
- **Fuel** — `FabricFuelValuesInjector` + `KernelFabricFuel`: the server, and a client on a NeoForge connection, build
  fuels in NeoForge's `DataMapHooks.populateFuelValues`, which never calls vanilla's `FuelValues.vanillaBurnTimes`
  where fabric-content-registries fires `FuelValueEvents`; the builder goes through the kernel there, and the mixins on
  `vanillaBurnTimes` itself (Create's wrap of its `build()`, a RETURN hook) run on the same builder
  (`-Dforbric.fabricFuel=off`, `-Dforbric.fabricFuel.returnHooks=off`).
- **Experience from a break** — NeoForge computes it in `BlockDropsEvent` and pops it once in `handleBlockDrops`;
  Fabric mods hook vanilla's `Block.tryDropExperience`, which nothing in NeoForge's game calls. The kernel's listener
  at `LOWEST` (installed on NeoForge's bus by `KernelServerDutiesInstaller`, see "Server duties" below) runs the relay of
  `tryDropExperience` (`UncalledValueRelay`, §7.4.1) for a block whose native `spawnAfterBreak` asked it, handing it
  the event's value and dropping what it would have popped, so a Fabric mod's replaced or cancelled ore experience
  decides the experience once, where NeoForge pops it. A NeoForge listener at `LOWEST` registered after the kernel's
  sees the relayed value, one registered before sees the value before it.
- **Server duties** — `KernelServerDuties` holds what the kernel does on a running server for both ecosystems: the
  compatibility tick, `FabricLoader.getGameInstance()` on a dedicated server (fallback to the entry's own
  `Hooks.setGameInstance`), the load report once a world is up. They used to ride inside the MinecraftForge forward
  pass, whose missing classes took them all with them on NeoForge's base; they are installed on NeoForge's bus
  unconditionally now.

## 9. Datapacks, resources and data

### 9.1 Server data

A genuine loader turns every mod jar into a pack by walking `ModList`'s mod files. The kernel publishes mods into
`ModList` with `modFiles` empty, so that walk finds nothing. `DataPackHookInjector` →
`KernelLifecycle.onServerDataPacks` → `KernelDataPacks` / `runtime.KernelDataPackSource` serve each NeoForge
jar's `data/` — and the carrier's (`c:` convention tags, `neoforge:` damage types and data maps exist only there).
Metadata is read through NeoForge's `ResourcePackLoader.readWithOptionalMeta`, keeping root-pack overlays. Fabric
mods' data is served by fabric-api's own resource loader as on Fabric (`-Dforbric.modDataPacks=off` disables the
NeoForge packs). Each served jar declares the known pack NeoForge declares (`KnownPack("neoforge", "mod/<ids>",
<versions>)` for every mod file, the carrier included), so the carrier's data-driven damage type does not make every
world experimental and ask the player for a backup. The carrier's pack id is stable, `forbric/carrier/2-neoforge-runtime`
(older installs put the carrier's file name, with its version, into the id; a world saved by one is migrated on load,
§6 "Packs and data").

### 9.2 Client assets

NeoForge's `mod_resources` source lists the mod files of NeoForge's own loading, which the kernel's mods are not.
`ClientPackHookInjector` redirects the body of `ClientModLoader.setupModResourcePacks(PackRepository)` to
`KernelLifecycle.onClientResourcePacks`, where `KernelClientPacks` adds one visible, movable parent pack holding one
hidden pack per ecosystem jar (compatibility forced to `COMPATIBLE`, as both genuine loaders do) before the first
reload, the shape a genuine instance has, so a player's own resource pack can be put above a mod's assets.
The kernel's own assets (the Mods button icon) ride in `forbric-kernel-runtime.jar`.

### 9.3 Pack metadata written for three loaders at once

A multiloader `pack.mcmeta` carries a section per loader, and on Forbric all three parsers read it.
`KernelPackMetadata` + `PackMetadataFailSoftInjector` keep an unparsable foreign section from dropping the whole
pack; `PackOverlayMutabilityInjector` (repair) and `NullPackGuardInjector` (backstop) handle NeoForge's overlay
merge mutating a list fabric-api has frozen (`KernelPackRepair` documents both).

### 9.4 Conditions, registries, data maps, worldgen

- **Resource conditions** — two evaluators, because NeoForge's `RegistryLoadTask` decodes every datapack-registry
  element of every pack through NeoForge's `ConditionalOps`: `runtime.KernelFabricConditions`
  (`fabric:load_conditions`, judged with fabric-api's public `ResourceCondition.CONDITION_CODEC` and `test()`;
  fabric-api's own two mixins cannot fit) and `KernelNeoConditions`, which keeps a `neoforge:conditions` type NeoForge
  does not know from failing a multi-loader mod's file: decoded as a condition that does not veto, so the element loads
  and the other dialect decides. Only in a pack Fabric's resource loader serves for a Fabric mod — in a NeoForge mod's,
  vanilla's or a world datapack NeoForge's own verdict stands and the file is skipped, as natively.
  `KernelConditionSources` tells the decoder which pack the file came from (`Resource.open()` hands it its source pack;
  recipes, parsed all before any is decoded, are named again right before each decode);
  `-Dforbric.neoConditions.fabricPacksOnly=off` gives every pack the leniency, as before (gate-m21's `dual_forge` file
  is its red control).
- **Registry directories** — `RegistryDirectoryOwnerInjector` / `KernelRegistryDirectories`: `registryDirPath`
  answers as the owning ecosystem does. **Aliases** — `KernelRegistryAliases.aliasTargetPresent` answers
  fabric-registry-sync's alias-target warning for NeoForge's alias-first registration order
  (`-Dforbric.aliasPresenceParity=off`). MinecraftForge's registry wrappers, and the repairs that made them public,
  synced and alias-aware, went with MinecraftForge.
- **Block → item** — `Block.asItem()` reads `Item.BY_BLOCK`, which NeoForge's item add-callback fills as items are
  added; on NeoForge's base every add links where `asItem()` looks, as natively. (The merged base's `Items` was
  MinecraftForge's and filled a map nothing read; that repair is retired with MinecraftForge.) The sweep when the
  registration window closes stays as a net and normally links nothing.
- **Datapack registries** — `KernelLifecycle.registerDataPackRegistries` posts `DataPackRegistryEvent.NewRegistry`
  and mirrors Fabric dynamic registries both ways.
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
- **Worldgen** — NeoForge's biome and structure modifiers run in its single modifier pass; `NativeCoremodParity`,
  `BiomeInfoRebaseInjector`, `BiomeLateWriteInjector` make the modified views read, and NeoForge's structure modifiers
  apply again when a Fabric mod adds a feature (`FeaturesPerStepRefreshInjector`, §6). gate-m31 holds zero-mod
  worldgen to vanilla's biomes and structure starts at the same seed, against a pure vanilla dedicated server.

## 10. The unified API — `net.forbric.api`

Parent-pinned (one copy per JVM), and what the kernel and both compatibility layers speak instead of
translating pairwise:

| Type | Role |
| --- | --- |
| `Ecosystem`, `Side` | the one ecosystem and side vocabulary |
| `ForeignType` | 49 rows naming one NeoForge concept's class — names only. Its MinecraftForge column and the rows only MinecraftForge read are gone; folding it into plain constants is a later step |
| `DiscoveredMod`, `UnifiedDependency`, `VersionPredicate`, `ModIds` | the mod and dependency model |
| `ModPresence`, `ModCatalog` | "is X running", and the list a player sees with status `OK` / `DEGRADED` / `FAILED` |
| `OperationSeams`, `CallbackSeams`, `LookupOutcomes`, `VirtualProperties` | what the code the adapters of §7.4 write calls at run time: a callback run around a proved call inside a carrier gateway, a callback carried to its proved program point, a lookup's outcome steering its caller's cache, a property read only through a proved field getter |
| `ProtocolExtension`, `ProtocolExtensions` | optional protocol adapters, published as services (`META-INF/services/net.forbric.api.ProtocolExtension`) and constructed before game classes load |
| `CompatibilityFinding`, `CompatibilityFindings` | per-launch evidence ledger (§12) |

It is not a stable API for mods.

## 11. The Mods screen

`runtime.KernelModListScreen` replaces NeoForge's mod list screen (`ModsButtonRedirector`) and reads
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
opens NeoForge's settings screen, is there when the instance has a NeoForge mod or a universal jar arbitration gave to
NeoForge; a pure-Fabric pack, a universal jar Fabric won included, has no such row, and neither does a refused
MinecraftForge jar (it is listed under "MinecraftForge", counted under no ecosystem, §5.3). NeoForge itself still runs, and reads its own settings file, under every instance. The row is
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
for every NeoForge mod with a config; `-Dforbric.modMenuForeignScreens=off` offers nothing. A presence
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
NeoForge mod), no NeoForge row in the list widget's rows while the game's row still opens; the plain
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
is the control. Not measured by any gate: Mod Menu's own list screen and a real press of its Config button, a NeoForge
mod other than that canary in Mod Menu, the unlisted-factory log, the 2 s refresh, Tab, NeoForge's row for a set whose
only NeoForge mod is a carried one (`ModListRowsTest` only; a universal jar through
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
`Minecraft.<init>` it leaves through `SilentInitException`, so it is not reported as a crash. The policy decides
compatibility findings only: a mod that throws while loading (its constructor, a registration or setup listener, a
Fabric `main`/`client`/`server` entrypoint) is not a finding and no policy lets the boot carry on past it — it stops the game as native NeoForge
does (§3.4), exit `1` on a dedicated server; on a client, NeoForge's error screen while the game has no window yet and
a message box once it has one (§19), and for a Fabric entrypoint Minecraft's crash report alone. Findings that appear after boot never fork Swing or exit the JVM: on the server they are
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
inherited (`LibCaughtContractTest`; its strict case passes with the inner run keeping a file of its own too, since
the stub's outer gate ends on the inner run and takes its exit status, so that the outer gate turns red for an inner
run's failure is held by no test). Each inner run used to take a file of its own, print its CAUGHT line into the
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

Pure JDK, no dependencies, bytecode release 17, version `0.4.0-dev` (the version written into the profile's kernel
coordinate and shown in the window; a release sets it in `build.gradle`).

```
java -jar forbric-kernel-installer.jar                     # window (InstallerGui)
java -jar forbric-kernel-installer.jar --dir DIR [options] # headless install
    --mc 26.2  --artifacts DIR  --jdk PATH  --remote  --release TAG  --mirror PREFIX  --offline
java -jar forbric-kernel-installer.jar --doctor [--dir DIR] [--jdk PATH]
```

### 13.1 Building the game artifacts on the player's machine

`ArtifactBuilder.build` runs under `<mcDir>/.forbric-build/`, resuming at the first unfinished step:

```
neoforge userdev ─┬→ neoforge-runtime
                  └→ NFRT (client jar + server jar seeded) → patched-mc-neoforge   (the game base)

patched-mc-neoforge + neoforge-runtime ──→ link check            (nothing may dangle)
vanilla 26.2.jar + patched-mc-neoforge ──→ vanilla-reference     (resources only, §2.3)
```

- `NeoForgeRuntimeBuilder` (zip entry times fixed, so the carrier is byte-identical build to build), `NfrtRunner`
  (NeoFormRuntime, result `gameJarNoRecomp` — binary patches, no decompiler, no `javac`), `VanillaReference`
  (in-process, JDK only).
- **Link check.** `LinkCheckTool` unpacks `forbric-link-check.jar` (the kernel's own `net.forbric.kernel.linkcheck.LinkCheck`
  and ASM's core, carried as a resource in every installer build, slim ones included, because the check runs before
  any of Forbric's jars are staged) to `.forbric-build/tools/` and runs it twice, judging first the base's members,
  then the carrier's, each run scanning both jars. It judges with JVM semantics: existence of classes, fields and
  methods, access from the referring class (private and nestmates, package, protected, public), writes to final
  fields and overrides of final methods, and every invokedynamic bootstrap and method-handle constant. Every violation
  counts in the one number its verdict line ends with, `[link-check] loaded N classes (...); dangling references: K`.
  There is no baseline: NeoForge's patched game and its own carrier leave nothing dangling (0 in either run, measured
  on 26.2.0.93), so **an install fails unless both runs report 0.** The developer's check of the whole game a launch
  assembles (base and carrier through the kernel's twin and compat transformers, the kernel's game side, generated
  helpers) is `linkcheck.game.GameLinkCheck`, gate-m0 step 4.
- `--artifacts DIR` ("Built artifacts (leave empty)" in the window) is for developers only and skips the build.
  `GameArtifacts` takes the two jars from that directory alone and opens each before anything is downloaded or
  written: the base must be Minecraft 26.2 whose classes refer to `net/neoforged/` and to nothing of MinecraftForge
  (a base that does is the merged base of Forbric 0.3.x or earlier); the runtime must hold NeoForge's core class and its
  mod loader (`FMLLoader`, `IModInfo`), name the pinned version as its manifest's main `Implementation-Version`
  (`Pins.NEOFORGE`), and not be a MinecraftForge runtime. Only then the link check, which on its own passes any jar
  that refers to nothing outside itself (issue #13). The vanilla reference is built from the supplied base.
- `Pins`: `MINECRAFT = "26.2"` (the only supported version), `NEOFORGE = "26.2.0.93"`, `NFRT = "2.0.18"`,
  `NFRT_RESULT = "gameJarNoRecomp"`, each with its reason in the source. `BuildStamp` keys every cached artifact to
  the pin set alone, so a pin bump cannot be served from cache (an install stamped by an older installer, whose key
  also carried the merge tools' digest, is rebuilt once).
- `JdkLocator` needs Java ≥ 21 for the build tools (NeoFormRuntime is class-file 65; the link checker is compiled to
  21); it tries the running JVM, then the launcher's runtimes, then the system, and never downloads a JDK. The game
  itself needs Java 25.

### 13.2 The profile

`Installer` writes `versions/26.2-forbric/26.2-forbric.json`:

- `inheritsFrom: "26.2"`, `mainClass: net.forbric.kernel.boot.KernelClientLaunch`, no JVM arguments;
- game arguments `--gameJar <patched-mc-neoforge>`, `--runtimeJar <neoforge-runtime>` (one flag, one value: PCL2
  keeps only the last of a repeated flag), `--libraryPath <every vanilla library for this platform>`,
  `--nativeReference <vanilla-reference>` (its own flag, never a second `--gameJar`);
- `libraries`: the bundled Forbric jar (`net.forbric:forbric-kernel:<installer version>`) and the kernel's
  third-party dependencies (from the kernel's own `printBootClasspath`), plus the three artifacts staged as
  `net.forbric:patched-mc-neoforge`, `net.forbric:neoforge-runtime` and `net.forbric:vanilla-reference`, each
  versioned `26.2-26.2.0.93`;
- a `forbric` block, metadata only, declaring `net.fabricmc:fabric-loader:0.19.3` so launchers that detect a
  loader by searching the JSON's text treat the instance as modded (and give it its own mods folder), and listing its
  ecosystems as `fabric` and `neoforge`.

Mods for both ecosystems go in `<mcDir>/mods` (or `versions/26.2-forbric/mods` under an isolating launcher).

**Clean-up of older installs** (`OldInstallCleanup`, after the profile is written, so the new profile counts among
those whose libraries are kept; it never fails the install): in `.forbric-build`, a closed list of what older
installers wrote and this one never reads (the merged base and its conflict list, MinecraftForge's runtime, interop
runtime and patched game, MinecraftForge's work trees, the merge-tools jar, v0.1.0's `<mc>-<forge>/` tree) — never
`out/`'s current outputs or NFRT's caches; in `dl/` (the download cache), every file that is not one of this install's
inputs (`ArtifactBuilder.downloadInputs`: the NeoForge userdev jar, what the NeoForge runtime is built from — the same
names `NeoForgeRuntimeBuilder.inputFiles` gives the build to fetch — and the server jar), so an older installer's
MinecraftForge downloads go, and so (owner ruling, C6) do cached downloads for another Minecraft version, which the
install that needs them fetches again; an install given its game files (`--artifacts`) leaves `dl/` alone; in
`libraries/net/forbric`, a library version
directory only when no version profile names anything inside it or above it (every `versions/<id>/*.json` is read,
following `inheritsFrom`, and every string naming a `net.forbric` coordinate or `net/forbric/` path counts, in any
case and however it is joined), so a second profile that still uses an old base keeps it. Nothing outside
`net/forbric` is touched; links and junctions are never followed or removed; what this install staged is never
removed; a file that cannot be deleted (a game still holding it on Windows) is named and left. Every removal is
logged, in the player's language. Measured by gate-m17 phase U on an install the real v0.3.1-beta2 installer laid out:
63 removals, 285.8 MB (22 outside `dl/`, 258.5 MB; 41 of beta2's 56 cached downloads, 27.3 MiB, 13.3 MiB of them
MinecraftForge's jars and tools), `dl/` left holding exactly this install's 17 inputs, unchanged, and the new install
boots into a world and leaves cleanly; a second install over a fresh one leaves `dl/` byte-identical. About 16 MiB of
the old version's files stay: NFRT's cache (12.5 MiB) and work files (3.1 MiB) for 26.2.0.88 (measured by INSTFIX on a real-player layout,
not re-measured). `-Dforbric.installerCleanup=off` leaves
everything (phase U's reverse control).

### 13.3 Where Forbric's own jars come from

The default build bundles them (`bundleForbric` writes `forbric-kernel-libraries.json` with SHA-1s). `-Pslim`
builds carry none and fetch at install time through `RemoteSource`: Forbric's jars from the GitHub release, other
libraries from Maven first. `-PreleasePin` compiles `forbric-release.properties` (tag, repo, `manifestSha256`) into
the jar — the digest of the manifest is the trust anchor, which is why release is two steps (`releaseAssets`,
then the pinned slim build). `releaseAssets` depends on `bundleForbric` alone and refuses to write assets unless
`run/compat/evidence.py release-check` confirms the kernel jar is byte-identical to the accepted candidate; the release
roles evidence binds are vanilla, the NeoForge base, the NeoForge runtime, the kernel, the kernel's game side and the
vanilla reference.

### 13.4 `--doctor`

`Doctor.examine` writes nothing, creates nothing, downloads nothing: platform, JVMs found, whether the base
version is installed, pins, which artifacts are present or will be built, expected disk (about 480 MB at peak, about
470 MB kept; measured on gate-m17's cold install: 476,904 KiB at its last sample, the size only grows during an
install), and a one-line verdict.

## 14. What `forbric-loader/` is still for

It no longer builds anything the kernel runs on or is built against: the installer no longer ships or runs the merge
tools (`MergedBaseBuilder`, `EditSplicer`, `MergedLinkChecker`, `RuntimeInteropPatcher` and the rest), nothing builds
`:mergeToolsJar` for it, nothing outside `forbric-loader`'s own Stage-5 leftovers reads the link baseline any more (they
are listed below), and developer stages come from `tools/dev.py prepare`, not from the
shell pipeline under `forbric-loader/run`. What is left:

- **Test inputs.** The canary mod sources in `run/livemod-src*`/`testmod-src` (`build-testmods.sh`; `tools/dev.py`
  builds the NeoForge canary from them), the mod sets in its run directories that gate-m0's discovery oracle reads, and
  the Fabric server launcher jar `native-controls.py prepare --family fabric` reads from its downloads.
- **Fallbacks.** `forbric-kernel/build.gradle`'s staged root, `lib.sh` and `launch-kernel-*.sh` still fall back to
  `forbric-loader/run` when no `FORBRIC_OLD` / `-Pforbric.stagedRoot` / `.dev` stage is given; such a stage is unstamped
  and the tools that check provenance refuse it. Set `FORBRIC_OLD` to a stage `tools/dev.py prepare` made.
- **Leftovers for Stage 5**, which deletes the directory: `run/build-merged-base.sh` (no CI step runs it any more;
  `tools/test_pins.py` and `LinkBaselineProvenanceTest` still read it), the old link baseline
  (`src/test/resources/merge/link-check-baseline.txt` and its `.provenance.json`; `LinkBaselineProvenanceTest` reads
  both, and CI's `build` job runs that test when it builds `forbric-loader`; `build.gradle`'s `mergeToolsJar` packs
  the baseline, and `run/build-merged-base.sh` and `run/check-merged-links.sh` take it as their default `LINK_BASELINE`, but
  nothing runs those three any more: `mergeToolsJar` is not part of `assemble`, and no gate or CI step calls the two
  scripts; only `run/test-link-check.sh` calls `check-merged-links.sh`, with its own baseline, and nothing runs it),
  and CI's `build` job, which still bootstraps and builds `forbric-loader`.

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
├── .github/workflows/build.yml    CI: job `build` (bootstrap + forbric-loader), `kernel`, `kernel-prepared`, `development-tools`
│
├── forbric-kernel/                THE KERNEL — own Gradle build and wrapper
│   ├── build.gradle               boot jar (release 21), runtimeJar, transferTest, staged-artifact wiring
│   ├── gradle.properties          ASM, sponge-mixin, MixinExtras, SAT4J, NightConfig … versions
│   ├── src/main/java/net/forbric/api/          the unified API (18 files)
│   ├── src/main/java/net/forbric/kernel/       boot (93), transform (105), mixin (166), fabric (15),
│   │                                           metadata (12), access (7), classloading (8), discovery (7),
│   │                                           interop (21), ui (6), util (13), mapping (2), soak (2),
│   │                                           linkcheck (2: the link checker, §13.1)
│   ├── src/main/java/net/fabricmc/             vendored Fabric API surface (47 files)
│   ├── src/main/resources/                     Mixin service registrations; mixin census tables
│   ├── src/runtime/                            GAME side, net.forbric.kernel.runtime (+ soak/, transfer/)
│   ├── src/modmenuPlugin/                      GAME side, the kernel's own Mod Menu entrypoint (KernelModMenuPlugin)
│   ├── src/modmenuApi/                         GAME side, a stand-in of Mod Menu's API, shipped as resources (§11)
│   ├── src/test/, src/transferTest/            unit tests; transfer-engine tests
│   ├── canary/                                 canary mods the gates build and load
│   └── run/                                    gate-m*.sh, launch-kernel-{client,server}.sh, lib.sh,
│                                               diff-oracle.sh, mixin-inventory.sh, merge-packs.sh,
│                                               build-*-canary.sh, compat/ (sweep and evidence tooling,
│                                               rename-mod.py: the rename experiment, §16.1)
│
├── forbric-kernel-installer/      THE INSTALLER — src/main/java/net/forbric/installer/kernel/, packaging/
│
├── forbric-loader/                first generation; test inputs only, deleted in Stage 5 (§14)
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

`check` also runs development/evidence-tool self-tests and `installerTest`: it builds `linkCheckJar` and runs every
installer `*Test` class that has a `main` (the packaged link check's controls among them), failing when any fails or none
ran.
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
./run/gate-m0.sh               # build + suite from the JUnit XML + scan + game link check + discovery oracle
java -cp <boot-cp> net.forbric.kernel.boot.Main --scan --mods <dir> --report out.json
```

- **Staged artifacts.** The game side compiles against a staged root's `neoforge-patched/patched-mc-neoforge-26.2.jar`
  and `neoforge-runtime/neoforge-runtime.jar` (`-Pforbric.stagedRoot`, else `$FORBRIC_OLD/run`, else
  `forbric-kernel/.dev/staged/run` from `tools/dev.py prepare`), and its tests read the stage's
  `vanilla-reference/vanilla-reference-26.2.jar`; plus brigadier, datafixerupper and gson from a local Minecraft install, a fabric-api jar for the transfer modules (`-Pforbric.fabricApi`), and
  Team Reborn Energy 5.0.0 pinned by SHA-256 (`run/energy-api/energy-5.0.0.jar` or `-Pforbric.rebornEnergy`).
  Without the staged jars `compileRuntimeJava` is skipped and `jar` produces a boot jar with no game side — which
  is what CI builds. `KernelRuntimeClasses.verify` is what catches such a jar at launch.
- **Unit tests.** How many run and skip depends on the staged root and the fixtures present (some tests generate one
  case per fixture), so each count names its root. The full run of the tree Stage 4 C6 merged
  (`-Pforbric.requireFixtures=all jar cleanTest test --continue`, no daemon, JDK 25, on a `.stage4` root prepared from
  NeoForge 26.2.0.93) reported 5,969 tests in its JUnit XML, 0 skipped and 5 failed. The five are fixtures this machine
  no longer has: three LiquidBounce tests (its two jars are lost and cannot be fetched again) and two opt-in
  shared-final tests; `-Pforbric.requireFixtures=all` turns a missing fixture into a failure rather than a skip. Many
  tests read the staged jars; gate-m0 fails on *any* skipped test, because a skip there means the tests did not look at
  the real base, so gate-m0 is red on those three LiquidBounce tests on this machine. The weave tests (`mixin/weave/`)
  run synthetic mods with unrelated names through the real Mixin and MixinExtras, each with its mechanism switched off
  as the control. Tests must pass on JDK 21 as well as 25 (the CI unstaged job is JDK 21).
- **Gates.** 61 scripts (one of them the two-hour soak, gate-m34), `forbric-kernel/run/gate-m*.sh`, each asserting on the real logs and files of a real
  instance, most with named negative controls (a `-D…=off` or input removal that must turn exactly the named
  checks red). `run/compat/gates-all.sh` discovers them by glob; `gates-parallel.py` overlaps them using each
  gate's `# GATE-PARALLEL: rundirs=… mem=…` line (60 of 61 carry one; a gate without it runs alone), giving each
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
  rounds and m15 (since removed: m16 makes each of its checks) on each tree in two of three, and in a `-j 5` batch with
  the full test suites beside it m12 and m16 did. (Those rounds ran on the merged base.) A red
  gate from a loaded parallel run says nothing about the kernel until the boot times in its logs have been read. The
  clock can also bite by date: the game's log4j configuration rolls `logs/latest.log` at midnight, eleven gates read
  that file, and m9 went red once because its session crossed midnight and the reload line it looks for went to the
  rolled archive.

| Gate | Asserts |
| --- | --- |
| m0 | build, the whole suite, `--scan`, the game link check (`GameLinkCheck` over base, carrier and the kernel's game side as a launch assembles them: 0 violations), discovery oracle, tool self-tests, `installerTest`, transfer suite |
| m1, m3 | the base to `Done` with zero mods and no genuine lifecycle; NeoForge's baseline + a real `@Mod` |
| m2, m2b | a real Fabric mod, then full fabric-api, with no Fabric Loader |
| m4, m4-canary, m7-neo | real Fabric and NeoForge mods in one server, universal jars arbitrated to one family; pure NeoForge jars |
| m8, m10 | multiloader `pack.mcmeta` sections do not delete or break a pack |
| m9, m27 | client into a world and out cleanly with the 97-jar pack; a fresh non-black frame |
| m11 | a config-driven mod stack on a dedicated server |
| m12–m14, m16 | client↔server over a real socket; a Paper anti-cheat; a pure Fabric server; NeoForge's login/configuration handshake and per-world SERVER config sync |
| m17 | the installer: its profile, launched the way a launcher does, the link-check jar it carries, a cached and an offline reinstall, the refusal of an old install (control N), and phase U — an install the real v0.3.1-beta2 installer laid out, upgraded: exactly what nothing uses any more is removed (with `-Dforbric.installerCleanup=off` as reverse control), a profile that still uses old files keeps them, and the upgraded install boots into a world |
| m18, m19, m20 | cross-ecosystem presence; a nested library initialised once; unmet dependency reaches the player |
| m21, m26, m28, m29 | the setup lifecycle reaches NeoForge mods and their deferred work runs; client registration events and the client's question about a refused MinecraftForge mod; NeoForge configs + the live file watcher; a MinecraftForge-only mod refused by name and a dedicated server stopping on it |
| m22, m23 | quitting survives a replaced kernel jar (the client JVM gets `M22_READY_SECONDS`, 600, from its own start to reach a world; the launcher's Gradle build before it is timed and bounded apart; negative controls: a client JVM stopped as it starts, and a pack whose only world is unreadable); elytra flight |
| m24, m24b, m30 | a failing mod, a mod whose metadata cannot be read, and a partly failing mod, attributed on every surface; a mod that throws while loading stops a server as native NeoForge does |
| m24c | a jar listed in `forbric-disabled.txt` is loaded by nobody and named in the load report; a server only logs the crash-suspects offer |
| m24d | the same stops on a client, on each of its three paths, ending in NeoForge's own error report (it opens client windows on the desktop) |
| m25, m31, m32 | NeoForge's biome and structure modifiers; zero-mod worldgen parity with a pure vanilla server; a save opens with a mod removed |
| m33, m39, m40, m52 | item/fluid/energy transfer between Fabric and NeoForge; hoppers into Fabric storages |
| m34 | ≥ 7200 s occupied simulation soak with retention checks |
| m35–m38, m42–m46, m50, m51, m53 | per-surface behaviour: mixin outcome, entity callbacks, enchantments, coremod parity, block break and loot, interaction, everyday actions, stub rebind, load predicates, tooltips, widened `NEW` anchors |
| m54 | a NeoForge mod's play-phase packet reaches the server: Carry On, with fabric-api installed, carries a chest and a pig through real key and mouse input; the same run with the repair off must pick nothing up (third-party jars: `M54_CARRYON`, `M54_FABRIC_API`) |
| m55 | the creative inventory's search finds items after a Fabric mod refreshed the search trees the vanilla way, judged from the screen's own grid |
| m56 | with no Mod Menu, Fabric mods' config screens open through the kernel Mods screen's Config button (own entrypoint, a provided factory, two mods carried inside a jar — one under the heading it names, positions read from the screen's own list widget), as do a universal jar's NeoForge screen, the game's options and NeoForge's own settings (its row named `NeoForge`, listed because the set has a NeoForge mod), all driven through the game's keyboard and mouse handlers (arrow keys, clicks, double clicks); `-Dforbric.modMenuStandIn=off` must take the Fabric canaries' buttons away; with Fabric canaries only, NeoForge's row must be absent. With Mod Menu installed (required: `M56_MODMENU`/`M56_FABRIC_API`, default `run/modmenu/`, else SKIP-FATAL), Mod Menu's API answers for the universal canary with its `fabric.mod.json` name and description and NeoForge's screen (its list screen is not opened), and Mod Menu's own factory answers the game's row once; `-Dforbric.modMenuForeignScreens=off` is that phase's control. Canaries built from `canary/modmenu-config` |
| m57 | breathing, drowning, damage and liquids on NeoForge's game against native NeoForge: a dedicated server with the NeoForge canary plays 53 scenarios (NeoForge's breathe and drown listeners at three priorities, water breathing, Breath of the Nautilus alone in open air, water and a bubble column, a pig drowning in NeoForge's own liquid, the probe's player drowning through the damage chain, each liquid placed, taken up and poured with its bucket); each row is compared with the same jar on native NeoForge (`canary/forge-breath-drown/native-reference.json`, three agreeing native runs, regenerated by `run/compat/breath-drown.py reference`) |
| m58 | a world the kernel at tag `tri-eco-26.2-final` wrote (built by `run/old-kernel.sh`) opens on this one and keeps what it held, judged on the files (`run/compat/world-snapshot.py`) and through the mods' own APIs, server and client, with a reverse control |
| m59 | a world written with a mod's MinecraftForge build, opened with its NeoForge build, loses exactly what the same swap loses natively (`canary/forge-swap/expected-losses.tsv`) and nothing Forbric causes |
| m60, m61, m62 | the Fabric-on-NeoForge adaptations in dedicated servers, each with its switch off as a red control: fabric-item-api's damage handler, BCLib's tag shears, the furnace remainder (m60); fabric-block-api's and Create Fly's flow vetoes on a neighbour change (m61); Carpet's 34 behaviour checks (m62) |

- **Compatibility sweeps.** `run/compat/PROTOCOL.md` is the procedure for running random/popular Modrinth sets on
  a Windows machine through the installed profile (`push-and-run.sh`, `win/*.py`, `pick_mods.py`, `evidence.py`),
  plus static tools (`abi-audit.py`, `field-drift.py`, `fapi-usage.py`, `hook-worklist.sh`, `repair-drift.sh`,
  `control-diff.sh` — same Fabric mods on native Fabric vs Forbric).
- **CI** (`.github/workflows/build.yml`): job `build` bootstraps and builds `forbric-loader/` (it goes with that
  directory in Stage 5). Job `kernel` (JDK 21, no game files) runs `./gradlew build -Pforbric.skipBaseline=ci-unstaged`
  in `forbric-kernel/`: the boot side compiles and the unit tests that need no game files run. About a third of the
  suite skips without game files, and that skipped set must equal `src/test/skip-baseline/ci-unstaged.tsv` line for line
  (`skipRatchet`, `tools/junit_report.py`): a test that starts skipping fails the job, and a line that stops skipping
  has to be deleted. The run page shows tests / executed / skipped with the most common skip reasons, and the JUnit
  reports are uploaded as `kernel-test-results`; regenerate the baseline from that artifact's
  `skips-actual-ci-unstaged.tsv` or with `-Pforbric.writeSkipBaseline`. Job `kernel-prepared` (JDK 25) first builds
  the game files on the runner with `tools/dev.py prepare --no-assets` (Minecraft from Mojang, NeoForge's artifacts from
  its maven; NeoForge's patched game, the NeoForge carrier, the vanilla reference and the NeoForge canary built there;
  only upstream downloads are cached and nothing derived is uploaded), then runs the same suite plus `transferTest` with
  `-Pforbric.requireFixtures=staged,game-side,mc-libraries,java-25`: every kind of fixture except third-party mod packs
  is present there, so a skip for any other kind, or an untagged skip, fails the job at the test that skipped. The
  tests that still skip there, which need third-party mod packs that are not in this repository or are opt-in, are held
  to `ci-prepared.tsv` the same way (its artifact is `kernel-prepared-skips`). kernel-prepared then runs four real
  dedicated-server gates on the same files: m1, m36, m46 and m53, whose mods are canaries built from this repository.
  Job `development-tools` runs `tools/dev.py tool-test` and the installer's `installerTest` (the packaged link check
  and its controls) on Windows, Linux and macOS. The other gates (client, third-party packs, soak) run on a developer
  machine; nothing runs them on a schedule.

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
3. **Native comparison.** The same mods run on native Fabric Loader 0.19.5 and native NeoForge 26.2.0.93 (and, until
   Stage 4, native MinecraftForge 26.2-65.0.1), and are compared injector by injector: the instruction each injector lands on (the
   export census, and the instructions around the landing point), whether the method it lands in runs in the game
   (its callers, and the kernel's uncalled table), what in-game probes report (counts per frame, values, screenshots),
   and the warnings. The same instruction, run on the same executions, with the same effect, is equivalent; anything
   else is a named gap, or comes with a proof that no structural trigger can close it. The per-mod harness for this
   lives on the developer machine, not in the repository; `rename-mod.py census`/`compare`,
   `run/compat/control-diff.sh`, `run/compat/native-controls.py` (native Fabric and NeoForge arms against a Forbric arm
   booted from a stamped stage; native MinecraftForge stays only as an install, `prepare --family forge`, for gate-m59's
   swap oracle and gate-m57's reference) and `run/compat/breath-drown.py` (gate-m57's native reference: it runs the
   native servers itself and writes the reference only when all three runs of each agree on every stored field, and
   stops, naming the differences, when they do not) are the parts that are.

The last round's results are in §19.

## 17. System properties

Set with `-D` on the JVM. The installed profile sets none. There are 463 distinct `"forbric.*"` string literals in
`src/main` and `src/runtime`, 86 of them retired names (`ForbricSwitches.RETIRED`: setting one changes nothing and logs
a WARN saying why, among them every MinecraftForge switch); a renamed switch's old name still works with a WARN
(`ForbricSwitches.RENAMED`); almost every repair has an `-Dforbric.<name>=off` switch, and turning one off logs a
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
| `forbric.multiLoaderPreference` | per-jar ecosystem order, default `neoforge,fabric` (`minecraftforge` is warned about and ignored) |
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
| `forbric.mixinFit.nativeAbsent.base` | `<digest>`: the base members digest `native-only-methods.txt` is trusted for instead of its own (fixture games in tests) |
| `forbric.mixinOverlapLint` | `off`: no cross-mod overlap findings at boot (§7.6) |
| `forbric.guestMixinAdapter` | `off`: no derived drops, only the hand list |
| `forbric.mergedBaseCompat` | `off`: drop the built-in incompatibility lists |
| `forbric.disableMixinConfigs`, `forbric.enableMixinConfigs` | csv of configs to disable / force on |
| `forbric.suppressMixins`, `forbric.keepMixins` | csv of `config:Mixin` to drop / keep |
| `forbric.lateMixinJudgment` | `off`: a mixin a config plugin adds is not judged when Mixin first reads it |

**Native-equivalence mechanisms** (§7.4.1, §6; each `=off` turns one off and logs what is lost):
`forbric.nativeOccurrences`, `forbric.addedCallOccurrences`, `forbric.nativeSliceOccurrences`, `forbric.returnOrdinals`,
`forbric.shiftedPoints`, `forbric.vanillaEarlyReturns`, `forbric.mixinStubRebind`,
`forbric.mixinTwinRebind`, `forbric.renamedBodyEdges`, `forbric.splitBodyEdges`,
`forbric.nativeCallInlining` (and `.registeredMixins`), `forbric.operationSeams.additions`, `forbric.mixinFieldInitialisers`, `forbric.expressionThunks`, `forbric.carrierDecisions`,
`forbric.carrierGuardedPoints`, `forbric.carrierDefaultChains`, `forbric.carrierHelperResults`,
`forbric.carrierReturnedCalls`, `forbric.carrierMethodReferences`, `forbric.replacedDecisionValues`,
`forbric.nativeDecisionIsland`, `forbric.placementCallWitness`, `forbric.nativeValueStores`,
`forbric.nativeConsumerStores`, `forbric.nativeDeadValueConsumers`, `forbric.nativeLocalPins`,
`forbric.nativeLocalMaterialization`, `forbric.capturedLocals`, `forbric.localRanges`, `forbric.tailLocals`,
`forbric.scopedClearRelay`, `forbric.uncalledDecisionEntries`, `forbric.uncalledValueRelay`, `forbric.mixinAsideMarks`,
`forbric.mixinPrivateDefaults` (a guest mixin's private method that would hide a NeoForge interface default is renamed,
`MixinPrivateDefaultCollision`), `forbric.lambdaShapeEvidence` (`LambdaShapeEvidence`: the lambda shapes a Fabric mod's
game had that the base no longer gives, which the handler shims need before treating a handler as written for a live
lambda), `forbric.postMixinFixups.nativeDelegation` (the delegated-constructor replay stands down where the mixin's
own platform already delegates), `forbric.vanillaMemberTwins` (`VanillaMemberTwinInjector`: members vanilla has and
the base lacks, re-added from the vanilla reference).

**Diagnostics and test drivers**

| Property | Effect |
| --- | --- |
| `forbric.clientSmoke` (+ `clientSmokeWorld`, `clientSmokeReadyTicks`, `clientSmokeDisconnectTicks`, …) | unattended client run: enter a world, live, leave, exit; the gate drills `clientSmokeMultipart`(+`Types`) and `clientSmokeTooltipEvent` are test drivers only |
| `forbric.definedClassEvidence` | directory for a content-addressed record of every defined class |
| `forbric.traceClassDefine` | csv of binary names; log a stack the first time each is defined |
| `forbric.tickSampler` | `off`: no server tick-time sampling |

**Selected repair switches** — `forbric.arbitratedAwayEntrypoints`, `forbric.graphicsBootstrappers`,
`forbric.channelDeclarationsOnce`, `forbric.creativeTabEcosystemOrder`, `forbric.glideFreshEquipment`,
`forbric.staleWatchGuard`, `forbric.commonNetworkInterop`, `forbric.customPayloadInteropSeam`,
`forbric.playPayloadFallThrough`, `forbric.chunkExecutorGuard`, `forbric.transferBridge`,
`forbric.hopperFabricStorage`, `forbric.earlyConfigs`, `forbric.fabricHooks`, `forbric.fabricImpl`,
`forbric.kernelBundledFirst`, `forbric.modDataPacks`, `forbric.modMenuStandIn`, `forbric.ownFluidTypeClauses`,
`forbric.emptyFluidTypeTracker`, `forbric.supersededMixins`, `forbric.neoScanData` (the `ModFileScanData` index,
§4.1), `forbric.modFilePerJar` (§4.1), `forbric.neoConditions.fabricPacksOnly` (§9.4), `forbric.fatalErrorWindow`
(§3.2).

**Fabric adaptations on NeoForge's base** (§6; each restores what the mod does on native Fabric) —
`forbric.itemDamageHandler`, `forbric.shearsRelayMobs`, `forbric.fluidReactionNeighbourChange` (and `.head`),
`forbric.featuresPerStepRefresh`, `forbric.particleViewDetach`.

**Installer** — `forbric.installerCleanup` (`off`: an upgrade removes nothing, §13.2); `forbric.dialogLanguage` also
picks the installer's language. `forbric.launchInputCheck=off` only warns about the launch inputs (§3.2 step 0), never
about an old install.

## 18. Invariants

Break one and the failure usually surfaces far from the cause.

1. **Boot code names no game type.** It reaches the game side by string, and every such string is in
   `KernelRuntimeClasses`. Anything shared across the boundary is `ALWAYS_PARENT`; anything game-side is
   `ALWAYS_GAME`. One copy of each per JVM.
2. **`MIXIN` is terminal.** Nothing registers into it through `TransformChain`; Mixin is served pre-Mixin bytes;
   the post-Mixin stage order is fixed.
3. **Loader identity exists before the first class reaches the Mixin transformer.** Mixin constructs every guest
   config plugin on the first class it transforms, and plugins read `FMLPaths`/`FMLLoader` in their `<clinit>`.
4. **One registration window, one freeze.** Content registration happens between `unfreeze` and
   `closeRegistrationWindow`; the client reopens once, for its entrypoints, and re-freezes.
5. **The lifecycle trigger is redirected or the kernel does not boot.**
6. **Game buses start before setup phases; the payload phase closes after them.**
7. **Arbitration decides once.** The pre-scan's plan is consumed by both discoveries; later passes verify, never
   re-choose. Selection is bounded by work, not time.
8. **A repair that stands down says so** — `AnchorSet`/`AnchorLedger`, a WARN per switch.
9. **Attribution never changes an outcome.** Error handlers and reports record; Mixin's decision and a mod's
   failure are left as they were. `CompatibilityDecision` never exits the JVM; only the launch boundary does.
10. **Nothing carrying Mojang or NeoForge bytes is committed or shipped.** The game side links
    against staged jars `compileOnly`; the installer builds them on the player's machine.
11. **No catch is silent.** Every catch rethrows or reports to `Caught` (§12.5), and a unit test that makes the
    kernel carry on from a failure fails unless it says so with `@ExpectCaught`.
12. **No repair keys on a name.** What triggers a repair is a shape — bytecode, descriptors, inheritance and
    interface relations, a contract, whether an event is delivered — never a mod id, a mod's class or package name,
    a jar name, a mixin config, mixin class, handler, `@Local` or `@Share` name, a mod's implementation class
    (fabric-api is a mod: its public API may be called, its implementation is not named), a one-mod bytecode
    template or a body hash. A mod is a sample and a test case, never the condition (§16.1).
13. **A loading failure stops.** A NeoForge mod that throws while the game loads stops the boot with NeoForge's own
    error, as native NeoForge 26.2.0.93 does; no phase but NeoForge's "Config loading" contains it and carries on
    (§3.4). A Fabric `preLaunch` entrypoint and a throwing Fabric injector at the moved freeze point are still
    contained too (§19; the Stage 4 record's STOP open item 2). A Fabric `main`
    entrypoint that throws stops the run too, as native Fabric does, but a dedicated server exits `1` where native
    Fabric's exits `0` (neither starts a world; the Stage 4 record, D87(e)); that difference is open (§19).
14. **MinecraftForge is refused, never loaded.** A jar is judged from its bytes by `LoaderHalfJudge` alone, a refused
    jar's claim is never an ecosystem and never read as a library, and this kernel refuses to launch over an old install's game base (§5.3).

## 19. Current state and known boundaries

- **Minecraft 26.2 only**, Mojmap identity namespace. There is no remapping step: a jar compiled against another
  namespace is not translated (`kernel/mapping/` is carried over from the weld and is not on the boot path).
- **NeoForge's own game, with Fabric adapted onto it.** The base is NeoForge 26.2.0.93's patched game as NeoForge
  builds it; a NeoForge mod meets exactly its native game, and every kernel change to it either adapts a Fabric mod to
  where NeoForge moved vanilla's code (§6, §7.4) or restores vanilla behaviour NeoForge's recompile lost (world
  generation, `System.out`, `writeByte`). MinecraftForge mods are refused (§5.3); nothing of MinecraftForge runs.
  What is not repaired is reported: `FieldDriftAudit`, `AbiLinkAudit`, the mixin verdicts of §7.3 and the anchor
  census.
- **`PARTIAL` mixins apply by default** — half-application is kept, visible, in preference to dropping working
  hooks.
- **One class, one copy.** When two ecosystems' builds of a mod compete, one wins; the losing ecosystem sees a
  presence alias, not the mod's own platform glue.
- **Measured, not promised.** `MOD_TEST_FAILURES.md` records a per-mod test (each jar alone with its required
  dependencies, into a world, screenshot, exit) on three fresh random Modrinth sets against `main` as of 2026-09-26
  (`11ca1ffa`), on the merged base: 89.0 % loaded without failure lines on average (91.8 % reached the world;
  79.1 % with nothing reported DEGRADED in the load report), against 80.5 % for release v0.2.0 on the same jars. That
  sweep has not been re-run on NeoForge's base.
- **Measured for Stage 4** (the removal of MinecraftForge; every figure with its log in
  `forbric-kernel/run/compat/reports/2026-10-30-forge-removal-baseline/`): the refusal rule over 1,953 distinct local
  jars refused 90, each checked by hand, with 0 false refusals; the full gate sweep at the end of C5 (`394b8804`, no
  verdict cache, the soak left out) was green on every gate but gate-m0, red only on the three lost LiquidBounce
  fixtures; a pure-Fabric A/B against native Fabric found no Forbric-only difference the merged base did not have, and
  native NeoForge A/B 3 of 3; worlds the old kernel wrote open whole (gate-m58), a mod swapped from its MinecraftForge
  build to its NeoForge build loses only what the native swap loses (gate-m59), zero-mod worldgen matches vanilla
  (gate-m31), hoppers into Fabric storages pass (gate-m52).
- **Native comparison, last round on the merged base.** Before Stage 4, 27 mods were measured against their native
  loaders by the method of §16.1 (instruction-level landing, liveness, in-game probes, rename experiment): Carpet,
  Create Fly, C2ME, Lithium (Fabric and NeoForge builds), Physics Mod, Sodium (NeoForge), Continuity, Iris, Corpse,
  Shoulder Surfing with CustomSkinLoader, fusion, Do a Barrel Roll, Controlify, Just Enough Characters, SpectreLib, Mod
  Menu, Forge Config API Port, PuzzlesLib, Item Glint Relight, Jade, Essential, fabric-api, Balm, Architectury, Origins
  Legacy, Better Mount HUD. 22 were equivalent; Lithium (Fabric) and Forge Config API Port were not only for reasons
  proven not closable, and Mod Menu, fabric-api and Balm were not for the reasons below. Every rename experiment came
  out the same as the original. That round has not been repeated on NeoForge's base; Carpet, Create Fly's fluid
  reactions and BCLib were measured again there by gates m60–m62, and air under a fluid by gate-m57 against native
  NeoForge.

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
  server, where vanilla draws only while it drowns. NeoForge's `baseTick` hands its breathing hook its consume
  amount up front, `air - decreaseAirSupply(air)`, and `decreaseAirSupply` calls `random.nextDouble()` whenever the
  `OXYGEN_BONUS` attribute is above zero; vanilla calls it only in its drowning branch (native NeoForge draws as
  Forbric does). The air values are vanilla's — the amount is used only when the body does not
  breathe — but that entity's later random draws (movement, sounds, particles) are not. It can be closed as a trade-off,
  not built and not measured: around that one call in `baseTick`, keep the state of the entity's random, draw, and put
  the state back, then move the random on as the call did only where the hook uses the amount (the event left the body
  not breathing), which is where vanilla draws. A listener reads the same amount as on NeoForge, and while no listener
  draws from the entity's random during the event the sequence is vanilla's. The costs: a NeoForge listener that does
  draw from it gets the value the amount was drawn from where NeoForge gives it the next one; keeping and restoring the
  state depends on the random source's implementation (the entity's is vanilla's `LegacyRandomSource`, whose
  `nextDouble` moves only its private `seed`); and an override of `decreaseAirSupply` that draws from another source
  still draws every tick.
- Drowning: NeoForge's breathing hook never broadcasts entity event 67 (a client shows no burst of drowning bubbles)
  and draws six `nextDouble`s from the body's random source per bubble before it asks the level for a particle, which a
  server level ignores, so after every drowning the body's random sequence differs from native Fabric's. Native NeoForge
  does the same; left as it is (a random-sequence trade-off for the owner).
- Experience: the relay of `tryDropExperience` (§7.4.1) declines, with a WARN, a hook that reads or changes the raw
  draw or replaces the `popExperience` call, and runs for a block only when what its native caller hands the provider
  can be read (a field, a static field, a static call on constants); a NeoForge `BlockDropsEvent` listener at `LOWEST`
  registered before the kernel's sees the value before the relay. Read from the bytecode, not measured: NeoForge's
  `handleBlockDrops` calls `spawnAfterBreak(…, false)`, so a Fabric mod's own block whose `spawnAfterBreak` override
  pops experience when `dropXp` is true pops none.
- Two mods' `HEAD` hooks on one static decision nothing calls any more (`UncalledDecisionEntries`) become two wraps of
  the carrier's decision: they run in the reverse of the native order, and a cancel by the first no longer skips the
  second (pinned by `UncalledDecisionTwoMixinsWeaveTest`; no measured mod has two such hooks).
- Report-only noise, behaviour equivalent: Sodium's `WindowMixin` "partial 3/4", the `MixinOverlap` R3 warning on
  Sodium and fabric-rendering, Continuity's stale "partial (kept)", the contended call-site warning for Shoulder
  Surfing with CustomSkinLoader, Create's `FuelValuesMixin` `SUSPECTED` and the render thread's second preflight of
  `EntityMixin`. The loader-nested `mixinextras.init.mixins.json` (no mixin, only MixinExtras' init) is applied in
  every pack, as Fabric Loader does.
- Not measured: Controlify's gamepad features (no gamepad); Sodium's NVIDIA/AMD/Intel workaround branches (only Apple
  hardware was available; the code path is the same on both sides); the load point of a Fabric consumer's server
  config under SpectreLib (`ServerAboutToStart` instead of `SERVER_STARTING`, both before the world loads; argued
  from bytecode, no such consumer to run).
- A deliberate difference: genuine NeoForge 26.2.0.88 crashed rendering a Corpse corpse (`NoSuchFieldError
  NeoForgeMod.NAMETAG_DISTANCE`); `ZeroNameTagMigrationInjector` proves the field is only used to set 0 and reads
  `Attributes.NAME_TAG_DISTANCE` instead, so the corpse renders without a name tag.

**Since Stage 4** (NeoForge's own base):

- `System.out`/`err` are wrapped as vanilla and Fabric wrap them; native NeoForge does not.
- A hopper follows NeoForge's semantics; into a Fabric storage it goes through the transfer bridge (gate-m52).
- With the client resource preload gone, as natively, EnhancedVisuals logs 20 "Could not find any resources" lines and
  CreativeCore "Could not load default style" during client setup; native NeoForge also runs client setup before the
  first reload (inferred from bytecode order, not observed natively).
- Known gaps the merged base had too: better-nether's placement-only `bn_shouldSpreadLiquid` (its handler captures
  locals inside the method, so the neighbour-change wrap cannot serve it); a Fabric TAIL `@Inject` on `ItemStack.useOn`
  most likely never runs server-side, because NeoForge's server path returns from `CommonHooks.onPlaceItemIntoWorld`
  before vanilla's tail (bytecode only, not measured).
- Stopping like native, open: a Fabric `main` entrypoint that throws now ends a dedicated server with exit `1`, where
  native Fabric's ends with exit `0` (neither starts a world; in the pure-Fabric A/B, Forbric's signature for the same four mods moved from `POLICY_STOP:78`
  to `EXIT:1`, verdicts unchanged; whether to keep that is an open owner question); a NeoForge mod whose required dependency failed still runs its later phases (natively the boot
  has stopped); still contained, where every other loading failure stops: NeoForge's "Config loading" listeners, a
  Fabric `preLaunch` entrypoint (its mod is marked FAILED) and a throwing Fabric injector at the moved freeze point
  (logged, later ones on that hook skipped; the Stage 4 record's STOP open item 2, not measured there); a stop inside
  the game shows a message box where native NeoForge draws the error in the game window;
  the crash report's mod list is empty and its "Complete loading of N" count short; datapack-registry declarations are
  processed after the freeze on a client whose Fabric mains run in `Minecraft.<init>`; and NeoForge's mod construction
  runs in three kernel-level passes, with registration on the caller's thread. Owner decisions, recorded in the Stage 4
  record.
- An old world from an older install logs one `Missing data pack` warning for the carrier's old pack id when it is
  first opened (the selection is migrated, §6); expected, once.
- **Versions.** `forbric-kernel/build.gradle` says `0.1.0-SNAPSHOT`; the installer is `0.4.0-dev`, the line whose first
  release is the first without MinecraftForge. `net.forbric.api` is internal and changes without notice.

## 20. Further reading

- [`forbric-kernel/README.md`](forbric-kernel/README.md) — the kernel's own summary and gate notes
- [`forbric-kernel/run/compat/PROTOCOL.md`](forbric-kernel/run/compat/PROTOCOL.md) — the compatibility sweep procedure
- [`forbric-kernel/run/compat/reports/2026-10-30-forge-removal-baseline/README.md`](forbric-kernel/run/compat/reports/2026-10-30-forge-removal-baseline/README.md) — the record of dropping MinecraftForge, stage by stage, with every measurement and decision
- [`forbric-loader/README.md`](forbric-loader/README.md), [`forbric-loader/run/README.md`](forbric-loader/run/README.md) — the first generation and its former artifact pipeline
- [`forbric-loader/CREDITS.md`](forbric-loader/CREDITS.md), [`forbric-loader/MAPPINGS.md`](forbric-loader/MAPPINGS.md) — the clean-room boundary and mapping position
- The class javadoc. Almost every class under `net.forbric.kernel` opens with the failure it exists for.

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.
