/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No test skips except through {@link TestFixtures}, apart from the files already doing it when this was written.
 *
 * <p>A raw {@code assumeTrue} says neither what is missing nor whether a machine is supposed to have it, so it
 * skips on the one machine that should have failed. {@code @Disabled} and the {@code @EnabledIf…}/{@code
 * @DisabledIf…} conditions are the same thing at class level, and {@code new TestAbortedException} is the same
 * thing spelled by hand. src/test/raw-assumption-allowlist.txt lists the files that still do it, and it only
 * shrinks: a file that stops offending must come off it, so a migrated file cannot quietly start again.
 */
public class NoRawAssumptionsTest {
	private static final Path PROJECT = Path.of(System.getProperty("user.dir"));
	private static final List<String> ROOTS = List.of("src/test/java", "src/transferTest/java");
	private static final String HELPER = "src/test/java/net/forbric/kernel/TestFixtures.java";
	private static final Path ALLOWLIST = PROJECT.resolve("src/test/raw-assumption-allowlist.txt");

	/** Matched against code with comments and literals blanked, so prose and patterns like these never count. */
	private static final Map<String, Pattern> RAW = Map.of(
			"Assumptions.", Pattern.compile("\\bAssumptions\\s*\\."),
			"static-imported assume*", Pattern.compile("(?<![\\w.])(?:assume[A-Z]\\w*|assumingThat)\\s*\\("),
			"@Disabled*/@Enabled*", Pattern.compile("@\\s*(?:[\\w.]+\\.)?(?:Disabled|Enabled)\\w*"),
			"new TestAbortedException", Pattern.compile("\\bnew\\s+(?:[\\w.]+\\.)?TestAbortedException\\b"));

	@Test
	void everyFileThatSkipsByHandIsOnTheAllowlist() throws Exception {
		Map<String, String> offenders = offenders();
		Set<String> allowed = allowlist();
		List<String> unlisted = new ArrayList<>();
		offenders.forEach((file, where) -> {
			if (!allowed.contains(file)) unlisted.add(file + " (" + where + ")");
		});
		assertTrue(unlisted.isEmpty(), "skip through TestFixtures.require(Fixture, present, what), which tags the "
				+ "skip and honours -Dforbric.requireFixtures, instead of a raw assumption, @Disabled/@Enabled* "
				+ "condition or hand-made TestAbortedException in:\n" + String.join("\n", unlisted));
	}

	@Test
	void theAllowlistOnlyShrinks() throws Exception {
		Set<String> offending = offenders().keySet();
		List<String> stale = new ArrayList<>();
		for (String file : allowlist()) {
			if (!offending.contains(file)) stale.add(file);
		}
		assertTrue(stale.isEmpty(), "these files no longer skip by hand (or no longer exist): take them off "
				+ PROJECT.relativize(ALLOWLIST) + " so they cannot start again:\n" + String.join("\n", stale));
	}

	@Test
	void theAllowlistIsAPlainSortedListOfFiles() throws Exception {
		List<String> lines = entries();
		assertEquals(new ArrayList<>(new TreeSet<>(lines)), lines, "sorted, one file per line, no duplicates");
		assertTrue(!lines.contains(HELPER), "TestFixtures is the one place skips are made and is never listed");
	}

	@Test
	void theScanSeesTheSuite() throws Exception {
		// The walk itself: a wrong root would find nothing, offend nowhere, and pass both tests above.
		assertTrue(sources().size() > 300, "only " + sources().size() + " test sources found under " + ROOTS);
	}

