/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two edits that keep a type the kernel derived for a fluid from claiming what its family never claimed, run: their
 * output is defined and called over stand-ins with the shapes each edit keys on. The staged tests
 * ({@code OwnFluidTypeClausesInjectorTest}) hold the edits to NeoForge's real classes; gate-m45 holds the whole in game
 * (fire.vanillaWater, fire.fabricWater).
 */
@ExecutesInjector({OwnFluidTypeClausesInjector.class, EmptyFluidTypeTrackerInjector.class})
class ForeignFluidTypeClaimsExecutionTest {
	private static final String NEO_TYPE = """
			package net.neoforged.neoforge.fluids;
			public class FluidType {
				public final String name; public final boolean extinguishes;
				public FluidType(String name, boolean extinguishes) { this.name = name; this.extinguishes = extinguishes; }
				public final boolean isAir() { return name.equals("empty"); }
			}
			""";

	/** NeoForge's Fluid's shape: its getter, and entityInside's fire clause handing that getter's type to the entity. */
	private static final Map<String, String> CLAUSES = Map.of(
			"net.neoforged.neoforge.fluids.FluidType", NEO_TYPE,
			"net.minecraft.world.entity.Entity", """
					package net.minecraft.world.entity;

					public class Entity {
						public boolean canFluidExtinguish(net.neoforged.neoforge.fluids.FluidType type) { return type.extinguishes; }
					}
					""",
			"net.minecraft.world.level.material.Fluid", """
					package net.minecraft.world.level.material;

					public class Fluid {
						public net.neoforged.neoforge.fluids.FluidType neo;
						public final java.util.List<String> effects = new java.util.ArrayList<>();

						public net.neoforged.neoforge.fluids.FluidType getFluidType() { return neo; }

						/** NeoForge's clause: put the fire out when the fluid's type says the entity can be extinguished in it. */
						public void entityInside(net.minecraft.world.entity.Entity entity) {
							if (entity.canFluidExtinguish(getFluidType())) effects.add("extinguish");
						}

						/** Asks the type for something else: not the fire clause, left alone. */
						public String typeName() { return getFluidType().name; }
					}
					""",
			"net.forbric.kernel.runtime.KernelFluidTypes", """
					package net.forbric.kernel.runtime;

					/** Answers as the kernel does: a stand-in is replaced by NeoForge's empty type, anything else passes. */
					public final class KernelFluidTypes {
						public static final java.util.List<String> calls = new java.util.ArrayList<>();
						public static net.neoforged.neoforge.fluids.FluidType ownNeoType(net.minecraft.world.level.material.Fluid fluid,
								net.neoforged.neoforge.fluids.FluidType type) {
							calls.add("neo " + type.name);
							return type.name.startsWith("stand-in") ? new net.neoforged.neoforge.fluids.FluidType("empty", false) : type;
						}
					}
					""");

	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		return owner.getClass().getField(name).get(owner);
	}

	private static void setField(Object owner, String name, Object value) throws ReflectiveOperationException {
		owner.getClass().getField(name).set(owner, value);
	}

	private static Object type(ClassLoader loader, String name, boolean extinguishes) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.fluids.FluidType"), name, extinguishes);
	}

	/**
	 * NeoForge's fire clause is answered by the kernel with the type the fluid gave itself: a stand-in becomes NeoForge's
	 * empty type, which puts out nothing (vanilla's empty Fluid.entityInside); a type of the fluid's own still does. Left
	 * alone, a stand-in that extinguishes did.
	 */
	@Test void neoForgesFireClauseIsAskedOnlyTheTypeTheFluidGaveItself(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = new HashMap<>(InjectorExecution.compile(work, CLAUSES));
		Map<String, byte[]> classes = new HashMap<>(original);
		byte[] fluidBytes = original.get("net/minecraft/world/level/material/Fluid");
		byte[] edited = InjectorExecution.transform(new OwnFluidTypeClausesInjector(), "net.minecraft.world.level.material.Fluid", fluidBytes, EnvType.SERVER);
		assertNotSame(fluidBytes, edited, "a fluid type handed to canFluidExtinguish is the shape the edit takes");
		classes.put("net/minecraft/world/level/material/Fluid", edited);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(edited, loader));
		assertSame(edited, InjectorExecution.transform(new OwnFluidTypeClausesInjector(), "net.minecraft.world.level.material.Fluid", edited, EnvType.SERVER),
				"not made twice");

		Class<?> fluidClass = loader.loadClass("net.minecraft.world.level.material.Fluid");
		Object entity = InjectorExecution.construct(loader.loadClass("net.minecraft.world.entity.Entity"));
		@SuppressWarnings("unchecked")
		List<String> calls = (List<String>) InjectorExecution.getStatic(loader.loadClass("net.forbric.kernel.runtime.KernelFluidTypes"), "calls");
		// A NeoForge mod's fluid whose own type does not extinguish.
		Object neoFluid = InjectorExecution.construct(fluidClass);
		setField(neoFluid, "neo", type(loader, "own", false));
		InjectorExecution.invoke(neoFluid, "entityInside", entity);
		assertEquals(List.of(), field(neoFluid, "effects"), "the fluid's own type says no");
		assertEquals(List.of("neo own"), calls, "the getter's answer goes through the kernel with its fluid");
		calls.clear();
		// A NeoForge mod's (or vanilla's) fluid whose own type extinguishes still does.
		Object wetFluid = InjectorExecution.construct(fluidClass);
		setField(wetFluid, "neo", type(loader, "own", true));
		InjectorExecution.invoke(wetFluid, "entityInside", entity);
		assertEquals(List.of("extinguish"), field(wetFluid, "effects"), "the fluid's own type still decides");
		// A Fabric mod's fluid in minecraft:water: the stand-in claims nothing, as vanilla's Fluid.entityInside.
		Object fabricFluid = InjectorExecution.construct(fluidClass);
		setField(fabricFluid, "neo", type(loader, "stand-in water", true));
		InjectorExecution.invoke(fabricFluid, "entityInside", entity);
		assertEquals(List.of(), field(fabricFluid, "effects"));
		calls.clear();
		assertEquals("stand-in water", InjectorExecution.invoke(fabricFluid, "typeName"), "a getter call that is not the fire clause is left alone");
		assertEquals(List.of(), calls);

		ClassLoader stockLoader = InjectorExecution.load(original);
		Object stock = InjectorExecution.construct(stockLoader.loadClass("net.minecraft.world.level.material.Fluid"));
		setField(stock, "neo", type(stockLoader, "stand-in water", true));
		InjectorExecution.invoke(stock, "entityInside", InjectorExecution.construct(stockLoader.loadClass("net.minecraft.world.entity.Entity")));
		assertEquals(List.of("extinguish"), field(stock, "effects"), "premise: left alone, the stand-in put the fire out");
	}

	private static final Map<String, String> TRACKERS = Map.of(
			"net.neoforged.neoforge.fluids.FluidType", NEO_TYPE,
			"net.minecraft.world.entity.EntityFluidInteraction", """
					package net.minecraft.world.entity;

					import java.util.*;

					public class EntityFluidInteraction {
						public static class Tracker { }
						private final Map<net.neoforged.neoforge.fluids.FluidType, Tracker> trackerByFluid = new HashMap<>();

						/** NeoForge's walk: each non-empty cell's type gets a tracker; a cell with none is passed over. */
						public int walk(List<net.neoforged.neoforge.fluids.FluidType> cells) {
							int tracked = 0;
							for (net.neoforged.neoforge.fluids.FluidType type : cells) {
								Tracker tracker = getTrackerFor(type);
								if (tracker == null) continue;
								tracked++;
							}
							return tracked;
						}

						private Tracker getTrackerFor(net.neoforged.neoforge.fluids.FluidType type) {
							return trackerByFluid.computeIfAbsent(type, t -> new Tracker());
						}

						public int trackers() { return trackerByFluid.size(); }
					}
					""");

	/** A cell of NeoForge's empty type gets no tracker, so the walk passes over it; every other type still gets one. */
	@Test void theEmptyTypeGetsNoTracker(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, TRACKERS);
		Map<String, byte[]> classes = new HashMap<>(original);
		byte[] bytes = original.get(EmptyFluidTypeTrackerInjector.OWNER);
		byte[] edited = InjectorExecution.transform(new EmptyFluidTypeTrackerInjector(), EmptyFluidTypeTrackerInjector.INTERACTION, bytes, EnvType.SERVER);
		assertNotSame(bytes, edited, "NeoForge's tracker lookup by type is the shape the edit takes");
		classes.put(EmptyFluidTypeTrackerInjector.OWNER, edited);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(edited, loader));
		assertSame(edited, InjectorExecution.transform(new EmptyFluidTypeTrackerInjector(), EmptyFluidTypeTrackerInjector.INTERACTION, edited, EnvType.SERVER),
				"not made twice");
		Class<?> neo = loader.loadClass("net.neoforged.neoforge.fluids.FluidType");
		Object interaction = InjectorExecution.construct(loader.loadClass(EmptyFluidTypeTrackerInjector.INTERACTION));
		Object empty = InjectorExecution.construct(neo, "empty", false), water = InjectorExecution.construct(neo, "water", true);
		assertEquals(1, InjectorExecution.invoke(interaction, "walk", List.of(empty, water, empty)), "only the typed cell is tracked");
		assertEquals(1, InjectorExecution.invoke(interaction, "trackers"), "no tracker of the empty type exists");

		ClassLoader merged = InjectorExecution.load(original);
		Class<?> stockNeo = merged.loadClass("net.neoforged.neoforge.fluids.FluidType");
		Object stock = InjectorExecution.construct(merged.loadClass(EmptyFluidTypeTrackerInjector.INTERACTION));
		assertEquals(3, InjectorExecution.invoke(stock, "walk", List.of(InjectorExecution.construct(stockNeo, "empty", false),
				InjectorExecution.construct(stockNeo, "water", true), InjectorExecution.construct(stockNeo, "empty", false))),
				"premise: as merged, the empty type was tracked like any other");
	}
}
