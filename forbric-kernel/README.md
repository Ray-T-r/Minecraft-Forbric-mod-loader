# Forbric Kernel

English | [简体中文](README.zh-CN.md)

A **sovereign** unified Minecraft mod loader that runs **Fabric + traditional MinecraftForge + NeoForge**
mods on one Minecraft 26.2 instance — a ground-up rewrite of the `forbric-loader` "weld".

For source development, start with [the development guide](run/README.md). From the repository root,
`python3 tools/dev.py client` prepares an isolated game environment and launches the current kernel.
Gradle also exposes `prepareDev`, `runClient`, `runServer`, `toolTest` and `integrationTest`.

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

**Status of the game side:** filled. `src/runtime/java` (130 files at the time of writing; introduction.md §2
keeps the current count) is compiled against the staged jars and shipped as `forbric-kernel-runtime.jar` —
registry and lifecycle drivers, both families' setup phases, the condition evaluators, the pack sources, the
unified Mods screen. An earlier revision of this file said it was empty, which was true when it was written
and stopped being true without the sentence changing.

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

## Compatibility contracts

Compatibility transforms match declared API contracts and proved bytecode structure. A guest's package, mod ID,
private callback name or a released mod's bytecode hash is not an admission rule. The same callback shape from a
new mod receives the same adaptation; ambiguous shapes remain unchanged and are diagnosed. Tests include renamed
real mod callbacks, execution of generated bytecode, and unsafe lookalikes that must be refused.

