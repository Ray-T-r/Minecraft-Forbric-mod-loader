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
public final class KernelModMetadata implements ModMetadata, net.fabricmc.loader.impl.metadata.LoaderModMetadata {
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
		return builtin(id, version, name, Map.of());
	}

	/**
	 * The same synthetic mod, carrying custom values.
	 *
	 * <p>Used for the presence registration of a mod the OTHER family loaded. A Forge-family mod declares in
	 * {@code [modproperties.<id>]} exactly what a Fabric mod declares in its {@code custom} block — the keys are
	 * even written in Fabric's namespaced spelling ({@code fabric-renderer-api-v1:contains_renderer}) — and the
	 * readers are Fabric mods calling {@code containsCustomValue}. Registering the identity without them answers
	 * "that mod is here" and "it offers nothing", which is a different, wrong answer.
	 */
	public static KernelModMetadata builtin(String id, String version, String name,
			Map<String, CustomValue> customValues) {
		return builtin(id, version, name, customValues, Map.of());
	}

	/**
	 * The same synthetic mod, also carrying the entrypoints the other family's metadata declares in its own spelling
	 * (see {@code CrossEcosystemDeclarations.fabricEntrypoints}): a Forge-family mod that names its Sodium options
	 * class in {@code [modproperties]} is read by a Fabric Sodium through {@code getEntrypointContainers}, exactly as
	 * that mod's own Fabric build would have declared it.
	 */
	public static KernelModMetadata builtin(String id, String version, String name,
			Map<String, CustomValue> customValues, Map<String, List<EntrypointDecl>> entrypoints) {
		Version parsed;

		try {
			parsed = KernelVersion.parse(version);
		} catch (net.fabricmc.loader.api.VersionParsingException e) {
			throw new IllegalArgumentException("builtin mod " + id + " has an unparseable version " + version, e);
		}

		return new KernelModMetadata("builtin", id, List.of(), parsed, ModEnvironment.UNIVERSAL, List.of(), name, "",
				List.of(), List.of(), ContactInformation.EMPTY, List.of(), Map.of(),
				customValues == null ? Map.of() : Map.copyOf(customValues),
				entrypoints == null ? Map.of() : Map.copyOf(entrypoints), List.of(), null, List.of(), Map.of());
	}

	/**
	 * A presence identity that shows itself as {@code display} does: its name, description, people, contact, licence
	 * and icon. Nothing else is taken from {@code display} -- no dependencies, provides, environment, entrypoints, mixins
	 * or nested jars -- because none of those is this identity's: the mod runs under the family that won it.
	 *
	 * <p>For a mod whose Fabric build lost arbitration: that build's own {@code fabric.mod.json} is how the mod presents
	 * itself to every Fabric mod list, and an identity carrying only the id made it a row named by its id with no icon
	 * and no description on Mod Menu, beside every other mod showing its own.
	 */
	public static KernelModMetadata presenceShownAs(KernelModMetadata display, String id, String version,
			Map<String, CustomValue> customValues, Map<String, List<EntrypointDecl>> entrypoints) {
		KernelModMetadata bare = builtin(id, version, display == null ? id : display.getName(), customValues, entrypoints);
		if (display == null) return bare;
		return new KernelModMetadata(bare.type, bare.id, bare.provides, bare.version, bare.environment, bare.dependencies,
				display.name, display.description, display.authors, display.contributors, display.contact, display.license,
				display.icons, bare.customValues, bare.entrypoints, bare.mixinConfigs, bare.accessWidener, bare.nestedJars,
				bare.languageAdapters);
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

	// --- Fabric Loader's internal view (LoaderModMetadata), for a mod that registers a mod of its own at run time ---

	@Override
	public Collection<String> getEntrypointKeys() {
		return entrypoints.keySet();
	}

	@Override
	public List<net.fabricmc.loader.impl.metadata.EntrypointMetadata> getEntrypoints(String type) {
		return List.copyOf(entrypoints.getOrDefault(type, List.of()));
	}

	@Override
	public Collection<String> getMixinConfigs(net.fabricmc.api.EnvType type) {
		List<String> configs = new java.util.ArrayList<>();
		for (MixinConfigDecl decl : mixinConfigs) if (decl.environment() == null || decl.environment().matches(type)) configs.add(decl.config());
		return configs;
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
	public record EntrypointDecl(String adapter, String value) implements net.fabricmc.loader.impl.metadata.EntrypointMetadata {
		public boolean isDefaultAdapter() {
			return adapter == null || adapter.equals("default");
		}

		@Override
		public String getAdapter() {
			return adapter == null ? "default" : adapter;
		}

		@Override
		public String getValue() {
			return value;
		}
	}

	/** One declared mixin config plus the side it applies on. */
	public record MixinConfigDecl(String config, ModEnvironment environment) {
	}
}
