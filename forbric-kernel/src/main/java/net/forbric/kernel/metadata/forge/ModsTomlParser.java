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

import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

/**
 * Clean-room reader for a modern Forge {@code META-INF/mods.toml} (and the NeoForge
 * {@code META-INF/neoforge.mods.toml}) file.
 *
 * <p>Written against the public {@code mods.toml} schema using the same TOML library Forge/NeoForge
 * use ({@code night-config}). It deliberately contains no FML source. Output is a plain data model
 * ({@link ForgeModsToml}); turning that into Forbric's unified mod model and feeding the dependency
 * solver happens in the discovery layer (milestone P4).
 */
public final class ModsTomlParser {
	private ModsTomlParser() {
	}

	public static ForgeModsToml parse(String toml) {
		return parse(new StringReader(toml));
	}

	public static ForgeModsToml parse(InputStream in) {
		return parse(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
	}

	public static ForgeModsToml parse(Reader reader) {
		UnmodifiableConfig config = new TomlParser().parse(reader);

		String modLoader = getString(config, "modLoader");
		String loaderVersion = getString(config, "loaderVersion");

		UnmodifiableConfig dependenciesTable = getSubConfig(config, "dependencies");

		List<ForgeModEntry> mods = new ArrayList<>();

		for (UnmodifiableConfig modConfig : getConfigList(config, "mods")) {
			String modId = getString(modConfig, "modId");

			ForgeModEntry entry = new ForgeModEntry(
					modId,
					getString(modConfig, "version"),
					getString(modConfig, "displayName"),
					getString(modConfig, "description"),
					parseDependencies(dependenciesTable, modId));

			mods.add(entry);
		}

		List<String> mixinConfigs = new ArrayList<>();

		for (UnmodifiableConfig mixin : getConfigList(config, "mixins")) {
			String mixinConfig = getString(mixin, "config");
			if (mixinConfig != null) mixinConfigs.add(mixinConfig);
		}

		// NeoForge declares Access Transformers as [[accessTransformers]] file="..." (Forge uses the default path).
		List<String> accessTransformers = new ArrayList<>();

		for (UnmodifiableConfig at : getConfigList(config, "accessTransformers")) {
			String file = getString(at, "file");
			if (file != null) accessTransformers.add(file);
		}

		return new ForgeModsToml(modLoader, loaderVersion, mods, mixinConfigs, accessTransformers);
	}

	private static List<ForgeDependency> parseDependencies(UnmodifiableConfig dependenciesTable, String modId) {
		if (dependenciesTable == null || modId == null) return Collections.emptyList();

		List<ForgeDependency> result = new ArrayList<>();

		for (UnmodifiableConfig dep : getConfigList(dependenciesTable, modId)) {
			// Forge uses `mandatory = true/false`; NeoForge uses `type = "required"/"optional"`. Support both.
			boolean mandatory;
			String type = getString(dep, "type");

			if (type != null) {
				mandatory = "required".equalsIgnoreCase(type.trim());
			} else {
				mandatory = getBoolean(dep, "mandatory", true);
			}

			result.add(new ForgeDependency(
					getString(dep, "modId"),
					mandatory,
					getString(dep, "versionRange"),
					ForgeDependency.Ordering.parse(getString(dep, "ordering")),
					ForgeDependency.Side.parse(getString(dep, "side"))));
		}

		return result;
	}

	/**
	 * Substitutes the common {@code ${file.jarVersion}} placeholder a {@code mods.toml} version field may
	 * carry, using the value from the jar manifest's {@code Implementation-Version}.
	 *
	 * @param raw        the raw version string (may be {@code null})
	 * @param jarVersion the manifest version, or {@code null} to leave the placeholder in place
	 */
	public static String resolveVersion(String raw, String jarVersion) {
		if (raw == null || jarVersion == null) return raw;
		return raw.replace("${file.jarVersion}", jarVersion);
	}

	// --- night-config accessors (single-element paths to avoid TOML dot-path interpretation) ---

	private static String getString(UnmodifiableConfig config, String key) {
		Object value = config.get(Collections.singletonList(key));
		return value == null ? null : value.toString();
	}

	private static boolean getBoolean(UnmodifiableConfig config, String key, boolean def) {
		Object value = config.get(Collections.singletonList(key));

		if (value instanceof Boolean) return (Boolean) value;
		if (value instanceof String) return Boolean.parseBoolean((String) value);

		return def;
	}

	private static UnmodifiableConfig getSubConfig(UnmodifiableConfig config, String key) {
		Object value = config.get(Collections.singletonList(key));
		return value instanceof UnmodifiableConfig ? (UnmodifiableConfig) value : null;
	}

	@SuppressWarnings("unchecked")
	private static List<UnmodifiableConfig> getConfigList(UnmodifiableConfig config, String key) {
		Object value = config.get(Collections.singletonList(key));

		if (!(value instanceof List)) return Collections.emptyList();

		List<UnmodifiableConfig> result = new ArrayList<>();

		for (Object element : (List<?>) value) {
			if (element instanceof UnmodifiableConfig) {
				result.add((UnmodifiableConfig) element);
			} else if (element instanceof Map) {
				// Defensive: some configurations expose tables as raw maps.
				result.add(com.electronwill.nightconfig.core.Config.wrap((Map<String, Object>) element,
						com.electronwill.nightconfig.toml.TomlFormat.instance()));
			}
		}

		return result;
	}
}
