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

import net.forbric.api.VersionPredicate;

/**
 * Evaluates a Maven version range — the syntax {@code mods.toml} writes {@code versionRange} in — against a
 * concrete version.
 *
 * <p>{@link ForgeVersionRangeTranslator} converts the same syntax into Fabric's predicate STRING, which is what a
 * Fabric-shaped metadata object wants. This answers the other question: is it actually satisfied, here, now.
 *
 * <p>Only the RANGE syntax is Forge's. Ordering versions is not — {@link VersionPredicate} holds the segment
 * rules for every ecosystem, and this compares through it.
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

	/**
	 * Compares two version strings.
	 *
	 * <p>Delegates to {@link VersionPredicate#compare}: the segment rules are not a Forge thing, they are how
	 * Forbric orders versions from every ecosystem, and two copies of them would be two chances to disagree about
	 * whether {@code 1.0-beta} precedes {@code 1.0}.
	 */
	public static int compare(String a, String b) {
		return VersionPredicate.compare(a, b);
	}
}
