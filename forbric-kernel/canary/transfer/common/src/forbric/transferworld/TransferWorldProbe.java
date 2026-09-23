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
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

/** Actual public world queries only. No call to a kernel transfer adapter or bridge registration helper. */
public final class TransferWorldProbe {
	private static final List<String> FAMILIES = List.of(Machines.FABRIC, Machines.NEO, Machines.FORGE);
	private static final Map<String, BlockPos> POSITIONS = new LinkedHashMap<>();
	static { for (int i = 0; i < FAMILIES.size(); i++) POSITIONS.put(FAMILIES.get(i), new BlockPos(16 + i * 2, 80, 16)); }
	private static final long FLUID_TOTAL = 3 * 200 * 81L + 17;
	private static final BlockPos DIRTY_PROBE = new BlockPos(112, 80, 16);
	private static final BlockPos FORGE_CRATE = new BlockPos(28, 80, 16), NEO_CRATE = new BlockPos(30, 80, 16), NEO_CABINET = new BlockPos(32, 80, 16);
	private static final BlockPos FORGE_BIN = new BlockPos(34, 80, 16), FORGE_KILN = new BlockPos(36, 80, 16), FABRIC_BIN = new BlockPos(38, 80, 16);
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
				System.out.println("[M33Transfer] PASS save/reload: three primary inventories and components retained");
			} else {
				for (String family : FAMILIES) {
					Machines.Machine be = place(level, POSITIONS.get(family), family);
					be.seed(tagged(20), 200 * 81L + (family.equals(Machines.FABRIC) ? 17 : 0));
				}
				checkQueriesAndFaces(level); checkNativePriority(level); checkOwnerPrecedence(level); checkGenericContainerViews(level);
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
				System.out.println("[M33Transfer] PASS save: six public-query routes committed, 17 Fabric fluid units retained");
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
			Files.writeString(output, "{\"schemaVersion\":1,\"scope\":\"three-primary-machines\",\"phase\":" + json(phase) + ",\"runToken\":" + json(token)
					+ ",\"pass\":" + pass + ",\"itemRoutes\":" + itemRoutes + ",\"fluidRoutes\":" + fluidRoutes
					+ ",\"items\":" + items + ",\"fluidFabricUnits\":" + fluids + ",\"detail\":" + json(detail) + "}\n");
		} catch (Exception writeFailure) { System.out.println("[M33Transfer] FAIL result-write " + writeFailure); }
		if (pass) System.out.println("[M33Transfer] PASS phase=" + phase + " items=" + items + " fluidFabricUnits=" + fluids);
		server.halt(false);
	}

	/**
	 * A real chunk unload and reload, not a manual invalidation. Three machines stand in a chunk nothing else
	 * touches, every foreign view of them is cached, and the server is left to unload the chunk by itself. While it
	 * is gone and after it is back, every cached view must move nothing and every cached Forge LazyOptional must be
	 * empty; the reloaded block entities keep their contents, and fresh public queries reach them.
	 */
	private static final class ChunkUnload {
		final ServerLevel level;
		final Map<String, Machines.Machine> old = new LinkedHashMap<>();
		final List<LazyOptional<?>> optionals = new ArrayList<>();
		final List<IntSupplier> writes = new ArrayList<>();
		int ticks;
		ChunkUnload(ServerLevel level) {
			this.level = level;
			for (String family : FAMILIES) { Machines.Machine be = place(level, unloadPos(family), family); be.seed(tagged(4), 4 * 81L); old.put(family, be); }
			for (String target : FAMILIES) for (String consumer : FAMILIES) if (!consumer.equals(target)) cache(consumer, unloadPos(target));
			equal(12, writes.size()); equal(4, optionals.size());
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
				case Machines.FORGE -> {
					var itemOptional = forgeProvider(level, pos).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH);
					var fluidOptional = forgeProvider(level, pos).getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH);
					IItemHandler items = itemOptional.resolve().orElseThrow(); IFluidHandler fluids = fluidOptional.resolve().orElseThrow();
					optionals.add(itemOptional); optionals.add(fluidOptional);
					writes.add(() -> 1 - items.insertItem(0, tagged(1), false).getCount());
					writes.add(() -> fluids.fill(new FluidStack(Fluids.WATER, 1), IFluidHandler.FluidAction.EXECUTE));
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
			BlockPos fabric = unloadPos(Machines.FABRIC), neo = unloadPos(Machines.NEO), forge = unloadPos(Machines.FORGE);
			try (Transaction tx = Transaction.openOuter()) {
				equal(1, ItemStorage.SIDED.find(level, neo, Direction.NORTH).insert(ItemVariant.of(tagged(1)), 1, tx));
				equal(81, FluidStorage.SIDED.find(level, neo, Direction.NORTH).insert(FluidVariant.of(Fluids.WATER), 81, tx)); tx.commit();
			}
			try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
				equal(1, level.getCapability(Capabilities.Item.BLOCK, forge, Direction.NORTH).insert(0, ItemResource.of(tagged(1)), 1, tx));
				equal(1, level.getCapability(Capabilities.Fluid.BLOCK, forge, Direction.NORTH).insert(0, FluidResource.of(Fluids.WATER), 1, tx)); tx.commit();
			}
			equal(0, forgeProvider(level, fabric).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).resolve().orElseThrow().insertItem(0, tagged(1), false).getCount());
			equal(1, forgeProvider(level, fabric).getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH).resolve().orElseThrow()
					.fill(new FluidStack(Fluids.WATER, 1), IFluidHandler.FluidAction.EXECUTE));
			for (String family : FAMILIES) { equal(5, reloaded.get(family).itemSnapshot().getCount()); equal(5 * 81L, reloaded.get(family).fluidUnits()); }
			for (Machines.Machine be : old.values()) { equal(4, be.itemSnapshot().getCount()); equal(4 * 81L, be.fluidUnits()); }
			System.out.println("[M33Transfer] PASS chunk unload: cached views refuse while unloaded and after reload; fresh queries reach the reloaded machines");
			return true;
		}
		private void refused(String when) {
			for (LazyOptional<?> optional : optionals) yes(!optional.isPresent(), "a cached Forge LazyOptional is still present " + when);
			for (IntSupplier write : writes) equal(0, write.getAsInt());
		}
	}
	private static BlockPos unloadPos(String family) { return UNLOAD.offset(FAMILIES.indexOf(family) * 2, 0, 0); }

	private static void checkDirtyCommit(ServerLevel level, MinecraftServer server) {
		Machines.Machine be = place(level, DIRTY_PROBE, Machines.FORGE); be.seed(tagged(1), 81);
		server.saveEverything(false, true, true);
		var chunk = level.getChunk(DIRTY_PROBE.getX() >> 4, DIRTY_PROBE.getZ() >> 4);
		yes(!chunk.isUnsaved(), "initial placement save did not clear the isolated Forge chunk dirty flag");
		Storage<ItemVariant> items = ItemStorage.SIDED.find(level, DIRTY_PROBE, Direction.NORTH);
		Storage<FluidVariant> fluids = FluidStorage.SIDED.find(level, DIRTY_PROBE, Direction.NORTH);
		yes(items != null && fluids != null, "missing foreign Forge providers for dirty notification");
		try (Transaction tx = Transaction.openOuter()) {
			equal(6, items.insert(ItemVariant.of(tagged(1)), 6, tx));
			equal(8 * 81L, fluids.insert(FluidVariant.of(Fluids.WATER), 8 * 81L, tx));
		}
		yes(!chunk.isUnsaved(), "aborted foreign writes dirtied the isolated Forge chunk");
		equal(1, be.itemSnapshot().getCount()); equal(81, be.fluidUnits());
		try (Transaction tx = Transaction.openOuter()) {
			equal(6, items.insert(ItemVariant.of(tagged(1)), 6, tx));
			equal(8 * 81L, fluids.insert(FluidVariant.of(Fluids.WATER), 8 * 81L, tx)); tx.commit();
		}
		yes(chunk.isUnsaved(), "committed foreign writes did not dirty the isolated Forge chunk");
		equal(7, be.itemSnapshot().getCount()); equal(9 * 81L, be.fluidUnits());
		// No direct setChanged after the first save. The following save/reload depends on the bridge.
		System.out.println("[M33Transfer] PASS clean chunk -> abort stays clean -> commit becomes dirty");
	}
	private static void checkDirtyProbeReload(ServerLevel level) {
		level.getChunk(DIRTY_PROBE.getX() >> 4, DIRTY_PROBE.getZ() >> 4);
		Machines.Machine be = machine(level, DIRTY_PROBE);
		yes(be.loadedFromDisk, "isolated Forge dirty probe was not deserialized");
		equal(7, be.itemSnapshot().getCount()); equal(9 * 81L, be.fluidUnits());
		yes(ItemStack.isSameItemSameComponents(tagged(1), be.itemSnapshot()), "dirty probe item components changed");
		IItemHandler ownItems = forgeProvider(level, DIRTY_PROBE).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).resolve().orElseThrow();
		IFluidHandler ownFluids = forgeProvider(level, DIRTY_PROBE).getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH).resolve().orElseThrow();
		equal(7, ownItems.getStackInSlot(0).getCount()); equal(9, ownFluids.getFluidInTank(0).getAmount());
		System.out.println("[M33Transfer] PASS bridge dirty notification persisted item/fluid changes after a clean baseline save");
	}

	private static void checkQueriesAndFaces(ServerLevel level) {
		for (String target : FAMILIES) for (String consumer : FAMILIES) {
			BlockPos pos = POSITIONS.get(target);
			for (Direction face : new Direction[] {Direction.NORTH, null}) {
				query(level, pos, target, consumer, face, false, true); query(level, pos, target, consumer, face, true, true);
				Machines.Machine be = machine(level, pos);
				Direction received = switch (target) { case Machines.FABRIC -> be.lastFabric; case Machines.NEO -> be.lastNeo; default -> be.lastForge; };
				yes(received == face, "face changed on " + consumer + " -> " + target + ": " + face + " -> " + received);
			}
			query(level, pos, target, consumer, Direction.SOUTH, false, false); query(level, pos, target, consumer, Direction.SOUTH, true, false);
		}
		System.out.println("[M33Transfer] PASS all three public APIs preserve NORTH/null and refuse SOUTH");
	}
	private static Object query(ServerLevel level, BlockPos pos, String target, String consumer, Direction face, boolean fluid, boolean present) {
		Object found = switch (consumer) {
			case Machines.FABRIC -> fluid ? FluidStorage.SIDED.find(level, pos, face) : ItemStorage.SIDED.find(level, pos, face);
			case Machines.NEO -> fluid ? level.getCapability(Capabilities.Fluid.BLOCK, pos, face) : level.getCapability(Capabilities.Item.BLOCK, pos, face);
			case Machines.FORGE -> fluid ? forgeProvider(level, pos).getCapability(ForgeCapabilities.FLUID_HANDLER, face).resolve().orElse(null)
					: forgeProvider(level, pos).getCapability(ForgeCapabilities.ITEM_HANDLER, face).resolve().orElse(null);
			default -> throw new IllegalStateException(consumer);
		};
		yes(present == (found != null), (present ? "missing " : "unexpected ") + consumer + (fluid ? " fluid" : " item") + " provider for " + target + " face=" + face);
		return found;
	}
	private static void checkNativePriority(ServerLevel level) {
		Machines.Machine fabric = machine(level, POSITIONS.get(Machines.FABRIC)), neo = machine(level, POSITIONS.get(Machines.NEO)), forge = machine(level, POSITIONS.get(Machines.FORGE));
		yes(ItemStorage.SIDED.find(level, fabric.getBlockPos(), Direction.NORTH) == fabric.fabricItems, "Fabric native provider was replaced");
		yes(FluidStorage.SIDED.find(level, fabric.getBlockPos(), Direction.NORTH) == fabric.fabricFluids, "Fabric native fluid provider was replaced");
		yes(level.getCapability(Capabilities.Item.BLOCK, neo.getBlockPos(), Direction.NORTH) == neo.neoItems, "Neo native provider was replaced");
		yes(level.getCapability(Capabilities.Fluid.BLOCK, neo.getBlockPos(), Direction.NORTH) == neo.neoFluids, "Neo native fluid provider was replaced");
		yes(forgeProvider(level, forge.getBlockPos()).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).resolve().orElseThrow() == forge.forgeItems, "Forge native provider was replaced");
		yes(forgeProvider(level, forge.getBlockPos()).getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH).resolve().orElseThrow() == forge.forgeFluids, "Forge native fluid provider was replaced");
		Machines.Machine competing = place(level, Machines.PRIORITY, Machines.FABRIC); competing.seed(tagged(5), 5 * 81); competing.neoItems.set(0, ItemResource.of(tagged(1)), 41); competing.neoFluids.set(0, FluidResource.of(Fluids.WATER), 41);
		yes(ItemStorage.SIDED.find(level, Machines.PRIORITY, Direction.NORTH) == competing.fabricItems, "fallback preempted native Fabric storage");
		yes(level.getCapability(Capabilities.Item.BLOCK, Machines.PRIORITY, Direction.NORTH) == competing.neoItems, "fallback preempted native Neo storage");
		yes(FluidStorage.SIDED.find(level, Machines.PRIORITY, Direction.NORTH) == competing.fabricFluids, "fallback preempted native Fabric fluid storage");
		yes(level.getCapability(Capabilities.Fluid.BLOCK, Machines.PRIORITY, Direction.NORTH) == competing.neoFluids, "fallback preempted native Neo fluid storage");
		System.out.println("[M33Transfer] PASS native providers take priority, including competing Fabric/Neo answers");
	}

	/**
	 * Container-shaped machines. Fabric API's generic fallback wraps ANY Container as a writable store on every face
	 * and runs before any bridge; it must not answer for a Forge or NeoForge owner. A face the owner refuses stays
	 * refused for every foreign consumer, and on the permitted face every consumer reaches the owner's own handler,
	 * never the Container slots (which count every write).
	 */
	private static void checkOwnerPrecedence(ServerLevel level) {
		Machines.Crate forgeCrate = place(level, FORGE_CRATE, Machines.CRATE_BLOCKS.get(Machines.FORGE), Machines.Crate.class);
		Machines.Crate neoCrate = place(level, NEO_CRATE, Machines.CRATE_BLOCKS.get(Machines.NEO), Machines.Crate.class);
		yes(level.getCapability(Capabilities.Item.BLOCK, FORGE_CRATE, Direction.SOUTH) == null, "NeoForge got a generic Container bridge on the Forge crate's refused face");
		yes(level.getCapability(Capabilities.Item.BLOCK, NEO_CRATE, Direction.SOUTH) == null, "NeoForge got a generic Container bridge on its own crate's refused face");
		yes(forgeProvider(level, NEO_CRATE).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.SOUTH).resolve().isEmpty(),
				"Forge got a generic Container bridge on the NeoForge crate's refused face");
		for (BlockPos pos : List.of(FORGE_CRATE, NEO_CRATE)) {
			Storage<ItemVariant> fabric = ItemStorage.SIDED.find(level, pos, Direction.NORTH);
			yes(fabric != null, "missing Fabric view of the crate at " + pos);
			try (Transaction tx = Transaction.openOuter()) { equal(3, fabric.insert(ItemVariant.of(tagged(1)), 3, tx)); tx.commit(); }
		}
		ResourceHandler<ItemResource> neoOnForge = level.getCapability(Capabilities.Item.BLOCK, FORGE_CRATE, Direction.NORTH);
		yes(neoOnForge != null, "missing NeoForge view of the Forge crate");
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { equal(2, neoOnForge.insert(0, ItemResource.of(tagged(1)), 2, tx)); tx.commit(); }
		IItemHandler forgeOnNeo = forgeProvider(level, NEO_CRATE).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).resolve().orElseThrow();
		equal(0, forgeOnNeo.insertItem(0, tagged(2), false).getCount());
		equal(5, forgeCrate.forgeItems.getStackInSlot(0).getCount()); equal(5, neoCrate.neoItems.getAmountAsLong(0));
		for (Machines.Crate crate : List.of(forgeCrate, neoCrate)) {
			equal(0, crate.containerWrites); yes(crate.isEmpty(), "a foreign consumer wrote into the Container slots of " + crate.getBlockPos());
		}
		// BaseContainerBlockEntity: the merged Forge override's generic whole-Container wrapper is not the owner.
		Machines.Cabinet cabinet = place(level, NEO_CABINET, Machines.CABINET_BLOCK.get(), Machines.Cabinet.class);
		IItemHandler items = forgeProvider(level, NEO_CABINET).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).resolve().orElseThrow();
		equal(0, items.insertItem(0, tagged(4), false).getCount());
		IFluidHandler fluids = forgeProvider(level, NEO_CABINET).getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH).resolve().orElse(null);
		yes(fluids != null, "Forge cannot see the NeoForge cabinet's own fluid handler");
		equal(6, fluids.fill(new FluidStack(Fluids.WATER, 6), IFluidHandler.FluidAction.EXECUTE));
		yes(forgeProvider(level, NEO_CABINET).getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.SOUTH).resolve().isEmpty(),
				"Forge got a fluid handler on the cabinet's refused face");
		Storage<ItemVariant> fabricOnCabinet = ItemStorage.SIDED.find(level, NEO_CABINET, Direction.NORTH);
		try (Transaction tx = Transaction.openOuter()) { equal(1, fabricOnCabinet.insert(ItemVariant.of(tagged(1)), 1, tx)); tx.commit(); }
		equal(5, cabinet.neoItems.getAmountAsLong(0)); equal(6, cabinet.neoFluids.getAmountAsLong(0));
		yes(cabinet.isEmpty(), "a foreign consumer wrote into the cabinet's Container slots instead of its owner's handler");
		System.out.println("[M33Transfer] PASS owner providers precede Fabric's generic Container view and Forge's generic wrapper");
	}

	/**
	 * BaseContainerBlockEntity machines that leave getCapability alone, the shape of most mod chests. Forge answers
	 * every face with its own InvWrapper over the whole Container. For a Forge owner that is the owner's answer: a
	 * NeoForge consumer gets NeoForge's own wrapper of the same Container, which writes through the game's setItem,
	 * and an abort leaves it untouched. A kiln whose Container declares its own setItem is not offered to NeoForge at
	 * all. For a Fabric mod's bin with no storage of its own, a Forge consumer keeps that native InvWrapper rather
	 * than a bridge over Fabric's generic view.
	 */
	private static void checkGenericContainerViews(ServerLevel level) {
		Machines.Bin forgeBin = place(level, FORGE_BIN, Machines.BIN_BLOCKS.get(Machines.FORGE), Machines.Bin.class);
		Machines.Kiln kiln = place(level, FORGE_KILN, Machines.KILN_BLOCK.get(), Machines.Kiln.class);
		place(level, FABRIC_BIN, Machines.BIN_BLOCKS.get(Machines.FABRIC), Machines.Bin.class);
		for (Direction face : new Direction[] {Direction.NORTH, Direction.SOUTH, null}) {
			ResourceHandler<ItemResource> neo = level.getCapability(Capabilities.Item.BLOCK, FORGE_BIN, face);
			yes(neo != null, "NeoForge cannot reach the Forge bin's Container on face " + face);
			try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { equal(2, neo.insert(0, ItemResource.of(tagged(1)), 2, tx)); }
			yes(forgeBin.isEmpty(), "an aborted NeoForge insert stayed in the Forge bin");
		}
		ResourceHandler<ItemResource> neo = level.getCapability(Capabilities.Item.BLOCK, FORGE_BIN, Direction.NORTH);
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { equal(3, neo.insert(0, ItemResource.of(tagged(1)), 3, tx)); tx.commit(); }
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { equal(1, neo.extract(0, ItemResource.of(tagged(1)), 1, tx)); tx.commit(); }
		equal(2, forgeBin.getItem(0).getCount());
		IItemHandler forge = forgeProvider(level, FORGE_BIN).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).resolve().orElseThrow();
		yes(forge.getClass() == InvWrapper.class, "Forge's own view of the Forge bin changed to " + forge.getClass().getName());
		equal(2, forge.getStackInSlot(0).getCount());
		Storage<ItemVariant> fabric = ItemStorage.SIDED.find(level, FORGE_BIN, Direction.NORTH);
		yes(fabric != null, "Fabric cannot reach the Forge bin");
		try (Transaction tx = Transaction.openOuter()) { equal(1, fabric.insert(ItemVariant.of(tagged(1)), 1, tx)); tx.commit(); }
		equal(3, forgeBin.getItem(0).getCount());
		yes(level.getCapability(Capabilities.Item.BLOCK, FORGE_KILN, Direction.NORTH) == null, "NeoForge was given the kiln's Container, whose writes are its own");
		equal(0, kiln.restarts);
		IItemHandler onFabric = forgeProvider(level, FABRIC_BIN).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH).resolve().orElseThrow();
		yes(onFabric.getClass() == InvWrapper.class, "a Forge consumer of a Fabric mod's plain bin got " + onFabric.getClass().getName() + " instead of Forge's InvWrapper");
		System.out.println("[M33Transfer] PASS Forge's generic InvWrapper: NeoForge's own Container view for a Forge bin, none for a kiln, native for a Fabric bin");
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
			case Machines.FORGE -> {
				IItemHandler a = forgeProvider(level, from).getCapability(ForgeCapabilities.ITEM_HANDLER, face).resolve().orElseThrow();
				IItemHandler b = forgeProvider(level, to).getCapability(ForgeCapabilities.ITEM_HANDLER, face).resolve().orElseThrow();
				long total = itemTotal(level); equal(2, a.extractItem(0, 2, true).getCount()); equal(0, b.insertItem(0, stack, true).getCount()); equal(total, itemTotal(level));
				ItemStack extracted = a.extractItem(0, 2, false); equal(2, extracted.getCount()); equal(0, b.insertItem(0, extracted, false).getCount());
			}
		}
		equal(60, itemTotal(level));
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
			case Machines.FORGE -> {
				IFluidHandler a = forgeProvider(level, from).getCapability(ForgeCapabilities.FLUID_HANDLER, face).resolve().orElseThrow();
				IFluidHandler b = forgeProvider(level, to).getCapability(ForgeCapabilities.FLUID_HANDLER, face).resolve().orElseThrow();
				long total = fluidTotal(level); equal(3, a.drain(3, IFluidHandler.FluidAction.SIMULATE).getAmount()); equal(3, b.fill(new FluidStack(Fluids.WATER, 3), IFluidHandler.FluidAction.SIMULATE)); equal(total, fluidTotal(level));
				FluidStack extracted = a.drain(3, IFluidHandler.FluidAction.EXECUTE); equal(3, extracted.getAmount()); equal(3, b.fill(extracted, IFluidHandler.FluidAction.EXECUTE));
			}
		}
		equal(FLUID_TOTAL, fluidTotal(level));
	}

	private static void checkInvalidation(ServerLevel level) {
		BlockPos pos = new BlockPos(24, 80, 16); Machines.Machine old = place(level, pos, Machines.FORGE);
		Storage<ItemVariant> cachedFabric = ItemStorage.SIDED.find(level, pos, Direction.NORTH);
		ResourceHandler<ItemResource> cachedNeo = level.getCapability(Capabilities.Item.BLOCK, pos, Direction.NORTH);
		Storage<FluidVariant> cachedFabricFluid = FluidStorage.SIDED.find(level, pos, Direction.NORTH);
		ResourceHandler<FluidResource> cachedNeoFluid = level.getCapability(Capabilities.Fluid.BLOCK, pos, Direction.NORTH);
		yes(cachedFabric != null && cachedNeo != null && cachedFabricFluid != null && cachedNeoFluid != null, "missing foreign Forge views before replacement");
		// No manual invalidateCapabilities: removing the block entity must invalidate every cached view by itself.
		level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); Machines.Machine replacement = place(level, pos, Machines.FORGE);
		yes(old != replacement && old.isRemoved(), "world did not replace the block entity");
		try (Transaction tx = Transaction.openOuter()) { equal(0, cachedFabric.insert(ItemVariant.of(tagged(1)), 1, tx)); equal(0, cachedFabricFluid.insert(FluidVariant.of(Fluids.WATER), 81, tx)); tx.commit(); }
		try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) { equal(0, cachedNeo.insert(0, ItemResource.of(tagged(1)), 1, tx)); equal(0, cachedNeoFluid.insert(0, FluidResource.of(Fluids.WATER), 1, tx)); tx.commit(); }
		equal(0, old.itemSnapshot().getCount()); equal(0, replacement.itemSnapshot().getCount()); equal(0, old.fluidUnits()); equal(0, replacement.fluidUnits());

		BlockPos fabricPos = new BlockPos(26, 80, 16); Machines.Machine oldFabric = place(level, fabricPos, Machines.FABRIC);
		var optional = forgeProvider(level, fabricPos).getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.NORTH); IItemHandler facade = optional.resolve().orElseThrow();
		var fluidOptional = forgeProvider(level, fabricPos).getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.NORTH); IFluidHandler fluidFacade = fluidOptional.resolve().orElseThrow();
		level.setBlockAndUpdate(fabricPos, Blocks.AIR.defaultBlockState()); place(level, fabricPos, Machines.FABRIC);
		yes(!optional.isPresent(), "the cached Forge LazyOptional survived removal"); equal(1, facade.insertItem(0, tagged(1), false).getCount()); equal(0, oldFabric.itemSnapshot().getCount());
		yes(!fluidOptional.isPresent(), "the cached Forge fluid LazyOptional survived removal"); equal(0, fluidFacade.fill(new FluidStack(Fluids.WATER, 1), IFluidHandler.FluidAction.EXECUTE)); equal(0, oldFabric.fluidUnits());
		System.out.println("[M33Transfer] PASS cached foreign views and Forge LazyOptional cannot write replaced block entities");
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
		equal(60, itemTotal(level)); equal(FLUID_TOTAL, fluidTotal(level));
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
	private static ICapabilityProvider forgeProvider(ServerLevel level, BlockPos pos) {
		return (ICapabilityProvider) (Object) java.util.Objects.requireNonNull(level.getBlockEntity(pos), "missing block entity at " + pos);
	}
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
