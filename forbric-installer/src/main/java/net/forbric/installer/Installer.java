package net.forbric.installer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Core install logic: stage Forbric's jars into a launcher's {@code libraries/} tree and write a version
 * profile ({@code versions/forbric-<mc>/forbric-<mc>.json}) that PCL2/HMCL can launch directly.
 *
 * <p>Two jar categories are handled differently (this is load-bearing):
 * <ul>
 *   <li><b>classpath</b> — forbric-loader core + reused substrate deps. Staged into {@code libraries/} AND
 *       listed in the profile's {@code libraries} so the launcher puts them on {@code -cp} (parent-loaded).</li>
 *   <li><b>knot-addmods</b> — {@code forbricruntime.jar}. Staged into {@code libraries/} but deliberately NOT
 *       listed as a profile library (that would force it onto {@code -cp}, where its intermediary-named game
 *       references cannot resolve). Instead the profile injects
 *       {@code -Dfabric.addMods=${library_directory}/…} so Forbric's Knot discovers and transform-loads it.</li>
 * </ul>
 */
final class Installer {

	static final String MAIN_CLASS = "net.forbric.loader.impl.launch.ForbricClient";
	static final String CATEGORY_CLASSPATH = "classpath";
	static final String CATEGORY_ADDMODS = "knot-addmods";
	/** Bundled clean-room Forge {@code @Mod} that opens the Fabric-content window in Forge's registration span. */
	static final String CATEGORY_FORGE_BRIDGE = "forge-bridge";
	/** Classpath location of the manifest packed into a self-contained installer jar. */
	static final String BUNDLED_MANIFEST = "/forbric-libraries.json";

	static final String MODE_INTERMEDIARY_V1 = "intermediary-v1";
	static final String MODE_FULL_FORGE_26_2 = "full-forge-26.2";
	/** The proven traditional-MinecraftForge build the full-forge mode targets (Mojmap-native, identity mode). */
	static final String FORGE_VERSION_26_2 = "26.2-65.0.1";

	/** One entry from the build manifest. */
	static final class Lib {
		final String coordinate;
		final String category;
		final String file;     // absolute source path (dev / --manifest build output); may be null when bundled
		final String resource; // classpath-relative path inside a self-contained installer jar; may be null (dev)
		final String sha1;
		final long size;

		Lib(String coordinate, String category, String file, String resource, String sha1, long size) {
			this.coordinate = coordinate;
			this.category = category;
			this.file = file;
			this.resource = resource;
			this.sha1 = sha1;
			this.size = size;
		}
	}

	final String forbricVersion;
	final List<Lib> libs;

	private Installer(String forbricVersion, List<Lib> libs) {
		this.forbricVersion = forbricVersion;
		this.libs = libs;
	}

	/** Parse an on-disk {@code forbric-libraries.json} (dev / {@code --manifest} path). */
	static Installer fromManifest(Path manifestFile) throws IOException {
		return fromJson(new String(Files.readAllBytes(manifestFile), StandardCharsets.UTF_8), manifestFile.toString());
	}

	/** True when this installer jar carries a bundled manifest (a self-contained release build). */
	static boolean hasBundledManifest() {
		return Installer.class.getResource(BUNDLED_MANIFEST) != null;
	}

