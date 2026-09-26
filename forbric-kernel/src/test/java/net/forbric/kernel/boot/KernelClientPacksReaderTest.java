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
				"META-INF/mods.toml", PACK_MCMETA), false));
	}

	@Test
	void aFabricModsPackIsReadTheWayFabricApiReadsIt() throws IOException {
		assertTrue(KernelClientPacks.readsItsMetadataTheVanillaWay(jar("fabricmod.jar", "fabric.mod.json", PACK_MCMETA), false));
	}

	@Test
	void aNeoForgeModsPackKeepsNeoForgesReader() throws IOException {
		assertFalse(KernelClientPacks.readsItsMetadataTheVanillaWay(jar("neomod.jar",
				"META-INF/neoforge.mods.toml", PACK_MCMETA), false));
	}

	/** Without a pack.mcmeta vanilla's reader has nothing and says "Missing metadata"; NeoForge's supplies a default. */
	@Test
	void aJarWithoutPackMetadataKeepsNeoForgesReader() throws IOException {
		assertFalse(KernelClientPacks.readsItsMetadataTheVanillaWay(jar("assetsonly.jar", "META-INF/mods.toml", null), false));
	}

	@Test
	void switchedOffEveryJarKeepsNeoForgesReader() throws IOException {
		Path forge = jar("antiblocksrechiseled.jar", "META-INF/mods.toml", PACK_MCMETA);
		System.setProperty(KernelClientPacks.VANILLA_READER, "off");
		assertFalse(KernelClientPacks.readsItsMetadataTheVanillaWay(forge, false));
	}

	/**
	 * The two carriers as they ship: each its ecosystem's manifest plus a {@code fabric.mod.json}, and a
	 * pack.mcmeta. Nobody arbitrates a carrier, and asking the arbiter about the MinecraftForge one scanned it for
	 * {@code @Mod} classes inside {@code Minecraft.<init>} and logged a load decision nothing was making.
	 */
	@Test
	void aCarrierIsReadAsItsOwnEcosystemReadsItWithoutBeingArbitrated() throws IOException {
		Path forge = jarDeclaring("forge-runtime-interop.jar", PACK_MCMETA, "META-INF/mods.toml", "fabric.mod.json");
		Path neo = jarDeclaring("neoforge-runtime.jar", PACK_MCMETA, "META-INF/neoforge.mods.toml", "fabric.mod.json");
		boolean[] vanilla = new boolean[2];
		String log = captured(() -> {
			vanilla[0] = KernelClientPacks.readsItsMetadataTheVanillaWay(forge, true);
			vanilla[1] = KernelClientPacks.readsItsMetadataTheVanillaWay(neo, true);
		});
		assertTrue(vanilla[0], "MinecraftForge's carrier: vanilla's reader, as MinecraftForge reads it");
		assertFalse(vanilla[1], "NeoForge's carrier: NeoForge's reader, as NeoForge reads it");
		assertFalse(log.contains("[Forbric/MultiLoader]"), "a carrier is never put to the arbiter: " + log);

		// premise: the same jar as a MOD is arbitrated, and that is the line the carrier used to get
		assertTrue(captured(() -> KernelClientPacks.readsItsMetadataTheVanillaWay(forge, false))
				.contains("forge-runtime-interop.jar declares 2 loaders"));
	}

	private Path jar(String name, String manifest, String packMcmeta) throws IOException {
		return jarDeclaring(name, packMcmeta, manifest);
	}

	private Path jarDeclaring(String name, String packMcmeta, String... manifests) throws IOException {
		Path jar = Files.createDirectories(temp.resolve(name.replace(".jar", ""))).resolve(name);
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
			for (String manifest : manifests) {
				put(out, manifest, manifest.endsWith(".json") ? "{\"schemaVersion\": 1, \"id\": \"m\", \"version\": \"1\"}"
						: "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\"m\"\n");
			}
			if (packMcmeta != null) put(out, "pack.mcmeta", packMcmeta);
			put(out, "assets/m/lang/en_us.json", "{}");
		}
		return jar;
	}

	/** ForbricLog has no log4j under test: info goes to System.out, warn to System.err. Both are captured. */
	private static String captured(Runnable body) {
		PrintStream out = System.out;
		PrintStream err = System.err;
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream sink = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		System.setOut(sink);
		System.setErr(sink);
		try {
			body.run();
		} finally {
			System.setOut(out);
			System.setErr(err);
		}
		return buffer.toString(StandardCharsets.UTF_8);
	}

	private static void put(ZipOutputStream out, String name, String text) throws IOException {
		out.putNextEntry(new ZipEntry(name));
		out.write(text.getBytes(StandardCharsets.UTF_8));
		out.closeEntry();
	}
}
