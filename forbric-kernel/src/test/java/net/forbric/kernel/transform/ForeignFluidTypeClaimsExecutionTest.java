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
 * ({@code OwnFluidTypeClausesInjectorTest}) hold the edits to the real merged classes; the in-game probe holds the whole
 * against native NeoForge and Fabric.
 */
@ExecutesInjector({OwnFluidTypeClausesInjector.class, EmptyFluidTypeTrackerInjector.class})
class ForeignFluidTypeClaimsExecutionTest {
	private static final String FORGE_TYPE = """
			package net.minecraftforge.fluids;
			public class FluidType {
				public final String name; public final boolean extinguishes;
				public FluidType(String name, boolean extinguishes) { this.name = name; this.extinguishes = extinguishes; }
			}
			""";
	private static final String NEO_TYPE = """
			package net.neoforged.neoforge.fluids;
			public class FluidType {
				public final String name; public final boolean extinguishes;
				public FluidType(String name, boolean extinguishes) { this.name = name; this.extinguishes = extinguishes; }
				public final boolean isAir() { return name.equals("empty"); }
			}
			""";

	/** The merged Fluid's shape: both families' getters, and a clause asking each, as entityInside does. */
	private static final Map<String, String> CLAUSES = Map.of(
			"net.minecraftforge.fluids.FluidType", FORGE_TYPE,
			"net.neoforged.neoforge.fluids.FluidType", NEO_TYPE,
			"net.minecraft.world.level.material.Fluid", """
					package net.minecraft.world.level.material;

					public class Fluid {
						public net.minecraftforge.fluids.FluidType forge;
						public net.neoforged.neoforge.fluids.FluidType neo;
						public final java.util.List<String> effects = new java.util.ArrayList<>();

						public net.minecraftforge.fluids.FluidType getFluidType() { return forge; }
						public net.neoforged.neoforge.fluids.FluidType getNeoFluidType() { return neo; }

						/** Both families' clauses, each putting the fire out when its type says so. */
						public void entityInside() {
							if (getFluidType().extinguishes) effects.add("forge:" + getFluidType().name);
							if (neoType().extinguishes) effects.add("neo:" + neoType().name);
						}

						private net.neoforged.neoforge.fluids.FluidType neoType() { return neo; }
					}
					""",
			"net.forbric.kernel.runtime.KernelFluidTypes", """
					package net.forbric.kernel.runtime;

					/** Answers as the kernel does: a stand-in is replaced by that family's empty type, anything else passes. */
					public final class KernelFluidTypes {
						public static final java.util.List<String> calls = new java.util.ArrayList<>();
						public static net.minecraftforge.fluids.FluidType ownForgeType(net.minecraft.world.level.material.Fluid fluid,
								net.minecraftforge.fluids.FluidType type) {
							calls.add("forge " + type.name);
							return type.name.startsWith("stand-in") ? new net.minecraftforge.fluids.FluidType("empty", false) : type;
						}
						public static net.neoforged.neoforge.fluids.FluidType ownNeoType(net.minecraft.world.level.material.Fluid fluid,
								net.neoforged.neoforge.fluids.FluidType type) {
							calls.add("neo " + type.name);
							return type.name.startsWith("stand-in") ? new net.neoforged.neoforge.fluids.FluidType("empty", false) : type;
						}
					}
					""");

