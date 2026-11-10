# Stage 4 C5 — lane K1: block-state ids on a socket join

Branch `s4-c5-k1` from the wave-2 stage tip `7aaebbec`. Evidence: `~/Forbric-backups/stage4-notes/c5/k1/`
(`repro.sh`, `probe/` (the dump mod and its source), `runs/<label>/` (out.txt, both logs, the id→state dumps),
`gates/<label>.out` + `.logs/`, `k2set/`, `unstaged/`, `baseline/`).

## Symptom

A Forbric client joining a Forbric dedicated server over a socket dies on a chunk packet,
`IllegalArgumentException: No value with id <n>` (`LinearPalette.read`), once the server has a NeoForge mod's block in a
chunk it sends. Found by UPG in gate-m58 (s3, s4, bb-write, bb-read, c1: `No value with id 43766`), by K2 with
waystones + sophisticatedbackpacks (37849), by K4 in gate-m12 with the waystones set (36329).

Smallest reproduction (`k1/repro.sh`: a flat world, three mcw-bridges piers set at spawn from the console before anyone
joins, then a client joins; both ends carry the same jars plus `k1probe.jar`):

| run (tree) | mods | server map | client map (pre / post sync) | join |
|---|---|---|---|---|
| `fb-bridges`, `base-fapi-bridges` (7aaebbec) | fabric-api 0.154.0, mcw-bridges | size 34392, **nextId 36418, 2026 stale** | size 34392, nextId 34392 / same | **`No value with id 35212`**, Network Protocol Error |

Both ends report 34392 block states (m58's "42954" is that count for its larger mod set, as UPG read it); the server's palette
carries ids up to 36417.

## Root cause

The **dedicated server's** `Block.BLOCK_STATE_REGISTRY` is wrong, from its registration window on; the client's is right.

1. On NeoForge's base the map is NeoForge's `BlockCallbacks.BLOCKSTATE_TO_ID_MAP` (`ClearableObjectIntIdentityMap`, an
   `IdMapper`). It is empty when mods are constructed (probe `construct`: size 0, on the Forbric server, the Forbric
   client and native NeoForge alike).
2. fabric-registry-sync 7.1.0 attaches a `StateIdTracker` to that map (`register(BLOCK, Block.BLOCK_STATE_REGISTRY, …)`):
   its `onEntryAdded` hands each newly registered block's states to `IdMapper::add` at once, as Fabric numbers states
   (javap; pinned by `BlockStateIdNumberingStagedTest`). So while the kernel's registration window registers
   mcw-bridges' blocks, their 2026 states land at ids 0..2025.
3. The freeze that closes the window runs NeoForge's `BlockCallbacks.onBake`, which walks the whole block registry and
   `add`s every state from `nextId` on, emptying nothing first (javap; same test). `unfreezeData` empties nothing either.
   So air is 2026, stone 2027, …, and every state's id is its native id + 2026 (dump `runs/fb-bridges/k1probe-server.txt`:
   `0-2025=stale:mcwbridges 2026-34391=live:minecraft 34392-36417=live:mcwbridges`). `size()` counts distinct states
   (34392) and is right; the ids are not.
4. The client numbers natively: lane S's `835ec80c` empties the map right before the client entrypoint window's freeze,
   so the client's last bake starts from an empty map (dumps `client-pre`/`client-post`: 0 stale, liveSha
   `4fbe5c007e19eda2`, before and after the sync). The server sends `35212` for the acacia pier (native `33186`); the
   client's map stops at 34391 → `No value with id 35212`. A vanilla block in a chunk is decoded as the state 2026 ids
   further on (a different block) without any error, which is why joins without a modded block in view passed.

Native arm: NeoForge 26.2.0.93's own server (native-controls image, `K1_SERVER=native`, mods mcw-bridges + probe):
size 34392, nextId 34392, 0 stale, liveSha `4fbe5c007e19eda2` — the same block registry (blocksSha
`8199c06e65160ef2` on every arm) and **exactly the Forbric client's numbering**; the Forbric client (with fabric-api)
joins it cleanly (`runs/native-bridges`). The fixed Forbric server's live list equals the native one line for line.

