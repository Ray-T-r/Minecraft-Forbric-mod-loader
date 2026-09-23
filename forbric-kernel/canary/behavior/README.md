# M35 actual-world behavior contract

The single `forbricbehaviorprobe` NeoForge test mod registers a real entity type and listeners on both
NeoForge and Forge's game buses. It is explicitly one mixed-bus fixture, not three native mods. Its only
Mixin changes the Forge portal hook's return value in the one replacement scenario. The native carriers
have no shape replacement setter; that scenario verifies the contract of a mod transforming the hook.

Eleven cases execute after the actual dedicated server starts in a nonce-owned fresh world:

- Four portal cases place real obsidian frames and a fire block, thereby entering `BaseFireBlock.onPlace`.
  Allow builds six portal blocks, each family's cancellation builds none, and a Forge hook-return Mixin
  directs creation to a second frame. Every event count and resulting frame is asserted; the Neo veto
  does not dispatch Forge, and the return Mixin must actually run once.
- Six spawner cases place a real spawner block entity, load its serialized spawn settings, and call its
  real server ticker, which reaches the unmocked `BaseSpawner.serverTick` in the running ServerLevel.
  A real Neo fake player satisfies the native nearby-player condition. A registered test mob observes
  its real `Entity.load` ValueInput and delegates finalization to `Mob.finalizeSpawn`; only its spawn
  predicates are deterministic. The Forge event must receive that exact input object, and the only
  finalization must receive Forge's replacement SpawnGroupData. Cancellation of finalization remains
  separate from world insertion veto; a later Forge listener cannot undo Neo's insertion veto. A custom
  NBT case passes a real field through ValueInput and respects native `initialize=false` semantics.
  No event is manually posted. No kernel helper is called by this fixture.
  Insertion is measured by the native added-to-level flag, which ServerLevel sets only after its section
  manager accepts the entity. The public entity lookup is recorded separately: newly loaded sections may
  not become accessible until the next chunk visibility update, so absence there is not a spawn veto.
- One item case inserts the test mob into a forced real chunk and starts milk consumption. The server's
  normal world ticks invoke the living entity completion path. Neo changes the result to gold; Forge
  must receive gold and replace it with two diamonds carrying custom components. The live hand slot,
  both callback counts, and actual entity ticks are checked. The fixture does not manually tick the
  entity or call `completeUsingItem`.

`gate-m35-behavior.sh` runs the same jar in four fresh worlds: all repairs enabled; portal repair off;
spawner repair off; unified events off (item return bridge). Negative acceptance requires exactly the
named behavioral failures plus completion of all eleven cases, not a crash, missing mod, startup error,
or timeout. Portal-off must fail only replacement writeback. Spawner-off must fail five Forge-dependent
spawner cases while Neo's cancel-finalize case still passes. Unified-events-off must fail only item result.
The spawner-off experiment explicitly selects compatibility `continue` because its old input-less helper
records a confirmed required loss; the final compatibility JSON must still contain that finding.

Run from the worktree root (the root agent schedules kernel Gradle/server work):

```sh
FORBRIC_OLD=/Users/jerry/Documents/Forbric/forbric-loader bash forbric-kernel/run/gate-m35-behavior.sh
```

`build-behavior-canary.sh` performs only javac/jar and can run separately. Compilation uses an upstream
NeoForge game API plus the two event carriers; runtime uses the kernel's recorded merged carrier. Gate
results, logs, source/artifact/mod hashes and probe JSON are retained under `build/verification/m35-behavior`.
The mod disarms itself outside the gate-owned world. Original workspace and player worlds are untouched.
This is a deterministic behavioral contract test, not a modpack compatibility or natural mob-spawn rate test.
