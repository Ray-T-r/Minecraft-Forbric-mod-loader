/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.multiplayer.SessionSearchTrees;
import net.minecraft.client.searchtree.SearchTree;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.CreativeModeTabSearchRegistry;

import net.forbric.kernel.util.ForbricLog;

/**
 * The creative search trees, filed and read where the merged creative screen reads them.
 *
 * <p>Called from the {@code SessionSearchTrees} methods whose merged bodies are MinecraftForge's
 * ({@code CreativeSearchTreesInjector}). On the current base that is MinecraftForge's reader {@code getSearchTree(Key)}
 * alone, which as merged joined a future from the class's private MinecraftForge map — a map nothing fills, defaulting
 * to a future nothing completes — so a MinecraftForge mod reading a search tree waited forever. On a merge that also
 * kept MinecraftForge's bodies for vanilla's two producers {@code updateCreativeTooltips(Provider, List)} and
 * {@code updateCreativeTags(List)}, they filled that map while the creative screen — NeoForge's — reads NeoForge's
 * {@link CreativeModeTabSearchRegistry}: a mod that rebuilt the creative tabs itself and then refreshed the search
 * through vanilla's methods (TCDCommons does it on every join) filled a map nothing reads, and took the screen's own
 * rebuild with it, since the tabs had not changed and the screen skipped its rebuild and searched an empty tree.
 *
 * <p>Each producer refreshes every tab that has a search bar, the search tab from the list it was given and every
 * other from its own contents. That is what MinecraftForge's body did and what the screen's own rebuild does — a
 * superset of NeoForge's, which refreshes only the search tab and leaves a mod's searchable tab empty after such a
 * caller.
 */
public final class KernelCreativeSearch {
	private static final AtomicBoolean REPORTED = new AtomicBoolean();

	private KernelCreativeSearch() {
	}

	/** {@code SessionSearchTrees.updateCreativeTooltips(Provider, List)}: the name trees, keyed as the screen reads them. */
	public static void updateNames(SessionSearchTrees trees, HolderLookup.Provider registries, List<ItemStack> searchTabItems) {
		report("names");
		for (Map.Entry<CreativeModeTab, SessionSearchTrees.Key> tab : CreativeModeTabSearchRegistry.getNameSearchKeys().entrySet()) {
			trees.updateCreativeTooltips(registries, itemsOf(tab.getKey(), searchTabItems), tab.getValue());
		}
	}

	/** {@code SessionSearchTrees.updateCreativeTags(List)}: the tag trees, keyed as the screen reads them. */
	public static void updateTags(SessionSearchTrees trees, List<ItemStack> searchTabItems) {
		report("tags");
		for (Map.Entry<CreativeModeTab, SessionSearchTrees.Key> tab : CreativeModeTabSearchRegistry.getTagSearchKeys().entrySet()) {
			trees.updateCreativeTags(itemsOf(tab.getKey(), searchTabItems), tab.getValue());
		}
	}

	/**
	 * {@code SessionSearchTrees.getSearchTree(Key)}: a tag key's tag tree, any other key's name tree. A key nothing was
	 * built under answers the empty tree — as merged it joined a default the merged constructor left incomplete.
	 *
	 * <p>The caller of this method is a MinecraftForge mod, and its key may come from MinecraftForge's own registry.
	 * For the search tab both registries hand out vanilla's shared constants, but for a mod's searchable tab each
	 * makes its own {@code Key}, so a MinecraftForge key is first turned into NeoForge's key for the same tab — the
	 * one the trees are filed under. Without that, the tab's tree is there and the mod is told it is empty.
	 */
	public static SearchTree<ItemStack> tree(SessionSearchTrees trees, SessionSearchTrees.Key key) {
		SessionSearchTrees.Key filed = neoForgeKeyFor(key);
		return CreativeModeTabSearchRegistry.getTagSearchKeys().containsValue(filed)
				? trees.creativeTagSearch(filed)
				: trees.creativeNameSearch(filed);
	}

	/** NeoForge's key for the tab MinecraftForge's {@code key} stands for; {@code key} itself when it is not one. */
	private static SessionSearchTrees.Key neoForgeKeyFor(SessionSearchTrees.Key key) {
		for (Map.Entry<CreativeModeTab, SessionSearchTrees.Key> tab
				: net.minecraftforge.client.CreativeModeTabSearchRegistry.getTagSearchKeys().entrySet()) {
			if (tab.getValue() == key) return CreativeModeTabSearchRegistry.getTagSearchKey(tab.getKey());
		}
		for (Map.Entry<CreativeModeTab, SessionSearchTrees.Key> tab
				: net.minecraftforge.client.CreativeModeTabSearchRegistry.getNameSearchKeys().entrySet()) {
			if (tab.getValue() == key) return CreativeModeTabSearchRegistry.getNameSearchKey(tab.getKey());
		}
		return key;
	}

	private static List<ItemStack> itemsOf(CreativeModeTab tab, List<ItemStack> searchTabItems) {
		return tab == CreativeModeTabs.searchTab() ? searchTabItems : List.copyOf(tab.getDisplayItems());
	}

	private static void report(String which) {
		if (REPORTED.compareAndSet(false, true)) {
			ForbricLog.info("[Forbric/CreativeSearch] a caller refreshed the creative search %s through vanilla's "
					+ "SessionSearchTrees method; the trees are filed under NeoForge's keys, where the creative screen "
					+ "reads them", which);
		}
	}
}
