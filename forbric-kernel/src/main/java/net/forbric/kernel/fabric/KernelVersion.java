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
import java.util.Optional;

import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/**
 * The kernel's native {@link Version} parser — the sovereign replacement for Fabric Loader's
 * {@code impl.util.version.VersionParser}, which the kernel does not vendor.
 *
 * <p>{@link #parse} yields a {@link SemanticVersion} when the string is SemVer-shaped and falls back to an
 * order-by-string version otherwise (Fabric's own behaviour for non-SemVer mods). Real ecosystem versions this
 * must handle: {@code 0.154.0+26.2} (fabric-api), {@code 26.2.9+fabric} (Jade), {@code 26.2} (the game),
 * {@code 1.0.0-beta.3} (prerelease), and wildcards like {@code 1.21.x} used in predicates.
 */
public final class KernelVersion {
	private KernelVersion() {
	}

	/** SemVer if possible, else a plain string version. Never returns null. */
	public static Version parse(String string) throws VersionParsingException {
		String s = requireNonBlank(string);

		try {
			return parseSemantic(s);
		} catch (VersionParsingException notSemver) {
			return new StringVersion(s);
		}
	}

	/** Strict SemVer-shaped parse. Throws when {@code string} is not SemVer-shaped. */
	public static SemanticVersion parseSemantic(String string) throws VersionParsingException {
		String s = requireNonBlank(string);

		String build = null;
		int plus = s.indexOf('+');

		if (plus >= 0) {
			build = s.substring(plus + 1);
			s = s.substring(0, plus);
		}

		String prerelease = null;
		int dash = s.indexOf('-');

		if (dash >= 0) {
			prerelease = s.substring(dash + 1);
			s = s.substring(0, dash);
		}

		if (s.isEmpty()) throw new VersionParsingException("no version components in '" + string + "'");

		String[] parts = s.split("\\.", -1);
		int[] components = new int[parts.length];
		boolean wildcard = false;

		for (int i = 0; i < parts.length; i++) {
			String part = parts[i];

			if (part.equals("x") || part.equals("X") || part.equals("*")) {
				components[i] = SemanticVersion.COMPONENT_WILDCARD;
				wildcard = true;
				continue;
			}

			if (wildcard) {
				throw new VersionParsingException("no component may follow a wildcard in '" + string + "'");
			}

			if (part.isEmpty()) throw new VersionParsingException("empty version component in '" + string + "'");

			for (int c = 0; c < part.length(); c++) {
				if (!Character.isDigit(part.charAt(c))) {
					throw new VersionParsingException("non-numeric version component '" + part + "' in '" + string + "'");
				}
			}

			try {
				components[i] = Integer.parseInt(part);
			} catch (NumberFormatException e) {
				throw new VersionParsingException("version component out of range in '" + string + "'", e);
			}
		}

		return new SemVer(string, components, prerelease, build);
	}

	private static String requireNonBlank(String string) throws VersionParsingException {
		if (string == null) throw new VersionParsingException("version is null");

		String s = string.trim();
		if (s.isEmpty()) throw new VersionParsingException("version is empty");

		return s;
	}

	/** A non-SemVer version: equal-or-ordered by its literal string. */
	static final class StringVersion implements Version {
		private final String raw;

		StringVersion(String raw) {
			this.raw = raw;
		}

		@Override
		public String getFriendlyString() {
			return raw;
		}

		@Override
		public int compareTo(Version other) {
			// A SemVer always sorts above a non-comparable string version of a different shape; among string
			// versions, lexicographic order is the only total order available.
			return raw.compareTo(other.getFriendlyString());
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof StringVersion && raw.equals(((StringVersion) o).raw);
		}

		@Override
		public int hashCode() {
			return raw.hashCode();
		}

		@Override
		public String toString() {
			return raw;
		}
	}

	/** A SemVer-shaped version. Build metadata is carried but, per SemVer, never affects ordering. */
	static final class SemVer implements SemanticVersion {
		private final String raw;
		private final int[] components;
		private final String prerelease;
		private final String build;

		SemVer(String raw, int[] components, String prerelease, String build) {
			this.raw = raw;
			this.components = components;
			this.prerelease = prerelease;
			this.build = build;
		}

		@Override
		public String getFriendlyString() {
			return raw;
		}

		@Override
		public int getVersionComponentCount() {
			return components.length;
		}

		@Override
		public int getVersionComponent(int pos) {
			// Absent trailing components read as 0 (1.2 behaves as 1.2.0), matching Fabric.
			if (pos < 0) throw new IndexOutOfBoundsException("negative version component index: " + pos);
			if (pos >= components.length) return hasWildcard() ? COMPONENT_WILDCARD : 0;

			return components[pos];
		}

		@Override
		public Optional<String> getPrereleaseKey() {
			return Optional.ofNullable(prerelease);
		}

		@Override
		public Optional<String> getBuildKey() {
			return Optional.ofNullable(build);
		}

		@Override
		public boolean hasWildcard() {
			for (int c : components) {
				if (c == COMPONENT_WILDCARD) return true;
			}

			return false;
		}

		@Override
		public int compareTo(Version other) {
			if (!(other instanceof SemanticVersion)) {
				return getFriendlyString().compareTo(other.getFriendlyString());
			}

			SemanticVersion o = (SemanticVersion) other;
			int count = Math.max(getVersionComponentCount(), o.getVersionComponentCount());

			for (int i = 0; i < count; i++) {
				int a = getVersionComponent(i);
				int b = o.getVersionComponent(i);

				if (a == COMPONENT_WILDCARD || b == COMPONENT_WILDCARD) continue;
				if (a != b) return Integer.compare(a, b);
			}

			return comparePrerelease(prerelease, o.getPrereleaseKey().orElse(null));
		}

		/** SemVer §11.3-11.4: a prerelease sorts BELOW its release; otherwise compare dot-separated identifiers. */
		private static int comparePrerelease(String a, String b) {
			if (a == null && b == null) return 0;
			if (a == null) return 1;
			if (b == null) return -1;

			List<String> as = split(a);
			List<String> bs = split(b);
			int n = Math.min(as.size(), bs.size());

			for (int i = 0; i < n; i++) {
				String x = as.get(i);
				String y = bs.get(i);
				boolean xn = isNumeric(x);
				boolean yn = isNumeric(y);

				if (xn && yn) {
					int cmp = Long.compare(Long.parseLong(x), Long.parseLong(y));
					if (cmp != 0) return cmp;
				} else if (xn != yn) {
					// Numeric identifiers always have lower precedence than alphanumeric ones.
					return xn ? -1 : 1;
				} else {
					int cmp = x.compareTo(y);
					if (cmp != 0) return cmp;
				}
			}

			return Integer.compare(as.size(), bs.size());
		}

		private static List<String> split(String s) {
			List<String> out = new ArrayList<>();

			for (String part : s.split("\\.", -1)) {
				out.add(part);
			}

			return out;
		}

		private static boolean isNumeric(String s) {
			if (s.isEmpty()) return false;

			for (int i = 0; i < s.length(); i++) {
				if (!Character.isDigit(s.charAt(i))) return false;
			}

			return true;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof SemVer && compareTo((Version) o) == 0;
		}

		@Override
		public int hashCode() {
			int h = 0;

			for (int i = 0; i < components.length; i++) {
				h = h * 31 + components[i];
			}

			return h * 31 + (prerelease == null ? 0 : prerelease.hashCode());
		}

		@Override
		public String toString() {
			return raw;
		}
	}
}