	@Test
	void everySpellingOfARawSkipIsCaughtAndNothingElse() {
		for (String raw : List.of(
				"import static org.junit.jupiter.api.Assumptions.assumeTrue;",
				"import static org.junit.jupiter.api.Assumptions.*;",
				"assumeTrue(present, \"x\");",
				"assumeFalse (absent);",
				"assumingThat(x, () -> {});",
				"org.junit.jupiter.api.Assumptions.assumeTrue(present);",
				"Assumptions.abort(\"why\");",
				"@Disabled(\"later\") void t() {}",
				"@org.junit.jupiter.api.Disabled class T {}",
				"@EnabledIfSystemProperty(named = \"x\", matches = \"y\")",
				"@DisabledIfEnvironmentVariable(named = \"x\", matches = \"y\")",
				"@EnabledOnOs(OS.MAC)",
				"@EnabledForJreRange(min = JRE.JAVA_25)",
				"throw new TestAbortedException(\"why\");",
				"throw new org.opentest4j.TestAbortedException();")) {
			assertTrue(!firstOffence(raw).isEmpty(), "not caught: " + raw);
		}
		for (String clean : List.of(
				"// assumeTrue(present) used to be here, and @Disabled before it",
				"/* Assumptions.assumeTrue(x); new TestAbortedException() */",
				"/** {@code @EnabledIf} would hide this */",
				"String s = \"Assumptions.assumeTrue(x) @Disabled new TestAbortedException\";",
				// One unpaired quote inside: read as plain strings, everything after it would flip to code.
				"String block = \"\"\"\n\t\"he said\n\tassumeTrue(x); @Disabled\n\t\"\"\";",
				"char c = '\"'; String after = \"assumeTrue(\";",
				"catch (org.opentest4j.TestAbortedException e) { throw e; }",
				"assertThrows(TestAbortedException.class, skip);",
				"TestFixtures.require(Fixture.STAGED, present, \"x\");",
				"void disabledWhenAbsent() {}",
				"x.assumeTrue(y);")) {
			assertEquals("", firstOffence(clean), "a false alarm: " + clean);
		}
		// Blanking keeps line breaks, so a reported line number is the real one even after a text block.
		assertTrue(firstOffence("String b = \"\"\"\n\tone\n\ttwo\\\n\t\"\"\";\n@Disabled").startsWith("line 5:"),
				firstOffence("String b = \"\"\"\n\tone\n\ttwo\\\n\t\"\"\";\n@Disabled"));
	}

	/** Repo-relative path of every offending file, to where its first offence is. */
	private static Map<String, String> offenders() throws IOException {
		Map<String, String> found = new TreeMap<>();
		for (Path source : sources()) {
			String file = PROJECT.relativize(source).toString().replace('\\', '/');
			if (file.equals(HELPER)) continue;
			String where = firstOffence(Files.readString(source, StandardCharsets.UTF_8));
			if (!where.isEmpty()) found.put(file, where);
		}
		return found;
	}

	private static List<Path> sources() throws IOException {
		List<Path> sources = new ArrayList<>();
		for (String root : ROOTS) {
			Path dir = PROJECT.resolve(root);
			if (!Files.isDirectory(dir)) continue;
			try (Stream<Path> walk = Files.walk(dir)) {
				walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(sources::add);
			}
		}
		return sources;
	}

	/** "line N: what" for the first raw skip in {@code java}, or "" for none. */
	static String firstOffence(String java) {
		String code = codeOnly(java);
		int first = Integer.MAX_VALUE;
		String what = "";
		for (Map.Entry<String, Pattern> rule : RAW.entrySet()) {
			Matcher m = rule.getValue().matcher(code);
			if (m.find() && m.start() < first) {
				first = m.start();
				what = rule.getKey();
			}
		}
		if (what.isEmpty()) return "";
		int line = 1;
		for (int i = 0; i < first; i++) if (code.charAt(i) == '\n') line++;
		return "line " + line + ": " + what;
	}

	/** {@code java} with comments, strings, text blocks and chars blanked; line breaks are kept. */
	public static String codeOnly(String java) {
		StringBuilder out = new StringBuilder(java.length());
		int n = java.length();
		int i = 0;
		while (i < n) {
			char c = java.charAt(i);
			if (java.startsWith("//", i)) {
				while (i < n && java.charAt(i) != '\n') i++;
			} else if (java.startsWith("/*", i)) {
				i = skip(java, i + 2, "*/", out);
			} else if (java.startsWith("\"\"\"", i)) {
				i = skip(java, i + 3, "\"\"\"", out);
				out.append("\"\"");
			} else if (c == '"' || c == '\'') {
				i = skip(java, i + 1, String.valueOf(c), out);
				out.append(c).append(c);
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString();
	}

	/** The index after {@code end}, keeping only the line breaks of what it skipped over. */
	private static int skip(String java, int from, String end, StringBuilder out) {
		boolean comment = end.equals("*/");
		int i = from;
		while (i < java.length() && !java.startsWith(end, i)) {
			int width = !comment && java.charAt(i) == '\\' ? 2 : 1;
			for (int k = i; k < Math.min(i + width, java.length()); k++) if (java.charAt(k) == '\n') out.append('\n');
			i += width;
		}
		return Math.min(i + end.length(), java.length());
	}

	private static Set<String> allowlist() throws IOException {
		return new TreeSet<>(entries());
	}

	private static List<String> entries() throws IOException {
		List<String> entries = new ArrayList<>();
		for (String line : Files.readAllLines(ALLOWLIST, StandardCharsets.UTF_8)) {
			String entry = line.strip();
			if (!entry.isEmpty() && !entry.startsWith("#")) entries.add(entry);
		}
		return entries;
	}
}
