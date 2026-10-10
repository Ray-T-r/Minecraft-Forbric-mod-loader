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
 * {@link BlockItemLinkInjector}'s output, run: MinecraftForge's live item registry keeps its block→item map in
 * {@code Item.BY_BLOCK}, so a block item is linked to its block as the registry adds it, and a Fabric main entrypoint that
 * asks a builtin block for its item — and builds an ingredient from it, as Naturalist's hippo does from its static
 * initializer — gets the item, not AIR.
 *
 * <p>The hook is the kernel's real {@code KernelBlockItemLinks}, compiled from {@code src/runtime/java} against stand-ins
 * in the merged game's shapes: MinecraftForge's {@code ItemCallbacks} as it is written (a slave map made in
 * {@code onCreate}, filled through {@code registerBlocks} and emptied through {@code removeFromBlockToItemMap} in
 * {@code onAdd}, cleared in {@code onClear}); a {@code ForgeRegistry} that calls them the way the real one does, a
 * {@code sync} being a clear followed by every entry re-added; NeoForge's {@code GameData.getBlockItemMap()}, which the
 * merged {@code Item.BY_BLOCK} is taken from; and {@code Block.asItem()} caching its first answer.
 * {@code BlockItemLinkInjectorTest} edits the real callbacks from the staged carrier.
 */
@ExecutesInjector(BlockItemLinkInjector.class)
@ResourceLock("system-properties")
class BlockItemLinkInjectorExecutionTest {
	private static final String CALLBACKS = BlockItemLinkInjector.TARGET;
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelBlockItemLinks.java");

