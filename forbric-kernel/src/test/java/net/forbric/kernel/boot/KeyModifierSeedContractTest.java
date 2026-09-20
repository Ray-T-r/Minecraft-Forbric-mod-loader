/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * gate-m9 re-seeds a MinecraftForge modifier binding into its {@code options.txt} on every run, and that step is
 * load-bearing: the client SAVES defaults over the file after failing to read it, so the very failure the gate
 * asserts against erases its own evidence. This fixture had three of JEI's modifier bindings and lost all three
 * exactly that way, which is how the failure stayed invisible.
 *
 * <p>The gate's own seeding block is executed here, not a copy of it.
 */
class KeyModifierSeedContractTest {
	@TempDir Path temporary;

	@Test
	void theGatesOwnSeedGivesTheFirstBindingAModifierAndIsIdempotent() throws Exception {
		Path options = temporary.resolve("options.txt");
		Files.writeString(options, String.join("\n", List.of(
				"version:4903", "fov:0.0", "key_key.attack:key.mouse.left", "key_key.use:key.mouse.right")) + "\n",
				StandardCharsets.UTF_8);

		run(options);
		List<String> once = Files.readAllLines(options, StandardCharsets.UTF_8);
		assertEquals("key_key.attack:key.mouse.left:CONTROL_OR_COMMAND", once.get(2),
				"the first key binding must come back with MinecraftForge's modifier suffix");
		assertEquals("key_key.use:key.mouse.right", once.get(3), "and only the first one");
		assertEquals("version:4903", once.get(0), "non-key lines are untouched");

		run(options);
		assertEquals(once, Files.readAllLines(options, StandardCharsets.UTF_8),
				"a second run must not stack a second suffix");
	}

	@Test
	void anOptionsFileWithNoKeyBindingsFailsLoudly() throws Exception {
		Path options = temporary.resolve("options.txt");
		Files.writeString(options, "version:4903\nfov:0.0\n", StandardCharsets.UTF_8);
		Process process = start(options);
		assertNotEquals(0, process.waitFor(),
				"a fixture that cannot carry the binding must stop the gate, not let it pass vacuously");
	}

	private static void run(Path options) throws Exception {
		Process process = start(options);
		assertEquals(0, process.waitFor(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	private static Process start(Path options) throws Exception {
		String script = Files.readString(Path.of("run/gate-m9-client.sh"));
		int begin = script.indexOf("# M9_KEY_MODIFIER_SEED_BEGIN");
		int end = script.indexOf("# M9_KEY_MODIFIER_SEED_END", begin);
		assertTrue(begin >= 0 && end > begin, "missing executable key-modifier seeding contract in gate-m9");
		String body = script.substring(begin, end).replace("$RUNDIR/options.txt", options.toString());
		ProcessBuilder builder = new ProcessBuilder("bash", "-c", body);
		builder.redirectErrorStream(true);
		return builder.start();
	}
}
