/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link CreativeSearchTreesInjector}'s output, run: a caller that refreshes the creative search through vanilla's
 * two methods — what TCDCommons does on every join, right after rebuilding the tabs itself — fills the trees the
 * creative screen searches, where as merged it filled MinecraftForge's private map and every search found nothing.
 *
 * <p>The stand-in {@code SessionSearchTrees} has the merged class's shape: NeoForge's keyed producers and readers
 * over NeoForge's registry, and MinecraftForge's bodies for vanilla's two producers and for {@code getSearchTree},
 * over a private map whose default future is never completed. The helper is the kernel's real
 * {@code KernelCreativeSearch}, compiled from {@code src/runtime/java}. "What the screen searches" is the merged
 * screen's own read: {@code creativeNameSearch(NeoForge.getNameSearchKey(tab))}.
 */
@ExecutesInjector(CreativeSearchTreesInjector.class)
@ResourceLock("system-properties")
class CreativeSearchTreesInjectorExecutionTest {
	private static final Path HELPER_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelCreativeSearch.java");
	private static final String TREES = CreativeSearchTreesInjector.TARGET;
	private static final String NEO = "net.neoforged.neoforge.client.CreativeModeTabSearchRegistry";

	private static final String MERGED_TREES = """
			package net.minecraft.client.multiplayer;

			import java.util.HashMap;
			import java.util.IdentityHashMap;
			import java.util.List;
			import java.util.Map;
			import java.util.concurrent.CompletableFuture;
			import net.minecraft.client.searchtree.SearchTree;
			import net.minecraft.core.HolderLookup;
			import net.minecraft.world.item.CreativeModeTab;
			import net.minecraft.world.item.CreativeModeTabs;
			import net.minecraft.world.item.ItemStack;

			public class SessionSearchTrees {
				public static final Key CREATIVE_NAMES = new Key();
				public static final Key CREATIVE_TAGS = new Key();
				private final Map<Key, Runnable> reloaders = new IdentityHashMap<>();
				// As merged: MinecraftForge's default, but created by the merged constructor and never completed.
				private CompletableFuture<SearchTree<ItemStack>> EMPTY = new CompletableFuture<>();
				private Map<Key, CompletableFuture<SearchTree<ItemStack>>> creativeSearch = new HashMap<>();

				private void register(Key key, Runnable reload) {
					reload.run();
					reloaders.put(key, reload);
				}

				public void rebuildAfterLanguageChange() {
					for (Runnable reload : reloaders.values()) reload.run();
				}

				// MinecraftForge's body: every tab of MinecraftForge's registry, into the private map.
				public void updateCreativeTooltips(HolderLookup.Provider registries, List<ItemStack> items) {
					for (Map.Entry<CreativeModeTab, Key> e : net.minecraftforge.client.CreativeModeTabSearchRegistry.getNameSearchKeys().entrySet()) {
						register(e.getValue(), () -> {
							List<ItemStack> list = e.getKey() == CreativeModeTabs.searchTab() ? items : List.copyOf(e.getKey().getDisplayItems());
							CompletableFuture<SearchTree<ItemStack>> old = creativeSearch.getOrDefault(e.getValue(), EMPTY);
							creativeSearch.put(e.getValue(), CompletableFuture.completedFuture(byName(list)));
							old.cancel(true);
						});
					}
				}

				public void updateCreativeTags(List<ItemStack> items) {
					for (Map.Entry<CreativeModeTab, Key> e : net.minecraftforge.client.CreativeModeTabSearchRegistry.getTagSearchKeys().entrySet()) {
						register(e.getValue(), () -> {
							List<ItemStack> list = e.getKey() == CreativeModeTabs.searchTab() ? items : List.copyOf(e.getKey().getDisplayItems());
							CompletableFuture<SearchTree<ItemStack>> old = creativeSearch.getOrDefault(e.getValue(), EMPTY);
							creativeSearch.put(e.getValue(), CompletableFuture.completedFuture(byTag(list)));
							old.cancel(true);
						});
					}
				}

				public SearchTree<ItemStack> getSearchTree(Key key) {
					return creativeSearch.getOrDefault(key, EMPTY).join();
				}

				// NeoForge's keyed producers and readers, over NeoForge's registry: what the creative screen calls.
				public void updateCreativeTooltips(HolderLookup.Provider registries, List<ItemStack> items, Key key) {
					register(key, () -> {
						CompletableFuture<SearchTree<ItemStack>> old = net.neoforged.neoforge.client.CreativeModeTabSearchRegistry.getNameSearchTree(key);
						net.neoforged.neoforge.client.CreativeModeTabSearchRegistry.putNameSearchTree(key, CompletableFuture.completedFuture(byName(items)));
						old.cancel(true);
					});
				}

				public void updateCreativeTags(List<ItemStack> items, Key key) {
					register(key, () -> {
						CompletableFuture<SearchTree<ItemStack>> old = net.neoforged.neoforge.client.CreativeModeTabSearchRegistry.getTagSearchTree(key);
						net.neoforged.neoforge.client.CreativeModeTabSearchRegistry.putTagSearchTree(key, CompletableFuture.completedFuture(byTag(items)));
						old.cancel(true);
					});
				}

				public SearchTree<ItemStack> creativeNameSearch() {
					return creativeNameSearch(CREATIVE_NAMES);
				}

				public SearchTree<ItemStack> creativeNameSearch(Key key) {
					return net.neoforged.neoforge.client.CreativeModeTabSearchRegistry.getNameSearchTree(key).join();
				}

				public SearchTree<ItemStack> creativeTagSearch(Key key) {
					return net.neoforged.neoforge.client.CreativeModeTabSearchRegistry.getTagSearchTree(key).join();
				}

				private static SearchTree<ItemStack> byName(List<ItemStack> items) {
					return query -> items.stream().filter(stack -> stack.id().contains(query)).toList();
				}

				private static SearchTree<ItemStack> byTag(List<ItemStack> items) {
					return query -> items.stream().filter(stack -> stack.tag().equals(query)).toList();
				}

				public static class Key {
				}
			}
			""";

