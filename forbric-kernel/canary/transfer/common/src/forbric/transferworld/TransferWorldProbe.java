package forbric.transferworld;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Actual public world queries only. No call to a kernel transfer adapter or bridge registration helper.
 *
 * <p>Two families since Stage 3 of dropping MinecraftForge: Forbric refuses the MinecraftForge machine mod
 * (forbrictransferforge), and only a MinecraftForge mod asks MinecraftForge's capabilities, so every route, provider
 * and consumer on MinecraftForge's side went with it. Fabric and NeoForge keep every route between them, and the two
 * checks that stood on a Forge machine (replacement invalidation, the clean-chunk dirty probe) now stand on a NeoForge
 * and a Fabric one. The MinecraftForge carrier still runs: its generic InvWrapper is still composed into every
 * BaseContainerBlockEntity, and the bridge's Forge leg still reads it, so a NeoForge consumer of the NeoForge cabinet's
 * refused face must still get nothing.
 */
public final class TransferWorldProbe {
	private static final List<String> FAMILIES = List.of(Machines.FABRIC, Machines.NEO);
	private static final Map<String, BlockPos> POSITIONS = new LinkedHashMap<>();
	static { for (int i = 0; i < FAMILIES.size(); i++) POSITIONS.put(FAMILIES.get(i), new BlockPos(16 + i * 2, 80, 16)); }
	private static final long FLUID_TOTAL = 2 * 200 * 81L + 17;
	private static final BlockPos DIRTY_PROBE = new BlockPos(112, 80, 16);
	private static final BlockPos NEO_CRATE = new BlockPos(30, 80, 16), NEO_CABINET = new BlockPos(32, 80, 16);
	// A chunk nothing else touches, far from spawn and from every other probe position.
	private static final BlockPos UNLOAD = new BlockPos(4096, 80, 4096);
	private static final int UNLOAD_TICKS = 1200;
	private static int itemRoutes, fluidRoutes;
	private static volatile Pending pending;
	private record Pending(MinecraftServer server, Path output, String phase, String token, long items, long fluids, ChunkUnload unload) { }
	private TransferWorldProbe() { }

