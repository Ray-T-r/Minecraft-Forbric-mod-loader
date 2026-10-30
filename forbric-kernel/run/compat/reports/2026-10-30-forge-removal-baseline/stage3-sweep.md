# Stage 3 sweep: every local mod jar, tri-eco kernel vs the MinecraftForge refusal (2026-10-30)

Result: **0 false refusals.** 90 distinct jars are refused. I checked each one by hand, and none of them has a Fabric
or NeoForge half that works. 10 jars change loader (MinecraftForge → NeoForge). No real jar moves from MinecraftForge
to Fabric. The detection rule (`LoaderHalfJudge` at `s3/core` `f2989d49`) needed no change for this corpus.

Files beside this one:
- `stage3-sweep.tsv`: one row per distinct jar (SHA-1), with the packs it sits in, before, after, the per-half reasons,
  the discovered mods and their mandatory dependencies.
- `stage3-sweep-summary.txt`: the tool's printed summary (transitions, refused list, dependency checks).
- `stage3-logcheck.txt`: the cross-check of the "before" column against real boot logs (below).
- `stage3-refused.tsv`: the hand-check evidence for every refused jar. It lists the manifests and ids, the
  MinecraftForge and NeoForge `@Mod` classes, the Fabric entrypoints and mixin configs (marked ABSENT when the jar does
  not carry them), class counts naming `net.minecraftforge` / `net.neoforged` / `net.fabricmc`, and nested jars. It
  also gives the Modrinth project and the loaders that project ships for 26.2.

## How it was measured

`forbric-kernel/run/compat/stage3-sweep.py` plus `Stage3Sweep.java`, from `forbric-kernel/`:

```
./gradlew -q compileJava printBootClasspath $(cat <props>) | tail -1 > /tmp/cp.txt
python3 run/compat/stage3-sweep.py --classpath "$(cat /tmp/cp.txt)" --out <dir> > <dir>/summary.txt
python3 run/compat/stage3-refused-check.py <dir>/stage3-sweep.tsv <dir>/stage3-refused.tsv   # needs curl + network
python3 run/compat/stage3-logcheck.py <dir>/stage3-sweep.tsv --exclude per-mod-v020- > <dir>/stage3-logcheck.txt
```

The scripts find the main checkout themselves (the parent of the repository's common git directory, so they work from
a worktree too), or take `FORBRIC_SWEEP_HOME`. The TSV's `path` column is relative to that checkout. A jar found only
nested in another is written `<parent path>!/<entry>`, and `stage3-refused-check.py` reads it out of its parent.

- **Corpus**: every `*.jar` under the main checkout's `forbric-kernel/run`, `forbric-kernel/build` and
  `forbric-loader/run`. Symlinks are followed, each directory is read once, and `libraries/`, `versions/` and `.gradle/`
  are skipped. Jars nested in those (`META-INF/jars`, `META-INF/jarjar`, recursively) are added too, because the kernel
  arbitrates nested candidates as well. Totals: 11,689 mod jar files, **1,947 distinct by SHA-1**. That is 1,587
  top-level mod jars, 254 jars found only nested in another jar, and 106 jars the old loader rewrote into its
  `.forbric/cache` (more on those below). Set aside: 643 jars with no loader manifest at their root, and 246 staged
  game, carrier or kernel jars, matched by exact name (`patched-mc-*`, `forge-runtime*`, `neoforge-runtime*`,
  `server`, `client`, `forbric-kernel*`, `forbric-installer*`, `vanilla-reference*`). A first version of the sweep
  also set aside every `minecraft-*.jar`. No game jar has that name, but one player mod does:
  `minecraft-server-updater+forge-0.2.1-alpha.1.jar` (sweep80-mac `v020-rounds/r2`). That pattern is gone, and the jar
  is in the counts (refused, see below).
- **Before** = the `tri-eco-26.2-final` kernel's own answer. The tool compiles the tag's `MultiLoaderArbiter`,
  `ForbricModDiscoverer` and `MetadataFailures` from the tag's sources (`git show`) against this tree's classes, and
  puts them first on the class path. Only those three files differ between the tag and this tree in deciding a jar's
  owner: `git diff tri-eco-26.2-final -- …/discovery/` changes only them and adds `LoaderHalfJudge`. **After** = this
  tree's classes as they are. Both runs call `MultiLoaderArbiter.ownerOf`, not a re-implementation. A family counts as
  lost when the owner's own manifest failed in `MetadataFailures`; no jar landed there.
