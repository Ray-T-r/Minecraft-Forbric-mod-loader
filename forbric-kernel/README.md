# Forbric Kernel

A **sovereign** unified Minecraft mod loader that runs **Fabric + traditional MinecraftForge + NeoForge**
mods on one Minecraft 26.2 instance — a ground-up rewrite of the `forbric-loader` "weld".

## Why a rewrite

`../forbric-loader` reached tri-in-one by *welding three genuine sovereign loaders together*: real
fabric-loader/Knot as host (8 substrate patches), real FML and FancyModLoader reflectively impersonated and
driven side-by-side, over a byte-merged 3-ABI game base. It works, but a 38-wall root-cause census showed
**~53% of the conflicts are direct artifacts of that architecture** (lifecycle-welding + multi-pipeline
byte-merge). This kernel deletes the weld: **one** transforming classloader, **one** lifecycle state machine,
**one** registry/tag model — with the three ecosystems reduced to adapters over kernel-owned services. No
genuine loader lifecycle ever boots; the Forge/NeoForge universal jars are passive ABI carriers only.

The two wall categories a rewrite does *not* eliminate — guest-mixin/ABI mismatch against the merged base (C),
and cross-ecosystem registry/tag/resource/network semantics (D) — become first-class kernel workstreams
(the mixin adapter layer; the single-freeze registry model that makes "Tags not bound" structurally impossible).

The milestone plan this was built against is not in the repository; what it asserted is, as the gate
scripts under `run/` — one per milestone, each asserting on the real logs of a real instance.

## Architecture (two sides, no JPMS module layer)

- **BOOT side** (system classloader, `forbric-kernel.jar`): classloading, discovery, dependency resolution,
  the transform pipeline, the Mixin service, mapping, access, metadata, the vendored `net.fabricmc.api.*`
  surface. No game types.
- **GAME side** (`ForbricClassLoader`, the one transforming CL, `forbric-kernel-runtime.jar`): everything that
  references `net.minecraft.*` / `net.minecraftforge.*` / `net.neoforged.*` / `net.fabricmc.fabric.*` —
  registry/lifecycle/event/network/resource machinery — typed against the staged jars, JiJ-nesting MixinExtras.

The old "Knot classloader split" law survives as the kernel's own boot↔game split.

**Status of the game side:** filled. `src/runtime/java` is 39 files and about 6000 lines, compiled against the
staged jars and shipped as `forbric-kernel-runtime.jar` — registry and lifecycle drivers, both families' setup
phases, the condition evaluators, the pack sources, the unified Mods screen. An earlier revision of this file
said it was empty, which was true when it was written and stopped being true without the sentence changing.

Injected bytecode still calls some boot-side statics directly, and a few classes are still synthesized at
runtime with ASM by boot-side factories (`KernelModContainerFactory`, `KernelHudBridge`, `KernelGameLookup`) —
those are the cases that cannot be compiled at all, and each says why where it lives.

## The unified API (`net.forbric.api`)

Being grown domain by domain: the vocabulary and services the kernel and all three compatibility layers align to,
instead of accommodating each other pairwise. Parent-pinned in `DelegationPolicy` for the same reason
`net.fabricmc.api.` is — exactly one copy per JVM.

| type | what it replaced |
|---|---|
| `Ecosystem` | five different ecosystem enums (two with different spellings, one missing NeoForge, one dead) plus a hand-written translator |
| `ForeignType` | pairs of adjacent Forge/NeoForge class-name literals at each call site |
| `DiscoveredMod`, `UnifiedDependency` | moved here from `kernel.metadata`; the one mod model |
| `ModPresence` | `boot.KernelForeignMods`; the one answer to "is mod X running", which injected bytecode now calls as `net/forbric/api/ModPresence.isLoaded` |
| `ModCatalog` | the one list a player sees, and what became of each mod — `OK`, `DEGRADED` or `FAILED` |

Two rules it is built on, both learned the hard way:

