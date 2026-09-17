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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The seeded MinecraftForge {@code ModFile}'s jar, without either carrier on the classpath.
 *
 * <p>The stand-in dispatches by method NAME, so a locally declared interface with MinecraftForge's method names
 * exercises exactly the code the real {@code cpw.mods.jarhandling.SecureJar} would reach. That is deliberate:
 * the real interface cannot be loaded in a unit test, and the alternative — testing nothing and finding out on a
 * boot — is what this whole fix is repairing.
 */
class ForgeSecureJarStandInTest {

	/** MinecraftForge's {@code SecureJar}, name for name, minus what the seeded path never touches. */
	interface FakeSecureJar {
		Path getPrimaryPath();

		Path getRootPath();

		Path getPath(String first, String... more);

		String name();

		boolean hasSecurityData();

		Object getManifestSigners();

		Set<String> getPackages();

		List<Object> getProviders();

		Object moduleDataProvider();

		Object getFileStatus(String name);

		/** Not part of the real interface; here to prove an unknown method answers empty rather than throwing. */
		List<String> somethingNobodyImplemented();

		interface ModuleDataProvider {
			String name();

			URI uri();

			Manifest getManifest();

			Optional<URI> findFile(String name);

			Optional<java.io.InputStream> open(String name);

			java.lang.module.ModuleDescriptor descriptor();
		}
	}

	private static Path jarWith(Path dir, String name, String entry, String body) throws Exception {
		Path jar = dir.resolve(name);
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().putValue("FMLModType", "MOD");
		try (OutputStream out = Files.newOutputStream(jar);
				JarOutputStream jos = new JarOutputStream(out, manifest)) {
			jos.putNextEntry(new ZipEntry(entry));
			jos.write(body.getBytes(StandardCharsets.UTF_8));
			jos.closeEntry();
		}
		return jar;
	}

	@Test
	void answersThePathAccessorsModFileActuallyCalls(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir, "shoulder-surfing-forge-1.0.jar", "META-INF/mods.toml", "modLoader=\"javafml\"");
		FakeSecureJar secure = (FakeSecureJar) ForgeSecureJarStandIn.create(FakeSecureJar.class, jar);

		// getFilePath -> getPrimaryPath: the identity of the jar, which getFileName and toString both read.
		assertSame(jar, secure.getPrimaryPath());
		assertTrue(secure.toString().contains(jar.toString()));

		// findResource -> getPath: a real path INSIDE the jar, which is the point of not using SecureJar.from.
		Path inside = secure.getPath("META-INF", "mods.toml");
		assertTrue(Files.exists(inside), "the path must resolve inside the jar, not next to it");
		assertTrue(Files.readString(inside).contains("javafml"));

		// A resource that is not there must answer "not there" rather than throw.
		assertFalse(Files.exists(secure.getPath("META-INF", "absent.toml")));

		assertNotNull(secure.getRootPath());
	}

	@Test
	void answersTheRestWithHonestEmpties(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir, "collective-2.0.jar", "pack.mcmeta", "{}");
		FakeSecureJar secure = (FakeSecureJar) ForgeSecureJarStandIn.create(FakeSecureJar.class, jar);

		// Unsigned, and says so. Claiming otherwise would be a lie the kernel cannot back up.
		assertFalse(secure.hasSecurityData());
		assertEquals(null, secure.getManifestSigners());

		assertEquals(Set.of(), secure.getPackages());
		assertEquals(List.of(), secure.getProviders());
		assertEquals("collective.2.0", secure.name());

		// No Status enum nested in the fake interface, so the stand-in must answer null instead of blowing up.
		assertEquals(null, secure.getFileStatus("whatever"));

		// An interface method the stand-in has never heard of: empty of the right shape, never an exception.
		assertEquals(List.of(), secure.somethingNobodyImplemented());
	}

	@Test
	void theModuleDataProviderReadsTheRealManifest(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir, "spark-forge.jar", "META-INF/mods.toml", "x");
		FakeSecureJar secure = (FakeSecureJar) ForgeSecureJarStandIn.create(FakeSecureJar.class, jar);

		FakeSecureJar.ModuleDataProvider data = (FakeSecureJar.ModuleDataProvider) secure.moduleDataProvider();
		assertNotNull(data, "ModFile.parseType reads FMLModType through this; null here is an NPE there");

		// parseType's exact route: moduleDataProvider().getManifest().getMainAttributes().getValue("FMLModType").
		assertEquals("MOD", data.getManifest().getMainAttributes().getValue("FMLModType"));
		assertEquals(jar.toUri(), data.uri());
		assertEquals("spark.forge", data.name());
		assertTrue(data.findFile("META-INF/mods.toml").isPresent());
		assertFalse(data.findFile("META-INF/nothing.toml").isPresent());
		assertTrue(data.open("META-INF/mods.toml").isPresent());

		// An automatic module name has to be a legal identifier, which is why the jar name is scrubbed.
		assertEquals("spark.forge", data.descriptor().name());

		// Same instance each time: ModFile holds on to it.
		assertSame(data, secure.moduleDataProvider());
	}

	@Test
	void survivesSomethingElseClosingTheZipFileSystem(@TempDir Path dir) throws Exception {
		Path jar = jarWith(dir, "journeymap.jar", "META-INF/mods.toml", "y");
		FakeSecureJar secure = (FakeSecureJar) ForgeSecureJarStandIn.create(FakeSecureJar.class, jar);

		assertTrue(Files.exists(secure.getPath("META-INF", "mods.toml")));

		// KernelDataPacks mounts and unmounts these same jars per world load. A stand-in that went dead after the
		// first world would be a bug that only shows up on the SECOND one.
		secure.getPath("META-INF", "mods.toml").getFileSystem().close();

		assertTrue(Files.exists(secure.getPath("META-INF", "mods.toml")),
				"the stand-in must re-open the zip rather than stay broken");
	}

	@Test
	void scrubsJarNamesIntoLegalModuleNames() {
		assertEquals("some.mod.1.2.3", ForgeSecureJarStandIn.moduleNameOf(Path.of("Some-Mod +1.2.3.jar")));
		assertEquals("mod.2fast", ForgeSecureJarStandIn.moduleNameOf(Path.of("2fast.jar")));
		assertEquals("mod", ForgeSecureJarStandIn.moduleNameOf(Path.of("---.jar")));
	}
}
