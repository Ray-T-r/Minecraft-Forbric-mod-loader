/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every test class that can change DuplicateLambdaPruneInjector's drop record puts it back:
 * {@code @ExtendWith(DroppedLambdaRecordRestore.class)}.
 *
 * <p>The record is process-wide, so a test that leaves it changed decides what a later test class in the same worker
 * sees, and which class that is depends on the order Gradle picked. Saving and restoring by hand was tried and missed
 * most of the classes that needed it; this holds every one, including those written later.
 *
 * <p>What counts as changing it, in the source with comments and literals blanked: a call to a record seam
 * ({@code recordDroppedForTest}, {@code forgetDroppedForTest}, {@code restoreDroppedForTest}), constructing the pruner
 * (its transform records every body it drops), and running KernelBoot's pre-Mixin chain, which constructs it
 * reflectively ({@code ChainReferences.preMixinChain}, {@code ForgeTransferShapeAuditChainStagedTest.chain}, and the file
 * that declares the latter, known by its {@code chain(List<Path> jars, ...)} signature; another test's own
 * {@code chain()} of its own transformers is not that). The weave harness builds the same chain in a child JVM, which shares nothing with this one,
 * so it needs no restore. Only files with a test method are held: a helper's caller is.
 */
class DroppedLambdaRecordRestoreGuardTest {
	private static final Path PROJECT = Path.of(System.getProperty("user.dir"));
	private static final List<String> ROOTS = List.of("src/test/java", "src/transferTest/java");
	/** The extension itself restores; it is what the others carry. */
	private static final String EXTENSION = "src/test/java/net/forbric/kernel/DroppedLambdaRecordRestore.java";

