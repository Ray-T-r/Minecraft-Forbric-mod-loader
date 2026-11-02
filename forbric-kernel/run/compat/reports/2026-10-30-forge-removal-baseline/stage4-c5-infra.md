# Stage 4 C5, lane INFRA: Phase 0 (0a-0e)

Phase 0 of the C5 plan (`s4/c5/PLAN.md` §1 as amended by D45, D46, D66 and the critic's F1/F3/F7/F8/F15), run on the
stage branch `claude/remove-forge-neoforge-fabric-83b024` itself, from `ecc93165`, before any lane worktree was cut.
Ports 46600-46607 (block 46600-46619). Logs: `s4/c5/infra/` (scratchpad).

## Commits

| sha | step | subject |
|---|---|---|
| `cee74905` | 0b (H1) | Stop tracking run/installed, run/energy-api and run/.gate-downloads, since every worktree cut from the branch checked them out as symlinks looping into one worktree's own path |
| `3500dc9b` | 0c (D39/D46) | Add refuse_shared_rundir to lib.sh, so a gate can refuse a rundir that is, or resolves into, a fixture every worktree shares |
| `c365cf58` | review INFRA-1 | Make refuse_shared_rundir also refuse forge-swap at its own home, since a worktree cut before that fixture existed has no run/forge-swap link to find it by |
| `28c97cb6` | review INFRA-3 | Drop the trailing slash from the run/carry-on and run/modmenu ignore patterns, since every worktree links them and a pattern ending in / never matches a link |

0a, 0d and 0e change only untracked files (`.stage4/`, the scratchpad, `.stage/`).

## 0a: the neolive canary (H2, D45)

`run/build-neolive-canary.sh` at `ecc93165` with `FORBRIC_OLD=.stage4` (through heavy.sh; `MC_DIR` not set).

| | file | bytes | sha1 |
|---|---|---|---|
| stale (kept) | `.stage4/run/neoforge-runtime/forbricneolive.jar.stale-da7d005a` | 12016 | `da7d005afe3fa264b4c9d3063092e7f7790bc24f` |
| fresh (in place) | `.stage4/run/neoforge-runtime/forbricneolive.jar` (= `run/canary/forbricneolive.jar`) | 32930 | `2e74d5897656edf59b62191e78ceb3a8e99d1a18` |

The file sha1 is not reproducible across builds (jar entries carry build times; the earlier 20:46 build of the same
sources, 32930 B, was `e24767d1…` and was overwritten by this build). A rebuild compares by content: sha1 over the sorted
non-directory entries (name + bytes) = `c001358616720d37c6a9d5ea020b279dd1e76022`. The stale jar has none of the probe
strings; in the fresh one `/CFG]` is in 2 entries (9 distinct strings), `/SCAN]` in 1 (8), `/CLIENT]` in 2 (27)
(corrected after review INFRA-5: the first version gave `grep -c` counts over the binary stream, ×4/×2/×17, which count
newline-split stretches, not entries or strings). The jar has 34 entries, incl. `ForbricNeoLiveClient*`,
`ForbricNeoLiveMixinPlugin`, `NeoLiveScanned*`, the mixin config and the biome/structure-modifier data). The stale name
does not end in `.jar`, so nothing that lists `neoforge-runtime/*.jar` picks it up.

Check: gate-m28 on `.stage4` with the fresh canary (`M28_PORT=46606`): **16 PASS / 2 FAIL**, the same as
`s4/c5/gates/m28fresh.out`; the two FAILs are the Forge COMMON-config lines G-A owns (`Forge COMMON configs were applied
(want>=1 got 0)`, `config/forge-common.toml was not created`).

Per D45, every C5 gate result read with the stale jar that reads `ForbricNeoLive` lines is void. Lane worktrees cut
after this step clone `.stage4` and so get the fresh jar. A lane that edits `ForbricNeoLiveMod` keeps its line count or
rebuilds and records the new content sha1 (D66/F17).

## 0b: the three looped links (H1)

