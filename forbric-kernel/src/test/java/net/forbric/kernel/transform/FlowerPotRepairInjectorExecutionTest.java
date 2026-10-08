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

/**
 * {@link FlowerPotRepairInjector}'s output, run: a poppy goes into a flower pot, a MinecraftForge mod's plant added with
 * {@code addPlant} goes into it too, pick-block on a pot answers, and vanilla's {@code POTTED_BY_CONTENT} lists the
 * pots — where as merged the empty pot refused every plant and pick-block threw.
 *
 * <p>The hook is the kernel's real {@code KernelFlowerPots}, compiled from {@code src/runtime/java} against stand-ins:
 * a block registry, NeoForge's {@code GameData} pot table (on a stand-in Guava {@code Table}, which the test classpath
 * does not carry), and a {@code FlowerPotBlock} of the merged three halves — NeoForge's constructors, which leave
 * {@code potted} null; MinecraftForge's {@code useItemOn}, which looks the plant up in the empty pot's
 * {@code fullPots}; NeoForge's {@code addPlant}, which only checks its argument; and vanilla's {@code isEmpty} and
 * pick-block, which read {@code potted}.
 */
@ExecutesInjector(FlowerPotRepairInjector.class)
@ResourceLock("system-properties")
class FlowerPotRepairInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFlowerPots.java");
	private static final String POT = FlowerPotRepairInjector.TARGET;

	private static String empty(String binaryName) {
		int dot = binaryName.lastIndexOf('.');
		return "package " + binaryName.substring(0, dot) + "; public class " + binaryName.substring(dot + 1) + " { }";
	}

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			"net.minecraft.resources.Identifier", """
					package net.minecraft.resources;

					public record Identifier(String id) {
					}
					""",
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

					import net.minecraft.world.item.BlockItem;
					import net.minecraft.world.level.block.state.BlockBehaviour;

					public class Block extends BlockBehaviour {
						private BlockItem item;

						public Block(BlockBehaviour.Properties properties) {
							super(properties);
						}

						public BlockItem asItem() {
							if (item == null) item = new BlockItem(this);
							return item;
						}
					}
					""",
			"net.minecraft.world.item.Item", "package net.minecraft.world.item; public class Item { }",
			"net.minecraft.world.item.BlockItem", """
					package net.minecraft.world.item;

					import net.minecraft.world.level.block.Block;

					public class BlockItem extends Item {
						private final Block block;

						public BlockItem(Block block) {
							this.block = block;
						}

						public Block getBlock() {
							return block;
						}
					}
					""",
			"net.minecraft.world.item.ItemStack", """
					package net.minecraft.world.item;

					import net.minecraft.world.level.block.Block;

					public class ItemStack {
						public static final ItemStack EMPTY = new ItemStack(new Item());
						private final Item item;

						public ItemStack(Item item) {
							this.item = item;
						}

						/** What pick-block builds: a stack of the block's item. */
						public ItemStack(Block block) {
							this(block.asItem());
						}

						public Item getItem() {
							return item;
						}
					}
					""",
			"net.minecraft.world.level.Level", """
					package net.minecraft.world.level;

					import java.util.HashMap;
					import java.util.Map;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.level.block.Block;

					public class Level {
						public final Map<BlockPos, Block> blocks = new HashMap<>();

						public void setBlock(BlockPos pos, Block block) {
							blocks.put(pos, block);
						}
					}
					""",
			"net.minecraft.core.BlockPos", "package net.minecraft.core; public record BlockPos(int x, int y, int z) { }",
			"net.minecraft.world.InteractionResult", """
					package net.minecraft.world;

					public enum InteractionResult {
						SUCCESS, CONSUME, TRY_WITH_EMPTY_HAND
					}
					"""));

	static {
		for (String name : new String[] {"net.minecraft.world.level.block.state.BlockState", "net.minecraft.world.entity.player.Player",
				"net.minecraft.world.InteractionHand", "net.minecraft.world.phys.BlockHitResult"}) {
			STAND_INS.put(name, empty(name));
		}
		STAND_INS.put("net.minecraft.core.registries.BuiltInRegistries", """
				package net.minecraft.core.registries;

				import java.util.Iterator;
				import java.util.LinkedHashMap;
				import java.util.Map;
				import net.minecraft.resources.Identifier;
				import net.minecraft.world.level.block.Block;

				public class BuiltInRegistries {
					public static final Registry BLOCK = new Registry();

					public static class Registry implements Iterable<Block> {
						private final Map<Block, Identifier> keys = new LinkedHashMap<>();

						public <T extends Block> T register(String id, T block) {
							keys.put(block, new Identifier(id));
							return block;
						}

						public Identifier getKey(Block block) {
							return keys.getOrDefault(block, getDefaultKey());
						}

						public Identifier getDefaultKey() {
							return new Identifier("minecraft:air");
						}

						public Block get(Identifier id) {
							for (var entry : keys.entrySet()) if (entry.getValue().equals(id)) return entry.getKey();
							return null;
						}

						@Override
						public Iterator<Block> iterator() {
							return keys.keySet().iterator();
						}
					}
				}
				""");
		STAND_INS.put("net.minecraft.world.level.block.Blocks", """
				package net.minecraft.world.level.block;

				import net.minecraft.core.registries.BuiltInRegistries;
				import net.minecraft.world.level.block.state.BlockBehaviour;

				public class Blocks {
					public static final Block AIR = BuiltInRegistries.BLOCK.register("minecraft:air", new Block(new BlockBehaviour.Properties()));
					public static final Block POPPY = BuiltInRegistries.BLOCK.register("minecraft:poppy", new Block(new BlockBehaviour.Properties()));
					public static final Block FLOWER_POT = BuiltInRegistries.BLOCK.register("minecraft:flower_pot",
							new FlowerPotBlock(AIR, new BlockBehaviour.Properties()));
					public static final Block POTTED_POPPY = BuiltInRegistries.BLOCK.register("minecraft:potted_poppy",
							new FlowerPotBlock(POPPY, new BlockBehaviour.Properties()));
				}
				""");
		STAND_INS.put("net.minecraftforge.registries.ForgeRegistries", """
				package net.minecraftforge.registries;

				import java.util.function.Supplier;
				import net.minecraft.core.registries.BuiltInRegistries;
				import net.minecraft.resources.Identifier;
				import net.minecraft.world.level.block.Block;

				public class ForgeRegistries {
					public interface IForgeRegistry<V> {
						Identifier getKey(V value);

						Supplier<V> getDelegateOrThrow(V value);
					}

					public static final IForgeRegistry<Block> BLOCKS = new IForgeRegistry<>() {
						public Identifier getKey(Block block) {
							return BuiltInRegistries.BLOCK.getKey(block);
						}

						public Supplier<Block> getDelegateOrThrow(Block block) {
							return () -> block;
						}
					};
				}
				""");
		STAND_INS.put("com.google.common.collect.Table", """
				package com.google.common.collect;

				/** The three members KernelFlowerPots uses; Guava is not on the test classpath. */
				public interface Table<R, C, V> {
					V get(Object rowKey, Object columnKey);

					V put(R rowKey, C columnKey, V value);

					void clear();
				}
				""");
		STAND_INS.put("net.neoforged.neoforge.registries.GameData", """
				package net.neoforged.neoforge.registries;

				import java.util.HashMap;
				import java.util.List;
				import java.util.Map;
				import com.google.common.collect.Table;
				import net.minecraft.world.level.block.Block;

				public class GameData {
					private static final Map<List<Object>, Block> CELLS = new HashMap<>();
					private static final Table<Block, Block, Block> POTS = new Table<>() {
						public Block get(Object row, Object column) {
							return CELLS.get(List.of(row, column));
						}

						public Block put(Block row, Block column, Block value) {
							return CELLS.put(List.of(row, column), value);
						}

						public void clear() {
							CELLS.clear();
						}
					};

					public static Table<Block, Block, Block> getFlowerPotBlockTable() {
						return POTS;
					}
				}
				""");
		STAND_INS.put(POT, """
				package net.minecraft.world.level.block;

				import java.util.HashMap;
				import java.util.Map;
				import java.util.function.Supplier;
				import net.minecraft.core.BlockPos;
				import net.minecraft.resources.Identifier;
				import net.minecraft.world.InteractionHand;
				import net.minecraft.world.InteractionResult;
				import net.minecraft.world.entity.player.Player;
				import net.minecraft.world.item.BlockItem;
				import net.minecraft.world.item.Item;
				import net.minecraft.world.item.ItemStack;
				import net.minecraft.world.level.Level;
				import net.minecraft.world.level.block.state.BlockBehaviour;
				import net.minecraft.world.level.block.state.BlockState;
				import net.minecraft.world.phys.BlockHitResult;
				import net.minecraftforge.registries.ForgeRegistries;

				public class FlowerPotBlock extends Block {
					/** Vanilla's. */
					public static final Map<Block, Block> POTTED_BY_CONTENT = new HashMap<>();
					/** Vanilla's, which NeoForge's constructors leave null. */
					private final Block potted;
					private final Supplier<FlowerPotBlock> emptyPot;
					private final Supplier<? extends Block> plant;
					/** MinecraftForge's. */
					private final Map<Identifier, Supplier<? extends Block>> fullPots;

					/** NeoForge's vanilla-shaped constructor. */
					public FlowerPotBlock(Block potted, BlockBehaviour.Properties properties) {
						super(properties);
						this.potted = null;
						this.emptyPot = null;
						this.plant = () -> potted;
						this.fullPots = new HashMap<>();
					}

					/** NeoForge's (empty pot, plant) constructor; MinecraftForge's has the same descriptor. */
					public FlowerPotBlock(Supplier<FlowerPotBlock> emptyPot, Supplier<? extends Block> plant, BlockBehaviour.Properties properties) {
						super(properties);
						this.potted = null;
						this.emptyPot = emptyPot;
						this.plant = plant;
						this.fullPots = new HashMap<>();
					}

					public FlowerPotBlock getEmptyPot() {
						return emptyPot == null ? (FlowerPotBlock) Blocks.FLOWER_POT : emptyPot.get();
					}

					public Block getPotted() {
						return plant.get();
					}

					/** Vanilla's. */
					public boolean isEmpty() {
						return this.potted == Blocks.AIR;
					}

					/** Vanilla's pick-block. */
					public ItemStack getCloneItemStack() {
						return new ItemStack(this.isEmpty() ? this : this.potted);
					}

					/** MinecraftForge's body. */
					public InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
							InteractionHand hand, BlockHitResult hit) {
						Item item = stack.getItem();
						Block newBlock = item instanceof BlockItem blockItem ? getEmptyPot().fullPots.getOrDefault(
								ForgeRegistries.BLOCKS.getKey(blockItem.getBlock()), ForgeRegistries.BLOCKS.getDelegateOrThrow(Blocks.AIR)).get()
								: Blocks.AIR;
						boolean newIsAir = newBlock == Blocks.AIR;
						boolean thisIsEmpty = this.isEmpty();
						if (newIsAir != thisIsEmpty) {
							if (thisIsEmpty) {
								level.setBlock(pos, newBlock);
								return InteractionResult.SUCCESS;
							}
							return InteractionResult.CONSUME;
						}
						return InteractionResult.TRY_WITH_EMPTY_HAND;
					}

					/** NeoForge's: it only checks its argument. */
					public void addPlant(Identifier flower, Supplier<? extends Block> fullPot) {
						if (getEmptyPot() != this) throw new IllegalArgumentException("Cannot add plant to non-empty pot: " + this);
					}
				}
				""");
		STAND_INS.put("fixture.RoseMod", """
				package fixture;

				import net.minecraft.core.registries.BuiltInRegistries;
				import net.minecraft.resources.Identifier;
				import net.minecraft.world.level.block.Block;
				import net.minecraft.world.level.block.Blocks;
				import net.minecraft.world.level.block.FlowerPotBlock;
				import net.minecraft.world.level.block.state.BlockBehaviour;

				/** A MinecraftForge mod declaring its pot the MinecraftForge way: addPlant on the empty pot. */
				public class RoseMod {
					public static final Block ROSE = BuiltInRegistries.BLOCK.register("rosemod:rose", new Block(new BlockBehaviour.Properties()));
					public static final FlowerPotBlock POTTED_ROSE = new FlowerPotBlock(() -> (FlowerPotBlock) Blocks.FLOWER_POT, () -> ROSE,
							new BlockBehaviour.Properties());

					public static void register() {
						((FlowerPotBlock) Blocks.FLOWER_POT).addPlant(new Identifier("rosemod:rose"), () -> POTTED_ROSE);
					}
				}
				""");
	}

	@AfterEach void reset() {
		System.clearProperty(NativeCoremodParity.FLOWER_POT);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelFlowerPots.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	private static Object block(ClassLoader loader, String name) throws Throwable {
		return InjectorExecution.getStatic(loader.loadClass("net.minecraft.world.level.block.Blocks"), name);
	}

	/** {@code plant}'s item used on the vanilla flower pot: the result, then what the level holds there. */
	private static Object[] plant(ClassLoader loader, Object plant) throws Throwable {
		Object level = InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.Level"));
		Object pos = InjectorExecution.construct(loader.loadClass("net.minecraft.core.BlockPos"), 0, 64, 0);
		Object stack = InjectorExecution.construct(loader.loadClass("net.minecraft.world.item.ItemStack"), InjectorExecution.invoke(plant, "asItem"));
		Object result = InjectorExecution.invoke(block(loader, "FLOWER_POT"), "useItemOn", stack, null, level, pos, null, null, null);
		return new Object[] {result.toString(), ((Map<?, ?>) level.getClass().getField("blocks").get(level)).get(pos)};
	}

	private static Object pickBlock(Object pot) throws Throwable {
		return InjectorExecution.invoke(InjectorExecution.invoke(pot, "getCloneItemStack"), "getItem");
	}

	@Test void aPlantGoesInAndPickBlockAnswers(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		String internal = FlowerPotRepairInjector.OWNER;
		byte[] repaired = InjectorExecution.transform(new FlowerPotRepairInjector(), POT, original.get(internal), EnvType.SERVER);
		assertNotSame(original.get(internal), repaired, "the merged class was edited");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, repaired);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(repaired, loader));

		Object poppy = block(loader, "POPPY"), pottedPoppy = block(loader, "POTTED_POPPY"), flowerPot = block(loader, "FLOWER_POT");
		InjectorExecution.invokeStatic(loader.loadClass("fixture.RoseMod"), "register");
		Class<?> rose = loader.loadClass("fixture.RoseMod");
		assertEquals(1, InjectorExecution.invokeStatic(loader.loadClass("net.forbric.kernel.runtime.KernelFlowerPots"), "rebuildTable"),
				"NeoForge's pot table is filled from the registered pots (the poppy's) as the registration window closes");
		assertArrayEquals(new Object[] {"SUCCESS", pottedPoppy}, plant(loader, poppy), "a poppy goes into a flower pot");
		assertArrayEquals(new Object[] {"SUCCESS", InjectorExecution.getStatic(rose, "POTTED_ROSE")},
				plant(loader, InjectorExecution.getStatic(rose, "ROSE")), "and so does a plant a MinecraftForge mod added with addPlant");
		assertSame(InjectorExecution.invoke(poppy, "asItem"), pickBlock(pottedPoppy), "pick-block on a full pot gives its plant");
		assertSame(InjectorExecution.invoke(flowerPot, "asItem"), pickBlock(flowerPot), "and on an empty pot the pot");
		assertEquals(Map.of(block(loader, "AIR"), flowerPot, poppy, pottedPoppy),
				InjectorExecution.getStatic(loader.loadClass(POT), "POTTED_BY_CONTENT"), "vanilla's map lists the vanilla-built pots");

		ClassLoader stock = InjectorExecution.load(original);
		Object mergedPoppy = block(stock, "POPPY");
		InjectorExecution.invokeStatic(stock.loadClass("net.forbric.kernel.runtime.KernelFlowerPots"), "rebuildTable");
		Object[] refused = plant(stock, mergedPoppy);
		assertEquals("CONSUME", refused[0], "premise: as merged, the empty pot takes itself for full");
		assertNull(refused[1], "premise: and the poppy does not go in");
		Object mergedPotted = block(stock, "POTTED_POPPY");
		assertThrows(NullPointerException.class, () -> pickBlock(mergedPotted), "premise: as merged, pick-block throws");
		assertSame(repaired, InjectorExecution.transform(new FlowerPotRepairInjector(), POT, repaired, EnvType.SERVER),
				"a repaired pot is left alone");
	}

    @Test void theLivePublicSdkQueryRetainsNativeEffectsAndReportsBadExplicitSuppliers(@TempDir Path work)throws Throwable{
        Map<String,String> sources=new HashMap<>(STAND_INS);
        String pot=sources.get(POT);
        pot=pot.replace("public class FlowerPotBlock extends Block {", "public class FlowerPotBlock extends Block {public static int nativeQueries;public Block getFullPot(Block content){nativeQueries++;if(getEmptyPot()!=this)throw new IllegalStateException(\"not empty\");Block result=net.neoforged.neoforge.registries.GameData.getFlowerPotBlockTable().get(this,content);return result==null?Blocks.AIR:result;}");
        String old="getEmptyPot().fullPots.getOrDefault(\n\t\t\t\tForgeRegistries.BLOCKS.getKey(blockItem.getBlock()), ForgeRegistries.BLOCKS.getDelegateOrThrow(Blocks.AIR)).get()";
        int begin=pot.indexOf("getEmptyPot().fullPots.getOrDefault("),end=pot.indexOf(".get()",begin)+6;
        assertTrue(begin>=0&&end>begin);pot=pot.substring(0,begin)+"getEmptyPot().getFullPot(blockItem.getBlock())"+pot.substring(end);
        sources.put(POT,pot);sources.put("net/forbric/kernel/runtime/KernelFlowerPots.java",Files.readString(HOOK_SOURCE));
        Map<String,byte[]> classes=new HashMap<>(InjectorExecution.compile(work,sources));classes.put(FlowerPotRepairInjector.OWNER,InjectorExecution.transform(new FlowerPotRepairInjector(),POT,classes.get(FlowerPotRepairInjector.OWNER),EnvType.SERVER));
        ClassLoader loader=InjectorExecution.load(classes);Class<?> type=loader.loadClass(POT);Object empty=block(loader,"FLOWER_POT"),content=block(loader,"POPPY"),full=block(loader,"POTTED_POPPY");
        InjectorExecution.invokeStatic(loader.loadClass("net.forbric.kernel.runtime.KernelFlowerPots"),"rebuildTable");
        assertSame(full,InjectorExecution.invoke(empty,"getFullPot",content));assertEquals(1,InjectorExecution.getStatic(type,"nativeQueries"));
        InjectorExecution.invokeStatic(loader.loadClass("fixture.RoseMod"),"register");Class<?> rose=loader.loadClass("fixture.RoseMod");Object roseContent=InjectorExecution.getStatic(rose,"ROSE");
        assertSame(InjectorExecution.getStatic(rose,"POTTED_ROSE"),InjectorExecution.invoke(empty,"getFullPot",roseContent));assertEquals(2,InjectorExecution.getStatic(type,"nativeQueries"),"the original SDK table query executes even when an explicit SDK entry wins");
        Object id=loader.loadClass("net.minecraft.resources.Identifier").getConstructor(String.class).newInstance("fixture:broken");
        Object bad=InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.block.Block"),InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.block.state.BlockBehaviour$Properties")));
        Object registry=InjectorExecution.getStatic(loader.loadClass("net.minecraft.core.registries.BuiltInRegistries"),"BLOCK");InjectorExecution.invoke(registry,"register","fixture:broken",bad);
        java.util.concurrent.atomic.AtomicInteger supplierCalls=new java.util.concurrent.atomic.AtomicInteger();java.util.function.Supplier<Object> supplier=()->{supplierCalls.incrementAndGet();throw new IllegalStateException("supplier failed");};InjectorExecution.invoke(empty,"addPlant",id,supplier);
        assertEquals("supplier failed",assertThrows(IllegalStateException.class,()->InjectorExecution.invoke(empty,"getFullPot",bad)).getMessage());assertEquals(1,supplierCalls.get());assertEquals(3,InjectorExecution.getStatic(type,"nativeQueries"));
        assertEquals("not empty",assertThrows(IllegalStateException.class,()->InjectorExecution.invoke(full,"getFullPot",content)).getMessage(),"the original public query's guard stays ahead of the added resolver");
    }

	@Test void switchedOffThePotIsLeftAsMerged(@TempDir Path work) throws Exception {
		byte[] bytes = compile(work).get(FlowerPotRepairInjector.OWNER);
		System.setProperty(NativeCoremodParity.FLOWER_POT, "off");
		assertSame(bytes, InjectorExecution.transform(new FlowerPotRepairInjector(), POT, bytes, EnvType.SERVER));
	}
}