- **The hub carries per-family divergence as data; it does not average it away.** An earlier unified subscriber
  registration broke three things at once (see `KernelEventSubscribers`' own javadoc). So `ForeignType` maps
  *names* only — the two `ServerModLoader.load` triggers keep their different descriptors and different hooks.
- **Not every grouping is a missing split.** `LoaderProbePolicy.Family` stays two-valued on purpose: a NeoForge
  mod probing for MinecraftForge's `FMLLoader` must still be told yes.

## Shared assets (kept, not rewritten)

The merged-base pipeline stays in `../forbric-loader`: `src/tools/{MergedBaseBuilder,MergedLinkChecker,
RuntimeInteropPatcher}` + `run/{build-merged-base,assemble-*-runtime}.sh` produce the 3-ABI game jar and the
passive runtime jars. The kernel consumes them (compileOnly for typing, runtime-supplied at launch). LGPL /
Mojang-derived artifacts are never bundled.

## Milestones

The gate scripts under `run/` assert on real logs and artifacts from a real instance. The milestone table
that used to be here listed `gate-m5.sh` and `gate-m6.sh`, which have never existed, and recorded M2 and M4
onwards as unfinished long after their gates were passing — so the scripts themselves are the list now:

| gate | what it proves |
|---|---|
| `gate-m0` | build + the whole unit suite (asserted from the JUnit XML, not gradle's exit code) + discovery vs an independent parser |
| `gate-m1` / `gate-m3` | merged base boots to Done with zero mods; both Forge-family baselines + a real `@Mod`, natively |
| `gate-m2` / `gate-m2b` | a real Fabric mod, then full fabric-api, with no Fabric Loader anywhere |
| `gate-m4` / `gate-m4-canary` / `gate-m7-neo` | real third-party mods of all three families in one server; pure NeoForge |
| `gate-m9-client` | the client half: a 97-jar pack into a world, the unified Mods screen, a clean exit |
| `gate-m12` … `gate-m16` | multiplayer over a real socket, an anti-cheat's opinion, a pure Fabric server, both Forge families' networking |
| `gate-m17` | the installer, resolved and launched the way a launcher does it |
| `gate-m24` | a mod that fails on purpose: the others still load and the failure is attributed |
| `gate-m25-worldgen` | biome modifier canaries leave distinct blocks in saved regions; the Forge half starts as expected red |
| `gate-m26-forgeclient` | the Forge client receives key, renderer and creative-tab registration events; starts as expected red |
| `gate-m27-frame` | the 97-jar client produces a fresh, non-black Minecraft screenshot |
| `gate-m28-forgeconfig` | Forge COMMON configs load once, then its native watcher reads a live file edit; dedicated servers never open CLIENT configs |

`run/compat/gates-all.sh` discovers and runs every gate in numerical order, including network/GUI gates;
an intentional `--skip <script.sh>` is printed in the results. Portable Windows baseline collection and
the negative controls are described in [the compatibility protocol](run/compat/PROTOCOL.md).

The rest (`m8`, `m10`, `m11`, `m18`–`m23`) each pin one previously-shipped defect. Sixteen of the twenty-five
had not been run for a day when that was last measured, and one of them had been red the whole time — which is
why `gate-m0` now refuses to report on a test task that did not execute.

The old `forbric-loader` is kept runnable as the **differential oracle**, and still builds the shared game
artifacts the kernel consumes.

## Build & run

```sh
./gradlew --offline test          # the unit suite; ~a third of it reads the staged game jars and
                                  # SKIPS without them, which is why gate-m0 asserts on the results
./gradlew --offline jar           # boot jar
./run/gate-m0.sh                  # build + the suite (tests>0, no failures, skip ceiling) + the oracle
# unified discovery over a mods/ dir → deterministic JSON (the oracle anchor):
java -cp <boot-cp> net.forbric.kernel.boot.Main --scan --mods <dir> --report out.json
```

Build targets Java 21 bytecode (`options.release = 21`); the game itself needs Java 25. Deps come from
maven.fabricmc.net + Maven Central.

## License

Apache-2.0 (`LICENSE`). Attribution for vendored Fabric Loader sources and the clean-room stance on
FML/FancyModLoader: `NOTICE`.
