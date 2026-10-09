/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The guest callbacks open on one thread around one native operation, nested the way the wrappers that opened them are.
 *
 * <p>Where a guest wrapped a vanilla call that the native platform moved into its own method, the kernel binds the wrap
 * around the native method's call instead and opens a frame for its handler; the native method asks the frames where the
 * vanilla call used to be. Two mods that wrapped the same vanilla call get two such wrappers, nested as MixinExtras nests
 * any two wraps of one call, so their frames nest the same way: the first frame opened belongs to the outermost handler.
 * {@link #outermostFirst} is the order vanilla calls those handlers in — each handed the next one inward as its original,
 * the innermost handed the call itself — so every wrap runs and an outer one decides whether, and with what, the inner
 * one runs. One slot that each opening overwrote ran the innermost wrap alone and dropped every other mod's.
 *
 * <p>While the frames run, the thread has none open ({@link #suspend}): a query the handlers themselves cause is another
 * call site, which vanilla's wrap of this one never reached.
 */
public final class CallbackFrames<T> {
	private record Frame<T>(T callbacks, Frame<T> outer) {
	}

	private final ThreadLocal<Frame<T>> innermost = new ThreadLocal<>();

	/** Opens a frame inside the current ones; returns what {@link #leave} restores. */
	public Object enter(T callbacks) {
		Frame<T> previous = innermost.get();
		innermost.set(new Frame<>(callbacks, previous));
		return previous;
	}

	/** Restores the frames {@link #enter} or {@link #suspend} returned. */
	@SuppressWarnings("unchecked")
	public void leave(Object previous) {
		if (previous == null) innermost.remove();
		else innermost.set((Frame<T>) previous);
	}

	/** Closes every frame for the duration of one composed call; returns what {@link #leave} restores. */
	public Object suspend() {
		Frame<T> open = innermost.get();
		innermost.remove();
		return open;
	}

	/** The callbacks of the innermost frame, or null when none is open. */
	public T innermost() {
		Frame<T> frame = innermost.get();
		return frame == null ? null : frame.callbacks();
	}

	/** The open frames' callbacks, outermost first; empty when none is open. */
	public List<T> outermostFirst() {
		List<T> callbacks = new ArrayList<>();
		for (Frame<T> frame = innermost.get(); frame != null; frame = frame.outer()) callbacks.add(frame.callbacks());
		Collections.reverse(callbacks);
		return callbacks;
	}

	/**
	 * The original a frame's handler calls: given the value the handler passes for the wrapped call's receiver, the next
	 * frame inward with that value, or the native call on it. As a {@link Supplier} it runs on the value the frame was
	 * itself called with.
	 */
	public static final class Next implements Function<Object, Object>, Supplier<Object> {
		private final Object subject;
		private final Function<Object, Object> inward;

		public Next(Object subject, Function<Object, Object> inward) {
			this.subject = subject;
			this.inward = inward;
		}

		@Override
		public Object apply(Object passed) {
			return inward.apply(passed);
		}

		@Override
		public Object get() {
			return inward.apply(subject);
		}
	}

	/**
	 * An original whose value is computed only when a handler asks for it: the inner frames' answer, handed to an outer
	 * frame whose callback was written to receive the value itself. {@code KernelWrapOperations.constant} resolves it when
	 * the handler calls its original, so an inner wrap runs exactly when the outer one lets the call through.
	 */
	public static final class Deferred implements Supplier<Object> {
		private final Supplier<Object> value;

		public Deferred(Supplier<Object> value) {
			this.value = value;
		}

		@Override
		public Object get() {
			return value.get();
		}

		/** {@code value}, or what it defers to. */
		public static Object resolve(Object value) {
			return value instanceof Deferred deferred ? resolve(deferred.get()) : value;
		}
	}
}
