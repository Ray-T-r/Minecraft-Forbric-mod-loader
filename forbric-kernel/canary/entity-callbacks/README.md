# M37 real entity callbacks

The fixture is one NeoForge mod subscribing to public Fabric events. The test pack loads the actual,
unmodified `fabric-api-base` and `fabric-entity-events-v1` modules from Fabric API 0.155.2+26.2; their original
Mixin configs, class tweakers and handlers run. This is a focused entity-module gate, not acceptance of the
whole Fabric API umbrella or the broad mixed client pack.

A fresh nonce-owned server world contains a real fake player, a visible zombie, ordinary beds and a registered
NeoForge custom bed with its own occupancy property and setter. Eleven cases execute through game methods:

- Force-add an effect: one before-add callback mutates its duration; the actual installed effect retains it.
- Remove effects: one before-remove callback sees the still-active effect; the game then removes it.
- Veto native permitted gliding, provide custom gliding without a native attribute, and cover the boolean
  equipment path. Actual flight flags and callback counts are checked.
- Sleep/wake in an ordinary bed, intercept its occupation writes, and allow sleeping on a stone block
  without replacing it with a bed. Both callback directions and actual block state are checked.
- Use a native custom bed without vanilla OCCUPIED, preserving its setter, actor and own world state; then
  let Fabric handle that same bed, preventing the native setter from writing.
- Override the nearby-monster sleep check with an actual visible zombie. The game must return success and
  put the player to sleep, not merely deliver the event.

The positive run is strict and must have zero confirmed required findings. The same eleven cases all fail
with `forbric.fabricEntityAnchors=off`; that deliberate negative experiment explicitly uses continue, retains
its confirmed missing-injector evidence, and has a failing inner command. Each phase binds sources, game,
runtime and mod bytes. Startup failures, crashes, timeouts or missing cases cannot satisfy the gate.

The occupancy adapter checks the original handler's instruction fingerprint before substituting its
handled-result contract. Unknown bodies and callback groups are not rewritten. Native operation still uses
the current world's block state and its native setter, including modded beds. The other adapters move only
proven annotation anchors; they do not synthesize or manually post the Fabric events.
