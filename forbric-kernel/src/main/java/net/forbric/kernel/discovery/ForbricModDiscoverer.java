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

package net.forbric.kernel.discovery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.metadata.fabric.FabricModJsonReader;
import net.forbric.kernel.metadata.forge.EcosystemVersions;
import net.forbric.kernel.metadata.forge.ForgeMetadataMapper;
import net.forbric.kernel.metadata.forge.ForgeModsToml;
import net.forbric.kernel.metadata.forge.ModsTomlParser;
import net.forbric.kernel.util.ForbricLog;

/**
 * Forbric's single mod-discovery pass: it scans a {@code mods/} directory and recognizes BOTH ecosystems'
 * manifests, producing one unified {@link DiscoveredMod} list.
 *
 * <p>A jar is classified by which descriptors it carries:
 * <ul>
 *   <li>{@code fabric.mod.json} (jar root) &rarr; a Fabric mod,</li>
 *   <li>{@code META-INF/mods.toml} or {@code META-INF/neoforge.mods.toml} &rarr; one or more Forge mods,</li>
 *   <li>a jar carrying both yields both (a multi-loader jar) &mdash; each side enters the unified list.</li>
 * </ul>
 *
 * <p>This is the structural skeleton of the final discoverer. In the integrated build it is fused with
 * Fabric Loader's reused {@code ModDiscoverer} (parallel scan, nested JarInJar) and feeds the single SAT
 * resolver; here it establishes the dual-manifest recognition and the unified output shape.
 */
public final class ForbricModDiscoverer {
	public static final String FABRIC_MANIFEST = "fabric.mod.json";
	public static final String FORGE_MANIFEST = "META-INF/mods.toml";
	public static final String NEOFORGE_MANIFEST = "META-INF/neoforge.mods.toml";
	/** The classic (Forge) default Access Transformer path, used when the toml declares none. */
	public static final String DEFAULT_AT = "META-INF/accesstransformer.cfg";

	/** Discovers every mod jar directly inside {@code modsDir}. Non-mod jars are ignored. */
	public List<DiscoveredMod> discover(Path modsDir) throws IOException {
		List<DiscoveredMod> result = new ArrayList<>();

		if (!Files.isDirectory(modsDir)) return result;

		try (Stream<Path> entries = Files.list(modsDir)) {
			List<Path> jars = new ArrayList<>();

			entries.filter(p -> p.getFileName().toString().endsWith(".jar"))
					.filter(Files::isRegularFile)
					.forEach(jars::add);

			jars.sort((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));

			for (Path jar : jars) {
				result.addAll(discoverJar(jar));
			}
		}

		return result;
	}

	/**
	 * What a jar's own metadata files say, remembered per jar.
	 *
	 * <p>Seven different passes ask about the same jar during one boot — discovery, the dependency audit, the
	 * seeder, the merge report, the enum-extension loader, duplicate arbitration and the boot list — and each ask
	 * opened the zip, re-read every metadata file in it and re-ran the parse. It also re-ran the WARNINGS: a
	 * single jar's "dropping declared mixin config" line appeared four times in one boot, which reads like four
	 * problems.
	 *
	 * <p>Safe to remember because a jar's contents do not change while the game runs. An unreadable jar is not
	 * remembered: it throws again on the next ask, which is more honest than answering from a cached error. A
	 * manifest that cannot be read is remembered with the rest of the jar, and handed to {@link MetadataFailures}
	 * again on every ask, so a pass that runs after {@link MetadataFailures#reset()} still sees it.
	 */
	private final java.util.Map<Path, Parsed> discovered = new java.util.concurrent.ConcurrentHashMap<>();

	private record Parsed(List<DiscoveredMod> mods, List<MetadataFailures.Failure> failures) {
	}

