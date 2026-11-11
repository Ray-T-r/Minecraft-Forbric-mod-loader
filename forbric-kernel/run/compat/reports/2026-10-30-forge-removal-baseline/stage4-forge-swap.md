# Stage 4 C5c: swapping a mod's MinecraftForge build for its NeoForge build on an old Forbric world

Lane SWAP, wave 2 (2026-11-09/10), branch `s4-c5-swap`. Plan: map-swap W2-W6; D32 (native-controls is the swap
oracle), D54 (scope, representatives, fixture location), O1 (the owner rules on mod-format losses; no migration code
before that). This file is the measurement (W5) and the classification of every lost key (W6). The gate (W8,
gate-m59) and `expected-losses.tsv` waited for the owner's ruling (W7); they are in "Wave 2b" at the end (D77).

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
(`classify-with-client.json`, server-only `classify-server.json`). After the wave-2 review the classifier was fixed
(4565d2fd, review A-1/A-2) and both tables re-derived from the same snapshots: `w2/review-fixes/` (with this
branch's world-snapshot.py, 01b883dd's frozen file, and with UPG tip's, 6ce60ac9's rules). The counts below are the
re-derived ones; the first tables' counts are kept in brackets where they were quoted before.

newclient3's mod set (its log, line 4): BiomesOPlenty, GlitchCore, TerraBlender, animalgarden-owl, aquariuslibs,
brickfurnace, classicpipes, journeymap, tombstone (NeoForge builds) - packedup, supermartijn642corelib and
supermartijn642configlib left out. Keys posted: `+4,f2,+2,o,+3,6,+1,escape,+2,f2` on `client-ready after`
(new-client.result.json). It was run as `w2/new-client.sh` with those three jars not in the client's mods/; the exact
command line was not kept, so the saved script alone does not reproduce it (it copies every mods-neo jar).

## Results by facet

- **Phases**: every phase DONE and saved, both arms (old create 71.7 s, old client clean disconnect, old A/A 32.0 s;
  Forbric open 44.2 s / reopen 21.1 s; native create 50.0 / A/A 10.5 / open 33.9 / reopen 10.0 s).
- **Re-save proof** (R4): every probe chunk's `LastUpdate` rose: S0 1615-1618 -> NEW 2209-2212; native N0 859 -> 1461.
  The after-open readings were taken with `w2/lastupdate.py` on the run worlds, which were deleted afterwards, and the
  readings were not saved (the exports strip LastUpdate); S0's 1615-1618 re-reads on the fixture's old-save. The
  tombstone clock in the diffs corroborates the re-save: Forbric 1678 -> 2209 on the open (2275 on the reopen), native
  928 -> 1461 (1526).
