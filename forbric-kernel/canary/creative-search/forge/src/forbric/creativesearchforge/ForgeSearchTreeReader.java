/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.creativesearchforge;

import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.CreativeModeTabSearchRegistry;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * A MinecraftForge mod that reads the creative search tab's trees exactly as MinecraftForge's own creative screen does:
 * {@code connection.searchTrees().getSearchTree(CreativeModeTabSearchRegistry.get{Name,Tag}SearchKey(tab))
 * .search(query.toLowerCase(Locale.ROOT))}, a {@code #} query through the tag key with the {@code #} cut off.
 *
 * <p>On the merged game that method is MinecraftForge's body: it joins a future from a map nothing fills, defaulting
 * to one the merged constructor leaves incomplete, so as merged the call never returns. A Forge mod making it on the
 * client thread would freeze the game for good; this one asks from its own daemon thread and reports, from the client
 * tick, when the answer has not come within the bound, so gate-m55's control can see the hang without hanging.
 */
@Mod(ForgeSearchTreeReader.ID)
public final class ForgeSearchTreeReader {
	static final String ID = "forbriccreativesearchforge";
	private static final String[] QUERIES = {"stone", "minecraft:oak", "#planks"};
	/** Long enough for trees still being built at login to finish; the control's hang is not a matter of seconds. */
	private static final long BOUND_MS = 15_000;
	/** Client ticks with a player in the world before asking: the login's refresh has been filed by then. */
	private static final int AFTER_TICKS = 20;

	private int playerTicks;
	private Thread worker;
	private long startedAt;
	private boolean reported;
	private volatile boolean done;
	private volatile String waitingOn = "";

	public ForgeSearchTreeReader(FMLJavaModLoadingContext context) {
		TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
	}

	private void tick() {
		Minecraft minecraft = Minecraft.getInstance();
		if (worker == null) {
			if (minecraft.player == null || minecraft.getConnection() == null || ++playerTicks < AFTER_TICKS) return;
			SessionSearchTrees trees = minecraft.getConnection().searchTrees();
			System.out.println("[ForbricCreativeSearchForge] asking SessionSearchTrees.getSearchTree for the search tab the "
					+ "way MinecraftForge's creative screen does, from a worker thread, " + BOUND_MS + " ms bound");
			startedAt = System.nanoTime();
			worker = new Thread(() -> read(trees), "forbric-creative-search-forge");
			worker.setDaemon(true);
			worker.start();
		} else if (!done && !reported && System.nanoTime() - startedAt > BOUND_MS * 1_000_000L) {
			reported = true;
			System.out.println("[ForbricCreativeSearchForge] getSearchTree did not return within " + BOUND_MS
					+ " ms (still waiting on '" + waitingOn + "')");
		}
	}

	private void read(SessionSearchTrees trees) {
		CreativeModeTab tab = CreativeModeTabs.searchTab();
		try {
			for (String query : QUERIES) {
				waitingOn = query;
				boolean tag = query.startsWith("#");
				SessionSearchTrees.Key key = tag ? CreativeModeTabSearchRegistry.getTagSearchKey(tab)
						: CreativeModeTabSearchRegistry.getNameSearchKey(tab);
				List<ItemStack> found = trees.getSearchTree(key).search((tag ? query.substring(1) : query).toLowerCase(Locale.ROOT));
				System.out.println("[ForbricCreativeSearchForge] getSearchTree '" + query + "' -> " + found.size()
						+ " item(s)" + first(found));
			}
		} catch (Throwable thrown) {
			System.out.println("[ForbricCreativeSearchForge] getSearchTree threw " + thrown);
		} finally {
			done = true;
		}
	}

	private static String first(List<ItemStack> found) {
		return found.isEmpty() ? "" : " [" + BuiltInRegistries.ITEM.getKey(found.getFirst().getItem()) + ", ...]";
	}
}
