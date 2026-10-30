# Stage 3 fixtures: the MinecraftForge jars the gates and tests staged, and their NeoForge builds (2026-10-30)

Forbric refuses a MinecraftForge-only jar since Stage 3, and a dedicated server or a quick-play client stops on the
refusal, so every gate that staged one stopped before it could test what it was written for. Each such jar was replaced
by the **same mod's NeoForge 26.2 build from Modrinth**, the same version wherever Modrinth publishes one (every row
below is the same mod version; `better-furnaces-saplings` is not in either set and was not needed). Every download was
checked against the sha1 Modrinth publishes for the file, and every new jar was run through `LoaderHalfJudge`: all 30
are `KEPT [NEOFORGE]`. Lookup: Modrinth's `version_file/<sha1>` for the Forge jar's own project, then that project's
`neoforge` + `26.2` versions. No jar is in git (`*.jar` is ignored).

## `$RUN_OLD/downloads/forge-26.2` → `forbric-kernel/run/neoforge-builds/` (lib.sh `NEO_BUILDS`)

Twelve of the directory's fifteen jars are refused; the other three (`FallingTree-26.2-25.jar`,
`collective-26.2.0-8.39.jar`, `starterkit-26.2.0-8.1.jar`) are universal jars with a usable NeoForge half, stay where
they are, and now load as NeoForge (gate-m4, m15, m16 assert that for FallingTree). The gates read the replacements from
`$NEO_BUILDS`, default `forbric-kernel/run/neoforge-builds`, a local fixture directory like `run/carry-on` and
`run/modmenu` (`FORBRIC_NEO_BUILDS` overrides it).

