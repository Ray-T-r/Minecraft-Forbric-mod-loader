# Forbric Loader

A **clean-room unified Minecraft mod loader** that loads **Fabric** mods (`fabric.mod.json`) and both
**Forge-family** kinds — traditional MinecraftForge (`META-INF/mods.toml`) and NeoForge
(`META-INF/neoforge.mods.toml`) — in one Minecraft 26.2 instance.

- Licensing and provenance: [CREDITS.md](CREDITS.md), [MAPPINGS.md](MAPPINGS.md), [NOTICE](NOTICE)
- Boot harness — launch scripts, patched game-base builders, probe mods: [run/](run/)

## Approach in one paragraph

Forbric is the single process launcher. It reuses the Apache-2.0 **fabric-loader** as its substrate
(Knot class loader, mod discovery + SAT resolver, metadata parsers, Mixin service, game-provider
framework) and adds, on top, a **clean-room Forge side** plus two unifying pieces: one **unified
transform pipeline** (`TransformChain`) hosted in a single transforming class loader with **Mixin fixed
last**, and one **mapping spine** (`ForbricMappings`) that joins Fabric intermediary and Mojang official
("Mojmap") names on their shared obfuscated column, so a Forge mod's bytecode can be moved into whichever
namespace the running game is in. The Forge side is written from public specs — no LGPL FML code is
copied — so the whole project stays Apache-2.0, and **no MCP mapping data is ever bundled**.

## Building

The substrate is a **sibling checkout, not vendored**. `build.gradle` adds
`../fabric-loader/src/main/java`, its `legacyJava` root and `../fabric-loader/minecraft/src/main/java`
to this module's `main` source set, so Gradle compiles upstream's code alongside Forbric's own. This
repository contains none of that source. That is deliberate: with the substrate kept as its own git
checkout at a pinned upstream tag, `git -C fabric-loader diff <tag>` shows exactly what Forbric changed
in upstream's code, and the eight changes Forbric depends on stay reviewable as patches
(`patches/fabric-loader/`) instead of disappearing into a copied tree.

From the repository root:

```bash
./bootstrap.sh                          # fetch ../fabric-loader at the pinned tag, apply patches/
cd forbric-loader && ./gradlew build    # compile + run the unit tests
```

`bootstrap.sh` needs `git` and network access the first time — it clones
`https://github.com/FabricMC/fabric-loader` — and after that only re-checks, so re-running it is safe.
Set `FABRIC_LOADER_REMOTE` to clone from a mirror instead. Which upstream release is used is stated
exactly once, as `fabric_loader_ref` in [gradle.properties](gradle.properties) (currently **0.19.3**):
`bootstrap.sh` clones that tag, and `run/verify-substrate-patches.sh` — which `bootstrap.sh` runs at the
end — diffs the live substrate against that same tag, so the patches cannot silently rot.
`./bootstrap.sh --check` verifies and changes nothing.

`./gradlew build` writes two jars into `build/libs/`:

- `forbric-loader-<version>.jar` — the loader core. Parent-loaded, on the JVM's `-cp`.
- `forbricruntime-<version>.jar` — the Knot-loaded half (see the architecture note below), with
  MixinExtras JiJ-nested inside it under `META-INF/jars/`.

Requires JDK 17 or newer; the output is pinned to Java 17 bytecode.

## What is in the tree

