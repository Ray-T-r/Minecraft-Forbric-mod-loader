# M37 real entity callbacks

The fixture is one NeoForge mod subscribing to public Fabric events. The test pack loads the actual,
unmodified `fabric-api-base` and `fabric-entity-events-v1` modules from Fabric API 0.155.2+26.2; their original
Mixin configs, class tweakers and handlers run. This is a focused entity-module gate, not acceptance of the
whole Fabric API umbrella or the broad mixed client pack.

A fresh nonce-owned server world contains a real fake player, a visible zombie, ordinary beds and a registered
NeoForge custom bed with its own occupancy property and setter. Sixteen cases execute through game methods:

- Force-add an effect: one before-add callback mutates its duration; the actual installed effect retains it.
- Remove effects: one before-remove callback sees the still-active effect; the game then removes it.
- Clear all effects with an early-removal veto on one of two: the vetoed effect stays, the other goes, and the
  veto is asked once per effect (NeoForge asks per effect where vanilla clears the map).
- Veto native permitted gliding, provide custom gliding without a native attribute, and cover the boolean
  equipment path. Actual flight flags and callback counts are checked.
- Drive one flight tick at a damage tick: with custom flight and no glider item Fabric's tick question
  (`CUSTOM(entity, true)`) is asked and claims it, so no glide game event follows; with a real elytra (the control)
  the question is asked and declined, the elytra wears by one and the game event is heard.
- Sleep/wake in an ordinary bed, intercept its occupation writes, and allow sleeping on a stone block
  without replacing it with a bed. Both callback directions and actual block state are checked.
- Use a native custom bed without vanilla OCCUPIED, preserving its setter, actor and own world state; then
  let Fabric handle that same bed, preventing the native setter from writing.
- Turn a sleeping player's direction: a north-facing bed reads south through Fabric's listener, and a stone
  sleeping spot (allowed by ALLOW_BED) gets east instead of none; nothing is asked before the player sleeps.
- Override the nearby-monster sleep check with an actual visible zombie. The game must return success and
  put the player to sleep, not merely deliver the event.

The positive run is strict and must have zero confirmed required findings. Every case but the native flight-tick
control fails with `forbric.fabricEntityAnchors=off`, `forbric.mixinRetarget=off` and `forbric.mixinStubRebind=off`
(Fabric's tick anchors natively on the glider-slot choice, which a real elytra reaches; the
carrier-stub rebind would move the boolean elytra check on its own) — the generic renamed-body retarget moves
the sleep redirects into NeoForge's `startSleepInBed` lambda on its own, before the entity anchors are asked, so
with the anchors alone off the nearby-monster case still passes. That deliberate negative experiment explicitly
uses continue, retains its confirmed missing-injector evidence, and has a failing inner command. Each phase binds sources, game,
runtime and mod bytes. Startup failures, crashes, timeouts or missing cases cannot satisfy the gate. A third run,
strict, turns off only `forbric.fabricElytraTickAnchor`: exactly the glider-less flight tick fails.

The occupancy adapter checks the original handler's instruction fingerprint before substituting its
handled-result contract. Unknown bodies and callback groups are not rewritten. Native operation still uses
the current world's block state and its native setter, including modded beds. The clear-all veto and
sleeping-direction adapters synthesize their own handler, which asks ALLOW_EARLY_REMOVE or MODIFY_SLEEPING_DIRECTION
itself (each only after the original handler matches its audited shape); the remaining adapters move only proven
annotation anchors.
