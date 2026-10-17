/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * The relay answers a call only inside the frame an island opened, only for the operands the island asked with, and only on
 * the island's thread; frames nest and a frame closed twice, or out of order, leaves the thread at the frame it was opened in.
 */
class NativeDecisionRelayTest {
	private static final String KEY = "Lpond/Stone;skips(Lpond/Frog;)Z";

	@Test void anAnswerHoldsOnlyInsideItsFrameAndForItsOperands() {
		Object frog = new Object(), toad = new Object();
		assertFalse(NativeDecisionRelay.anyOpen());
		assertSame(NativeDecisionRelay.NONE, NativeDecisionRelay.answer(KEY, new Object[] {frog}), "no frame: no answer");
		Object frame = NativeDecisionRelay.enter();
		try {
			assertTrue(NativeDecisionRelay.anyOpen());
			assertSame(NativeDecisionRelay.NONE, NativeDecisionRelay.answer(KEY, new Object[] {frog}), "nothing offered yet");
			NativeDecisionRelay.offer(Boolean.TRUE, KEY, new Object[] {frog});
			assertEquals(Boolean.TRUE, NativeDecisionRelay.answer(KEY, new Object[] {frog}));
			assertSame(NativeDecisionRelay.NONE, NativeDecisionRelay.answer(KEY, new Object[] {toad}), "another operand is asked as always");
			assertSame(NativeDecisionRelay.NONE, NativeDecisionRelay.answer("Lpond/Stone;sinks(Lpond/Frog;)Z", new Object[] {frog}), "another call");
			NativeDecisionRelay.offer(Boolean.FALSE, KEY, new Object[] {frog});
			assertEquals(Boolean.FALSE, NativeDecisionRelay.answer(KEY, new Object[] {frog}), "a second offer replaces the first");
		} finally {
			NativeDecisionRelay.exit(frame);
		}
		assertFalse(NativeDecisionRelay.anyOpen());
		assertSame(NativeDecisionRelay.NONE, NativeDecisionRelay.answer(KEY, new Object[] {frog}), "closed: no answer");
	}

	@Test void boxedPrimitivesMatchByValueAndReferencesByIdentity() {
		Object frame = NativeDecisionRelay.enter();
		try {
			NativeDecisionRelay.offer(7, "Lpond/Depth;at(DI)I", new Object[] {Double.valueOf(1.5), Integer.valueOf(400)});
			assertEquals(7, NativeDecisionRelay.answer("Lpond/Depth;at(DI)I", new Object[] {Double.valueOf(1.5), Integer.valueOf(400)}));
			String a = new String("lily"), b = new String("lily");
			NativeDecisionRelay.offer(Boolean.TRUE, KEY, new Object[] {a});
			assertSame(NativeDecisionRelay.NONE, NativeDecisionRelay.answer(KEY, new Object[] {b}), "an equal but other object is another operand");
		} finally {
			NativeDecisionRelay.exit(frame);
		}
	}

	@Test void framesNestAndRestoreTheOuterOne() {
		Object frog = new Object();
		Object outer = NativeDecisionRelay.enter();
		try {
			NativeDecisionRelay.offer(Boolean.TRUE, KEY, new Object[] {frog});
			Object inner = NativeDecisionRelay.enter();
			try {
				assertSame(NativeDecisionRelay.NONE, NativeDecisionRelay.answer(KEY, new Object[] {frog}), "the inner frame holds its own answers");
				NativeDecisionRelay.offer(Boolean.FALSE, KEY, new Object[] {frog});
				assertEquals(Boolean.FALSE, NativeDecisionRelay.answer(KEY, new Object[] {frog}));
			} finally {
				NativeDecisionRelay.exit(inner);
			}
			assertEquals(Boolean.TRUE, NativeDecisionRelay.answer(KEY, new Object[] {frog}), "the outer frame's answer is back");
			NativeDecisionRelay.exit(inner);
			assertEquals(Boolean.TRUE, NativeDecisionRelay.answer(KEY, new Object[] {frog}), "closing a closed frame changes nothing");
		} finally {
			NativeDecisionRelay.exit(outer);
		}
		assertFalse(NativeDecisionRelay.anyOpen());
	}

	@Test void anOuterFrameClosedFirstClosesTheInnerOneLeftOpen() {
		Object outer = NativeDecisionRelay.enter();
		NativeDecisionRelay.enter();   // an aborted inner stretch that never closed its frame
		NativeDecisionRelay.exit(outer);
		assertFalse(NativeDecisionRelay.anyOpen());
		assertSame(NativeDecisionRelay.NONE, NativeDecisionRelay.answer(KEY, new Object[] {new Object()}));
	}

	@Test void anotherThreadIsNeverAnsweredFromThisThreadsFrame() throws Exception {
		Object frog = new Object();
		Object frame = NativeDecisionRelay.enter();
		try {
			NativeDecisionRelay.offer(Boolean.TRUE, KEY, new Object[] {frog});
			AtomicReference<Object> seen = new AtomicReference<>();
			Thread other = new Thread(() -> seen.set(NativeDecisionRelay.answer(KEY, new Object[] {frog})));
			other.start();
			other.join();
			assertSame(NativeDecisionRelay.NONE, seen.get());
		} finally {
			NativeDecisionRelay.exit(frame);
		}
	}
}
