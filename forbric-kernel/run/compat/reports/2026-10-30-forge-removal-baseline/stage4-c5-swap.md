# Stage 4 C5, lane SWAP (C5c): the Forge->NeoForge build swap, W2-W6

Branch `s4-c5-swap`, worktree `s4c5-swap`, ports 46780-46799. Logs: `~/Forbric-backups/stage4-notes/c5/swap/w2/` (D67).
Results of the measurement and the per-mod table: `stage4-forge-swap.md` beside this file. STOPPED before W7/W8:
gate-m59, `expected-losses.tsv` and any migration code wait for the owner's ruling (D54/O1).

## Base

Cut from the stage tip f0cb66b0 by `bootstrap-lane.sh swap`, then fast-forwarded to lane UPG's tip 01b883dd (U1
`run/old-kernel.sh`, U2 frozen `world-snapshot.py`), which this lane's tools import and run; UPG merges before SWAP
(PLAN §3), so the merge stays a fast-forward as long as UPG only appends. UPG's files are not edited here.

Old kernel: an APFS clone of UPG's built cache into this worktree's `build/old-kernel/` (`FORBRIC_OLD_KERNEL_CACHE`
default), so UPG's cache stays read-only and the two lanes never share the old tree's build dir. `old-kernel.sh build`
printed the same kernel jar sha1 aae280b5… and merged base ca98f147… as UPG; the stage is the tarball extract
(5befce98…). Native images: `cp -c -R` of the main checkout's MinecraftForge 65.0.1 image and the orchestrator's
NeoForge 26.2.0.93 image into `build/native-controls/native/{forge,neo}`; `prepare` never ran (nothing downloaded).

## Commits

| sha | item | subject |
|---|---|---|
| `7c719280` | W4 / map-misc §4.1 | native-controls' Forbric arm boots `FORBRIC_OLD` as given (`forbric_stage()`), not the reference checkout's forbric-loader; README pins NeoForge .93 |
| `940cab60` | W4 | `native-controls.py swap --engine native|forbric`: cp -c image clones, create/A-A/open/reopen sessions, world-snapshot exports and diffs |
| `471f3c5b` | W4 | the NeoForge instance starts from the old instance's config/; `--after-create` hook for the config marks |
| `1598429f` | W3, W5 tooling | `forge-swap-make-old.sh` (old side + fixture) and `forge-swap.py` (probe, drill, kit, config marks, census, sessions, world files, classify) + `test_forge_swap.py`; PROTOCOL.md names them |
| `261061b8` | W6 tooling | player rows only from the NEW client's export (a server never loads a player file) |
| `e054e47e` | W3 | packedup's open-bag key rebound to keypad 7 in the old client (journeymap's fullscreen-options key is also O) |
| `07264248` | record | this file and `stage4-forge-swap.md` |
| `4565d2fd` | review A-1, A-2 | classify: per-arm state (own row / whole value above / rows below / kept / absent), NOT IN FORBRIC'S OLD WORLD, FORBRIC CHANGED candidate, keyed list elements, block-entity id attribution, LOADER METADATA, counts + candidates |
| `b93745ee` | review A-4, A-5 | swap_session's saved flag from "Saved the game" only; decoy reference stage in the two Forbric-arm stage tests |
| `7cbf1694` | review B-4 | forge-swap-make-old.sh: the bag step described as attempted, WARN + MANIFEST `packedup-bag` line |

`compileJava compileRuntimeJava compileTestJava` are untouched by these commits (Python, shell and Markdown only).

PROTOCOL.md is lane G-B2's file this wave. CompatProtocolTest requires every script under `run/compat` to be named
there, so `1598429f` adds one paragraph after UPG's world-snapshot lines (UPG's precedent, 932f9a6b) and `7c719280` one
sentence correcting what the Forbric arm boots. Both hunks are small and separate for the merger.

## Suite (pinned build, D68: `--offline` with props.txt, no init script)

