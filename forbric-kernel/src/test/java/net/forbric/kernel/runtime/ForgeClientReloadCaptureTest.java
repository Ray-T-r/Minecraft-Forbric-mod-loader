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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Exercises the runtime API through reflection without adding the game-side source set to the test classpath. */
class ForgeClientReloadCaptureTest {

	@Test
	void noScopeAndAnUnconsumedScopeRemainDistinctFromAnEmptyCapture() throws Exception {
		try (RuntimeApi api = runtime()) {
			assertNull(api.drain());
			assertFalse(api.withCaptured(List.of(new Object()), () -> {}));
			assertNull(api.drain(), "a successful but unconsumed capture must leave no pending listeners");
			assertFalse(api.withCaptured(List.of(), () -> {}));
			assertTrue(api.withCaptured(List.of(), () -> {
				List<?> empty = api.drain();
				assertNotNull(empty, "an empty capture must not trigger the legacy registration path");
				assertTrue(empty.isEmpty());
			}));
			assertNull(api.drain());
		}
	}

	@Test
	void snapshotPreservesListenerIdentityAndOrderAndIsOnlyDrainedOnce() throws Exception {
		try (RuntimeApi api = runtime()) {
			Object first = new Object();
			Object second = new Object();
			List<Object> mutable = new ArrayList<>(List.of(first, second));
			assertTrue(api.withCaptured(mutable, () -> {
				mutable.clear();
				List<?> captured = api.drain();
				assertEquals(2, captured.size(), "capture must snapshot before dispatch mutates the source list");
				assertSame(first, captured.get(0));
				assertSame(second, captured.get(1));
				assertThrows(UnsupportedOperationException.class, captured::clear);
				List<?> again = api.drain();
				assertNotNull(again, "a consumed capture must not fall back to reposting Forge registration");
				assertTrue(again.isEmpty());
			}));
			assertNull(api.drain());
		}
	}

	@Test
	void dispatchFailuresPropagateUnchangedAndClearBothUndrainedAndDrainedScopes() throws Exception {
		try (RuntimeApi api = runtime()) {
			for (boolean drainFirst : List.of(false, true)) {
				RuntimeException failure = new IllegalStateException("dispatch failed");
				assertSame(failure, assertThrows(IllegalStateException.class, () ->
						api.withCaptured(List.of(new Object()), () -> {
							if (drainFirst) api.drain();
							throw failure;
						})));
				assertNull(api.drain(), "failed dispatch must leave no capture for a later event");
			}
			AssertionError failure = new AssertionError("dispatch error");
			assertSame(failure, assertThrows(AssertionError.class, () ->
					api.withCaptured(List.of(), () -> { throw failure; })));
			assertNull(api.drain());
		}
	}

	@Test
	void nestedDispatchRestoresTheOuterListenersAndConsumptionState() throws Exception {
		try (RuntimeApi api = runtime()) {
			Object outer = new Object();
			Object inner = new Object();
			assertTrue(api.withCaptured(List.of(outer), () -> {
				assertTrue(api.withCaptured(List.of(inner), () -> assertSame(inner, api.drain().get(0))));
				assertSame(outer, api.drain().get(0));
				assertFalse(api.withCaptured(List.of(inner), () -> {}));
				assertNotNull(api.drain());
				assertTrue(api.drain().isEmpty(), "restoring a consumed outer scope must not restore its contents");
			}));
			assertFalse(api.withCaptured(List.of(outer), () ->
					assertTrue(api.withCaptured(List.of(inner), () -> assertSame(inner, api.drain().get(0))))),
					"draining an inner capture must not mark the outer capture drained");
			assertNull(api.drain());
		}
	}

	@Test
	void nestedFailureRestoresTheOuterScopeBeforeTheCallerHandlesTheFailure() throws Exception {
		try (RuntimeApi api = runtime()) {
			Object outer = new Object();
			RuntimeException failure = new IllegalArgumentException("inner dispatch failed");
			assertTrue(api.withCaptured(List.of(outer), () -> {
				assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
						api.withCaptured(List.of(new Object()), () -> { throw failure; })));
				assertSame(outer, api.drain().get(0));
			}));
			assertNull(api.drain());
		}
	}

	@Test
	void simultaneousScopesAreIsolatedAndNothingRemainsOnAReusedWorkerThread() throws Exception {
		try (RuntimeApi api = runtime()) {
			ExecutorService worker = Executors.newSingleThreadExecutor();
			CountDownLatch workerCaptured = new CountDownLatch(1);
			CountDownLatch mainDrained = new CountDownLatch(1);
			Object mainListener = new Object();
			Object workerListener = new Object();
			try {
				assertTrue(api.withCaptured(List.of(mainListener), () -> {
					CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> {
						assertNull(api.drain(), "another thread must not inherit the current capture");
						return api.withCaptured(List.of(workerListener), () -> {
							workerCaptured.countDown();
							await(mainDrained);
							assertSame(workerListener, api.drain().get(0));
						});
					}, worker);
					try {
						await(workerCaptured);
						assertSame(mainListener, api.drain().get(0));
					} finally {
						mainDrained.countDown();
					}
					assertTrue(result.orTimeout(5, TimeUnit.SECONDS).join());
				}));
				assertNull(api.drain());
				assertNull(worker.submit(api::drain).get(5, TimeUnit.SECONDS),
						"a later task on the same worker must see no leftover capture");
			} finally {
				mainDrained.countDown();
				worker.shutdownNow();
				assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
			}
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS), "concurrent dispatch did not reach its rendezvous");
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}

	private static RuntimeApi runtime() throws Exception {
		Path compiled = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		String binary = "net.forbric.kernel.runtime.ForgeClientReloadCapture";
		assumeTrue(Files.isRegularFile(compiled.resolve(binary.replace('.', '/') + ".class")),
				"runtime capture class has not been compiled");
		URLClassLoader loader = new URLClassLoader(new URL[] {compiled.toUri().toURL()},
				ClassLoader.getPlatformClassLoader());
		try {
			Class<?> type = Class.forName(binary, true, loader);
			return new RuntimeApi(loader, type.getMethod("withCaptured", List.class, Runnable.class),
					type.getMethod("drain"));
		} catch (Throwable failure) {
			loader.close();
			throw failure;
		}
	}

	private record RuntimeApi(URLClassLoader loader, Method capture, Method take) implements AutoCloseable {
		boolean withCaptured(List<?> listeners, Runnable dispatch) {
			return (boolean) invoke(capture, listeners, dispatch);
		}

		List<?> drain() {
			return (List<?>) invoke(take);
		}

		@Override
		public void close() throws Exception {
			loader.close();
		}

		private static Object invoke(Method method, Object... arguments) {
			try {
				return method.invoke(null, arguments);
			} catch (InvocationTargetException wrapped) {
				Throwable failure = wrapped.getCause();
				if (failure instanceof RuntimeException runtime) throw runtime;
				if (failure instanceof Error error) throw error;
				throw new AssertionError(failure);
			} catch (ReflectiveOperationException failure) {
				throw new AssertionError(failure);
			}
		}
	}
}
