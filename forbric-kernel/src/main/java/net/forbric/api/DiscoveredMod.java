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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;


/**
 * One mod, normalized into Forbric's unified model regardless of which ecosystem declared it.
 *
 * <p>This is the single shape the discovery pass produces from both {@code fabric.mod.json} and Forge
 * {@code mods.toml}. The dependency resolver, the mapping/remap stage, and the lifecycle orchestrator
 * all consume {@code DiscoveredMod}; only {@link #getEcosystem()} tells them which runtime conventions
 * (namespace, refmap, lifecycle) to apply.
 */
public final class DiscoveredMod {
	private final Ecosystem ecosystem;
	private final String id;
	private final String version;
	private final String displayName;
	private final List<UnifiedDependency> dependencies;
	private final List<String> mixinConfigs;
	private final String accessConfig;
	private final List<String> accessTransformers;
	private final String source;
	private final List<String> aliases;
	private final Map<String, Object> modProperties;
	private final Map<String, Object> configElements;

	public DiscoveredMod(Ecosystem ecosystem, String id, String version, String displayName,
			List<UnifiedDependency> dependencies, List<String> mixinConfigs, String accessConfig, String source) {
		this(ecosystem, id, version, displayName, dependencies, mixinConfigs, accessConfig, Collections.emptyList(), source);
	}

	public DiscoveredMod(Ecosystem ecosystem, String id, String version, String displayName,
			List<UnifiedDependency> dependencies, List<String> mixinConfigs, String accessConfig,
			List<String> accessTransformers, String source) {
		this(ecosystem, id, version, displayName, dependencies, mixinConfigs, accessConfig, accessTransformers,
				source, Collections.emptyList(), Map.of(), Map.of());
	}

	private DiscoveredMod(Ecosystem ecosystem, String id, String version, String displayName,
			List<UnifiedDependency> dependencies, List<String> mixinConfigs, String accessConfig,
			List<String> accessTransformers, String source, List<String> aliases,
			Map<String, Object> modProperties, Map<String, Object> configElements) {
		this.ecosystem = ecosystem;
		this.id = id;
		this.version = version;
		this.displayName = displayName;
		this.dependencies = frozen(dependencies);
		this.mixinConfigs = frozen(mixinConfigs);
		this.accessConfig = accessConfig;
		this.accessTransformers = frozen(accessTransformers);
		this.source = source;
		this.aliases = frozen(aliases);
		this.modProperties = modProperties == null ? Map.of() : Map.copyOf(modProperties);
		this.configElements = configElements == null ? Map.of() : Map.copyOf(configElements);
	}

	/**
	 * A copy of this mod that also answers to {@code aliases} — Fabric's {@code provides}.
	 *
	 * <p>A copy rather than a tenth constructor argument: every existing caller passes what it has, and a
	 * mod's aliases are known in exactly one place, where its own metadata is read.
	 */
	public DiscoveredMod withAliases(List<String> aliases) {
		return new DiscoveredMod(ecosystem, id, version, displayName, dependencies, mixinConfigs, accessConfig,
				accessTransformers, source, aliases, modProperties, configElements);
	}

	/**
	 * A copy of this mod carrying the {@code [modproperties.<id>]} table its own metadata declared.
	 *
	 * <p>A copy for the same reason {@link #withAliases} is one. The table is a mod's way of telling ANOTHER
	 * mod something the loader itself has no opinion about, and it is read through
	 * {@code IModInfo.getModProperties()} by whoever cares: Sodium looks up {@code sodium:config_api_user} there
	 * to find the class that builds a mod's page in Video Settings, Jade looks up {@code jade}. The kernel used
	 * to answer every such question with an empty map, so iris declared its Sodium config entry point correctly,
	 * in its own {@code neoforge.mods.toml}, and its options page did not exist.
	 */
	public DiscoveredMod withModProperties(Map<String, Object> properties) {
		return new DiscoveredMod(ecosystem, id, version, displayName, dependencies, mixinConfigs, accessConfig,
				accessTransformers, source, aliases, properties, configElements);
	}

