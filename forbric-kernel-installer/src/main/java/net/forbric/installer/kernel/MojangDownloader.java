package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dependency-free downloader for the vanilla Minecraft client, using only {@code java.net.http} (JDK 11+).
 *
 * <p>It follows the standard Mojang chain: the version manifest → the per-version JSON → the client jar. It
 * downloads only {@code versions/<v>/<v>.json} and {@code <v>.jar}; the launcher itself resolves the base's
 * libraries/assets/natives on first launch from the {@code <v>.json} written here (that is what makes the
 * Forbric profile's {@code inheritsFrom:<v>} resolvable).
 *
 * <p>Safety: every artifact is written to a {@code .part} temp beside its target and verified (size + SHA-1)
 * before an atomic move into place; the {@code <v>.json} is committed only after the jar verifies, so a crash
 * never leaves a {@code <v>.json} without a matching {@code <v>.jar} — the exact half-state
 * {@link Installer#discoverBaseVersions} guards against.
 */
final class MojangDownloader {

	static final String VERSION_MANIFEST =
			"https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

	private final Http http;
	private final Consumer<String> log;

	MojangDownloader(Consumer<String> log) {
		this.log = log;
		this.http = new Http(log);
	}

	/** Download + verify {@code <v>.json} and {@code <v>.jar} into {@code verDir}. */
	@SuppressWarnings("unchecked")
	void downloadClient(String mcVersion, Path verDir) throws IOException {
		// A) Version manifest → the per-version JSON url (and that JSON's own sha1).
		Map<String, Object> manifest = (Map<String, Object>) parseJson(http.getString(VERSION_MANIFEST), VERSION_MANIFEST);
		String perVersionUrl = null, jsonSha1 = null;
		Object versions = manifest.get("versions");
		if (versions instanceof List) {
			for (Object o : (List<Object>) versions) {
				Map<String, Object> e = (Map<String, Object>) o;
				if (mcVersion.equals(e.get("id"))) {
					perVersionUrl = (String) e.get("url");
					jsonSha1 = (String) e.get("sha1");
					break;
				}
			}
		}
		if (perVersionUrl == null) {
			throw new IOException(mcVersion + " is not a known Minecraft version "
					+ "(Mojang's version manifest lists no such id)");
		}

		Path json = verDir.resolve(mcVersion + ".json");
		Path jar = verDir.resolve(mcVersion + ".jar");
		Path jsonPart = verDir.resolve(mcVersion + ".json.part");
		Path jarPart = verDir.resolve(mcVersion + ".jar.part");
		Files.createDirectories(verDir);

		try {
			// B) Per-version JSON → downloads.client {url, sha1, size}.
			byte[] jsonBytes = http.getBytes(perVersionUrl);
			Files.write(jsonPart, jsonBytes);
			if (jsonSha1 != null) verifySha1(jsonPart, jsonSha1, mcVersion + ".json");
			Map<String, Object> perVersion =
					(Map<String, Object>) parseJson(new String(jsonBytes, StandardCharsets.UTF_8), perVersionUrl);
			Map<String, Object> downloads = (Map<String, Object>) perVersion.get("downloads");
			Map<String, Object> client = downloads == null ? null : (Map<String, Object>) downloads.get("client");
			if (client == null || client.get("url") == null) {
				throw new IOException("per-version JSON for " + mcVersion + " has no downloads.client.url");
			}
			String clientUrl = (String) client.get("url");
			String clientSha1 = (String) client.get("sha1");
			long clientSize = client.get("size") instanceof Number ? ((Number) client.get("size")).longValue() : -1L;

			// C) Client jar → stream to a temp, verify size + sha1.
			log.accept("downloading " + mcVersion + ".jar ("
					+ (clientSize > 0 ? (clientSize / (1024 * 1024)) + " MB" : "size unknown") + ") …");
			http.downloadToFile(clientUrl, jarPart);
			long got = Files.size(jarPart);
			if (clientSize >= 0 && got != clientSize) {
				throw new IOException("size mismatch on " + mcVersion + ".jar (expected " + clientSize
						+ ", got " + got + ")");
			}
			if (clientSha1 != null) verifySha1(jarPart, clientSha1, mcVersion + ".jar");

			// D) Both verified — commit. json first, then jar, so a crash never leaves a jar without its json
			//    (and vice-versa still re-triggers a full re-download since both files are required).
			move(jsonPart, json);
			move(jarPart, jar);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(jsonPart);
			deleteQuietly(jarPart);
			throw e;
		}
	}

	// ---- helpers ----

	private static Object parseJson(String text, String where) throws IOException {
		try {
			return Json.parse(text);
		} catch (RuntimeException e) {
			throw new IOException("malformed JSON from " + where + ": " + e.getMessage(), e);
		}
	}

	private static void verifySha1(Path file, String expected, String what) throws IOException {
		String actual = Util.sha1(file);
		if (!expected.equalsIgnoreCase(actual)) {
			throw new IOException("sha1 mismatch on " + what + " (expected " + expected + ", got " + actual + ")");
		}
	}

	private static void move(Path src, Path dest) throws IOException {
		try {
			Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void deleteQuietly(Path p) {
		try {
			Files.deleteIfExists(p);
		} catch (IOException ignored) {
			// best-effort cleanup
		}
	}
}
