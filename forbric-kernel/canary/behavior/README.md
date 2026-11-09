# M35 actual-world behavior contract

The single `forbricbehaviorprobe` NeoForge test mod registers a real entity type and listeners on NeoForge's game
bus. It carries no Mixin.

Seven cases execute after the actual dedicated server starts in a nonce-owned fresh world:

- Two portal cases place real obsidian frames and a fire block, thereby entering `BaseFireBlock.onPlace`.
  Allow builds six portal blocks and NeoForge's cancellation builds none; NeoForge's portal event must be asked
  exactly once in each.
- Four spawner cases place a real spawner block entity, load its serialized spawn settings, and call its
  real server ticker, which reaches the unmocked `BaseSpawner.serverTick` in the running ServerLevel.
  A real Neo fake player satisfies the native nearby-player condition. A registered test mob observes
  its real `Entity.load` ValueInput and delegates finalization to `Mob.finalizeSpawn`; only its spawn
  predicates are deterministic. NeoForge's FinalizeSpawnEvent fires exactly once per case, and the only
  finalization must receive the SpawnGroupData NeoForge's listener set. Cancellation of finalization remains
  separate from the world insertion veto. A custom NBT case passes a real field through ValueInput and respects
  native `initialize=false` semantics (the event fires, finalization does not).
  No event is manually posted. No kernel helper is called by this fixture.
  Insertion is measured by the native added-to-level flag, which ServerLevel sets only after its section
  manager accepts the entity. The public entity lookup is recorded separately: newly loaded sections may
  not become accessible until the next chunk visibility update, so absence there is not a spawn veto.
- One item case inserts the test mob into a forced real chunk and starts milk consumption. The server's
  normal world ticks invoke the living entity completion path. NeoForge's listener changes the result to one
  gold ingot carrying custom components; the live hand slot, the callback count and actual entity ticks are
  checked. The fixture does not manually tick the entity or call `completeUsingItem`.

`gate-m35-behavior.sh` runs the same jar in fresh worlds and judges every case from the probe's JSON: once as `positive`
(all seven pass) and once as `withhold`, the premise control. In `withhold` every NeoForge listener still counts its event
but gives no answer (no portal veto, no replacement SpawnGroupData, no finalize cancel or spawn veto, no result stack), so
exactly `portal-neo-veto`, the four `spawner-*` cases and `item-result` must fail and `portal-allow` must pass: the
positive verdicts come from NeoForge's listeners reaching the game through its live callers. (The three repair-off
phases this gate ran on the merged base, portal-off, spawner-off and item-off, went with the merged base: their
switches are retired and nothing on NeoForge's base is repaired on these paths.)

Run from the worktree root (the root agent schedules kernel Gradle/server work):

```sh
FORBRIC_OLD=/Users/jerry/Documents/Forbric/forbric-loader bash forbric-kernel/run/gate-m35-behavior.sh
```

`build-behavior-canary.sh` performs only javac/jar and can run separately. Compilation uses NeoForge's patched game
and the NeoForge carrier; the build records the game base the launchers run (`base`). Gate results, logs,
source/artifact/mod hashes and probe JSON are retained under `build/verification/m35-behavior`.
The mod disarms itself outside the gate-owned world. Original workspace and player worlds are untouched.
This is a deterministic behavioral contract test, not a modpack compatibility or natural mob-spawn rate test.
