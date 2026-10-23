/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * MinecraftForge's field-to-getter rules ({@link NativeCoremodParity}, {@code forbric.forgeFieldGetters}), run.
 *
 * <p>The stand-ins keep the merged pairing: vanilla's constructor sets the field and a supplier of it, MinecraftForge's
 * {@code (Supplier, …)} constructor stores null in the field and keeps only the supplier, the getter is MinecraftForge's
 * {@code supplier.get()}, and the bodies read the field, as MinecraftForge's do before its transformer rewrites them.
 * Both fields are public, as on the merged base, and a third class (NeoForge's fluid code) reads them from outside.
 * As merged, a MinecraftForge-built liquid throws when placed and its bucket when emptied; routed, both answer, and
 * vanilla's water still does.
 */
@ResourceLock("system-properties")
class ForgeFieldGettersExecutionTest {
	private static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
	private static final String BUCKET = "net/minecraft/world/item/BucketItem";
	private static final String READER = "example/neo/FluidReader";

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.world.level.material.Fluid", """
					package net.minecraft.world.level.material;

					public class Fluid {
						private final String id;

						public Fluid(String id) {
							this.id = id;
						}

						@Override
						public String toString() {
							return id;
						}
					}
					""",
			"net.minecraft.world.level.material.FlowingFluid", """
					package net.minecraft.world.level.material;

					public class FlowingFluid extends Fluid {
						public FlowingFluid(String id) {
							super(id);
						}

						public String getTickDelay(Object level) {
							return "tick " + this;
						}
					}
					""",
			LIQUID.replace('/', '.'), """
					package net.minecraft.world.level.block;

					import java.util.function.Supplier;
					import net.minecraft.world.level.material.FlowingFluid;

					public class LiquidBlock {
						public final FlowingFluid fluid;
						private final Supplier<? extends FlowingFluid> supplier;

						public LiquidBlock(FlowingFluid fluid, Object properties) {
							this.fluid = fluid;
							this.supplier = () -> fluid;
						}

						public LiquidBlock(Supplier<? extends FlowingFluid> supplier, Object properties) {
							this.fluid = null;
							this.supplier = supplier;
						}

						public String onPlace(Object level) {
							return this.fluid.getTickDelay(level);
						}

						public FlowingFluid accessor$fluid() {
							return this.fluid;
						}

						public FlowingFluid getFluid() {
							return (FlowingFluid) this.supplier.get();
						}
					}
					""",
			BUCKET.replace('/', '.'), """
					package net.minecraft.world.item;

					import java.util.function.Supplier;
					import net.minecraft.world.level.material.Fluid;

					public class BucketItem {
						public final Fluid content;
						private final Supplier<? extends Fluid> fluidSupplier;

						public BucketItem(Fluid content, Object properties) {
							this.content = content;
							this.fluidSupplier = () -> content;
						}

						public BucketItem(Supplier<? extends Fluid> supplier, Object properties) {
							this.content = null;
							this.fluidSupplier = supplier;
						}

						public String emptyContents() {
							return "placed " + this.content.toString();
						}

						public Fluid getContent() {
							return this.content;
						}

						public Fluid getFluid() {
							return this.fluidSupplier.get();
						}
					}
					""",
			READER.replace('/', '.'), """
					package example.neo;

					import net.minecraft.world.item.BucketItem;
					import net.minecraft.world.level.block.LiquidBlock;

					public final class FluidReader {
						public static String bucket(BucketItem bucket) {
							return bucket.content.toString();
						}

						public static String liquid(LiquidBlock block) {
							return block.fluid.toString();
						}
					}
					""");

	@AfterEach void reset() {
		for (String key : List.of(NativeCoremodParity.PROPERTY, NativeCoremodParity.FORGE_GETTERS, LiquidBlockFluidInjector.PROPERTY)) {
			System.clearProperty(key);
		}
		NativeCoremodParity.readGameClassesFrom(null);
	}

	/** Every class through NativeCoremodParity, with {@code base} as the game's raw classes; the bytes each came out as. */
	private static Map<String, byte[]> parity(Map<String, byte[]> classes, Map<String, byte[]> base) {
		NativeCoremodParity.readGameClassesFrom(path -> base.get(path.substring(0, path.length() - ".class".length())));
		Map<String, byte[]> out = new HashMap<>();
		classes.forEach((name, bytes) -> out.put(name, NativeCoremodParity.apply(name.replace('/', '.'), bytes)));
		return out;
	}

	private static Object fluid(ClassLoader loader, String id) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.material.FlowingFluid"), id);
	}

	private static Object liquid(ClassLoader loader, Object fluidOrSupplier) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(LIQUID.replace('/', '.')), fluidOrSupplier, new Object());
	}

	private static Object bucket(ClassLoader loader, Object fluidOrSupplier) throws Throwable {
		return InjectorExecution.construct(loader.loadClass(BUCKET.replace('/', '.')), fluidOrSupplier, new Object());
	}

	private static Object read(ClassLoader loader, String what, Object of) throws Throwable {
		return InjectorExecution.invokeStatic(loader.loadClass(READER.replace('/', '.')), what, of);
	}

	@Test void aMinecraftForgeLiquidAndBucketAnswerOnceRoutedAndVanillaWaterStillDoes(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);

		// Premise: as merged, the MinecraftForge-built liquid and bucket read the null field.
		ClassLoader merged = InjectorExecution.load(original);
		Object rawOil = fluid(merged, "mod:oil");
		Supplier<Object> rawLazy = () -> rawOil;
		Object rawLiquid = liquid(merged, rawLazy), rawBucket = bucket(merged, rawLazy);
		assertThrows(NullPointerException.class, () -> InjectorExecution.invoke(rawLiquid, "onPlace", new Object()),
				"premise: as merged, placing a MinecraftForge liquid reads its null fluid");
		assertThrows(NullPointerException.class, () -> InjectorExecution.invoke(rawBucket, "emptyContents"),
				"premise: as merged, emptying a MinecraftForge bucket reads its null content");
		assertThrows(NullPointerException.class, () -> read(merged, "bucket", rawBucket),
				"premise: NeoForge's code reading the public field from outside meets the same null");

		Map<String, byte[]> routed = parity(original, original);
		for (String name : List.of(LIQUID, BUCKET, READER)) assertNotSame(original.get(name), routed.get(name), name + " is rewritten");
		ClassLoader loader = InjectorExecution.load(routed);
		for (String name : List.of(LIQUID, BUCKET, READER)) assertEquals("", InjectorExecution.verify(routed.get(name), loader), name);

		Object oil = fluid(loader, "mod:oil");
		Supplier<Object> lazy = () -> oil;
		Object forgeLiquid = liquid(loader, lazy), forgeBucket = bucket(loader, lazy);
		assertEquals("tick mod:oil", InjectorExecution.invoke(forgeLiquid, "onPlace", new Object()), "the liquid is placed");
		assertSame(oil, InjectorExecution.invoke(forgeLiquid, "accessor$fluid"),
				"a method shaped like the getter (a mixin accessor) is routed too: MinecraftForge keeps only the getter raw");
		assertEquals("placed mod:oil", InjectorExecution.invoke(forgeBucket, "emptyContents"), "the bucket empties its fluid");
		assertSame(oil, InjectorExecution.invoke(forgeBucket, "getContent"), "vanilla's getContent answers the supplier's fluid");
		assertEquals("mod:oil", read(loader, "bucket", forgeBucket), "NeoForge's outside read answers the bucket's fluid");
		assertEquals("mod:oil", read(loader, "liquid", forgeLiquid), "and the liquid's");

		Object water = fluid(loader, "minecraft:water");
		Object vanillaLiquid = liquid(loader, water), vanillaBucket = bucket(loader, water);
		assertEquals("tick minecraft:water", InjectorExecution.invoke(vanillaLiquid, "onPlace", new Object()), "vanilla water still places");
		assertEquals("placed minecraft:water", InjectorExecution.invoke(vanillaBucket, "emptyContents"));
		assertEquals("minecraft:water", read(loader, "bucket", vanillaBucket));
		assertEquals("minecraft:water", read(loader, "liquid", vanillaLiquid));

		for (String name : List.of(LIQUID, BUCKET, READER)) {
			byte[] once = routed.get(name);
			assertSame(once, NativeCoremodParity.apply(name.replace('/', '.'), once), "a second pass changes nothing: " + name);
		}
	}

	/**
	 * Negative control: a base whose vanilla constructor leaves the supplier unset (NeoForge's constructors with
	 * MinecraftForge's getter). There the getter does not answer for water, so routing would take water's fluid away:
	 * the reads stay raw, inside and outside the class, and water still places. Once LiquidBlockFluidInjector has made the
	 * getter fall back to the field (as the COREMOD phase does before this), the getter answers every constructor and
	 * the reads inside are routed; outside reads judge the base as it is and stay raw.
	 */
	@Test void aGetterThatDoesNotAnswerForWaterKeepsTheReadsRaw(@TempDir Path work) throws Throwable {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put(LIQUID.replace('/', '.'), STAND_INS.get(LIQUID.replace('/', '.')).replace("this.supplier = () -> fluid;", "this.supplier = null;"));
		assertNotEquals(STAND_INS.get(LIQUID.replace('/', '.')), sources.get(LIQUID.replace('/', '.')));
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);

		Map<String, byte[]> asIs = parity(original, original);
		assertSame(original.get(LIQUID), asIs.get(LIQUID), "the getter throws for water: the liquid's reads stay raw");
		ClassLoader loader = InjectorExecution.load(asIs);
		Object water = fluid(loader, "minecraft:water");
		Object vanillaLiquid = liquid(loader, water);
		assertEquals("tick minecraft:water", InjectorExecution.invoke(vanillaLiquid, "onPlace", new Object()), "water still places");
		assertEquals("minecraft:water", read(loader, "liquid", vanillaLiquid), "the outside read of the liquid stays raw too");
		Object oil = fluid(loader, "mod:oil");
		assertEquals("mod:oil", read(loader, "bucket", bucket(loader, (Supplier<Object>) () -> oil)),
				"the bucket, whose getter does answer, is routed outside all the same");

		Map<String, byte[]> repaired = new HashMap<>(original);
		repaired.put(LIQUID, InjectorExecution.transform(new LiquidBlockFluidInjector(), LIQUID.replace('/', '.'), original.get(LIQUID),
				EnvType.SERVER));
		assertNotSame(original.get(LIQUID), repaired.get(LIQUID), "premise: the getter repair applies to this base");
		Map<String, byte[]> both = parity(repaired, original);
		assertNotSame(repaired.get(LIQUID), both.get(LIQUID), "the repaired getter falls back to the field: reads are routed");
		ClassLoader after = InjectorExecution.load(both);
		Object routedOil = fluid(after, "mod:oil");
		assertEquals("tick mod:oil", InjectorExecution.invoke(liquid(after, (Supplier<Object>) () -> routedOil), "onPlace", new Object()));
		Object routedWater = fluid(after, "minecraft:water");
		assertEquals("tick minecraft:water", InjectorExecution.invoke(liquid(after, routedWater), "onPlace", new Object()));
	}

	/** Negative control: switched off, nothing is rewritten and the MinecraftForge liquid still throws as merged. */
	@Test void switchedOffTheReadsStayAsMerged(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		for (String property : List.of(NativeCoremodParity.PROPERTY, NativeCoremodParity.FORGE_GETTERS)) {
			System.setProperty(property, "off");
			Map<String, byte[]> out = parity(original, original);
			for (String name : List.of(LIQUID, BUCKET, READER)) assertSame(original.get(name), out.get(name), property + ": " + name);
			ClassLoader loader = InjectorExecution.load(out);
			Object oil = fluid(loader, "mod:oil");
			Object forgeLiquid = liquid(loader, (Supplier<Object>) () -> oil);
			assertThrows(NullPointerException.class, () -> InjectorExecution.invoke(forgeLiquid, "onPlace", new Object()), property);
			System.clearProperty(property);
		}
	}

	/** Negative control: vanilla's shape (no supplier, the getter returns the field) has nothing to route. */
	@Test void vanillasShapeIsLeftAlone(@TempDir Path work) throws Throwable {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put(LIQUID.replace('/', '.'), """
				package net.minecraft.world.level.block;

				import net.minecraft.world.level.material.FlowingFluid;

				public class LiquidBlock {
					public final FlowingFluid fluid;

					public LiquidBlock(FlowingFluid fluid, Object properties) {
						this.fluid = fluid;
					}

					public String onPlace(Object level) {
						return this.fluid.getTickDelay(level);
					}

					public FlowingFluid getFluid() {
						return this.fluid;
					}
				}
				""");
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		Map<String, byte[]> out = parity(original, original);
		assertSame(original.get(LIQUID), out.get(LIQUID));
		ClassLoader loader = InjectorExecution.load(out);
		Object water = fluid(loader, "minecraft:water");
		assertEquals("minecraft:water", read(loader, "liquid", liquid(loader, water)), "the outside read stays raw and answers");
	}
}
