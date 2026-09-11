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

package net.forbric.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Holds this package to the two claims its {@code package-info} makes about where it sits.
 *
 * <p>Both were prose, and prose does not fail a build. The package is parent-pinned so exactly one copy exists
 * per JVM, which is what lets an {@link Ecosystem} constant handed across the boot/game boundary still equal
 * itself — and that only holds while nothing here drags a game type or a kernel package along with it. A single
 * import added in passing would break it, and the symptom would not be a compile error: it would be a branch
 * that is "just wrong" for a day.
 */
class ApiLayeringTest {
	private static final Path API =
			Path.of(System.getProperty("user.dir"), "src", "main", "java", "net", "forbric", "api");

	/**
	 * The ONE dependency back into the kernel that the package-info admits to, and the terms it admits it on.
	 *
	 * <p>{@code ForbricLog} is allowed because it appears only inside method bodies and in no public signature, so
	 * nothing compiling against this package's surface needs it, and because it is self-contained — JDK types and
	 * a reflective log4j lookup. This list existing is the point: a second entry has to be argued for in a diff
	 * rather than appearing in an import block nobody reads.
	 */
	private static final Set<String> ALLOWED_KERNEL_IMPORTS = Set.of("net.forbric.kernel.util.ForbricLog");

	/** Prefixes that would make this package name a game type. */
	private static final List<String> GAME_PACKAGES =
			List.of("net.minecraft.", "net.minecraftforge.", "net.neoforged.", "net.fabricmc.", "com.mojang.");

	@Test
	void theOnlyWayBackIntoTheKernelIsTheOneTheDocumentationAdmitsTo() throws Exception {
		List<String> unexpected = new ArrayList<>();
		for (Path file : sources()) {
			for (String imported : importsOf(file)) {
				if (!imported.startsWith("net.forbric.kernel.")) continue;
				if (ALLOWED_KERNEL_IMPORTS.contains(imported)) continue;
				unexpected.add(file.getFileName() + " -> " + imported);
			}
		}

		assertEquals(List.of(), unexpected,
				"the unified API may not depend on kernel internals. If one of these is genuinely needed, move the "
						+ "type into this package or add it to ALLOWED_KERNEL_IMPORTS with the reason");
	}

	/**
	 * Nothing here may NAME a game type — not as an import, and not fully qualified either.
	 *
	 * <p>String literals are exempt and that is the whole design: {@link ForeignType} exists precisely to hold
	 * those names as data, so the check has to see the difference between a name the code links against and a name
	 * it merely stores. Comments are exempt for the same reason the foreign-type scan skips them — this package's
	 * javadoc explains the split by quoting the very packages it is forbidden to use.
	 */
	@Test
	void noGameTypeIsNamedInCode() throws Exception {
		List<String> named = new ArrayList<>();
		for (Path file : sources()) {
			String code = codeOutsideStringsAndComments(Files.readString(file));
			for (String prefix : GAME_PACKAGES) {
				if (code.contains(prefix)) named.add(file.getFileName() + " -> " + prefix);
			}
		}

		assertEquals(List.of(), named,
				"this package is parent-pinned and must be loadable with no game classes present. A game object "
						+ "crosses as java.lang.Object; its NAME belongs in ForeignType");
	}

	/**
	 * The allowance is "in method bodies, never in a signature". A public method returning or taking a kernel type
	 * would put it on this package's surface, and then the leak stops being cosmetic.
	 */
	@Test
	void theLoggerNeverReachesThePublicSurface() throws Exception {
		List<String> leaks = new ArrayList<>();
		for (Path file : sources()) {
			for (String line : codeOutsideStringsAndComments(Files.readString(file)).split("\n")) {
				String trimmed = line.trim();
				if (!trimmed.contains("ForbricLog")) continue;
				if (trimmed.startsWith("import ")) continue; // the import is what the allowance IS
				// A use is always `ForbricLog.something(...)`. Anything else is a declaration mentioning the type.
				if (trimmed.contains("ForbricLog.")) continue;
				leaks.add(file.getFileName() + ": " + trimmed);
			}
		}

		assertEquals(List.of(), leaks, "ForbricLog may be called here, not declared");
	}

	private static List<Path> sources() throws Exception {
		assumeTrue(Files.isDirectory(API), "API sources not present");
		try (Stream<Path> files = Files.list(API)) {
			List<Path> java = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
			assertTrue(java.size() > 1, "the scan found nothing to check, which would make every assertion vacuous");
			return java;
		}
	}

	private static List<String> importsOf(Path file) throws Exception {
		List<String> imports = new ArrayList<>();
		for (String line : Files.readString(file).split("\n")) {
			String trimmed = line.trim();
			if (!trimmed.startsWith("import ") || !trimmed.endsWith(";")) continue;
			String name = trimmed.substring("import ".length(), trimmed.length() - 1).trim();
			if (name.startsWith("static ")) name = name.substring("static ".length()).trim();
			imports.add(name);
		}
		return imports;
	}

	/** The file with comments AND string contents blanked out, so only what the code links against is left. */
	private static String codeOutsideStringsAndComments(String java) {
		StringBuilder out = new StringBuilder(java.length());
		boolean inString = false, inChar = false, inLine = false, inBlock = false, escaped = false;
		for (int i = 0; i < java.length(); i++) {
			char c = java.charAt(i);
			char next = i + 1 < java.length() ? java.charAt(i + 1) : '\0';
			if (inLine) {
				if (c == '\n') { inLine = false; out.append(c); }
				continue;
			}
			if (inBlock) {
				if (c == '*' && next == '/') { inBlock = false; i++; }
				else if (c == '\n') out.append(c);
				continue;
			}
			if (inString || inChar) {
				if (escaped) escaped = false;
				else if (c == '\\') escaped = true;
				else if (inString && c == '"') { inString = false; out.append(c); }
				else if (inChar && c == '\'') { inChar = false; out.append(c); }
				continue; // the contents are data, not a reference
			}
			if (c == '/' && next == '/') { inLine = true; i++; continue; }
			if (c == '/' && next == '*') { inBlock = true; i++; continue; }
			if (c == '"') inString = true;
			else if (c == '\'') inChar = true;
			out.append(c);
		}
		return out.toString();
	}
}
