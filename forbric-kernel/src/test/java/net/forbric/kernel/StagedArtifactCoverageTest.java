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

package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * If the staged game artifacts are here, the tests that read them have to actually be reading them.
 *
 * <h2>Why a green suite has not meant much</h2>
 *
 * <p>About a third of the assertions in this project are about real bytecode — whether a carrier still declares
 * a method the kernel splices at, whether a vanilla field's descriptor survived the merge, whether a repair's
 * anchor is still there. Every one of them is guarded by an {@code assumeTrue} on a staged jar, because those
 * jars embed Mojang/Forge/NeoForge code and are built on the user's own machine rather than checked in.
 *
 * <p>JUnit reports an assumption as SKIPPED, not failed. So on a machine without the artifacts the suite says
 * green while a third of it never ran, and the CI job — which deliberately has no artifacts, to prove a clean
 * clone still builds — has never executed a single one of those assertions. That is the right call for CI and a
 * trap everywhere else: the one run that matters is the one on the machine that HAS the jars, and nothing was
 * checking that it used them.
 *
 * <p>So this asserts the conditional: artifacts absent, say so and pass; artifacts present, and a floor of
 * real-bytecode tests must have executed. It is the cheapest thing that makes "910 tests, 0 failures" mean what
 * a reader takes it to mean.
 */
class StagedArtifactCoverageTest {

	/**
	 * Where the staged artifacts live: {@link TestFixtures#stagedRoot()}, the root Gradle hands every test task.
	 *
	 * <p>This used to resolve {@code FORBRIC_OLD} itself, which was right when every other test hardcoded the
	 * relative path and a worktree skipped them all; it now has to agree with the root the tests it counts read,
	 * including the Forge-less one {@code -Pforbric.hideForgeCarrier} hands them.
	 */
	static Path stagedRoot() {
		return TestFixtures.stagedRoot().normalize();
	}

	/**
	 * The artifacts whose presence means the real-bytecode tests must have run: the base and the NeoForge carrier,
	 * which every staged root has. The MinecraftForge carrier is reported but does not decide it: a root without it
	 * (the genuine base's layout) still runs every test that does not need MinecraftForge.
	 */
	private static List<Path> artifacts() {
		return List.of(TestFixtures.stagedBase(), TestFixtures.neoCarrier());
	}

	@Test
	void whenTheStagedArtifactsArePresentTheTestsThatReadThemHaveRun() throws Exception {
		List<Path> present = new ArrayList<>();
		List<Path> missing = new ArrayList<>();
		for (Path artifact : artifacts()) {
			(Files.isRegularFile(artifact) ? present : missing).add(artifact);
		}

		if (present.isEmpty()) {
			// Not a skip. Passing loudly is the point: the message is what tells a reader that the suite they are
			// looking at did not check any of the bytecode claims, which a SKIPPED line does not say.
			System.out.println("[staged] none of the staged artifacts are here (" + stagedRoot() + ") — the "
					+ "real-bytecode assertions did not run in this suite. That is expected in CI and is not "
					+ "expected on a machine that can run the gates.");
			return;
		}

		// Every test class that reads one. Counted from source rather than from a list someone maintains, because
		// a list is the thing that goes stale while looking maintained.
		Path tests = Path.of(System.getProperty("user.dir"), "src", "test", "java").normalize();
		assertTrue(Files.isDirectory(tests), "test sources not where this expects them: " + tests);

		List<String> readers = new ArrayList<>();
		try (var walk = Files.walk(tests)) {
			for (Path file : walk.filter(f -> f.toString().endsWith("Test.java")).toList()) {
				String text = Files.readString(file);
				// TestFixtures is the one place that resolves the staged root now; a test reading the staged
				// artifacts says so with stagedRoot() or Fixture.STAGED. The two spellings it replaced still count.
				if (text.contains("stagedRoot()") || text.contains("Fixture.STAGED")
						|| text.contains("stagedBase()") || text.contains("genuineBase()") || text.contains("BASE_JAR")
						|| text.contains("forbric-loader") || text.contains("FORBRIC_OLD")) {
					readers.add(file.getFileName().toString());
				}
			}
		}

		// A floor, not an exact count: new bytecode tests should not have to come here to be counted, and losing
		// most of them should not be able to pass. It was 56 files when this was written.
		assertTrue(readers.size() >= 40,
				"only " + readers.size() + " test class(es) read the staged artifacts, which is far fewer than "
						+ "this project has. Either they were deleted or they stopped resolving the staged path — "
						+ "and either way the bytecode claims are no longer being checked: " + readers);

		if (!missing.isEmpty()) {
			System.out.println("[staged] present: " + present.size() + "/" + artifacts().size() + ", missing: " + missing
					+ " — the assertions that read the missing one(s) skipped.");
		}
		if (!TestFixtures.forgeCarrierStaged()) {
			System.out.println("[staged] no MinecraftForge carrier ("
					+ (TestFixtures.forgeCarrierHidden() ? "hidden by -Pforbric.hideForgeCarrier" : "not staged")
					+ ") — the assertions that need it skipped as [fixture:forge-carrier].");
		}
	}
}
