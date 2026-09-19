package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Portable tooling must stay executable without a developer's temporary workspace. */
class CompatToolingCensusTest {
	@TempDir Path temporary;

	@Test void scriptsArePortableAndSyntacticallyValid() throws Exception {
		List<Path> scripts;
		try (var paths = Files.walk(Path.of("run/compat"))) {
			scripts = paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".sh")
					|| p.toString().endsWith(".py")).sorted().toList();
		}
		assertFalse(scripts.isEmpty());
		for (Path script : scripts) {
			String text = Files.readString(script);
			for (String forbidden : List.of("scratch" + "pad", "/Users/" + "jerry",
					"C:\\Users\\" + "Administrator", "26.2" + "-forbric", "Forbric" + "Compat")) {
				assertFalse(text.contains(forbidden), script + " contains machine-local literal " + forbidden);
			}
			var command = script.toString().endsWith(".sh")
					? List.of("bash", "-n", script.toAbsolutePath().toString())
					: List.of("python3", "-c", "import py_compile,sys; py_compile.compile(sys.argv[1],cfile=sys.argv[2],doraise=True)",
						script.toAbsolutePath().toString(), temporary.resolve("syntax.pyc").toString());
			Path log = temporary.resolve("syntax.log");
			Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
			assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), script + " syntax check hung");
			assertEquals(0, process.exitValue(), script + "\n" + Files.readString(log));
		}
	}
}
