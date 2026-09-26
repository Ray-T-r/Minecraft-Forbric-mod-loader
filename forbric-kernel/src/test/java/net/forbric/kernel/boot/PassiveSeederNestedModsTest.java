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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.DiscoveredMod;

/**
 * The Forge-family mods that came out of another mod's jar are in the seeded NeoForge {@code LoadingModList}, as
 * they are in native NeoForge's.
 *
 * <p>LibJF is the pack case: its outer {@code lowcodefml} jar carries twelve modules as jar-in-jar, and native
 * NeoForge lists all thirteen ids. The seeded list had only {@code libjf}, so LibJF's entry-point lookup — which
 * walks {@code getMods()} at mixin-plugin time, before {@code ModList} exists, and caches what it finds — could
 * never see {@code libjf_data_manipulation_v0}, the module that declares its {@code libjf:asm} patch.
 */
class PassiveSeederNestedModsTest {
	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		System.clearProperty(PassiveSeeder.NESTED_SWITCH);
		System.clearProperty("forbric.multiLoaderPreference");
		MultiLoaderArbiter.reset();
	}

	@Test
	void aNestedModResolvesThroughTheSeededListWithItsOwnProperties() throws Exception {
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		KernelModLoaderDeclaredTest.neoJar(mods.resolve("libjf.jar"), "lowcodefml", "libjf", "26.2.2+forge", "");
		Path nested = manipulationJar();

		try (URLClassLoader game = neoForgeLoader()) {
			PassiveSeederLoadingModListTest.FakeFmlLoader loader = new PassiveSeederLoadingModListTest.FakeFmlLoader();
			PassiveSeeder.seedNeoForgeLoadingModList(game, PassiveSeederLoadingModListTest.FakeFmlLoader.class, loader,
					mods, List.of(nested));
			Object list = PassiveSeederLoadingModListTest.seededList(loader);

			assertNotNull(PassiveSeederLoadingModListTest.call(list, "getModFileById", String.class, "libjf"));
			Object file = PassiveSeederLoadingModListTest.call(list, "getModFileById", String.class,
					"libjf_data_manipulation_v0");
			assertNotNull(file, "the nested module must resolve through getModFileById, as it does natively");

			Object info = modInfo(list, "libjf_data_manipulation_v0");
			assertNotNull(info, "and be in getMods(), which is what LibJF walks");
			Map<?, ?> properties = (Map<?, ?>) info.getClass().getMethod("getModProperties").invoke(info);
			assertTrue(properties.containsKey("libjf:entrypoints"),
					"carrying its OWN [modproperties], where LibJF finds libjf:asm: " + properties);
		}
	}

	@Test
	void switchedOffTheNestedModIsAbsentAgain() throws Exception {
		System.setProperty(PassiveSeeder.NESTED_SWITCH, "off");
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		KernelModLoaderDeclaredTest.neoJar(mods.resolve("libjf.jar"), "lowcodefml", "libjf", "26.2.2+forge", "");
		Path nested = manipulationJar();

		try (URLClassLoader game = neoForgeLoader()) {
			PassiveSeederLoadingModListTest.FakeFmlLoader loader = new PassiveSeederLoadingModListTest.FakeFmlLoader();
			PassiveSeeder.seedNeoForgeLoadingModList(game, PassiveSeederLoadingModListTest.FakeFmlLoader.class, loader,
					mods, List.of(nested));
			Object list = PassiveSeederLoadingModListTest.seededList(loader);

			assertNotNull(PassiveSeederLoadingModListTest.call(list, "getModFileById", String.class, "libjf"));
			assertNull(PassiveSeederLoadingModListTest.call(list, "getModFileById", String.class,
					"libjf_data_manipulation_v0"), "off => only the jars in mods/, as before");
			assertNull(modInfo(list, "libjf_data_manipulation_v0"));
		}
	}

	@Test
	void theTopLevelDeclarationOfAnIdWinsAndNothingIsListedTwice() throws Exception {
		Path nested = manipulationJar();
		Set<String> seen = new LinkedHashSet<>(List.of("libjf_data_manipulation_v0"));
		assertTrue(PassiveSeeder.arbitratedNestedForgeFamilyMods(List.of(nested), seen).isEmpty(),
				"an id a jar in mods/ already declares keeps that declaration");

		Set<String> fresh = new LinkedHashSet<>();
		assertEquals(List.of("libjf_data_manipulation_v0"),
				ids(PassiveSeeder.arbitratedNestedForgeFamilyMods(List.of(nested, nested), fresh)));
	}

	@Test
	void aNestedJarTheArbiterGaveToFabricContributesNoForgeFamilyMod() throws Exception {
		Path universal = tmp.resolve("candidates/cd/universal.jar");
		Files.createDirectories(universal.getParent());
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(universal))) {
			KernelModLoaderDeclaredTest.put(zip, "META-INF/neoforge.mods.toml",
					KernelModLoaderDeclaredTest.toml("javafml", "universalmod", "1.0", ""));
			KernelModLoaderDeclaredTest.put(zip, "fabric.mod.json",
					"{\"schemaVersion\":1,\"id\":\"universalmod\",\"version\":\"1.0\"}");
		}
		MultiLoaderArbiter.reset();
		assertEquals(List.of("universalmod"),
				ids(PassiveSeeder.arbitratedNestedForgeFamilyMods(List.of(universal), new LinkedHashSet<>())));

		System.setProperty("forbric.multiLoaderPreference", "fabric");
		MultiLoaderArbiter.reset();
		assertTrue(PassiveSeeder.arbitratedNestedForgeFamilyMods(List.of(universal), new LinkedHashSet<>()).isEmpty(),
				"a jar the arbiter handed to FABRIC must not appear as a Forge-family mod");
	}

	@Test
	void beforeExtractionNothingIsAdded() {
		assertTrue(PassiveSeeder.arbitratedNestedForgeFamilyMods(null, new LinkedHashSet<>()).isEmpty());
		assertFalse(PassiveSeeder.arbitratedNestedForgeFamilyMods(List.of(), new LinkedHashSet<>()).iterator()
				.hasNext());
	}

	// --- helpers --------------------------------------------------------------------------------------------

	/** libjf-data-manipulation-v0 as extracted from LibJF: lowcodefml, no class, its libjf:asm entry point. */
	private Path manipulationJar() throws Exception {
		return KernelModLoaderDeclaredTest.neoJar(tmp.resolve("candidates/ab/libjf-data-manipulation-v0.jar"),
				"lowcodefml", "libjf_data_manipulation_v0", "26.2.2+forge",
				"[[modproperties.libjf_data_manipulation_v0.\"libjf:entrypoints\".\"libjf:asm\"]]\n"
						+ "value = \"dev.jfronny.libjf.data.manipulation.impl.ResourcePackHookPatch\"\n");
	}

	private static Object modInfo(Object list, String id) throws Exception {
		for (Object info : (List<?>) PassiveSeederLoadingModListTest.call(list, "getMods")) {
			if (id.equals(info.getClass().getMethod("getModId").invoke(info))) return info;
		}
		return null;
	}

	private static List<String> ids(List<DiscoveredMod> mods) {
		List<String> out = new ArrayList<>();
		for (DiscoveredMod mod : mods) out.add(mod.getId());
		return out;
	}

	private URLClassLoader neoForgeLoader() throws Exception {
		assumeTrue(Files.isRegularFile(PassiveSeederLoadingModListTest.NEO_RUNTIME), "staged neoforge-runtime.jar absent");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(tmp.resolve("stubs"));
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), PassiveSeederLoadingModListTest.NEO_RUNTIME.toUri()
				.toURL()}, ClassLoader.getPlatformClassLoader());
	}
}
