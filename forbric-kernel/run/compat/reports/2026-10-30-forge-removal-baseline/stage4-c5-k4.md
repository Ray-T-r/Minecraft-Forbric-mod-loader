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
   *Amended (review A-3/B-3):* that is NeoForge's default, not a universal fact. A Fabric mod can replace the field:
   lithium-fabric 0.25.3 does (lithium-neoforge does not), and NeoForge's writers then fill a map nobody reads — a second
   defect in the same mechanism, fixed by e974788c (see "K4 review fixes").
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

## Fix — first design (940e4cca; replaced by e2489e85, see "K4 review fixes")

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

## Tests and red controls (first design; the current tests are under "K4 review fixes")

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
  *Amended (review A-5):* this 27/5 did not recur. Three later runs of the same set are 32/0 (tip twice, first design
  once; `review-fix/m12-ws-full*.out`), so it is neither shown to be K1's nor the PoI repair's. The hand-off is withdrawn.

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

## Widened — first pass (the full census, one verdict per jar, is under "K4 review fixes")

Scan (`scan/scan-poi.py`, `scan/poi-jars.tsv`): Fabric jars under build/compat-inputs, build/sweep*, build/purefabric-2026-10-03,
run/client-* whose classes name `PoiHelper`, `registerBlockStates`, `PoiTypes`, `POINT_OF_INTEREST_TYPE`,
`VillagerProfession`, or ship poi data. The first pass booted these four (`widen.sh`); the scan's case-sensitive signals
missed invoker-style callers, so this was not every such mod (review A-2/B-3):

| mod | how it maps states | genuine stage + fix | native Fabric | note |
|---|---|---|---|---|
| waystones 26.2.0.12 (balm 26.2.0.9) | Balm `@Invoker` registerBlockStates | Done, 2 takeovers, 640 states | Done | the K4 case |
| kaleidoscope_cookery 1.6.0.1 | fabric-api `PoiHelper` → `PoiTypes.register` | Done, 4 takeovers (144 states: chopping_board 8, pot 64, stockpot 64, stove 8) | Done, 4 registerBlockStates | at 0daa5b0b the same boot dies: `Block{kaleidoscope_cookery:stove}[…] is defined in more than one PoI type` ← `PoiTypes.register` ← fabric-api `PoiHelper.register` (`boot-w-kc-base.log`, no Done) — measured on kaleidoscope_cookery; follows for every PoiHelper caller from the code path |
| puzzleslib 26.2.4 (+ ForgeConfigAPIPort, fabric-api 0.161.0) | `PoiHelper` for dependents | Done, registers none alone | Done | library |
| farmersdelight 26.2-3.6.26+refabricated | none (names VillagerProfession only) | Done, 0 PoI; `fabric-loot-api-v3 did not finish loading` (not PoI) | Done | open item below |
| friendsandfoes 4.0.27 (+ Fabric resourcefullib 5.0.4, same folder) | its accessor's `@Invoker` of registerBlockStates | Done, 11 takeovers, 264 states, 0 Caught (`review-fix/boot-rf2-ff.log`) | Done, 11 registerBlockStates (review-k4-B) | *amended (B-1):* the first pass paired it with the NeoForge ResourcefulLib build; at 0daa5b0b its entrypoint dies (`review-fix/boot-base-ff.log`) |

## Suite and gates (first design, b0d21405; the tip's are under "K4 review fixes")

- Baseline at 0daa5b0b (`suite-base.log`): 5789 tests / 5 failed = failures-wave1.txt.
- Fix tip b0d21405 (`suite-fix.log`): 5797 tests / 5 failed, the same 5 names (0 new, 0 gone; +8 new tests).
- Gates at b0d21405, lane ports 46920–46939 (`gate-*.out`, counts of `^[kernel] PASS` / `^[kernel] FAIL`):
  gate-m8-packmeta 20/0, gate-m10-overlay 24/0, gate-m25-worldgen 16/0 — the same counts recorded at lane S's tip
  (checks.md: m8 20/0, m10 24/0, m25 16/0). gate-m12 with the waystones set: 27/5 (not reproduced later, see A-5); gate-m12's own set not run.

