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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * The scope's contract, over the compiled game-side class: it names only {@code java.util}, so a loader over
 * {@code build/classes/java/runtime} alone is enough.
 */
class KernelSnippetsTest {
	private static Class<?> load() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		assumeTrue(Files.isRegularFile(compiled.resolve("net/forbric/kernel/runtime/KernelSnippets.class")), "runtime helper not compiled");
		URLClassLoader loader = new URLClassLoader(new URL[] { compiled.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
		return loader.loadClass("net.forbric.kernel.runtime.KernelSnippets");
	}

	@Test
	void scopeThenTakeReturnsTheValueOnceAndThenEmpty() throws Exception {
		Class<?> k = load();
		Method scope = k.getMethod("scope", Optional.class), take = k.getMethod("take");
		Optional<String> stencil = Optional.of("stencil");
		scope.invoke(null, stencil);
		assertSame(stencil, take.invoke(null), "the scoped value, by identity");
		assertEquals(Optional.empty(), take.invoke(null), "consumed on the first read");
	}

	@Test
	void takeWithoutScopeIsEmptyAndClearDiscards() throws Exception {
		Class<?> k = load();
		Method scope = k.getMethod("scope", Optional.class), take = k.getMethod("take"), clear = k.getMethod("clear");
		assertEquals(Optional.empty(), take.invoke(null));
		scope.invoke(null, Optional.of("x"));
		clear.invoke(null);
		assertEquals(Optional.empty(), take.invoke(null), "a wrap that never called the original leaves nothing behind");
		scope.invoke(null, new Object[] { null });
		assertEquals(Optional.empty(), take.invoke(null), "a null scope reads as empty, never as null");
	}

	@Test
	void aValueScopedOnOneThreadIsInvisibleOnAnother() throws Exception {
		Class<?> k = load();
		Method scope = k.getMethod("scope", Optional.class), take = k.getMethod("take");
		scope.invoke(null, Optional.of("mine"));
		AtomicReference<Object> other = new AtomicReference<>();
		Thread t = new Thread(() -> {
			try {
				other.set(take.invoke(null));
			} catch (Exception e) {
				other.set(e);
			}
		});
		t.start();
		t.join();
		assertEquals(Optional.empty(), other.get());
		assertTrue(take.invoke(null).equals(Optional.of("mine")), "and still there for the thread that scoped it");
	}
}