| run | result |
|---|---|
| first act, f0cb66b0, fresh worktree | 5772 tests, 0 skipped, 7 failed: the 5 of failures-wave1.txt + `KernelBundledMixinExtrasStagedTest` [1] true, [2] false, both "[fixture:third-party] … build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar" (a fresh worktree has no kernel jar). `gradlew jar` then that class: 2/2 pass. So exactly the 5 names once the boot jar exists |
| final, e054e47e (every code commit of the lane) | **5784 tests, 0 skipped, 5 failed: exactly the 5 names of failures-wave1.txt** (LB x3, SharedFinalSourceActualTest x2). 5784 = UPG's count; SWAP adds no Gradle test. CompatProtocolTest passes with the PROTOCOL.md mention. XML: swap/w2/suite-final-xml/ |

Python: `test_native_controls.py` 26/26 (4.7 s), `test_forge_swap.py` 12/12.

Red checks (mutated copies in a temp dir, never the worktree's files):
- `forbric_stage` back to `ORIGINAL / "forbric-loader"`: 2 of its 3 tests fail (the third pins the unset case).
- swap's Forbric arm handed `ORIGINAL/forbric-loader`: `test_the_swap_forbric_arm_boots_the_callers_stage` fails.
- `swap_session` without the save-all: `test_lines_then_save_then_stop_in_that_order` fails.
- the brickfurnace use check left at x 42 (inside the box): `test_the_console_only_writes_inside_the_exported_box` fails
  (the real bug it was written for: native3 ran with it).

## Measurement runs (all through heavy.sh; clients through client.sh first)

| run | what | result |
|---|---|---|
| old0 (scratch) | old side without a client | 23/0 drill, A/A clean; used for the first Forbric arm only |
| old1 (scratch) | old side with client | kit ran; no key fired (pgrep took `--gameDir` as an option) |
| old2 = frozen fixture | old side with client, config marks, box -2..3 | drill 23/0, kit + grave, key fired but O opened journeymap's options; A/A clean; copied to `~/Forbric-backups/forge-swap/` |
| native1..native4 | native arm | native4 of record: all phases DONE, drill 20/3 (classicpipes), configs KEPT |
| forbric0..forbric2 | Forbric server open/reopen | forbric2 of record: DONE, drill 23/0, configs KEPT, 0 Forbric-caused world losses |
| newclient2 | NEW client, all NeoForge builds | crash: packedup "Container screen registered with null menu type!" (Forbric-caused, open) |
| newclient3 | NEW client without packedup + libs | clean; F2 x2 saved 2 screenshots (posted keys reach the window); player rows |

| old3 (scratch) | old side again with the keypad-7 binding | FAILED: the old client reached the title screen (02:41:50), its render thread stopped logging at 02:41:54 and it never joined the world; killed at the 900 s timeout. Not diagnosed (a GUI stall at ~02:40 local; the display may have been asleep). The fixture stays old2. Logs swap/w2/old3-logs/ |

## Open

1. **Forbric-caused, not fixed: corelib's `@RegistryEntryAcceptor` on NeoForge** (packedup's NEW client crash). Root
   cause and two fix shapes in `stage4-forge-swap.md` W6; the weave into the uncalled `postRegisterEvents` is now shown
   by a server run (review fixes). The fix is in the kernel's NeoForge registration lifecycle (`KernelLifecycle.java` /
   `KernelNeoRegistries.java`), touches how every NeoForge mod's RegisterEvent is dispatched and needs the registration
   gates re-run; not attempted in this lane. PLAN W6 gives the fix to SWAP "with files cleared with S/T owners"; those
   two files are in no lane's §3 set and kernel code is lane S's, and SWAP has no channel to clear them with S in this
   run. **Request to the orchestrator (review B-1): name the owner (lane S), or rule that SWAP does it after clearing
   the files with S.** Until then PLAN's SWAP acceptance (Forbric-caused losses = 0) is NOT met.
