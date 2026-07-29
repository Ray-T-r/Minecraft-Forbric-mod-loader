# Credits & provenance

Forbric is a clean-room unified mod loader. It stands on the work of two prior loaders.

## Fabric Loader — substrate (reused)

Forbric's core (class loading, mod discovery + dependency solving, metadata
parsing, the Mixin service, the game-provider framework) is **reused and adapted
from [Fabric Loader](https://github.com/FabricMC/fabric-loader)**, © FabricMC,
licensed under the **Apache License 2.0**. Adapted files keep the original
FabricMC copyright header. The mod-facing API packages (`net.fabricmc.api`,
`net.fabricmc.loader.api`) are kept under their original names so that existing
Fabric mods continue to link.

## Forge Mod Loader (FML) — behavioural reference only (NOT reused)

Forbric's Forge-compatibility code was written **clean-room** from public
specifications. [FML](https://github.com/MinecraftForge/FML), © cpw and
contributors, licensed under the **GNU LGPL v2.1 or later**, was read only as a
behavioural reference for:

- Access Transformer semantics (including `INVOKESPECIAL` → `INVOKEVIRTUAL`),
- SRG deobfuscation behaviour,
- the coremod ordering algorithm (sort index + topological pre-depends),
- Maven version-range semantics,
- the Forge mod lifecycle / event model.

**No FML source code is included in Forbric.** This keeps the distribution free
of LGPL-derived code, so the whole project remains Apache-2.0. The Forge-side
support targets **modern** Forge (`mods.toml` + ModLauncher), not the 1.8-era FML
that was used as a reference.

> Maintainer note: do not copy code from FML (or any other LGPL/GPL source) into
> the `net.forbric.*` tree. If LGPL-derived code is ever genuinely needed, it must
> live in a separately-licensed sibling module with its own `LICENSE-lgpl.txt` and
> a source-availability offer.

## Mappings

See [MAPPINGS.md](MAPPINGS.md). Forbric never bundles MCP data.
