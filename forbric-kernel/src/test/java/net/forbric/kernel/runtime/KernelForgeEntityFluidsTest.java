/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.InjectorExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * MinecraftForge's entity-fluid queries, answered from the record {@code KernelForgeEntityFluids} keeps, over the compiled
 * game side and the staged game: the answers MinecraftForge's own {@code EntityFluidInteraction} gives from what its
 * update records, including its loop's quirks, and what that loop records cell by cell. Off-game no entity, world or
 * fluid state can exist (their classes need the bootstrapped registries), so the record's own steps are driven with
 * what the merged {@code update} hands them — the entity's heights and column, each cell's position, fluid and height —
 * over mod fluids compiled as a MinecraftForge mod's are; what the entity and the cell are read from, and which NeoForge
 * type is its water or lava type, run in game (the fluid probe).
 */
@ResourceLock("system-properties")
class KernelForgeEntityFluidsTest {
	private static final Path FORGE_PATCHED = TestFixtures.stagedRoot().resolve("forge-patched/patched-mc-forge-26.2.jar");

	private static final Map<String, String> MOD = Map.of(
			"example.forge.OwnFluid", """
					package example.forge;

					import net.minecraft.world.level.material.EmptyFluid;

					/** A MinecraftForge mod's fluid: its class gives its own type (off-game its tags are answered here). */
					public class OwnFluid extends EmptyFluid {
						public net.minecraftforge.fluids.FluidType type;
						public java.util.Set<Object> tags;

						@Override
						public net.minecraftforge.fluids.FluidType getFluidType() {
							return type;
						}

						@Override
						public boolean is(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag) {
							return tags != null && tags.contains(tag);
						}
					}
					""",
			"example.forge.ForeignFluid", """
					package example.forge;

					import net.minecraft.world.level.material.EmptyFluid;

					/** A fluid whose class gives no MinecraftForge type of its own: a Fabric or NeoForge mod's, or vanilla's. */
					public class ForeignFluid extends EmptyFluid {
						public java.util.Set<Object> tags;

						@Override
						public boolean is(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag) {
							return tags != null && tags.contains(tag);
						}
					}
					""");

	@TempDir Path temp;
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

	/** {@link #load} with the mod fluids above, compiled against MinecraftForge's patched game as a mod's are. */
	private void loadWithFluids() throws Exception {
		Path forgeRuntime = TestFixtures.stagedRoot().resolve(TestFixtures.FORGE_INTEROP_JAR);
		TestFixtures.requireFiles(Fixture.STAGED,
				"MinecraftForge's patched game or its runtime is absent", FORGE_PATCHED, forgeRuntime);
		Map<String, byte[]> classes = InjectorExecution.compile(temp.resolve("work"), MOD, List.of(FORGE_PATCHED, forgeRuntime));
		Path out = temp.resolve("mod");
		for (var entry : classes.entrySet()) {
			Path file = out.resolve(entry.getKey() + ".class");
			Files.createDirectories(file.getParent());
			Files.write(file, entry.getValue());
		}
		loader = StagedGameClassLoader.create(List.of(out.toUri().toURL()));
		runtime = loader.loadClass("net.forbric.kernel.runtime.KernelForgeEntityFluids");
		forgeType = loader.loadClass("net.minecraftforge.fluids.FluidType");
	}

	private Object tag(String name) throws Exception {
		return loader.loadClass("net.minecraft.tags.FluidTags").getField(name).get(null);
	}

	private Object tracking(Object... tags) throws Exception {
		return runtime.getMethod("state", Set.class).invoke(null, Set.of(tags));
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		return (sun.misc.Unsafe) theUnsafe.get(null);
	}

	/** A MinecraftForge mod's fluid of {@code type} in {@code tags}, without the registry its constructor would join off-game. */
	private Object own(Object type, Object... tags) throws Exception {
		Class<?> fluidClass = loader.loadClass("example.forge.OwnFluid");
		Object fluid = unsafe().allocateInstance(fluidClass);
		fluidClass.getField("type").set(fluid, type);
		fluidClass.getField("tags").set(fluid, Set.of(tags));
		return fluid;
	}

