# `run/` — the pipeline that produces a runnable Forbric instance

Forbric's loader is Apache-2.0 source. What it runs on top of is not: the Minecraft jar belongs to Mojang,
and the MinecraftForge and NeoForge runtimes are LGPL. None of that is committed here. This directory holds
the scripts that **fetch and assemble those pieces on your machine**, plus the launchers that put them
together, plus the sources of the small test mods used to check that the result actually works.

Nothing in this directory is committed except the scripts, those mod sources, and one report. Everything the
scripts produce — patched game jars, runtime carriers, game instances, logs, worlds — is deliberately
ignored (see `../.gitignore`).

## Build pipeline

Run in this order. Each writes into `run/` and is idempotent.

| Script | Produces |
|---|---|
| `build-patched-forge.sh` | the traditional-MinecraftForge-patched, Mojmap-named MC 26.2 jar. Forge patches the game with BinaryPatcher + MCPConfig, not NeoForm, so this is the Forge analogue of NFRT. |
| `assemble-minecraftforge-runtime.sh` | `forge-runtime.jar` — the Knot-loaded traditional-Forge runtime, merged from Forge's `-universal` jar and its declared libraries. |
| `assemble-neoforge-runtime.sh` | `neoforge-runtime.jar` — the same for NeoForge. |
| `build-merged-base.sh` | `patched-mc-merged-26.2.jar` — vanilla 26.2 carrying **both** the MinecraftForge and the NeoForge injections in one jar, which is what lets all three ecosystems load in one instance. Compiles and runs `src/tools/MergedBaseBuilder`. |
| `dedupe-runtime-overlap.sh` | resolves split packages between the two runtime carriers. Both bundle their own copies of third-party classes (maven-artifact, terminalconsoleappender, annotation jars); in one shared JPMS layer that is a split package, and `Configuration.resolve()` rejects it outright. |
| `build-testmods.sh` | compiles the canary mods from `livemod-src/` and `livemod-src-neoforge/` against the runtime-supplied Forge and patched MC. |

`merged-base/merge-conflicts.txt` is the report `build-merged-base.sh` writes: every class where the two
Forge families' injections collided, and which side won. It is the one build output that **is** committed,
because which way each conflict resolved is a design decision worth reviewing rather than re-deriving.

## Launchers

| Script | Instance |
|---|---|
| `launch-server-merged.sh` | dedicated server on the merged base, both runtimes staged — Fabric + MinecraftForge + NeoForge in one process. |
| `launch-client-merged.sh` | the client equivalent, with both bridge mods staged. |
| `launch-server-minecraftforge.sh` / `launch-client-forge-26.2.sh` | MC 26.2 on the MinecraftForge-patched base only. |
| `launch-server-26.2.sh` | MC 26.2 on the NeoForge-patched base only. |
| `launch-1.21.11.sh` | the older 1.21.11 path, which runs on the Fabric intermediary namespace rather than Mojmap. |

MC 26.2 ships deobfuscated, so on 26.2 Forbric runs in identity mode — no intermediary remap. The 1.21.11
launcher is the one that still needs an `intermediary` mapping and a Fabric profile for LWJGL natives.

Each launcher reads your Minecraft directory from `$MC_DIR`, defaulting to the platform's usual location.

## Mod sources

- `bridge-src/`, `bridge-src-neoforge/` — Forbric's own bridge mods. Each opens the Fabric-content window
  inside the genuine Forge-family registration span, so Fabric-registered content lands in the same window
  Forge mods register in.
- `livemod-src/`, `livemod-src-neoforge/` — canaries. They exercise a traditional-Forge `SimpleChannel`, a
  server config that must sync over the socket, gameplay events, and cross-ecosystem mod-presence lookups,
  and log a line per outcome. Built by `build-testmods.sh`.
- `testmod-src/` — minimal Fabric and Forge probe mods, used to check that discovery classifies each one
  correctly and that a remapped Forge class links against the live game.

## Checks

- `verify-substrate-patches.sh` — the eight required `../fabric-loader` patches still match
  `patches/fabric-loader/*.patch`. Compared against the pinned upstream release, so it passes whether you
  left them as working-tree changes or committed them. `../../bootstrap.sh` runs it for you.
- `regress-real-mods.sh` — the standing regression gate: substrate patches, unit tests, a headless
  registration baseline, and a real server boot with real third-party mods.
- `regress-merged-client.sh` — quick-plays the merged client into a fixed world and disconnects cleanly,
  covering the protocol / known-pack / registry-sync path that only shows up on the client.

## Running the pipeline

```bash
cd ..                    # the repository root
./bootstrap.sh           # fetch and patch the fabric-loader substrate
cd forbric-loader
./gradlew jar runtimeJar # the two loader jars
run/build-patched-forge.sh
run/assemble-minecraftforge-runtime.sh
# ... then a launcher
```

These scripts assume a working JDK, `git`, and network access to Mojang's and Forge's Maven repositories.
They are development tools, not an installer — for that, see `../../forbric-installer/`.
