package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The old-shaped Operation hands the merged call its arguments in the merged order, with the call site's extras. */
class KernelWrapOperationsTest {
	private URLClassLoader loader;
	private Class<?> operation;
	private Method reordered, call;

	@BeforeEach void load() throws Exception {
		String extras = System.getProperty("forbric.mixinExtrasForTests", "");
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		Assumptions.assumeTrue(!extras.isEmpty() && Files.isDirectory(runtime), "game-side classes and MixinExtras required");
		loader = new URLClassLoader(new URL[] { runtime.toUri().toURL(), Path.of(extras).toUri().toURL() }, ClassLoader.getPlatformClassLoader());
		operation = loader.loadClass("com.llamalad7.mixinextras.injector.wrapoperation.Operation");
		reordered = loader.loadClass("net.forbric.kernel.runtime.KernelWrapOperations")
				.getMethod("reordered", operation, boolean.class, String.class, Object[].class);
		call = operation.getMethod("call", Object[].class);
	}

	@AfterEach void close() throws Exception {
		if (loader != null) loader.close();
	}

	@Test void argumentsTheHandlerPassesLandWhereTheMergedCallTakesThem() throws Exception {
		AtomicReference<Object[]> seen = new AtomicReference<>();
		// vanilla (receiver, level, pos, includeData) → NeoForge (receiver, pos, level, includeData, player)
		Object old = reordered.invoke(null, merged(seen, "clone"), true, "1,0,2", new Object[] { null, null, null, "player" });
		assertEquals("clone", call.invoke(old, (Object) new Object[] { "state", "level", "pos", Boolean.TRUE }));
		assertEquals(List.of("state", "pos", "level", Boolean.TRUE, "player"), Arrays.asList(seen.get()));
		call.invoke(old, (Object) new Object[] { "state", "otherLevel", "otherPos", Boolean.FALSE });
		assertEquals(List.of("state", "otherPos", "otherLevel", Boolean.FALSE, "player"), Arrays.asList(seen.get()),
				"what the handler changes is what the call receives");
	}

	@Test void aStaticCallHasNoReceiverAndAPrefixKeepsItsOrder() throws Exception {
		AtomicReference<Object[]> seen = new AtomicReference<>();
		Object old = reordered.invoke(null, merged(seen, 7), false, "0,1", new Object[] { null, null, "protocol", "flow" });
		assertEquals(7, call.invoke(old, (Object) new Object[] { "fallback", "list" }));
		assertEquals(List.of("fallback", "list", "protocol", "flow"), Arrays.asList(seen.get()));
	}

	@Test void nothingIsCalledUntilTheHandlerCalls() throws Exception {
		AtomicReference<Object[]> seen = new AtomicReference<>();
		reordered.invoke(null, merged(seen, "x"), true, "0", new Object[] { null });
		assertNull(seen.get(), "a handler that skips the call skips it");
	}

	private Object merged(AtomicReference<Object[]> seen, Object result) {
		return Proxy.newProxyInstance(loader, new Class<?>[] { operation }, (proxy, method, args) -> {
			if (!method.getName().equals("call")) return method.invoke(this, args);
			seen.set((Object[]) args[0]);
			return result;
		});
	}
}