The lifecycle also exposes generic worker resources and registry element callbacks. Executors are registered
through their proved native start/drain/join operations or `ManagedWorkerResources.register`; shutdown never probes
a particular mod's lazy singleton. Closed registry initializer walks record successful element callbacks as a
batch, publish only on normal return, and initialize only newly registered identities after the registration
window closes. Public entrypoint and API names (such as config-screen or config-registration protocols) remain
explicit adapter contracts. Conflicts between independent mods are reported rather than settled by a built-in
mod priority list, and the game keeps the outcome Mixin itself gives the two mods: two mods' mixins on one
method (`MixinOverlapLint`), and one mod's injector taking a call that another mod's raw post-Mixin patch looks for
(`ContendedCallSites`: Mixin runs every injector before any config plugin's `postApply`, so the injector keeps the
call and the patch finds nothing, as it does with both mods in any game that runs Mixin's own applicator).

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
| `gate-m24c` | a jar listed in `forbric-disabled.txt` is loaded by nobody and named in `load-report.txt`; the control loads it |
| `gate-m25-worldgen` | biome modifier canaries of both Forge families leave distinct blocks in saved regions |
| `gate-m26-forgeclient` | the Forge client receives key, renderer, tint, tooltip, geometry-loader and creative-tab registration events, and the CLIENT_INIT/REGISTRATION bridge inventory reports complete |
| `gate-m27-frame` | the 97-jar client produces a fresh, non-black Minecraft screenshot |
| `gate-m28-forgeconfig` | Forge COMMON configs load once, then its native watcher reads a live file edit; dedicated servers never open CLIENT configs |
| `gate-m29-forgecaps` | MinecraftForge capabilities on the merged base: the item-handler capability of a zombie and a furnace, with the lost field initializers replayed |
| `gate-m30-attribution` | a mod that loses PART of itself — a mixin left out or failing at world creation, a subscriber that cannot register, a listener on a dead event, a throwing deferred task, a jar compiled against another NeoForge — is named with the reason in the log, on the Mods screen and in `load-report.txt`, which is rewritten once the world is up |
| `gate-m31-vanilla-parity` | with zero mods and the same seed, a Forbric overworld carries the same biomes and the same structure starts as a pure-vanilla 26.2 server generating beside it |

`run/compat/gates-all.sh` discovers every gate by glob, in numerical order, including network/GUI gates;
an intentional `--skip <script.sh>` is printed in the results. It runs several at once by default (`-j auto`,
`-j 1` for the old strictly-sequential run), which took a full sweep on this machine from 26 minutes to 7.
`auto` is one slot per two cores: memory never binds (5.5 GB of game JVMs at the peak, on 16 GB), but starving
the gates does — at `-j 7` a shutdown that normally finishes well inside `await_server`'s grace window stopped
doing so, and that window is a real check for a leaked non-daemon thread.

Overlapping them is not free, and each gate says what it needs in one line near the top:

```sh
# GATE-PARALLEL: rundirs=server-kernel,canary mem=1500
```

`rundirs` names what it owns while it runs — two gates naming the same thing never run together — and `mem` is
what it costs. `clone=<dir>:<VAR>` asks for a private copy of a shared fixture instead: the four gates that want
`run/client-merged-pack` each get one, which on APFS clones that 434 MB install in 0.17s and costs no disk. Three collisions are why the line exists rather than an `xargs -P`: every gate that starts
a server writes its port into `server.properties`, and the loser of a port race prints `FAILED TO BIND TO PORT`,
writes a crash report and then still prints `Stopping server`, so the clean-shutdown assertions pass and the gate
goes red on "Done" instead — which reads as the kernel failing to boot, not as a port (`lib.sh`'s `port_was_free`
now names the port); `gate-m1`/`m2`/`m3` share `run/server-kernel` and `gate-m9`/`m17`/`m22`/`m23`/`m27` share
`run/client-merged-pack`; and every kernel JVM is launched without `-Xmx`, so each one inherits an ergonomic
quarter-of-RAM ceiling. The scheduler hands every concurrent slot its own port block, never co-schedules gates
that name the same rundir, and keeps the running set inside a memory budget. **A gate with no
`# GATE-PARALLEL:` line runs alone** and the run says so on stderr — a new gate is slow, not silently broken.

Portable Windows baseline collection and the negative controls are described in
[the compatibility protocol](run/compat/PROTOCOL.md).

The rest (`m8`, `m10`, `m11`, `m18`–`m23`) each pin one previously-shipped defect. Sixteen of the twenty-five
had not been run for a day when that was last measured, and one of them had been red the whole time — which is
why `gate-m0` now refuses to report on a test task that did not execute.

The old `forbric-loader` is kept runnable as the **differential oracle**, and still builds the shared game
artifacts the kernel consumes. It cannot certify removed mutable ancestors: a base declaring
`required-ancestor-compositions.tsv` must use the kernel with registered composition proofs.

## Generic compatibility and protocol extensions

Mixin suppression, contributed interfaces, call relocation and local capture follow actual source/current bytecode and control-flow evidence. Class names, mod ids, handler names and fixed ordinals no longer authorize these compatibility rules. An unproved callback is never moved across another event's effects. Native-reference hashes establish provenance rather than pinning a mod release.

A mod's declarations to other mods cross ecosystems (`CrossEcosystemDeclarations`). A Fabric mod's `custom` values appear in its `[modproperties]` table, as FML's TOML reader would type them. The class names it declares under a namespaced entrypoint key appear there too, but only under a key some reader's own bytecode reads as a `String` and none reads as anything else. A Forge-family mod's namespaced class-name properties are Fabric entrypoints of that key. A class that reads `getModProperties()` out of a Forge-family `ModList` sees, in that class only, the Fabric mods that declare something (`DeclarationReaderModListInjector`). Other `ModList` readers, such as the Mods screen, the handshake and version checks, keep the native list.

Optional SDKs publish independent adapters through `META-INF/services/net.forbric.api.ProtocolExtension`, with one registry per game loader. Providers can offer absent APIs, register transforms, receive configuration lifecycle events and provide configuration screens. A merged artifact records removed stateful ancestors in `required-ancestor-compositions.tsv`; an `AncestorComposition` must prove that the final definition retains the state protocol or loading fails explicitly.

The shared API also exposes `VirtualGetters` for public getters selected by their full JVM return type and `VirtualProperties` for mutable storage witnessed by a defined public getter. These work for any declaring class and preserve virtual dispatch; property writes require the actual getter/field proof before effects run. Extension providers must avoid linking game classes during construction and resolve game objects through `Context.gameLoader()`, as described in `ProtocolExtension`.

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
