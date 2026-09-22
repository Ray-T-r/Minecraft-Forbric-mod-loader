/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Optional acceptance evidence. Only bytes successfully defined by this loader are recorded. */
final class DefinedClassEvidence {
	static final String PROPERTY = "forbric.definedClassEvidence";
	private final Path directory;

	DefinedClassEvidence() {
		String requested = System.getProperty(PROPERTY);
		if (requested == null || requested.isBlank()) { directory = null; return; }
		try {
			Path root = Path.of(requested).toAbsolutePath().normalize();
			Files.createDirectories(root);
			directory = Files.createTempDirectory(root, "definitions-");
			Files.writeString(directory.resolve("definitions.tsv"), "# forbric-defined-classes-v1\n"
					+ "# Successfully defined bytes; class presence does not prove method execution.\n");
			net.forbric.kernel.util.ForbricLog.info("[Forbric/Evidence] defined classes: %s", directory);
		} catch (IOException failure) { throw new UncheckedIOException("Cannot create requested class evidence", failure); }
	}

	synchronized void defined(String binaryName, byte[] bytes) {
		if (directory == null) return;
		String internal = binaryName.replace('.', '/');
		Path destination = directory.resolve(internal + ".class").normalize();
		if (!destination.startsWith(directory) || internal.indexOf('\t') >= 0 || internal.indexOf('\n') >= 0)
			throw new IllegalArgumentException("Invalid class evidence name: " + binaryName);
		try {
			String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
			Files.createDirectories(destination.getParent());
			Files.write(destination, bytes, StandardOpenOption.CREATE_NEW);
			Files.writeString(directory.resolve("definitions.tsv"), internal + "\t" + hash + "\n", StandardOpenOption.APPEND);
		} catch (IOException failure) {
			throw new UncheckedIOException("Could not record defined bytes for " + binaryName, failure);
		} catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
}
