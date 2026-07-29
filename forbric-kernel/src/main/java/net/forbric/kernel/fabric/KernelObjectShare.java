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

package net.forbric.kernel.fabric;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import net.fabricmc.loader.api.ObjectShare;

/**
 * The kernel's {@link ObjectShare}: a thread-safe key/value channel for inter-mod communication.
 *
 * <p>{@code whenAvailable} callbacks are held until a matching {@code put}, then fired exactly once. The
 * pending-callback map is consulted under the same lock as the write so a value published concurrently with a
 * registration cannot be missed nor delivered twice.
 */
public final class KernelObjectShare implements ObjectShare {
	private final Map<String, Object> values = new ConcurrentHashMap<>();
	private final Map<String, List<BiConsumer<String, Object>>> pending = new ConcurrentHashMap<>();

	@Override
	public Object get(String key) {
		validateKey(key);

		return values.get(key);
	}

	@Override
	public void whenAvailable(String key, BiConsumer<String, Object> consumer) {
		validateKey(key);

		Object value;

		synchronized (this) {
			value = values.get(key);

			if (value == null) {
				pending.computeIfAbsent(key, k -> new ArrayList<>()).add(consumer);
				return;
			}
		}

		consumer.accept(key, value);
	}

	@Override
	public Object put(String key, Object value) {
		validateKey(key);
		if (value == null) throw new NullPointerException("null value for " + key);

		Object prev;
		List<BiConsumer<String, Object>> waiting;

		synchronized (this) {
			prev = values.put(key, value);
			waiting = pending.remove(key);
		}

		fire(waiting, key, value);
		return prev;
	}

	@Override
	public Object putIfAbsent(String key, Object value) {
		validateKey(key);
		if (value == null) throw new NullPointerException("null value for " + key);

		Object prev;
		List<BiConsumer<String, Object>> waiting = null;

		synchronized (this) {
			prev = values.putIfAbsent(key, value);
			if (prev == null) waiting = pending.remove(key);
		}

		if (prev == null) fire(waiting, key, value);
		return prev;
	}

	@Override
	public Object remove(String key) {
		validateKey(key);

		synchronized (this) {
			return values.remove(key);
		}
	}

	private static void fire(List<BiConsumer<String, Object>> waiting, String key, Object value) {
		if (waiting == null) return;

		for (BiConsumer<String, Object> consumer : waiting) {
			consumer.accept(key, value);
		}
	}

	private static void validateKey(String key) {
		if (key == null) throw new NullPointerException("null key");

		int sep = key.indexOf(':');
		if (sep <= 0 || sep >= key.length() - 1) {
			throw new IllegalArgumentException("invalid object share key '" + key + "', must be modid:subkey");
		}
	}
}
