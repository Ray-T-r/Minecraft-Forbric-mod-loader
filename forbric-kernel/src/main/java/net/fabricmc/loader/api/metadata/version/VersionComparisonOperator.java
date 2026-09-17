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

package net.fabricmc.loader.api.metadata.version;

import net.fabricmc.loader.api.Version;

/**
 * The comparison a {@link VersionPredicate.PredicateTerm} applies.
 *
 * <p>Part of the Fabric Loader API surface the kernel implements. The seven constants and their serialized
 * spellings are the API — a mod switches on them and prints them — so they are reproduced exactly; the
 * comparison itself is the kernel's own, in {@code net.forbric.api.VersionPredicate}.
 */
public enum VersionComparisonOperator {
	GREATER_EQUAL(">=", true, false),
	LESS_EQUAL("<=", false, true),
	GREATER(">", false, false),
	LESS("<", false, false),
	EQUAL("=", true, true),
	SAME_TO_NEXT_MINOR("~", true, false),
	SAME_TO_NEXT_MAJOR("^", true, false);

	private final String serialized;
	private final boolean minInclusive;
	private final boolean maxInclusive;

	VersionComparisonOperator(String serialized, boolean minInclusive, boolean maxInclusive) {
		this.serialized = serialized;
		this.minInclusive = minInclusive;
		this.maxInclusive = maxInclusive;
	}

	public String getSerialized() {
		return serialized;
	}

	public boolean isMinInclusive() {
		return minInclusive;
	}

	public boolean isMaxInclusive() {
		return maxInclusive;
	}

	/** Whether {@code version} satisfies this operator against {@code reference}. */
	public boolean test(Version version, Version reference) {
		if (version == null || reference == null) return false;
		return net.forbric.api.VersionPredicate.matches(serialized + reference.getFriendlyString(),
				version.getFriendlyString());
	}
}
