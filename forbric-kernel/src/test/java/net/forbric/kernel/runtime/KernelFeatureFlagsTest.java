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

import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A NeoForge jar's declared flags land in the registry builder; a foreign namespace is refused; off registers nothing. */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelFeatureFlagsTest {
	@AfterEach
	void reset() {
		System.clearProperty("forbric.moddedFeatureFlags");
	}

	@Test
	void aDeclaredFlagIsRegisteredAndAForeignOneIsNot(@TempDir Path dir) throws Exception {
		Path jar = modJar(dir.resolve("tofu.jar"), "tofu", "{\"flags\": [\"tofu:extra\", {\"flag\": \"tofu:more\"}, \"other:stolen\"]}");
		try (URLClassLoader cl = gameSideLoader()) {
			Map<?, ?> flags = flagsAfter(cl, List.of(jar));
			Method parse = Class.forName("net.minecraft.resources.Identifier", true, cl).getMethod("parse", String.class);
			assertTrue(flags.containsKey(parse.invoke(null, "tofu:extra")), flags.toString());
			assertTrue(flags.containsKey(parse.invoke(null, "tofu:more")), "an object entry with a flag key");
			assertFalse(flags.containsKey(parse.invoke(null, "other:stolen")), "a namespace no mod in the jar owns");
		}
	}

	@Test
	void switchedOffNothingIsRegistered(@TempDir Path dir) throws Exception {
		System.setProperty("forbric.moddedFeatureFlags", "off");
		Path jar = modJar(dir.resolve("tofu.jar"), "tofu", "{\"flags\": [\"tofu:extra\"]}");
		try (URLClassLoader cl = gameSideLoader()) {
			Map<?, ?> flags = flagsAfter(cl, List.of(jar));
			Method parse = Class.forName("net.minecraft.resources.Identifier", true, cl).getMethod("parse", String.class);
			assertFalse(flags.containsKey(parse.invoke(null, "tofu:extra")));
		}
	}

	private static Map<?, ?> flagsAfter(URLClassLoader cl, List<Path> jars) throws Exception {
		Class<?> helper = Class.forName("net.forbric.kernel.runtime.KernelFeatureFlags", true, cl);
		Method bind = helper.getDeclaredMethod("bindJarsForTest", List.class);
		bind.setAccessible(true);
		bind.invoke(null, jars);
		Class<?> builderClass = Class.forName("net.minecraft.world.flag.FeatureFlagRegistry$Builder", true, cl);
		Object builder = builderClass.getConstructor(String.class).newInstance("test");
		helper.getMethod("loadModdedFlags", builderClass).invoke(null, builder);
		Object registry = builderClass.getMethod("build").invoke(builder);
		return (Map<?, ?>) registry.getClass().getMethod("getAllFlags").invoke(registry);
	}

	private static Path modJar(Path file, String modId, String flagsJson) throws Exception {
		try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("META-INF/neoforge.mods.toml"));
			zip.write(("modLoader=\"javafml\"\nloaderVersion=\"[3,)\"\nlicense=\"x\"\nfeatureFlags = \"META-INF/feature_flags.json\"\n\n[[mods]]\nmodId=\""
					+ modId + "\"\nversion=\"1\"\n").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("META-INF/feature_flags.json"));
			zip.write(flagsJson.getBytes(StandardCharsets.UTF_8));
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
				"it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api",
				"io/netty/netty-common", "io/netty/netty-buffer", "io/netty/netty-codec", "io/netty/netty-transport", "io/netty/netty-handler",
				"org/joml/joml", "com/mojang/authlib", "org/apache/commons/commons-lang3")) {
			Path library = newestUnder(pattern);
			assumeTrue(library != null, "no staged " + pattern + " jar in the local Minecraft libraries");
			urls.add(library.toUri().toURL());
		}
		for (String pattern : List.of("org/apache/commons/commons-io", "org/apache/commons/commons-codec", "com/mojang/logging",
				"org/apache/logging/log4j/log4j-core")) {
			Path library = newestUnder(pattern);
			if (library != null) urls.add(library.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), KernelFeatureFlagsTest.class.getClassLoader());
	}

	private static Path newestUnder(String pattern) throws java.io.IOException {
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
