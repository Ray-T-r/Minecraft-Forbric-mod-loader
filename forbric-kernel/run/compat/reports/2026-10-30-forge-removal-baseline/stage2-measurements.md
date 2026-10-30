# Stage 2.0: the genuine NeoForge base measured against the merged base (2026-10-30)

No code from these measurements is merged; the tools live as patches in the session scratchpad. Roots: MERGED =
the .93 stage; GENUINE = the same with the base replaced by `patched-mc-neoforge-26.2.jar` (sha1 `67bbf24f…`,
NFRT `gameJarNoRecomp`) and no MinecraftForge carrier; GENREF = GENUINE plus the merged jar's
`META-INF/forbric/**` (isolates the missing native-reference index from the changed bytes).

## Repairs (every transformer KernelBoot registers, read from its own bytecode, replayed over every class)

| | merged | genuine | genuine, no index | genuine + Forge carrier |
| --- | ---: | ---: | ---: | ---: |
| declared anchors + claims | 205 | 203 | 203 | 205 |
| landed | 196 | 132 | 131 | 161 |
| declined (REQUIRED) | 0 | 39 | 40 | 39 |
| target in no jar | 1 | 30 | 30 | 1 |
| member references edits add that resolve nowhere | 0 | 10 | 10 | 3 |

Changed rows: 61 MinecraftForge-only, 13 merge-artifact candidates, 4 "moved?", 3 regressions, 1 regression without
the index. Findings that change the plan:

- `ItemUseOnInjector` half-applies on the genuine body: `CommonHooks.onPlaceItemIntoWorld` is pointed at
  `ItemStack.forbric$useOnItemFor`, which only the stood-down half adds (NoSuchMethodError on every item use).
- `UntrackedFluidEyeQueryInjector` lands but calls `EntityFluidInteraction.getTracker(TagKey)`, which only the
  merged base (its Forge half) has. The anchor ledger cannot see this; dangling references must be a Stage 4 gate.
- `FlowerPotBlock(Block,Properties)` stores null into `potted` on the genuine base too: the repair's constructor half
  is not a merge artifact.
- Forge transformers do not stand down without the carrier: they splice net.minecraftforge references into Entity,
  Level, BlockEntity, ClientLevel and CompoundTag. 26 Forge-serving rows still land on genuine, so Forge code must be
  removed by purpose, never by "it no longer lands".

## Census tables and Fabric mixin fit

Shipped tables equal a MERGED regeneration byte for byte. On GENUINE: NEOFORGE rows vanish; FORGE rows do not (the
generators still read forge-patched) and must go with the enum value. FABRIC: native-only +79 / −9 (+79 = the 13
members only the Forge half kept + 66 duplicate lambdas; 71 of them were "never runs" rows, so their failure mode
changes from never-runs to unresolved target), renames +10 / −13, uncalled −79, stubs −8 / +2
(`ItemStack.hurtAndBreak` and `processDurabilityChange` now target LivingEntity), early returns +4.
MixinFit over 10,968 guest mixins: FIT/PARTIAL/UNFIT/HAZARD 7776/379/32/6 merged, 7726/432/35/0 genuine,
7765/393/35/0 GENREF — 26 mixin-level changes, 11 of them caused only by the missing index. fabric-api lost vanilla
anchors 61 → 64. Weave suite: merged 577/0, genuine 568/9, GENREF 570/7 — none caused by the genuine bytes (fixtures
that depend on merge-only table rows, and the missing index).

## Throwaway boot on the genuine base (dedicated server, NeoForge carrier only)

Zero mods reached Done after 6 walls, every one a kernel splice of MinecraftForge code into vanilla/NeoForge code
(capability composition, forge:condition funnel, Forge ingredient types, data-file judges, Forge loot conditions,
KernelNeoWorldgen calling the Forge worldgen audit outside its try). 77 server mods of client-merged-pack: STRICT
stop; a Forge attribute repair breaking NeoForge's own attribute event; then fabric-api leaves 13 vanilla datapack
registries empty because the index is missing — a resources-only sidecar with the FABRIC half fixes it, FABRIC +
NEOFORGE halves bring the whole pack to Done. Also measured: GameEventMultiplexer's ForgeEventFactory probe silently
drops all 66 game-bus bridges plus the game instance and started duties; KernelContainers falls back to its own
container for 74 NeoForge mods without Forge's UnsafeHacks; universal Fabric+NeoForge+Forge jars (collective,
doubledoors, nutritiousmilk) are lost because AbiAudit still judges their suppressed Forge half.

