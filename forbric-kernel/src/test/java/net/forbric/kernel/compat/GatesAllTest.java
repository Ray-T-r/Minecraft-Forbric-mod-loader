package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
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

	private static void write(Path gates, String name, String body) throws Exception {
		Files.writeString(gates.resolve(name), "#!/usr/bin/env bash\n" + body);
	}
}