	/** NeoForge's own class: vanilla's two producers delegate to the keyed ones, and there is no private map. */
	private static final String NEOFORGE_TREES = """
			package net.minecraft.client.multiplayer;

			import java.util.List;
			import net.minecraft.client.searchtree.SearchTree;
			import net.minecraft.core.HolderLookup;
			import net.minecraft.world.item.ItemStack;

			public class SessionSearchTrees {
				public static final Key CREATIVE_NAMES = new Key();
				public static final Key CREATIVE_TAGS = new Key();

				public void updateCreativeTooltips(HolderLookup.Provider registries, List<ItemStack> items) {
					updateCreativeTooltips(registries, items, CREATIVE_NAMES);
				}

				public void updateCreativeTooltips(HolderLookup.Provider registries, List<ItemStack> items, Key key) {
				}

				public void updateCreativeTags(List<ItemStack> items) {
					updateCreativeTags(items, CREATIVE_TAGS);
				}

				public void updateCreativeTags(List<ItemStack> items, Key key) {
				}

				public SearchTree<ItemStack> creativeNameSearch(Key key) {
					return SearchTree.empty();
				}

				public SearchTree<ItemStack> creativeTagSearch(Key key) {
					return SearchTree.empty();
				}

				public static class Key {
				}
			}
			""";

