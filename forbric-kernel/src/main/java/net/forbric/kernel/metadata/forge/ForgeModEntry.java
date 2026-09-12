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

import java.util.Collections;
import java.util.List;

/**
 * One {@code [[mods]]} entry from a Forge {@code mods.toml}, plus its declared dependencies.
 */
public final class ForgeModEntry {
	private final String modId;
	private final String version;
	private final String displayName;
	private final String description;
	private final List<ForgeDependency> dependencies;

	public ForgeModEntry(String modId, String version, String displayName, String description, List<ForgeDependency> dependencies) {
		this.modId = modId;
		this.version = version;
		this.displayName = displayName;
		this.description = description;
		this.dependencies = dependencies == null ? Collections.emptyList() : Collections.unmodifiableList(dependencies);
	}

	public String getModId() {
		return modId;
	}

	/**
	 * The declared version. May still contain the literal placeholder {@code ${file.jarVersion}} if it was
	 * not resolved against the jar manifest; see {@link ModsTomlParser#resolveVersion}.
	 */
	public String getVersion() {
		return version;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getDescription() {
		return description;
	}

	public List<ForgeDependency> getDependencies() {
		return dependencies;
	}

	@Override
	public String toString() {
		return "ForgeModEntry{" + modId + "@" + version + " deps=" + dependencies.size() + '}';
	}
}
