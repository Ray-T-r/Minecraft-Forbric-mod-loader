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

package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;

/**
 * Assembles NeoForge's runtime into one Knot-loadable jar — the Java port of
 * {@code forbric-loader/run/assemble-neoforge-runtime.sh}.
 *
 * <p>Same shape as {@link ForgeRuntimeBuilder}: take the {@code -universal} jar plus the subset of
 * {@code config.libraries} that is not already on the classpath, merge them, and give the result a
 * {@code fabric.mod.json} so Knot discovers it. Three things are deliberately <em>not</em> copied from the Forge
 * side, and each one is a bug if it is:
 *
 * <ul>
 *   <li><strong>Input order is declaration order, not filename order.</strong> {@code ForgeRuntimeBuilder} sorts
 *       by filename and relies on {@code -universal} sorting first; nothing guarantees that here.</li>
 *   <li><strong>The manifest is four fixed lines.</strong> Forge harvests per-package version sections because
 *       FML's {@code JarVersionLookupHandler} reads them; NeoForge only needs its own
 *       {@code Implementation-Version}, without which {@code LanguageProviderLoader} throws "Failed to find
 *       implementation version for language provider javafml".</li>
 *   <li><strong>Guava goes entirely.</strong> Forge keeps {@code failureaccess} while dropping {@code guava};
 *       NeoForge's config lists three Guava artifacts and all three are classpath-provided.</li>
 * </ul>
 *
 * <p>The library set is derived from {@code config.libraries} rather than hand-listed. That was checked against
 * the script it replaces: of the 40 entries in NeoForge 26.2.0.38-beta's config, exactly the 14 the script names
 * survive {@link #isProvidedElsewhere}, and every one of the 14 is present in the config — so the deny-list is
 * complete in both directions, and a version bump that adds a library gets it for free instead of silently
 * leaving it out.
 */
final class NeoForgeRuntimeBuilder {

	private final NeoForgeArtifacts nfa;
	private final Http http;
	private final Path dlDir;
	private final Path outJar;
	private final Consumer<String> log;

	NeoForgeRuntimeBuilder(NeoForgeArtifacts nfa, Http http, Path workDir, Path outJar, Consumer<String> log) {
		this.nfa = nfa;
		this.http = http;
		this.dlDir = workDir.resolve("dl");
		this.outJar = outJar;
		this.log = log;
	}

