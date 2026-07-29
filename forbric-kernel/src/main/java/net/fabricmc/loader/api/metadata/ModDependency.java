/*
 * Copyright 2016 FabricMC
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

package net.fabricmc.loader.api.metadata;

import java.util.HashMap;
import java.util.Map;

import net.fabricmc.loader.api.Version;

/**
 * A dependency declared by a mod.
 *
 * <p><b>Forbric v1 deviation.</b> Upstream also exposes {@code getVersionRequirements()} (a
 * {@code Collection<VersionPredicate>}) and {@code getVersionIntervals()} (a {@code List<VersionInterval>}).
 * Those pull in the whole {@code metadata.version} predicate/interval algebra, which nothing in the shipped
 * ecosystem calls: a constant-pool scan of fabric-api 0.154.0 (43 modules) finds zero references to
 * {@code ModDependency} at all, and the kernel resolves dependencies with its own unified model
 * ({@code net.forbric.kernel.metadata.UnifiedDependency}). They are omitted rather than stubbed, so a caller
 * fails loudly at link time instead of silently receiving an empty collection. Restore them with the version
 * algebra if a real mod is found to need them.
 */
public interface ModDependency {
	Kind getKind();

	String getModId();

	boolean matches(Version version);

	enum Kind {
		DEPENDS("depends", true, false),
		RECOMMENDS("recommends", true, true),
		SUGGESTS("suggests", true, true),
		CONFLICTS("conflicts", false, true),
		BREAKS("breaks", false, false);

		private static final Map<String, Kind> MAP = createMap();

		private final String key;
		private final boolean positive;
		private final boolean soft;

		Kind(String key, boolean positive, boolean soft) {
			this.key = key;
			this.positive = positive;
			this.soft = soft;
		}

		public String getKey() {
			return key;
		}

		/** Whether the dependency is positive (the mod should be present) rather than negative. */
		public boolean isPositive() {
			return positive;
		}

		/** Whether a violation is only a warning rather than a hard load failure. */
		public boolean isSoft() {
			return soft;
		}

		public static Kind parse(String key) {
			return MAP.get(key);
		}

		private static Map<String, Kind> createMap() {
			Kind[] values = values();
			Map<String, Kind> ret = new HashMap<>(values.length);

			for (Kind kind : values) {
				ret.put(kind.key, kind);
			}

			return ret;
		}
	}
}
