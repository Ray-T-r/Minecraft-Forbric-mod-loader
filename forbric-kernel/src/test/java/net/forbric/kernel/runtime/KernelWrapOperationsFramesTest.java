package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.interop.CallbackFrames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The originals a callback frame hands its handler. {@code applied}: the handler's own receiver goes inward, so a handler
 * that passes another state on is heard with it. {@code constant}: an outer frame's original is the inner frames' answer,
 * computed only when — and each time — the handler calls it.
 */
class KernelWrapOperationsFramesTest {
	private URLClassLoader loader;
	private Method applied, constant, call;

	@BeforeEach void load() throws Exception {
		String extras = System.getProperty("forbric.mixinExtrasForTests", "");
		Path runtime = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		assertFalse(extras.isEmpty(), "Gradle hands every test task MixinExtras, from nestedMods, as forbric.mixinExtrasForTests");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(runtime), "game-side classes required");
		// The frames' Deferred comes from the kernel's boot side, as ForbricClassLoader always takes that package from it.
		loader = new URLClassLoader(new URL[] { runtime.toUri().toURL(), Path.of(extras).toUri().toURL() }, getClass().getClassLoader());
		Class<?> operations = loader.loadClass("net.forbric.kernel.runtime.KernelWrapOperations");
		Class<?> operation = loader.loadClass("com.llamalad7.mixinextras.injector.wrapoperation.Operation");
		applied = operations.getMethod("applied", Function.class);
		constant = operations.getMethod("constant", Object.class);
		call = operation.getMethod("call", Object[].class);
	}

	@AfterEach void close() throws Exception {
		if (loader != null) loader.close();
	}

	@Test void appliedRunsTheOriginalOnTheReceiverTheHandlerPasses() throws Exception {
		Object original = applied.invoke(null, (Function<Object, Object>) state -> "group of " + state);
		assertEquals("group of felt", call.invoke(original, (Object) new Object[] { "felt" }));
		assertEquals("group of wool", call.invoke(original, (Object) new Object[] { "wool" }), "what the handler passes is what is asked");
	}

	@Test void constantResolvesTheInnerFramesOnlyWhenAsked() throws Exception {
		AtomicInteger inner = new AtomicInteger();
		Object deferred = new CallbackFrames.Deferred(() -> { inner.incrementAndGet(); return Boolean.TRUE; });
		Object original = constant.invoke(null, deferred);
		assertEquals(0, inner.get(), "nothing runs until the handler calls its original");
		assertEquals(Boolean.TRUE, call.invoke(original, (Object) new Object[] { "diver" }));
		assertEquals(Boolean.TRUE, call.invoke(original, (Object) new Object[] { "diver" }));
		assertEquals(2, inner.get(), "each call runs the inner wrap again, as vanilla's original would");
		assertEquals(Boolean.FALSE, call.invoke(constant.invoke(null, Boolean.FALSE), (Object) new Object[] { "diver" }));
	}
}
