package net.forbric.installer;

import java.nio.file.Path;

/**
 * The result of an install-time build: a jar on disk plus the metadata the version-profile writer needs. The two
 * built categories ({@code patched-mc}, {@code forge-runtime}) arrive in the manifest as placeholders with null
 * {@code file}/{@code sha1}; {@link Installer} fills them in from these results, keyed by coordinate.
 */
final class ArtifactResult {
	final String coordinate;
	final Path file;
	final String sha1;
	final long size;

	ArtifactResult(String coordinate, Path file, String sha1, long size) {
		this.coordinate = coordinate;
		this.file = file;
		this.sha1 = sha1;
		this.size = size;
	}
}
