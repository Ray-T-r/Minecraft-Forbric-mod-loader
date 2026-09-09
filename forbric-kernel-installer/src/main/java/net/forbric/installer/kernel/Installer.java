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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Writes a Forbric version into an ordinary Minecraft directory, so any launcher that reads Mojang's version
 * format can start it.
 *
 * <p>The profile inherits from vanilla, which is what lets the launcher resolve assets, natives and the base
 * libraries by itself; on top of that it carries the kernel as {@code mainClass}, Forbric's jars and the kernel's
 * dependencies as libraries, and — as game arguments — the three jars the kernel opens the game with. The
 * kernel's own argument parser takes those four flags out and forwards everything else to the game, so the
 * launcher's own arguments can arrive in any order around them.
 *
 * <p>The Minecraft libraries are listed explicitly rather than left to the classpath: the kernel has to OWN them
 * (mods weave into DataFixerUpper and friends), and only the version JSON knows which ones this version uses.
 */
public final class Installer {
	static final String MAIN_CLASS = "net.forbric.kernel.boot.KernelClientLaunch";
	private static final String BUNDLE_MANIFEST = "/forbric-kernel-libraries.json";
	private static final String LIBRARY_DIR = "${library_directory}";

	private final Consumer<String> log;

	public Installer(Consumer<String> log) {
		this.log = log;
	}

	/**
	 * @param mcDir      the Minecraft directory the launcher uses
	 * @param mcVersion  the base version, e.g. {@code 26.2}
	 * @param artifactDir where to look for the locally built game artifacts, or null to search the checkout
	 * @return the id of the version written
	 */
	public String install(Path mcDir, String mcVersion, Path artifactDir) throws IOException {
		Path versions = mcDir.resolve("versions");
		Path libraries = mcDir.resolve("libraries");
		String id = mcVersion + "-forbric";

		log.accept("Minecraft directory: " + mcDir);
		GameArtifacts artifacts = GameArtifacts.locate(mcVersion, artifactDir);
		for (Map.Entry<String, Path> e : artifacts.all().entrySet()) log.accept("  found " + e.getValue());

		Map<String, Object> baseJson = ensureBaseVersion(versions, mcVersion);
		List<String> mcLibraries = minecraftLibraryPaths(baseJson);
		log.accept(mcLibraries.size() + " Minecraft libraries the kernel will own");

		List<Map<String, Object>> libraryEntries = new ArrayList<>();
		libraryEntries.addAll(stageBundledJars(libraries));
		libraryEntries.addAll(stageGameArtifacts(libraries, artifacts, mcVersion));

		Path profile = versions.resolve(id).resolve(id + ".json");
		Files.createDirectories(profile.getParent());
		Files.writeString(profile, Json.write(profile(id, mcVersion, libraryEntries, mcLibraries, artifacts)),
				StandardCharsets.UTF_8);
		log.accept("wrote " + profile);

		Path mods = mcDir.resolve("mods");
		Files.createDirectories(mods);
		log.accept("");
		log.accept("Installed. In your launcher, pick the version \"" + id + "\".");
		log.accept("Fabric, MinecraftForge and NeoForge mods all go in " + mods
				+ " (a launcher with per-version isolation uses versions/" + id + "/mods instead).");
		return id;
	}

	// --- the profile ------------------------------------------------------------------------------------------

	private Map<String, Object> profile(String id, String mcVersion, List<Map<String, Object>> libraries,
			List<String> mcLibraries, GameArtifacts artifacts) {
		Map<String, Object> profile = new LinkedHashMap<>();
		profile.put("id", id);
		profile.put("inheritsFrom", mcVersion);
		profile.put("type", "release");
		profile.put("mainClass", MAIN_CLASS);

		List<Object> game = new ArrayList<>();
		game.add("--gameJar");
		game.add(libraryRef(coordinate("net.forbric:patched-mc-merged", mcVersion)));
		// Both runtimes travel in ONE flag, joined the way a classpath is. A launcher may read game arguments as a
		// flag-to-value map and keep only the last occurrence of a repeated flag — PCL2 does, and says so — which
		// would drop MinecraftForge's runtime and kill the game on the first net.minecraftforge class it touches.
		game.add("--runtimeJar");
		game.add(libraryRef(coordinate("net.forbric:forge-runtime", mcVersion)) + java.io.File.pathSeparator
				+ libraryRef(coordinate("net.forbric:neoforge-runtime", mcVersion)));
		game.add("--libraryPath");
		game.add(String.join(java.io.File.pathSeparator, mcLibraries));

		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("game", game);
		arguments.put("jvm", new ArrayList<>());
		profile.put("arguments", arguments);
		profile.put("libraries", new ArrayList<Object>(libraries));
		return profile;
	}

	private static String coordinate(String groupAndName, String version) {
		return groupAndName + ":" + version;
	}

	private static String libraryRef(String coordinate) {
		return LIBRARY_DIR + "/" + Util.coordinateToPath(coordinate);
	}

