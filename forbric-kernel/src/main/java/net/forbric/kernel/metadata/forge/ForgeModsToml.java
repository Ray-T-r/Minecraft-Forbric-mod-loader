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
 * The parsed contents of a Forge {@code META-INF/mods.toml} (or {@code META-INF/neoforge.mods.toml}) file.
 */
public final class ForgeModsToml {
	private final String modLoader;
	private final String loaderVersion;
	private final List<ForgeModEntry> mods;
	private final List<String> mixinConfigs;
	private final List<String> accessTransformers;
	private final Map<String, Object> configElements;

	public ForgeModsToml(String modLoader, String loaderVersion, List<ForgeModEntry> mods, List<String> mixinConfigs) {
		this(modLoader, loaderVersion, mods, mixinConfigs, Collections.emptyList());
	}

	public ForgeModsToml(String modLoader, String loaderVersion, List<ForgeModEntry> mods,
			List<String> mixinConfigs, List<String> accessTransformers) {
		this(modLoader, loaderVersion, mods, mixinConfigs, accessTransformers, Map.of());
	}

	public ForgeModsToml(String modLoader, String loaderVersion, List<ForgeModEntry> mods,
			List<String> mixinConfigs, List<String> accessTransformers, Map<String, Object> configElements) {
		this.modLoader = modLoader;
		this.loaderVersion = loaderVersion;
		this.mods = mods == null ? Collections.emptyList() : Collections.unmodifiableList(mods);
		this.mixinConfigs = mixinConfigs == null ? Collections.emptyList() : Collections.unmodifiableList(mixinConfigs);
		this.accessTransformers = accessTransformers == null ? Collections.emptyList() : Collections.unmodifiableList(accessTransformers);
		this.configElements = configElements == null ? Map.of() : Map.copyOf(configElements);
	}

	/**
	 * The file's top level, never null — its keys, with a table left as night-config's own {@code Config} the way FML
	 * leaves it. This is what the owning {@code ModFileInfo.getConfigElement} answers from, which is a different
	 * object from any one mod's {@code [[mods]]} entry: {@code issueTrackerURL} and {@code license} live here, and so
	 * does any top-level table a mod addresses to another mod, like Unlit Campfire's {@code ["lithium:options"]}.
	 */
	public Map<String, Object> getConfigElements() {
		return configElements;
	}

	/** The declared mod-loading language, e.g. {@code "javafml"} (or {@code "lowcodefml"} / {@code "kotlinforforge"}). */
	public String getModLoader() {
		return modLoader;
	}

	/** The accepted loader version range, e.g. {@code "[47,)"}. */
	public String getLoaderVersion() {
		return loaderVersion;
	}

	/** The mods declared in this file; usually one, but a single jar may declare several. */
	public List<ForgeModEntry> getMods() {
		return mods;
	}

	/** Top-level {@code [[mixins]] config="..."} entries (the modern Forge/NeoForge way to declare Mixin configs). */
	public List<String> getMixinConfigs() {
		return mixinConfigs;
	}

	/** Top-level {@code [[accessTransformers]] file="..."} entries (NeoForge declares its ATs here). */
	public List<String> getAccessTransformers() {
		return accessTransformers;
	}

	@Override
	public String toString() {
		return "ForgeModsToml{loader=" + modLoader + " loaderVersion=" + loaderVersion
				+ " mods=" + mods + " mixins=" + mixinConfigs + '}';
	}
}
