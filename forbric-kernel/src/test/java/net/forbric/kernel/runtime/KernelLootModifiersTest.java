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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The hiding view over a recording ResourceManager: which ids the predicate it forwards rejects, and identity when off. */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelLootModifiersTest {
	@AfterEach
	void reset() {
		System.clearProperty("forbric.lootModifierIndex");
	}

	@Test
	void theViewHidesEveryLegacyIndexAndNothingElse() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Class<?> helper = Class.forName("net.forbric.kernel.runtime.KernelLootModifiers", true, cl);
			Class<?> rm = Class.forName("net.minecraft.server.packs.resources.ResourceManager", false, cl);
			Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", true, cl);
			Method parse = identifier.getMethod("parse", String.class);
			AtomicReference<Predicate<Object>> captured = new AtomicReference<>();
			Object delegate = Proxy.newProxyInstance(cl, new Class<?>[] { rm }, (proxy, method, args) -> {
				if ("listResources".equals(method.getName())) {
					@SuppressWarnings("unchecked") Predicate<Object> p = (Predicate<Object>) args[1];
					captured.set(p);
					return Map.of();
				}
				if ("toString".equals(method.getName())) return "delegate";
				throw new UnsupportedOperationException(method.getName());
			});
			Object view = helper.getMethod("withoutTheLegacyIndex", rm).invoke(null, delegate);
			assertNotNull(view);
			rm.getMethod("listResources", String.class, Predicate.class).invoke(view, "loot_modifiers", (Predicate<Object>) id -> true);
			Predicate<Object> forwarded = captured.get();
			assertNotNull(forwarded, "the scan reached the delegate through the view");
			assertFalse(forwarded.test(parse.invoke(null, "forge:loot_modifiers/global_loot_modifiers.json")), "the carrier's legacy index");
			assertFalse(forwarded.test(parse.invoke(null, "neoforge:loot_modifiers/global_loot_modifiers.json")), "a mod's legacy index");
			assertTrue(forwarded.test(parse.invoke(null, "usefulfood:loot_modifiers/glow_squid.json")), "a real modifier");
			assertTrue(forwarded.test(parse.invoke(null, "x:other/global_loot_modifiers.json")), "outside loot_modifiers/ it is not the index");
		}
	}

	@Test
	void switchedOffTheOriginalManagerIsHandedBack() throws Exception {
		System.setProperty("forbric.lootModifierIndex", "off");
		try (URLClassLoader cl = gameSideLoader()) {
			Class<?> helper = Class.forName("net.forbric.kernel.runtime.KernelLootModifiers", true, cl);
			Class<?> rm = Class.forName("net.minecraft.server.packs.resources.ResourceManager", false, cl);
			Object delegate = Proxy.newProxyInstance(cl, new Class<?>[] { rm }, (proxy, method, args) -> { throw new UnsupportedOperationException(); });
			assertSame(delegate, helper.getMethod("withoutTheLegacyIndex", rm).invoke(null, delegate));
		}
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
		// Identifier's static initialiser reaches further on some installs; whatever else is staged is welcome.
		for (String pattern : List.of("org/apache/commons/commons-io", "org/apache/commons/commons-codec", "com/mojang/logging",
				"org/apache/logging/log4j/log4j-core")) {
			Path library = newestUnder(pattern);
			if (library != null) urls.add(library.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), KernelLootModifiersTest.class.getClassLoader());
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
