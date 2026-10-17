/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Carries the answers a native decision island computed to the carrier code that decides the same thing right after it.
 *
 * <p>Where the surviving carrier replaced a stretch of a vanilla method with a call into a hook of its own, the kernel puts
 * the native stretch's decisions back in front of that call ({@code NativeDecisionIsland}): the native calls are made
 * again, under the native conditions, so a mod's injector on one of them binds and runs where its own loader runs it. A
 * native call the carrier's hook makes too — a static method both ask — must then answer the hook what it answered the
 * island, the mod's wrap included, or the mod's answer would be lost: the island opens a frame ({@link #enter}), offers each
 * such answer with the operands it was asked for ({@link #offer}), and closes the frame when the carrier's stretch is over
 * ({@link #exit}); the asked method answers from the open frame when it is asked again with the same operands
 * ({@link #answer}), and runs as always otherwise — on any other operands, on any other thread, outside the stretch.
 *
 * <p>Frames nest: a stretch that runs another stretch (an event listener ticking another entity) opens an inner frame and
 * restores the outer one when it closes. Names no game type: operands are compared by identity (references) or by value
 * (boxed primitives).
 */
public final class NativeDecisionRelay {
	/** What {@link #answer} returns when the open frame holds no answer for the call. */
	public static final Object NONE = new Object() {
		@Override public String toString() {
			return "NativeDecisionRelay.NONE";
		}
	};

	private static final AtomicInteger OPEN = new AtomicInteger();
	private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

	private static final class Frame {
		final Frame outer;
		String[] keys = new String[2];
		Object[][] operands = new Object[2][];
		Object[] answers = new Object[2];
		int size;

		Frame(Frame outer) {
			this.outer = outer;
		}

		void put(String key, Object[] args, Object answer) {
			for (int i = 0; i < size; i++) {
				if (keys[i].equals(key) && same(operands[i], args)) {
					answers[i] = answer;
					return;
				}
			}
			if (size == keys.length) {
				keys = java.util.Arrays.copyOf(keys, size * 2);
				operands = java.util.Arrays.copyOf(operands, size * 2);
				answers = java.util.Arrays.copyOf(answers, size * 2);
			}
			keys[size] = key;
			operands[size] = args;
			answers[size] = answer;
			size++;
		}

		Object get(String key, Object[] args) {
			for (int i = 0; i < size; i++) if (keys[i].equals(key) && same(operands[i], args)) return answers[i];
			return NONE;
		}
	}

	private NativeDecisionRelay() {
	}

	/** Opens a frame inside the current one; returns what {@link #exit} restores. */
	public static Object enter() {
		Frame frame = new Frame(CURRENT.get());
		CURRENT.set(frame);
		OPEN.incrementAndGet();
		return frame;
	}

	/** Closes the frame {@link #enter} returned, restoring the one it was opened in. Idempotent for one frame. */
	public static void exit(Object token) {
		if (!(token instanceof Frame frame)) return;
		Frame current = CURRENT.get();
		// A frame is closed once: an exception path that closes it again, or a frame left open by an aborted inner stretch,
		// leaves the thread at the frame this one was opened in.
		boolean open = false;
		for (Frame f = current; f != null; f = f.outer) if (f == frame) { open = true; break; }
		if (!open) return;
		for (Frame f = current; f != frame.outer; f = f.outer) OPEN.decrementAndGet();
		if (frame.outer == null) CURRENT.remove();
		else CURRENT.set(frame.outer);
	}

	/** Records, in the innermost open frame, that the call {@code key} answered {@code answer} for {@code operands}. */
	public static void offer(Object answer, String key, Object[] operands) {
		Frame frame = CURRENT.get();
		if (frame != null) frame.put(key, operands, answer);
	}

	/** Whether any thread has a frame open: the asked method's fast path, read before it builds its operands. */
	public static boolean anyOpen() {
		return OPEN.get() != 0;
	}

	/** The answer the innermost open frame holds for the call {@code key} with {@code operands}, or {@link #NONE}. */
	public static Object answer(String key, Object[] operands) {
		Frame frame = CURRENT.get();
		return frame == null ? NONE : frame.get(key, operands);
	}

	private static boolean same(Object[] a, Object[] b) {
		if (a.length != b.length) return false;
		for (int i = 0; i < a.length; i++) {
			Object x = a[i], y = b[i];
			if (x == y) continue;
			// Boxed primitives by value (an int or a double handed again is a new box); references by identity.
			if (x instanceof Number || x instanceof Boolean || x instanceof Character) {
				if (!Objects.equals(x, y)) return false;
			} else return false;
		}
		return true;
	}
}
