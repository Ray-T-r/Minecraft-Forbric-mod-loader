/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three edits behind a MinecraftForge fluid's parity with native MinecraftForge, run: their output is defined and
 * called over stand-ins that keep the shapes each edit keys on, with the kernel's game-side helpers replaced by recorders.
 * The staged tests ({@code ForgeEntityFluidsInjectorTest}, {@code ForgeFluidViewInjectorsTest}) hold the same edits to
 * the real merged classes; the in-game probe holds the whole against native MinecraftForge.
 */
@ExecutesInjector({ForgeEntityFluidsInjector.class, ForgeFluidViewExtensionsInjector.class, ForgeFluidWaterRulesInjector.class})
class ForgeFluidParityInjectorsExecutionTest {
	private static final String FORGE_TYPE = """
			package net.minecraftforge.fluids;
			public class FluidType {
				public final String name;
				public FluidType(String name) { this.name = name; }
			}
			""";
	private static final String NEO_TYPE = """
			package net.neoforged.neoforge.fluids;
			public class FluidType {
				public final String name;
				public FluidType(String name) { this.name = name; }
			}
			""";

	/** The merged interaction's shapes: NeoForge's walk reading one cell per step, MinecraftForge's queries of state nothing writes. */
	private static final Map<String, String> INTERACTION = Map.of(
			"net.minecraftforge.fluids.FluidType", FORGE_TYPE,
			"net.neoforged.neoforge.fluids.FluidType", NEO_TYPE,
			"net.minecraft.core.BlockPos", """
					package net.minecraft.core;
					public class BlockPos { public int y; }
					""",
			"net.minecraft.world.level.material.FluidState", """
					package net.minecraft.world.level.material;
					public record FluidState(String fluid) { }
					""",
			"net.minecraft.world.level.BlockGetter", """
					package net.minecraft.world.level;
					public interface BlockGetter {
						net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos);
					}
					""",
			"net.minecraft.world.entity.Entity", """
					package net.minecraft.world.entity;
					public class Entity {
						public net.minecraft.world.level.BlockGetter level = pos -> new net.minecraft.world.level.material.FluidState("cell" + pos.y);
					}
					""",
			"net.minecraft.world.entity.EntityFluidInteraction", """
					package net.minecraft.world.entity;

					import java.util.*;
					import java.util.function.*;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.level.BlockGetter;
					import net.minecraft.world.level.material.FluidState;

					public class EntityFluidInteraction {
						private final Map<net.minecraftforge.fluids.FluidType, Object> trackerByFluidType = new HashMap<>();
						private boolean isInFluid;
						private net.minecraftforge.fluids.FluidType eyeFluid;
						public final List<String> neoWalk = new ArrayList<>();

						public EntityFluidInteraction(Set<String> tags) {
							isInFluid = false;
							eyeFluid = null;
						}

						/** NeoForge's walk: three cells up one column. */
						public void update(Entity entity, Predicate<net.neoforged.neoforge.fluids.FluidType> ignored) {
							BlockGetter level = entity.level;
							BlockPos pos = new BlockPos();
							for (int y = 0; y < 3; y++) {
								pos.y = y;
								FluidState state = level.getFluidState(pos);
								neoWalk.add(state.fluid());
							}
						}

						public double getFluidHeight(net.minecraftforge.fluids.FluidType type) { return trackerByFluidType.containsKey(type) ? 1 : 0; }
						public boolean isEyeInFluid(net.minecraftforge.fluids.FluidType type) { return type == eyeFluid; }
						public net.minecraftforge.fluids.FluidType getEyeFluid() { return eyeFluid; }
						public boolean isInFluid() { return isInFluid; }
						public final boolean isInFluid(BiPredicate<net.minecraftforge.fluids.FluidType, Double> p, boolean all) { return isInFluid; }
						public net.minecraftforge.fluids.FluidType getMaxHeightFluid() { return eyeFluid; }

						public boolean isInFluid(net.neoforged.neoforge.fluids.FluidType type) { return getFluidHeight(type) > 0; }
						public boolean isEyeInFluid(net.neoforged.neoforge.fluids.FluidType type) { return type.name.equals("eyes"); }
						public double getFluidHeight(net.neoforged.neoforge.fluids.FluidType type) { return type.name.equals("water") ? 0.5 : 0; }
					}
					""",
			"net.forbric.kernel.runtime.KernelForgeEntityFluids", """
					package net.forbric.kernel.runtime;

					import java.util.*;
					import java.util.function.BiPredicate;

					/** Records what the edited interaction hands the kernel, and answers recognisably. */
					public final class KernelForgeEntityFluids {
						public static final List<String> calls = new ArrayList<>();
						public static Object state(Set<?> tags) { calls.add("state " + new TreeSet<>(tags)); return "record"; }
						public static void begin(Object state, net.minecraft.world.entity.Entity entity) { calls.add("begin " + state); }
						public static void cell(Object state, net.minecraft.world.level.material.FluidState fluid, net.minecraft.world.level.BlockGetter level,
								net.minecraft.core.BlockPos pos) { calls.add("cell " + state + " " + fluid.fluid() + " y=" + pos.y); }
						public static double forgeHeight(Object state, net.minecraftforge.fluids.FluidType type) { return 7; }
						public static boolean forgeEyes(Object state, net.minecraftforge.fluids.FluidType type) { return true; }
						public static net.minecraftforge.fluids.FluidType forgeEye(Object state) { return new net.minecraftforge.fluids.FluidType("recorded eye"); }
						public static boolean forgeInFluid(Object state) { return true; }
						public static boolean forgeMatching(Object state, BiPredicate<net.minecraftforge.fluids.FluidType, Double> p, boolean all) {
							return p.test(new net.minecraftforge.fluids.FluidType("asked"), 2.0) && all;
						}
						public static net.minecraftforge.fluids.FluidType forgeMaxHeight(Object state) { return new net.minecraftforge.fluids.FluidType("recorded max"); }
						public static boolean neoIn(Object state, net.neoforged.neoforge.fluids.FluidType type, boolean neo) {
							return neo || type.name.equals("forge fluid in minecraft:lava");
						}
						public static boolean neoEyes(Object state, net.neoforged.neoforge.fluids.FluidType type, boolean neo) { return neo; }
						public static double neoHeight(Object state, net.neoforged.neoforge.fluids.FluidType type, double neo) {
							return type.name.equals("forge fluid in minecraft:lava") ? 0.9 : neo;
						}
					}
					""");