## What other lanes must do (first design; superseded by the list under "K4 review fixes")

- Merger: the change touches `ForbricMergedBaseCompatTransformer` (REPAIRS list, claims, dispatch: one line each at the end)
  and `KernelRuntimeClasses` (one entry after `KernelConditionSources`). K2 also edits `KernelRuntimeClasses` (other
  entry) — textual merge only.
- UPG / G-C / SWAP: no gate change required by this fix (it only adds a repair; no gate asserts the old failure).
- Lane S: D69 evidence above (the real Fabric data-file case, kept).

## Open items (first design; superseded by the list under "K4 review fixes")

- ~~K1 (owner K1): the socket join of the waystones set fails on chunk block-state ids~~ — withdrawn: not reproduced in
  three later runs (A-5).
- farmersdelight 26.2-3.6.26+refabricated on the genuine stage: `fabric-loot-api-v3 did not finish loading` (`boot-w-fd.log`;
  the load-report went with the scratch rundir). Not PoI; not seen with fabric-api alone or the other sets. Owner:
  orchestrator to assign.
- ~~friendsandfoes 4.0.27: not judgeable~~ — withdrawn: the Fabric resourcefullib is on disk; Done with the fix (B-1).
- Why the pre-Stage-4 merged base never invoked PoiTypeCallbacks.onAdd was measured (0 calls) but not explained; it does
  not affect the fix. Owner: none (record only).

## K4 review fixes (2026-11-11)

Reviews: `c5/review-k4-A.md` (adversarial) and `c5/review-k4-B.md` (completeness). Evidence: `c5/k4/review-fix/` (boot
matrix `rf-tip.sh`, mutants `mut/`, census `scan/`, socket joins `m12-*.out`, suite, gates). The first review-fix run made
e2489e85, e974788c and 6be81a1b and was then killed mid-suite (`suite-tip.killed-partial.log`); a second run finished the
suite, gates, mutants at the tip and this record. Arms: **base** = 0daa5b0b code on a clone, **first design** = c2b6cfef
on a clone, **tip** = e974788c code (6be81a1b and ab13842f change only ci-unstaged.tsv). Every boot is a dedicated server
with the K4 probe. Counts are of `IllegalStateException.*PoI type`, `[Forbric/PoI] … maps`, the probe's `K4PROBE` lines,
the Caught summary and the dump.

### Commits

- **e2489e85** (A-4, A-1, B-8). The head of `PoiTypes.registerBlockStates` now calls
  `KernelPoiStates.beforeRegisterBlockStates(TYPE_BY_STATE, holder, states)`: void, four instructions, no branch. Vanilla's
  body then runs for every caller. For a Fabric mod's call the helper removes only the mappings NeoForge's callback made
  to that same holder for states no registerBlockStates call has named yet, and vanilla's body puts them again. Every
  call names its states, whoever makes it. So re-mapping vanilla's or an earlier call's states, or mapping one's own twice,
  still gets vanilla's throw (the first design named only a Fabric call's states). Callers that are not a Fabric mod's are
  untouched. New `KernelPoiStatesThroughPoiTypesTest` runs the caller decision for real (A-1).
