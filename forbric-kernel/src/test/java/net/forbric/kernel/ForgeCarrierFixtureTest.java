/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import net.forbric.kernel.TestFixtures.Fixture;

/**
 * A staged root without the MinecraftForge carrier is the genuine NeoForge base's layout, and what
 * {@code -Pforbric.hideForgeCarrier} hands the tests. On it a test that needs the carrier must skip as
 * {@code forge-carrier}, also under a policy that requires {@code staged} (the prepared CI profile), while a missing
 * base still fails there. Synthetic roots only: this runs on every checkout.
 */
class ForgeCarrierFixtureTest {
	private static final boolean LEGACY_ALL = "1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"));

	@TempDir
	Path tmp;

	private String before;

	@BeforeEach
	void remember() {
		before = System.getProperty(TestFixtures.POLICY);
		System.setProperty(TestFixtures.POLICY, "staged,game-side,mc-libraries,java-25");
	}

	@AfterEach
	void restore() {
		if (before == null) System.clearProperty(TestFixtures.POLICY);
		else System.setProperty(TestFixtures.POLICY, before);
	}

	@Test
	void theCarrierIsKnownByWhereItIsStagedWhateverRootItIsBuiltFrom() {
		assertTrue(TestFixtures.isForgeCarrier(tmp.resolve("run").resolve(TestFixtures.FORGE_CARRIER_JAR)));
		assertTrue(TestFixtures.isForgeCarrier(tmp.resolve("run").resolve(TestFixtures.FORGE_INTEROP_JAR)));
		assertTrue(TestFixtures.isForgeCarrier(tmp.resolve("run/forge-runtime/forbriclive.jar")), "the Forge canary beside it");
		assertFalse(TestFixtures.isForgeCarrier(tmp.resolve("run").resolve(TestFixtures.BASE_JAR)));
		assertFalse(TestFixtures.isForgeCarrier(tmp.resolve("run").resolve(TestFixtures.NEO_CARRIER_JAR)));
		assertFalse(TestFixtures.isForgeCarrier(tmp.resolve("run").resolve(TestFixtures.GENUINE_BASE_JAR)));
		assertFalse(TestFixtures.isForgeCarrier(tmp.resolve("run/forge-patched/patched-mc-forge-26.2.jar")),
				"MinecraftForge's patched game is a merge input, not a carrier");
	}

	@Test
	void aRootWithTheBaseButNoForgeCarrierSkipsAsAForgeNeedUnderTheStagedPolicy() throws Exception {
		Path run = Files.createDirectories(tmp.resolve("run"));
		Path base = file(run.resolve(TestFixtures.BASE_JAR)), neo = file(run.resolve(TestFixtures.NEO_CARRIER_JAR));
		Path forge = run.resolve(TestFixtures.FORGE_CARRIER_JAR);
		if (LEGACY_ALL) return; // that spelling requires forge-carrier too, which is the point of a separate kind

		TestAbortedException files = assertThrows(TestAbortedException.class,
				() -> TestFixtures.requireFiles(Fixture.STAGED, "the base and both carriers", base, forge, neo));
		assertTrue(files.getMessage().startsWith(TestFixtures.tag(Fixture.FORGE_CARRIER)), files.getMessage());
		TestAbortedException file = assertThrows(TestAbortedException.class,
				() -> TestFixtures.requireFile(Fixture.STAGED, forge, "the Forge carrier"));
		assertTrue(file.getMessage().startsWith(TestFixtures.tag(Fixture.FORGE_CARRIER)), file.getMessage());
		TestAbortedException entry = assertThrows(TestAbortedException.class,
				() -> TestFixtures.requireEntry(Fixture.STAGED, forge, "net/minecraftforge/common/ForgeHooks.class"));
		assertTrue(entry.getMessage().startsWith(TestFixtures.tag(Fixture.FORGE_CARRIER)), entry.getMessage());
		assertDoesNotThrow(() -> TestFixtures.requireFile(Fixture.STAGED, neo, "the NeoForge carrier"));
	}

	@Test
	void aMissingBaseIsStillAStagedFailureEvenWhenTheCarrierIsListedFirst() throws Exception {
		Path run = Files.createDirectories(tmp.resolve("run"));
		Path forge = run.resolve(TestFixtures.FORGE_CARRIER_JAR), base = run.resolve(TestFixtures.BASE_JAR);
		AssertionFailedError failed = assertThrows(AssertionFailedError.class,
				() -> TestFixtures.requireFiles(Fixture.STAGED, "the base and the carrier", forge, base));
		assertTrue(failed.getMessage().startsWith(TestFixtures.tag(Fixture.STAGED)) && failed.getMessage().contains(base.toString()),
				failed.getMessage());
		assertThrows(AssertionFailedError.class, () -> TestFixtures.requireFile(Fixture.STAGED, base, "the base"));
	}

	@Test
	void aRunThatRequiresTheCarrierFailsWithoutIt() throws Exception {
		System.setProperty(TestFixtures.POLICY, "staged,forge-carrier");
		Path forge = tmp.resolve("run").resolve(TestFixtures.FORGE_INTEROP_JAR);
		assertThrows(AssertionFailedError.class, () -> TestFixtures.requireFile(Fixture.STAGED, forge, "the Forge carrier"));
	}

	private static Path file(Path path) throws Exception {
		Files.createDirectories(path.getParent());
		return Files.writeString(path, "staged");
	}
}
