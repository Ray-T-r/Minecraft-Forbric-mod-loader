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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.discovery.ForbricModDiscoverer;

/**
 * Which jars' {@code data/} the kernel takes responsibility for.
 *
 * <p>The selection is the whole design decision, so it is what gets asserted: Forge-family jars are served because
 * nothing else serves them (the kernel leaves {@code ModList.modFiles} empty), and Fabric jars are NOT, because
 * fabric-api's resource loader already does — serving both would append every tag entry twice.
 */
class KernelDataPacksTest {
	@TempDir
	Path dir;

	@BeforeEach
	@AfterEach
	void forgetArbitration() {
		MultiLoaderArbiter.reset();
		System.clearProperty(KernelDataPacks.PROPERTY);
	}

	@Test
	void aJarWithDataIsServable() throws Exception {
		Path jar = jar("neo-with-data.jar", ForbricModDiscoverer.NEOFORGE_MANIFEST, "data/mymod/recipe/thing.json");
		assertTrue(KernelDataPacks.carriesData(jar));
	}

	@Test
	void aJarWithOnlyAssetsIsNot() throws Exception {
		// Assets are the CLIENT path's business (KernelClientPacks); a datapack source has nothing to serve here.
		Path jar = jar("neo-assets-only.jar", ForbricModDiscoverer.NEOFORGE_MANIFEST, "assets/mymod/lang/en_us.json");
		assertFalse(KernelDataPacks.carriesData(jar));
	}

	@Test
	void somethingThatIsNotAJarIsNot() throws Exception {
		Path notAJar = dir.resolve("readme.txt");
		Files.writeString(notAJar, "this is not a zip");
		assertFalse(KernelDataPacks.carriesData(notAJar));
		assertFalse(KernelDataPacks.carriesData(dir.resolve("absent.jar")));
	}

	@Test
	void servesForgeFamilyJarsAndLeavesFabricOnesToFabricApi() throws Exception {
		Path neo = jar("neo.jar", ForbricModDiscoverer.NEOFORGE_MANIFEST, "data/neo/recipe/a.json");
		Path forge = jar("forge.jar", ForbricModDiscoverer.FORGE_MANIFEST, "data/forge/recipe/b.json");
		Path fabric = jar("fabric.jar", ForbricModDiscoverer.FABRIC_MANIFEST, "data/fabric/recipe/c.json");
		Path neoNoData = jar("neo-empty.jar", ForbricModDiscoverer.NEOFORGE_MANIFEST, "assets/neo/lang/en_us.json");
		Path plainLibrary = jar("library.jar", null, "data/lib/recipe/d.json");

		List<Path> served = KernelDataPacks.forgeFamilyJarsWithData(
				List.of(neo, forge, fabric, neoNoData, plainLibrary));

		assertEquals(List.of(neo, forge), served);
	}

	@Test
	void aUniversalJarIsServedWhenItsWinningClaimIsForgeFamily() throws Exception {
		// Universal jars declare every loader; the arbiter picks NeoForge by default, so its data/ is ours to serve
		// and fabric-api will not have it in Fabric's mod list.
		Path universal = dir.resolve("universal.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(universal))) {
			put(zip, ForbricModDiscoverer.NEOFORGE_MANIFEST, "");
			put(zip, ForbricModDiscoverer.FABRIC_MANIFEST, "{}");
			put(zip, "data/universal/recipe/a.json", "{}");
		}
		assertEquals(List.of(universal), KernelDataPacks.forgeFamilyJarsWithData(List.of(universal)));
	}

	@Test
	void theKillSwitchIsHonoured() {
		assertTrue(KernelDataPacks.enabled());
		System.setProperty(KernelDataPacks.PROPERTY, "off");
		assertFalse(KernelDataPacks.enabled());
		System.setProperty(KernelDataPacks.PROPERTY, "on");
		assertTrue(KernelDataPacks.enabled());
	}

	@Test
	void packIdsDropTheExtensionOnly() {
		assertEquals("lithostitched-1.7.13-neoforge-26.2",
				KernelDataPacks.stripExtension("lithostitched-1.7.13-neoforge-26.2.jar"));
		assertEquals("noextension", KernelDataPacks.stripExtension("noextension"));
	}

	private Path jar(String name, String manifest, String... entries) throws Exception {
		Path jar = dir.resolve(name);
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			if (manifest != null) put(zip, manifest, "");
			for (String entry : entries) put(zip, entry, "{}");
		}
		return jar;
	}

	private static void put(ZipOutputStream zip, String name, String body) throws Exception {
		zip.putNextEntry(new ZipEntry(name));
		OutputStream out = zip;
		out.write(body.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
