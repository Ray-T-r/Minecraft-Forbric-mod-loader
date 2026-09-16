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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The cancel rule the cancellable-entity bridges live or die by, driven for real.
 *
 * <p>These three events are {@code ICancellableEvent} and a MinecraftForge mod cancelling one is the whole point
 * of listening — a grave mod cancels the death, a mob filter cancels the join. Forwarding without carrying the
 * veto back gives those mods a listener that runs, decides and is ignored, which is worse than one that does not
 * run because it looks like it works. Carrying it back TOO eagerly is worse still: a MinecraftForge mod that
 * merely does not cancel would clear a NeoForge mod's cancellation, and that is a far harder bug to find.
 *
 * <p>The decision is one static with no game types in its signature, so it is loaded through a game-side loader
 * and invoked directly rather than asserted on as text.
 */
class KernelGameEntityEventsTest {
	/** Records every {@code setCanceled} the rule performs, so "did not call it at all" is distinguishable. */
	private static final class Recorder implements InvocationHandler {
		private final List<Boolean> calls = new ArrayList<>();

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			if ("setCanceled".equals(method.getName())) calls.add((Boolean) args[0]);
			return null;
		}
	}

	private static List<Boolean> run(boolean alreadyCanceled, boolean forgeVetoed) throws Exception {
		try (URLClassLoader cl = gameSideLoader()) {
			Class<?> bridge = Class.forName("net.forbric.kernel.runtime.KernelGameEntityEvents", true, cl);
			Class<?> veto = Class.forName("net.forbric.kernel.runtime.KernelGameEntityEvents$Veto", true, cl);
			Recorder recorder = new Recorder();
			Object proxy = Proxy.newProxyInstance(cl, new Class<?>[] {veto}, recorder);
			Method carry = bridge.getDeclaredMethod("carryVeto", boolean.class, boolean.class, veto);
			carry.setAccessible(true);
			carry.invoke(null, alreadyCanceled, forgeVetoed, proxy);
			return recorder.calls;
		}
	}

	@Test
	void aMinecraftForgeVetoCancelsTheNeoForgeEvent() throws Exception {
		assertEquals(List.of(true), run(false, true),
				"a MinecraftForge mod that cancels the death, the drops or the join must actually stop it — "
						+ "otherwise its listener runs, decides and is ignored");
	}

	@Test
	void aMinecraftForgeModThatDoesNotVetoChangesNothing() throws Exception {
		assertTrue(run(false, false).isEmpty(),
				"setCanceled must not be called at all when nobody cancelled");
	}

	@Test
	void aMinecraftForgeModCannotUndoANeoForgeCancellation() throws Exception {
		assertTrue(run(true, false).isEmpty(),
				"a MinecraftForge mod that merely does not cancel must never clear a NeoForge mod's cancellation — "
						+ "not even by writing false, which is how that silently happens");
	}

	@Test
	void anAlreadyCancelledEventIsNotCancelledTwice() throws Exception {
		assertTrue(run(true, true).isEmpty(),
				"when both families cancel, the event is already in the right state and writing again buys nothing");
	}

	/** The runtime output plus the staged carriers, which is what these classes link against. */
	private static URLClassLoader gameSideLoader() throws Exception {
		Path compiled = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").normalize();
		Path run = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
		Path forgeRt = run.resolve("forge-runtime/forge-runtime.jar");
		Path neoRt = run.resolve("neoforge-runtime/neoforge-runtime.jar");
		assumeTrue(Files.isDirectory(compiled) && Files.isRegularFile(forgeRt) && Files.isRegularFile(neoRt),
				"the game-side set is not compiled, or the staged carriers are absent");
		List<URL> urls = new ArrayList<>(List.of(compiled.toUri().toURL(), forgeRt.toUri().toURL(),
				neoRt.toUri().toURL()));
		return new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
	}
}
