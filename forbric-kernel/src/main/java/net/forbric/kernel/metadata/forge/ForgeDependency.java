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

import net.forbric.api.UnifiedDependency;
import net.forbric.api.UnifiedDependency.Ordering;
import net.forbric.api.UnifiedDependency.SideScope;

/**
 * One {@code [[dependencies.<modId>]]} entry from a Forge {@code mods.toml}.
 *
 * <p>This is the raw, clean-room representation: the Maven {@link #getVersionRange() version range} exactly as
 * the file wrote it. {@code ForgeMetadataMapper} translates it into a {@link UnifiedDependency}, whose predicate
 * dialect is the one Forbric evaluates.
 *
 * <p>The {@link #getOrdering() ordering} and {@link #getSideScope() side} axes are the unified enums rather than
 * Forge-specific copies. They were Forge-specific once, which meant two identical three-valued enums with two
 * identical parsers — and a mapper that could only cross the gap by dropping them, which is exactly what it did.
 */
public final class ForgeDependency {
	private final String modId;
	private final boolean mandatory;
	private final String versionRange;
	private final Ordering ordering;
	private final SideScope side;

	public ForgeDependency(String modId, boolean mandatory, String versionRange, Ordering ordering, SideScope side) {
		this.modId = modId;
		this.mandatory = mandatory;
		this.versionRange = versionRange == null ? "" : versionRange;
		this.ordering = ordering == null ? Ordering.NONE : ordering;
		this.side = side == null ? SideScope.BOTH : side;
	}

	public String getModId() {
		return modId;
	}

	/** {@code true} for a hard dependency (resolution must satisfy it), {@code false} for a soft/ordering-only one. */
	public boolean isMandatory() {
		return mandatory;
	}

	/** The Maven-style version range, e.g. {@code "[47,)"}; empty means "any". */
	public String getVersionRange() {
		return versionRange;
	}

	public Ordering getOrdering() {
		return ordering;
	}

	public SideScope getSideScope() {
		return side;
	}

	@Override
	public String toString() {
		return "ForgeDependency{" + modId
				+ (mandatory ? " (mandatory)" : " (optional)")
				+ " range=" + versionRange
				+ " ordering=" + ordering
				+ " side=" + side
				+ '}';
	}
}
