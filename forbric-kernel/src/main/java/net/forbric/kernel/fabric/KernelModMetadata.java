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

package net.forbric.kernel.fabric;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;

/**
 * The kernel's {@link ModMetadata} — one parsed {@code fabric.mod.json}.
 *
 * <p>Beyond the mod-facing interface it also carries the loader-facing declarations the kernel acts on
 * ({@linkplain #getEntrypoints() entrypoints}, {@linkplain #getMixinConfigs() mixin configs},
 * {@linkplain #getAccessWidener() access widener}, {@linkplain #getNestedJars() nested jars}). Fabric Loader
 * keeps those in a separate internal metadata type; the kernel has no split between public and internal
 * metadata, so they live here.
 */
public final class KernelModMetadata implements ModMetadata {
	private final String type;
	private final String id;
	private final Collection<String> provides;
	private final Version version;
	private final ModEnvironment environment;
	private final Collection<ModDependency> dependencies;
	private final String name;
	private final String description;
	private final Collection<Person> authors;
	private final Collection<Person> contributors;
	private final ContactInformation contact;
	private final Collection<String> license;
	private final Map<Integer, String> icons;
	private final Map<String, CustomValue> customValues;

	private final Map<String, List<EntrypointDecl>> entrypoints;
	private final List<MixinConfigDecl> mixinConfigs;
	private final String accessWidener;
	private final List<String> nestedJars;
	private final Map<String, String> languageAdapters;

	KernelModMetadata(String type, String id, Collection<String> provides, Version version, ModEnvironment environment,
			Collection<ModDependency> dependencies, String name, String description, Collection<Person> authors,
			Collection<Person> contributors, ContactInformation contact, Collection<String> license,
			Map<Integer, String> icons, Map<String, CustomValue> customValues,
			Map<String, List<EntrypointDecl>> entrypoints, List<MixinConfigDecl> mixinConfigs, String accessWidener,
			List<String> nestedJars, Map<String, String> languageAdapters) {
		this.type = type;
		this.id = id;
		this.provides = Collections.unmodifiableCollection(provides);
		this.version = version;
		this.environment = environment;
		this.dependencies = Collections.unmodifiableCollection(dependencies);
		this.name = name;
		this.description = description;
		this.authors = Collections.unmodifiableCollection(authors);
		this.contributors = Collections.unmodifiableCollection(contributors);
		this.contact = contact;
		this.license = Collections.unmodifiableCollection(license);
		this.icons = Collections.unmodifiableMap(icons);
		this.customValues = Collections.unmodifiableMap(customValues);
		this.entrypoints = Collections.unmodifiableMap(entrypoints);
		this.mixinConfigs = Collections.unmodifiableList(mixinConfigs);
		this.accessWidener = accessWidener;
		this.nestedJars = Collections.unmodifiableList(nestedJars);
		this.languageAdapters = Collections.unmodifiableMap(languageAdapters);
	}

	/**
	 * A synthetic {@code builtin} mod — {@code minecraft}, {@code java}, {@code fabricloader}. Mods declare hard
	 * dependencies on these and query them through {@code getModContainer(...)}, so they must be present in the
	 * mod set even though no jar provides them.
	 */
	public static KernelModMetadata builtin(String id, String version, String name) {
		Version parsed;

		try {
			parsed = KernelVersion.parse(version);
		} catch (net.fabricmc.loader.api.VersionParsingException e) {
			throw new IllegalArgumentException("builtin mod " + id + " has an unparseable version " + version, e);
		}

		return new KernelModMetadata("builtin", id, List.of(), parsed, ModEnvironment.UNIVERSAL, List.of(), name, "",
				List.of(), List.of(), ContactInformation.EMPTY, List.of(), Map.of(), Map.of(), Map.of(), List.of(),
				null, List.of(), Map.of());
	}

	// --- loader-facing (kernel) ---

	/** Entrypoint declarations by key ({@code main}, {@code client}, {@code server}, {@code preLaunch}, custom…). */
	public Map<String, List<EntrypointDecl>> getEntrypoints() {
		return entrypoints;
	}

	/** Declared mixin configs, each with the side it applies on. */
	public List<MixinConfigDecl> getMixinConfigs() {
		return mixinConfigs;
	}

	/**
	 * The {@code languageAdapters} this mod provides: adapter name → the class implementing
	 * {@code net.fabricmc.loader.api.LanguageAdapter}. Declared by one mod, named by others in their entrypoints.
	 */
	public Map<String, String> getLanguageAdapters() {
		return languageAdapters;
	}

	/** The declared {@code accessWidener} / {@code .classtweaker} resource path, or {@code null}. */
	public String getAccessWidener() {
		return accessWidener;
	}

	/** Jar-relative paths of JiJ-nested mods ({@code "jars": [{"file": …}]}). */
	public List<String> getNestedJars() {
		return nestedJars;
	}

	// --- mod-facing (ModMetadata) ---

	@Override
	public String getType() {
		return type;
	}

	@Override
	public String getId() {
		return id;
	}

	@Override
	public Collection<String> getProvides() {
		return provides;
	}

	@Override
	public Version getVersion() {
		return version;
	}

	@Override
	public ModEnvironment getEnvironment() {
		return environment;
	}

	@Override
	public Collection<ModDependency> getDependencies() {
		return dependencies;
	}

	@Override
	public String getName() {
		return name == null || name.isEmpty() ? id : name;
	}

	@Override
	public String getDescription() {
		return description == null ? "" : description;
	}

	@Override
	public Collection<Person> getAuthors() {
		return authors;
	}

	@Override
	public Collection<Person> getContributors() {
		return contributors;
	}

	@Override
	public ContactInformation getContact() {
		return contact;
	}

	@Override
	public Collection<String> getLicense() {
		return license;
	}

	@Override
	public Optional<String> getIconPath(int size) {
		if (icons.isEmpty()) return Optional.empty();

		// Smallest icon >= the requested size; failing that, the largest available. (Fabric's contract.)
		int best = -1;

		for (int available : icons.keySet()) {
			if (available >= size && (best < 0 || available < best)) best = available;
		}

		if (best < 0) {
			for (int available : icons.keySet()) {
				if (available > best) best = available;
			}
		}

		return Optional.ofNullable(icons.get(best));
	}

	@Override
	public boolean containsCustomValue(String key) {
		return customValues.containsKey(key);
	}

	@Override
	public CustomValue getCustomValue(String key) {
		return customValues.get(key);
	}

	@Override
	public Map<String, CustomValue> getCustomValues() {
		return customValues;
	}

	@Override
	@Deprecated
	public boolean containsCustomElement(String key) {
		return containsCustomValue(key);
	}

	@Override
	public String toString() {
		return id + "@" + version.getFriendlyString();
	}

	/** One entrypoint declaration: a value plus the language adapter that turns it into an instance. */
	public record EntrypointDecl(String adapter, String value) {
		public boolean isDefaultAdapter() {
			return adapter == null || adapter.equals("default");
		}
	}

	/** One declared mixin config plus the side it applies on. */
	public record MixinConfigDecl(String config, ModEnvironment environment) {
	}
}
