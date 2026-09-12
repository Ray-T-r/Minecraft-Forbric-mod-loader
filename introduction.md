# Forbric — architecture and internals

For mod and loader developers. This document is precise rather than gentle: it states what Forbric
actually does, in the order it does it, naming the real types. If you want the player-facing version,
read the [README](README.md).

> **Which generation this describes.** There are two in this repository, and this document is about the
> first. `forbric-loader/` is the *weld*: real fabric-loader/Knot as host, with FML and FancyModLoader
> reflectively driven alongside it. `forbric-kernel/` is a ground-up rewrite that deletes the weld — one
> transforming class loader, one lifecycle, one registry model, with the two Forge-family universal jars
> demoted to passive ABI carriers and no genuine loader lifecycle booting at all. The installer on the
> release page installs the kernel. Its own design is in
> [forbric-kernel/README.md](forbric-kernel/README.md); everything below still describes the weld, and is
> kept because the kernel is a reaction to it and reads better with it in view.

Terminology used throughout:

| Term | Meaning here |
| --- | --- |
| **substrate** | the Apache-2.0 [fabric-loader](https://github.com/FabricMC/fabric-loader) code Forbric compiles into itself: Knot, the SAT resolver, metadata parsing, the Mixin service, the game-provider framework |
| **Forge-family** | traditional MinecraftForge *and* NeoForge, jointly. They are separate runtimes with separate manifests, and Forbric treats them as two members of one family |
| **canonical runtime namespace** | the namespace the running game is actually in. `intermediary` by default; `named` (Mojmap) on 26.2 via `-Dforbric.runtimeNamespace=named` |
| **guest mixin** | a mixin belonging to a third-party mod, as opposed to Forbric's own or the game's |

---

## 1. The problem, stated precisely

Three independent incompatibilities. Every part of Forbric's design is an answer to one of them.

**1.1 Launch ownership.** Both loaders are the process entry point, and both build a transforming class
loader. Fabric builds Knot. Forge's ModLauncher/BootstrapLauncher builds its own, plus a JPMS module
layer, plus `securemodules`. Two transforming loaders means two definitions of every game class, with
two distinct `Class` identities; mods on either side then see a game the other side never modified.

**1.2 Namespace.** Historically the game ships obfuscated. Fabric mods reference the `intermediary`
namespace; modern Forge-family mods reference Mojang official names (Mojmap); older Forge mods reference
SRG. Nothing links them at runtime unless someone supplies the join.

**1.3 Lifecycle and registry windows.** Each ecosystem has its own load phases, its own event bus, and
its own registration window that opens and closes. Content registered outside the active window is
rejected. The two schedules are not aligned, and neither yields to the other.

Note what is *not* on this list: mod APIs. Forbric does not re-implement either ecosystem's API. A Forge
mod under Forbric calls the genuine MinecraftForge or NeoForge runtime, loaded from Forge's own
artifacts. That is the central design decision, and it is why the work sits in the loader rather than in
a compatibility mod.

## 2. Shape of the solution

One process, one loader, one transforming class loader, one namespace, one registration window.

Forbric reuses the substrate rather than reimplementing it, and adds:

- a clean-room Forge-family side (manifest parsing, access transformers, version ranges, mod
  preparation, runtime bring-up);
- **one** unified transform pipeline (`TransformChain`) hosted in Knot, with Mixin pinned last;
- **one** mapping spine (`ForbricMappings`) joining intermediary and Mojmap;
- bring-up drivers that start the *real* Forge-family runtimes under Knot without ModLauncher ever
  executing.

### 2.1 How the substrate is composed in

The substrate is **not vendored and not shaded**. `forbric-loader/build.gradle` adds three upstream
source roots to its own `main` source set:

```
../fabric-loader/src/main/java
../fabric-loader/src/main/legacyJava
../fabric-loader/minecraft/src/main/java
```

so Gradle compiles upstream's sources alongside Forbric's. Consequences a contributor must know:

- The upstream release is pinned exactly once, as `fabric_loader_ref` in
  `forbric-loader/gradle.properties` (currently **0.19.3**). `bootstrap.sh` clones that tag;
  `run/verify-substrate-patches.sh` diffs the live checkout against that same tag and fails on drift.
- Forbric's changes to upstream code live as eight patch files under
  `forbric-loader/patches/fabric-loader/`, not as edits buried in a copied tree. `git -C fabric-loader
  diff <tag>` is the ground truth.
- The built loader jar therefore legitimately contains compiled FabricMC code. Its `fabric.mod.json`
  keeps the id **`fabricloader`** — every Fabric mod and `forbricruntime` itself depend on that id, so
  it cannot be renamed. Only name/description/contact are rewritten, with a build-time assertion that
  fails if upstream reformats the file.

### 2.2 The eight substrate patches

| Patch | Target | What it changes and why |
| --- | --- | --- |
| `0001-entrypoint-26.2-dedicatedserver` | `EntrypointPatch` | recognises the 26.2 dedicated-server entrypoint shape |
| `0002-fabrictransformer-forbric-bridge` | `FabricTransformer` | the one-line seam: every class is routed through `ForbricTransformBridge` after the substrate's built-in transforms, on the single pre-Mixin byte path. Pass-through until Forbric installs the chain |
| `0003-knotclassloader-module-resources` | `KnotClassLoader` | implements the module-aware `getResourceAsStream(Module,String)` / `loadClass(Module,String)` overloads. The JDK defaults return null for any named module, which would hide every layered jar's resources (e.g. `/forge_version.json`) and break `Class.forName(Module,…)` and `ServiceLoader.load(layer,…)`. Knot's model is one flat classpath, so module resources *are* the flat resources |
| `0004-knotclassdelegate-package-manifest` | `KnotClassDelegate` | defines packages with the owning jar's manifest attributes (per-package section first, then main attributes) instead of an all-null stub. Without it, FML's `JarVersionLookupHandler` → `Package.getImplementationVersion()` returns null and production FML rejects its own language providers. The same patch implements the `-Dforbric.downgradeInjectionErrors` skip, since the class delegate owns the single `IMixinTransformer` |
| `0005-hooks-defer-fabric-main` | `Hooks` | under `-Dforbric.fabricMainDeferred`, defers Fabric `main` entrypoints into the Forge registration window, and preserves stock `main`-before-`client` ordering (see §8) |
| `0006-moddiscoverer-suppress-wrongloader` | `ModDiscoverer` | honours `-Dforbric.suppressMods` / `-Dforbric.suppressModSources`, so a Forge-only jar carrying a deliberately-broken "wrongloader trap" `fabric.mod.json` can be loaded through its real Forge identity instead |
| `0007-fabricmixinbootstrap-suppress-configs` | `FabricMixinBootstrap` | honours `-Dforbric.suppressMixinConfigs`, and the config-level half of `-Dforbric.relaxMixinOverwrites` |
| `0008-mixinserviceknot-relax-overwrites` | `MixinServiceKnot` | honours `-Dforbric.relaxMixinOverwrites` for guest mixins whose `@Overwrite` shape does not survive the Forge-patched base, and `-Dforbric.suppressMixins` for individual guest mixin classes |

Patches 0003 and 0004 exist solely because of the synthesized module layer in §7. Patches 0006–0008 are
escape hatches for third-party mixin/manifest edge cases and are driven entirely by system properties —
none of them changes default upstream behaviour.

## 3. Entry points and boot order

`ForbricClient` / `ForbricServer` replace `KnotClient` / `KnotServer` as the launch `mainClass`:

```java
public static void main(String[] args) {
    ForbricBootstrap.run(args, "client");   // parent-loaded, pre-Knot
    Knot.launch(args, EnvType.CLIENT);      // substrate takes over
}
```

Everything in `ForbricBootstrap` runs **before Knot exists**, on the parent class loader, with no game
types resolvable. That constraint is why `ModAnnotationScanner` (ASM `@Mod` discovery) lives in the core
jar and reads bytes rather than loading classes.

The whole Forge path is best-effort: any failure logs and falls back to a Fabric-only boot rather than
aborting.

### 3.1 Pre-Knot (`ForbricBootstrap.run`)

1. **Resolve `gameDir`** from the launch arguments; `mods` is `gameDir/mods`.
2. **Unified discovery** — `ForbricModDiscoverer.discover(mods)` → `List<DiscoveredMod>` (§4).
3. **Resolve the active Forge families.** An instance's game base carries MinecraftForge *or* NeoForge —
   they patch vanilla differently and are mutually exclusive — or **both**, on the tri-in-one merged base
   built by `run/build-merged-base.sh`. Explicit via `-Dforbric.forgeFamily` (the installer writes it),
   otherwise probed from the staged runtime jar's injected identity (mod id `forge` / `neoforge`).
4. **Classify and suppress.** Several cases are decided here, and each one is a real jar shape in the
   wild:
   - a jar carrying both `fabric.mod.json` and a Forge manifest is usually already substrate-loadable;
     preparing its Forge identity as well would duplicate the whole jar;
   - **except** "wrongloader traps" — Forge-only builds that ship a deliberately unparseable
     `fabric.mod.json` (bad version, throwing entrypoint) to fail fast in a Fabric loader. Those are
     suppressed on the Fabric side (`-Dforbric.suppressMods`, patch 0006) and loaded via their real
     Forge identity;
   - the runtime carriers themselves (mod id `forge` / `neoforge`) must load through their own Fabric
     identity and are never Forge-wrapped;
   - a mod whose *only* manifest belongs to an inactive family is skipped with a log line naming the
     profile that would load it.
5. **Version gate** — `ForbricVersionGate` records mods incompatible with the running MC version in
   `-Dforbric.versionIncompatibleMods`.
6. **Prepare each Forge-family mod** (§5) and hand the results to the substrate via `fabric.addMods`.
7. **Install the transform chain** through the `FabricTransformer` → `ForbricTransformBridge` seam
   (patch 0002), including the collected Access Transformers.

### 3.2 Under Knot

8. Knot starts; the game provider locates the game jar (on the full-Forge profile, via Fabric's own
   `-Dfabric.gameJarPath.client`, which `LibClassifier` resolves first-origin-wins *before* the
   classpath — so launcher library reordering cannot defeat it).
9. `forbricruntime`'s **`preLaunch` entrypoints** run: the two Forge-family drivers (§7). Each is
   presence-gated and idles if its runtime is not staged.
10. The game's `main` runs. Forge's own `ServerModLoader` / `ClientModLoader` drives Forge mod loading;
    Fabric's own entrypoints run for Fabric mods; the bridge mod aligns the registration windows (§8).

## 4. Unified discovery and metadata

`ForbricModDiscoverer` scans `mods/` and classifies each jar by the descriptors it carries:

| Descriptor | Result |
| --- | --- |
| `fabric.mod.json` at the jar root | a Fabric mod |
| `META-INF/mods.toml` | one or more traditional-Forge mods |
| `META-INF/neoforge.mods.toml` | one or more NeoForge mods |
| both kinds present | both identities enter the list (multi-loader jar) |

Everything lands in one `DiscoveredMod` model tagged with `ModEcosystem` (`FABRIC` / `FORGE` /
`NEOFORGE`) and a `UnifiedDependency` list.

- `ModsTomlParser` and its model (`ForgeModsToml`, `ForgeModEntry`, `ForgeDependency`,
  `ForgeMetadataMapper`) are clean-room: written from the published schema. TOML lexing uses
  night-config; the schema interpretation is Forbric's.
- `ForgeVersionRangeTranslator` converts Maven version-range grammar (`[1.2,2.0)`, and the open-ended
  forms) into Fabric `VersionPredicate`s, so both ecosystems' constraints reach the substrate's single
  SAT resolver.
- `FabricModJsonReader` reads the Fabric side into the same shape.

## 5. Forge-family mod preparation

For each Forge-family `DiscoveredMod`, before Knot:

1. **`JarJarTranslator`** — extract JiJ-nested jars into loadable form.
2. **`ModAnnotationScanner`** — ASM scan for `@Mod` classes and their ids. Bytes only; nothing is loaded.
3. **Access Transformers** — `AccessTransformerParser` reads `.cfg` files *and* the AT block declared
   inside a `mods.toml`, producing `AtDirective`s applied later in the `ACCESS` phase.
4. **Remap** — `ForgeModRemapper` drives tiny-remapper with `ForbricMappings` (§6), if the running
   version needs it. On 26.2 with `-Dforbric.runtimeNamespace=named` this is identity and is skipped.
5. **Wrap** — `ForgeModRemapper.wrapAsFabricMod` gives the prepared jar a synthetic `fabric.mod.json` so
   the substrate discovers and Knot-loads it like any other mod.
6. **Cache** — `ForbricCache` keys the result by content hash, so preparation costs are paid once per
   jar, not once per launch.

## 6. The mapping spine

`ForbricMappings` exposes the game in both namespaces at once, built by joining two permitted,
non-MCP sources on their **shared obfuscated column**:

```
Fabric intermediary   official → intermediary
Mojang "Mojmap"       named    → official        (ProGuard format)
                      ─────────────────────────
join on `official`  ⇒  tree keyed by `named`, carrying intermediary
```

`mapClass` / `mapField` / `mapMethod` then translate the names a modern Forge mod references into the
canonical runtime namespace. SRG can be synthesized from the same join for older Forge mods; it is not
bundled.

**No MCP data is ever read.** MCP's name tables are not third-party redistributable, so Forbric ships no
mapping data at all and generates what it needs locally from permitted sources — see
[`forbric-loader/MAPPINGS.md`](forbric-loader/MAPPINGS.md).

Version-dependent behaviour:

- **MC 26.2** is Mojmap-native — the vanilla jar is already deobfuscated. Forbric runs the canonical
  namespace as identity (`-Dforbric.runtimeNamespace=named`): no intermediary, no remap step.
- **MC 1.21.11** (the earlier proof target) is obfuscated. The canonical namespace is `intermediary`,
  and a Forge mod's Mojmap bytecode is remapped into it. The mapping inputs are supplied by the launch
  environment: `-Dforbric.intermediary`, `-Dforbric.mojmap`, `-Dforbric.gameJar`.

## 7. The unified transform pipeline

One transforming class loader hosts every edit either ecosystem wants to make. `TransformPhase` is the
fixed order:

| Phase | Contents |
| --- | --- |
| `RAW_PATCH` | game entrypoint/bootstrap patches injected by the `GameProvider` |
| `DEOBF_REMAP` | remap a mod's bytecode from its source namespace (intermediary or SRG/Mojmap) to the canonical runtime namespace |
| `ENV_STRIP` | strip members annotated for the other physical side (Fabric `@Environment`) |
| `ACCESS` | the unified access model — Fabric Access Wideners **and** Forge Access Transformers |
| `COREMOD` | Forge-style class transformers / coremods, ordered by sort index then topological pre-depends |
| `FABRIC_BUILTIN` | Fabric's built-in transforms (package-access fixes, class tweaks) |
| `MIXIN` | the single Mixin transformer |

`MIXIN` is **terminal and exclusive**: it is applied by the class delegate, which owns the single
`IMixinTransformer`, and no transformer may register into it via the `TransformChain`.
`TransformPhase.LAST_CHAIN_PHASE` is `FABRIC_BUILTIN`.

This replaces Fabric's hardcoded `entrypoint → FabricTransformer → Mixin` sequence and Forge's
separately-owned `IClassTransformer` / `ILaunchPluginService` chain with one ordering that all mods
share. The ordering is load-bearing: Mixin resolves targets by name and descriptor, so remapping and
access widening must both have completed before it looks.

Mixin coexistence problems have three dedicated escape hatches, all opt-in:

- `-Dforbric.suppressMixinConfigs` (patch 0007) — drop a named guest config entirely;
- `-Dforbric.relaxMixinOverwrites` (patches 0007 + 0008) — relax the `@Overwrite` shape check for a
  guest config; `-Dforbric.suppressMixins` (patch 0008) drops individual guest mixin classes;
- `-Dforbric.downgradeInjectionErrors` (patch 0004 + `ForbricMixinDowngrade`) — a guest config whose *required*
  injector matches zero targets on the Forge-patched base throws a fatal `InjectionError`; this
  downgrades it to skipping that class's mixins (it then loads vanilla-shaped) instead of killing the
  client.

`ForbricBootstrap` also carries an explicit set of **Forge-owned guest-mixin targets** — classes and
package prefixes (most of `net/minecraft/client/renderer/**`) where the Forge-patched base diverges
enough that a guest mixin written against vanilla cannot be expected to apply.

## 8. Bringing up the genuine Forge-family runtimes

This is the part with no prior art, and the part most likely to bite you.

`ForbricMinecraftForgeRuntime` and `ForbricNeoForgeRuntime` are the `preLaunch` entrypoints declared by
`forbricruntime`. They stand up the minimum FML pre-loading state that lets Forge's *own*
`ModLoader` / `ServerModLoader` / `ClientModLoader` run under Knot's flat class loader — **without
ModLauncher, BootstrapLauncher or securemodules ever executing.** Everything is reflection-only: the
loader keeps no compile-time Forge dependency, which is what lets an Apache-2.0 tree drive an LGPL
runtime supplied at launch.

**Presence gate.** Each driver probes for its runtime as a *resource*:

```java
if (cl.getResource("net/minecraftforge/fml/loading/FMLLoader.class") == null) { … idle … }
```

Not `Class.forName`, even with `initialize=false` — that would define the class into the unnamed module
and break the invariant below.

**The GAME `ModuleLayer`.** The one piece of JPMS Forge genuinely requires: `FMLModContainer`'s
constructor resolves the mod's named module from the layer (`layer.findModule` + `Class.forName(module,
cn)`), and FML `ServiceLoader.load(layer, …)`s its `IModStateProvider` / `IModLanguageProvider`. So
`ForbricFmlBootstrap.defineGameLayer` derives every Knot-staged Forge jar as an **automatic module** and
defines them into one layer **whose loader is Knot's transforming class loader** — classes keep Knot
identity and Knot's transforms keep applying; they merely also become members of named modules.

> **HARD ORDERING INVARIANT (probe-proven).** The layer must be defined before *any* class from those
> jars is loaded by Knot. A package that already has a class in Knot's unnamed module cannot join a named
> module — `defineModules` throws `LayerInstantiationException("Package … is already in the unnamed
> module")`. That includes Forge's own `UnsafeHacks` and `ModuleLayerHandler`, which is why
> `defineGameLayer` uses pure JDK + Fabric APIs only and all Forge-class reflection is deferred to
> `wireFml`.

`ForbricGameLayer.defineShared` builds **one** layer shared by both drivers. On the tri-in-one merged
base, NeoForge's jars join that same layer and the same `minecraft` module rather than a second one —
JPMS forbids two same-named modules on one class loader. Each driver's `layerJars` stays
family-scoped, because it feeds that family's `ForbricFmlDiscovery`.

**Then, in order:**

1. `seedFmlEnvironment` — set `FMLLoader`'s static `dist` / `production` / `naming` (`mojmap`) and force
   `FMLEnvironment.<clinit>` while they are correct.
2. `wireFml` — synthesize `Launcher.INSTANCE` with its `environment`, `moduleLayerHandler` **and**
   `NameMappingServiceHandler`. That last one matters: `ObfuscationReflectionHelper.findMethod` →
   `FMLLoader.getNameFunction` → `environment().findNameMapping()` NPEs without it, which breaks any
   Forge mod that reflects by name. On Mojmap-native 26.2 an empty name function is correct — the names
   the mod passes already resolve.
3. `ForbricFmlDiscovery` — install a genuine `LoadingModList` from the layer's jars. If no layer could be
   built, a minimal system-mods-only list is installed anyway: Forge code baked into the patched base
   touches `LoadingModList` unconditionally and far from here (`ServerStatusPing` → `ModList` on world
   load), and an uninstalled list permanently poisons its lazy init.

From there the game's own Forge mod-loading path drives normally.

## 9. Class loader topology and the two-jar split

The build produces two jars from one compilation, and the split is required, not packaging taste.

| Jar | Loaded by | Contains |
| --- | --- | --- |
| `forbric-loader-<v>.jar` | the JVM's `-cp`, parent-loaded | the loader core: discovery, metadata, mapping, transformer, access, launch, util — plus the compiled substrate |
| `forbricruntime-<v>.jar` | Knot's transforming class loader | `impl/forge/{minecraftforge,neoforge,mixin,runtime}`, the mixin configs, `src/runtime-meta/fabric.mod.json`, and MixinExtras JiJ-nested under `META-INF/jars/` |

A class that resolves game types must be loaded by Knot, where the patched game classes and the
Knot-loaded Forge-family runtime live. A class in the loader's own code source is parent-loaded and
cannot see them. `build.gradle` expresses this as `runtimeModPackages`: the `jar` task excludes them,
`runtimeJar` includes exactly them.

Two deliberate exceptions:

- `ModAnnotationScanner` stays in the **core** jar. It ASM-scans at prep time, before Knot exists.
- `ForbricLog` stays in the **core** jar and touches only `net.fabricmc.loader.impl.util.log.Log` — a
  package Knot always delegates to the parent — so the Knot-loaded drivers can call it across the
  boundary without dragging game types along.

`src/runtime-meta/fabric.mod.json` declares both `preLaunch` drivers and the mixin configs; each driver
no-ops when its family is absent, so one build covers all cases.

**Delivery.** `forbricruntime` reaches Knot either via `-Dfabric.addMods=…` (the `intermediary-v1`
profile) or by being placed in `gameDir/mods` (the `full-forge-26.2` profile). The full-Forge profile
uses the second because `ForbricBootstrap` gates Forge activation on finding mod id `forge` in
`gameDir/mods`. It is kept out of the *user's* mods folder in the first case so it cannot be deleted by
accident.

## 10. Reconciling the two lifecycles

Fabric and Forge-family content registration each happen inside a window that then closes. Forbric
aligns them rather than reimplementing either.

- **`forbric-bridge`** (`run/bridge-src/`, and `bridge-src-neoforge/` for NeoForge) is Forbric's own
  `@Mod`. Its only job is to open the Fabric-content window from *inside* the genuine Forge-family
  registration span — its `RegisterEvent` handler calls into `ForbricFabricWindow`.
- **`-Dforbric.fabricMainDeferred`** defers Fabric `main` entrypoints into that window. Patch 0005 makes
  `Hooks.startClient` call `ForbricFabricWindow.runClientInitUnlocked(clientInit)`, which unlocks the
  registry wrappers, runs the deferred `main` entrypoints once (guarded by an `AtomicBoolean`, so the
  later bridge `RegisterEvent` is a no-op), runs the `client` entrypoints, and relocks in a `finally`.
  Stock Fabric `main`-before-`client` ordering is preserved; genuine entrypoint failures propagate.

  The reason both stages are inside the unlock: Fabric mods register content in the `client` entrypoint
  too (particles, for example), so unlocking only around `main` moves the crash rather than fixing it.
- **`impl/forge/bridge/`** holds the rest of the cross-ecosystem agreements: pack repository and
  known-packs identity, the registry-sync boundary, client dual lifecycle and shutdown, client model
  data, Fabric channel registration.

## 11. Diagnostics

Driver output goes through `ForbricLog` → fabric-loader's `Log` → log4j, via `GameLogBridge`.

This is not cosmetic. Stock launchers (PCL2, HMCL) do not capture the game process's stdout into
`logs/latest.log` — that file is log4j's output — so `System.out` diagnostics are invisible in the only
log a bug report ever contains. Worse, fabric-loader's default `Log` handler is a *buffer*: it prints
only once an ERROR arrives (replaying what it held) or someone finishes its configuration. A dedicated
server could therefore run to completion having printed not one loader-side warning. `GameLogBridge`
installs the log4j handler that fabric-loader's own launcher would have installed — Forbric is the entry
point here, so nothing else does it — and `Log.init` replays the buffer, so nothing logged earlier is
lost.

- `-Dforbric.debug` — enable `ForbricLog.debug` and forward fabric-loader DEBUG/TRACE.
- `-Dforbric.logDiagnostics` — report on stderr if the log4j routing could not be installed. Anything
  launching the real game should set it; tests and tooling should not.

When triaging a mod under Forbric, the useful order is: does it appear in the unified discovery list →
was it prepared/wrapped → did its module join the GAME layer → did its mixins apply → did its
entrypoint run.

## 12. Repository layout

```
Minecraft-Forbric-mod-loader/
├── README.md                       player-facing
├── introduction.md                 this document
├── LICENSE, NOTICE                 Apache-2.0 + substrate/dependency attribution
├── bootstrap.sh                    clone ../fabric-loader at fabric_loader_ref, apply patches, verify
├── .github/workflows/build.yml     CI: bootstrap + build + upload both jars
│
├── fabric-loader/                  gitignored. Upstream's checkout at the pinned tag. Never edited
│                                   here, never committed
│
├── forbric-loader/
│   ├── README.md                   architecture summary + per-area implementation state
│   ├── CREDITS.md                  clean-room boundary: what was reused vs written from spec
│   ├── MAPPINGS.md                 why no mapping data is shipped, and what is generated locally
│   ├── NOTICE, HEADER, LICENSE
│   ├── build.gradle                source-set composition, the jar/runtimeJar split,
│   │                               generateInstallerManifest
│   ├── gradle.properties           fabric_loader_ref + every dependency version
│   ├── patches/fabric-loader/      the eight substrate patches (§2.2)
│   │
│   ├── src/main/java/net/forbric/loader/impl/
│   │   ├── access/                 AccessTransformer, AccessTransformerParser, AtDirective, AtAccess
│   │   ├── compat/                 ForbricCustomPayloadInterop — cross-ecosystem payload/channel interop
│   │   ├── discovery/              ForbricModDiscoverer
│   │   ├── metadata/               DiscoveredMod, ModEcosystem, UnifiedDependency
│   │   │   ├── fabric/             FabricModJsonReader
│   │   │   └── forge/              ModsTomlParser + model, ForgeVersionRangeTranslator,
│   │   │                           ForgeMetadataMapper
│   │   ├── mapping/                ForbricMappings, ForgeModRemapper, ForbricCache
│   │   ├── transformer/            TransformPhase, TransformChain, TransformContext,
│   │   │                           ClassTransformer, ForbricTransformBridge,
│   │   │                           ForbricMixinDowngrade, ForbricMergedBaseCompatTransformer
│   │   ├── launch/                 ForbricBootstrap, ForbricClient, ForbricServer, ForbricForgeLoader
│   │   ├── util/                   ForbricLog, GameLogBridge, ForbricVersionGate
│   │   └── forge/
│   │       ├── ModAnnotationScanner, JarJarTranslator      (core jar — parent-loaded, pre-Knot)
│   │       ├── minecraftforge/     ForbricMinecraftForgeRuntime, ForbricFmlBootstrap,
│   │       │                       ForbricFmlDiscovery, ForbricFabricWindow      ┐
│   │       ├── neoforge/           ForbricNeoForgeRuntime, ForbricNeoFmlDiscovery,│ forbricruntime
│   │       │                       ForbricNeoFabricWindow                         │ (Knot-loaded)
│   │       ├── mixin/              Forbric's own game mixins + ForbricNeoBridgeMixinPlugin
│   │       ├── runtime/            ForbricGameLayer, ForbricDualLifecycle,       │
│   │       │                       ForbricRegistryBridge, ForbricClientWindow, … ┘
│   │       └── bridge/             ForbricEventBridge, pack/known-pack identity,
│   │                               ForbricFabricPackCompat, registry-sync boundary
│   │
│   ├── src/main/resources/         forbric-loader.mixins.json,
│   │                               forbric-neoforge-bridge.mixins.json
│   ├── src/runtime-meta/           fabric.mod.json for forbricruntime (declares the two preLaunch
│   │                               drivers + the mixin configs)
│   ├── src/stub/                   compile-time stand-ins for the handful of game / Forge types the
│   │                               mixins reference, so the tree builds with no game jar present
│   ├── src/test/                   65 unit tests, plus hand-written stand-ins for game, Forge,
│   │                               NeoForge and fabric-api types the tests exercise
│   ├── src/tools/                  MergedBaseBuilder, MergedLinkChecker, RuntimeInteropPatcher
│   └── run/                        the instance pipeline (§13)
│
└── forbric-installer/              pure-JDK, zero-dependency installer
    ├── src/main/java/net/forbric/installer/
    │   ├── Main, InstallerGui              CLI + Swing shell over the same Installer
    │   ├── Installer                       stage jars into libraries/, write versions/<id>/<id>.json
    │   ├── MojangDownloader                vanilla client acquisition
    │   ├── ForgeArtifacts, ForgeTool       Forge coordinates + running Forge's own fatjar tools
    │   ├── ForgeRuntimeBuilder             merge forge-universal + declared libs → forge-runtime.jar
    │   ├── PatchedMcBuilder                BUNDLER_EXTRACT → mergetool → binarypatcher → AT →
    │   │                                   covariant self() injection
    │   └── Http, Json, Util, Zips, RemoteSource, ArtifactResult
    └── packaging/                          double-click launchers (Windows .bat, macOS .command)
```

## 13. Build, run and test

```bash
./bootstrap.sh                          # clone ../fabric-loader at fabric_loader_ref, apply the
                                        # eight patches, then verify them.  --check verifies only
cd forbric-loader && ./gradlew build    # compile both source trees, run 65 unit tests,
                                        # emit forbric-loader-*.jar + forbricruntime-*.jar
```

JDK 17 or newer. Output is pinned to release 17 (`options.release = 17`) but compiles under whatever JDK
runs Gradle, so no separate toolchain is needed. Dependencies resolve from `maven.fabricmc.net` and
Maven Central. `FABRIC_LOADER_REMOTE` points the substrate clone at a mirror.

`forbric-loader/run/` is the pipeline that produces a runnable instance. Nothing it produces is
committed — patched jars, runtime carriers, instances, logs, worlds are all ignored.

| Script | Produces |
| --- | --- |
| `build-patched-forge.sh` | the MinecraftForge-patched, Mojmap-named MC 26.2 jar (BinaryPatcher + MCPConfig — the Forge analogue of NFRT) |
| `assemble-minecraftforge-runtime.sh` | `forge-runtime.jar` — the Knot-loaded traditional-Forge runtime, merged from Forge's `-universal` jar and its declared libraries. Also builds `forbric-bridge.jar` |
| `assemble-neoforge-runtime.sh` | `neoforge-runtime.jar` |
| `build-merged-base.sh` | `patched-mc-merged-26.2.jar` — vanilla 26.2 carrying **both** families' injections. Runs `src/tools/MergedBaseBuilder`; writes the committed `merged-base/merge-conflicts.txt` report |
| `dedupe-runtime-overlap.sh` | resolves split packages between the two runtime carriers (both bundle their own maven-artifact, terminalconsoleappender, annotation jars; in one JPMS layer that is a split package and `Configuration.resolve()` rejects it) |
| `build-testmods.sh` | compiles the canaries in `livemod-src*/` and `testmod-src/` |
| `launch-server-merged.sh`, `launch-client-merged.sh` | Fabric + MinecraftForge + NeoForge in one process |
| `launch-client-forge-26.2.sh`, `launch-server-minecraftforge.sh` | MinecraftForge-only base |
| `launch-server-26.2.sh` | NeoForge-only base |
| `launch-1.21.11.sh` | the intermediary-namespace path |
| `verify-substrate-patches.sh` | the eight patches still match, diffed against the pinned tag (so it holds whether they are committed or working-tree changes) |
| `regress-real-mods.sh`, `regress-merged-client.sh` | the standing gates: patches, unit tests, headless boot with real third-party mods |

CI (`.github/workflows/build.yml`) runs `bootstrap.sh` then `gradlew build` on every push. Its purpose is
specifically to keep the *bootstrap* honest: because the substrate is fetched rather than vendored, a
moved tag or a patch that stops applying breaks a clean clone, and this is what catches it.

## 14. System properties

Set on the JVM command line, or written into the version profile by the installer.

**Namespace and mapping inputs**

| Property | Effect |
| --- | --- |
| `forbric.runtimeNamespace` | canonical runtime namespace. Default `intermediary`; `named` on Mojmap-native 26.2 |
| `forbric.intermediary` | path to the Fabric intermediary mappings (tiny) for this MC version |
| `forbric.mojmap` | path to the Mojang client mappings (ProGuard) |
| `forbric.gameJar` | path to the obfuscated vanilla jar — remap source in the intermediary branch |
| `forbric.mcVersion` | running MC version; defaults to `26.2` |

**Forge-family selection and bring-up**

| Property | Effect |
| --- | --- |
| `forbric.forgeFamily` | `forge`, `neoforge` or `both`. Overrides probing from the staged runtime jars |
| `forbric.forgeMods`, `forbric.neoforgeMods` | explicit prepared-mod lists for the respective drivers |
| `forbric.neoforgeVersion`, `forbric.neoformVersion` | override the version strings seeded into NeoForge's `FMLLoader.versionInfo` (otherwise read off the staged carrier) |
| `forbric.fabricMainDeferred` | defer Fabric `main` entrypoints into the Forge registration window (§10) |
| `forbric.headlessRegister` | drive registration without a game window — the headless test path |
| `forbric.fmlSmoke` | print a pre-game smoke report of the synthesized FML state |
| `forbric.forgeHandshake` | set to `off` to disable Forge-style network handshake interop |
| `forbric.verifyItems` | post-registration item verification pass |

**Mod and mixin escape hatches**

| Property | Effect |
| --- | --- |
| `forbric.suppressMods`, `forbric.suppressModSources` | hide a mod id / jar from the substrate's discovery (patch 0006) |
| `forbric.suppressMixinConfigs` | drop named guest mixin configs (patch 0007) |
| `forbric.suppressMixins` | drop individual guest mixin classes (patch 0008) |
| `forbric.relaxMixinOverwrites` | relax the `@Overwrite` shape check for a named config (patches 0007 + 0008) |
| `forbric.downgradeInjectionErrors` | turn a fatal zero-target `InjectionError` into a skip for that class (patch 0004) |
| `forbric.versionIncompatibleMods` | populated by `ForbricVersionGate`; readable for diagnostics |

**Diagnostics and shutdown**

| Property | Effect |
| --- | --- |
| `forbric.debug` | verbose driver tracing; forwards fabric-loader DEBUG/TRACE |
| `forbric.logDiagnostics` | report log4j routing failure on stderr |
| `forbric.clientSmoke`, `forbric.clientSmokeWorld`, `forbric.clientSmokeReadyTicks`, `forbric.clientSmokeDisconnectTicks` | the scripted client smoke test: load a world, tick, disconnect |
| `forbric.watcherStopMillis`, `forbric.exitGuardMillis`, `forbric.exitGuardHalt` | shutdown sweep timings |

## 15. Invariants

Break one of these and the failure will usually surface far from the cause.

1. **The GAME `ModuleLayer` is defined before any `net.minecraftforge.*` / `net.neoforged.*` class is
   Knot-loaded.** Probe for the runtime with `getResource`, never `Class.forName`.
2. **One layer, one `minecraft` module**, shared by both drivers. JPMS forbids two same-named modules on
   one class loader.
3. **`MIXIN` stays terminal.** Nothing registers into it through the `TransformChain`.
4. **Anything resolving game types belongs in `runtimeModPackages`**, i.e. in `forbricruntime`. Anything
   running before Knot belongs in the core jar and must touch no game types.
5. **The Forge side stays reflection-only.** No compile-time dependency on Forge from the Apache-2.0
   tree.
6. **The substrate mod id stays `fabricloader`.**
7. **Patches stay in `patches/fabric-loader/` and stay verified.** If you change something in
   `fabric-loader/`, regenerate the patch and re-run `verify-substrate-patches.sh`.
8. **Nothing that carries Mojang, Forge or MCP bytes gets committed.** It is fetched and assembled at
   build or install time. See `CREDITS.md` and `MAPPINGS.md`.

## 16. Current state and known boundaries

- **MC 26.2** is what this tree builds and boots against, in identity namespace mode.
- **MC 1.21.11** was the earlier proof target and is what the intermediary path and `run/README.md`
  describe. A 26.2 build does not run 1.21.11 mods.
- An instance carries **one** Forge-family game base, unless you build the merged base yourself. Both
  drivers are always declared; each idles unless its runtime is staged.
- No compatibility guarantee for arbitrary mods. What has been run is documented in
  [`forbric-loader/README.md`](forbric-loader/README.md), not promised here.
- Version `0.1.0`. No stable API.

## 17. Further reading

- [`forbric-loader/README.md`](forbric-loader/README.md) — per-area implementation state
- [`forbric-loader/run/README.md`](forbric-loader/run/README.md) — the instance pipeline in detail
- [`forbric-installer/README.md`](forbric-installer/README.md) — install modes, the profile contract,
  acquisition order and the trust anchor
- [`forbric-loader/CREDITS.md`](forbric-loader/CREDITS.md) — the clean-room boundary
- [`forbric-loader/MAPPINGS.md`](forbric-loader/MAPPINGS.md) — the mapping position

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.
