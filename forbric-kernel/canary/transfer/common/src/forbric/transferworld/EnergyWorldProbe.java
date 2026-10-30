package forbric.transferworld;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forbric.transferworld.EnergyMachines.Cell;
import forbric.transferworld.EnergyMachines.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * The energy world gate's probe. Public lookups only: Reborn's EnergyStorage.SIDED (through forbricenergyfabric),
 * NeoForge's Capabilities.Energy.BLOCK and Forge's ICapabilityProvider/ForgeCapabilities.ENERGY. It never calls a
 * kernel adapter. It acts only in the gate-owned world: the token in .energy-owned, the JVM parameters and the
 * server's world path must agree, or it prints DISARMED and does nothing.
 *
 * <p>Phases: prepare (Fabric and NeoForge cells, three consumers), reload (same world, second boot), negative (bridge off:
 * must go red at a foreign lookup) and noreborn (no Team Reborn Energy: the NeoForge cell and a second NeoForge cell,
 * the NeoForge and Forge consumers only).
 *
 * <p>Stage 3 of dropping MinecraftForge: forbricenergyforge is a MinecraftForge-only mod, which Forbric refuses and a
 * strict server stops on, so the gate no longer stages it. No Forge cell is placed: the cells are Fabric's (Reborn) and
 * NeoForge's. The Forge CONSUMER stays: it is this jar's own code asking MinecraftForge's public capability API, which
 * the merged base and the kernel's bridge still answer with no MinecraftForge mod aboard. It owns no cell, so it has no
 * native store to prefer; its routes go between two cells that are both foreign to it (Fabric <-> NeoForge, or, without
 * Reborn, the NeoForge cell <-> a second NeoForge cell), each a committed Forge extract from one and insert into the
 * other.
 */
public final class EnergyWorldProbe {
	private EnergyWorldProbe() { }
	private static final long SEED = 20_000, STEP = 1_000;
	private static int routes;

	/** NeoForge's public API. */
	static final Consumer NEO_CONSUMER = new Consumer() {
		public String family() { return EnergyMachines.NEO; }
		public Object find(ServerLevel level, BlockPos pos, Direction face) { return level.getCapability(Capabilities.Energy.BLOCK, pos, face); }
		public long amount(Object port) { return ((EnergyHandler) port).getAmountAsLong(); }
		public long capacity(Object port) { return ((EnergyHandler) port).getCapacityAsLong(); }
		public long insert(Object port, long max, boolean commit) {
			try (var tx = Transaction.openRoot()) { int moved = ((EnergyHandler) port).insert(Math.toIntExact(max), tx); if (commit) tx.commit(); return moved; }
		}
		public long extract(Object port, long max, boolean commit) {
			try (var tx = Transaction.openRoot()) { int moved = ((EnergyHandler) port).extract(Math.toIntExact(max), tx); if (commit) tx.commit(); return moved; }
		}
		public long move(Object from, Object to, long max) {
			try (var tx = Transaction.openRoot()) { int moved = EnergyHandlerUtil.move((EnergyHandler) from, (EnergyHandler) to, Math.toIntExact(max), tx); tx.commit(); return moved; }
		}
		public long nestedThenAbort(Object from, Object to, long max) {
			try (var root = Transaction.openRoot()) {
				long kept;
				try (var child = Transaction.open(root)) { kept = EnergyHandlerUtil.move((EnergyHandler) from, (EnergyHandler) to, Math.toIntExact(max), child); child.commit(); }
				try (var child = Transaction.open(root)) { EnergyHandlerUtil.move((EnergyHandler) from, (EnergyHandler) to, Math.toIntExact(max), child); }
				return kept;
			}
		}
	};
	/** MinecraftForge's public API: simulate/execute, no transactions. */
	static final Consumer FORGE_CONSUMER = new Consumer() {
		public String family() { return EnergyMachines.FORGE; }
		public Object find(ServerLevel level, BlockPos pos, Direction face) { return optional(level, pos, face).resolve().orElse(null); }
		public long amount(Object port) { return ((IEnergyStorage) port).getEnergyStored(); }
		public long capacity(Object port) { return ((IEnergyStorage) port).getMaxEnergyStored(); }
		public long insert(Object port, long max, boolean commit) { return ((IEnergyStorage) port).receiveEnergy(Math.toIntExact(max), !commit); }
		public long extract(Object port, long max, boolean commit) { return ((IEnergyStorage) port).extractEnergy(Math.toIntExact(max), !commit); }
		public long move(Object from, Object to, long max) {
			IEnergyStorage source = (IEnergyStorage) from, target = (IEnergyStorage) to;
			int offered = source.extractEnergy(Math.toIntExact(max), true), accepted = target.receiveEnergy(offered, true);
			int taken = source.extractEnergy(accepted, false), given = target.receiveEnergy(taken, false);
			if (given != taken) throw new IllegalStateException("Forge move lost energy: took " + taken + ", gave " + given);
			return given;
		}
		public long nestedThenAbort(Object from, Object to, long max) { return -1; }
	};
	static LazyOptional<IEnergyStorage> optional(ServerLevel level, BlockPos pos, Direction face) {
		return ((ICapabilityProvider) (Object) java.util.Objects.requireNonNull(level.getBlockEntity(pos), "missing block entity at " + pos))
				.getCapability(ForgeCapabilities.ENERGY, face);
	}