	public static void run(MinecraftServer server) {
		String phase = System.getProperty("forbric.transferCanaryPhase", "");
		String token = System.getProperty("forbric.transferCanaryToken", "");
		Path output;
		try {
			Path root = Path.of(System.getProperty("forbric.transferCanaryRoot", ".")).toAbsolutePath().normalize();
			Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			if (token.isBlank() || !List.of("prepare", "reload", "negative").contains(phase)
					|| !world.equals(root.resolve("world")) || !Files.readString(root.resolve(".m33-owned")).trim().equals(token)) {
				System.out.println("[M33Transfer] DISARMED: this is not the gate-owned world"); return;
			}
			output = Path.of(System.getProperty("forbric.transferCanaryOutput"));
		} catch (Exception unarmed) { System.out.println("[M33Transfer] DISARMED: missing gate ownership proof"); return; }
		boolean pass = false; String detail = ""; long items = -1, fluids = -1; ChunkUnload unload = null;
		try {
			ServerLevel level = server.overworld(); yes(server.isSameThread(), "probe is not on the server thread");
			for (BlockPos pos : POSITIONS.values()) level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
			if (phase.equals("reload")) {
				for (String family : FAMILIES) yes(machine(level, POSITIONS.get(family)).loadedFromDisk, "block entity was not deserialized: " + family);
				checkPrimaryState(level);
				checkDirtyProbeReload(level);
				checkQueriesAndFaces(level);
				System.out.println("[M33Transfer] PASS save/reload: two primary inventories and components retained");
			} else {
				for (String family : FAMILIES) {
					Machines.Machine be = place(level, POSITIONS.get(family), family);
					be.seed(tagged(20), 200 * 81L + (family.equals(Machines.FABRIC) ? 17 : 0));
				}
				checkQueriesAndFaces(level); checkNativePriority(level); checkOwnerPrecedence(level);
				for (String consumer : FAMILIES) for (String destination : FAMILIES) {
					if (consumer.equals(destination)) continue;
					for (Direction face : new Direction[] {Direction.NORTH, null}) {
						moveItems(level, consumer, destination, face); itemRoutes++;
						moveFluids(level, consumer, destination, face); fluidRoutes++;
						System.out.println("[M33Transfer] PASS route " + consumer + " -> " + destination + " face=" + face + " items+fluids");
					}
				}
				checkPrimaryState(level); checkQuantization(level); checkInvalidation(level);
				// A separate chunk isolates the bridge notification from native providers and all block placement.
				checkDirtyCommit(level, server);
				server.saveEverything(false, true, true);
				System.out.println("[M33Transfer] PASS save: both directed public-query routes committed, 17 Fabric fluid units retained");
				// Last, because it spans server ticks. Its chunk is outside the primary census taken below.
				unload = new ChunkUnload(level);
			}
			items = itemTotal(level); fluids = fluidTotal(level); pass = true;
		} catch (Throwable failure) {
			detail = failure.toString(); System.out.println("[M33Transfer] FAIL phase=" + phase + " " + failure); failure.printStackTrace();
		}
		if (pass && unload != null) { pending = new Pending(server, output, phase, token, items, fluids, unload); return; }
		finish(server, output, phase, token, pass, items, fluids, detail);
	}
	/** Every server tick after run(): drives a started chunk unload to its end, then finishes the phase. */
	public static void tick(MinecraftServer server) {
		Pending current = pending;
		if (current == null || current.server() != server) return;
		boolean pass = false; String detail = "";
		try {
			if (!current.unload().advance()) return;
			pass = true;
		} catch (Throwable failure) {
			detail = failure.toString(); System.out.println("[M33Transfer] FAIL phase=" + current.phase() + " " + failure); failure.printStackTrace();
		}
		pending = null;
		finish(server, current.output(), current.phase(), current.token(), pass, current.items(), current.fluids(), detail);
	}
	private static void finish(MinecraftServer server, Path output, String phase, String token, boolean pass, long items, long fluids, String detail) {
		try {
			Files.createDirectories(output.toAbsolutePath().getParent());
			Files.writeString(output, "{\"schemaVersion\":1,\"scope\":\"two-primary-machines\",\"phase\":" + json(phase) + ",\"runToken\":" + json(token)
					+ ",\"pass\":" + pass + ",\"itemRoutes\":" + itemRoutes + ",\"fluidRoutes\":" + fluidRoutes
					+ ",\"items\":" + items + ",\"fluidFabricUnits\":" + fluids + ",\"detail\":" + json(detail) + "}\n");
		} catch (Exception writeFailure) { System.out.println("[M33Transfer] FAIL result-write " + writeFailure); }
		if (pass) System.out.println("[M33Transfer] PASS phase=" + phase + " items=" + items + " fluidFabricUnits=" + fluids);
		server.halt(false);
	}

