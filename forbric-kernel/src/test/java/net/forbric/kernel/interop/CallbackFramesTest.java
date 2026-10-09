/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.interop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * Two unrelated mods' wraps of one vanilla call, moved by the kernel around the native method that makes the call now,
 * open two nested frames. The native query must run them as vanilla runs two nested wraps: the outer handler first, its
 * original the inner handler, the inner one's original the native call — every wrap runs, the inner one sees what the
 * outer passed on, and an outer handler that does not call its original keeps the inner one from running. One frame
 * behaves as one wrap. While the frames run, a query their handlers cause sees none of them.
 */
class CallbackFramesTest {
	/** A handler: marks the state it is given, then calls its original with {@code passOn.apply(state)}, or not at all. */
	private static Function<Object[], Object> handler(String mark, List<String> trace, Function<Object, Object> passOn, int original) {
		return args -> {
			trace.add(mark + ":" + args[0]);
			if (passOn == null) return mark;
			Function<Object, Object> next = (Function<Object, Object>) args[original];
			return next.apply(passOn.apply(args[0])) + "+" + mark;
		};
	}

	@Test void soundFramesComposeOutermostFirstAndTheInnerHearsWhatTheOuterPassedOn() {
		List<String> trace = new ArrayList<>();
		Object first = BlockSoundCallbackScope.enter(handler("chime", trace, state -> state, 2));
		try {
			Object second = BlockSoundCallbackScope.enter(handler("echo", trace, state -> "wool", 2));
			try {
				assertEquals("native(wool)+echo+chime", BlockSoundCallbackScope.compose("felt", "pos", state -> "native(" + state + ")"));
				assertEquals(List.of("chime:felt", "echo:felt"), trace);
			} finally {
				BlockSoundCallbackScope.leave(second);
			}
			trace.clear();
			assertEquals("native(felt)+chime", BlockSoundCallbackScope.compose("felt", "pos", state -> "native(" + state + ")"));
			assertEquals(List.of("chime:felt"), trace, "leaving the inner frame restores the outer one alone");
		} finally {
			BlockSoundCallbackScope.leave(first);
		}
		assertEquals("native(felt)", BlockSoundCallbackScope.compose("felt", "pos", state -> "native(" + state + ")"));
	}

	@Test void anOuterHandlerThatSkipsItsOriginalKeepsTheInnerOneAndTheNativeQueryFromRunning() {
		List<String> trace = new ArrayList<>();
		Object first = BlockSoundCallbackScope.enter(handler("override", trace, null, 2));
		Object second = BlockSoundCallbackScope.enter(handler("inner", trace, state -> state, 2));
		try {
			assertEquals("override", BlockSoundCallbackScope.compose("stone", "pos", state -> { throw new AssertionError("native ran"); }));
			assertEquals(List.of("override:stone"), trace);
		} finally {
			BlockSoundCallbackScope.leave(second);
			BlockSoundCallbackScope.leave(first);
		}
	}

	@Test void aQueryTheHandlersCauseSeesNoFramesAndTheFramesSurviveAThrowingHandler() {
		List<String> nested = new ArrayList<>();
		Object first = BlockSoundCallbackScope.enter(args -> {
			// The handler plays another block's sound itself: that query is another call site, not this wrap's.
			nested.add((String) BlockSoundCallbackScope.compose("other", "pos", state -> "plain(" + state + ")"));
			return ((Function<Object, Object>) args[2]).apply(args[0]);
		});
		try {
			assertEquals("plain(stone)", BlockSoundCallbackScope.compose("stone", "pos", state -> "plain(" + state + ")"));
			assertEquals(List.of("plain(other)"), nested);
			Object failing = BlockSoundCallbackScope.enter(args -> { throw new IllegalStateException("handler failed"); });
			try {
				assertThrows(IllegalStateException.class, () -> BlockSoundCallbackScope.compose("stone", "pos", state -> state));
			} finally {
				BlockSoundCallbackScope.leave(failing);
			}
			nested.clear();
			assertEquals("plain(stone)", BlockSoundCallbackScope.compose("stone", "pos", state -> "plain(" + state + ")"));
			assertEquals(List.of("plain(other)"), nested, "the frames are open again after a throwing composition");
		} finally {
			BlockSoundCallbackScope.leave(first);
		}
	}

	@Test void hudFramesComposeAndTheNativeSelectionRunsOnTheHudPassedOn() {
		List<String> trace = new ArrayList<>();
		Object first = HudContextCallbackScope.enter(handler("train", trace, hud -> hud, 1));
		Object second = HudContextCallbackScope.enter(handler("minimap", trace, hud -> "other-" + hud, 1));
		try {
			assertEquals("select(other-hud)+minimap+train", HudContextCallbackScope.compose("hud", hud -> "select(" + hud + ")"));
			assertEquals(List.of("train:hud", "minimap:hud"), trace);
			// The supplier form answers one selection whatever HUD is passed on.
			assertEquals("EXPERIENCE+minimap+train", HudContextCallbackScope.query("hud", () -> "EXPERIENCE"));
		} finally {
			HudContextCallbackScope.leave(second);
			HudContextCallbackScope.leave(first);
		}
	}

	@Test void everyBreathingFrameRunsItsLavaCallbackAndWaterComposesLazily() {
		List<String> trace = new ArrayList<>();
		Object entity = "diver", level = "level";
		// Each callback is shaped as the adapter writes one: the native answer arrives as args[1], its original.
		Function<Object[], Object> lavaA = args -> { trace.add("lava-a"); return null; }, lavaB = args -> { trace.add("lava-b"); return null; };
		Function<Object[], Object> waterOuter = args -> {
			trace.add("water-outer");
			return !(Boolean) CallbackFrames.Deferred.resolve(args[1]);   // the handler calls its original, then negates it
		};
		Function<Object[], Object> waterInner = args -> {
			trace.add("water-inner:" + args[1]);
			assertInstanceOf(Boolean.class, args[1], "the innermost frame is handed the native answer itself");
			return Boolean.TRUE.equals(args[1]) || entity.equals(args[0]);
		};
		Object first = BreathingCallbackScope.enter(lavaA, waterOuter);
		Object second = BreathingCallbackScope.enter(lavaB, waterInner);
		try {
			BreathingCallbackScope.lava(entity, level);
			assertEquals(List.of("lava-a", "lava-b"), trace, "every mod's lava callback runs, outermost first");
			trace.clear();
			assertFalse(BreathingCallbackScope.water(entity, false, level));
			assertEquals(List.of("water-outer", "water-inner:false"), trace);
		} finally {
			BreathingCallbackScope.leave(second);
			BreathingCallbackScope.leave(first);
		}
		trace.clear();
		Object skipping = BreathingCallbackScope.enter(args -> null, args -> { trace.add("skips"); return Boolean.TRUE; });
		Object inner = BreathingCallbackScope.enter(args -> null, args -> { trace.add("inner ran"); return Boolean.FALSE; });
		try {
			assertTrue(BreathingCallbackScope.water(entity, false, level));
			assertEquals(List.of("skips"), trace, "an outer handler that never asks keeps the inner one from running");
		} finally {
			BreathingCallbackScope.leave(inner);
			BreathingCallbackScope.leave(skipping);
		}
		assertTrue(BreathingCallbackScope.water(entity, true, level), "no frame: the native answer");
	}
}
