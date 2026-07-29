package net.forbric.installer;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Dependency-free zip/jar primitives, replacing the {@code python3} heredocs in the dev build scripts
 * ({@code assemble-minecraftforge-runtime.sh} and {@code build-patched-forge.sh}). The Forge-specific merge/overlay
 * policy lives in {@link ForgeRuntimeBuilder}/{@link PatchedMcBuilder}; this class only reads, writes, and extracts.
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

	/** Write a deflated jar whose entries are exactly {@code entries}, in iteration order. */
	static void writeJar(Path out, Map<String, byte[]> entries) throws IOException {
		Files.createDirectories(out.getParent());
		try (OutputStream fos = Files.newOutputStream(out);
		     ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {
			zos.setMethod(ZipOutputStream.DEFLATED);
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				ZipEntry ze = new ZipEntry(e.getKey());
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
