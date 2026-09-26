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
import java.util.Map;

/**
 * One {@code [[mods]]} entry from a Forge {@code mods.toml}, plus its declared dependencies.
 */
public final class ForgeModEntry {
	private final String modId;
	private final String version;
	private final String displayName;
	private final String description;
	private final List<ForgeDependency> dependencies;
	private final Map<String, Object> properties;
	private final Map<String, Object> configElements;

	public ForgeModEntry(String modId, String version, String displayName, String description, List<ForgeDependency> dependencies) {
		this(modId, version, displayName, description, dependencies, Map.of(), Map.of());
	}

	public ForgeModEntry(String modId, String version, String displayName, String description,
			List<ForgeDependency> dependencies, Map<String, Object> properties) {
		this(modId, version, displayName, description, dependencies, properties, Map.of());
	}

	public ForgeModEntry(String modId, String version, String displayName, String description,
			List<ForgeDependency> dependencies, Map<String, Object> properties,
			Map<String, Object> configElements) {
		this.modId = modId;
		this.version = version;
		this.displayName = displayName;
		this.description = description;
		this.dependencies = dependencies == null ? Collections.emptyList() : Collections.unmodifiableList(dependencies);
		this.properties = properties == null ? Map.of() : Map.copyOf(properties);
		this.configElements = configElements == null ? Map.of() : Map.copyOf(configElements);
	}

	/**
	 * This mod's whole {@code [[mods]]} entry — its top-level keys, with a nested table left as night-config's own
	 * {@code Config} the way FML leaves it — the thing {@code IConfigurable.getConfigElement} answers from.
	 *
	 * <p>Separate from {@link #getProperties()} because they are different accessors with different readers:
	 * {@code getModProperties} answers the {@code [modproperties.<id>]} table, this answers the mod entry
	 * itself. Sodium reads {@code sodium:options} out of THIS one to let a mod switch off the sodium mixin
	 * features it has taken over.
	 */
	public Map<String, Object> getConfigElements() {
		return configElements;
	}

	/**
	 * This mod's {@code [modproperties.<modId>]} table, never null.
	 *
	 * <p>Not loader data. It is how a mod tells another mod something, and the reader is whoever looks: Sodium
	 * reads {@code sodium:config_api_user} out of it to find the class that builds the mod's Video Settings page.
	 * Shallow, as FML's is: a nested table is night-config's {@code Config}, which LibJF casts it to.
	 */
	public Map<String, Object> getProperties() {
		return properties;
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
