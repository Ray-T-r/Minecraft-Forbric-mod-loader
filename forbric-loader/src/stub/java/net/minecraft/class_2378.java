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

package net.minecraft;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compile-only stub for the intermediary game type {@code class_2378} (Mojmap {@code Registry}). NOT packaged
 * or on the runtime classpath — the real game class provides this name at boot. It is declared as an
 * <b>interface</b> because the real {@code Registry} is one: the static registration intermediary
 * {@code method_39197} ({@code Registry.register(Registry, ResourceKey, T)}, verified against the 1.21.11
 * mappings) is an interface static, so call sites must emit an {@code InterfaceMethodref} — which only happens
 * if this stub is an interface. {@code method_30517} stands in for the inherited {@code key()} the shim
 * resolves reflectively. The nested {@link Fake} + {@link #forbricTestRegistry} exist only so unit tests can
 * observe registrations without a game.
 */
public interface class_2378<T> {
	/** {@code Registry.register(Registry, ResourceKey, T)} (static; interface static). */
	@SuppressWarnings("unchecked")
	static <V> V method_39197(class_2378<?> registry, class_5321<?> key, V value) {
		((Fake<Object>) registry).map.put(key, value); // unit-test path only; unused at runtime (stub absent)
		return value;
	}

	/** Stand-in for the inherited {@code Registry.key()} (no-arg, returns a ResourceKey). */
	class_5321<?> method_30517();

	int method_10204();                       // size()

	boolean method_10250(class_5321<?> key);  // containsKey(ResourceKey)

	Object method_29107(class_5321<?> key);   // getValue(ResourceKey) (test helper)

	/** Test-only concrete registry (never instantiated at runtime; the stub is compile-only). */
	static <T> class_2378<T> forbricTestRegistry(class_5321<?> key) {
		return new Fake<>(key);
	}

	final class Fake<T> implements class_2378<T> {
		final class_5321<?> key;
		final Map<class_5321<?>, Object> map = new LinkedHashMap<>();

		Fake(class_5321<?> key) {
			this.key = key;
		}

		@Override public class_5321<?> method_30517() { return key; }
		@Override public int method_10204() { return map.size(); }
		@Override public boolean method_10250(class_5321<?> k) { return map.containsKey(k); }
		@Override public Object method_29107(class_5321<?> k) { return map.get(k); }
	}
}
