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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;

/**
 * Vanilla's {@code KeyMapping.MAP} — every bound key to the mappings bound to it — as a read-only view of the
 * mappings by name, which the merged {@code KeyMapping} still keeps as vanilla's {@code ALL}.
 *
 * <p>Both ecosystems re-type vanilla's {@code MAP:Ljava/util/Map;} to their own {@code KeyMappingLookup}, and the
 * merged class keeps their two and not vanilla's. A Fabric mod that reads {@code KeyMapping.MAP} as a {@code Map}
 * (LiquidBounce's inventory movement, on every key press in a screen) died on {@code NoSuchFieldError}. The field is
 * given back as this view: what vanilla keeps in it, grouped from the same mappings, keyed by each one's current key.
 *
 * <p>A view rather than a copy: the mappings and their keys change as the player rebinds them, and vanilla rebuilds
 * its map from {@code ALL} each time ({@code resetMapping}); grouping on every read gives the same answer without a
 * second structure to keep in step. Writes are refused — nothing in the merged game writes vanilla's map, and a
 * write that went nowhere would be worse than one that throws.
 */
public final class KernelKeyMappingMap {

	private KernelKeyMappingMap() {
	}

	/**
	 * The view over {@code all}, vanilla's {@code KeyMapping.ALL}. Declared in JDK types, as every seam here is, so
	 * {@code KernelRuntimeClasses} can state it; the generated caller casts.
	 */
	public static Object vanillaView(Map<?, ?> all) {
		@SuppressWarnings("unchecked")
		Map<String, KeyMapping> byName = (Map<String, KeyMapping>) all;
		return new ByKey(byName);
	}

	static final class ByKey extends AbstractMap<InputConstants.Key, List<KeyMapping>> {
		private final Map<String, KeyMapping> byName;

		ByKey(Map<String, KeyMapping> byName) {
			this.byName = byName;
		}

		/** The mappings bound to {@code key}, or null when none is — vanilla's map has no entry for an unbound key. */
		@Override
		public List<KeyMapping> get(Object key) {
			if (!(key instanceof InputConstants.Key wanted)) return null;
			List<KeyMapping> bound = null;
			for (KeyMapping mapping : byName.values()) {
				if (!wanted.equals(mapping.getKey())) continue;
				if (bound == null) bound = new ArrayList<>();
				bound.add(mapping);
			}
			return bound;
		}

		@Override
		public boolean containsKey(Object key) {
			return get(key) != null;
		}

		@Override
		public Set<Map.Entry<InputConstants.Key, List<KeyMapping>>> entrySet() {
			Map<InputConstants.Key, List<KeyMapping>> grouped = new LinkedHashMap<>();
			for (KeyMapping mapping : byName.values()) {
				grouped.computeIfAbsent(mapping.getKey(), k -> new ArrayList<>()).add(mapping);
			}
			return Collections.unmodifiableMap(grouped).entrySet();
		}
	}
}
