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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

/**
 * The {@code IModFileInfo} a kernel-constructed mod reports as its owning file.
 *
 * <p>It exists because NeoForge's error path dereferences it: when ANY mod-bus event listener throws,
 * {@code ModContainer.acceptEvent} → {@code ModLoadingIssue.withAffectedMod} walks
 * {@code getOwningFile().getFile().getFilePath()}. Against a null owning file that walk NPEs and MASKS the real
 * listener error — caught empirically on the client's {@code RegisterKeyMappingsEvent}, where the actual cause
 * was invisible until this chain existed.
 *
 * <p>Three of this interface's ten methods were named by the proxy this replaces; the other seven fell through
 * to a default. The version string now follows the carriers' first-mod contract as well: a null there prevented
 * Player Animation Library from constructing when it checked whether its own version contained "dev".
 *
 * <p>{@link #getConfig()} was null here, while {@code IModInfo.getConfig()} on the very same mod was carefully
 * non-null, and it was left that way until a reader was established. There is more than one. Not Enough Crashes
 * builds its description of every mod it names from {@code getOwningFile().getConfig().getConfigElement(
 * "issueTrackerURL")} and threw on the null, and NeoForge's own mod-loading crash report asks the same object for
 * the same key.
 */
public final class KernelModFileInfo implements IModFileInfo {
	private final String modId;
	private final KernelModFile file;
	private final IModInfo[] owner;
	private final IConfigurable config;

	/**
	 * @param owner  a one-slot holder back-filled with the {@link KernelModInfo} once it exists. The two types
	 *               refer to each other — a mod info has an owning file, and that file's {@code getMods()} must
	 *               return the mod info — so one of them has to be constructed second. NeoForge's title-screen
	 *               version check is what reads it back: {@code getModFileById(id).getMods().get(0)}.
	 * @param config the file's top level, or null with {@code -Dforbric.fileConfigElements=off}
	 */
	KernelModFileInfo(String modId, Path jar, IModInfo[] owner, IConfigurable config) {
		this.modId = modId;
		this.file = new KernelModFile(modId, jar);
		this.file.setModFileInfo(this);
		this.owner = owner;
		this.config = config;
	}

	@Override
	public IModFile getFile() {
		return file;
	}

	@Override
	public List<IModInfo> getMods() {
		return owner[0] != null ? List.of(owner[0]) : List.of();
	}

	/** The empty string, as before — {@code ModListScreen.updateCache} reads it straight into the info pane. */
	@Override
	public String getLicense() {
		return "";
	}

	// --- answered the way the proxy's defaultReturn answered them -----------------------------------------

	/**
	 * The top level of the mod's {@code neoforge.mods.toml}, answered as NeoForge's {@code NightConfigWrapper} over the
	 * parsed file answers it; native {@code ModFileInfo.getConfig()} is the file itself, which delegates to that
	 * wrapper. {@code issueTrackerURL} and {@code license} are here, and so is any top-level table one mod addresses to
	 * another. Null only with {@code -Dforbric.fileConfigElements=off}, as it always was before.
	 */
	@Override
	public IConfigurable getConfig() {
		return config;
	}

	/**
	 * Both carriers report the first mod's resolved version, not the raw jar manifest's version. The owner is
	 * back-filled before this file info is published, and already carries discovery's TOML/manifest substitutions.
	 */
	@Override
	public String versionString() {
		return getMods().getFirst().getVersion().toString();
	}

	/** Empty, as before. The kernel constructs mods itself and asks FML for no language loader. */
	@Override
	public List<IModFileInfo.LanguageSpec> requiredLanguageLoaders() {
		return List.of();
	}

	/** Empty, as before. */
	@Override
	public Map<String, Object> getFileProperties() {
		return Map.of();
	}

	/** Empty, as before. */
	@Override
	public List<String> usesServices() {
		return List.of();
	}

	/** False, as before. A kernel container is not a resource pack source in its own right. */
	@Override
	public boolean showAsResourcePack() {
		return false;
	}

	/** False, as before. */
	@Override
	public boolean showAsDataPack() {
		return false;
	}

	@Override
	public String toString() {
		return "KernelModFileInfo[" + modId + "]";
	}
}