	public static void run(MinecraftServer server) {
		String phase = System.getProperty("forbric.energyCanaryPhase", "");
		String token = System.getProperty("forbric.energyCanaryToken", "");
		Path output;
		try {
			Path root = Path.of(System.getProperty("forbric.energyCanaryRoot", ".")).toAbsolutePath().normalize();
			Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			if (token.isBlank() || !List.of("prepare", "reload", "negative", "noreborn").contains(phase)
					|| !world.equals(root.resolve("world")) || !Files.readString(root.resolve(".energy-owned")).trim().equals(token)) {
				System.out.println("[M40Energy] DISARMED: this is not the gate-owned world"); return;
			}
			output = Path.of(System.getProperty("forbric.energyCanaryOutput"));
		} catch (Exception unarmed) { System.out.println("[M40Energy] DISARMED: missing gate ownership proof"); return; }
		boolean pass = false; String detail = ""; long energy = -1; List<Consumer> consumers = new ArrayList<>();
		Map<String, BlockPos> primaries = new LinkedHashMap<>(), cells = new LinkedHashMap<>();
		try {
			ServerLevel level = server.overworld(); yes(server.isSameThread(), "probe is not on the server thread");
			Consumer fabric = EnergyMachines.FABRIC_CONSUMER.get();
			if (phase.equals("noreborn")) yes(fabric == null, "the Reborn-dependent fixture loaded in a pack without Team Reborn Energy");
			else { yes(fabric != null, "the Reborn-dependent fixture did not register its Fabric consumer"); consumers.add(fabric); }
			consumers.add(NEO_CONSUMER); consumers.add(FORGE_CONSUMER);
			primaries.putAll(primaries(consumers));
			// The census: the primaries, and without Reborn a second NeoForge cell for the Forge consumer to move into.
			cells.putAll(primaries);
			if (phase.equals("noreborn")) cells.put(PEER, EnergyMachines.NEO_PEER);
			if (phase.equals("reload")) {
				for (BlockPos pos : cells.values()) yes(cell(level, pos).loadedFromDisk(), "cell was not deserialized at " + pos);
				checkPrimaryState(level, cells);
				yes(cell(level, EnergyMachines.DIRTY).loadedFromDisk(), "the dirty probe was not deserialized");
				equal(1357, cell(level, EnergyMachines.DIRTY).energy());
				equal(5_000_000_000L - Integer.MAX_VALUE, cell(level, EnergyMachines.RESERVOIR).energy());
				equal(Integer.MAX_VALUE, cell(level, EnergyMachines.SINK).energy());
				System.out.println("[M40Energy] PASS save/reload: primary, dirty-probe, reservoir and sink energy retained");
				checkQueriesAndFaces(level, consumers, primaries);
				checkRoutes(level, consumers, primaries, cells);
				checkPrimaryState(level, cells);
			} else {
				for (var entry : primaries.entrySet()) place(level, entry.getValue(), entry.getKey(), "cell").seed(SEED);
				if (cells.containsKey(PEER)) place(level, EnergyMachines.NEO_PEER, EnergyMachines.NEO, "cell").seed(SEED);
				checkQueriesAndFaces(level, consumers, primaries);
				checkNativePriority(level, consumers, primaries);
				checkLimits(level, consumers, primaries);
				checkRoutes(level, consumers, primaries, cells);
				checkNestedRollback(level, consumers, primaries);
				checkPrimaryState(level, cells);
				checkInvalidation(level, consumers);
				checkDirtyCommit(level, server, consumers);
				checkExplicitFabric(level, consumers);
				if (fabric != null) checkClamp(level, fabric);
				server.saveEverything(false, true, true);
				System.out.println("[M40Energy] PASS save: " + routes + " public-lookup routes committed");
			}
			energy = total(level, cells); pass = true;
		} catch (Throwable failure) {
			detail = failure.toString(); System.out.println("[M40Energy] FAIL phase=" + phase + " " + failure); failure.printStackTrace();
		}
		try {
			Files.createDirectories(output.toAbsolutePath().getParent());
			Files.writeString(output, "{\"schemaVersion\":1,\"phase\":" + json(phase) + ",\"runToken\":" + json(token) + ",\"pass\":" + pass
					+ ",\"families\":" + primaries.size() + ",\"cells\":" + cells.size() + ",\"consumers\":" + consumers.size() + ",\"routes\":" + routes
					+ ",\"energy\":" + energy + ",\"detail\":" + json(detail) + "}\n");
		} catch (Exception writeFailure) { System.out.println("[M40Energy] FAIL result-write " + writeFailure); }
		if (pass) System.out.println("[M40Energy] PASS phase=" + phase + " families=" + primaries.size() + " cells=" + cells.size() + " consumers=" + consumers.size()
				+ " routes=" + routes + " energy=" + energy);
		server.halt(false);
	}