	/** Load the manifest packed inside this installer jar ({@value #BUNDLED_MANIFEST}). */
	static Installer fromBundledManifest() throws IOException {
		try (InputStream in = Installer.class.getResourceAsStream(BUNDLED_MANIFEST)) {
			if (in == null) throw new IOException("no bundled manifest on classpath (" + BUNDLED_MANIFEST + ")");
			return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8), "bundled:" + BUNDLED_MANIFEST);
		}
	}

	/** Shared parser for {@code forbric-libraries.json} — used by both the external and bundled entry points. */
	@SuppressWarnings("unchecked")
	private static Installer fromJson(String text, String where) throws IOException {
		Map<String, Object> m = (Map<String, Object>) Json.parse(text);
		String ver = (String) m.get("forbricVersion");
		List<Object> arr = (List<Object>) m.get("libraries");
		if (arr == null) throw new IOException("manifest has no 'libraries' array: " + where);
		List<Lib> out = new ArrayList<>();
		for (Object o : arr) {
			Map<String, Object> e = (Map<String, Object>) o;
			Object sz = e.get("size");
			out.add(new Lib(
					(String) e.get("coordinate"),
					(String) e.get("category"),
					(String) e.get("file"),
					(String) e.get("resource"),
					(String) e.get("sha1"),
					sz instanceof Number ? ((Number) sz).longValue() : 0L));
		}
		return new Installer(ver, out);
	}

	/** Back-compat entry point: install the v1 intermediary profile (the pre-mode default). */
	void install(Path mcDir, String mcVersion, boolean autoDownloadBase, Consumer<String> log) throws IOException {
		install(mcDir, mcVersion, MODE_INTERMEDIARY_V1, autoDownloadBase, log);
	}

	/**
	 * Perform the install into {@code mcDir} for base version {@code mcVersion} (e.g. "1.21.11" or "26.2").
	 *
	 * <p>Two modes:
	 * <ul>
	 *   <li>{@link #MODE_INTERMEDIARY_V1} — vanilla MC on the Fabric substrate; Fabric mods + simple Forge mods.</li>
	 *   <li>{@link #MODE_FULL_FORGE_26_2} — builds the full Forge runtime (a merged {@code forge-runtime.jar} + a
	 *       Forge-patched Mojmap MC jar) at install time and drives the genuine Forge lifecycle, so a raw Forge jar
	 *       dropped into the profile's {@code mods/} loads. The heavy artifacts embed Mojang/Forge code and are
	 *       built on this machine, never redistributed.</li>
	 * </ul>
	 *
	 * <p>When {@code autoDownloadBase} is set, the vanilla client is fetched from Mojang if the base version is
	 * absent. Idempotent: re-running overwrites the profile, re-stages jars, and reuses already-built artifacts.
	 */
	void install(Path mcDir, String mcVersion, String mode, boolean autoDownloadBase, Consumer<String> log)
			throws IOException {
		boolean fullForge = MODE_FULL_FORGE_26_2.equals(mode);

		// 0) Make sure the base vanilla version exists so the launcher can resolve inheritsFrom=<mcVersion> (and, in
		//    full-forge mode, so the patched-MC build has the vanilla client/server + assets to patch from).
		if (autoDownloadBase) ensureBaseVersion(mcDir, mcVersion, log);

		Path libDir = mcDir.resolve("libraries");
		Lib runtime = null, forgeBridge = null;

		// 1) Stage every manifest jar into libraries/, verifying sha1 after copy.
		for (Lib l : libs) {
			String rel = Util.coordinateToPath(l.coordinate);
			Path dest = libDir.resolve(rel);
			Files.createDirectories(dest.getParent());

			// Prefer a jar bundled inside this installer (self-contained release); otherwise copy from the
			// absolute source path of an on-disk/--manifest build (dev). sha1 is verified after the write either
			// way, so "what landed on disk is correct" holds regardless of the source.
			InputStream bundled = l.resource != null ? Installer.class.getResourceAsStream(l.resource) : null;
			if (bundled != null) {
				try (InputStream in = bundled) {
					Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
				}
			} else {
				if (l.file == null) {
					throw new IOException("no source for " + l.coordinate
							+ " (manifest entry has neither a bundled resource nor a file path)");
				}
				Path src = Util.path(l.file);
				if (!Files.isRegularFile(src)) {
					throw new IOException("manifest jar not found on disk: " + src + " (build Forbric first?)");
				}
				Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
			}

			String actual = Util.sha1(dest);
			if (l.sha1 != null && !l.sha1.equalsIgnoreCase(actual)) {
				throw new IOException("sha1 mismatch after staging " + l.coordinate
						+ " (manifest " + l.sha1 + " vs on-disk " + actual + ")");
			}
			log.accept("staged  " + l.coordinate + "  →  libraries/" + rel);
			if (CATEGORY_ADDMODS.equals(l.category)) runtime = l;
			else if (CATEGORY_FORGE_BRIDGE.equals(l.category)) forgeBridge = l;
		}
		if (runtime == null) {
			throw new IOException("manifest is missing the '" + CATEGORY_ADDMODS + "' (forbricruntime) entry");
		}

		String id = fullForge ? ("forbric-forge-" + mcVersion) : ("forbric-" + mcVersion);
		Path verDir = mcDir.resolve("versions").resolve(id);

		// 2) Full-forge only: build the two heavy artifacts on this machine and stage the Forge infra into mods/.
		ArtifactResult patchedMc = null;
		if (fullForge) {
			if (forgeBridge == null) {
				throw new IOException("full-forge needs the '" + CATEGORY_FORGE_BRIDGE + "' (forbric-bridge) manifest "
						+ "entry — rebuild the installer with the bridge bundled");
			}
			patchedMc = buildFullForge(mcDir, mcVersion, libDir, verDir, runtime, forgeBridge, log);
		}

		// 3) Build and write the version profile.
		Map<String, Object> profile = new LinkedHashMap<>();
		profile.put("id", id);
		profile.put("inheritsFrom", mcVersion);
		profile.put("type", "release");
		profile.put("mainClass", MAIN_CLASS);

		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("game", new ArrayList<>());
		List<String> jvm = new ArrayList<>();
		if (fullForge) {
			// Mojmap/identity path: select the Forge-patched game jar (Fabric's own env-jar override — processed
			// before the classpath, so a launcher reordering libraries[] can't defeat it) and drive the genuine
			// lifecycle. The Forge infra jars live in versions/<id>/mods (not addMods) — see buildFullForge.
			String patchedPath = "${library_directory}/" + Util.coordinateToPath(patchedMc.coordinate);
			jvm.add("-Djava.awt.headless=true");
			jvm.add("-Dforbric.runtimeNamespace=named");
			jvm.add("-Dforbric.fabricMainDeferred=true");
			jvm.add("-Dfabric.gameJarPath.client=" + patchedPath);
		} else {
			jvm.add("-Djava.awt.headless=true");
			jvm.add("-Dfabric.addMods=${library_directory}/" + Util.coordinateToPath(runtime.coordinate));
		}
		arguments.put("jvm", jvm);
		profile.put("arguments", arguments);

		List<Object> libraries = new ArrayList<>();
		for (Lib l : libs) {
			if (!CATEGORY_CLASSPATH.equals(l.category)) continue; // Knot-loaded jars excluded — never on -cp
			Map<String, Object> e = new LinkedHashMap<>();
			e.put("name", l.coordinate);
			e.put("sha1", l.sha1);
			e.put("size", l.size);
			libraries.add(e);
		}
		if (!fullForge) {
			// Fabric intermediary mappings — only the intermediary mode needs them; the Mojmap/identity path does not.
			Map<String, Object> intermediary = new LinkedHashMap<>();
			intermediary.put("name", "net.fabricmc:intermediary:" + mcVersion);
			intermediary.put("url", "https://maven.fabricmc.net/");
			libraries.add(intermediary);
		}
		profile.put("libraries", libraries);

		Files.createDirectories(verDir);
		Path profileFile = verDir.resolve(id + ".json");
		Files.write(profileFile, Json.write(profile).getBytes(StandardCharsets.UTF_8));
		log.accept("wrote   " + profileFile);
		log.accept("");
		log.accept("Forbric " + forbricVersion + " installed for Minecraft " + mcVersion
				+ (fullForge ? " (full Forge runtime)." : "."));
		log.accept("In PCL2 / HMCL, select the version '" + id + "' and launch.");
		log.accept("Put your own Fabric/Forge mod jars into that profile's mods/ folder"
				+ (fullForge ? " (" + verDir.resolve("mods") + ")." : "."));
	}

	/**
	 * Build the full-Forge artifacts and stage the Forge infrastructure. Returns the patched-MC result (its path is
	 * referenced by the profile's {@code fabric.gameJarPath.client}). The merged runtime + patched MC are built into
	 * {@code libraries/} under {@code net.forbric} coordinates and never bundled/redistributed; the Forge infra jars
	 * ({@code forbricruntime}, {@code forge-runtime}, {@code forbric-bridge}) are copied into {@code versions/<id>/mods}
	 * because {@code ForbricBootstrap} discovers the Forge lifecycle by scanning {@code gameDir/mods}.
	 */
	private ArtifactResult buildFullForge(Path mcDir, String mcVersion, Path libDir, Path verDir,
	                                      Lib runtime, Lib forgeBridge, Consumer<String> log) throws IOException {
		String forgeVersion = FORGE_VERSION_26_2;
		ForgeArtifacts fa = new ForgeArtifacts(mcVersion, forgeVersion);
		Http http = new Http(log);
		Path work = mcDir.resolve(".forbric-build").resolve(mcVersion + "-" + forgeVersion);
		Files.createDirectories(work.resolve("dl"));

		log.accept("");
		log.accept("building the full Forge runtime for " + forgeVersion
				+ " — first run downloads Forge + patches Minecraft (a few minutes; cached afterward) …");

		Path userdev = work.resolve("dl").resolve("forge-userdev.jar");
		http.ensureWithFallback(fa.forgeUrl(fa.userdevCoordinate()), fa.centralUrl(fa.userdevCoordinate()), userdev);
		ForgeArtifacts.UserdevConfig cfg = ForgeArtifacts.readConfig(userdev);

		Path rtOut = libDir.resolve(Util.coordinateToPath(fa.runtimeCoordinate()));
		Path pmcOut = libDir.resolve(Util.coordinateToPath(fa.patchedMcCoordinate()));
		Files.createDirectories(rtOut.getParent());
		Files.createDirectories(pmcOut.getParent());

		ArtifactResult forgeRuntime = new ForgeRuntimeBuilder(fa, http, work, rtOut, log).build(cfg);
		ArtifactResult patchedMc = new PatchedMcBuilder(fa, http, mcDir, work, pmcOut, log)
				.build(userdev, cfg, forgeRuntime.file);
		log.accept("staged  " + forgeRuntime.coordinate + "  →  libraries/" + Util.coordinateToPath(forgeRuntime.coordinate));
		log.accept("staged  " + patchedMc.coordinate + "  →  libraries/" + Util.coordinateToPath(patchedMc.coordinate));

		// The Forge lifecycle is discovered from gameDir/mods (not fabric.addMods), so co-locate the infra jars
		// with the user's mods, exactly as the proven launch-client-forge script does.
		Path modsDir = verDir.resolve("mods");
		Files.createDirectories(modsDir);
		Files.copy(libDir.resolve(Util.coordinateToPath(runtime.coordinate)), modsDir.resolve("forbricruntime.jar"),
				StandardCopyOption.REPLACE_EXISTING);
		Files.copy(rtOut, modsDir.resolve("forge-runtime.jar"), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(libDir.resolve(Util.coordinateToPath(forgeBridge.coordinate)), modsDir.resolve("forbric-bridge.jar"),
				StandardCopyOption.REPLACE_EXISTING);
		log.accept("staged  Forge infra → " + modsDir + "  (forbricruntime + forge-runtime + forbric-bridge)");
		return patchedMc;
	}

	/**
	 * Ensure {@code versions/<v>/<v>.json} and {@code <v>.jar} exist under {@code mcDir}, downloading the vanilla
	 * client from Mojang when either is absent. No-op (and offline-safe) when both are already present, so this
	 * is cheap to call on every install and never re-downloads. The launcher itself resolves the base's
	 * libraries/assets/natives on first launch from the {@code <v>.json} written here.
	 */
	void ensureBaseVersion(Path mcDir, String mcVersion, Consumer<String> log) throws IOException {
		Path verDir = mcDir.resolve("versions").resolve(mcVersion);
		Path json = verDir.resolve(mcVersion + ".json");
		Path jar = verDir.resolve(mcVersion + ".jar");
		if (Files.isRegularFile(json) && Files.isRegularFile(jar)) {
			log.accept("base " + mcVersion + " already present — no download needed");
			return;
		}
		log.accept("base " + mcVersion + " missing — downloading the vanilla client from Mojang …");
		new MojangDownloader(log).downloadClient(mcVersion, verDir);
		log.accept("base " + mcVersion + " ready");
	}

	/** List installed vanilla-style versions under {@code <mcDir>/versions} that have both a &lt;id&gt;.json and &lt;id&gt;.jar. */
	static List<String> discoverBaseVersions(Path mcDir) {
		Path versions = mcDir.resolve("versions");
		if (!Files.isDirectory(versions)) return List.of();
		try (Stream<Path> s = Files.list(versions)) {
			return s.filter(Files::isDirectory)
					.map(p -> p.getFileName().toString())
					.filter(id -> !id.startsWith("forbric-"))
					.filter(id -> Files.isRegularFile(versions.resolve(id).resolve(id + ".json"))
							&& Files.isRegularFile(versions.resolve(id).resolve(id + ".jar")))
					.sorted()
					.collect(Collectors.toList());
		} catch (IOException e) {
			return List.of();
		}
	}
}
