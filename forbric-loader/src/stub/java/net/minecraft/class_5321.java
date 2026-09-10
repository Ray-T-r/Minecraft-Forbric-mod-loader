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

/**
 * Compile-only stub for the intermediary game type {@code class_5321} (Mojmap {@code ResourceKey}). NOT
 * packaged or on the runtime classpath — the real game class provides this name at boot. The static factory
 * intermediary name {@code method_29179} ({@code ResourceKey.create(ResourceKey, Identifier)}) is verified
 * against the 1.21.11 mappings. Equality is by (registry, location) so a registry key and an object key
 * match the way the real game's interned keys do.
 */
public final class class_5321<T> {
	private final class_2960 registry; // the registry this key belongs to (null for a registry's own key)
	private final class_2960 location;

	private class_5321(class_2960 registry, class_2960 location) {
		this.registry = registry;
		this.location = location;
	}

	/** {@code ResourceKey.create(ResourceKey registryKey, Identifier location)}. */
	public static <T> class_5321<T> method_29179(class_5321<?> registryKey, class_2960 location) {
		return new class_5321<>(registryKey == null ? null : registryKey.location, location);
	}

	/** Test-only helper to mint a registry's own key (the real game uses {@code createRegistryKey}). */
	public static <T> class_5321<T> forRegistry(class_2960 location) {
		return new class_5321<>(null, location);
	}

	public class_2960 method_29177() { // location()
		return location;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof class_5321)) return false;
		class_5321<?> other = (class_5321<?>) o;
		return java.util.Objects.equals(registry, other.registry) && location.equals(other.location);
	}

	@Override
	public int hashCode() {
		return java.util.Objects.hashCode(registry) * 31 + location.hashCode();
	}

	@Override
	public String toString() {
		return "ResourceKey[" + (registry == null ? "" : registry + " / ") + location + "]";
	}
}
