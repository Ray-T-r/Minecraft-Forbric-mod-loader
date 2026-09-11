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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.UnifiedDependency;
import net.forbric.api.UnifiedDependency.Ordering;
import net.forbric.api.Side;
import net.forbric.api.UnifiedDependency.SideScope;

/**
 * Covers the cross-ecosystem dependency audit.
 *
 * <p>Every assertion here is on what the boot log SAYS, because saying it is the entire feature: the audit changes
 * nothing about what loads, so a version of it that computed everything correctly and printed nothing would be
 * indistinguishable from the state before it existed — which was silence while a mod failed inside its own
 * constructor for want of a dependency nobody had checked.
 */
class DependencyAuditTest {
	@Test
	void aMissingHardDependencyIsNamedAlongWithWhoNeedsIt() {
		String log = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.FORGE, "createaddon", "1.0.0", dep("create", ">=6", true))), List.of(), Side.CLIENT));

		assertTrue(log.contains("createaddon"), log);
		assertTrue(log.contains("create >=6"), log);
		assertTrue(log.contains("not installed"), log);
	}

	@Test
	void aPresentButOutOfRangeProviderIsNamedWithBothVersions() {
		String log = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.NEOFORGE, "needsnew", "1.0.0", dep("libx", ">=5.2", true)),
				mod(Ecosystem.NEOFORGE, "libx", "4.9.0")), List.of(), Side.CLIENT));

		assertTrue(log.contains("needsnew"), log);
		assertTrue(log.contains(">=5.2"), log);
		assertTrue(log.contains("4.9.0"), log);
		assertFalse(log.contains("not installed"), "it IS installed — just not the version asked for: " + log);
	}

	/**
	 * The Forbric-shaped case: the Forge mod's requirement is met by a FABRIC mod. Neither loader could see this —
	 * Forge's resolver does not know the Fabric mod exists, and Fabric's never hears the question.
	 */
	@Test
	void aRequirementMetByTheOtherEcosystemIsCountedAndSaidOutLoud() {
		String log = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.FORGE, "forgemod", "1.0.0", dep("sodium", ">=0.5", true)),
				mod(Ecosystem.FABRIC, "sodium", "0.6.13")), List.of(), Side.CLIENT));

		assertTrue(log.contains("ACROSS ecosystems"), log);
		assertFalse(log.contains("not installed"), log);
	}

	@Test
	void anOptionalDependencyIsNeverReported() {
		String log = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.FORGE, "polite", "1.0.0", dep("nothere", "*", false))), List.of(), Side.CLIENT));

		assertFalse(log.contains("nothere"), log);
	}

	@Test
	void platformAndLoaderIdsAreNotTreatedAsMissingMods() {
		String log = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.NEOFORGE, "normalmod", "1.0.0",
						dep("minecraft", "*", true), dep("neoforge", ">=21", true), dep("java", ">=21", true))),
				List.of(), Side.CLIENT));

		assertFalse(log.contains("not installed"), "none of these is ever a jar in mods/: " + log);
	}

	@Test
	void aClientOnlyRequirementIsNotReportedOnAServer() {
		UnifiedDependency clientOnly =
				new UnifiedDependency("jei", "*", true, Ordering.NONE, SideScope.CLIENT);
		String onServer = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.FORGE, "servermod", "1.0.0", clientOnly)), List.of(), Side.DEDICATED_SERVER));
		assertFalse(onServer.contains("jei"), onServer);

		String onClient = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.FORGE, "servermod", "1.0.0", clientOnly)), List.of(), Side.CLIENT));
		assertTrue(onClient.contains("jei"), onClient);
	}

	/**
	 * With no known side, a side-scoped requirement is skipped rather than guessed at. Guessing produces a false
	 * accusation half the time, and a boot log that cries wolf is worse than one that says nothing.
	 */
	@Test
	void withNoKnownSideASideScopedRequirementIsNotJudged() {
		String log = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.FORGE, "servermod", "1.0.0",
						new UnifiedDependency("jei", "*", true, Ordering.NONE, SideScope.CLIENT))), List.of(), null));

		assertFalse(log.contains("jei"), log);
	}

	/**
	 * A JarJar-nested provider counts as installed.
	 *
	 * <p>This is the case that made the first version of this audit lie on a real modpack: Journeymap nests
	 * {@code commonnetworking}, the kernel unpacks it and loads it exactly like a top-level mod, but it is not in
	 * {@code mods/} and so not in the presence list. The audit warned that it was not installed four log lines
	 * after the boot said it had extracted it.
	 */
	@Test
	void aDependencyProvidedByANestedJarIsNotReportedMissing(@TempDir Path dir) throws Exception {
		Path nested = neoJar(dir, "common-networking.jar", "commonnetworking", "1.1.0");

		String log = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.NEOFORGE, "journeymap", "6.0.1", dep("commonnetworking", ">=1.0.22", true))),
				List.of(nested), Side.CLIENT));

		assertFalse(log.contains("not installed"), log);
		assertTrue(log.contains("every hard dependency"), log);
	}

	/** And a nested provider is still version-checked, not merely counted as present. */
	@Test
	void aNestedProviderIsStillHeldToTheRange(@TempDir Path dir) throws Exception {
		Path nested = neoJar(dir, "common-networking.jar", "commonnetworking", "0.9.0");

		String log = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.NEOFORGE, "journeymap", "6.0.1", dep("commonnetworking", ">=1.0.22", true))),
				List.of(nested), Side.CLIENT));

		assertTrue(log.contains("0.9.0"), log);
	}

	/**
	 * Before JarJar extraction has run the index cannot be complete, and "not run" must not look like "found
	 * nothing" — every nested provider would read as absent.
	 */
	@Test
	void beforeExtractionHasRunNothingIsReportedMissing() {
		String log = capture(() -> DependencyAudit.report(List.of(
				mod(Ecosystem.NEOFORGE, "journeymap", "6.0.1", dep("commonnetworking", ">=1.0.22", true))),
				null, Side.CLIENT));

		assertFalse(log.contains("not installed"), log);
	}

	// --- helpers -----------------------------------------------------------------------------------------------

	private static Path neoJar(Path dir, String name, String id, String version) throws IOException {
		String toml = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n"
				+ "[[mods]]\nmodId=\"" + id + "\"\nversion=\"" + version + "\"\n";
		Path jar = dir.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
			zip.write(toml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	private static UnifiedDependency dep(String id, String constraint, boolean mandatory) {
		return new UnifiedDependency(id, constraint, mandatory);
	}

	private static DiscoveredMod mod(Ecosystem eco, String id, String version, UnifiedDependency... deps) {
		return new DiscoveredMod(eco, id, version, id, List.of(deps), List.of(), null, id + ".jar");
	}

	/**
	 * ForbricLog has no log4j binding under test, so it falls back to the console — and it splits by level:
	 * {@code info} to {@code System.out}, {@code warn}/{@code error} to {@code System.err}. Capturing only one of
	 * them silently loses half the output, which for a test whose whole subject is what got said would mean
	 * asserting on an empty string and calling it a pass.
	 */
	private static String capture(Runnable body) {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream sink = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			body.run();
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
		return buffer.toString(StandardCharsets.UTF_8);
	}
}
