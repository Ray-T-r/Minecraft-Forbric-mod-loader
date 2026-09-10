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

package net.forbric.loader.impl.forge.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Keeps Forge's client model-data lifecycle invariant intact on the Forge+Neo merged client.
 */
public final class ForbricClientModelDataBridge {
	private static final Map<Object, Object> FORGE_MODEL_DATA_MANAGERS =
			Collections.synchronizedMap(new WeakHashMap<>());
	private static volatile boolean loggedInit;

	private ForbricClientModelDataBridge() {
	}

	public static Object ensureForgeModelDataManager(Object clientLevel, Object cached, ClassLoader cl) {
		if (clientLevel == null || !isMergedForgeNeoBase(cl)) return cached;
		try {
			Class<?> forgeManagerCls =
					Class.forName("net.minecraftforge.client.model.data.ModelDataManager", false, cl);
			if (forgeManagerCls.isInstance(cached)) return cached;

			Object remembered = FORGE_MODEL_DATA_MANAGERS.get(clientLevel);
			if (forgeManagerCls.isInstance(remembered)) return remembered;

			Object existing = findFieldValue(clientLevel, forgeManagerCls);
			if (existing != null) {
				FORGE_MODEL_DATA_MANAGERS.put(clientLevel, existing);
				return existing;
			}

			Class<?> levelCls = Class.forName("net.minecraft.world.level.Level", false, cl);
			Constructor<?> ctor = forgeManagerCls.getConstructor(levelCls);
			Object created = ctor.newInstance(clientLevel);
			writeNullFields(clientLevel, forgeManagerCls, created);
			FORGE_MODEL_DATA_MANAGERS.put(clientLevel, created);
			if (!loggedInit) {
				loggedInit = true;
				ForbricLog.info("[Forbric/ClientModelData] initialized Forge model-data manager on merged ClientLevel");
			}
			return created;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ClientModelData] could not initialize Forge model-data manager", t);
			return cached;
		}
	}

	private static Object findFieldValue(Object owner, Class<?> type) throws IllegalAccessException {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (!type.isAssignableFrom(field.getType())) continue;
				field.setAccessible(true);
				Object value = field.get(owner);
				if (value != null) return value;
			}
		}
		return null;
	}

	private static void writeNullFields(Object owner, Class<?> type, Object value) {
		for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (!type.isAssignableFrom(field.getType())) continue;
				try {
					field.setAccessible(true);
					if (field.get(owner) == null) field.set(owner, value);
				} catch (ReflectiveOperationException | RuntimeException ignored) {
					// Some transformed fields may be final or otherwise unavailable; the injected getter still caches.
				}
			}
		}
	}

	private static boolean isMergedForgeNeoBase(ClassLoader cl) {
		try {
			Class.forName("net.minecraftforge.registries.NamespacedWrapper", false, cl);
			Class.forName("net.neoforged.neoforge.network.registration.NetworkRegistry", false, cl);
			Class.forName("net.neoforged.neoforge.model.data.ModelDataManager", false, cl);
			Class.forName("net.minecraftforge.client.model.data.ModelDataManager", false, cl);
			return true;
		} catch (Throwable ignored) {
			return false;
		}
	}
}
