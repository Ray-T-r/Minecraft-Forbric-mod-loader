/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacementType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;

/** Runs Forge's spawn registration before NeoForge, whose original merged lambda still writes the final table. */
public final class KernelForgeSpawnPlacements {
	private KernelForgeSpawnPlacements() { }

	/** Same descriptor as NeoForge ModLoader.postEvent; an already transformed caller can also be switched off. */
	public static void postBothFamilies(Event event) {
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.forgeSpawnPlacements", "on"))
				&& event instanceof RegisterSpawnPlacementsEvent registration) {
			try {
				Bridge.apply(registration);
			} catch (Throwable failure) {
				ForbricLog.warn("[Forbric/SpawnPlacements] could not bridge MinecraftForge spawn registration — "
						+ "NeoForge registration continues with its existing entries", failure);
			}
		}
		ModLoader.postEvent((Event & IModBusEvent) event);
	}

	// Lazy: the off path must not resolve Forge or reflect into its fields.
	private static final class Bridge {
		private static final Shape SHAPE = new Shape();

		@SuppressWarnings("unchecked")
		static void apply(RegisterSpawnPlacementsEvent event) throws ReflectiveOperationException {
			Map<EntityType<?>, ?> vanilla = (Map<EntityType<?>, ?>) SHAPE.vanilla.get(null);
			Map<EntityType<?>, RegisterSpawnPlacementsEvent.MergedSpawnPredicate<?>> neo =
					(Map<EntityType<?>, RegisterSpawnPlacementsEvent.MergedSpawnPredicate<?>>) SHAPE.neoMap.get(event);
			Map<EntityType<?>, Original> originals = new LinkedHashMap<>();
			Map<EntityType<?>, SpawnPlacementRegisterEvent.MergedSpawnPredicate<?>> forge = new LinkedHashMap<>();
			for (var entry : vanilla.entrySet()) {
				Object data = entry.getValue();
				Original original = new Original((SpawnPlacements.SpawnPredicate<?>) SHAPE.dataPredicate.get(data),
						(SpawnPlacementType) SHAPE.dataPlacement.get(data), (Heightmap.Types) SHAPE.dataHeightmap.get(data));
				originals.put(entry.getKey(), original);
				forge.put(entry.getKey(), new SpawnPlacementRegisterEvent.MergedSpawnPredicate<>(
						original.predicate(), original.placement(), original.heightmap()));
			}

			SpawnPlacementRegisterEvent.BUS.post(new SpawnPlacementRegisterEvent(forge));
			// Prepare all replacements before touching the Neo event. A Forge listener/build failure leaves the
			// original Neo map intact, and the outer method still posts Neo exactly once.
			Map<EntityType<?>, RegisterSpawnPlacementsEvent.MergedSpawnPredicate<?>> replacements = new LinkedHashMap<>();
			for (var entry : forge.entrySet()) {
				var value = entry.getValue();
				if (!changed(value, originals.get(entry.getKey()))) continue;
				// Do not use neo.register(..., REPLACE): both carriers' build() returns replacementPredicate before
				// considering OR/AND. Forge's completed result is the ORIGINAL of a fresh Neo builder so later
				// Neo listeners can still add OR/AND rules. Unchanged entries retain the original builder identity.
				replacements.put(entry.getKey(), new RegisterSpawnPlacementsEvent.MergedSpawnPredicate<>(
						value.build(), value.getSpawnType(), value.getHeightmapType()));
			}
			neo.putAll(replacements);
			ForbricLog.info("[Forbric/SpawnPlacements] applied %d MinecraftForge spawn placement change(s) "
					+ "before NeoForge registration", replacements.size());
		}

		private static boolean changed(SpawnPlacementRegisterEvent.MergedSpawnPredicate<?> value, Original original)
				throws IllegalAccessException {
			if (original == null || value.getSpawnType() != original.placement()
					|| value.getHeightmapType() != original.heightmap()) return true;
			Object replacement = SHAPE.replacement.get(value);
			if (replacement != null) return replacement != original.predicate();
			return SHAPE.original.get(value) != original.predicate()
					|| !((List<?>) SHAPE.or.get(value)).isEmpty() || !((List<?>) SHAPE.and.get(value)).isEmpty();
		}

		private record Original(SpawnPlacements.SpawnPredicate<?> predicate, SpawnPlacementType placement,
				Heightmap.Types heightmap) { }

		private static final class Shape {
			final Field vanilla, neoMap, dataPredicate, dataPlacement, dataHeightmap, original, replacement, or, and;

			Shape() {
				try {
					Class<?> data = Class.forName(SpawnPlacements.class.getName() + "$Data", false,
							SpawnPlacements.class.getClassLoader());
					vanilla = field(SpawnPlacements.class, "DATA_BY_TYPE", Map.class, true);
					neoMap = field(RegisterSpawnPlacementsEvent.class, "map", Map.class, false);
					dataPredicate = field(data, "predicate", SpawnPlacements.SpawnPredicate.class, false);
					dataPlacement = field(data, "placement", SpawnPlacementType.class, false);
					dataHeightmap = field(data, "heightMap", Heightmap.Types.class, false);
					Class<?> merged = SpawnPlacementRegisterEvent.MergedSpawnPredicate.class;
					original = field(merged, "originalPredicate", SpawnPlacements.SpawnPredicate.class, false);
					replacement = field(merged, "replacementPredicate", SpawnPlacements.SpawnPredicate.class, false);
					or = field(merged, "orPredicates", List.class, false);
					and = field(merged, "andPredicates", List.class, false);
				} catch (ReflectiveOperationException failure) {
					throw new IllegalStateException("spawn registration carrier shape changed", failure);
				}
			}

			private static Field field(Class<?> owner, String name, Class<?> type, boolean isStatic)
					throws NoSuchFieldException {
				Field field = owner.getDeclaredField(name);
				if (field.getType() != type || Modifier.isStatic(field.getModifiers()) != isStatic)
					throw new IllegalStateException("unexpected spawn field: " + field);
				field.setAccessible(true);
				return field;
			}
		}
	}
}