2. packedup backpack contents are not measured on either side: old side (fixture old2) never opened a bag (key clash,
   now rebound), NEW side cannot open one until 1 is fixed. A re-made fixture with a filled bag needs one more
   `forge-swap-make-old.sh` run; the old3 attempt stalled on the old client's title screen (above), so the keypad-7
   binding is committed but not yet shown to open the bag.
3. W7 (owner, O1): the mod-format rows (tombstone entity/player ForgeCaps, the renamed `silent_bond` effect, MinecraftForge's
   `forge:swim_speed`) and the one "Forbric keeps, native loses" row (tombstone `PlayerPersisted`; native side static).
   `fml/Registries` is withdrawn from O1: the old Forbric world never had it (review A-1).
4. W8 has three preconditions (review B-1): the W7 ruling (3), the corelib fix (1), and a re-made fixture with a filled
   bag (2). Then gate-m59 on the frozen fixture with controls (a)-(d) and `expected-losses.tsv`, with the classifier's
   verdicts as fixed in 4565d2fd and the counts re-derived with UPG's rules.
5. Done upstream: UPG's 6ce60ac9 treats `tickets` as unordered (and sorts recipeBook, drops the warden tick). This
   branch is at 01b883dd + SWAP commits; it rebases onto UPG's tip before its merge (or the stage merge brings both),
   and the counts change as `w2/review-fixes/*-upgtip.*` show (server 46 -> 36 keys; with the client 138 -> 42).
6. `forge-swap.py configs` reads `<instance>/world/serverconfig`, which a singleplayer instance keeps under
   `saves/<world>/serverconfig`: it says MISSING for brickfurnace's server config on a client instance (the file is
   there). Cosmetic, client runs only.
7. The journeymap Forge->Fabric row (D54) is not measured: the local Fabric build is 6.0.9, the Forge build 6.0.8.
8. The energy gap and the implicit swaps are recorded in `stage4-forge-swap.md` (D54), not measured, no canary.
9. Not measured, now said so in the per-mod table (review A-3/B-2): packedup in the player's inventory; journeymap's
   and BiomesOPlenty's configs (no mark); packedup place/use and tombstone use drills (cannot be drilled on a server).

## SWAP wave-2 review fixes (2026-11-10)

Reviews: `~/Forbric-backups/stage4-notes/c5/review-w2-swap-A.md` (lens A) and `review-w2-swap-B.md` (lens B). Every
finding was re-checked against the evidence before it was fixed; none was refuted. Code edits were made in a scratch
copy (a PreToolUse hook blocks writes into this worktree from the orchestrating session) and copied in with cp.

