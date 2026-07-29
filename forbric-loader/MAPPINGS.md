# Mapping provenance & the no-MCP guarantee

Fabric mods reference the game through the **intermediary** namespace; Forge mods
reference it through **SRG** (and, at runtime on modern versions, Mojang official
names a.k.a. "Mojmap"). Forbric must expose the game in both namespaces and remap
every mod to one **canonical runtime namespace** (default: **intermediary**).

## Permitted sources (the only ones Forbric uses)

| Namespace | Source | Notes |
|---|---|---|
| official / Mojmap | Mojang official mappings (the ProGuard `.txt` shipped per MC version) | bundle Mojang's mapping-file licence acknowledgement |
| intermediary | Fabric intermediary | published per MC version by FabricMC |
| named (yarn) | Yarn | dev-time readability only |

## SRG is synthesized, never ingested

Forbric does **not** read SRG from any MCP- or FML-derived artifact. The SRG
namespace is **synthesized at build / first run** by joining Mojang official and
Fabric intermediary on their shared obfuscated columns. This produces the SRG
member identifiers Forge mods expect without ever touching MCP data.

## Why: MCP data is non-redistributable

FML's own licence states that MCP data (its method/field name tables) is **not**
redistributable by third parties. Bundling or re-deriving from it would make
Forbric non-distributable. Forbric therefore ships **no mapping data at all**;
mapping tables are generated locally from the permitted sources above.

## Enforcement

A build-time **provenance gate** (a Gradle verification task, added in milestone
P3) asserts that every generated SRG/Mojmap name traces only to Mojang-official +
intermediary inputs, and fails the build if any string originates from an
MCP/FML input path.
