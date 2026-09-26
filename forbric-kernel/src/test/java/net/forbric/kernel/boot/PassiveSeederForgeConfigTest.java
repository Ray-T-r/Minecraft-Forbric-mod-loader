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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipOutputStream;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.DiscoveredMod;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.metadata.forge.FmlConfigElements;

/**
 * The seeded MinecraftForge {@code LoadingModList} — built with MinecraftForge's own {@code ModFile},
 * {@code ModFileInfo} and {@code ModInfo} record — answers {@code getConfigElement} as MinecraftForge does: the file
 * from the whole {@code mods.toml}, each mod from its own {@code [[mods]]} entry, a table as Guava's
 * {@code ImmutableMap}. Both answered empty.
 *
 * <p>The oracle is MinecraftForge's {@code NightConfigWrapper} from the same carrier, over the same parsed file.
 */
class PassiveSeederForgeConfigTest {
	private static final Path FORGE_RUNTIME = Path.of(System.getenv().getOrDefault("FORBRIC_OLD",
			System.getProperty("user.dir") + "/../forbric-loader"), "run", "forge-runtime", "forge-runtime.jar").normalize();

	@TempDir
	Path tmp;

	@AfterEach
	void reset() {
		System.clearProperty(FmlConfigElements.SWITCH);
	}

	@Test
	void theSeededFileAndModsAnswerAsMinecraftForgeDoes() throws Exception {
		String toml = fixture("wthit.mods.toml");
		List<DiscoveredMod> mods = new ForbricModDiscoverer().discoverJar(jar(tmp.resolve("wthit.jar"), toml));
		UnmodifiableConfig root = new TomlParser().parse(toml);

		try (URLClassLoader game = forgeLoader()) {
			PassiveSeeder.ForgeLoadingLists lists = PassiveSeeder.buildForgeLoadingLists(game, mods);
			Object fileInfo = call(lists.files().get(0), "getModFileInfo");
			assertSame(fileInfo, call(fileInfo, "getConfig"), "MinecraftForge's file is its own IConfigurable");

			Object fileWrapper = wrapper(game, root);
			for (Map.Entry<String, Object> key : new TreeMap<>(root.valueMap()).entrySet()) {
				if (key.getValue() instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof UnmodifiableConfig) continue;
				assertSameAnswer(element(fileWrapper, key.getKey()), element(fileInfo, key.getKey()), "file " + key.getKey());
				assertSameAnswer(element(fileWrapper, key.getKey()), single(fileInfo, key.getKey()), "file(String) " + key.getKey());
			}
			assertEquals(Optional.of("https://github.com/badasintended/wthit/issues"), single(fileInfo, "issueTrackerURL"),
					"wthit's own read, through MinecraftForge's single-key overload");
			assertTrue(Class.forName("com.google.common.collect.ImmutableMap", false, game)
					.isInstance(((Optional<?>) element(fileInfo, "dependencies")).orElseThrow()));

			List<?> entries = (List<?>) root.get(List.of("mods"));
			for (int i = 0; i < entries.size(); i++) {
				UnmodifiableConfig entry = (UnmodifiableConfig) entries.get(i);
				Object modInfo = lists.modInfos().get(i);
				Object entryWrapper = wrapper(game, entry);
				for (String key : new TreeMap<>(entry.valueMap()).keySet()) {
					if (key.equals("version")) continue; // the seeded entry carries the resolved version
					assertSameAnswer(element(entryWrapper, key), element(call(modInfo, "getConfig"), key), key);
					assertSameAnswer(element(entryWrapper, key), element(modInfo, key), "record " + key);
				}
			}
			assertEquals(Optional.of("deirn, TehNut, ProfMobius"), element(call(lists.modInfos().get(1), "getConfig"), "authors"));
		}
	}

