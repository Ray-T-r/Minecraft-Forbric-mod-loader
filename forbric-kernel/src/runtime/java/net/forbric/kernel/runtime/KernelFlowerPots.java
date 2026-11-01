/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;

import com.google.common.collect.Table;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.neoforged.neoforge.registries.GameData;

/**
 * NeoForge's flower pot table, filled from every registered pot the way its bake callback does (rebuildTable).
 *
 * <p>The merged block registry was MinecraftForge's wrapper, and NeoForge's bake callback never ran on it, so every pot
 * lookup answered air. The merged {@code useItemOn} lookup also asked MinecraftForge's {@code fullPots} first through two
 * decorators here; NeoForge's base has no such half, and they went in Stage 4.
 */
public final class KernelFlowerPots {
	private KernelFlowerPots() {
	}

	/**
	 * Fills NeoForge's pot table from every registered pot, as its bake callback would: each pot that is not its own
	 * empty pot is the full pot of (its empty pot, its plant). Returns how many pots were entered, or -1 on failure.
	 */
	public static int rebuildTable() {
		// The same switches as the rest of the flower pot repair (NativeCoremodParity, FlowerPotRepairInjector).
		if ("off".equalsIgnoreCase(System.getProperty("forbric.coremodParity", "on"))
				|| "off".equalsIgnoreCase(System.getProperty("forbric.flowerPotRepair", "on"))) return -1;
		try {
			Table<Block, Block, Block> table = GameData.getFlowerPotBlockTable();
			table.clear();
			int entered = 0, failed = 0;
			for (Block block : BuiltInRegistries.BLOCK) {
				if (!(block instanceof FlowerPotBlock pot)) continue;
				try {
					FlowerPotBlock empty = pot.getEmptyPot();
					if (empty == pot) continue;
					table.put(empty, pot.getPotted(), pot);
					entered++;
				} catch (Throwable unresolved) {
					Caught.expected(unresolved);
					failed++;   // one mod's unbound supplier must not cost every other pot its entry
				}
			}
			invalidateLegacyView();
			ForbricLog.info("[Forbric/FlowerPot] filled NeoForge's flower pot table with %d pot(s)%s — its bake callback "
					+ "never runs on the merged block registry, so every plant looked up air", entered,
					failed == 0 ? "" : " (" + failed + " pot(s) could not name their plant yet)");
			return entered;
		} catch (Throwable failure) {
			Caught.warn(failure, "[Forbric/FlowerPot] could not fill NeoForge's flower pot table");
			return -1;
		}
	}

	/** The (empty pot, id) → supplier view NeoForge derives lazily from the table; drop it so it re-derives. */
	private static void invalidateLegacyView() {
		try {
			Class<?> callbacks = Class.forName("net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$BlockCallbacks");
			Field lazy = callbacks.getDeclaredField("LEGACY_EMPTY_POT_AND_FLOWER_TO_FULL_POT_TABLE");
			lazy.setAccessible(true);
			Object value = lazy.get(null);
			value.getClass().getMethod("invalidate").invoke(value);
		} catch (Throwable absent) {
			Caught.warn(absent, "[Forbric/FlowerPot] NeoForge's legacy pot view could not be invalidated");
		}
	}
}
