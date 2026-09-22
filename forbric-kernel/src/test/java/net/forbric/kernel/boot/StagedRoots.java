package net.forbric.kernel.boot;

import java.nio.file.Path;

/**
 * Where the staged game artifacts live, for tests outside {@code net.forbric.kernel}.
 *
 * <p>{@code FORBRIC_OLD} first, because that is what lets a second worktree run against the real tree.
 * Hardcoding the relative path is how a worktree skips every bytecode test and reports green.
 */
final class StagedRoots {
	private StagedRoots() {
	}

	static Path stagedRoot() {
		String override = System.getenv("FORBRIC_OLD");
		if (override != null && !override.isBlank()) return Path.of(override, "run").normalize();
		return Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
	}
}
