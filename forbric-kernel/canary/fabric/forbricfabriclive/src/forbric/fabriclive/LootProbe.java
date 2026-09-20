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

package forbric.fabriclive;

import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;

import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;

/**
 * Registers on fabric-loot-api-v3's three events, the way balm-fabric and instantfeedback do. Linked only when
 * {@link #install()} runs, so a canary staged without fabric-api never resolves these classes.
 */
final class LootProbe {
	private static final AtomicInteger REPLACE_ASKED = new AtomicInteger();

	private LootProbe() {
	}

	static void install() {
		LootTableEvents.REPLACE.register((key, table, source, provider) -> {
			REPLACE_ASKED.incrementAndGet();
			return null;    // decline: the table stays, and MODIFY must then see a copy of the ORIGINAL
		});
		LootTableEvents.MODIFY.register((key, builder, source, provider) -> {
			if ("minecraft:blocks/dirt".equals(key.identifier().toString())) {
				builder.withPool(LootPool.lootPool().add(LootItem.lootTableItem(Items.DIAMOND)));
				System.out.println("[ForbricFabricLive] LootTableEvents.MODIFY saw minecraft:blocks/dirt (source=" + source
						+ ") and added a pool");
			}
		});
		LootTableEvents.ALL_LOADED.register((resourceManager, registry) ->
				System.out.println("[ForbricFabricLive] LootTableEvents.ALL_LOADED: " + registry.size()
						+ " loot table(s), REPLACE was asked " + REPLACE_ASKED.get() + " time(s)"));
		System.out.println("[ForbricFabricLive] LootTableEvents listeners registered (REPLACE, MODIFY, ALL_LOADED)");
	}
}
