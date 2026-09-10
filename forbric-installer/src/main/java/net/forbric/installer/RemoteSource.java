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

package net.forbric.installer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * The third source a manifest entry can be satisfied from: a published GitHub release, instead of a jar bundled
 * inside this installer or a path on the build machine.
 *
 * <p><b>Where each jar comes from.</b> Forbric's own artifacts ({@code net.forbric:*}) exist nowhere but the
 * release, so they are fetched from it. Everything else — ASM, Mixin, SAT4J, night-config and the rest — is a
 * third-party library with a canonical home, so it is fetched from Maven (fabricmc.net for {@code net.fabricmc:*},
 * Maven Central otherwise) and only falls back to the release. Publishing someone else's jar under Forbric's name
 * would be the wrong default even where the licence permits it.
 *
 * <p><b>What "verified" means here.</b> Every download is checked against the SHA-1 the manifest declares, and an
 * already-present file is only treated as a cache hit when its digest matches — "exists and is non-empty" is not
 * good enough for bytes that arrived over a network, because a truncated download satisfies it forever.
 *
 * <p>That check is only as trustworthy as the manifest, though. A SHA-1 carried in a manifest downloaded from the
 * same release as the jars proves the transfer was not corrupted; it proves nothing about whoever wrote the
 * release, since they would simply publish a matching manifest. The trust anchor is therefore
 * {@code manifestSha256} in {@value #RELEASE_PROPERTIES}, compiled into this installer at build time: when it is
 * present the downloaded manifest must match it, and the per-jar digests inherit that trust. When it is absent
 * (an installer built outside a release), the manifest is trusted on TLS alone and this is said out loud rather
 * than passed over in silence.
 */
final class RemoteSource {

	/** Classpath location of the release coordinates baked in at build time; absent in a plain dev build. */
	static final String RELEASE_PROPERTIES = "/forbric-release.properties";
	/** The manifest's filename as a release asset. */
	static final String MANIFEST_ASSET = "forbric-libraries.json";

	private static final String DEFAULT_REPO = "https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader";
	private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";
	private static final String MAVEN_FABRIC = "https://maven.fabricmc.net/";
	private static final String FORBRIC_GROUP = "net.forbric:";

	private final Http http;
	private final Consumer<String> log;
	/** Release asset base, always ending in '/'. */
	private final String assetBase;
	/** Expected SHA-256 of the release manifest, or null when this installer carries no pin. */
	private final String manifestSha256;
	/** URL prefix applied to github.com requests (a ghproxy-style relay), or null. */
	private final String mirror;
	private final String tag;

	private RemoteSource(Http http, Consumer<String> log, String assetBase, String manifestSha256,
			String mirror, String tag) {
		this.http = http;
		this.log = log;
		this.assetBase = assetBase.endsWith("/") ? assetBase : assetBase + "/";
		this.manifestSha256 = manifestSha256;
		this.mirror = mirror;
		this.tag = tag;
	}

	/**
	 * Build a source for a release.
	 *
	 * @param tagOverride an explicit release tag ({@code --release}), or null to use the tag this installer was
	 *                    built against — falling back to {@code releases/latest} when there is none. Passing a tag
	 *                    explicitly discards the compiled-in manifest pin, because that pin describes a different
	 *                    release and could not match.
	 * @param mirror      a URL prefix placed in front of every github.com request, for networks where github.com
	 *                    is slow or blocked (e.g. {@code https://ghproxy.example/}), or null.
	 */
	static RemoteSource create(Http http, Consumer<String> log, String tagOverride, String mirror) {
		Properties p = loadReleaseProperties();
		String builtTag = trimToNull(p.getProperty("tag"));
		String pin = trimToNull(p.getProperty("manifestSha256"));
		String repo = trimToNull(p.getProperty("repo"));
		if (repo == null) repo = DEFAULT_REPO;

		String tag = trimToNull(tagOverride) != null ? tagOverride.trim() : builtTag;
		String base;
		if (tag != null) {
			base = repo + "/releases/download/" + tag + "/";
		} else {
			// No tag anywhere: take whatever the repository currently calls latest. Fine for fetching, but it
			// means the installed version can change under you, so it is never the pinned path.
			base = repo + "/releases/latest/download/";
		}
		// A pin only describes the release it was generated from. Asking for a different tag invalidates it.
		String effectivePin = (trimToNull(tagOverride) == null || tagOverride.trim().equals(builtTag)) ? pin : null;
		return new RemoteSource(http, log, base, effectivePin, trimToNull(mirror), tag);
	}

	/** Human-readable description of where this source points, for the install log. */
	String describe() {
		StringBuilder sb = new StringBuilder();
		sb.append(tag != null ? tag : "latest");
		sb.append(manifestSha256 != null ? " (manifest pinned)" : " (manifest UNPINNED)");
		if (mirror != null) sb.append(" via ").append(mirror);
		return sb.toString();
	}

	/**
	 * Fetch the release manifest and verify it against the compiled-in pin when there is one.
	 *
	 * @throws IOException if the download fails, or the manifest does not match the pin — a mismatch is fatal, not
	 *                     a warning, because it means the bytes are not the ones this installer was built for.
	 */
	String manifestJson() throws IOException {
		String url = github(assetBase + MANIFEST_ASSET);
		log.accept("fetching manifest  " + url);
		byte[] body = http.getBytes(url);
		String actual = sha256(body);
		if (manifestSha256 != null) {
			if (!manifestSha256.equalsIgnoreCase(actual)) {
				throw new IOException("release manifest does not match the digest this installer was built with"
						+ " (expected " + manifestSha256 + ", got " + actual + "). Refusing to install:"
						+ " either the release was re-published with different content, or the download was"
						+ " tampered with.");
			}
		} else {
			log.accept("WARNING: this installer carries no manifest digest, so the release is trusted on"
					+ " HTTPS alone. A release-built installer pins it.");
		}
		return new String(body, StandardCharsets.UTF_8);
	}

	/**
	 * Put {@code lib}'s bytes at {@code dest}, downloading only if what is already there is not already correct.
	 * The manifest's SHA-1 is enforced before the file is moved into place, so a failed or truncated transfer
	 * never leaves a plausible-looking jar behind.
	 */
	void fetchInto(Installer.Lib lib, Path dest) throws IOException {
		if (lib.sha1 != null && Files.isRegularFile(dest) && lib.sha1.equalsIgnoreCase(Util.sha1(dest))) {
			log.accept("cached  " + lib.coordinate);
			return;
		}

		String relPath = Util.coordinateToPath(lib.coordinate);
		String basename = relPath.substring(relPath.lastIndexOf('/') + 1);
		String primary, fallback;
		if (lib.coordinate.startsWith(FORBRIC_GROUP)) {
			// Forbric's own jars are published only by the release.
			primary = github(assetBase + basename);
			fallback = null;
		} else {
			primary = (lib.coordinate.startsWith("net.fabricmc:") ? MAVEN_FABRIC : MAVEN_CENTRAL) + relPath;
			fallback = github(assetBase + basename);
		}

		Files.createDirectories(dest.getParent());
		Path part = dest.resolveSibling(dest.getFileName() + ".part");
		try {
			IOException first;
			try {
				http.downloadToFile(primary, part);
				first = null;
			} catch (IOException e) {
				first = e;
			}
			if (first != null) {
				if (fallback == null) throw first;
				log.accept("  " + primary + " failed (" + first.getMessage() + "), trying the release asset");
				http.downloadToFile(fallback, part);
			}

			if (lib.sha1 != null) {
				String actual = Util.sha1(part);
				if (!lib.sha1.equalsIgnoreCase(actual)) {
					throw new IOException("sha1 mismatch downloading " + lib.coordinate
							+ " (manifest " + lib.sha1 + " vs downloaded " + actual + ")");
				}
			}
			Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			try {
				Files.deleteIfExists(part);
			} catch (IOException ignore) {
				// A leftover .part is harmless: the next run overwrites it.
			}
		}
	}

	/** Apply the mirror prefix, if any, to a github.com URL. Maven URLs are left alone. */
	private String github(String url) {
		return mirror == null ? url : mirror + url;
	}

	private static Properties loadReleaseProperties() {
		Properties p = new Properties();
		try (InputStream in = RemoteSource.class.getResourceAsStream(RELEASE_PROPERTIES)) {
			if (in != null) p.load(in);
		} catch (IOException e) {
			throw new UncheckedIOException("unreadable " + RELEASE_PROPERTIES, e);
		}
		return p;
	}

	private static String sha256(byte[] body) throws IOException {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(body);
			StringBuilder sb = new StringBuilder(64);
			for (byte b : d) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 unavailable", e);
		}
	}

	private static String trimToNull(String s) {
		return (s == null || s.trim().isEmpty()) ? null : s.trim();
	}
}