- **Cross-check of "before"** (`stage3-logcheck.py`, output in `stage3-logcheck.txt`). Every `*.log`, `*.txt` and
  `*.out` under `forbric-kernel/run` and `forbric-kernel/build` is read, and every line
  `[Forbric/MultiLoader] <jar> declares N loaders — loading it as X` is compared, by file name, with the "before"
  column. Each log line counts, not a majority per jar. That gives 1,615 logs and 186 jar names, 156 of them in the
  sweep. 142 were only ever logged with the "before" owner, and 14 were logged at least once with another. Those 14
  all come from kernels other than tri-eco:
  - 29 lines are in logs written before 29043460 (2026-09-20 10:31 +0800, "Choose universal mod loaders from their
    real initialization code"), the commit that introduced the rule tri-eco uses.
  - 7 lines are in `build/sweep80-mac/per-mod-v020-*`. Those logs date from 2026-09-27, but that instance (`mc-v020`)
    installs `net.forbric:forbric-kernel:0.2.0` (its `versions/26.2-forbric` profile), the v0.2.0 release kernel.
  Restricted to logs written at or after 29043460 and not under `per-mod-v020-`, there are 1,562 logs and 131 jar
  names in the sweep, and **all 131 agree**. Logs do not record the kernel version, so the date and that one
  directory are the only filters, and both are stated. Without `--exclude`, the script lists the 5 v0.2.0 jars as
  CURRENT disagreements and exits 1. That is how this check fails.
- **The check can fail.** The summary flags every refused jar that the tri-eco kernel loaded through its Fabric or
  NeoForge half (`FALSE REFUSAL?`). On the real rule it flags 0. As a red control (`--after-first`), I compiled a
  `LoaderHalfJudge` where a NeoForge half with no `@Mod` and a library-style Fabric half are both unusable. That run
  flags **103** jars as `FALSE REFUSAL?` (101 top-level, 2 nested) and refuses 10 more that were MinecraftForge before.
  With only the NeoForge branch broken, those jars move to FABRIC instead: every one of them also has a library
  `fabric.mod.json`. The mechanical flag cannot see a usable half that the old arbitration passed over in favour of
  MinecraftForge. That is why every refused jar was also checked by hand.

## Transitions

| kind | before → after | jars |
| --- | --- | ---: |
| mod jar | FABRIC → FABRIC | 1013 |
| mod jar | NEOFORGE → NEOFORGE | 477 |
| mod jar | **FORGE → NEOFORGE** | **10** |
| mod jar | **FORGE → refused** | **87** |
| nested only | FABRIC → FABRIC / NEOFORGE → NEOFORGE | 239 / 13 |
| nested only | FORGE → refused | 2 |
| old-loader cache | FORGE → FABRIC | 82 |
| old-loader cache | FORGE → refused / NEOFORGE → NEOFORGE | 1 / 23 |

No jar that loaded as Fabric or NeoForge before is refused or changes loader. No jar was lost or unreadable on either
side.

## Hand check of the 90 refused jars

**85 carry only a MinecraftForge `META-INF/mods.toml`.** They have no Fabric or NeoForge half at all, so under the rule
they cannot be false refusals. I still opened each one. Every `@Mod` is MinecraftForge's, and no nested jar is a
NeoForge or Fabric build (the nested jars are MixinExtras-forge, Kotlin, PhysX natives, pngj, mixinconstraints, and
Forge builds of xaerolib, journeymap-api, common-networking, forbricnestlib and crash_assistant). A few need a note:
- `ForgeConfigAPIPort-…-Forge` (22 classes) and `ShoulderSurfing-Forge` (10 classes) name `net.neoforged`. Both are
  MinecraftForge builds carrying NeoForge's config API ported to Forge. Neither has a NeoForge manifest.
- `dungeons-and-taverns-5.3.2`, `crafting_smithing_armor_iron_to_diamond_v4.12-forge` and
  `effective_tools_hoe_cactus_v3.12-forge` are Modrinth-generated datapack jars (`modLoader = 'lowcodefml'`, 0
  classes). The PurpurPacks ones even say `version = '4.12+forge_neoforge'`. NeoForge 26.2 reads only
  `META-INF/neoforge.mods.toml` (`JarModsDotTomlModFileReader`'s only manifest constant, checked in the staged
  carrier), so native NeoForge does not load them either. Modrinth has a NeoForge build of all three for 26.2. See
  owner question 1.
- `minecraft-server-updater+forge-0.2.1-alpha.1` (the jar the first sweep left out): one `@Mod`,
  `com.local.msu.platform.forge.ForgeUpdaterEntrypoint`, MinecraftForge's; 6 of its 297 classes name
  `net.minecraftforge`, none `net.neoforged` or `net.fabricmc`. Its Modrinth project ships Fabric and Forge for 26.2
  (no NeoForge).
- `forge-26.2-65.0.1-universal.jar` is MinecraftForge itself (`forbric-loader/run/downloads`).
- 17 are Forbric's own MinecraftForge test fixtures: forbriclive ×5, forbrictransferforge ×2, forbricnestforge ×2,
  forbricnestlib-forge ×2, forbric-bridge, forbric-probe-forge, forbricenergyforge, forbricforgecanary,
  forbrictest-raw, and census-forge.

**5 carry more than one manifest, and every other half is fake.** These are the three shapes the plan names:
- `journeymap-forge-26.2-6.0.0` and `-6.0.8`: the `fabric.mod.json` id is `journeymap-wrongloader`, version
  `"not a fabric mod"`. Its only entrypoint is `preLaunch journeymap.common.FabricError`, and `javap` shows that
  `onPreLaunch()` is `new UnsupportedOperationException(getError()); athrow`, with the text "User Attempting to load
  JourneyMap for Forge in Fabric Loader".
- `animalgarden-owl-1.2.8-forge` and `animalgarden-bison-1.0.0-forge` (same author template).
  - The Fabric `main` entrypoint is the MinecraftForge `@Mod` class, whose only constructor takes
    `FMLJavaModLoadingContext`.
  - The Fabric `client` entrypoint is absent (bison) or does not implement `ClientModInitializer` (owl).
  - The NeoForge half has no NeoForge `@Mod` (its mod id's only `@Mod` is the MinecraftForge `ModSetup`), and 0
    classes name `net.neoforged`.
  - Its mixins cannot carry the mod either. Bison's `neoforge.mods.toml` declares no `[[mixins]]`, and its
    `animalgarden_bison.mixins.json` lists none. Owl's does declare `[[mixins]] config="animalgarden_owl.mixins.json"`,
    with 5 real mixins, but none of them can do the mod's work without MinecraftForge code:
    - `PlayerMixin` and `PlayerRendererMixin` link against `net/minecraftforge/registries/RegistryObject` themselves.
    - `ParrotMixin` uses the owl entity class `ModMob`, which itself names `RegistryObject`.
    - `ServerPlayerMixin` uses `setup/Networking`, a MinecraftForge `SimpleChannel`.
    - `PlayerRenderStateMixin` only adds the `IOwlOnShoulder` interface, which does nothing without the rest.

    On native NeoForge these mixins would apply, but the owl entity they refer to is never registered.
  - 10 of owl's 28 classes and 5 of bison's 10 name `net.minecraftforge`, among them the registrations, events and
    setup. On NeoForge none of that would run.
- `aquariuslibs-1.2.0-forge`: the NeoForge half's mod id has only a MinecraftForge `@Mod` (`ModLibSetup`), and 0 of
  its 64 classes name `net.neoforged`. Its library function (`RegisterFunctions` and friends) is MinecraftForge code.

**Is there a NeoForge or Fabric build?** I matched each jar on Modrinth by its SHA-1 (`POST /v2/version_files`) and
then listed the loaders its project ships for 26.2.
- 65 of the 73 non-fixture jars matched exactly. 63 of those projects have a NeoForge build for 26.2, and all but four
  (the two alexander8vkhz mods, previews, tombstone: Forge and NeoForge only) have Fabric too.
  `minecraft-server-updater` has a Fabric build but no NeoForge one.
- `lucid-dreams-1.9.6` matched by SHA-1, but its project ships **only Forge** for 26.2. This is the one genuinely
  MinecraftForge-only mod in the corpus. CurseForge was not checked: its API needs a key.
- The 8 that did not match by SHA-1:
  - `forge-…-universal`: MinecraftForge itself.
  - `xaerolib-forge` ×2: Modrinth has no 26.2 Xaero's Lib build at all. These jars are nested in the refused Xaero
    minimap / world map Forge builds, whose projects have NeoForge builds.
  - `common-networking-forge`, `journeymap-api-forge` ×2, `mixinextras-forge-0.3.5`, `crash_assistant-forge`: libraries
    nested in refused Forge jars (journeymap, badpackets, CrashAssistant), not separate Modrinth projects. The parents'
    projects have NeoForge builds. The kernel bundles MixinExtras itself.

**Refused nested jars and their parents.** Every refused nested jar sits in a parent that is refused too. The single
exception is the old loader's rewritten `xaerominimap-b2529…` cache jar, which is not an input.

## What players will see change (FORGE → NEOFORGE, 10 jars)

Each one carries all three manifests. The tri-eco kernel chose MinecraftForge because only that half had an
initialiser: a dummy MinecraftForge `@Mod`. Now the NeoForge half (no `@Mod`; its code does not stand on
MinecraftForge) is chosen by preference over the equally usable library Fabric half.

| jar | what it is | after |
| --- | --- | --- |
| better-furnaces-saplings-1.4.0, dragon-egg-recipe-1.0, fastpath-og-1, glowing-glow-squids-3, norocketusage-26.9.0, silvers-better-wood-cutting-stone-cutter-1.7 | Modrinth datapack wrappers (`com.modrinth.<id>.ModrinthWrapper`, an empty `@Mod`) | data loads as a NeoForge data mod, as on native NeoForge |
| CLMG-[UNIVERSAL]-1.21-26.2-1.1.0 | the same shape, with one empty `@Mod` class plus data | same |
| minechess-v1.1.3 | datapack with per-version overlays, `@Mod` dummy `ModId` | same |
| SimpleRPC-4.1.4 | CraterLib-based; `@Mod` is `DummyForgeEntrypoint` | NeoForge half requires `craterlib`. In `compat-inputs/random` the only CraterLib is the Forge build, which is now refused, so SimpleRPC's dependency is missing there |
| **qubes-mouse-fix-1.1** | client mixin mod. The Forge half's config came from the MANIFEST `MixinConfigs`. `neoforge.mods.toml` declares no `[[mixins]]`. The Fabric half (library, `qubes_mouse_fix.mixins.json`, client) is the working one | **loads as NeoForge with no mixin at all, so it silently does nothing.** Native NeoForge 26.2 behaves the same: no FML class mentions `MixinConfigs`, checked in the staged carrier. Native Fabric works. See owner question 2 |

Mandatory dependencies of these 10, checked pack by pack against the ids the other jars in the same directory supply
(nested jars included; presence only, version ranges not compared): all satisfied, except SimpleRPC → `craterlib`
above.

**Forge+Fabric universal jars → Fabric: none in the corpus.**
- The only real jars with exactly a Forge and a Fabric manifest are the two JourneyMap builds, which are refused.
- Of the 141 three-manifest mod jars:
  - 125 were and stay NeoForge;
  - 4 were and stay Fabric (bannerstone, formationsoverworld, pisceslibs-fabric, weaponsofindia-fabric: real Fabric
    entrypoints beside a template `mods.toml`);
  - 10 move to NeoForge (above);
  - 2 are refused (owl, bison).
- So the "check fabric-api / architectury are present" step has no real jar to check.

**Jars that still load but lose a dependency to a refusal** (a mandatory dependency that, in that pack, only a refused
jar supplied):
- `SimpleRPC-4.1.4` → `craterlib` (`compat-inputs/random`).
- `MoogsOceanStructures-universal-1.21-1.1.1` → `moogs_structures` (`compat-inputs/mix80`: only
  `MoogsStructureLib-forge`).

Both fixture dirs need the NeoForge build of the library when Stage 3 swaps fixtures. Every other refused library's
dependents are themselves refused MinecraftForge builds.

## The old loader's cache: a false-negative class, not a false positive

The 82 "FORGE → FABRIC" rows are all `forbric-loader/run/*/.forbric/cache/*.jar`. These are jars the old loader
rewrote: a MinecraftForge mod given a synthesized library `fabric.mod.json` (`"name": "geckolib (Forge via Forbric)"`,
no entrypoints, a `forbric:forgeClass` custom value, the Forge mixin config listed as a Fabric one). They are not player
inputs, and the old loader is due to be deleted. They do show that the plan's rule (a library-style Fabric half is
usable) lets a MinecraftForge mod that ships an entrypoint-less `fabric.mod.json` load as Fabric, with its Forge
classes and mixins. No real jar in the corpus has that shape. See owner question 3.

## Owner questions

1. Zero-class MinecraftForge-only datapack jars (`lowcodefml`, only `mods.toml`): keep refusing them as the plan
   says, or load them as plain data? Native NeoForge does not load them, and Modrinth has NeoForge builds.
2. qubes-mouse-fix shape: both remaining halves are usable, neither has an initialiser, and only the Fabric half
   declares a mixin config. Should arbitration prefer the half that ships its mixins over the NEOFORGE-first
   preference? Today Forbric matches native NeoForge here, and the mod does nothing.
3. Should a library-style Fabric half count as usable when the jar's only `@Mod` is MinecraftForge's and its classes
   name `net.minecraftforge` but not `net.fabricmc`? That is the old-loader cache shape. Refusing it would close the
   false-negative class above, at the risk of refusing an Architectury library whose Fabric side is mixin-only.
