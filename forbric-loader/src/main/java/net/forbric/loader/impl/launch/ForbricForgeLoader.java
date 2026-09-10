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

package net.forbric.loader.impl.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import net.forbric.loader.impl.forge.JarJarTranslator;
import net.forbric.loader.impl.forge.ModAnnotationScanner;
import net.forbric.loader.impl.mapping.ForbricCache;
import net.forbric.loader.impl.mapping.ForbricMappings;
import net.forbric.loader.impl.mapping.ForgeModRemapper;
import net.forbric.loader.impl.metadata.DiscoveredMod;
import net.forbric.loader.impl.util.ForbricLog;

/**
 * Turns each discovered raw Forge/NeoForge mod jar (Mojmap bytecode) into a runtime-ready, Fabric-wrapped jar
 * — remap Mojmap&rarr;intermediary, scan its {@code @Mod} classes, inject a synthetic {@code fabric.mod.json}
 * (carrying the mod's own mixin configs) — so the substrate loads it like any Fabric mod. Outputs are content
 * hash-cached, so an unchanged input is reused rather than rebuilt. This is the automation that replaces the
 * hand-run remap+wrap steps; {@link ForbricBootstrap} calls it before the substrate freezes the loader.
 */
public final class ForbricForgeLoader {
	/** Version tag of the wrap OUTPUT format; bump to invalidate cached wraps when wrapping logic changes. */
	private static final String WRAP_FORMAT = "wrap8"; // wrap8: NeoForge wraps' module name = bare modId (FML getId lookup)

	private final ForbricMappings mappings;
	private final List<Path> remapClasspath; // game (named) + libs for inheritance resolution
	private final ForbricCache cache;
	private final String mappingsKey;
	private final boolean identity; // Mojmap-canonical: NeoForge mods are already Mojmap -> no remap
	/** Mod ids present this boot (Fabric + wrapped Forge), lowercased; drives soft-dependency downgrade. */
	private final java.util.Set<String> presentModIds;
	/** Which Forge family this boot loads ({@code FORGE} / {@code NEOFORGE}) — stamps wraps, filters nested mods. */
	private final net.forbric.loader.impl.metadata.ModEcosystem family;

	public ForbricForgeLoader(ForbricMappings mappings, List<Path> remapClasspath, ForbricCache cache, String mappingsKey) {
		this(mappings, remapClasspath, cache, mappingsKey, false, java.util.Set.of(),
				net.forbric.loader.impl.metadata.ModEcosystem.FORGE);
	}

	public ForbricForgeLoader(ForbricMappings mappings, List<Path> remapClasspath, ForbricCache cache,
			String mappingsKey, java.util.Set<String> presentModIds) {
		this(mappings, remapClasspath, cache, mappingsKey, false, presentModIds,
				net.forbric.loader.impl.metadata.ModEcosystem.FORGE);
	}

	private ForbricForgeLoader(ForbricMappings mappings, List<Path> remapClasspath, ForbricCache cache,
			String mappingsKey, boolean identity, java.util.Set<String> presentModIds,
			net.forbric.loader.impl.metadata.ModEcosystem family) {
		this.mappings = mappings;
		this.remapClasspath = remapClasspath;
		this.cache = cache;
		this.mappingsKey = mappingsKey;
		this.identity = identity;
		this.presentModIds = presentModIds == null ? java.util.Set.of() : presentModIds;
		this.family = family == null ? net.forbric.loader.impl.metadata.ModEcosystem.FORGE : family;
	}

	/**
	 * Mojmap-canonical / identity mode (MC 26.2+): NeoForge mods are already compiled against Mojmap names —
	 * the same names the patched Mojmap game runs in — so preparing a mod is just "scan its {@code @Mod}
	 * classes + inject a synthetic {@code fabric.mod.json}", with NO bytecode remap and no mappings/remap
	 * classpath. The Knot-loaded NeoForge runtime driver then discovers the {@code forbric:forgeClasses} keys.
	 */
	public static ForbricForgeLoader identity(ForbricCache cache) {
		return identity(cache, java.util.Set.of());
	}

	/** As {@link #identity(ForbricCache)}, with the set of present mod ids for soft-dependency downgrade. */
	public static ForbricForgeLoader identity(ForbricCache cache, java.util.Set<String> presentModIds) {
		return identity(cache, presentModIds, net.forbric.loader.impl.metadata.ModEcosystem.FORGE);
	}

	/** As above, for a specific Forge family ({@code FORGE} / {@code NEOFORGE}) — the active game base's. */
	public static ForbricForgeLoader identity(ForbricCache cache, java.util.Set<String> presentModIds,
			net.forbric.loader.impl.metadata.ModEcosystem family) {
		return new ForbricForgeLoader(null, List.of(), cache, "mojmap-identity", true, presentModIds, family);
	}

