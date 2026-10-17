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

package net.forbric.kernel.boot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * {@code <gameDir>/forbric-disabled.txt}: jars in {@code mods/} the player has switched off without moving them.
 *
 * <p>Taking a jar out of the mods folder is the one fix that always works, and the crash analysis and the load
 * report both end in it. It is also the step players get wrong — the jar goes into a folder nobody remembers, or
 * the wrong one of three builds goes. A line in a text file next to {@code mods/} is reversible by deleting it, and
 * {@link CrashSuspectOffer} can write it on the player's say-so.
 *
 * <p>Parsed like {@code forbric-mods.txt}: blank lines and {@code #} comments (whole-line and trailing) are
 * ignored, and a line that is not a jar file name is warned about and skipped. Nothing here throws: a typo must
 * never be able to stop the game, and an unreadable file switches nothing off.
 *
 * <p>Applied by {@link DuplicateModArbiter}: a listed jar never becomes a claim, and lands in
 * {@code Decision.suppressedJars} but not in its {@code rescueJars}, so every discovery skips it and the class
 * loader never serves a class out of it.
 */
public final class DisabledMods {
	/** The file name, next to {@code mods/}. */
	public static final String FILE = "forbric-disabled.txt";

	/** The jars this boot switched off, by file name, in the order the file lists them. */
	private static volatile List<String> switchedOff = List.of();

	private DisabledMods() {
	}

	/** What the last {@link #load} switched off, by file name; empty before it runs. */
	public static List<String> switchedOff() {
		return switchedOff;
	}

	/** Forgets the last load — for tests, and so a re-launch in one process reads the file again. */
	static void reset() {
		switchedOff = List.of();
	}

	/**
	 * The jars in {@code modsDir} that {@code <rundir>/forbric-disabled.txt} lists, as {@code toAbsolutePath()} of
	 * the path the directory listing gives — the same spelling every discovery checks {@code suppressed} with.
	 * Records them for {@link #switchedOff}.
	 *
	 * <p>A listed name with no jar behind it is said once and switches nothing off. Matching is exact first and
	 * case-insensitive only when that finds a single jar: a player typing {@code Sodium.jar} for
	 * {@code sodium.jar} meant that jar, on a file system that would open it under either spelling anyway.
	 */
	static Set<Path> load(Path rundir, Path modsDir) {
		switchedOff = List.of();
		if (rundir == null) return Set.of();
		Path file = rundir.resolve(FILE);
		if (!Files.isRegularFile(file)) return Set.of();
		List<String> names;
		try {
			names = parse(Files.readAllLines(file, StandardCharsets.UTF_8));
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Disabled] could not read %s — nothing is switched off", FILE);
			return Set.of();
		}
		if (names.isEmpty()) return Set.of();

		List<Path> jars = new ArrayList<>();
		if (modsDir != null && Files.isDirectory(modsDir)) {
			try (var entries = Files.list(modsDir)) {
				jars = entries.filter(p -> p.getFileName().toString().endsWith(".jar")).filter(Files::isRegularFile)
						.sorted().toList();
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/Disabled] could not list %s — nothing is switched off", modsDir);
				return Set.of();
			}
		}
		Set<Path> disabled = new LinkedHashSet<>();
		List<String> named = new ArrayList<>();
		List<String> absent = new ArrayList<>();
		for (String name : names) {
			Path jar = find(jars, name);
			if (jar == null) {
				absent.add(name);
				continue;
			}
			if (disabled.add(jar.toAbsolutePath())) named.add(jar.getFileName().toString());
		}
		if (!absent.isEmpty()) {
			ForbricLog.info("[Forbric/Disabled] %s lists %s, which %s not in %s — nothing to switch off there", FILE,
					String.join(", ", absent), absent.size() == 1 ? "is" : "are", modsDir);
		}
		if (!named.isEmpty()) {
			ForbricLog.info("[Forbric/Disabled] %s switches off %d jar(s): %s", FILE, named.size(), String.join(", ", named));
		}
		switchedOff = List.copyOf(named);
		return Set.copyOf(disabled);
	}

	private static Path find(List<Path> jars, String name) {
		for (Path jar : jars) if (jar.getFileName().toString().equals(name)) return jar;
		Path only = null;
		for (Path jar : jars) {
			if (!jar.getFileName().toString().equalsIgnoreCase(name)) continue;
			if (only != null) return null;
			only = jar;
		}
		return only;
	}

	/**
	 * The jar file names {@code lines} lists, in order, duplicates dropped. A line that is not a bare
	 * {@code something.jar} — a path, a mod id, a name without the extension — is warned about and skipped: the
	 * file says which jars, and guessing which jar a mod id meant would switch off the wrong one.
	 */
	static List<String> parse(List<String> lines) {
		Set<String> names = new LinkedHashSet<>();
		int lineNo = 0;
		for (String raw : lines) {
			lineNo++;
			int hash = raw.indexOf('#');
			String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
			if (line.isEmpty()) continue;
			if (line.contains("/") || line.contains("\\") || !line.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
					|| line.length() == ".jar".length()) {
				ForbricLog.warn("[Forbric/Disabled] %s line %d: expected the file name of a jar in the mods folder, "
						+ "e.g. some-mod-1.0.jar, got '%s' — skipped", FILE, lineNo, line);
				continue;
			}
			names.add(line);
		}
		return List.copyOf(names);
	}

	/**
	 * Appends {@code jarNames} to {@code <rundir>/forbric-disabled.txt} under {@code comment}, creating the file
	 * with a short explanation if it does not exist. Never rewrites what is already there — the file is the
	 * player's — and a name already listed is not listed twice.
	 *
	 * @param comment one line, written as a {@code #} comment above the new lines, so the player can tell later
	 *                when and why they appeared
	 */
	static void append(Path rundir, List<String> jarNames, String comment) throws IOException {
		Path file = rundir.resolve(FILE);
		List<String> existing = Files.isRegularFile(file) ? parse(Files.readAllLines(file, StandardCharsets.UTF_8)) : List.of();
		List<String> fresh = new ArrayList<>();
		for (String name : jarNames) if (!existing.contains(name) && !fresh.contains(name)) fresh.add(name);
		if (fresh.isEmpty()) return;
		StringBuilder out = new StringBuilder();
		if (!Files.exists(file)) {
			for (String line : header()) out.append(line).append('\n');
		} else {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			if (!text.isEmpty() && !text.endsWith("\n")) out.append('\n');
			out.append('\n');
		}
		out.append("# ").append(comment.replace('\n', ' ').replace('\r', ' ')).append('\n');
		for (String name : fresh) out.append(name).append('\n');
		Files.writeString(file, out.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/** The comment a new file starts with, in the system language. */
	static List<String> header() {
		if ("zh".equalsIgnoreCase(java.util.Locale.getDefault().getLanguage())) {
			return List.of(
					"# 这里列出的 jar 文件留在 mods 文件夹里，但 Forbric 不会加载它们。",
					"# 一行写一个文件名。想重新启用哪个，就把它那一行删掉。",
					"");
		}
		return List.of(
				"# The jars listed here stay in your mods folder, but Forbric does not load them.",
				"# One file name per line. Delete a line to turn that mod back on.",
				"");
	}
}
