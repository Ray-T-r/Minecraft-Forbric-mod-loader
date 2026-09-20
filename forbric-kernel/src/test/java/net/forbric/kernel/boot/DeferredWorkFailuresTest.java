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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.junit.jupiter.api.Test;

/** The owners read off a queue shaped like both carriers' DeferredWorkQueue: failed tasks only, distinct, in order. */
class DeferredWorkFailuresTest {
	/** The carriers' shape: a private deque of TaskInfo, each with a private owner and future. */
	static final class FakeQueue {
		@SuppressWarnings("unused")
		private final ConcurrentLinkedDeque<TaskInfo> tasks = new ConcurrentLinkedDeque<>();
	}

	static final class TaskInfo {
		@SuppressWarnings("unused")
		private final Object owner;
		@SuppressWarnings("unused")
		private CompletableFuture<?> future;

		TaskInfo(Object owner, CompletableFuture<?> future) {
			this.owner = owner;
			this.future = future;
		}
	}

	public static final class Container {
		private final String id;

		Container(String id) {
			this.id = id;
		}

		public String getModId() {
			return id;
		}
	}

	@Test
	void onlyTheOwnersOfTasksThatThrewAreNamed() {
		FakeQueue queue = new FakeQueue();
		queue.tasks.add(new TaskInfo(new Container("goodmod"), CompletableFuture.completedFuture(null)));
		queue.tasks.add(new TaskInfo(new Container("badmod"), CompletableFuture.failedFuture(new NoClassDefFoundError("gone"))));
		queue.tasks.add(new TaskInfo(new Container("badmod"), CompletableFuture.failedFuture(new IllegalStateException())));
		queue.tasks.add(new TaskInfo(new Container("worse"), CompletableFuture.failedFuture(new IllegalStateException())));
		queue.tasks.add(new TaskInfo(new Container("pending"), new CompletableFuture<>()));
		assertEquals(List.of("badmod", "worse"), DeferredWorkFailures.owners(queue));
	}

	@Test
	void nothingFailedIsAnEmptyList() {
		FakeQueue queue = new FakeQueue();
		queue.tasks.add(new TaskInfo(new Container("goodmod"), CompletableFuture.completedFuture(null)));
		assertTrue(DeferredWorkFailures.owners(queue).isEmpty());
		assertTrue(DeferredWorkFailures.owners(null).isEmpty());
	}

	@Test
	void aQueueOfAnotherShapeAnswersEmptyAndDoesNotThrow() {
		assertTrue(DeferredWorkFailures.owners(new Object()).isEmpty(), "no tasks field");
		FakeQueue queue = new FakeQueue();
		queue.tasks.add(new TaskInfo(new Object(), CompletableFuture.failedFuture(new IllegalStateException())));
		assertTrue(DeferredWorkFailures.owners(queue).isEmpty(), "an owner without getModId");
	}
}
