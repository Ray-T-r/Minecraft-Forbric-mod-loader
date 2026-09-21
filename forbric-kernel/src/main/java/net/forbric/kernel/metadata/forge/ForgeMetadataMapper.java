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

import java.util.ArrayList;
import java.util.List;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.UnifiedDependency;
import net.forbric.kernel.util.ForbricLog;

/** Maps a parsed Forge {@link ForgeModsToml} into Forbric's unified {@link DiscoveredMod} model. */
public final class ForgeMetadataMapper {
	private ForgeMetadataMapper() {
	}

	/** @see #toDiscoveredMods(ForgeModsToml, String, String, List) */
	public static List<DiscoveredMod> toDiscoveredMods(ForgeModsToml toml, String jarVersion, String source) {
		return toDiscoveredMods(toml, jarVersion, source, toml.getAccessTransformers());
	}

	/**
	 * @param toml               the parsed mods.toml
	 * @param jarVersion         the jar manifest {@code Implementation-Version} for resolving {@code ${file.jarVersion}}, or {@code null}
	 * @param source             where the mod was found (for diagnostics)
	 * @param accessTransformers the Access Transformer config paths to attach (declared + classic default present in the jar)
	 */
	public static List<DiscoveredMod> toDiscoveredMods(ForgeModsToml toml, String jarVersion, String source, List<String> accessTransformers) {
		return toDiscoveredMods(toml, jarVersion, source, accessTransformers, List.of());
	}

	/**
	 * @param extraMixinConfigs mixin configs declared OUTSIDE mods.toml — in the wild most Forge mods use
	 *                          the jar manifest's {@code MixinConfigs} attribute rather than {@code [[mixins]]}
	 */
	public static List<DiscoveredMod> toDiscoveredMods(ForgeModsToml toml, String jarVersion, String source,
			List<String> accessTransformers, List<String> extraMixinConfigs) {
		return toDiscoveredMods(toml, jarVersion, source, accessTransformers, extraMixinConfigs, config -> true);
	}

	/**
	 * @param mixinConfigPresent tests whether a declared mixin config actually exists in the jar. Multiloader
	 *                           jars frequently over-declare a sibling loader's configs (e.g. a NeoForge-style
	 *                           {@code [[mixins]]} block listing {@code *.neoforge.mixins.json} that only ships
	 *                           in the NeoForge jar); traditional Forge reads configs from the manifest
	 *                           {@code MixinConfigs} attribute, so a declared-but-absent config is dropped
	 *                           rather than handed to Mixin, which would abort on the phantom resource.
	 */
	public static List<DiscoveredMod> toDiscoveredMods(ForgeModsToml toml, String jarVersion, String source,
			List<String> accessTransformers, List<String> extraMixinConfigs, java.util.function.Predicate<String> mixinConfigPresent) {
		return toDiscoveredMods(toml, jarVersion, source, accessTransformers, extraMixinConfigs, mixinConfigPresent,
				Ecosystem.FORGE);
	}

	/**
	 * @param ecosystem which Forge-family ecosystem declared this toml — {@link Ecosystem#NEOFORGE} when it
	 *                  came from {@code META-INF/neoforge.mods.toml}, {@link Ecosystem#FORGE} for the classic
	 *                  {@code META-INF/mods.toml}. Both files share the same schema; the ecosystem is decided by
	 *                  which manifest the jar carried, not by the toml contents.
	 */
	public static List<DiscoveredMod> toDiscoveredMods(ForgeModsToml toml, String jarVersion, String source,
			List<String> accessTransformers, List<String> extraMixinConfigs,
			java.util.function.Predicate<String> mixinConfigPresent, Ecosystem ecosystem) {
		List<String> declared = new ArrayList<>(toml.getMixinConfigs());
		for (String config : extraMixinConfigs) {
			if (!declared.contains(config)) declared.add(config);
		}

		List<String> mixinConfigs = new ArrayList<>();
		for (String config : declared) {
			if (mixinConfigPresent.test(config)) {
				mixinConfigs.add(config);
			} else {
				ForbricLog.warn("[Forbric] dropping declared mixin config '%s' - not present in %s%n", config, source);
			}
		}

		List<DiscoveredMod> result = new ArrayList<>();

		for (ForgeModEntry mod : toml.getMods()) {
			List<UnifiedDependency> deps = new ArrayList<>();

			for (ForgeDependency dep : mod.getDependencies()) {
				deps.add(new UnifiedDependency(
						dep.getModId(),
						ForgeVersionRangeTranslator.toFabricPredicate(dep.getVersionRange()),
						dep.isMandatory(),
						dep.getOrdering(),
						dep.getSideScope()));
			}

			result.add(new DiscoveredMod(
					ecosystem,
					mod.getModId(),
					ModsTomlParser.resolveVersion(mod.getVersion(), jarVersion),
					mod.getDisplayName(),
					deps,
					mixinConfigs,
					null, // Fabric .accesswidener — N/A for Forge; ATs are carried separately
					accessTransformers,
					source)
					// Its [modproperties.<id>] table, which is how this mod addresses OTHER mods — Sodium reads
					// its config entry point out of it. Empty for the overwhelming majority.
					.withModProperties(mod.getProperties())
					// The whole [[mods]] entry, which IConfigurable.getConfigElement answers from. The version is
					// overwritten with the RESOLVED one: the raw entry still says ${file.jarVersion}, and a reader
					// asking this seam for a version next to IModInfo.getVersion() must not get two answers.
					.withConfigElements(resolvedEntry(mod, jarVersion)));

			// Said out loud because the reader is another ecosystem's code and the failure is silent: Sodium looks
			// up sodium:config_api_user in here to build this mod's page in Video Settings, and when the kernel
			// answered with an empty map the page simply did not exist. A parser that matches nothing must be
			// visible as "matched nothing" rather than as an absent line.
			if (!mod.getProperties().isEmpty()) {
				ForbricLog.info("[Forbric/Meta] %s declares %d [modproperties] key(s) %s — a mod reads these to find "
						+ "what this mod offers it (Sodium's config entry point, Jade's flags)", mod.getModId(),
						mod.getProperties().size(), mod.getProperties().keySet());
			}
		}

		return result;
	}
	/** One mod's {@code [[mods]]} entry with {@code version} resolved against the jar manifest. */
	private static java.util.Map<String, Object> resolvedEntry(ForgeModEntry mod, String jarVersion) {
		java.util.Map<String, Object> entry = mod.getConfigElements();
		if (entry.isEmpty()) return entry;
		java.util.Map<String, Object> resolved = new java.util.LinkedHashMap<>(entry);
		String version = ModsTomlParser.resolveVersion(mod.getVersion(), jarVersion);
		if (version != null) resolved.put("version", version);
		return resolved;
	}
}
