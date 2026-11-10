# Stage 4 C5 — lane K4: a Fabric mod's PoI states and NeoForge's PoiType add-callback

Branch `s4-c5-k4` from lane S's tip `0daa5b0b`. Evidence: `~/Forbric-backups/stage4-notes/c5/k4/`.

## Symptom

Waystones-fabric 26.2.0.12 (with balm-fabric 26.2.0.9, shogi-fabric 26.2.0.8, lithostitched 1.7.13, fabric-api 0.155.2)
does not finish loading on the genuine stage. Its main entrypoint dies in Balm's PoI registrar:

    java.lang.IllegalStateException: Block{waystones:…_waystone}[…] is defined in more than one PoI type
      at PoiTypes.lambda$registerBlockStates$0 ← PoiTypes.registerBlockStates ← PoiTypes.callRegisterBlockStates$balm_…
      ← balm PoiTypesAccessor.callRegisterBlockStates ← FabricBalmPoiTypeRegistrar.register:21 ← ModWorldGen.initializePoiTypes:69

Everything waystones registers after its first PoI type is lost, and the world's registries then fail on its missing
features (lane S, `c5/s/w2r/boot-ws.log`).

Reproduction (`k4/boot.sh` + `k4/run-fb.sh`, a dedicated server with the jar set above and a scratch probe agent
`k4/probe/k4-poi-probe.jar`, which prints a stack at `PoiTypes.registerBlockStates` and at NeoForge's
`PoiTypeCallbacks.onAdd` for holders naming a filter, and dumps `TYPE_BY_STATE` at exit):

| arm | log | result | registerBlockStates (waystones) | onAdd (waystones) | waystones states in TYPE_BY_STATE |
|---|---|---|---|---|---|
| native Fabric 26.2, loader 0.19.5 (native-controls image, APFS clone) | `native-ws.log` | Done | 2 (wild, village), one each | — (no callback) | (dump not printed on native) |
| pre-Stage-4: cf0498eb jar on an APFS clone of the merged `.stage` | `boot-pre-ws.log`, `dump-pre-ws.txt` | Done | 2 | 0 | 640 (320 + 320), 1136 in all |
| genuine stage at 0daa5b0b | `boot-fb-ws.log`, `dump-fb-ws.txt` | entrypoint dies | 1 (throws) | 1 (wild) | 320 (wild only), 816 in all |
| genuine stage, fix | `boot-fb-fix.log`, `dump-fb-fix.txt` | Done | 2 | 2 | 640 (320 + 320), 1136 in all |
| genuine stage, fix mutated out (scratch build) | `boot-fb-mutant.log`, `dump-fb-mutant.txt` | entrypoint dies | 1 (throws) | 1 | 320, 816 in all |

So the defect is Forbric's, not the mod's or the set's: native Fabric boots the same jars.

## Root cause

1. On NeoForge's base `PoiTypes.TYPE_BY_STATE` is NeoForge's map: `PoiTypes.<clinit>` stores
   `GameData.getBlockStatePointOfInterestTypeMap()` (javap offsets 311–314), which returns
   `NeoForgeRegistryCallbacks.PoiTypeCallbacks.BLOCKSTATE_TO_POI_TYPE_MAP` (the dump: `sameAsTypeByState=true`).
2. `NeoForgeRegistriesSetup` attaches `PoiTypeCallbacks.INSTANCE` to `BuiltInRegistries.POINT_OF_INTEREST_TYPE` (javap
   offsets 72–78), after vanilla's bootstrap. Its `onAdd` puts every matching state of an added type into that map and
   throws only if a state already had a type. So vanilla's 21 types are mapped by vanilla's own `registerBlockStates`
   (no onAdd: `boot-fb-all.log`, 22 registerBlockStates / 1 onAdd), and everything added later is mapped by the callback.
   NeoForge mods only register; they never call `registerBlockStates`.
