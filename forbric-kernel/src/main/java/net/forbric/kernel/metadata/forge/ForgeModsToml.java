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
 * The parsed contents of a Forge {@code META-INF/mods.toml} (or {@code META-INF/neoforge.mods.toml}) file.
 */
public final class ForgeModsToml {
	private final String modLoader;
	private final String loaderVersion;
	private final List<ForgeModEntry> mods;
	private final List<String> mixinConfigs;
	private final List<String> accessTransformers;

	public ForgeModsToml(String modLoader, String loaderVersion, List<ForgeModEntry> mods, List<String> mixinConfigs) {
		this(modLoader, loaderVersion, mods, mixinConfigs, Collections.emptyList());
	}

	public ForgeModsToml(String modLoader, String loaderVersion, List<ForgeModEntry> mods,
			List<String> mixinConfigs, List<String> accessTransformers) {
		this.modLoader = modLoader;
		this.loaderVersion = loaderVersion;
		this.mods = mods == null ? Collections.emptyList() : Collections.unmodifiableList(mods);
		this.mixinConfigs = mixinConfigs == null ? Collections.emptyList() : Collections.unmodifiableList(mixinConfigs);
		this.accessTransformers = accessTransformers == null ? Collections.emptyList() : Collections.unmodifiableList(accessTransformers);
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
