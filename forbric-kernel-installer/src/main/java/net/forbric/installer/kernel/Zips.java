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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Dependency-free zip/jar primitives, replacing the {@code python3} heredocs in the dev build scripts
 * ({@code assemble-neoforge-runtime.sh}). The runtime's merge policy lives in {@link NeoForgeRuntimeBuilder}; this
 * class only reads, writes, and extracts.
 */
final class Zips {
	private Zips() {}

	/** Read every non-directory entry into an insertion-ordered map (name → bytes). */
	static LinkedHashMap<String, byte[]> readAll(Path zip) throws IOException {
		LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			// Sort by name for a stable, launcher-independent iteration order.
			TreeMap<String, ZipEntry> sorted = new TreeMap<>();
			zf.stream().filter(e -> !e.isDirectory()).forEach(e -> sorted.put(e.getName(), e));
			for (ZipEntry e : sorted.values()) {
				try (InputStream in = zf.getInputStream(e)) {
					out.put(e.getName(), in.readAllBytes());
				}
			}
		}
		return out;
	}

	/** Read a single entry; returns null if absent. */
	static byte[] readEntry(Path zip, String name) throws IOException {
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			ZipEntry e = zf.getEntry(name);
			if (e == null) return null;
			try (InputStream in = zf.getInputStream(e)) {
				return in.readAllBytes();
			}
		}
	}

	/** Extract the named entries into {@code destDir} (creating parents), overwriting — like {@code unzip -o}. */
	static void extractEntries(Path zip, Path destDir, String... names) throws IOException {
		try (ZipFile zf = new ZipFile(zip.toFile())) {
			for (String name : names) {
				ZipEntry e = zf.getEntry(name);
				if (e == null) throw new IOException("entry '" + name + "' not found in " + zip.getFileName());
				Path dest = destDir.resolve(name);
				Files.createDirectories(dest.getParent());
				try (InputStream in = zf.getInputStream(e)) {
					Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/**
	 * The one time every entry {@link #writeJar} writes carries, the same one {@link VanillaReference} uses.
	 *
	 * <p>Without it each entry took the clock's time, so the NeoForge runtime built twice from the same inputs came out
	 * with the same entries and contents but different bytes: an upgrade rewrote {@code net.forbric:neoforge-runtime:
	 * 26.2.0.93} in place under a new SHA-1, and two machines never agreed on one. {@link ZipEntry#setTimeLocal} writes it
	 * as given, in every time zone.
	 */
	static final LocalDateTime FIXED_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

	/**
	 * Write a deflated jar whose entries are exactly {@code entries}, in iteration order, every one at {@link #FIXED_TIME}:
	 * the same entries give the same bytes.
	 */
	static void writeJar(Path out, Map<String, byte[]> entries) throws IOException {
		writeJar(out, entries, FIXED_TIME);
	}

	/** {@link #writeJar(Path, Map)} at another entry time: only the reproducible-runtime test asks for one. */
	static void writeJar(Path out, Map<String, byte[]> entries, LocalDateTime entryTime) throws IOException {
		Files.createDirectories(out.getParent());
		try (OutputStream fos = Files.newOutputStream(out);
		     ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {
			zos.setMethod(ZipOutputStream.DEFLATED);
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				ZipEntry ze = new ZipEntry(e.getKey());
				ze.setTimeLocal(entryTime);
				zos.putNextEntry(ze);
				zos.write(e.getValue());
				zos.closeEntry();
			}
		}
	}

	/** True for a JAR signature side-file ({@code META-INF/*.SF|RSA|DSA|EC}) — stripped when re-serializing classes. */
	static boolean isSignatureFile(String name) {
		String u = name.toUpperCase(Locale.ROOT);
		if (!u.startsWith("META-INF/")) return false;
		int dot = u.lastIndexOf('.');
		if (dot < 0) return false;
		String ext = u.substring(dot + 1);
		return ext.equals("SF") || ext.equals("RSA") || ext.equals("DSA") || ext.equals("EC");
	}
}
