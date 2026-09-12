# Forbric Loader

A **clean-room unified Minecraft mod loader** that natively loads both **Fabric** mods
(`fabric.mod.json`) and modern **Forge** mods (`mods.toml`) in one Minecraft 26.2 instance.

- Architecture and rationale: [introduction.md](../introduction.md)
- Licensing & provenance: [CREDITS.md](CREDITS.md), [MAPPINGS.md](MAPPINGS.md), [NOTICE](NOTICE)

## Approach in one paragraph

Forbric is the single process launcher. It reuses the Apache-2.0 **fabric-loader** as its substrate
(Knot class loader, mod discovery + SAT resolver, metadata parsers, Mixin service, game-provider
framework) and adds, on top, a **clean-room Forge side** plus two unifying pieces: one **unified
transform pipeline** (`TransformChain`) hosted in a single transforming class loader with **Mixin
fixed last**, and one **mapping spine** that exposes the game in both the intermediary (Fabric) and
SRG/Mojmap (Forge) namespaces and remaps every mod to one canonical runtime namespace
(default: **intermediary**). The Forge side is written from public specs — no LGPL FML code is
copied — so the whole project stays Apache-2.0, and **no MCP mapping data is ever bundled**.

## Status

| Area | State |
|---|---|
| Module scaffold (Gradle, Java 17) | ✅ |
| Licensing files (LICENSE / NOTICE / CREDITS / MAPPINGS) | ✅ designed-in |
| Kept-as-is Fabric mod API (`net.fabricmc.api.*`) | ✅ copied verbatim (mods link against it) |
| **Unified transform pipeline** SPI — `TransformPhase`, `ClassTransformer`, `TransformContext`, `TransformChain` | ✅ implemented + unit-tested |
| **Clean-room `mods.toml` parser** — `ModsTomlParser` + model | ✅ implemented + unit-tested |
| **Unified mod discovery** — `ForbricModDiscoverer` detects `fabric.mod.json` AND `mods.toml` → one `DiscoveredMod` model; `ForgeVersionRangeTranslator` (Maven → Fabric predicates) | ✅ implemented + unit-tested |
| **Substrate integration (P0)** — reused fabric-loader core + Minecraft game provider compiled into the module under `net.fabricmc.*` (Apache, disclosed) | ✅ compiles as one unit (328 classes) |
| **First end-to-end boot (P1)** — `ForbricClient` boots real **MC 1.21.11** to the title screen, loads a real Fabric mod (`ModInitializer` fired), Mixin active, and reports the Forge mod via unified discovery | ✅ verified — see [run/](run/) |
| **Mapping spine (P3)** — `ForbricMappings` joins Fabric intermediary + Mojang Mojmap on the shared obfuscated column to translate a Forge mod's Mojmap names → the runtime intermediary namespace (no MCP data) | ✅ implemented + unit-tested + verified on real 1.21.11 data |
| **Forge mod loading (P4)** — `ForgeModRemapper` remaps a Forge mod's Mojmap bytecode → intermediary (tiny-remapper), wraps it as a Fabric mod; a **Forge mod and a Fabric mod load and run together in one MC 1.21.11 instance** | ✅ verified end-to-end — see [run/p4-forge-coexistence.log](run/p4-forge-coexistence.log) |
| **Forge lifecycle / event bus (P6)** — `ForbricEventBus` + `ForgeModLifecycle` + `ForgeModInitShim` construct a NeoForge-style `@Mod` class with an event bus and fire `FMLCommonSetupEvent` during init; a `@SubscribeEvent` handler receives it | ✅ verified end-to-end — see [run/p6-forge-lifecycle.log](run/p6-forge-lifecycle.log) |
| **Forge mod registers GAME-VISIBLE content (P7)** — `RegisterEvent` + `DeferredRegister` + a registry-window mixin (`@At("HEAD")` of `BuiltInRegistries.bootStrap`) construct + register a Forge mod's block while the registries are open | ✅ verified end-to-end — a `DeferredRegister<Block>` Forge mod registered `forbricdefer:example_block` and the **game's own model loader processed it**, beside a running Fabric mod. See [run/p7b-registration.log](run/p7b-registration.log) |
| **Access Transformers (P5)** — clean-room `.cfg` parser → `AccessTransformer` in the `ACCESS` phase, wired into the game load path via a 1-line `FabricTransformer` → `ForbricTransformBridge` seam | ✅ implemented + unit-tested |
| **Enriched event bus + global bus (W2)** — `EventPriority`, `ICancellableEvent`, static `@SubscribeEvent`, `NeoForge.EVENT_BUS` | ✅ implemented + unit-tested |
| **Automation (W4)** — `ModAnnotationScanner` (ASM `@Mod` discovery), `ForbricCache` (content-hash cache), multi-`@Mod`/mixin-config jar wrapping, `ForbricForgeLoader` + `ForbricBootstrap` auto-remap+wrap+`fabric.addMods` | ✅ implemented + unit-tested |

