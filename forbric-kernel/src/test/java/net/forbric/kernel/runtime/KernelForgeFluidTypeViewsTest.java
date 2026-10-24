/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.transform.InjectorExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * A MinecraftForge mod's fluid answers NeoForge's fluid-type questions with its own MinecraftForge type, over the
 * COMPILED game-side classes and the staged game.
 *
 * <p>The mod's fluids are compiled as a MinecraftForge mod is, against MinecraftForge's patched game (whose
 * {@code Fluid} has only MinecraftForge's {@code getFluidType()}), and run on the merged one, where NeoForge's entity
 * code asks NeoForge's getter — which, for a fluid that does not override it, ends in
 * {@code KernelFluidTypes.foreignNeoLookup}, which asks {@code KernelForgeFluidTypeViews} first. "Murk" is a type built {@code canDrown(true)}, "mist" {@code canDrown(false)}.
 * Before, both were NeoForge's empty type: air, so nothing drowned in murk. Off-game MinecraftForge's own
 * {@code ForgeMod} cannot initialise, so the lookup is taken in parts (the override test, the switch, the view); the
 * whole of it runs in game (the air probe).
 */
@ResourceLock("system-properties")
class KernelForgeFluidTypeViewsTest {
	/**
	 * Under the staged root the game side was compiled against, beside the runtime the mod is compiled with: CI's
	 * prepared job stages under {@code forbric-kernel/.dev/staged}, where a path naming the sibling forbric-loader
	 * checkout finds nothing (and on a machine that has both, mixes two roots).
	 */
	private static final Path FORGE_PATCHED = TestFixtures.stagedRoot().resolve("forge-patched/patched-mc-forge-26.2.jar");

	private static final Map<String, String> MOD = Map.of(
			"example.forge.TypedFluid", """
					package example.forge;

					import net.minecraft.world.level.material.EmptyFluid;

					/** A MinecraftForge mod's fluid: its class gives its own type, as ForgeFlowingFluid does. */
					public class TypedFluid extends EmptyFluid {
						public net.minecraftforge.fluids.FluidType type;

						@Override
						public net.minecraftforge.fluids.FluidType getFluidType() {
							if (type == null) throw new IllegalStateException("Registry Object not present: example:murk");
							return type;
						}
					}
					""",
			"example.forge.UntypedFluid", """
					package example.forge;

					import net.minecraft.world.level.material.EmptyFluid;

					/** A fluid that gives no MinecraftForge type of its own, as a Fabric mod's never does. */
					public class UntypedFluid extends EmptyFluid {
					}
					""",
			"example.forge.WaterTaggedFluid", """
					package example.forge;

					/** A MinecraftForge mod's fluid in minecraft:water (off-game its tags are answered here, not by a registry). */
					public class WaterTaggedFluid extends TypedFluid {
						@Override
						public boolean is(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag) {
							return tag == net.minecraft.tags.FluidTags.WATER;
						}
					}
					""",
			"example.forge.DryFluid", """
					package example.forge;

					/** A MinecraftForge mod's fluid in no vanilla tag (off-game its tags are answered here). */
					public class DryFluid extends TypedFluid {
						@Override
						public boolean is(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag) {
							return false;
						}
					}
					""",
			"example.forge.FoggyType", """
					package example.forge;

					/** A MinecraftForge fluid type with client extensions of its own: a dark red fog, as Biomes O' Plenty's blood. */
					public class FoggyType extends net.minecraftforge.fluids.FluidType {
						public FoggyType() {
							super(Properties.create().descriptionId("block.example.blood"));
						}

						@Override
						public Object getRenderPropertiesInternal() {
							return new net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions() {
								@Override
								public org.joml.Vector3f modifyFogColor(net.minecraft.client.Camera camera, float partialTick,
										net.minecraft.client.multiplayer.ClientLevel level, int renderDistance, float darkenWorldAmount,
										org.joml.Vector3f fluidFogColor) {
									return new org.joml.Vector3f(0.407f, 0.121f, 0.137f);
								}
							};
						}
					}
					""");

	@TempDir Path temp;
	private URLClassLoader loader;
	private Class<?> runtime, entity, living;

	private void load() throws Exception {
		Path forgeRuntime = TestFixtures.stagedRoot().resolve("merged-base/forge-runtime-interop.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(FORGE_PATCHED) && Files.isRegularFile(forgeRuntime),
				"MinecraftForge's patched game or its runtime is absent");
		// The client extensions a mod's type gives take JOML's vectors, as MinecraftForge's do.
		Path joml;
		try (var jars = Files.walk(TestFixtures.minecraftDir().resolve("libraries/org/joml/joml"))) {
			joml = jars.filter(f -> f.getFileName().toString().matches("joml-[0-9.]+\\.jar")).findFirst().orElse(null);
		} catch (java.io.IOException absent) {
			joml = null;
		}
		TestFixtures.require(Fixture.MC_LIBRARIES, joml != null, "no JOML in the local Minecraft libraries");
		Map<String, byte[]> classes = InjectorExecution.compile(temp.resolve("work"), MOD, List.of(FORGE_PATCHED, forgeRuntime, joml));
		Path out = temp.resolve("mod");
		for (var entry : classes.entrySet()) {
			Path file = out.resolve(entry.getKey() + ".class");
			Files.createDirectories(file.getParent());
			Files.write(file, entry.getValue());
		}
		loader = StagedGameClassLoader.create(List.of(out.toUri().toURL(),
				Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").toUri().toURL()));
		runtime = loader.loadClass("net.forbric.kernel.runtime.KernelForgeFluidTypeViews");
		entity = loader.loadClass("net.minecraft.world.entity.Entity");
		living = loader.loadClass("net.minecraft.world.entity.LivingEntity");
	}

	@AfterEach void close() throws Exception {
		System.clearProperty("forbric.forgeFluidOwnType");
		if (loader != null) loader.close();
	}

	/** A MinecraftForge fluid type built as a mod builds one. */
	private Object forgeType(String name, boolean drowns) throws Exception {
		Class<?> properties = loader.loadClass("net.minecraftforge.fluids.FluidType$Properties");
		Object built = properties.getMethod("create").invoke(null);
		built = properties.getMethod("descriptionId", String.class).invoke(built, "block.example." + name);
		built = properties.getMethod("canDrown", boolean.class).invoke(built, drowns);
		built = properties.getMethod("canSwim", boolean.class).invoke(built, true);
		built = properties.getMethod("density", int.class).invoke(built, 3000);
		built = properties.getMethod("motionScale", double.class).invoke(built, 0.007);
		return loader.loadClass("net.minecraftforge.fluids.FluidType").getConstructor(properties).newInstance(built);
	}

	/** An instance of a mod fluid class, without the registry its constructor would join off-game. */
	private Object fluid(String className, Object type) throws Exception {
		Class<?> fluidClass = loader.loadClass(className);
		Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		Object fluid = ((sun.misc.Unsafe) theUnsafe.get(null)).allocateInstance(fluidClass);
		if (type != null) fluidClass.getField("type").set(fluid, type);
		return fluid;
	}

	private Object own(Object fluid) throws Exception {
		Method own = runtime.getDeclaredMethod("of", loader.loadClass("net.minecraft.world.level.material.Fluid"));
		own.setAccessible(true);
		return own.invoke(null, fluid);
	}

	@SuppressWarnings("unchecked")
	private boolean ownsForgeType(String className) throws Exception {
		Field field = runtime.getDeclaredField("OWNS_FORGE_TYPE");
		field.setAccessible(true);
		return ((ClassValue<Boolean>) field.get(null)).get(loader.loadClass(className));
	}

	/** NeoForge's view of a MinecraftForge type, as KernelForgeFluidTypeViews.of makes it for a type that is not one of Forge's three. */
	private Object view(Object forgeType) throws Exception {
		var constructor = loader.loadClass("net.forbric.kernel.runtime.KernelForgeFluidTypeViews$View")
				.getDeclaredConstructor(loader.loadClass("net.minecraftforge.fluids.FluidType"));
		constructor.setAccessible(true);
		return constructor.newInstance(forgeType);
	}

	/** Asked through NeoForge's public FluidType, as the game asks it: the kernel's view class is package-private. */
	private Object ask(Object type, String name, Class<?> parameter) throws Exception {
		Class<?> api = loader.loadClass("net.neoforged.neoforge.fluids.FluidType");
		return parameter == null ? api.getMethod(name).invoke(type) : api.getMethod(name, parameter).invoke(type, (Object) null);
	}

	/**
	 * The view answers NeoForge's questions with the MinecraftForge type's own answers. The kernel's answer before
	 * (and with the switch off) was NeoForge's empty type, air: nothing drowned in murk.
	 */
	@Test void neoForgesViewOfAMinecraftForgeTypeAnswersWithThatType() throws Exception {
		load();
		Object murk = view(forgeType("murk", true));
		assertEquals("net.neoforged.neoforge.fluids.FluidType", murk.getClass().getSuperclass().getName());
		assertEquals(true, ask(murk, "canDrownIn", living), "a body drowns in murk, as its MinecraftForge type says");
		assertEquals(true, ask(murk, "canSwim", entity));
		assertEquals(0.007, ask(murk, "motionScale", entity));
		assertEquals(3000, ask(murk, "getDensity", null));
		assertEquals("block.example.murk", ask(murk, "getDescriptionId", null));
		Object mist = view(forgeType("mist", false));
		assertEquals(false, ask(mist, "canDrownIn", living), "nothing drowns in mist, as its type says");
	}

	/** A fluid has its own MinecraftForge type exactly when its class overrides MinecraftForge's getter. */
	@Test void aFluidOwnsATypeWhenItsClassGivesOne() throws Exception {
		load();
		assertTrue(ownsForgeType("example.forge.TypedFluid"));
		assertFalse(ownsForgeType("example.forge.UntypedFluid"), "Fluid's own fallback is not the fluid's type");
		assertFalse(ownsForgeType("net.minecraft.world.level.material.EmptyFluid"));
		assertNull(own(fluid("example.forge.UntypedFluid", null)), "no override of MinecraftForge's getter: the tags decide");
	}

	/** Negative control: switched off, a MinecraftForge fluid is left to its tags, as before. */
	@Test void switchedOffTheTagsDecide() throws Exception {
		load();
		System.setProperty("forbric.forgeFluidOwnType", "off");
		assertNull(own(fluid("example.forge.TypedFluid", forgeType("murk", true))));
	}

	private static List<Float> rgba(Object vector) throws Exception {
		List<Float> out = new java.util.ArrayList<>();
		for (String c : new String[] {"x", "y", "z", "w"}) out.add(vector.getClass().getField(c).getFloat(vector));
		return out;
	}

	/**
	 * NeoForge's view of {@code forgeType} with {@code fluid} seen with it, as {@code KernelForgeFluidTypeViews.of} notes a
	 * fluid (off-game {@code of} itself cannot run past MinecraftForge's own {@code ForgeMod}, which cannot initialise).
	 */
	private Object viewOf(Object forgeType, Object fluid) throws Exception {
		Object view = view(forgeType);
		Method note = view.getClass().getDeclaredMethod("note", loader.loadClass("net.minecraft.world.level.material.Fluid"));
		note.setAccessible(true);
		note.invoke(view, fluid);
		return view;
	}

	/** A MinecraftForge type built as a mod builds one, unswimmable: "wet" in the probe. */
	private Object unswimmableType() throws Exception {
		Class<?> properties = loader.loadClass("net.minecraftforge.fluids.FluidType$Properties");
		Object built = properties.getMethod("create").invoke(null);
		built = properties.getMethod("descriptionId", String.class).invoke(built, "block.example.wet");
		built = properties.getMethod("canSwim", boolean.class).invoke(built, false);
		return loader.loadClass("net.minecraftforge.fluids.FluidType").getConstructor(properties).newInstance(built);
	}

	/**
	 * NeoForge's entity code asks {@code isWaterLike} where vanilla and MinecraftForge ask {@code minecraft:water}; the view
	 * of a MinecraftForge fluid in that tag is water-like, one outside it is not. Before, every view answered false, so a
	 * MinecraftForge fluid in {@code minecraft:water} was not water to NeoForge's entity code (the probe: not in water,
	 * not under water, no fall reset, no swimming).
	 */
	@Test void aViewOfAFluidInMinecraftWaterIsWaterLike() throws Exception {
		load();
		Object wetType = forgeType("wet", false), murkType = forgeType("murk", true);
		Object wet = viewOf(wetType, fluid("example.forge.WaterTaggedFluid", wetType));
		Object murk = viewOf(murkType, fluid("example.forge.DryFluid", murkType));
		assertEquals(true, ask(wet, "getIsWaterLike", null));
		assertEquals(false, ask(murk, "getIsWaterLike", null), "a fluid outside the tag is not water, whatever its type says");
		assertEquals(false, ask(view(forgeType("unseen", true)), "getIsWaterLike", null), "a type no fluid was seen with yet");
	}

	/**
	 * MinecraftForge's game never scales a fall by the fluid it falls through (only lava's factor, in lava), so the view's
	 * factor is 1. Before it was the MinecraftForge type's (0.5 by default, 0 for Biomes O' Plenty's blood): a body that
	 * dies landing through a MinecraftForge fluid on MinecraftForge survived it here.
	 */
	@Test void aViewNeverShortensAFall() throws Exception {
		load();
		Object murk = view(forgeType("murk", true));
		assertEquals(1.0f, ask(murk, "getFallDistanceModifier", entity));
	}

	/**
	 * Without a body under water (off-game no entity can exist: NeoForge's attachment holder cannot initialise), the view
	 * swims as its type says — the tag's clause needs the body; the in-game probe measures that half (a player sprinting
	 * in a MinecraftForge fluid in minecraft:water starts swimming on the same tick as on MinecraftForge).
	 */
	@Test void aViewSwimsAsItsTypeSaysWithoutABodyUnderWater() throws Exception {
		load();
		Object dryType = unswimmableType(), wetType = unswimmableType();
		Method canSwim = loader.loadClass("net.neoforged.neoforge.fluids.FluidType").getMethod("canSwim", entity);
		assertEquals(false, canSwim.invoke(viewOf(wetType, fluid("example.forge.WaterTaggedFluid", wetType)), (Object) null));
		assertEquals(false, canSwim.invoke(viewOf(dryType, fluid("example.forge.DryFluid", dryType)), (Object) null));
		assertEquals(true, canSwim.invoke(view(forgeType("murk", true)), (Object) null), "a swimmable type is swum in");
	}

	/**
	 * The two NeoForge water rules MinecraftForge does not have: a player under water in any type it can swim in, and a
	 * body in such a type, eyes out, in shallow water. For a view they answer MinecraftForge's way (the tag; never), for any
	 * other type NeoForge's {@code canSwimInFluidType}, as merged.
	 */
	@Test void neoForgesOwnWaterRulesAnswerAViewAsMinecraftForgeDoes() throws Exception {
		load();
		Class<?> neoType = loader.loadClass("net.neoforged.neoforge.fluids.FluidType");
		Method underwater = runtime.getMethod("underwaterIn", entity, neoType), shallow = runtime.getMethod("shallowIn", entity, neoType);
		// A view is answered from the view alone; any other type goes to the body's canSwimInFluidType (in game).
		Object bloodType = forgeType("blood", true), wetType = unswimmableType();
		Object swimmableUntagged = viewOf(bloodType, fluid("example.forge.DryFluid", bloodType));
		assertEquals(false, underwater.invoke(null, null, swimmableUntagged), "swimmable, outside minecraft:water: not under water on MinecraftForge");
		assertEquals(false, shallow.invoke(null, null, swimmableUntagged), "nor in shallow water");
		Object unswimmableTagged = viewOf(wetType, fluid("example.forge.WaterTaggedFluid", wetType));
		assertEquals(true, underwater.invoke(null, null, unswimmableTagged), "in minecraft:water: under water on MinecraftForge");
		assertEquals(false, shallow.invoke(null, null, unswimmableTagged));
	}

	/**
	 * NeoForge's client extensions of a view answer the fog colour with the MinecraftForge type's own. The merged client
	 * asks NeoForge alone for it, so before, a MinecraftForge fluid's fog kept the sky's colour (the client probe: grey-blue
	 * inside Biomes O' Plenty's blood, dark red on native MinecraftForge). Anything else is NeoForge's default.
	 */
	@Test void aViewsFogColourIsItsMinecraftForgeTypes() throws Exception {
		load();
		Class<?> client = loader.loadClass("net.forbric.kernel.runtime.KernelForgeFluidClientViews");
		Method extensions = client.getMethod("neoExtensions", Object.class);
		Object blood = view(loader.loadClass("example.forge.FoggyType").getConstructor().newInstance());
		Object neo = extensions.invoke(null, blood);
		assertNotNull(neo, "a view has extensions of its own");
		assertSame(neo, extensions.invoke(null, blood), "made once per view");
		Class<?> vector = loader.loadClass("org.joml.Vector4f");
		Object colour = vector.getConstructor(float.class, float.class, float.class, float.class).newInstance(0.5f, 0.6f, 0.7f, 1f);
		Class<?> api = loader.loadClass("net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions");
		api.getMethod("modifyFogColor", loader.loadClass("net.minecraft.client.Camera"), float.class,
				loader.loadClass("net.minecraft.client.multiplayer.ClientLevel"), int.class, float.class, vector)
				.invoke(neo, null, 0f, null, 8, 0f, colour);
		assertEquals(List.of(0.407f, 0.121f, 0.137f, 1f), rgba(colour), "the mod's fog colour, NeoForge's alpha");
		Object plain = extensions.invoke(null, view(forgeType("murk", true)));
		Object untouched = vector.getConstructor(float.class, float.class, float.class, float.class).newInstance(0.5f, 0.6f, 0.7f, 1f);
		api.getMethod("modifyFogColor", loader.loadClass("net.minecraft.client.Camera"), float.class,
				loader.loadClass("net.minecraft.client.multiplayer.ClientLevel"), int.class, float.class, vector)
				.invoke(plain, null, 0f, null, 8, 0f, untouched);
		assertEquals(List.of(0.5f, 0.6f, 0.7f, 1f), rgba(untouched), "a type with no client extensions changes nothing");
		assertNull(extensions.invoke(null, (Object) null), "any other type: NeoForge decides");
	}

	/** A type its getter cannot give yet (its registry entry is unfilled) is not pinned: the tags answer, uncached. */
	@Test void aTypeNotReadableYetLeavesItToTheTagsForNow() throws Exception {
		load();
		Object murk = fluid("example.forge.TypedFluid", null);
		assertNull(own(murk), "the getter throws before MinecraftForge's fluid types are registered");
		assertNull(own(murk), "and again: nothing is pinned");
	}
}
