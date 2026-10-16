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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

import net.forbric.kernel.GateLogContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where ConditionalOps' funnel asks MinecraftForge, over the compiled game-side classes, with each judge a stand-in so
 * that only the funnel's own decisions are under test (KernelForgeConditionsRealCodecTest runs MinecraftForge's and
 * NeoForge's real codecs through the same funnel): an element whose {@code forge:condition} does not hold is skipped the
 * way NeoForge skips one, one MinecraftForge cannot read fails a registry element, a data file goes through
 * MinecraftForge's data-file reader and a {@code forge:conditional} one becomes its chosen variant -- and MinecraftForge
 * is asked only inside one of its two readers, at that reader's first decode, never over an element NeoForge judges,
 * and never around a codec that judges the key itself.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelForgeConditionFunnelTest {
	private static final String NEO_KEY = "neoforge:conditions";

	@Test void aForgeConditionIsJudgedBeforeNeoForgeDecodes(@TempDir Path scratch) throws Exception {
		String pattern = GateLogContract.pattern(Path.of("run/gate-m21-forgesetup.sh"), "MinecraftForge judges forge:condition at the conditional-decode funnel");
		try (Funnel funnel = new Funnel()) {
			Object codec = funnel.registryElement(funnel.wrap(funnel.neoforge(), (ops, conditions) -> true));
			int[] before = funnel.counts();
			String[] out = new String[1];
			Object[] results = GateLogContract.capture(() -> new Object[] {
					funnel.decode(codec, "{\"value\":1}"),
					funnel.decode(codec, "{\"forge:condition\":{\"type\":\"no\"},\"value\":1}"),
					funnel.decode(codec, "{\"forge:condition\":{\"type\":\"yes\"},\"value\":1}"),
					funnel.decode(codec, "{\"forge:condition\":{\"type\":\"broken\"},\"value\":1}"),
					funnel.decode(codec, "[1,2]") }, out);
			assertEquals(3, funnel.neoforge.get(), "NeoForge decodes the element without a condition, the one that holds and the list -- not the skipped or the failed one");
			assertEquals(Optional.empty(), funnel.first(results[1]), "a condition that does not hold is NeoForge's own skip: an empty result");
			assertEquals(Optional.of("decoded"), funnel.first(results[2]));
			assertTrue(funnel.failed(results[3]), "an unreadable condition fails the registry element, as on MinecraftForge");
			int[] after = funnel.counts();
			assertArrayEquals(new int[] { 2, 1 }, new int[] { after[0] - before[0], after[1] - before[1] }, "judged the two readable conditions, rejected one");
			if (before[0] == 0) assertEquals(1, GateLogContract.count(scratch, pattern, out[0]), out[0]);

			System.setProperty("forbric.forgeConditionFunnel", "off");
			try {
				funnel.decode(codec, "{\"forge:condition\":{\"type\":\"no\"},\"value\":1}");
				assertEquals(4, funnel.neoforge.get(), "switched off, NeoForge decodes it unjudged");
			} finally {
				System.clearProperty("forbric.forgeConditionFunnel");
			}
		}
	}

	@Test void onlyTheOutermostDecodeIsMinecraftForgesToJudge() throws Exception {
		// MinecraftForge reads forge:condition at the top of a data file or registry element; NeoForge nests further
		// conditional decodes inside an element, and those are not places MinecraftForge ever looked.
		try (Funnel funnel = new Funnel()) {
			Object nested = funnel.wrap(funnel.neoforge(), (ops, conditions) -> true);
			Object outer = funnel.registryElement(funnel.wrap(funnel.decodingAlso(nested, "{\"forge:condition\":{\"type\":\"no\"},\"value\":2}"), (ops, conditions) -> true));
			Object result = funnel.decode(outer, "{\"value\":1}");
			assertEquals(Optional.of("decoded"), funnel.first(result));
			assertEquals(List.of(Optional.of("decoded")), funnel.nestedResults, "the nested element was decoded, not judged");
			assertEquals(0, funnel.counts()[0], "MinecraftForge was not asked about the nested one");
			// The negative control: the same element, read on its own as a registry element, is judged and skipped.
			assertEquals(Optional.empty(), funnel.first(funnel.decode(funnel.registryElement(nested), "{\"forge:condition\":{\"type\":\"no\"},\"value\":2}")));
		}
	}

	@Test void nothingIsJudgedOutsideMinecraftForgesTwoReaders() throws Exception {
		// The funnel serves every conditional decode NeoForge makes -- a pack.mcmeta overlay entry, an atlas sprite source,
		// a client's network registry load, a mod's own. MinecraftForge read forge:condition in none of those.
		try (Funnel funnel = new Funnel()) {
			Object codec = funnel.wrap(funnel.neoforge(), (ops, conditions) -> true);
			String no = "{\"forge:condition\":{\"type\":\"no\"},\"value\":1}";
			assertEquals(Optional.of("decoded"), funnel.first(funnel.decode(codec, no)), "outside both readers NeoForge decodes it unjudged");
			assertArrayEquals(new int[] {0, 0}, funnel.counts());
			// The negative control: the same codec, read by either reader, judges it.
			assertEquals(Optional.empty(), funnel.first(funnel.decode(funnel.registryElement(codec), no)));
			assertEquals(Optional.empty(), funnel.first(funnel.decode(funnel.dataFile(codec), no)));
			assertArrayEquals(new int[] {2, 2}, funnel.counts());
		}
	}

	@Test void eachReaderJudgesWithItsOwnCode() throws Exception {
		// A data file goes through readConditional (here: a stand-in that skips everything), a registry element through
		// ConditionCodec (here: one that keeps "yes"); and only the data-file reader reads forge:conditional at all.
		try (Funnel funnel = new Funnel()) {
			List<String> asked = new ArrayList<>();
			Object codec = funnel.wrap(funnel.neoforge(), (ops, conditions) -> true, (ops, json) -> {
				asked.add(String.valueOf(json));
				return null;
			});
			String yes = "{\"forge:condition\":{\"type\":\"yes\"},\"value\":1}";
			assertEquals(Optional.empty(), funnel.first(funnel.decode(funnel.dataFile(codec), yes)), "readConditional's answer");
			assertEquals(List.of(yes), asked);
			assertEquals(Optional.of("decoded"), funnel.first(funnel.decode(funnel.registryElement(codec), yes)), "ConditionCodec's answer");
			String variants = "{\"forge:conditional\":[{\"value\":1}]}";
			assertEquals(Optional.of("decoded"), funnel.first(funnel.decode(funnel.registryElement(codec), variants)),
					"MinecraftForge's registry reader never read forge:conditional: NeoForge decodes the element as it is");
			assertEquals(List.of(yes), asked, "readConditional was not asked about the registry element");
		}
	}

	@Test void aCodecThatJudgesTheKeyItselfIsNeverAskedAround() throws Exception {
		try (Funnel funnel = new Funnel()) {
			Class<?> helper = funnel.type("net.forbric.kernel.runtime.KernelForgeConditions");
			Class<?> codec = funnel.type("com.mojang.serialization.Codec");
			Method alsoAskForge = helper.getMethod("alsoAskForge", codec, codec, String.class);
			Object own = funnel.neoforge();
			helper.getMethod("judgesForgeConditionItself", Object.class).invoke(null, own);
			String no = "{\"forge:condition\":{\"type\":\"no\"},\"value\":1}";
			Object around = funnel.registryElement(alsoAskForge.invoke(null, funnel.neoforge(), own, NEO_KEY));
			assertEquals(Optional.of("decoded"), funnel.first(funnel.decode(around, no)), "around a registered codec NeoForge's wrapper decodes the element unjudged");
			assertEquals(0, funnel.counts()[0], "MinecraftForge was not asked");
			// The negative control: around any other codec the factory's result is the funnel itself (whose judging the
			// tests above hold with stand-in judges; alsoAskForge's are MinecraftForge's real ones).
			assertTrue(String.valueOf(alsoAskForge.invoke(null, funnel.neoforge(), funnel.neoforge(), NEO_KEY)).startsWith("Forbric/MinecraftForge("),
					"around any other codec the funnel is wrapped");
			assertTrue(String.valueOf(alsoAskForge.invoke(null, funnel.neoforge(), own, NEO_KEY)).startsWith("Forbric/NeoForgeVerdict("),
					"around a registered codec only NeoForge's verdict is passed in");
		}
	}

	@Test void anElementNeoForgeJudgesIsNotAskedAgainByMinecraftForge() throws Exception {
		try (Funnel funnel = new Funnel()) {
			List<String> askedNeoForge = new ArrayList<>();
			Object codec = funnel.registryElement(funnel.wrap(funnel.neoforge(), (ops, conditions) -> {
				askedNeoForge.add(String.valueOf(conditions));
				return !String.valueOf(conditions).contains("unknown");
			}));
			String judged = "{\"neoforge:conditions\":[{\"type\":\"neoforge:always\"}],\"forge:condition\":{\"type\":\"no\"},\"value\":1}";
			String unjudgeable = "{\"neoforge:conditions\":[{\"type\":\"unknown\"}],\"forge:condition\":{\"type\":\"no\"},\"value\":1}";
			assertEquals(Optional.of("decoded"), funnel.first(funnel.decode(codec, judged)), "NeoForge judges its own conditions: its verdict stands");
			assertEquals(0, funnel.counts()[0]);
			assertEquals(Optional.empty(), funnel.first(funnel.decode(codec, unjudgeable)), "NeoForge cannot: MinecraftForge decides");
			assertEquals(1, funnel.counts()[0]);
			assertEquals(2, askedNeoForge.size(), "the dialect probe was handed the value under NeoForge's key both times: " + askedNeoForge);
			assertEquals(Optional.empty(), funnel.first(funnel.decode(codec, "{\"forge:condition\":{\"type\":\"no\"},\"value\":1}")),
					"no NeoForge conditions at all: MinecraftForge's alone");
			assertEquals(2, askedNeoForge.size(), "nothing to probe when NeoForge's key is absent");
		}
	}

	@Test void aForgeConditionalElementBecomesItsChosenVariant() throws Exception {
		try (Funnel funnel = new Funnel()) {
			List<String> decodedNeo = new ArrayList<>();
			Object codec = funnel.dataFile(funnel.wrap(funnel.neoforge(decodedNeo), (ops, conditions) -> true, (ops, json) -> {
				String text = String.valueOf(json);
				if (text.contains("broken")) throw new IllegalStateException("not an object");
				return text.contains("pick") ? funnel.parse("{\"value\":\"picked\"}") : null;
			}));
			assertEquals(Optional.of("decoded"), funnel.first(funnel.decode(codec, "{\"forge:conditional\":[\"pick\"]}")));
			assertEquals(List.of("{\"value\":\"picked\"}"), decodedNeo, "NeoForge decodes the chosen variant, not the array");
			assertEquals(Optional.empty(), funnel.first(funnel.decode(codec, "{\"forge:conditional\":[\"none\"]}")), "no variant holds: skipped");
			assertTrue(funnel.failed(funnel.decode(codec, "{\"forge:conditional\":[\"broken\"]}")));
			assertArrayEquals(new int[] {2, 1}, funnel.counts(), "two arrays judged, one rejected");
		}
	}

	/** The game-side classes in their own loader, with stand-ins for NeoForge's decoder and both families' judges. */
	private static final class Funnel implements AutoCloseable {
		final AtomicInteger neoforge = new AtomicInteger();
		final List<Object> nestedResults = new ArrayList<>();
		private final URLClassLoader cl;
		private final Class<?> codecClass;
		private final Class<?> dataResult;
		private final Method success;
		private final Method error;
		private final Method pair;
		private final Object json;

		Funnel() throws Exception {
			cl = KernelConditionKillSwitchesTest.gameSideLoader();
			codecClass = type("com.mojang.serialization.Codec");
			dataResult = type("com.mojang.serialization.DataResult");
			success = dataResult.getMethod("success", Object.class);
			error = dataResult.getMethod("error", java.util.function.Supplier.class);
			pair = type("com.mojang.datafixers.util.Pair").getMethod("of", Object.class, Object.class);
			json = type("com.mojang.serialization.JsonOps").getField("INSTANCE").get(null);
		}

		@Override
		public void close() throws Exception {
			cl.close();
		}

		Class<?> type(String name) throws ClassNotFoundException {
			return Class.forName(name, true, cl);
		}

		Object parse(String text) {
			try {
				return type("com.google.gson.JsonParser").getMethod("parseString", String.class).invoke(null, text);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		/** NeoForge's conditional codec, reduced to "decoded" and a count. */
		Object neoforge() {
			return neoforge(new ArrayList<>());
		}

		Object neoforge(List<String> inputs) {
			return Proxy.newProxyInstance(cl, new Class<?>[] { codecClass }, (proxy, method, args) -> {
				if ("decode".equals(method.getName())) {
					neoforge.incrementAndGet();
					inputs.add(String.valueOf(args[1]));
					return success.invoke(null, pair.invoke(null, Optional.of("decoded"), args[1]));
				}
				if ("toString".equals(method.getName())) return "neoforge";
				if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
				if ("equals".equals(method.getName())) return proxy == args[0];
				throw new UnsupportedOperationException(method.getName());
			});
		}

		/** NeoForge's codec for an element that decodes a nested element through {@code nested} on the way. */
		Object decodingAlso(Object nested, String nestedInput) {
			return Proxy.newProxyInstance(cl, new Class<?>[] { codecClass }, (proxy, method, args) -> {
				if ("decode".equals(method.getName())) {
					nestedResults.add(first(decode(nested, nestedInput)));
					return success.invoke(null, pair.invoke(null, Optional.of("decoded"), args[1]));
				}
				if ("toString".equals(method.getName())) return "outer";
				throw new UnsupportedOperationException(method.getName());
			});
		}

		/** MinecraftForge's verdict per input: the value of its "forge:condition" type. */
		Object forgeJudge() {
			return Proxy.newProxyInstance(cl, new Class<?>[] { type0("com.mojang.serialization.Decoder") }, (proxy, method, args) -> {
				if (!"decode".equals(method.getName())) throw new UnsupportedOperationException(method.getName());
				String type = String.valueOf(args[1]);
				if (type.contains("broken")) return error.invoke(null, (java.util.function.Supplier<String>) () -> "unreadable condition");
				return success.invoke(null, pair.invoke(null, type.contains("\"no\"") ? Optional.empty() : Optional.of(Boolean.TRUE), args[1]));
			});
		}

		/** The funnel with a readConditional stand-in that answers a forge:condition as {@link #forgeJudge} does. */
		Object wrap(Object conditional, BiPredicate<Object, Object> neoForgeJudges) throws Exception {
			return wrap(conditional, neoForgeJudges, (ops, element) -> {
				String text = String.valueOf(element);
				if (text.contains("forge:conditional")) throw new AssertionError("no forge:conditional here");
				if (text.contains("broken")) throw new IllegalStateException("unreadable condition");
				return text.contains("\"no\"") ? null : element;
			});
		}

		Object wrap(Object conditional, BiPredicate<Object, Object> neoForgeJudges, BiFunction<Object, Object, Object> variants) throws Exception {
			Class<?> helper = type("net.forbric.kernel.runtime.KernelForgeConditions");
			Method alsoAsk = helper.getDeclaredMethod("alsoAsk", codecClass, String.class, BiPredicate.class, type("com.mojang.serialization.Decoder"), BiFunction.class);
			alsoAsk.setAccessible(true);
			return alsoAsk.invoke(null, conditional, NEO_KEY, neoForgeJudges, forgeJudge(), variants);
		}

		/** {@code codec} read by MinecraftForge's registry-element reader. */
		Object registryElement(Object codec) throws Exception {
			return type("net.forbric.kernel.runtime.KernelForgeConditions").getMethod("readsRegistryElements", codecClass).invoke(null, codec);
		}

		/** {@code codec} read by MinecraftForge's data-file reader. */
		Object dataFile(Object codec) throws Exception {
			return type("net.forbric.kernel.runtime.KernelForgeConditions").getMethod("readsDataFiles", codecClass).invoke(null, codec);
		}

		Object decode(Object codec, String input) {
			try {
				return codecClass.getMethod("decode", type("com.mojang.serialization.DynamicOps"), Object.class).invoke(codec, json, parse(input));
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		Object first(Object result) {
			try {
				Optional<?> value = (Optional<?>) dataResult.getMethod("result").invoke(result);
				return value.orElseThrow().getClass().getMethod("getFirst").invoke(value.orElseThrow());
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		boolean failed(Object result) throws Exception {
			return ((Optional<?>) dataResult.getMethod("error").invoke(result)).isPresent();
		}

		int[] counts() throws Exception {
			Method counts = type("net.forbric.kernel.runtime.KernelForgeConditions").getDeclaredMethod("funnelCountsForTest");
			counts.setAccessible(true);
			return (int[]) counts.invoke(null);
		}

		private Class<?> type0(String name) {
			try {
				return type(name);
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
		}
	}
}
