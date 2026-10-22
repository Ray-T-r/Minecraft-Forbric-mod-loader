/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

/**
 * Carries one value a guest changed, in a method whose native consumer of it the carrier replaced, to the carrier's own
 * consumer of that value (written into both by {@code NativeDeadValueConsumers}, never called by hand).
 *
 * <p>Per thread: {@link #base} records the value as the method first computed it — the expression the carrier computes on
 * its own for its replacement — {@link #offer} the guest-changed value just before the carrier's call, and {@link #close}
 * ends the offer right after it. Inside, {@link #substitute} hands the carrier's consumer the guest's value in place of the
 * one item equal to the base, once per run of the method (the native consumer read the value once, so the carrier's
 * consumer gets it once, whichever of its calls comes to it first); every other item, and everything outside an offer,
 * passes unchanged.
 */
public final class NativeValueRelay {
	private static final ThreadLocal<Object[]> STATE = ThreadLocal.withInitial(() -> new Object[4]);
	private static final Object OPEN = new Object();

	private NativeValueRelay() {
	}

	/** The value as first computed, before anything changed it. */
	public static void base(Object value) {
		Object[] state = STATE.get();
		state[0] = value;
		state[1] = null;
		state[2] = null;
		state[3] = null;
	}

	/** The final value, offered to the carrier's consumer until {@link #close} — unless this run already handed it over. */
	public static void offer(Object value) {
		Object[] state = STATE.get();
		if (state[3] != null) return;
		state[1] = value;
		state[2] = OPEN;
	}

	public static void close() {
		Object[] state = STATE.get();
		state[1] = null;
		state[2] = null;
	}

	/** {@code item}, or the offered value when {@code item} is the base and the offer is still open (then spent for this run). */
	public static Object substitute(Object item) {
		Object[] state = STATE.get();
		if (state[2] != OPEN || state[3] != null || item == null || !item.equals(state[0])) return item;
		state[2] = null;
		state[3] = OPEN;
		return state[1];
	}
}
