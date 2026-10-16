/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package forbric.creativesearchforge;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.client.CreativeModeTabSearchRegistry;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;

/**
 * A MinecraftForge mod that reads the creative search tab's trees exactly as MinecraftForge's own creative screen does:
 * {@code connection.searchTrees().getSearchTree(CreativeModeTabSearchRegistry.get{Name,Tag}SearchKey(tab))
 * .search(query.toLowerCase(Locale.ROOT))}, a {@code #} query through the tag key with the {@code #} cut off.
 *
 * <p>On the merged game that method is MinecraftForge's body: it joins a future from a map nothing fills, defaulting
 * to one the merged constructor leaves incomplete, so as merged the call never returns. A Forge mod making it on the
 * client thread would freeze the game for good; this one asks from its own daemon thread and reports, from the client
 * tick, when the answer has not come within the bound, so gate-m55's control can see the hang without hanging.
 *
 * <p>It also registers a searchable tab of its own, through MinecraftForge's registry, and -- once a creative screen has
 * opened and built every searchable tab's trees -- reads that tab's trees with the keys MinecraftForge's
 * {@code CreativeModeTabSearchRegistry} hands it. For the search tab both families answer vanilla's shared constants;
 * for any other tab each family makes its own key, so only this read sees whether a MinecraftForge key reaches the tree
 * the (NeoForge) screen filed. A key nobody handed out is asked too, and must find nothing.
 */
@Mod(ForgeSearchTreeReader.ID)
public final class ForgeSearchTreeReader {
	static final String ID = "forbriccreativesearchforge";
	private static final String[] QUERIES = {"stone", "minecraft:oak", "#planks"};
	/** Long enough for trees still being built at login to finish; the control's hang is not a matter of seconds. */
	private static final long BOUND_MS = 15_000;
	/** Client ticks with a player in the world before asking: the login's refresh has been filed by then. */
	private static final int AFTER_TICKS = 20;

	/** Ticks after a creative screen first opened before the own tab is read: its trees are filed by then. */
	private static final int AFTER_SCREEN_TICKS = 20;
	private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ID);
	/** Three items whose names all say "oak", one of them in #minecraft:planks. */
	private static final Supplier<CreativeModeTab> OWN_TAB = TABS.register("search_canary", () -> CreativeModeTab.builder()
			.title(Component.literal("Forbric search canary"))
			.icon(() -> new ItemStack(Items.OAK_PLANKS))
			.displayItems((parameters, output) -> {
				output.accept(Items.OAK_PLANKS);
				output.accept(Items.OAK_LOG);
				output.accept(Items.OAK_SLAB);
			})
			.withSearchBar()
			.build());

	private int playerTicks;
	private Thread worker;
	private long startedAt;
	private boolean reported;
	private volatile boolean done;
	private volatile String waitingOn = "";
	private int screenTicks = -1;
	private Thread ownTabWorker;

	public ForgeSearchTreeReader(FMLJavaModLoadingContext context) {
		TABS.register(context.getModBusGroup());
		TickEvent.ClientTickEvent.Post.BUS.addListener(event -> tick());
	}

	private void tick() {
		Minecraft minecraft = Minecraft.getInstance();
		if (ownTabWorker == null && minecraft.getConnection() != null) {
			if (screenTicks < 0 && minecraft.gui.screen() instanceof CreativeModeInventoryScreen) screenTicks = 0;
			if (screenTicks >= 0 && ++screenTicks > AFTER_SCREEN_TICKS) {
				SessionSearchTrees trees = minecraft.getConnection().searchTrees();
				ownTabWorker = new Thread(() -> readOwnTab(trees), "forbric-creative-search-forge-own-tab");
				ownTabWorker.setDaemon(true);
				ownTabWorker.start();
			}
		}
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

	/**
	 * The canary's own searchable tab, read with MinecraftForge's keys for it. Reported as counts beside what the tab
	 * displays, so the gate compares the search with the tab's own contents rather than with a number written down.
	 */
	private void readOwnTab(SessionSearchTrees trees) {
		try {
			CreativeModeTab tab = OWN_TAB.get();
			List<ItemStack> shown = List.copyOf(tab.getDisplayItems());
			long tagged = shown.stream().filter(stack -> stack.is(ItemTags.PLANKS)).count();
			System.out.println("[ForbricCreativeSearchForge] own tab displays " + shown.size() + " item(s), " + tagged
					+ " in #minecraft:planks (search bar: " + tab.hasSearchBar() + ")");
			SessionSearchTrees.Key names = CreativeModeTabSearchRegistry.getNameSearchKey(tab);
			SessionSearchTrees.Key tags = CreativeModeTabSearchRegistry.getTagSearchKey(tab);
			System.out.println("[ForbricCreativeSearchForge] own-tab search by name 'oak' -> " + trees.getSearchTree(names).search("oak").size() + " item(s)");
			System.out.println("[ForbricCreativeSearchForge] own-tab search by tag 'planks' -> " + trees.getSearchTree(tags).search("planks").size() + " item(s)");
			System.out.println("[ForbricCreativeSearchForge] own-tab search with a key nobody handed out -> "
					+ trees.getSearchTree(new SessionSearchTrees.Key()).search("oak").size() + " item(s)");
		} catch (Throwable thrown) {
			System.out.println("[ForbricCreativeSearchForge] own-tab search failed: " + thrown);
		}
	}

	private static String first(List<ItemStack> found) {
		return found.isEmpty() ? "" : " [" + BuiltInRegistries.ITEM.getKey(found.getFirst().getItem()) + ", ...]";
	}
}