	/**
	 * A fluid that gives no MinecraftForge type of its own, in {@code tags}, whose MinecraftForge getter answers
	 * {@code type}: the kernel's tag-derived stand-in for a Fabric or NeoForge mod's fluid ({@code standIn}), or as for
	 * vanilla's, MinecraftForge's own type of it.
	 */
	@SuppressWarnings("unchecked")
	private Object foreign(Object type, boolean standIn, Object... tags) throws Exception {
		Class<?> fluidClass = loader.loadClass("example.forge.ForeignFluid"), base = loader.loadClass("net.minecraft.world.level.material.Fluid");
		Object fluid = unsafe().allocateInstance(fluidClass);
		fluidClass.getField("tags").set(fluid, Set.of(tags));
		boolean cached = false;
		for (Field field : base.getDeclaredFields()) {   // the merged Fluid caches both families' type, in two fields of one name
			if (field.getType() == forgeType) {
				field.setAccessible(true);
				field.set(fluid, type);
				cached = true;
			}
		}
		assertTrue(cached, "premise: the merged Fluid caches its MinecraftForge type in a field");
		if (standIn) {
			Field standIns = loader.loadClass("net.forbric.kernel.runtime.KernelFluidTypes").getDeclaredField("FORGE_STAND_INS");
			standIns.setAccessible(true);
			((Set<Object>) standIns.get(null)).add(fluid);
		}
		return fluid;
	}

