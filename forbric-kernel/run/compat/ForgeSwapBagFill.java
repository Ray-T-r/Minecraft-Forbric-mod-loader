import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * forge-swap's packedup bag step, as a Java agent for the server that CREATES the swap's old world.
 *
 * <p>Why an agent. packedup gives a backpack an inventory only in {@code PackedUpCommon.openBackpackInventory}: a
 * player using the bag (or pressing packedup's open-bag key, whose packet ends in the same method) makes
 * {@code BackpackStorageManager.createInventoryIndex(type)} allocate the inventory, stamps its id on the stack as the
 * {@code packedup:inventory_id} component, and the player then moves stacks in through the bag's slots, which call
 * {@code BackpackInventory.setStackInSlot}. No command, datapack or console line reaches any of that, and a
 * dedicated server has no player (and the native MinecraftForge arm has no client at all). This agent runs exactly
 * the server-side half of those two player actions on the server thread, with packedup's own classes: the bag in
 * slot 0 of the chest at {@code bag=X,Y,Z} gets an inventory, and every stack of the chest at {@code source=X,Y,Z}
 * is moved into it slot by slot. What the bag holds is then written by packedup's own save (its LevelEvent.Save
 * listener) and is read back from {@code packedup/backpacks/inventory<id>.nbt}, never assumed.
 *
 * <p>It acts once, when both chests exist and the bag has no inventory yet, and prints one line the session log
 * keeps: {@code [forge-swap] bag filled: <where> inventory_id=<id> type=<type> stacks=<n> (<item*count> ...)}, or
 * {@code [forge-swap] bag NOT filled: <why>}. Pure reflection: it compiles against nothing and runs on any loader
 * that loads packedup (old Forbric with the MinecraftForge build, native MinecraftForge, native NeoForge).
 *
 * <p>Args: {@code bag=X,Y,Z|player;source=X,Y,Z[;timeout=SECONDS]}; {@code bag=player} is slot 0 (hotbar 0) of the
 * first player's inventory, for the singleplayer phase.
 */
public final class ForgeSwapBagFill {
	private static volatile boolean done;

	public static void premain(String args, Instrumentation inst) {
		String bagArg = arg(args, "bag");
		int[] bag = "player".equals(bagArg) ? null : pos(bagArg);
		int[] source = pos(arg(args, "source"));
		long timeout = Long.parseLong(arg(args, "timeout") == null ? "1200" : arg(args, "timeout")) * 1000L;
		Thread t = new Thread(() -> loop(inst, bag, source, timeout), "forge-swap-bagfill");
		t.setDaemon(true);
		t.start();
	}

	private static void loop(Instrumentation inst, int[] bag, int[] source, long timeout) {
		long until = System.currentTimeMillis() + timeout;
		while (!done && System.currentTimeMillis() < until) {
			try {
				Thread.sleep(1000);
				Class<?> utils = loaded(inst, "com.supermartijn642.core.CommonUtils");
				Class<?> storage = loaded(inst, "com.supermartijn642.packedup.storage.BackpackStorageManager");
				if (utils == null || storage == null) continue;
				Object server = utils.getMethod("getServer").invoke(null);
				if (server == null) continue;
				Field directory = storage.getDeclaredField("directory");
				directory.setAccessible(true);
				if (directory.get(null) == null) continue; // packedup has not seen the overworld load yet
				((Executor) server).execute(() -> {
					if (done) return;
					try {
						attempt(server, storage, bag, source);
					} catch (Throwable e) {
						done = true;
						System.out.println("[forge-swap] bag NOT filled: " + e);
						e.printStackTrace(System.out);
					}
				});
			} catch (InterruptedException e) {
				return;
			} catch (Throwable e) {
				// not ready yet (a class half-initialised, the server still starting): try again next second
			}
		}
		if (!done) System.out.println("[forge-swap] bag NOT filled: timed out waiting for both chests");
	}