Pre-Stage-4 arm (an APFS clone of `c3-merged-ro` cf0498eb on the merged stage, `runs/c3-fapi-bridges`): server and
client number natively (liveSha `4fbe5c007e19eda2`), join clean. Its server log says why:
`rebuilt NeoForge blockstate→id map (34392 states, was 2026) — 32366 more state(s) were registered after the first
pass`. On the merged base the bake did not refill the map, so after the freeze it held only fabric-registry-sync's 2026,
the count check of `KernelRegistryContent.rebuildBlockStateIds` saw 2026 ≠ 34392, and it cleared and rebuilt the map in
registry order. On NeoForge's base the bake appends all 34392 after the 2026, the count is 34392 = 34392, and the
rebuild never ran (G-A: "never prints on the genuine base"; lane S then deleted it, `35985d32`). So the regression is
the C1 base flip under a repair whose guard compared counts, not ids.

Differentials that flip the symptom (all on the unfixed tree 7aaebbec, same world, same placement):
- **without fabric-api on the server** (`base-nofapi-bridges`): server numbers natively (0 stale), join clean;
- **`-Dforbric.fabricRegistryInitialization=off` on the server only** (`base-fapi-friOff-server`; fabric-api present,
  its BootstrapMixin/MainMixin kept suppressed, so no `StateIdTracker` is attached): server numbers natively, join clean;
- **the fix** (`fix-fapi-bridges`): server numbers natively, join clean.

What would have falsified it, and the check: stale entries on a server without fabric-api, or with the tracker never
attached (both 0); stale entries after the fix (0); a client whose numbering differs from native NeoForge's (equal,
before and after the sync); a pre-Stage-4 server that numbered twice and still joined (it did not number twice: the
rebuild line).

Candidates from the brief, judged:
- fabric-registry-sync's id rebuild against NeoForge's: **yes**, but it is the tracker's add-as-registered, not a remap
  rebuild (no remap happens on a dedicated server; on the client its remap recalculation and NeoForge's snapshot bake
  both yield the native list);
- `KernelRegistryContent.rebuildBlockStateIds`: it hid the double numbering on the merged base and could not see it on
  NeoForge's base (count-only guard); it is gone (S), and nothing of it is restored;
- registration window ordering vs the server's order: **no** — both block registries are identical (blocksSha);
- a server map appended twice: **yes**, exactly (tracker, then bake).

## Fix

`ac71bf38` — `KernelLifecycle.closeRegistrationWindow` calls `clearBlockStateIds(cl)` (NeoForge's own
`ClearableObjectIntIdentityMap.clear()`, the one `BlockCallbacks.onClear` calls) right before `freeze(cl)`, as lane S's
`835ec80c` already does before the client entrypoint window's freeze. Every freeze the kernel drives now starts
NeoForge's bake from an empty map, which is NeoForge's native precondition (the native server's map is empty at mod
construction, and afterwards holds each state once: nextId = size = 34392, 0 stale). Whatever fabric-registry-sync's tracker added during the window is
renumbered by that bake, in registry order, which is also the order native Fabric's tracker produces. Nothing is
special-cased: no mod, id or fixture; no new switch (the clear is part of the freeze the kernel already owns, like S's,
and has no switch either). fabric-registry-sync's tracker keeps working where it is needed: its remap recalculation on a
client that joins a pure Fabric server (m14) is untouched. Javadocs that described the merged base's map (a clear on
unfreeze, a bake of the window's blocks only) say what NeoForge's base does.

`bc6ea650` — gate-m12 places an mcw-bridges acacia pier and a diamond block at spawn before the join (setworldspawn,
forceload for the placement only, retry until "Changed the block") and the client probe reads both back by name. This is
the check that was missing: a wrong numbering is silent for vanilla blocks.

`30c5277f` — three ci-unstaged rows for the new staged test (measured).

Owner note (K3 owns KernelLifecycle.java): the hunk is committed here because the fix is one line next to S's identical
call and the lane's tests and gates need it; the same hunk is in `c5/k1/for-k3.patch`. `git merge-tree` of s4-c5-k1 with
s4-c5-k2 and with s4-c5-k4 is clean.

## Tests and red controls

- `KernelLifecycleRegistrationWindowTest` (+2): `theRegistrationWindowEmptiesTheBlockStateMapRightBeforeItsFreeze`,
  `everyFreezeTheKernelDrivesIsTheOneRightAfterAClear` (freeze has exactly the two window closes as callers); S's client
  test now shares the helper. **Red control** on a scratch clone with only the new `clearBlockStateIds(cl)` removed:
  exactly `theRegistrationWindowEmptiesTheBlockStateMapRightBeforeItsFreeze` red (`k1/red-mutation-unit.log`).