	/**
	 * A real chunk unload and reload, not a manual invalidation. Two machines stand in a chunk nothing else touches,
	 * every foreign view of them is cached, and the server is left to unload the chunk by itself. While it is gone and
	 * after it is back, every cached view must move nothing; the reloaded block entities keep their contents, and fresh
	 * public queries reach them.
	 */
	private static final class ChunkUnload {
		final ServerLevel level;
		final Map<String, Machines.Machine> old = new LinkedHashMap<>();
		final List<IntSupplier> writes = new ArrayList<>();
		int ticks;
		ChunkUnload(ServerLevel level) {
			this.level = level;
			for (String family : FAMILIES) { Machines.Machine be = place(level, unloadPos(family), family); be.seed(tagged(4), 4 * 81L); old.put(family, be); }
			for (String target : FAMILIES) for (String consumer : FAMILIES) if (!consumer.equals(target)) cache(consumer, unloadPos(target));
			equal(4, writes.size());
			System.out.println("[M33Transfer] cached every foreign view of the unload chunk; waiting for the server to unload it");
		}
		private void cache(String consumer, BlockPos pos) {
			switch (consumer) {
				case Machines.FABRIC -> {
					Storage<ItemVariant> items = ItemStorage.SIDED.find(level, pos, Direction.NORTH);
					Storage<FluidVariant> fluids = FluidStorage.SIDED.find(level, pos, Direction.NORTH);
					yes(items != null && fluids != null, "missing Fabric views before the unload at " + pos);
					writes.add(() -> { try (Transaction tx = Transaction.openOuter()) { long moved = items.insert(ItemVariant.of(tagged(1)), 1, tx); tx.commit(); return (int) moved; } });
					writes.add(() -> { try (Transaction tx = Transaction.openOuter()) { long moved = fluids.insert(FluidVariant.of(Fluids.WATER), 81, tx); tx.commit(); return (int) moved; } });
				}
				case Machines.NEO -> {
					ResourceHandler<ItemResource> items = level.getCapability(Capabilities.Item.BLOCK, pos, Direction.NORTH);
					ResourceHandler<FluidResource> fluids = level.getCapability(Capabilities.Fluid.BLOCK, pos, Direction.NORTH);
					yes(items != null && fluids != null, "missing NeoForge views before the unload at " + pos);
					writes.add(() -> { try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { int moved = items.insert(0, ItemResource.of(tagged(1)), 1, tx); tx.commit(); return moved; } });
					writes.add(() -> { try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { int moved = fluids.insert(0, FluidResource.of(Fluids.WATER), 1, tx); tx.commit(); return moved; } });
				}
				default -> throw new IllegalStateException(consumer);
			}
		}
		/** True once the scenario is complete; false while the chunk is still loaded. */
		boolean advance() {
			if (level.hasChunkAt(UNLOAD) || !old.values().stream().allMatch(Machines.Machine::isRemoved)) {
				yes(++ticks <= UNLOAD_TICKS, "the server did not unload the probe chunk within " + UNLOAD_TICKS + " ticks");
				return false;
			}
			refused("while unloaded");
			for (Machines.Machine be : old.values()) { equal(4, be.itemSnapshot().getCount()); equal(4 * 81L, be.fluidUnits()); }
			level.getChunk(UNLOAD.getX() >> 4, UNLOAD.getZ() >> 4);
			Map<String, Machines.Machine> reloaded = new LinkedHashMap<>();
			for (String family : FAMILIES) {
				Machines.Machine be = machine(level, unloadPos(family));
				yes(be != old.get(family) && be.loadedFromDisk, "the unload chunk's " + family + " machine was not reloaded from disk");
				equal(4, be.itemSnapshot().getCount()); equal(4 * 81L, be.fluidUnits());
				reloaded.put(family, be);
			}
			refused("after reload");
			for (String family : FAMILIES) { equal(4, reloaded.get(family).itemSnapshot().getCount()); equal(4 * 81L, reloaded.get(family).fluidUnits()); }
			// Fresh public queries bind to the reloaded block entities, one foreign consumer each.
			BlockPos fabric = unloadPos(Machines.FABRIC), neo = unloadPos(Machines.NEO);
			try (Transaction tx = Transaction.openOuter()) {
				equal(1, ItemStorage.SIDED.find(level, neo, Direction.NORTH).insert(ItemVariant.of(tagged(1)), 1, tx));
				equal(81, FluidStorage.SIDED.find(level, neo, Direction.NORTH).insert(FluidVariant.of(Fluids.WATER), 81, tx)); tx.commit();
			}
			try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
				equal(1, level.getCapability(Capabilities.Item.BLOCK, fabric, Direction.NORTH).insert(0, ItemResource.of(tagged(1)), 1, tx));
				equal(1, level.getCapability(Capabilities.Fluid.BLOCK, fabric, Direction.NORTH).insert(0, FluidResource.of(Fluids.WATER), 1, tx)); tx.commit();
			}
			for (String family : FAMILIES) { equal(5, reloaded.get(family).itemSnapshot().getCount()); equal(5 * 81L, reloaded.get(family).fluidUnits()); }
			for (Machines.Machine be : old.values()) { equal(4, be.itemSnapshot().getCount()); equal(4 * 81L, be.fluidUnits()); }
			System.out.println("[M33Transfer] PASS chunk unload: cached views refuse while unloaded and after reload; fresh queries reach the reloaded machines");
			return true;
		}
		private void refused(String when) {
			for (IntSupplier write : writes) equal(0, write.getAsInt());
		}
	}
	private static BlockPos unloadPos(String family) { return UNLOAD.offset(FAMILIES.indexOf(family) * 2, 0, 0); }

	/**
	 * A NeoForge machine in a chunk of its own, written only through public Fabric queries. Its handlers mark it changed
	 * when NeoForge's own root transaction commits, and nothing else does: an aborted Fabric write must leave the chunk
	 * clean, a committed one must dirty it, and the save that follows must carry the new amounts.
	 */
	private static void checkDirtyCommit(ServerLevel level, MinecraftServer server) {
		Machines.Machine be = place(level, DIRTY_PROBE, Machines.NEO); be.seed(tagged(1), 81);
		server.saveEverything(false, true, true);
		var chunk = level.getChunk(DIRTY_PROBE.getX() >> 4, DIRTY_PROBE.getZ() >> 4);
		yes(!chunk.isUnsaved(), "initial placement save did not clear the isolated NeoForge chunk dirty flag");
		Storage<ItemVariant> items = ItemStorage.SIDED.find(level, DIRTY_PROBE, Direction.NORTH);
		Storage<FluidVariant> fluids = FluidStorage.SIDED.find(level, DIRTY_PROBE, Direction.NORTH);
		yes(items != null && fluids != null, "missing foreign NeoForge providers for dirty notification");
		try (Transaction tx = Transaction.openOuter()) {
			equal(6, items.insert(ItemVariant.of(tagged(1)), 6, tx));
			equal(8 * 81L, fluids.insert(FluidVariant.of(Fluids.WATER), 8 * 81L, tx));
		}
		yes(!chunk.isUnsaved(), "aborted foreign writes dirtied the isolated NeoForge chunk");
		equal(1, be.itemSnapshot().getCount()); equal(81, be.fluidUnits());
		try (Transaction tx = Transaction.openOuter()) {
			equal(6, items.insert(ItemVariant.of(tagged(1)), 6, tx));
			equal(8 * 81L, fluids.insert(FluidVariant.of(Fluids.WATER), 8 * 81L, tx)); tx.commit();
		}
		yes(chunk.isUnsaved(), "committed foreign writes did not dirty the isolated NeoForge chunk");
		equal(7, be.itemSnapshot().getCount()); equal(9 * 81L, be.fluidUnits());
		// No direct setChanged after the first save. The following save/reload depends on the committed write.
		System.out.println("[M33Transfer] PASS clean chunk -> abort stays clean -> commit becomes dirty");
	}
	private static void checkDirtyProbeReload(ServerLevel level) {
		level.getChunk(DIRTY_PROBE.getX() >> 4, DIRTY_PROBE.getZ() >> 4);
		Machines.Machine be = machine(level, DIRTY_PROBE);
		yes(be.loadedFromDisk, "isolated NeoForge dirty probe was not deserialized");
		equal(7, be.itemSnapshot().getCount()); equal(9 * 81L, be.fluidUnits());
		yes(ItemStack.isSameItemSameComponents(tagged(1), be.itemSnapshot()), "dirty probe item components changed");
		ResourceHandler<ItemResource> ownItems = level.getCapability(Capabilities.Item.BLOCK, DIRTY_PROBE, Direction.NORTH);
		ResourceHandler<FluidResource> ownFluids = level.getCapability(Capabilities.Fluid.BLOCK, DIRTY_PROBE, Direction.NORTH);
		yes(ownItems == be.neoItems && ownFluids == be.neoFluids, "the reloaded dirty probe is not answered by its own NeoForge handlers");
		equal(7, ownItems.getAmountAsLong(0)); equal(9, ownFluids.getAmountAsLong(0));
		System.out.println("[M33Transfer] PASS committed foreign item/fluid writes persisted after a clean baseline save");
	}

	private static void checkQueriesAndFaces(ServerLevel level) {
		for (String target : FAMILIES) for (String consumer : FAMILIES) {
			BlockPos pos = POSITIONS.get(target);
			for (Direction face : new Direction[] {Direction.NORTH, null}) {
				query(level, pos, target, consumer, face, false, true); query(level, pos, target, consumer, face, true, true);
				Machines.Machine be = machine(level, pos);
				Direction received = target.equals(Machines.FABRIC) ? be.lastFabric : be.lastNeo;
				yes(received == face, "face changed on " + consumer + " -> " + target + ": " + face + " -> " + received);
			}
			query(level, pos, target, consumer, Direction.SOUTH, false, false); query(level, pos, target, consumer, Direction.SOUTH, true, false);
		}
		System.out.println("[M33Transfer] PASS the Fabric and NeoForge public APIs preserve NORTH/null and refuse SOUTH");
	}
	private static Object query(ServerLevel level, BlockPos pos, String target, String consumer, Direction face, boolean fluid, boolean present) {
		Object found = switch (consumer) {
			case Machines.FABRIC -> fluid ? FluidStorage.SIDED.find(level, pos, face) : ItemStorage.SIDED.find(level, pos, face);
			case Machines.NEO -> fluid ? level.getCapability(Capabilities.Fluid.BLOCK, pos, face) : level.getCapability(Capabilities.Item.BLOCK, pos, face);
			default -> throw new IllegalStateException(consumer);
		};
		yes(present == (found != null), (present ? "missing " : "unexpected ") + consumer + (fluid ? " fluid" : " item") + " provider for " + target + " face=" + face);
		return found;
	}
	private static void checkNativePriority(ServerLevel level) {
		Machines.Machine fabric = machine(level, POSITIONS.get(Machines.FABRIC)), neo = machine(level, POSITIONS.get(Machines.NEO));
		yes(ItemStorage.SIDED.find(level, fabric.getBlockPos(), Direction.NORTH) == fabric.fabricItems, "Fabric native provider was replaced");
		yes(FluidStorage.SIDED.find(level, fabric.getBlockPos(), Direction.NORTH) == fabric.fabricFluids, "Fabric native fluid provider was replaced");
		yes(level.getCapability(Capabilities.Item.BLOCK, neo.getBlockPos(), Direction.NORTH) == neo.neoItems, "Neo native provider was replaced");
		yes(level.getCapability(Capabilities.Fluid.BLOCK, neo.getBlockPos(), Direction.NORTH) == neo.neoFluids, "Neo native fluid provider was replaced");
		Machines.Machine competing = place(level, Machines.PRIORITY, Machines.FABRIC); competing.seed(tagged(5), 5 * 81); competing.neoItems.set(0, ItemResource.of(tagged(1)), 41); competing.neoFluids.set(0, FluidResource.of(Fluids.WATER), 41);
		yes(ItemStorage.SIDED.find(level, Machines.PRIORITY, Direction.NORTH) == competing.fabricItems, "fallback preempted native Fabric storage");
		yes(level.getCapability(Capabilities.Item.BLOCK, Machines.PRIORITY, Direction.NORTH) == competing.neoItems, "fallback preempted native Neo storage");
		yes(FluidStorage.SIDED.find(level, Machines.PRIORITY, Direction.NORTH) == competing.fabricFluids, "fallback preempted native Fabric fluid storage");
		yes(level.getCapability(Capabilities.Fluid.BLOCK, Machines.PRIORITY, Direction.NORTH) == competing.neoFluids, "fallback preempted native Neo fluid storage");
		System.out.println("[M33Transfer] PASS native providers take priority, including competing Fabric/Neo answers");
	}

	/**
	 * Container-shaped NeoForge machines. Fabric API's generic fallback wraps ANY Container as a writable store on every
	 * face and runs before any bridge; it must not answer for a NeoForge owner. On the permitted face a Fabric consumer
	 * reaches the owner's own handlers, never the Container slots (which count every write). The cabinet is a
	 * BaseContainerBlockEntity, into which the MinecraftForge carrier composes its generic InvWrapper over the whole
	 * Container; the bridge's Forge leg reads that wrapper on the face the owner refuses, and it is no owner's handler,
	 * so a NeoForge consumer gets nothing there.
	 */
	private static void checkOwnerPrecedence(ServerLevel level) {
		Machines.Crate neoCrate = place(level, NEO_CRATE, Machines.CRATE_BLOCKS.get(Machines.NEO), Machines.Crate.class);
		yes(level.getCapability(Capabilities.Item.BLOCK, NEO_CRATE, Direction.SOUTH) == null, "NeoForge got a generic Container bridge on its own crate's refused face");
		Storage<ItemVariant> fabricOnCrate = ItemStorage.SIDED.find(level, NEO_CRATE, Direction.NORTH);
		yes(fabricOnCrate != null, "missing Fabric view of the crate at " + NEO_CRATE);
		try (Transaction tx = Transaction.openOuter()) { equal(3, fabricOnCrate.insert(ItemVariant.of(tagged(1)), 3, tx)); tx.commit(); }
		equal(3, neoCrate.neoItems.getAmountAsLong(0));
		equal(0, neoCrate.containerWrites); yes(neoCrate.isEmpty(), "a foreign consumer wrote into the Container slots of " + neoCrate.getBlockPos());
		Machines.Cabinet cabinet = place(level, NEO_CABINET, Machines.CABINET_BLOCK.get(), Machines.Cabinet.class);
		yes(level.getCapability(Capabilities.Item.BLOCK, NEO_CABINET, Direction.SOUTH) == null,
				"NeoForge was handed the MinecraftForge carrier's whole-Container InvWrapper on the NeoForge cabinet's refused face");
		yes(level.getCapability(Capabilities.Fluid.BLOCK, NEO_CABINET, Direction.SOUTH) == null, "NeoForge got a fluid handler on the cabinet's refused face");
		Storage<ItemVariant> fabricOnCabinet = ItemStorage.SIDED.find(level, NEO_CABINET, Direction.NORTH);
		yes(fabricOnCabinet != null, "missing Fabric view of the NeoForge cabinet");
		try (Transaction tx = Transaction.openOuter()) { equal(1, fabricOnCabinet.insert(ItemVariant.of(tagged(1)), 1, tx)); tx.commit(); }
		Storage<FluidVariant> fabricFluidsOnCabinet = FluidStorage.SIDED.find(level, NEO_CABINET, Direction.NORTH);
		yes(fabricFluidsOnCabinet != null, "Fabric cannot see the NeoForge cabinet's own fluid handler");
		try (Transaction tx = Transaction.openOuter()) { equal(6 * 81L, fabricFluidsOnCabinet.insert(FluidVariant.of(Fluids.WATER), 6 * 81L, tx)); tx.commit(); }
		equal(1, cabinet.neoItems.getAmountAsLong(0)); equal(6, cabinet.neoFluids.getAmountAsLong(0));
		yes(cabinet.isEmpty(), "a foreign consumer wrote into the cabinet's Container slots instead of its owner's handler");
		System.out.println("[M33Transfer] PASS owner providers precede Fabric's generic Container view; the carrier's InvWrapper answers no NeoForge consumer");
	}

	private static void moveItems(ServerLevel level, String source, String destination, Direction face) {
		BlockPos from = POSITIONS.get(source), to = POSITIONS.get(destination); ItemStack stack = tagged(2);
		switch (source) {
			case Machines.FABRIC -> {
				Storage<ItemVariant> a = ItemStorage.SIDED.find(level, from, face), b = ItemStorage.SIDED.find(level, to, face);
				try (Transaction tx = Transaction.openOuter()) { equal(2, b.insert(ItemVariant.of(stack), 2, tx)); equal(2, a.extract(ItemVariant.of(stack), 2, tx)); tx.commit(); }
			}
			case Machines.NEO -> {
				var a = level.getCapability(Capabilities.Item.BLOCK, from, face); var b = level.getCapability(Capabilities.Item.BLOCK, to, face);
				try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { equal(2, b.insert(0, ItemResource.of(stack), 2, tx)); equal(2, a.extract(0, ItemResource.of(stack), 2, tx)); tx.commit(); }
			}
			default -> throw new IllegalStateException(source);
		}
		equal(40, itemTotal(level));
	}
	private static void moveFluids(ServerLevel level, String source, String destination, Direction face) {
		BlockPos from = POSITIONS.get(source), to = POSITIONS.get(destination);
		switch (source) {
			case Machines.FABRIC -> {
				Storage<FluidVariant> a = FluidStorage.SIDED.find(level, from, face), b = FluidStorage.SIDED.find(level, to, face);
				try (Transaction tx = Transaction.openOuter()) { long accepted = b.insert(FluidVariant.of(Fluids.WATER), 3 * 81 + 17, tx); equal(3 * 81, accepted); equal(accepted, a.extract(FluidVariant.of(Fluids.WATER), accepted, tx)); tx.commit(); }
			}
			case Machines.NEO -> {
				var a = level.getCapability(Capabilities.Fluid.BLOCK, from, face); var b = level.getCapability(Capabilities.Fluid.BLOCK, to, face);
				try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { equal(3, b.insert(0, FluidResource.of(Fluids.WATER), 3, tx)); equal(3, a.extract(0, FluidResource.of(Fluids.WATER), 3, tx)); tx.commit(); }
			}
			default -> throw new IllegalStateException(source);
		}
		equal(FLUID_TOTAL, fluidTotal(level));
	}

	/**
	 * Cached foreign views of a replaced block entity: a Fabric consumer's views of a NeoForge machine and a NeoForge
	 * consumer's views of a Fabric machine. No manual invalidateCapabilities: removing the block entity must invalidate
	 * every cached view by itself, and none of them may write the old block entity or reach the new one.
	 */
	private static void checkInvalidation(ServerLevel level) {
		BlockPos neoPos = new BlockPos(24, 80, 16), fabricPos = new BlockPos(26, 80, 16);
		Machines.Machine oldNeo = place(level, neoPos, Machines.NEO), oldFabric = place(level, fabricPos, Machines.FABRIC);
		Storage<ItemVariant> cachedFabric = ItemStorage.SIDED.find(level, neoPos, Direction.NORTH);
		Storage<FluidVariant> cachedFabricFluid = FluidStorage.SIDED.find(level, neoPos, Direction.NORTH);
		ResourceHandler<ItemResource> cachedNeo = level.getCapability(Capabilities.Item.BLOCK, fabricPos, Direction.NORTH);
		ResourceHandler<FluidResource> cachedNeoFluid = level.getCapability(Capabilities.Fluid.BLOCK, fabricPos, Direction.NORTH);
		yes(cachedFabric != null && cachedNeo != null && cachedFabricFluid != null && cachedNeoFluid != null, "missing foreign views before replacement");
		level.setBlockAndUpdate(neoPos, Blocks.AIR.defaultBlockState()); Machines.Machine newNeo = place(level, neoPos, Machines.NEO);
		level.setBlockAndUpdate(fabricPos, Blocks.AIR.defaultBlockState()); Machines.Machine newFabric = place(level, fabricPos, Machines.FABRIC);
		yes(oldNeo != newNeo && oldNeo.isRemoved() && oldFabric != newFabric && oldFabric.isRemoved(), "world did not replace the block entities");
		try (Transaction tx = Transaction.openOuter()) { equal(0, cachedFabric.insert(ItemVariant.of(tagged(1)), 1, tx)); equal(0, cachedFabricFluid.insert(FluidVariant.of(Fluids.WATER), 81, tx)); tx.commit(); }
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { equal(0, cachedNeo.insert(0, ItemResource.of(tagged(1)), 1, tx)); equal(0, cachedNeoFluid.insert(0, FluidResource.of(Fluids.WATER), 1, tx)); tx.commit(); }
		for (Machines.Machine be : List.of(oldNeo, newNeo, oldFabric, newFabric)) { equal(0, be.itemSnapshot().getCount()); equal(0, be.fluidUnits()); }
		System.out.println("[M33Transfer] PASS cached foreign views cannot write replaced block entities");
	}

	private static void checkQuantization(ServerLevel level) {
		BlockPos pos = POSITIONS.get(Machines.FABRIC);
		ResourceHandler<FluidResource> foreign = level.getCapability(Capabilities.Fluid.BLOCK, pos, Direction.NORTH);
		yes(foreign != null, "missing Neo fluid provider for Fabric quantization probe");
		try (var outer = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
			// The actual Fabric store initially returns 16,217 for a 16,281-unit request. The adapter must
			// abort that fractional trial, retry 16,200, and expose exactly 200 mB while retaining 17 units.
			equal(200, foreign.extract(0, FluidResource.of(Fluids.WATER), 201, outer));
			equal(17, machine(level, pos).fluidUnits());
			// Abort the outer scope too: the world inventory must recover its original complete amount.
		}
		checkPrimaryState(level);
		System.out.println("[M33Transfer] PASS world fluid quantization retains 17 units and outer abort restores the inventory");
	}

	private static void checkPrimaryState(ServerLevel level) {
		equal(40, itemTotal(level)); equal(FLUID_TOTAL, fluidTotal(level));
		for (String family : FAMILIES) {
			Machines.Machine be = machine(level, POSITIONS.get(family)); equal(20, be.itemSnapshot().getCount());
			yes(ItemStack.isSameItemSameComponents(tagged(1), be.itemSnapshot()), "item component/NBT changed on " + family);
			equal(200 * 81L + (family.equals(Machines.FABRIC) ? 17 : 0), be.fluidUnits());
		}
	}
	private static Machines.Machine place(ServerLevel level, BlockPos pos, String family) {
		return place(level, pos, Machines.BLOCKS.get(family), Machines.Machine.class);
	}
	private static <T> T place(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block, Class<T> type) {
		level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
		yes(level.setBlockAndUpdate(pos, block.defaultBlockState()), "could not place " + block + " at " + pos);
		return type.cast(java.util.Objects.requireNonNull(level.getBlockEntity(pos), "missing block entity at " + pos));
	}
	private static Machines.Machine machine(ServerLevel level, BlockPos pos) { return (Machines.Machine) java.util.Objects.requireNonNull(level.getBlockEntity(pos), "missing machine at " + pos); }
	private static long itemTotal(ServerLevel level) { return POSITIONS.values().stream().mapToLong(pos -> machine(level, pos).itemSnapshot().getCount()).sum(); }
	private static long fluidTotal(ServerLevel level) { return POSITIONS.values().stream().mapToLong(pos -> machine(level, pos).fluidUnits()).sum(); }
	private static ItemStack tagged(int amount) {
		ItemStack stack = new ItemStack(Items.COBBLESTONE, amount); CompoundTag data = new CompoundTag(), nested = new CompoundTag();
		nested.putInt("retained", 33); data.put("nested", nested); data.putString("probe", "m33-transfer"); stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); return stack;
	}
	private static void equal(long expected, long actual) { yes(expected == actual, expected + " != " + actual); }
	private static void yes(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
	private static String json(String text) { return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""; }
}
