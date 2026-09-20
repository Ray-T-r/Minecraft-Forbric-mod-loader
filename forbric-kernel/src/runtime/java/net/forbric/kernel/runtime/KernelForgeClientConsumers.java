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

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.ImmutableMap;

import net.minecraft.client.color.block.BlockTintCache;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.worldselection.PresetEditor;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

import net.forbric.kernel.util.ForbricLog;

/** Typed consumers of the tables populated by the two carriers' genuine client registration hooks. */
public final class KernelForgeClientConsumers {
	private static final Set<String> REPORTED_PRESET_CONFLICTS = ConcurrentHashMap.newKeySet();

	private KernelForgeClientConsumers() {}

	public static void loadLayerDefinitions(ImmutableMap.Builder<ModelLayerLocation, LayerDefinition> builder) {
		if (!ForgeClientConsumerFlow.enabled()) {
			net.neoforged.neoforge.client.ClientHooks.loadLayerDefinitions(builder);
			return;
		}
		ForgeClientConsumerFlow.appendBoth(builder, net.neoforged.neoforge.client.ClientHooks::loadLayerDefinitions,
				net.minecraftforge.client.ForgeHooksClient::loadLayerDefinitions);
	}

	public static ClientTooltipComponent createClientTooltipComponent(TooltipComponent component) {
		if (!ForgeClientConsumerFlow.enabled()) {
			return net.neoforged.neoforge.client.gui.ClientTooltipComponentManager.createClientTooltipComponent(component);
		}
		return ForgeClientConsumerFlow.tooltip(
				() -> net.neoforged.neoforge.client.gui.ClientTooltipComponentManager.createClientTooltipComponent(component),
				() -> net.minecraftforge.client.gui.ClientTooltipComponentManager.createClientTooltipComponent(component));
	}

	public static PresetEditor getPresetEditor(ResourceKey<WorldPreset> key) {
		PresetEditor neo = net.neoforged.neoforge.client.PresetEditorManager.get(key);
		if (!ForgeClientConsumerFlow.enabled()) return neo;
		PresetEditor forge = net.minecraftforge.client.PresetEditorManager.get(key);
		PresetEditor vanilla = PresetEditor.EDITORS.get(Optional.ofNullable(key));
		return ForgeClientConsumerFlow.preset(vanilla, neo, forge, () -> {
			String id = String.valueOf(key);
			if (REPORTED_PRESET_CONFLICTS.add(id)) {
				ForbricLog.warn("[Forbric/ClientConsumers] both Forge families registered different custom preset "
						+ "editors for %s; using the NeoForge editor (Neo=%s, Forge=%s)", id,
						neo.getClass().getName(), forge.getClass().getName());
			}
		});
	}

	public static void registerBlockTintCaches(ClientLevel level, Map<ColorResolver, BlockTintCache> caches) {
		if (!ForgeClientConsumerFlow.enabled()) {
			net.neoforged.neoforge.client.ColorResolverManager.registerBlockTintCaches(level, caches);
			return;
		}
		ForgeClientConsumerFlow.appendBoth(caches,
				map -> net.neoforged.neoforge.client.ColorResolverManager.registerBlockTintCaches(level, map),
				map -> net.minecraftforge.client.ColorResolverManager.registerBlockTintCaches(level, map));
	}
}
