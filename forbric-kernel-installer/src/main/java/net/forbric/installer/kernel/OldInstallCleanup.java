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
 *       only once this installer carries none, since until then it is the link checker this very install just ran.
 *       v0.1.0's MinecraftForge work tree, {@code .forbric-build/<mc>-<forge>/}, goes whole: no profile ever read it
 *       (its outputs were copied into {@code libraries} and the profile's {@code mods}).</li>
 *   <li><strong>{@code libraries/net/forbric}</strong> is shared with the launcher. A Forbric library directory there
 *       (one {@code <artifact>/<version>/}) is removed only when no version profile names anything inside it: every
 *       {@code versions/<id>/*.json} is read, with the profile each one {@code inheritsFrom}, and every string in it
 *       that names a {@code net.forbric} coordinate or a {@code net/forbric/...} path counts, wherever it sits
 *       (libraries, arguments) and however many share one string (a classpath joined by a launcher's separator, a
 *       coordinate with a classifier or an {@code @ext}). So a second profile that still uses an old base keeps it,
 *       and an older kernel version another profile names survives. Nothing outside {@code net/forbric} is ever
 *       touched: the kernel's third-party libraries sit beside Mojang's under their own groups, and other launchers'
 *       profiles may use them.</li>
 * </ul>
 *
 * <p>What this install itself staged or built is never removed, whatever a rule says (the guard is the last line, not
 * the first: the rules already exclude it). A profile ({@code versions/<id>/<id>.json}, or a parent it names) that
 * cannot be read stops the library half — what it uses is unknown; any other JSON beside it that does not parse is the
 * game's or a mod's (a launcher with version isolation makes {@code versions/<id>} the game directory) and is skipped,
 * named in the log. Links are never followed and never removed — symbolic links, and what the file system reports as
 * "other" (a Windows junction is a directory that is also a reparse point): a {@code libraries} or
 * {@code .forbric-build} reached through one, at any step below the Minecraft directory, is shared with something else
 * whose profiles are not read here, and a link inside is someone else's file. A file that cannot be deleted (on Windows,
 * one a running game still holds open) is named and left, and the install carries on: an old file left behind costs
 * disk space, a failed install costs the game. Anything unexpected stops the clean-up, says so, and leaves the rest in
 * place. Every removal is logged.
 *
 * <p>{@code -Dforbric.installerCleanup=off} leaves everything in place (gate-m17 phase U's reverse control).
 */
final class OldInstallCleanup {

	/** The switch: {@code off} leaves every old file where it is. */
	static final String SWITCH = "forbric.installerCleanup";

	/**
	 * What an older installer wrote under {@code .forbric-build} and this one never reads: names relative to it, a
	 * {@code *} matching within one name. Taken from the public installers v0.1.0 through v0.3.1-beta2 and the last
	 * merged-base build (tri-eco-26.2-final): ArtifactBuilder's outputs, MergedBaseTool's conflict list, PatchedMcBuilder's
	 * work files and the binary patches and access transformers it unpacked from MinecraftForge's userdev jar (v0.2.0 on
	 * wrote them at the top of {@code .forbric-build}, v0.1.0 in a work tree of its own).
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
			"ats",
			// v0.1.0's MinecraftForge work tree, .forbric-build/<mc>-<forge>/ (its dl/, the same work files as above,
			// and the unpacked userdev): a name only that installer wrote -- this one's are dl, nfrt, nfrt-work, out, tools.
			"[0-9]*.[0-9]*-[0-9]*.[0-9]*");

	/** The merge tools an older installer unpacked for its link checker (and, before C1, for the merge). */
	static final String MERGE_TOOLS = "tools/forbric-merge-tools.jar";
	private static final String MERGE_TOOLS_RESOURCE = "/forbric/tools/forbric-merge-tools.jar";

	/**
	 * A {@code net.forbric} coordinate anywhere in a string: group (with any sub-group), artifact, version, then an
	 * optional classifier and {@code @ext}, which do not change the library's directory.
	 */
	private static final Pattern COORDINATE = Pattern.compile(
			"(?<![A-Za-z0-9_.-])net\\.forbric((?:\\.[A-Za-z0-9_-]+)*):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+-]+)"
					+ "(?::[A-Za-z0-9_.-]+)?(?:@[A-Za-z0-9]+)?");
	/**
	 * A path into {@code net/forbric} anywhere in a string, up to the next separator a launcher or a classpath would put
	 * after it -- {@code :} or {@code ;}, a quote, white space, or the {@code $} of a launcher's placeholder such as
	 * {@code ${classpath_separator}}.
	 */
	private static final Pattern FORBRIC_PATH =
			Pattern.compile("(?<![A-Za-z0-9_.-])(net[/\\\\]forbric[/\\\\][^:;\"'\\s$]+)");

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
			// The clean-up is a courtesy; the install it follows has already succeeded. Not a file that could not be
			// removed: the clean-up itself stopped, and what it had not reached yet stays.
			log.accept(InstallerText.cleanupStopped(String.valueOf(unexpected)));
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
		if (Files.exists(build, LinkOption.NOFOLLOW_LINKS) && (isLink(build) || redirected(build))) {
			log.accept(InstallerText.cleanupLeftLink(build));
			return;
		}
		if (!Files.isDirectory(build, LinkOption.NOFOLLOW_LINKS)) return;
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
			// A directory reached through a link of any kind is someone else's (the link itself is logged where met).
			if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || isLink(dir)) continue;
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
			if (isLink(old)) {
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
				if (isLink(inside)) {
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
			if (Files.exists(step, LinkOption.NOFOLLOW_LINKS) && (isLink(step) || redirected(step))) {
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
				// A junction is a directory to the walk: it never follows symbolic links, but it descends into these.
				if (attributes.isOther() || attributes.isSymbolicLink()) {
					log.accept(InstallerText.cleanupLeftLink(dir));
					return FileVisitResult.SKIP_SUBTREE;
				}
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
					if (isLink(file)) {
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
	 * <p>A profile is {@code versions/<id>/<id>.json}, the name every launcher reads, and the parent each one
	 * {@code inheritsFrom}: one of these that cannot be read stops the library half. Any other {@code .json} directly in
	 * {@code versions/<id>} is read too when it parses (it can only keep more), and skipped, named in the log, when it
	 * does not: under a launcher's version isolation {@code versions/<id>} is the game directory, and the game and its
	 * mods write JSON of their own there.
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
				profile = Json.parse(withoutByteOrderMark(Files.readString(json, StandardCharsets.UTF_8)));
			} catch (IOException | RuntimeException unreadable) {
				if (!isProfile(json)) {
					log.accept(InstallerText.cleanupSkippedJson(json, String.valueOf(unreadable)));
					continue;
				}
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

	/** {@code versions/<id>/<id>.json}: the file a launcher reads as version {@code <id>}. */
	static boolean isProfile(Path json) {
		Path folder = json.getParent();
		return folder != null && folder.getFileName() != null
				&& json.getFileName().toString().equals(folder.getFileName() + ".json");
	}

	private static String withoutByteOrderMark(String text) {
		return text.startsWith("\uFEFF") ? text.substring(1) : text;
	}

	/** Every string anywhere in a parsed profile that names a net.forbric coordinate or path: the library directories. */
	static void collect(Object node, Set<String> referenced) {
		if (node instanceof Map<?, ?> map) {
			for (Object value : map.values()) collect(value, referenced);
		} else if (node instanceof List<?> list) {
			for (Object value : list) collect(value, referenced);
		} else if (node instanceof String text) {
			Matcher coordinate = COORDINATE.matcher(text);
			while (coordinate.find()) {
				// group/artifact/version/ -- the library's directory, whatever classifier or extension follows.
				referenced.add("net/forbric" + coordinate.group(1).replace('.', '/') + "/" + coordinate.group(2) + "/"
						+ coordinate.group(3) + "/");
			}
			Matcher path = FORBRIC_PATH.matcher(text);
			while (path.find()) referenced.add(slashes(path.group(1)));
		}
	}

	// --- links --------------------------------------------------------------------------------------------------

	/**
	 * A link of any kind, not followed: a symbolic link, or what the file system calls "other" -- a device or a FIFO,
	 * and on Windows a junction or any other reparse point that is not a symbolic link (the JDK reports a junction as
	 * a directory that is also "other", never as a symbolic link).
	 */
	static boolean isLink(Path path) {
		try {
			BasicFileAttributes attributes =
					Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			return attributes.isSymbolicLink() || attributes.isOther();
		} catch (IOException absent) {
			return false;
		}
	}

	/**
	 * Whether {@code step}, resolved, is somewhere other than its own parent, resolved, plus its name: then something
	 * at that step (a link, a junction, a mount the JDK does not call either) leads elsewhere. The parent's own links are
	 * not this step's business (a Minecraft directory may well sit under a linked folder); the name is compared without
	 * case, since a case-insensitive file system returns the name as it is on disk.
	 */
	static boolean redirected(Path step) {
		try {
			Path absolute = step.toAbsolutePath().normalize();
			Path parent = absolute.getParent();
			if (parent == null) return false;
			Path real = absolute.toRealPath();
			return !(parent.toRealPath().equals(real.getParent())
					&& real.getFileName() != null
					&& real.getFileName().toString().equalsIgnoreCase(absolute.getFileName().toString()));
		} catch (IOException unresolved) {
			return true;
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
