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

package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link NeoParallelDispatch} runs a NeoForge phase the way {@code ModLoader.dispatchParallelTask} does: on the executor
 * it is handed, mods with nothing between them at the same time, a mod only after the mods it waits for, and every mod's
 * throw handed back to the caller instead of ending the phase.
 */
class NeoParallelDispatchTest {
	private static final Function<String, String> ID = Function.identity();
	private ExecutorService pool;

	@BeforeEach
	void pool() {
		pool = Executors.newFixedThreadPool(4, r -> {
			Thread t = new Thread(r, "modloading-worker-0");
			t.setDaemon(true);
			return t;
		});
	}

	@AfterEach
	void stop() {
		pool.shutdownNow();
	}

	/** {@code after} as {@link ModConstructionOrder#edges} gives it: each id to the ids that come after it. */
	private static Map<String, Set<String>> after(String... pairs) {
		Map<String, Set<String>> after = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			after.computeIfAbsent(pairs[i], k -> new java.util.LinkedHashSet<>()).add(pairs[i + 1]);
		}
		return after;
	}

	@Test
	void everyModRunsOnTheExecutorAndTheCallerWaitsForAll() {
		Map<String, Thread> ranOn = new ConcurrentHashMap<>();
		List<Throwable> failures = NeoParallelDispatch.run(pool, List.of("a", "b", "c"), ID, Map.of(),
				id -> ranOn.put(id, Thread.currentThread()));

		assertEquals(Set.of("a", "b", "c"), ranOn.keySet(), "run returns only after every mod has run");
		for (Thread t : ranOn.values()) {
			assertNotSame(Thread.currentThread(), t, "not on the caller's thread");
			assertEquals("modloading-worker-0", t.getName(), "on the executor's worker");
		}
		assertEquals(Arrays.asListOfNulls(3), failures, "nothing threw");
	}

	/** Two mods with nothing between them are running at the same moment: each waits for the other to have started. */
	@Test
	void modsWithNothingBetweenThemRunAtTheSameTime() {
		CountDownLatch both = new CountDownLatch(2);
		Map<String, Boolean> sawTheOther = new ConcurrentHashMap<>();
		NeoParallelDispatch.run(pool, List.of("a", "b"), ID, Map.of(), id -> {
			both.countDown();
			sawTheOther.put(id, both.await(10, TimeUnit.SECONDS));
		});
		assertEquals(Map.of("a", true, "b", true), sawTheOther,
				"one after another, the first would wait out the latch alone: native NeoForge runs them in parallel");
	}

	@Test
	void aModStartsOnlyAfterTheModsItWaitsFor() {
		List<String> finished = Collections.synchronizedList(new ArrayList<>());
		Map<String, List<String>> doneBefore = new ConcurrentHashMap<>();
		// lib before dep; mid has nothing to do with either.
		NeoParallelDispatch.run(pool, List.of("lib", "mid", "dep"), ID, after("lib", "dep"), id -> {
			doneBefore.put(id, List.copyOf(finished));
			if (id.equals("lib")) Thread.sleep(200);
			finished.add(id);
		});
		assertTrue(doneBefore.get("dep").contains("lib"), "dep started after lib finished: " + doneBefore);
	}

	/** An edge through a mod this phase does not run (a Fabric mod, say) still orders the two it connects. */
	@Test
	void anEdgeThroughAModNotDispatchedIsFollowedThroughIt() {
		assertEquals(Map.of("lib", Set.of(), "dep", Set.of("lib")),
				NeoParallelDispatch.waitsFor(List.of("lib", "dep"), after("lib", "fabric", "fabric", "dep")));

		List<String> finished = Collections.synchronizedList(new ArrayList<>());
		Map<String, List<String>> doneBefore = new ConcurrentHashMap<>();
		NeoParallelDispatch.run(pool, List.of("lib", "dep"), ID, after("lib", "fabric", "fabric", "dep"), id -> {
			doneBefore.put(id, List.copyOf(finished));
			if (id.equals("lib")) Thread.sleep(200);
			finished.add(id);
		});
		assertEquals(List.of("lib"), doneBefore.get("dep"));
	}

	/** A declared cycle cannot hang the phase: a mod never waits for one listed after it. */
	@Test
	void aCycleDoesNotDeadlock() {
		Set<String> ran = ConcurrentHashMap.newKeySet();
		NeoParallelDispatch.run(pool, List.of("x", "y"), ID, after("x", "y", "y", "x"), ran::add);
		assertEquals(Set.of("x", "y"), ran);
	}

	@Test
	void aThrowIsHandedBackAndTheOthersStillRun() {
		IllegalStateException boom = new IllegalStateException("a mod's own failure");
		Set<String> ran = ConcurrentHashMap.newKeySet();
		List<Throwable> failures = NeoParallelDispatch.run(pool, List.of("lib", "dep", "free"), ID, after("lib", "dep"),
				id -> {
					ran.add(id);
					if (id.equals("lib")) throw boom;
				});
		assertSame(boom, failures.get(0), "the mod's own throw, unwrapped, at its index");
		assertNull(failures.get(1));
		assertNull(failures.get(2));
		assertEquals(Set.of("lib", "dep", "free"), ran,
				"what a failure means is the caller's business: the dispatch runs the dependent as before");
	}

	@Test
	void withNoOrderTheModsRunOneAfterAnotherInTheGivenOrder() {
		List<String> order = Collections.synchronizedList(new ArrayList<>());
		NeoParallelDispatch.run(pool, List.of("c", "a", "b"), ID, null, id -> {
			if (id.equals("c")) Thread.sleep(100);
			order.add(id);
		});
		assertEquals(List.of("c", "a", "b"), order, "-Dforbric.modOrder=name keeps the file-name order it asks for");
	}

	@Test
	void noExecutorRunsHereInOrder() {
		List<Thread> threads = new ArrayList<>();
		List<String> order = new ArrayList<>();
		NeoParallelDispatch.run(null, List.of("b", "a"), ID, Map.of(), id -> {
			threads.add(Thread.currentThread());
			order.add(id);
		});
		assertEquals(List.of("b", "a"), order);
		assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), threads);
	}

	@Test
	void aCarrierWithoutModWorkManagerReportsNoExecutor() {
		assertNull(NeoParallelDispatch.parallelExecutor(NeoParallelDispatchTest.class.getClassLoader()),
				"net.neoforged.fml.ModWorkManager is not on the test classpath, so this must fail soft");
	}

	private static final class Arrays {
		static List<Throwable> asListOfNulls(int n) {
			List<Throwable> out = new ArrayList<>();
			for (int i = 0; i < n; i++) out.add(null);
			return out;
		}
	}
}
