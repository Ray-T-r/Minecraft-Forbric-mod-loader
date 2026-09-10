# Forbric

Forbric is a Minecraft mod loader that loads **Fabric** mods (`fabric.mod.json`) and **Forge-family**
mods (`mods.toml`) in one game instance, on Minecraft 26.2. It reuses the Apache-2.0
[fabric-loader](https://github.com/FabricMC/fabric-loader) as its substrate — class loading, mod
discovery and dependency solving, metadata parsing, the Mixin service, the game-provider framework —
and adds a clean-room Forge side plus the pieces that make the two coexist: one transform pipeline with
Mixin fixed last, and one mapping spine so both ecosystems' mods resolve against the same runtime
namespace.

This is a research loader. It is published so the approach and its provenance can be inspected, not as a
product you install and play. See [Status](#status) before you spend time on it.

## Layout

```
forbric-loader/       the loader. Forbric's own code lives under net.forbric.*; the reused
                      substrate is compiled in from ../fabric-loader (see below).
forbric-installer/    a standalone, pure-JDK installer that stages a built loader into a
                      Minecraft launcher (PCL2 / HMCL): it writes a version profile and lays
                      the jars into the launcher's libraries/ tree.
bootstrap.sh          clones the substrate and applies forbric-loader/patches/fabric-loader/.
fabric-loader/        created by bootstrap.sh. Upstream's checkout — gitignored, never edited
                      here, and never committed.
```

The substrate is deliberately **not vendored**. `forbric-loader/build.gradle` adds the sibling checkout's
source roots to its own `main` source set, so a built jar legitimately contains compiled FabricMC code —
but the code itself stays in an upstream checkout at an upstream tag, with Forbric's changes to it expressed
as eight reviewable patches under `forbric-loader/patches/fabric-loader/`. That is what keeps the
clean-room boundary auditable: you can see exactly what was reused, and exactly what was changed.

Which upstream release is pinned once, as `fabric_loader_ref` in `forbric-loader/gradle.properties`.
`bootstrap.sh` clones that ref, and `forbric-loader/run/verify-substrate-patches.sh` diffs the live
checkout against it and fails if a patch has drifted or been reverted.

## Quick start

Requirements: **git**, and a **JDK 17 or newer**. The build pins its output to release 17
(`options.release = 17`) but compiles with whatever JDK runs Gradle, so a newer JDK is fine and no
separate JDK 17 toolchain is needed. The Gradle wrapper is checked in; the build resolves dependencies
from `maven.fabricmc.net` and Maven Central.

```bash
git clone https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader.git
cd Minecraft-Forbric-mod-loader
./bootstrap.sh
cd forbric-loader && ./gradlew build
```

`./bootstrap.sh` clones (or updates) `fabric-loader/`, applies the patches — re-running it is a no-op if
they are already applied — and then verifies them. `./bootstrap.sh --check` verifies without changing
anything. If `github.com` is slow for you, point the clone at a mirror:

```bash
FABRIC_LOADER_REMOTE=https://gitee.com/mirrors/fabric-loader.git ./bootstrap.sh
```

`./gradlew build` compiles both source trees and runs the loader's unit tests (65 at the time of
writing), and produces two jars in `forbric-loader/build/libs/`: the parent-loaded loader core, and
`forbricruntime`, the half that must be loaded by Knot's transforming class loader. The split is
load-bearing, not packaging taste — `forbric-loader/build.gradle` explains why at the `runtimeJar` task.

## Status

What this repository is known to do: build, from a clean clone, with the command above, and pass its unit
tests. `forbric-loader/run/` holds the development harnesses used to boot and regression-test the loader
against a real game.

What it does **not** do, and what you should not expect:

- **There is no downloadable, ready-to-play release here.** Getting a playable instance means building,
  assembling a Forge-family runtime locally, and staging it — the `run/*.sh` scripts do this, but they
  assume a local Minecraft installation and a specific working layout. They are development tools.
- **No game files, Forge-family runtime jars, or mapping data are in this repository.** Forge and NeoForge
  are LGPL; their runtimes are fetched and assembled at build/run time by `forbric-loader/run/assemble-*.sh`
  and supplied to the loader, never committed into this Apache-2.0 tree. No MCP mapping data is bundled —
  see [forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md).
- **No compatibility guarantee for arbitrary mods.** Both loader ecosystems are large; what has actually
  been run is what has actually been tested, and that is documented in
  [forbric-loader/README.md](forbric-loader/README.md), not promised here.
- **No stable API, no support, no roadmap commitment.** The version is `0.1.0-SNAPSHOT`. Internals move.
- An instance carries exactly one Forge-family game base. Both drivers (traditional MinecraftForge and
  NeoForge) are declared; each idles unless its runtime is present.

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.

## Licensing and provenance

Forbric is licensed under the Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). Two points
are worth stating up front, because the project is arranged around them:

- **The Forge side is clean-room.** It was written from public formats and specifications — the `mods.toml`
  schema, the Maven version-range grammar, the modern event-bus and registry contracts. No FML source is
  copied. FML is LGPL; keeping it out is what lets the whole of Forbric stay Apache-2.0.
  See [forbric-loader/CREDITS.md](forbric-loader/CREDITS.md).
- **No MCP mapping data is bundled.** MCP's name tables are not redistributable by third parties, so
  Forbric ships no mapping data at all and generates what it needs locally from permitted sources.
  See [forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md).

The full substrate attribution and the bundled third-party dependency list are in
[forbric-loader/NOTICE](forbric-loader/NOTICE).
