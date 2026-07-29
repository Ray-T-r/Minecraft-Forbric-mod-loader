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

package net.forbric.loader.impl.forge.bridge;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tri-in-one Fabric-mod compat: defensively guarantees fabric-resource-loader's {@code parentsPredicate} field
 * (added to {@code net.minecraft.server.packs.repository.Pack} by its {@code PackMixin}) is non-null on every
 * {@code Pack} instance. On the MERGED base, NeoForge's {@code Pack} carries an extra private constructor for its
 * {@code withChildren}/{@code hidden} parent-pack feature; empirically some {@code Pack} instances reach Fabric's
 * {@code fabric$parentsEnabled} with a null {@code parentsPredicate} (NPE in {@code configurePackRepository},
 * before world load) despite the mixin's field initializer — a subtle interaction between the two-constructor
 * NeoForge shape and Sponge Mixin's initializer placement. This seeds the default ({@code s -> true}, matching
 * fabric-resource-loader's {@code DEFAULT_PARENT_PREDICATE}) whenever it is still null, so a pack with no explicit
 * parent predicate behaves exactly as on a single-ecosystem base.
 *
 * <p>Reflection-only (the field is added by another mod's mixin, unknown at compile time); a no-op wherever the
 * field is absent (no fabric-resource-loader) or already set (single-ecosystem bases — hence never a regression).
 */
public final class ForbricFabricPackCompat {
	private ForbricFabricPackCompat() {
	}

	private static final Predicate<Set<String>> DEFAULT = s -> true;
	private static volatile Field parentsField;
	private static volatile boolean resolved;

	/**
	 * Seeds every available pack of a {@code PackRepository} so none reaches fabric-resource-loader's
	 * {@code fabric$parentsEnabled} with a null predicate. Called at the HEAD of {@code rebuildSelected} (right
	 * before Fabric's {@code handleAutoEnableDisable} runs), which sidesteps the merged-base construction paths
	 * that empirically leave the ctor initializer unrun. Reflection-only; a no-op off the merged/Fabric base.
	 */
	public static void seedRepository(Object packRepository) {
		if (packRepository == null) return;
		try {
			Object packs = packRepository.getClass().getMethod("getAvailablePacks").invoke(packRepository);
			if (packs instanceof Iterable<?> it) {
				int seeded = 0;
				for (Object pack : it) if (ensureParentsPredicate(pack)) seeded++;
				if (seeded > 0) {
					net.forbric.loader.impl.util.ForbricLog.info("[Forbric/FabricPackCompat] seeded parentsPredicate "
							+ "on " + seeded + " pack(s) missing fabric-resource-loader's default (merged-base compat)");
				}
			}
		} catch (Throwable ignore) {
			// getAvailablePacks missing/failed — nothing to seed.
		}
	}

	/**
	 * Ensures {@code pack.parentsPredicate} is non-null, seeding fabric-resource-loader's default when it isn't.
	 * Returns {@code true} if it actually seeded (was null), {@code false} otherwise.
	 */
	public static boolean ensureParentsPredicate(Object pack) {
		if (pack == null) return false;
		try {
			Field f = parentsField;
			if (f == null) {
				if (resolved) return false; // already determined the field isn't present
				synchronized (ForbricFabricPackCompat.class) {
					if (parentsField == null && !resolved) {
						resolved = true;
						parentsField = findField(pack.getClass(), "parentsPredicate"); // null if no fabric-resource-loader
						if (parentsField != null) parentsField.setAccessible(true);
					}
					f = parentsField;
				}
				if (f == null) return false;
			}
			if (f.get(pack) == null) {
				f.set(pack, DEFAULT);
				return true;
			}
		} catch (Throwable ignore) {
			// Never let a compat shim abort pack loading.
		}
		return false;
	}

	private static Field findField(Class<?> c, String name) {
		for (; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignore) {
				// try superclass
			}
		}
		return null;
	}
}
