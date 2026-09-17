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
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.Version;

/**
 * A dependency declared by a mod.
 *
 * <p><b>The omission that was argued, and the half of it that was wrong.</b> These two methods were left out on
 * the grounds that nothing in the shipped ecosystem reads a dependency's requirement objects — a constant-pool
 * scan of fabric-api 0.154.0 finds zero references to {@code ModDependency} at all — and that omitting rather
 * than stubbing makes a caller fail loudly instead of silently receiving an empty collection.
 *
 * <p>That reasoning was about the INSTANCE side and it still holds. What it missed is that the types it avoided
 * naming have STATIC entry points mods call with no dependency involved: {@code VersionPredicate.parse} is an
 * {@code invokestatic} in ShoulderSurfing-Fabric's {@code Platform.parseVersionPredicateSilent} and in
 * conditional-mixin's version gate. Absent, those raised {@code NoClassDefFoundError} — past the
 * {@code catch (Exception)} those call sites wrap themselves in. The package is vendored now, so these two can
 * be answered honestly rather than omitted.
 */
public interface ModDependency {
	Kind getKind();

	String getModId();

	boolean matches(Version version);

	/**
	 * The requirements this dependency declares, parsed.
	 *
	 * <p>Empty when the requirement is unreadable, which is the same thing {@link #matches} does with it: a
	 * dependency the kernel could not parse does not constrain anything, and a caller enumerating requirements
	 * must see the same set the matcher used.
	 */
	default Collection<net.fabricmc.loader.api.metadata.version.VersionPredicate> getVersionRequirements() {
		return List.of();
	}

	/**
	 * The ranges those requirements admit.
	 *
	 * <p>Empty by default for the reason {@code VersionPredicate.getInterval()} answers null: a requirement like
	 * {@code ">=1.0 <2.0 !=1.5"} is not one range, and inventing a bound a caller would trust is worse than
	 * saying nothing.
	 */
	default List<net.fabricmc.loader.api.metadata.version.VersionInterval> getVersionIntervals() {
		return List.of();
	}

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
