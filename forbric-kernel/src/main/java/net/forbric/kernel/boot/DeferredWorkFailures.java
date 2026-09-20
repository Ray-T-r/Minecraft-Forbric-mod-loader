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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.forbric.kernel.util.ForbricLog;

/**
 * Which mods' deferred setup tasks threw, read off a carrier's own {@code DeferredWorkQueue} after
 * {@code runTasks}.
 *
 * <p>Both families' queues have the same private shape — a {@code tasks} deque of {@code TaskInfo}, each with an
 * {@code owner} container and a {@code future} — and {@code runTasks} leaves the deque as it was, with a failed
 * task's future completed exceptionally. So the owners are read from there. The obvious alternative, reading
 * the mod id off the suppressed throwables {@code runTasks} throws, is not possible: both carriers wrap the raw
 * failure and put the mod id only in their log line, never on the exception.
 *
 * <p>Any reflective failure — a renamed field, a container without {@code getModId} — answers an empty list and
 * one DEBUG line, so the callers' count-only report is the worst case; the staged carriers' field names are
 * pinned by a test so a carrier bump goes red there rather than losing attribution silently.
 */
public final class DeferredWorkFailures {
	private DeferredWorkFailures() {
	}

	/** Distinct owner ids of the tasks that completed exceptionally, in queue order; empty when none or unreadable. */
	public static List<String> owners(Object queue) {
		if (queue == null) return List.of();
		try {
			Object tasks = read(queue, "tasks");
			if (!(tasks instanceof Iterable<?> each)) return List.of();
			Set<String> ids = new LinkedHashSet<>();
			for (Object task : each) {
				if (task == null) continue;
				Object future = read(task, "future");
				if (!(future instanceof CompletableFuture<?> cf) || !cf.isCompletedExceptionally()) continue;
				Object owner = read(task, "owner");
				if (owner == null) continue;
				Object id = owner.getClass().getMethod("getModId").invoke(owner);
				if (id != null) ids.add(id.toString());
			}
			return List.copyOf(new ArrayList<>(ids));
		} catch (ReflectiveOperationException | RuntimeException unreadable) {
			ForbricLog.debug("[Forbric/Lifecycle] could not read the failed deferred tasks' owners off %s — %s",
					queue.getClass().getName(), String.valueOf(unreadable));
			return List.of();
		}
	}

	private static Object read(Object target, String name) throws ReflectiveOperationException {
		Class<?> type = target.getClass();
		while (type != null) {
			try {
				Field field = type.getDeclaredField(name);
				field.setAccessible(true);
				return field.get(target);
			} catch (NoSuchFieldException next) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchFieldException(name);
	}
}
