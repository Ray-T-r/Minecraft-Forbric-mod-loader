# Stage 4 C5c: swapping a mod's MinecraftForge build for its NeoForge build on an old Forbric world

Lane SWAP, wave 2 (2026-11-09/10), branch `s4-c5-swap`. Plan: map-swap W2-W6; D32 (native-controls is the swap
oracle), D54 (scope, representatives, fixture location), O1 (the owner rules on mod-format losses; no migration code
before that). This file is the measurement (W5) and the classification of every lost key (W6). The gate (W8,
gate-m59) and `expected-losses.tsv` wait for the owner's ruling (W7) and are NOT here.

Evidence: `~/Forbric-backups/stage4-notes/c5/swap/w2/` (logs, phase results, diffs, drill and classify outputs).
Frozen old side: `~/Forbric-backups/forge-swap/` (D54; MANIFEST, old-save/, old-config/, old-snapshot.json sha1
90de68b4…, both builds of every candidate).

## How it was measured

| arm | old side | new side |
|---|---|---|
| Forbric | `forge-swap-make-old.sh` on the old kernel (tag `tri-eco-26.2-final` = 187143a1, kernel jar sha1 aae280b5…, merged base ca98f147…): old server create + drill, config marks, old singleplayer client (kit datapack: death -> tombstone grave, inventory, armour, offhand, ender chest, xp 23, tombstone knowledge +7 / alignment +120, `silent_bond` effect), old server A/A; S0 = export of the A/A world | `native-controls.py swap --engine forbric` on this branch (`.stage4`, base 67bbf24f, carrier 0e3ce8d9): open with the NeoForge builds + drill, reopen; NEW singleplayer client on a clone of S0 (player rows) |
| native | `native-controls.py swap --engine native`: MinecraftForge 26.2-65.0.1 image (`cp -c` clone, upstream sha1 f0e4d503…): create + drill, config marks, A/A | NeoForge 26.2.0.93 image (installer sha1 97205829…): open + drill, reopen. Server only: no native client exists here, so player rows have no native measurement |

Same seed (8035262), flat world, same console script and drill (`forge-swap.py console` / `checks`), export box
`--chunks -2,-2,3,3` (probe chunks 0..1 plus the spawn area where the grave lands), drill at x 96..127 outside it.
Candidates (all jars local, D68): brickfurnace 26.2-5.2.1.0, classicpipes 1.1.7, packedup 1.1.1 + supermartijn642corelib
1.1.24a + configlib 1.1.8, tombstone 9.9.3, BiomesOPlenty 26.2.0.0.28 + TerraBlender 26.2.0.0.2 + GlitchCore 26.2.0.0.0,
journeymap 26.2-6.0.8, animalgarden-owl 1.2.8 + aquariuslibs 1.2.0 (sha1s in the fixture MANIFEST). The Forge->Fabric
journeymap row (D54) is not measured: the only local Fabric build is 6.0.9, not the Forge build's 6.0.8. classicpipes
needs `forge:[65.0.9,)` and has no native row (native Forge is 65.0.1).

Runs of record: Forbric = fixture `old2` (old side, 2026-11-10 01:31-01:42) + `forbric2` (server open/reopen) +
`newclient3` (NEW client; see the packedup row for why it ran without packedup and its libraries); native = `native4`.
Classification: `forge-swap.py classify --forbric forbric2 --native native4 --client newclient3/new-client.snapshot.json`
(`classify-with-client.json`, server-only `classify-server.json`).

## Results by facet

- **Phases**: every phase DONE and saved, both arms (old create 71.7 s, old client clean disconnect, old A/A 32.0 s;
  Forbric open 44.2 s / reopen 21.1 s; native create 50.0 / A/A 10.5 / open 33.9 / reopen 10.0 s).
- **Re-save proof** (R4): every probe chunk's `LastUpdate` rose: S0 1615-1618 -> NEW 2209-2212; native N0 859 -> 1461.
- **Old A/A** (old side's own reopen, before any swap): only ticking values moved (lit furnace, tombstone countTicks and
  global clock), the `file/forge-swap` pack left after its folder was removed, and chunk-ticket order. Nothing lost before
  the swap.
- **World blocks / block entities / entities**: 36891 = 36891 blocks, 20 = 20 block entities, 4 = 4 entities on the
  Forbric arm; per mod: brickfurnace 3, classicpipes 6, tombstone 4, BOP 5 blocks, all with the same state. Every chest
  slot, every machine inventory, the grave's stored inventory (tombstone grave at -1,-60,-1) equal.
