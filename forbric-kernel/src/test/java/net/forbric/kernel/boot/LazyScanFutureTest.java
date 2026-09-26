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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
}
