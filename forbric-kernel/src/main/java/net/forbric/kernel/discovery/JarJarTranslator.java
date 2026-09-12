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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.json.JsonFormat;

import net.forbric.kernel.util.ForbricLog;

/**
 * Translates a Forge mod's JarJar (JiJ) nested jars into shapes the Fabric substrate understands.
 * Identity/Mojmap mode only (MC 26.2+ — nested bytecode is never remapped).
 *
 * <ul>
 *   <li><b>Plain libraries</b> (no mod metadata): get a minimal synthetic {@code fabric.mod.json} injected
 *       in place, and are referenced from the outer wrap's {@code "jars"} array — Fabric's own nested-jar
 *       mechanism then loads them (and version-dedupes across mods, approximating JarJarSelector).</li>
 *   <li><b>Nested Forge MODS</b> ({@code META-INF/mods.toml}): extracted and handed back to the caller to be
 *       prepared as top-level wrapped mods — they must join the synthetic module layer and the genuine FML
 *       LoadingModList, which nested (non-PATH-origin) jars cannot.</li>
 *   <li><b>mixinextras-forge / org.spongepowered:mixin</b>: dropped — Forbric already ships
 *       mixinextras-fabric (JiJ in forbricruntime) and Fabric's sponge-mixin; loading the Forge flavors
 *       beside them would collide.</li>
 * </ul>
 */
public final class JarJarTranslator {
	private static final String METADATA = "META-INF/jarjar/metadata.json";

	/** Outcome of {@link #translate}: nested paths to reference via {@code "jars"} + extracted Forge mods. */
	public static final class Result {
		public final List<String> nestedJarPaths = new ArrayList<>();
		public final List<Path> nestedForgeModJars = new ArrayList<>();
	}

	private JarJarTranslator() {
	}

	/**
	 * Processes {@code jar} in place. {@code extractDir} receives nested Forge mod jars (caller prepares
	 * them as top-level mods). Returns an empty result when the jar carries no JarJar metadata.
	 */
	public static Result translate(Path jar, Path extractDir) throws IOException {
		Result result = new Result();

		try (FileSystem fs = FileSystems.newFileSystem(jar)) {
			Path metadata = fs.getPath(METADATA);
			if (!Files.exists(metadata)) return result;

			UnmodifiableConfig root;
			try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
				root = JsonFormat.fancyInstance().createParser().parse(reader);
			}

			List<? extends UnmodifiableConfig> jars = root.getOrElse("jars", List.of());
			for (UnmodifiableConfig entry : jars) {
				UnmodifiableConfig identifier = entry.get("identifier");
				String group = identifier == null ? "" : identifier.getOrElse("group", "");
				String artifact = identifier == null ? "" : identifier.getOrElse("artifact", "");
				String path = entry.getOrElse("path", (String) null);
				UnmodifiableConfig version = entry.get("version");
				String artifactVersion = version == null ? "0.0.0" : version.getOrElse("artifactVersion", "0.0.0");

				if (path == null || !Files.exists(fs.getPath(path))) continue;

				if (isMixinInfra(group, artifact)) {
					ForbricLog.info("[Forbric/JiJ] dropping nested " + group + ":" + artifact
							+ " (Forbric supplies the Fabric flavor of the mixin stack)");
					continue;
				}

				Path nestedInJar = fs.getPath(path);
				Path extracted = extractDir.resolve(nestedInJar.getFileName().toString());
				Files.copy(nestedInJar, extracted, StandardCopyOption.REPLACE_EXISTING);

				if (hasEntry(extracted, "META-INF/mods.toml") || hasEntry(extracted, "META-INF/neoforge.mods.toml")) {
					ForbricLog.info("[Forbric/JiJ] nested Forge mod " + group + ":" + artifact
							+ " -> extracted for top-level preparation");
					result.nestedForgeModJars.add(extracted);
					continue; // not referenced as a nested jar; it becomes a first-class wrapped mod
				}

				if (!hasEntry(extracted, "fabric.mod.json")) {
					injectLibraryModJson(extracted, group, artifact, artifactVersion);
					Files.copy(extracted, nestedInJar, StandardCopyOption.REPLACE_EXISTING);
				}

				result.nestedJarPaths.add(path);
				ForbricLog.info("[Forbric/JiJ] nested library " + group + ":" + artifact + "@" + artifactVersion
						+ " -> referenced via fabric \"jars\"");
			}
		}

		return result;
	}

	private static boolean isMixinInfra(String group, String artifact) {
		String a = artifact.toLowerCase(Locale.ROOT);
		return a.startsWith("mixinextras") || ("org.spongepowered".equals(group) && a.startsWith("mixin"));
	}

	private static boolean hasEntry(Path jar, String entry) throws IOException {
		try (FileSystem fs = FileSystems.newFileSystem(jar)) {
			return Files.exists(fs.getPath(entry));
		}
	}

	/** Minimal synthetic fabric.mod.json so Fabric accepts the nested jar (every "jars" entry must be a mod). */
	private static void injectLibraryModJson(Path jar, String group, String artifact, String version) throws IOException {
		String id = sanitizeId(group + "_" + artifact);
		String json = "{\n"
				+ "  \"schemaVersion\": 1,\n"
				+ "  \"id\": \"" + id + "\",\n"
				+ "  \"version\": \"" + sanitizeVersion(version) + "\",\n"
				+ "  \"name\": \"" + group + ":" + artifact + " (JiJ library via Forbric)\",\n"
				+ "  \"environment\": \"*\"\n"
				+ "}\n";

		try (FileSystem fs = FileSystems.newFileSystem(jar)) {
			Files.write(fs.getPath("fabric.mod.json"), json.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String sanitizeId(String raw) {
		String id = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
		if (id.isEmpty() || !Character.isLetter(id.charAt(0))) id = "lib_" + id;
		return id.length() > 63 ? id.substring(0, 63) : id;
	}

	private static String sanitizeVersion(String version) {
		return version == null || version.isEmpty() ? "0.0.0" : version;
	}
}
