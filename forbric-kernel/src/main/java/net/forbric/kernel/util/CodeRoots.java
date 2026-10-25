/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;

/**
 * Where a class's code was read from.
 *
 * <p>Asking a class for its jar means {@code getProtectionDomain().getCodeSource().getLocation()}, and every step of
 * that can legitimately answer null. A class generated at run time has no jar: a dynamic proxy is defined with no
 * code source at all, as is anything a loader defines with no protection domain, and a JDK class has none either.
 * Writing the chain out at a call site made the first proxy that reached it a {@code NullPointerException}, reported
 * as a kernel failure (the transfer interop did exactly that, on every gate-m39 run). Those classes are not a
 * failure to find a jar; they are classes that came from no jar, and {@link #of} says so with null.
 *
 * <p>A hidden class -- a lambda, or anything {@code Lookup.defineHiddenClass} made -- is defined in its lookup class's
 * protection domain, so it answers with the jar of the class that defined it, which is the answer a caller asking
 * "whose code is this" wants.
 */
public final class CodeRoots {
	private CodeRoots() {
	}

	/**
	 * The jar or class directory {@code type} was defined from, or null when it was defined from neither: no protection
	 * domain, no code source, no location (a dynamic proxy, a JDK class), a location that is not a local file, or a path
	 * that no longer exists.
	 */
	public static Path of(Class<?> type) {
		try {
			ProtectionDomain domain = type.getProtectionDomain();
			CodeSource source = domain == null ? null : domain.getCodeSource();
			URL location = source == null ? null : source.getLocation();
			if (location == null || !"file".equals(location.getProtocol())) return null;
			Path path;
			try {
				path = Path.of(location.toURI());
			} catch (java.net.URISyntaxException | IllegalArgumentException unencoded) {
				// A location spelled with raw spaces, brackets or non-ASCII (mod jars are named like that) is not a
				// valid URI as it stands; quote its path rather than decode it, since '+' is literal in a file name.
				Caught.expected(unencoded);
				path = Path.of(new java.net.URI("file", null, location.getPath(), null));
			}
			String spelling = path.toString().replace('\\', '/');
			String entry = type.getName().replace('.', '/') + ".class";
			// A class defined out of a directory has its own file as its code source (ForbricClassLoader.jarUrlOf).
			if (spelling.endsWith("/" + entry)) return Path.of(spelling.substring(0, spelling.length() - entry.length()));
			return Files.exists(path) ? path : null;
		} catch (Exception unreadable) {
			Caught.warn(unreadable, "[Forbric/CodeSource] could not find the jar or directory %s came from", type.getName());
			return null;
		}
	}
}