| Area | State |
|---|---|
| **Unified transform pipeline** — `TransformPhase`, `ClassTransformer`, `TransformContext`, `TransformChain`, entered from the substrate through the one-line `FabricTransformer` → `ForbricTransformBridge` seam (patch `0002`) | implemented + unit-tested |
| **Unified mod discovery** — `ForbricModDiscoverer` reads `fabric.mod.json`, `META-INF/mods.toml` and `META-INF/neoforge.mods.toml` into one `DiscoveredMod` model (`ModEcosystem` = FABRIC / FORGE / NEOFORGE); `ForgeVersionRangeTranslator` turns Maven ranges into Fabric version predicates | implemented + unit-tested |
| **Clean-room `mods.toml` parser** — `ModsTomlParser` + model (`ForgeModsToml`, `ForgeModEntry`, `ForgeDependency`, `ForgeMetadataMapper`) | implemented + unit-tested |
| **Access Transformers** — clean-room `.cfg` parser → `AccessTransformer` in the `ACCESS` phase, including the AT block declared inside a `mods.toml` | implemented + unit-tested |
| **Mapping spine** — `ForbricMappings` joins Fabric intermediary + Mojang official on the shared obfuscated column (no MCP data); `ForgeModRemapper` drives tiny-remapper with it; `ForbricCache` keys the result by content hash | implemented + unit-tested |
| **Mod preparation** — `ModAnnotationScanner` (ASM `@Mod` discovery, parent-loaded, before Knot), `JarJarTranslator`, and `ForbricForgeLoader` / `ForbricBootstrap`, which wrap prepared Forge-family jars as Fabric mods and hand them to the substrate via `fabric.addMods` | implemented, partly unit-tested |
| **Forge-family runtime drivers** — `ForbricMinecraftForgeRuntime` and `ForbricNeoForgeRuntime` bring up the **real** MinecraftForge / NeoForge runtimes under Knot without running FML's own ModLauncher startup (which would build a module layer and a second transforming class loader). Reflection-only, so the loader keeps no compile-time Forge dependency. They are the `preLaunch` entrypoints declared by `forbricruntime` | implemented |
| **Cross-ecosystem bridges and game mixins** — `impl/forge/bridge` + `impl/forge/mixin`: pack repository and known-packs identity, the registry-sync boundary, client dual lifecycle and shutdown, client model data, Fabric channel registration | implemented, partly unit-tested |
| **Entry points** — `ForbricClient` / `ForbricServer`, used as the launch `mainClass` in place of Knot's own | implemented |

**65 unit tests** run under `./gradlew test`.

## Architecture note (load-bearing)

The game-touching half of Forbric ships as a **separate, Knot-loaded module** (`forbricruntime`),
distinct from the parent-loaded loader core. This is required: a class that resolves game types has to be
loaded by Knot's transforming class loader, where the patched game classes and the Knot-loaded
Forge-family runtime live — a class in the loader's own code source is parent-loaded and cannot see them.
`build.gradle` produces the split from one compilation: `jar` excludes
`impl/forge/{minecraftforge,neoforge,mixin,runtime}` and the mixin configs, and `runtimeJar` packages
exactly those, plus `src/runtime-meta/fabric.mod.json`, which declares the two `preLaunch` drivers and
the mixin configs. Each driver no-ops if its Forge family is not present at runtime, so one build covers
both. `ModAnnotationScanner` deliberately stays in the core jar: it ASM-scans `@Mod` classes at prep
time, before Knot exists.

## Target

- **Minecraft 26.2** is what this tree builds and boots against: the substrate's 26.2 dedicated-server
  entrypoint patch (`0001`), the launch scripts in `run/`, and the patched / merged game-base builders
  (`run/build-patched-forge.sh`, `run/build-merged-base.sh`) all target it. 26.2 is Mojmap-native — the
  vanilla jar is already deobfuscated — so Forbric runs the canonical namespace as identity there
  (`-Dforbric.runtimeNamespace=named`): no intermediary, no remap.
- **Minecraft 1.21.11** was the earlier proof target and is what `run/README.md` and the mapping spine
  describe. On an obfuscated version the canonical runtime namespace is **intermediary**, and a Forge
  mod's Mojmap bytecode is remapped into it by `ForgeModRemapper`.

## Scope notes

- The MinecraftForge and NeoForge runtimes, and the patched or merged Minecraft bases Forbric loads, are
  **supplied at runtime and never committed here**. The scripts under `run/` fetch them from upstream
  Maven and assemble them locally; the repository ignores `*.jar`. Only Forbric's own clean-room bytecode
  is distributed.
- Forbric does not run FML's own startup path. The drivers seed the FML environment reflectively and let
  the game's own client mod-loading flow do the rest, because ModLauncher's module layer and transforming
  class loader would fight Knot.
