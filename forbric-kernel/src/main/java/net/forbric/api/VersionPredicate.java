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
 * <p><b>Comparison</b> splits on {@code . - +} and compares segment by segment: numeric against numeric
 * numerically, anything else lexicographically, a number outranking a qualifier at the same position. Where one
 * version runs out of segments, what the OTHER has left decides: trailing zeros are padding, so {@code 1.0.0}
 * equals {@code 1.0}; a trailing non-zero number makes it larger, so {@code 26.2.0.7.1} follows {@code 26.2.0.7};
 * and a trailing qualifier makes it SMALLER — {@code 1.0-beta} precedes {@code 1.0}, the semver pre-release rule,
 * and the reason a {@code -beta} NeoForge compares sanely.
 *
 * <p><b>Everything here fails OPEN.</b> A predicate this cannot parse, or an absent version, counts as satisfied.
 * These answers feed diagnostics — "this mod's requirement is not met" — and a requirement we cannot read is not
 * evidence that anything is wrong. Failing closed would turn every unusual predicate into a false accusation.
 */
public final class VersionPredicate {
	private VersionPredicate() {
	}

	/** True if {@code version} satisfies {@code predicate}. {@code "*"}, blank, and unparseable all mean yes. */
	public static boolean matches(String predicate, String version) {
		if (predicate == null || predicate.isBlank() || version == null || version.isBlank()) return true;

		// "||" is OR between whole predicates; a space inside one is AND. Fabric's own precedence.
		for (String alternative : predicate.split("\\|\\|")) {
			if (allClausesMatch(alternative, version)) return true;
		}
		return false;
	}

	private static boolean allClausesMatch(String alternative, String version) {
		String trimmed = alternative.trim();
		if (trimmed.isEmpty()) return true;

		for (String clause : trimmed.split("\\s+")) {
			if (!clauseMatches(clause, version)) return false;
		}
		return true;
	}

	private static boolean clauseMatches(String clause, String version) {
		if (clause.equals("*") || clause.isEmpty()) return true;

		if (clause.startsWith(">=")) return compare(version, clause.substring(2).trim()) >= 0;
		if (clause.startsWith("<=")) return compare(version, clause.substring(2).trim()) <= 0;
		if (clause.startsWith(">")) return compare(version, clause.substring(1).trim()) > 0;
		if (clause.startsWith("<")) return compare(version, clause.substring(1).trim()) < 0;
		// "~1.2.3" allows patch updates: >=1.2.3 and <1.3. "^1.2.3" allows minor ones too: >=1.2.3 and <2.
		if (clause.startsWith("~")) return tildeRange(clause.substring(1).trim(), version);
		if (clause.startsWith("^")) return caretRange(clause.substring(1).trim(), version);
		if (clause.startsWith("=")) return equalOrWildcard(clause.substring(1).trim(), version);

		// Not an operator and not version-shaped — nothing we can hold a version against. Fail open rather than
		// report a mismatch against a string we did not understand.
		if (!isVersionShaped(clause)) return true;
		return equalOrWildcard(clause, version);
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

	/** {@code ~1.2.3} and {@code ~1.2} both bound at the next MINOR; {@code ~1} bounds at the next major. */
	private static boolean tildeRange(String floor, String version) {
		return inBoundedRange(floor, version, Math.min(2, segments(floor)));
	}

	/**
	 * {@code ^} bounds at the leftmost NON-ZERO segment, not simply at the major.
	 *
	 * <p>This is not pedantry about npm's rules: most Fabric mods are versioned {@code 0.x}, so reading
	 * {@code ^0.15.0} as "anything below 1" accepts every future breaking release of exactly the mods where a
	 * major bump never happens. Bumping the leftmost non-zero segment makes it {@code <0.16}, which is what the
	 * author meant.
	 */
	private static boolean caretRange(String floor, String version) {
		String[] parts = floor.split("[.\\-+]");
		int bumpAt = 1;
		for (int i = 0; i < parts.length; i++) {
			Long value = asNumber(parts[i]);
			if (value != null && value != 0L) {
				bumpAt = i + 1;
				break;
			}
			if (i == parts.length - 1) bumpAt = parts.length; // every segment is zero: bump the last
		}
		return inBoundedRange(floor, version, bumpAt);
	}

	private static int segments(String version) {
		return version.isEmpty() ? 0 : version.split("[.\\-+]").length;
	}

	/** {@code floor <= version} and {@code version} below the next value of segment {@code bumpAt} (1-based). */
	private static boolean inBoundedRange(String floor, String version, int bumpAt) {
		if (floor.isEmpty()) return true;
		if (compare(version, floor) < 0) return false;

		String[] parts = floor.split("[.\\-+]");
		int index = Math.min(bumpAt, parts.length) - 1;
		if (index < 0) return true;

		Long value = asNumber(parts[index]);
		if (value == null) return true; // a qualifier where a number was expected — no ceiling we can name

		StringBuilder ceiling = new StringBuilder();
		for (int i = 0; i < index; i++) ceiling.append(parts[i]).append('.');
		ceiling.append(value + 1);
		return compare(version, ceiling.toString()) < 0;
	}

	/** An exact version, or one with {@code x}/{@code *} wildcard segments ({@code "1.2.x"}). */
	private static boolean equalOrWildcard(String wanted, String version) {
		if (wanted.isEmpty()) return true;
		if (wanted.indexOf('x') < 0 && wanted.indexOf('X') < 0 && wanted.indexOf('*') < 0) {
			return compare(version, wanted) == 0;
		}

		String[] want = wanted.split("[.\\-+]");
		String[] have = version.split("[.\\-+]");
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

	private static String[] split(String version) {
		String[] parts = version.trim().split("[.\\-+]");
		return parts.length == 0 ? new String[] {version.trim()} : parts;
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