	private static final Map<String, String> GAME = Map.ofEntries(
			Map.entry("net.minecraftforge.registries.RegistryManager", """
					package net.minecraftforge.registries;

					public class RegistryManager {
						public static final RegistryManager ACTIVE = new RegistryManager();
						public static final RegistryManager FROZEN = new RegistryManager();
					}
					"""),
			Map.entry("net.minecraftforge.registries.IForgeRegistry", """
					package net.minecraftforge.registries;

					public interface IForgeRegistry<V> {
						final class SlaveKey<T> {
							private final String name;

							private SlaveKey(String name) {
								this.name = name;
							}

							public static <T> SlaveKey<T> create(String name) {
								return new SlaveKey<>(name);
							}
						}
					}
					"""),
			Map.entry("net.minecraftforge.registries.IForgeRegistryInternal", """
					package net.minecraftforge.registries;

					public interface IForgeRegistryInternal<V> extends IForgeRegistry<V> {
						<T> T getSlaveMap(IForgeRegistry.SlaveKey<T> key);

						<T> void setSlaveMap(IForgeRegistry.SlaveKey<T> key, T value);
					}
					"""),
			// What the real ForgeRegistry does with its callbacks: create on construction, add on every add (with the
			// entry it replaces), and a sync that clears and then re-adds every entry of the registry it copies.
			Map.entry("net.minecraftforge.registries.ForgeRegistry", """
					package net.minecraftforge.registries;

					import java.util.HashMap;
					import java.util.LinkedHashMap;
					import java.util.Map;
					import net.minecraft.world.item.Item;

					public class ForgeRegistry implements IForgeRegistryInternal<Item> {
						private final RegistryManager stage;
						private final Map<Object, Object> slaves = new HashMap<>();
						private final Map<String, Item> entries = new LinkedHashMap<>();

						public ForgeRegistry(RegistryManager stage) {
							this.stage = stage;
							GameData.ItemCallbacks.INSTANCE.onCreate(this, stage);
						}

						@SuppressWarnings("unchecked")
						@Override
						public <T> T getSlaveMap(IForgeRegistry.SlaveKey<T> key) {
							return (T) slaves.get(key);
						}

						@Override
						public <T> void setSlaveMap(IForgeRegistry.SlaveKey<T> key, T value) {
							slaves.put(key, value);
						}

						public void add(String name, Item item) {
							Item old = entries.put(name, item);
							GameData.ItemCallbacks.INSTANCE.onAdd(this, stage, item, old);
						}

						public ForgeRegistry copy(RegistryManager to) {
							ForgeRegistry copy = new ForgeRegistry(to);
							entries.forEach(copy::add);
							return copy;
						}

						public void sync(ForgeRegistry from) {
							GameData.ItemCallbacks.INSTANCE.onClear(this, stage);
							entries.clear();
							from.entries.forEach(this::add);
						}

						public Map<?, ?> blockToItem() {
							return GameData.ItemCallbacks.INSTANCE.mapOf(this);
						}
					}
					"""),
			Map.entry("net.minecraftforge.registries.GameData", """
					package net.minecraftforge.registries;

					import java.util.HashMap;
					import java.util.Map;
					import net.minecraft.world.item.BlockItem;
					import net.minecraft.world.item.Item;
					import net.minecraft.world.level.block.Block;

					public class GameData {
						public static class ItemCallbacks {
							public static final ItemCallbacks INSTANCE = new ItemCallbacks();
							private static final IForgeRegistry.SlaveKey<Map<Block, Item>> BLOCK_TO_ITEM = IForgeRegistry.SlaveKey.create("block_to_item");

							public void onAdd(IForgeRegistryInternal<Item> owner, RegistryManager stage, Item item, Item oldItem) {
								if (oldItem instanceof BlockItem old) old.removeFromBlockToItemMap(owner.getSlaveMap(BLOCK_TO_ITEM), item);
								if (item instanceof BlockItem blockItem) blockItem.registerBlocks(owner.getSlaveMap(BLOCK_TO_ITEM), item);
							}

							public void onClear(IForgeRegistryInternal<Item> owner, RegistryManager stage) {
								%s
							}

							public void onCreate(IForgeRegistryInternal<Item> owner, RegistryManager stage) {
								owner.setSlaveMap(BLOCK_TO_ITEM, new HashMap<>());
							}

							public Map<Block, Item> mapOf(IForgeRegistryInternal<Item> owner) {
								return owner.getSlaveMap(BLOCK_TO_ITEM);
							}
						}
					}
					"""),
			Map.entry("net.neoforged.neoforge.registries.GameData", """
					package net.neoforged.neoforge.registries;

					import java.util.HashMap;
					import java.util.Map;
					import net.minecraft.world.item.Item;
					import net.minecraft.world.level.block.Block;

					public class GameData {
						private static final Map<Block, Item> BLOCK_TO_ITEM_MAP = new HashMap<>();

						public static Map<Block, Item> getBlockItemMap() {
							return BLOCK_TO_ITEM_MAP;
						}
					}
					"""),
			// The merged Block.asItem(): its first answer is cached, AIR included.
			Map.entry("net.minecraft.world.level.block.Block", """
					package net.minecraft.world.level.block;

					import net.minecraft.world.item.Item;

					public class Block {
						private Item item;

						public Item asItem() {
							if (item == null) item = Item.byBlock(this);
							return item;
						}
					}
					"""),
			// The merged Item is NeoForge's: BY_BLOCK comes from NeoForge's GameData.
			Map.entry("net.minecraft.world.item.Item", """
					package net.minecraft.world.item;

					import java.util.Map;
					import net.minecraft.world.level.block.Block;

					public class Item {
						public static final Map<Block, Item> BY_BLOCK = net.neoforged.neoforge.registries.GameData.getBlockItemMap();

						public static Item byBlock(Block block) {
							return BY_BLOCK.getOrDefault(block, Items.AIR);
						}
					}
					"""),
			Map.entry("net.minecraft.world.item.BlockItem", """
					package net.minecraft.world.item;

					import java.util.Map;
					import net.minecraft.world.level.block.Block;

					public class BlockItem extends Item {
						private final Block block;

						public BlockItem(Block block) {
							this.block = block;
						}

						public Block getBlock() {
							return block;
						}

						public void registerBlocks(Map<Block, Item> map, Item item) {
							map.put(getBlock(), item);
						}

						public void removeFromBlockToItemMap(Map<Block, Item> map, Item item) {
							map.remove(getBlock());
						}
					}
					"""),
			// MinecraftForge's Items: registerItem adds the item to the live registry and leaves the link to the
			// add-callback.
			Map.entry("net.minecraft.world.item.Items", """
					package net.minecraft.world.item;

					import java.util.Map;
					import net.minecraft.world.level.block.Block;
					import net.minecraftforge.registries.ForgeRegistry;
					import net.minecraftforge.registries.RegistryManager;

					public final class Items {
						public static final Item AIR = new Item();
						public static final ForgeRegistry REGISTRY = new ForgeRegistry(RegistryManager.ACTIVE);

						public static Item registerItem(String name, Item item) {
							REGISTRY.add(name, item);
							return item;
						}

						/** A block item standing for a block and its wall variant, as Items$1 does for its alternatives. */
						public static BlockItem standingFor(Block block, Block wall) {
							return new BlockItem(block) {
								@Override
								public void registerBlocks(Map<Block, Item> map, Item item) {
									super.registerBlocks(map, item);
									map.put(wall, item);
								}

								@Override
								public void removeFromBlockToItemMap(Map<Block, Item> map, Item item) {
									super.removeFromBlockToItemMap(map, item);
									map.remove(wall);
								}
							};
						}
					}
					"""),
			// A main entrypoint's static initializer building an ingredient from a builtin block's item.
			Map.entry("example.MainEntrypoint", """
					package example;

					import net.minecraft.world.item.Item;
					import net.minecraft.world.item.Items;
					import net.minecraft.world.level.block.Block;

					public final class MainEntrypoint {
						public static Item ingredientOf(Block block) {
							Item item = block.asItem();
							if (item == Items.AIR) throw new UnsupportedOperationException("Ingredient can't contain air");
							return item;
						}
					}
					"""));

