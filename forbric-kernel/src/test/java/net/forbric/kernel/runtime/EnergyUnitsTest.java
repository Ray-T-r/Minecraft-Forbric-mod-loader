package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The energy bridge's unit rules (1 FE = 1 E; int/long width only), off-game. EnergyUnits is game-side, compiled
 * against nothing, so the compiled class is loaded directly; the transfer engine suite covers the same rules through
 * the real APIs.
 */
class EnergyUnitsTest {
	private static final Path RUNTIME = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
	private static Class<?> units;

	@BeforeAll static void load() throws Exception {
		boolean gameSide = Files.isRegularFile(RUNTIME.resolve("net/forbric/kernel/runtime/transfer/EnergyUnits.class"));
		if ("1".equals(System.getenv("FORBRIC_COMPAT_FIXTURES_REQUIRED"))) assertTrue(gameSide, "game-side classes were not compiled");
		org.junit.jupiter.api.Assumptions.assumeTrue(gameSide, "no staged game jars, so no game side was built");
		units = Class.forName("net.forbric.kernel.runtime.transfer.EnergyUnits", true,
				new URLClassLoader(new URL[] {RUNTIME.toUri().toURL()}, null));
	}
	private static Object call(String name, Class<?> type, Object argument) throws Throwable {
		Method method = units.getMethod(name, type);
		try { return method.invoke(null, argument); } catch (InvocationTargetException failure) { throw failure.getCause(); }
	}
	private static Object call(String name, long first, long second) throws Throwable {
		try { return units.getMethod(name, long.class, long.class).invoke(null, first, second); }
		catch (InvocationTargetException failure) { throw failure.getCause(); }
	}

	/** A long request is asked of an int API as the largest int it can express; the caller keeps the rest. */
	@Test void aLongRequestIsClampedBeforeAnythingMoves() throws Throwable {
		assertEquals(0, call("request", long.class, 0L));
		assertEquals(1234, call("request", long.class, 1234L));
		assertEquals(Integer.MAX_VALUE, call("request", long.class, (long) Integer.MAX_VALUE));
		assertEquals(Integer.MAX_VALUE, call("request", long.class, Integer.MAX_VALUE + 1L));
		assertEquals(Integer.MAX_VALUE, call("request", long.class, 5_000_000_000L));
		assertEquals(Integer.MAX_VALUE, call("request", long.class, Long.MAX_VALUE));
		assertThrows(IllegalArgumentException.class, () -> call("request", long.class, -1L));
	}
	/** A provider's answer outside [0, request] is a broken provider, never an amount to pass on. */
	@Test void aProviderAnswerMustLieWithinItsRequest() throws Throwable {
		assertEquals(0L, call("moved", 0, 10));
		assertEquals(10L, call("moved", 10, 10));
		assertEquals((long) Integer.MAX_VALUE, call("moved", Integer.MAX_VALUE, 5_000_000_000L));
		assertThrows(IllegalStateException.class, () -> call("moved", 11, 10));
		assertThrows(IllegalStateException.class, () -> call("moved", -1, 10));
	}
	/** A long amount READ through an int API saturates; reads never move energy. */
	@Test void readsSaturateAtTheIntLimit() throws Throwable {
		assertEquals(0, call("saturated", long.class, 0L));
		assertEquals(77, call("saturated", long.class, 77L));
		assertEquals(Integer.MAX_VALUE, call("saturated", long.class, 5_000_000_000L));
		assertEquals(0, call("saturated", long.class, -5L));
		assertEquals(5_000_000_000L, call("reported", long.class, 5_000_000_000L));
		assertThrows(IllegalStateException.class, () -> call("reported", long.class, -1L));
	}
}
