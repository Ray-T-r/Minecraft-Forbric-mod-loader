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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.metadata.forge.FmlConfigElements;

/**
 * The seeded {@code LoadingModList}'s FILES answer what their {@code mods.toml} declares at the top level, as a native
 * {@code ModFileInfo} does — which is where Lithium looks for a mod's {@code ["lithium:options"]}.
 *
 * <p>The pack case is the player's own: Unlit Campfire asks Lithium to switch off
 * {@code mixin.world.block_entity_ticking.sleeping.campfire}, and the player's log said
 * {@code Loaded configuration file for Lithium: 171 options available, 0 override(s) found.} Every seeded file
 * answered {@code getConfigElement} empty.
 */
class PassiveSeederFileConfigTest {
	private static final Path MERGED_PACK = Path.of(System.getProperty("user.dir"), "run", "client-merged-pack", "mods")
			.normalize();
	private static final String UNLIT_CAMPFIRE = "unlitcampfire-neoforge-26.2-4.1.0.0.jar";
	private static final String LITHIUM = "lithium-neoforge-0.25.2+mc26.2.jar";
	private static final String CAMPFIRE_OPTION = "mixin.world.block_entity_ticking.sleeping.campfire";

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		System.clearProperty(FmlConfigElements.SWITCH);
		MultiLoaderArbiter.reset();
	}

	/**
	 * The player's real Lithium, run against a list seeded from the player's real Unlit Campfire and Lithium jars:
	 * {@code NeoForgeMixinOverrides.applyModOverrides()} — the method whose result is Lithium's "override(s) found" —
	 * finds Unlit Campfire's one override, as it does on NeoForge. Switched off, it finds none: the player's log.
	 */
	@Test
	void theRealLithiumFindsUnlitCampfiresOverride() throws Exception {
		Path unlit = MERGED_PACK.resolve(UNLIT_CAMPFIRE);
		Path lithium = MERGED_PACK.resolve(LITHIUM);
		assumeTrue(Files.isRegularFile(unlit) && Files.isRegularFile(lithium), "the player's pack is not here");
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		Files.copy(unlit, mods.resolve(UNLIT_CAMPFIRE), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(lithium, mods.resolve(LITHIUM), StandardCopyOption.REPLACE_EXISTING);

		assertEquals(List.of("unlitcampfire " + CAMPFIRE_OPTION + "=false"), lithiumOverrides(mods, lithium));

		System.setProperty(FmlConfigElements.SWITCH, "off");
		assertEquals(List.of(), lithiumOverrides(mods, lithium), "switched off: the player's 0 override(s) found");
	}

	/**
	 * The same question asked the way Lithium asks it — {@code getMods()}, each one's concrete
	 * {@code ModInfo.getOwningFile()}, then {@code ModFileInfo.getConfigElement("lithium:options")} — of a list seeded
	 * from Unlit Campfire's real manifest. Runs wherever the carrier is staged, without the player's pack.
	 */
	@Test
	void theSeededFileAnswersLithiumsQuestionFromUnlitCampfiresManifest() throws Exception {
		Path mods = Files.createDirectories(tmp.resolve("mods"));
		jar(mods.resolve("unlitcampfire.jar"), fixture("unlitcampfire.neoforge.mods.toml"));

		try (URLClassLoader game = neoForgeLoader()) {
			Object file = owningFileOf(seed(game, mods), "unlitcampfire");
			Method element = file.getClass().getMethod("getConfigElement", String[].class);
			assertEquals(Optional.of(Map.of(CAMPFIRE_OPTION, false)),
					element.invoke(file, (Object) new String[] {"lithium:options"}));
			assertEquals(Optional.of("https://github.com/cech12/UnlitCampfire/issues"),
					element.invoke(file, (Object) new String[] {"issueTrackerURL"}));
			assertEquals(Optional.empty(), element.invoke(file, (Object) new String[] {"absent"}));
			// Natively getConfig() on a ModFileInfo is the file itself, which delegates to the same wrapper.
			Object config = file.getClass().getMethod("getConfig").invoke(file);
			assertEquals(Optional.of("The MIT License (MIT)"), config.getClass().getMethod("getConfigElement", String[].class)
					.invoke(config, (Object) new String[] {"license"}));

			System.setProperty(FmlConfigElements.SWITCH, "off");
			Object before = owningFileOf(seed(game, mods), "unlitcampfire");
			assertEquals(Optional.empty(), element.invoke(before, (Object) new String[] {"lithium:options"}),
					"switched off: what every seeded file answered");
		}
	}

	/** A Fabric mod carries no mods.toml table, so a file listed for its presence still declares nothing. */
	@Test
	void aFabricModsFileDeclaresNothing() throws Exception {
		Path universal = tmp.resolve("universal.jar");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(universal))) {
			KernelModLoaderDeclaredTest.put(zip, "META-INF/neoforge.mods.toml", fixture("unlitcampfire.neoforge.mods.toml"));
			KernelModLoaderDeclaredTest.put(zip, "fabric.mod.json",
					"{\"schemaVersion\":1,\"id\":\"unlitcampfire\",\"version\":\"1\"}");
		}
		List<DiscoveredMod> both = new ForbricModDiscoverer().discoverJar(universal);
		DiscoveredMod fabric = both.stream().filter(m -> m.getEcosystem() == Ecosystem.FABRIC).findFirst().orElseThrow();
		DiscoveredMod neo = both.stream().filter(m -> m.getEcosystem() == Ecosystem.NEOFORGE).findFirst().orElseThrow();
		assertTrue(fabric.getFileConfigElements().isEmpty(), "Fabric metadata has no mods.toml top level");
		assertTrue(neo.getFileConfigElements().containsKey("lithium:options"));
	}

	// --- helpers --------------------------------------------------------------------------------------------

	/** Seeds a real NeoForge {@code FMLLoader} with the list, makes it current, and runs Lithium's override walk. */
	private List<String> lithiumOverrides(Path mods, Path lithiumJar) throws Exception {
		try (URLClassLoader game = neoForgeLoader();
				URLClassLoader lithium = new URLClassLoader(new URL[] {lithiumJar.toUri().toURL()}, game)) {
			Class<?> fmlLoader = Class.forName("net.neoforged.fml.loading.FMLLoader", true, game);
			Object loader = allocate(fmlLoader);
			PassiveSeeder.seedNeoForgeLoadingModList(game, fmlLoader, loader, mods, List.of());
			currentOf(fmlLoader).set(loader);
			try {
				Object overrides = Class.forName("net.caffeinemc.mods.lithium.neoforge.NeoForgeMixinOverrides", true, lithium)
						.getConstructor().newInstance();
				List<String> out = new ArrayList<>();
				for (Object override : (List<?>) overrides.getClass().getMethod("applyModOverrides").invoke(overrides)) {
					out.add(call(override, "modId") + " " + call(override, "option") + "=" + call(override, "enabled"));
				}
				return out;
			} finally {
				currentOf(fmlLoader).set(null);
			}
		}
	}

	private Object seed(ClassLoader game, Path mods) throws Exception {
		PassiveSeederLoadingModListTest.FakeFmlLoader loader = new PassiveSeederLoadingModListTest.FakeFmlLoader();
		PassiveSeeder.seedNeoForgeLoadingModList(game, PassiveSeederLoadingModListTest.FakeFmlLoader.class, loader, mods,
				List.of());
		return PassiveSeederLoadingModListTest.seededList(loader);
	}

	/** Lithium's own chain: {@code (ModInfo) mod} from {@code getMods()}, then its concrete {@code getOwningFile()}. */
	private static Object owningFileOf(Object list, String modId) throws Exception {
		for (Object info : (List<?>) PassiveSeederLoadingModListTest.call(list, "getMods")) {
			if (modId.equals(PassiveSeederLoadingModListTest.call(info, "getModId"))) {
				Object file = PassiveSeederLoadingModListTest.call(info, "getOwningFile");
				assertEquals("net.neoforged.fml.loading.moddiscovery.ModFileInfo", file.getClass().getName());
				return file;
			}
		}
		throw new AssertionError(modId + " is not in the seeded list");
	}

	private static Object call(Object target, String method) throws Exception {
		Method m = target.getClass().getMethod(method);
		m.setAccessible(true);
		return m.invoke(target);
	}

	@SuppressWarnings("unchecked")
	private static AtomicReference<Object> currentOf(Class<?> fmlLoader) throws Exception {
		Field current = fmlLoader.getDeclaredField("current");
		current.setAccessible(true);
		return (AtomicReference<Object>) current.get(null);
	}

	/** An FMLLoader with no constructor run: the seeder only writes its {@code loadingModList}. */
	private static Object allocate(Class<?> type) throws Exception {
		Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		Object unsafe = unsafeField.get(null);
		return unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
	}

	private URLClassLoader neoForgeLoader() throws Exception {
		assumeTrue(Files.isRegularFile(PassiveSeederLoadingModListTest.NEO_RUNTIME), "staged neoforge-runtime.jar absent");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(tmp.resolve("stubs"));
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), PassiveSeederLoadingModListTest.NEO_RUNTIME.toUri()
				.toURL()}, ClassLoader.getPlatformClassLoader());
	}

	private static Path jar(Path jar, String neoToml) throws Exception {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			KernelModLoaderDeclaredTest.put(zip, "META-INF/neoforge.mods.toml", neoToml);
		}
		return jar;
	}

	private static String fixture(String name) throws Exception {
		try (InputStream in = PassiveSeederFileConfigTest.class.getResourceAsStream("/forge/" + name)) {
			assertNotNull(in, name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
