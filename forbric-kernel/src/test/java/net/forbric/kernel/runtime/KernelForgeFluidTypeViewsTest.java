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
	private static final Path FORGE_PATCHED = Path.of("../forbric-loader/run/forge-patched/patched-mc-forge-26.2.jar");

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
					""");

	@TempDir Path temp;
	private URLClassLoader loader;
	private Class<?> runtime, entity, living;

	private void load() throws Exception {
		Path forgeRuntime = TestFixtures.stagedRoot().resolve("merged-base/forge-runtime-interop.jar");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(FORGE_PATCHED) && Files.isRegularFile(forgeRuntime),
				"MinecraftForge's patched game or its runtime is absent");
		Map<String, byte[]> classes = InjectorExecution.compile(temp.resolve("work"), MOD, List.of(FORGE_PATCHED, forgeRuntime));
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

	/** A type its getter cannot give yet (its registry entry is unfilled) is not pinned: the tags answer, uncached. */
	@Test void aTypeNotReadableYetLeavesItToTheTagsForNow() throws Exception {
		load();
		Object murk = fluid("example.forge.TypedFluid", null);
		assertNull(own(murk), "the getter throws before MinecraftForge's fluid types are registered");
		assertNull(own(murk), "and again: nothing is pinned");
	}
}