	private static final Map<String, Pattern> FILLS = Map.of(
			"a record seam", Pattern.compile("\\b(?:recordDroppedForTest|forgetDroppedForTest|restoreDroppedForTest)\\s*\\("),
			"constructs the pruner", Pattern.compile("\\bnew\\s+(?:[\\w.]+\\.)?DuplicateLambdaPruneInjector\\s*\\("),
			"runs KernelBoot's pre-Mixin chain", Pattern.compile(
					"\\b(?:preMixinChain|ForgeTransferShapeAuditChainStagedTest\\s*\\.\\s*chain)\\s*\\(|\\bstatic\\s+TransformChain\\s+chain\\s*\\(\\s*List\\s*<\\s*Path\\s*>"));
	private static final Pattern TEST = Pattern.compile("@\\s*(?:[\\w.]+\\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
	private static final Pattern RESTORES = Pattern.compile(
			"@\\s*(?:[\\w.]+\\.)?ExtendWith\\s*\\([^)]*?\\b(?:[\\w.]+\\.)?DroppedLambdaRecordRestore\\s*\\.\\s*class");

	@Test
	void everyTestClassThatChangesTheDropRecordRestoresIt() throws IOException {
		List<String> unrestored = new ArrayList<>();
		fillers().forEach((file, where) -> {
			if (!where.restores()) unrestored.add(file + " (" + where.what() + ")");
		});
		assertTrue(unrestored.isEmpty(), "these test classes change DuplicateLambdaPruneInjector's process-wide drop record "
				+ "and leave it changed for the next class in the worker; annotate each with "
				+ "@ExtendWith(net.forbric.kernel.DroppedLambdaRecordRestore.class):\n" + String.join("\n", unrestored));
	}

	@Test
	void theScanSeesTheClassesThatFillTheRecord() throws IOException {
		// A wrong root or a broken pattern would find no filler and pass the test above over nothing.
		Map<String, Where> fillers = fillers();
		for (String known : List.of("net/forbric/kernel/mixin/MixinOverloadPinTest.java", "net/forbric/kernel/mixin/MixinHandlerShimTest.java",
				"net/forbric/kernel/transform/DuplicateLambdaPruneInjectorExecutionTest.java",
				"net/forbric/kernel/transform/ForgeTransferShapeAuditChainStagedTest.java")) {
			assertTrue(fillers.containsKey("src/test/java/" + known), known + " fills the record and was not found: " + fillers.keySet());
		}
	}

	@Test
	void everySpellingIsCaughtAndNothingElse() {
		for (String fills : List.of(
				"DuplicateLambdaPruneInjector.recordDroppedForTest(TARGET, NAME, DESC);",
				"net.forbric.kernel.transform.DuplicateLambdaPruneInjector.forgetDroppedForTest();",
				"restoreDroppedForTest (saved);",
				"byte[] out = new DuplicateLambdaPruneInjector().transform(name, bytes, null);",
				"new net.forbric.kernel.transform.DuplicateLambdaPruneInjector ()",
				"TransformChain chain = ChainReferences.preMixinChain(jars, registered, skipped);",
				"ForgeTransferShapeAuditChainStagedTest.chain(jars, a, b)",
				"static TransformChain chain(List<Path> jars, List<String> registered, List<String> skipped)")) {
			assertTrue(!fills(fills).isEmpty(), "not caught: " + fills);
		}
		for (String clean : List.of(
				"// recordDroppedForTest(TARGET, NAME, DESC) used to be here",
				"/** {@link DuplicateLambdaPruneInjector#forgetDroppedForTest} */",
				"String s = \"new DuplicateLambdaPruneInjector()\";",
				"DuplicateLambdaPruneInjector.droppedDescriptors(owner, name)",
				"DuplicateLambdaPruneInjector.droppedForTest()",
				"List.of(DuplicateLambdaPruneInjector.class)",
				"chain(game.paths())",
				// Another test's own chain, of its own transformers: no pruner in it.
				"private static TransformChain chain() {")) {
			assertEquals("", fills(clean), "a false alarm: " + clean);
		}
		for (String restores : List.of(
				"@ExtendWith(DroppedLambdaRecordRestore.class) class T {}",
				"@org.junit.jupiter.api.extension.ExtendWith(net.forbric.kernel.DroppedLambdaRecordRestore.class)",
				"@ExtendWith({ Other.class, DroppedLambdaRecordRestore.class })")) {
			assertTrue(RESTORES.matcher(NoRawAssumptionsTest.codeOnly(restores)).find(), "not seen as restoring: " + restores);
		}
		for (String not : List.of("// @ExtendWith(DroppedLambdaRecordRestore.class)", "@ExtendWith(Other.class) DroppedLambdaRecordRestore x;")) {
			assertTrue(!RESTORES.matcher(NoRawAssumptionsTest.codeOnly(not)).find(), "seen as restoring: " + not);
		}
	}

	/** How a file changes the record, and whether it restores it. */
	record Where(String what, boolean restores) {
	}

	/** Repo-relative path of every test file that changes the record. */
	private static Map<String, Where> fillers() throws IOException {
		Map<String, Where> found = new TreeMap<>();
		for (Path source : sources()) {
			String file = PROJECT.relativize(source).toString().replace('\\', '/');
			if (file.equals(EXTENSION)) continue;
			String code = NoRawAssumptionsTest.codeOnly(Files.readString(source, StandardCharsets.UTF_8));
			if (!TEST.matcher(code).find()) continue;
			String what = fillsCode(code);
			if (!what.isEmpty()) found.put(file, new Where(what, RESTORES.matcher(code).find()));
		}
		assertTrue(found.size() > 0 && sources().size() > 300, "the scan found " + sources().size() + " sources under " + ROOTS);
		return found;
	}

	/** "line N: what" for the first change to the record in {@code java}, or "" for none. */
	static String fills(String java) {
		return fillsCode(NoRawAssumptionsTest.codeOnly(java));
	}

	private static String fillsCode(String code) {
		int first = Integer.MAX_VALUE;
		String what = "";
		for (Map.Entry<String, Pattern> rule : FILLS.entrySet()) {
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
