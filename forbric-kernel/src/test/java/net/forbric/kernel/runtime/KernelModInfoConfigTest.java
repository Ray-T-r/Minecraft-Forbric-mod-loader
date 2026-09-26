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

package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.DiscoveredMod;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.metadata.forge.FmlConfigElements;

/**
 * The kernel's own NeoForge {@code IModInfo} — what {@code ModList.get().getMods()} holds for every mod the kernel
 * constructed — answers {@code getConfig()} from the mod's {@code [[mods]]} entry and its owning file's
 * {@code getConfig()} from the file's top level, as NeoForge's {@code ModInfo}/{@code ModFileInfo} do.
 *
 * <p>The readers are real. Not Enough Crashes builds its description of every mod it names from
 * {@code getOwningFile().getConfig().getConfigElement("issueTrackerURL")} and {@code getConfig().getConfigElement(
 * "authors")}; the first was null and threw. Puzzles Lib reads {@code authors}, {@code credits} and {@code displayURL}
 * off {@code getConfig()}, and got a mod that declared none.
 */
class KernelModInfoConfigTest {
	private static final Path STAGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run");
	private static final Path NEC = Path.of("build/compat-inputs/sweep90/mods/notenoughcrashes-neoforge-4.4.9+26.2.jar");
	private static final Path PUZZLES = Path.of("run/client-popular/mods/PuzzlesLib-v26.2.4-mc26.2.x-NeoForge.jar");

	@TempDir
	Path tmp;

	@AfterEach
	void clear() {
		System.clearProperty(FmlConfigElements.SWITCH);
	}

