/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.minecraftforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.GameData;

/**
 * The game-side half of {@code BlockItemLinkInjector}: MinecraftForge's live item registry keeps its block→item map in
 * {@code Item.BY_BLOCK}, the map {@code Block.asItem()} reads, as it does on MinecraftForge itself.
 *
 * <p>On MinecraftForge, {@code Item.BY_BLOCK} IS the {@code block_to_item} map of the item registry in
 * {@code RegistryManager.ACTIVE}: {@code Item.<clinit>} takes it from {@code GameData$ItemCallbacks.getBlockItemMap()}, and
 * the item add-callback links every {@code BlockItem} into it as the registry adds it — inside {@code Bootstrap} for the
 * builtin ones, which is why MinecraftForge's {@code Items.registerItem} has no {@code registerBlocks} call of its own.
 * The merged {@code Item} is NeoForge's, which takes {@code BY_BLOCK} from NeoForge's {@code GameData.getBlockItemMap()}
 * instead, so the add-callback filled a map nothing read. {@link #blockToItemMap} hands the ACTIVE item registry that
 * same NeoForge map when the registry is created; every other stage (FROZEN, VANILLA, a staging copy) keeps the map
 * MinecraftForge made for it.
 *
 * <p>MinecraftForge empties the map when it re-syncs its live registry — world close ({@code revertToFrozen}), loading
 * a save's MinecraftForge ids and a server's ids ({@code injectSnapshot}) — and re-adds every entry, which relinks
 * each block item. Shared, that clear would also drop what other code put into {@code BY_BLOCK} by hand (fabric-api's
 * {@code BlockItemTracker} goes through {@code registerBlocks}, but a mod may {@code put} directly), which Fabric never
 * clears and NeoForge clears only for a full revert. So {@link #clear} leaves the shared map alone and empties every other.
 * That keeps a hand-put entry only for a block no registered {@code BlockItem} claims: the re-adds still overwrite a
 * remap of a registered block's item, as they do on MinecraftForge.
 *
 * <p>Both methods take and return {@code Object}: verifying this class at boot must not load a game class.
 */
public final class KernelBlockItemLinks {
	private static final AtomicBoolean SHARED = new AtomicBoolean();
	private static final AtomicBoolean KEPT = new AtomicBoolean();

	private KernelBlockItemLinks() {
	}

	/**
	 * {@code GameData$ItemCallbacks.onCreate}: the block→item map a new MinecraftForge item registry gets — the one
	 * {@code Item.BY_BLOCK} is for the live registry, {@code created} (the map MinecraftForge built) for any other stage.
	 */
	public static Object blockToItemMap(Object created, Object stage) {
		if (stage != RegistryManager.ACTIVE) return created;
		Object shared = GameData.getBlockItemMap();
		if (SHARED.compareAndSet(false, true)) {
			ForbricLog.info("[Forbric/Registries] MinecraftForge's item registry links block items into Item.BY_BLOCK "
					+ "(the map Block.asItem() reads) as it adds them, as on MinecraftForge (-Dforbric.blockItemLinks=off "
					+ "to leave it its own map)");
		}
		return shared;
	}

	/** {@code GameData$ItemCallbacks.onClear}: empties {@code map}, unless it is the shared {@code Item.BY_BLOCK}. */
	public static void clear(Object map, Object stage) {
		if (stage == RegistryManager.ACTIVE && map == GameData.getBlockItemMap()) {
			if (KEPT.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Registries] MinecraftForge re-synced its item registry; Item.BY_BLOCK was kept, "
						+ "not emptied, and the re-added block items relink into it");
			}
			return;
		}
		((Map<?, ?>) map).clear();
	}
}
