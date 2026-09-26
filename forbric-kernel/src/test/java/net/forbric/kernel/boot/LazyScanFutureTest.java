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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@link LazyScanFuture}: computed on first read, exactly once, and never seen half-done — the stand-in for the
 * background scan FML's {@code startScan} would have run for a seeded {@code ModFile}.
 */
class LazyScanFutureTest {

	@Test
	void nothingRunsUntilSomethingReads() {
		AtomicInteger runs = new AtomicInteger();
		new LazyScanFuture(() -> runs.incrementAndGet());
		assertEquals(0, runs.get(), "building the future must not scan anything");
	}

	@Test
	void everyReadPathSeesOneComputation() throws Exception {
		AtomicInteger runs = new AtomicInteger();
		Object value = new Object();
		LazyScanFuture future = new LazyScanFuture(() -> {
			runs.incrementAndGet();
			return value;
		});

		// getNow first: on an ordinary in-flight future it would hand back the default. Here there is no in-flight.
		assertSame(value, future.getNow("absent"));
		assertSame(value, future.get());
		assertSame(value, future.get(1, TimeUnit.SECONDS));
		assertSame(value, future.join());
		assertSame(value, future.resultNow());
		assertTrue(future.isDone());
		assertEquals(Future.State.SUCCESS, future.state());
		assertEquals(1, runs.get(), "one scan, however many readers");
	}

	@Test
	void aStateQueryAloneComputesItSoIsDoneNeverLies() {
		AtomicInteger runs = new AtomicInteger();
		LazyScanFuture future = new LazyScanFuture(() -> runs.incrementAndGet());
		assertTrue(future.isDone(), "a caller checking isDone() before getNow(null) must get the value, not null");
		assertEquals(1, future.getNow(null));
		assertEquals(1, runs.get());
	}

	@Test
	void aSupplierThatThrowsIsAFailedScanNotAHang() {
		LazyScanFuture future = new LazyScanFuture(() -> {
			throw new IllegalStateException("no index");
		});
		ExecutionException thrown = assertThrows(ExecutionException.class, future::get);
		assertEquals("no index", thrown.getCause().getMessage());
		assertTrue(future.isCompletedExceptionally());
	}

	@Test
	void aSecondReaderWaitsForTheOneScanInsteadOfStartingItsOwn() throws Exception {
		// RollingGate's constructor walks every NeoForge file on the main thread while JEI or Jade may ask ModList
		// for the same jar from a worker: the late reader must get the first reader's object, from one scan.
		AtomicInteger runs = new AtomicInteger();
		CountDownLatch scanning = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Object value = new Object();
		LazyScanFuture future = new LazyScanFuture(() -> {
			runs.incrementAndGet();
			scanning.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return value;
		});
		AtomicReference<Object> first = new AtomicReference<>();
		AtomicReference<Object> second = new AtomicReference<>();
		Thread firstReader = new Thread(() -> first.set(future.join()), "first-reader");
		firstReader.start();
		assertTrue(scanning.await(10, TimeUnit.SECONDS), "the first reader started the scan");
		Thread secondReader = new Thread(() -> second.set(future.join()), "second-reader");
		secondReader.start();
		// The second reader is parked on the lock, not running a scan of its own.
		for (int i = 0; i < 200 && secondReader.getState() != Thread.State.BLOCKED; i++) Thread.sleep(10);
		assertEquals(Thread.State.BLOCKED, secondReader.getState());
		release.countDown();
		firstReader.join(10_000);
		secondReader.join(10_000);
		assertSame(value, first.get());
		assertSame(value, second.get());
		assertEquals(1, runs.get(), "one scan for both readers");
	}

	@Test
	void theScanReadingItsOwnResultFailsInsteadOfHangingForever() {
		// The monitor is re-entrant: without the guard the nested get() found the supplier taken and waited in
		// super.get() for a value only this same thread could produce.
		AtomicReference<LazyScanFuture> self = new AtomicReference<>();
		LazyScanFuture future = new LazyScanFuture(() -> {
			try {
				return self.get().get();
			} catch (InterruptedException | ExecutionException e) {
				throw new IllegalStateException(e);
			}
		});
		self.set(future);
		ExecutionException thrown = assertTimeoutPreemptively(Duration.ofSeconds(10),
				() -> assertThrows(ExecutionException.class, future::get), "a re-entrant read must not hang");
		assertTrue(thrown.getCause() instanceof IllegalStateException, String.valueOf(thrown.getCause()));
		assertTrue(thrown.getCause().getMessage().contains("re-entrant scan read"), thrown.getCause().getMessage());
		assertTrue(future.isCompletedExceptionally(), "the failed scan is what every later reader sees");
	}
}
