/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * MinecraftForge's entity-fluid queries, answered from the record {@code KernelForgeEntityFluids} keeps, over the compiled
 * game side and the staged game: the answers MinecraftForge's own {@code EntityFluidInteraction} gives from what its
 * update records, including its loop's quirks. The record is filled cell by cell in game (the fluid probe); here it is
 * set directly, since an entity cannot walk a world off-game.
 */
@ResourceLock("system-properties")
class KernelForgeEntityFluidsTest {
	private URLClassLoader loader;
	private Class<?> runtime, forgeType;

	@AfterEach void close() throws Exception {
		System.clearProperty("forbric.forgeEntityFluids");
		if (loader != null) loader.close();
	}

	private void load() throws Exception {
		loader = StagedGameClassLoader.create(List.of());
		runtime = loader.loadClass("net.forbric.kernel.runtime.KernelForgeEntityFluids");
		forgeType = loader.loadClass("net.minecraftforge.fluids.FluidType");
	}

	private Object type(String name) throws Exception {
		Class<?> properties = loader.loadClass("net.minecraftforge.fluids.FluidType$Properties");
		Object built = properties.getMethod("create").invoke(null);
		built = properties.getMethod("descriptionId", String.class).invoke(built, "block.example." + name);
		return forgeType.getConstructor(properties).newInstance(built);
	}

	private Object state() throws Exception {
		return runtime.getMethod("state", Set.class).invoke(null, Set.of());
	}

	/** Records what MinecraftForge's loop would have recorded for {@code type}: a tracker at that height, eyes in or not. */
	@SuppressWarnings("unchecked")
	private void record(Object state, Object type, double height, boolean eyes) throws Exception {
		Class<?> trackerClass = loader.loadClass("net.forbric.kernel.runtime.KernelForgeEntityFluids$Tracker");
		Constructor<?> make = trackerClass.getDeclaredConstructor();
		make.setAccessible(true);
		Object tracker = make.newInstance();
		set(tracker, "height", height);
		set(tracker, "eyes", eyes);
		Field byType = state.getClass().getDeclaredField("byType");
		byType.setAccessible(true);
		((Map<Object, Object>) byType.get(state)).put(type, tracker);
		set(state, "touched", true);
		if (height > 0) set(state, "inFluid", true);
		if (eyes) set(state, "eye", type);
	}

	private static void set(Object owner, String name, Object value) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(owner, value);
	}

	private Object call(String name, Object... args) throws Exception {
		for (Method m : runtime.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == args.length) return m.invoke(null, args);
		throw new AssertionError(name);
	}

	@Test void minecraftForgesQueriesReadTheRecord() throws Exception {
		load();
		Object state = state(), water = type("water"), blood = type("blood");
		assertEquals(false, call("forgeInFluid", state), "nothing recorded: in no fluid");
		record(state, water, 0.4, false);
		record(state, blood, 1.2, true);
		assertEquals(true, call("forgeInFluid", state));
		assertEquals(0.4, call("forgeHeight", state, water));
		assertEquals(1.2, call("forgeHeight", state, blood));
		assertEquals(false, call("forgeEyes", state, water));
		assertEquals(true, call("forgeEyes", state, blood));
		assertSame(blood, call("forgeEye", state), "the type the eyes are in");
		assertEquals(0.0, call("forgeHeight", state, type("lava")), "a type never met: height 0, as MinecraftForge's map.get(type) == null");
	}

	/**
	 * {@code isInFluid(BiPredicate, boolean)} is MinecraftForge's loop exactly, including that with {@code all} a type met
	 * earlier and left (height 0, its tracker kept) answers false: MinecraftForge keeps every tracker it ever made.
	 */
	@Test void matchingIsMinecraftForgesLoop() throws Exception {
		load();
		Object state = state(), water = type("water"), blood = type("blood");
		record(state, water, 0.5, false);
		record(state, blood, 0.9, true);
		BiPredicate<Object, Double> deep = (type, height) -> height > 0.6;
		assertEquals(true, call("forgeMatching", state, deep, false), "one type matches");
		assertEquals(false, call("forgeMatching", state, deep, true), "not every type does");
		BiPredicate<Object, Double> any = (type, height) -> true;
		assertEquals(true, call("forgeMatching", state, any, true));
		record(state, water, 0.0, false);   // left the water: its tracker stays, at height 0
		assertEquals(false, call("forgeMatching", state, any, true), "MinecraftForge's all-match fails on a kept, empty tracker");
		assertEquals(true, call("forgeMatching", state, any, false));
	}

	/** NeoForge's own answers pass through untouched while no MinecraftForge mod's fluid in water or lava was recorded. */
	@Test void neoForgesAnswersPassThroughWhenNoTaggedFluidWasRecorded() throws Exception {
		load();
		Object state = state();
		assertEquals(0.7, call("neoHeight", state, null, 0.7));
		assertEquals(false, call("neoIn", state, null, false));
		assertEquals(true, call("neoEyes", state, null, true));
		assertEquals(0.3, call("neoHeight", null, null, 0.3), "an interaction made before the edit has no record: NeoForge decides");
	}

	/** Switched off, no record is made, and every MinecraftForge query answers "no fluid" as merged. */
	@Test void switchedOffThereIsNoRecord() throws Exception {
		load();
		System.setProperty("forbric.forgeEntityFluids", "off");
		assertNull(state());
		assertEquals(false, call("forgeInFluid", (Object) null));
		assertEquals(false, call("forgeMatching", null, (BiPredicate<Object, Double>) (t, h) -> true, false));
	}
}
