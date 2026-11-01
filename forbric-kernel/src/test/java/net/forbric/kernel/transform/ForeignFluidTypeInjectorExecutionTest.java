/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link ForeignFluidTypeInjector}'s output, run: a Fabric mod's fluid answers NeoForge's {@code getFluidType()} from
 * its fluid tags, where on NeoForge's base it throws "Mod fluids must override getFluidType." and the first entity to
 * touch it stops the server. A vanilla fluid keeps NeoForge's cached lookup.
 *
 * <p>{@code Fluid}, NeoForge's {@code FluidType} and {@code CommonHooks}, and the game-side {@code KernelFluidTypes} are
 * stand-ins under their names, with NeoForge's shape; the kernel's answers from a fluid's {@code tag}, which the test
 * rebinds to show the answer is not cached. (The merged base's {@code Fluid} also had MinecraftForge's
 * {@code getFluidType()}, differing only in return type, which this test once modelled; NeoForge's base has one.)
 */
@ExecutesInjector(ForeignFluidTypeInjector.class)
@ResourceLock("system-properties")
class ForeignFluidTypeInjectorExecutionTest {
	private static final String FLUID = ForeignFluidTypeInjector.FLUID;
	private static final String NEO_TYPE = ForeignFluidTypeInjector.TYPE.replace('/', '.');

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			NEO_TYPE, """
					package net.neoforged.neoforge.fluids;

					public record FluidType(String name) {
						public static final FluidType EMPTY = new FluidType("empty");
						public static final FluidType WATER = new FluidType("water");
						public static final FluidType LAVA = new FluidType("lava");
					}
					""",
			"net.neoforged.neoforge.common.CommonHooks", """
					package net.neoforged.neoforge.common;

					import net.minecraft.world.level.material.Fluid;
					import net.neoforged.neoforge.fluids.FluidType;

					public class CommonHooks {
						public static int lookups;

						public static FluidType getVanillaFluidType(Fluid fluid) {
							lookups++;
							if (fluid.id.equals("minecraft:water")) return FluidType.WATER;
							if (fluid.id.equals("minecraft:lava")) return FluidType.LAVA;
							throw new RuntimeException("Mod fluids must override getFluidType.");
						}
					}
					""",
			"net.forbric.kernel.runtime.KernelFluidTypes", """
					package net.forbric.kernel.runtime;

					import net.minecraft.world.level.material.Fluid;
					import net.neoforged.neoforge.fluids.FluidType;

					public final class KernelFluidTypes {
						public static FluidType foreignType(Fluid fluid) {
							if (fluid.id.startsWith("minecraft:")) return null;
							return switch (fluid.tag) {
								case "minecraft:water" -> FluidType.WATER;
								case "minecraft:lava" -> FluidType.LAVA;
								default -> FluidType.EMPTY;
							};
						}
					}
					""",
			FLUID, """
					package net.minecraft.world.level.material;

					import net.neoforged.neoforge.common.CommonHooks;
					import net.neoforged.neoforge.fluids.FluidType;

					public class Fluid {
						public final String id;
						public String tag;
						private FluidType forgeFluidType;

						public Fluid(String id, String tag) {
							this.id = id;
							this.tag = tag;
						}

						/** NeoForge's getFluidType(). */
						public FluidType getFluidType() {
							if (forgeFluidType == null) forgeFluidType = CommonHooks.getVanillaFluidType(this);
							return forgeFluidType;
						}
					}
					""",
			"fixture.Probe", """
					package fixture;

					import net.minecraft.world.level.material.Fluid;

					/** An entity touching a fluid asks its type the way NeoForge's code does. */
					public class Probe {
						public static Object neoForge(Fluid fluid) {
							return fluid.getFluidType();
						}
					}
					"""));
    static {
        String hook=STAND_INS.get("net.forbric.kernel.runtime.KernelFluidTypes");
        hook=hook.replace("if (fluid.id.startsWith(\"minecraft:\")) return null;", "");
        hook=hook.replace("public static FluidType foreignType(Fluid fluid)","public static FluidType foreignNeoLookup(Fluid fluid)")
            .replace("return switch (fluid.tag)","return (FluidType)net.forbric.api.LookupOutcomes.foreign(fluid,FluidType.class,switch (fluid.tag)").replace("};", "});");
        STAND_INS.put("net.forbric.kernel.runtime.KernelFluidTypes",hook);
    }

	@AfterEach void reset() {
		System.clearProperty(ForeignFluidTypeInjector.PROPERTY);
	}

	/** NeoForge's shape, compiled. */
	private static Map<String, byte[]> base(Path work) throws Exception {
		return new HashMap<>(InjectorExecution.compile(work, STAND_INS));
	}

	private static Object neoForge(ClassLoader loader, Object fluid) throws Throwable {
		return InjectorExecution.invokeStatic(loader.loadClass("fixture.Probe"), "neoForge", fluid);
	}

	private static Object neoType(ClassLoader loader, String constant) throws Throwable {
		return InjectorExecution.getStatic(loader.loadClass(NEO_TYPE), constant);
	}

	@Test void aFabricFluidHasNeoForgesTypeFromItsTags(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = base(work);
		String internal = ForeignFluidTypeInjector.FLUID_INTERNAL;
		byte[] repaired = InjectorExecution.transform(new ForeignFluidTypeInjector(), FLUID, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), repaired, "the reviewed shape was edited");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, repaired);
        for(String owner:new String[]{ForeignFluidTypeInjector.NEO_HOOKS})classes.put(owner,InjectorExecution.transform(new ForeignFluidTypeInjector(),owner.replace('/','.'),original.get(owner),EnvType.SERVER));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		Class<?> fluid = loader.loadClass(FLUID);
		Object fabricOil = InjectorExecution.construct(fluid, "fabricmod:oil", "minecraft:water");
		assertSame(neoType(loader, "WATER"), neoForge(loader, fabricOil), "a Fabric fluid in the water tag is water to NeoForge");
		fluid.getField("tag").set(fabricOil, "minecraft:lava");
		assertSame(neoType(loader, "LAVA"), neoForge(loader, fabricOil), "tags rebound on reload: the answer is not cached");

		Class<?> hooks = loader.loadClass("net.neoforged.neoforge.common.CommonHooks");
		Object water = InjectorExecution.construct(fluid, "minecraft:water", "minecraft:water");
		assertSame(neoType(loader, "WATER"), neoForge(loader, water));
		assertSame(neoType(loader, "WATER"), neoForge(loader, water));
		assertEquals(3, InjectorExecution.getStatic(hooks, "lookups"), "two foreign requests execute their SDK lookup and remain uncached; vanilla executes once then stays cached");

		ClassLoader stock = InjectorExecution.load(original);
		Object stockOil = InjectorExecution.construct(stock.loadClass(FLUID), "fabricmod:oil", "minecraft:water");
		assertEquals("Mod fluids must override getFluidType.",
				assertThrows(RuntimeException.class, () -> neoForge(stock, stockOil)).getMessage(),
				"premise: on NeoForge's base, NeoForge's lookup throws for a Fabric fluid");
		assertSame(repaired, InjectorExecution.transform(new ForeignFluidTypeInjector(), FLUID, repaired, EnvType.SERVER),
				"a getter that already asks the kernel is left alone");
	}

    @Test void neoForgesPublicGetterLinksAndRunsThroughTheActualTransformingClassLoader(@TempDir Path work)throws Throwable {
        Map<String,byte[]> classes=base(work);Path jar=work.resolve("public-sdk-shape.jar");
        try(var zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar))){for(var entry:classes.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()+".class"));zip.write(entry.getValue());zip.closeEntry();}}
        try(var loader=new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[]{jar.toUri().toURL()},ForeignFluidTypeInjectorExecutionTest.class.getClassLoader())){
            loader.setTransformer((name,bytes)->new ForeignFluidTypeInjector().transform(name,bytes,new TransformContext(EnvType.SERVER,false,"named")));
            Class<?> fluid=loader.loadClass(FLUID);assertEquals(1,java.util.Arrays.stream(fluid.getDeclaredMethods()).filter(method->method.getName().equals("getFluidType")).count());
            Object value=InjectorExecution.construct(fluid,"minecraft:unrecognized","minecraft:water");
            assertSame(neoType(loader,"WATER"),neoForge(loader,value),"a namespace never substitutes for the actual SDK unsupported branch");
            fluid.getField("tag").set(value,"minecraft:lava");assertSame(neoType(loader,"LAVA"),neoForge(loader,value));
        }
    }

	@Test void switchedOffFluidIsLeftAsCompiled(@TempDir Path work) throws Exception {
		byte[] bytes = base(work).get(ForeignFluidTypeInjector.FLUID_INTERNAL);
		System.setProperty(ForeignFluidTypeInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new ForeignFluidTypeInjector(), FLUID, bytes, EnvType.SERVER));
	}
}
