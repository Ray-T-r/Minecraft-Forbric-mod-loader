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

package net.forbric.loader.impl.metadata;

/**
 * A mod dependency in Forbric's unified model, normalized from a Fabric {@code depends}/{@code recommends}
 * entry or a Forge {@code [[dependencies]]} entry.
 *
 * <p>The {@link #getVersionConstraint() version constraint} is kept as a Fabric-style predicate string
 * (e.g. {@code ">=0.15.0"}, {@code ">=47 <48"}). Forge Maven ranges are translated into this form by
 * {@code ForgeVersionRangeTranslator} when the dependency is built.
 */
public final class UnifiedDependency {
	private final String modId;
	private final String versionConstraint;
	private final boolean mandatory;

	public UnifiedDependency(String modId, String versionConstraint, boolean mandatory) {
		this.modId = modId;
		this.versionConstraint = versionConstraint == null || versionConstraint.isEmpty() ? "*" : versionConstraint;
		this.mandatory = mandatory;
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

	@Override
	public String toString() {
		return modId + " " + versionConstraint + (mandatory ? "" : " (optional)");
	}
}
