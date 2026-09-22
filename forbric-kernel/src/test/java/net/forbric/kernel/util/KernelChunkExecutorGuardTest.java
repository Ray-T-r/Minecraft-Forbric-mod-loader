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

package net.forbric.kernel.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.Test;

/**
 * The guard's decision rule, driven directly.
 *
 * <p>Split out of the injected {@code execute} for exactly this: the rule is the whole risk. A guard that
 * is too eager does not fail loudly, it rejects legitimate chunk work during a normal shutdown and takes
 * the save with it — so "alive means never reject" needs an assertion, not a comment.
 *
 * <p>This class names no game type, which is why it can be tested at all on a machine with no staged
 * Minecraft artifacts.
 */
class KernelChunkExecutorGuardTest {
	@Test
	void aLiveThreadIsNeverRefused() {
		assertDoesNotThrow(() -> KernelChunkExecutorGuard.check(Thread.currentThread()),
				"the executor's own thread is alive, so its queue will be drained and the guard must be inert — "
						+ "this is the case that covers every normal tick and the whole of a normal shutdown");
	}

	@Test
	void anUnknownThreadIsNeverRefused() {
		assertDoesNotThrow(() -> KernelChunkExecutorGuard.check(null),
				"an executor that will not say which thread it belongs to is not evidence that the thread is "
						+ "gone; a guard must never invent one");
	}

	@Test
	void aDeadThreadIsRefusedAndSaysWhy() throws Exception {
		CountDownLatch started = new CountDownLatch(1);
		Thread dead = new Thread(started::countDown, "forbric-test-dead-executor");
		dead.start();
		started.await();
		dead.join();

		assertTrue(!dead.isAlive(), "the fixture has to be genuinely dead or the assertion below proves nothing");

		RejectedExecutionException thrown = assertThrows(RejectedExecutionException.class,
				() -> KernelChunkExecutorGuard.check(dead),
				"nothing can ever drain a queue whose only thread is dead, so a join() on work offered to it is "
						+ "an infinite park by construction");
		assertTrue(thrown.getMessage().contains("forbric-test-dead-executor"),
				"the message has to name the dead thread — the whole point is that the crash report identifies "
						+ "the culprit instead of showing three parked stacks, so: " + thrown.getMessage());
	}
}
