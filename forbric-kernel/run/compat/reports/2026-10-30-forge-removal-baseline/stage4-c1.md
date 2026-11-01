# Stage 4 C1: the flip to NeoForge's own patched game (s4-c1, 00a5e585..73a4314c)

C1 made NeoForge's patched 26.2 jar (the "genuine base", G below) the game base everywhere: the installer builds it, the
kernel compiles and tests against it, the launchers run it, the canaries are built against it, and the first genuine
boots were taken and their walls fixed. 46 commits on top of the C2 tip 00a5e585, in five lanes, each reviewed twice
(A and B) and followed by a review-fix round. This file keeps the measured outcomes; the working notes lived in a session
scratchpad that is not kept.

Names: `.stage4` is the genuine stage root (`run/neoforge-patched/patched-mc-neoforge-26.2.jar`,
`run/neoforge-runtime/neoforge-runtime.jar` = NeoForge 26.2.0.93, `run/vanilla-reference/vanilla-reference-26.2.jar`);
`.stage` is the merged stage of Stages 0-3. Heavy commands went through `.stage/heavy.sh`; `MC_DIR` was never set.

## Lanes and commits

| lane | commits | what changed |
|---|---|---|
| installer | 4da13f7f; review fixes b2c23f3d, f1ff42a2, 4e48031f, cbcd8e77, 81eeb9e3, 5e5ba88f | `ArtifactBuilder` builds only NeoForge's patched game and the NeoForge runtime; installed as `net.forbric:patched-mc-neoforge:26.2-26.2.0.93` with one `--runtimeJar` and the sidecar `net.forbric:vanilla-reference:26.2-26.2.0.93` (D11). `ForgeArtifacts`/`ForgeRuntimeBuilder`/`PatchedMcBuilder` deleted, `MergedBaseTool` became `LinkCheckTool` (no baseline, requires `dangling references: 0`, D10), `downloadLibraries` deleted (D13). dev.py prepare from a worktree no longer deletes the shared stage's retired directories (D12). |
| build-fixtures | 7b29c893; review fixes 864e473e, 29313e2c, 99efe6d5, 05bb87f5, 942feb3c, 6d53ec73, 00171fe9, 2a6aef6d | build.gradle stages two game jars (base + NeoForge carrier); the runtime compile classpath is G + carrier; `TestFixtures` lost every Forge/merged helper and `Fixture.FORGE_CARRIER`. Tests on G are handed the class the game hands a mod (after `VanillaMemberTwinInjector`), not the raw jar class (864e473e). |
| launch | de67130d; review fixes 8068b9fc, 1b9850bb, 652a0c91, 1bc965a2, fb50d1a8 | launchers, dev.py, push-and-run, soak-run use BASE + one NEO_RT; `LaunchInputCheck` FAMILIES = [NEOFORGE]; an install made by Forbric 0.4 or earlier is refused with exit 2 by what is in its jars (D9, always enforced); launchers exit 2 when `MERGED` or `FORGE_RT` is set (D12). |
| canaries | 2b41f722, d44d57b4, ec521283, 2f6a0176, 198846c5, c047e6d2, 05ab867a, aa9aa9a9, 0a3076e2, 610fb9ab, aaacdeef, b0915f60; review fixes cd6c5864, d24f6859, e6b953eb, 27c52295, 09d2f120 | every `run/build-*.sh` builds on `.stage4` except the four MinecraftForge-listener builders (chain, damage-events, server-events, world-events), which stop with "canary retired" (their gates m41/m47/m48/m49 are C5's). |
| genuine-boot | 122bf712, a1221860, 14fb5fe7, 08ce2ea2; review fixes d287e2a7, 61f91ffd, 73a4314c | first genuine boots (ladder below) and three kernel defects fixed (walls W1-W3). |

## Installer facts (measured)

- Built from local caches: `patched-mc-neoforge-26.2.jar` sha1 `67bbf24f850965e5943a1bfe0295ecb701d4465b` (the same as
  `.stage4`'s); link check base-first and carrier-first both `dangling references: 0` (13,394 classes).
- Profile: `--gameJar …/net/forbric/patched-mc-neoforge/26.2-26.2.0.93/…`, one
  `--runtimeJar …/net/forbric/neoforge-runtime/26.2.0.93/…`, `--nativeReference …/net/forbric/vanilla-reference/26.2-26.2.0.93/…`.
- Old-install refusal (launch review fixes, `launchfix/reverse.sh`): the merged base, the switch
  `-Dforbric.launchInputCheck=off`, the Forge interop jar as NEO_RT, `MERGED`/`FORGE_RT` set, and the exact old profile
  shape (merged base + one joined `forge-runtime-interop.jar:neoforge-runtime.jar` `--runtimeJar`) each exit 2 with the one
  old-install line, naming all three signals of D9 for the old profile.
- Smoke at the launch-review tip fb50d1a8 (zero-mod dedicated server): no `[Forbric/Install]` line,
  `[Forbric/Boot] game base server entry loaded`, carrier pack `forbric/carrier/2-neoforge-runtime`, `Done (0.467s)!`,
  exit 0. First wall-like signal: `[Forbric/Anchor] 68 of 116 declared repair(s) landed, and 8 did NOT` (the eight C3
  candidates; C3 removed them).

## Boot ladder on G (genuine-boot lane)

| step | what | result |
|---|---|---|
| 1 | dedicated server, zero mods | PASS: `Done (1.565s)!`, save-all, /stop, "All dimensions are saved", exit 0 |
| 2 | server + fabric-api 0.155.2 + forbricneolive.jar | PASS: `[Forbric/Catalog] 2 installed mod(s) … 1 Fabric, 1 NeoForge, 0 MinecraftForge`; LoadingModList seeded 1 NeoForge + 37 Fabric; every SETUP phase DELIVERED + DEFERRED; 20 NeoForge server ticks; exit 0 |
| 3 | client, zero mods, title screen + step 1's world | PASS: `client-ready after 60 world tick(s)`, NeoForge's "Mods (Forbric)" button opens KernelModListScreen, clean disconnect, exit 0 in ~24 s |
| 4a | client-merged-pack (97 jars) through gate-m9 on a clone | run 1 W1, run 2 W2; after both fixes: joins, survives 60 ticks, mods and config screens, leaves cleanly; 145 PASS / 26 FAIL, all classified below |
| 4b | client-neo-pack (63 jars) | STRICT stops on 2 CONFIRMED required findings (W4, native); without the 3 jars NeoForge 26.2.0.93 cannot run natively: joins, tooltips, Controlling's screen swap, 62 mods listed, exit 0 |
| 5 | BCLib 26.201.2 + wunderlib + worldweaver + fabric-api, server | Done, exit 0; two required BCLib shears wraps unattached (W5) |
| 6 | FancyMenu 3.9.12 NeoForge client | PASS: its `@Inject` on NeoForge's own `ClientHooks.initClientHooks` call in `Minecraft.<init>` attaches with 0 injection warnings |

The data-map C3 trigger was present on every boot: `[Forbric/Worldgen] NeoForge's own reload path already applied data
maps for 6 registries — the kernel's about-to-start fallback stood down` (C3 deleted the fallback in 280e84b9).

## Walls

**W1, 97-mod client kicked one second after joining.** `Client disconnected with reason: Internal Exception:
java.lang.IllegalStateException: Unknown addon`. NeoForge's `ServerGamePacketListenerImpl.handleCustomPayload` calls
`super.handleCustomPayload` (javap G: `aload_0 aload_1 invokespecial ServerCommonPacketListenerImpl.handleCustomPayload`;
vanilla `0: return`; merged `ForgeHooks.onCustomPayload … pop return`), so play payloads reach fabric-networking's HEAD
injector, which serves only the configuration addon and throws otherwise. Stack:
`ServerCommonPacketListenerImpl.handler$bac000$fabric-networking-api-v1$handleCustomPayloadReceivedAsync <-
ServerCommonPacketListenerImpl.handleCustomPayload <- ServerGamePacketListenerImpl.handleCustomPayload(:2231)`.
Fixed: 122bf712 `FabricCommonPayloadMixinAdapter` (the handler returns at its head for a play listener), narrowed in
d287e2a7 (exactly one INSTANCEOF of a configuration addon whose false branch is the `new IllegalStateException`) with its
own reader of `-Dforbric.playPayloadFallThrough`.

**W2, integrated server crash on the first axolotl tick.** `AbstractMethodError: Receiver class …axolotl.Axolotl does not
define or inherit an implementation of the resolved method 'LivingEntity self()' of interface …ILivingEntityExtension` at
`ILivingEntityExtension.canDrownInFluidType:56 <- CommonHooks.onLivingBreathe:1445 <- LivingEntity.baseTick:454`.
fabric-entity-events' `effect.LivingEntityMixin` declares `private LivingEntity self()`, merged into LivingEntity; G's
LivingEntity declares no `self()` (the merged one had `public LivingEntity self()`), and interface dispatch skips private
methods. Fixed: a1221860 `MixinPrivateDefaultCollision` renames such a method with its calls
(`-Dforbric.mixinPrivateDefaults=off`); 61f91ffd keeps the name when a class nested in the mixin calls it.

**W3, fabric-block-api's open-trapdoor-over-a-tagged-ladder rule not attached.** `injection warning: mod=fabric-block-api-v1
… LivingEntityMixin handler=allowTaggedBlocksForTrapdoorClimbing … did not attach`. MixinDefaultCallbackTransport found
`IBlockExtension.makesOpenTrapdoorAboveClimbable` only through forwarders the merge had copied into LadderBlock/TrapDoorBlock
(javap G: 0 `makesOpen…` in LadderBlock and Block). Fixed: 14fb5fe7 walks the inherited interfaces.

**W4, client-neo-pack stops under STRICT: native, not fixed.** sophisticatedcore/sophisticatedbackpacks need
`ContainerScreenEvent$Render$Foreground`, which NeoForge 26.2.0.93 does not have (their range admits
`[26.2.0.6-beta,26.3.0)`); earthmobsmod 30.1.0 crashes `Ticking player` with `Cannot look up tracker by tag for
non-vanilla fluid: earthmobsmod:mud` from NeoForge's own `EntityFluidInteraction.getFluidTypeByTag`. Native NeoForge .93
behaves the same. The fixture needs builds that run on .93 (C5).

**W5, BCLib's tag shears on sheep and snow golems (reported, then fixed in C3).** NeoForge removed the shears branch from
`Sheep.mobInteract` / `SnowGolem.mobInteract` (javap G: `getItemInHand; invokespecial Animal.mobInteract; areturn`; vanilla
`ItemStack.is(Items.SHEARS)` at offset 10), so BCLib's two required `@WrapOperation`s attach nowhere. The owner decided
(D35) to restore it; C3 commit a162172a.

## Gates on G at the C1 tip (run, not edited; C5 rebaselines)

- gate-m1: 15 PASS, 2 FAIL (`every declared repair landed`, `no repair was handed its target and declined`: the eight C3
  candidates). C3 removed them (gate-m1 GREEN at the C3 tip).
- gate-m9-client (rerun at 73a4314c on a fresh clone): 145 PASS / 26 FAIL, line for line the same as run 3. Classes:
  anchor census ×2 and the mip-lowering line (C3 candidates, gone at the C3 tip); EventMux bridge lines ×5 (vacuous, D34);
  carriers 2→1 ×2, alias parity, loot index, MinecraftForge hears ×9, MinecraftForge lang, carrier asset namespaces, client
  commands (premise, C5); the pack-screen filter line (vacuous, D34); and one real defect, "Fabric's screen draw events reach
  an open screen" (merged `beforeExtract 48, afterExtract 48`, G `0, 0`), fixed in C3 by 9369f153.
- gate-m14: 33 PASS, 4 FAIL — the three "Forge-wrapped registries followed the server's ids" lines and "fabric-api's remap
  listeners were told", printed only by the MinecraftForge wrapper sync deleted in 3c72ff50 (vacuous, D34). The behaviour
  passes (id 1640 → 1639 → 1640, read-backs as themselves).
- gate-m22: GREEN (5 PASS). gate-m31: GREEN (13 PASS; 1764 chunks, 0 biome / structure-start / spawner-mob differences).

## Suite on G

| tip | tests | failed | note |
|---|---:|---:|---|
| 7b29c893 | 5827 | 175 | first run on G |
| 2a6aef6d (build-fixtures + fixes) | 5826 | 131 | classes (a) 34, (a)->C4 24, (c) 34, (d) 33, P1 6 |
| fb50d1a8 (launch + fixes) | 5833 | 130 | `LaunchInputCheckTest.theStagedBaseAndItsNeoForgeCarrierPass` resolved |
| 09d2f120 (canaries + fixes) | 5833 | 130 | same names |
| 73a4314c (genuine-boot + fixes) | **5845** | **124** | the 6 trapdoor (d) rows resolved; critic recount: exact name-for-name match |

Classes at 73a4314c: (a) premise was the merged base or MinecraftForge (C5 re-pin) 34; (a)->C4 (FORGE arms and census tables
regenerated with `Ecosystem.FORGE`'s removal) 24; (c) a C3 candidate whose subject is gone on G 34; (d) genuine-base Fabric
loss 26 (21 MixinFluidReactionAdapter/Carpet, 5 furnace remainder); P1 lost third-party/opt-in fixtures 6. C3 removed all
34 (c) and fixed all 26 (d) rows (see `stage4-c3-regress-server.md`). transferTest --rerun-tasks 57/0; python tools 52 OK;
run/compat 145 OK; installer linkGateTest 9 PASS.

## Open after C1 (owners)

- Genuine-base Fabric losses, owner's call (all decided and implemented in C3: D35, D36): fabric-screen-api screen draw
  events beside spruceui, fabric-item-api hookDamage, fabric-item-api furnace remainder (measured not lost), BCLib shears,
  Carpet's neighbour-change fluid rules.
- C5: every gate line listed above; CI on s4-c1 stays red until C5/C6 (the kernel-prepared ratchet step runs
  build-merged-base.sh, which calls the deleted `dev.merge_libraries`; the hidden-carrier job is vacuous).
- C4: prose naming the merged base or `forge-runtime-interop.jar` in KernelDataPacks:63, MergedBaseUncalledMethods:44,
  MixinFit:48,268, KernelGuestMixinAdapter:60, ForeignMixinTargets:39; 53 test files whose local constant `MERGED` now holds
  the genuine base (rename in C5).
- C6: link check reseeded with access levels; `ci-prepared-noforge.tsv` and the hidden-carrier CI job deleted; Doctor
  PEAK/RESIDENT re-measured. Stage 5: `forbric-installer/` (the old loader's installer).