- **Swap diff, Forbric vs native** (`classify-server`): 46 keys: 33 CHANGED (ticking furnace, tombstone counters,
  LoadingModList, chunk tickets), 9 GAINED (NeoForge's empty `NeoForgeData`, `neoforge:data_attachments` files,
  tombstone's `last_rite_ticks`), 3 MOD FORMAT (the three entities' tombstone ForgeCaps), 1 FORBRIC KEEPS (level.dat's
  `fml/Registries`, a stale MinecraftForge registry snapshot native drops; inert). 0 FORBRIC-CAUSED at world level.
- **Drill on the NeoForge open**: Forbric 23 PASS / 0 FAIL; native 20 / 3 (the three classicpipes checks: not loadable
  natively). Place, use (a loaded brick furnace smelts) and craft (a crafter crafts brickfurnace's furnace, packedup's
  conditional backpack recipe through corelib's `supermartijn642corelib:conditional`, tombstone's marble) pass.
- **Log absences** (map-swap §4): Forbric open: one line, `Missing data pack forbric/carrier/1-forge-runtime-interop`
  (expected, F8). Native: 16 `Missing data pack mod:<id>` (NeoForge's `mod_data` replaces them).
- **Data packs**: S0 `forbric/data/{animalgarden_owl,biomesoplenty,brickfurnace,classicpipes,packedup,
  supermartijn642corelib,terrablender,tombstone}` all still enabled after the open; the Forge carrier leaves, the NeoForge
  carrier keeps its id `forbric/carrier/2-neoforge-runtime` (D43).
- **Configs** (one non-default mark per candidate): brickfurnace `cookTimeFactor 1.25` (world serverconfig), packedup
  `maxBagInBagLayer 3`, tombstone `sniffer_range 7`: KEPT on both arms; the brick furnace's `cooking_total_time` is 250
  (200 x 1.25) on both, so the value is read, not only left on disk. NeoForge rewrites the Forge-format serverconfig and
  keeps `brickfurnace-server-1.toml.bak`, identically on both arms.
- **Mod world files**: `tombstone/saved_players/<uuid>/*.save` KEPT (byte-equal) after the server open and after the NEW
  client. `packedup/backpacks/` is empty in the fixture (see packedup).
- **SavedData**: `data/journeymap/{world_uuid,player_settings,player_waypoints}.dat` equal; tombstone `tb_global_clock`
  moved forward (a clock).

## Per-mod table (for the owner's ruling)

| mod | what survives (Forbric) | lost on Forbric | lost on native | Forbric-caused | mod-format loss (native-identical) |
|---|---|---|---|---|---|
| brickfurnace | 3 machines (state, inventory, progress keeps cooking), chest of its items, player inventory/ender stacks, server config (1.25 read and applied); drill place/use/craft PASS | nothing | nothing | none | none |
| classicpipes | 6 pipe blocks + block entities (empty `ForgeCaps` dropped, nothing in them), chest of pipes, player's pipes; drill PASS | nothing | no native row (needs Forge >= 65.0.9; native is 65.0.1) | none | none measured |
| packedup (+corelib, configlib) | backpack items in chests and the player's inventory, recipe (crafter, through corelib's conditional recipe type), common config | **the NEW client does not start**: `Container screen registered with null menu type!` in `RegisterMenuScreensEvent` (crash `newclient2`). Same root on the server: `PackedUp.container` is never set, so opening a bag cannot work | not measured (no native client) | **OPEN** (root cause below); backpack contents NOT measured: the old client's O press opened journeymap's fullscreen-options screen instead (same key), so the fixture's backpack has no inventory | none known |
| tombstone | decorative grave, marble, chest items, the death grave block entity with the dead player's inventory, `tb_global_clock`, `tombstone/saved_players` file, player `PlayerPersisted` keys (`tb_grave_locations`, `tb_cooldowns`, last-ground location; Forbric reads them from `NeoForgeData`, which the old kernel wrote beside `ForgeData`), config | entity ForgeCaps `cap_familiar` (wolf, owl, zombie), `cap_servant_entity` (zombie); player ForgeCaps `cap_tombstone` (knowledge 8, alignment 120, perks, watcher_knowledge) and `cap_protected_entity`; the player's `tombstone:silent_bond` effect (the NeoForge build has `beyond_the_grave_bond` instead) | the same entity ForgeCaps (measured); player: ForgeCaps and the effect by static evidence (NeoForge never reads `ForgeCaps`; `silent_bond` is not in the NeoForge build), and natively also the `PlayerPersisted` keys (native NeoForge reads only `NeoForgeData`, the native Forge build wrote `ForgeData`) | none | entity caps x2 kinds, player caps x2, the renamed effect -> **O1** |
| BiomesOPlenty (+TerraBlender, GlitchCore) | 5 blocks (log, planks, white sand, rose quartz, leaves), chest items, the fir boat entity, biomes of the flat world, configs; drill PASS | nothing | nothing | none | none |
| journeymap | `world_uuid`, `player_settings`, `player_waypoints` SavedData equal; server config | nothing | nothing | none | none |
| animalgarden-owl (+aquariuslibs) | the owl entity (custom name, NoAI, persistence), spawn eggs in chest and inventory; drill PASS | the owl's tombstone `cap_familiar` (tombstone's, counted there) | same | none | (tombstone's) |
| loader-level | world: blocks, BEs, entities, SavedData, packs, configs | player attribute `forge:swim_speed` (MinecraftForge's own attribute; NeoForge's is `neoforge:swim_speed`) | same by static evidence | none | MinecraftForge platform attribute, not a mod's |

"Forbric keeps, native loses" rows (O1 asks the owner to confirm them): the player's tombstone `PlayerPersisted` keys
(read from `NeoForgeData`), and level.dat `fml/Registries` (inert).

## W6: Forbric-caused losses

**One, OPEN: supermartijn642corelib's `@RegistryEntryAcceptor` fields are never filled on NeoForge, so packedup (and any
mod using corelib's acceptors) gets null registry objects; on a client that is a crash at startup.**

- Symptom: NEW client with the NeoForge builds crashes in `MenuScreens.init` -> `RegisterMenuScreensEvent` for packedup:
  `java.lang.RuntimeException: Container screen registered with null menu type!` (crash report in `w2/newclient2/`).
- Root cause (javap of `supermartijn642corelib-1.1.24a-neoforge-mc26.2.jar` and NeoForge 26.2.0.93): corelib gathers
  the annotated fields on `FMLConstructModEvent` (posted by the kernel: "posted FML construct to 14 NeoForge mod(s)"),
  but applies them only from its mixin `GameDataMixin` into `GameData.postRegisterEvents()`: `@ModifyVariable(STORE)` on
  the `RegisterEvent` local and `@Inject(INVOKE ModLoader.postEventWrapContainerInModOrder, shift AFTER)` ->
  `RegistryEntryAcceptor$Handler.onRegisterEvent`. The kernel never runs `postRegisterEvents`: it dispatches
  `RegisterEvent` itself (`KernelNeoRegistries.fireRegisterEvents`) and calls only that method's tail
  (`KernelLifecycle.java` ~:727-750, which documents why the genuine method is not called: it double-fires every
  DeferredRegister once ModList is published and reverts the registries on error). So the guest mixin applies and never
  runs; nothing logs it.
- Not a swap property: any NeoForge instance with corelib-acceptor mods is affected, on any world.
- Why not fixed here: the fix is in the kernel's NeoForge registration lifecycle (`KernelLifecycle.java`,
  `KernelNeoRegistries.java`), and every way to give guest mixins a live `postRegisterEvents` changes how every NeoForge
  mod's `RegisterEvent` is dispatched (order, one event per registry vs one per bus, the error path), which needs the
  registration gates (m7, m9, m21, ...) re-run. Candidate shapes, for whoever takes it: (a) let the kernel's per-registry
  dispatch run through the genuine `postRegisterEvents` with `ModLoader.postEventWrapContainerInModOrder` redirected
  inside `ModLoader` (not at the call site, so a guest's INVOKE point still matches) to the kernel's bus list, and drop
  the kernel's copy of the tail; (b) a liveness rule that retargets injectors aimed at an uncalled
  `postRegisterEvents` call site onto the kernel's dispatch loop.
- Measured only with corelib/packedup. The NEW client was therefore run without packedup, corelib and configlib
  (`newclient3`) to measure every other mod's player rows; that run's backpack-item and `packedup:obsidian_backpack`
  advancement losses are the absent mod, not the swap.

No other Forbric-caused loss: every world key Forbric loses, native loses too (the three tombstone ForgeCaps), and the
player-only losses are ForgeCaps, a renamed effect and MinecraftForge's own attribute, which native NeoForge cannot read
either.

## Found on the way (not swap losses)

- Old Forbric (tag tri-eco-26.2-final) bound packedup's open-bag key and journeymap's `key.journeymap.fullscreen_options`
  both to O; the O press paused the game ("Saving and pausing game...") and opened no bag. `forge-swap.py` now rebinds
  packedup's to keypad 7 in the client's options.txt.
- Native MinecraftForge 65.0.1's A/A reopen did not tick the forceloaded probe (furnace unchanged across the reopen);
  the native open did. The native arm's re-save proof is taken after the NeoForge open.
- `chunk_tickets.dat`'s ticket list changes order on every save: 10 "differences" per reopen in world-snapshot.py's
  diff (feature request to UPG: treat it as unordered).

## Not done (waits for the owner, D54/O1)

W7 ruling on the mod-format rows (tombstone caps, the renamed effect; the "Forbric keeps" rows), then W8 gate-m59 +
`expected-losses.tsv` on the frozen fixture, then any migration code. The packedup backpack-content row needs the corelib
fix first (NEW side) and a fixture whose backpack was filled (old side; the keypad-7 rebinding is in place for that).
