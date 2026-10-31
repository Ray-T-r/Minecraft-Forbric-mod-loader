package net.forbric.installer.kernel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The base version's libraries as the profile lists them for the kernel to own ({@code --libraryPath}): exactly the
 * ones the version JSON lists for this platform, at the paths a launcher puts them, in the JSON's order -- read the way
 * a launcher reads Mojang's rules (last matching rule wins, default allow), so a natives jar for another platform
 * never reaches the classpath and a library with no rules always does.
 *
 * <p>The installer no longer downloads them. That existed for the merge, which read them before any launcher had run
 * the version; the launcher fetches them on first launch, as it does for every version.
 */
public final class MinecraftLibrariesTest {
	public static void main(String[] args) throws Exception {
		int checks = 0;
		String here = osName(), other = here.equals("windows") ? "osx" : "windows";
		Map<String, Object> json = new LinkedHashMap<>();
		List<Object> list = new ArrayList<>();
		// Listed out of path order, so an answer that sorts instead of keeping the JSON's order shows.
		list.add(library("org/zlib/zlib/1/zlib-1.jar", null, null));
		list.add(library("org/lwjgl/lwjgl/3/lwjgl-natives-" + other + ".jar", "allow", other));
		list.add(library("com/alib/alib/1/alib-1.jar", "allow", here));
		// Allowed everywhere, then disallowed here: the last matching rule wins.
		Map<String, Object> notHere = library("com/nothere/nothere/1/nothere-1.jar", "allow", null);
		notHere.put("rules", List.of(Map.of("action", "allow"), Map.of("action", "disallow", "os", Map.of("name", here))));
		list.add(notHere);
		// No downloads.artifact: the path is derived from the coordinate.
		Map<String, Object> bare = new LinkedHashMap<>();
		bare.put("name", "com.example:bare:2");
		list.add(bare);
		json.put("libraries", list);

		List<String> paths = Installer.minecraftLibraryPaths(json);
		require(paths.equals(List.of("${library_directory}/org/zlib/zlib/1/zlib-1.jar",
				"${library_directory}/com/alib/alib/1/alib-1.jar",
				"${library_directory}/com/example/bare/2/bare-2.jar")), "not this platform's libraries in the JSON's order: " + paths);
		checks++;
		require(Installer.minecraftLibraryPaths(Map.of()).isEmpty(), "a version JSON without libraries lists some");
		checks++;
		System.out.println("PASS Minecraft libraries the profile lists: " + checks + " checks (platform rules, last rule"
				+ " wins, the JSON's order, a path from the coordinate)");
	}

	private static Map<String, Object> library(String path, String action, String onlyOn) {
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("path", path);
		Map<String, Object> lib = new LinkedHashMap<>();
		lib.put("name", path);
		lib.put("downloads", Map.of("artifact", artifact));
		if (action != null) {
			Map<String, Object> rule = new LinkedHashMap<>();
			rule.put("action", action);
			if (onlyOn != null) rule.put("os", Map.of("name", onlyOn));
			lib.put("rules", List.of(rule));
		}
		return lib;
	}

	private static String osName() {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		return os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
