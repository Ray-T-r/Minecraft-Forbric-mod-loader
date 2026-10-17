/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * {@code KernelDispatch} answers which implementation a receiver selects, as the JVM selects it, and sees through a declaration
 * that only hands its arguments, unchanged, to the very default the declarer's implementation is or forwards to — the shape a
 * merge writes where a class inherits two carriers' defaults of one signature. Names here are unrelated to any game or mod.
 */
class KernelDispatchTest {
	interface Lamp {
		default String glow(int watts, String tint) {
			return "lamp " + watts + tint;
		}
	}

	interface Torch {
		default String glow(int watts, String tint) {
			return "torch " + watts + tint;
		}
	}

	/** Inherits Lamp's default. */
	static class Plain implements Lamp { }

	/** Declares glow only to forward, unchanged, to Lamp's default. */
	static class Relay implements Lamp, Torch {
		@Override
		public String glow(int watts, String tint) {
			return Lamp.super.glow(watts, tint);
		}
	}

	/** A subclass of the relay that declares nothing: still Lamp's default. */
	static class RelayChild extends Relay { }

	/** Forwards to the other carrier's default: a different implementation. */
	static class OtherRelay implements Lamp, Torch {
		@Override
		public String glow(int watts, String tint) {
			return Torch.super.glow(watts, tint);
		}
	}

	/** Forwards, but swaps nothing in: hands a changed argument on, so it is an override. */
	static class Dimmer implements Lamp {
		@Override
		public String glow(int watts, String tint) {
			return Lamp.super.glow(watts / 2, tint);
		}
	}

	/** Its own body. */
	static class Custom implements Lamp {
		@Override
		public String glow(int watts, String tint) {
			return "custom";
		}
	}

	/** A class declarer whose own method forwards to Lamp's default, and a subclass that declares nothing. */
	static class Fixture implements Lamp, Torch {
		@Override
		public String glow(int watts, String tint) {
			return Lamp.super.glow(watts, tint);
		}
	}

	static class FixtureChild extends Fixture { }

	private static final String DESC = "(ILjava/lang/String;)Ljava/lang/String;";

	/** The game-side class, off its compiled output: the test source set does not link against the runtime one. */
	private static Class<?> dispatch;

	private static Class<?> dispatch() throws Exception {
		if (dispatch != null) return dispatch;
		java.nio.file.Path classes = java.nio.file.Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.GAME_SIDE,
				java.nio.file.Files.isRegularFile(classes.resolve("net/forbric/kernel/runtime/KernelDispatch.class")), "game-side classes not compiled yet");
		dispatch = new java.net.URLClassLoader(new java.net.URL[] {classes.toUri().toURL()}, KernelDispatchTest.class.getClassLoader())
				.loadClass("net.forbric.kernel.runtime.KernelDispatch");
		return dispatch;
	}

	private static Object call(String name, Class<?>[] types, Object... args) {
		try {
			java.lang.reflect.Method method = dispatch().getDeclaredMethod(name, types);
			method.setAccessible(true);
			return method.invoke(null, args);
		} catch (org.opentest4j.TestAbortedException skipped) {
			throw skipped;
		} catch (Exception failure) {
			throw new AssertionError(failure);
		}
	}

	private static boolean selects(Class<?> type, Class<?> declarer) {
		return (Boolean) call("selects", new Class<?>[] {Class.class, String.class, String.class, String.class}, type, declarer.getName(), "glow", DESC);
	}

	private static String forwarded(Class<?> type) {
		return (String) call("forwardedDefault", new Class<?>[] {Class.class, String.class, String.class}, type, "glow", DESC);
	}

	@Test void anInheritedDefaultIsSelected() {
		assertTrue(selects(Plain.class, Lamp.class));
	}

	@Test void aDeclarationThatOnlyForwardsToTheSameDefaultIsNoOverride() {
		assertEquals(Lamp.class.getName(), forwarded(Relay.class));
		assertTrue(selects(Relay.class, Lamp.class));
		assertTrue(selects(RelayChild.class, Lamp.class));
	}

	@Test void aForwarderToAnotherDefaultOrWithChangedArgumentsIsAnOverride() {
		assertFalse(selects(OtherRelay.class, Lamp.class), "forwards to the other carrier's default");
		assertNull(forwarded(Dimmer.class), "hands a changed argument on");
		assertFalse(selects(Dimmer.class, Lamp.class));
		assertFalse(selects(Custom.class, Lamp.class));
	}

	@Test void aClassDeclarerThatForwardsIsSelectedForItsSubclasses() {
		assertEquals(Lamp.class.getName(), forwarded(Fixture.class));
		assertTrue(selects(Fixture.class, Fixture.class));
		assertTrue(selects(FixtureChild.class, Fixture.class));
		assertFalse(selects(Relay.class, Torch.class), "a forwarder to Lamp's default does not run Torch's");
	}
}