	private static final Map<String, String> GAME = Map.of(
			"net.minecraft.world.item.ItemStack", """
					package net.minecraft.world.item;

					public record ItemStack(String id, String tag) {
					}
					""",
			"net.minecraft.world.item.CreativeModeTab", """
					package net.minecraft.world.item;

					import java.util.Collection;
					import java.util.List;

					public class CreativeModeTab {
						private final boolean searchBar;
						private final List<ItemStack> items;

						public CreativeModeTab(boolean searchBar, List<ItemStack> items) {
							this.searchBar = searchBar;
							this.items = items;
						}

						public boolean hasSearchBar() {
							return searchBar;
						}

						public Collection<ItemStack> getDisplayItems() {
							return items;
						}
					}
					""",
			"net.minecraft.world.item.CreativeModeTabs", """
					package net.minecraft.world.item;

					import java.util.List;

					public class CreativeModeTabs {
						public static final CreativeModeTab SEARCH = new CreativeModeTab(true, List.of());
						public static final CreativeModeTab MOD = new CreativeModeTab(true,
								List.of(new ItemStack("examplemod:stone_gear", "gears")));
						public static final CreativeModeTab PLAIN = new CreativeModeTab(false,
								List.of(new ItemStack("minecraft:dirt", "dirt")));

						public static CreativeModeTab searchTab() {
							return SEARCH;
						}

						public static List<CreativeModeTab> allTabs() {
							return List.of(SEARCH, MOD, PLAIN);
						}
					}
					""",
			"net.minecraft.client.searchtree.SearchTree", """
					package net.minecraft.client.searchtree;

					import java.util.List;

					public interface SearchTree<T> {
						List<T> search(String query);

						static <T> SearchTree<T> empty() {
							return query -> List.of();
						}
					}
					""",
			"net.minecraft.core.HolderLookup", """
					package net.minecraft.core;

					public interface HolderLookup {
						interface Provider {
						}
					}
					""",
			NEO, registry("net.neoforged.neoforge.client", true),
			"net.minecraftforge.client.CreativeModeTabSearchRegistry", registry("net.minecraftforge.client", false));

	/** A family's search-key registry: one key per tab with a search bar, the search tab's being vanilla's own. */
	private static String registry(String pkg, boolean trees) {
		return """
				package %s;

				import java.util.IdentityHashMap;
				import java.util.Map;
				import java.util.concurrent.CompletableFuture;
				import net.minecraft.client.multiplayer.SessionSearchTrees;
				import net.minecraft.client.searchtree.SearchTree;
				import net.minecraft.world.item.CreativeModeTab;
				import net.minecraft.world.item.CreativeModeTabs;
				import net.minecraft.world.item.ItemStack;

				public class CreativeModeTabSearchRegistry {
					private static final Map<CreativeModeTab, SessionSearchTrees.Key> NAME_KEYS = new IdentityHashMap<>();
					private static final Map<CreativeModeTab, SessionSearchTrees.Key> TAG_KEYS = new IdentityHashMap<>();

					public static Map<CreativeModeTab, SessionSearchTrees.Key> getNameSearchKeys() {
						Map<CreativeModeTab, SessionSearchTrees.Key> keys = new IdentityHashMap<>();
						for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
							SessionSearchTrees.Key key = getNameSearchKey(tab);
							if (key != null) keys.put(tab, key);
						}
						return keys;
					}

					public static Map<CreativeModeTab, SessionSearchTrees.Key> getTagSearchKeys() {
						Map<CreativeModeTab, SessionSearchTrees.Key> keys = new IdentityHashMap<>();
						for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
							SessionSearchTrees.Key key = getTagSearchKey(tab);
							if (key != null) keys.put(tab, key);
						}
						return keys;
					}

					public static SessionSearchTrees.Key getNameSearchKey(CreativeModeTab tab) {
						if (tab == CreativeModeTabs.searchTab()) return SessionSearchTrees.CREATIVE_NAMES;
						return tab.hasSearchBar() ? NAME_KEYS.computeIfAbsent(tab, t -> new SessionSearchTrees.Key()) : null;
					}

					public static SessionSearchTrees.Key getTagSearchKey(CreativeModeTab tab) {
						if (tab == CreativeModeTabs.searchTab()) return SessionSearchTrees.CREATIVE_TAGS;
						return tab.hasSearchBar() ? TAG_KEYS.computeIfAbsent(tab, t -> new SessionSearchTrees.Key()) : null;
					}
				%s}
				""".formatted(pkg, !trees ? "" : """

					private static final CompletableFuture<SearchTree<ItemStack>> DEFAULT = CompletableFuture.completedFuture(SearchTree.empty());
					private static final Map<SessionSearchTrees.Key, CompletableFuture<SearchTree<ItemStack>>> NAMES = new IdentityHashMap<>();
					private static final Map<SessionSearchTrees.Key, CompletableFuture<SearchTree<ItemStack>>> TAGS = new IdentityHashMap<>();

					public static CompletableFuture<SearchTree<ItemStack>> getNameSearchTree(SessionSearchTrees.Key key) {
						return NAMES.getOrDefault(key, DEFAULT);
					}

					public static void putNameSearchTree(SessionSearchTrees.Key key, CompletableFuture<SearchTree<ItemStack>> tree) {
						NAMES.put(key, tree);
					}

					public static CompletableFuture<SearchTree<ItemStack>> getTagSearchTree(SessionSearchTrees.Key key) {
						return TAGS.getOrDefault(key, DEFAULT);
					}

					public static void putTagSearchTree(SessionSearchTrees.Key key, CompletableFuture<SearchTree<ItemStack>> tree) {
						TAGS.put(key, tree);
					}
				""");
	}