**47 unit tests pass** (`./gradlew test`). **Headline result (real MC 1.21.11 boot):** a Forge/NeoForge mod using `DeferredRegister<Block>` registered `forbricdefer:example_block` into the live `BuiltInRegistries.BLOCK` during Forbric's registration window, and Minecraft's own model loader then processed it (`Missing model for variant: 'Block{forbricdefer:example_block}'`) — game-visible content from a Forge mod, running beside a Fabric mod, on the Fabric substrate.

## Architecture note (load-bearing)
The NeoForge API + registration bridge + game mixins ship as a **separate, Knot-loaded module** (`forbricruntime`),
distinct from the parent-loaded loader core. This is required: classes that reference intermediary game types
(`class_2378`, …) must be loaded by Knot's transforming classloader, where those remapped classes exist — a class
in the loader's own code source is parent-loaded and cannot see them. `@Mod` classes are *queued* at preLaunch
(loaded without initialization) and *constructed* inside the registration-window mixin, because
`BuiltInRegistries` cannot initialize until the game's bootstrap is underway, yet registration must happen while
the window is open. The boot harness (`run/` scripts) builds this split; encoding it as a Gradle two-jar task is the
remaining productionization.

## Next steps
- **Gradle two-jar split** — produce `forbricruntime.jar` (NeoForge API + bridge + mixins) + the loader core jar
  from the build, so the loader runs without the boot-harness repackaging.
- **Broaden the game-event bridge (W2)** — more `NeoForge.EVENT_BUS` events (tick/block/entity/player) via the
  same mixin-post pattern, and `@Mod` auto-registration / `@EventBusSubscriber`.
- **More NeoForge surface** — config (`ModConfigSpec`), networking (`PayloadRegistrar`), capabilities (deferred
  stubs today, fail-loud when invoked).

## Target

- **Development / proof target: Minecraft 1.21.11** — it has published Fabric intermediary and real
  Fabric (and Forge) mods, so we can demonstrate real mods of both kinds coexisting. The user's existing
  `1.21.11` + `1.21.11-Fabric` installs (fabric-loader 0.18.4, intermediary 1.21.11) are the boot base.
- **Final target: Minecraft 26.2** — real (June 2026) but its mod ecosystem isn't published yet
  (no intermediary / Yarn / Forge for 26.2 as of 2026-06-28). The loader is built version-parametric and
  forward-ports to 26.2 once those land. On 1.21.11 the canonical runtime namespace is **intermediary**.

## Build & test

Requires JDK 17+ (a JDK 17 toolchain is selected by Gradle).

```bash
./gradlew test
```

> The two new components depend only on `night-config` (TOML) and JUnit, so they can also be compiled
> and tested with a minimal `javac` classpath — see the verification used during development.

## Next increment — P0 substrate repackage

Copy fabric-loader's loader sources into this module and repackage **only the internals**, keeping the
mod-facing API package names so existing Fabric mods still link:

- **Rename** `net.fabricmc.loader.impl.*` → `net.forbric.loader.impl.*` (across *all* files, so api→impl
  references update too), and rewrite the same prefix inside the Mixin service descriptors under
  `src/main/resources/META-INF/services/`.
- **Keep unchanged**: `net.fabricmc.api.*` and `net.fabricmc.loader.api.*` (the public, mod-facing API),
  and the `net/fabricmc/loader/Messages*.properties` resource bundle path.
- Wire `ForbricClassDelegate` (the adapted `KnotClassDelegate`) so its pre-Mixin byte path calls
  `TransformChain.applyBeforeMixin(...)` and its post-Mixin path runs the single Mixin transformer.

See the reuse map in the plan for the per-file reuse / adapt / reference classification.
