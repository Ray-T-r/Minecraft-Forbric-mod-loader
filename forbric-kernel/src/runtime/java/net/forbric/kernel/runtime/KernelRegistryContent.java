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

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.IdMapper;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CreativeModeTabRegistry;
import net.neoforged.neoforge.registries.GameData;

/**
 * The game side of everything the kernel repairs in the vanilla registries once its registration window closes.
 *
 * <p>Five jobs that share nothing but their timing — {@code KernelLifecycle.closeRegistrationWindow} decides WHEN
 * each runs and that ordering is hand-written, hard-won and stays boot-side. What is here is only what each one
 * DOES, which was ~20 {@code Class.forName} strings and as many {@code getMethod} names.
 *
 * <p>Each is best-effort by design: none of them may fail the window they repair. That is unchanged. What changes
 * is that a method or class that moves out from under them is now a build failure rather than a caught
 * {@code Throwable} and a warning about a symptom.
 */
public final class KernelRegistryContent {
	private KernelRegistryContent() {
	}

	/**
	 * Refills NeoForge's blockstate→id map, which the registration window's clear callback empties.
	 *
	 * <p>Returns early when NeoForge kept it: a non-empty map is the normal case and rebuilding it would append a
	 * second id for every state.
	 *
	 * @return how many states were added, or -1 when the map did not need rebuilding
	 */
	public static int rebuildBlockStateIds() {
		try {
			IdMapper<BlockState> idMap = GameData.getBlockStateIDMap();
			if (idMap.size() > 0) return -1;

			int states = 0;
			for (Block block : BuiltInRegistries.BLOCK) {
				for (BlockState state : block.getStateDefinition().getPossibleStates()) {
					idMap.add(state);
					states++;
				}
			}
			ForbricLog.info("[Forbric/Lifecycle] rebuilt NeoForge blockstate→id map (%d states) — the registration "
					+ "window's clear callback had emptied it", states);
			return states;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not rebuild NeoForge blockstate→id map "
					+ "(block_update packets will fail to encode)", Reflect.unwrap(t));
			return -1;
		}
	}

	/** The switch that leaves every late-registered block state's cache exactly as the registration left it. */
	static final String STATE_CACHE_SWITCH = "forbric.blockStateCaches";

	/**
	 * Computes the per-state cache for every block state, the way vanilla's own bootstrap does.
	 *
	 * <p>{@code BlockStateBase.initCache()} fills the fields the game reads on the hot path — the collision shape,
	 * the light and opacity flags, the fluid state. Vanilla calls it for every state during {@code Bootstrap},
	 * which is BEFORE any mod has registered a block, and each loader calls it again for what its own mods add.
	 * The kernel drives registration itself, so nothing did: every block a mod registered carried an uninitialised
	 * cache for the whole run.
	 *
	 * <p>Vanilla tolerates that by computing lazily. Lithium does not — it replaces the lazy path with a flags
	 * field and throws {@code Could not initialize block state flags} the first time an uninitialised state is put
	 * in a chunk section. Biomes O' Plenty's fir leaves were the first: the crash is "Feature placement", during
	 * worldgen, in Lithium's code, naming a Biomes O' Plenty block — and nothing in it points at a cache the
	 * kernel never filled.
	 *
	 * <p>Called after the registration window and again after the client entrypoints, because both register
	 * blocks. Recomputing an already-computed cache is what vanilla itself does on every bootstrap.
	 *
	 * @return how many states were initialised, or -1 when the pass could not run
	 */
	public static int initialiseBlockStateCaches() {
		if ("off".equalsIgnoreCase(System.getProperty(STATE_CACHE_SWITCH, "on"))) return -1;
		try {
			int states = 0;
			for (Block block : BuiltInRegistries.BLOCK) {
				for (BlockState state : block.getStateDefinition().getPossibleStates()) {
					state.initCache();
					states++;
				}
			}
			ForbricLog.info("[Forbric/Lifecycle] initialised %d block state cache(s) — vanilla does this in "
					+ "Bootstrap, before any mod has registered a block, and the kernel drives registration itself",
					states);
			return states;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not initialise the block state caches — a mod's block put "
					+ "into a chunk can throw from inside another mod's optimisation", Reflect.unwrap(t));
			return -1;
		}
	}

	/**
	 * Re-sorts NeoForge's creative tabs so the ones registered inside the kernel's window reach the tab strip.
	 *
	 * <p>{@code sortTabs()} REPLACES {@code SORTED_TABS} but only APPENDS to {@code DEFAULT_TABS} — it re-adds the
	 * four special tabs (hotbar / search / op / inventory) on every call and never clears. The special tabs'
	 * screen column is {@code indexOf % (size/2) + 5}, so a duplicated list (size 8) yields columns 5..8 and the
	 * tab-sprite array (length 7) overflows: {@code ArrayIndexOutOfBoundsException: Index 7} in
	 * {@code extractTabButton} the moment the creative screen renders. The baseline bring-up already sorted once,
	 * so the kernel's re-sort is always a SECOND call — the list is cleared first to make the call idempotent.
	 */
	public static void sortCreativeTabs() {
		try {
			Field defaultsField = CreativeModeTabRegistry.class.getDeclaredField("DEFAULT_TABS");
			defaultsField.setAccessible(true);
			List<?> defaults = (List<?>) defaultsField.get(null);
			defaults.clear();

			int before = CreativeModeTabRegistry.getSortedCreativeModeTabs().size();
			CreativeModeTabRegistry.sortTabs();

			List<CreativeModeTab> after = CreativeModeTabRegistry.getSortedCreativeModeTabs();
			StringBuilder names = new StringBuilder();
			for (CreativeModeTab tab : after) {
				if (names.length() > 0) names.append(", ");
				names.append(CreativeModeTabRegistry.getName(tab));
			}
			ForbricLog.info("[Forbric/Lifecycle] re-sorted NeoForge creative tabs %d -> %d (special tabs: %d, must "
					+ "stay 4): [%s] (the creative screen's tab strip reads ONLY this list; the baseline sort "
					+ "predates the kernel's registration window, so window-registered tabs were searchable but "
					+ "had no tab)", before, after.size(), defaults.size(), names);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not re-sort NeoForge creative tabs "
					+ "(mod creative tabs may be missing from the tab strip)", Reflect.unwrap(t));
		}
	}

	/**
	 * {@code -Dforbric.tabProbe} — dumps every non-vanilla creative tab's live state every 3s.
	 *
	 * <p>Pure diagnostic for the "tab registered + sorted + searchable, but the strip does not draw it" class of
	 * bug: the strip's render-time predicate is {@code shouldDisplay()} = {@code hasAnyItems()} for CATEGORY tabs,
	 * so this reports exactly the fields that predicate reads, straight from the live objects.
	 */
	public static void startCreativeTabProbe() {
		Thread probe = new Thread(() -> {
			try {
				Registry<CreativeModeTab> tabs = BuiltInRegistries.CREATIVE_MODE_TAB;
				Field display = CreativeModeTab.class.getDeclaredField("displayItems");
				Field search = CreativeModeTab.class.getDeclaredField("displayItemsSearchTab");
				display.setAccessible(true);
				search.setAccessible(true);

				while (true) {
					for (CreativeModeTab tab : tabs) {
						String key = String.valueOf(tabs.getKey(tab));
						if (key.startsWith("minecraft:")) continue;

						Collection<ItemStack> d = asItems(display.get(tab));
						Collection<ItemStack> s = asItems(search.get(tab));
						boolean inSorted = CreativeModeTabRegistry.getSortedCreativeModeTabs().contains(tab);
						ForbricLog.info("[Forbric/TabProbe] %s type=%s display=%d search=%d shouldDisplay=%s "
								+ "inSorted=%s identity=%08x", key, tab.getType(),
								d == null ? -1 : d.size(), s == null ? -1 : s.size(),
								tab.shouldDisplay(), inSorted, System.identityHashCode(tab));
					}
					Thread.sleep(3000);
				}
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/TabProbe] probe died", Reflect.unwrap(t));
			}
		}, "forbric-tab-probe");
		probe.setDaemon(true);
		probe.start();
	}

	@SuppressWarnings("unchecked")
	private static Collection<ItemStack> asItems(Object field) {
		return (Collection<ItemStack>) field;
	}

	/**
	 * Fills {@code Item.BY_BLOCK} for every registered {@code BlockItem} — the block→item link.
	 *
	 * <p>{@code Block.asItem()} resolves through {@code Item.byBlock(this)}, which is a plain
	 * {@code BY_BLOCK.get(block)}. The merged {@code BlockItem} constructor only stores its block; it never adds
	 * itself to that map. In Forge the map is filled by the ITEMS registry's ADD-CALLBACK
	 * ({@code GameData.ItemCallbacks} → {@code BlockItem.registerBlocks}), and the kernel registers content
	 * without running those callbacks — so for every modded block {@code asItem()} fell through to AIR.
	 *
	 * <p>That is invisible in the registry dump (the blocks and their items both register fine, and gate-m4
	 * counted them) but breaks anything that goes block→item. It is why Macaw's Bridges was unreachable: its
	 * creative tab feeds blocks in via {@code Output.accept(ItemLike)}, each became {@code new ItemStack(AIR)} =
	 * EMPTY, all ~150 entries were dropped, and Minecraft HIDES a tab that ends up empty — indistinguishable from
	 * "the tab was never registered". Picking a block with the middle mouse button and any recipe or tag lookup
	 * going through {@code asItem()} were equally affected.
	 *
	 * <p>{@code putIfAbsent} so an entry vanilla already established always wins.
	 *
	 * @return how many links were made
	 */
	public static int linkBlockItems() {
		try {
			Map<Block, Item> byBlock = Item.BY_BLOCK;

			int linked = 0;
			for (Item item : BuiltInRegistries.ITEM) {
				if (!(item instanceof BlockItem blockItem)) continue;

				Block block = blockItem.getBlock();
				if (block != null && byBlock.putIfAbsent(block, item) == null) linked++;
			}
			if (linked > 0) {
				ForbricLog.info("[Forbric/Lifecycle] linked %d block->item mapping(s) that Forge's registry "
						+ "add-callback would have made (Block.asItem() returns AIR without them)", linked);
			}
			return linked;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not link block->item mappings "
					+ "(modded blocks may have no item form)", Reflect.unwrap(t));
			return 0;
		}
	}

	/**
	 * Reports what the registration window actually put into the vanilla registries, grouped by namespace.
	 *
	 * <p>Constructing a mod is not the same as the mod registering anything, and {@code DeferredRegister} is
	 * silent — so a kernel that fired {@code RegisterEvent} at a mod whose listeners never attached looked
	 * exactly like one that worked. This is the line that tells them apart, and it is how M7 Wall A was
	 * confirmed.
	 *
	 * <p>Namespaces come off the id's {@code toString} ("namespace:path") rather than an accessor, which was
	 * originally because the boot side must not pin a vanilla type name to count things. It stays that way here
	 * for a different reason: it is the one form that cannot be wrong if the id type is ever swapped.
	 */
	public static void logRegisteredContent() {
		try {
			// namespace -> registry -> count, skipping vanilla's own content (the overwhelming majority).
			Map<String, Map<String, Integer>> byNamespace = new TreeMap<>();
			for (Field f : BuiltInRegistries.class.getFields()) {
				if (!Registry.class.isAssignableFrom(f.getType())) continue;
				Registry<?> registry = (Registry<?>) f.get(null);
				String regName = f.getName().toLowerCase(Locale.ROOT);
				for (Object id : (Set<?>) registry.keySet()) {
					String s = String.valueOf(id);
					int colon = s.indexOf(':');
					String ns = colon < 0 ? s : s.substring(0, colon);
					if ("minecraft".equals(ns)) continue;
					byNamespace.computeIfAbsent(ns, k -> new TreeMap<>()).merge(regName, 1, Integer::sum);
				}
			}

			if (byNamespace.isEmpty()) {
				ForbricLog.info("[Forbric/Lifecycle] registered content: none outside minecraft:");
				return;
			}
			for (Map.Entry<String, Map<String, Integer>> e : byNamespace.entrySet()) {
				int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
				ForbricLog.info("[Forbric/Lifecycle] registered content: %s: %d entr(ies) %s", e.getKey(), total,
						e.getValue());
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not summarise registered content", Reflect.unwrap(t));
		}
	}
}
