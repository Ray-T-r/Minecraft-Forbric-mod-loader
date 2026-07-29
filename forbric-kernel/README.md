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

Full plan: `~/.claude/plans/fabric-neo-forge-mod-eager-rabbit.md`.

## Architecture (two sides, no JPMS module layer)

- **BOOT side** (system classloader, `forbric-kernel.jar`): classloading, discovery, dependency resolution,
  the transform pipeline, the Mixin service, mapping, access, metadata, the vendored `net.fabricmc.api.*`
  surface. No game types.
- **GAME side** (`ForbricClassLoader`, the one transforming CL, `forbric-kernel-runtime.jar`): everything that
  references `net.minecraft.*` / `net.minecraftforge.*` / `net.neoforged.*` / `net.fabricmc.fabric.*` —
  registry/lifecycle/event/network/resource machinery — typed against the staged jars, JiJ-nesting MixinExtras.

The old "Knot classloader split" law survives as the kernel's own boot↔game split; boot→game crosses through
`net.forbric.kernel.api.KernelHooks`, injected bytecode calls `net.forbric.kernel.runtime.Hooks`.

## Shared assets (kept, not rewritten)

The merged-base pipeline stays in `../forbric-loader`: `src/tools/{MergedBaseBuilder,MergedLinkChecker,
RuntimeInteropPatcher}` + `run/{build-merged-base,assemble-*-runtime}.sh` produce the 3-ABI game jar and the
passive runtime jars. The kernel consumes them (compileOnly for typing, runtime-supplied at launch). LGPL /
Mojang-derived artifacts are never bundled.

## Milestones

| | status | gate |
|---|---|---|
| **M0** scaffold + ported libs + oracle | ✅ green | `run/gate-m0.sh` |
| **M1** merged base boots to Done (zero mods) | ✅ green — server Done, ticks, clean shutdown, zero genuine lifecycle | `run/gate-m1.sh` |
| M2 Fabric ecosystem native | — | `run/gate-m2.sh` |
| **M3** Forge-family native lifecycle + real @Mod | ✅ green (server) — both baselines (ForgeMod + NeoForgeMod) + real @Mod constructed natively; tick/event-bus → M4 | `run/gate-m3.sh` |
| M4 tri-in-one server | — | `run/gate-m4.sh` |
| M5 merged client to title | — | `run/gate-m5.sh` |
| M6 world join (beat the old system) | — | `run/gate-m6.sh` |
| M7 mixin adapter (category C) | — | `run/gate-m7.sh` |
| M8 installer + cutover | — | `run/gate-m8.sh` |

The old `forbric-loader` is kept runnable as the **differential oracle**: on the same mod set and merged base,
wherever it reaches, the kernel must reach — and from M6 on, further (world join).

## Build & run

```sh
./gradlew --offline test          # ported unit tests (transform/access/mapping/metadata/discovery)
./gradlew --offline jar           # boot jar
./run/gate-m0.sh                  # M0 gate: build + tests + scan + differential oracle
# unified discovery over a mods/ dir → deterministic JSON (the oracle anchor):
java -cp <boot-cp> net.forbric.kernel.boot.Main --scan --mods <dir> --report out.json
```

Build targets Java 21 bytecode (`options.release = 21`); the game itself needs Java 25. Deps come from
maven.fabricmc.net + Maven Central.

## License

Apache-2.0 (`LICENSE`). Attribution for vendored Fabric Loader sources and the clean-room stance on
FML/FancyModLoader: `NOTICE`.
