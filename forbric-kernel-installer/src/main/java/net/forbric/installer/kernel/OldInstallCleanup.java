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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes what an older Forbric install left behind and this one never reads again, after the new profile is written.
 *
 * <p>Before Stage 4 of dropping MinecraftForge an install built MinecraftForge's patched game and runtime and merged
 * them with NeoForge's into one base; since then it builds NeoForge's patched game and runtime only. Nothing removed
 * the old products, so an upgraded install kept several hundred megabytes nobody reads, and the README told players to
 * delete {@code .forbric-build} and {@code libraries/net/forbric} by hand. Two places, two rules:
 *
 * <ul>
 *   <li><strong>{@code .forbric-build}</strong> is the installer's own work directory. What goes there is a closed list,
 *       {@link #RETIRED_BUILD_FILES}: the names an older installer wrote and this one never writes or reads (the merged
 *       base and its conflict list, MinecraftForge's runtime, its interop runtime and its patched game, and the
 *       MinecraftForge build's work trees). Nothing else is a candidate: not {@code out/}'s current outputs or their
 *       stamps, not {@code dl/} (the download cache), not NFRT's caches. The merge-tools jar in {@code tools/} goes
 *       only once this installer carries none, since until then it is the link checker this very install just ran.</li>
 *   <li><strong>{@code libraries/net/forbric}</strong> is shared with the launcher. A Forbric library directory there
 *       (one {@code <artifact>/<version>/}) is removed only when no version profile names anything inside it: every
 *       {@code versions/<id>/*.json} is read, with the profile each one {@code inheritsFrom}, and every string in it
 *       that names a {@code net.forbric} coordinate or a {@code net/forbric/...} path counts, wherever it sits
 *       (libraries, arguments). So a second profile that still uses an old base keeps it, and an older kernel version
 *       another profile names survives. Nothing outside {@code net/forbric} is ever touched: the kernel's third-party
 *       libraries sit beside Mojang's under their own groups, and other launchers' profiles may use them.</li>
 * </ul>
 *
 * <p>What this install itself staged or built is never removed, whatever a rule says (the guard is the last line, not
 * the first: the rules already exclude it). A profile that cannot be read stops the library half — what it uses is
 * unknown. Symbolic links are never followed and never removed: a {@code libraries} directory reached through one is
 * shared with something else, and a link inside is someone else's file. A file that cannot be deleted (on Windows, one
 * a running game still holds open) is named and left, and the install carries on: an old file left behind costs disk
 * space, a failed install costs the game. Every removal is logged.
 *
 * <p>{@code -Dforbric.installerCleanup=off} leaves everything in place (gate-m17 phase U's reverse control).
 */
final class OldInstallCleanup {

	/** The switch: {@code off} leaves every old file where it is. */
	static final String SWITCH = "forbric.installerCleanup";

	/**
	 * What an older installer wrote under {@code .forbric-build} and this one never reads: names relative to it, a
	 * {@code *} matching within one name. Taken from the installers of v0.3.0 through the last merged-base build
	 * (tri-eco-26.2-final): ArtifactBuilder's outputs, MergedBaseTool's conflict list, PatchedMcBuilder's work files and
	 * the binary patches and access transformers it unpacked from MinecraftForge's userdev jar.
	 */
	static final List<String> RETIRED_BUILD_FILES = List.of(
			// The merged base, its build stamp, and the merge's conflict list.
			"out/patched-mc-merged-*.jar", "out/patched-mc-merged-*.jar.pins", "out/merge-conflicts.txt",
			// MinecraftForge's runtime and the interop runtime built from it.
			"out/forge-runtime.jar", "out/forge-runtime.jar.pins",
			"out/forge-runtime-interop.jar", "out/forge-runtime-interop.jar.pins",
			// MinecraftForge's patched game and its build's work files.
			"out/patched-mc-forge-*.jar", "out/patched-mc-forge-*.jar.pins",
			"server-main.jar", "clean.jar", "patched-subset.jar", "patched-full.jar", "patched-at.jar", "joined.lzma",
			"ats");

	/** The merge tools an older installer unpacked for its link checker (and, before C1, for the merge). */
	static final String MERGE_TOOLS = "tools/forbric-merge-tools.jar";
	private static final String MERGE_TOOLS_RESOURCE = "/forbric/tools/forbric-merge-tools.jar";

	/** A {@code net.forbric} coordinate (group, artifact, version, optional classifier), the whole string. */
	private static final Pattern COORDINATE =
			Pattern.compile("net\\.forbric(\\.[A-Za-z0-9_.-]+)?:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+-]+(:[A-Za-z0-9_.-]+)?");
	/** A path into {@code net/forbric}, up to the next separator a launcher or a classpath would put after it. */
	private static final Pattern FORBRIC_PATH = Pattern.compile("(?:^|[/\\\\])(net[/\\\\]forbric[/\\\\][^:;\"'\\s]+)");

	private final Consumer<String> log;
	private final List<String> retiredBuildFiles;
	private final boolean carriesMergeTools;
	private final boolean honourProfiles;
	private final boolean guardCurrent;

	OldInstallCleanup(Consumer<String> log) {
		this(log, RETIRED_BUILD_FILES, OldInstallCleanup.class.getResource(MERGE_TOOLS_RESOURCE) != null, true, true);
	}

	/**
	 * The rules spelled out, for the test's controls: a clean-up that ignores the profiles, or one with its guard off
	 * and a rule that reaches a current output, must turn the test red.
	 */
	OldInstallCleanup(Consumer<String> log, List<String> retiredBuildFiles, boolean carriesMergeTools,
			boolean honourProfiles, boolean guardCurrent) {
		this.log = log;
		this.retiredBuildFiles = retiredBuildFiles;
		this.carriesMergeTools = carriesMergeTools;
		this.honourProfiles = honourProfiles;
		this.guardCurrent = guardCurrent;
	}

	/** What one run removed, and what it could not. */
	record Result(int removed, long bytes, int failed) {
	}

	/**
	 * @param mcDir     the Minecraft directory just installed into
	 * @param built     the game artifacts this install built or was given (their stamps are kept with them)
	 * @param staged    the library entries this install wrote into the profile ({@code downloads.artifact.path})
	 */
	Result run(Path mcDir, Collection<Path> built, List<Map<String, Object>> staged) {
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "").trim())) {
			log.accept(InstallerText.cleanupOff());
			return new Result(0, 0, 0);
		}
		Counter count = new Counter();
		try {
			Set<Path> current = currentFiles(mcDir, built, staged);
			cleanBuildDirectory(mcDir.resolve(".forbric-build"), current, count);
			cleanLibraries(mcDir, current, count);
		} catch (IOException | RuntimeException unexpected) {
			// The clean-up is a courtesy; the install it follows has already succeeded.
			count.failed++;
			log.accept(InstallerText.cleanupFailed(mcDir, String.valueOf(unexpected)));
		}
		log.accept(InstallerText.cleanupSummary(count.removed, count.bytes, count.failed));
		return new Result(count.removed, count.bytes, count.failed);
	}

	private static final class Counter {
		int removed;
		long bytes;
		int failed;
	}

	// --- what this install owns ------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static Set<Path> currentFiles(Path mcDir, Collection<Path> built, List<Map<String, Object>> staged) {
		Set<Path> current = new HashSet<>();
		for (Path file : built) {
			Path normal = file.toAbsolutePath().normalize();
			current.add(normal);
			current.add(normal.resolveSibling(normal.getFileName() + ".pins"));
		}
		Path libraries = mcDir.resolve("libraries");
		for (Map<String, Object> entry : staged) {
			Object downloads = entry.get("downloads");
			Object artifact = downloads instanceof Map<?, ?> d ? ((Map<String, Object>) d).get("artifact") : null;
			Object path = artifact instanceof Map<?, ?> a ? ((Map<String, Object>) a).get("path") : null;
			if (path instanceof String p) current.add(libraries.resolve(p).toAbsolutePath().normalize());
		}
		return current;
	}

	private boolean isCurrent(Set<Path> current, Path file) {
		return guardCurrent && current.contains(file.toAbsolutePath().normalize());
	}

	// --- .forbric-build -----------------------------------------------------------------------------------------

	private void cleanBuildDirectory(Path build, Set<Path> current, Counter count) throws IOException {
		if (!Files.isDirectory(build, LinkOption.NOFOLLOW_LINKS)) {
			if (Files.isSymbolicLink(build)) log.accept(InstallerText.cleanupLeftLink(build));
			return;
		}
		List<String> rules = new ArrayList<>(retiredBuildFiles);
		if (!carriesMergeTools) {
			rules.add(MERGE_TOOLS);
			rules.add(MERGE_TOOLS + ".part");
		}
		Set<Path> matched = new TreeSet<>();
		for (String rule : rules) {
			int slash = rule.lastIndexOf('/');
			Path dir = slash < 0 ? build : build.resolve(rule.substring(0, slash));
			String name = rule.substring(slash + 1);
			if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
			if (name.indexOf('*') < 0) {
				Path one = dir.resolve(name);
				if (Files.exists(one, LinkOption.NOFOLLOW_LINKS)) matched.add(one);
				continue;
			}
			PathMatcher glob = FileSystems.getDefault().getPathMatcher("glob:" + name);
			try (DirectoryStream<Path> listing = Files.newDirectoryStream(dir)) {
				for (Path entry : listing) {
					if (glob.matches(entry.getFileName())) matched.add(entry);
				}
			}
		}
		for (Path old : matched) {
			if (Files.isSymbolicLink(old)) {
				log.accept(InstallerText.cleanupLeftLink(old));
			} else if (Files.isDirectory(old, LinkOption.NOFOLLOW_LINKS)) {
				removeTree(old, current, count);
			} else {
				removeFile(old, current, count);
			}
		}
	}

	/** A whole retired directory, unless something inside is a link or this install's own. */
	private void removeTree(Path dir, Set<Path> current, Counter count) throws IOException {
		try (var walk = Files.walk(dir)) {
			for (Path inside : (Iterable<Path>) walk::iterator) {
				if (Files.isSymbolicLink(inside)) {
					log.accept(InstallerText.cleanupLeftLink(inside));
					return;
				}
				if (isCurrent(current, inside)) return;
			}
		}
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
				removeFile(file, current, count);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path visited, IOException failed) {
				removeEmptyDirectory(visited, count);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	// --- libraries/net/forbric ----------------------------------------------------------------------------------

	private void cleanLibraries(Path mcDir, Set<Path> current, Counter count) throws IOException {
		Path libraries = mcDir.resolve("libraries");
		Path root = libraries.resolve("net").resolve("forbric");
		for (Path step : List.of(libraries, libraries.resolve("net"), root)) {
			if (Files.isSymbolicLink(step)) {
				// A libraries tree reached through a link is shared with another directory whose profiles are not read.
				log.accept(InstallerText.cleanupLeftLink(step));
				return;
			}
		}
		if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return;

		Set<String> referenced = new HashSet<>();
		if (honourProfiles) {
			Path unreadable = readReferences(mcDir.resolve("versions"), referenced);
			if (unreadable != null) return;
		}

		// Every directory under net/forbric that holds a file of its own is one unit: a library's version directory.
		List<Path> units = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
				try (DirectoryStream<Path> listing = Files.newDirectoryStream(dir)) {
					for (Path entry : listing) {
						if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
							units.add(dir);
							break;
						}
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});
		for (Path unit : units) {
			String relative = slashes(libraries.relativize(unit).toString()) + "/";
			boolean named = false;
			for (String path : referenced) {
				if (path.startsWith(relative)) {
					named = true;
					break;
				}
			}
			if (named) continue;
			try (DirectoryStream<Path> listing = Files.newDirectoryStream(unit)) {
				for (Path file : listing) {
					if (Files.isSymbolicLink(file)) {
						log.accept(InstallerText.cleanupLeftLink(file));
					} else if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
						removeFile(file, current, count);
					}
				}
			}
			// The version directory, then its artifact (and group) directories once nothing is left in them.
			for (Path dir = unit; dir.startsWith(root) && !dir.equals(root); dir = dir.getParent()) {
				if (!isEmptyDirectory(dir)) break;
				removeEmptyDirectory(dir, count);
			}
		}
	}

	/**
	 * Every {@code net/forbric} path that any profile under {@code versions} names, relative to {@code libraries}.
	 *
	 * @return the profile that could not be read (and the libraries are then left alone), or null
	 */
	private Path readReferences(Path versions, Set<String> referenced) throws IOException {
		if (!Files.isDirectory(versions)) return null;
		Deque<Path> pending = new ArrayDeque<>();
		try (DirectoryStream<Path> ids = Files.newDirectoryStream(versions)) {
			for (Path id : ids) {
				if (!Files.isDirectory(id)) continue;
				try (DirectoryStream<Path> files = Files.newDirectoryStream(id, "*.json")) {
					for (Path json : files) pending.add(json);
				}
			}
		}
		Set<Path> seen = new LinkedHashSet<>();
		while (!pending.isEmpty()) {
			Path json = pending.removeFirst();
			if (!seen.add(json.toAbsolutePath().normalize()) || !Files.isRegularFile(json)) continue;
			Object profile;
			try {
				profile = Json.parse(Files.readString(json, StandardCharsets.UTF_8));
			} catch (IOException | RuntimeException unreadable) {
				log.accept(InstallerText.cleanupProfileUnreadable(json, String.valueOf(unreadable)));
				return json;
			}
			collect(profile, referenced);
			// The profile it inherits from is read too, wherever its file is: its libraries are this profile's.
			if (profile instanceof Map<?, ?> map && map.get("inheritsFrom") instanceof String parent && !parent.isBlank()) {
				pending.add(versions.resolve(parent).resolve(parent + ".json"));
			}
		}
		return null;
	}

	/** Every string anywhere in a parsed profile that names a net.forbric coordinate or path. */
	static void collect(Object node, Set<String> referenced) {
		if (node instanceof Map<?, ?> map) {
			for (Object value : map.values()) collect(value, referenced);
		} else if (node instanceof List<?> list) {
			for (Object value : list) collect(value, referenced);
		} else if (node instanceof String text) {
			if (COORDINATE.matcher(text.trim()).matches()) {
				referenced.add(Util.coordinateToPath(text.trim()));
			}
			Matcher path = FORBRIC_PATH.matcher(text);
			while (path.find()) referenced.add(slashes(path.group(1)));
		}
	}

	// --- removing ----------------------------------------------------------------------------------------------

	private void removeFile(Path file, Set<Path> current, Counter count) {
		if (isCurrent(current, file)) return;
		long size = 0;
		try {
			size = Files.size(file);
			Files.delete(file);
			count.removed++;
			count.bytes += size;
			log.accept(InstallerText.cleanupRemoved(file));
		} catch (IOException couldNot) {
			count.failed++;
			log.accept(InstallerText.cleanupFailed(file, String.valueOf(couldNot)));
		}
	}

	private void removeEmptyDirectory(Path dir, Counter count) {
		try {
			if (isEmptyDirectory(dir)) Files.delete(dir);
		} catch (IOException couldNot) {
			count.failed++;
			log.accept(InstallerText.cleanupFailed(dir, String.valueOf(couldNot)));
		}
	}

	private static boolean isEmptyDirectory(Path dir) {
		if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false;
		try (DirectoryStream<Path> listing = Files.newDirectoryStream(dir)) {
			return !listing.iterator().hasNext();
		} catch (IOException unreadable) {
			return false;
		}
	}

	private static String slashes(String path) {
		return path.replace('\\', '/');
	}
}
