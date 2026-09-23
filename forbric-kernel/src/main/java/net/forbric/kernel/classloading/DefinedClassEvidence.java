/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Optional acceptance evidence. Only bytes successfully defined by this loader are recorded.
 *
 * <p>Stored by CONTENT, {@code blobs/<sha256>.class}, and never by class name. Two classes whose names differ
 * only in case ({@code a/a} and {@code a/A}, ordinary in an obfuscated jar) are one file on the case-insensitive
 * filesystems macOS and Windows ship with, so a per-name file made the second definition's write collide with the
 * first — and that exception left {@code ForbricClassLoader.define} after {@code defineClass} had already
 * succeeded, so the caller got an error for a class the JVM now held. {@code definitions.tsv} maps each name to
 * its blob.
 *
 * <p>Recording never fails the definition it records. A write that fails marks the session {@code #incomplete}
 * in the manifest instead, and the reader refuses an incomplete session: a lost record fails closed where the
 * evidence is judged, not in the game it was observing.
 */
final class DefinedClassEvidence {
	static final String PROPERTY = "forbric.definedClassEvidence";
	static final String HEADER = "# forbric-defined-classes-v2";
	private final Path directory;

	DefinedClassEvidence() {
		String requested = System.getProperty(PROPERTY);
		if (requested == null || requested.isBlank()) { directory = null; return; }
		try {
			Path root = Path.of(requested).toAbsolutePath().normalize();
			Files.createDirectories(root);
			directory = Files.createTempDirectory(root, "definitions-");
			Files.writeString(directory.resolve("definitions.tsv"), HEADER + "\n"
					+ "# <internal name> TAB <sha256 of blobs/<sha256>.class>; successfully defined bytes only."
					+ " Class presence does not prove method execution.\n");
			net.forbric.kernel.util.ForbricLog.info("[Forbric/Evidence] defined classes: %s", directory);
		} catch (IOException failure) { throw new UncheckedIOException("Cannot create requested class evidence", failure); }
	}

	synchronized void defined(String binaryName, byte[] bytes) {
		if (directory == null) return;
		String internal = binaryName.replace('.', '/');
		try {
			if (internal.isEmpty() || internal.chars().anyMatch(c -> c == '\t' || c == '\n' || c == '\r'))
				throw new IOException("class name cannot be written as one manifest row");
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			Path blob = directory.resolve("blobs").resolve(hash + ".class");
			if (!Files.isRegularFile(blob)) {
				// Identical bytes already have their blob. Otherwise write a sibling and move it in, so a reader
				// never meets half a class under a hash it will then fail to match.
				Files.createDirectories(blob.getParent());
				Path part = blob.resolveSibling(hash + ".part");
				Files.write(part, bytes);
				Files.move(part, blob, StandardCopyOption.REPLACE_EXISTING);
			}
			append(internal + "\t" + hash + "\n");
		} catch (IOException | RuntimeException failure) {
			incomplete(internal, failure);
		} catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}

	private void append(String row) throws IOException {
		Files.writeString(directory.resolve("definitions.tsv"), row, StandardOpenOption.APPEND);
	}

	private void incomplete(String internal, Exception failure) {
		String name = internal.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
		String reason = String.valueOf(failure).replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
		try {
			append("#incomplete\t" + name + "\t" + reason + "\n");
		} catch (IOException unwritable) {
			// The manifest itself is gone or read-only; the reader then finds rows without blobs or no manifest,
			// and refuses the session either way.
		}
		net.forbric.kernel.util.ForbricLog.warn("[Forbric/Evidence] could not record defined bytes for %s; this"
				+ " evidence session is incomplete (%s)", name, reason);
	}
}
