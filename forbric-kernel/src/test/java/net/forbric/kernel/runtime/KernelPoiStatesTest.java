/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Lane K4: a Fabric mod's {@code PoiTypes.registerBlockStates} on NeoForge's base, where NeoForge's PoiType add-callback
 * has already put the same states under the same holder into the same map ({@code TYPE_BY_STATE} is NeoForge's
 * {@code BLOCKSTATE_TO_POI_TYPE_MAP}). Measured on the genuine stage: the callback maps waystones:wild_waystone's 320
 * states, then Balm's invoker of {@code registerBlockStates} throws "defined in more than one PoI type" on the first one.
 * The map stands in for {@code TYPE_BY_STATE}; the caller predicate stands in for the kernel loader's ecosystem record.
 * The helper is the game side's, loaded the way the game loads it (StagedGameClassLoader), and called reflectively.
 */
class KernelPoiStatesTest {
	private static URLClassLoader game;
	private static Method register, bind, reset, takenOverCount;

	@BeforeAll
	static void gameSide() throws Exception {
		java.net.URL boot = net.forbric.kernel.util.ForbricLog.class.getProtectionDomain().getCodeSource().getLocation();
		game = StagedGameClassLoader.create(List.of(boot));
		Class<?> helper = Class.forName("net.forbric.kernel.runtime.KernelPoiStates", true, game);
		register = helper.getMethod("registerBlockStates", Map.class, Object.class, Set.class);
		bind = helper.getDeclaredMethod("bindFabricCodeForTest", Predicate.class);
		reset = helper.getDeclaredMethod("resetForTest");
		takenOverCount = helper.getDeclaredMethod("takenOver");
		for (Method m : List.of(bind, reset, takenOverCount)) m.setAccessible(true);
	}

	@AfterAll
	static void close() throws Exception {
		if (game != null) game.close();
	}

	private static boolean registerBlockStates(Map<Object, Object> map, Object poi, Set<?> states) throws Throwable {
		try {
			return (boolean) register.invoke(null, map, poi, states);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	private static void bindFabricCode(Predicate<Class<?>> fabric) throws Exception {
		bind.invoke(null, fabric);
	}

	private static int takenOver() throws Exception {
		return (int) takenOverCount.invoke(null);
	}

	private final Object wild = new Object() { @Override public String toString() { return "Reference{waystones:wild_waystone}"; } };
	private final Object other = new Object() { @Override public String toString() { return "Reference{minecraft:home}"; } };
	private final List<String> states = List.of("Block{waystones:a}[half=lower]", "Block{waystones:a}[half=upper]");

	@BeforeEach
	void fabricCaller() throws Exception {
		reset.invoke(null);
		bindFabricCode(type -> type == KernelPoiStatesTest.class);
	}

	/** What NeoForge's PoiTypeCallbacks.onAdd leaves behind for the type before the mod's own call. */
	private Map<Object, Object> mappedByTheCallback(Object holder) {
		Map<Object, Object> map = new HashMap<>();
		for (String state : states) map.put(state, holder);
		return map;
	}

	@Test
	void aFabricModsOwnCallTakesOverTheStatesTheCallbackMappedToItsType() throws Throwable {
		Map<Object, Object> map = mappedByTheCallback(wild);
		assertTrue(registerBlockStates(map, wild, new LinkedHashSet<>(states)),
				"a Fabric caller's states are mapped by the kernel, not by vanilla's body (which throws on the first one)");
		assertEquals(2, map.size());
		for (String state : states) assertSame(wild, map.get(state), "the mapping is the type's, unchanged");
		assertEquals(2, takenOver());
	}

	@Test
	void mappingTheSameStatesTwiceStillThrowsAsOnFabric() throws Throwable {
		Map<Object, Object> map = mappedByTheCallback(wild);
		registerBlockStates(map, wild, new LinkedHashSet<>(states));
		IllegalStateException twice = assertThrows(IllegalStateException.class,
				() -> registerBlockStates(map, wild, new LinkedHashSet<>(states)));
		assertEquals(states.get(0) + " is defined in more than one PoI type", twice.getMessage(), "vanilla's message");
	}

	@Test
	void aStateAnotherTypeHoldsStillThrows() throws Throwable {
		Map<Object, Object> map = mappedByTheCallback(other);
		IllegalStateException clash = assertThrows(IllegalStateException.class,
				() -> registerBlockStates(map, wild, Set.of(states.get(0))));
		assertEquals(states.get(0) + " is defined in more than one PoI type", clash.getMessage());
		assertEquals(0, takenOver());
	}

	@Test
	void withoutTheCallbackAFabricCallMapsExactlyAsVanillaDoes() throws Throwable {
		Map<Object, Object> map = new HashMap<>();
		assertTrue(registerBlockStates(map, wild, new LinkedHashSet<>(states)));
		for (String state : states) assertSame(wild, map.get(state));
		assertEquals(0, takenOver(), "nothing was there to take over");
		assertThrows(IllegalStateException.class, () -> registerBlockStates(map, wild, Set.of(states.get(1))),
				"and the same states a second time are vanilla's conflict");
	}

	@Test
	void codeThatIsNotAFabricModsGetsVanillasBodyUntouched() throws Throwable {
		bindFabricCode(type -> false);
		Map<Object, Object> map = mappedByTheCallback(wild);
		assertFalse(registerBlockStates(map, wild, new LinkedHashSet<>(states)),
				"false hands the call back to vanilla's body — NeoForge's own behaviour for NeoForge and vanilla code");
		assertEquals(mappedByTheCallback(wild), map, "and the kernel wrote nothing");
	}
}