	/** The census key of the second NeoForge cell (pack without Reborn only). */
	private static final String PEER = EnergyMachines.NEO + " peer";

	/** The cells, one per consumer whose ecosystem owns one: Fabric's (Reborn phases) and NeoForge's. The Forge consumer owns none. */
	private static Map<String, BlockPos> primaries(List<Consumer> consumers) {
		Map<String, BlockPos> out = new LinkedHashMap<>();
		for (Consumer consumer : consumers) switch (consumer.family()) {
			case EnergyMachines.FABRIC -> out.put(EnergyMachines.FABRIC, EnergyMachines.FABRIC_CELL);
			case EnergyMachines.NEO -> out.put(EnergyMachines.NEO, EnergyMachines.NEO_CELL);
			default -> { }
		}
		return out;
	}

	/** Every consumer, every primary, NORTH and null present with the face passed through unchanged; SOUTH refused. */
	private static void checkQueriesAndFaces(ServerLevel level, List<Consumer> consumers, Map<String, BlockPos> primaries) {
		for (var target : primaries.entrySet()) for (Consumer consumer : consumers) {
			for (Direction face : new Direction[] {Direction.NORTH, null}) {
				Cell cell = cell(level, target.getValue()); cell.face(Direction.UP);
				yes(consumer.find(level, target.getValue(), face) != null, "missing " + consumer.family() + " energy provider for " + target.getKey() + " face=" + face);
				yes(cell.lastFace() == face, "face changed on " + consumer.family() + " -> " + target.getKey() + ": " + face + " -> " + cell.lastFace());
			}
			yes(consumer.find(level, target.getValue(), Direction.SOUTH) == null, "unexpected " + consumer.family() + " energy provider for " + target.getKey() + " face=SOUTH");
		}
		System.out.println("[M40Energy] PASS all public energy lookups preserve NORTH/null and refuse SOUTH");
	}

	/**
	 * Each consumer gets its OWN ecosystem's store wherever one is registered, even where another could be bridged;
	 * among foreign stores the owner's answers first.
	 */
	private static void checkNativePriority(ServerLevel level, List<Consumer> consumers, Map<String, BlockPos> primaries) {
		for (Consumer consumer : consumers) {
			BlockPos pos = primaries.get(consumer.family());
			if (pos == null) continue; // the Forge consumer owns no cell
			Cell own = cell(level, pos);
			Object expected = consumer.family().equals(EnergyMachines.FABRIC) ? own.fabricStore() : own.neoStore();
			yes(consumer.find(level, pos, Direction.NORTH) == expected, consumer.family() + " native provider was replaced");
		}
		Consumer fabric = EnergyMachines.FABRIC_CONSUMER.get();
		if (fabric != null) {
			Cell cell = place(level, EnergyMachines.PRIORITY_FABRIC, EnergyMachines.FABRIC, "cell");
			cell.seed(111); cell.neoStore().set(222);
			yes(fabric.find(level, EnergyMachines.PRIORITY_FABRIC, Direction.NORTH) == cell.fabricStore(), "Fabric's own provider was replaced on a Fabric cell");
			yes(NEO_CONSUMER.find(level, EnergyMachines.PRIORITY_FABRIC, Direction.NORTH) == cell.neoStore(), "a bridge preempted NeoForge's own provider on a Fabric cell");
			// Forge has no provider there: the owner (Fabric) answers before NeoForge's extra store.
			equal(111, FORGE_CONSUMER.amount(FORGE_CONSUMER.find(level, EnergyMachines.PRIORITY_FABRIC, Direction.NORTH)));
		}
		System.out.println("[M40Energy] PASS native energy providers take priority and the owner answers first among foreign ones");
	}

