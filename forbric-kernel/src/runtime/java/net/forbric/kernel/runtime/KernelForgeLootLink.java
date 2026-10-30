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

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.event.ForgeEventFactory;

/**
 * MinecraftForge's link in {@link KernelLootBridge}'s loot-table chain: its {@code LootTableLoadEvent}, posted between
 * NeoForge's hook and Fabric's for every table that survived NeoForge's.
 *
 * <p>Apart from the chain because it is the chain's only MinecraftForge name. The chain itself — NeoForge, then Fabric —
 * is the game's own route through {@code ReloadableServerRegistries} on any base and must not go wherever MinecraftForge
 * goes; this link goes in only when {@code GameEventMultiplexer} installs the {@code LOOT_TABLE_LOAD} bridge, which it
 * does only where MinecraftForge is present and the bridges are on.
 */
public final class KernelForgeLootLink {
	private KernelForgeLootLink() {
	}

	/** Puts MinecraftForge's {@code LootTableLoadEvent} into the chain. Called once, with the other bridges. */
	public static void install() {
		KernelLootBridge.link(KernelForgeLootLink::onLoad);
	}

	/**
	 * MinecraftForge's answer for one table NeoForge kept: the table its listeners leave, or null when one cancelled the
	 * load, which drops the table — the same answer NeoForge's own hook gives for a cancelled event.
	 */
	static LootTable onLoad(Identifier id, LootTable table) {
		return ForgeEventFactory.onLoadLootTable(id, table);
	}
}
