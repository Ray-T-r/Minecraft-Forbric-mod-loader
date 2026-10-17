# M37 real entity callbacks

The fixture is one NeoForge mod subscribing to public Fabric events. The test pack loads the actual,
unmodified `fabric-api-base` and `fabric-entity-events-v1` modules from Fabric API 0.155.2+26.2; their original
Mixin configs, class tweakers and handlers run. This is a focused entity-module gate, not acceptance of the
whole Fabric API umbrella or the broad mixed client pack.

A fresh nonce-owned server world contains a real fake player, a visible zombie, ordinary beds, a registered
NeoForge custom bed with its own occupancy property and setter, and a registered NeoForge custom bed that extends vanilla's
bed and inherits NeoForge's default setter. Nineteen cases execute through game methods:

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
- Use a native custom bed without vanilla OCCUPIED: its own setter runs, with its actor and its own world state, and
  Fabric's occupancy event is not asked for it, whether or not a listener would handle it. No native Fabric game contains
  such a bed, so there is no native behaviour to reproduce; asking the event there would mean writing fabric-api's handler
  for it. This is a deliberate, stated drop.
- Use a mod's own bed that inherits NeoForge's default setter (vanilla's OCCUPIED write): Fabric's event is asked as for
  vanilla's bed, and a listener that handles the write keeps it unoccupied.
- Turn a sleeping player's direction: a north-facing bed reads south through Fabric's listener, and a stone
  sleeping spot (allowed by ALLOW_BED) gets east instead of none; nothing is asked before the player sleeps.
- Override the nearby-monster sleep check with an actual visible zombie. The game must return success and
  put the player to sleep, not merely deliver the event.

The positive run is strict and must have zero confirmed required findings. Every case but the native flight-tick
control and the two NeoForge-only bed cases fails with `forbric.carrierDefaultChains=off`, `forbric.mixinRetarget=off` and `forbric.mixinStubRebind=off`
(Fabric's tick anchors natively on the glider-slot choice, which a real elytra reaches; the
carrier-stub rebind would move the boolean elytra check on its own) — the generic renamed-body retarget moves
the sleep redirects into NeoForge's `startSleepInBed` lambda on its own, before the entity anchors are asked, so
with the anchors alone off the nearby-monster case still passes. That deliberate negative experiment explicitly
uses continue, retains its confirmed missing-injector evidence, and has a failing inner command. Each phase binds sources, game,
runtime and mod bytes. Startup failures, crashes, timeouts or missing cases cannot satisfy the gate. A third run,
strict, turns off only `forbric.fabricElytraTickAnchor`: exactly the glider-less flight tick fails.

The occupancy redirect is moved by `CarrierDefaultChains`, which knows no mod: bound to the sleep start and the wake-up
lambda, it moves onto the state's `setBedOccupied` and runs NeoForge's inherited default with fabric-api's own handler, as
written, at the vanilla write; a state or block that overrides the setter keeps its own. The clear-all veto and
sleeping-direction adapters synthesize their own handler, which asks ALLOW_EARLY_REMOVE or MODIFY_SLEEPING_DIRECTION
itself (each only after the original handler matches its audited shape); the remaining adapters move only proven
annotation anchors.