## preloadClientResources

gate-m9 with preload off: the client joins and leaves cleanly; the only failing check is the gate's own check of the
preload. EnhancedVisuals logs 21 missing-resource lines and CreativeCore one default-style error during setup, as on
genuine NeoForge, which runs ClientModLoader.finish() before the first reload (bytecode offsets 3081 < 3155).

## Not gated, found on the way

client-neo-pack is stale on both .88 and .93: jei 30.14.0.87, sophisticatedcore 1.4.90 and sophisticatedbackpacks
3.25.83 call `ContainerScreenEvent$Render$Foreground`, removed in NeoForge 26.2.0.40-beta, so STRICT stops the client;
with them removed the integrated server crashes on `earthmobsmod:mud` in `EntityFluidInteraction.getFluidTypeByTag`
(the merged base's Forge fluid tracker). No gate uses this pack.

## Behaviour changes on the merged base

Stage 2 is behaviour-preserving on the merged base with one deliberate exception, from lane relocate (the kernel's own
server duties no longer ride the MinecraftForge bridge pass). It shows only under `-Dforbric.unifiedEvents=off`, or when
something in the bridge pass fails; with the switch on and every bridge installed, the listeners, their events, priorities
and order are what they were.

- **The duties now run with the switch off.** `KernelServerDutiesInstaller` puts them on NeoForge's bus from
  `KernelLifecycle.startGameBuses`, right after `GameEventMultiplexer.install`, whatever the switch says. Before, three
  of them were installed inside that pass, after its switch check, so the switch silently dropped them: the dedicated
  server's Fabric game-instance fallback (`ServerAboutToStartEvent`, HIGHEST), the load report and dedicated-server
  compatibility decision (`ServerStartedEvent`, LOWEST), and the `Block.tryDropExperience` relay (`BlockDropsEvent`,
  LOWEST; it was inside the MinecraftForge break bridge). The data-map watch already ran with the switch off and still
  does. Pinned by `KernelServerDutiesGenuineBaseTest.withTheBridgesSwitchedOffTheProductionPassStillInstallsEveryDuty`,
  which runs the production pass under the switch on both bases.
- **A STRICT run with the switch off halts at ServerStarted.** With confirmed findings, the compatibility decision now
  stops a dedicated server from its `ServerStartedEvent` listener ("FATAL: a required feature failed during world
  loading"), not at the first tick from `LateServerCompatibility`, which applied the same decision one tick later and
  still does. Of the gates that switch the bridges off, m47, m48, m49 and m57 use the `continue` policy, m4's control
  passed under the lane's run, m25 only names the switch as a RED recipe, and m41's negative control (default policy,
  one canary mod) was not rerun.
- **The dead-event judgement and the report are separate listeners.** `KernelGameServerLifecycle.installStartedDuties`
  (MinecraftForge's: the login gate, `DeadEventAudit.judgePending`) and `KernelServerDuties.installStartedDuties` (the
  kernel's: the report and the decision) are two LOWEST listeners on `ServerStartedEvent`, the kernel's added after the
  whole bridge pass and so run after the judgement (NeoForge runs one priority's listeners in the order they were added;
  pinned by `onTheMergedBaseTheProductionPassPutsTheKernelsWorkAfterMinecraftForgesDuties`, read off NeoForge's own
  listener list). A throw in the judgement no longer skips the report and the decision. With the switch off the
  judgement is not installed at all (it is part of the switched-off pass, as before), so the report written then carries
  no dead-event findings.
- **The relay is on NeoForge's bus itself**, not on the break bridge's stand-down view. Equivalent, because MinecraftForge's
  `BreakEvent` is in `DeadEventAudit.NO_NATIVE_PATH` and no scope stands it down; pinned by
  `theExperienceRelayIsNotStoodDownWithTheBreakBridge`.
- **Log lines.** The partial-mixin and unmodelled-accessor censuses (gate-m12 reads the first) are logged by
  `KernelLifecycle.reportGuestCensuses` right after the subscriber pass, in the same words, each isolated; they now also
  print when that pass returns early (no bus API of either family). `KernelServerDutiesInstaller`'s own lines say
  `[Forbric/Duties]`; the data-map-watch lines it took over said `[Forbric/EventMux]` (only failure and debug lines; no
  gate or test greps them). `RegistryRevertInjector`'s transform-time line now prints after `RegistrySyncParityInjector`'s
  flush line; no gate reads their order. The multiplexer's started-duties install warning names only the judgement and
  the login gate now.

The `[Forbric/EBS] registered N @EventBusSubscriber class(es) on the game bus` line gate-m4-canary reads was not moved,
because it is not on the MinecraftForge path: without MinecraftForge `ForgeBusApi.resolve` answers null, the loop skips
that family's subscribers one by one, and the line is printed before the two MinecraftForge passes (`reportDeadEvents`,
`CapabilityUseAudit`). The throwaway boot above printed it on the genuine base (carriers `none, neoforge-runtime.jar`:
"registered 38 @EventBusSubscriber class(es) on the game bus"), and `KernelEventSubscribersGenuineBaseTest` prints it there
for a NeoForge subscriber and counts it with gate-m4-canary's own grep.

## Access census: one more merged-only wider member

`Hud.extractHotbarAndDecorations(GuiGraphicsExtractor, DeltaTracker)` is `public` on the merged base and `private` in
vanilla; the genuine base does not declare it at all. The Stage 2.4 twin copies vanilla's declaration, so on the genuine
base it comes back `private`, narrower than the merged base's. Nothing in the merged or genuine jar calls it (it is dead
on both), and its consumers are Mixin injectors (fabric-rendering's seven `@WrapOperation`s, apoli, bettermounthud,
tcdcommons), which do not care about access. It belongs on the 2.0 access list all the same: a mod that linked against
the merged base's `public` would get an IllegalAccessError on the genuine base.

## Stage 4 obligations (recorded during the Stage 2 fix round)

The vanilla-reference sidecar is measured against a base and versioned like it. Today every place that produces,
names or locates it is keyed to the merged base, the artifact Stage 4/5 removes; each must follow the new base:

- `Installer.artifactVersion`: `net.forbric:vanilla-reference` takes `patched-mc-merged`'s version
  (`Pins.FORGE-Pins.NEOFORGE`), not the plan's bare `<mc>`, because a published coordinate's bytes never change and
  the sidecar's bytes depend on the base. With the genuine base it must take that base's version.
- `Installer` (supplied `--artifacts` path) builds a missing sidecar from `artifacts.get(ArtifactBuilder.MERGED)`;
  `ArtifactBuilder.build` measures it against `merged.file`; `DevPrepare` stages it beside the merged base.
- `run/compat/push-and-run.py`: `with_native_reference` derives the library's version from the profile's
  `net.forbric:patched-mc-merged` entry (a profile without one is a ValueError); `local_vanilla_reference` is handed the
  `patched-mc-merged` artifact (default or `--artifact` override) and now checks the staged sidecar's recorded base SHA-1
  against it.
- `run/vanilla-reference-jar.sh` locates the staged copy as `<dir of base>/../vanilla-reference/`, one path for both the
  merged and the genuine base (which sit side by side in a stage); since this round it hands one out only while its
  recorded base SHA-1 matches the base being launched, so the two cannot be mixed up silently, but Stage 4 must stage the
  sidecar measured against the genuine base there. `run/compat/soak-run.py` and `tools/dev.py` launch with the merged
  base path (`STAGED_BASE`) and the sidecar built for it.
- On a base that embeds no index the kernel has no second opinion: `NativeGameReferences` falls back per class to the
  base's embedded index only where the base embeds one (the merged base). With the genuine base a stale sidecar costs
  evidence (a changed class without a copy has no FABRIC reference, said once), so its provenance must be right.

Twins that are added at run time (`VanillaMemberTwinInjector`) are absent from the raw genuine jar: the Stage 4 census
regenerations (carrier-stubs, uncalled-methods, native-only-methods, MixinFit liveness) must read the base after that
transformer, or the AxeItem carrier-stubs row vanishes and twinned members read as absent.

On the genuine base with the twins but with `-Dforbric.customPayloadInteropSeam=off`, a vanilla-compiled caller of
`CustomPacketPayload.codec(FallbackProvider, List)` reaches NeoForge's own `$1.findCodec`, which calls
`NetworkRegistry.getCodec(id, null, null)` (the delegate twin passes no protocol or flow). With the seam on, the lookup is
`PayloadInterop.findCodec` with that null context, which VanillaCallerPayloadLookupTest runs. Whether NeoForge's
`getCodec` tolerates the nulls was not measured; the switch should not be offered on the genuine base until it is.

**Deferred twin: `RegistryDataLoader.load(LoaderFactory, List, List, Executor)`.** Measured: vanilla and the merged base
declare the private four-argument loader, and the merged base also keeps vanilla's `lambda$load$0..5` beside NeoForge's
same-named lambdas (vanilla's `lambda$load$0(List, LoaderFactory, List, Executor)` and NeoForge's
`lambda$load$0(List, LoaderFactory, List, Executor, boolean)`; `lambda$load$3/4/5` likewise collide by name with other
descriptors). The genuine base has only the five-argument `(…, boolean)` overload and NeoForge's lambdas. Nothing calls
the four-argument loader on the merged base either: it is the dead loader `FabricRegistryLoaderMixinAdapter` exists for,
which moves fabric-registry-sync's injectors that name it (and its call) onto the live widened pair, reading each
selector against the class the mod was compiled against (the FABRIC reference) rather than by spelling. A twin would
copy vanilla's body together with its lambdas; `VanillaMemberTwinInjector` keeps a lambda's name when the class has no
method of that name and descriptor, so `lambda$load$0` would come back as a second overload of NeoForge's live lambda —
re-creating on the genuine base the very ambiguity that adapter has to refuse on the merged base (a bare name-only
`lambda$load$0` selector that "binds a single method but matches several"), where today it matches NeoForge's one live
lambda. So the twin is not a member to add blindly: the adapter's owner decides, on the genuine base, whether its move
(callee `C` → the one called overload `C'` whose parameters are `C`'s followed by the carrier's) needs `C` declared in the
target at all, or works from the FABRIC reference alone. If it needs it, the twin's lambdas must be renamed
(`forbric$vanilla$…`) rather than kept, and the adapter's selector reading checked against that.

**Turn `-Dforbric.modFilePerJar` on: one `IModFile` per jar for NeoForge mods.** Stage 2 builds the native shape and
leaves it OFF, because turning it on changes what a mod walking `ModList.getAllScanData()` meets on the merged base.
Stage 4 flips the default (`KernelModFile.perJar()`), on either base. The evidence:

- Native semantics, from the 26.2.0.93 carrier's own bytecode (`javap -c` on `ModFile` and `ModFileInfo` in
  `neoforge-runtime.jar`). FML builds one `ModFile` and one `ModFileInfo` per jar. `ModFileInfo.mods` is
  `getConfigList("mods").stream().map(ModInfo::new).toList()`, every `[[mods]]` entry in file order. `ModFile.id` is
  `modFileInfo.getMods().getFirst().getModId()`, and `getModInfos()` is `modFileInfo.getMods()`. `ModList.getAllScanData()`
  streams `sortedList → getOwningFile → getFile → distinct → getScanResult`, so a jar holding several mods is one entry.
