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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.OutputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.util.TreeMap;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.api.ModPresence;
import net.forbric.kernel.discovery.ForbricModDiscoverer;
import net.forbric.kernel.metadata.forge.FmlConfigElements;

/** wthit's read: getModInfo().getOwningFile().getConfig().getConfigElement("issueTrackerURL") answers from the jar's mods.toml. */
class KernelForgeModInfoTest {
	private static final String TOML = "modLoader = \"javafml\"\nloaderVersion = \"[38,)\"\n"
			+ "issueTrackerURL = \"https://github.com/badasintended/wthit/issues\"\nlicense = \"CC\"\n\n"
			+ "[[mods]]\nmodId = \"wthit\"\nversion = \"20.0.0\"\ndescription = \"What the hell is that?\"\n\n"
			+ "[[mods]]\nmodId = \"waila\"\nversion = \"20.0.0\"\ndescription = \"Actually WTHIT\"\n\n"
			+ "[[dependencies.wthit]]\nmodId = \"forge\"\n";

	@Test
	void theOwningFileAnswersTheTopLevelKeysAndTheModItsOwnTable(@TempDir Path dir) throws Exception {
		Path jar = jar(dir.resolve("wthit.jar"), TOML);
		try (URLClassLoader cl = gameSideLoader()) {
			Object info = Class.forName("net.forbric.kernel.runtime.KernelForgeModInfo", true, cl)
					.getConstructor(String.class, Path.class).newInstance("wthit", jar);
			Object file = call(info, "getOwningFile");
			assertNotNull(file, "the owning file used to be null, and wthit's static initialiser died on it");
			Object fileConfig = call(file, "getConfig");
			assertEquals(Optional.of("https://github.com/badasintended/wthit/issues"), element(fileConfig, "issueTrackerURL"));
			assertEquals(Optional.empty(), element(fileConfig, "absent"));
			assertEquals("CC", call(file, "getLicense"));
			assertSame(info, ((List<?>) call(file, "getMods")).get(0), "the file's one mod is this info");
			Method list = fileConfig.getClass().getMethod("getConfigList", String[].class);
			list.setAccessible(true);
			assertEquals(2, ((List<?>) list.invoke(fileConfig, (Object) new String[] { "mods" })).size());

			Object modConfig = call(info, "getConfig");
			assertEquals(Optional.of("What the hell is that?"), element(modConfig, "description"), "its OWN [[mods]] table, not waila's");
		}
	}

	/**
	 * A MinecraftForge mod's {@code [modproperties]} reach a reader with MinecraftForge's own value types. FML builds
	 * that map as an {@code ImmutableMap} of the table's entries — shallow, so a nested table is still
	 * night-config's {@code Config} and an array of tables a {@code List} of them. The kernel handed
	 * {@code LinkedHashMap}s, which a {@code (Config)} cast rejects.
	 */
	@Test
	void modPropertiesKeepMinecraftForgesValueTypes(@TempDir Path dir) throws Exception {
		Path jar = jar(dir.resolve("iceberg.jar"), "modLoader = \"javafml\"\nloaderVersion = \"[65,)\"\nlicense = \"MIT\"\n\n"
				+ "[[mods]]\nmodId = \"iceberg\"\nversion = \"1.4.2.2\"\n\n"
				+ "[modproperties.iceberg]\n"
				+ "configuredProviders=[\n\"com.anthonyhilyard.iceberg.compat.configured.IcebergConfigProvider\"\n]\n"
				+ "[modproperties.iceberg.nested]\ninner = \"yes\"\n"
				+ "[[modproperties.iceberg.entries]]\nvalue = \"example.Entry\"\n");
		ModPresence.publishForgeFamily(new ForbricModDiscoverer().discoverJar(jar));
		try (URLClassLoader cl = gameSideLoader()) {
			Object info = Class.forName("net.forbric.kernel.runtime.KernelForgeModInfo", true, cl)
					.getConstructor(String.class, Path.class).newInstance("iceberg", jar);
			Map<?, ?> properties = (Map<?, ?>) call(info, "getModProperties");
			assertEquals(List.of("com.anthonyhilyard.iceberg.compat.configured.IcebergConfigProvider"),
					properties.get("configuredProviders"), "Iceberg's real declaration, a list of strings");
			assertEquals("yes", ((Config) properties.get("nested")).get("inner"));
			assertEquals("example.Entry", ((Config) ((List<?>) properties.get("entries")).get(0)).get("value"));
		} finally {
			ModPresence.publishForgeFamily(List.of());
		}
	}