	@AfterEach
	void reset() {
		System.clearProperty(CreativeSearchTreesInjector.PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work, String trees) throws Exception {
		assertTrue(Files.isRegularFile(HELPER_SOURCE), "the game-side helper's source is part of the checkout: " + HELPER_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(GAME);
		sources.put(TREES, trees);
		sources.put("net/forbric/kernel/runtime/KernelCreativeSearch.java", Files.readString(HELPER_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	/** The search tab's stacks, as a caller that just rebuilt the tabs hands them over. */
	private static List<?> searchTabItems(ClassLoader loader) throws Throwable {
		Class<?> stack = loader.loadClass("net.minecraft.world.item.ItemStack");
		return List.of(InjectorExecution.construct(stack, "minecraft:stone", "stone"),
				InjectorExecution.construct(stack, "minecraft:oak_planks", "planks"));
	}

	/** What the merged creative screen shows for {@code query} in {@code tab}: its own read, by NeoForge's key. */
	private static List<?> screenSearch(ClassLoader loader, Object trees, String tab, String query, boolean tags) throws Throwable {
		Object creativeTab = InjectorExecution.getStatic(loader.loadClass("net.minecraft.world.item.CreativeModeTabs"), tab);
		Class<?> neo = loader.loadClass(NEO);
		Object key = InjectorExecution.invokeStatic(neo, tags ? "getTagSearchKey" : "getNameSearchKey", creativeTab);
		Object tree = InjectorExecution.invoke(trees, tags ? "creativeTagSearch" : "creativeNameSearch", key);
		return (List<?>) InjectorExecution.invoke(tree, "search", query);
	}

	private static List<String> ids(List<?> stacks) throws Throwable {
		List<String> ids = new java.util.ArrayList<>();
		for (Object stack : stacks) ids.add((String) InjectorExecution.invoke(stack, "id"));
		return ids;
	}

	/** A session in which a mod refreshed the search the vanilla way; the screen's own rebuild never ran. */
	private static Object refreshedTheVanillaWay(ClassLoader loader) throws Throwable {
		Object trees = InjectorExecution.construct(loader.loadClass(TREES));
		List<?> items = searchTabItems(loader);
		InjectorExecution.invoke(trees, "updateCreativeTooltips", null, items);
		InjectorExecution.invoke(trees, "updateCreativeTags", items);
		return trees;
	}

	@Test void aVanillaRefreshFillsWhatTheCreativeScreenSearches(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work, MERGED_TREES);
		String internal = TREES.replace('.', '/');
		byte[] served = InjectorExecution.transform(new CreativeSearchTreesInjector(), TREES, original.get(internal), EnvType.CLIENT);
		assertNotSame(original.get(internal), served, "the merged shape was not recognised");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, served);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(served, loader));

		Object trees = refreshedTheVanillaWay(loader);
		assertEquals(List.of("minecraft:stone"), ids(screenSearch(loader, trees, "SEARCH", "stone", false)),
				"the search tab finds what the caller handed over");
		assertEquals(List.of("minecraft:oak_planks"), ids(screenSearch(loader, trees, "SEARCH", "planks", true)),
				"and its # search finds it by tag");
		assertEquals(List.of("examplemod:stone_gear"), ids(screenSearch(loader, trees, "MOD", "gear", false)),
				"a mod's own searchable tab is refreshed too, from its own contents");

		// MinecraftForge's reader (the merged getSearchTree) sees the same trees, and a key nothing was built under answers
		// at once. (A MinecraftForge mod's own registry keys are no longer translated: no MinecraftForge mod is loaded.)
		Object names = InjectorExecution.getStatic(loader.loadClass(TREES), "CREATIVE_NAMES");
		Object tags = InjectorExecution.getStatic(loader.loadClass(TREES), "CREATIVE_TAGS");
		assertEquals(List.of("minecraft:stone"),
				ids((List<?>) InjectorExecution.invoke(InjectorExecution.invoke(trees, "getSearchTree", names), "search", "stone")));
		assertEquals(List.of("minecraft:oak_planks"),
				ids((List<?>) InjectorExecution.invoke(InjectorExecution.invoke(trees, "getSearchTree", tags), "search", "planks")));
		Object unknown = InjectorExecution.construct(loader.loadClass(TREES + "$Key"));
		Object empty = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> InjectorExecution.invoke(trees, "getSearchTree", unknown),
				"as merged this joined a future nothing ever completes");
		assertEquals(List.of(), InjectorExecution.invoke(empty, "search", "stone"));

		// A language change re-runs every registered refresh; the trees stay where the screen reads them.
		InjectorExecution.invoke(trees, "rebuildAfterLanguageChange");
		assertEquals(List.of("minecraft:stone"), ids(screenSearch(loader, trees, "SEARCH", "stone", false)));

		assertSame(served, InjectorExecution.transform(new CreativeSearchTreesInjector(), TREES, served, EnvType.CLIENT),
				"a class already repaired is left alone");
	}

	@Test void premiseAsMergedTheScreenSearchesAnEmptyTree(@TempDir Path work) throws Throwable {
		ClassLoader loader = InjectorExecution.load(compile(work, MERGED_TREES));
		Object trees = refreshedTheVanillaWay(loader);
		assertEquals(List.of(), screenSearch(loader, trees, "SEARCH", "stone", false),
				"premise: the vanilla refresh went into MinecraftForge's map, which the screen never reads");
		assertEquals(List.of(), screenSearch(loader, trees, "SEARCH", "planks", true));
		Object names = InjectorExecution.getStatic(loader.loadClass(TREES), "CREATIVE_NAMES");
		assertEquals(List.of("minecraft:stone"),
				ids((List<?>) InjectorExecution.invoke(InjectorExecution.invoke(trees, "getSearchTree", names), "search", "stone")),
				"premise: the trees exist, in the map only MinecraftForge's reader looks at");
	}

	@Test void neoForgesOwnClassAndTheSwitchLeaveTheClassAlone(@TempDir Path work) throws Exception {
		String internal = TREES.replace('.', '/');
		byte[] neoForge = compile(work.resolve("neo"), NEOFORGE_TREES).get(internal);
		assertSame(neoForge, InjectorExecution.transform(new CreativeSearchTreesInjector(), TREES, neoForge, EnvType.CLIENT),
				"vanilla's producers already delegate to the keyed ones: nothing to repair");

		byte[] merged = compile(work.resolve("merged"), MERGED_TREES).get(internal);
		assertSame(merged, InjectorExecution.transform(new CreativeSearchTreesInjector(), "net.minecraft.client.Minecraft", merged, EnvType.CLIENT));
		System.setProperty(CreativeSearchTreesInjector.PROPERTY, "off");
		assertSame(merged, InjectorExecution.transform(new CreativeSearchTreesInjector(), TREES, merged, EnvType.CLIENT));
	}
}