	/** Every limit is the store's own; nothing here commits. */
	private static void checkLimits(ServerLevel level, List<Consumer> consumers, Map<String, BlockPos> primaries) {
		long before = total(level, primaries);
		for (Consumer consumer : consumers) for (var target : primaries.entrySet()) {
			Object port = consumer.find(level, target.getValue(), Direction.NORTH);
			equal(100_000, consumer.capacity(port)); equal(SEED, consumer.amount(port));
			for (int i = 0; i < 2; i++) {
				equal(5_000, consumer.insert(port, 6_000, false));
				equal(4_000, consumer.extract(port, 6_000, false));
			}
		}
		equal(before, total(level, primaries));
		checkPrimaryState(level, primaries);
		System.out.println("[M40Energy] PASS capacity, maxInsert and maxExtract are each store's own; simulations moved nothing");
	}

	/**
	 * Directed routes, each face, through the consumer's own move helper. A cell-owning consumer moves from its cell to
	 * the other family's (Fabric <-> NeoForge; none without Reborn). The Forge consumer owns no cell, so it moves between
	 * every two cells of the census, both foreign to it: Fabric <-> NeoForge, or without Reborn the NeoForge cell <-> the
	 * second NeoForge cell. Its move is a simulate then a committed extract and insert, so every Forge route commits
	 * through the Forge view of a NeoForge store at one end or both.
	 */
	private static void checkRoutes(ServerLevel level, List<Consumer> consumers, Map<String, BlockPos> primaries, Map<String, BlockPos> cells) {
		long total = total(level, cells);
		for (Consumer consumer : consumers) for (var source : cells.entrySet()) for (var destination : cells.entrySet()) {
			if (source.getKey().equals(destination.getKey())) continue;
			BlockPos own = primaries.get(consumer.family());
			if (own != null && (!source.getValue().equals(own) || !primaries.containsKey(destination.getKey()))) continue;
			for (Direction face : new Direction[] {Direction.NORTH, null}) {
				Object from = consumer.find(level, source.getValue(), face), to = consumer.find(level, destination.getValue(), face);
				yes(from != null && to != null, "missing " + consumer.family() + " energy provider for " + source.getKey() + " -> " + destination.getKey() + " face=" + face);
				long sourceBefore = cell(level, source.getValue()).energy(), targetBefore = cell(level, destination.getValue()).energy();
				equal(STEP, consumer.move(from, to, STEP));
				equal(sourceBefore - STEP, cell(level, source.getValue()).energy());
				equal(targetBefore + STEP, cell(level, destination.getValue()).energy());
				equal(total, total(level, cells)); routes++;
				System.out.println("[M40Energy] PASS route " + consumer.family() + ": " + source.getKey() + " -> " + destination.getKey() + " face=" + face);
			}
		}
	}

	/** Real nested scopes across engines: a committed child survives only until its root aborts. */
	private static void checkNestedRollback(ServerLevel level, List<Consumer> consumers, Map<String, BlockPos> primaries) {
		for (Consumer consumer : consumers) {
			if (!primaries.containsKey(consumer.family())) continue; // Forge: no cell, and no transactions
			for (var destination : primaries.entrySet()) {
				if (destination.getKey().equals(consumer.family())) continue;
				Object from = consumer.find(level, primaries.get(consumer.family()), Direction.NORTH), to = consumer.find(level, destination.getValue(), Direction.NORTH);
				equal(700, consumer.nestedThenAbort(from, to, 700));
				checkPrimaryState(level, primaries);
			}
		}
		System.out.println("[M40Energy] PASS nested commit then root abort restored every cell on both transaction engines");
	}

