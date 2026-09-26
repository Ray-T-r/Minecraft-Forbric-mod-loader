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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The gates' "no genuine Fabric Loader" assertions must still catch the genuine loader and must no longer catch the
 * kernel's own {@code net.fabricmc.loader.impl.FabricLoaderImpl}.
 *
 * <p>The kernel ships that class now: SuperMartijn642's Core Lib links against it. The four gates matched the bare
 * name, so any logged stack trace passing through the facade -- a mod's entrypoint throwing inside
 * {@code getEntrypointContainers} -- turned them red with "genuine Fabric Loader present" for a reason that had
 * nothing to do with it. Each pattern is run through {@code grep -E}, as {@code check_absent} runs it.
 */
class GateGenuineFabricLoaderContractTest {
	private static final List<String> GATES =
			List.of("run/gate-m2.sh", "run/gate-m2b.sh", "run/gate-m4.sh", "run/gate-m4-canary.sh");
	private static final Pattern CHECK_ABSENT = Pattern.compile("^check_absent \"([^\"]*)\"\\s+\"([^\"]*)\"");

	/** The kernel's facade in a stack trace: must not count. */
	private static final String FACADE =
			"\tat net.fabricmc.loader.impl.FabricLoaderImpl.getEntrypointContainers(FabricLoaderImpl.java:97)";
	/** Knot and the genuine loader's own lifecycle: must count. */
	private static final List<String> GENUINE = List.of(
			"\tat net.fabricmc.loader.impl.FabricLoaderImpl.setup(FabricLoaderImpl.java:198)",
			"\tat net.fabricmc.loader.impl.FabricLoaderImpl.load(FabricLoaderImpl.java:186)",
			"\tat net.fabricmc.loader.impl.FabricLoaderImpl.freeze(FabricLoaderImpl.java:283)");
	private static final List<String> KNOT = List.of(
			"\tat net.fabricmc.loader.impl.launch.knot.Knot.launch(Knot.java:74)",
			"java.lang.ClassNotFoundException: x (KnotClassLoader)");

	@TempDir
	Path temporary;

	@Test
	void everyGenuineLoaderAssertionIgnoresTheFacadeAndStillSeesTheLoader() throws Exception {
		int checked = 0;
		for (String gate : GATES) {
			for (String[] check : genuineChecks(gate)) {
				checked++;
				String where = gate + ": " + check[0];
				assertFalse(matches(check[1], FACADE), where + " counts the kernel's own FabricLoaderImpl");
				boolean namesImpl = check[1].contains("FabricLoaderImpl");
				boolean namesKnot = check[1].contains("Knot");
				assertTrue(namesImpl || namesKnot, where + " no longer looks for the genuine loader at all");
				if (namesImpl) {
					for (String line : GENUINE) assertTrue(matches(check[1], line), where + " misses " + line.strip());
				}
				if (namesKnot) {
					for (String line : KNOT) assertTrue(matches(check[1], line), where + " misses " + line.strip());
				}
			}
		}
		// m2 has two (the loader, then Knot on its own line); the other three one each.
		assertEquals(5, checked, "a gate's genuine-loader assertion was renamed or removed");
	}

	/** {@code {name, pattern}} of each check_absent in {@code gate} that is about the genuine Fabric Loader. */
	private static List<String[]> genuineChecks(String gate) throws Exception {
		List<String[]> out = new ArrayList<>();
		for (String line : Files.readAllLines(Path.of(gate), StandardCharsets.UTF_8)) {
			Matcher m = CHECK_ABSENT.matcher(line);
			if (!m.find()) continue;
			if (m.group(1).contains("genuine Fabric") || m.group(1).contains("FabricLoaderImpl")
					|| m.group(1).contains("Knot")) {
				out.add(new String[] {m.group(1), m.group(2)});
			}
		}
		return out;
	}

	/** Whether {@code grep -acE pattern} counts {@code line}, exactly as {@code check_absent} asks. */
	private boolean matches(String pattern, String line) throws Exception {
		Path log = Files.writeString(temporary.resolve("log.txt"), line + "\n");
		Process grep = new ProcessBuilder("grep", "-acE", pattern, log.toString()).redirectErrorStream(true).start();
		assertTrue(grep.waitFor(15, TimeUnit.SECONDS), "grep timed out");
		String count = new String(grep.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
		return !"0".equals(count);
	}
}
