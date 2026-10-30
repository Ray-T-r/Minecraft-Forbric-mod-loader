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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
		// That spelling requires forge-carrier too (the point of a separate kind), so the skip this checks cannot happen.
		assumeFalse(LEGACY_ALL, "FORBRIC_COMPAT_FIXTURES_REQUIRED=1 requires forge-carrier, so a missing carrier fails there");

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

	/**
	 * A policy that requires {@code forge-carrier} and not {@code staged}: the missing carrier fails, and only because
	 * every way of asking for it is routed to {@link Fixture#FORGE_CARRIER} (the caller names {@code staged}, which this
	 * run would let skip). The missing base under the same policy is still a {@code staged} skip, so the routing is by
	 * file, not a blanket upgrade.
	 */
	@Test
	void aRunThatRequiresTheCarrierFailsWithoutIt() throws Exception {
		assumeFalse(LEGACY_ALL, "FORBRIC_COMPAT_FIXTURES_REQUIRED=1 requires staged too, so the routing cannot be told apart");
		System.setProperty(TestFixtures.POLICY, "forge-carrier");
		Path run = tmp.resolve("run");
		Path forge = run.resolve(TestFixtures.FORGE_INTEROP_JAR), base = run.resolve(TestFixtures.BASE_JAR);
		String carrier = TestFixtures.tag(Fixture.FORGE_CARRIER);
		AssertionFailedError file = assertThrows(AssertionFailedError.class,
				() -> TestFixtures.requireFile(Fixture.STAGED, forge, "the Forge carrier"));
		assertTrue(file.getMessage().startsWith(carrier), file.getMessage());
		AssertionFailedError files = assertThrows(AssertionFailedError.class,
				() -> TestFixtures.requireFiles(Fixture.STAGED, "the Forge carrier", run.resolve(TestFixtures.FORGE_CARRIER_JAR)));
		assertTrue(files.getMessage().startsWith(carrier), files.getMessage());
		AssertionFailedError entry = assertThrows(AssertionFailedError.class,
				() -> TestFixtures.requireEntry(Fixture.STAGED, forge, "net/minecraftforge/common/ForgeHooks.class"));
		assertTrue(entry.getMessage().startsWith(carrier), entry.getMessage());
		AssertionFailedError asked = assertThrows(AssertionFailedError.class,
				() -> TestFixtures.require(Fixture.FORGE_CARRIER, false, "asked for by name"));
		assertTrue(asked.getMessage().startsWith(carrier), asked.getMessage());

		TestAbortedException missingBase = assertThrows(TestAbortedException.class,
				() -> TestFixtures.requireFile(Fixture.STAGED, base, "the base"));
		assertTrue(missingBase.getMessage().startsWith(TestFixtures.tag(Fixture.STAGED)), missingBase.getMessage());
	}

	/**
	 * Where only the merged base's own classes link MinecraftForge, a NeoForge or Fabric test that reads them needs the
	 * carrier there and nowhere else: on a base whose Entity names no {@code net/minecraftforge/} class (the genuine
	 * NeoForge jar) the same test runs without one, instead of skipping for good once the carrier is gone.
	 */
	@Test
	void onlyABaseThatLinksMinecraftForgeNeedsTheCarrierForItsOwnClasses() throws Exception {
		assumeFalse(LEGACY_ALL, "FORBRIC_COMPAT_FIXTURES_REQUIRED=1 requires forge-carrier, so the missing carrier fails there");
		String handed = System.getProperty("forbric.stagedRoot");
		try {
			Path merged = Files.createDirectories(tmp.resolve("merged/run")), genuine = Files.createDirectories(tmp.resolve("genuine/run"));
			base(merged.resolve(TestFixtures.BASE_JAR), "Lnet/minecraftforge/common/extensions/IForgeEntity;");
			base(genuine.resolve(TestFixtures.BASE_JAR), "Lnet/neoforged/neoforge/common/extensions/IEntityExtension;");

			System.setProperty("forbric.stagedRoot", merged.toString());
			assertTrue(TestFixtures.baseNamesMinecraftForge());
			TestAbortedException skipped = assertThrows(TestAbortedException.class,
					() -> TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("a NeoForge default reached through Entity"));
			assertTrue(skipped.getMessage().startsWith(TestFixtures.tag(Fixture.FORGE_CARRIER)), skipped.getMessage());
			file(merged.resolve(TestFixtures.FORGE_INTEROP_JAR));
			assertDoesNotThrow(() -> TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("with the carrier staged"));

			System.setProperty("forbric.stagedRoot", genuine.toString());
			assertFalse(TestFixtures.baseNamesMinecraftForge());
			assertDoesNotThrow(() -> TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("a NeoForge default reached through Entity"));
		} finally {
			if (handed == null) System.clearProperty("forbric.stagedRoot");
			else System.setProperty("forbric.stagedRoot", handed);
		}
	}

	/** A base jar whose Entity class names {@code descriptor} (its bytes need only carry the name, as a class's pool does). */
	private static void base(Path jar, String descriptor) throws Exception {
		Files.createDirectories(jar.getParent());
		try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
			out.putNextEntry(new java.util.zip.ZipEntry("net/minecraft/world/entity/Entity.class"));
			out.write(("\u00ca\u00fe\u00ba\u00be net/minecraft/world/entity/Entity " + descriptor).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
			out.closeEntry();
		}
	}

	private static Path file(Path path) throws Exception {
		Files.createDirectories(path.getParent());
		return Files.writeString(path, "staged");
	}
}