| MinecraftForge jar (sha1) | NeoForge build | Modrinth version | sha1 (Modrinth's, verified) | staged by |
| --- | --- | --- | --- | --- |
| `AdvancementPlaques-26.2-forge-1.7.2.jar` (`358457be58f6…`) | `AdvancementPlaques-26.2-neoforge-1.7.2.jar` | `Tl7N8BOp` (advancement-plaques 1.7.2) | `95a9be1342115286f5f88ab278b31f2f5a572412` | nothing (kept for completeness) |
| `Highlighter-26.2-forge-1.2.2.jar` (`c21512d76bf1…`) | `Highlighter-26.2-neoforge-1.2.2.jar` | `7GI1pFjX` (item-highlighter 1.2.2) | `f1dc96fb2f0ce64716f231c2b508862110a1641b` | nothing (kept for completeness) |
| `MouseTweaks-forge-mc26.2-2.31.jar` (`52a1e87f809e…`) | `MouseTweaks-neoforge-mc26.2-2.31.jar` | `rokwij6O` (mouse-tweaks 26.2-2.31-neoforge) | `7b914ba36cbfe9a6017ac9cb116bb205c389136e` | nothing (kept for completeness) |
| `NoChatReports-FORGE-26.2-v2.20.0.jar` (`6a94b1c93427…`) | `NoChatReports-NEOFORGE-26.2-v2.20.0.jar` | `4vGlggzy` (no-chat-reports NeoForge-26.2-v2.20.0) | `606d11d56be77053aff10568abc121daae76e623` | gate-m4 |
| `ShoulderSurfing-Forge-26.2-5.0.4.jar` (`f5d7d43937c8…`) | `ShoulderSurfing-NeoForge-26.2-5.0.4.jar` | `LpXeU88n` (shoulder-surfing-reloaded 26.2-5.0.4+neoforge) | `ebcf2c82d1c5d85e175dc0f6d68e55a3073c97fc` | nothing (kept for completeness) |
| `TerraBlender-forge-26.2-26.2.0.0.1.jar` (`e7511ff6c26e…`) | `TerraBlender-neoforge-26.2-26.2.0.0.1.jar` | `jNsOmsnb` (terrablender 26.2.0.0.1) | `827c48b36bdf5ff17a1e764ed05b18ba3f60f491` | gate-m4 |
| `boatiview-forge-0.0.9-26.2.jar` (`14fc0258bee6…`) | `boatiview-neoforge-0.0.9-26.2.jar` | `fXPkJEq3` (boat-item-view 26.2-0.0.9-neoforge) | `90cbd79b1d9c98428c9ef2584d2ac07b37e593e0` | nothing (kept for completeness) |
| `durabilitytooltip-1.1.6-forge-mc26.2.jar` (`9ce7d3c88684…`) | `durabilitytooltip-1.1.6-neoforge-mc26.2.jar` | `e3AW8zWi` (durability-tooltip 1.1.6-neoforge-mc26.2) | `9f1326d188e9f991caf0d3b8aba114233d95ac5d` | nothing (kept for completeness) |
| `geckolib-forge-26.2-5.5.3.jar` (`7e5e9ae894ff…`) | `geckolib-neoforge-26.2-5.5.3.jar` | `Kgc42zo3` (geckolib 5.5.3) | `5e177d4523547e4641566a043fd19ae4de0127b5` | gate-m4 |
| `journeymap-forge-26.2-6.0.0.jar` (`2ebc28fd6eae…`) | `journeymap-neoforge-26.2-6.0.0.jar` | `6XAmiEWa` (journeymap 26.2-6.0.0+neoforge) | `e91dc050ff7bb24fb3ae7e3db0420f96cf61b1b3` | nothing (kept for completeness) |
| `mcw-bridges-3.1.2-mc26.2forge.jar` (`5f6c700e408c…`) | `mcw-bridges-3.1.2-mc26.2neoforge.jar` | `VA1Q4KvP` (macaws-bridges 3.1.2) | `1c9622a8f740adc2d8e47b17ba6a4bb2355fa887` | gate-m4, m12, m13, m32 |
| `spark-1.10.173-forge.jar` (`d6e2b47552bb…`) | `spark-1.10.173-neoforge.jar` | `DdMsOH3O` (spark 1.10.173-neoforge) | `c4e339e787aa32949fd56848d26cc7bbfe812ef6` | gate-m4 |

## `run/client-popular/mods`

Eighteen of the pack's 37 jars are refused. Each was moved to `run/client-popular/forge-refused/` (outside `mods/`, so
nothing loads it) and its NeoForge build put in `mods/` in its place, so the pack is again a pack a client can run. The
refused jars stay as the refusal's real-jar fixtures: `LoaderHalfJudgeTest` reads `animalgarden-owl`, `aquariuslibs` and
`journeymap-forge` from there, `FmlTomlShapeOracleTest` cross-checks its wthit `mods.toml` copy against it, and gate-m29
stages `animalgarden-owl` as its real multi-manifest refused build. The other client-popular tests (AdapterPointSpelling,
MixinStubRebind, CarrierHelperResults, MixinReturnOrdinals, PostMixinFixupsAliasTarget, LambdaShapeEvidence,
CarrierDefaultChains, AddedCallOccurrences, MixinCapturedLocals, SpawnPositionCalls, VanillaMemberTwin,
KernelModInfoConfig) read only its Fabric and NeoForge jars and pass on the new set (run 2026-10-30, `-Pforbric.requireFixtures=all`);
`MixinRetargetRenamedBodyCorpusTest` fails exactly as in the Stage 0 baseline (the lost random100 corpus).

