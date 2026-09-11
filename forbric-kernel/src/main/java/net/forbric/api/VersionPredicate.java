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

package net.forbric.api;

/**
 * Forbric's one version vocabulary: compare two versions, and evaluate a Fabric-style predicate against one.
 *
 * <p>The three ecosystems write version requirements in two dialects — Fabric's predicate strings
 * ({@code ">=0.15.0"}, {@code ">=47 <48"}, {@code "~1.2.3"}) and the Forge families' Maven ranges
 * ({@code "[47,48)"}). {@link UnifiedDependency} normalises to the Fabric dialect because that is the one that can
 * express everything the other can; this evaluates it. Maven ranges are parsed by
 * {@code ForgeVersionRangeTranslator} on the way in, so nothing downstream has to know which ecosystem a
 * requirement came from — which is the entire point of a unified dependency.
 *
 * <p><b>Comparison</b> splits on {@code .} and {@code -}, ignores {@code +} build metadata, and compares
 * segment by segment: numeric against numeric
 * numerically, anything else lexicographically, a number outranking a qualifier at the same position. Where one
 * version runs out of segments, what the OTHER has left decides: trailing zeros are padding, so {@code 1.0.0}
 * equals {@code 1.0}; a trailing non-zero number makes it larger, so {@code 26.2.0.7.1} follows {@code 26.2.0.7};
 * and a trailing qualifier makes it SMALLER — {@code 1.0-beta} precedes {@code 1.0}, the semver pre-release rule,
 * and the reason a {@code -beta} NeoForge compares sanely.
 *
 * <p><b>Two failure directions, one engine.</b> {@link #matches} treats an unreadable predicate as satisfied and
 * {@link #matchesStrictly} treats it as unsatisfied. Both are right for their callers — a diagnostic must not
 * accuse a mod over a predicate it could not read, and a resolver must not admit a dependency it could not check —
 * and the point of putting them here is that the difference is now a named argument instead of two separate
 * implementations that drifted apart. There were two: this class and {@code KernelMetadataSupport}, and they
 * disagreed about {@code ^}, which is the most common shape in the Fabric ecosystem.
 *
 * <h2>The shorthands are Fabric's, verified against Fabric Loader</h2>
 *
 * <p>{@code ~} and {@code ^} are read off {@code VersionComparisonOperator} in fabric-loader 0.19.5:
 * {@code ~} is {@code SAME_TO_NEXT_MINOR} (same major AND same minor, at or above the floor) and {@code ^} is
 * {@code SAME_TO_NEXT_MAJOR} (same major, at or above the floor). There is NO npm-style "leftmost non-zero"
 * rule — {@code ^0.15.0} admits {@code 0.16.0} here, as it does under Fabric. An earlier version of this class
 * implemented npm's rule on the reasoning that most Fabric mods are 0.x and a plain major bound lets every
 * breaking release through. That reasoning is sound and irrelevant: a mod that loads under Fabric has to load
 * under Forbric, so the dialect is not ours to improve.
 */
public final class VersionPredicate {
	private VersionPredicate() {
	}

	/** True if {@code version} satisfies {@code predicate}. {@code "*"}, blank, and unparseable all mean yes. */
	public static boolean matches(String predicate, String version) {
		return matches(predicate, version, true);
	}

	/**
	 * As {@link #matches}, but an unreadable predicate counts as NOT satisfied.
	 *
	 * <p>For callers that admit or reject a dependency rather than describe one: letting a predicate nobody could
	 * parse resolve to "fine" is how an unchecked requirement gets through.
	 */
	public static boolean matchesStrictly(String predicate, String version) {
		return matches(predicate, version, false);
	}

	private static boolean matches(String predicate, String version, boolean openOnUnreadable) {
		if (predicate == null || predicate.isBlank()) return true;
		if (version == null || version.isBlank()) return openOnUnreadable;

		// "||" is OR between whole predicates; a space inside one is AND. Fabric's own precedence.
		for (String alternative : predicate.split("\\|\\|")) {
			if (allClausesMatch(alternative, version, openOnUnreadable)) return true;
		}
		return false;
	}

	private static boolean allClausesMatch(String alternative, String version, boolean openOnUnreadable) {
		String trimmed = alternative.trim();
		if (trimmed.isEmpty()) return true;

		for (String clause : trimmed.split("\\s+")) {
			if (!clauseMatches(clause, version, openOnUnreadable)) return false;
		}
		return true;
	}

	private static boolean clauseMatches(String clause, String version, boolean openOnUnreadable) {
		if (clause.equals("*") || clause.isEmpty()) return true;

		if (clause.startsWith(">=")) return bounded(clause.substring(2).trim(), version, openOnUnreadable, 0);
		if (clause.startsWith("<=")) return bounded(clause.substring(2).trim(), version, openOnUnreadable, 1);
		if (clause.startsWith(">")) return bounded(clause.substring(1).trim(), version, openOnUnreadable, 2);
		if (clause.startsWith("<")) return bounded(clause.substring(1).trim(), version, openOnUnreadable, 3);
		// Fabric's two shorthands: ~ pins major+minor, ^ pins major. Both also require >= the floor.
		if (clause.startsWith("~")) return samePrefixAndAtLeast(clause.substring(1).trim(), version, 2, openOnUnreadable);
		if (clause.startsWith("^")) return samePrefixAndAtLeast(clause.substring(1).trim(), version, 1, openOnUnreadable);
		if (clause.startsWith("=")) return equalOrWildcard(clause.substring(1).trim(), version, openOnUnreadable);

		// Not an operator and not version-shaped — nothing we can hold a version against.
		if (!isVersionShaped(clause)) return openOnUnreadable;
		return equalOrWildcard(clause, version, openOnUnreadable);
	}

