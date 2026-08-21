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

package net.forbric.kernel.metadata.forge;

/**
 * Evaluates a Maven version range — the syntax {@code mods.toml} writes {@code versionRange} in — against a
 * concrete version.
 *
 * <p>{@link ForgeVersionRangeTranslator} converts the same syntax into Fabric's predicate STRING, which is what a
 * Fabric-shaped metadata object wants. This answers the other question: is it actually satisfied, here, now.
 *
 * <p>Comparison splits on {@code . - +} and compares segment by segment: numeric against numeric numerically,
 * anything else lexicographically. Where one version runs out of segments, what the OTHER has left decides:
 * trailing zeros are padding, so {@code 1.0.0} equals {@code 1.0}; a trailing non-zero number makes it the larger,
 * so {@code 26.2.0.7.1} follows {@code 26.2.0.7}; and a trailing qualifier makes it the SMALLER — {@code 1.0-beta}
 * precedes {@code 1.0}, the semver pre-release rule, and the reason a {@code -beta} NeoForge compares sanely.
 */
public final class ForgeVersionRange {
	private ForgeVersionRange() {
	}

	/**
	 * True if {@code version} falls in {@code mavenRange}.
	 *
	 * <p>Fails OPEN: an empty or unparseable range, or an absent version, is treated as satisfied. This exists to
	 * explain a failure that has already happened, and a range we cannot read is not evidence of anything.
	 */
	public static boolean satisfies(String mavenRange, String version) {
		if (mavenRange == null || mavenRange.isBlank() || version == null || version.isBlank()) return true;

		String range = mavenRange.trim();
		// A bare version is a Maven "soft" requirement — a floor, not a constraint. Forge treats it as a minimum.
		if (range.indexOf('[') < 0 && range.indexOf('(') < 0) return compare(version, range) >= 0;

		boolean sawInterval = false;
		int i = 0;
		while (i < range.length()) {
			char open = range.charAt(i);
			if (open != '[' && open != '(') {
				i++;
				continue;
			}
			int close = closingIndex(range, i);
			if (close < 0) return true; // malformed — fail open

			sawInterval = true;
			if (inInterval(range.substring(i + 1, close), open == '[', range.charAt(close) == ']', version)) {
				return true; // a union: any interval matching is enough
			}
			i = close + 1;
		}
		return !sawInterval;
	}

	private static boolean inInterval(String body, boolean lowerInclusive, boolean upperInclusive, String version) {
		int comma = body.indexOf(',');
		if (comma < 0) {
			// [1.0] — a single pinned version. "(1.0)" is meaningless and never matches.
			return lowerInclusive && upperInclusive && compare(version, body.trim()) == 0;
		}

		String lower = body.substring(0, comma).trim();
		String upper = body.substring(comma + 1).trim();

		if (!lower.isEmpty()) {
			int cmp = compare(version, lower);
			if (cmp < 0 || (cmp == 0 && !lowerInclusive)) return false;
		}
		if (!upper.isEmpty()) {
			int cmp = compare(version, upper);
			if (cmp > 0 || (cmp == 0 && !upperInclusive)) return false;
		}
		return true;
	}

	private static int closingIndex(String range, int openIdx) {
		for (int i = openIdx + 1; i < range.length(); i++) {
			char c = range.charAt(i);
			if (c == ']' || c == ')') return i;
		}
		return -1;
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
