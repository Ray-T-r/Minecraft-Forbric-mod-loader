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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Dependency-free downloader for Mojang's files, using only {@code java.net.http} (JDK 11+).
 *
 * <p>Two jobs. {@link #downloadClient} follows the standard Mojang chain -- the version manifest → the per-version
 * JSON → the client jar -- and writes {@code versions/<v>/<v>.json} and {@code <v>.jar}; that JSON is what makes the
 * Forbric profile's {@code inheritsFrom:<v>} resolvable. {@link #downloadServer} fetches the server jar that JSON
 * names, which NeoFormRuntime needs for its joined distribution and is handed rather than left to fetch
 * ({@link NfrtRunner}). Libraries, assets and native extraction stay the launcher's job on first launch.
 *
 * <p>Safety: every artifact is written to a {@code .part} temp beside its target and verified (size + SHA-1)
 * before an atomic move into place. The client's two files are moved only after both verified, and a directory
 * holding one without the other counts as not having the version ({@code Installer.ensureBaseVersion} checks for
 * both), so an interrupted download is fetched again rather than half used.
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

	/**
	 * The server jar {@code versions/<mc>/<mc>.json} names under {@code downloads.server}, at {@code dest}: reused when a
	 * copy is already there whose SHA-1 matches the JSON, otherwise downloaded beside it and verified before it is moved
	 * into place, so a partial or mismatched download never stands in for it.
	 */
	@SuppressWarnings("unchecked")
	void downloadServer(Path mcDir, String mcVersion, Path dest) throws IOException {
		Path versionJson = mcDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".json");
		if (!Files.isRegularFile(versionJson)) {
			throw new IOException("version json not found: " + versionJson + " — download vanilla " + mcVersion + " first");
		}
		Object root = parseJson(Files.readString(versionJson, StandardCharsets.UTF_8), versionJson.toString());
		Object downloads = root instanceof Map<?, ?> map ? map.get("downloads") : null;
		Map<String, Object> server = downloads instanceof Map<?, ?> d && d.get("server") instanceof Map<?, ?> s
				? (Map<String, Object>) s : null;
		if (server == null || !(server.get("url") instanceof String url)) {
			throw new IOException(mcVersion + ".json has no downloads.server.url (a client-only version can't be "
					+ "NeoForge-patched)");
		}
		String sha1 = server.get("sha1") instanceof String s ? s : null;
		if (Files.isRegularFile(dest) && Files.size(dest) > 0
				&& (sha1 == null || sha1.equalsIgnoreCase(Util.sha1(dest)))) {
			return;
		}
		Files.createDirectories(dest.getParent());
		Path part = dest.resolveSibling(dest.getFileName() + ".part");
		log.accept("[neoform] downloading the " + mcVersion + " server jar …");
		try {
			http.downloadToFile(url, part);
			if (sha1 != null) verifySha1(part, sha1, mcVersion + " server.jar");
			move(part, dest);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(part);
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
