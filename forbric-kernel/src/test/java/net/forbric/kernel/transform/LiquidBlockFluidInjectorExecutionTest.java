/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link LiquidBlockFluidInjector}'s output, run: {@code getFluid()} answers for a liquid built by vanilla's
 * constructor (water, lava), which as merged throws, and still answers through the supplier for one built by
 * MinecraftForge's.
 *
 * <p>The stand-in keeps the merged pairing: NeoForge's {@code (FlowingFluid, …)} constructor sets the field, MinecraftForge's
 * {@code (Supplier, …)} one sets the supplier, and the getter is MinecraftForge's {@code supplier.get()}.
 */
@ExecutesInjector(LiquidBlockFluidInjector.class)
@ResourceLock("system-properties")
class LiquidBlockFluidInjectorExecutionTest {
	private static final String FLUID = "net.minecraft.world.level.material.FlowingFluid";

	private static final Map<String, String> STAND_INS = Map.of(
			FLUID, """
					package net.minecraft.world.level.material;

					public class FlowingFluid {
						private final String id;

						public FlowingFluid(String id) {
							this.id = id;
						}

						@Override
						public String toString() {
							return id;
						}
					}
					""",
			LiquidBlockFluidInjector.TARGET, """
					package net.minecraft.world.level.block;

					import java.util.function.Supplier;
					import net.minecraft.world.level.material.FlowingFluid;

					public class LiquidBlock {
						protected final FlowingFluid fluid;
						private final Supplier<? extends FlowingFluid> supplier;

						public LiquidBlock(FlowingFluid fluid, Object properties) {
							this.fluid = fluid;
							this.supplier = null;
						}

						public LiquidBlock(Supplier<? extends FlowingFluid> supplier, Object properties) {
							this.fluid = null;
							this.supplier = supplier;
						}

						public FlowingFluid getFluid() {
							return (FlowingFluid) this.supplier.get();
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(LiquidBlockFluidInjector.PROPERTY);
	}

	private static Object fluid(ClassLoader loader, String id) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(FLUID), id);
	}

	@Test void vanillaWaterAnswersAndASupplierBuiltLiquidStillDoes(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		String internal = LiquidBlockFluidInjector.OWNER;
		java.util.List<String> hits = new java.util.ArrayList<>();
		byte[] repaired = new LiquidBlockFluidInjector().transform(LiquidBlockFluidInjector.TARGET, original.get(internal), null,
				hits::add);
		assertNotSame(original.get(internal), repaired,
				"vanilla's constructor stores null into the supplier: the getter throws for it, so the repair does not stand by");
		assertEquals(java.util.List.of(LiquidBlockFluidInjector.CLAIM), hits);
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		Class<?> liquid = loader.loadClass(LiquidBlockFluidInjector.TARGET);
		Object water = fluid(loader, "minecraft:water");
		assertSame(water, InjectorExecution.invoke(InjectorExecution.construct(liquid, water, new Object()), "getFluid"),
				"vanilla's constructor sets the field, and the getter answers it");
		Object oil = fluid(loader, "mod:oil");
		Supplier<Object> lazy = () -> oil;
		assertSame(oil, InjectorExecution.invoke(InjectorExecution.construct(liquid, lazy, new Object()), "getFluid"),
				"MinecraftForge's constructor still answers through its supplier");

		ClassLoader merged = InjectorExecution.load(original);
		Object block = InjectorExecution.construct(merged.loadClass(LiquidBlockFluidInjector.TARGET),
				fluid(merged, "minecraft:water"), new Object());
		assertThrows(NullPointerException.class, () -> InjectorExecution.invoke(block, "getFluid"),
				"premise: as merged, asking vanilla water for its fluid throws");
		assertSame(repaired, InjectorExecution.transform(new LiquidBlockFluidInjector(), LiquidBlockFluidInjector.TARGET,
				repaired, EnvType.SERVER), "a getter that already reads the field is left alone");
	}

	/**
	 * A base that keeps both families' constructor edits fills the supplier in vanilla's constructor as well: there the
	 * merged getter already answers for water, and the repair stands by — the class untouched, the claim met.
	 */
	@Test void aBaseWhoseConstructorsAllFillTheSupplierNeedsNoRepair(@TempDir Path work) throws Throwable {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put(LiquidBlockFluidInjector.TARGET, STAND_INS.get(LiquidBlockFluidInjector.TARGET)
				.replace("this.supplier = null;", "this.supplier = () -> fluid;"));
		assertNotEquals(STAND_INS.get(LiquidBlockFluidInjector.TARGET), sources.get(LiquidBlockFluidInjector.TARGET));
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		byte[] bytes = original.get(LiquidBlockFluidInjector.OWNER);
		java.util.List<String> hits = new java.util.ArrayList<>();
		assertSame(bytes, new LiquidBlockFluidInjector().transform(LiquidBlockFluidInjector.TARGET, bytes, null, hits::add));
		assertEquals(java.util.List.of(LiquidBlockFluidInjector.CLAIM), hits, "the getter answers as merged, so the claim is met");

		ClassLoader loader = InjectorExecution.load(original);
		Object water = fluid(loader, "minecraft:water");
		assertSame(water, InjectorExecution.invoke(InjectorExecution.construct(loader.loadClass(LiquidBlockFluidInjector.TARGET),
				water, new Object()), "getFluid"), "as merged, vanilla water answers");
	}

	@Test void switchedOffTheGetterIsLeftAsMerged(@TempDir Path work) throws Exception {
		byte[] bytes = InjectorExecution.compile(work, STAND_INS).get(LiquidBlockFluidInjector.OWNER);
		System.setProperty(LiquidBlockFluidInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new LiquidBlockFluidInjector(), LiquidBlockFluidInjector.TARGET, bytes,
				EnvType.SERVER));
	}
}
