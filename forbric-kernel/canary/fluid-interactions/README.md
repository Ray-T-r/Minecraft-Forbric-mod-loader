# Fluid-interaction canary

One mod for `run/compat/fluid-parity-gate.py --mods`, using only NeoForge's API and registering at common setup, as
mods do:

- `neo/` — `forbricfluidneo`, NeoForge: lava next to an iron block or an emerald block becomes glowstone.

It prints `[FluidCanary] neoforge <fluid> rule fired at x, y, z` when its rule runs. The tool compiles it against the
native NeoForge installed by `native-controls.py prepare` (never against a Forbric carrier), runs it on that native
server, and runs it unchanged on the kernel; every case cell and every firing must match native NeoForge. Test fixture
only.

Until Stage 4 of dropping MinecraftForge a second mod, `forge/` (`forbricfluidforge`, MinecraftForge: lava next to a
lapis or an emerald block became shroomlight, water next to lapis a sponge), ran beside it against native
MinecraftForge 26.2-65.0.1, and the emerald block, matched by both, showed whether one liquid reacted twice. Forbric
refuses a MinecraftForge-only mod, so that column went with it.
