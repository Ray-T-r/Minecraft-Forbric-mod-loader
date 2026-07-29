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

import java.util.Locale;

/**
 * One {@code [[dependencies.<modId>]]} entry from a Forge {@code mods.toml}.
 *
 * <p>This is the raw, clean-room representation. Translating {@link #getVersionRange() Maven version
 * ranges} into Fabric {@code VersionPredicate}s and feeding {@link #getOrdering() ordering} / {@link #getSide() side}
 * into the unified resolver is done later by the discovery layer (milestone P4).
 */
public final class ForgeDependency {
	/** Forge load ordering relative to the named mod. */
	public enum Ordering {
		NONE, BEFORE, AFTER;

		static Ordering parse(String value) {
			if (value == null) return NONE;

			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return NONE;
			}
		}
	}

	/** Physical side(s) a dependency applies to. */
	public enum Side {
		BOTH, CLIENT, SERVER;

		static Side parse(String value) {
			if (value == null) return BOTH;

			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return BOTH;
			}
		}
	}

	private final String modId;
	private final boolean mandatory;
	private final String versionRange;
	private final Ordering ordering;
	private final Side side;

	public ForgeDependency(String modId, boolean mandatory, String versionRange, Ordering ordering, Side side) {
		this.modId = modId;
		this.mandatory = mandatory;
		this.versionRange = versionRange == null ? "" : versionRange;
		this.ordering = ordering == null ? Ordering.NONE : ordering;
		this.side = side == null ? Side.BOTH : side;
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

	public Side getSide() {
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