	/**
	 * Prepares every Forge mod in {@code forgeMods} and returns the wrapped, runtime-ready jar paths. A mod
	 * that fails to prepare is logged and skipped rather than aborting the whole boot.
	 */
	public List<Path> prepare(List<DiscoveredMod> forgeMods) {
		List<Path> wrapped = new ArrayList<>();

		for (DiscoveredMod mod : forgeMods) {
			try {
				List<Path> outs = new ArrayList<>();
				prepareInto(mod, outs);
				for (Path out : outs) {
					if (!wrapped.contains(out)) wrapped.add(out);
				}
				if (!outs.isEmpty()) {
					ForbricLog.info("[Forbric] auto-prepared Forge mod %s @ %s -> %s%s%n",
							mod.getId(), mod.getVersion(), outs.get(0).getFileName(),
							outs.size() > 1 ? " (+" + (outs.size() - 1) + " JiJ-nested Forge mod(s))" : "");
				}
			} catch (Exception e) {
				ForbricLog.error("[Forbric] could not prepare Forge mod " + mod.getId(), e);
			}
		}

		return wrapped;
	}

	private Path prepareOne(DiscoveredMod mod) throws IOException {
		List<Path> sink = new ArrayList<>();
		prepareInto(mod, sink);
		return sink.isEmpty() ? null : sink.get(0);
	}

	/**
	 * Prepares a mod (and, recursively, its JarJar-nested Forge mods) into {@code sink}. The main wrapped
	 * jar is added first; nested Forge mods follow as first-class wrapped jars (they must join the synthetic
	 * module layer + FML LoadingModList, which Fabric-nested jars cannot). A {@code .nested} sidecar next to
	 * the cached output records the nested outputs so cache hits re-yield them.
	 */
	private void prepareInto(DiscoveredMod mod, List<Path> sink) throws IOException {
		Path source = Path.of(mod.getSource());
		if (!Files.isRegularFile(source)) return;

		// WRAP_FORMAT salts the key so cached wraps rebuild when the wrapping OUTPUT format changes
		// (e.g. wrap2 added the Automatic-Module-Name manifest attribute for the synthetic module layer).
		// The family is in the key too: a dual-toml jar wraps differently per Forge family.
		String key = ForbricCache.key(mappingsKey, mod.getId(), mod.getVersion(), source,
				WRAP_FORMAT + "-" + family.familyId());
		Path out = cache.resolve(sanitize(mod.getId()), key, ".jar");
		Path nestedSidecar = cache.resolve(sanitize(mod.getId()), key, ".nested");

		if (ForbricCache.isCached(out)) { // unchanged input -> reuse (incl. previously extracted nested mods)
			sink.add(out);
			if (Files.isRegularFile(nestedSidecar)) {
				for (String line : Files.readAllLines(nestedSidecar)) {
					if (!line.isBlank() && Files.isRegularFile(Path.of(line))) sink.add(Path.of(line));
				}
			}
			return;
		}

		Path remapped = cache.resolve(sanitize(mod.getId()), key, ".remap.jar");
		if (identity) {
			// Already Mojmap — copy through, no bytecode remap; just scan + wrap below.
			Files.copy(source, remapped, StandardCopyOption.REPLACE_EXISTING);
		} else {
			ForgeModRemapper.remapJar(source, remapped, mappings, remapClasspath);
		}

		// JarJar: nested libraries become Fabric-nested "jars"; nested Forge MODS extract + recurse.
		JarJarTranslator.Result jij = JarJarTranslator.translate(remapped, cache.dir());
		List<Path> nestedOuts = new ArrayList<>();
		for (Path nestedJar : jij.nestedForgeModJars) {
			try {
				for (DiscoveredMod nested : new net.forbric.loader.impl.discovery.ForbricModDiscoverer().discoverJar(nestedJar)) {
					if (nested.getEcosystem() != family) continue;
					int before = sink.size();
					prepareInto(nested, sink);
					nestedOuts.addAll(sink.subList(before, sink.size()));
					break; // one prepared jar per nested file (first mod entry names it)
				}
			} catch (Exception e) {
				ForbricLog.error("[Forbric/JiJ] could not prepare nested Forge mod " + nestedJar.getFileName(), e);
			}
		}

		List<String> forgeClasses = new ArrayList<>();
		for (ModAnnotationScanner.ModClassInfo info : ModAnnotationScanner.scan(remapped)) {
			forgeClasses.add(info.className);
		}

		ForgeModRemapper.wrapAsFabricMod(remapped, sanitize(mod.getId()), safeVersion(mod.getVersion()),
				forgeClasses, mod.getMixinConfigs(), mod.getDependencies(), jij.nestedJarPaths, presentModIds, family);

		Files.move(remapped, out, StandardCopyOption.REPLACE_EXISTING);
		sink.add(out);

		if (!nestedOuts.isEmpty()) {
			List<String> lines = new ArrayList<>();
			for (Path p : nestedOuts) lines.add(p.toString());
			Files.write(nestedSidecar, lines);
		}
	}

	private static String sanitize(String id) {
		return id == null ? "mod" : id.toLowerCase().replaceAll("[^a-z0-9_]", "_");
	}

	private static String safeVersion(String version) {
		return version == null || version.isEmpty() ? "0.0.0" : version;
	}
}