- `BlockStateIdNumberingStagedTest` (+3, the premise on the real bytes of the carrier, the base and the pinned
  fabric-api): onBake walks the registry and adds without clearing (in-test control: the same predicate finds the clear
  in `onClear`), `IdMapper.add` numbers from `nextId`, `unfreezeData`/`MappedRegistry.unfreeze` clear nothing,
  `StateIdTracker.onEntryAdded` hands the states to `IdMapper::add` and exactly one fabric-registry-sync site attaches it
  to `Block.BLOCK_STATE_REGISTRY`. If NeoForge's bake starts clearing or Fabric's tracker stops adding, it goes red and
  says the clear may be unnecessary.
- gate-m12's read-back (above), red 28/9 without the fix and with the fix's clear removed, green 37/0 with it.

## Acceptance (lane env, ports 46860-46879; counts are `^[kernel] PASS/FAIL` lines; logs `c5/k1/gates/`)

| run | tree | result | note |
|---|---|---|---|
| gate-m58 as committed (phases main sp held aa bb transfer energy forge installed controls) | s4-c5-k1 | **216 / 0** | was 211 / 5 at 7aaebbec: s3, s4, bb-write, bb-read, c1 joins now pass; `held` unchanged (D74) |
| gate-m58 full set, phase main, K1+K2 merge-tree clone, held mods and held*.commands moved into main (scratch edit) | K1+K2 | 52 / 3 | s3/s4 joins pass, S2→S3 0 and S3→S4 0 differences; the 3 FAILs are my scratch edit putting balm, shogi, sophisticatedcore in REAL_MODS, whose write check wants a population line of their own |
| K2's six-mod set (k2-repro.sh, gate-m12 harness) | K1+K2 | **32 / 0** | K2 alone: 27 / 5 on `No value with id 37849` |
| gate-m13 | 7aaebbec | 30 / 0 | |
| gate-m13 | 7aaebbec minus 835ec80c | **26 / 4** | G-C's four (walk, swim, movement flag, Grim control) |
| gate-m13 | s4-c5-k1 | **30 / 0** | |
| gate-m12 (with the read-back) | s4-c5-k1 | **37 / 0** | 32 earlier checks + 5 new |
| gate-m12 (with the read-back) | 7aaebbec / fix minus its clear | 28 / 9 / 28 / 9 | `No value with id 35212` |
| UPG's m12 repro (m58 set via M12_MODS, with / without mcw-furniture) | s4-c5-k1 | **37 / 0**, **37 / 0** | UPG: 31 / 1 (tick lag) and 32 / 0 without a read-back |
| gate-m14 | s4-c5-k1 | **33 / 0** | read-back `andesite_bridge[connection=middle_ns]`, diamond block |
| gate-m14 control A (`M14_EXTRA_JVM=-Dforbric.fabricRegistryInitialization=off`) | s4-c5-k1 | 33 / 0 | re-measured: reads `andesite_bridge[connection=base]`, a neighbouring state of the same block, so the block-name check stays green (was 32 / 1 reading `minecraft:note_block` before S + K1) |
| gate-m26 | s4-c5-k1 | **45 / 0** | probe_block 32366, late_block 32368; a dedicated server with the same canaries numbers probe_block 32366 (`runs/fix-m26canaries`) |
| gate-m9 | s4-c5-k1 | **163 / 0** | unchanged |
| native NeoForge .93 server + Forbric client (fabric-api, mcw-bridges) | 7aaebbec / s4-c5-k1 | join clean / join clean | the client numbers natively on both |
| Forbric server + client, same mods (`repro.sh`) | s4-c5-k1 | join clean, server = native list | |

Full suite on the branch tip `bc6ea650` (APFS clone, pinned, plain --offline, `-Pforbric.requireFixtures=all jar
cleanTest test --continue`): **5820 tests / 0 skipped / 5 failed**, exactly failures-wave2.txt's five names (0 new,
0 gone); +5 = this lane's tests. Baseline at 7aaebbec in the lane worktree: 5815 / 0 / 5, same names.

Not run: K4's `run-m12-ws.sh` (waystones-fabric needs K4's PoI fix, not on this branch); gate-m12 with K2's default set
(sophisticatedcore + its two checks) together with this read-back (expected 39 / 0 after the merge, not measured).

## What other lanes must change

