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
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No test spells a staged jar's path itself. {@link TestFixtures} names the merged base ({@link TestFixtures#BASE_JAR},
 * {@link TestFixtures#MERGED_BASE_JAR}), the genuine NeoForge base ({@link TestFixtures#GENUINE_BASE_JAR}) and the
 * NeoForge carrier ({@link TestFixtures#NEO_CARRIER_JAR}), so moving the base or the stage's layout is a change in one
 * place. ~265 copies of the base's path were folded into it once; without this a test added afterwards brings its own
 * copy back, gated as a plain staged fixture, and goes on reading the merged base after the base has moved.
 *
 * <p>The same holds for the reads only the merge can answer: a line that reads the merged base's {@code .pins} stamp or
 * an embedded {@code META-INF/forbric/native-reference/} entry through {@link TestFixtures#BASE_JAR} or
 * {@link TestFixtures#stagedBase()} would read the genuine jar, which has neither, once {@code BASE_JAR} moves; those go
 * through {@link TestFixtures#MERGED_BASE_JAR} / {@link TestFixtures#mergedBase()}.
 *
 * <p>Source text, not bytecode: the constants are compile-time constants, so the compiled tests cannot tell the two
 * spellings apart, and a check of the class files would see every converted read as a raw one.
 */
class StagedJarLiteralGuardTest {
	private static final Path SOURCES = Path.of("src/test/java");

	/**
	 * Files allowed to keep a raw spelling, each with why. The synthetic crash logs (CrashAttributionTest) and the
	 * fabricated game and carrier jars (LaunchInputCheckTest) spell bare file names, not these staged paths, so they
	 * need no entry. An entry whose file no longer spells one fails too, so the list cannot outlive its reasons.
	 */
	private static final Map<String, String> ALLOWED = Map.of(
			// TODO stage2-integrator: the Stage 2 fix lanes that own these files convert them; drop each entry once merged.
			"net/forbric/kernel/mixin/VanillaReferenceStagedTest.java", "stage2-integrator: converted by the refs fix lane",
			"net/forbric/kernel/transform/GenuineNativeReferencesStagedTest.java", "stage2-integrator: converted by the refs fix lane",
			"net/forbric/kernel/transform/VanillaMemberTwinInjectorTest.java", "stage2-integrator: converted by the refs fix lane",
			"net/forbric/kernel/boot/KernelServerDutiesGenuineBaseTest.java", "stage2-integrator: converted by the relocate fix lane",
			"net/forbric/kernel/mixin/LambdaShapeEvidenceStagedTest.java", "stage2-integrator: converted by the lambda fix lane",
			"net/forbric/kernel/transform/ChainReferences.java", "stage2-integrator: converted by the lambda fix lane",
			"net/forbric/kernel/transform/ItemUseOnInjectorTest.java", "stage2-integrator: converted by the lambda fix lane");

	/** The staged paths, quoted as a literal spells them (with or without a prefix or suffix inside the quotes). */
	static Pattern rawLiteral() {
		String paths = Stream.of(TestFixtures.MERGED_BASE_JAR, TestFixtures.GENUINE_BASE_JAR, TestFixtures.NEO_CARRIER_JAR)
				.map(Pattern::quote).reduce((a, b) -> a + "|" + b).orElseThrow();
		return Pattern.compile("\"[^\"\\n]*(?:" + paths + ")[^\"\\n]*\"");
	}

	/** A line reading a merged-only artifact through the moving base constant. */
	static boolean mergedOnlyReadThroughTheBase(String line) {
		boolean base = Pattern.compile("(?<!MERGED_)\\bBASE_JAR\\b|\\bstagedBase\\(\\)").matcher(line).find();
		return base && (line.contains(".pins\"") || line.contains("META-INF/forbric/native-reference/"));
	}

	@Test
	void noTestSpellsAStagedJarsPathOutsideTestFixtures() throws IOException {
		assertTrue(Files.isDirectory(SOURCES), "run from forbric-kernel, where " + SOURCES + " is");
		Pattern raw = rawLiteral();
		Map<String, List<String>> found = new TreeMap<>(), mergedOnly = new TreeMap<>();
		try (Stream<Path> files = Files.walk(SOURCES)) {
			for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
				String name = SOURCES.relativize(file).toString().replace('\\', '/');
				// TestFixtures is where the paths are spelled; this file spells both patterns to test them.
				if (name.equals("net/forbric/kernel/TestFixtures.java") || name.equals("net/forbric/kernel/StagedJarLiteralGuardTest.java")) continue;
				List<String> lines = Files.readAllLines(file);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i);
					if (raw.matcher(line).find()) found.computeIfAbsent(name, n -> new ArrayList<>()).add((i + 1) + ": " + line.strip());
					if (mergedOnlyReadThroughTheBase(line)) mergedOnly.computeIfAbsent(name, n -> new ArrayList<>()).add((i + 1) + ": " + line.strip());
				}
			}
		}
		Map<String, List<String>> unexplained = new TreeMap<>(found);
		unexplained.keySet().removeAll(ALLOWED.keySet());
		assertEquals(Map.of(), unexplained, "staged jar paths spelled outside TestFixtures; use TestFixtures.BASE_JAR (or "
				+ "MERGED_BASE_JAR for the merged base as such), GENUINE_BASE_JAR, NEO_CARRIER_JAR, or stagedBase(), "
				+ "genuineBase(), neoCarrier()");
		List<String> stale = ALLOWED.keySet().stream().filter(name -> !found.containsKey(name)).sorted().toList();
		assertEquals(List.of(), stale, "allowed to spell a staged jar's path but no longer does: remove the entry");
		assertEquals(Map.of(), mergedOnly, "reads of what only the merged base has (its .pins, its embedded native "
				+ "references) through BASE_JAR or stagedBase(), which Stage 4 points at the genuine jar; use MERGED_BASE_JAR "
				+ "or mergedBase()");
	}

	/** The guard's own two patterns, on the spellings it must and must not catch. */
	@Test
	void theGuardTellsARawSpellingFromAConstant() {
		Pattern raw = rawLiteral();
		String q = "\"";
		assertTrue(raw.matcher("STAGED.resolve(" + q + TestFixtures.NEO_CARRIER_JAR + q + ")").find());
		assertTrue(raw.matcher("OLD.resolve(" + q + "run/" + TestFixtures.MERGED_BASE_JAR + q + ")").find());
		assertTrue(raw.matcher("Path.of(" + q + "../forbric-loader/run/" + TestFixtures.GENUINE_BASE_JAR + q + ")").find());
		assertTrue(!raw.matcher("OLD.resolve(" + q + "run/" + q + " + TestFixtures.NEO_CARRIER_JAR)").find());
		assertTrue(!raw.matcher("dir.resolve(" + q + "neoforge-runtime-26.2.jar" + q + ")").find(), "a fabricated carrier's bare name");
		assertTrue(mergedOnlyReadThroughTheBase("STAGED.resolve(TestFixtures.BASE_JAR + " + q + ".pins" + q + ")"));
		assertTrue(mergedOnlyReadThroughTheBase("staged(TestFixtures.BASE_JAR, " + q + "META-INF/forbric/native-reference/FABRIC/x" + q + ")"));
		assertTrue(!mergedOnlyReadThroughTheBase("STAGED.resolve(TestFixtures.MERGED_BASE_JAR + " + q + ".pins" + q + ")"));
		assertTrue(!mergedOnlyReadThroughTheBase("staged(TestFixtures.BASE_JAR, owner + " + q + ".class" + q + ")"));
	}
}
