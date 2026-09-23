package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transfer engine suite holds the only coverage of rollback failures, native nested rollback and re-entry
 * across the two real transaction engines, and no gate ran it: M0 runs `test`, M39 only compiled the classes, and a
 * missing game side made Gradle skip it green. M39 now runs it as a required step. This executes that exact section
 * of the gate with Gradle replaced by a recorder, so the verdict is the gate's own code, not a copy of it.
 */
class TransferGateContractTest {
	private static final Path GATE = Path.of("run/gate-m39-transfer-core.sh");
	@TempDir Path temporary;

	private record Outcome(int exit, String output, List<String> gradleArgs) { }

	private static String section() throws Exception {
		String script = Files.readString(GATE);
		int begin = script.indexOf("# TRANSFER_SUITE_BEGIN"), end = script.indexOf("# TRANSFER_SUITE_END");
		assertTrue(begin >= 0 && end > begin, "gate-m39 lost its required transfer-suite step");
		return script.substring(begin, end);
	}

	/** Runs the gate's section against a fake kernel whose gradlew writes {@code reports} and exits {@code rc}. */
	private Outcome run(List<String> declared, String reports, int rc) throws Exception {
		Path kernel = temporary.resolve("kernel with spaces");
		Path sources = Files.createDirectories(kernel.resolve("src/transferTest/java/example"));
		StringBuilder source = new StringBuilder("package example;\nclass SuiteTest {\n");
		for (String name : declared) source.append("\t@Test void ").append(name).append("() throws Exception { }\n");
		Files.writeString(sources.resolve("SuiteTest.java"), source.append("}\n"));
		Path canned = Files.createDirectories(temporary.resolve("canned"));
		if (reports != null) Files.writeString(canned.resolve("TEST-example.SuiteTest.xml"), reports);
		Path args = temporary.resolve("gradle-args.txt");
		Path gradlew = kernel.resolve("gradlew");
		Files.writeString(gradlew, """
				#!/usr/bin/env bash
				printf '%s\\n' "$@" > "$ARGS"
				rm -rf "$KERNEL/build/test-results/transferTest"; mkdir -p "$KERNEL/build/test-results/transferTest"
				cp "$CANNED"/*.xml "$KERNEL/build/test-results/transferTest/" 2>/dev/null || true
				exit "$RC"
				""");
		assertTrue(gradlew.toFile().setExecutable(true));
		ProcessBuilder process = new ProcessBuilder("bash", "-c", "set -euo pipefail\nFAIL=0\n" + section() + "\necho \"FAIL=$FAIL\"\n");
		process.environment().put("KERNEL", kernel.toString());
		process.environment().put("BUILD", Files.createDirectories(kernel.resolve("build")).toString());
		process.environment().put("ARGS", args.toString());
		process.environment().put("CANNED", canned.toString());
		process.environment().put("RC", Integer.toString(rc));
		process.redirectErrorStream(true);
		Process started = process.start();
		assertTrue(started.waitFor(60, TimeUnit.SECONDS), "the gate section hung");
		String output = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return new Outcome(started.exitValue(), output, Files.isRegularFile(args) ? Files.readAllLines(args) : List.of());
	}
	private static String report(String... cases) {
		StringBuilder xml = new StringBuilder("<testsuite name=\"example.SuiteTest\" tests=\"" + cases.length + "\">");
		for (String testCase : cases) xml.append(testCase);
		return xml.append("</testsuite>").toString();
	}
	private static String passed(String name) { return "<testcase name=\"" + name + "()\" classname=\"example.SuiteTest\"/>"; }
	private static String skipped(String name) { return "<testcase name=\"" + name + "()\" classname=\"example.SuiteTest\"><skipped/></testcase>"; }

	@Test void theGateRunsTheSuiteAsRequiredAndPassesOnlyWhenEveryDeclaredTestRan() throws Exception {
		Outcome green = run(List.of("first", "second"), report(passed("first"), passed("second")), 0);
		assertEquals(0, green.exit(), green.output());
		assertTrue(green.output().contains("FAIL=0") && green.output().contains("PASS transfer engine suite"), green.output());
		assertTrue(green.gradleArgs().containsAll(List.of("--offline", "-Pforbric.requireTransfer=true", "cleanTransferTest", "transferTest")),
				green.gradleArgs().toString());
	}
	@Test void aSkippedSuiteASkippedTestAMissingTestOrAFailedBuildIsRed() throws Exception {
		for (Outcome red : List.of(
				run(List.of("first"), null, 0),
				run(List.of("first", "second"), report(passed("first"), skipped("second")), 0),
				run(List.of("first", "second"), report(passed("first")), 0),
				run(List.of("first"), report(passed("first")), 1))) {
			// Red, but the gate itself carries on (set -e must not abort it): its later steps still report.
			assertEquals(0, red.exit(), red.output());
			assertTrue(red.output().contains("FAIL=1") && red.output().contains("FAIL transfer engine suite"), red.output());
		}
	}
	@Test void theStepRunsBeforeTheGameAndTheScriptParses() throws Exception {
		String script = Files.readString(GATE);
		assertTrue(script.indexOf("# TRANSFER_SUITE_BEGIN") < script.indexOf("evidence.py\" run"), "the suite must not depend on a game run");
		Process syntax = new ProcessBuilder("bash", "-n", GATE.toString()).redirectErrorStream(true).start();
		assertTrue(syntax.waitFor(30, TimeUnit.SECONDS));
		assertEquals(0, syntax.exitValue(), new String(syntax.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}
}
