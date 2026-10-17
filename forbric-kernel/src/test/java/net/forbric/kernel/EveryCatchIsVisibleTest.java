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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every {@code catch} in the kernel either rethrows or tells {@link net.forbric.kernel.util.Caught} what it caught.
 *
 * <p>Both have to happen as a statement of the catch body itself, not under an {@code if}: a catch that reports
 * only the first time ({@code if (warned.compareAndSet(false, true)) …}) or only for some exceptions is the silent
 * catch this test exists to rule out, written with one more line. Logging through {@code ForbricLog} alone does not
 * count either — it reaches the log, but nothing counts it, so no test can notice it happened.
 *
 * <p>There is no allowlist. {@code Caught.expected} is the way to say "this exception is the answer", and saying it
 * is the point: a reader, and a debug run, can then see every place that decided an exception was nothing.
 */
class EveryCatchIsVisibleTest {
	private static final Path PROJECT = Path.of(System.getProperty("user.dir"));
	private static final List<String> ROOTS = List.of("src/main/java", "src/runtime/java");
	/** The two classes the rule is built from. Either one calling Caught from its own catch would recurse. */
	private static final Set<String> EXEMPT = Set.of(
			"src/main/java/net/forbric/kernel/util/Caught.java",
			"src/main/java/net/forbric/kernel/util/ForbricLog.java");

	private static final Pattern CATCH = Pattern.compile("\\bcatch\\s*\\(");
	private static final Pattern VISIBLE = Pattern.compile(
			"(?:(?:net\\.forbric\\.kernel\\.util\\.)?Caught\\s*\\.\\s*(?:warn|error|expected)\\s*\\(|throw\\b)");

	@Test
	void everyCatchRethrowsOrReports() throws Exception {
		List<String> silent = new ArrayList<>();
		for (Path source : sources()) {
			String file = PROJECT.relativize(source).toString().replace('\\', '/');
			if (EXEMPT.contains(file)) continue;
			for (int line : silentCatches(Files.readString(source, StandardCharsets.UTF_8))) silent.add(file + ":" + line);
		}
		assertTrue(silent.isEmpty(), silent.size() + " catch block(s) neither rethrow nor report what they caught. "
				+ "Give each one a statement of its own (not under an if) that is a throw, Caught.warn/error(t, what "
				+ "failed and what happens instead) or, where the exception is the answer and nothing went wrong, "
				+ "Caught.expected(t):\n" + String.join("\n", silent));
	}

	@Test
	void theScanSeesTheKernel() throws Exception {
		// A wrong root would find nothing, offend nowhere, and pass the test above.
		int catches = 0;
		for (Path source : sources()) {
			Matcher m = CATCH.matcher(NoRawAssumptionsTest.codeOnly(Files.readString(source, StandardCharsets.UTF_8)));
			while (m.find()) catches++;
		}
		assertTrue(catches > 1000, "only " + catches + " catch blocks found under " + ROOTS);
	}

	@Test
	void whatCountsAsVisible() {
		for (String visible : List.of(
				"try { a(); } catch (Exception e) { Caught.warn(e, \"x\"); }",
				"try { a(); } catch (Exception e) { Caught.expected(e); return null; }",
				"try { a(); } catch (Exception e) { int x = 1; net.forbric.kernel.util.Caught.error(e, \"x %s\", x); }",
				"try { a(); } catch (Exception e) { throw new IllegalStateException(e); }",
				"try { a(); } catch (Exception e) { if (e instanceof Error err) throw err; Caught.warn(e, \"x\"); }",
				"try { a(); } catch (Exception e) { for (int i = 0; i < 2; i++) { b(); } Caught.warn(e, \"x\"); }",
				"try { a(); } catch (Exception e) { Runnable r = () -> { b(); }; Caught.expected(e); }",
				"try { a(); } catch (IOException | RuntimeException e) {\n\tCaught.warn(e, \"x\");\n}")) {
			assertEquals(List.of(), silentCatches(visible), "a false alarm: " + visible);
		}
		for (String silent : List.of(
				"try { a(); } catch (Exception e) { }",
				"try { a(); } catch (Exception e) { return null; }",
				"try { a(); } catch (Exception e) { ForbricLog.warn(\"x\", e); }",
				"try { a(); } catch (Exception e) { ForbricLog.debug(\"Caught.warn(e)\"); }",
				"try { a(); } catch (Exception e) { // Caught.warn(e, \"x\");\n }",
				"try { a(); } catch (Exception e) { if (once.compareAndSet(false, true)) Caught.warn(e, \"x\"); }",
				"try { a(); } catch (Exception e) { if (once) { Caught.warn(e, \"x\"); } }",
				"try { a(); } catch (Exception e) { if (bad) throw e; }",
				"try { a(); } catch (Exception e) { Runnable r = () -> Caught.warn(e, \"x\"); }",
				"try { a(); } catch (Exception e) { x.Caught.warn(e, \"x\"); }")) {
			assertEquals(1, silentCatches(silent).size(), "not caught: " + silent);
		}
		assertEquals(List.of(3), silentCatches("class A {\n\tvoid m() {\n\t\ttry { a(); } catch (Exception e) {}\n\t}\n}"));
	}

	/** Line numbers of the catch blocks in {@code java} that neither rethrow nor report. */
	static List<Integer> silentCatches(String java) {
		String code = NoRawAssumptionsTest.codeOnly(java);
		List<Integer> silent = new ArrayList<>();
		Matcher m = CATCH.matcher(code);
		while (m.find()) {
			int close = matching(code, m.end() - 1, '(', ')');
			int open = close < 0 ? -1 : code.indexOf('{', close);
			int end = open < 0 ? -1 : matching(code, open, '{', '}');
			if (end < 0 || !visible(code.substring(open + 1, end))) silent.add(lineOf(code, m.start()));
		}
		return silent;
	}

	/** Whether one of {@code body}'s own statements (brace and paren depth 0) is a throw or a call to Caught. */
	private static boolean visible(String body) {
		int braces = 0;
		int parens = 0;
		boolean statementStart = true;
		for (int i = 0; i < body.length(); i++) {
			char c = body.charAt(i);
			if (Character.isWhitespace(c)) continue;
			if (statementStart && braces == 0 && parens == 0) {
				Matcher m = VISIBLE.matcher(body).region(i, body.length());
				if (m.lookingAt()) return true;
			}
			statementStart = false;
			switch (c) {
				case '(' -> parens++;
				case ')' -> parens--;
				case '{' -> {
					braces++;
					statementStart = true;
				}
				case '}' -> {
					braces--;
					statementStart = true;
				}
				case ';' -> statementStart = parens == 0;
				default -> { }
			}
		}
		return false;
	}

	/** The index of the bracket closing the one at {@code open}, or -1. */
	private static int matching(String code, int open, char opening, char closing) {
		int depth = 0;
		for (int i = open; i < code.length(); i++) {
			char c = code.charAt(i);
			if (c == opening) depth++;
			else if (c == closing && --depth == 0) return i;
		}
		return -1;
	}

	private static int lineOf(String code, int index) {
		int line = 1;
		for (int i = 0; i < index; i++) if (code.charAt(i) == '\n') line++;
		return line;
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
}
