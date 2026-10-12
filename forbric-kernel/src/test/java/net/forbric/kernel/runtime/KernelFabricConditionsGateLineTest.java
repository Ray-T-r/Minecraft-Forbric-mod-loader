package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import net.forbric.kernel.GateLogContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * gate-m9 asserts that a decode no source guard owns is still judged by the line KernelFabricConditions prints the
 * first time it asks Fabric's evaluator. That line was reworded (2b9c8baa) while the gate kept grepping the old one,
 * so every m9 run went red whether conditions were judged or not. This runs the real wrap over the compiled game-side
 * classes, with the evaluator substituted, and greps what it prints with the gate's own pattern.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class KernelFabricConditionsGateLineTest {
	@Test void theFirstJudgementPrintsTheLineGateM9Reads(@TempDir Path scratch) throws Exception {
		String pattern = GateLogContract.pattern(Path.of("run/gate-m9-client.sh"), "and the conditions no source guard owns are still judged");
		try (URLClassLoader cl = KernelConditionKillSwitchesTest.gameSideLoader()) {
			Class<?> helper = Class.forName("net.forbric.kernel.runtime.KernelFabricConditions", true, cl);
			Class<?> codecClass = Class.forName("com.mojang.serialization.Codec", true, cl);
			Class<?> dataResult = Class.forName("com.mojang.serialization.DataResult", true, cl);
			Class<?> ops = Class.forName("com.mojang.serialization.DynamicOps", true, cl);
			Object json = Class.forName("com.mojang.serialization.JsonOps", true, cl).getField("INSTANCE").get(null);
			Class<?> jsonObject = Class.forName("com.google.gson.JsonObject", true, cl);
			Object input = Class.forName("com.google.gson.JsonParser", true, cl).getMethod("parseString", String.class)
					.invoke(null, "{\"fabric:load_conditions\":{\"condition\":\"fabric:not\"},\"value\":1}");

			AtomicInteger asked = new AtomicInteger();
			MethodHandle no = MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, jsonObject, String.class,
					Class.forName("net.minecraft.resources.Identifier", false, cl),
					Class.forName("net.minecraft.resources.RegistryOps$RegistryInfoLookup", false, cl));
			Method bind = helper.getDeclaredMethod("bindForTest", MethodHandle.class);
			bind.setAccessible(true);
			bind.invoke(null, MethodHandles.foldArguments(no, MethodHandles.dropArguments(MethodHandles.lookup().findVirtual(AtomicInteger.class,
					"incrementAndGet", java.lang.invoke.MethodType.methodType(int.class)).bindTo(asked).asType(java.lang.invoke.MethodType.methodType(void.class)),
					0, no.type().parameterList())));
			Method error = dataResult.getMethod("error", java.util.function.Supplier.class);
			Object strict = Proxy.newProxyInstance(cl, new Class<?>[] { codecClass }, (proxy, method, args) -> {
				if ("decode".equals(method.getName())) return error.invoke(null, (java.util.function.Supplier<String>) () -> "strict");
				if ("toString".equals(method.getName())) return "strict";
				throw new UnsupportedOperationException(method.getName());
			});
			Object wrapped = helper.getMethod("alsoAskFabric", codecClass).invoke(null, strict);
			Method decode = codecClass.getMethod("decode", ops, Object.class);
			String[] out = new String[1];
			GateLogContract.capture(() -> { decode.invoke(wrapped, json, input); decode.invoke(wrapped, json, input); return null; }, out);
			assertEquals(2, asked.get(), "premise: Fabric's evaluator was asked about both decodes");
			assertEquals(1, GateLogContract.count(scratch, pattern, out[0]), out[0]);
		}
	}
}
