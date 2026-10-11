/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The injector runs for every class the game loads, so a class that does not call into both families is handed back
 * before it is parsed, and the hook classes a proof reads are parsed once per run. Names here are invented: the
 * prefilter keys on the families' namespaces and on calls, never on who makes them.
 */
class NativeDualEventInjectorPrefilterTest {
	private static final NativeDualEventInjector.Families FAMILIES = NativeDualEventInjectorExecutionTest.FAMILIES;

	private static Map<String, byte[]> classes(Path work) throws Exception {
		return NativeDualEventInjectorExecutionTest.classes(work, Map.of(
				// Calls one family only; names the other only as a type; quotes both as text.
				"later.call.OneFamily", "package later.call; public final class OneFamily { public static void run(Object who){ late.forge.Factory.touch(who); } }",
				"later.call.TypeOnly", "package later.call; public final class TypeOnly { public static Object run(Object who){ late.forge.Factory.touch(who); return (late.neo.NeoTouch) null; } }",
				"later.call.Quoted", """
						package later.call;
						public final class Quoted { public static String[] words(){ return new String[]{"late/forge/Factory","late/neo/Hooks","touch"}; } }
						""",
				// Two callers proved alike.
				"later.call.Relay", "package later.call; public final class Relay { public static void run(Object who){ late.forge.Factory.touch(who); late.neo.Hooks.touch(who); } }",
				"later.call.Relay2", "package later.call; public final class Relay2 { public static void run(Object who){ late.forge.Factory.touch(who); late.neo.Hooks.touch(who); } }"));
	}

	/** Resource lookups the injector made, by path. */
	private static Function<String, byte[]> counting(Map<String, byte[]> classes, Map<String, Integer> reads) {
		return path -> {
			reads.merge(path, 1, Integer::sum);
			return classes.get(path.substring(0, path.length() - ".class".length()));
		};
	}

	private static NativeDualEventInjector injector(Map<String, byte[]> classes, Map<String, Integer> reads) {
		return new NativeDualEventInjector(counting(classes, reads), Set.of(NativeDualEventInjectorExecutionTest.FORGE_EVENT), FAMILIES);
	}

	private static byte[] run(NativeDualEventInjector injector, Map<String, byte[]> classes, String internal) {
		return injector.transform(internal.replace('/', '.'), classes.get(internal), null);
	}

	@Test
	void aClassThatCannotCallBothFamiliesIsReturnedUnparsed(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = classes(work);
		Map<String, Integer> reads = new HashMap<>();
		NativeDualEventInjector injector = injector(classes, reads);
		for (String internal : List.of("later/call/OneFamily", "later/call/TypeOnly", "late/bridge/Bridge", "late/neo/NeoTouch")) {
			assertSame(classes.get(internal), run(injector, classes, internal), internal + " is returned untouched");
		}
		assertEquals(0, injector.classesParsed(), "none of them was parsed as a caller");
		assertEquals(Map.of(), reads, "and no hook class was read for them");
	}

	@Test
	void textThatNamesBothFamiliesIsNotACall(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = classes(work);
		Map<String, Integer> reads = new HashMap<>();
		NativeDualEventInjector injector = injector(classes, reads);
		assertSame(classes.get("later/call/Quoted"), run(injector, classes, "later/call/Quoted"));
		assertEquals(0, injector.classesParsed(), "the call scan reads instructions, not the constant pool's strings");
		assertEquals(Map.of(), reads);
	}

	@Test
	void anEmptyBridgedSetTouchesNothing(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = classes(work);
		NativeDualEventInjector none = new NativeDualEventInjector(counting(classes, new HashMap<>()), Set.of(), FAMILIES);
		for (String internal : List.of("later/call/Relay", "late/neo/Hooks", "late/forge/Factory")) {
			assertSame(classes.get(internal), run(none, classes, internal), "no bridge, nothing to stand down: " + internal);
		}
	}

	@Test
	void eachHookClassIsParsedOncePerRunAndCachedProofsMatchFreshOnes(@TempDir Path work) throws Exception {
		Map<String, byte[]> classes = classes(work);
		Map<String, Integer> reads = new HashMap<>();
		NativeDualEventInjector injector = injector(classes, reads);
		byte[] relay = run(injector, classes, "later/call/Relay");
		assertNotSame(classes.get("later/call/Relay"), relay, "the proved caller is wrapped");
		assertNotNull(run(injector, classes, "later/call/Relay2"));
		assertNotNull(run(injector, classes, "later/call/Relay"));
		assertEquals(1, reads.get("late/forge/Factory.class"), "MinecraftForge hook: " + reads);
		assertEquals(1, reads.get("late/neo/Hooks.class"), "NeoForge hook: " + reads);
		assertArrayEquals(relay, run(injector(classes, new HashMap<>()), classes, "later/call/Relay"),
				"a run's cached hook reads give exactly what a fresh instance gives");
	}
}
