package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The vanilla-shaped Operation a Fabric shears wrap gets: asked about shears it asks the carrier; otherwise vanilla's is. */
class KernelShearsTest {
	private URLClassLoader loader;
	private Class<?> operation;
	private Method relay, call;

	@BeforeEach void load() throws Exception {
		String extras = System.getProperty("forbric.mixinExtrasForTests", "");
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		Assumptions.assumeTrue(!extras.isEmpty() && Files.isDirectory(runtime), "game-side classes and MixinExtras required");
		loader = new URLClassLoader(new URL[] { runtime.toUri().toURL(), Path.of(extras).toUri().toURL() }, ClassLoader.getPlatformClassLoader());
		operation = loader.loadClass("com.llamalad7.mixinextras.injector.wrapoperation.Operation");
		relay = loader.loadClass("net.forbric.kernel.runtime.KernelShears")
				.getDeclaredMethod("relay", operation, Object.class, Object.class, BiPredicate.class);
		relay.setAccessible(true);
		call = operation.getMethod("call", Object[].class);
	}

	@AfterEach void close() throws Exception {
		if (loader != null) loader.close();
	}

	private Object carrier(List<Object[]> seen, boolean answer) {
		return Proxy.newProxyInstance(loader, new Class<?>[] { operation }, (proxy, method, args) -> {
			if (!method.getName().equals("call")) return method.invoke(this, args);
			seen.add((Object[]) args[0]);
			return answer;
		});
	}

	@Test void askedAboutShearsItAsksTheCarrierAboutTheStackItWasHanded() throws Exception {
		List<Object[]> seen = new ArrayList<>();
		BiPredicate<Object, Object> vanilla = (stack, item) -> { throw new AssertionError("vanilla is() must not run for shears"); };
		Object old = relay.invoke(null, carrier(seen, true), "SHEARS_CARVE", "shears", vanilla);
		assertEquals(Boolean.TRUE, call.invoke(old, (Object) new Object[] { "stack", "shears" }));
		assertEquals(List.of("stack", "SHEARS_CARVE"), Arrays.asList(seen.getFirst()), "the carrier's own constant, not the vanilla item");
		call.invoke(old, (Object) new Object[] { "otherStack", "shears" });
		assertEquals(List.of("otherStack", "SHEARS_CARVE"), Arrays.asList(seen.get(1)), "a stack the handler substitutes is the one asked");
	}

	@Test void askedAboutAnythingElseItMakesTheVanillaCall() throws Exception {
		List<Object[]> seen = new ArrayList<>();
		List<Object> asked = new ArrayList<>();
		Object old = relay.invoke(null, carrier(seen, true), "SHEARS_HARVEST", "shears",
				(BiPredicate<Object, Object>) (stack, item) -> asked.add(stack + ":" + item) && item.equals("bowl"));
		assertEquals(Boolean.TRUE, call.invoke(old, (Object) new Object[] { "stack", "bowl" }));
		assertEquals(Boolean.FALSE, call.invoke(old, (Object) new Object[] { "stack", "stick" }));
		assertEquals(List.of("stack:bowl", "stack:stick"), asked);
		assertTrue(seen.isEmpty(), "the carrier is never asked about a bowl");
	}
}
