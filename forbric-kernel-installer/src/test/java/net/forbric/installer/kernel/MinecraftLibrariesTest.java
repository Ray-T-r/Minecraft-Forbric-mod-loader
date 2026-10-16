package net.forbric.installer.kernel;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * The base version's libraries, which the merge reads: a launcher fetches them only on the first launch, and a
 * directory no launcher has started the version in has none -- the merge then proved nothing and refused (gate-m17).
 * Served from a local HTTP server:
 *
 * <ul>
 *   <li>what the version JSON lists for this platform lands at its own path and is verified; a file already there
 *       with the right SHA-1 is not fetched again, one there with the wrong SHA-1 is, and a download that does not
 *       verify is refused without a partial file left behind;</li>
 *   <li>the merge is handed exactly the jars the JSON names, in its order -- not whatever else sits in the folder,
 *       where an older guava sorted first used to answer;</li>
 *   <li>they are asked for only when the merge or the interop patch actually runs, so a reinstall whose artifacts
 *       are all cached works with no network, even with a library missing.</li>
 * </ul>
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
			require(Files.isRegularFile(jar) && Arrays.equals(Files.readAllBytes(jar), guava), "the library is not at its own path");
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
			require(Arrays.equals(Files.readAllBytes(jar), guava), "the mismatched library was not replaced by the verified one");
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
			checks += onlyWhenAToolRuns(work.resolve("lazy"), guava);
		} finally {
			server.stop(0);
		}
		System.out.println("PASS Minecraft libraries for the merge: " + checks + " checks (platform rules, verified download,"
				+ " cache hit, mismatched file fetched again, refusal, exactly the JSON's jars, only when a tool runs)");
	}

	/**
	 * What the merge reads is the JSON's list, in its order, and nothing else in the folder: a player's libraries hold
	 * every version any launcher fetched, and the old directory walk let the first in sorted path order answer.
	 */
	private static int exactList(Path work, String base, byte[] bytes) throws Exception {
		Path mc = Files.createDirectories(work.resolve("minecraft"));
		Path libraries = mc.resolve("libraries");
		// Listed out of path order, so an answer that sorts instead of keeping the JSON's order shows.
		String zlib = "org/zlib/zlib/1/zlib-1.jar", alib = "com/alib/alib/1/alib-1.jar";
		versionJson(mc, "9.9", library(zlib, base + "z.jar", sha1(bytes), bytes.length, null),
				library(alib, base + "a.jar", sha1(bytes), bytes.length, null));
		// What sits in the folder besides them: an older version of a listed library, sorting before everything.
		Path stale = libraries.resolve("com/alib/alib/0.1/alib-0.1.jar");
		Files.createDirectories(stale.getParent());
		Files.write(stale, "an older version nobody listed".getBytes(StandardCharsets.UTF_8));

		List<Path> jars = ArtifactBuilder.baseLibraries(mc, "9.9", new MojangDownloader(line -> { })).jars();
		require(jars.equals(List.of(libraries.resolve(zlib), libraries.resolve(alib))),
				"the merge is not handed exactly the JSON's jars in its order: " + jars);
		require(!jars.contains(stale), "a jar the JSON does not name reached the merge: " + jars);
		require(MergedBaseTool.classpath(jars).equals(libraries.resolve(zlib) + File.pathSeparator + libraries.resolve(alib)),
				"the tools' argument is not the jars joined in order: " + MergedBaseTool.classpath(jars));
		return 3;
	}

	/**
	 * The merge and the interop patch ask for the libraries only once they have found their output stale. A cached
	 * reinstall therefore never asks -- here with the library URL pointing at a port nothing listens on and one
	 * library missing, which is an offline machine whose libraries folder is not complete: before, the download ran
	 * ahead of the merge's cache check and that install failed although nothing needed the libraries.
	 */
	private static int onlyWhenAToolRuns(Path work, byte[] bytes) throws Exception {
		int checks = 0;
		Path mc = Files.createDirectories(work.resolve("minecraft"));
		String offline = "http://127.0.0.1:" + closedPort() + "/";
		String present = "com/google/guava/guava/33/guava-33.jar", missing = "org/example/gone/1/gone-1.jar";
		versionJson(mc, "9.9", library(present, offline + "guava.jar", sha1(bytes), bytes.length, null),
				library(missing, offline + "gone.jar", sha1(bytes), bytes.length, null));
		Path presentJar = mc.resolve("libraries").resolve(present);
		Files.createDirectories(presentJar.getParent());
		Files.write(presentJar, bytes);

		Path out = Files.createDirectories(work.resolve("out"));
		Path merged = out.resolve("patched-mc-merged-9.9.jar"), interop = out.resolve("forge-runtime-interop.jar");
		Files.write(merged, new byte[] { 1 });
		Files.write(interop, new byte[] { 2 });
		BuildStamp.write(merged);
		BuildStamp.write(interop);
		Path tools = work.resolve("tools");
		List<String> logs = new ArrayList<>();
		MergedBaseTool tool = new MergedBaseTool(tools, logs::add);
		JdkLocator.Jvm jvm = new JdkLocator.Jvm(Path.of(ForgeTool.javaBin()), Runtime.version().feature(), "test");

		// Cached: neither tool asks, so the offline machine with an incomplete folder installs.
		AtomicInteger asked = new AtomicInteger();
		MergedBaseTool.Libraries real = ArtifactBuilder.baseLibraries(mc, "9.9", new MojangDownloader(logs::add));
		MergedBaseTool.Libraries counted = () -> {
			asked.incrementAndGet();
			return real.jars();
		};
		merge(tool, jvm, work, merged, counted);
		tool.interop(jvm, work.resolve("forge-runtime.jar"), interop, "c", merged, work.resolve("neo.jar"), counted);
		require(asked.get() == 0, "a cached merge or interop patch asked for the libraries");
		require(logs.contains("[merge] up-to-date: " + merged.getFileName())
				&& logs.contains("[interop] up-to-date: " + interop.getFileName()), "the cached artifacts were not reused: " + logs);
		require(!Files.exists(mc.resolve("libraries").resolve(missing)), "a cached install fetched a library");
		checks += 2;

		// Stale: each asks before its tool starts. Asking here means fetching the missing library, which offline fails,
		// and the failure is the fetch's -- no tool was unpacked, so none ran on an incomplete library list.
		Files.delete(merged.resolveSibling(merged.getFileName() + ".pins"));
		Files.delete(interop.resolveSibling(interop.getFileName() + ".pins"));
		try {
			merge(tool, jvm, work, merged, counted);
			throw new AssertionError("a merge that has to run went ahead without its libraries");
		} catch (IOException expected) {
			require(asked.get() == 1, "a merge that has to run did not ask for the libraries");
		}
		require(!Files.exists(tools.resolve("forbric-merge-tools.jar")), "the merge tool ran before its libraries were in place");
		try {
			tool.interop(jvm, work.resolve("forge-runtime.jar"), interop, "c", merged, work.resolve("neo.jar"), counted);
			throw new AssertionError("an interop patch that has to run went ahead without its libraries");
		} catch (IOException expected) {
			require(asked.get() == 2, "an interop patch that has to run did not ask for the libraries");
		}
		require(!Files.exists(tools.resolve("forbric-merge-tools.jar")), "the interop tool ran before its libraries were in place");
		checks += 2;
		return checks;
	}

	private static void merge(MergedBaseTool tool, JdkLocator.Jvm jvm, Path work, Path out, MergedBaseTool.Libraries libraries)
			throws IOException {
		tool.merge(jvm, work.resolve("vanilla.jar"), work.resolve("forge.jar"), work.resolve("neo.jar"),
				work.resolve("forge-runtime.jar"), work.resolve("neo-runtime.jar"), out, work.resolve("report.txt"), "c",
				libraries, work.resolve("forge-userdev.jar"), work.resolve("neo-userdev.jar"));
	}

	@SafeVarargs
	private static void versionJson(Path mc, String version, Map<String, Object>... libraries) throws IOException {
		StringBuilder json = new StringBuilder("{\"id\":\"").append(version).append("\",\"libraries\":[");
		for (int i = 0; i < libraries.length; i++) {
			@SuppressWarnings("unchecked")
			Map<String, Object> artifact = (Map<String, Object>) ((Map<String, Object>) libraries[i].get("downloads")).get("artifact");
			if (i > 0) json.append(',');
			json.append("{\"name\":\"").append(libraries[i].get("name")).append("\",\"downloads\":{\"artifact\":{")
					.append("\"path\":\"").append(artifact.get("path")).append("\",\"url\":\"").append(artifact.get("url"))
					.append("\",\"sha1\":\"").append(artifact.get("sha1")).append("\",\"size\":").append(artifact.get("size"))
					.append("}}}");
		}
		Path dir = Files.createDirectories(mc.resolve("versions").resolve(version));
		Files.writeString(dir.resolve(version + ".json"), json.append("]}").toString());
	}

	/** A port nothing listens on: bound by the system, then released. */
	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
			return socket.getLocalPort();
		}
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
