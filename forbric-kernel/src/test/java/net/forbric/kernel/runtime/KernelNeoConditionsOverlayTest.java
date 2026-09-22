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
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Inside a {@code forOverlayEntries} decode a foreign condition answers NO; outside it, YES; the scope closes when
 * the decode returns; and with the switch off the overlay scope changes nothing.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelNeoConditionsOverlayTest {
	private static final String HELPER = "net.forbric.kernel.runtime.KernelNeoConditions";

	@AfterEach
	void reset() {
		System.clearProperty("forbric.overlayConditions");
	}

	@Test
	void aForeignConditionIsVetoedOnlyWhileAnOverlayListIsBeingDecoded() throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Fixture f = new Fixture(cl);
			assertEquals(true, f.decodeForeignCondition(), "outside any overlay scope: ignored, i.e. test() is true");
			assertEquals(false, f.decodeInsideOverlayScope(), "inside forOverlayEntries: vetoed, test() is false");
			assertEquals(true, f.decodeForeignCondition(), "the scope closed with the decode");
		}
	}

	@Test
	void switchedOffTheOverlayScopeIsIdentity() throws Exception {
		System.setProperty("forbric.overlayConditions", "off");
		try (URLClassLoader cl = gameSideLoader()) {
			Fixture f = new Fixture(cl);
			assertSame(f.listStub, f.forOverlayEntries(f.listStub), "off: the list codec is handed back untouched");
			assertEquals(true, f.decodeInsideOverlayScope(), "and a foreign condition inside it is ignored as before");
		}
	}

	/** The game-side classes, a strict codec that must never be reached, and a list codec that decodes one condition. */
	private static final class Fixture {
		final Class<?> helper, codecClass, dataResult, opsClass, pairClass, conditionClass;
		final Object jsonOps, input, strict, lenient, listStub;
		final Method decode;

		Fixture(URLClassLoader cl) throws Exception {
			helper = Class.forName(HELPER, true, cl);
			codecClass = Class.forName("com.mojang.serialization.Codec", true, cl);
			dataResult = Class.forName("com.mojang.serialization.DataResult", true, cl);
			opsClass = Class.forName("com.mojang.serialization.DynamicOps", true, cl);
			pairClass = Class.forName("com.mojang.datafixers.util.Pair", true, cl);
			conditionClass = Class.forName("net.neoforged.neoforge.common.conditions.ICondition", false, cl);
			jsonOps = Class.forName("com.mojang.serialization.JsonOps", true, cl).getField("INSTANCE").get(null);
			input = Class.forName("com.google.gson.JsonParser", true, cl).getMethod("parseString", String.class)
					.invoke(null, "{\"type\":\"x:foreign\"}");
			Method bind = helper.getDeclaredMethod("bindKnownTypesForTest", Predicate.class);
			bind.setAccessible(true);
			bind.invoke(null, (Predicate<String>) id -> false);
			Method error = dataResult.getMethod("error", java.util.function.Supplier.class);
			strict = Proxy.newProxyInstance(cl, new Class<?>[] { codecClass }, (proxy, method, args) -> {
				if ("decode".equals(method.getName())) return error.invoke(null, (java.util.function.Supplier<String>) () -> "strict");
				if ("toString".equals(method.getName())) return "strict";
				throw new UnsupportedOperationException(method.getName());
			});
			lenient = helper.getMethod("lenient", codecClass).invoke(null, strict);
			decode = codecClass.getMethod("decode", opsClass, Object.class);
			// The list codec: decodes ONE condition through the leniency (as NeoForge's ConditionalDecoder would)
			// and answers an empty list — the test reads the condition it decoded.
			Method success = dataResult.getMethod("success", Object.class);
			Method pairOf = pairClass.getMethod("of", Object.class, Object.class);
			AtomicReference<Object> seen = SEEN;
			listStub = Proxy.newProxyInstance(cl, new Class<?>[] { codecClass }, (proxy, method, args) -> {
				if ("decode".equals(method.getName())) {
					Object result = decode.invoke(lenient, args[0], args[1]);
					seen.set(conditionOf(result));
					return success.invoke(null, pairOf.invoke(null, List.of(), args[1]));
				}
				if ("toString".equals(method.getName())) return "listStub";
				throw new UnsupportedOperationException(method.getName());
			});
		}

		static final AtomicReference<Object> SEEN = new AtomicReference<>();

		Object forOverlayEntries(Object listCodec) throws Exception {
			return helper.getMethod("forOverlayEntries", codecClass).invoke(null, listCodec);
		}

		/** Decodes the foreign condition directly and answers what its test() says. */
		boolean decodeForeignCondition() throws Exception {
			return test(conditionOf(decode.invoke(lenient, jsonOps, input)));
		}

		/** Decodes it INSIDE a forOverlayEntries decode and answers what that condition's test() says. */
		boolean decodeInsideOverlayScope() throws Exception {
			Object wrapped = forOverlayEntries(listStub);
			decode.invoke(wrapped, jsonOps, input);
			return test(SEEN.get());
		}

		Object conditionOf(Object result) throws Exception {
			Optional<?> pair = (Optional<?>) dataResult.getMethod("result").invoke(result);
			Object p = pair.orElseThrow();
			return pairClass.getMethod("getFirst").invoke(p);
		}

		boolean test(Object condition) throws Exception {
			// Through the condition's OWN class, uninitialised interface: ICondition's static codecs would otherwise
			// drag Identifier and half the game's static state into this loader.
			Class<?> context = Class.forName("net.neoforged.neoforge.common.conditions.ICondition$IContext", false, helper.getClassLoader());
			Method test = condition.getClass().getMethod("test", context);
			test.setAccessible(true);
			return (Boolean) test.invoke(condition, new Object[] { null });
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
				"org/joml/joml")) {
			Path library = newestUnder(pattern);
			assumeTrue(library != null, "no staged " + pattern + " jar in the local Minecraft libraries");
			urls.add(library.toUri().toURL());
		}
		return new URLClassLoader(urls.toArray(new URL[0]), KernelNeoConditionsOverlayTest.class.getClassLoader());
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
