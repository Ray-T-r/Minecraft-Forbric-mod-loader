/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.util.HashMap;
import java.util.Map;

/**
 * Counts, per thread, the times a relocated vanilla call was actually reached, so a mod's handler replayed at the return of
 * the method the call left runs only when the call was made in between.
 *
 * <p>Where the surviving carrier makes a vanilla call inside a transaction of its own, the kernel replays a mod's handler for
 * a point after that call at the method's return; whether vanilla would have reached the call is decided at the method's
 * head. The carrier may still refuse after the head — an event a listener cancels, a placement the transaction rolls back
 * — and then the call is never made: the return must not replay a handler for a point after a call that did not happen.
 * The head reads a {@link #stamp}; the call site counts with {@link #reached}; the return compares. Names no game type.
 */
public final class CallWitness {
	private static final ThreadLocal<Map<String, long[]>> COUNTS = ThreadLocal.withInitial(HashMap::new);

	private CallWitness() {
	}

	/** How many times this thread has reached the call {@code key} so far. */
	public static long stamp(String key) {
		long[] count = COUNTS.get().get(key);
		return count == null ? 0 : count[0];
	}

	/** This thread reaches the call {@code key} now. */
	public static void reached(String key) {
		COUNTS.get().computeIfAbsent(key, ignored -> new long[1])[0]++;
	}

	/** Whether this thread reached the call {@code key} since {@code stamp} was read. */
	public static boolean since(String key, long stamp) {
		return stamp(key) != stamp;
	}
}
