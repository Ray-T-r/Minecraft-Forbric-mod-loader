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

package net.forbric.kernel.runtime;

import java.util.List;
import java.util.Map;

import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;

/**
 * The owning file a traditional-Forge mod info hands out: the jar's mods.toml top-level keys, the one mod info.
 *
 * <p>{@link #getFile()} is null: MinecraftForge's {@code IModFile} is a large surface the kernel has no use for,
 * and the one reader that walks into it (Forge's own mod-bus error reporting) hit a null owning file before this
 * class existed, so nothing is lost. What is gained is every mod that reads its own file config — wthit asks for
 * {@code issueTrackerURL} in its static initialiser and every {@code @Mod} in its jar died on the null.
 */
final class KernelForgeModFileInfo implements IModFileInfo {
	private final String modId;
	private final IModInfo owner;
	private final KernelForgeModsToml toml;

	KernelForgeModFileInfo(String modId, IModInfo owner, KernelForgeModsToml toml) {
		this.modId = modId;
		this.owner = owner;
		this.toml = toml;
	}

	@Override
	public List<IModInfo> getMods() {
		return List.of(owner);
	}

	@Override
	public List<LanguageSpec> requiredLanguageLoaders() {
		return List.of();
	}

	@Override
	public boolean showAsResourcePack() {
		return false;
	}

	@Override
	public Map<String, Object> getFileProperties() {
		return Map.of();
	}

	@Override
	public String getLicense() {
		return toml.top.getOrDefault("license", "");
	}

	@Override
	public String moduleName() {
		return modId;
	}

	@Override
	public String versionString() {
		return owner.getVersion().toString();
	}

	@Override
	public List<String> usesServices() {
		return List.of();
	}

	@Override
	public IModFile getFile() {
		return null;
	}

	@Override
	public IConfigurable getConfig() {
		return new KernelForgeConfigurable(toml.top, toml.mods);
	}

	@Override
	public String toString() {
		return "KernelForgeModFileInfo[" + modId + "]";
	}
}
