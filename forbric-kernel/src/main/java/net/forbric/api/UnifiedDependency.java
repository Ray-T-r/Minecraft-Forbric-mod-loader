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

import java.util.Locale;

/**
 * A mod dependency in Forbric's unified model, normalized from a Fabric {@code depends}/{@code recommends} entry
 * or a Forge-family {@code [[dependencies]]} entry.
 *
 * <p>The {@link #getVersionConstraint() version constraint} is a Fabric-style predicate ({@code ">=0.15.0"},
 * {@code ">=47 <48"}); Maven ranges are translated into that form by {@code ForgeVersionRangeTranslator} when the
 * dependency is built. {@link #isSatisfiedBy} evaluates it, so a caller never has to know which ecosystem the
 * requirement came from — that is what makes this "unified" rather than merely "shared".
 *
 * <h2>What the Forge families say that Fabric has no word for</h2>
 *
 * <p>A Forge {@code [[dependencies]]} entry carries two axes Fabric's {@code depends} map cannot express: load
 * {@link Ordering ordering} relative to the other mod, and the physical {@link Side side} the requirement applies
 * to. Both were read by the parser and then dropped on the floor here, which made this type's name a promise it
 * did not keep. They are carried as DATA, per family, exactly as they were written — not folded into some average
 * that both families would then be slightly wrong about.
 *
 * <p>The side matters for more than tidiness: a client-only requirement counted as unconditional turns a dedicated
 * server into a false report of a missing mod, and a hard-failing resolver would refuse to start over it.
 */
public final class UnifiedDependency {
	/** Load order relative to the named mod. Fabric declares no ordering, so a Fabric entry is always {@link #NONE}. */
	public enum Ordering {
		NONE, BEFORE, AFTER;

		/** Parses a Forge {@code ordering} value; anything unrecognised or absent is {@link #NONE}. */
		public static Ordering parse(String value) {
			if (value == null) return NONE;
			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException notAnOrdering) {
				return NONE;
			}
		}
	}

	/** The physical side a requirement applies to. A Fabric entry is always {@link #BOTH}. */
	public enum Side {
		BOTH, CLIENT, SERVER;

		/** Parses a Forge {@code side} value; anything unrecognised or absent is {@link #BOTH}. */
		public static Side parse(String value) {
			if (value == null) return BOTH;
			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException notASide) {
				return BOTH;
			}
		}

		/** True if a requirement scoped to this side applies while running on {@code physical}. */
		public boolean appliesOn(Side physical) {
			return this == BOTH || physical == BOTH || this == physical;
		}
	}

	private final String modId;
	private final String versionConstraint;
	private final boolean mandatory;
	private final Ordering ordering;
	private final Side side;

	public UnifiedDependency(String modId, String versionConstraint, boolean mandatory) {
		this(modId, versionConstraint, mandatory, Ordering.NONE, Side.BOTH);
	}

	public UnifiedDependency(String modId, String versionConstraint, boolean mandatory,
			Ordering ordering, Side side) {
		this.modId = modId;
		this.versionConstraint = versionConstraint == null || versionConstraint.isEmpty() ? "*" : versionConstraint;
		this.mandatory = mandatory;
		this.ordering = ordering == null ? Ordering.NONE : ordering;
		this.side = side == null ? Side.BOTH : side;
	}

	public String getModId() {
		return modId;
	}

	/** A Fabric-style version predicate; {@code "*"} means "any version". */
	public String getVersionConstraint() {
		return versionConstraint;
	}

	/** {@code true} for a hard dependency that resolution must satisfy. */
	public boolean isMandatory() {
		return mandatory;
	}

	/** Where this dependency wants to sit in load order relative to {@link #getModId()}. */
	public Ordering getOrdering() {
		return ordering;
	}

	/** The physical side this requirement applies to. */
	public Side getSide() {
		return side;
	}

	/**
	 * True if {@code version} satisfies the constraint.
	 *
	 * <p>Fails OPEN, like everything in {@link VersionPredicate}: a constraint that cannot be parsed, or an unknown
	 * version, counts as satisfied. This answer is used to explain problems, and a requirement we cannot read is
	 * not evidence that one exists.
	 */
	public boolean isSatisfiedBy(String version) {
		return VersionPredicate.matches(versionConstraint, version);
	}

	/** True if this requirement is in force while running on {@code physical} ({@link Side#BOTH} if unknown). */
	public boolean appliesOn(Side physical) {
		return side.appliesOn(physical);
	}

	@Override
	public String toString() {
		return modId + " " + versionConstraint
				+ (mandatory ? "" : " (optional)")
				+ (ordering == Ordering.NONE ? "" : " " + ordering)
				+ (side == Side.BOTH ? "" : " " + side + "-only");
	}
}
