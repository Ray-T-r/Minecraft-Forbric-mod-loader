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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;

import net.fabricmc.api.EnvType;

/**
 * {@link ForeignFluidTypeInjector}'s output, run: a Fabric mod's fluid answers both families' {@code getFluidType()}
 * from its fluid tags, where as merged both throw "Mod fluids must override getFluidType." and the first entity to touch
 * it stops the server. A vanilla fluid keeps NeoForge's cached lookup, and a MinecraftForge mod's fluid keeps its own
 * override.
 *
 * <p>The merged {@code Fluid} has two {@code getFluidType()}s that differ only in return type, which Java source cannot
 * declare, so NeoForge's is written as {@code getNeoFluidType} and renamed in the class files. {@code CommonHooks} and
 * the game-side {@code KernelFluidTypes} are stand-ins under their names; the kernel's answers from a fluid's
 * {@code tag}, which the test rebinds to show the answer is not cached.
 */
@ExecutesInjector(ForeignFluidTypeInjector.class)
@ResourceLock("system-properties")
class ForeignFluidTypeInjectorExecutionTest {
	private static final String FLUID = ForeignFluidTypeInjector.FLUID;
	private static final String NEO_TYPE = ForeignFluidTypeInjector.TYPE.replace('/', '.');
	private static final String FORGE_TYPE = ForeignFluidTypeInjector.FORGE_TYPE.replace('/', '.');

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			NEO_TYPE, """
					package net.neoforged.neoforge.fluids;

					public record FluidType(String name) {
						public static final FluidType EMPTY = new FluidType("empty");
						public static final FluidType WATER = new FluidType("water");
						public static final FluidType LAVA = new FluidType("lava");
					}
					""",
			FORGE_TYPE, """
					package net.minecraftforge.fluids;

					public record FluidType(String name) {
					}
					""",
			"net.minecraftforge.common.extensions.IForgeFluid", """
					package net.minecraftforge.common.extensions;

					import net.minecraftforge.fluids.FluidType;

					public interface IForgeFluid {
						default FluidType getFluidType() {
							throw new RuntimeException("Mod fluids must override getFluidType.");
						}
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

						public static net.minecraftforge.fluids.FluidType forgeType(Fluid fluid) {
							return new net.minecraftforge.fluids.FluidType("forge:" + fluid.tag);
						}
					}
					""",
			FLUID, """
					package net.minecraft.world.level.material;

					import net.minecraftforge.common.extensions.IForgeFluid;
					import net.neoforged.neoforge.common.CommonHooks;
					import net.neoforged.neoforge.fluids.FluidType;

					public class Fluid implements IForgeFluid {
						public final String id;
						public String tag;
						private FluidType forgeFluidType;

						public Fluid(String id, String tag) {
							this.id = id;
							this.tag = tag;
						}

						/** NeoForge's getFluidType(), renamed in the class file. */
						public FluidType getNeoFluidType() {
							if (forgeFluidType == null) forgeFluidType = CommonHooks.getVanillaFluidType(this);
							return forgeFluidType;
						}
					}
					""",
			"fixture.ForgeOil", """
					package fixture;

					import net.minecraft.world.level.material.Fluid;
					import net.minecraftforge.fluids.FluidType;

					public class ForgeOil extends Fluid {
						public ForgeOil() {
							super("forgemod:oil", "c:oil");
						}

						@Override
						public FluidType getFluidType() {
							return new FluidType("forgemod:own");
						}
					}
					""",
			"fixture.Probe", """
					package fixture;

					import net.minecraft.world.level.material.Fluid;
					import net.minecraftforge.common.extensions.IForgeFluid;

					/** An entity touching a fluid asks each family's type the way that family's code does. */
					public class Probe {
						public static Object neoForge(Fluid fluid) {
							return fluid.getNeoFluidType();
						}

						public static Object minecraftForge(IForgeFluid fluid) {
							return fluid.getFluidType();
						}
					}
					"""));
    static {
        STAND_INS.put("net.minecraftforge.common.ForgeHooks","""
         package net.minecraftforge.common;public class ForgeHooks {
          public static int lookups;
          public static net.minecraftforge.fluids.FluidType getVanillaFluidType(net.minecraft.world.level.material.Fluid fluid){
           lookups++;if(fluid.id.equals("minecraft:water"))return new net.minecraftforge.fluids.FluidType("builtin-water");
           if(fluid.id.equals("minecraft:lava"))return new net.minecraftforge.fluids.FluidType("builtin-lava");
           if(fluid.id.equals("sdk:milk"))return new net.minecraftforge.fluids.FluidType("builtin-milk");
           if(fluid.id.equals("sdk:broken"))throw new IllegalStateException("provider broke");
           throw new RuntimeException("an arbitrary unsupported message");}}
         """);
        String hook=STAND_INS.get("net.forbric.kernel.runtime.KernelFluidTypes");
        hook=hook.replace("if (fluid.id.startsWith(\"minecraft:\")) return null;", "");
        hook=hook.replace("public static FluidType foreignType(Fluid fluid)","public static FluidType foreignNeoLookup(Fluid fluid)")
            .replace("return switch (fluid.tag)","return (FluidType)net.forbric.api.LookupOutcomes.foreign(fluid,FluidType.class,switch (fluid.tag)").replace("};", "});");
        hook=hook.replace("return new net.minecraftforge.fluids.FluidType(\"forge:\" + fluid.tag);", "return (net.minecraftforge.fluids.FluidType)net.forbric.api.LookupOutcomes.foreign(fluid,net.minecraftforge.fluids.FluidType.class,new net.minecraftforge.fluids.FluidType(\"forge:\"+fluid.tag));");
        hook=hook.replace("forgeType(Fluid fluid)","foreignForgeLookup(Fluid fluid)");STAND_INS.put("net.forbric.kernel.runtime.KernelFluidTypes",hook);
        String fluid=STAND_INS.get(FLUID);fluid=fluid.replace("private FluidType forgeFluidType;", "private FluidType forgeFluidType; private net.minecraftforge.fluids.FluidType forgeCached;");
        fluid=fluid.replace("public FluidType getNeoFluidType()", "public net.minecraftforge.fluids.FluidType getFluidType(){if(forgeCached==null)forgeCached=net.minecraftforge.common.ForgeHooks.getVanillaFluidType(this);return forgeCached;} public FluidType getNeoFluidType()");STAND_INS.put(FLUID,fluid);
    }

	@AfterEach void reset() {
		System.clearProperty(ForeignFluidTypeInjector.PROPERTY);
	}

	/** The merged shape: NeoForge's getter under the name it shares with MinecraftForge's. */
	private static Map<String, byte[]> merged(Path work) throws Exception {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, STAND_INS));
		SimpleRemapper rename = new SimpleRemapper(Map.of(
				ForeignFluidTypeInjector.FLUID_INTERNAL + ".getNeoFluidType()L" + ForeignFluidTypeInjector.TYPE + ";", "getFluidType"));
		for (String name : new String[] {ForeignFluidTypeInjector.FLUID_INTERNAL, "fixture/Probe"}) {
			ClassWriter writer = new ClassWriter(0);
			new ClassReader(classes.get(name)).accept(new ClassRemapper(writer, rename), 0);
			classes.put(name, writer.toByteArray());
		}
		return classes;
	}

	private static Object neoForge(ClassLoader loader, Object fluid) throws Throwable {
		return InjectorExecution.invokeStatic(loader.loadClass("fixture.Probe"), "neoForge", fluid);
	}

	private static Object minecraftForge(ClassLoader loader, Object fluid) throws Throwable {
		return InjectorExecution.invokeStatic(loader.loadClass("fixture.Probe"), "minecraftForge", fluid);
	}

	private static Object neoType(ClassLoader loader, String constant) throws Throwable {
		return InjectorExecution.getStatic(loader.loadClass(NEO_TYPE), constant);
	}

	@Test void aFabricFluidHasBothFamiliesTypesFromItsTags(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		String internal = ForeignFluidTypeInjector.FLUID_INTERNAL;
		byte[] repaired = InjectorExecution.transform(new ForeignFluidTypeInjector(), FLUID, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), repaired, "the reviewed shape was edited");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, repaired);
        for(String owner:new String[]{ForeignFluidTypeInjector.NEO_HOOKS,ForeignFluidTypeInjector.FORGE_HOOKS})classes.put(owner,InjectorExecution.transform(new ForeignFluidTypeInjector(),owner.replace('/','.'),original.get(owner),EnvType.SERVER));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		Class<?> fluid = loader.loadClass(FLUID);
		Object fabricOil = InjectorExecution.construct(fluid, "fabricmod:oil", "minecraft:water");
		assertSame(neoType(loader, "WATER"), neoForge(loader, fabricOil), "a Fabric fluid in the water tag is water to NeoForge");
		fluid.getField("tag").set(fabricOil, "minecraft:lava");
		assertSame(neoType(loader, "LAVA"), neoForge(loader, fabricOil), "tags rebound on reload: the answer is not cached");
		assertEquals("forge:minecraft:lava", InjectorExecution.invoke(minecraftForge(loader, fabricOil), "name"),
				"MinecraftForge's getFluidType() answers from the tags too, instead of its throwing default");

		Class<?> hooks = loader.loadClass("net.neoforged.neoforge.common.CommonHooks");
		Object water = InjectorExecution.construct(fluid, "minecraft:water", "minecraft:water");
		assertSame(neoType(loader, "WATER"), neoForge(loader, water));
		assertSame(neoType(loader, "WATER"), neoForge(loader, water));
		assertEquals(3, InjectorExecution.getStatic(hooks, "lookups"), "two foreign requests execute their SDK lookup and remain uncached; vanilla executes once then stays cached");

		Object forgeOil = InjectorExecution.construct(loader.loadClass("fixture.ForgeOil"));
		assertEquals("forgemod:own", InjectorExecution.invoke(minecraftForge(loader, forgeOil), "name"),
				"a MinecraftForge fluid's override still wins");

		ClassLoader stock = InjectorExecution.load(original);
		Object mergedOil = InjectorExecution.construct(stock.loadClass(FLUID), "fabricmod:oil", "minecraft:water");
		assertEquals("Mod fluids must override getFluidType.",
				assertThrows(RuntimeException.class, () -> neoForge(stock, mergedOil)).getMessage(),
				"premise: as merged, NeoForge's lookup throws for a Fabric fluid");
		assertEquals("an arbitrary unsupported message",
				assertThrows(RuntimeException.class, () -> minecraftForge(stock, mergedOil)).getMessage(),
				"premise: as merged, MinecraftForge's interface default throws too");
		assertSame(repaired, InjectorExecution.transform(new ForeignFluidTypeInjector(), FLUID, repaired, EnvType.SERVER),
				"a getter that already asks the kernel is left alone");
	}

    @Test void bothPublicGettersLinkAndRunThroughTheActualTransformingClassLoader(@TempDir Path work)throws Throwable {
        Map<String,byte[]> classes=merged(work);Path jar=work.resolve("public-sdk-shape.jar");
        try(var zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(jar))){for(var entry:classes.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()+".class"));zip.write(entry.getValue());zip.closeEntry();}}
        try(var loader=new net.forbric.kernel.classloading.ForbricClassLoader(new java.net.URL[]{jar.toUri().toURL()},ForeignFluidTypeInjectorExecutionTest.class.getClassLoader())){
            loader.setTransformer((name,bytes)->new ForeignFluidTypeInjector().transform(name,bytes,new TransformContext(EnvType.SERVER,false,"named")));
            Class<?> fluid=loader.loadClass(FLUID);assertEquals(2,java.util.Arrays.stream(fluid.getDeclaredMethods()).filter(method->method.getName().equals("getFluidType")).count());
            Object value=InjectorExecution.construct(fluid,"minecraft:unrecognized","minecraft:water");
            assertSame(neoType(loader,"WATER"),neoForge(loader,value),"a namespace never substitutes for the actual SDK unsupported branch");
            assertEquals("forge:minecraft:water",InjectorExecution.invoke(minecraftForge(loader,value),"name"));
            fluid.getField("tag").set(value,"minecraft:lava");assertSame(neoType(loader,"LAVA"),neoForge(loader,value));assertEquals("forge:minecraft:lava",InjectorExecution.invoke(minecraftForge(loader,value),"name"));
            Object milk=InjectorExecution.construct(fluid,"sdk:milk","minecraft:water");Object first=minecraftForge(loader,milk),second=minecraftForge(loader,milk);assertSame(first,second);assertEquals("builtin-milk",InjectorExecution.invoke(first,"name"));
            Object broken=InjectorExecution.construct(fluid,"sdk:broken","minecraft:water");assertEquals("provider broke",assertThrows(IllegalStateException.class,()->minecraftForge(loader,broken)).getMessage(),"another native failure must remain visible");
            assertSame(neoType(loader,"LAVA"),neoForge(loader,value),"a prior native failure does not change a later live lookup");
        }
    }

	@Test void switchedOffFluidIsLeftAsMerged(@TempDir Path work) throws Exception {
		byte[] bytes = merged(work).get(ForeignFluidTypeInjector.FLUID_INTERNAL);
		System.setProperty(ForeignFluidTypeInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new ForeignFluidTypeInjector(), FLUID, bytes, EnvType.SERVER));
	}
}
