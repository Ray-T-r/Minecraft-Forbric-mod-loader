package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Actually executes the shipped probes, without importing a parallel test implementation. */
final class CompatProbeProcess {
	private CompatProbeProcess() { }

	record Result(int exitCode, String output) { }

	static Result run(Path temporary, String interpreter, String script, String... arguments) throws Exception {
		return run(temporary, Map.of(), interpreter, script, arguments);
	}

	static Result run(Path temporary, Map<String, String> environment,
			String interpreter, String script, String... arguments) throws Exception {
		List<String> command = new ArrayList<>(List.of(interpreter,
				Path.of("run/compat", script).toAbsolutePath().toString()));
		command.addAll(List.of(arguments));
		Path output = Files.createTempFile(temporary, "probe-", ".log");
		ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
				.redirectOutput(output.toFile());
		builder.environment().remove("ASSERT_EXTRA");
		builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
		builder.environment().putAll(environment);
		Process process = builder.start();
		boolean finished = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
		if (!finished) process.destroyForcibly().waitFor();
		String text = Files.readString(output);
		assertTrue(finished, "Probe timed out: " + command + "\n" + text);
		return new Result(process.exitValue(), text);
	}
}