	/**
	 * The stand-in's shape needs NeoForge's getter under the same name as MinecraftForge's; javac cannot declare two
	 * methods differing only in return type, so the source names it {@code getNeoFluidType} and calls it through a
	 * private helper, and the bytes are renamed to the merged class's shape before the edit.
	 */
	private static byte[] mergedShape(byte[] bytes) {
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
		for (var m : node.methods) {
			if (m.name.equals("getNeoFluidType")) m.name = "getFluidType";
			if (m.name.equals("neoType")) {
				// private neoType() { return this.neo; } becomes { return this.getFluidType(); } (NeoForge's getter)
				m.instructions.clear();
				m.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
				m.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, node.name, "getFluidType",
						"()Lnet/neoforged/neoforge/fluids/FluidType;", false));
				m.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARETURN));
			}
		}
		// entityInside asks NeoForge's getter directly, as the merged one does: inline the helper call.
		for (var m : node.methods) {
			if (!m.name.equals("entityInside")) continue;
			for (var insn : m.instructions.toArray()) {
				if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("neoType")) {
					m.instructions.set(call, new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, node.name,
							"getFluidType", "()Lnet/neoforged/neoforge/fluids/FluidType;", false));
				}
			}
		}
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		return owner.getClass().getField(name).get(owner);
	}

	private static void setField(Object owner, String name, Object value) throws ReflectiveOperationException {
		owner.getClass().getField(name).set(owner, value);
	}

	private static Object type(ClassLoader loader, String family, String name, boolean extinguishes) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(family.equals("forge") ? "net.minecraftforge.fluids.FluidType" : "net.neoforged.neoforge.fluids.FluidType"),
				name, extinguishes);
	}

	/**
	 * Each family's clause is answered by the kernel with the type the fluid gave it: a stand-in becomes that family's empty
	 * type, which puts out nothing; a type of the fluid's own still does. As merged, a stand-in that extinguishes did.
	 */
	@Test void eachFamilysClauseIsAskedOnlyTheTypeItGaveTheFluid(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = new HashMap<>(InjectorExecution.compile(work, CLAUSES));
		original.put("net/minecraft/world/level/material/Fluid", mergedShape(original.get("net/minecraft/world/level/material/Fluid")));
		Map<String, byte[]> classes = new HashMap<>(original);
		byte[] fluidBytes = original.get("net/minecraft/world/level/material/Fluid");
		byte[] edited = InjectorExecution.transform(new OwnFluidTypeClausesInjector(), "net.minecraft.world.level.material.Fluid", fluidBytes, EnvType.SERVER);
		assertNotSame(fluidBytes, edited, "a method asking both families' type is the shape the edit takes");
		classes.put("net/minecraft/world/level/material/Fluid", edited);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(edited, loader));
		assertSame(edited, InjectorExecution.transform(new OwnFluidTypeClausesInjector(), "net.minecraft.world.level.material.Fluid", edited, EnvType.SERVER),
				"not made twice");

		Class<?> fluidClass = loader.loadClass("net.minecraft.world.level.material.Fluid");
		@SuppressWarnings("unchecked")
		List<String> calls = (List<String>) InjectorExecution.getStatic(loader.loadClass("net.forbric.kernel.runtime.KernelFluidTypes"), "calls");
		// A NeoForge mod's fluid: its own NeoForge type does not extinguish; MinecraftForge's stand-in (water's) would.
		Object neoFluid = InjectorExecution.construct(fluidClass);
		setField(neoFluid, "forge", type(loader, "forge", "stand-in water", true));
		setField(neoFluid, "neo", type(loader, "neo", "own", false));
		InjectorExecution.invoke(neoFluid, "entityInside");
		assertEquals(List.of(), field(neoFluid, "effects"), "the stand-in claims nothing; the fluid's own type says no");
		assertEquals(List.of("forge stand-in water", "neo own"), calls, "each getter's answer goes through the kernel with its fluid");
		calls.clear();
		// A MinecraftForge mod's fluid: its own MinecraftForge type, and NeoForge's view of it, both say yes.
		Object forgeFluid = InjectorExecution.construct(fluidClass);
		setField(forgeFluid, "forge", type(loader, "forge", "own", true));
		setField(forgeFluid, "neo", type(loader, "neo", "view", true));
		InjectorExecution.invoke(forgeFluid, "entityInside");
		assertEquals(List.of("forge:own", "neo:view"), field(forgeFluid, "effects"), "a family's own type still decides");
		// A Fabric mod's fluid in minecraft:water: stand-ins on both sides, so nothing, as vanilla's Fluid.entityInside.
		Object fabricFluid = InjectorExecution.construct(fluidClass);
		setField(fabricFluid, "forge", type(loader, "forge", "stand-in water", true));
		setField(fabricFluid, "neo", type(loader, "neo", "stand-in water", true));
		InjectorExecution.invoke(fabricFluid, "entityInside");
		assertEquals(List.of(), field(fabricFluid, "effects"));

		ClassLoader merged = InjectorExecution.load(original);
		Object stock = InjectorExecution.construct(merged.loadClass("net.minecraft.world.level.material.Fluid"));
		setField(stock, "forge", type(merged, "forge", "stand-in water", true));
		setField(stock, "neo", type(merged, "neo", "own", false));
		InjectorExecution.invoke(stock, "entityInside");
		assertEquals(List.of("forge:stand-in water"), field(stock, "effects"), "premise: as merged, the stand-in put the fire out");
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
