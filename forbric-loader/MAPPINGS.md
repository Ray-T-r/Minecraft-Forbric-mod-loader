# Mapping provenance & the no-MCP guarantee

Fabric mods reference the game through the **intermediary** namespace; Forge mods
reference it through **SRG** (and, at runtime on modern versions, Mojang official
names a.k.a. "Mojmap"). Forbric must expose the game in both namespaces and remap
every mod to one **canonical runtime namespace**.

Which namespace that is depends on the game version. On an obfuscated version
(1.21.11, say) the canonical namespace is **intermediary**, and a Forge mod's
Mojmap bytecode is remapped into it. On a Mojmap-native version — 26.2, whose
vanilla jar is already deobfuscated — Forbric runs the canonical namespace as
identity (`-Dforbric.runtimeNamespace=named`) and no remap happens at all.

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
Forbric non-distributable. Forbric therefore **ships no mapping table**: no
obfuscation mapping of any kind is committed to this repository, and none is
derived from an MCP or FML input. The mapping trees Forbric works with are built
locally, at run time, from the permitted sources above — files you already have
for your own game version.

One committed file does contain Minecraft names, and it is worth being exact
about it. `run/merged-base/merge-conflicts.txt` is the diagnostic report emitted
by the merged-game-base builder; it lists classes, fields and method descriptors
in Mojang official form, e.g.

```
net/minecraft/world/entity/Entity#baseTick()V (forge hook lost)
```

That is a report, not a mapping table. It is a single column of names with no
obfuscated counterpart anywhere in the file, so nothing can be deobfuscated with
it, and it maps nothing to anything. The names in it are the ones already legible
in a Mojmap-native game jar; what is non-redistributable is MCP's name tables,
and none of those are present.

## Enforcement

Today the guarantee is **structural, not automated**:

- No mapping data is committed. The only file in the tree carrying Minecraft
  names is the diagnostic report described above; there is no tiny, ProGuard,
  SRG or TSRG table anywhere in this repository.
- Nothing in the code path ingests MCP or FML output. `ForbricMappings.load`
  takes exactly two inputs — a Fabric intermediary file and Mojang's ProGuard
  file — and everything downstream, SRG included, is joined from those two.

**Not implemented:** there is no build-time provenance gate. A Gradle
verification task that asserted every generated SRG/Mojmap name traces only to
Mojang-official + intermediary inputs, and failed the build if any string came
from an MCP/FML input path, would make this guarantee mechanical instead of
merely reviewable. No such task exists in `build.gradle`; the claim above rests
on reading the code and the tree.
