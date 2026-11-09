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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The NeoForge carrier's datapack id on an INSTALLED profile, from the installer's own source (D43).
 *
 * <p>A staged tree runs the carrier as {@code neoforge-runtime.jar}; an installed profile runs it under its library file
 * name, which the installer derives from a coordinate. Every gate runs staged, so none of them can see what an installed
 * world records: this test reads the file name the installer gives the jar ({@code --runtimeJar} =
 * {@code libraryRef(coordinate(NEOFORGE_RUNTIME, …))}, versioned by {@code Pins.NEOFORGE}) and the one Forbric 0.4 gave
 * it (v0.3.1-beta2: {@code net.forbric:neoforge-runtime:<mcVersion>}, i.e. {@code neoforge-runtime-26.2.jar},
 * {@code NeoForgeArtifacts.java:68} at that tag), and requires the kernel to give all three names one id.
 *
 * <p>The installer module does not compile against the kernel (it builds the kernel jar as a subprocess), so its
 * source is read as text, like InstallerLoaderBrandTest.
 */
class InstalledCarrierPackIdTest {
	private static final Path INSTALLER = Path.of(System.getProperty("user.dir"), "..", "forbric-kernel-installer", "src",
			"main", "java", "net", "forbric", "installer", "kernel").normalize();

	@TempDir
	Path dir;

	@Test
	void everyNameTheCarrierHasBeenInstalledUnderGivesTheSameId() throws Exception {
		String installer = Files.readString(INSTALLER.resolve("Installer.java"));
		assertTrue(Pattern.compile("game\\.add\\(\"--runtimeJar\"\\);\\s*game\\.add\\(libraryRef\\(coordinate\\("
				+ "ArtifactBuilder\\.NEOFORGE_RUNTIME, mcVersion\\)\\)\\);").matcher(installer).find(),
				"the profile's --runtimeJar is no longer the NeoForge carrier's library path; re-derive this test");
		assertTrue(installer.contains("case ArtifactBuilder.NEOFORGE_RUNTIME -> Pins.NEOFORGE;"),
				"the carrier's library version is no longer Pins.NEOFORGE; re-derive this test");
		String coordinate = constant(INSTALLER.resolve("ArtifactBuilder.java"), "NEOFORGE_RUNTIME");
		String version = constant(INSTALLER.resolve("Pins.java"), "NEOFORGE");
		assertEquals("net.forbric:neoforge-runtime", coordinate);
		// Util.coordinateToPath: <group>/<artifact>/<version>/<artifact>-<version>.jar
		String installed = coordinate.split(":")[1] + "-" + version + ".jar";

		for (String name : new String[] {installed, "neoforge-runtime-26.2.jar", "neoforge-runtime.jar"}) {
			assertEquals(KernelDataPacks.NEO_CARRIER_ID, KernelDataPacks.carrierPackId(carrier(name)),
					"the NeoForge carrier installed as " + name);
		}
		assertEquals("neoforge-runtime-26.2.0.93.jar", installed, "the name this installer writes today");
	}

	private static String constant(Path source, String name) throws Exception {
		Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]+)\"").matcher(Files.readString(source));
		assertTrue(m.find(), name + " is no longer a string constant in " + source.getFileName());
		return m.group(1);
	}

	private Path carrier(String name) throws Exception {
		Path jar = dir.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("data/neoforge/damage_type/thing.json"));
			OutputStream out = zip;
			out.write("{}".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}
}
