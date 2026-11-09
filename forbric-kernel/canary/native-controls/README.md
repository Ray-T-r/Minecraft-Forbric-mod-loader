# Native server controls, Minecraft 26.2

These are three separate mods, each using only Minecraft and its own loader's public API. They have no
Forbric runtime dependency and no cross-ecology claims. The same compiled jar and dependency set is used
unchanged on the native and Forbric arms. Each arm has a fresh runner-owned world with seed `8035262`.

Pinned native distributions:

- Fabric Loader `0.19.5`, server launcher `1.1.2`, full Fabric API `0.155.2+26.2`.
- Forge `26.2-65.0.1`, installed using its official installer and native shim/server.
- NeoForge `26.2.0.93`, installed using its official installer and native FML server entrypoint (the version
  `native-controls.py` pins in `VERSIONS`; an image prepared before that pin, such as a main checkout's `.88`, is not it).

The declared behavior denominator is one mod initialization, one ServerStarted event, one command
registration, at least 20 real server ticks, and three command actions: registered block identity,
placing the mod's block at `(0,80,0)` in a ServerLevel, and reading that block back. The runner waits for
both `Done` and the 20-tick marker before sending `nativecontrol`, followed by `save-all flush` and `stop`.
This limited control does not test players, rendering, world reload, transfer APIs, Mixins, or arbitrary mods.
M33 is a separate cross-ecology transfer test and cannot run on a native single-ecology loader.

From the worktree root:

```sh
python3 forbric-kernel/run/compat/native-controls.py prepare
python3 forbric-kernel/run/compat/native-controls.py build
python3 forbric-kernel/run/compat/native-controls.py run --engine native
# The root agent schedules this separately: the existing Forbric launcher invokes kernel Gradle.
python3 forbric-kernel/run/compat/native-controls.py run --engine forbric
python3 forbric-kernel/run/compat/native-controls.py compare NATIVE_RESULT.json FORBRIC_RESULT.json
```

`--family fabric|forge|neo` selects an arm of prepare/run. Each attempt is retained below
`forbric-kernel/build/native-controls/{instances,results}`; the runner never deletes/reuses worlds.
`NATIVE_CONTROL_CACHE` points to the read-only existing download/cache workspace, defaulting to the
original Forbric workspace. The Forbric arm boots the stage `FORBRIC_OLD` names, exactly as set; only without it
does it fall back to the cache's `forbric-loader`. Only exact upstream-checksummed libraries seed Forge/Neo installations.
The official installer produces and validates their own game jars; no welded Forbric loader/carrier is
used by the native arms or by compilation. Fabric copies only native distribution cache directories,
never a cache instance's mods, configs, transformed mod cache, or world.

Each result retains the command, tool version, canary source hashes, compile classpath hashes, native
installer and runtime inputs, complete mod set hashes, fixed seed/actions, full log, explicit startup
and action/event denominators, exit status, freshness token, and input-drift check. A failed arm remains
failed; the runner still attempts the other predeclared arms without changing any mod set. Comparison
requires identical mod hashes, seed and actions. A native startup failure is inconclusive for attributing
a missing action to Forbric, never a passing control.

## The swap oracle (Stage 4 C5c)

`native-controls.py swap --engine native --forge-mods F.jar... --neo-mods N.jar... --console C.txt --checks K.txt
--chunks X0,Z0,X1,Z1 --out DIR` asks what a world written with mods' MinecraftForge builds keeps when they are
replaced by the same mods' NeoForge builds, on the native loaders and with nothing of Forbric: it creates a flat world
from the fixed seed on an APFS clone (`cp -c -R`) of the prepared MinecraftForge `26.2-65.0.1` image with the Forge
builds and runs the console script once, reopens it once there (the old side's own A/A), then opens the same world on a
clone of the NeoForge `26.2.0.93` image with the NeoForge builds, runs the check lines, and reopens it. Every phase is
one server session (Done, its lines, `save-all flush`, stop) and every phase's world is exported with
`world-snapshot.py` (`<phase>.snapshot.json`); `diff-forge-aa.json`, `diff-swap.json` (upgrade rules) and
`diff-reopen.json` are written beside them. `--engine forbric --old-world DIR` runs the same open and reopen on this
checkout's kernel for a world the old kernel wrote (`forge-swap-make-old.sh`). The images are never prepared here
(that downloads); clone the prepared ones into `build/native-controls/native/{forge,neo}`. `forge-swap.py console` and
`forge-swap.py checks` print the lines both arms use, and `forge-swap.py classify` turns the two arms into the per-key
loss table.
