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
   cause and two fix shapes in `stage4-forge-swap.md` W6. The fix is in the kernel's NeoForge registration lifecycle
   (`KernelLifecycle.java` / `KernelNeoRegistries.java`), touches how every NeoForge mod's RegisterEvent is dispatched
   and needs the registration gates re-run; not attempted in this lane. Needs an owner.
2. packedup backpack contents are not measured on either side: old side (fixture old2) never opened a bag (key clash,
   now rebound), NEW side cannot open one until 1 is fixed. A re-made fixture with a filled bag needs one more
   `forge-swap-make-old.sh` run; the old3 attempt stalled on the old client's title screen (above), so the keypad-7
   binding is committed but not yet shown to open the bag.
3. W7 (owner, O1): the mod-format rows (tombstone entity/player ForgeCaps, the renamed `silent_bond` effect, MinecraftForge's
   `forge:swim_speed`) and the "Forbric keeps, native loses" rows (tombstone `PlayerPersisted`, `fml/Registries`).
4. W8 after W7: gate-m59 on the frozen fixture with controls (a)-(d); `expected-losses.tsv`; the gate's packedup
   representative needs 1 and 2 first.
5. Feature request to UPG (world-snapshot.py): treat `chunk_tickets.dat`'s `tickets` list as unordered (10 false
   differences per reopen).
6. `forge-swap.py configs` reads `<instance>/world/serverconfig`, which a singleplayer instance keeps under
   `saves/<world>/serverconfig`: it says MISSING for brickfurnace's server config on a client instance (the file is
   there). Cosmetic, client runs only.
7. The journeymap Forge->Fabric row (D54) is not measured: the local Fabric build is 6.0.9, the Forge build 6.0.8.
8. The energy gap is recorded (D54), no canary.
