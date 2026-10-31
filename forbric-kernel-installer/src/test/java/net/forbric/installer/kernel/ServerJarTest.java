package net.forbric.installer.kernel;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The server jar NFRT needs (R4): fetched and SHA-1 checked only when NFRT is about to run, never on a reinstall the
 * cache serves, and never left half-downloaded or mismatched where NFRT would be seeded from it.
 */
public final class ServerJarTest {
	public static void main(String[] args) throws Exception {
		Path work = Files.createDirectories(Path.of(args[0]));
		int checks = 0;
		checks += freshBaseNeedsNoServerJar(work.resolve("fresh"));
		checks += downloads(work.resolve("download"));
		System.out.println("PASS server jar: " + checks + " checks (a cached base needs neither the jar nor the version"
				+ " JSON, a stale one asks for the jar before NFRT; a matching copy is reused, a wrong one replaced, a"
				+ " mismatched download leaves nothing behind)");
	}

	/**
	 * A base the cache serves: no version JSON, no server jar, no network, and no NFRT -- {@code run} must answer
	 * up-to-date before it looks for any of them. A stale one goes for the server jar first, before NFRT is fetched.
	 */
	private static int freshBaseNeedsNoServerJar(Path work) throws Exception {
		Path mc = Files.createDirectories(work.resolve("minecraft"));
		Path out = Files.createDirectories(work.resolve("out")).resolve("patched-mc-neoforge-26.2.jar");
		Files.write(out, new byte[] { 'P', 'K', 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 });
		BuildStamp.write(out);
		Path server = work.resolve("dl/server.jar");
		Path nfrtHome = work.resolve("nfrt");
		Path tools = work.resolve("tools");
		List<String> log = new ArrayList<>();
		NfrtRunner runner = new NfrtRunner(new Http(log::add), tools, nfrtHome, work.resolve("work"), log::add);
		JdkLocator.Jvm jvm = new JdkLocator.Jvm(Path.of(ToolProcess.javaBin()), Runtime.version().feature(), "test");
		ArtifactResult result = runner.run(jvm, mc, out, "net.forbric:patched-mc-neoforge:test", "26.2", server);
		require(result.file.equals(out), "the cached base was not the result: " + result.file);
		require(!Files.exists(server) && !Files.exists(nfrtHome) && !Files.exists(tools),
				"a cached base touched the server jar, NFRT's cache or its tool: " + log);
		require(log.stream().anyMatch(l -> l.contains("up-to-date")), "the cache hit was not logged: " + log);

		Files.delete(out.resolveSibling(out.getFileName() + ".pins"));
		try {
			runner.run(jvm, mc, out, "net.forbric:patched-mc-neoforge:test", "26.2", server);
			throw new AssertionError("a stale base was rebuilt without the version JSON naming its server jar");
		} catch (IOException expected) {
			require(expected.getMessage().contains("version json not found"), "not refused for the server jar: " + expected);
		}
		require(!Files.exists(tools), "NFRT was fetched before the server jar was in hand");
		return 4;
	}

	private static int downloads(Path work) throws Exception {
		byte[] payload = "the 26.2 server jar".getBytes(StandardCharsets.UTF_8);
		AtomicInteger requests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/server.jar", exchange -> {
			requests.incrementAndGet();
			exchange.sendResponseHeaders(200, payload.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(payload);
			}
		});
		server.start();
		try {
			String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/server.jar";
			Path mc = Files.createDirectories(work.resolve("minecraft"));
			Path versionDir = Files.createDirectories(mc.resolve("versions/26.2"));
			Path dest = work.resolve("dl/server.jar");
			Path part = dest.resolveSibling("server.jar.part");
			MojangDownloader downloader = new MojangDownloader(s -> { });

			Files.writeString(versionDir.resolve("26.2.json"), versionJson(url, sha1(payload)));
			downloader.downloadServer(mc, "26.2", dest);
			require(requests.get() == 1 && Arrays.equals(Files.readAllBytes(dest), payload) && !Files.exists(part),
					"the server jar was not downloaded and moved into place");
			downloader.downloadServer(mc, "26.2", dest);
			require(requests.get() == 1, "a copy whose SHA-1 matches was downloaded again");
			Files.writeString(dest, "an older server jar");
			downloader.downloadServer(mc, "26.2", dest);
			require(requests.get() == 2 && Arrays.equals(Files.readAllBytes(dest), payload),
					"a copy whose SHA-1 does not match was kept");

			Files.delete(dest);
			Files.writeString(versionDir.resolve("26.2.json"),
					versionJson(url, sha1("something else".getBytes(StandardCharsets.UTF_8))));
			try {
				downloader.downloadServer(mc, "26.2", dest);
				throw new AssertionError("a server jar whose SHA-1 does not match the version JSON was accepted");
			} catch (IOException expected) {
				require(expected.getMessage().contains("sha1 mismatch"), "not refused for its SHA-1: " + expected);
			}
			require(!Files.exists(dest) && !Files.exists(part), "a mismatched download was left behind");
			return 4;
		} finally {
			server.stop(0);
		}
	}

	private static String versionJson(String url, String sha1) {
		return "{\"id\":\"26.2\",\"downloads\":{\"server\":{\"url\":\"" + url + "\",\"sha1\":\"" + sha1 + "\"}}}";
	}

	private static String sha1(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
