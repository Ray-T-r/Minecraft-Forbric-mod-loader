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
