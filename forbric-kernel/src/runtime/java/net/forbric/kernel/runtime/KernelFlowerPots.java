/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Supplier;

import com.google.common.collect.Table;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.neoforged.neoforge.registries.GameData;

/**
 * Which full pot a plant makes, for every family's way of declaring one (FlowerPotRepairInjector).
 *
 * <p>The merged {@code FlowerPotBlock.useItemOn} is MinecraftForge's body: it looks the plant up in the empty pot's
 * {@code fullPots} map, which only MinecraftForge's constructor and {@code addPlant} ever filled, and the merge kept
 * NeoForge's bodies of both. NeoForge declares a pot by its constructor (empty pot + plant) and keeps them in
 * {@code GameData}'s pot table, which its registry bake callback fills — and that callback never runs on the merged
 * base, where the block registry is MinecraftForge's wrapper. So every lookup answered air.
 *
 * <p>The lookup now asks, in order: the empty pot's explicit {@code addPlant} entries (MinecraftForge's API), then
 * NeoForge's table, which {@link #rebuildTable} fills from every registered pot the way NeoForge's bake does. A
 * vanilla or Fabric pot is in that table too: NeoForge's (Block, Properties) constructor names the vanilla empty pot.
 */
public final class KernelFlowerPots {
	private static volatile boolean warned;

	private KernelFlowerPots() {
	}

	/** The full pot {@code content} makes in {@code self}'s empty pot, or air. Never throws. */
	public static Block fullPotFor(Map<?, ?> explicit, FlowerPotBlock self, Block content) {
		try {
			if (explicit != null && !explicit.isEmpty()) {
				Identifier key = BuiltInRegistries.BLOCK.getKey(content);
				// An unregistered block answers the default key (air); that is not a request for the air slot.
				boolean aliased = content != Blocks.AIR && key.equals(BuiltInRegistries.BLOCK.getDefaultKey());
				if (!aliased && explicit.get(key) instanceof Supplier<?> supplier && supplier.get() instanceof Block full) {
					return full;
				}
			}
			Block full = GameData.getFlowerPotBlockTable().get(self.getEmptyPot(), content);
			return full != null ? full : Blocks.AIR;
		} catch (Throwable failure) {
			if (!warned) {
				warned = true;
				ForbricLog.warn("[Forbric/FlowerPot] could not look up the full pot for %s (%s); the pot stays empty",
						content, Reflect.unwrap(failure));
			}
			return Blocks.AIR;
		}
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
					failed++;   // one mod's unbound supplier must not cost every other pot its entry
				}
			}
			invalidateLegacyView();
			ForbricLog.info("[Forbric/FlowerPot] filled NeoForge's flower pot table with %d pot(s)%s — its bake callback "
					+ "never runs on the merged block registry, so every plant looked up air", entered,
					failed == 0 ? "" : " (" + failed + " pot(s) could not name their plant yet)");
			return entered;
		} catch (Throwable failure) {
			ForbricLog.warn("[Forbric/FlowerPot] could not fill NeoForge's flower pot table", Reflect.unwrap(failure));
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
			ForbricLog.debug("[Forbric/FlowerPot] NeoForge's legacy pot view could not be invalidated: %s", absent);
		}
	}
}