	/**
	 * Every library the base version lists, as a launcher-substituted path. Entries whose rules exclude this
	 * platform are skipped — a Windows natives jar has no business on a macOS classpath, and the kernel only ever
	 * opens files that exist.
	 */
	@SuppressWarnings("unchecked")
	private static List<String> minecraftLibraryPaths(Map<String, Object> baseJson) {
		List<String> paths = new ArrayList<>();
		Object libs = baseJson.get("libraries");
		if (!(libs instanceof List<?> list)) return paths;
		for (Object entry : list) {
			if (!(entry instanceof Map<?, ?> lib)) continue;
			if (!appliesToThisPlatform((Map<String, Object>) lib)) continue;
			Object name = lib.get("name");
			Map<String, Object> downloads = (Map<String, Object>) lib.get("downloads");
			Map<String, Object> artifact = downloads == null ? null : (Map<String, Object>) downloads.get("artifact");
			Object path = artifact == null ? null : artifact.get("path");
			if (path instanceof String p) paths.add(LIBRARY_DIR + "/" + p);
			else if (name instanceof String n) paths.add(LIBRARY_DIR + "/" + Util.coordinateToPath(n));
		}
		return paths;
	}

	/** Mojang's rule list, read the way a launcher reads it: last matching rule wins, default allow. */
	@SuppressWarnings("unchecked")
	private static boolean appliesToThisPlatform(Map<String, Object> lib) {
		Object rules = lib.get("rules");
		if (!(rules instanceof List<?> list) || list.isEmpty()) return true;
		String os = osName();
		boolean allowed = false;
		for (Object entry : list) {
			if (!(entry instanceof Map<?, ?> rule)) continue;
			Map<String, Object> osSpec = (Map<String, Object>) rule.get("os");
			if (osSpec != null && !os.equals(osSpec.get("name"))) continue;
			allowed = "allow".equals(rule.get("action"));
		}
		return allowed;
	}

	private static String osName() {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		if (os.contains("win")) return "windows";
		if (os.contains("mac")) return "osx";
		return "linux";
	}

	// --- staging ----------------------------------------------------------------------------------------------

	/** Forbric's own jars and the kernel's dependencies, carried inside this installer. */
	private List<Map<String, Object>> stageBundledJars(Path libraries) throws IOException {
		Object manifest;
		try (InputStream in = Installer.class.getResourceAsStream(BUNDLE_MANIFEST)) {
			if (in == null) {
				throw new IOException("this installer carries no library manifest — it was built without its "
						+ "bundleForbric step");
			}
			manifest = Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		@SuppressWarnings("unchecked")
		List<Object> entries = (List<Object>) ((Map<String, Object>) manifest).get("libraries");
		List<Map<String, Object>> written = new ArrayList<>();
		for (Object entry : entries) {
			@SuppressWarnings("unchecked")
			Map<String, Object> lib = (Map<String, Object>) entry;
			String resource = (String) lib.get("resource");
			String path = (String) lib.get("path");
			Path dest = libraries.resolve(path);
			Files.createDirectories(dest.getParent());
			try (InputStream in = Installer.class.getResourceAsStream(resource)) {
				if (in == null) throw new IOException("missing bundled jar " + resource);
				Files.write(dest, in.readAllBytes());
			}
			written.add(libraryEntry((String) lib.get("coordinate"), path, dest));
		}
		log.accept("staged " + written.size() + " Forbric and kernel-dependency jar(s) into " + libraries);
		return written;
	}

	/** The three locally built jars, copied under net.forbric coordinates so the profile can name them. */
	private List<Map<String, Object>> stageGameArtifacts(Path libraries, GameArtifacts artifacts, String mcVersion)
			throws IOException {
		List<Map<String, Object>> written = new ArrayList<>();
		for (Map.Entry<String, Path> found : artifacts.all().entrySet()) {
			String coordinate = coordinate(found.getKey(), mcVersion);
			String path = Util.coordinateToPath(coordinate);
			Path dest = libraries.resolve(path);
			Files.createDirectories(dest.getParent());
			Files.copy(found.getValue(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			written.add(libraryEntry(coordinate, path, dest));
		}
		log.accept("staged " + written.size() + " game artifact(s) built on this machine");
		return written;
	}

	private static Map<String, Object> libraryEntry(String coordinate, String path, Path file) throws IOException {
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("path", path);
		artifact.put("sha1", Util.sha1(file));
		artifact.put("size", Files.size(file));
		Map<String, Object> downloads = new LinkedHashMap<>();
		downloads.put("artifact", artifact);
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("name", coordinate);
		entry.put("downloads", downloads);
		return entry;
	}

	// --- the base version -------------------------------------------------------------------------------------

	/** Reads the base version's JSON, downloading it (and its client jar) when the directory has none. */
	@SuppressWarnings("unchecked")
	private Map<String, Object> ensureBaseVersion(Path versions, String mcVersion) throws IOException {
		Path dir = versions.resolve(mcVersion);
		Path json = dir.resolve(mcVersion + ".json");
		if (!Files.isRegularFile(json) || !Files.isRegularFile(dir.resolve(mcVersion + ".jar"))) {
			log.accept("base version " + mcVersion + " is missing — downloading it from Mojang");
			new MojangDownloader(log).downloadClient(mcVersion, dir);
		}
		try {
			Object parsed = Json.parse(Files.readString(json, StandardCharsets.UTF_8));
			if (!(parsed instanceof Map)) throw new IOException(json + " is not a version JSON");
			return (Map<String, Object>) parsed;
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
	}
}