	/**
	 * Discovers the mod(s) declared by a single jar. May return a Fabric mod, Forge mod(s), or both.
	 *
	 * <p>Each manifest is read on its own. One that cannot be read — a malformed version range, TOML or JSON —
	 * is recorded in {@link MetadataFailures} and leaves the jar's other manifests, and every other jar, alone.
	 * Only a jar that cannot be opened at all throws.
	 */
	public List<DiscoveredMod> discoverJar(Path jarPath) throws IOException {
		Path key = MetadataFailures.key(jarPath);
		Parsed remembered = discovered.get(key);
		if (remembered == null) {
			// Stored unmodifiable: seven callers share this list now, and one of them quietly adding to it would
			// change what the other six see. Any such caller fails here and immediately rather than later and
			// somewhere else.
			List<MetadataFailures.Failure> failures = new ArrayList<>();
			remembered = new Parsed(List.copyOf(parseJar(jarPath, failures)), List.copyOf(failures));
			discovered.put(key, remembered);
		}
		remembered.failures().forEach(MetadataFailures::record);
		return remembered.mods();
	}

	private List<DiscoveredMod> parseJar(Path jarPath, List<MetadataFailures.Failure> failures) throws IOException {
		List<DiscoveredMod> result = new ArrayList<>();
		String source = jarPath.toString();

		try (JarFile jar = new JarFile(jarPath.toFile())) {
			String jarVersion = manifestVersion(jar.getManifest());

			// Fabric side
			ZipEntry fabricEntry = jar.getEntry(FABRIC_MANIFEST);

			if (fabricEntry != null) {
				try (InputStream in = jar.getInputStream(fabricEntry)) {
					result.add(FabricModJsonReader.read(in, source));
				} catch (IOException | RuntimeException unreadable) {
					failures.add(new MetadataFailures.Failure(jarPath, Ecosystem.FABRIC, FABRIC_MANIFEST, List.of(),
							describe(unreadable)));
				}
			}

			// Forge / NeoForge side — a jar may carry either or both (multiloader builds ship one toml per
			// family). Each present manifest is reported truthfully under its own ecosystem; which family
			// actually loads is a boot-time policy (the active game base), not a discovery concern.
			discoverForgeFamily(jarPath, jar, NEOFORGE_MANIFEST, Ecosystem.NEOFORGE, jarVersion, source, result, failures);
			discoverForgeFamily(jarPath, jar, FORGE_MANIFEST, Ecosystem.FORGE, jarVersion, source, result, failures);
		}

		return result;
	}

	/**
	 * Reads one Forge-family manifest ({@code mods.toml} / {@code neoforge.mods.toml}) into {@code sink}, if
	 * present. Nothing is added when it fails: every mod a manifest declares shares its dependency list and mixin
	 * configs, so half a manifest is not a safer answer than none.
	 */
	private static void discoverForgeFamily(Path jarPath, JarFile jar, String manifestPath, Ecosystem ecosystem,
			String jarVersion, String source, List<DiscoveredMod> sink, List<MetadataFailures.Failure> failures) {
		ZipEntry entry = jar.getEntry(manifestPath);
		if (entry == null) return;

		ForgeModsToml toml;
		try (InputStream in = jar.getInputStream(entry)) {
			toml = ModsTomlParser.parse(in);
		} catch (IOException | RuntimeException unreadable) {
			failures.add(new MetadataFailures.Failure(jarPath, ecosystem, manifestPath, List.of(), describe(unreadable)));
			return;
		}
		try {
			sink.addAll(mapForgeFamily(jar, toml, ecosystem, jarVersion, source));
		} catch (IOException | RuntimeException unreadable) {
			failures.add(new MetadataFailures.Failure(jarPath, ecosystem, manifestPath,
					toml.getMods().stream().map(net.forbric.kernel.metadata.forge.ForgeModEntry::getModId).toList(),
					describe(unreadable)));
		}
	}

	private static String describe(Throwable error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message.strip();
	}

