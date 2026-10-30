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

import java.util.function.BiFunction;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.storage.loot.LootTable;

import net.forbric.kernel.boot.LootTableEventDispatch;
import net.neoforged.neoforge.event.EventHooks;

/**
 * The two calls {@code LootTableEventBridgeInjector} routes out of {@code ReloadableServerRegistries}, typed.
 *
 * <p>Same names and descriptors as the callees they replace, so the injector swaps only the owner. NeoForge's
 * {@link EventHooks#loadLootTable} runs first and untouched — {@code LootTable.EMPTY} and a cancelled event both
 * answer {@code null}, which drops the table exactly as before — and only a survivor is offered to Fabric.
 *
 * <p>MinecraftForge's {@code LootTableLoadEvent} is named nowhere in the merged base, so it is posted HERE, in
 * the seam it belongs at. The chain is NeoForge, then MinecraftForge, then Fabric — the same order every other
 * bridge uses, with the surviving hook's listeners first — and each link may drop the table or replace it, which
 * is what those mods are for: a loot mod that adds to or replaces a table on load had no effect at all before.
 *
 * <p>MinecraftForge's link is its own class, {@link KernelForgeLootLink}, registered as
 * {@link net.forbric.api.GameEventBridge#LOOT_TABLE_LOAD} rather than simply called, so {@code -Dforbric.unifiedEvents=off}
 * leaves the old behaviour and the dead-event audit keeps naming a waiting mod when the link is not there. This class
 * names nothing of MinecraftForge: the NeoForge-then-Fabric chain runs whether or not the link was put in, on a base with
 * no MinecraftForge at all as on today's.
 */
public final class KernelLootBridge {
	/**
	 * MinecraftForge's link in the chain, or null when it is not there.
	 *
	 * <p>Set by {@link KernelForgeLootLink#install()} rather than read from the carrier's presence: the chain runs on
	 * every loaded table, on a worker thread, long after boot, and a per-table {@code Class.forName} in a
	 * {@code catch} is both slower and quieter than one decision made once at the point the rest of the bridges
	 * are installed.
	 */
	private static volatile BiFunction<Identifier, LootTable, LootTable> forgeLink;

	private KernelLootBridge() {
	}

	/** Puts MinecraftForge's link into the chain, between NeoForge's hook and Fabric's. Called once, by that link. */
	static void link(BiFunction<Identifier, LootTable, LootTable> link) {
		forgeLink = link;
	}

	/** {@code ReloadableServerRegistries.lambda$scheduleRegistryLoad$1}'s call, for every loaded table. */
	public static LootTable loadLootTable(HolderLookup.Provider provider, Identifier id, LootTable table) {
		LootTable neo = EventHooks.loadLootTable(provider, id, table);
		if (neo == null) return null;
		BiFunction<Identifier, LootTable, LootTable> link = forgeLink;
		LootTable forge = link == null ? neo : link.apply(id, neo);
		// null means a MinecraftForge mod cancelled the load, which drops the table — the same answer NeoForge's
		// own hook gives for a cancelled event, so the caller needs no new case.
		if (forge == null) return null;
		Object fabric = LootTableEventDispatch.afterLoad(provider, ResourceKey.create(Registries.LOOT_TABLE, id), id, forge);
		return fabric instanceof LootTable result ? result : forge;
	}

	/** {@code ReloadableServerRegistries.lambda$scheduleRegistryLoad$0}'s call, once per reloadable registry. */
	public static void loadTagsForRegistry(ResourceManager resources, WritableRegistry<?> registry) {
		TagLoader.loadTagsForRegistry(resources, registry);
		if (Registries.LOOT_TABLE.equals(registry.key())) {
			LootTableEventDispatch.allLoaded(resources, registry);
		}
	}
}
