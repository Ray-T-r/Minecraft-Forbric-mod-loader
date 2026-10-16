package net.forbric.installer.kernel;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * The installer fetches the base version's libraries itself before the merge, which reads them: a launcher fetches
 * them only on the first launch, and a directory no launcher has started the version in has none -- the merge then
 * proved nothing and refused (gate-m17). Served from a local HTTP server: what the version JSON lists for this
 * platform lands at its own path and is verified, a file already there with the right SHA-1 is not fetched again, one
 * already there with the wrong SHA-1 is, and a download that does not verify is refused without a partial file left
 * behind. And the merge is handed exactly the jars the JSON names, in its order -- not whatever else sits in the
 * folder, where an older guava sorted first used to answer.
 */
public final class MinecraftLibrariesTest {
	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(Path.of(args[0]));
		Path libraries = work.resolve("libraries");
		byte[] guava = "a library jar".getBytes(StandardCharsets.UTF_8), natives = "not for this platform".getBytes(StandardCharsets.UTF_8);
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			byte[] body = exchange.getRequestURI().getPath().endsWith("natives.jar") ? natives : guava;
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) { out.write(body); }
		});
		server.start();
		int checks = 0;
		try {
			String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
			Map<String, Object> json = new LinkedHashMap<>();
			List<Object> list = new ArrayList<>();
			list.add(library("com/google/guava/guava/33/guava-33.jar", base + "guava.jar", sha1(guava), guava.length, null));
			String other = osName().equals("windows") ? "osx" : "windows";
			list.add(library("org/lwjgl/lwjgl/3/lwjgl-natives.jar", base + "natives.jar", sha1(natives), natives.length, other));
			json.put("libraries", list);

			List<Map<String, Object>> artifacts = Installer.minecraftLibraryArtifacts(json);
			require(artifacts.size() == 1, "only this platform's libraries are fetched: " + artifacts);
			checks++;

			MojangDownloader downloader = new MojangDownloader(line -> { });
			Path jar = libraries.resolve("com/google/guava/guava/33/guava-33.jar");
			List<Path> got = downloader.downloadLibraries(artifacts, libraries);
			require(requests.get() == 1 && got.equals(List.of(jar)), "the library was not downloaded: " + got);
			require(Files.isRegularFile(jar) && java.util.Arrays.equals(Files.readAllBytes(jar), guava), "the library is not at its own path");
			require(!Files.exists(libraries.resolve("org/lwjgl")), "another platform's library was fetched");
			checks += 2;

			int before = requests.get();
			require(downloader.downloadLibraries(artifacts, libraries).equals(List.of(jar)) && requests.get() == before,
					"a verified library was fetched again");
			checks++;

			// A file already there whose content is not what the JSON says -- same length, so only the SHA-1 can tell --
			// is not the library. Taking it on its existence alone hands the merge the wrong bytes.
			Files.write(jar, "A LIBRARY JAR".getBytes(StandardCharsets.UTF_8));
			before = requests.get();
			require(downloader.downloadLibraries(artifacts, libraries).equals(List.of(jar)) && requests.get() == before + 1,
					"a library whose SHA-1 does not match was not fetched again");
			require(java.util.Arrays.equals(Files.readAllBytes(jar), guava), "the mismatched library was not replaced by the verified one");
			checks++;

			List<Object> wrong = new ArrayList<>();
			wrong.add(library("com/example/bad/1/bad-1.jar", base + "bad.jar", "0000000000000000000000000000000000000000", guava.length, null));
			json.put("libraries", wrong);
			try {
				downloader.downloadLibraries(Installer.minecraftLibraryArtifacts(json), libraries);
				throw new AssertionError("a library that does not verify was accepted");
			} catch (IOException expected) {
				require(expected.getMessage().contains("sha1 mismatch"), "the refusal does not say why: " + expected.getMessage());
			}
			require(!Files.exists(libraries.resolve("com/example/bad/1/bad-1.jar"))
					&& !Files.exists(libraries.resolve("com/example/bad/1/bad-1.jar.part")), "a library that does not verify left a file");
			checks += 2;

			checks += exactList(work.resolve("exact"), base, guava);
		} finally {
			server.stop(0);
		}
		System.out.println("PASS Minecraft libraries before the merge: " + checks + " checks (platform rules, verified download, cache hit,"
				+ " mismatched file fetched again, refusal, exactly the JSON's jars)");
	}

	/**
	 * What the merge reads is the JSON's list, in its order, and nothing else in the folder: a player's libraries hold
	 * every version any launcher fetched, and the old directory walk let the first in sorted path order answer.
	 */
	private static int exactList(Path work, String base, byte[] bytes) throws Exception {
		Path libraries = work.resolve("libraries");
		// Listed out of path order, so an answer that sorts instead of keeping the JSON's order shows.
		String zlib = "org/zlib/zlib/1/zlib-1.jar", alib = "com/alib/alib/1/alib-1.jar";
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("libraries", List.of(library(zlib, base + "z.jar", sha1(bytes), bytes.length, null),
				library(alib, base + "a.jar", sha1(bytes), bytes.length, null)));
		// What sits in the folder besides them: an older version of a listed library, sorting before everything.
		Path stale = libraries.resolve("com/alib/alib/0.1/alib-0.1.jar");
		Files.createDirectories(stale.getParent());
		Files.write(stale, "an older version nobody listed".getBytes(StandardCharsets.UTF_8));

		List<Path> jars = new MojangDownloader(line -> { }).downloadLibraries(Installer.minecraftLibraryArtifacts(json), libraries);
		require(jars.equals(List.of(libraries.resolve(zlib), libraries.resolve(alib))),
				"the merge is not handed exactly the JSON's jars in its order: " + jars);
		require(!jars.contains(stale), "a jar the JSON does not name reached the merge: " + jars);
		require(MergedBaseTool.classpath(jars).equals(libraries.resolve(zlib) + File.pathSeparator + libraries.resolve(alib)),
				"the tools' argument is not the jars joined in order: " + MergedBaseTool.classpath(jars));
		return 3;
	}

	private static Map<String, Object> library(String path, String url, String sha1, long size, String onlyOn) {
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("path", path); artifact.put("url", url); artifact.put("sha1", sha1); artifact.put("size", size);
		Map<String, Object> lib = new LinkedHashMap<>();
		lib.put("name", path);
		lib.put("downloads", Map.of("artifact", artifact));
		if (onlyOn != null) lib.put("rules", List.of(Map.of("action", "allow", "os", Map.of("name", onlyOn))));
		return lib;
	}

	private static String sha1(byte[] bytes) throws Exception {
		return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes));
	}

	private static String osName() {
		String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
		return os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