	/**
	 * wthit's real {@code mods.toml}, through the kernel's owning file and each of its two mods, against
	 * MinecraftForge's own {@code NightConfigWrapper} over the same file: every key answers the same value of the same
	 * class — a table as Guava's {@code ImmutableMap}. The line-by-line reading this replaced answered strings only,
	 * and lost {@code waila}'s description outright: it has escaped quotes in it.
	 */
	@Test
	void wthitsRealManifestAnswersEveryKeyAsMinecraftForgeDoes(@TempDir Path dir) throws Exception {
		String toml = KernelModInfoConfigTest.fixture("wthit.mods.toml");
		Path jar = jar(dir.resolve("wthit.jar"), toml);
		UnmodifiableConfig root = new TomlParser().parse(toml);
		try (URLClassLoader cl = gameSideLoader()) {
			Object wthit = Class.forName("net.forbric.kernel.runtime.KernelForgeModInfo", true, cl)
					.getConstructor(String.class, Path.class).newInstance("wthit", jar);
			Object fileConfig = call(call(wthit, "getOwningFile"), "getConfig");
			Object fileWrapper = forgeWrapper(cl, root);
			for (Map.Entry<String, Object> key : new TreeMap<>(root.valueMap()).entrySet()) {
				if (key.getValue() instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof UnmodifiableConfig) continue;
				assertSameAnswer(element(fileWrapper, key.getKey()), element(fileConfig, key.getKey()), "file " + key.getKey());
			}
			assertTrue(Class.forName("com.google.common.collect.ImmutableMap", false, cl)
					.isInstance(((Optional<?>) element(fileConfig, "dependencies")).orElseThrow()),
					"a table comes back as MinecraftForge's ImmutableMap");

			for (Object table : (List<?>) root.get(List.of("mods"))) {
				UnmodifiableConfig entry = (UnmodifiableConfig) table;
				String modId = entry.get("modId");
				Object info = Class.forName("net.forbric.kernel.runtime.KernelForgeModInfo", true, cl)
						.getConstructor(String.class, Path.class).newInstance(modId, jar);
				Object modConfig = call(info, "getConfig");
				Object entryWrapper = forgeWrapper(cl, entry);
				for (String key : new TreeMap<>(entry.valueMap()).keySet()) {
					assertSameAnswer(element(entryWrapper, key), element(modConfig, key), modId + " " + key);
				}
			}
			Object waila = Class.forName("net.forbric.kernel.runtime.KernelForgeModInfo", true, cl)
					.getConstructor(String.class, Path.class).newInstance("waila", jar);
			assertEquals(Optional.of("Actually WTHIT but with \"waila\" as it's id lmao"),
					element(call(waila, "getConfig"), "description"));
			assertEquals("CC-BY-NC-SA-4.0", call(call(waila, "getOwningFile"), "getLicense"));
		}
	}

	/** Switched off, the owning file and each mod are read the strings-only way again, as they were. */
	@Test
	void switchedOffTheManifestIsReadAsStringsAgain(@TempDir Path dir) throws Exception {
		System.setProperty(FmlConfigElements.SWITCH, "off");
		try {
			Path jar = jar(dir.resolve("wthit.jar"), KernelModInfoConfigTest.fixture("wthit.mods.toml"));
			try (URLClassLoader cl = gameSideLoader()) {
				Object waila = Class.forName("net.forbric.kernel.runtime.KernelForgeModInfo", true, cl)
						.getConstructor(String.class, Path.class).newInstance("waila", jar);
				assertEquals(Optional.empty(), element(call(waila, "getConfig"), "description"));
				Object fileConfig = call(call(waila, "getOwningFile"), "getConfig");
				assertEquals(Optional.of("https://github.com/badasintended/wthit/issues"), element(fileConfig, "issueTrackerURL"));
				assertEquals(Optional.empty(), element(fileConfig, "dependencies"));
			}
		} finally {
			System.clearProperty(FmlConfigElements.SWITCH);
		}
	}