- **e974788c** (A-3, B-3). `pointNeoForgesPoiWritersAtTheLiveTable`: each read of NeoForge's map in `PoiTypeCallbacks`
  (onAdd's lambda, onClear) and `PoiTypeExtender` is followed by `KernelPoiStates.liveTable(map)`. That returns the map
  itself while it is `TYPE_BY_STATE`, and the replacement otherwise (logged once). The mechanism is "a guest replaced the
  field", not lithium.
- **6be81a1b**: ci-unstaged rows. 2 renamed rows out, 11 in; 17 PoI rows in all.
- **ab13842f**: the six `KernelPoiStatesTest` rows get the reason CI writes. On a plain `git clone --shared` at 6be81a1b
  (tracked files only, as on the CI runner) the class's `@BeforeAll` aborts, and Gradle writes `<skipped/>` without a
  message. The first measurement ran on an APFS clone of the worktree (K2's walls trap). The ratchet keys were already
  right: 17 of 17 rows, 0 new, 0 stale (`review-fix/unstaged-measure-plainclone.log`). ci-prepared requires the staged
  game, so no rows there.

### Per finding

| finding | verdict | what was done / evidence |
|---|---|---|
| A-1 caller decision untested | fixed, e2489e85 | `KernelPoiStatesThroughPoiTypesTest`: a real `ForbricClassLoader` (mod origins set) defines a `PoiTypes` from a jar no mod owns. It is built from the staged base's own `registerBlockStates` + lambda (retyped to run off-game) and transformed by the real compat transformer, plus the three measured routes (vanilla's `register`, Balm's merged `callRegisterBlockStates`, pillager_deterrent's merged `invokeRegister`). Callers are classes of jars the loader records as a Fabric mod's or a NeoForge mod's. Premise test now also pins `register` = `Registry.register` then `registerBlockStates`, and `NeoForgeRegistriesSetup` attaching `PoiTypeCallbacks`; renamed `theCallbackAndVanillasRegisterBothMapATypesStatesOnThisBase`. Mutants **at the tip** (`review-fix/mut/`, patch + log + results each; 31 tests: the 17 PoI tests + repair-claim and anchor-census suites): m0 control **0 red**; m5 frame filter removed **3**; m3 every caller Fabric **2**; m1 dispatch removed 12; m2 hand-back removed 5; m4 names ignored 4; m6 first design's naming 2; m7 liveTable inert 1; m8 live-table repair not applied 5. A's m5 and m3 were 8/8 green on the first design. (m1–m6 were first run at e2489e85, 26 tests: 10/4/2/4/2/2; kept in `mut-e2489e85-era/`.) |
| A-2 pillager_deterrent missed, "every" overclaimed | fixed (record) | base-pd: main entrypoint dies on its banner PoI, 1 Caught ERROR; the server still reaches Done under `continue`, so Done is not the criterion, the Caught count is. Tip rf2-pd: Done, 1 takeover, 20 states, 0 Caught. Native: Done (review-k4-B). The first-pass widen heading is narrowed (above). |
| A-3 lithium-fabric divorces TYPE_BY_STATE | fixed, e974788c (same mechanism, so this lane) | lithium-fabric 0.25.3 `PoiTypesMixin`: `@Mutable` plus a merged `<clinit>` tail that stores a `Reference2ReferenceOpenHashMap` copy. lithium-neoforge 0.25.2 has no such mixin. First design rf1-lith-neo (lithium-fabric + waystones/balm/shogi-neoforge): Done, `sameAsTypeByState=false`, **0** waystones states. Tip rf2-lith-neo: **640**, one `[Forbric/PoI] PoiTypes.TYPE_BY_STATE is no longer NeoForge's map …` line. Tip rf2-lith-ws (lithium + the Fabric set): 640, 2 takeovers. Without lithium nothing changes (rf2-neo: 640, 0 takeovers, no live-table line). Root-cause step 1 amended. |
| A-4 head-return skipped vanilla's body | fixed, e2489e85 | Redesigned as A-4 suggested: Fabric callers run vanilla's body (and any mixin on it). No on-disk Fabric jar mixes into `registerBlockStates` or its lambda (census), so the gain is by reading, not measured on a mod. |
| A-5 m12 27/5 attributed to K1 by inference | refuted as K1's / not reproduced | gate-m12 socket joins of the same waystones-fabric set (client.sh): tip **32/0** twice (`m12-ws-full.out`, `m12-ws-full-3.out`), first design **32/0** (`m12-ws-full-old.out`; told apart by the takeover line's wording), waystones without lithostitched 32/0, fabric-api + lithostitched 32/0. PoI 640 on both ends in each dump. K4's single 27/5 (`../m12-ws.out`, `No value with id 36329`) did not recur in three runs of the same set: it is recorded as an unexplained one-off, not K1's and not the PoI repair's. K2's 32/0 used waystones-NEOFORGE (A is right). |
| B-1 friendsandfoes "not judgeable" | fixed (record) | The Fabric `fabric-ResourcefulLib-5.0.4.jar` is in the same folder. base-ff: entrypoint dies (`acacia_beehive … more than one PoI type`, 1 Caught ERROR). Tip rf2-ff: Done, 11 registerBlockStates / 11 onAdd / 11 takeovers, 264 states, 0 Caught. Native: Done (review-k4-B). Orchestrator item dropped. |
| B-2 run-m12-ws.sh unusable for K1 | fixed (script) | `c5/k4/run-m12-ws.sh` requires `WT=` (tree to measure) and `OUT=` (caller's dir), takes `LABEL`, `MODS`, `PROBE_FILTER`, sources WT's lane env, and writes only under OUT. Used this way for `m12-ws-full-3`. "Expect 32/0" is withdrawn as a prediction. |
| B-3 census incomplete | fixed | `review-fix/scan/PoiCensus.java` (ASM, case-insensitive; mixin `@Invoker`/`@Accessor`/`@Mutable` on PoiTypes, calls, field writes) → `poi-census.tsv` (29 distinct Fabric jars) → `poi-triage.tsv` (one verdict each). Newly booted: pillager_deterrent, friendsandfoes (Fabric lib), BetterNether via wover-block, lolmlmv, lithium. |
| B-4 farmersdelight item lacked evidence | confirmed; evidence attached | Review B's kept boot (`c5/review-k4-B/boot-fd.log`, `fd-load-report.txt`): "fabric-loot-api-v3 … partly did not run — the kernel leaves out its mixin ReloadableServerRegistriesMixin on this base". The mixin is suppressed in every boot (also tip rf2-ws), and KernelLootBridge supersedes it. Only a mod with a MODIFY listener promotes it to "did not finish loading", while the bridge fired MODIFY on 1468 tables. A load-report label for a designed suppression; not PoI. |
| B-5 red mutation not saved | fixed | Every mutant has `mut/<m>.patch`, `mut-<m>.log`, `results-<m>.txt`; `mutate.py` + `run-mutants.sh` re-run them on clones (done at the tip, above). |
| B-6 suspects without verdicts | fixed (here) | 835ec80c: not an ancestor of f333f1f6 (`git merge-base --is-ancestor` exit 1), and the defect is already at f333f1f6 (walls), so not its cause. Bootstrap re-run: vanilla's bootstrap ran once (`boot-fb-all.log`: 22 registerBlockStates = 21 vanilla + 1). D69: the throw is in the main entrypoint, before any datapack is read. The cause is NeoForge's own callback (root cause above). |
| B-7 scratch left / session-only paths | fixed | Session scratch `k4`, `k4o` (first run) and `k4r` (first review-fix run) deleted; the review-fix scratch clones under `c5/k4/scratch/` deleted. Scripts take `K4_SCRATCH` (default `c5/k4/scratch`) and `OUT`; `run-sp.sh` makes its world itself when it is missing. |
| B-8 two phrasings | fixed | PoiHelper: "measured on kaleidoscope_cookery; follows for every PoiHelper caller from the code path". Premise test renamed and extended (A-1). |

### Boot matrix (dedicated server; `review-fix/boot-*.log`, `dump-*.txt`)

| set | base 0daa5b0b | tip | native Fabric |
|---|---|---|---|
| waystones-fabric set (fabric-api 0.155.2) | dies (`../boot-fb-ws.log`) | rf2-ws Done, 2 takeovers, 640 | Done (`../native-ws.log`) |
| waystones-NEOFORGE set | — | rf2-neo Done, 0 takeovers, 640, no live-table line | (native NeoForge path) |
| lithium-fabric + NeoForge set | — | rf2-lith-neo Done, 640, live-table line (first design rf1: 0) | — |
| lithium-fabric + Fabric set | — | rf2-lith-ws Done, 2 takeovers, 640, live-table line | — |
| NeoForge set + kaleidoscope_cookery | — | rf2-mixed Done, 4 takeovers (kaleidoscope_cookery only), 640 | — |
| kaleidoscope_cookery | dies (`../boot-w-kc-base.log`) | rf2-kc Done, 4 takeovers, 144 | Done (`../native-w-kc.log`) |
| pillager_deterrent | base-pd entrypoint dies | rf2-pd Done, 1 takeover, 20 | Done (review-k4-B) |
| friendsandfoes + Fabric resourcefullib | base-ff entrypoint dies | rf2-ff Done, 11 takeovers, 264 | Done (review-k4-B) |
| better-nether + bclib + worldweaver + wunderlib (fabric-api 0.161.0) | base-bn: entrypoint dies (`pig_statue_respawner … more than one PoI type`, 1 Caught ERROR), then "Failed to load registries", no Done | rf2-bn Done, 1 takeover (wover `PoiManagerImpl`), 313 | native-bn Done, 1 registerBlockStates via `PoiManagerImpl` → `PoiTypes.register` |
| lolmlmv (MoreLoomVariants) | — | rf2-lol Done, 0 takeovers, 44 states via its accessor | — |

Every tip boot: 0 `IllegalStateException.*PoI type`, 0 Caught ERROR.

### Suite and gates (tip 6be81a1b code; ab13842f changes only TSV text)

- Full suite (pinned props, plain `--offline`, `-Pforbric.requireFixtures=all jar cleanTest test --continue`, heavy.sh;
  `review-fix/suite-tip.log`, `suite-tip-failures.txt`): **5806 tests / 5 failed**. The 5 are exactly the names in
  failures-wave1.txt: 0 new, 0 gone. 5806 = 5797 − 8 first-design PoI tests + 17.
- Gates in the K4 worktree, lane ports (`review-fix/gate-*.out`, `^[kernel] PASS` / `^[kernel] FAIL`): m8 **20/0**,
  m10 **24/0**, m25 **16/0**, m11 **14/0** (waystones-neoforge on a dedicated server; its boot log, copied to `review-fix/gate-m11-serverconfig-boot.log`,
  shows both K4 repairs applied: PoiTypes and the two writer classes).
  m8/m10/m25 equal lane S's counts (checks.md) and m11 equals G-A's and the wave-2 merger's (`merge-wave2.md`: 14/0).
- gate-m12's own set (fabric-api 0.154, mcw-bridges-neoforge, collective) registers no PoI type, so it was not run. The
  waystones-fabric socket join was run instead: 32/0 at the tip (twice) and on the first design (A-5).

### What other lanes must change

- Merger: `ForbricMergedBaseCompatTransformer` has two K4 repairs (REPAIRS, claims, dispatch: two lines each at the end).
  `KernelRuntimeClasses`' KernelPoiStates entry lists two calls (`beforeRegisterBlockStates`, `liveTable`); K2 adds a
  different entry to that file. ci-unstaged.tsv: 17 K4 rows. `git merge-tree` of ab13842f is clean onto 7aaebbec and
  against s4-c5-k1 (30c5277f), s4-c5-k2 (e37b0cf0) and s4-c5-k3.
- K1: nothing is handed to you. `c5/k4/run-m12-ws.sh` with `WT=<tree with K1 and K4>` `OUT=<your dir>` is an optional
  extra socket join (waystones-fabric set: modded block states in sent chunks).
- K2: Farmer's Delight refabricated over a socket hits your `Int2ObjectMap.clear` signature (`review-fix/m12-fd-client.log`,
  20/12, on this tree without your fix). Worth one run on your fix.
- UPG / G-C / SWAP: none. No gate asserts PoI behaviour, and m58 uses waystones-neoforge.

### Open items

- A-5: one unexplained 27/5 socket join (`../m12-ws.out`), not reproduced in three later runs. Owner: orchestrator (watch
  only; no lane holds it).
- farmersdelight-refabricated's load-report label (B-4 evidence above). Owner: orchestrator / owner (label policy).
- No on-disk mod mixes into `registerBlockStates`' body, so A-4's gain is by reading only.
- NeoForge mods that call `GameData.getBlockStatePointOfInterestTypeMap()` themselves are not redirected (the getter
  also runs inside `PoiTypes.<clinit>`); none is known. Record only.
- Why the pre-Stage-4 merged base never called onAdd: still unexplained. Record only.