- **UPG (gate-m58, D74), once K1 and K2 are both in:** nothing for K1's own checks (the five joins pass as the gate is).
  For D74, K2's list holds (handoff "K2 review fixes"), with one correction measured here: only waystones and
  sophisticatedbackpacks go into REAL_MODS; balm, shogi and sophisticatedcore must stay libraries outside the
  population write check (in REAL_MODS each fails "no OK line of world.commands puts <mod> content in the world").
  held.commands / held-player.commands lines into world.commands / player.commands as they are: with them in phase main
  both new joins pass and both diffs are 0 (K1+K2, one run). Optional K1 line for each new client's game log in main and
  bb: `check_absent "No value with id"`.
- **G-C (gate-m14):** control A is no longer red (33 / 0): with S's and K1's clears the client's own map is native, so
  the unsynced state id lands on a neighbouring state of the same block, as the header predicts, and "the shared mod's
  block reads back as itself" compares block names only. To keep a red control, assert the full state in that check
  (`state Block\{mcwbridges:andesite_bridge\}\[connection=middle_ns\]`, measured on the default run; control A reads
  `connection=base`), then re-measure control A, and replace the header's "32 PASS / 1 FAIL … minecraft:note_block"
  paragraph. A-10 (raw-id survival line) is unblocked.
- **G-C / merger (gate-m13):** m13's green at the tip comes from S's 835ec80c (A/B above); K1 keeps it green.
- **K3:** if K3 rewrites `closeRegistrationWindow`, keep `clearBlockStateIds(cl)` immediately before `freeze(cl)`
  (pinned by KernelLifecycleRegistrationWindowTest; patch `c5/k1/for-k3.patch`).
- **K4 / merger:** re-run `c5/k4/run-m12-ws.sh` on K1+K4 (expected join pass; not measured).
- **merger:** gate-m12 also carries K2's hunks; merge-tree with s4-c5-k2 is clean. Expected m12 default count after
  K1+K2: 39 / 0.

## Open items

| item | owner |
|---|---|
| gate-m14 control A needs a full-state check to be a red control again | G-C |
| m58 `held` → main per D74 with the library correction above | UPG (after K1 + K2 merge) |
| K4's waystones-fabric m12 set on K1+K4 | merger / K4 |
| gate-m26 asserts id ≥ 1 and a round trip, not "client id == dedicated-server id"; the equality was measured here by dump, not by the gate | orchestrator (whether m26 should assert it) |

## K1 review fixes (2026-11-11)

Reviews: `c5/review-k1-A.md` (lens A, adversarial) and `c5/review-k1-B.md` (lens B, completeness and record). Every
finding was checked again here; all were confirmed, none refuted. Evidence: `c5/k1/gates/rf-*` (each `.out` now carries
`src=` and a `.src.diff`, see B-5) and `c5/k1/review-fixes/` (focused and mutation unit runs, the unstaged measurement,
the suite) and `c5/k1/rf/` (the gate batch, the K1+K2 merge patch, the UPG patch, the six-mod probe dumps).

