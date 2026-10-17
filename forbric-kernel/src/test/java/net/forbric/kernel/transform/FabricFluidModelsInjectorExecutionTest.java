/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link FabricFluidModelsInjector}'s output, run: NeoForge's "Missing FluidModel" check no longer reports a fluid
 * fabric-rendering-fluids gives a model (Traveler's Backpack's potion fluids, every client boot), still reports a real
 * miss, and is NeoForge's own check when fabric-rendering-fluids is not installed.
 *
 * <p>The hook is the kernel's real {@code KernelFabricFluidModels}, compiled from {@code src/runtime/java}; it finds
 * fabric-rendering-fluids' registry through its own loader, where a stand-in under that name holds Fabric's models.
 * {@code FabricFluidModelsInjectorTest} runs the same hook from the compiled game side, which only exists where the game
 * is staged.
 */
@ExecutesInjector(FabricFluidModelsInjector.class)
class FabricFluidModelsInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFabricFluidModels.java");
	private static final String HOOKS = FabricFluidModelsInjector.HOOKS;

	private static final Map<String, String> STAND_INS = Map.of(
			HOOKS, """
					package net.neoforged.neoforge.client;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.Map;

					public class ClientHooks {
						public static final List<String> warnings = new ArrayList<>();

						static final class Logger {
							void warn(String message, Object fluid) {
								warnings.add(message.replace("{}", String.valueOf(fluid)));
							}
						}

						private static final Logger LOGGER = new Logger();

						public static Map<Object, Object> gatherFluidModels(Map<Object, Object> models, List<Object> fluids) {
							for (Object fluid : fluids) {
								if (!models.containsKey(fluid)) LOGGER.warn("Missing FluidModel for fluid '{}'", fluid);
							}
							return models;
						}
					}
					""");

	/** A build of fabric-rendering-fluids: its public API, and where it keeps models under names of no loader's. */
	private static final Map<String, String> FABRIC_RENDERING_FLUIDS = Map.of(
			"net.minecraft.world.level.material.Fluid", "package net.minecraft.world.level.material; public class Fluid { }",
			"net.minecraft.client.renderer.block.FluidModel", "package net.minecraft.client.renderer.block; public class FluidModel { public interface Unbaked { } }",
			"net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry", """
					package net.fabricmc.fabric.api.client.render.fluid.v1;

					import net.minecraft.client.renderer.block.FluidModel;
					import net.minecraft.world.level.material.Fluid;

					public final class FluidRenderingRegistry {
						public static void register(Fluid fluid, FluidModel.Unbaked model) {
							org.quarry.render.Shapes.add(fluid, model);
						}
					}
					""",
			"org.quarry.render.Shapes", """
					package org.quarry.render;

					import java.util.HashMap;
					import java.util.Map;
					import net.minecraft.client.renderer.block.FluidModel;
					import net.minecraft.world.level.material.Fluid;

					public final class Shapes {
						private static final Map<Object, Object> SHAPES = new HashMap<>(Map.of("travelersbackpack:potion_still", "fabric model",
								"travelersbackpack:potion_flowing", "fabric model"));

						public static void add(Fluid fluid, FluidModel.Unbaked model) {
							SHAPES.put(fluid, model);
						}
					}
					""");

	private static Map<String, byte[]> compile(Path work, boolean fabricRenderingFluids) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelFabricFluidModels.java", Files.readString(HOOK_SOURCE));
		if (fabricRenderingFluids) sources.putAll(FABRIC_RENDERING_FLUIDS);
		return InjectorExecution.compile(work, sources);
	}

	private static ClassLoader transformed(Map<String, byte[]> original) {
		String internal = HOOKS.replace('.', '/');
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, InjectorExecution.transform(new FabricFluidModelsInjector(), HOOKS, original.get(internal), EnvType.CLIENT));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(internal), loader));
		return loader;
	}

	private static List<?> warnings(ClassLoader loader) throws Throwable {
		Class<?> hooks = loader.loadClass(HOOKS);
		InjectorExecution.invokeStatic(hooks, "gatherFluidModels", new HashMap<>(Map.of("minecraft:water", "neoforge model")),
				List.of("minecraft:water", "travelersbackpack:potion_still", "examplemod:stray"));
		return (List<?>) InjectorExecution.getStatic(hooks, "warnings");
	}

	@Test void aFluidFabricGivesAModelIsNotReportedMissing(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work, true);
		assertEquals(List.of("Missing FluidModel for fluid 'examplemod:stray'"), warnings(transformed(original)),
				"the potion has Fabric's model; only the real miss is reported");
		assertEquals(List.of("Missing FluidModel for fluid 'travelersbackpack:potion_still'",
				"Missing FluidModel for fluid 'examplemod:stray'"), warnings(InjectorExecution.load(original)),
				"premise: as shipped, the potion is reported missing every boot");
	}

	@Test void withoutFabricRenderingFluidsTheCheckIsNeoForgesOwn(@TempDir Path work) throws Throwable {
		assertEquals(List.of("Missing FluidModel for fluid 'travelersbackpack:potion_still'",
				"Missing FluidModel for fluid 'examplemod:stray'"), warnings(transformed(compile(work, false))));
	}

	@Test void aSecondPassIsANoOp(@TempDir Path work) throws Exception {
		byte[] original = compile(work, false).get(HOOKS.replace('.', '/'));
		byte[] once = InjectorExecution.transform(new FabricFluidModelsInjector(), HOOKS, original, EnvType.CLIENT);
		assertSame(once, InjectorExecution.transform(new FabricFluidModelsInjector(), HOOKS, once, EnvType.CLIENT));
	}
}
