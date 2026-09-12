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
 * to a default. They are all stated below, each answering exactly what that default produced.
 *
 * <p>The one worth arguing about is {@link #getConfig()}: it returns null here, while {@code IModInfo.getConfig()}
 * on the very same mod is carefully non-null because a null there crashes the Mods screen on every tick. The two
 * were written at different times and the second lesson was never applied to the first. Whether anything reads
 * THIS one has not been established, so it is left as it was rather than changed on a hunch.
 */
public final class KernelModFileInfo implements IModFileInfo {
	private final String modId;
	private final KernelModFile file;
	private final IModInfo[] owner;

	/**
	 * @param owner a one-slot holder back-filled with the {@link KernelModInfo} once it exists. The two types
	 *              refer to each other — a mod info has an owning file, and that file's {@code getMods()} must
	 *              return the mod info — so one of them has to be constructed second. NeoForge's title-screen
	 *              version check is what reads it back: {@code getModFileById(id).getMods().get(0)}.
	 */
	KernelModFileInfo(String modId, Path jar, IModInfo[] owner) {
		this.modId = modId;
		this.file = new KernelModFile(modId, jar);
		this.owner = owner;
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

	/** Null, as before. Unlike {@code IModInfo.getConfig()}, nothing has been shown to dereference this one. */
	@Override
	public IConfigurable getConfig() {
		return null;
	}

	/** Null, as before. A real mod file info would report the mod's version string. */
	@Override
	public String versionString() {
		return null;
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