	private static List<DiscoveredMod> mapForgeFamily(JarFile jar, ForgeModsToml toml, Ecosystem ecosystem,
			String jarVersion, String source) throws IOException {
		EcosystemVersions.audit(toml, source);
		net.forbric.kernel.metadata.forge.LanguageProviders.audit(toml, source);

		// Union of toml-declared ATs and the classic default path (if the jar actually carries it).
		List<String> accessTransformers = new ArrayList<>(toml.getAccessTransformers());
		if (accessTransformers.isEmpty() && jar.getEntry(DEFAULT_AT) != null) {
			accessTransformers.add(DEFAULT_AT);
		}

		// Most real MinecraftForge mods declare mixins via the manifest MixinConfigs attribute, not [[mixins]] —
		// GeckoLib ships exactly `MixinConfigs: geckolib.mixins.json` and nothing in its toml. That attribute is a
		// MinecraftForge/ModLauncher convention: NeoForge reads [[mixins]] from the toml and never looks at the
		// manifest. Feeding it to BOTH families made a universal jar's NeoForge mod claim the Forge-side config too
		// (collective would have registered collective_forge.mixins.json AND collective_neoforge.mixins.json).
		List<String> manifestMixins = new ArrayList<>();
		Manifest manifest = jar.getManifest();
		String attr = manifest == null ? null : manifest.getMainAttributes().getValue("MixinConfigs");
		if (attr != null && ecosystem == Ecosystem.FORGE) {
			for (String config : attr.split(",")) {
				if (!config.strip().isEmpty()) manifestMixins.add(config.strip());
			}
		} else if (attr != null) {
			ForbricLog.debug("[Forbric] ignoring manifest MixinConfigs '%s' for the %s side of %s — that attribute "
					+ "is a MinecraftForge convention; NeoForge declares mixins in [[mixins]]", attr, ecosystem, source);
		}

		// Read only when a declared config is missing: most jars carry every config they declare.
		java.util.Set<String>[] nested = new java.util.Set[1];
		return ForgeMetadataMapper.toDiscoveredMods(toml, jarVersion, source, accessTransformers,
				manifestMixins, config -> jar.getEntry(config) != null,
				config -> (nested[0] == null ? nested[0] = nestedDeclaredConfigs(jar) : nested[0]).contains(config), ecosystem);
	}

	/**
	 * The mixin configs the jars a jar nests in {@code META-INF/jarjar/} declare themselves AND carry, read without
	 * extracting them: a config of a nested jar's own {@code neoforge.mods.toml} / {@code mods.toml} {@code [[mixins]]},
	 * its manifest {@code MixinConfigs} or its {@code fabric.mod.json} {@code mixins}, present in that jar. A nested jar
	 * that only carries a file of that name — a plain library — registers nothing, so it accounts for nothing: a
	 * wrapper's config it holds is still warned about as dropped.
	 */
	static java.util.Set<String> nestedDeclaredConfigs(JarFile jar) {
		java.util.Set<String> configs = new java.util.HashSet<>();
		for (var entries = jar.entries(); entries.hasMoreElements(); ) {
			ZipEntry entry = entries.nextElement();
			if (!entry.getName().startsWith("META-INF/jarjar/") || !entry.getName().endsWith(".jar")) continue;
			java.util.Set<String> names = new java.util.HashSet<>();
			java.util.Set<String> declared = new java.util.LinkedHashSet<>();
			try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(jar.getInputStream(entry))) {
				for (ZipEntry inner; (inner = in.getNextEntry()) != null; ) {
					String name = inner.getName();
					names.add(name);
					try {
						if (name.equals(NEOFORGE_MANIFEST) || name.equals(FORGE_MANIFEST)) {
							declared.addAll(ModsTomlParser.parse(new java.io.ByteArrayInputStream(in.readAllBytes())).getMixinConfigs());
						} else if (name.equals(FABRIC_MANIFEST)) {
							declared.addAll(FabricModJsonReader.read(new java.io.ByteArrayInputStream(in.readAllBytes()), entry.getName())
									.getMixinConfigs());
						} else if (name.equals("META-INF/MANIFEST.MF")) {
							String attr = new Manifest(new java.io.ByteArrayInputStream(in.readAllBytes())).getMainAttributes().getValue("MixinConfigs");
							if (attr != null) for (String config : attr.split(",")) if (!config.isBlank()) declared.add(config.strip());
						}
					} catch (IOException | RuntimeException unreadableManifest) {
						// A manifest that cannot be read declares nothing.
					}
				}
			} catch (IOException unreadable) {
				// A nested jar that cannot be read accounts for nothing; its configs are warned about as before.
				continue;
			}
			for (String config : declared) if (names.contains(config)) configs.add(config);
		}
		return configs;
	}

	private static String manifestVersion(Manifest manifest) {
		if (manifest == null) return null;
		return manifest.getMainAttributes().getValue("Implementation-Version");
	}
}
