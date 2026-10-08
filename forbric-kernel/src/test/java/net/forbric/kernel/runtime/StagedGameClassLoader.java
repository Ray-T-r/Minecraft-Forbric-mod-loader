package net.forbric.kernel.runtime;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * The compiled game-side classes over the staged game and Minecraft 26.2's own libraries, for tests that initialise
 * game classes off-game; they skip when any of it is absent.
 *
 * <p>The libraries are the ones 26.2's version JSON names, as build.gradle resolves them for the transfer tests — not
 * the newest-looking cached jar per artifact: by name "9.0.19" sorts after "10.0.21", and that picked a
 * datafixerupper two majors older than the game's.
 */
public final class StagedGameClassLoader {
	private static final Pattern ARTIFACT_PATH = Pattern.compile("\"artifact\"\\s*:\\s*\\{[^}]*?\"path\"\\s*:\\s*\"([^\"]+)\"");

	private StagedGameClassLoader() {
	}

	public static List<URL> urls() throws Exception {
		Path run = TestFixtures.stagedRoot();
		Path compiled = Path.of(System.getProperty("forbric.test.runtimeClasses",
				Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").toString())).normalize();
		Path merged = run.resolve("merged-base/patched-mc-merged-26.2.jar"), neo = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path forge = run.resolve("merged-base/forge-runtime-interop.jar");
		if (!Files.isRegularFile(forge)) forge = run.resolve("forge-runtime/forge-runtime.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(merged) && Files.isRegularFile(neo) && Files.isRegularFile(forge),
				"the staged game is absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the compiled game side is absent");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), merged.toUri().toURL(), neo.toUri().toURL(), forge.toUri().toURL()));
		Path libraries = TestFixtures.minecraftDir().resolve("libraries");
		Path version = libraries.resolveSibling("versions/26.2/26.2.json");
		TestFixtures.require(Fixture.MC_LIBRARIES, Files.isRegularFile(version), "no local Minecraft 26.2 version JSON");
		Matcher artifact = ARTIFACT_PATH.matcher(Files.readString(version));
		while (artifact.find()) {
			Path jar = libraries.resolve(artifact.group(1));
			if (Files.isRegularFile(jar)) urls.add(jar.toUri().toURL());
		}
		return urls;
	}

	public static URLClassLoader create(List<URL> extra) throws Exception {
		List<URL> urls = new ArrayList<>(extra);
		urls.addAll(urls());
		return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
	}
}