3. The genuine base's `PoiTypes.register` still does `Registry.register` then `registerBlockStates(holder, states)`
   (offsets 13–29), and `registerBlockStates`' lambda throws "%s is defined in more than one PoI type" whenever `put`
   returns non-null — including the same holder.
4. A Fabric mod makes vanilla's two steps itself: Balm's `FabricBalmPoiTypeRegistrar.register` (Registry.register, then its
   `@Invoker` of `registerBlockStates`), fabric-api's `PoiHelper.register` (vanilla's `PoiTypes.register`, measured on
   kaleidoscope_cookery). In the kernel's registration window the callback is attached, so the add maps the states and
   the mod's own second step finds each state under its own holder and throws. Probe order in `boot-fb-ws.log`: onAdd
   `waystones:wild_waystone` → registerBlockStates `waystones:wild_waystone` → throw; the dump shows the 320 states under
   that same holder.

Native Fabric has no callback (one registerBlockStates per type, `native-ws.log`). On the pre-Stage-4 merged base the
callback never fired for any PoI type (`boot-pre-all.log`: 23 registerBlockStates, 0 onAdd), so the double mapping is
new with the genuine base. Why the merged base's callback did not fire was not established (not needed for the fix).

What would falsify this: the throw coming from a second, different type claiming the same states (a real conflict), or
the callback not being the first writer. The dump shows one holder per state and the probe shows onAdd immediately before
the failing call; and with the callback's mapping absent (pre-Stage-4 arm) the same jars map both types once.

Not another K lane's defect: no registration is fired twice (one onAdd per type), no RegisterEvent is involved
(Balm-fabric calls Registry.register directly from the Fabric entrypoint), and block-state ids are untouched.

## Fix (940e4cca)

`ForbricMergedBaseCompatTransformer#letAFabricModMapThePoiStatesNeoForgeMappedForIt` puts six instructions and a frame at
the head of `PoiTypes.registerBlockStates(Holder, Set)`:
`if (KernelPoiStates.registerBlockStates(TYPE_BY_STATE, holder, states)) return;` — vanilla's body is otherwise unchanged.

`KernelPoiStates` (game side) finds the first frame outside `PoiTypes` (vanilla's `register`, or a mod's invoker merged
into PoiTypes, are skipped) and asks the kernel loader's ecosystem record whether it is a Fabric mod's class
(`ForbricClassLoader.ecosystemOfClass == FABRIC`, the same test `KernelMobShears.fabricCode` uses).
- Not a Fabric mod's (NeoForge mod, vanilla, kernel): returns false and vanilla's body runs — native NeoForge behaviour.
- A Fabric mod's: maps the states itself. A state the callback already gave this holder is taken over, once; a state
  another type holds, or one the mod maps a second time, throws vanilla's message — native Fabric behaviour. Each takeover
  is logged: `[Forbric/PoI] <caller> maps N block state(s) NeoForge's registry callback already gave <holder> …`.

No switch: the ledger asks a new switch only for a leniency that changes a native outcome; this one restores Fabric's.
No existing repair became unnecessary (C3 checks not applicable). `KernelRuntimeClasses` lists the new seam.

## Tests and red controls

- `KernelPoiStatesTest` (5, game side via `StagedGameClassLoader`): takeover of the callback's mapping; mapping twice
  still throws; another type's state still throws; no callback = vanilla; non-Fabric caller = vanilla's body.
