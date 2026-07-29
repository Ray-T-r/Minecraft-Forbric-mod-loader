# Forbric — first end-to-end boot (Minecraft 1.21.11)

This directory reproduces Forbric's first real launch: **the `ForbricClient` entry point boots vanilla
Minecraft 1.21.11 through the reused Fabric substrate, loads a real Fabric mod, and reports any Forge
mods present via Forbric's unified discovery.**

## What it proves

From a real run (`boot-evidence.log`):

```
 Forbric Loader 0.1.0 (client) — unified Fabric + Forge
[Forbric] unified discovery: 2 mod(s) — 1 Fabric, 1 Forge
[main/INFO]: Loading Minecraft 1.21.11 with Fabric Loader 0.19.3
[main/INFO]: Loading 4 mods:  - fabricloader 0.19.3  - forbric_probe_fabric 1.0.0  - minecraft 1.21.11 ...
[main/WARN]: Found 1 non-fabric mod:  - forbric-probe-forge.jar      <-- substrate ignores it
[main/INFO]: SpongePowered MIXIN Subsystem ... Service=Knot/Fabric Env=CLIENT
[Render thread]: >>> [ProbeMod] FORBRIC RAN A REAL FABRIC MOD ON MINECRAFT 1.21.11 <<<
[Render thread/INFO]: Backend library: LWJGL version 3.3.3-snapshot
[Render thread/INFO]: OpenAL initialized ...    (reached the title screen)
```

The contrast is the whole point: the underlying Fabric loader says *"Found 1 non-fabric mod"* and
**ignores** the Forge jar — while **Forbric's unified discovery recognizes it as a Forge mod**. Loading
that Forge mod (mapping spine + transform chain + Forge lifecycle shim) is the next milestone.

## Files
- `mods/forbric-probe-fabric.jar` — a real Fabric mod; its `ModInitializer.onInitialize()` prints the proof line.
- `mods/forbric-probe-forge.jar` — a Forge `mods.toml` mod, recognized by Forbric's discovery.
- `testmod-src/com/example/ProbeMod.java` — source of the Fabric probe.
- `launch-1.21.11.sh` — assembles the classpath from your MC install and launches `ForbricClient`.
- `boot-evidence.log` — the captured signal lines from the run above.

## P4: a Forge mod and a Fabric mod, loaded together (the goal)

`p4-forge-coexistence.log` captures the milestone — one real MC 1.21.11 boot loading **both** ecosystems:

```
[main]: Loading 5 mods:  - forbric_probe_fabric 1.0.0  - forbric_probe_forge 3.1.4  - minecraft 1.21.11 ...
>>> [ForgeProbe] Forge @Mod-style class RUNNING; my game field type resolves to: net.minecraft.class_243
>>> [Forbric] LOADED + LINKED + CONSTRUCTED Forge class com.example.forge.ForgeProbe (mod 'forbric_probe_forge') <<<
>>> [ProbeMod] FORBRIC RAN A REAL FABRIC MOD ON MINECRAFT 1.21.11 <<<
```

The Forge mod (`testmod-src/com/example/forge/ProbeMod`-style class referencing Mojmap `Vec3`) was remapped
**Mojmap → intermediary** by `ForgeModRemapper`, wrapped as a Fabric mod, and added via `-Dfabric.addMods=...`.
Its `Vec3` field resolving to the live `net.minecraft.class_243` proves the remapped bytecode linked against
the running game — next to a Fabric mod, in the same instance.

## Run it
```bash
cd forbic-loader
./gradlew jar            # compiles the loader + reused substrate once
run/launch-1.21.11.sh    # boots MC 1.21.11 via Forbric with the test mods
```
Requires a vanilla `1.21.11` profile (game jar + libraries) and a `1.21.11-Fabric` profile (for
`intermediary-1.21.11` + LWJGL natives) under your Minecraft directory (`$MC_DIR`, default
`~/Library/Application Support/minecraft`). The 401 / Realms auth errors in the log are expected for the
offline dummy account and are not loader failures.
