package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The staged jars the build compiles against and the ones the bytecode tests read are one set. The tests resolve
 * FORBRIC_OLD + "/run" themselves, so the build must hand them the root it used: -Pforbric.stagedRoot once moved
 * the compile and the declared inputs while every test kept reading the old base.
 */
class StagedRootHandoffTest {
	@Test
	void theTestsReadTheStagedRootTheBuildCompiledAgainst() {
		String compiled = System.getProperty("forbric.stagedRoot");
		assertNotNull(compiled, "the build hands its staged root to the tests");
		String old = System.getenv("FORBRIC_OLD");
		assertNotNull(old, "FORBRIC_OLD is what every staged test resolves its jars through");
		assertEquals(Path.of(compiled).toAbsolutePath().normalize(),
				Path.of(old, "run").toAbsolutePath().normalize());
	}
}