	/**
	 * Not Enough Crashes' own {@code ForgePlatform.toCommon}, run on a kernel mod info built from its real jar: the
	 * issue page and the authors its own manifest declares. Switched off, it throws the NullPointerException it threw
	 * before.
	 */
	@Test
	void notEnoughCrashesDescribesAKernelModAsNeoForgeLetsIt() throws Exception {
		assumeTrue(Files.isRegularFile(NEC), "the sweep's Not Enough Crashes jar is not here");
		DiscoveredMod declared = new ForbricModDiscoverer().discoverJar(NEC).get(0);
		try (URLClassLoader runtime = runtimeLoader(); URLClassLoader nec = new URLClassLoader(new URL[] {NEC.toUri().toURL()}, runtime)) {
			Method toCommon = Class.forName("fudge.notenoughcrashes.forge.platform.ForgePlatform", true, nec)
					.getDeclaredMethod("toCommon", Class.forName("net.neoforged.neoforgespi.language.IModInfo", false, runtime));
			toCommon.setAccessible(true);

			Object metadata = toCommon.invoke(null, modInfo(runtime, "notenoughcrashes", NEC, declared));
			assertEquals("https://github.com/natanfudge/Not-Enough-Crashes/issues", call(metadata, "issuesPage"));
			assertEquals(List.of("Fudge"), call(metadata, "authors"));
			assertEquals(NEC, call(metadata, "rootPath"));

			System.setProperty(FmlConfigElements.SWITCH, "off");
			Object before = modInfo(runtime, "notenoughcrashes", NEC, declared);
			InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> toCommon.invoke(null, before));
			assertInstanceOf(NullPointerException.class, thrown.getCause(), "getOwningFile().getConfig() was null");
		}
	}

	/** Puzzles Lib's own {@code NeoForgeModContainer} on a kernel mod info built from its real jar. */
	@Test
	void puzzlesLibReadsItsAuthorsAndHomepage() throws Exception {
		assumeTrue(Files.isRegularFile(PUZZLES), "the popular pack's Puzzles Lib jar is not here");
		DiscoveredMod declared = new ForbricModDiscoverer().discoverJar(PUZZLES).get(0);
		Path guava = KernelForgeModInfoTest.newestUnder("com/google/guava/guava");
		assumeTrue(guava != null, "no Guava in the local Minecraft library tree");
		try (URLClassLoader runtime = runtimeLoader();
				URLClassLoader puzzles = new URLClassLoader(new URL[] {PUZZLES.toUri().toURL(), guava.toUri().toURL()}, runtime)) {
			Class<?> iModInfo = Class.forName("net.neoforged.neoforgespi.language.IModInfo", false, runtime);
			Object container = Class.forName("fuzs.puzzleslib.neoforge.impl.core.NeoForgeModContainer", true, puzzles)
					.getConstructor(iModInfo).newInstance(modInfo(runtime, "puzzleslib", PUZZLES, declared));

			assertEquals(List.of("Fuzs"), call(container, "getAuthors"));
			assertEquals(List.of(), call(container, "getCredits"));
			assertEquals(Map.of("homepage", "https://modrinth.com/mod/puzzles-lib"), call(container, "getContactTypes"));

			System.setProperty(FmlConfigElements.SWITCH, "off");
			Object before = Class.forName("fuzs.puzzleslib.neoforge.impl.core.NeoForgeModContainer", true, puzzles)
					.getConstructor(iModInfo).newInstance(modInfo(runtime, "puzzleslib", PUZZLES, declared));
			assertEquals(List.of(), call(before, "getAuthors"), "switched off: a mod that declares no authors");
		}
	}

	/**
	 * Every key of Not Enough Crashes' real manifest, through {@code KernelModInfo.getConfig()} and its owning file's
	 * {@code getConfig()}, against NeoForge's own {@code NightConfigWrapper} over the same file: the same value of the
	 * same class. Arrays of tables are left out; the wrapper throws for them and nothing asks.
	 */
	@Test
	void everyKeyAnswersAsNeoForgesWrapperDoes() throws Exception {
		String toml = fixture("notenoughcrashes.neoforge.mods.toml");
		Path jar = jar(tmp.resolve("nec.jar"), toml);
		DiscoveredMod declared = new ForbricModDiscoverer().discoverJar(jar).get(0);
		UnmodifiableConfig root = TomlFormat.instance().createParser().parse(toml).unmodifiable();
		UnmodifiableConfig entry = (UnmodifiableConfig) ((List<?>) root.get(List.of("mods"))).get(0);

		try (URLClassLoader runtime = runtimeLoader()) {
			Object info = modInfo(runtime, "notenoughcrashes", jar, declared);
			Object modConfig = call(info, "getConfig");
			Object fileConfig = call(call(info, "getOwningFile"), "getConfig");
			assertNotNull(fileConfig, "the owning file's config used to be null");
			Object fileWrapper = wrapper(runtime, root);
			Object entryWrapper = wrapper(runtime, entry);

			for (Map.Entry<String, Object> key : new TreeMap<>(root.valueMap()).entrySet()) {
				if (key.getValue() instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof UnmodifiableConfig) continue;
				assertSameAnswer(element(fileWrapper, key.getKey()), element(fileConfig, key.getKey()), "file " + key.getKey());
			}
			for (String key : new TreeMap<>(entry.valueMap()).keySet()) {
				assertSameAnswer(element(entryWrapper, key), element(modConfig, key), "[[mods]] " + key);
			}
			assertEquals(Optional.empty(), element(modConfig, "absent"));
			assertEquals(List.of(), modConfig.getClass().getMethod("getConfigList", String[].class)
					.invoke(modConfig, (Object) new String[] {"mods"}));
		}
	}

	@Test
	void switchedOffTheFileHasNoConfigAndTheModDeclaresNothing() throws Exception {
		System.setProperty(FmlConfigElements.SWITCH, "off");
		Path jar = jar(tmp.resolve("nec.jar"), fixture("notenoughcrashes.neoforge.mods.toml"));
		DiscoveredMod declared = new ForbricModDiscoverer().discoverJar(jar).get(0);
		try (URLClassLoader runtime = runtimeLoader()) {
			Object info = modInfo(runtime, "notenoughcrashes", jar, declared);
			assertNull(call(call(info, "getOwningFile"), "getConfig"), "null, as it was");
			assertEquals(Optional.empty(), element(call(info, "getConfig"), "authors"));
		}
	}

	// --- helpers --------------------------------------------------------------------------------------------

	private static void assertSameAnswer(Object nativeAnswer, Object kernelAnswer, String what) {
		assertEquals(shape(nativeAnswer), shape(kernelAnswer), what);
		if (nativeAnswer instanceof Optional<?> present && present.isPresent()) {
			assertEquals(present.get().getClass(), ((Optional<?>) kernelAnswer).orElseThrow().getClass(), what);
		}
	}

	/** The type a reader can rely on, all the way down; {@code Config} kept apart from other maps, as a cast is. */
	static String shape(Object value) {
		if (value instanceof Optional<?> optional) return optional.map(v -> "Optional[" + shape(v) + "]").orElse("empty");
		if (value instanceof com.electronwill.nightconfig.core.Config config) return "Config" + entries(config.valueMap());
		if (value instanceof UnmodifiableConfig config) return "UnmodifiableConfig" + entries(config.valueMap());
		if (value instanceof Map<?, ?> map) return "Map" + entries(map);
		if (value instanceof List<?> list) {
			List<String> out = new ArrayList<>();
			for (Object element : list) out.add(shape(element));
			return "List" + out;
		}
		return value == null ? "null" : value.getClass().getSimpleName() + "(" + value + ")";
	}

	private static String entries(Map<?, ?> map) {
		Map<String, String> sorted = new TreeMap<>();
		for (Map.Entry<?, ?> entry : map.entrySet()) sorted.put(String.valueOf(entry.getKey()), shape(entry.getValue()));
		return sorted.toString();
	}

	private static Object modInfo(ClassLoader runtime, String modId, Path jar, DiscoveredMod declared) throws Exception {
		return Class.forName("net.forbric.kernel.runtime.KernelModInfo", true, runtime)
				.getConstructor(String.class, Path.class, DiscoveredMod.class).newInstance(modId, jar, declared);
	}

	private static Object wrapper(ClassLoader runtime, UnmodifiableConfig config) throws Exception {
		return Class.forName("net.neoforged.fml.loading.moddiscovery.NightConfigWrapper", true, runtime)
				.getConstructor(UnmodifiableConfig.class).newInstance(config);
	}

	private static Object element(Object configurable, String... path) throws Exception {
		Method m = configurable.getClass().getMethod("getConfigElement", String[].class);
		m.setAccessible(true);
		return m.invoke(configurable, (Object) path);
	}

	private static Object call(Object target, String method) throws Exception {
		Method m = target.getClass().getMethod(method);
		m.setAccessible(true);
		return m.invoke(target);
	}

	private static Path jar(Path jar, String toml) throws Exception {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
			zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
			zip.write(toml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	static String fixture(String name) throws Exception {
		try (InputStream in = KernelModInfoConfigTest.class.getResourceAsStream("/forge/" + name)) {
			assertNotNull(in, name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** The compiled game side plus the NeoForge carrier, over this suite's own classes (one night-config, one API). */
	private static URLClassLoader runtimeLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		Path carrier = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(carrier), "staged runtime classes/carrier absent");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), carrier.toUri().toURL()));
		return new URLClassLoader(urls.toArray(new URL[0]), KernelModInfoConfigTest.class.getClassLoader());
	}
}