	private static Object field(Object owner, String name) throws ReflectiveOperationException {
		java.lang.reflect.Field field = owner.getClass().getField(name);
		return field.get(owner);
	}

	private static void setField(Object owner, String name, Object value) throws ReflectiveOperationException {
		owner.getClass().getField(name).set(owner, value);
	}

	private static Map<String, byte[]> edit(Map<String, byte[]> original, ClassTransformer edit, String... targets) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : targets) {
			byte[] bytes = original.get(target.replace('.', '/'));
			byte[] out = InjectorExecution.transform(edit, target, bytes, EnvType.CLIENT);
			assertNotSame(bytes, out, target + " is the shape " + edit.name() + " edits");
			classes.put(target.replace('.', '/'), out);
		}
		return classes;
	}

	/**
	 * MinecraftForge's record is made over the cells NeoForge's walk reads, in its order, after one reset; MinecraftForge's
	 * queries answer from the record instead of fields nothing writes; NeoForge's type queries pass every answer through.
	 */
	@Test void minecraftForgesHalfIsRecordedOverNeoForgesWalk(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = edit(InjectorExecution.compile(work, INTERACTION), new ForgeEntityFluidsInjector(), ForgeEntityFluidsInjector.INTERACTION);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get("net/minecraft/world/entity/EntityFluidInteraction"), loader));
		@SuppressWarnings("unchecked")
		List<String> calls = (List<String>) InjectorExecution.getStatic(loader.loadClass("net.forbric.kernel.runtime.KernelForgeEntityFluids"), "calls");
		calls.clear();
		Object interaction = InjectorExecution.construct(loader.loadClass(ForgeEntityFluidsInjector.INTERACTION), Set.of("minecraft:water", "minecraft:lava"));
		Object entity = InjectorExecution.construct(loader.loadClass("net.minecraft.world.entity.Entity"));
		InjectorExecution.invoke(interaction, "update", entity, (java.util.function.Predicate<Object>) t -> true);
		assertEquals(List.of("state [minecraft:lava, minecraft:water]", "begin record", "cell record cell0 y=0", "cell record cell1 y=1",
				"cell record cell2 y=2"), calls, "made with the tracked tags; reset first; each cell right after NeoForge reads it");
		assertEquals(List.of("cell0", "cell1", "cell2"), field(interaction, "neoWalk"), "NeoForge's walk is untouched");

		Class<?> forge = loader.loadClass("net.minecraftforge.fluids.FluidType"), neo = loader.loadClass("net.neoforged.neoforge.fluids.FluidType");
		Object water = InjectorExecution.construct(forge, "water");
		assertEquals(7.0, InjectorExecution.invoke(interaction, "getFluidHeight", water), "MinecraftForge's height comes from the record");
		assertEquals(true, InjectorExecution.invoke(interaction, "isEyeInFluid", water));
		assertEquals(true, InjectorExecution.invoke(interaction, "isInFluid"));
		assertEquals("recorded eye", field(InjectorExecution.invoke(interaction, "getEyeFluid"), "name"));
		assertEquals("recorded max", field(InjectorExecution.invoke(interaction, "getMaxHeightFluid"), "name"));
		assertEquals(true, InjectorExecution.invoke(interaction, "isInFluid", (java.util.function.BiPredicate<Object, Double>) (t, h) -> h == 2.0, true),
				"the predicate and the all flag reach the record's answer");

		Object neoWater = InjectorExecution.construct(neo, "water"), lavaTagged = InjectorExecution.construct(neo, "forge fluid in minecraft:lava");
		assertEquals(0.5, InjectorExecution.invoke(interaction, "getFluidHeight", neoWater), "NeoForge's own answer passes through");
		assertEquals(0.9, InjectorExecution.invoke(interaction, "getFluidHeight", lavaTagged), "and the kernel may widen it");
		assertEquals(true, InjectorExecution.invoke(interaction, "isInFluid", neoWater));
		assertEquals(true, InjectorExecution.invoke(interaction, "isInFluid", lavaTagged), "isInFluid passes its own answer through too");
		assertEquals(false, InjectorExecution.invoke(interaction, "isEyeInFluid", neoWater));
	}

	private static final Map<String, String> EXTENSIONS = Map.of(
			"net.neoforged.neoforge.fluids.FluidType", NEO_TYPE,
			"net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions", """
					package net.neoforged.neoforge.client.extensions.common;

					public interface IClientFluidTypeExtensions {
						IClientFluidTypeExtensions DEFAULT = new IClientFluidTypeExtensions() { };
						java.util.Map<Object, IClientFluidTypeExtensions> REGISTERED = new java.util.HashMap<>();

						static IClientFluidTypeExtensions of(net.neoforged.neoforge.fluids.FluidType type) {
							return REGISTERED.getOrDefault(type, DEFAULT);
						}

						default String colour() { return "sky"; }
					}
					""",
			"net.forbric.kernel.runtime.KernelForgeFluidClientViews", """
					package net.forbric.kernel.runtime;

					public final class KernelForgeFluidClientViews {
						public static Object neoExtensions(Object type) {
							if (!((net.neoforged.neoforge.fluids.FluidType) type).name.equals("view")) return null;
							return new net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions() {
								@Override public String colour() { return "the MinecraftForge type's"; }
							};
						}
					}
					""");

	/** NeoForge's lookup answers a view with the kernel's extensions and every other type as before. */
	@Test void neoForgesLookupAnswersAViewWithTheKernelsExtensions(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = edit(InjectorExecution.compile(work, EXTENSIONS), new ForgeFluidViewExtensionsInjector(),
				ForgeFluidViewExtensionsInjector.EXTENSIONS);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(ForgeFluidViewExtensionsInjector.EXTENSIONS_INTERNAL), loader));
		Class<?> api = loader.loadClass(ForgeFluidViewExtensionsInjector.EXTENSIONS), type = loader.loadClass("net.neoforged.neoforge.fluids.FluidType");
		Object view = InjectorExecution.construct(type, "view"), water = InjectorExecution.construct(type, "water");
		assertEquals("the MinecraftForge type's", InjectorExecution.invoke(InjectorExecution.invokeStatic(api, "of", view), "colour"));
		assertSame(InjectorExecution.getStatic(api, "DEFAULT"), InjectorExecution.invokeStatic(api, "of", water), "any other type: NeoForge's map");
	}

	private static final Map<String, String> RULES = Map.of(
			"net.neoforged.neoforge.fluids.FluidType", NEO_TYPE,
			"net.minecraft.world.entity.Entity", """
					package net.minecraft.world.entity;

					public class Entity {
						public interface InFluidPredicate { boolean test(Entity entity, net.neoforged.neoforge.fluids.FluidType type, double height); }
						public net.neoforged.neoforge.fluids.FluidType in = new net.neoforged.neoforge.fluids.FluidType("blood");

						public boolean canSwimInFluidType(net.neoforged.neoforge.fluids.FluidType type) { return true; }
						public boolean canStartSwimming() { return false; }
						public boolean matching(InFluidPredicate p) { return p.test(this, in, 1.0); }

						/** NeoForge's: in water, eyes out; or in a type it can swim in, eyes out. */
						public boolean isInShallowWater() {
							return matching((e, type, h) -> e.canSwimInFluidType(type) && !e.canStartSwimming());
						}
					}
					""",
			"net.minecraft.world.entity.player.Player", """
					package net.minecraft.world.entity.player;

					public class Player extends net.minecraft.world.entity.Entity {
						/** NeoForge's: eyes in a type the player can swim in. */
						protected boolean updateIsUnderwater() {
							return matching((e, type, h) -> e.canSwimInFluidType(type));
						}
					}
					""",
			"net.forbric.kernel.runtime.KernelForgeFluidTypeViews", """
					package net.forbric.kernel.runtime;

					public final class KernelForgeFluidTypeViews {
						public static boolean underwaterIn(net.minecraft.world.entity.Entity entity, net.neoforged.neoforge.fluids.FluidType type) {
							return type.name.equals("in minecraft:water");
						}
						public static boolean shallowIn(net.minecraft.world.entity.Entity entity, net.neoforged.neoforge.fluids.FluidType type) {
							return false;
						}
					}
					""");

	/** Each rule's predicate asks the kernel where it asked whether the body can swim in the type; the rest of it runs as before. */
	@Test void bothWaterRulesAskTheKernelForTheType(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, RULES);
		Map<String, byte[]> classes = edit(original, new ForgeFluidWaterRulesInjector(), ForgeFluidWaterRulesInjector.ENTITY, ForgeFluidWaterRulesInjector.PLAYER);
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : List.of(ForgeFluidWaterRulesInjector.ENTITY, ForgeFluidWaterRulesInjector.PLAYER)) {
			assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);
		}
		Object player = InjectorExecution.construct(loader.loadClass(ForgeFluidWaterRulesInjector.PLAYER));
		assertEquals(false, InjectorExecution.invoke(player, "updateIsUnderwater"), "swimmable but not in minecraft:water: not under water");
		assertEquals(false, InjectorExecution.invoke(player, "isInShallowWater"), "nor in shallow water");
		setField(player, "in", InjectorExecution.construct(loader.loadClass("net.neoforged.neoforge.fluids.FluidType"), "in minecraft:water"));
		assertEquals(true, InjectorExecution.invoke(player, "updateIsUnderwater"));

		ClassLoader merged = InjectorExecution.load(original);
		Object stock = InjectorExecution.construct(merged.loadClass(ForgeFluidWaterRulesInjector.PLAYER));
		assertEquals(true, InjectorExecution.invoke(stock, "updateIsUnderwater"), "premise: as merged, a swimmable type is under water");
		assertEquals(true, InjectorExecution.invoke(stock, "isInShallowWater"), "and, eyes out, shallow water");
	}
}
