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

package net.forbric.loader.impl.forge.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.electronwill.nightconfig.core.file.FileWatcher;
import net.minecraftforge.fml.config.ConfigFileTypeHandler;

/**
 * The shutdown sweep against stand-ins for night-config's watcher and MinecraftForge's config handler: both owners
 * are stopped, a watcher re-created after the first sweep is caught by the next one, and nothing is asked into
 * existence just to be stopped.
 */
class ForbricClientShutdownTest {
	private final ClassLoader cl = getClass().getClassLoader();

	@BeforeEach
	void fresh() {
		FileWatcher.reset();
		ConfigFileTypeHandler.reset();
	}

	@Test
	void stopsBothOwnersAndDropsForgesFieldLikeItsOwnStopWatcher() {
		FileWatcher neo = FileWatcher.defaultInstance();
		FileWatcher forgeClient = ConfigFileTypeHandler.client().getWatcher();
		FileWatcher forgeServer = ConfigFileTypeHandler.server().getWatcher();

		List<String> stopped = ForbricClientShutdown.sweep(cl);

		assertEquals(3, stopped.size(), stopped.toString());
		assertFalse(neo.running);
		assertFalse(forgeClient.running);
		assertFalse(forgeServer.running);
		assertNull(ConfigFileTypeHandler.client().peekWatcher(), "field dropped, so a later getWatcher builds afresh");
		assertNull(ConfigFileTypeHandler.server().peekWatcher());
	}

	@Test
	void aSweepWithNothingCreatedCreatesNothing() {
		assertEquals(List.of(), ForbricClientShutdown.sweep(cl));
		assertNull(FileWatcher.peekDefault(), "must not call defaultInstance() just to stop it");
		assertNull(ConfigFileTypeHandler.client().peekWatcher());
	}

	@Test
	void aWatcherRecreatedAfterTheSweepIsCaughtByTheNextOne() {
		FileWatcher first = FileWatcher.defaultInstance();
		assertEquals(1, ForbricClientShutdown.sweep(cl).size());
		assertFalse(first.running);

		// The race: a late config load asks for the default instance again; night-config hands out a new one.
		FileWatcher second = FileWatcher.defaultInstance();
		assertTrue(second != first && second.running);
		FileWatcher forgeAgain = ConfigFileTypeHandler.server().getWatcher();

		List<String> late = ForbricClientShutdown.sweep(cl);
		assertEquals(2, late.size(), late.toString());
		assertFalse(second.running);
		assertFalse(forgeAgain.running);
		assertEquals(1, first.stops, "the one already stopped is not stopped twice");
		assertEquals(List.of(), ForbricClientShutdown.sweep(cl), "and a third sweep finds nothing");
	}

	@Test
	void anIdleScheduledWorkerIsRecognisedAsUnreachable() throws Exception {
		java.util.concurrent.ScheduledExecutorService pool = java.util.concurrent.Executors.newScheduledThreadPool(1);
		pool.submit(() -> { }).get();
		try {
			Thread worker = null;
			for (Thread t : Thread.getAllStackTraces().keySet()) {
				if (t.getName().startsWith("pool-") && !t.isDaemon() && ForbricClientShutdown.isIdleExecutorWorker(t)) worker = t;
			}
			assertTrue(worker != null, "the pool's idle worker parks in take() and is recognised");
			assertFalse(ForbricClientShutdown.isIdleExecutorWorker(Thread.currentThread()));
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void leakedThreadsListsOnlyNonDaemonOnes() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		Thread leaker = new Thread(() -> {
			try {
				release.await();
			} catch (InterruptedException ignored) {
				// released
			}
		}, "pool-99-thread-1");
		leaker.setDaemon(false);
		Thread daemon = new Thread(() -> {
			try {
				release.await();
			} catch (InterruptedException ignored) {
				// released
			}
		}, "harmless daemon");
		daemon.setDaemon(true);
		leaker.start();
		daemon.start();
		try {
			List<Thread> alive = ForbricClientShutdown.leakedNonDaemonThreads();
			assertTrue(alive.contains(leaker), alive.toString());
			assertFalse(alive.contains(daemon));
			assertFalse(alive.contains(Thread.currentThread()));
		} finally {
			release.countDown();
			leaker.join(5_000);
			daemon.join(5_000);
		}
	}
}
