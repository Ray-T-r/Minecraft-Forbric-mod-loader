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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.Person;
import net.forbric.api.VersionPredicate;

/** Small value implementations behind the kernel's Fabric metadata model. */
public final class KernelMetadataSupport {
	private KernelMetadataSupport() {
	}

	/** An author/contributor entry. */
	public static final class SimplePerson implements Person {
		private final String name;
		private final ContactInformation contact;

		public SimplePerson(String name, ContactInformation contact) {
			this.name = name;
			this.contact = contact == null ? ContactInformation.EMPTY : contact;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public ContactInformation getContact() {
			return contact;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/** A {@code contact} block. */
	public static final class MapContactInformation implements ContactInformation {
		private final Map<String, String> map;

		public MapContactInformation(Map<String, String> map) {
			this.map = Collections.unmodifiableMap(map);
		}

		@Override
		public Optional<String> get(String key) {
			return Optional.ofNullable(map.get(key));
		}

		@Override
		public Map<String, String> asMap() {
			return map;
		}
	}

	/**
	 * A {@code depends}/{@code breaks}/… entry. {@code constraints} are OR-joined, matching the
	 * {@code fabric.mod.json} array form.
	 */
	public static final class SimpleModDependency implements ModDependency {
		private final Kind kind;
		private final String modId;
		private final List<String> constraints;

		public SimpleModDependency(Kind kind, String modId, List<String> constraints) {
			this.kind = kind;
			this.modId = modId;
			this.constraints = List.copyOf(constraints);
		}

		@Override
		public Kind getKind() {
			return kind;
		}

		@Override
		public String getModId() {
			return modId;
		}

		/** The OR-joined predicate strings as declared. */
		public List<String> getConstraints() {
			return constraints;
		}

		@Override
		public boolean matches(Version version) {
			if (constraints.isEmpty()) return true;

			for (String constraint : constraints) {
				if (matchesPredicate(constraint, version)) return true;
			}

			return false;
		}

		@Override
		public String toString() {
			return kind.getKey() + " " + modId + " " + constraints;
		}
	}

	/**
	 * Evaluates one Fabric version predicate against {@code version}.
	 *
	 * <p>Delegates to {@link VersionPredicate}, which is the one place Forbric knows this dialect. There used to be
	 * a second engine here, and the two disagreed about {@code ^}: this one implemented Fabric's
	 * {@code SAME_TO_NEXT_MAJOR} correctly while the other had an npm-style rule, so the same {@code depends}
	 * string could be satisfied for a mod and unsatisfied for the boot diagnostic looking at the same mod.
	 *
	 * <p>Strict, not lenient: this answer admits or rejects a dependency rather than describing one, so a
	 * predicate nobody could parse must not resolve to "fine".
	 */
	static boolean matchesPredicate(String predicate, Version version) {
		return VersionPredicate.matchesStrictly(predicate, version == null ? null : version.getFriendlyString());
	}
}