	/** Removing the block entity invalidates every cached foreign view by itself; no manual invalidation. */
	private static void checkInvalidation(ServerLevel level, List<Consumer> consumers) {
		Cell neoCell = place(level, EnergyMachines.INVALIDATE_NEO, EnergyMachines.NEO, "cell");
		List<Object[]> cached = new ArrayList<>();
		for (Consumer consumer : consumers) if (!consumer.family().equals(EnergyMachines.NEO)) {
			Object port = consumer.find(level, EnergyMachines.INVALIDATE_NEO, Direction.NORTH);
			yes(port != null, "missing " + consumer.family() + " energy provider for the NeoForge cell before replacement");
			cached.add(new Object[] {consumer, port});
		}
		LazyOptional<IEnergyStorage> optional = optional(level, EnergyMachines.INVALIDATE_NEO, Direction.NORTH);
		IEnergyStorage facade = optional.resolve().orElseThrow();
		// A Reborn cell's foreign views are the Reborn half's own (its live store), not the ones above: NeoForge's cached
		// handler and Forge's cached LazyOptional of it (Reborn phases only).
		boolean reborn = EnergyMachines.FABRIC_CONSUMER.get() != null;
		List<BlockPos> replaced = new ArrayList<>(List.of(EnergyMachines.INVALIDATE_NEO));
		Cell fabricCell = null;
		List<Object[]> cachedFabric = new ArrayList<>();
		LazyOptional<IEnergyStorage> fabricOptional = null;
		if (reborn) {
			fabricCell = place(level, EnergyMachines.INVALIDATE_FABRIC, EnergyMachines.FABRIC, "cell");
			for (Consumer consumer : List.of(NEO_CONSUMER, FORGE_CONSUMER)) {
				Object port = consumer.find(level, EnergyMachines.INVALIDATE_FABRIC, Direction.NORTH);
				yes(port != null, "missing " + consumer.family() + " energy provider for the Fabric cell before replacement");
				cachedFabric.add(new Object[] {consumer, port});
			}
			fabricOptional = optional(level, EnergyMachines.INVALIDATE_FABRIC, Direction.NORTH);
			yes(fabricOptional.isPresent(), "missing Forge LazyOptional for the Fabric cell before replacement");
			replaced.add(EnergyMachines.INVALIDATE_FABRIC);
		}
		for (BlockPos pos : replaced) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
		Cell neoReplacement = place(level, EnergyMachines.INVALIDATE_NEO, EnergyMachines.NEO, "cell");
		yes(neoReplacement != neoCell && ((BlockEntity) neoCell).isRemoved(), "world did not replace the NeoForge cell");
		for (Object[] entry : cached) equal(0, ((Consumer) entry[0]).insert(entry[1], 10, true));
		yes(!optional.isPresent(), "the cached Forge LazyOptional survived removal");
		equal(0, facade.receiveEnergy(10, false));
		for (Cell cell : List.of(neoCell, neoReplacement)) equal(0, cell.energy());
		System.out.println("[M40Energy] PASS cached foreign energy views and Forge LazyOptional cannot write replaced block entities");
		if (reborn) {
			Cell fabricReplacement = place(level, EnergyMachines.INVALIDATE_FABRIC, EnergyMachines.FABRIC, "cell");
			yes(fabricReplacement != fabricCell && ((BlockEntity) fabricCell).isRemoved(), "world did not replace the Fabric cell");
			for (Object[] entry : cachedFabric) equal(0, ((Consumer) entry[0]).insert(entry[1], 10, true));
			yes(!fabricOptional.isPresent(), "the cached Forge LazyOptional of a Reborn cell survived removal");
			equal(0, fabricCell.energy()); equal(0, fabricReplacement.energy());
			// Asked again, each consumer reaches the new cell.
			for (Consumer consumer : List.of(NEO_CONSUMER, FORGE_CONSUMER)) {
				long before = fabricReplacement.energy();
				equal(10, consumer.insert(consumer.find(level, EnergyMachines.INVALIDATE_FABRIC, Direction.NORTH), 10, true));
				equal(before + 10, fabricReplacement.energy());
			}
			equal(0, fabricCell.energy());
			System.out.println("[M40Energy] PASS cached NeoForge and Forge views of a replaced Reborn cell move nothing; asked again they reach the new cell");
		}
	}

