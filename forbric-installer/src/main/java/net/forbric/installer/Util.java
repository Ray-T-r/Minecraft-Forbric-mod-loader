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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small dependency-free helpers: SHA-1, Maven-coordinate → path, path/home expansion, launcher dir defaults. */
final class Util {
	private Util() {}

	/** Lowercase hex SHA-1 of a file — the checksum launchers compare against a library's declared {@code sha1}. */
	static String sha1(Path p) throws IOException {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-1");
			try (InputStream in = Files.newInputStream(p)) {
				byte[] buf = new byte[65536];
				int n;
				while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
			}
			StringBuilder sb = new StringBuilder(40);
			for (byte b : md.digest()) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-1 unavailable", e);
		}
	}

	/**
	 * "group:artifact:version[:classifier]" → Maven-layout relative path
	 * "group/with/slashes/artifact/version/artifact-version[-classifier].jar" — the same layout every launcher
	 * uses under {@code libraries/}.
	 */
	static String coordinateToPath(String coordinate) {
		String[] p = coordinate.split(":");
		if (p.length < 3) throw new IllegalArgumentException("bad coordinate: " + coordinate);
		String group = p[0].replace('.', '/');
		String artifact = p[1];
		String version = p[2];
		String classifier = p.length > 3 ? "-" + p[3] : "";
		return group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar";
	}

	/** Expand a leading {@code ~/} to the user's home dir (Java does not do this for us). */
	static Path path(String raw) {
		if (raw.startsWith("~" + java.io.File.separator) || raw.equals("~")) {
			raw = System.getProperty("user.home") + raw.substring(1);
		} else if (raw.startsWith("~/")) {
			raw = System.getProperty("user.home") + raw.substring(1);
		}
		return Paths.get(raw);
	}

	/** Default Minecraft directory per OS (the same location the official launcher and PCL/HMCL use by default). */
	static Path defaultMinecraftDir() {
		String os = System.getProperty("os.name", "").toLowerCase();
		String home = System.getProperty("user.home");
		if (os.contains("win")) {
			String appdata = System.getenv("APPDATA");
			return Paths.get(appdata != null ? appdata : home, ".minecraft");
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return Paths.get(home, "Library", "Application Support", "minecraft");
		}
		return Paths.get(home, ".minecraft");
	}
}
