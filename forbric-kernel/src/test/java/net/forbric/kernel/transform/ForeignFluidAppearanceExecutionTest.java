/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * A fluid MinecraftForge did not type keeps its own appearance on the merged fluid renderer.
 *
 * <p>The merged {@code FluidRenderer.tesselate} asks a fluid's MinecraftForge client extensions for its model and, where
 * the model has no tint source, for its tint ({@code KernelForgeFluids}). A Fabric or NeoForge mod's fluid has no
 * MinecraftForge type: {@link ForeignFluidTypeInjector} gives it one from its tags, for entity physics — water's, for a
 * fluid in {@code minecraft:water}. Asking that stand-in's client extensions multiplied water's tint into a fluid whose
 * own platform draws it untinted (Create's honey turned green, its chocolate blue).
 *
 * <p>Run here over stand-ins of the game's classes with unrelated names, through the REAL kernel classes: the injector's
 * repair of {@code Fluid.getFluidType()} and {@code ForgeHooks.getVanillaFluidType}, and the compiled game-side
 * {@code KernelFluidTypes} and {@code KernelForgeFluids}. Syrup is a fluid of another platform in the water tag; tar
 * gives its own MinecraftForge type; water is vanilla's, which MinecraftForge types itself.
 */
@ExecutesInjector(ForeignFluidTypeInjector.class)
@ResourceLock("system-properties")
class ForeignFluidAppearanceExecutionTest {
	private static final String FORGE_FLUIDS = "net.forbric.kernel.runtime.KernelForgeFluids";
	private static final String FLUID_TYPES = "net.forbric.kernel.runtime.KernelFluidTypes";
	private static final int WATER_TINT = 0xFF3F76E4, TAR_TINT = 0xFF102030;

	private static final Map<String, String> STAND_INS = Map.ofEntries(
			Map.entry("net.minecraftforge.fluids.FluidType", """
					package net.minecraftforge.fluids;

					import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

					public class FluidType {
						public final String name;
						public final IClientFluidTypeExtensions client;

						public FluidType(String name, IClientFluidTypeExtensions client) {
							this.name = name;
							this.client = client;
						}
					}
					"""),
			// Named by KernelFluidTypes' NeoForge-side signatures; never constructed here.
			Map.entry("net.neoforged.neoforge.fluids.FluidType", """
					package net.neoforged.neoforge.fluids;

					public class FluidType { }
					"""),
			Map.entry("net.minecraftforge.registries.RegistryObject", """
					package net.minecraftforge.registries;

					public final class RegistryObject<T> {
						private final T value;

						public RegistryObject(T value) {
							this.value = value;
						}

						public T get() {
							return value;
						}
					}
					"""),
			Map.entry("net.minecraftforge.common.ForgeMod", """
					package net.minecraftforge.common;

					import net.minecraft.client.renderer.block.FluidModel;
					import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
					import net.minecraftforge.fluids.FluidType;
					import net.minecraftforge.registries.RegistryObject;

					public final class ForgeMod {
						/** Water's own client extensions: a tint and a model of its own, both water's. */
						public static final RegistryObject<FluidType> WATER_TYPE = new RegistryObject<>(new FluidType("water",
								new IClientFluidTypeExtensions() {
									@Override public int getTintColor() { return 0xFF3F76E4; }
									@Override public FluidModel getModel(net.minecraft.world.level.material.FluidState state,
											net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
											FluidModel model) { return new FluidModel("water's own"); }
								}));
						public static final RegistryObject<FluidType> LAVA_TYPE = new RegistryObject<>(new FluidType("lava", IClientFluidTypeExtensions.DEFAULT));
						public static final RegistryObject<FluidType> EMPTY_TYPE = new RegistryObject<>(new FluidType("empty", IClientFluidTypeExtensions.DEFAULT));
					}
					"""),
			Map.entry("net.minecraftforge.common.ForgeHooks", """
					package net.minecraftforge.common;

					import net.minecraft.world.level.material.Fluid;
					import net.minecraftforge.fluids.FluidType;

					public class ForgeHooks {
						public static FluidType getVanillaFluidType(Fluid fluid) {
							if (fluid.id.equals("minecraft:water")) return ForgeMod.WATER_TYPE.get();
							if (fluid.id.equals("minecraft:lava")) return ForgeMod.LAVA_TYPE.get();
							throw new RuntimeException("Mod fluids must override getFluidType.");
						}
					}
					"""),
			Map.entry("net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions", """
					package net.minecraftforge.client.extensions.common;

					import net.minecraft.client.renderer.block.BlockAndTintGetter;
					import net.minecraft.client.renderer.block.FluidModel;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.level.material.FluidState;
					import net.minecraftforge.fluids.FluidType;

					public interface IClientFluidTypeExtensions {
						IClientFluidTypeExtensions DEFAULT = new IClientFluidTypeExtensions() { };

						static IClientFluidTypeExtensions of(FluidState state) {
							return of(state.getType().getFluidType());
						}

						static IClientFluidTypeExtensions of(FluidType type) {
							return type.client;
						}

						default int getTintColor() {
							return 0xFFFFFFFF;
						}

						default FluidModel getModel(FluidState state, BlockAndTintGetter level, BlockPos pos, FluidModel model) {
							return model;
						}
					}
					"""),
			Map.entry("net.minecraft.client.renderer.block.FluidModel", """
					package net.minecraft.client.renderer.block;

					public record FluidModel(String name) { }
					"""),
			Map.entry("net.minecraft.client.renderer.block.BlockAndTintGetter", """
					package net.minecraft.client.renderer.block;

					public interface BlockAndTintGetter { }
					"""),
			Map.entry("net.minecraft.core.BlockPos", """
					package net.minecraft.core;

					public class BlockPos { }
					"""),
			Map.entry("net.minecraft.tags.TagKey", """
					package net.minecraft.tags;

					public record TagKey<T>(String name) { }
					"""),
			Map.entry("net.minecraft.tags.FluidTags", """
					package net.minecraft.tags;

					import net.minecraft.world.level.material.Fluid;

					public final class FluidTags {
						public static final TagKey<Fluid> WATER = new TagKey<>("minecraft:water");
						public static final TagKey<Fluid> LAVA = new TagKey<>("minecraft:lava");
					}
					"""),
			Map.entry("net.minecraft.world.level.material.FluidState", """
					package net.minecraft.world.level.material;

					import net.minecraft.tags.TagKey;

					public final class FluidState {
						private final Fluid fluid;

						public FluidState(Fluid fluid) {
							this.fluid = fluid;
						}

						public Fluid getType() {
							return fluid;
						}

						public boolean is(TagKey<Fluid> tag) {
							return tag.name().equals(fluid.tag);
						}
					}
					"""),
			Map.entry("net.minecraft.world.level.material.Fluid", """
					package net.minecraft.world.level.material;

					import net.minecraftforge.common.ForgeHooks;
					import net.minecraftforge.fluids.FluidType;

					/** MinecraftForge's cached getter, as the merged Fluid carries it. */
					public class Fluid {
						public final String id;
						public final String tag;
						private FluidType forgeFluidType;

						public Fluid(String id, String tag) {
							this.id = id;
							this.tag = tag;
						}

						public FluidState defaultFluidState() {
							return new FluidState(this);
						}

						public FluidType getFluidType() {
							if (forgeFluidType == null) forgeFluidType = ForgeHooks.getVanillaFluidType(this);
							return forgeFluidType;
						}
					}
					"""),
			Map.entry("fixture.SyrupFluid", """
					package fixture;

					import net.minecraft.world.level.material.Fluid;

					/** Another platform's fluid in the water tag: no MinecraftForge type of its own. */
					public class SyrupFluid extends Fluid {
						public SyrupFluid() {
							super("syrupworks:maple_syrup", "minecraft:water");
						}
					}
					"""),
			Map.entry("fixture.TarFluid", """
					package fixture;

					import net.minecraft.client.renderer.block.FluidModel;
					import net.minecraft.world.level.material.Fluid;
					import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
					import net.minecraftforge.fluids.FluidType;

					/** A MinecraftForge mod's fluid, also in the water tag, giving its own type and client extensions. */
					public class TarFluid extends Fluid {
						private static final FluidType OWN = new FluidType("tar", new IClientFluidTypeExtensions() {
							@Override public int getTintColor() { return 0xFF102030; }
							@Override public FluidModel getModel(net.minecraft.world.level.material.FluidState state,
									net.minecraft.client.renderer.block.BlockAndTintGetter level, net.minecraft.core.BlockPos pos,
									FluidModel model) { return new FluidModel("tar's own"); }
						});

						public TarFluid() {
							super("tarpits:tar", "minecraft:water");
						}

						@Override
						public FluidType getFluidType() {
							return OWN;
						}
					}
					"""));

	@AfterEach void reset() {
		System.clearProperty("forbric.forgeFluidModels");
	}

	/** The stand-ins, the injector's repairs of Fluid and ForgeHooks, and the compiled game-side helpers, all child-first. */
	private static ClassLoader game(Path work) throws Throwable {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, STAND_INS));
		String fluid = ForeignFluidTypeInjector.FLUID_INTERNAL, hooks = ForeignFluidTypeInjector.FORGE_HOOKS;
		byte[] repairedFluid = InjectorExecution.transform(new ForeignFluidTypeInjector(), fluid.replace('/', '.'), classes.get(fluid), EnvType.CLIENT);
		byte[] repairedHooks = InjectorExecution.transform(new ForeignFluidTypeInjector(), hooks.replace('/', '.'), classes.get(hooks), EnvType.CLIENT);
		assertNotSame(classes.get(fluid), repairedFluid, "premise: the injector repaired MinecraftForge's cached getter");
		assertNotSame(classes.get(hooks), repairedHooks, "premise: and MinecraftForge's unsupported branch");
		classes.put(fluid, repairedFluid);
		classes.put(hooks, repairedHooks);
		for (String helper : new String[] {FORGE_FLUIDS, FLUID_TYPES}) classes.put(helper.replace('.', '/'), compiled(helper));
		return InjectorExecution.load(classes);
	}

	/** The game-side class as compiled against the staged game (absent where that game is: the test skips). */
	private static byte[] compiled(String binaryName) throws Exception {
		Path classes = Path.of(System.getProperty("forbric.test.runtimeClasses",
				Path.of(System.getProperty("user.dir"), "build", "classes", "java", "runtime").toString()));
		Path file = classes.resolve(binaryName.replace('.', '/') + ".class");
		TestFixtures.require(Fixture.GAME_SIDE, Files.isRegularFile(file), "the compiled game side is absent");
		return Files.readAllBytes(file);
	}

	private static Object state(ClassLoader loader, Object fluid) throws Throwable {
		return InjectorExecution.invoke(fluid, "defaultFluidState");
	}

	private static int tint(ClassLoader loader, Object state) throws Throwable {
		return (Integer) InjectorExecution.invokeStatic(loader.loadClass(FORGE_FLUIDS), "tintColor", state);
	}

	private static String model(ClassLoader loader, Object state) throws Throwable {
		Object chosen = InjectorExecution.construct(loader.loadClass("net.minecraft.client.renderer.block.FluidModel"), "registered");
		Object level = java.lang.reflect.Proxy.newProxyInstance(loader,
				new Class<?>[] {loader.loadClass("net.minecraft.client.renderer.block.BlockAndTintGetter")}, (p, m, a) -> null);
		Object pos = InjectorExecution.construct(loader.loadClass("net.minecraft.core.BlockPos"));
		Object answer = InjectorExecution.invokeStatic(loader.loadClass(FORGE_FLUIDS), "model", chosen, state, level, pos);
		return (String) InjectorExecution.invoke(answer, "name");
	}

	private static Object extensions(ClassLoader loader, Object state) throws Throwable {
		return InjectorExecution.invokeStatic(loader.loadClass("net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions"), "of", state);
	}

	@Test void aFluidMinecraftForgeDidNotTypeKeepsItsOwnModelAndDrawsUntinted(@TempDir Path work) throws Throwable {
		ClassLoader loader = game(work);
		Object syrup = state(loader, InjectorExecution.construct(loader.loadClass("fixture.SyrupFluid")));

		// Premise: entity physics treat syrup as water — its MinecraftForge type is water's, extensions and all.
		assertEquals(WATER_TINT, (Integer) InjectorExecution.invoke(extensions(loader, syrup), "getTintColor"),
				"premise: the stand-in's extensions are water's, which is what drew the fluid water-tinted");
		assertTrue((Boolean) InjectorExecution.invokeStatic(loader.loadClass(FLUID_TYPES), "forgeTypeIsStandIn",
				InjectorExecution.invoke(syrup, "getType")), "the fallback recorded the stand-in");

		assertEquals(-1, tint(loader, syrup), "a model without a tint source draws untinted, as on syrup's own platform");
		assertEquals("registered", model(loader, syrup), "the model its own registration chose stays");
	}

	@Test void aFluidThatGivesItsOwnMinecraftForgeTypeIsStillAsked(@TempDir Path work) throws Throwable {
		ClassLoader loader = game(work);
		Object tar = state(loader, InjectorExecution.construct(loader.loadClass("fixture.TarFluid")));
		assertEquals(TAR_TINT, tint(loader, tar), "its own extensions' tint, as genuine MinecraftForge asks it");
		assertEquals("tar's own", model(loader, tar));
		assertFalse((Boolean) InjectorExecution.invokeStatic(loader.loadClass(FLUID_TYPES), "forgeTypeIsStandIn",
				InjectorExecution.invoke(tar, "getType")));
	}

	@Test void vanillaWaterIsTypedByMinecraftForgeItselfAndStillAsked(@TempDir Path work) throws Throwable {
		ClassLoader loader = game(work);
		Object water = state(loader, InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.material.Fluid"),
				"minecraft:water", "minecraft:water"));
		assertEquals(WATER_TINT, tint(loader, water), "MinecraftForge types vanilla water itself: its extensions are asked, as natively");
		assertEquals("water's own", model(loader, water));
	}

	@Test void theSwitchStillAnswersVanillasMinusOne(@TempDir Path work) throws Throwable {
		ClassLoader loader = game(work);
		Object tar = state(loader, InjectorExecution.construct(loader.loadClass("fixture.TarFluid")));
		System.setProperty("forbric.forgeFluidModels", "off");
		assertEquals(-1, tint(loader, tar));
		assertEquals("registered", model(loader, tar));
	}
}
