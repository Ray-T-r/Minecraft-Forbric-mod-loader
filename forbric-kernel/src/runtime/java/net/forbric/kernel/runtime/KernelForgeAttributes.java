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

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraftforge.common.ForgeHooks;
import net.neoforged.neoforge.common.CommonHooks;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * The producer and the consumer of mod entity attributes, which the byte merge put on opposite sides.
 *
 * <p>Both ecosystems collect a mod's entity attributes the same way and into their own map:
 * {@code ForgeHooks.modifyAttributes()} posts {@code EntityAttributeCreationEvent} and fills
 * {@code ForgeHooks.FORGE_ATTRIBUTES}; {@code CommonHooks.modifyAttributes()} does the same for NeoForge. The
 * CONSUMER is vanilla's {@code DefaultAttributes}, and the merge kept exactly one of the two patches:
 * {@code javap} of the merged class shows both {@code getSupplier} and {@code hasSupplier} calling
 * {@code net.neoforged.neoforge.common.CommonHooks.getAttributesView()}, and the whole merged base names
 * {@code EntityAttributeCreationEvent} nowhere at all.
 *
 * <p>So a traditional MinecraftForge mod's attributes went into a map with no reader. The cost is not subtle:
 * {@code AttributeSupplier} is what gives a living entity its health and movement, and an entity without one is
 * rejected outright — {@code cursed_breeding} logged "Entity … has no attributes" 348 times in one boot and its
 * two mobs could not exist.
 *
 * <p>A VIEW rather than a copy, for the reason this project keeps rediscovering: both maps are themselves live
 * views that each ecosystem refills, and a snapshot taken at the wrong moment is a map that looks right and
 * answers with yesterday's content. {@code DefaultAttributes} only ever asks {@code get} and {@code containsKey}
 * of it, so the composition costs one extra map probe per miss and nothing else.
 */
public final class KernelForgeAttributes {

	private static volatile Map<EntityType<? extends LivingEntity>, AttributeSupplier> view;

	private KernelForgeAttributes() {
	}

	/**
	 * Posts MinecraftForge's own attribute events, next to the NeoForge ones the kernel already posts.
	 *
	 * <p>The kernel used to reach these through {@code GameData.postRegisterEvents()}, which NEVER RAN: its
	 * second instruction block is {@code new LinkedHashSet<>(GameData.vanillaRegistryOrder)} and that field is
	 * written only by {@code GameData.vanillaSnapshot()}, which the kernel deliberately does not call on the
	 * MinecraftForge side because it LOCKS the vanilla registry wrappers. So it threw NPE on every boot, before
	 * reaching anything, and the warning it produced named the symptom rather than the cause.
	 */
	public static void fireForgeAttributeEvents() {
		int before = size(forgeAttributes());
		try {
			ForgeHooks.modifyAttributes();
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Attributes] MinecraftForge's EntityAttributeCreationEvent did not finish — "
					+ "its mods' living entities have no attributes and cannot exist", Reflect.unwrap(t));
			return;
		}
		int after = size(forgeAttributes());
		ForbricLog.info("[Forbric/Attributes] posted MinecraftForge's entity-attribute events — %d entity type(s) "
				+ "got their attributes (%d before), next to NeoForge's %d. The merged DefaultAttributes reads "
				+ "only NeoForge's map, so the kernel serves it both",
				after, before, size(neoAttributes()));
	}

	/**
	 * What the merged {@code DefaultAttributes} asks instead of {@code CommonHooks.getAttributesView()}.
	 *
	 * <p>NeoForge's map is consulted first because it is the one the merged base was built to read; a key in both
	 * would mean the two ecosystems registered attributes for the same entity type, which cannot happen for a mod
	 * entity and is reported rather than silently resolved.
	 */
	public static Map<EntityType<? extends LivingEntity>, AttributeSupplier> attributesView() {
		Map<EntityType<? extends LivingEntity>, AttributeSupplier> cached = view;
		if (cached != null) return cached;
		Map<EntityType<? extends LivingEntity>, AttributeSupplier> neo = neoAttributes();
		Map<EntityType<? extends LivingEntity>, AttributeSupplier> forge = forgeAttributes();
		cached = forge == null ? neo : (neo == null ? forge : new Both(neo, forge));
		view = cached;
		return cached;
	}

	private static Map<EntityType<? extends LivingEntity>, AttributeSupplier> neoAttributes() {
		try {
			return CommonHooks.getAttributesView();
		} catch (Throwable t) {
			return null;
		}
	}

	private static Map<EntityType<? extends LivingEntity>, AttributeSupplier> forgeAttributes() {
		try {
			return ForgeHooks.getAttributesView();
		} catch (Throwable t) {
			return null;
		}
	}

	private static int size(Map<?, ?> map) {
		return map == null ? -1 : map.size();
	}

	/** Read-only composition of the two ecosystems' attribute maps, in that order. */
	private static final class Both extends AbstractMap<EntityType<? extends LivingEntity>, AttributeSupplier> {
		private final Map<EntityType<? extends LivingEntity>, AttributeSupplier> first;
		private final Map<EntityType<? extends LivingEntity>, AttributeSupplier> second;

		Both(Map<EntityType<? extends LivingEntity>, AttributeSupplier> first,
				Map<EntityType<? extends LivingEntity>, AttributeSupplier> second) {
			this.first = first;
			this.second = second;
		}

		@Override
		public AttributeSupplier get(Object key) {
			AttributeSupplier found = first.get(key);
			return found != null ? found : second.get(key);
		}

		@Override
		public boolean containsKey(Object key) {
			return first.containsKey(key) || second.containsKey(key);
		}

		@Override
		public int size() {
			return entrySet().size();
		}

		@Override
		public Set<Entry<EntityType<? extends LivingEntity>, AttributeSupplier>> entrySet() {
			// Built on demand, from whatever the two maps hold NOW — the composition is a view, and an iteration
			// that answered from a set captured at construction would be the snapshot this class exists to avoid.
			Map<EntityType<? extends LivingEntity>, AttributeSupplier> merged = new LinkedHashMap<>(second);
			merged.putAll(first);
			return merged.entrySet();
		}
	}
}
