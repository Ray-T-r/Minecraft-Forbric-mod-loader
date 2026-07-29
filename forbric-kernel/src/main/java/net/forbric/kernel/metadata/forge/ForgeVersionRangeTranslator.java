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

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a Forge/Maven version range into a Fabric-style version-predicate string.
 *
 * <p>Forge dependency versions use Maven's range grammar; Fabric's resolver consumes predicate strings
 * such as {@code ">=1.20.1 <1.21"}. This converts between them so a Forge dependency can take part in
 * the single (Fabric) dependency solve.
 *
 * <p>Supported forms:
 * <ul>
 *   <li>empty / {@code null} &rarr; {@code "*"} (any)</li>
 *   <li>a bare version {@code "47"} &rarr; {@code ">=47"} (Maven "soft" requirement, treated as a floor)</li>
 *   <li>{@code "[a,b)"} &rarr; {@code ">=a <b"}; {@code "[a,b]"} &rarr; {@code ">=a <=b"}</li>
 *   <li>{@code "(a,b)"} &rarr; {@code ">a <b"}; open ends {@code "[a,)"} / {@code "(,b]"}</li>
 *   <li>exact {@code "[a]"} &rarr; {@code "=a"}</li>
 *   <li>multiple comma-joined groups, e.g. {@code "(,1.0],[1.2,)"}, joined with {@code " || "}</li>
 * </ul>
 */
public final class ForgeVersionRangeTranslator {
	private ForgeVersionRangeTranslator() {
	}

	public static String toFabricPredicate(String mavenRange) {
		if (mavenRange == null) return "*";

		String range = mavenRange.trim();
		if (range.isEmpty()) return "*";

		// A bare version (no brackets) is a Maven "soft" floor.
		if (range.indexOf('[') < 0 && range.indexOf('(') < 0) {
			return ">=" + range;
		}

		List<String> groups = new ArrayList<>();

		int i = 0;

		while (i < range.length()) {
			char open = range.charAt(i);

			if (open != '[' && open != '(') {
				// Skip separators/whitespace between groups.
				i++;
				continue;
			}

			int close = indexOfClosing(range, i);
			if (close < 0) throw new IllegalArgumentException("unbalanced version range: " + mavenRange);

			groups.add(parseGroup(range.substring(i, close + 1), mavenRange));
			i = close + 1;
		}

		if (groups.isEmpty()) return "*";
		if (groups.size() == 1) return groups.get(0);

		return String.join(" || ", groups);
	}

	private static int indexOfClosing(String range, int openIdx) {
		for (int i = openIdx + 1; i < range.length(); i++) {
			char c = range.charAt(i);
			if (c == ']' || c == ')') return i;
		}

		return -1;
	}

	private static String parseGroup(String group, String original) {
		boolean lowerInclusive = group.charAt(0) == '[';
		boolean upperInclusive = group.charAt(group.length() - 1) == ']';

		String inner = group.substring(1, group.length() - 1);
		int comma = inner.indexOf(',');

		// "[a]" — an exact, single-version pin.
		if (comma < 0) {
			String v = inner.trim();
			if (v.isEmpty()) throw new IllegalArgumentException("empty version range group: " + original);
			return "=" + v;
		}

		String lower = inner.substring(0, comma).trim();
		String upper = inner.substring(comma + 1).trim();

		List<String> parts = new ArrayList<>(2);

		if (!lower.isEmpty()) {
			parts.add((lowerInclusive ? ">=" : ">") + lower);
		}

		if (!upper.isEmpty()) {
			parts.add((upperInclusive ? "<=" : "<") + upper);
		}

		if (parts.isEmpty()) return "*";

		return String.join(" ", parts);
	}
}
