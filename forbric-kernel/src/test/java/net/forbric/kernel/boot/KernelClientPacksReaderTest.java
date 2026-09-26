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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which reader builds a served mod pack's metadata: the one that jar's own loader would have used, because that
 * is the one its mods hook. fusion (MinecraftForge) mounts Rechiseled Anti-Blocks' connected-texture overrides
 * from inside vanilla's {@code Pack.readPackMetadata}; NeoForge's reader never calls it.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelClientPacksReaderTest {
	private static final String PACK_MCMETA = "{\"pack\": {\"pack_format\": 55, \"description\": \"x\"}, "
			+ "\"fusion\": {\"overrides_folder\": \"fusion-overrides\"}}";

	@TempDir
	Path temp;

	@AfterEach
	void reset() {
		System.clearProperty(KernelClientPacks.VANILLA_READER);
	}

	@Test
	void aMinecraftForgeModsPackIsReadTheWayMinecraftForgeReadsIt() throws IOException {
		assertTrue(KernelClientPacks.readsItsMetadataTheVanillaWay(jar("antiblocksrechiseled.jar",
				"META-INF/mods.toml", PACK_MCMETA)));
	}

	@Test
	void aFabricModsPackIsReadTheWayFabricApiReadsIt() throws IOException {
		assertTrue(KernelClientPacks.readsItsMetadataTheVanillaWay(jar("fabricmod.jar", "fabric.mod.json", PACK_MCMETA)));
	}

	@Test
	void aNeoForgeModsPackKeepsNeoForgesReader() throws IOException {
		assertFalse(KernelClientPacks.readsItsMetadataTheVanillaWay(jar("neomod.jar",
				"META-INF/neoforge.mods.toml", PACK_MCMETA)));
	}

	/** Without a pack.mcmeta vanilla's reader has nothing and says "Missing metadata"; NeoForge's supplies a default. */
	@Test
	void aJarWithoutPackMetadataKeepsNeoForgesReader() throws IOException {
		assertFalse(KernelClientPacks.readsItsMetadataTheVanillaWay(jar("assetsonly.jar", "META-INF/mods.toml", null)));
	}

	@Test
	void switchedOffEveryJarKeepsNeoForgesReader() throws IOException {
		Path forge = jar("antiblocksrechiseled.jar", "META-INF/mods.toml", PACK_MCMETA);
		System.setProperty(KernelClientPacks.VANILLA_READER, "off");
		assertFalse(KernelClientPacks.readsItsMetadataTheVanillaWay(forge));
	}

	private Path jar(String name, String manifest, String packMcmeta) throws IOException {
		Path jar = Files.createDirectories(temp.resolve(name.replace(".jar", ""))).resolve(name);
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			put(out, manifest, manifest.endsWith(".json") ? "{\"schemaVersion\": 1, \"id\": \"m\", \"version\": \"1\"}"
					: "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"m\"\n");
			if (packMcmeta != null) put(out, "pack.mcmeta", packMcmeta);
			put(out, "assets/m/lang/en_us.json", "{}");
		}
		return jar;
	}

	private static void put(ZipOutputStream out, String name, String text) throws IOException {
		out.putNextEntry(new ZipEntry(name));
		out.write(text.getBytes(StandardCharsets.UTF_8));
		out.closeEntry();
	}
}
