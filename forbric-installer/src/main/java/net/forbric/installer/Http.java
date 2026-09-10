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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Dependency-free HTTP helper (JDK {@code java.net.http} only), shared by {@link MojangDownloader} and the
 * install-time Forge builders. Follows redirects (Mojang piston-data + Forge Maven both 30x), reports non-200 as
 * an {@link IOException}, and wraps transport failures with an actionable "offline?" message.
 *
 * <p>{@link #ensure} is the cached-download primitive: it is a no-op when the destination already exists, else it
 * streams to a {@code .part} temp and atomically moves it into place — mirroring the dev scripts' {@code get()}
 * ({@code [ -f "$out" ] && return 0; curl -L ...}). {@link #ensureWithFallback} adds the scripts'
 * "Forge Maven, then Maven Central" fallback.
 */
final class Http {

	private final HttpClient http;
	private final Consumer<String> log;

	Http(Consumer<String> log) {
		this.log = log;
		this.http = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(Duration.ofSeconds(20))
				.build();
	}

	/** GET a URL as bytes; throws on any non-200. */
	byte[] getBytes(String url) throws IOException {
		HttpResponse<byte[]> r = send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
		return r.body();
	}

	/** GET a URL as a UTF-8 string; throws on any non-200. */
	String getString(String url) throws IOException {
		return new String(getBytes(url), StandardCharsets.UTF_8);
	}

	/** Stream a URL straight to {@code dest} (overwriting); throws on non-200 and removes the partial file. */
	void downloadToFile(String url, Path dest) throws IOException {
		HttpResponse<Path> r = send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofFile(dest));
		if (r.statusCode() != 200) {
			deleteQuietly(dest);
			throw new IOException("HTTP " + r.statusCode() + " downloading " + url);
		}
	}

	/**
	 * Cached download: no-op if {@code dest} already exists and is non-empty; otherwise stream {@code url} to a
	 * {@code .part} temp, require a non-empty body, and atomically move into place.
	 */
	void ensure(String url, Path dest) throws IOException {
		ensureWithFallback(url, null, dest);
	}

	/**
	 * Cached download with a fallback URL (the scripts' Forge-Maven-then-Central pattern). Tries {@code primaryUrl}
	 * first; on a non-200/transport error and when {@code fallbackUrl} is non-null, tries the fallback. No-op if
	 * {@code dest} already exists non-empty.
	 */
	void ensureWithFallback(String primaryUrl, String fallbackUrl, Path dest) throws IOException {
		if (Files.isRegularFile(dest)) {
			try {
				if (Files.size(dest) > 0) return;
			} catch (IOException ignore) { /* fall through and re-fetch */ }
		}
		Files.createDirectories(dest.getParent());
		Path part = dest.resolveSibling(dest.getFileName() + ".part");
		try {
			if (!tryStream(primaryUrl, part) || isEmpty(part)) {
				deleteQuietly(part);
				if (fallbackUrl == null) {
					throw new IOException("could not download " + primaryUrl + " (non-200 or empty body)");
				}
				if (!tryStream(fallbackUrl, part) || isEmpty(part)) {
					deleteQuietly(part);
					throw new IOException("could not download " + primaryUrl
							+ " (also tried fallback " + fallbackUrl + ")");
				}
			}
			move(part, dest);
		} catch (IOException | RuntimeException e) {
			deleteQuietly(part);
			throw e;
		}
	}

	// ---- internals ----

	/** Stream {@code url} to {@code dest}; return true on 200, false on any non-200 (partial removed). */
	private boolean tryStream(String url, Path dest) throws IOException {
		HttpResponse<Path> r = send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofFile(dest));
		if (r.statusCode() != 200) {
			deleteQuietly(dest);
			return false;
		}
		return true;
	}

	private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			return http.send(req, handler);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted while contacting " + req.uri(), e);
		} catch (IOException e) {
			throw new IOException("could not reach " + req.uri().getHost() + " — offline? Cause: " + e, e);
		}
	}

	private static boolean isEmpty(Path p) {
		try {
			return !Files.isRegularFile(p) || Files.size(p) == 0;
		} catch (IOException e) {
			return true;
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

	void info(String line) {
		if (log != null) log.accept(line);
	}
}