	/**
	 * A Fabric energy addon registers Reborn's SIDED provider for a NeoForge mod's block that has no energy of its own.
	 * That is Fabric's EXPLICIT provider for the block, the only Fabric answer a Forge or NeoForge owner admits: NeoForge
	 * and Forge consumers must reach it, with the face passed through (null included), and moves land in the addon's
	 * store. Before the addon registers, nothing answers for the block (Reborn phases only).
	 */
	private static void checkExplicitFabric(ServerLevel level, List<Consumer> consumers) {
		EnergyMachines.FabricAddon addon = EnergyMachines.FABRIC_ADDON.get();
		Consumer fabric = EnergyMachines.FABRIC_CONSUMER.get();
		if (addon == null || fabric == null) return;
		place(level, EnergyMachines.EXPLICIT, EnergyMachines.NEO, "bare");
		for (Consumer consumer : consumers) yes(consumer.find(level, EnergyMachines.EXPLICIT, Direction.NORTH) == null,
				consumer.family() + " found energy on a block nothing provides it for");
		addon.attach(java.util.Objects.requireNonNull(EnergyMachines.BLOCKS.get(EnergyMachines.NEO + ":bare"), "unregistered bare block"));
		yes(fabric.find(level, EnergyMachines.EXPLICIT, Direction.NORTH) == addon.store(EnergyMachines.EXPLICIT), "the addon's own provider was replaced for Fabric");
		for (Consumer consumer : List.of(NEO_CONSUMER, FORGE_CONSUMER)) {
			for (Direction face : new Direction[] {Direction.NORTH, null}) {
				addon.face(Direction.UP);
				yes(consumer.find(level, EnergyMachines.EXPLICIT, face) != null, "missing " + consumer.family() + " energy provider for the Fabric addon's store face=" + face);
				yes(addon.lastFace() == face, "face changed on " + consumer.family() + " -> the Fabric addon: " + face + " -> " + addon.lastFace());
			}
			yes(consumer.find(level, EnergyMachines.EXPLICIT, Direction.SOUTH) == null, "unexpected " + consumer.family() + " energy provider for the Fabric addon's store face=SOUTH");
		}
		Object neo = NEO_CONSUMER.find(level, EnergyMachines.EXPLICIT, Direction.NORTH), forge = FORGE_CONSUMER.find(level, EnergyMachines.EXPLICIT, null);
		equal(300, NEO_CONSUMER.insert(neo, 300, false)); equal(0, addon.energy(EnergyMachines.EXPLICIT));
		equal(300, NEO_CONSUMER.insert(neo, 300, true)); equal(300, addon.energy(EnergyMachines.EXPLICIT));
		equal(200, FORGE_CONSUMER.insert(forge, 200, true)); equal(500, addon.energy(EnergyMachines.EXPLICIT));
		equal(100, FORGE_CONSUMER.extract(forge, 100, true)); equal(400, addon.energy(EnergyMachines.EXPLICIT));
		equal(400, NEO_CONSUMER.amount(neo)); equal(100_000, FORGE_CONSUMER.capacity(forge));
		System.out.println("[M40Energy] PASS a Fabric addon's explicit Reborn provider on a NeoForge block reaches NeoForge and Forge consumers");
	}

