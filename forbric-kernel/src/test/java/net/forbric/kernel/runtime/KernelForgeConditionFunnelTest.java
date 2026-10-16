package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.forbric.kernel.GateLogContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MinecraftForge's {@code forge:condition}, asked at ConditionalOps' funnel over the compiled game-side classes: an
 * element whose condition does not hold is skipped the way NeoForge skips one (an empty result, NeoForge's codec never
 * asked), one that holds or carries no condition is decoded by NeoForge as before, and one MinecraftForge cannot read
 * fails as it would on MinecraftForge. The judge is MinecraftForge's own decoder in game; here it is a stand-in, since
 * nothing has built MinecraftForge's condition registry in a unit test.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelForgeConditionFunnelTest {
	@Test void aForgeConditionIsJudgedBeforeNeoForgeDecodes(@TempDir Path scratch) throws Exception {
		String pattern = GateLogContract.pattern(Path.of("run/gate-m21-forgesetup.sh"), "MinecraftForge judges forge:condition at the conditional-decode funnel");
		try (URLClassLoader cl = KernelConditionKillSwitchesTest.gameSideLoader()) {
			Class<?> helper = Class.forName("net.forbric.kernel.runtime.KernelForgeConditions", true, cl);
			Class<?> codecClass = Class.forName("com.mojang.serialization.Codec", true, cl);
			Class<?> decoderClass = Class.forName("com.mojang.serialization.Decoder", true, cl);
			Class<?> dataResult = Class.forName("com.mojang.serialization.DataResult", true, cl);
			Class<?> pairClass = Class.forName("com.mojang.datafixers.util.Pair", true, cl);
			Class<?> ops = Class.forName("com.mojang.serialization.DynamicOps", true, cl);
			Object json = Class.forName("com.mojang.serialization.JsonOps", true, cl).getField("INSTANCE").get(null);
			Method parse = Class.forName("com.google.gson.JsonParser", true, cl).getMethod("parseString", String.class);
			Method success = dataResult.getMethod("success", Object.class), error = dataResult.getMethod("error", java.util.function.Supplier.class);
			Method pair = pairClass.getMethod("of", Object.class, Object.class);

			// MinecraftForge's verdict per input: the value of its "forge:condition" type.
			Object judge = Proxy.newProxyInstance(cl, new Class<?>[] { decoderClass }, (proxy, method, args) -> {
				if (!"decode".equals(method.getName())) throw new UnsupportedOperationException(method.getName());
				String type = String.valueOf(args[1]);
				if (type.contains("broken")) return error.invoke(null, (java.util.function.Supplier<String>) () -> "unreadable condition");
				return success.invoke(null, pair.invoke(null, type.contains("\"no\"") ? Optional.empty() : Optional.of(Boolean.TRUE), args[1]));
			});
			AtomicInteger neoforge = new AtomicInteger();
			Object conditional = Proxy.newProxyInstance(cl, new Class<?>[] { codecClass }, (proxy, method, args) -> {
				if ("decode".equals(method.getName())) { neoforge.incrementAndGet(); return success.invoke(null, pair.invoke(null, Optional.of("decoded"), args[1])); }
				if ("toString".equals(method.getName())) return "neoforge";
				throw new UnsupportedOperationException(method.getName());
			});
			Method alsoAsk = helper.getDeclaredMethod("alsoAsk", codecClass, decoderClass);
			alsoAsk.setAccessible(true);
			Object funnel = alsoAsk.invoke(null, conditional, judge);
			Method decode = codecClass.getMethod("decode", ops, Object.class);
			Method counts = helper.getDeclaredMethod("funnelCountsForTest");
			counts.setAccessible(true);
			int[] before = (int[]) counts.invoke(null);

			String[] out = new String[1];
			Object[] results = GateLogContract.capture(() -> new Object[] {
					decode.invoke(funnel, json, parse.invoke(null, "{\"value\":1}")),
					decode.invoke(funnel, json, parse.invoke(null, "{\"forge:condition\":{\"type\":\"no\"},\"value\":1}")),
					decode.invoke(funnel, json, parse.invoke(null, "{\"forge:condition\":{\"type\":\"yes\"},\"value\":1}")),
					decode.invoke(funnel, json, parse.invoke(null, "{\"forge:condition\":{\"type\":\"broken\"},\"value\":1}")),
					decode.invoke(funnel, json, parse.invoke(null, "[1,2]")) }, out);
			assertEquals(3, neoforge.get(), "NeoForge decodes the element without a condition, the one that holds and the list -- not the skipped or the failed one");
			assertEquals(Optional.empty(), first(dataResult, results[1]), "a condition that does not hold is NeoForge's own skip: an empty result");
			assertEquals(Optional.of("decoded"), first(dataResult, results[2]));
			assertTrue(((Optional<?>) dataResult.getMethod("error").invoke(results[3])).isPresent(), "an unreadable condition fails the element, as on MinecraftForge");
			int[] after = (int[]) counts.invoke(null);
			assertArrayEquals(new int[] { 2, 1 }, new int[] { after[0] - before[0], after[1] - before[1] }, "judged the two readable conditions, rejected one");
			if (before[0] == 0) assertEquals(1, GateLogContract.count(scratch, pattern, out[0]), out[0]);

			System.setProperty("forbric.forgeConditionFunnel", "off");
			try {
				decode.invoke(funnel, json, parse.invoke(null, "{\"forge:condition\":{\"type\":\"no\"},\"value\":1}"));
				assertEquals(4, neoforge.get(), "switched off, NeoForge decodes it unjudged");
			} finally {
				System.clearProperty("forbric.forgeConditionFunnel");
			}
		}
	}

	private static Object first(Class<?> dataResult, Object result) throws Exception {
		Optional<?> pair = (Optional<?>) dataResult.getMethod("result").invoke(result);
		return pair.orElseThrow().getClass().getMethod("getFirst").invoke(pair.orElseThrow());
	}
}