	/**
	 * A copy carrying this mod's whole {@code [[mods]]} entry, which is what
	 * {@code IConfigurable.getConfigElement} answers from.
	 *
	 * <p>A different accessor from {@link #withModProperties}, with a different reader. Sodium reads
	 * {@code sodium:options} out of THIS one, to let a mod switch off the sodium mixin features it has taken
	 * over — iris does exactly that for the sky it renders itself.
	 */
	public DiscoveredMod withConfigElements(Map<String, Object> elements) {
		return new DiscoveredMod(ecosystem, id, version, displayName, dependencies, mixinConfigs, accessConfig,
				accessTransformers, source, aliases, modProperties, elements);
	}

	/**
	 * This mod's {@code [[mods]]} entry, never null: its top-level keys, with a nested table left as night-config's
	 * own {@code Config}, as FML leaves it. The kernel's {@code IConfigurable} answers a table with its
	 * {@code valueMap()}, which is what NeoForge's wrapper hands a mod.
	 */
	public Map<String, Object> getConfigElements() {
		return configElements;
	}

	/**
	 * The {@code [modproperties.<id>]} table, never null.
	 *
	 * <p>With the value types FML gives a mod, because mods are written against them: the table is SHALLOW, so a
	 * scalar or list is itself, a nested table is night-config's own {@code Config} and an array of tables a
	 * {@code List} of them. LibJF Config Core casts {@code get("libjf:config")} straight to {@code Config}; when this
	 * handed it a {@code LinkedHashMap} instead, LibJF Config Core failed to construct on every launch. There is one
	 * night-config in the JVM (the kernel's class loader pins the package to its parent), so the {@code Config} here
	 * is the one a mod links against. {@code -Dforbric.nightConfigTables=off} flattens it to plain maps again.
	 */
	public Map<String, Object> getModProperties() {
		return modProperties;
	}

	/**
	 * Other ids this mod answers to, beyond {@link #getId()}.
	 *
	 * <p>Fabric's {@code provides}, and it is not decoration: LibJF's modules are ALL named this way — the mod
	 * id is {@code libjf-base} and the id everything depends on is {@code libjf_base}. A consumer that indexes
	 * only {@link #getId()} reports a mod that is right there as absent, and answers {@code isModLoaded} with a
	 * confident, wrong no.
	 *
	 * <p>MinecraftForge and NeoForge have no equivalent, so this is empty for them.
	 */
	public List<String> getAliases() {
		return aliases;
	}

	/**
	 * An unmodifiable COPY, with null elements dropped.
	 *
	 * <p>{@code Collections.unmodifiableList} was here, and it returns a VIEW: the caller keeps a reference to the
	 * backing list, so a mod record built from a list that is later added to reports members it was never
	 * constructed with. Nothing does that today, which is why it has cost nothing — but this is a value type that
	 * gets published into {@code ModPresence} and read from several threads during boot, and "unmodifiable" is a
	 * promise callers are entitled to rely on.
	 *
	 * <p>Nulls are dropped rather than thrown on, for the reason {@code ModPresence.usable} records: a throwing
	 * constructor here fails inside a caller that degrades to "no mods at all", so one bad element would cost the
	 * whole list instead of itself.
	 */
	private static <T> List<T> frozen(List<T> values) {
		if (values == null || values.isEmpty()) return Collections.emptyList();

		List<T> copy = new ArrayList<>(values.size());
		for (T value : values) {
			if (value != null) copy.add(value);
		}
		return Collections.unmodifiableList(copy);
	}

	public Ecosystem getEcosystem() {
		return ecosystem;
	}

	public String getId() {
		return id;
	}

	public String getVersion() {
		return version;
	}

	public String getDisplayName() {
		return displayName;
	}

	public List<UnifiedDependency> getDependencies() {
		return dependencies;
	}

	/** Mixin config resource names declared by the mod (intermediary-refmap for Fabric, SRG/Mojmap-refmap for Forge). */
	public List<String> getMixinConfigs() {
		return mixinConfigs;
	}

	/** The mod's access-edit file: a Fabric {@code .accesswidener}, or {@code null} (Forge ATs are listed separately). */
	public String getAccessConfig() {
		return accessConfig;
	}

	/** The mod's Forge/NeoForge Access Transformer config paths (jar-relative), or empty. */
	public List<String> getAccessTransformers() {
		return accessTransformers;
	}

	/** Where the mod was found (jar path or directory). */
	public String getSource() {
		return source;
	}

	@Override
	public String toString() {
		return "DiscoveredMod{" + ecosystem + " " + id + "@" + version
				+ " deps=" + dependencies.size()
				+ " mixins=" + mixinConfigs.size()
				+ '}';
	}
}
