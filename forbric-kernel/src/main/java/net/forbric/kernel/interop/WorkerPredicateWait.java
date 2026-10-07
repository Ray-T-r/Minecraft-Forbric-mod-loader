/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/** Rechecks a proved running/queued-work predicate under the notifier's monitor. */
public final class WorkerPredicateWait {
	private WorkerPredicateWait() { }
	public static void awaitNotification(Object notifier, Object executor, String runningField, String queueField) {
		awaitNotification(notifier, executor, runningField, queueField, false);
	}
	public static void awaitNotification(Object notifier, Object executor, String runningField, String queueField, boolean restoreInterrupt) {
        awaitNotification(notifier, executor, executor.getClass(), runningField, queueField, restoreInterrupt);
    }
    /** Binds fields to their proved declaration even when the live pool has a subclass with shadowing fields. */
    public static void awaitNotification(Object notifier, Object executor, Class<?> declaration, String runningField, String queueField, boolean restoreInterrupt) {
        if(!declaration.isInstance(executor))throw new IllegalArgumentException("worker predicate owner");
		try {
			Field running = declaration.getDeclaredField(runningField);
			Field queue = declaration.getDeclaredField(queueField);
			running.setAccessible(true); queue.setAccessible(true);
			synchronized (notifier) {
				if (((AtomicBoolean) running.get(executor)).get() && ((Deque<?>) queue.get(executor)).isEmpty()) {
					try { notifier.wait(); }
					catch (InterruptedException interrupted) { if (restoreInterrupt) Thread.currentThread().interrupt(); }
				}
			}
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("The proved worker predicate is no longer accessible", failure);
		}
	}
}
