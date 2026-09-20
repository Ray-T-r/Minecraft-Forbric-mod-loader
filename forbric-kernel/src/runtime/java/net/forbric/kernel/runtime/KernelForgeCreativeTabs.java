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

import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.common.ForgeHooks;
import net.neoforged.neoforge.event.EventHooks;

/** Composes the carriers' own generators, event dispatch and visibility merging. */
public final class KernelForgeCreativeTabs {
	private KernelForgeCreativeTabs() { }

	/** Same descriptor as both carriers' onCreativeModeTabBuildContents. */
	public static void buildContents(CreativeModeTab tab, CreativeModeTab.DisplayItemsGenerator generator,
			CreativeModeTab.ItemDisplayParameters parameters, CreativeModeTab.Output output) {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.forgeCreativeTabs", "on"))) {
			EventHooks.onCreativeModeTabBuildContents(tab, generator, parameters, output);
			return;
		}
		// Neo emits parent and search entries separately. Forge's native merge lambda combines matching stacks
		// into PARENT_AND_SEARCH_TABS, then its event can amend the result. Keep the Neo generator inside it:
		// its existing empty-stack tolerance still runs before anything reaches Forge's collector.
		ForgeHooks.onCreativeModeTabBuildContents(tab,
				(p, o) -> EventHooks.onCreativeModeTabBuildContents(tab, generator, p, o), parameters, output);
	}
}