| finding | outcome | commit |
|---|---|---|
| A-1 m14 control A green after K1 (name-only check) | fixed: the server is asked whether it holds `mcwbridges:andesite_bridge[connection=middle_ns]` ("Test passed") and the client must read that full state; default **34/0**, control A **33/1** (the one red is the full-state read-back, `[connection=base]`); header rewritten | `c53206aa` |
| A-2 m12 read-back skipped without mcw-bridges | fixed: the vanilla block is placed and read back for every `M12_MODS` set, the pier only with mcw-bridges | `13154197` |
| A-3 the defect class is silent at runtime | fixed: `KernelLifecycle.verifyBlockStateIds` after both kernel-driven freezes (outside the freeze's try): `nextId == size()` and `size()` = the states of every registered block, a count line when it holds, a `Caught.Invariant` at WARN when not; gate-m12 asserts both ends' count lines and the CAUGHT's absence | `92ffc52b`, `13154197` |
| B-1 UPG hand-off not executable, layout never run | fixed: the edit is saved as `c5/k1/rf/upg-d74-held-to-main.patch` and measured on all default phases (below) | record |
| B-2 six-mod 32/0 was one join, no read-back, no dump | fixed: re-run on K1+K2 with the probe and the new checks (below) | record |
| B-3 K4's 27/5 unexplained in the record | fixed in the record (below); measured by review A, not re-run here | record |
| B-4 m14 control A credited to K1's clear | fixed: measured at a 7aaebbec-source arm, the same 33/1 and `[connection=base]`; credited to S's `835ec80c` (inferred) | `c53206aa`, record |
| B-5 `head=` of scratch arms named the worktree's HEAD | fixed in the lane harness (`rungate.sh` prints `src=` = sha of HEAD + the arm's diff, keeps `<label>.src.diff`); how the earlier arms were made is below | harness, record |
| B-6 m58 game logs not kept | fixed in the lane harness (copies `build/verification/m58-world-upgrade/logs` for m58 only); the earlier `m58-fix` / `m58-full-main-k12` runs kept only their `.out` (the record above said otherwise) | harness, record |
| B-7 gates the fix reaches were not run | fixed: m15, m16, m33, m39, m40, m52 run at the tip, with m13, m26, m9 again (below) | record |
| B-8 open items / hand-offs incomplete | fixed: gate-m12 default on K1+K2 measured (**43/0**: the 39/0 forecast plus this review's four checks); SWAP: nothing; S: nothing | record |
| B-9 no census of sibling maps | fixed: `theFluidStateMapHasNoSecondNumbering` pins it on the real bytes (only vanilla's `Fluid`/`Fluids` touch `FLUID_STATE_REGISTRY`; no carrier class does); ci-unstaged row measured | `da4d0bdd`, `e589bc7d` |
| B-10 two overclaims | fixed: gate-m12's comment says only the window's states were numbered twice and every id was offset (`13154197`). Correction for `ac71bf38`'s body, which cannot be reworded without rewriting history: the native NeoForge arm ran mcw-bridges and the probe only, not "the same mods" (native NeoForge cannot load fabric-api) | `13154197`, record |

### A-3: the check, its tests and its red controls

- Unit: `BlockStateIdInvariantTest` (6): the arithmetic on K1's measured numbers (36418 / 34392 / 34392 is a problem
  though every state is mapped; an empty map after the freeze is one; states of no registered block are one), the check
  never throws (off-game it reports through Caught, `@ExpectCaught`), and both closes call it after their freeze and
  outside the freeze's try. **Mutation** on a scratch clone (`review-fixes/red-mutation-unit.log`): with the `nextId`
  comparison dropped and the client-window call removed, exactly `aNumberingAppendedAfterAnotherIsAProblemThoughEveryStateIsMapped`
  and `bothWindowClosesCheckTheMapAfterTheirFreeze` fail (KernelLifecycleRegistrationWindowTest 7/0).
- Game, red: gate-m12 with `M12_MODS` = fabric-api 0.154.0 + mcw-furniture + collective (no mcw-bridges) on a scratch
  clone with only K1's clear removed (`rf-m12-furniture-red`): **35/5** — the server prints
  `Caught$Invariant: blockstate->id map: next id 49490, 40928 state(s) mapped, 40928 registered; 8562 id(s) are stale
  slots …` instead of its count line, the client reads the diamond block as `minecraft:acacia_fence[…]` with no error
  (the silent half), and the fifth FAIL is the tick-lag check (one 2029 ms overload). Green, same set, at the tip
  (`rf-m12-furniture-tip`): **40/0**, server and client `40928 states, each numbered once`.
- Every gate run at the tip below printed the count line in its kept new-kernel logs and reported `CAUGHT none`; so did the
  m58 run on K1+K2 (servers of 32366 to 47050 states, its old-kernel JVMs excepted: they predate the check).

### B-1: the UPG edit for D74, measured

`c5/k1/rf/upg-d74-held-to-main.patch` (against `gate-m58-world-upgrade.sh` and `canary/world-upgrade/` at this branch):
- `REAL_MODS` gains `waystones-neoforge-26.2-26.2.0.5.jar` and `sophisticatedbackpacks-26.2-3.26.2.2154.jar`;
- a new `LIB_MODS=(balm, shogi, sophisticatedcore)` goes into `MAIN_MODS` and into the missing-file check, and NOT into
  `REAL_MODS` (write_checks asks each `REAL_MODS` jar for a population line of its own; libraries have none). This
  corrects K1's first hand-off, which named no place for them;
- `held.commands` lines go to the end of world.commands' D53 block and `held-player.commands` lines into player.commands
  (hotbar.4 and hotbar.6), unchanged; both held files are deleted;
- phase `held` is deleted (the step, `HELD_POPULATED`/`HELD_MODS`, the header paragraph, the default `PHASES` entry and
  the manifest line), as D74 says. Left for UPG: `write_checks`' `WC_BOX_FORCED` branch is now unused, and
  `canary/world-upgrade/README.md` still describes the held files.

Measured on an APFS clone of this branch with `s4-c5-k2` (e37b0cf0) merged in by `git merge-tree` (clean;
`c5/k1/rf/k1-c53206aa-to-k1k2-e37b0cf0.patch`) and the patch applied (`rf-m58-k1k2-heldmain`, default phases
`main sp aa bb transfer energy forge installed controls`, logs kept): **179 / 0**, CAUGHT none. The waystones and
sophisticatedbackpacks population lines are OK on the old server and the new (bb) server; S2→S3 and S3→S4 (server),
S2c→S3c and S3c→S4c (singleplayer) and B/B S2n→S3n are 0 differences; the forge phase's control C5 still finds its 4
mcwbridges differences. 216/0 as committed counted phase held's checks, which the patch deletes.

### B-2: the six-mod set again, with the probe

fabric-api 0.155.2 + balm, shogi, waystones, sophisticatedcore, sophisticatedbackpacks + `k1probe.jar`, gate-m12 harness,
K1+K2 clone (`rf-m12-six-k1k2`, dumps `c5/k1/rf/six-k12-probe/`): **40/0** (the vanilla read-back runs; no mcw-bridges, so
no pier). Server: size = nextId = 36462, 0 stale, liveSha `ead729dc7092c614`. Client before the sync: size = nextId =
36462, 0 stale, but its block registry order differs (blocksSha `e838a0a54840064a` vs the server's `611ca548eb651cf9`);
after the sync its blocks and its live list equal the server's (`611ca548…`, `ead729dc…`). So the server numbers once, and
the join's remap (K2's path) brings the client to the server's numbering.

### B-3: K4's 27/5

K4's unexplained `No value with id 36329` on gate-m12 with the waystones-fabric set is K1's mechanism: every unfixed
dedicated server with fabric-api carries the offset, and a join fails only when a chunk it sends holds a state whose
shifted id runs past the client's map, so the failure comes and goes with what is in view. Review A ran K4's
`run-m12-ws.sh` on the K1+K4 merge-tree: 32/0, against 27/5 on K4 alone (`c5/review-k1-A/m12ws/`). Not re-run here.

### B-4 and B-5: how the arms were made

- The arms of the first round were APFS clones of the lane worktree; their `head=` is the worktree's HEAD at the time,
  not the measured source. `m12-red*`/`wt-red` = the branch with only the new `clearBlockStateIds(cl)` removed;
  `m12-base2`/`m13-base`/`wt-base` = 7aaebbec's sources; `m13-no835`/`wt-no835` = 7aaebbec with `835ec80c` reversed by
  `patch -R` (review B checked that it reverses cleanly on 7aaebbec's file). Their diffs were not kept.
- This round: `src=` and `<label>.src.diff` on every `rf-*` run. `rf-m12-furniture-red`: the tip minus the K1 clear
  (`src=7219909a080989a4`); `rf-m14-ctlA-7aaebbec`: the tip with `git diff 7aaebbec HEAD -- forbric-kernel/src`
  reversed (KernelLifecycle.java byte-equal to 7aaebbec's) and this round's gate-m14 (`src=f558adb5adcf6734`).
- m14 control A at that 7aaebbec arm: **33/1**, the client reads `[connection=base]` exactly as at the tip, so K1's clear
  does not move it (its server is pure Fabric, and the client renumbers from empty at its last freeze). The move from the
  2026-11-09 `minecraft:note_block` reading is lane S's `835ec80c` (committed 2026-11-10, after that measurement):
  inferred, not measured without it.

### Suite and gates at the tip

Counts are `^[kernel] PASS/FAIL` lines; logs `c5/k1/gates/rf-<label>.out` and `.logs/`; ports 46860-46879.

| run | tree | result | note |
|---|---|---|---|
| gate-m12 default (`rf-m12-tip`) | da4d0bdd + this round's gate edits (= `13154197`'s gate-m12) | **41 / 0** | 37 + the four count/CAUGHT checks; 34392 states on both ends |
| gate-m12, fabric-api 0.154.0 + mcw-furniture + collective (`rf-m12-furniture-tip`) | same | **40 / 0** | vanilla read-back alone; 40928 states |
| the same set, K1's clear removed (`rf-m12-furniture-red`) | scratch clone | 35 / 5 | red control of A-2 and A-3, above |
| gate-m14 (`rf-m14-tip`) | `13154197` + the m14 edit (committed as `c53206aa` with only its header reworded) | **34 / 0** | "Test passed"; client `[connection=middle_ns]` |
| gate-m14 control A (`rf-m14-ctlA-tip`) | same | 33 / 1 | the red is the full-state read-back (`[connection=base]`) |
| gate-m14 control A (`rf-m14-ctlA-7aaebbec`) | 7aaebbec sources + this gate | 33 / 1 | same reading: K1's clear does not move it |
| gate-m12 default (`rf-m12-k1k2`) | K1 `c53206aa` + K2 `e37b0cf0` merge-tree | **43 / 0** | B-8 |
| gate-m12, six-mod set + probe (`rf-m12-six-k1k2`) | K1 + K2 | **40 / 0** | B-2 |
| gate-m58, D74 patch, default phases (`rf-m58-k1k2-heldmain`) | K1 + K2 | **179 / 0** | B-1 |
| gate-m15 / m16 (`rf-m15-tip`, `rf-m16-tip`) | `e589bc7d` | **22 / 0**, **39 / 0** | client to a Forbric dedicated server over a socket (B-7) |
| gate-m13 / m26 / m9 | `e589bc7d` | **30 / 0**, **45 / 0**, **163 / 0** | m9 on an APFS clone of client-merged-pack (`M9_RUNDIR`): 97562 states |
| gate-m33 / m39 / m40 / m52 | `e589bc7d` | **22 / 0**, **6 / 0**, **36 / 0**, **29 / 0** | servers with fabric-api (B-7) |
| gate-m58 as committed, all phases incl. held (`rf-m58-tip`) | `e589bc7d` (+ this record, uncommitted then) | **216 / 0** | game logs kept this time (45 files) |

Every run above reported `CAUGHT none` except the red control; every kept new-kernel log carries the count line and none
carries "not one id per block state" (m39's kept log is its test driver's, with neither).

Full suite at `e589bc7d` (lane worktree, pinned, plain --offline, `-Pforbric.requireFixtures=all jar cleanTest test
--continue`, `c5/k1/review-fixes/suite/`): **5827 tests / 0 skipped / 5 failed**, exactly failures-wave2.txt's five names
(0 new, 0 gone); +7 = BlockStateIdInvariantTest's 6 and the fluid census. Unstaged profile (plain `git clone` of
`c53206aa`, staged props pointing nowhere, `review-fixes/unstaged/`): BlockStateIdInvariantTest 6/0 runs; the staged
class's 4 skip on `[fixture:staged]`, all four rowed in ci-unstaged.

### What other lanes must change (replaces the list above where they differ)

- **UPG (gate-m58, D74), after K1 and K2 merge:** apply `c5/k1/rf/upg-d74-held-to-main.patch` (or its content) and delete
  phase held; measured 179/0 on all default phases on K1+K2. Then tidy `WC_BOX_FORCED` and the world-upgrade README.
- **G-C (gate-m14):** nothing to do for control A any more: this branch makes it red again (33/1) and rewrites the
  header. G-C's branch does not touch gate-m14 (`git merge-tree` clean). A-10 (raw-id survival line) stays G-C's.
- **K3:** keep `clearBlockStateIds(cl)` immediately before `freeze(cl)` and `verifyBlockStateIds` after the freeze's try
  in `closeRegistrationWindow` and `closeClientEntrypointWindow` (pinned by KernelLifecycleRegistrationWindowTest and
  BlockStateIdInvariantTest). `git merge-tree` with s4-c5-k3 (d375ad0f) is clean.
- **K2, K4:** nothing; merge-tree clean with s4-c5-k2 (e37b0cf0) and s4-c5-k4 (fca37afe).
- **SWAP, S:** nothing. (K1 reworded the javadoc and the warning of S's `clearBlockStateIds`; behaviour unchanged.)
- **merger:** gate-m12 after K1+K2: **43/0** (measured). gate-m14: 34/0. After the merge, `gate-m58` as committed still
  has phase held until UPG applies the patch.

### Open items

| item | owner |
|---|---|
| apply the D74 patch to gate-m58, delete phase held, tidy `WC_BOX_FORCED` and the README | UPG (after K1 + K2) |
| gate-m26 asserts id ≥ 1 and a round trip, not "client id == dedicated-server id" (measured equal by dump, 32366) | orchestrator |