	@Test
	void switchedOffTheyAnswerNothing() throws Exception {
		System.setProperty(FmlConfigElements.SWITCH, "off");
		List<DiscoveredMod> mods = new ForbricModDiscoverer().discoverJar(jar(tmp.resolve("wthit.jar"), fixture("wthit.mods.toml")));
		try (URLClassLoader game = forgeLoader()) {
			PassiveSeeder.ForgeLoadingLists lists = PassiveSeeder.buildForgeLoadingLists(game, mods);
			assertEquals(Optional.empty(), single(call(lists.files().get(0), "getModFileInfo"), "issueTrackerURL"));
			assertEquals(Optional.empty(), element(call(lists.modInfos().get(0), "getConfig"), "authors"));
		}
	}

	// --- helpers --------------------------------------------------------------------------------------------

	private static void assertSameAnswer(Object nativeAnswer, Object kernelAnswer, String what) {
		assertEquals(FmlTomlShapeOracleTest.shape(nativeAnswer), FmlTomlShapeOracleTest.shape(kernelAnswer), what);
		if (nativeAnswer instanceof Optional<?> present && present.isPresent()) {
			assertEquals(present.get().getClass(), ((Optional<?>) kernelAnswer).orElseThrow().getClass(), what);
		}
	}

	private static Object wrapper(ClassLoader game, UnmodifiableConfig config) throws Exception {
		Constructor<?> ctor = Class.forName("net.minecraftforge.fml.loading.moddiscovery.NightConfigWrapper", true, game)
				.getConstructor(UnmodifiableConfig.class);
		ctor.setAccessible(true);
		return ctor.newInstance(config);
	}

	private static Object element(Object configurable, String... path) throws Exception {
		return FmlTomlShapeOracleTest.element(configurable, path);
	}

	/** MinecraftForge's extra single-key overload, which wthit calls. */
	private static Object single(Object configurable, String key) throws Exception {
		Method m = configurable.getClass().getMethod("getConfigElement", String.class);
		m.setAccessible(true);
		return m.invoke(configurable, key);
	}

	private static Object call(Object target, String method) throws Exception {
		Method m = target.getClass().getMethod(method);
		m.setAccessible(true);
		return m.invoke(target);
	}

	/**
	 * The MinecraftForge carrier, the two libraries Minecraft gives it (Guava, which its wrapper and the seeder's copy
	 * link against, and Commons Lang, which its version parser does) and the logging stand-ins, over this suite's own
	 * classes so there is one night-config — as at runtime.
	 */
	private URLClassLoader forgeLoader() throws Exception {
		assumeTrue(Files.isRegularFile(FORGE_RUNTIME), "staged forge-runtime.jar absent");
		Path guava = newestLibrary("com/google/guava/guava");
		Path lang = newestLibrary("org/apache/commons/commons-lang3");
		assumeTrue(guava != null && lang != null, "no Guava / Commons Lang in the local Minecraft library tree");
		Path stubs = PassiveSeederLoadingModListTest.loggingStubs(tmp.resolve("stubs"));
		return new URLClassLoader(new URL[] {stubs.toUri().toURL(), FORGE_RUNTIME.toUri().toURL(), guava.toUri().toURL(),
				lang.toUri().toURL()}, PassiveSeederForgeConfigTest.class.getClassLoader());
	}

	private static Path newestLibrary(String under) throws java.io.IOException {
		String env = System.getenv("MC_DIR");
		Path root = Path.of(env != null ? env + "/libraries"
				: System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries").resolve(under);
		if (!Files.isDirectory(root)) return null;
		try (var stream = Files.walk(root)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}

	private static Path jar(Path jar, String toml) throws Exception {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			KernelModLoaderDeclaredTest.put(zip, "META-INF/mods.toml", toml);
		}
		return jar;
	}

	private static String fixture(String name) throws Exception {
		try (InputStream in = PassiveSeederForgeConfigTest.class.getResourceAsStream("/forge/" + name)) {
			assertNotNull(in, name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