| finding | verdict | fix |
|---|---|---|
| A-1 classify calls a key Forbric's old world never had "kept" (`fml/Registries` as FORBRIC KEEPS) | confirmed: fixture old-snapshot and forbric2 have `fml` = {LoadingModList} only; native4 forge-reopen has Registries; the reviewer's repro printed FORBRIC KEEPS | `4565d2fd`: per-arm state with "absent"; verdict NOT IN FORBRIC'S OLD WORLD; repro now prints `absent lost NOT IN FORBRIC'S OLD WORLD`; row withdrawn from O1 in both records, handoff, checks |
| A-2 no verdict for (changed, kept); whole-list rows; BE rows "vanilla" | confirmed: repro printed CHANGED for a Forbric-only furnace reset; player Inventory/active_effects/attributes were single CHANGED rows; brick furnace rows "vanilla" | `4565d2fd`: FORBRIC CHANGED (candidate), (lost, changed) = FORBRIC-CAUSED, keyed lists by Slot / id, BE id attribution, ancestor/descendant states, LOADER METADATA for LoadingModList, `counts` + `candidates` in the output. Re-derived tables in `w2/review-fixes/` |
| A-3 packedup "in the player's inventory" never measured; server null menu inferred | confirmed (newclient3 log :511 drops the obsidian backpack; newclient2 crashed before the world) | record: "NOT measured" with reasons; the server statement marked inferred from javap |
| A-4 `saved` also matched the shutdown's "All dimensions are saved" | confirmed: forbric2/neo-reopen.log:363 prints it after stop; every session log has exactly one "Saved the game" | `b93745ee`: SAVED = "Saved the game"; the fake server prints the shutdown line, so the old pattern fails `test_a_save_the_server_never_answers_is_not_saved` |
| A-5 stage tests red by FileNotFoundError, not the assertion | confirmed in a scratch bed outside any worktree | `b93745ee`: `nc.ORIGINAL` -> temp tree with a decoy stage; under the revert all 3 ForbricStage tests FAIL with AssertionError |
| B-1 W6 acceptance unmet, the loss has no named owner, W8 preconditions incomplete | confirmed | records + handoff: the three W8 preconditions, the acceptance stated as unmet, an explicit owner request to the orchestrator; the weave shown by a server run (`w2/review-fixes/corelib-probe/`) |
| B-2 journeymap / BOP configs claimed, never marked | confirmed (CONFIG_MARKS has 3 rows; `world_files` skips serverconfig/) | record: NOT measured |
| B-3 implicit swaps, energy gap, undrillable cells missing from the record | confirmed (no "implicit"/"energy" in stage4-forge-swap.md) | record: implicit swaps from `stage3-sweep.tsv` (9 FORGE->NEOFORGE jars named; 83 FORGE->FABRIC rows / 48 jars), the energy-gap sentence, packedup place/use and tombstone use with reasons |
| B-4 1598429f / script header describe the bag step as working | confirmed (fixture's packedup/backpacks/ empty; old1/old2/old3) | `7cbf1694` (header, WARN, MANIFEST `packedup-bag`); record says 1598429f's sentence is intent |
| B-5 native "Missing data pack" 16 (is 14); effect/attribute verdicts were hand judgments | confirmed: 14 lines in native4/neo-open.log | record corrected; hand-judged rows named; the fixed classifier now reports them |
| B-6 re-save readings not kept; reopen result missing; newclient3 not reproducible | confirmed; readings cannot be recovered (worlds deleted); S0 1615-1618 re-read on the fixture; the tombstone clock corroborates 2209 / 1461 | record: the S1->S2 line, the not-kept statement, newclient3's mod set and keys from its log/result (exact command line not kept) |
| B-7 open item 5 done upstream (6ce60ac9) | confirmed: `s4-c5-upg` = bc020d49, 2 commits past 01b883dd | record: done upstream; counts with both rule sets; rebase before merge; W8 re-derives |

Also found while fixing: the console's `effect give` on the wolf answered `Unable to apply this effect` on both old
sides, so the mob effect was never measured (the player's was); recorded in `stage4-forge-swap.md`.

Not done, by design: no rebase onto UPG's tip in this run (the merge brings 6ce60ac9; the counts with its rules are in
`w2/review-fixes/*-upgtip.*`, computed from a scratch copy of its world-snapshot.py); no change to the frozen fixture
(its MANIFEST predates the `packedup-bag` line; its bag is `none`).

Tests: `test_forge_swap.py` 17/17 (5 new), `test_native_controls.py` 26/26. Mutation red-checks, each on a scratch
copy outside any worktree, each turning exactly the named tests red with AssertionError: per-arm "absent" reverted ->
the fml/Registries test; FORBRIC CHANGED removed -> the furnace-reset test; KEYED_LISTS emptied -> the slot-element
test; BE id attribution removed -> the furnace-reset and slot-element tests (both assert the mod); ancestor state
removed -> the whole-value test; old SAVED pattern -> the never-answered save test; forbric_stage reverted -> all
three ForbricStage tests.

Suite (pinned build, D68: plain `--offline` with props.txt, no init script, through heavy.sh) at 7cbf1694, the last
code commit: **5784 tests, 0 skipped, 5 failed: exactly the 5 names of failures-wave1.txt** (LB x3,
SharedFinalSourceActualTest x2). Log and XML: `w2/review-fixes/suite-final.log`, `suite-final-xml/`. No gate file was
changed, so no gate was re-run. One game JVM ran (the corelib probe server, heavy.sh slot, port 46781, scratch rundir
deleted afterwards).
