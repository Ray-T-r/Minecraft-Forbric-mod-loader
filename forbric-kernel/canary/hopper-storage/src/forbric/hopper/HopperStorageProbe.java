package forbric.hopper;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.GsonBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Hoppers against three Fabric item storages on a dedicated server: into and out of each (one step driven directly,
 * then left to the world's own ticking), a side face the storage refuses, a hopper locked by redstone, a storage
 * refilled through Fabric's API later, an empty block-only storage that must stop the hopper picking up an item lying
 * on it, and vanilla chests beside them.
 */
public final class HopperStorageProbe implements ModInitializer {
	private static final String[] KINDS = {"slotted", "unslotted", "blockonly"};
	private static final int[] COLUMN = {2, 6, 10};
	private static final List<Map<String, Object>> cases = new ArrayList<>();
	private static final Map<String, Object> premise = new LinkedHashMap<>();
	private static MinecraftServer server;
	private static ServerLevel level;
	private static int ticks;
	private static ItemEntity lying;

	@Override
	public void onInitialize() {
		Stores.register();
		ServerLifecycleEvents.SERVER_STARTED.register(started -> {
			server = started;
			started.overworld().setChunkForced(0, 0, true);
		});
		ServerTickEvents.END_SERVER_TICK.register(ticking -> {
			if (ticking != server) return;
			try {
				switch (++ticks) {
					case 20 -> build();
					case 80 -> refill();
					case 140 -> settle();
					case 200 -> { refilled(); finish(); }
					default -> { }
				}
			} catch (Throwable failure) {
				failure.printStackTrace();
				test("probe", () -> { throw failure; });
				finish();
			}
		});
	}

	private static Block block(int kind) {
		return kind == 0 ? Stores.slotted : kind == 1 ? Stores.unslotted : Stores.blockOnly;
	}

	private static SingleVariantStorage<ItemVariant> place(int kind, BlockPos pos) {
		level.setBlock(pos, block(kind).defaultBlockState(), Block.UPDATE_ALL);
		return store(kind, pos);
	}

	private static SingleVariantStorage<ItemVariant> store(int kind, BlockPos pos) {
		if (kind == 2) return Stores.BLOCK_ONLY.computeIfAbsent(pos.immutable(), p -> Stores.newStore(() -> { }));
		return ((Stores.StoreEntity) level.getBlockEntity(pos)).store;
	}

	private static HopperBlockEntity hopper(BlockPos pos, Direction facing, int items) {
		level.setBlock(pos, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, facing), Block.UPDATE_ALL);
		HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(pos);
		if (items > 0) hopper.setItem(0, new ItemStack(Items.COBBLESTONE, items));
		return hopper;
	}

	private static void seed(SingleVariantStorage<ItemVariant> store, int amount) {
		try (Transaction transaction = Transaction.openOuter()) {
			store.insert(ItemVariant.of(Items.COBBLESTONE), amount, transaction);
			transaction.commit();
		}
	}

	private static int held(BlockPos pos) {
		HopperBlockEntity hopper = (HopperBlockEntity) level.getBlockEntity(pos);
		int count = 0;
		for (int slot = 0; slot < hopper.getContainerSize(); slot++) count += hopper.getItem(slot).getCount();
		return count;
	}

	private static int cooldown(HopperBlockEntity hopper) throws ReflectiveOperationException {
		Field field = HopperBlockEntity.class.getDeclaredField("cooldownTime");
		field.setAccessible(true);
		return field.getInt(hopper);
	}

	private static Direction lastFace(BlockPos pos) {
		List<Direction> faces = Stores.FACES.get(pos);
		return faces == null || faces.isEmpty() ? null : faces.get(faces.size() - 1);
	}

	/** Does NeoForge's own block capability see it? Asked reflectively: null when NeoForge is absent. */
	private static Boolean neoSees(BlockPos pos) {
		try {
			Class<?> capabilities = Class.forName("net.neoforged.neoforge.capabilities.Capabilities$Item");
			Object cap = capabilities.getField("BLOCK").get(null);
			Class<?> blockCapability = Class.forName("net.neoforged.neoforge.capabilities.BlockCapability");
			var get = level.getClass().getMethod("getCapability", blockCapability, BlockPos.class,
					net.minecraft.world.level.block.state.BlockState.class, net.minecraft.world.level.block.entity.BlockEntity.class, Object.class);
			return get.invoke(level, cap, pos, null, null, Direction.UP) != null;
		} catch (ReflectiveOperationException absent) {
			return null;
		}
	}

	private static void build() throws Exception {
		level = server.overworld();
		for (int kind = 0; kind < KINDS.length; kind++) {
			int x = COLUMN[kind];
			BlockPos probe = new BlockPos(x, 100, 20);
			Stores.PREMISE.add(probe);
			place(kind, probe);
			premise.put(KINDS[kind] + ".neoSees", neoSees(probe));
		}
		premise.put("unslottedIsSlotted", ((Object) new net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage<>(List.of()))
				instanceof net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage);

		for (int kind = 0; kind < KINDS.length; kind++) {
			final int k = kind;
			String name = KINDS[kind];
			int x = COLUMN[kind];
			// Into it: the storage under a hopper facing down.
			BlockPos intoStore = new BlockPos(x, 100, 0), intoHopper = new BlockPos(x, 101, 0);
			SingleVariantStorage<ItemVariant> into = place(k, intoStore);
			HopperBlockEntity pushing = hopper(intoHopper, Direction.DOWN, 3);
			test(name + ".insert.step", () -> {
				HopperBlockEntity.pushItemsTick(level, intoHopper, level.getBlockState(intoHopper), pushing);
				int cool = cooldown(pushing), held = held(intoHopper);
				HopperBlockEntity.pushItemsTick(level, intoHopper, level.getBlockState(intoHopper), pushing);
				require(held == 2 && into.getAmount() == 1 && cool == 8 && held(intoHopper) == 2 && lastFace(intoStore) == Direction.UP,
						"held " + held + " stored " + into.getAmount() + " cooldown " + cool + " then " + held(intoHopper) + " face " + lastFace(intoStore));
			});
			// Out of it: the storage over a hopper.
			BlockPos fromStore = new BlockPos(x, 101, 4), fromHopper = new BlockPos(x, 100, 4);
			SingleVariantStorage<ItemVariant> from = place(k, fromStore);
			seed(from, 3);
			HopperBlockEntity pulling = hopper(fromHopper, Direction.NORTH, 0);
			test(name + ".extract.step", () -> {
				HopperBlockEntity.pushItemsTick(level, fromHopper, level.getBlockState(fromHopper), pulling);
				int cool = cooldown(pulling), held = held(fromHopper);
				HopperBlockEntity.pushItemsTick(level, fromHopper, level.getBlockState(fromHopper), pulling);
				require(held == 1 && from.getAmount() == 2 && cool == 8 && held(fromHopper) == 1 && lastFace(fromStore) == Direction.DOWN,
						"held " + held + " stored " + from.getAmount() + " cooldown " + cool + " then " + held(fromHopper) + " face " + lastFace(fromStore));
			});
			// A face the storage refuses: a hopper beside it, facing east into its west face.
			place(k, new BlockPos(x, 100, 8));
			hopper(new BlockPos(x - 1, 100, 8), Direction.EAST, 3);
			// Refilled later, through Fabric's API, with no block update.
			place(k, new BlockPos(x, 101, 12));
			hopper(new BlockPos(x, 100, 12), Direction.NORTH, 0);
			// Locked: a redstone block beside the hopper.
			place(k, new BlockPos(x, 100, 14));
			hopper(new BlockPos(x, 101, 14), Direction.DOWN, 3);
			level.setBlock(new BlockPos(x + 1, 101, 14), Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		}
		// An empty block-only storage over a hopper, an item lying on it.
		place(2, new BlockPos(14, 101, 4));
		hopper(new BlockPos(14, 100, 4), Direction.NORTH, 0);
		lying = new ItemEntity(level, 14.5, 101.6, 4.5, new ItemStack(Items.COBBLESTONE, 1));
		lying.setDeltaMovement(0, 0, 0);
		level.addFreshEntity(lying);
		// Vanilla chests: one under a hopper, one over a hopper.
		level.setBlock(new BlockPos(14, 100, 0), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		hopper(new BlockPos(14, 101, 0), Direction.DOWN, 3);
		level.setBlock(new BlockPos(14, 101, 8), Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
		((ChestBlockEntity) level.getBlockEntity(new BlockPos(14, 101, 8))).setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		hopper(new BlockPos(14, 100, 8), Direction.NORTH, 0);
	}

	private static void refill() {
		for (int kind = 0; kind < KINDS.length; kind++) seed(store(kind, new BlockPos(COLUMN[kind], 101, 12)), 2);
	}

	private static void settle() {
		for (int kind = 0; kind < KINDS.length; kind++) {
			final int k = kind;
			String name = KINDS[kind];
			int x = COLUMN[kind];
			test(name + ".insert.drain", () -> {
				long stored = store(k, new BlockPos(x, 100, 0)).getAmount();
				require(stored == 3 && held(new BlockPos(x, 101, 0)) == 0, "stored " + stored + " held " + held(new BlockPos(x, 101, 0)));
			});
			test(name + ".extract.drain", () -> {
				long stored = store(k, new BlockPos(x, 101, 4)).getAmount();
				require(stored == 0 && held(new BlockPos(x, 100, 4)) == 3, "stored " + stored + " held " + held(new BlockPos(x, 100, 4)));
			});
			test(name + ".side", () -> {
				long stored = store(k, new BlockPos(x, 100, 8)).getAmount();
				require(stored == 0 && held(new BlockPos(x - 1, 100, 8)) == 3, "a refused face took items: stored " + stored);
			});
			test(name + ".locked", () -> {
				long stored = store(k, new BlockPos(x, 100, 14)).getAmount();
				require(stored == 0 && held(new BlockPos(x, 101, 14)) == 3, "a locked hopper moved items: stored " + stored);
			});
		}
		test("blockonly.emptyBlocksPickup", () -> require(lying.isAlive() && lying.getItem().getCount() == 1 && held(new BlockPos(14, 100, 4)) == 0,
				"the hopper under an empty Fabric storage picked up the item lying on it (alive " + lying.isAlive() + ", held "
						+ held(new BlockPos(14, 100, 4)) + ")"));
		test("vanilla.chest.insert", () -> {
			var chest = (ChestBlockEntity) level.getBlockEntity(new BlockPos(14, 100, 0));
			require(chest.getItem(0).getCount() == 3 && held(new BlockPos(14, 101, 0)) == 0, "chest " + chest.getItem(0));
		});
		test("vanilla.chest.extract", () -> require(held(new BlockPos(14, 100, 8)) == 3, "held " + held(new BlockPos(14, 100, 8))));
	}

	private static void refilled() {
		for (int kind = 0; kind < KINDS.length; kind++) {
			final int k = kind;
			int x = COLUMN[kind];
			test(KINDS[kind] + ".refill", () -> {
				long stored = store(k, new BlockPos(x, 101, 12)).getAmount();
				require(stored == 0 && held(new BlockPos(x, 100, 12)) == 2, "stored " + stored + " held " + held(new BlockPos(x, 100, 12)));
			});
		}
	}

	private interface Probe { void run() throws Throwable; }

	private static void test(String name, Probe probe) {
		boolean pass = false;
		String detail = "";
		try { probe.run(); pass = true; } catch (Throwable failure) { detail = failure.toString(); }
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("name", name); row.put("pass", pass); row.put("detail", detail);
		cases.add(row);
		System.out.println("[M52Hopper] " + (pass ? "PASS " : "FAIL ") + name + (pass ? "" : " — " + detail));
	}

	private static void require(boolean condition, String detail) {
		if (!condition) throw new IllegalStateException(detail);
	}

	private static void finish() {
		try {
			Map<String, Object> report = new LinkedHashMap<>();
			report.put("phase", System.getProperty("forbric.hopperPhase"));
			report.put("premise", premise);
			report.put("cases", cases);
			Path path = Path.of(System.getProperty("forbric.hopperProbe"));
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.writeString(path, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(report));
			System.out.println("[M52Hopper] RESULT " + cases.stream().filter(c -> Boolean.TRUE.equals(c.get("pass"))).count() + "/" + cases.size());
		} catch (Exception failure) {
			failure.printStackTrace();
		} finally {
			server.halt(false);
		}
	}
}