	/** One comparison against {@code floor}: 0 is {@code >=}, 1 {@code <=}, 2 {@code >}, 3 {@code <}. */
	private static boolean bounded(String floor, String version, boolean openOnUnreadable, int mode) {
		if (!isVersionShaped(floor)) return openOnUnreadable;
		int cmp = compare(version, floor);
		return switch (mode) {
			case 0 -> cmp >= 0;
			case 1 -> cmp <= 0;
			case 2 -> cmp > 0;
			default -> cmp < 0;
		};
	}

	/**
	 * Fabric's {@code SAME_TO_NEXT_MINOR}/{@code SAME_TO_NEXT_MAJOR}: at or above {@code floor}, and identical in
	 * its first {@code pinned} numeric components.
	 *
	 * <p>Written as "same prefix" rather than as an upper bound because that is how Fabric writes it, and the two
	 * differ where a component is missing or non-numeric. A component the floor does not have counts as 0, and a
	 * non-numeric component on either side falls back to plain equality — the same fallback Fabric's operator
	 * makes for a version that is not semantic, because nothing else about its ordering is defined.
	 */
	private static boolean samePrefixAndAtLeast(String floor, String version, int pinned, boolean openOnUnreadable) {
		if (!isVersionShaped(floor)) return openOnUnreadable;
		if (compare(version, floor) < 0) return false;

		String[] have = split(version);
		String[] want = split(floor);
		for (int i = 0; i < pinned; i++) {
			Long h = component(have, i);
			Long w = component(want, i);
			if (h == null || w == null) return compare(version, floor) == 0; // not semantic — equality is all there is
			if (!h.equals(w)) return false;
		}
		return true;
	}

	/** Numeric component {@code i}, 0 for one past the end, or {@code null} when it is not a number. */
	private static Long component(String[] parts, int i) {
		if (i >= parts.length) return 0L;
		return asNumber(parts[i]);
	}

	private static boolean isVersionShaped(String clause) {
		for (int i = 0; i < clause.length(); i++) {
			char c = clause.charAt(i);
			boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| c == '.' || c == '-' || c == '+' || c == '_' || c == '*';
			if (!ok) return false;
		}
		return !clause.isEmpty();
	}

	/** An exact version, or one with {@code x}/{@code *} wildcard segments ({@code "1.2.x"}). */
	private static boolean equalOrWildcard(String wanted, String version, boolean openOnUnreadable) {
		if (wanted.isEmpty()) return openOnUnreadable;
		if (wanted.indexOf('x') < 0 && wanted.indexOf('X') < 0 && wanted.indexOf('*') < 0) {
			return compare(version, wanted) == 0;
		}

		String[] want = split(wanted);
		String[] have = split(version);
		for (int i = 0; i < want.length; i++) {
			String segment = want[i];
			if (segment.equalsIgnoreCase("x") || segment.equals("*")) return true; // matches this and every tail
			if (i >= have.length || !segment.equalsIgnoreCase(have[i])) return false;
		}
		return true;
	}

	/** Compares two version strings; see the class javadoc for the segment rules. */
	public static int compare(String a, String b) {
		String[] left = split(a);
		String[] right = split(b);
		int n = Math.max(left.length, right.length);

		for (int i = 0; i < n; i++) {
			if (i >= left.length) return -tailSign(right, i);
			if (i >= right.length) return tailSign(left, i);

			String x = left[i];
			String y = right[i];
			Long xn = asNumber(x);
			Long yn = asNumber(y);
			int cmp;
			if (xn != null && yn != null) {
				cmp = Long.compare(xn, yn);
			} else if (xn != null) {
				cmp = 1; // 1.0.1 > 1.0.beta — a number outranks a qualifier at the same position
			} else if (yn != null) {
				cmp = -1;
			} else {
				cmp = x.compareToIgnoreCase(y);
			}
			if (cmp != 0) return cmp;
		}
		return 0;
	}

	/**
	 * How the segments from {@code index} on weigh against a version that simply ended: {@code +1} if they make it
	 * larger, {@code -1} smaller, {@code 0} identical. Trailing zeros are padding — that is the whole reason this
	 * is not just "longer wins".
	 */
	private static int tailSign(String[] parts, int index) {
		for (int i = index; i < parts.length; i++) {
			Long value = asNumber(parts[i]);
			if (value == null) return -1; // a qualifier: pre-release, so it precedes the bare version
			if (value != 0L) return 1;
		}
		return 0;
	}

	/**
	 * Version text as comparable segments, with BUILD METADATA dropped.
	 *
	 * <p>Semver's two suffixes are not the same thing and must not be split the same way: {@code -} introduces a
	 * pre-release, which orders BELOW the plain version, while {@code +} introduces build metadata, which is
	 * ignored for ordering entirely. Splitting on both made {@code 6.3.3+72073ef09e} compare as a pre-release of
	 * {@code 6.3.3} and therefore fail {@code >=6.3.3} — and fabric-api stamps a build hash onto every module
	 * version, so that is most of the Fabric ecosystem. Every mod in a real pack reported its dependency unmet.
	 */
	private static String[] split(String version) {
		String trimmed = version.trim();
		int build = trimmed.indexOf('+');
		if (build >= 0) trimmed = trimmed.substring(0, build);
		String[] parts = trimmed.split("[.\\-]");
		return parts.length == 0 ? new String[] {trimmed} : parts;
	}

	private static Long asNumber(String part) {
		if (part.isEmpty()) return null;
		for (int i = 0; i < part.length(); i++) {
			if (part.charAt(i) < '0' || part.charAt(i) > '9') return null;
		}
		try {
			return Long.valueOf(part);
		} catch (NumberFormatException tooBig) {
			return null;
		}
	}
}