	private static void assertSameAnswer(Object nativeAnswer, Object kernelAnswer, String what) {
		assertEquals(KernelModInfoConfigTest.shape(nativeAnswer), KernelModInfoConfigTest.shape(kernelAnswer), what);
		if (nativeAnswer instanceof Optional<?> present && present.isPresent()) {
			assertEquals(present.get().getClass(), ((Optional<?>) kernelAnswer).orElseThrow().getClass(), what);
		}
	}

	/** MinecraftForge's own wrapper over {@code config}; the class is package-private, its constructor public. */
	private static Object forgeWrapper(ClassLoader cl, UnmodifiableConfig config) throws Exception {
		java.lang.reflect.Constructor<?> ctor = Class.forName("net.minecraftforge.fml.loading.moddiscovery.NightConfigWrapper",
				true, cl).getConstructor(UnmodifiableConfig.class);
		ctor.setAccessible(true);
		return ctor.newInstance(config);
	}

	@Test
	void aJarWithoutAModsTomlStillHasAnOwningFileThatDeclaresNothing(@TempDir Path dir) throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Object info = Class.forName("net.forbric.kernel.runtime.KernelForgeModInfo", true, cl)
					.getConstructor(String.class, Path.class).newInstance("bare", (Path) null);
			Object file = call(info, "getOwningFile");
			assertNotNull(file);
			assertEquals(Optional.empty(), element(call(file, "getConfig"), "issueTrackerURL"));
			assertTrue(((List<?>) call(file, "getMods")).contains(info));
		}
	}

	/** The kernel's twins are package-private classes; their public interface methods are reached with access opened. */
	private static Object call(Object target, String method) throws Exception {
		Method m = target.getClass().getMethod(method);
		m.setAccessible(true);
		return m.invoke(target);
	}

	private static Object element(Object configurable, String key) throws Exception {
		Method m = configurable.getClass().getMethod("getConfigElement", String[].class);
		m.setAccessible(true);
		return m.invoke(configurable, (Object) new String[] { key });
	}

	private static Path jar(Path file, String toml) throws Exception {
		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/mods.toml"));
			zip.write(toml.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return file;
	}

	private static URLClassLoader gameSideLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		Path run = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
		Path forgeRt = run.resolve("forge-runtime/forge-runtime.jar");
		Path neoRt = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path merged = run.resolve("merged-base/patched-mc-merged-26.2.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(forgeRt) && Files.isRegularFile(neoRt) && Files.isRegularFile(merged),
				"the game-side set is not compiled, or the staged artifacts are absent");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), forgeRt.toUri().toURL(), neoRt.toUri().toURL(), merged.toUri().toURL()));
		for (String pattern : List.of("com/mojang/datafixerupper", "com/google/code/gson", "com/mojang/brigadier", "com/google/guava/guava",
				"it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api", "org/apache/maven/maven-artifact",
				"io/netty/netty-common", "io/netty/netty-buffer", "io/netty/netty-codec", "io/netty/netty-transport", "io/netty/netty-handler",
				"org/joml/joml", "com/mojang/authlib", "org/apache/commons/commons-lang3")) {
			Path library = newestUnder(pattern);
			if (library != null) urls.add(library.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), KernelForgeModInfoTest.class.getClassLoader());
	}

	static Path newestUnder(String pattern) throws java.io.IOException {
		String env = System.getenv("MC_DIR");
		Path root = Path.of(env != null ? env + "/libraries" : System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries");
		Path under = root.resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}
}
