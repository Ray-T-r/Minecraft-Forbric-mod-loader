package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatesAllTest {
	@TempDir Path temporary;

	@Test void listsTheActualGateGlobInNumericalOrder() throws Exception {
		var result = CompatProbeProcess.run(temporary, "bash", "gates-all.sh", "--list");
		assertEquals(0, result.exitCode(), result.output());
		try (var paths = Files.list(Path.of("run"))) {
			Set<String> names = paths.map(p -> p.getFileName().toString())
					.filter(n -> n.startsWith("gate-m") && n.endsWith(".sh")).collect(Collectors.toSet());
			assertEquals(names, result.output().lines().collect(Collectors.toSet()));
		}
		var lines = result.output().lines().toList();
		assertTrue(lines.indexOf("gate-m2b.sh") < lines.indexOf("gate-m10-overlay.sh"));
	}

	@Test void runsEveryGateAndDistinguishesExpectedFailuresFromRegressions() throws Exception {
		Path gates = Files.createDirectory(temporary.resolve("gates"));
		Path output = temporary.resolve("results");
		write(gates, "gate-m1.sh", "[ \"$GATE_PORT\" = 25599 ]\n");
		write(gates, "gate-m2.sh", "# EXPECTED: RED until repair\necho '[kernel] EXPECTED-RED missing Forge registration'\nexit 2\n");
		write(gates, "gate-m3.sh", "# EXPECTED: RED until repair\necho 'Failed to start'\nexit 1\n");
		write(gates, "gate-m4.sh", "exit 0\n");
		write(gates, "gate-m10.sh", "exit 0\n");
		var env = Map.of("FORBRIC_GATE_DIR", gates.toString(), "FORBRIC_GATE_RESULTS", output.toString(), "GATE_PORT", "25599");
		var red = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--skip", "gate-m4.sh");
		assertEquals(1, red.exitCode(), red.output());
		assertTrue(red.output().contains("gate-m2.sh EXPECTED_RED"));
		assertTrue(red.output().contains("gate-m3.sh RED"));
		assertTrue(red.output().contains("gate-m4.sh SKIP (explicit --skip)"));
		assertTrue(red.output().contains("gate-m10.sh GREEN"), "Failures must not prevent later gates running");
		assertEquals(red.output(), Files.readString(output.resolve("summary.txt")));
		assertFalse(Files.exists(output.resolve("gate-m4.sh.log")));
		var green = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--skip", "gate-m3.sh");
		assertEquals(0, green.exitCode(), green.output());
		var unknown = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--skip", "gate-m99.sh");
		assertEquals(2, unknown.exitCode(), unknown.output());
	}

	/**
	 * The lenient sweep above exits 0 with gate-m3 skipped and gate-m2 EXPECTED_RED. A release run is an acceptance:
	 * a gate that was not run, or is still red by declaration, fails it -- and the line says which and why.
	 */
	@Test void aReleaseRunFailsOnASkippedOrExpectedRedGate() throws Exception {
		Path gates = Files.createDirectory(temporary.resolve("release-gates"));
		Path output = temporary.resolve("release-results");
		write(gates, "gate-m1.sh", "exit 0\n");
		write(gates, "gate-m2.sh", "# EXPECTED: RED until repair\necho '[kernel] EXPECTED-RED missing Forge registration'\nexit 2\n");
		write(gates, "gate-m3.sh", "exit 0\n");
		var env = Map.of("FORBRIC_GATE_DIR", gates.toString(), "FORBRIC_GATE_RESULTS", output.toString());
		var lenient = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--skip", "gate-m3.sh");
		assertEquals(0, lenient.exitCode(), lenient.output());

		var skipped = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--release", "--skip", "gate-m3.sh");
		assertEquals(1, skipped.exitCode(), skipped.output());
		assertTrue(skipped.output().contains("RESULT gate-m3.sh SKIP (explicit --skip; not run, so the release run fails)"),
				skipped.output());
		assertTrue(skipped.output().contains("RESULT gate-m2.sh EXPECTED_RED (exit=2; still red, so the release run fails)"),
				skipped.output());
		assertEquals(skipped.output(), Files.readString(output.resolve("summary.txt")));

		write(gates, "gate-m2.sh", "exit 0\n");
		var skipOnly = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--release", "--skip", "gate-m3.sh");
		assertEquals(1, skipOnly.exitCode(), "a skip alone fails a release run: " + skipOnly.output());
		var complete = CompatProbeProcess.run(temporary, env, "bash", "gates-all.sh", "--release");
		assertEquals(0, complete.exitCode(), complete.output());
		assertEquals(List.of("RESULT gate-m1.sh GREEN (exit=0)", "RESULT gate-m2.sh GREEN (exit=0)",
				"RESULT gate-m3.sh GREEN (exit=0)"), complete.output().lines().toList());
	}

	@Test void theDefaultRunExecutesEveryGateWithoutAnySkipArguments() throws Exception {
		Path gates = Files.createDirectory(temporary.resolve("default-gates"));
		write(gates, "gate-m1.sh", "exit 0\n");
		write(gates, "gate-m10.sh", "exit 0\n");
		var result = CompatProbeProcess.run(temporary, Map.of("FORBRIC_GATE_DIR", gates.toString(),
				"FORBRIC_GATE_RESULTS", temporary.resolve("default-results").toString()), "bash", "gates-all.sh");
		assertEquals(0, result.exitCode(), result.output());
		assertEquals(List.of("RESULT gate-m1.sh GREEN (exit=0)", "RESULT gate-m10.sh GREEN (exit=0)"),
				result.output().lines().toList());
	}

	private static void write(Path gates, String name, String body) throws Exception {
		Files.writeString(gates.resolve(name), "#!/usr/bin/env bash\n" + body);
	}
}