	/** On the server thread. Returns quietly while a chest is missing, so the loop tries again a second later. */
	private static void attempt(Object server, Class<?> storage, int[] bag, int[] source) throws Exception {
		ClassLoader cl = storage.getClassLoader();
		Object level = server.getClass().getMethod("overworld").invoke(server);
		Object bagChest = bag == null ? firstPlayerInventory(server) : blockEntity(level, cl, bag);
		Object sourceChest = blockEntity(level, cl, source);
		Class<?> container = Class.forName("net.minecraft.world.Container", false, cl);
		if (!container.isInstance(bagChest) || !container.isInstance(sourceChest)) return;
		Method getItem = container.getMethod("getItem", int.class);
		Object stack = getItem.invoke(bagChest, 0);
		Object item = stack.getClass().getMethod("getItem").invoke(stack);
		if (!item.getClass().getName().equals("com.supermartijn642.packedup.BackpackItem")) return;
		Class<?> backpackItem = item.getClass();
		Object idType = backpackItem.getField("INVENTORY_ID").get(null);
		Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType", false, cl);
		if ((boolean) stack.getClass().getMethod("has", componentType).invoke(stack, idType)) {
			done = true;
			System.out.println("[forge-swap] bag NOT filled: the bag at " + (bag == null ? "player slot 0" : str(bag)) + " already has an inventory");
			return;
		}
		Field typeField = backpackItem.getField("type");
		Object type = typeField.get(item);
		Class<?> typeClass = typeField.getType(); // BackpackType (an enum constant's own class may be a subclass)
		int id = (int) storage.getMethod("createInventoryIndex", typeClass).invoke(null, type);
		stack.getClass().getMethod("set", componentType, Object.class).invoke(stack, idType, Integer.valueOf(id));
		Object inventory = storage.getMethod("getInventory", int.class).invoke(null, id);
		inventory.getClass().getMethod("adjustSize", typeClass).invoke(inventory, type);
		Method remove = container.getMethod("removeItemNoUpdate", int.class);
		Class<?> stackClass = Class.forName("net.minecraft.world.item.ItemStack", false, cl);
		Method setSlot = inventory.getClass().getMethod("setStackInSlot", int.class, stackClass);
		int size = (int) container.getMethod("getContainerSize").invoke(sourceChest);
		StringBuilder moved = new StringBuilder();
		int slot = 0;
		for (int i = 0; i < size; i++) {
			Object s = remove.invoke(sourceChest, i);
			if ((boolean) stackClass.getMethod("isEmpty").invoke(s)) continue;
			moved.append(' ').append(stackClass.getMethod("getItem").invoke(s)).append('*')
					.append(stackClass.getMethod("getCount").invoke(s));
			setSlot.invoke(inventory, slot++, s);
		}
		container.getMethod("setChanged").invoke(sourceChest);
		container.getMethod("setChanged").invoke(bagChest);
		done = true;
		System.out.println("[forge-swap] bag filled: " + (bag == null ? "player slot 0" : str(bag)) + " inventory_id=" + id + " type=" + type + " stacks=" + slot + " ("
				+ moved.toString().trim() + ")");
	}

	/** The first player's inventory (slot 0 = hotbar 0), or null before anyone joined. */
	private static Object firstPlayerInventory(Object server) throws Exception {
		Object list = server.getClass().getMethod("getPlayerList").invoke(server);
		java.util.List<?> players = (java.util.List<?>) list.getClass().getMethod("getPlayers").invoke(list);
		if (players.isEmpty()) return null;
		Object player = players.get(0);
		return player.getClass().getMethod("getInventory").invoke(player);
	}

	private static Object blockEntity(Object level, ClassLoader cl, int[] p) throws Exception {
		Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos", false, cl);
		Object pos = blockPos.getConstructor(int.class, int.class, int.class).newInstance(p[0], p[1], p[2]);
		return level.getClass().getMethod("getBlockEntity", blockPos).invoke(level, pos);
	}

	private static Class<?> loaded(Instrumentation inst, String name) {
		for (Class<?> c : inst.getAllLoadedClasses()) if (c.getName().equals(name)) return c;
		return null;
	}

	private static String arg(String args, String key) {
		if (args == null) return null;
		for (String part : args.split(";")) {
			String[] kv = part.split("=", 2);
			if (kv.length == 2 && kv[0].trim().equals(key)) return kv[1].trim();
		}
		return null;
	}

	private static int[] pos(String text) {
		if (text == null) throw new IllegalArgumentException("ForgeSwapBagFill needs bag=X,Y,Z;source=X,Y,Z");
		String[] p = text.split(",");
		return new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim())};
	}

	private static String str(int[] p) {
		return p[0] + "," + p[1] + "," + p[2];
	}
}