| MinecraftForge jar (sha1) | NeoForge build | Modrinth version | sha1 (Modrinth's, verified) |
| --- | --- | --- | --- |
| `BiomesOPlenty-forge-26.2-26.2.0.0.28.jar` (`845f1bc4fb81…`) | `BiomesOPlenty-neoforge-26.2-26.2.0.0.28.jar` | `Kpuzuokl` (biomes-o-plenty 26.2.0.0.28) | `86f540d07cb53136ff4f01633e39b4169b602222` |
| `GlitchCore-forge-26.2-26.2.0.0.0.jar` (`959b4ecfd82b…`) | `GlitchCore-neoforge-26.2-26.2.0.0.0.jar` | `POAebwFo` (glitchcore 26.2.0.0.0) | `ed6a68cb80febcb5d03a76e5231f2aeaa372538a` |
| `TerraBlender-forge-26.2-26.2.0.0.2.jar` (`e619444cb426…`) | `TerraBlender-neoforge-26.2-26.2.0.0.2.jar` | `TzDe72Nr` (terrablender 26.2.0.0.2) | `82ab30bc5cf0177e4ca1a0e5aad14d0e79e2aa13` |
| `animalgarden-owl-1.2.8-forge-26.2-65.0.0.jar` (`12636f922353…`) | `animalgarden-owl-1.2.8-neoforge-26.2.0.6.jar` | `rDa01pCR` (animal-garden-owl 1.2.8-neoforge-26.2.0.6) (same mod version; the Forge build carried the Forge version in its number) | `a6bc5e87fe2c1827a5b15cf9579588c35ed42fca` |
| `aquariuslibs-1.2.0-forge-26.2-65.0.0.jar` (`9b479dedf45a…`) | `aquariuslibs-1.2.0-neoforge-26.2.0.2.jar` | `Gi2usHZL` (aquarius-libs 1.2.0-neoforge-26.2.0.2) (same mod version; the Forge build carried the Forge version in its number) | `209a551d5f5663595f552cef7173e2647cac9b54` |
| `badpackets-forge-0.12.2.jar` (`4b29c34c35e4…`) | `badpackets-neo-0.12.2.jar` | `8PgyQyuN` (badpackets neo-0.12.2) | `44aa856cc63f72783c70d2c63f8b755d4fbc1e0a` |
| `brickfurnace-forge-26.2-5.2.1.0.jar` (`5593074d91fe…`) | `brickfurnace-neoforge-26.2-5.2.1.0.jar` | `TQu82gct` (brick-furnace 26.2-5.2.1.0) | `ed34da6d21a8e2dd0e3d00b692e182b9f96ca748` |
| `classicpipes-forge-26.2-1.1.7.jar` (`933f1b1c3661…`) | `classicpipes-neoforge-26.2-1.1.7.jar` | `iJuPQewC` (classic-pipes 1.1.7) | `e2e4004cb0661a17bfe9159033acaa123740810c` |
| `geckolib-forge-26.2-5.5.6.jar` (`8d7913d2b20b…`) | `geckolib-neoforge-26.2-5.5.6.jar` | `IEGPh4CJ` (geckolib 5.5.6) | `980aba8415e4d9593c03403e955e96a8b11117c6` |
| `inventoryhud.forge.26.2-3.4.34.jar` (`53c0e8f3004b…`) | `inventoryhud.neoforge.26.2-3.4.34.jar` | `UrFPVHXz` (inventoryhudplus 3.4.34) | `83e63ed8ae8037a50d33f3493ac90a5278f39e89` |
| `journeymap-forge-26.2-6.0.8.jar` (`06a991b0c00b…`) | `journeymap-neoforge-26.2-6.0.8.jar` | `BvreZ8yd` (journeymap 26.2-6.0.8+neoforge) | `891a2f918230cf29e7cacbc6e502b75f5ee5c460` |
| `packedup-1.1.1-forge-mc26.2.jar` (`4c81a9261292…`) | `packedup-1.1.1-neoforge-mc26.2.jar` | `mEkrPug0` (packed-up-backpacks 1.1.1-neoforge-mc26.2) | `5731864ba08c661d004469e61082a91cfd9e9269` |
| `supermartijn642configlib-1.1.8-forge-mc26.2.jar` (`ccd5e3426a9c…`) | `supermartijn642configlib-1.1.8-neoforge-mc26.2.jar` | `hyLJJjeT` (supermartijn642s-config-lib 1.1.8-neoforge-mc26.2) | `80cc381a5a30a74de23b41f0a8826f05cc8ee615` |
| `supermartijn642corelib-1.1.24a-forge-mc26.2.jar` (`98656689f371…`) | `supermartijn642corelib-1.1.24a-neoforge-mc26.2.jar` | `vMlRLpq6` (supermartijn642s-core-lib 1.1.24a-neoforge-mc26.2) | `6e8085328d820b7e93c844ea8d04118f44956a52` |
| `tombstone-forge-26.2-9.9.3.jar` (`5b63b41a5140…`) | `tombstone-neoforge-26.2-9.9.3.jar` | `6ZkZoZ8J` (corail-tombstone neoforge-26.2-9.9.3) | `845ef45ebae39b20a4e391abf90285bf3a017bd3` |
| `wthit-26.2-forge-20.0.0.jar` (`99158bf6cb5a…`) | `wthit-26.2-neo-20.0.0.jar` | `nYkvtEUa` (wthit neo-20.0.0) | `e475c8e3b01e7eb421b87412f666326e8baeb75b` |
| `xaerominimap-forge-26.2-26.5.1.jar` (`dc870381892e…`) | `xaerominimap-neoforge-26.2-26.5.1.jar` | `WnaAZsas` (xaeros-minimap neoforge-26.2-26.5.1) | `3608c490ae43835e3a7076c9d0078a5c1b21ae48` |
| `xaeroworldmap-forge-26.2-1.46.1.jar` (`89b9cade6e37…`) | `xaeroworldmap-neoforge-26.2-1.46.1.jar` | `jvDo1iio` (xaeros-world-map neoforge-26.2-1.46.1) | `6e1346fba34f0218acf037574095deca2c0956ff` |

## What the integrator must do to the shared copies

The lane built both sets in its own worktree and changed nothing outside it. `forbric-kernel/run/client-popular` and the
stage's `downloads/` are links into the main checkout, shared by every worktree, so they were not modified in place.

1. **`forbric-kernel/run/neoforge-builds/`** (new): create it in the main checkout
   (`/Users/jerry/Documents/Forbric/forbric-kernel/run/neoforge-builds/`) holding the twelve jars of the first table
   (copy them from the lane worktree's `forbric-kernel/run/neoforge-builds/`, or fetch them by the version ids above and
   check the sha1s). Worktrees then link it like `run/modmenu`; the nightly's `FIXTURES` list already names it.
   `$RUN_OLD/downloads/forge-26.2/` itself can stay: three universal jars are still read from it.
2. **`forbric-kernel/run/client-popular/`** (shared): in the main checkout, `mkdir forge-refused`, move the eighteen
   MinecraftForge jars of the second table from `mods/` into `forge-refused/`, and copy the eighteen NeoForge builds into
   `mods/` (from the lane worktree's `forbric-kernel/run/client-popular/mods/`). Afterwards `mods/` holds 37 jars and
   `forge-refused/` 18. The lane worktree's `run/client-popular` is an APFS clone of the main checkout's with exactly that
   change, and can be compared file by file.
3. Nothing else moves. `forbriclive.jar` (the MinecraftForge canary DevPrepare builds) stays in the stage: gate-m26 and
   gate-m29 stage it on purpose, as the jar that must be refused.

## Gates run on these fixtures (lane s3/gates, on top of s3/core f2989d49, 2026-10-30)

All GREEN, each run on its own worktree with the stage's root (`FORBRIC_OLD=.stage`): gate-m1, m4, m4-canary, m9-client
(on an APFS clone of client-merged-pack), m12, m13, m14, m15, m16, m17, m18, m21, m25, m26, m28, m29, m32, m41 (on its own
clone), m47, m48, m49. Red controls measured: gate-m29 with `M29_POLICY=continue` and with `M29_REFUSED=''`; gate-m26's
phase 2 with the NeoForge canary in place of the MinecraftForge one; gate-m14 with `M14_UNIVERSAL_KEEP_NEO=1`. Each gate's
header says what it lost, what moved and why.