	/** Build (or reuse) the merged runtime jar; returns its coordinate/path/sha1/size. */
	ArtifactResult build(ForgeArtifacts.UserdevConfig cfg) throws IOException {
		String coordinate = nfa.runtimeCoordinate();
		if (Files.isRegularFile(outJar) && Files.size(outJar) > 0) {
			log.accept("[neoforge-runtime] up-to-date: " + outJar.getFileName());
			return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), Files.size(outJar));
		}
		Files.createDirectories(dlDir);
		Files.createDirectories(outJar.getParent());

		// The universal jar first, then the surviving libraries in the config's own order. Merging is last-wins
		// on collisions, so the order is part of the contract even though the class sets are disjoint today.
		List<String> inputs = new ArrayList<>();
		inputs.add(nfa.universalCoordinate());
		for (String lib : cfg.libraries) {
			if (!isProvidedElsewhere(lib)) inputs.add(lib);
		}

		log.accept("[neoforge-runtime] fetching " + inputs.size() + " NeoForge runtime artifacts …");
		List<Path> jars = new ArrayList<>();
		for (String coord : inputs) {
			String rel = Util.coordinateToPath(ForgeArtifacts.stripExtension(coord));
			Path dest = dlDir.resolve(rel.substring(rel.lastIndexOf('/') + 1)); // flat cache, like the script's dl/
			http.ensureWithFallback(nfa.neoforgedUrl(coord), nfa.centralUrl(coord), dest);
			jars.add(dest);
		}

		log.accept("[neoforge-runtime] merging universal + " + (jars.size() - 1) + " libs → " + outJar.getFileName());
		mergeInto(jars);

		String sha1 = Util.sha1(outJar);
		long size = Files.size(outJar);
		log.accept("[neoforge-runtime] wrote " + outJar.getFileName() + " (" + (size / (1024 * 1024)) + " MB)");
		return new ArtifactResult(coordinate, outJar, sha1, size);
	}

	/**
	 * Libraries the kernel or Minecraft already supplies, so putting them in the runtime jar would shadow the
	 * copy everything else links against.
	 *
	 * <p>Two of these are load-bearing beyond "it is already there". {@code org.ow2.asm} and
	 * {@code com.electronwill.night-config} are pinned {@code ALWAYS_PARENT} in the kernel's delegation policy,
	 * which makes the kernel's copy the only one NeoForge can see — so a bump means checking those two versions,
	 * not copying them in. A NightConfig mismatch in particular does not surface as a link error; it surfaces as
	 * {@code StampedConfig.valueMap()} throwing, a long way from the cause.
	 */
	private static boolean isProvidedElsewhere(String coordinate) {
		String group = coordinate.split(":")[0];
		return switch (group) {
			// Kernel-supplied and pinned ALWAYS_PARENT.
			case "org.ow2.asm", "com.electronwill.night-config" -> true;
			// The substrate owns Mixin, and MixinExtras is JiJ'd already.
			case "net.fabricmc", "io.github.llamalad7" -> true;
			// Everything Minecraft itself puts on the classpath.
			case "com.google.code.gson", "com.google.errorprone", "com.google.guava", "com.google.j2objc",
			     "com.mojang", "commons-io", "net.sf.jopt-simple", "org.apache.commons",
			     "org.apache.logging.log4j", "org.jline", "org.lwjgl", "org.slf4j" -> true;
			default -> false;
		};
	}

	// ---- the merge (ports the assemble script's python heredoc) ----

	private void mergeInto(List<Path> jars) throws IOException {
		Path universal = jars.get(0);

		LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
		LinkedHashMap<String, byte[]> services = new LinkedHashMap<>();

		for (Path jar : jars) {
			boolean isUniversal = jar.equals(universal);
			for (var e : Zips.readAll(jar).entrySet()) {
				String n = e.getKey();
				if (skip(n, isUniversal)) continue;
				if (n.startsWith("META-INF/services/")) {
					byte[] prev = services.get(n);
					byte[] cur = e.getValue();
					byte[] merged = new byte[(prev == null ? 0 : prev.length) + cur.length + 1];
					int off = 0;
					if (prev != null) {
						System.arraycopy(prev, 0, merged, 0, prev.length);
						off = prev.length;
					}
					System.arraycopy(cur, 0, merged, off, cur.length);
					merged[off + cur.length] = '\n';
					services.put(n, merged);
				} else {
					files.put(n, e.getValue()); // last wins on the (disjoint) class set
				}
			}
		}

		// Manifest, then classes, then services, then fabric.mod.json — the script's order, kept so a
		// jar-to-jar comparison against the reference is about content rather than layout.
		LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
		out.put("META-INF/MANIFEST.MF", buildManifest());
		for (var e : files.entrySet()) {
			if (e.getKey().equals("fabric.mod.json") || e.getKey().equals("META-INF/MANIFEST.MF")) continue;
			out.put(e.getKey(), e.getValue());
		}
		out.putAll(services);
		out.put("fabric.mod.json", buildFabricModJson());
		Zips.writeJar(outJar, out);
	}

	private static boolean skip(String n, boolean isUniversal) {
		if (n.endsWith("/")) return true;
		if (n.equals("module-info.class") || n.endsWith("/module-info.class")) return true;
		if (n.equals("META-INF/MANIFEST.MF")) return true;
		// The universal jar's neoforge.mods.toml is the "neoforge" system mod's identity — ModSorter needs it.
		if (n.equals("META-INF/neoforge.mods.toml")) return !isUniversal;
		if (n.equals("META-INF/mods.toml")) return true;
		// FML's sponge-mixin service bindings must not reach Knot's ServiceLoader; the substrate owns mixin.
		if (n.startsWith("META-INF/services/org.spongepowered.asm.service.")) return true;
		if (n.startsWith("META-INF/jarjar/")) return true;
		if (Zips.isSignatureFile(n)) return true;
		return false;
	}

	private byte[] buildManifest() {
		return ("Manifest-Version: 1.0\r\n"
				+ "Implementation-Title: NeoForge\r\n"
				+ "Implementation-Version: " + nfa.neoforgeVersion + "\r\n"
				+ "Automatic-Module-Name: neoforge\r\n\r\n").getBytes(StandardCharsets.UTF_8);
	}

	private byte[] buildFabricModJson() {
		// id "neoforge" so Knot discovers + loads this jar. The version is the script's literal: NeoForge's own
		// 26.2.0.38-beta is not a Fabric-parseable version, and nothing depends on this field's precision.
		String json = "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"id\": \"neoforge\",\n"
				+ "  \"version\": \"" + nfa.mcVersion + ".0\",\n"
				+ "  \"name\": \"NeoForge runtime (via Forbric)\",\n"
				+ "  \"environment\": \"*\"\n"
				+ "}\n";
		return json.getBytes(StandardCharsets.UTF_8);
	}
}