- **Forbric reopen** (S1 -> S2, map-swap §4 step 6): `forbric2/diff-reopen.json` has 16 differences: 3 ticking
  block-entity values (the lit furnace, the grave's countTicks), the tombstone clock, 10 chunk-ticket reorders and 2
  rule lines about the player file a server never loads. Nothing lost on the reopen.
- **Old A/A** (old side's own reopen, before any swap): only ticking values moved (lit furnace, tombstone countTicks and
  global clock), the `file/forge-swap` pack left after its folder was removed, and chunk-ticket order. Nothing lost before
  the swap.
- **World blocks / block entities / entities**: 36891 = 36891 blocks, 20 = 20 block entities, 4 = 4 entities on the
  Forbric arm; per mod: brickfurnace 3, classicpipes 6, tombstone 4, BOP 5 blocks, all with the same state. Every chest
  slot, every machine inventory, the grave's stored inventory (tombstone grave at -1,-60,-1) equal.
- **Swap diff, Forbric vs native** (`classify-server`, re-derived): 46 keys with 01b883dd's rules: 20 CHANGED (ticking
  furnace, tombstone counters, chunk tickets, native's level packs/brands), 13 LOADER METADATA (`fml/LoadingModList`),
  9 GAINED (NeoForge's empty `NeoForgeData`, `neoforge:data_attachments` files, tombstone's `last_rite_ticks`), 3 MOD
  FORMAT (the three entities' tombstone ForgeCaps), 1 NOT IN FORBRIC'S OLD WORLD (level.dat's `fml/Registries`: only
  native MinecraftForge writes it; the old Forbric world never had it, so it is neither a Forbric keep nor a loss), and
  **0 Forbric-caused candidates** (FORBRIC-CAUSED, FORBRIC CHANGED, NO NATIVE ROW). With UPG tip's rules (6ce60ac9,
  tickets unordered) the 10 ticket rows go: 36 keys, 10 CHANGED, still 0 candidates. [The first table said 33 CHANGED
  and "1 FORBRIC KEEPS (fml/Registries)"; that keep was a classifier artefact, review A-1. The first classifier could not
  report a value only Forbric changed (it said CHANGED), so its "0 FORBRIC-CAUSED" held only on reading every CHANGED
  row by hand (review A-2); the re-derived 0 is the verdict column's.]
- **With the NEW client** (`classify-with-client`, UPG tip's rules): 42 keys, 5 candidates, all player rows with no
  native measurement (NO NATIVE ROW): `active_effects[id=tombstone:silent_bond]` lost, `attributes[id=forge:swim_speed]`
  lost (it carried tombstone's `ghostly_shape_swim_speed` modifier), the player's tombstone ForgeCaps (`cap_tombstone`,
  `cap_protected_entity`), and two rows of packedup being absent from newclient3 (`Inventory[Slot=0]`, the obsidian
  backpack, and the `packedup:obsidian_backpack` advancement). With 01b883dd's rules the same table has 138 keys and 91
  candidates: the extra 86 are NO NATIVE ROW value moves of the player's session (recipeBook order, stats, XpSeed, the
  warden tick), which UPG's 6ce60ac9 rules remove. [The first table filed the effect and attribute losses under
  "CHANGED (a value moved; judged)" whole-list rows; they were found by hand (review A-2/B-5). Their MOD FORMAT verdict
  in the per-mod table is still a judgment: native's side is static evidence, there is no native client.]
- **Drill on the NeoForge open**: Forbric 23 PASS / 0 FAIL; native 20 / 3 (the three classicpipes checks: not loadable
  natively). Place, use (a loaded brick furnace smelts) and craft (a crafter crafts brickfurnace's furnace, packedup's
  conditional backpack recipe through corelib's `supermartijn642corelib:conditional`, tombstone's marble) pass.
  Cells the drill does not have (D54/F12: "a row that cannot be drilled says so"): **packedup place / use** - a backpack
  is an item, not a block, and opening one needs a client's screen (a dedicated server has no player to open it; the NEW
  client crashes before the world loads, W6); **tombstone use** - its uses (a grave giving back a player's items,
  knowledge and perks) need a player who dies and returns, which only a client phase has, and the NEW client pass did
  not drill them; its place and craft cells are drilled. classicpipes (not a gate representative) has place but no
  item-transfer use drill.
- **Mob effect not measured**: the console gives the wolf `tombstone:silent_bond`, and both old sides answered `Unable
  to apply this effect` (old2 old-create.log, native4 forge-create.log); no entity carried it, so the effect is measured
  on the player only.
- **Log absences** (map-swap §4): Forbric open: one line, `Missing data pack forbric/carrier/1-forge-runtime-interop`
  (expected, F8). Native: 14 `Missing data pack mod:<id>` (NeoForge's `mod_data` replaces them; the record said 16,
  which counted 2 classicpipes `Unknown block type` drill lines too, review B-5).
- **Data packs**: S0 `forbric/data/{animalgarden_owl,biomesoplenty,brickfurnace,classicpipes,packedup,
  supermartijn642corelib,terrablender,tombstone}` all still enabled after the open; the Forge carrier leaves, the NeoForge
  carrier keeps its id `forbric/carrier/2-neoforge-runtime` (D43).
- **Configs** (one non-default mark per candidate): brickfurnace `cookTimeFactor 1.25` (world serverconfig), packedup
  `maxBagInBagLayer 3`, tombstone `sniffer_range 7`: KEPT on both arms; the brick furnace's `cooking_total_time` is 250
  (200 x 1.25) on both, so the value is read, not only left on disk. NeoForge rewrites the Forge-format serverconfig and
  keeps `brickfurnace-server-1.toml.bak`, identically on both arms. journeymap's and BiomesOPlenty's (TerraBlender's,
  GlitchCore's) configs carry no mark, so a reset to defaults would not show: NOT measured (review B-2).
- **Mod world files**: `tombstone/saved_players/<uuid>/*.save` KEPT (byte-equal) after the server open and after the NEW
  client. `packedup/backpacks/` is empty in the fixture (see packedup): no bag was ever opened on the old side. Commit
  1598429f's message ("key presses posted to its window open a backpack and move two hotbar stacks into it") states
  the intent of that step, not a result; the script's header now says so and its MANIFEST records `packedup-bag`
  (7cbf1694, review B-4). The frozen fixture's MANIFEST predates that line; its bag is `none`.
- **Implicit swaps** (D54: recorded from the Stage 3 sweep, not measured): `stage3-sweep.tsv` (before -> after column)
  has 9 FORGE -> NEOFORGE rows, tri-loader jars whose NeoForge half now loads by itself (better-furnaces-saplings 1.4.0,
  CLMG-[UNIVERSAL] 1.1.0, dragon-egg-recipe 1.0, fastpath-og 1, glowing-glow-squids 3, minechess 1.1.3,
  norocketusage 26.9.0, silvers-better-wood-cutting-stone-cutter 1.7, SimpleRPC 4.1.4), and 83 FORGE -> FABRIC rows of
  48 distinct jars, most of them loader-cache or synthetic test jars (`forbric-<hash>`, `forbrictest-*`,
  `forbriclive-*`) and cached halves of real mods (geckolib, mcwbridges, nochatreports, spark, terrablender, xaerolib,
  xaerominimap, physicsmod, qubes-mouse-fix). The player changes no jar, yet the same ForgeCaps / ForgeData question
  applies to what their Forge half wrote. Not measured.
- **Energy gap** (D54: recorded, no canary): no local same-id Forge/NeoForge pair stores energy (map-swap §5), so a
  Forge-capability energy buffer (ForgeCaps) against a NeoForge attachment is not measured; the C5b canaries
  (forbricenergy*) have different mod ids per loader and are not a swap pair. W10's synthetic canary stays optional.
- **SavedData**: `data/journeymap/{world_uuid,player_settings,player_waypoints}.dat` equal; tombstone `tb_global_clock`
  moved forward (a clock).

## Per-mod table (for the owner's ruling)

| mod | what survives (Forbric) | lost on Forbric | lost on native | Forbric-caused | mod-format loss (native-identical) |
|---|---|---|---|---|---|
| brickfurnace | 3 machines (state, inventory, progress keeps cooking), chest of its items, player inventory/ender stacks, server config (1.25 read and applied); drill place/use/craft PASS | nothing | nothing | none | none |
| classicpipes | 6 pipe blocks + block entities (empty `ForgeCaps` dropped, nothing in them), chest of pipes, player's pipes; drill PASS | nothing | no native row (needs Forge >= 65.0.9; native is 65.0.1) | none | none measured |
| packedup (+corelib, configlib) | backpack items in chests (drill `packedup.items.kept`, the chest block entity equal), recipe (crafter, through corelib's conditional recipe type), common config (marked, KEPT). **The player's inventory: NOT measured** - a server never loads the player file, newclient2 crashed before the world loaded, newclient3 ran without packedup (its log line 511 drops the player's `packedup:obsidianbackpack` as an unknown item) | **the NEW client does not start**: `Container screen registered with null menu type!` in `RegisterMenuScreensEvent` (crash `newclient2`). On a server the same root leaves `PackedUp.container` null, so opening a bag could not work - **inferred from corelib's code (javap), not read on a server** | not measured (no native client) | **OPEN** (root cause below); backpack contents NOT measured: the old client's O press opened journeymap's fullscreen-options screen instead (same key), so the fixture's backpack has no inventory | none known |
| tombstone | decorative grave, marble, chest items, the death grave block entity with the dead player's inventory, `tb_global_clock`, `tombstone/saved_players` file, player `PlayerPersisted` keys (`tb_grave_locations`, `tb_cooldowns`, last-ground location; Forbric reads them from `NeoForgeData`, which the old kernel wrote beside `ForgeData`), config | entity ForgeCaps `cap_familiar` (wolf, owl, zombie), `cap_servant_entity` (zombie); player ForgeCaps `cap_tombstone` (knowledge 8, alignment 120, perks, watcher_knowledge) and `cap_protected_entity`; the player's `tombstone:silent_bond` effect (the NeoForge build has `beyond_the_grave_bond` instead) | the same entity ForgeCaps (measured); player: ForgeCaps and the effect by static evidence (NeoForge never reads `ForgeCaps`; `silent_bond` is not in the NeoForge build), and natively also the `PlayerPersisted` keys (native NeoForge reads only `NeoForgeData`, the native Forge build wrote `ForgeData`) | none | entity caps x2 kinds, player caps x2, the renamed effect -> **O1** |
| BiomesOPlenty (+TerraBlender, GlitchCore) | 5 blocks (log, planks, white sand, rose quartz, leaves), chest items, the fir boat entity, biomes of the flat world; drill PASS. Configs: NOT measured (no mark) | nothing measured | nothing measured | none | none |
| journeymap | `world_uuid`, `player_settings`, `player_waypoints` SavedData equal. Server config: NOT measured (no mark; `world_files` skips serverconfig/) | nothing measured | nothing measured | none | none |
| animalgarden-owl (+aquariuslibs) | the owl entity (custom name, NoAI, persistence), spawn eggs in chest and inventory; drill PASS | the owl's tombstone `cap_familiar` (tombstone's, counted there) | same | none | (tombstone's) |
| loader-level | world: blocks, BEs, entities, SavedData, packs, configs | player attribute `forge:swim_speed` (MinecraftForge's own attribute; NeoForge's is `neoforge:swim_speed`) | same by static evidence | none | MinecraftForge platform attribute, not a mod's |

"Forbric keeps, native loses" row (O1 asks the owner to confirm it): the player's tombstone `PlayerPersisted` keys
(read from `NeoForgeData`); the native side of that row is static evidence (no native client). level.dat's
`fml/Registries` is NOT such a row: the old Forbric world never had it (only native MinecraftForge writes it), so the
earlier "Forbric keeps" verdict was the classifier's default, not a measurement (review A-1).

Which verdicts are judgments: the MOD FORMAT verdicts for the player's ForgeCaps, effect and attribute rows are the
lane's (native's side is static evidence); the fixed classifier reports those rows as NO NATIVE ROW losses.

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
- Weave shown by a run (review fixes, `w2/review-fixes/corelib-probe/`): a Forbric server on this branch with only
  packedup + corelib + configlib (NeoForge builds) and `-Dmixin.debug.export=true` reached Done and saved; the exported
  `net/neoforged/neoforge/registries/GameData.class` has corelib's `localvar$...$modifyRegisterEvent` at the
  RegisterEvent store and `handler$...$postRegisterEvents`, which calls `RegistryEntryAcceptor$Handler.onRegisterEvent`,
  inside `postRegisterEvents()`; packedup registered `menu=1`. The kernel's sources name `postRegisterEvents` only in
  comments (KernelLifecycle.java:727/736/744, KernelRuntimeClasses.java:197). Still NOT shown by a run: that the
  handler never executes and that this is the only cause (no probe read the acceptor field) - the fix's first step.
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
  diff. Done upstream: UPG's 6ce60ac9 (on s4-c5-upg, after the 01b883dd this branch fast-forwarded to) treats
  `tickets` as unordered, sorts the player's recipeBook lists and drops the warden tick counter. The re-derived tables
  above show both rule sets; once this branch rebases onto UPG's tip (or the stage merges both), W8 re-derives the
  counts with UPG's rules.

## Not done (waits for the owner, D54/O1)

W8 (gate-m59 + `expected-losses.tsv` on the frozen fixture) has THREE preconditions (review B-1): (1) the W7 ruling on
the mod-format rows (tombstone caps, the renamed effect, `forge:swim_speed`) and the "Forbric keeps" row (tombstone
PlayerPersisted); (2) the corelib `@RegistryEntryAcceptor` fix (W6, OPEN, Forbric-caused) - packedup is a gate
representative and its NEW side cannot be measured before it; (3) a re-made fixture whose backpack was filled (old
side; the keypad-7 rebinding is in place, not yet shown to work). Then any migration code. PLAN's SWAP acceptance
("Forbric-caused losses = 0, each fixed or proven native-identical") is NOT met while (2) is open.

## Wave 2b (2026-11-11): W8 — a filled bag, the new client with packedup, the re-derived candidates, gate-m59

Lane SWAP wave 2b, branch `s4-c5-swap` reset to the stage tip b9154084 (K1-K4 merged, so K3's GameData fix is in).
D77 (owner ruling O1, "document only") is the rule this section applies: gate-m59 and `canary/forge-swap/expected-losses.tsv`
pin exactly the native-identical rows, no migration code, Forbric-caused losses stay 0. Evidence:
`~/Forbric-backups/stage4-notes/c5/swap/w2b/` (old4, native5, forbric5, m59-derive, m59-green, m59-final, controls/).
This section supersedes "Not done (waits for the owner)" above: its three preconditions are met (O1 = D77; the corelib
fix = K3, merged; a fixture with a filled bag = below).

### (1) A filled packedup bag in the old Forge world

Why the earlier step could not work (javap of packedup 1.1.1, both builds): a backpack's stacks are not in the item.
The stack carries only `packedup:inventory_id`; the stacks live in `packedup/backpacks/inventory<id>.nbt`, written by
packedup's own `LevelEvent.Save` listener. The inventory is created only in `PackedUpCommon.openBackpackInventory`,
server side, when a player uses the bag or presses packedup's open-bag key (its packet ends in the same method); stacks
go in through the bag's slots, which call `BackpackInventory.setStackInSlot`. No command or datapack function reaches
either, a dedicated server has no player, and the native MinecraftForge arm has no client at all, so a console or
datapack fill does not exist; the key presses of wave 2 (9488359f) depended on where the cursor was and never filled one.

What does it now: `run/compat/ForgeSwapBagFill.java`, a dependency-free `-javaagent` that runs exactly the server half of
those two player actions with packedup's own classes, on the server thread: `createInventoryIndex(type)`, the
`inventory_id` component, `adjustSize`, then `setStackInSlot` for every stack of a source chest. It runs on the old
Forbric create (the obsidian backpack in the chest at 2,-60,8, from the chest at 4,-60,8), in the old singleplayer
client (the kit's backpack in hotbar 0, from the chest at 6,-60,8), and on the native MinecraftForge create
(`native-controls.py swap --create-jvm=...`, the chest's bag only: no native client). `forge-swap-make-old.sh` fails
unless both "bag filled" lines appear and the drill's new `packedup.bag.kept` passes; MANIFEST records what each saved
inventory holds (`packedup-bag-stacks`). Verified by reading the saved NBT (`forge-swap.py bags`), not by the log:

| where | inventory | what packedup saved (old A/A world) |
|---|---|---|
| chest 2,-60,8 (old Forbric create) | inventory0.nbt | diamond x9, diamond_sword x1 {custom_name, damage, enchantments}, brickfurnace:brick_furnace x2, biomesoplenty:fir_log x16, tombstone:grave_dust x5, animalgarden_owl:mob_spawn_egg x1 |
| player hotbar 0 (old client) | inventory1.nbt | emerald x7, classicpipes:copper_pipe x4, tombstone:white_marble x3 |
| chest 2,-60,8 (native MinecraftForge create) | inventory0.nbt | the same six stacks |

New fixture (D54): `~/Forbric-backups/forge-swap-w2b/` = forge-swap-make-old.sh run `old4` (2026-11-11 07:43-07:47,
old kernel 187143a1 / jar aae280b5, merged base ca98f147, tree b9154084; drill 24/0; old A/A 27 differences, all ticking
values, the removed kit pack and the merged-only-key rule lines the reopen rules print; nothing lost before the swap)
plus `native/` (run `native5`, the frozen native arm gate-m59 classifies against; `native/README.txt`). S0 sha1
660f98e5. **Note for the orchestrator:** `~/Forbric-backups/forge-swap/` was rewritten on 2026-11-10 20:47-20:51
(its MANIFEST: tree 9b59fe61), i.e. after the 19:55 cut D78 names, so it is the discarded Fable run's product; this lane
neither read its content nor used it. Wave 2's Opus fixture is `~/Forbric-backups/forge-swap-superseded/old2-20261110/`.

### (2) The new client pass with packedup, corelib and configlib

gate-m59 phase client (runs m59-derive, m59-green, m59-final): this tree's singleplayer client with all twelve
NeoForge builds in mods/ (its `[kernel-launch] mods:` line names packedup, supermartijn642corelib and
supermartijn642configlib) opens a clone of the fixture's old-save, reaches the world, saves and quits by itself (clean
disconnect, no crash). The player's backpack is still in hotbar 0 with `packedup:inventory_id` 1, both inventory files
are KEPT (same NBT; rewritten by the NeoForge build's own save: their mtimes move on the open), and `bags` lists the
same stacks. So the wave-2 rows "`Inventory[Slot=0]` lost" and "`packedup:obsidian_backpack` advancement lost" were the
absent mod (newclient3), as recorded then; with packedup present neither row exists.

Not drilled, and why: opening a bag in a screen on the NEW side. The server side of "use" is what the files show (the
NeoForge build loaded both inventories and saved them back unchanged, and `packedup.bag.kept` reads the bag's id on the
running server); a GUI open would need a click a background window does not take.

### (3) classify's candidates, re-derived on all seven candidates (with the new fixture)

Server arms (`classify-server`: forbric5 vs native5): 36 keys, **0 Forbric-caused candidates**: 3 MOD FORMAT rows (tombstone `cap_familiar` on the owl, wolf and zombie, plus `cap_servant_entity` on the zombie: lost
on both arms), 1 NOT IN FORBRIC'S OLD WORLD (`fml/Registries`), 10 CHANGED (ticking furnace, tombstone counters), 9 GAINED, 13
LOADER METADATA. Drill on the open: Forbric 24 PASS / 0 FAIL; native 21 / 3 (the three classicpipes
checks, not loadable on native MinecraftForge 65.0.1), `packedup.bag.kept` PASS on both. World files: Forbric
inventory0/inventory1 and tombstone's saved player KEPT; native inventory0 KEPT. Config marks KEPT on both arms.

With the new client (`pin.json` table of m59-derive): 40 keys — 10 CHANGED, 10 GAINED, 13 LOADER METADATA, 3 MOD
FORMAT, 3 NO NATIVE ROW, 1 NOT IN FORBRIC'S OLD WORLD — and **3 candidates, all D77 rows**: the player's
`active_effects[id=tombstone:silent_bond]`, `attributes[id=forge:swim_speed]` and its tombstone ForgeCaps
(`cap_protected_entity`, `cap_tombstone`), NO NATIVE ROW because there is no native client (static evidence, as in
wave 2). No packedup, brickfurnace, classicpipes, BiomesOPlenty, journeymap or animalgarden-owl row is a loss.

### (4) gate-m59 and expected-losses.tsv

`run/gate-m59-forge-swap.sh` (header = the manual) and `canary/forge-swap/expected-losses.tsv` (six rows, each the key,
mod, verdict and why exactly as measured): the three entity ForgeCaps rows (MOD FORMAT) and the player's ForgeCaps,
`silent_bond` effect and `forge:swim_speed` attribute (NO NATIVE ROW). These are D77's three items, measured: tombstone
ForgeCaps, its renamed `silent_bond` effect, MinecraftForge's `forge:swim_speed`. The bag measurement adds no row.

| run (fixture forge-swap-w2b) | phases | result |
|---|---|---|
| m59-derive (placeholder TSV) | open client pin | 6 unpinned = the six rows above, 1 stale (the placeholder); re-save check red on a reader bug (26.2 keeps regions under dimensions/; fixed before commit) |
| m59-green | open client pin | 27 PASS / 0 FAIL |
| m59-final, committed tip 5ae4de6a | open client pin | **27 PASS / 0 FAIL, GREEN** |
| controls/none | open pin | 23 / 0 |
| controls/drop-slot (vanilla chest slot 1 removed from the new world) | open pin | 22 / 1: only `unpinned`, naming `block_entities /minecraft:overworld 0 -60 0/Items[Slot=1]` |
| controls/chest-slot (its count 64 -> 63) | open pin | 22 / 1: only `changed`, naming `.../Items[Slot=1]/count` |
| controls/drop-row2 (the TSV's first non-player row deleted) | open pin | 22 / 1: only `unpinned`, naming the owl's ForgeCaps row |
| controls/drop-pack (forbric/data/packedup taken out of the new level.dat) | open pin | 22 / 1: only the packs check |
| controls/drop-region (the probe's r.0.0.mca deleted) | open pin | 21 / 2: the re-save proof and `unpinned` (every key of the deleted region), as the header says |

The first drop-row run (controls/drop-row) dropped the TSV's first row, a player row, which a server-only run sets aside
(--no-player): 23 / 0, a control that broke nothing it asked about. The control now drops the first non-player row
(drop-row2). The per-mod table of wave 2 stands with these changes: packedup — the player's inventory and both bags
measured, nothing lost (Forbric and, for the chest's bag, native); "Forbric-caused: OPEN" is closed by K3 (the new
client boots and plays with packedup); tombstone — unchanged, now pinned by gate-m59.

PLAN's SWAP acceptance: per-mod rows not complete: journeymap's and BiomesOPlenty's configs are still unmarked, as wave 2
said (owner: SWAP; see "Wave 2b review fixes"); Forbric-caused losses 0; m59 green equal to expected-losses.tsv exactly; re-save proof holds (LastUpdate
1780-1783 -> 2383-2392 on every probe chunk in every run but drop-region); red controls (a) chest-slot, (b) drop-row, (c) drop-pack,
(d) drop-region, plus drop-slot (a kept key dropped).

### Wave 2b review fixes (2026-11-11)

Reviews `c5/review-swap-w2b-{A,B}.md`; fix commit `474f86cb`; the lane record `stage4-c5-swap.md` "SWAP wave 2b review
fixes" has every finding's outcome and the runs.

- **Provenance of forge-swap-w2b.** Its old side (`old4`) was written by the uncommitted work that became 4095dd7e; the
  MANIFEST's `tree b9154084` names the commit under that work, whose make-old has no bag agent. The committed make-old,
  run end to end at 474f86cb on a clean tree (`old5`), writes the same bags (6 + 3 stacks, listing identical), the same
  mod sets, the same old-create blocks (0 differences) and a create console whose sha256 (a704d83e) is the native arm's.
  MANIFESTs written from now on name the tree's uncommitted files, the scripts' and the agent's sha1s, the console's
  sha256 and the old create snapshot's sha1.
- **The read proof is now a check.** gate-m59 asks that every packedup inventory file was saved again after the clone,
  on the open and on the client (`forge-swap.py rewritten`); with the same stacks that is a file packedup read. The two
  bag controls (`bag-stack`, `bag-restore`) and the drill-count control (`drop-announce`) are red on exactly their
  checks.
- **native/ is tied to the old side** (`forge-swap.py tie`, gate fixture step): seed, box, jars by SHA-256, and the same
  blocks placed by both creates (classicpipes x6 set aside: native has none).
- gate-m59 at 474f86cb: **30 PASS / 0 FAIL**; pin unchanged (6 losses = the six TSV rows). The server binds GATE_PORT.
- Not changed by these fixes: the six D77 rows, Forbric-caused losses 0, and the not-measured list above (configs of
  journeymap and BiomesOPlenty, owner SWAP: a mark needs a new fixture on both arms; journeymap writes no file into the
  server's config/).
