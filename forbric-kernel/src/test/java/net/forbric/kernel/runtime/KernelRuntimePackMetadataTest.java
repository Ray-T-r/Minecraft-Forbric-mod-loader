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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * The kernel's own resource pack declares a format the game reads without falling back.
 *
 * <p>It declared only {@code pack_format: 78}. From format 65 the game requires {@code min_format} and
 * {@code max_format}, so every client boot printed "Error reading optional pack metadata for
 * forbric/forbric-kernel-runtime" with a {@code JsonParseException} stack, and NeoForge's reader then fell back.
 * The pack still loaded — its compatibility is forced — so this was noise, but it was noise that looked like a
 * Forbric failure in every log a player sends. Parsed here with the game's own strict codec, with no fallback, and
 * checked against the resource version the merged game itself reports.
 */
class KernelRuntimePackMetadataTest {
	@Test
	void theRuntimePackMetadataParsesStrictlyAndCoversTheGamesResourceVersion() throws Exception {
		Path mcmeta = Path.of(System.getProperty("user.dir"), "src", "runtime", "resources", "pack.mcmeta");
		assertTrue(Files.isRegularFile(mcmeta), mcmeta + " is the file this test is about");
		try (URLClassLoader cl = gameLoader()) {
			Object json = cl.loadClass("com.google.gson.JsonParser").getMethod("parseString", String.class)
					.invoke(null, Files.readString(mcmeta, StandardCharsets.UTF_8));
			Object pack = cl.loadClass("com.google.gson.JsonObject").getMethod("get", String.class).invoke(json, "pack");

			Class<?> packType = cl.loadClass("net.minecraft.server.packs.PackType");
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object clientResources = Enum.valueOf((Class) packType, "CLIENT_RESOURCES");
			Class<?> packFormat = cl.loadClass("net.minecraft.server.packs.metadata.pack.PackFormat");
			Object mapCodec = packFormat.getMethod("packCodec", packType).invoke(null, clientResources);
			Object codec = cl.loadClass("com.mojang.serialization.MapCodec").getMethod("codec").invoke(mapCodec);
			Class<?> ops = cl.loadClass("com.mojang.serialization.DynamicOps");
			Object jsonOps = cl.loadClass("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
			Object parsed = cl.loadClass("com.mojang.serialization.Decoder").getMethod("parse", ops, Object.class)
					.invoke(codec, jsonOps, pack);

			Class<?> dataResult = cl.loadClass("com.mojang.serialization.DataResult");
			Optional<?> error = (Optional<?>) dataResult.getMethod("error").invoke(parsed);
			assertTrue(error.isEmpty(), () -> "the game's strict pack codec rejects the kernel's pack.mcmeta: "
					+ error.map(Object::toString).orElse(""));
			Object range = ((Optional<?>) dataResult.getMethod("result").invoke(parsed)).orElseThrow();

			int[] current = resourceVersion();
			Object running = packFormat.getMethod("of", int.class, int.class).invoke(null, current[0], current[1]);
			Method inRange = cl.loadClass("net.minecraft.util.InclusiveRange").getMethod("isValueInRange", Comparable.class);
			assertTrue((Boolean) inRange.invoke(range, running), "the kernel's pack declares " + range
					+ ", which does not include the running game's resource version " + current[0] + "." + current[1]);
		}
	}

	/** {@code resource_major} and {@code resource_minor} from the merged game's own version.json. */
	private static int[] resourceVersion() throws IOException {
		try (ZipFile jar = new ZipFile(merged().toFile())) {
			String text = new String(jar.getInputStream(jar.getEntry("version.json")).readAllBytes(), StandardCharsets.UTF_8);
			return new int[] { number(text, "resource_major"), number(text, "resource_minor") };
		}
	}

	private static int number(String json, String key) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
		assertTrue(m.find(), "version.json has no " + key);
		return Integer.parseInt(m.group(1));
	}

	private static Path merged() {
		Path run = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", System.getProperty("user.dir") + "/../forbric-loader"), "run").normalize();
		return run.resolve("merged-base/patched-mc-merged-26.2.jar");
	}

	/**
	 * The merged game plus Minecraft 26.2's own library set, read from the launcher's version JSON — the list the
	 * build resolves for the game side's test linkage too. The pack codec reaches {@code ExtraCodecs}, whose
	 * static initialiser touches half a dozen libraries, so a hand-picked list is one class away from breaking.
	 */
	private static URLClassLoader gameLoader() throws Exception {
		Path merged = merged();
		assumeTrue(Files.isRegularFile(merged), "the staged merged game is absent");
		String env = System.getenv("MC_DIR");
		Path libraries = Path.of(env != null ? env + "/libraries"
				: System.getProperty("user.home") + "/Library/Application Support/minecraft/libraries");
		Path versionJson = libraries.getParent().resolve("versions/26.2/26.2.json");
		assumeTrue(Files.isRegularFile(versionJson), "no 26.2 version JSON beside the local Minecraft libraries");
		List<URL> urls = new ArrayList<>(List.of(merged.toUri().toURL()));
		java.util.regex.Matcher path = java.util.regex.Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+\\.jar)\"")
				.matcher(Files.readString(versionJson, StandardCharsets.UTF_8));
		while (path.find()) {
			Path library = libraries.resolve(path.group(1));
			if (Files.isRegularFile(library)) urls.add(library.toUri().toURL());
		}
		assumeTrue(urls.size() > 1, "none of 26.2's libraries is in the local Minecraft install");
		return new URLClassLoader(urls.toArray(new URL[0]), KernelRuntimePackMetadataTest.class.getClassLoader());
	}
}