	/**
	 * A bridged write dirties its block entity once per root commit, and an aborted one leaves the chunk clean. The cell
	 * is a Reborn one written through NeoForge's view (Reborn phases only): each NeoForge root must commit Reborn's
	 * transaction exactly once, or abort it; then one Forge execute, which is a root of its own.
	 */
	private static void checkDirtyCommit(ServerLevel level, MinecraftServer server, List<Consumer> consumers) {
		if (EnergyMachines.FABRIC_CONSUMER.get() == null) return;
		Cell cell = place(level, EnergyMachines.DIRTY, EnergyMachines.FABRIC, "cell"); cell.seed(1000);
		server.saveEverything(false, true, true);
		var chunk = level.getChunk(EnergyMachines.DIRTY.getX() >> 4, EnergyMachines.DIRTY.getZ() >> 4);
		yes(!chunk.isUnsaved(), "initial placement save did not clear the isolated Reborn chunk dirty flag");
		EnergyHandler port = level.getCapability(Capabilities.Energy.BLOCK, EnergyMachines.DIRTY, Direction.NORTH);
		yes(port != null, "missing NeoForge view of the isolated Reborn cell");
		cell.resetChanges();
		try (var root = Transaction.openRoot()) { equal(100, port.insert(100, root)); equal(1100, cell.energy()); }
		yes(!chunk.isUnsaved(), "an aborted bridged write dirtied the isolated Reborn chunk"); equal(0, cell.changes()); equal(1000, cell.energy());
		try (var root = Transaction.openRoot()) {
			equal(100, port.insert(100, root)); equal(200, port.insert(200, root));
			try (var child = Transaction.open(root)) { equal(50, port.insert(50, child)); child.commit(); }
			try (var child = Transaction.open(root)) { equal(25, port.extract(25, child)); }
			root.commit();
		}
		equal(1, cell.changes()); yes(chunk.isUnsaved(), "a committed bridged write did not dirty the isolated Reborn chunk"); equal(1350, cell.energy());
		cell.resetChanges();
		Object view = FORGE_CONSUMER.find(level, EnergyMachines.DIRTY, Direction.NORTH);
		equal(7, FORGE_CONSUMER.insert(view, 7, true)); equal(1, cell.changes()); equal(1357, cell.energy());
		// No direct setChanged after the baseline save: the reload phase depends on the commit's own notification.
		System.out.println("[M40Energy] PASS clean chunk -> abort stays clean -> one root commit dirties it once");
	}

	/**
	 * Reborn counts in long. Read through int APIs a 5,000,000,000 E store saturates; moved into an int store, exactly
	 * Integer.MAX_VALUE moves and the rest stays in the source.
	 */
	private static void checkClamp(ServerLevel level, Consumer fabric) {
		Cell reservoir = place(level, EnergyMachines.RESERVOIR, EnergyMachines.FABRIC, "reservoir"); reservoir.seed(5_000_000_000L);
		Cell sink = place(level, EnergyMachines.SINK, EnergyMachines.NEO, "sink");
		EnergyHandler neo = level.getCapability(Capabilities.Energy.BLOCK, EnergyMachines.RESERVOIR, Direction.NORTH);
		equal(5_000_000_000L, neo.getAmountAsLong()); equal(Integer.MAX_VALUE, neo.getAmountAsInt());
		IEnergyStorage forge = optional(level, EnergyMachines.RESERVOIR, Direction.NORTH).resolve().orElseThrow();
		equal(Integer.MAX_VALUE, forge.getEnergyStored()); equal(Integer.MAX_VALUE, forge.extractEnergy(Integer.MAX_VALUE, true));
		equal(Integer.MAX_VALUE, fabric.move(fabric.find(level, EnergyMachines.RESERVOIR, Direction.NORTH), fabric.find(level, EnergyMachines.SINK, Direction.NORTH), 5_000_000_000L));
		equal(5_000_000_000L - Integer.MAX_VALUE, reservoir.energy()); equal(Integer.MAX_VALUE, sink.energy());
		equal(5_000_000_000L, reservoir.energy() + sink.energy());
		System.out.println("[M40Energy] PASS long energy: int views saturate, a 5e9 move into an int store moves 2147483647 and keeps the rest");
	}

	private static void checkPrimaryState(ServerLevel level, Map<String, BlockPos> primaries) {
		for (var entry : primaries.entrySet()) equal(SEED, cell(level, entry.getValue()).energy());
	}
	private static long total(ServerLevel level, Map<String, BlockPos> primaries) {
		long sum = 0; for (BlockPos pos : primaries.values()) sum += cell(level, pos).energy(); return sum;
	}
	private static Cell place(ServerLevel level, BlockPos pos, String owner, String path) {
		level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
		var block = java.util.Objects.requireNonNull(EnergyMachines.BLOCKS.get(owner + ":" + path), "unregistered " + owner + ":" + path);
		yes(level.setBlockAndUpdate(pos, block.defaultBlockState()), "could not place " + block + " at " + pos);
		return cell(level, pos);
	}
	private static Cell cell(ServerLevel level, BlockPos pos) {
		level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
		return (Cell) java.util.Objects.requireNonNull(level.getBlockEntity(pos), "missing energy cell at " + pos);
	}
	private static void equal(long expected, long actual) { yes(expected == actual, expected + " != " + actual); }
	private static void yes(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
	private static String json(String text) { return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
}
