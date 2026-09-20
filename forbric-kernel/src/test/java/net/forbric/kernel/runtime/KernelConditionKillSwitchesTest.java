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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The two condition leniencies, on and off, over the compiled game-side classes: with the switch unset a foreign
 * type never reaches the strict codec and decodes to the kernel's marker; with it off the strict codec is asked
 * exactly once, as the carrier's own path would.
 */
class KernelConditionKillSwitchesTest {
	@AfterEach
	void reset() {
		System.clearProperty("forbric.neoConditions");
		System.clearProperty("forbric.forgeConditions");
	}

	@Test
	void neoForgeLeniencyIgnoresAForeignTypeUnlessSwitchedOff() throws Exception {
		run("net.forbric.kernel.runtime.KernelNeoConditions", "net.neoforged.neoforge.common.conditions.ICondition", "forbric.neoConditions");
	}

	@Test
	void minecraftForgeLeniencyIgnoresAForeignTypeUnlessSwitchedOff() throws Exception {
		run("net.forbric.kernel.runtime.KernelForgeConditions", "net.minecraftforge.common.crafting.conditions.ICondition", "forbric.forgeConditions");
	}

	private static void run(String helperName, String conditionName, String property) throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Class<?> helper = Class.forName(helperName, true, cl);
			Class<?> codecClass = Class.forName("com.mojang.serialization.Codec", true, cl);
			Class<?> dataResult = Class.forName("com.mojang.serialization.DataResult", true, cl);
			Class<?> ops = Class.forName("com.mojang.serialization.DynamicOps", true, cl);
			Class<?> jsonOps = Class.forName("com.mojang.serialization.JsonOps", true, cl);
			Object instance = jsonOps.getField("INSTANCE").get(null);
			Object input = Class.forName("com.google.gson.JsonParser", true, cl).getMethod("parseString", String.class)
					.invoke(null, "{\"type\":\"x:foreign\"}");

			Method bind = helper.getDeclaredMethod("bindKnownTypesForTest", Predicate.class);
			bind.setAccessible(true);
			bind.invoke(null, (Predicate<String>) id -> false);

			AtomicInteger strictCalls = new AtomicInteger();
			Method error = dataResult.getMethod("error", java.util.function.Supplier.class);
			Object strict = Proxy.newProxyInstance(cl, new Class<?>[] { codecClass }, (proxy, method, args) -> {
				if ("decode".equals(method.getName())) {
					strictCalls.incrementAndGet();
					return error.invoke(null, (java.util.function.Supplier<String>) () -> "strict");
				}
				if ("toString".equals(method.getName())) return "strict";
				throw new UnsupportedOperationException(method.getName());
			});
			Object lenient = helper.getMethod("lenient", codecClass).invoke(null, strict);
			Method decode = codecClass.getMethod("decode", ops, Object.class);

			Object result = decode.invoke(lenient, instance, input);
			assertEquals(0, strictCalls.get(), "a foreign type never reaches the strict codec while the leniency is on");
			Optional<?> pair = (Optional<?>) dataResult.getMethod("result").invoke(result);
			Object first = pair.orElseThrow().getClass().getMethod("getFirst").invoke(pair.orElseThrow());
			assertEquals("forbric:foreign-condition", String.valueOf(first));

			System.setProperty(property, "off");
			decode.invoke(lenient, instance, input);
			assertEquals(1, strictCalls.get(), "switched off, the strict codec is asked exactly once — the carrier's own path");
		}
	}

	private static URLClassLoader gameSideLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		Path run = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
		Path forgeRt = run.resolve("forge-runtime/forge-runtime.jar");
		Path neoRt = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		Path merged = run.resolve("merged-base/patched-mc-merged-26.2.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(forgeRt) && Files.isRegularFile(neoRt) && Files.isRegularFile(merged),
				"the game-side set is not compiled, or the staged artifacts are absent");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), forgeRt.toUri().toURL(), neoRt.toUri().toURL(), merged.toUri().toURL()));
		for (String pattern : List.of("com/mojang/datafixerupper", "com/google/code/gson", "com/mojang/brigadier", "com/google/guava/guava",
				"it/unimi/dsi/fastutil", "org/slf4j/slf4j-api", "org/apache/logging/log4j/log4j-api",
				// Identifier.<clinit> reaches netty's DecoderException
				"io/netty/netty-common", "io/netty/netty-buffer", "io/netty/netty-codec", "io/netty/netty-transport", "io/netty/netty-handler")) {
			Path library = newestUnder(pattern);
			assumeTrue(library != null, "no staged " + pattern + " jar in the local Minecraft libraries");
			urls.add(library.toUri().toURL());
		}
		// The test's own loader as parent: the runtime classes reach ForbricLog (a boot-side class) through it, and
		// nothing in it shadows the game jars below.
		return new URLClassLoader(urls.toArray(new URL[0]), KernelConditionKillSwitchesTest.class.getClassLoader());
	}

	private static Path newestUnder(String pattern) throws IOException {
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
