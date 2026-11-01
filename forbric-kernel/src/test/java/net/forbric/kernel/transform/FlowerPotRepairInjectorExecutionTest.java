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
 * {@link FlowerPotRepairInjector}'s output, run: a pot built with the vanilla-shaped constructor holds its plant in
 * {@code potted} and is listed in {@code POTTED_BY_CONTENT}, as vanilla's is — where NeoForge's own constructor leaves
 * {@code potted} null and lists nothing, and a Fabric mod reading either finds nothing.
 *
 * <p>The stand-in {@code FlowerPotBlock} is NeoForge's shape (javap of the genuine base): {@code (Block, Properties)}
 * stores null into {@code potted} and keeps the plant in a {@code flowerDelegate} supplier that {@code getPotted()} reads.
 * (Until Stage 4 this test ran the merged class's MinecraftForge half too — its {@code useItemOn} lookup in
 * {@code fullPots} and {@code addPlant} — which went with that half.)
 */
@ExecutesInjector(FlowerPotRepairInjector.class)
@ResourceLock("system-properties")
class FlowerPotRepairInjectorExecutionTest {
	private static final String POT = FlowerPotRepairInjector.TARGET;

	private static final Map<String, String> STAND_INS = Map.of(
			"net.minecraft.world.level.block.state.BlockBehaviour", """
					package net.minecraft.world.level.block.state;

					public class BlockBehaviour {
						public static class Properties {
						}

						public BlockBehaviour(Properties properties) {
						}
					}
					""",
			"net.minecraft.world.level.block.Block", """
					package net.minecraft.world.level.block;

					import net.minecraft.world.level.block.state.BlockBehaviour;

					public class Block extends BlockBehaviour {
						public Block(BlockBehaviour.Properties properties) {
							super(properties);
						}
					}
					""",
			"net.minecraft.world.level.block.FlowerPotBlock", """
					package net.minecraft.world.level.block;

					import java.util.HashMap;
					import java.util.Map;
					import java.util.function.Supplier;
					import net.minecraft.world.level.block.state.BlockBehaviour;

					public class FlowerPotBlock extends Block {
						private static final Map<Block, Block> POTTED_BY_CONTENT = new HashMap<>();
						private final Block potted;
						private final Supplier<? extends Block> flowerDelegate;

						public FlowerPotBlock(Block potted, BlockBehaviour.Properties properties) {
							super(properties);
							this.potted = null;
							this.flowerDelegate = () -> potted;
						}

						public Block getPotted() {
							return flowerDelegate.get();
						}

						public Block pottedField() {
							return potted;
						}

						public static Map<Block, Block> pottedByContent() {
							return POTTED_BY_CONTENT;
						}
					}
					""");

	@AfterEach void reset() {
		System.clearProperty(NativeCoremodParity.FLOWER_POT);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		return new HashMap<>(InjectorExecution.compile(work, STAND_INS));
	}

	@Test void theVanillaShapedConstructorStoresItsPlantAsVanillaDoes(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = FlowerPotRepairInjector.OWNER;
		byte[] repaired = InjectorExecution.transform(new FlowerPotRepairInjector(), POT, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), repaired, "NeoForge's constructor was edited");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		Object properties = InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.block.state.BlockBehaviour$Properties"));
		Object poppy = InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.block.Block"), properties);
		Class<?> potClass = loader.loadClass(POT);
		Object pottedPoppy = InjectorExecution.construct(potClass, poppy, properties);
		assertSame(poppy, InjectorExecution.invoke(pottedPoppy, "pottedField"), "potted holds the plant, as vanilla's does");
		assertSame(poppy, InjectorExecution.invoke(pottedPoppy, "getPotted"), "and NeoForge's getter still answers it");
		assertEquals(Map.of(poppy, pottedPoppy), InjectorExecution.invokeStatic(potClass, "pottedByContent"),
				"vanilla's map lists the pot under its plant");

		ClassLoader stock = InjectorExecution.load(original);
		Class<?> stockPot = stock.loadClass(POT);
		Object stockProperties = InjectorExecution.construct(stock.loadClass("net.minecraft.world.level.block.state.BlockBehaviour$Properties"));
		Object stockPoppy = InjectorExecution.construct(stock.loadClass("net.minecraft.world.level.block.Block"), stockProperties);
		Object stockPotted = InjectorExecution.construct(stockPot, stockPoppy, stockProperties);
		assertNull(InjectorExecution.invoke(stockPotted, "pottedField"), "premise: NeoForge's own constructor stores null");
		assertEquals(Map.of(), InjectorExecution.invokeStatic(stockPot, "pottedByContent"), "premise: and lists nothing");

		assertSame(repaired, InjectorExecution.transform(new FlowerPotRepairInjector(), POT, repaired, EnvType.SERVER),
				"a repaired pot is left alone");
	}

	@Test void switchedOffThePotIsLeftAsNeoForgeHasIt(@TempDir Path work) throws Exception {
		byte[] bytes = compile(work).get(FlowerPotRepairInjector.OWNER);
		System.setProperty(NativeCoremodParity.FLOWER_POT, "off");
		assertSame(bytes, InjectorExecution.transform(new FlowerPotRepairInjector(), POT, bytes, EnvType.SERVER));
	}
}
