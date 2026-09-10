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
import java.io.OutputStream;
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
 *
 * <p>Every download reports progress through the log consumer. This is the only class that needs to: all of
 * them — the release jars, Mojang's client jar, Forge's Maven artifacts — funnel through the two methods
 * below. The bodies are streamed by hand rather than handed to {@code BodyHandlers.ofFile}, which writes the
 * whole response with nothing observable in between; on the multi-megabyte downloads that is several silent
 * minutes, indistinguishable from a hang.
 */
final class Http {

	/**
	 * Marks a line as a transient status update: whatever is logged next REPLACES it instead of following it,
	 * so a download reports live on one line rather than scrolling a hundred of them past. The marker is a
	 * carriage return because that is already what it means to a terminal, so a consumer that knows nothing
	 * about this convention still renders it about right.
	 */
	static final String PROGRESS = "\r";

	/** How often a running download refreshes its line. Often enough to look alive, rare enough not to spam. */
	private static final long REPORT_INTERVAL_NANOS = 250_000_000L;

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
		if (!stream(url, dest)) {
			throw new IOException("HTTP error downloading " + url);
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
		return stream(url, dest);
	}

	/**
	 * The one download primitive: stream a 200 body to {@code dest}, reporting progress as it goes. Returns
	 * false on any non-200, having removed the partial file.
	 */
	private boolean stream(String url, Path dest) throws IOException {
		String name = fileName(url);
		info(PROGRESS + "  " + name + "  connecting...");
		HttpResponse<InputStream> r = send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream in = r.body()) {
			if (r.statusCode() != 200) {
				deleteQuietly(dest);
				return false;
			}
			// Absent on a chunked response, in which case we can still report bytes moved -- which is all the
			// question "is it stuck?" actually needs.
			long total = r.headers().firstValueAsLong("content-length").orElse(-1L);
			if (dest.getParent() != null) Files.createDirectories(dest.getParent());
			try (OutputStream out = Files.newOutputStream(dest)) {
				copy(in, out, total, name);
			}
		}
		return true;
	}

	private void copy(InputStream in, OutputStream out, long total, String name) throws IOException {
		byte[] buf = new byte[65536];
		long done = 0;
		long lastReport = System.nanoTime();
		boolean reported = false;
		int n;
		while ((n = in.read(buf)) != -1) {
			out.write(buf, 0, n);
			done += n;
			long now = System.nanoTime();
			if (now - lastReport >= REPORT_INTERVAL_NANOS) {
				info(PROGRESS + progressLine(name, done, total));
				lastReport = now;
				reported = true;
			}
		}
		// Land on a finished line rather than whatever fraction the last tick happened to catch. Skipped for a
		// download small enough that nothing was ever reported -- there is nothing to correct.
		if (reported) info(PROGRESS + progressLine(name, done, total < 0 ? done : total));
	}

	private static String progressLine(String name, long done, long total) {
		if (total > 0) {
			long pct = Math.min(100, done * 100 / total);
			return String.format("  %s  %3d%%  %s / %s", name, pct, human(done), human(total));
		}
		return "  " + name + "  " + human(done);
	}

	private static String human(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
		return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
	}

	/** Last path segment of a URL, for labelling progress. Falls back to the whole URL. */
	private static String fileName(String url) {
		int q = url.indexOf('?');
		String path = q >= 0 ? url.substring(0, q) : url;
		int slash = path.lastIndexOf('/');
		String name = slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
		return name.isEmpty() ? url : name;
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

	/** Emit a line to whoever is showing the install log, if anyone is. */
	private void info(String line) {
		if (log != null) log.accept(line);
	}
}