`git rm --cached forbric-kernel/run/{installed,energy-api,.gate-downloads}` (mode 120000, from 36dfef28). In
`forbric-kernel/.gitignore` the three patterns lose their trailing slash (a pattern ending in `/` never matches a
symlink), and the C5 paths are ignored before they exist (F15): `/run/forge-swap`, `/run/world-upgrade` (gate-m58's
`rundirs=world-upgrade`, map-upgrade §3.2/§6), and `/run/*-m5[89]`, `/run/*-m6[0-2]`, `/run/m5[89]-*`, `/run/m6[0-2]-*`
for the rundirs of m58-m62. `git ls-files -ci --exclude-standard -- run` lists only the three removed links, so no tracked
file is newly ignored.

In this worktree: `run/installed` is an empty local directory, `run/energy-api` a link to the main checkout's,
`run/.gate-downloads` an APFS clone of the main checkout's (`ac-server`, `fab-server`; not a link, F7). After the commit
`git status` shows only `?? .stage4/`; `git ls-files -s` of the three paths is empty.

## 0c: refuse_shared_rundir (D39, D46)

`lib.sh` `refuse_shared_rundir <dir>` prints `[kernel] FAIL refusing rundir <dir>: <why>. Clone the fixture …` and
exits 3 when the rundir:

- equals or lies inside one of `SHARED_FIXTURES` = client-merged-pack, client-popular, client-kernel, client-neo-pack,
  carry-on, modmenu, neoforge-builds, forge-swap, checked lexically against this worktree's `run/<name>` and the main
  checkout's (`FORBRIC_MAIN_KERNEL`, default the parent of `git rev-parse --git-common-dir`), and by real path when the
  fixture exists (so a scratch link into a fixture is caught);
- is spelled inside this worktree's `run/` but resolves outside it through a symlink;
- is a dangling link, or the argument is empty.

The rundir need not exist yet. A clone, a directory the gate creates, and a name that only starts like a fixture
(`client-merged-pack-clone`) are accepted. No gate calls it yet; the gate lanes add `refuse_shared_rundir "$RUNDIR"` and
an env override for each fixed rundir in their own files (G-B1 owns `lib.sh` in Phase 1).

