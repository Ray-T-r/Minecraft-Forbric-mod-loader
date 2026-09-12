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

	/** Discovers the mod(s) declared by a single jar. May return a Fabric mod, Forge mod(s), or both. */
	public List<DiscoveredMod> discoverJar(Path jarPath) throws IOException {
		List<DiscoveredMod> result = new ArrayList<>();
		String source = jarPath.toString();

		try (JarFile jar = new JarFile(jarPath.toFile())) {
			String jarVersion = manifestVersion(jar.getManifest());

			// Fabric side
			ZipEntry fabricEntry = jar.getEntry(FABRIC_MANIFEST);

			if (fabricEntry != null) {
				try (InputStream in = jar.getInputStream(fabricEntry)) {
					result.add(FabricModJsonReader.read(in, source));
				}
			}

			// Forge / NeoForge side — a jar may carry either or both (multiloader builds ship one toml per
			// family). Each present manifest is reported truthfully under its own ecosystem; which family
			// actually loads is a boot-time policy (the active game base), not a discovery concern.
			discoverForgeFamily(jar, NEOFORGE_MANIFEST, Ecosystem.NEOFORGE, jarVersion, source, result);
			discoverForgeFamily(jar, FORGE_MANIFEST, Ecosystem.FORGE, jarVersion, source, result);
		}

		return result;
	}

	/** Reads one Forge-family manifest ({@code mods.toml} / {@code neoforge.mods.toml}) into {@code sink}, if present. */
	private static void discoverForgeFamily(JarFile jar, String manifestPath, Ecosystem ecosystem,
			String jarVersion, String source, List<DiscoveredMod> sink) throws IOException {
		ZipEntry entry = jar.getEntry(manifestPath);
		if (entry == null) return;

		ForgeModsToml toml;
		try (InputStream in = jar.getInputStream(entry)) {
			toml = ModsTomlParser.parse(in);
		}
		EcosystemVersions.audit(toml, source);

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

		sink.addAll(ForgeMetadataMapper.toDiscoveredMods(toml, jarVersion, source, accessTransformers,
				manifestMixins, config -> jar.getEntry(config) != null, ecosystem));
	}

	private static String manifestVersion(Manifest manifest) {
		if (manifest == null) return null;
		return manifest.getMainAttributes().getValue("Implementation-Version");
	}
}
