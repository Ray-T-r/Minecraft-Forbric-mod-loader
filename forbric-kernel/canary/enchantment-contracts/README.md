# M38 actual enchantment contracts

One NeoForge fixture registers a native item and a Fabric-override item, then subscribes to Fabric's real
ALLOW_ENCHANTING event. The pack contains the unmodified Fabric item module and its declared dependency
closure from Fabric API 0.155.2+26.2. An additional Fabric-only data mod supplies a language entry.

Sixteen real-server cases cross command, loot-function, enchantment-candidate and anvil paths with four decisions:
PASS uses the native item decision exactly once, DENY prevents the operation, ALLOW changes the result
without invoking the fallback, and a Fabric item override remains callable. The event count, context,
native counters and actual resulting enchantment/candidate list are checked. No event is manually posted.

The fixture also checks the Fabric-only language entry, a vanilla translation, and the Minecraft mod
container's actual version.json resource. The language adapter merges Fabric strings before native map
capture while retaining the mutable map and separate styled-component map. It does not replace those maps
with Fabric's immutable return value.

The enchanting table's case is the one where NeoForge replaced vanilla's filter lambda
(`holder.value().isPrimaryItem(stack) || isBook`) with a method reference to its own `isPrimaryItemFor`, whose
inherited implementation answers true for a book first. Fabric's redirect inside the lambda is moved by
`CarrierMethodReferences`, which knows no mod: the method reference is pointed at a method taking the lambda's own
parameters (isBook read from the merged method's local), and the native lambda runs there with Fabric's handler at
the call, so the book disjunct is kept as natively.

The positive run is strict with zero confirmed necessary losses. Turning off the item contract adapter and the
two general mechanisms that move the enchanting redirects (CarrierDecisions, CarrierMethodReferences)
makes the same sixteen cases fail; this deliberate control uses explicit continue, retains its missing
injection findings, and fails its inner acceptance command. Sources, artifacts and mod hashes are bound to
each phase. Results live under build/verification/m38-entity (historical directory name).
