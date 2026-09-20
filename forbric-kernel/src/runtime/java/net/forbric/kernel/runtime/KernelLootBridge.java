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
 * <p>MinecraftForge's {@code LootTableLoadEvent} ({@code ForgeEventFactory.onLoadLootTable(Identifier, LootTable)})
 * is named nowhere in the merged base and stays dead; this is the seam it would belong at. It is listed in
 * {@code DeadEventAudit} so a Forge mod listening for it is named.
 */
public final class KernelLootBridge {
	private KernelLootBridge() {
	}

	/** {@code ReloadableServerRegistries.lambda$scheduleRegistryLoad$1}'s call, for every loaded table. */
	public static LootTable loadLootTable(HolderLookup.Provider provider, Identifier id, LootTable table) {
		LootTable neo = EventHooks.loadLootTable(provider, id, table);
		if (neo == null) return null;
		Object fabric = LootTableEventDispatch.afterLoad(provider, ResourceKey.create(Registries.LOOT_TABLE, id), id, neo);
		return fabric instanceof LootTable result ? result : neo;
	}

	/** {@code ReloadableServerRegistries.lambda$scheduleRegistryLoad$0}'s call, once per reloadable registry. */
	public static void loadTagsForRegistry(ResourceManager resources, WritableRegistry<?> registry) {
		TagLoader.loadTagsForRegistry(resources, registry);
		if (Registries.LOOT_TABLE.equals(registry.key())) {
			LootTableEventDispatch.allLoaded(resources, registry);
		}
	}
}
