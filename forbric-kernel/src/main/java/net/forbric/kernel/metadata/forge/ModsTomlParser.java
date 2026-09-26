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
import net.forbric.api.UnifiedDependency;

/**
 * Clean-room reader for a modern Forge {@code META-INF/mods.toml} (and the NeoForge
 * {@code META-INF/neoforge.mods.toml}) file.
 *
 * <p>Written against the public {@code mods.toml} schema using the same TOML library Forge/NeoForge
 * use ({@code night-config}). It deliberately contains no FML source. Output is a plain data model
 * ({@link ForgeModsToml}); turning that into Forbric's unified mod model and feeding the dependency
 * solver happens in the discovery layer (milestone P4). The one exception is the three tables a mod reads back
 * through FML — {@code [modproperties.<id>]}, the {@code [[mods]]} entry and the file's own top level — which keep
 * the value types FML gives them (see {@link #tableValues}).
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
		UnmodifiableConfig propertiesTable = getSubConfig(config, "modproperties");

		List<ForgeModEntry> mods = new ArrayList<>();

		for (UnmodifiableConfig modConfig : getConfigList(config, "mods")) {
			String modId = getString(modConfig, "modId");

			ForgeModEntry entry = new ForgeModEntry(
					modId,
					getString(modConfig, "version"),
					getString(modConfig, "displayName"),
					getString(modConfig, "description"),
					parseDependencies(dependenciesTable, modId),
					parseProperties(propertiesTable, modId),
					// The whole [[mods]] entry, shaped as its own IConfigurable sees it (see tableValues). Walked by
					// ENTRY like the properties table, for the same reason: iris' key is the single literal
					// "mixin.features.render.world.sky", and night-config's get(String) is a DOTTED PATH lookup
					// that would split it into five.
					tableValues(modConfig));

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

		// The whole file's top level, shaped the same way: what the owning ModFileInfo's getConfigElement answers
		// from. Unlit Campfire's ["lithium:options"] is a top-level table, so it is here and in no [[mods]] entry.
		return new ForgeModsToml(modLoader, loaderVersion, mods, mixinConfigs, accessTransformers, tableValues(config));
	}

	/**
	 * One mod's {@code [modproperties.<modId>]} table, with the value types FML gives it (see {@link #tableValues}).
	 *
	 * <p>The keys here are the reason this cannot use {@link #getString}: they are quoted and contain a colon
	 * ({@code "sodium:config_api_user"}), and night-config's dotted-path {@code get(String)} would split a key
	 * on a dot. Every lookup in this file goes through {@code Collections.singletonList(key)} for that reason,
	 * and the whole sub-table is walked by ENTRY rather than looked up key by key.
	 */
	private static Map<String, Object> parseProperties(UnmodifiableConfig propertiesTable, String modId) {
		if (propertiesTable == null || modId == null) return Map.of();
		UnmodifiableConfig mine = getSubConfig(propertiesTable, modId);
		if (mine == null) return Map.of();
		return tableValues(mine);
	}

	/**
	 * {@code -Dforbric.nightConfigTables=off} flattens every nested table into a {@code LinkedHashMap} again, which
	 * is what this parser handed out before — and what LibJF Config Core's {@code (Config)} cast rejects.
	 */
	public static final String NIGHT_CONFIG_TABLES = "forbric.nightConfigTables";

	/** Whether nested tables stay night-config's own {@code Config}, as FML leaves them. On unless switched off. */
	public static boolean nightConfigTables() {
		return !"off".equalsIgnoreCase(System.getProperty(NIGHT_CONFIG_TABLES, "on"));
	}

	/**
	 * A table's own entries, the way FML hands them to a mod: the table's {@code valueMap()}, SHALLOW.
	 *
	 * <p>Both FMLs build {@code IModInfo.getModProperties()} as
	 * {@code NightConfigWrapper.getConfigElement("modproperties", modId)}, which answers a table with its
	 * {@code valueMap()} (NeoForge) or an {@code ImmutableMap} of the same entries (MinecraftForge). Neither
	 * descends, so a table one level down is still night-config's own {@code Config} and an array of tables is a
	 * {@code List} of them — and mods are written against exactly that. LibJF Config Core casts
	 * {@code getModProperties().get("libjf:config")} to {@code Config} on every first launch; LibJF Translate
	 * declares that key as {@code [[modproperties.libjf_translate_v1."libjf:config"."previous_names"]]}. This parser
	 * used to flatten every level into {@code LinkedHashMap}/{@code ArrayList}, the cast threw, and LibJF Config Core
	 * failed to construct on server and client alike. The readers that accept either shape (LibJF's entry-point
	 * storage, yumi's custom values, Jade's metadata) take their {@code Config} branch here, as they do natively.
	 *
	 * <p>Handing out a {@code Config} is safe on class identity because there is exactly one night-config in the
	 * JVM: {@code DelegationPolicy} pins {@code com.electronwill.nightconfig.} to the parent loader, so the
	 * {@code Config} this parser builds IS the {@code Config} a mod links against. (The flattening was introduced on
	 * the opposite belief, that the kernel's copy was a different class from the mods'.)
	 */
	private static Map<String, Object> tableValues(UnmodifiableConfig table) {
		if (!nightConfigTables()) {
			Object plain = toPlain(table);
			return plain instanceof Map<?, ?> map ? castProperties(map) : Map.of();
		}
		return new java.util.LinkedHashMap<>(table.valueMap());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> castProperties(Map<?, ?> map) {
		return (Map<String, Object>) map;
	}

	/**
	 * night-config {@code Config}/{@code List} to {@code LinkedHashMap}/{@code ArrayList}; scalars unchanged. Only
	 * reached with {@link #NIGHT_CONFIG_TABLES} switched off.
	 */
	private static Object toPlain(Object value) {
		if (value instanceof UnmodifiableConfig cfg) {
			Map<String, Object> out = new java.util.LinkedHashMap<>();
			for (UnmodifiableConfig.Entry entry : cfg.entrySet()) out.put(entry.getKey(), toPlain(entry.getValue()));
			return out;
		}
		if (value instanceof List<?> list) {
			List<Object> out = new ArrayList<>(list.size());
			for (Object element : list) out.add(toPlain(element));
			return out;
		}
		return value;
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
					UnifiedDependency.Ordering.parse(getString(dep, "ordering")),
					UnifiedDependency.SideScope.parse(getString(dep, "side"))));
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