	private Object invoke(Object target, String name, Object... args) throws Exception {
		for (Method m : target.getClass().getDeclaredMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == args.length) {
				m.setAccessible(true);
				return m.invoke(target, args);
			}
		}
		throw new AssertionError(name);
	}

	/** The start of an update for a body whose interaction box and body start at those heights, eyes at {@code eyeY}. */
	private void begin(Object state, double boxMinY, double bodyMinY, double eyeY, int blockX, int blockZ) throws Exception {
		invoke(state, "begin", boxMinY, bodyMinY, eyeY, blockX, blockZ);
	}

	/** One non-empty cell the update reads: its position, its fluid, how high the fluid stands in it. */
	private void cell(Object state, Object fluid, int x, int y, int z, float height) throws Exception {
		invoke(state, "cell", fluid, x, y, z, height);
	}

	private Object tracker(Object state, Object tag) throws Exception {
		return invoke(state, "tag", tag);
	}

	private static Object read(Object owner, String name) throws Exception {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static Map<?, ?> byType(Object state) throws Exception {
		return (Map<?, ?>) read(state, "byType");
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
		TestFixtures.requireForgeCarrier("MinecraftForge's FluidType");
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
		TestFixtures.requireForgeCarrier("MinecraftForge's FluidType");
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
		TestFixtures.requireForgeCarrier("MinecraftForge's FluidType");
		load();
		Object state = state();
		assertEquals(0.7, call("neoHeight", state, null, 0.7));
		assertEquals(false, call("neoIn", state, null, false));
		assertEquals(true, call("neoEyes", state, null, true));
		assertEquals(0.3, call("neoHeight", null, null, 0.3), "an interaction made before the edit has no record: NeoForge decides");
	}

	/**
	 * MinecraftForge's loop over one cell: the height is the fluid's top above the body's bottom (not the interaction
	 * box's), and the eyes are in it in the body's own column between the cell's bottom and its top, both included — the
	 * type the eyes are in is the eye type.
	 */
	@Test void aCellIsRecordedAsMinecraftForgesLoopRecordsIt() throws Exception {
		loadWithFluids();
		Object blood = type("blood"), murk = type("murk");
		Object state = tracking();
		begin(state, 100.4, 100.0, 101.5, 0, 0);
		cell(state, own(blood), 0, 101, 0, 0.5f);
		assertEquals(true, call("forgeInFluid", state));
		assertEquals(1.5, call("forgeHeight", state, blood), "the top (101.5) above the body's bottom (100), not the box's (100.4)");
		assertEquals(true, call("forgeEyes", state, blood), "eyes exactly at the fluid's top are in it");
		assertSame(blood, call("forgeEye", state));
		cell(state, own(murk), 1, 101, 0, 0.5f);
		assertEquals(1.5, call("forgeHeight", state, murk));
		assertEquals(false, call("forgeEyes", state, murk), "another column: the body's height, never its eyes");
		assertSame(blood, call("forgeEye", state));

		Object low = tracking();
		begin(low, 100.0, 100.0, 101.0, 0, 0);
		cell(low, own(murk), 0, 101, 0, 0.25f);
		assertEquals(true, call("forgeEyes", low, murk), "eyes exactly at the cell's bottom are in it");
		Object high = tracking();
		begin(high, 100.0, 100.0, 101.5, 0, 0);
		cell(high, own(murk), 0, 101, 0, 0.25f);
		assertEquals(false, call("forgeEyes", high, murk), "eyes above the fluid's top are not");
		assertEquals(1.25, call("forgeHeight", high, murk));
	}

	/**
	 * The eyes are in a cell only in the body's own block column: MinecraftForge's loop compares the cell's x with the
	 * body's block x and its z with the body's block z, both. A cell beside the body in either axis alone, at the eyes'
	 * height, holds the body (its height counts) but not its eyes — for the type and for a tag the fluid is in.
	 */
	@Test void theEyesAreInTheBodysOwnColumnOnly() throws Exception {
		loadWithFluids();
		Object water = tag("WATER"), sameX = type("same-x"), sameZ = type("same-z"), own = type("own");
		Object state = tracking(water);
		begin(state, 100.0, 100.0, 101.5, 4, 7);
		cell(state, own(sameX, water), 4, 101, 8, 1.0f);   // the body's x, another z
		assertEquals(2.0, call("forgeHeight", state, sameX), "a cell beside the body holds it");
		assertEquals(false, call("forgeEyes", state, sameX), "the body's x but another z: not the eyes' column");
		assertEquals(false, read(tracker(state, water), "eyes"), "nor for the tag the fluid is in");
		cell(state, own(sameZ, water), 3, 101, 7, 1.0f);   // another x, the body's z
		assertEquals(false, call("forgeEyes", state, sameZ), "another x but the body's z: not the eyes' column");
		assertEquals(false, read(tracker(state, water), "eyes"));
		assertNull(read(state, "eye"), "no eye type: the eyes were in neither cell");
		cell(state, own(own, water), 4, 101, 7, 1.0f);    // the body's own column
		assertEquals(true, call("forgeEyes", state, own));
		assertEquals(true, read(tracker(state, water), "eyes"));
		assertSame(own, call("forgeEye", state));
	}

	/**
	 * A tracker keeps the greatest height of every cell it was met in during one update, whatever the order: MinecraftForge
	 * takes {@code Math.max} of the cell's height and the tracker's. A lower cell met after a higher one — the cells are
	 * walked by x, then y, then z, so a column beside the body is met after the body's own — leaves the height as it was,
	 * for the type and for each tag the fluid is in.
	 */
	@Test void aLowerCellMetLaterLeavesTheHeight() throws Exception {
		loadWithFluids();
		Object water = tag("WATER"), wet = type("wet");
		Object state = tracking(water);
		begin(state, 100.0, 100.0, 101.62, 0, 0);
		cell(state, own(wet, water), 0, 101, 0, 0.75f);   // the body's own column, 1.75 above its bottom
		cell(state, own(wet, water), 1, 100, 0, 0.25f);   // beside it, shallower: 0.25
		assertEquals(1.75, call("forgeHeight", state, wet), "the higher cell's height stands");
		assertEquals(1.75, read(tracker(state, water), "height"), "and the tag's");
		assertEquals(1.75, invoke(state, "heightIn", water, 0.0));
		cell(state, own(wet, water), 2, 101, 0, 0.5f);    // a third, between the two
		assertEquals(1.75, call("forgeHeight", state, wet));
		assertEquals(1.75, read(tracker(state, water), "height"));
	}

	/** A cell whose fluid tops out below the interaction box is touched (any-fluid) and nothing more: no tracker is made. */
	@Test void aCellBelowTheBoxIsOnlyTouched() throws Exception {
		loadWithFluids();
		Object blood = type("blood");
		Object state = tracking(tag("WATER"));
		begin(state, 100.4, 100.0, 101.62, 0, 0);
		cell(state, own(blood, tag("WATER")), 0, 99, 0, 1.0f);
		assertEquals(true, call("forgeInFluid", state), "MinecraftForge counts any fluid cell it reads");
		assertTrue(byType(state).isEmpty(), "no tracker below the box: " + byType(state));
		assertEquals(false, call("forgeMatching", state, (BiPredicate<Object, Double>) (t, h) -> true, false));
		assertEquals(0.0, read(tracker(state, tag("WATER")), "height"));
		assertEquals(false, read(state, "tagged"));
	}

	/**
	 * MinecraftForge's record holds the fluids MinecraftForge types: a MinecraftForge mod's (its own type) and vanilla's
	 * (MinecraftForge's type of it). A Fabric or NeoForge mod's fluid has only the kernel's stand-in, which is left out.
	 * The tag trackers count a MinecraftForge mod's fluid alone, in each tag it is in: vanilla's water already has
	 * NeoForge's water type, and a stand-in is decided by NeoForge.
	 */
	@Test void minecraftForgesRecordHoldsTheFluidsItTypes() throws Exception {
		loadWithFluids();
		Object water = tag("WATER"), lava = tag("LAVA");
		Object standInType = type("stand-in"), vanillaType = type("water"), wet = type("wet");
		Object state = tracking(water, lava);
		begin(state, 100.0, 100.0, 101.5, 0, 0);
		cell(state, foreign(standInType, true, water), 0, 100, 0, 1.0f);
		assertTrue(byType(state).isEmpty(), "the kernel's stand-in is not MinecraftForge's: " + byType(state));
		assertEquals(false, read(state, "tagged"), "nor is a fluid that is not a MinecraftForge mod's counted in a tag");
		cell(state, foreign(vanillaType, false, water), 0, 100, 0, 1.0f);
		assertEquals(1.0, call("forgeHeight", state, vanillaType), "vanilla's fluid: MinecraftForge's type of it");
		assertEquals(false, read(state, "tagged"), "vanilla's water is NeoForge's water already");
		cell(state, own(wet, water), 0, 101, 0, 0.5f);
		assertEquals(1.5, call("forgeHeight", state, wet));
		assertEquals(1.5, read(tracker(state, water), "height"), "a MinecraftForge mod's fluid in minecraft:water");
		assertEquals(true, read(tracker(state, water), "eyes"));
		assertEquals(0.0, read(tracker(state, lava), "height"), "and in no other tag");
		assertEquals(false, read(tracker(state, lava), "eyes"));
	}

	/**
	 * A fluid already known to hold the kernel's stand-in is not asked its MinecraftForge type again: that getter keeps no
	 * cache for a stand-in and would derive one from the fluid's tags for every cell, every tick. Here the stand-in fluid's
	 * cache is empty, so asking would run MinecraftForge's own lookup, which cannot even start off-game.
	 */
	@Test void aKnownStandInIsNotAskedAgain() throws Exception {
		loadWithFluids();
		Object water = tag("WATER");
		Object state = tracking(water);
		begin(state, 100.0, 100.0, 101.5, 0, 0);
		Object fabric = foreign(null, true, water);   // a Fabric fluid's: recorded as a stand-in, nothing cached
		assertDoesNotThrow(() -> cell(state, fabric, 0, 101, 0, 1.0f), "the getter is not asked");
		assertTrue(byType(state).isEmpty());
		assertEquals(true, call("forgeInFluid", state), "it is still a fluid touched, as MinecraftForge counts any");
	}

	/**
	 * NeoForge's water and lava answers, widened by a MinecraftForge mod's fluid in that tag: the greater height; "in" by a
	 * height above zero; "eyes in" by the eyes alone. NeoForge's own "yes" stands, and a type decided by no tag is
	 * NeoForge's alone.
	 */
	@Test void neoForgesWaterAndLavaAnswersWidenAsMinecraftForgesTagsDo() throws Exception {
		loadWithFluids();
		Object water = tag("WATER"), lava = tag("LAVA"), wet = type("wet");
		Object state = tracking(water, lava);
		begin(state, 100.0, 100.0, 101.62, 0, 0);
		cell(state, own(wet, water), 0, 99, 0, 1.0f);   // tops out at the body's bottom: height 0
		assertEquals(true, read(state, "tagged"));
		assertEquals(false, invoke(state, "inTag", water, false), "a height of 0 is not in, as MinecraftForge's isInFluid(tag)");
		assertEquals(0.25, invoke(state, "heightIn", water, 0.25));
		cell(state, own(wet, water), 1, 100, 0, 0.5f);   // beside the body: in, eyes not
		assertEquals(true, invoke(state, "inTag", water, false));
		assertEquals(false, invoke(state, "eyesInTag", water, false), "in it is not eyes in it");
		assertEquals(0.5, invoke(state, "heightIn", water, 0.25));
		assertEquals(0.75, invoke(state, "heightIn", water, 0.75), "NeoForge's own height when greater");
		assertEquals(false, invoke(state, "inTag", lava, false), "nothing in minecraft:lava");
		assertEquals(0.25, invoke(state, "heightIn", lava, 0.25));
		assertEquals(true, invoke(state, "inTag", lava, true), "NeoForge's own yes stands");
		assertEquals(true, invoke(state, "eyesInTag", lava, true));
		assertEquals(0.25, invoke(state, "heightIn", null, 0.25), "a type MinecraftForge decides by no tag: NeoForge's answer");
		cell(state, own(wet, water), 0, 101, 0, 0.62f);   // the eyes' cell
		assertEquals(true, invoke(state, "eyesInTag", water, false));
	}

	/**
	 * Each update starts from nothing — no fluid touched, no eye type, every tracker at 0 without eyes — and keeps every
	 * tracker MinecraftForge made, which its all-match loop then reads.
	 */
	@Test void everyUpdateStartsFromNothingAndKeepsTheTrackers() throws Exception {
		loadWithFluids();
		Object water = tag("WATER"), wet = type("wet");
		Object state = tracking(water);
		begin(state, 100.0, 100.0, 101.5, 0, 0);
		cell(state, own(wet, water), 0, 101, 0, 0.5f);
		assertEquals(true, call("forgeEyes", state, wet));
		begin(state, 100.0, 100.0, 101.5, 0, 0);
		assertEquals(false, call("forgeInFluid", state));
		assertEquals(0.0, call("forgeHeight", state, wet));
		assertEquals(false, call("forgeEyes", state, wet));
		assertNull(read(state, "eye"), "no eye type until the eyes are in a fluid again");
		assertEquals(1, byType(state).size(), "the tracker is kept");
		assertEquals(false, call("forgeMatching", state, (BiPredicate<Object, Double>) (t, h) -> true, true));
		assertEquals(0.0, read(tracker(state, water), "height"));
		assertEquals(false, read(tracker(state, water), "eyes"));
		assertEquals(false, invoke(state, "inTag", water, false));
	}

	/** {@code getMaxHeightFluid()}: the type with the greatest height, the first met of equal ones. */
	@Test void theTallestTypeIsTheFirstMetOfEqualHeights() throws Exception {
		loadWithFluids();
		Object first = type("first"), second = type("second"), third = type("third");
		Object state = tracking();
		begin(state, 100.0, 100.0, 103.0, 0, 0);
		cell(state, own(first), 1, 100, 0, 1.0f);
		cell(state, own(second), 2, 100, 0, 1.0f);
		assertSame(first, call("forgeMaxHeight", state));
		cell(state, own(third), 3, 100, 0, 1.0f);
		cell(state, own(third), 3, 101, 0, 0.25f);
		assertSame(third, call("forgeMaxHeight", state));
	}

	/** Switched off, no record is made, and every MinecraftForge query answers "no fluid" as merged. */
	@Test void switchedOffThereIsNoRecord() throws Exception {
		TestFixtures.requireForgeCarrier("MinecraftForge's FluidType");
		load();
		System.setProperty("forbric.forgeEntityFluids", "off");
		assertNull(state());
		assertEquals(false, call("forgeInFluid", (Object) null));
		assertEquals(false, call("forgeMatching", null, (BiPredicate<Object, Double>) (t, h) -> true, false));
	}
}