Test: `run/compat/test_lib_guards.py` (12 tests, discovered by `tools/dev.py`'s `unittest discover -s run/compat`) runs
the real lib.sh copied into a throwaway tree laid out like a worktree beside a main checkout. Red checks, on mutated
copies in the scratchpad: guard reduced to `return 0` → 9 of 12 fail; containment replaced by a string-prefix match → 1
fails (the `-clone` case). As written: 12/12.

PROTOCOL.md gains a paragraph on the guard that names `run/compat/test_lib_guards.py`: CompatProtocolTest requires
every shipped script under `run/compat` to be named there, and the first trial-lane suite (0d) caught its absence
(first written as `0b8b1f17` without it; amended to `3500dc9b` before any lane was cut). CompatProtocolTest +
KernelBundledMixinExtrasStagedTest after the amend: 3 tests, 0 failed.

Other runs after the commit: `python3 -m unittest discover -s run/compat -p 'test_*.py'` 157 tests OK (incl.
test_gate_ports); `bash -n` of every gate and lib.sh clean; the Java tests that read lib.sh (LibPortContractTest,
LibStopTreeTest, GateM0CaptureStepTest, GateNeoCanaryContractTest, M20OutcomeContractTest, VersionJsonSyncTest,
CompatTransportTest, PushAndRunTest, AntiCheatGateLogTest, GateLogContract*) 68 tests, 0 failed. The scheduler files
(gates-parallel.py, gates-all.sh, test_gate_ports.py, GatesAllTest) were not touched.

## 0d: lane bootstrap (`s4/c5/bootstrap-lane.sh <lane> <branch-base>`, untracked)

Cuts `.claude/worktrees/s4c5-<lane>` on `s4-c5-<lane>` and gives it (D66, F1, F7):

- links to the main checkout: `run/{client-merged-pack,client-neo-pack,client-kernel,neoforge-builds,carry-on,modmenu,
  energy-api}`, `build/{compat-inputs,journeymap-native,purefabric-2026-10-03,sweep100-mac,sweep100-mac-network,
  sweep80-mac,sweep90-mac}`, `forbric-kernel/.dev`, `fabric-loader`, `forbric-loader/run/{forge-patched,forge-runtime,
  neoforge-patched,neoforge-runtime}` (the set this worktree has);
- links to this worktree's real directories (they are not in the main checkout): `run/client-popular` (Stage 3 layout),
  root `build/{lb-vfp,random100-20260929}`;
- APFS clones (`cp -c -R` by real path, checked to be a directory): `run/.gate-downloads` (main's), `build/vanilla-reference`
  (this worktree's; **cloned, not linked as D66 lists it**, because `TestFixtures.vanillaReference` writes its cache there and
  a link would let every lane write into this worktree's copy), the fixed client rundirs `run/client-{carry-m54,
  config-screens-m56,creative-search-m55,depdialog,forge-refusal,forge-registration}` (never linked), and `.stage4`
  (65 MB; a relative link inside it, `minecraft -> ../.stage/minecraft`, is re-pointed at this worktree's `.stage/minecraft`);
- `run/installed` an empty local dir; `run/forge-swap -> ~/Forbric-backups/forge-swap` only when that fixture exists
  (it does not exist yet);
- `.stage4/lane-env.sh`: `FORBRIC_OLD`, `FORBRIC_NEOLIVE` (the lane's `.stage4` copy), `HEAVY_LANE`, and `GATE_PORT` plus
  every other `gates-parallel.py` `PORT_VARS` entry from the lane's PLAN §5 block (F3), `unset MC_DIR`;
- built: `build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar` (one `jar` run through heavy.sh; `BOOTSTRAP_NO_JAR=1` skips it).

Server rundirs, `mp-*`, `fab-*` and `run/canary` are not provided: the gates create them. The script checks that
`git status` stays clean (no fixture path un-ignored), every link resolves, the 0c guard refuses
`run/client-merged-pack`, and `bash -n` passes for all 60 gates.

Trials on a throwaway lane `zz-trial` (`LANE_PORT_BASE=46610`):

1. from `0b8b1f17`: the script found the dangling relative `.stage4/minecraft` link (fixed as above).
2. from `0b8b1f17`, `ok` (84 links, 778 MB logical, APFS clones cost nothing until written). Full suite in the lane
   (`cleanTest test --continue`, `-Pforbric.requireFixtures=all`): 5767 tests, 44 failed = the 41 plus
   `CompatProtocolTest.protocolNamesEveryShippedScript` (my new `test_lib_guards.py` was not named in PROTOCOL.md: fixed
   in 0c, amended) and `KernelBundledMixinExtrasStagedTest` `[1] true`, `[2] false` (the boot jar
   `build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar` does not exist in a fresh worktree: the bootstrap now builds it).
3. from `3500dc9b`, `ok`; full suite: **5767 tests, 0 skipped, 41 failed, exactly the 41 names of `s4/c4/close/suite.names`**;
   against `failures-c4.txt` (c4 `cmp.py`): 0 new, the same 1 gone (RepairDriftCensusStagedTest calibration row), 0 message
   differences over 240 characters, digits not folded. This is the D66/F1 acceptance a lane repeats as its first act.

After each trial the worktree and branch were removed (`git worktree remove --force`, `git branch -D s4-c5-zz-trial`); the
main checkout's fixtures were checked afterwards (`client-merged-pack` 505 MB, 97 mods; `.gate-downloads` both caches).

## 0e: heavy-slot harness (`.stage/`, untracked)

- `lock-lib.sh` (new): a lock is a `mkdir` directory whose `owner` file holds the holder's pid + start time, the command's
  pid + start time, the lane and the command. It is live while either process runs with the same start time (pid reuse is
  not mistaken for it), or for 60 s while it has no owner yet; a stale lock is reaped under a short reap mutex.
- `heavy.sh [--exclusive] <command…>`: two slots as before; the command runs as a child (stdin kept, TERM/INT forwarded,
  exit status passed through). `--exclusive` serializes on `heavy-exclusive.lock`, then takes slot 1 and slot 2; while it
  waits, new single-slot takers wait too. Back-off: a lane that released a slot under 30 s ago does not take one while a
  waiter of another lane exists (lanes that both just released do not hold back for each other). Lane = `$HEAVY_LANE` or
  the git worktree's name.
- `client.sh <command…>`: the client mutex (`client.lock`, same lock code). Taken before a heavy slot:
  `.stage/client.sh .stage/heavy.sh <command…>`.

Before installing, `ls .stage/*.lock*` was empty and `ps` showed only two idle Gradle daemons. Tests on a scratch lock
directory: exit status 7 passed through; stdin passed through; a third run waits for one of two busy slots; a lock of a
dead pid is reaped; `kill -9` of heavy.sh keeps the slot live while its command runs and frees it once the command is
gone; `--exclusive` waits for the busy slot, holds both, and a normal run queued behind it starts after it; a lane that
relaunches the instant its job ends yields the slot to a lane that was already waiting; TERM while queued exits 143 and
leaves no waiter entry; TERM while running ends the command, exits 143 and leaves no lock. The previous heavy.sh is kept
at `s4/c5/infra/heavy.sh.orig`.

## gate-m1 at the end

gate-m1 on `.stage4` with the fresh canary (`GATE_PORT=46600`, through heavy.sh): **17 PASS / 0 FAIL**, line for line the
same as C4 verify's `s4/c4/verify/gates/m1.out`.

## Review of Phase 0 (`s4/c5/review-infra.md`) and what changed

| finding | verdict | change | evidence (`s4/c5/infra/`) |
|---|---|---|---|
| INFRA-1 (MEDIUM) guard blind to forge-swap's home `~/Forbric-backups/forge-swap` | confirmed, fixed | `c365cf58`: lib.sh also checks `$FORBRIC_FORGE_SWAP` (default `$HOME/Forbric-backups/forge-swap`), lexically and by real path; +1 test; PROTOCOL.md; the bootstrap links `run/forge-swap` from the same variable | test fails on the old lib.sh (other 12 pass); `return 0` → 10/13 fail; 13/13 as written; compat python 158 OK (`review-compat-py.out`); Java lib.sh/PROTOCOL readers + compile tasks 93/0 (`review-java.out`); on this machine `refuse_shared_rundir $HOME/Forbric-backups/forge-swap/x` → FAIL, exit 3 |
| INFRA-2 (LOW) client.lock read stale after `kill -9` of both wrappers while the command runs | confirmed, fixed (untracked `.stage/lock-lib.sh`) | owner line 5 names the enclosing lock (`<lockdir>\|<holder pid> <start>`), passed down in `LOCK_ENCLOSING` by the wrapper; a lock is also live while a live lock names it there; the command line is written on one line; the owner-less reap no longer prints `owner: No such file` | reviewer's `heavy-test6.sh`: old lib → second `client.sh` took the lock after 0 s with the command alive (`infra2-t6-oldlib.out`); new lib → it waited 5 s, until the command ended (`infra2-t6.out`); same through `client.sh bash -c "bash -c 'heavy.sh …'"` (`infra2-t9-deep.out`); reviewer's whole `heavy-tests.sh` unchanged otherwise (`infra2-heavy-tests.out`) |
| INFRA-3 (LOW) `.stage4/` not ignored; carry-on/modmenu ignored only by `.git/info/exclude` | confirmed, fixed | `.stage4/` added to the shared `.git/info/exclude` (machine-local, like `.stage/`); `28c97cb6` drops the slash from `/run/carry-on/`, `/run/modmenu/` | `git check-ignore -v` names `forbric-kernel/.gitignore` for both links; `git status` here no longer lists `.stage4/` |
| INFRA-4 (LOW) `s4/c5/controls/m14/slot.sh` is the old owner-less lock loop | confirmed, fixed (scratchpad) | reduced to `exec .stage/heavy.sh "$@"`; the old file kept as `slot.sh.orig` | `slot.sh bash -c 'exit 4'` on a scratch lock dir → `[heavy] slot 1`, rc 4 |
| INFRA-5 (LOW) probe counts were binary `grep -c` | confirmed, fixed | §0a reworded | recount: 2/1/2 entries, 9/8/27 distinct strings (`strings -n 4`), the stale jar 0 |

## Open / for the lanes

- `/run/world-upgrade` is ignored as gate-m58's rundir. If UPG keeps tracked files under `run/world-upgrade/` (map-upgrade
  §3.2 keeps `world.commands` there; F15 asks UPG to say), they need `git add -f` or a `!` line; `.gitignore` has no Phase-1
  owner.
- New rundirs for m58-m62 must be named `<what>-m58` … `<what>-m62` or `m58-<what>` … to fall under the patterns (or under
  the existing `/run/server-*/`, `/run/client-*`).
- `build/lb-vfp` holds no `liquidbounce-*.jar`, so the three LiquidBounce rows stay the recorded Stage-0 fixture reds (D58).
