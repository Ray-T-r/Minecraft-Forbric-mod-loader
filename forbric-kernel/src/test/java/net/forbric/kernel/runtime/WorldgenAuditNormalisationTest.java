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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Covers the round-trip audit's comparison, which decides whether MinecraftForge's world modifiers run at all.
 *
 * <p>The audit rebuilds every biome through MinecraftForge's builders and compares the result with the original.
 * Any difference and it stands the whole bridge down — a deliberately conservative call, because the alternative
 * is applying modifiers through builders that quietly lose world data.
 *
 * <p>It was comparing encoded JSON exactly, and two differences that mean nothing at all were enough to trip it:
 * MinecraftForge's builder does not write out a trailing EMPTY decoration step or an empty spawner category, and
 * it emits the spawner categories in {@code MobCategory} order rather than the datapack's. On Stellarity's biomes
 * that was 36 of 98 "differing" and every MinecraftForge biome and structure modifier in the pack standing down —
 * Animal Garden's redpanda spawns among them — over empty brackets and key order.
 *
 * <p>What must still differ is a container that LOST something. That is the case the audit exists for, and the
 * test that would fail if this forgiveness were widened into "close enough".
 */
class WorldgenAuditNormalisationTest {
	@Test
	void anEmptyTailAndAKeyOrderAreNotADifference() throws Exception {
		Method normalise = normaliser();
		assumeTrue(normalise != null, "the game-side set is not compiled");

		assertEquals(normalise(normalise, "{\"features\":[[\"a\"],[],[]]}"),
				normalise(normalise, "{\"features\":[[\"a\"]]}"),
				"decoration steps past the last non-empty one mean the same thing written or not");
		assertEquals(normalise(normalise, "{\"spawners\":{\"ambient\":[\"bat\"],\"monster\":[\"zombie\"]}}"),
				normalise(normalise, "{\"spawners\":{\"monster\":[\"zombie\"],\"ambient\":[\"bat\"]}}"),
				"a JSON object is unordered; the two sides write the categories in their own orders");
		assertEquals(normalise(normalise, "{\"spawners\":{\"monster\":[\"zombie\"],\"creature\":[]}}"),
				normalise(normalise, "{\"spawners\":{\"monster\":[\"zombie\"]}}"),
				"an empty category and an absent category are the same instruction");
	}

	@Test
	void aContainerThatLostSomethingStillDiffers() throws Exception {
		Method normalise = normaliser();
		assumeTrue(normalise != null, "the game-side set is not compiled");

		assertNotEquals(normalise(normalise, "{\"spawners\":{\"monster\":[\"zombie\",\"creeper\"]}}"),
				normalise(normalise, "{\"spawners\":{\"monster\":[\"zombie\"]}}"),
				"a lost spawn is exactly what the audit exists to catch");
		assertNotEquals(normalise(normalise, "{\"features\":[[\"a\"],[],[\"b\"]]}"),
				normalise(normalise, "{\"features\":[[\"a\"],[\"b\"]]}"),
				"an empty step in the MIDDLE holds a position, so dropping it moves everything after it");
	}

	private static String normalise(Method normalise, String json) throws Exception {
		Class<?> parser = normalise.getDeclaringClass().getClassLoader().loadClass("com.google.gson.JsonParser");
		Object element = parser.getMethod("parseString", String.class).invoke(null, json);
		return String.valueOf(normalise.invoke(null, element));
	}

	private static Method normaliser() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		if (!Files.isDirectory(compiled)) return null;

		Path gson = newestUnder("com/google/code/gson");
		if (gson == null) return null;
		// Only the small class and gson: the normaliser is deliberately free of every game type, so the thing
		// under test can be loaded without staging the merged base or either carrier.
		List<URL> urls = List.of(compiled.toUri().toURL(), gson.toUri().toURL());
		@SuppressWarnings("resource")
		URLClassLoader loader = new URLClassLoader(urls.toArray(URL[]::new), null);
		Class<?> shape = Class.forName("net.forbric.kernel.runtime.WorldDataShape", false, loader);
		Class<?> jsonElement = Class.forName("com.google.gson.JsonElement", false, loader);
		Method normalise = shape.getDeclaredMethod("withoutEmptyContainers", jsonElement);
		normalise.setAccessible(true);
		return normalise;
	}

	private static Path newestUnder(String pattern) throws Exception {
		Path libraries = Path.of(System.getProperty("user.home"), "Library", "Application Support", "minecraft",
				"libraries", pattern.replace('/', java.io.File.separatorChar));
		if (!Files.isDirectory(libraries)) {
			libraries = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run", "downloads").normalize();
			if (!Files.isDirectory(libraries)) return null;
		}
		try (var walk = Files.walk(libraries)) {
			return walk.filter(p -> p.getFileName().toString().endsWith(".jar")).findFirst().orElse(null);
		}
	}
}
