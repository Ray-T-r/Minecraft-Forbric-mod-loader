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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import net.minecraftforge.forgespi.language.IConfigurable;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.ForgeFeature;

/**
 * The {@code IModInfo} a kernel-manufactured TRADITIONAL-Forge container carries.
 *
 * <p>Traditional Forge and NeoForge each have their own {@code IModInfo} — {@code net.minecraftforge.forgespi.*}
 * against {@code net.neoforged.neoforgespi.*} — so this is a separate type from {@link KernelModInfo}, not a
 * parameterisation of it. The two interfaces happen to be close in shape here, but nothing guarantees they stay
 * that way and a shared implementation could not name both.
 *
 * <h2>The asymmetry this class made visible</h2>
 *
 * <p>It replaces a {@link java.lang.reflect.Proxy} that named 12 of this interface's 14 methods and sent the rest
 * to a {@code defaultReturn}. The two it did not name are {@link #getConfig()} and {@link #getOwningFile()} — and
 * those are precisely the two that the NEOFORGE twin ({@link KernelModInfo}) makes non-null, each with a comment
 * naming the crash that taught it.
 *
 * <p>That is a striking coincidence and it is NOT the obvious story. The obvious story would be "the lesson was
 * learned on one side and never carried to the other", and it is wrong: the Forge side DID carry it, in
 * {@code PassiveSeeder.buildForgeModInfo}, which builds real {@code moddiscovery.ModInfo} records with a non-null
 * config and a real owning file whose license is {@code ""} — with a comment saying the Mods screen writes the
 * license into its info pane unguarded. Two different objects answer "what is this mod's info": the seeded record
 * and this one. Only the first ever reaches Forge's own readers.
 *
 * <p>Which is why both still return null here. Every non-constructor caller in traditional Forge that
 * dereferences these reads {@code ModList.SORTED_LIST}, and {@code SORTED_LIST} is a {@code private static final}
 * assigned once in {@code ModList.<clinit>} from {@code LoadingModList}; {@code ModList.setLoadedMods}, which is
 * what the kernel calls, writes {@code mods}/{@code sortedContainers}/{@code indexedMods} and cannot touch it.
 * The mods GUI never asks a container for its {@code IModInfo} at all — {@code getModInfo} appears zero times in
 * {@code ModListScreen}, its info panel, and the list widget.
 *
 * <p>And for {@link #getOwningFile()} there is a second, stronger reason not to guess: the one site that would
 * want a non-null value, {@code ModListScreen.updateCache}, follows it with a {@code checkcast} to the CONCRETE
 * {@code moddiscovery.ModFileInfo}. A file info written here would fail that cast, turning an NPE into a
 * ClassCastException — which is not a fix. The only answer that would count is handing over the real
 * {@code ModFileInfo} the seeder already built for that jar, and that is a wiring change between two components,
 * not a line in this file.
 */
public final class KernelForgeModInfo implements IModInfo {
	private static final ArtifactVersion UNKNOWN_VERSION = new DefaultArtifactVersion("0.0");

	private final String modId;

	public KernelForgeModInfo(String modId) {
		this.modId = modId;
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

	/** Non-null like the NeoForge twin: a mod-list UI renders {@code getVersion().toString()} unguarded. */
	@Override
	public ArtifactVersion getVersion() {
		return UNKNOWN_VERSION;
	}

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

	// --- the two the proxy never named, still answering what its fallback answered ------------------------

	/** Null, as before. See the class javadoc. */
	@Override
	public IConfigurable getConfig() {
		return null;
	}

	/** Null, as before. See the class javadoc — a non-null value here would fail a checkcast, not fix anything. */
	@Override
	public IModFileInfo getOwningFile() {
		return null;
	}

	@Override
	public String toString() {
		return "KernelForgeModInfo[" + modId + "]";
	}
}
