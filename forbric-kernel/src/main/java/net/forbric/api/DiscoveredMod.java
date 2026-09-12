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

	public DiscoveredMod(Ecosystem ecosystem, String id, String version, String displayName,
			List<UnifiedDependency> dependencies, List<String> mixinConfigs, String accessConfig, String source) {
		this(ecosystem, id, version, displayName, dependencies, mixinConfigs, accessConfig, Collections.emptyList(), source);
	}

	public DiscoveredMod(Ecosystem ecosystem, String id, String version, String displayName,
			List<UnifiedDependency> dependencies, List<String> mixinConfigs, String accessConfig,
			List<String> accessTransformers, String source) {
		this.ecosystem = ecosystem;
		this.id = id;
		this.version = version;
		this.displayName = displayName;
		this.dependencies = frozen(dependencies);
		this.mixinConfigs = frozen(mixinConfigs);
		this.accessConfig = accessConfig;
		this.accessTransformers = frozen(accessTransformers);
		this.source = source;
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
