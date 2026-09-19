package net.forbric.kernel.compat;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CompatProtocolTest {
	@Test void protocolNamesEveryShippedScript() throws Exception {
		Path root = Path.of("run/compat");
		String protocol = Files.readString(root.resolve("PROTOCOL.md"));
		try (var scripts = Files.walk(root)) {
			for (Path script : scripts.filter(Files::isRegularFile)
					.filter(p -> p.toString().endsWith(".sh") || p.toString().endsWith(".py")).toList()) {
				assertTrue(protocol.contains(root.relativize(script).toString()),
						"Missing operational documentation for " + script);
			}
		}
	}
}