- `MergedBasePoiStatesTest` (3, staged genuine base): the base still maps twice (TYPE_BY_STATE is NeoForge's map); the
  head call is exactly six instructions, vanilla's body follows unchanged, the method verifies, a second pass stands down;
  the inserted descriptor is the helper's real method.
- Red control (scratch clone, `red-mutation.log`): dispatch line removed + takeover branch disabled → 3 of 8 red
  (`aFabricModsOwnCall…`, `mappingTheSameStatesTwice…`, `registerBlockStatesAsksTheKernelFirst…`); the same mutant jar
  fails the boot (`boot-fb-mutant.log`), and the anchor ledger names the silent repair
  (`forbric-merged-base-compat#letAFabricModMapThePoiStatesNeoForgeMappedForIt was handed …PoiTypes and made no edit`).
- ci-unstaged (b0d21405): all 8 skip on `[fixture:staged]` with `-Pforbric.stagedRoot=/nonexistent-k4/run`
  (`unstaged-measure.log`); 8 rows added. ci-prepared has the staged game; no rows.

## Acceptance

- Dedicated server: `boot-fb-fix.log` Done, 0 Caught, both waystones PoI types mapped (dump: 640 waystones states,
  320 → `waystones:wild_waystone`, 320 → `waystones:village_waystone`; this is `PoiTypes.forState` on those blocks).
- Client, singleplayer (`run-sp.sh`, `client-sp.log`, `client-sp.latest.log`, `dump-sp.txt`): a Forbric client with the same
  jars enters a world the fixed server generated (integrated server), lives 60 ticks and leaves:
  `[Forbric/ClientSmoke] joined world via quick-play: K4World`, `clean disconnect observed`, 0 crash, 0 PoI throw; two
  `[Forbric/PoI]` takeovers (Balm's `PoiTypesAccessor`, 320 states each); the client JVM's TYPE_BY_STATE holds the 640
  waystones states under the two waystones types (1136 in all).
- Client over a socket (gate-m12 with `M12_MODS` = fabric-api 0.155.2 + the waystones set, `run-m12-ws.sh`,
  `m12-ws.out`): **27 PASS / 5 FAIL**. The PoI part holds on both ends (`dump-m12-ws.txt`: server and client each 640
  waystones states, both types). The client joined (`joined world via quick-play: 127.0.0.1:46921`) and was then
  disconnected decoding a chunk: `IllegalArgumentException: No value with id 36329` at `HashMapPalette.read` ←
  `LevelChunkSection.read` ← `handleLevelChunkWithLight` → `Network Protocol Error`. That matches K1's defect (block-state ids
  differ between a client and a dedicated server; walls: the client's second freeze appends every state again), which
  more modded block states (waystones' 31 blocks) reach; not separated from K1 by a run with K1's fix (none committed yet). The 5 FAILs are its consequences (no protocol error, survived
  simulation, left cleanly, file-watchers at close, no client crash). Handed to K1 under "K4" in handoff.md.

## D69 boot evidence (for lane S's record)

With waystones booting, its lithostitched worldgen modifiers (`data/waystones/lithostitched/worldgen_modifier/
guaranteed_waystone_*.json`) carry `fabric:load_conditions` AND `neoforge:conditions` naming
`waystones:force_spawn_in_villages`. With the default config (`spawnInVillages = "regular"`) Fabric's condition is false and
the file is skipped before NeoForge's evaluator sees it (`boot-fb-fix.log`: no condition line for it). With
`spawnInVillages = "frequent"` (`ws-config-frequent/`, `boot-fb-d69.log`) the Fabric condition passes and NeoForge's
evaluator meets the unregistered type in a Fabric mod's pack; the D69-narrowed leniency keeps it:

    [Worker-Main-3/WARN]: [Forbric/Conditions] resource condition 'waystones:force_spawn_in_villages' is not in NeoForge's
    condition registry, so NeoForge's evaluator — … — could not judge it … It is being ignored here instead; the
    ecosystem that owns that id decides. 2 distinct condition(s) so far

and no `and that pack is not a Fabric mod's` line; the server reaches Done with 0 Caught. Before the fix this path was
unreachable (waystones' entrypoint died first).

## Widened: every on-disk Fabric mod that registers a PoI type or profession

Scan (`scan/scan-poi.py`, `scan/poi-jars.tsv`): Fabric jars under build/compat-inputs, build/sweep*, build/purefabric-2026-10-03,
run/client-* whose classes name `PoiHelper`, `registerBlockStates`, `PoiTypes`, `POINT_OF_INTEREST_TYPE`,
`VillagerProfession`, or ship poi data. Those that register (not just read) a PoI type, each smoke-booted (`widen.sh`):

| mod | how it maps states | genuine stage + fix | native Fabric | note |
|---|---|---|---|---|
| waystones 26.2.0.12 (balm 26.2.0.9) | Balm `@Invoker` registerBlockStates | Done, 2 takeovers, 640 states | Done | the K4 case |
| kaleidoscope_cookery 1.6.0.1 | fabric-api `PoiHelper` → `PoiTypes.register` | Done, 4 takeovers (144 states: chopping_board 8, pot 64, stockpot 64, stove 8) | Done, 4 registerBlockStates | at 0daa5b0b the same boot dies: `Block{kaleidoscope_cookery:stove}[…] is defined in more than one PoI type` ← `PoiTypes.register` ← fabric-api `PoiHelper.register` (`boot-w-kc-base.log`, no Done) — every fabric-api PoiHelper user was broken |
| puzzleslib 26.2.4 (+ ForgeConfigAPIPort, fabric-api 0.161.0) | `PoiHelper` for dependents | Done, registers none alone | Done | library |
| farmersdelight 26.2-3.6.26+refabricated | none (names VillagerProfession only) | Done, 0 PoI; `fabric-loot-api-v3 did not finish loading` (not PoI) | Done | open item below |
| friendsandfoes 4.0.27 | direct `registerBlockStates` | not judgeable: needs Fabric resourcefullib, not on disk; with ResourcefulLib 5.0.4 (NeoForge build) its entrypoint dies `Trying to access unbound value: ResourceKey[minecraft:block / friendsandfoes:buttercup]` before any PoI | not run (dependency absent) | open item below |

## Suite and gates

- Baseline at 0daa5b0b (`suite-base.log`): 5789 tests / 5 failed = failures-wave1.txt.
- Fix tip b0d21405 (`suite-fix.log`): 5797 tests / 5 failed, the same 5 names (0 new, 0 gone; +8 new tests).
- Gates at b0d21405, lane ports 46920–46939 (`gate-*.out`, counts of `^[kernel] PASS` / `^[kernel] FAIL`):
  gate-m8-packmeta 20/0, gate-m10-overlay 24/0, gate-m25-worldgen 16/0 — the same counts recorded at lane S's tip
  (checks.md: m8 20/0, m10 24/0, m25 16/0). gate-m12 with the waystones set: 27/5 (K1, above); gate-m12's own set not run.

## What other lanes must do

- Merger: the change touches `ForbricMergedBaseCompatTransformer` (REPAIRS list, claims, dispatch: one line each at the end)
  and `KernelRuntimeClasses` (one entry after `KernelConditionSources`). K2 also edits `KernelRuntimeClasses` (other
  entry) — textual merge only.
- UPG / G-C / SWAP: no gate change required by this fix (it only adds a repair; no gate asserts the old failure).
- Lane S: D69 evidence above (the real Fabric data-file case, kept).

## Open items

- K1 (owner K1): the socket join of the waystones set fails on chunk block-state ids (above). Re-run `k4/run-m12-ws.sh`
  after K1's fix; expect 32/0 like gate-m12's own set.
- farmersdelight 26.2-3.6.26+refabricated on the genuine stage: `fabric-loot-api-v3 did not finish loading` (`boot-w-fd.log`;
  the load-report went with the scratch rundir). Not PoI; not seen with fabric-api alone or the other sets. Owner:
  orchestrator to assign.
- friendsandfoes 4.0.27 (direct registerBlockStates user): not judgeable here, Fabric resourcefullib is not on disk; with the
  NeoForge ResourcefulLib build it dies earlier on an unbound block holder (`boot-w-ff.log`). Owner: orchestrator (needs
  the jar, D68: not downloaded).
- Why the pre-Stage-4 merged base never invoked PoiTypeCallbacks.onAdd was measured (0 calls) but not explained; it does
  not affect the fix. Owner: none (record only).
