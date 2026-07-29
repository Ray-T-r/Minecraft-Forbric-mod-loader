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
	 * <p>Supported: {@code *} (any), the comparison operators {@code >=  <=  >  <  =}, and the range shorthands
	 * {@code ~} (same major+minor, at least this patch) and {@code ^} (same major, at least this version). A
	 * space-separated conjunction ({@code ">=1.0 <2.0"}) requires every term. An unparseable term yields
	 * {@code false} rather than throwing, so one malformed constraint cannot abort a whole load.
	 */
	static boolean matchesPredicate(String predicate, Version version) {
		if (predicate == null) return true;

		String p = predicate.trim();
		if (p.isEmpty() || p.equals("*")) return true;

		// Conjunction: every space-separated term must hold.
		if (p.indexOf(' ') >= 0) {
			for (String term : p.split("\\s+")) {
				if (!matchesPredicate(term, version)) return false;
			}

			return true;
		}

		String op = "=";

		for (String candidate : new String[] {">=", "<=", ">", "<", "=", "~", "^"}) {
			if (p.startsWith(candidate)) {
				op = candidate;
				p = p.substring(candidate.length()).trim();
				break;
			}
		}

		Version bound;

		try {
			bound = KernelVersion.parse(p);
		} catch (Exception e) {
			return false;
		}

		switch (op) {
			case ">=": return version.compareTo(bound) >= 0;
			case "<=": return version.compareTo(bound) <= 0;
			case ">": return version.compareTo(bound) > 0;
			case "<": return version.compareTo(bound) < 0;
			case "=": return version.compareTo(bound) == 0;
			case "~": return atLeastAndBelow(version, bound, 2);
			case "^": return atLeastAndBelow(version, bound, 1);
			default: return false;
		}
	}

	/**
	 * {@code version >= bound} and below the next increment of the component at {@code pinnedComponents - 1} —
	 * i.e. {@code ~1.2.3} accepts {@code [1.2.3, 1.3.0)} and {@code ^1.2.3} accepts {@code [1.2.3, 2.0.0)}.
	 * Non-semantic versions fall back to plain equality, which is all their ordering supports.
	 */
	private static boolean atLeastAndBelow(Version version, Version bound, int pinnedComponents) {
		if (!(version instanceof SemanticVersion) || !(bound instanceof SemanticVersion)) {
			return version.compareTo(bound) == 0;
		}

		if (version.compareTo(bound) < 0) return false;

		SemanticVersion v = (SemanticVersion) version;
		SemanticVersion b = (SemanticVersion) bound;

		for (int i = 0; i < pinnedComponents; i++) {
			if (v.getVersionComponent(i) != b.getVersionComponent(i)) return false;
		}

		return true;
	}
}
