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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * {@link KernelKeyMappingMap}'s view answers what vanilla's {@code KeyMapping.MAP} would: the mappings bound to a key,
 * null for an unbound one, grouped by each mapping's current key — against the merged base's real {@code KeyMapping}
 * (instances allocated without their constructor, which would register them with the game's own key lookup).
 */
class KernelKeyMappingMapTest {
	@Test
	@SuppressWarnings("unchecked")
	void theViewGroupsTheMappingsByTheirCurrentKey() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Class<?> keyMapping = Class.forName("net.minecraft.client.KeyMapping", false, cl);
			Class<?> type = Class.forName("com.mojang.blaze3d.platform.InputConstants$Type", true, cl);
			Object keysym = type.getField("KEYSYM").get(null);
			java.lang.reflect.Method getOrCreate = type.getMethod("getOrCreate", int.class);
			Object w = getOrCreate.invoke(keysym, 87), e = getOrCreate.invoke(keysym, 69), x = getOrCreate.invoke(keysym, 88);
			Map<String, Object> all = new LinkedHashMap<>();
			Object forward = mapping(keyMapping, w), sprint = mapping(keyMapping, w), inventory = mapping(keyMapping, e);
			all.put("key.forward", forward);
			all.put("key.sprint", sprint);
			all.put("key.inventory", inventory);

			Class<?> view = Class.forName("net.forbric.kernel.runtime.KernelKeyMappingMap", true, cl);
			Map<Object, List<Object>> map = (Map<Object, List<Object>>) view.getMethod("vanillaView", Map.class).invoke(null, all);
			assertEquals(List.of(forward, sprint), map.get(w), "both mappings bound to W, in ALL's order");
			assertEquals(List.of(inventory), map.get(e));
			assertNull(map.get(x), "vanilla's map has no entry for a key nothing is bound to");
			assertNull(map.get("key.forward"), "it is keyed by key, not by name");
			assertTrue(map.containsKey(e));
			assertFalse(map.containsKey(x));
			assertEquals(Set.of(w, e), map.keySet());
			assertEquals(2, map.size());

			// A view: rebinding a mapping is what the next read sees.
			setKey(keyMapping, sprint, x);
			assertEquals(List.of(sprint), map.get(x));
			assertEquals(List.of(forward), map.get(w));
			assertThrows(UnsupportedOperationException.class, () -> map.put(w, new ArrayList<>()), "writes go nowhere, so they throw");
		}
	}

	private static Object mapping(Class<?> keyMapping, Object key) throws Exception {
		Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		Object instance = ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(keyMapping);
		setKey(keyMapping, instance, key);
		return instance;
	}

	private static void setKey(Class<?> keyMapping, Object instance, Object key) throws Exception {
		Field field = keyMapping.getDeclaredField("key");
		field.setAccessible(true);
		field.set(instance, key);
	}

	private static URLClassLoader gameSideLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		Path run = TestFixtures.stagedRoot();
		Path forgeRt = run.resolve("forge-runtime/forge-runtime.jar");
		Path neoRt = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path merged = run.resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(forgeRt) && Files.isRegularFile(neoRt) && Files.isRegularFile(merged),
				"the staged artifacts are absent");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(compiled), "the game-side set is not compiled");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), forgeRt.toUri().toURL(), neoRt.toUri().toURL(),
				merged.toUri().toURL()));
		for (String pattern : List.of("com/mojang/datafixerupper", "com/google/code/gson", "com/mojang/brigadier", "com/google/guava/guava",
				"it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api", "io/netty/netty-common",
				"io/netty/netty-buffer", TestFixtures.nettyCodecLibrary(), "io/netty/netty-transport", "io/netty/netty-handler",
				"org/joml/joml", "com/mojang/authlib", "org/apache/commons/commons-lang3")) {
			Path library = newestUnder(pattern);
			TestFixtures.require(Fixture.MC_LIBRARIES, library != null, "no staged " + pattern + " jar in the local Minecraft libraries");
			urls.add(library.toUri().toURL());
		}
		for (String pattern : List.of("org/apache/commons/commons-io", "org/apache/commons/commons-codec", "com/mojang/logging",
				"org/apache/logging/log4j/log4j-core", "org/lwjgl/lwjgl-glfw", "org/lwjgl/lwjgl")) {
			Path library = newestUnder(pattern);
			if (library != null) urls.add(library.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), KernelKeyMappingMapTest.class.getClassLoader());
	}

	private static Path newestUnder(String pattern) throws java.io.IOException {
		Path under = TestFixtures.minecraftDir().resolve("libraries").resolve(pattern);
		if (!Files.isDirectory(under)) return null;
		try (var stream = Files.walk(under)) {
			return stream.filter(f -> f.toString().endsWith(".jar") && !f.toString().contains("sources") && !f.toString().contains("natives"))
					.sorted(java.util.Comparator.comparing(f -> f.getFileName().toString())).reduce((a, b) -> b).orElse(null);
		}
	}
}
