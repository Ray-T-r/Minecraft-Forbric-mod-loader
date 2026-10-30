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
 * No test spells a staged jar's path itself. {@link TestFixtures} names the merged base ({@link TestFixtures#BASE_JAR},
 * {@link TestFixtures#MERGED_BASE_JAR}), the genuine NeoForge base ({@link TestFixtures#GENUINE_BASE_JAR}), the
 * NeoForge carrier ({@link TestFixtures#NEO_CARRIER_JAR}) and the MinecraftForge carrier
 * ({@link TestFixtures#FORGE_CARRIER_JAR}, {@link TestFixtures#FORGE_INTEROP_JAR}), so moving the base or the stage's
 * layout is a change in one place. ~265 copies of the base's path and ~135 of the Forge carrier's were folded into it
 * once; without this a test added afterwards brings its own copy back, gated as a plain staged fixture, and goes on
 * reading the merged base after the base has moved, or the carrier after the stage has lost it.
 *
 * <p>A spelling is a staged path inside one string literal ({@code "forge-runtime/forge-runtime.jar"}, with or without
 * text around it inside the quotes), or the same path split into its directory and its file name across one
 * statement ({@code staged("neoforge-runtime", "neoforge-runtime.jar")}, {@code .resolve("merged-base").resolve(...)}),
 * in either order, including a directory literal whose file name a TestFixtures constant supplies
 * ({@code patched(Path.of(GENUINE_BASE_JAR).getFileName().toString(), "neoforge-patched")}).
 * A bare file name alone is not one: the fabricated game and carrier jars (LaunchInputCheckTest, KernelDataPacksTest)
 * and the synthetic crash logs (CrashAttributionTest) name jars of their own making in directories of their own.
 *
 * <p>The same holds for the reads only the merge can answer: its {@code .pins} stamp, an embedded
 * {@code META-INF/forbric/native-reference/} entry, a {@code .class.bin} copy, its {@code merge-conflicts.txt} report,
 * and above all a native-reference reader the test binds or builds over the base ({@code NativeGameReferences.bind},
 * {@code new NativeGameReferences}, {@code NativeGameReferences.over}, {@code NativeReferenceSources.open}, or the
 * construction of a class that builds one over what it is handed), which answers from the embedded index and spells
 * no entry name at all. Read through {@link TestFixtures#BASE_JAR}
 * or {@link TestFixtures#stagedBase()}, they would read the genuine jar, which has none of them, once {@code BASE_JAR}
 * moves; those go through {@link TestFixtures#MERGED_BASE_JAR} / {@link TestFixtures#mergedBase()}. Judged per file,
 * not per line: the entry is usually a constant declared at the top ({@code NEOFORGE_PREFIX}) and the jar a local named
 * {@code merged} a hundred lines below, and a line-local check let exactly that through. A file is flagged when its code
 * both reaches such a marker (a string literal holding one, a constant another source declares to hold one, or a
 * reader) and reads the moving base, and names the merged base nowhere; a line that does both is flagged even then.
 * Data-flow-light: a test helper's static method that builds a reader for its caller is not followed.
 *
 * <p>Source text, not bytecode: the constants are compile-time constants, so the compiled tests cannot tell the two
 * spellings apart, and a check of the class files would see every converted read as a raw one. Comments are blanked
 * first, so prose naming a path or {@link TestFixtures#stagedBase()} is neither a spelling nor a read.
 */
class StagedJarLiteralGuardTest {
	private static final Path SOURCES = Path.of("src/test/java");
	private static final Path MAIN_SOURCES = Path.of("src/main/java");

	/** Every staged jar, relative to the staged root, as TestFixtures names it. */
	private static final List<String> STAGED_JARS = List.of(TestFixtures.MERGED_BASE_JAR, TestFixtures.GENUINE_BASE_JAR,
			TestFixtures.NEO_CARRIER_JAR, TestFixtures.FORGE_CARRIER_JAR, TestFixtures.FORGE_INTEROP_JAR,
			TestFixtures.FORGE_PATCHED_JAR);

	/**
	 * Files allowed to keep a raw or split spelling, each with why. A reason starting {@code synthetic} marks a
	 * fabricated layout that only looks like the stage; the fold script for the Forge carrier's literals
	 * (fold_forge_carrier_literals.py, kept with the Stage 2 round-2 guard lane) skips those. An entry whose file no
	 * longer spells one fails too, so the list cannot outlive its reasons.
	 */
	private static final Map<String, String> ALLOWED_SPELLINGS = Map.of();

	/**
	 * Files allowed to reach a merged-only marker while reading the moving base without naming the merged base, each
	 * with why. Stale entries fail as above.
	 */
	private static final Map<String, String> ALLOWED_MERGED_ONLY = Map.of(
			"net/forbric/kernel/mixin/weave/LootSourceCallbacksSpellingTest.java",
			"either base: reads the base's embedded index only when it has one, else the vanilla-reference sidecar",
			"net/forbric/kernel/mixin/weave/MixinCrossHostPredicateIslandWeaveTest.java",
			"writes an embedded index into a fixture jar of its own; it reads the base through launchSources(base)");

	/** What only the merge produces, as a string literal spells it: its index entries, its stamp, its report. */
	private static final Pattern MERGED_ONLY_LITERAL = Pattern.compile(
			"\"[^\"\\n]*(?:META-INF/forbric/native-reference/|\\.pins|\\.class\\.bin|merge-conflicts\\.txt)[^\"\\n]*\"");
	/**
	 * A reader of the native references bound or built by the test itself. With no {@link
	 * net.forbric.kernel.mixin.NativeReferenceSources} configured (no test configures one unless it says so), it answers
	 * from the base's embedded index alone, so over the genuine base it answers nothing: the commonest merged-only read
	 * there is, and it spells no entry name. {@code bind(null)} unbinds, which reads nothing.
	 */
	private static final Pattern READER = Pattern.compile("\\bNativeGameReferences\\s*\\.\\s*(?:bind|over)\\s*\\((?!\\s*null\\s*\\))"
			+ "|\\bnew\\s+NativeGameReferences\\s*\\(|\\bNativeReferenceSources\\s*\\.\\s*open\\s*\\(");
	/** A main source that builds a reader over resources it is handed ({@code ModifiableDataViewsTransformer}). */
	private static final Pattern BUILDS_A_READER =
			Pattern.compile("\\bNativeGameReferences\\s*\\.\\s*over\\s*\\(|\\bnew\\s+NativeGameReferences\\s*\\(");
	private static final Pattern MOVING_BASE = Pattern.compile("(?<!MERGED_)\\bBASE_JAR\\b|\\bstagedBase\\(\\)");
	/**
	 * What names the merged base as such: TestFixtures' own constant and accessor, and ChainReferences' merged jars. A
	 * test's own helper that happens to be called {@code mergedBase()} does not count: TransportSpellings' was one, and it
	 * returned the moving base.
	 */
	private static final Pattern MERGED_BASE = Pattern.compile("\\bMERGED_BASE_JAR\\b|\\bTestFixtures\\s*\\.\\s*mergedBase\\(\\)"
			+ "|\\bChainReferences\\s*\\.\\s*mergedJars\\(\\)");
	/**
	 * A reader handed the sources a launch on its base has ({@code TestFixtures.launchSources(base)}), or a sidecar the
	 * installer's writer built for it ({@code TestFixtures.vanillaReference(base)}): it answers from the embedded index
	 * only on a base that embeds one and from the sidecar otherwise, so it reads what a launch on either base reads.
	 */
	private static final Pattern LAUNCH_SOURCES = Pattern.compile("\\b(?:launchSources|vanillaReference)\\s*\\(");
	private static final Pattern MARKER_CONSTANT = Pattern.compile(
			"(?<!private )static final String ([A-Z][A-Z0-9_]*)\\s*=\\s*" + MERGED_ONLY_LITERAL.pattern());

	/** The staged paths, quoted as a literal spells them (with or without a prefix or suffix inside the quotes). */
	static Pattern rawLiteral() {
		String paths = STAGED_JARS.stream().map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElseThrow();
		return Pattern.compile("\"[^\"\\n]*(?:" + paths + ")[^\"\\n]*\"");
	}

	/**
	 * A staged jar's directory as a whole literal: {@code "neoforge-patched"}, with a prefix ending in a slash
	 * ({@code "run/merged-base"}) or a trailing slash. A whole literal, so {@code "neoforge-runtime"} does not pass for
	 * {@code "forge-runtime"} and {@code "run/forge-runtime/forbriclive.jar"}, the Forge canary, is not a directory.
	 */
	private static Pattern directoryLiteral(String jar) {
		return Pattern.compile("\"(?:[^\"\\n]*/)?" + Pattern.quote(jar.substring(0, jar.lastIndexOf('/'))) + "/?\"");
	}

	/** What names a staged jar's file within a statement that also spells its directory. */
	private static Pattern fileNameOf(String jar) {
		return Pattern.compile("\"/?" + Pattern.quote(jar.substring(jar.lastIndexOf('/') + 1)) + "\""
				+ "|\\b(?:MERGED_BASE_JAR|BASE_JAR|GENUINE_BASE_JAR|NEO_CARRIER_JAR|FORGE_CARRIER_JAR|FORGE_INTEROP_JAR|FORGE_PATCHED_JAR)\\b"
				+ "|\\.getFileName\\(\\)");
	}

	/**
	 * Every staged path split into its directory and its file name within one statement, in either order: a directory
	 * literal ({@link #directoryLiteral}) beside that jar's exact file-name literal, or beside a TestFixtures jar
	 * constant or a {@code getFileName()} that supplies the name ({@code patched(Path.of(GENUINE_BASE_JAR).getFileName()
	 * .toString(), "neoforge-patched")}, which re-spells the directory the constant already names). The exact file name
	 * keeps {@code "neoforge-runtime-26.2.jar"}, a fabricated carrier's bare name, from passing for the staged one, and a
	 * statement ends at a {@code ;}, {@code {} or {@code }}, so a directory and a name in neighbouring statements are not
	 * one path. Offsets into {@code code}.
	 */
	static List<Integer> splitSpellings(String code) {
		List<Integer> found = new ArrayList<>();
		Matcher statement = Pattern.compile("[^;{}]+").matcher(code);
		while (statement.find()) {
			String text = statement.group();
			int first = -1;
			for (String jar : STAGED_JARS) {
				Matcher directory = directoryLiteral(jar).matcher(text);
				if (directory.find() && fileNameOf(jar).matcher(text).find() && (first < 0 || directory.start() < first)) first = directory.start();
			}
			if (first >= 0) found.add(statement.start() + first);
		}
		return found;
	}

	/**
	 * {@code source} with every comment blanked to spaces, line breaks kept, so a match's offset still gives its line.
	 * String, character and text-block literals are kept as they are.
	 */
	static String code(String source) {
		StringBuilder out = new StringBuilder(source);
		int i = 0, n = source.length();
		while (i < n) {
			char c = source.charAt(i);
			if (source.startsWith("//", i)) {
				int end = source.indexOf('\n', i);
				i = blank(out, i, end < 0 ? n : end);
			} else if (source.startsWith("/*", i)) {
				int end = source.indexOf("*/", i + 2);
				i = blank(out, i, end < 0 ? n : end + 2);
			} else if (source.startsWith("\"\"\"", i)) {
				int end = i + 3;
				while ((end = source.indexOf("\"\"\"", end)) >= 0 && source.charAt(end - 1) == '\\') end++;
				i = end < 0 ? n : end + 3;
			} else if (c == '"' || c == '\'') {
				int end = i + 1;
				while (end < n && source.charAt(end) != c && source.charAt(end) != '\n') end += source.charAt(end) == '\\' ? 2 : 1;
				i = end + 1;
			} else {
				i++;
			}
		}
		return out.toString();
	}

	private static int blank(StringBuilder out, int from, int to) {
		for (int i = from; i < to; i++) if (out.charAt(i) != '\n') out.setCharAt(i, ' ');
		return to;
	}

	/** A line reading a merged-only artifact through the moving base constant. */
	static boolean mergedOnlyReadThroughTheBase(String line) {
		return MOVING_BASE.matcher(line).find() && (MERGED_ONLY_LITERAL.matcher(line).find()
				|| READER.matcher(line).find() && !LAUNCH_SOURCES.matcher(line).find());
	}

	/**
	 * What reaches a merged-only marker from another file: the constants whose value is one and that another file can
	 * name ({@code NativeReferenceSources.PREFIX}), keyed by the declaring class's simple name, and the classes that
	 * build a reader over resources they are handed, whose construction is a reader as much as {@link #READER} is.
	 * A private constant is reached only by its own file, whose literal is already in view.
	 */
	record Markers(Map<String, Set<String>> constants, Set<String> readerBuilders) {
		static final Markers NONE = new Markers(Map.of(), Set.of());

		static Markers of(Map<String, String> codeByFile) {
			Map<String, Set<String>> constants = new TreeMap<>();
			Set<String> builders = new TreeSet<>();
			codeByFile.forEach((name, code) -> {
				String owner = name.substring(name.lastIndexOf('/') + 1).replace(".java", "");
				Matcher m = MARKER_CONSTANT.matcher(code);
				while (m.find()) constants.computeIfAbsent(owner, o -> new TreeSet<>()).add(m.group(1));
				if (!owner.equals("NativeGameReferences") && BUILDS_A_READER.matcher(code).find()) builders.add(owner);
			});
			return new Markers(constants, builders);
		}

		/** Where {@code code} first reaches a marker, or -1. */
		int first(String code) {
			int first = -1;
			List<Pattern> patterns = new ArrayList<>(List.of(MERGED_ONLY_LITERAL, READER));
			constants.forEach((owner, names) -> names.forEach(constant ->
					patterns.add(Pattern.compile("\\b" + Pattern.quote(owner) + "\\s*\\.\\s*" + constant + "\\b"))));
			readerBuilders.forEach(owner -> patterns.add(Pattern.compile("\\bnew\\s+" + Pattern.quote(owner) + "\\s*[(<]")));
			for (Pattern pattern : patterns) {
				Matcher m = pattern.matcher(code);
				while (m.find()) {
					if (pattern == READER && handedLaunchSources(code, m.start())) continue;
					if (first < 0 || m.start() < first) first = m.start();
					break;
				}
			}
			return first;
		}

		/**
		 * Whether the reader whose construction starts at {@code at} is handed a launch's sources among its arguments:
		 * the text up to the parenthesis that closes its argument list, so a lambda body's own statements in between do
		 * not end it early. Comments are blanked already; a parenthesis inside a string literal is skipped.
		 */
		private static boolean handedLaunchSources(String code, int at) {
			int open = code.indexOf('(', at), depth = 0, end = code.length();
			for (int i = open; open >= 0 && i < code.length(); i++) {
				char c = code.charAt(i);
				if (c == '"') {
					for (i++; i < code.length() && code.charAt(i) != '"' && code.charAt(i) != '\n'; i++) if (code.charAt(i) == '\\') i++;
				} else if (c == '(') depth++;
				else if (c == ')' && --depth == 0) { end = i; break; }
			}
			return LAUNCH_SOURCES.matcher(code.substring(at, end)).find();
		}
	}

	/**
	 * The merged-only reads through the moving base in one file's comment-blanked {@code code}: every line that does
	 * both, and, when the file names the merged base nowhere, the first marker and the first base read wherever they are.
	 */
	static List<String> mergedOnlyReads(String code, Markers markers) {
		List<String> found = new ArrayList<>();
		String[] lines = code.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			if (mergedOnlyReadThroughTheBase(lines[i])) found.add((i + 1) + ": " + lines[i].strip());
		}
		if (!found.isEmpty() || MERGED_BASE.matcher(code).find()) return found;
		Matcher base = MOVING_BASE.matcher(code);
		if (!base.find()) return found;
		int marker = markers.first(code);
		if (marker < 0) return found;
		found.add(lineAt(lines, code, marker) + " reaches what only the merged base has, and " + lineAt(lines, code, base.start())
				+ " reads the base that moves");
		return found;
	}

	private static String lineAt(String[] lines, String code, int offset) {
		int line = (int) code.substring(0, offset).chars().filter(c -> c == '\n').count();
		return "line " + (line + 1) + " (" + lines[line].strip() + ")";
	}

	/** Every staged-path spelling in one file's comment-blanked {@code code}: whole-literal ones, then split ones. */
	static List<String> spellings(String code) {
		List<String> found = new ArrayList<>();
		String[] lines = code.split("\n", -1);
		Matcher m = rawLiteral().matcher(code);
		while (m.find()) found.add(lineAt(lines, code, m.start()) + ": " + m.group().replaceAll("\\s+", " "));
		for (int offset : splitSpellings(code)) {
			int end = code.indexOf(';', offset);
			found.add(lineAt(lines, code, offset) + ": " + code.substring(offset, end < 0 ? code.length() : end).replaceAll("\\s+", " "));
		}
		return found;
	}

	private static Map<String, String> codeUnder(Path root) throws IOException {
		Map<String, String> code = new TreeMap<>();
		if (!Files.isDirectory(root)) return code;
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
				code.put(root.relativize(file).toString().replace('\\', '/'), code(Files.readString(file)));
			}
		}
		return code;
	}

	@Test
	void noTestSpellsAStagedJarsPathOutsideTestFixtures() throws IOException {
		assertTrue(Files.isDirectory(SOURCES), "run from forbric-kernel, where " + SOURCES + " is");
		Map<String, String> tests = codeUnder(SOURCES), everything = new TreeMap<>(codeUnder(MAIN_SOURCES));
		everything.putAll(tests);
		Markers markers = Markers.of(everything);
		Map<String, List<String>> found = new TreeMap<>(), mergedOnly = new TreeMap<>();
		tests.forEach((name, code) -> {
			// TestFixtures is where the paths are spelled; this file spells every pattern to test it.
			if (name.equals("net/forbric/kernel/TestFixtures.java") || name.equals("net/forbric/kernel/StagedJarLiteralGuardTest.java")) return;
			List<String> spelled = spellings(code);
			if (!spelled.isEmpty()) found.put(name, spelled);
			List<String> reads = mergedOnlyReads(code, markers);
			if (!reads.isEmpty()) mergedOnly.put(name, reads);
		});
		Map<String, List<String>> unexplained = new TreeMap<>(found);
		unexplained.keySet().removeAll(ALLOWED_SPELLINGS.keySet());
		assertEquals(Map.of(), unexplained, "staged jar paths spelled outside TestFixtures; use TestFixtures.BASE_JAR (or "
				+ "MERGED_BASE_JAR for the merged base as such), GENUINE_BASE_JAR, NEO_CARRIER_JAR, FORGE_CARRIER_JAR, "
				+ "FORGE_INTEROP_JAR, or stagedBase(), genuineBase(), neoCarrier(), forgeCarrier()");
		assertEquals(List.of(), stale(ALLOWED_SPELLINGS, found), "allowed to spell a staged jar's path but no longer does: "
				+ "remove the entry");
		Map<String, List<String>> unexplainedReads = new TreeMap<>(mergedOnly);
		unexplainedReads.keySet().removeAll(ALLOWED_MERGED_ONLY.keySet());
		assertEquals(Map.of(), unexplainedReads, "reads of what only the merged base has (its .pins, its embedded native "
				+ "references, their .class.bin copies) through BASE_JAR or stagedBase(), which Stage 4 points at the "
				+ "genuine jar; use MERGED_BASE_JAR or mergedBase()");
		assertEquals(List.of(), stale(ALLOWED_MERGED_ONLY, mergedOnly), "allowed a merged-only read through the moving "
				+ "base but no longer makes one: remove the entry");
	}

	private static List<String> stale(Map<String, String> allowed, Map<String, List<String>> found) {
		return allowed.keySet().stream().filter(name -> !found.containsKey(name)).sorted().toList();
	}

	/** The spelling patterns, on the spellings they must and must not catch. */
	@Test
	void theGuardTellsARawSpellingFromAConstant() {
		Pattern raw = rawLiteral();
		String q = "\"";
		assertTrue(raw.matcher("STAGED.resolve(" + q + TestFixtures.NEO_CARRIER_JAR + q + ")").find());
		assertTrue(raw.matcher("OLD.resolve(" + q + "run/" + TestFixtures.MERGED_BASE_JAR + q + ")").find());
		assertTrue(raw.matcher("Path.of(" + q + "../forbric-loader/run/" + TestFixtures.GENUINE_BASE_JAR + q + ")").find());
		assertTrue(raw.matcher("STAGED.resolve(" + q + TestFixtures.FORGE_CARRIER_JAR + q + ")").find(), "the Forge carrier");
		assertTrue(raw.matcher("RUN.resolve(" + q + TestFixtures.FORGE_INTEROP_JAR + q + ")").find(), "the Forge interop jar");
		assertTrue(!raw.matcher("OLD.resolve(" + q + "run/" + q + " + TestFixtures.NEO_CARRIER_JAR)").find());
		assertTrue(!raw.matcher("dir.resolve(" + q + "neoforge-runtime-26.2.jar" + q + ")").find(), "a fabricated carrier's bare name");

		assertEquals(1, spellings(code("Path p = staged(" + q + "neoforge-runtime" + q + ", " + q + "neoforge-runtime.jar" + q + ");")).size());
		assertEquals(1, spellings(code("Path p = staged(" + q + "forge-runtime" + q + ",\n\t\t" + q + "forge-runtime.jar" + q + ");")).size(),
				"split across lines");
		assertEquals(1, spellings(code("root.resolve(" + q + "merged-base/" + q + ").resolve(" + q + "patched-mc-merged-26.2.jar" + q + ");")).size());
		assertEquals(1, spellings(code("root.resolve(" + q + "run/neoforge-patched" + q + ").resolve(" + q + "patched-mc-neoforge-26.2.jar" + q + ");")).size());
		assertEquals(1, spellings(code("root.resolve(" + q + "merged-base" + q + ").resolve(" + q + "forge-runtime-interop.jar" + q + ");")).size());
		assertEquals(1, spellings(code("NEO = patched(Path.of(TestFixtures.GENUINE_BASE_JAR).getFileName().toString(), " + q + "neoforge-patched" + q + ");")).size(),
				"a directory re-spelled beside the constant that names it, after it");
		assertEquals(1, spellings(code("stagedRoot().resolve(" + q + "neoforge-patched" + q + ")\n\t.resolve(Path.of(TestFixtures.GENUINE_BASE_JAR).getFileName());")).size(),
				"a directory literal with the file name taken from the constant");
		assertEquals(1, spellings(code("Path p = root.resolve(" + q + "forge-runtime.jar" + q + ").resolve(" + q + "run/forge-runtime/" + q + ");")).size(),
				"the name first");
		assertEquals(List.of(), spellings(code("inspect(" + q + "forge" + q + ", " + q + "forbriclive" + q + ", " + q + "livemod-src" + q + ", " + q + "forge-runtime" + q + ");")),
				"a directory alone");
		assertEquals(List.of(), spellings(code("Path d = root.resolve(" + q + "merged-base" + q + "); Path j = STAGED.resolve(TestFixtures.BASE_JAR);")),
				"a directory and a constant in neighbouring statements");
		assertEquals(List.of(), spellings(code("staged(" + q + "neoforge-runtime" + q + ", " + q + "forge-runtime.jar" + q + ");")),
				"a directory and another jar's name are not a staged path");
		assertEquals(List.of(), spellings(code("dir.resolve(" + q + "forge-runtime-interop.jar" + q + "); jar(" + q + "neoforge-runtime.jar" + q + ");")),
				"bare names of fabricated jars");
		assertEquals(List.of(), spellings(code("tmp.resolve(" + q + "run/forge-runtime/forbriclive.jar" + q + ");")), "the Forge canary");
		assertEquals(List.of(), spellings(code("x(" + q + "forge-runtime" + q + "); y(); z(" + q + "forge-runtime.jar" + q + ");")),
				"one statement only");
		assertEquals(List.of(), spellings(code("// " + q + TestFixtures.FORGE_CARRIER_JAR + q + "\n/* staged(" + q + "forge-runtime" + q
				+ ", " + q + "forge-runtime.jar" + q + ") */")), "comments");
		assertEquals(q + "a // b" + q + "   ", code(q + "a // b" + q + "//c"), "a comment marker inside a string is not a comment");
	}

	/** The merged-only read check, on whole synthetic sources: what a line-local check missed, and what it must pass. */
	@Test
	void theGuardFollowsAMergedOnlyMarkerAcrossTheFile() {
		String q = "\"";
		Markers none = Markers.NONE;
		assertTrue(mergedOnlyReadThroughTheBase("STAGED.resolve(TestFixtures.BASE_JAR + " + q + ".pins" + q + ")"));
		assertTrue(mergedOnlyReadThroughTheBase("staged(TestFixtures.BASE_JAR, " + q + "META-INF/forbric/native-reference/FABRIC/x" + q + ")"));
		assertTrue(!mergedOnlyReadThroughTheBase("STAGED.resolve(TestFixtures.MERGED_BASE_JAR + " + q + ".pins" + q + ")"));
		assertTrue(!mergedOnlyReadThroughTheBase("staged(TestFixtures.BASE_JAR, owner + " + q + ".class" + q + ")"));

		// GenuineNativeReferencesStagedTest's shape before its conversion: the prefix a constant, the jar a local.
		String split = "class T {\n"
				+ "\tprivate static final String NEOFORGE_PREFIX = " + q + "META-INF/forbric/native-reference/NEOFORGE/" + q + ";\n"
				+ "\tprivate static final String NEOFORGE_INDEX = NEOFORGE_PREFIX + " + q + "index.tsv" + q + ";\n"
				+ "\tvoid t() {\n"
				+ "\t\tPath merged = TestFixtures.stagedBase();\n"
				+ "\t\ttry (ZipFile mergedJar = new ZipFile(merged.toFile())) { mergedJar.getEntry(NEOFORGE_INDEX); }\n"
				+ "\t}\n}\n";
		List<String> reads = mergedOnlyReads(code(split), none);
		assertEquals(1, reads.size(), reads.toString());
		assertTrue(reads.get(0).contains("line 2") && reads.get(0).contains("line 5"), reads.get(0));
		assertEquals(List.of(), mergedOnlyReads(code(split.replace("stagedBase()", "mergedBase()")), none),
				"the same read through the merged base");
		String twoLines = "Path base = TestFixtures.stagedBase();\nbytes(base, owner + " + q + ".class.bin" + q + ");\n";
		assertEquals(1, mergedOnlyReads(code(twoLines), none).size(), ".class.bin on the next line");
		String pins = "Path jar = STAGED.resolve(TestFixtures.BASE_JAR);\nPath pins = jar.resolveSibling(jar.getFileName() + " + q + ".pins" + q + ");\n";
		assertEquals(1, mergedOnlyReads(code(pins), none).size(), ".pins on the next line");

		// A constant another source declares: the marker is nowhere in this file's own literals.
		Markers constants = Markers.of(Map.of("net/forbric/kernel/mixin/NativeReferenceSources.java",
				code("final class NativeReferenceSources {\n\tstatic final String PREFIX = " + q + "META-INF/forbric/native-reference/" + q + ";\n}\n"),
				"net/forbric/kernel/mixin/Hidden.java",
				code("class Hidden {\n\tprivate static final String SECRET = " + q + "x.pins" + q + ";\n}\n")));
		assertEquals(Map.of("NativeReferenceSources", Set.of("PREFIX")), constants.constants(), "a private constant reaches only its own file");
		String elsewhere = "Path merged = TestFixtures.stagedBase();\nString entry = NativeReferenceSources.PREFIX + " + q + "FABRIC/index.tsv" + q + ";\n";
		assertEquals(1, mergedOnlyReads(code(elsewhere), constants).size(), "through another file's constant");
		assertEquals(List.of(), mergedOnlyReads(code(elsewhere), none), "without the constant known, nothing reaches a marker");

		// What must pass: prose, a class read, and a line that reads the merged base beside a file that also reads the moving one.
		assertEquals(List.of(), mergedOnlyReads(code("/** reads {@link TestFixtures#stagedBase()}'s {@code .pins} */\nclass T {}\n"), none),
				"prose");
		assertEquals(List.of(), mergedOnlyReads(code("bytes(TestFixtures.stagedBase(), owner + " + q + ".class" + q + ");\n"), none));
		String mixed = "ClassNode merged = staged(TestFixtures.BASE_JAR, owner + " + q + ".class" + q + ");\n"
				+ "ClassNode vanilla = staged(TestFixtures.MERGED_BASE_JAR, " + q + "META-INF/forbric/native-reference/FABRIC/" + q + " + owner);\n";
		assertEquals(List.of(), mergedOnlyReads(code(mixed), none), "the merged-only read already goes through the merged base");
		assertEquals(1, mergedOnlyReads(code(mixed + "staged(TestFixtures.BASE_JAR, " + q + "x.pins" + q + ");\n"), none).size(),
				"a line that reads a marker through the moving base is flagged even in a file that names the merged base");

		// The merge's report beside the base it describes (MergedLambdaCaptureTest's shape before its conversion).
		String report = "Path merged = STAGED.resolve(TestFixtures.BASE_JAR);\nPath report = STAGED.resolve(" + q + "merged-base/merge-conflicts.txt" + q + ");\n";
		assertEquals(1, mergedOnlyReads(code(report), none).size(), "the merge's conflict report");
	}

	/**
	 * A reader the test binds or builds itself reads the embedded index, which only the merge has: the shapes of
	 * MergedBaseDiscoveryFamilyTest, MixinStubRebindSharedRedirectTest and NativeOwnedComponentInitializersTest before
	 * their conversion, none of which spells an entry name.
	 */
	@Test
	void aReaderOverTheMovingBaseIsAMergedOnlyRead() {
		Markers none = Markers.NONE;
		String bound = "class T {\n"
				+ "\tvoid t() throws Exception {\n"
				+ "\t\tPath merged = TestFixtures.stagedBase();\n"
				+ "\t\tvar loader = new ForbricClassLoader(new URL[]{merged.toUri().toURL()}, null);\n"
				+ "\t\tNativeGameReferences.bind(loader);\n"
				+ "\t\tassertNotNull(NativeGameReferences.reference(Ecosystem.FABRIC, BOOTSTRAP));\n"
				+ "\t}\n}\n";
		List<String> reads = mergedOnlyReads(code(bound), none);
		assertEquals(1, reads.size(), reads.toString());
		assertTrue(reads.get(0).contains("line 5") && reads.get(0).contains("line 3"), reads.get(0));
		assertEquals(List.of(), mergedOnlyReads(code(bound.replace("stagedBase()", "mergedBase()")), none), "through the merged base");
		for (String reader : List.of("new NativeGameReferences(p -> bytes(jar, p))", "NativeGameReferences.over(resources)",
				"NativeReferenceSources.open(base, null)")) {
			assertEquals(1, mergedOnlyReads(code("Path base = TestFixtures.stagedBase();\nvar r = " + reader + ";\n"), none).size(), reader);
		}
		assertEquals(1, mergedOnlyReads(code("var r = new NativeGameReferences(read(TestFixtures.stagedBase()));\n"), none).size(),
				"on one line");
		assertEquals(List.of(), mergedOnlyReads(code("Path base = TestFixtures.stagedBase();\nNativeGameReferences.bind(null);\n"), none),
				"unbinding reads nothing");

		// A class that builds a reader over what it is handed: constructing it is a reader too.
		Markers builders = Markers.of(Map.of("net/forbric/kernel/transform/Views.java",
				code("public final class Views {\n\tpublic Views(Function<String,byte[]> r){refs=NativeGameReferences.over(r);}\n}\n"),
				"net/forbric/kernel/mixin/NativeGameReferences.java",
				code("public final class NativeGameReferences {\n\tstatic X over(F r){return new NativeGameReferences(r);}\n}\n")));
		assertEquals(Set.of("Views"), builders.readerBuilders(), "the reader itself is not a builder of one");
		String views = "Function<String,byte[]> r = resources(TestFixtures.stagedBase());\nbyte[] out = new Views(r).transform(owner, raw);\n";
		assertEquals(1, mergedOnlyReads(code(views), builders).size(), "through a class that builds a reader");
		assertEquals(List.of(), mergedOnlyReads(code(views), none), "without the builder known");
		assertEquals(List.of(), mergedOnlyReads(code("Path base = TestFixtures.stagedBase();\nvar v = new ViewsTest();\n"), builders),
				"a longer name is not the builder");
		assertEquals(List.of(), mergedOnlyReads(code("/** {@link NativeGameReferences#bind} */\nPath base = TestFixtures.stagedBase();\n"), none),
				"prose naming the reader");

		// A reader handed a launch's sources reads the sidecar where the base embeds no index: not a merged-only read.
		for (String sources : List.of("TestFixtures.launchSources(base)", "NativeReferenceSources.open(base, TestFixtures.vanillaReference(base))")) {
			String launched = "Path base = TestFixtures.stagedBase();\nvar r = new NativeGameReferences(p -> bytes(base, p), null, " + sources + ");\n";
			assertEquals(List.of(), mergedOnlyReads(code(launched), none), sources);
		}
		assertEquals(1, mergedOnlyReads(code("Path base = TestFixtures.stagedBase();\nvar s = TestFixtures.launchSources(base);\n"
				+ "var r = new NativeGameReferences(p -> bytes(base, p));\n"), none).size(), "the sources must be handed to that reader");
		// Only TestFixtures' own accessor names the merged base: a helper of the same name may return the moving one.
		String ownHelper = "static Path mergedBase() { return TestFixtures.stagedBase(); }\n"
				+ "var r = new NativeGameReferences(p -> bytes(mergedBase(), p));\n";
		assertEquals(1, mergedOnlyReads(code(ownHelper), none).size(), "a test's own mergedBase() helper");
	}
}