	/** MinecraftForge's onClear, as written. */
	private static final String FORGE_CLEAR = "owner.getSlaveMap(BLOCK_TO_ITEM).clear();";
	/** The same clear through a local: a shape the injector does not route, so the shared map is emptied. */
	private static final String UNROUTED_CLEAR = "Map<Block, Item> map = owner.getSlaveMap(BLOCK_TO_ITEM); map.clear();";

	@AfterEach void reset() {
		System.clearProperty(BlockItemLinkInjector.PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work, String onClear) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(GAME);
		sources.put("net.minecraftforge.registries.GameData", GAME.get("net.minecraftforge.registries.GameData").formatted(onClear));
		sources.put("net/forbric/kernel/runtime/KernelBlockItemLinks.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	/** The game with the callbacks as the chain leaves them after {@code edits}, verified and loaded with the rest. */
	private static ClassLoader game(Map<String, byte[]> original, ClassTransformer... edits) {
		String internal = CALLBACKS.replace('.', '/');
		Map<String, byte[]> classes = new HashMap<>(original);
		byte[] callbacks = original.get(internal);
		for (ClassTransformer edit : edits) callbacks = InjectorExecution.transform(edit, CALLBACKS, callbacks, EnvType.SERVER);
		classes.put(internal, callbacks);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(callbacks, loader));
		return loader;
	}

	private static Class<?> items(ClassLoader loader) throws ClassNotFoundException {
		return loader.loadClass("net.minecraft.world.item.Items");
	}

	private static Object block(ClassLoader loader) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("net.minecraft.world.level.block.Block"));
	}

	private static Object blockItem(ClassLoader loader, Object block) throws Throwable {
		return InjectorExecution.construct(loader.loadClass("net.minecraft.world.item.BlockItem"), block);
	}

	private static Object register(ClassLoader loader, String name, Object item) throws Throwable {
		return InjectorExecution.invokeStatic(items(loader), "registerItem", name, item);
	}

	@SuppressWarnings("unchecked")
	private static Map<Object, Object> byBlock(ClassLoader loader) throws Exception {
		return (Map<Object, Object>) InjectorExecution.getStatic(loader.loadClass("net.minecraft.world.item.Item"), "BY_BLOCK");
	}

	private static Object live(ClassLoader loader) throws Exception {
		return InjectorExecution.getStatic(items(loader), "REGISTRY");
	}

	private static Object stage(ClassLoader loader, String name) throws Exception {
		return InjectorExecution.getStatic(loader.loadClass("net.minecraftforge.registries.RegistryManager"), name);
	}

	private static Object ingredientOf(ClassLoader loader, Object block) throws Throwable {
		return InjectorExecution.invokeStatic(loader.loadClass("example.MainEntrypoint"), "ingredientOf", block);
	}

	/** The issue: bootstrap registers the melon's item, then a Fabric main builds an ingredient from the melon. */
	@Test void aFabricMainAfterBootstrapGetsABuiltinBlocksItem(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work, FORGE_CLEAR);
		ClassLoader repaired = game(original, new BlockItemLinkInjector());
		Object melon = block(repaired);
		Object melonItem = register(repaired, "minecraft:melon", blockItem(repaired, melon));
		assertSame(melonItem, byBlock(repaired).get(melon), "linked as the registry added it, before any mod code runs");
		assertSame(byBlock(repaired), InjectorExecution.invoke(live(repaired), "blockToItem"),
				"the live registry's block->item map IS Item.BY_BLOCK, as on MinecraftForge");
		assertSame(melonItem, ingredientOf(repaired, melon));

		ClassLoader merged = game(original);
		Object stockMelon = block(merged);
		Object stockItem = register(merged, "minecraft:melon", blockItem(merged, stockMelon));
		assertSame(stockItem, ((Map<?, ?>) InjectorExecution.invoke(live(merged), "blockToItem")).get(stockMelon),
				"control: as merged, the add-callback links the melon — into a map nothing reads");
		assertEquals("Ingredient can't contain air", assertThrows(UnsupportedOperationException.class,
				() -> ingredientOf(merged, stockMelon)).getMessage(), "control: so a main asking the melon gets AIR");
		byBlock(merged).put(stockMelon, stockItem);
		assertSame(InjectorExecution.getStatic(items(merged), "AIR"), InjectorExecution.invoke(stockMelon, "asItem"),
				"control: and a link made later — when the window closes — does not reach it: asItem() keeps its first answer");
	}

	/**
	 * Every add links through the item's own {@code registerBlocks}, so a block item standing for several blocks links them
	 * all; an override unlinks the item it replaces through {@code removeFromBlockToItemMap}, as MinecraftForge does.
	 */
	@Test void everyAddLinksAndAnOverrideUnlinksTheItemItReplaces(@TempDir Path work) throws Throwable {
		ClassLoader repaired = game(compile(work, FORGE_CLEAR), new BlockItemLinkInjector());
		Object torch = block(repaired);
		Object wallTorch = block(repaired);
		Object torchItem = register(repaired, "minecraft:torch", InjectorExecution.invokeStatic(items(repaired), "standingFor", torch, wallTorch));
		assertEquals(Map.of(torch, torchItem, wallTorch, torchItem), byBlock(repaired));
		register(repaired, "minecraft:stick", InjectorExecution.construct(repaired.loadClass("net.minecraft.world.item.Item")));
		assertEquals(2, byBlock(repaired).size(), "an item that is no BlockItem links nothing");

		Object redstoneTorch = block(repaired);
		Object override = register(repaired, "minecraft:torch", blockItem(repaired, redstoneTorch));
		assertEquals(Map.of(redstoneTorch, override), byBlock(repaired), "the replaced item's blocks are unlinked, the new one's linked");
	}

	/** Only the live registry shares the map: a FROZEN copy keeps one of its own, and its adds and clears stay in it. */
	@Test void everyOtherStageKeepsAMapOfItsOwn(@TempDir Path work) throws Throwable {
		ClassLoader repaired = game(compile(work, FORGE_CLEAR), new BlockItemLinkInjector());
		Object melon = block(repaired);
		Object melonItem = register(repaired, "minecraft:melon", blockItem(repaired, melon));
		Object frozen = InjectorExecution.invoke(live(repaired), "copy", stage(repaired, "FROZEN"));
		Map<?, ?> frozenMap = (Map<?, ?>) InjectorExecution.invoke(frozen, "blockToItem");
		assertNotSame(byBlock(repaired), frozenMap);
		assertEquals(Map.of(melon, melonItem), frozenMap, "the copy linked into its own map");

		Object stale = block(repaired);
		@SuppressWarnings("unchecked") Map<Object, Object> writable = (Map<Object, Object>) frozenMap;
		writable.put(stale, melonItem);
		InjectorExecution.invoke(frozen, "sync", live(repaired));
		assertEquals(Map.of(melon, melonItem), frozenMap, "a clear of any other stage's map still empties it");
		assertEquals(Map.of(melon, melonItem), byBlock(repaired), "and never touches Item.BY_BLOCK");
	}

	/**
	 * A re-sync of the live registry (world close, a save's or a server's ids) clears it and re-adds every entry. The
	 * re-adds relink every block item; the clear no longer empties {@code Item.BY_BLOCK}, so what other code put there by
	 * hand — which Fabric never clears — is still there.
	 */
	@Test void aResyncOfTheLiveRegistryKeepsWhatOthersPutInItemByBlock(@TempDir Path work) throws Throwable {
		ClassLoader repaired = game(compile(work, FORGE_CLEAR), new BlockItemLinkInjector());
		Object melon = block(repaired);
		Object melonItem = register(repaired, "minecraft:melon", blockItem(repaired, melon));
		Object frozen = InjectorExecution.invoke(live(repaired), "copy", stage(repaired, "FROZEN"));
		Object byHand = block(repaired);
		byBlock(repaired).put(byHand, melonItem);
		InjectorExecution.invoke(live(repaired), "sync", frozen);
		assertEquals(Map.of(melon, melonItem, byHand, melonItem), byBlock(repaired));

		ClassLoader unrouted = game(compile(work, UNROUTED_CLEAR), new BlockItemLinkInjector());
		Object stockMelon = block(unrouted);
		Object stockItem = register(unrouted, "minecraft:melon", blockItem(unrouted, stockMelon));
		Object stockFrozen = InjectorExecution.invoke(live(unrouted), "copy", stage(unrouted, "FROZEN"));
		byBlock(unrouted).put(block(unrouted), stockItem);
		InjectorExecution.invoke(live(unrouted), "sync", stockFrozen);
		assertEquals(Map.of(stockMelon, stockItem), byBlock(unrouted),
				"control: a clear the kernel does not route empties the shared map, and only the block items come back");
	}

	@Test void aSecondPassAndTheSwitchLeaveTheCallbacksAlone(@TempDir Path work) throws Exception {
		byte[] original = compile(work, FORGE_CLEAR).get(CALLBACKS.replace('.', '/'));
		byte[] once = InjectorExecution.transform(new BlockItemLinkInjector(), CALLBACKS, original, EnvType.SERVER);
		assertNotSame(original, once);
		assertSame(once, InjectorExecution.transform(new BlockItemLinkInjector(), CALLBACKS, once, EnvType.SERVER));
		System.setProperty(BlockItemLinkInjector.PROPERTY, "off");
		assertSame(original, InjectorExecution.transform(new BlockItemLinkInjector(), CALLBACKS, original, EnvType.SERVER));
	}
}
