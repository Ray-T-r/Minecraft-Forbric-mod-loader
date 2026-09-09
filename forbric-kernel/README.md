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

The old "Knot classloader split" law survives as the kernel's own boot↔game split.

**Status of the game side, stated honestly:** `src/runtime/java` is still empty, and neither
`net.forbric.kernel.api.KernelHooks` nor `net.forbric.kernel.runtime.Hooks` exists — earlier revisions of this
file described them as if they did. What actually happens today is that injected bytecode calls boot-side statics
directly (thirteen distinct owners), and the only `net.forbric.kernel.runtime.*` classes are three synthesized at
runtime with ASM by boot-side factories (`KernelModContainerFactory`, `KernelHudBridge`, `KernelGameLookup`).
`DelegationPolicy` already reserves `net.forbric.kernel.runtime.` as game-side, so the slot is real; it is just
unfilled.

That gap is the structural reason cross-ecosystem fixes have taken the shape they have: with no typed landing
place on the game side, each one is either a bytecode patch making one ecosystem satisfy another's expectations,
or an `Object`-in/`Object`-out reflective shim.

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
