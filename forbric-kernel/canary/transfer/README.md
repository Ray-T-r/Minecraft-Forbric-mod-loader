# M33 world-transfer fixture

Run `bash forbric-kernel/run/gate-m33-transfer.sh` after building/staging the kernel inputs. It builds three
separate mods, each with only its own ecosystem metadata and registration entrypoint. Common fixture classes
are shipped only by the Fabric jar. The selected Fabric API jar defaults to the same 0.155.2+26.2 fixture as
the kernel's runtime build; `M33_FABRIC_API` can point at an explicitly prepared equivalent fixture.
The upstream NeoForge-patched game is the compile view (`M33_COMPILE_GAME`); the merged game is the runtime
input. This preserves normal upstream compilation of a custom Block subclass without hiding the merged
interfaces' conflicting defaults behind canary-only overrides. Both game inputs are hashed.

The gate uses only `run/server-transfer-m33/world`. A nonce in `.m33-owned`, explicit JVM parameters and the
server's actual world path must all agree before the canary changes blocks. Without this proof it prints
`DISARMED` and does nothing. An existing unowned world is never removed.

Each mod registers its own block and block-entity type. Native provider registration uses Fabric's block API
lookup, NeoForge's `RegisterCapabilitiesEvent`, or Forge's attachment event with exact standard Forge handlers.
Transfers query `ItemStorage/FluidStorage.SIDED`, `ServerLevel.getCapability`, and
`ICapabilityProvider.getCapability`; this fixture never calls a kernel transfer adapter directly.

The positive phase checks all six directed item/fluid routes with BOTH NORTH and null (12 operations per
resource family), face preservation, SOUTH refusal, native
provider priority (including competing providers), and cached views after replacing a real block entity.
Container-shaped machines check ownership precedence: a Forge crate and a NeoForge crate are plain Containers
whose owners expose a separate handler on NORTH/null only, and a NeoForge cabinet extends BaseContainerBlockEntity.
Fabric API's generic Container fallback and the merged Forge override's generic wrapper must not answer for
them: a refused face stays refused for NeoForge and Forge consumers, and every foreign consumer on the permitted
face reaches the owner's handler (and the cabinet's fluid handler) without one write into the Container slots.
Three more BaseContainerBlockEntity machines leave getCapability alone, the shape of most mod chests, so Forge
answers each with its own InvWrapper over the whole Container. A NeoForge consumer of the Forge bin gets
NeoForge's own Container wrapper on every face: an aborted insert leaves the bin empty and committed moves land in
its slots. It gets nothing for the Forge kiln, whose Container declares its own setItem. A Forge consumer of the
Fabric bin (no Fabric storage of its own) keeps the native InvWrapper. The gate also requires the kiln's
CONTAINER_WRITES_NOT_VANILLA finding and forbids any "InvWrapper is not rollback-safe" finding. The
three primary inventories retain 60 component-tagged cobblestone and 48,617 Fabric fluid units, including a
17-unit remainder. A Neo query also requests 201 mB from the real Fabric store holding 200 mB plus 17 units,
forcing an actual fractional return, nested rollback/retry, then outer rollback. Temporary priority/invalidation
fixtures are outside this conservation census. Legacy
Forge simulate/execute calls are checked as separate calls; they are not claimed to form a transaction.

A second server loads the same world and verifies `loadAdditional` really ran, item components and per-machine
quantities survived, and world queries still work. A separate Forge machine at (112,80,16) occupies its own
chunk. The gate saves its seeded baseline and asserts the chunk is clean, then performs only public Fabric
queries: aborted item/fluid writes must leave it clean; committed writes must mark it dirty. There is no
explicit setChanged after the baseline save. The next boot checks that the new amounts (7 items, 9 mB)
and components persisted, including reads through the native Forge providers.

Finally, the gate creates another owned test world with the bridge disabled. The same positive assertions
must fail at a foreign public lookup; its acceptance command and recorded probe result must be red. Each run
uses `run/compat/evidence.py` to retain source, kernel, carrier, Fabric API and full mod hashes, the exact
command, logs and input-drift verdict under `build/verification/m33-transfer/`.