- What the switch gives: every NeoForge declaration of a jar shares the jar's one `KernelModFile` and one
  `KernelModFileInfo`. The info lists all of them in `[[mods]]` order, with any `@Mod`-only id after them (the kernel
  gives such a class a container). The file is named after the first entry, whichever mod was built first. A Fabric
  mod the kernel describes over the same universal jar (`KernelDeclarationReaders`) keeps a file of its own, and so does
  a NeoForge-path info whose declaration names another ecosystem. `KernelModFilePerJarTest` pins each of these: the
  walk-up, the id, the cross-ecosystem case, and the default. Each test was red-checked by breaking its code path.
- Measured on gate-m21. Its default boot prints `[ForbricNeoLive/SCAN] ModList.getAllScanData(): 2 file(s), 10 annotation(s)`
  and lists the canary's jar 2 times, which is today's behaviour. Its second boot, with the switch on, prints
  `1 file(s), 5 annotation(s)` and lists the jar once. The MinecraftForge canary's line is `2 file(s), 22 annotation(s)`
  in both boots (its files come from `KernelForgeContainers`, which this switch does not touch).
- When the default flips: `KernelModFilePerJarTest.byDefaultEveryModKeepsAFileOfItsOwn…` inverts (or goes), gate-m21's
  two "default" checks move to "1 time(s)" / exactly 1, and its second boot can go. Not measured with the switch on: the
  m9 pack and the real packs (JEI's `@JeiPlugin` sweep, Jade's provider walk). Those walks now meet each multi-mod
  NeoForge jar once; on the merged base today they meet it once per mod, over the same shared index object.
