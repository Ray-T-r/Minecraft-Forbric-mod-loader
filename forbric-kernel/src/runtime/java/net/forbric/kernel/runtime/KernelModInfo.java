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

import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.IModLanguageLoader;
import net.neoforged.neoforgespi.locating.ForgeFeature;

/**
 * The {@code IModInfo} a kernel-constructed {@code ModContainer} carries.
 *
 * <p>Fourteen of this interface's fifteen methods were named by the {@link java.lang.reflect.Proxy} this replaces.
 * That proxy was, by a distance, the most careful of the four — three of its answers carry a comment naming the
 * exact crash that taught it — which is precisely why it is worth having as a class: those lessons are attached
 * to a method signature now, so a rename in a future NeoForge is a build failure rather than a silent return to
 * the crash.
 *
 * <p>Its one unnamed method was {@link #getLoader()}, which the fallback answered with null. It still does.
 */
public final class KernelModInfo implements IModInfo {
	private static final ArtifactVersion UNKNOWN_VERSION = new DefaultArtifactVersion("0.0");

	private final String modId;
	private final IModFileInfo owningFile;
	private final IConfigurable config;

	public KernelModInfo(String modId, Path jar) {
		this.modId = modId;
		this.config = new KernelConfigurable(modId);

		// The owning file's getMods() has to return THIS object, so it is handed a slot to read back from.
		IModInfo[] self = new IModInfo[1];
		this.owningFile = new KernelModFileInfo(modId, jar, self);
		self[0] = this;
	}

	@Override
	public String getModId() {
		return modId;
	}

	@Override
	public String getNamespace() {
		return modId;
	}

	@Override
	public String getDisplayName() {
		return modId;
	}

	@Override
	public String getDescription() {
		return "";
	}

	/**
	 * Never null: {@code ModListScreen.init} renders each mod's version through
	 * {@code MavenVersionTranslator.artifactVersionToString(getVersion())}, which calls {@code toString()}
	 * unguarded. A null crashed the Mods screen the instant it opened, and then its tick NPE'd on the half-built
	 * modList. "0.0" is the conventional unknown-version placeholder.
	 */
	@Override
	public ArtifactVersion getVersion() {
		return UNKNOWN_VERSION;
	}

	/**
	 * Never null: {@code ModListScreen.updateCache} runs from the screen's TICK — so on every frame the Mods list
	 * is open — and calls {@code getConfig().getConfigElement(...)} unguarded. A null crashed the client the
	 * moment that list was shown again, which is what closing a mod's config screen with Done does.
	 */
	@Override
	public IConfigurable getConfig() {
		return config;
	}

	/**
	 * Never null: NeoForge's error path {@code ModContainer.acceptEvent} → {@code ModLoadingIssue.withAffectedMod}
	 * walks {@code getOwningFile().getFile().getFilePath()} whenever a mod-bus listener throws. Against a null
	 * that walk NPEs and MASKS the real listener error.
	 */
	@Override
	public IModFileInfo getOwningFile() {
		return owningFile;
	}

	// --- answered the way the proxy answered them ---------------------------------------------------------

	@Override
	public Map<String, Object> getModProperties() {
		return Map.of();
	}

	@Override
	public List<? extends IModInfo.ModVersion> getDependencies() {
		return List.of();
	}

	@Override
	public List<? extends ForgeFeature.Bound> getForgeFeatures() {
		return List.of();
	}

	@Override
	public Optional<URL> getUpdateURL() {
		return Optional.empty();
	}

	@Override
	public Optional<URL> getModURL() {
		return Optional.empty();
	}

	@Override
	public Optional<String> getLogoFile() {
		return Optional.empty();
	}

	@Override
	public boolean getLogoBlur() {
		return false;
	}

	/**
	 * Null, as before — the one method the proxy never named, so it came back through the default and nothing
	 * recorded that it had. A kernel mod has no FML language loader: the kernel constructs {@code @Mod} classes
	 * itself rather than asking javafmlmod to.
	 */
	@Override
	public IModLanguageLoader getLoader() {
		return null;
	}

	@Override
	public String toString() {
		return "KernelModInfo[" + modId + "]";
	}
}