Replacement is checked without calling invalidateCapabilities by hand: removing the block entity must invalidate
every cached view by itself. After the save, three more machines stand in a chunk at (4096, 4096) that nothing
else touches. Every foreign view of them is cached, and the probe keeps ticking until the server has unloaded
that chunk on its own. While it is unloaded and after it is reloaded, every cached view must move nothing and
every cached Forge LazyOptional must be empty; the reloaded machines keep their contents, and fresh public
queries reach them. The phase result is written only when this finishes (at most 1200 ticks).

These assertions supplement the lower-level transaction/alias canary; they do not replace it. No Gradle or
game run is implied merely by creating these sources.

# M40 energy fixture

Run `bash forbric-kernel/run/gate-m40-energy.sh`. It builds the same three jars plus three energy cells
(`TRANSFER_CANARY_ENERGY=1`) into `build/energy-canary/`, never into `run/canary/`, so it cannot rewrite what M33 reads.
The shared `EnergyMachines` and `EnergyWorldProbe` ship in the M33 Fabric jar and name no Team Reborn Energy type.
`forbricenergyfabric` (a Reborn `SimpleEnergyStorage` on `EnergyStorage.SIDED`) is the only fixture that depends on
Reborn; `forbricenergyforge` attaches Forge's exact `EnergyStorage` on `ForgeCapabilities.ENERGY`, and
`forbricenergyneo` registers a `SimpleEnergyHandler` on `Capabilities.Energy.BLOCK` and runs the probe; its `bare`
block entity gets no energy capability at all. `forbricenergyfabric` also acts as a Fabric energy addon: on request it
registers Reborn's `EnergyStorage.SIDED` for another mod's block (`registerForBlocks`, stores kept in memory). Every
provider answers NORTH and null only. The Reborn jar is copied from `M40_REBORN_ENERGY` (default
`forbric-kernel/run/energy-api/energy-5.0.0.jar` beside the staged tree); the gate fails if it is missing.

The probe uses only public lookups. `prepare` seeds 20,000 E in each primary cell and checks: every consumer sees
every cell on NORTH/null with the face passed through, and SOUTH refused; each consumer's own native store wins where
another ecosystem could be bridged, and the owner answers first among foreign stores; a custom Forge
`IEnergyStorage` gets no write bridge (reported once for its class) while a subclass of `EnergyStorage` that adds only
its own state is bridged; each store's capacity/maxInsert/maxExtract applies; twelve routes (six directed pairs x
NORTH/null, 1,000 E each, the consumer's own move helper) keep the total at 60,000; a committed nested move under an
aborted root restores every cell on both engines; replacing a block entity makes every cached view move nothing,
including the NeoForge handler and Forge LazyOptional of a Reborn cell (the Reborn half's own path), and fresh
queries then reach the new cell; a bridged write into a Forge cell in a clean chunk stays clean on abort and dirties
it exactly once per root commit; the Fabric addon's explicit Reborn provider on the NeoForge `bare` block (nothing
answers there before it registers) is reached by NeoForge and Forge consumers on NORTH and null with the face passed
through, SOUTH refused, and moves land in the addon's store; a Forge battery seeded through Forge's own
`deserializeNBT` with 1,500 E over its 1,000 E capacity moves nothing on bridged insertion (single operations and each
consumer's own move helper) and keeps its 1,500 E; a 5,000,000,000 E Reborn cell reads as Integer.MAX_VALUE through
int APIs and moving all of it into an int cell moves exactly 2,147,483,647 and keeps the rest. `reload` checks every
amount after a real save, runs the routes again, and checks the overfull battery again after the restart before
draining it back into its bounds. `noreborn` removes Reborn and the Fabric cell: Forge <-> NeoForge still work (4
routes, 40,000 E), the overfull battery check runs for NeoForge, and the JVM's own class-load log must contain no
`team.reborn` class and no Reborn half of the bridge. `negative` turns the bridge off and must fail at a foreign
lookup. Evidence: `build/verification/m40-energy/`.
