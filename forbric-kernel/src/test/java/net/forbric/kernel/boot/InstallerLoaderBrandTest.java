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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Pins the loader coordinate the installer writes into the version profile to the API level the kernel actually
 * implements.
 *
 * <p>The installer is a separate module that does not compile against the kernel — it builds the kernel jar as a
 * subprocess — so the coordinate over there is a string literal and nothing relates it to
 * {@link KernelFabricEcosystem#FABRIC_LOADER_API_LEVEL}. Raising the API level without raising the profile would
 * leave every freshly installed instance telling its launcher a version the kernel no longer provides, and no
 * build anywhere would notice.
 *
 * <p>Read as SOURCE TEXT on purpose. A test that called the installer would need it on the classpath, which is
 * the dependency this arrangement exists to avoid.
 */
class InstallerLoaderBrandTest {
	private static final Path INSTALLER_SOURCE = Path.of(System.getProperty("user.dir"), "..",
			"forbric-kernel-installer", "src", "main", "java", "net", "forbric", "installer", "kernel",
			"Installer.java").normalize();

	private static final Path GUI_SOURCE = INSTALLER_SOURCE.resolveSibling("InstallerGui.java");
	private static final Path INSTALLER_BUILD = Path.of(System.getProperty("user.dir"), "..",
			"forbric-kernel-installer", "build.gradle").normalize();

	private static final Pattern DECLARED = Pattern.compile(
			"DECLARED_LOADER\\s*=\\s*\"net\\.fabricmc:fabric-loader:([^\"]+)\"");

	@Test
	void theProfileDeclaresTheApiLevelTheKernelImplements() throws Exception {
		String source = source();
		Matcher m = DECLARED.matcher(source);
		assertTrue(m.find(), "the installer must still declare a fabric-loader coordinate — if the brand moved to "
				+ "another ecosystem, move this test with it");
		assertEquals(KernelFabricEcosystem.FABRIC_LOADER_API_LEVEL, m.group(1),
				"the version the installer tells a launcher about must be the one the kernel provides");
	}

	/**
	 * The installer's jar must carry its own version, and the fallback must not pretend to be one.
	 *
	 * <p>The window shows this number to the user. It read {@code Package.getImplementationVersion()} with a
	 * literal {@code "0.1.0"} fallback, and the jar task never set the attribute — so the lookup returned null
	 * on every build ever made, the fallback always won, and an installer built at 0.2.0 told the user it was
	 * 0.1.0. It was reported by a user, not by any build.
	 *
	 * <p>Two halves, and both are needed: the attribute makes the real answer available, and a non-version
	 * fallback means that if the attribute ever goes missing again the window says {@code dev} instead of
	 * quietly naming a version that was true once.
	 */
	@Test
	void theInstallersOwnVersionIsReadFromItsManifestAndNeverGuessed() throws Exception {
		assumeTrue(Files.isRegularFile(GUI_SOURCE), "installer module absent — skipping (" + GUI_SOURCE + ")");
		assumeTrue(Files.isRegularFile(INSTALLER_BUILD), "installer build file absent");

		String gradle = Files.readString(INSTALLER_BUILD, StandardCharsets.UTF_8);
		assertTrue(gradle.contains("'Implementation-Version': project.version"),
				"the jar task must stamp the version it was built at, or the window can only guess");

		String gui = Files.readString(GUI_SOURCE, StandardCharsets.UTF_8);
		Matcher fallback = Pattern.compile("getImplementationVersion\\(\\);\\s*return version == null \\? \"([^\"]*)\"")
				.matcher(gui);
		assertTrue(fallback.find(), "loaderVersion() must still read the manifest with an explicit fallback");
		String value = fallback.group(1);
		assertFalse(value.matches(".*\\d+\\.\\d+.*"),
				"the fallback must not look like a version — it is what the window shows when the real one "
						+ "cannot be determined, and \"" + value + "\" is a claim rather than an admission");
	}

	/**
	 * Exactly one ecosystem may appear as a COORDINATE.
	 *
	 * <p>A launcher's "is this modded" check is a first-match chain over the serialised profile, so a second
	 * coordinate would not add an answer — it would make the answer depend on which branch that launcher tests
	 * first, and two launchers would then disagree about the same file. The other ecosystems are named in prose
	 * in the same block; this asserts they stayed prose.
	 */
	@Test
	void noSecondEcosystemIsWrittenAsACoordinate() throws Exception {
		String block = identityBlock(source());
		assertFalse(block.contains("net.neoforge"),
				"a NeoForge coordinate here would be a second answer to a one-answer question");
		assertFalse(block.contains("minecraftforge"),
				"a MinecraftForge coordinate here would be a second answer to a one-answer question");
		// Both are still SUPPOSED to be named, just not as something a launcher matches on.
		assertTrue(block.contains("\"forge\"") && block.contains("\"neoforge\""),
				"the other two ecosystems must still be named — the block is what a reader opens to find out "
						+ "what this instance runs");
	}

	/** The {@code launcherIdentity} method body, which is the only place a coordinate may legitimately appear. */
	private static String identityBlock(String source) {
		int from = source.indexOf("private static Map<String, Object> launcherIdentity()");
		assertTrue(from > 0, "launcherIdentity() must still exist");
		int to = source.indexOf("\n\t}", from);
		assertTrue(to > from, "launcherIdentity() must still be a method");
		return source.substring(from, to);
	}

	private static String source() throws Exception {
		assumeTrue(Files.isRegularFile(INSTALLER_SOURCE),
				"installer module absent — skipping the profile pin (" + INSTALLER_SOURCE + ")");
		return Files.readString(INSTALLER_SOURCE, StandardCharsets.UTF_8);
	}
}
