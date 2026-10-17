/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link CompostablesFallbackInjector}'s output, run: a compostable a Fabric mod put in vanilla's {@code COMPOSTABLES}
 * goes into a composter by hand, by hopper and through fabric-transfer, at its own chance — where as merged only
 * NeoForge's data map was asked and it never composted. A datapack that takes a vanilla item out of the data map still
 * keeps it out, and fabric-transfer now agrees with the block about it.
 *
 * <p>The hook is the kernel's real {@code KernelCompostables}, compiled from {@code src/runtime/java} against stand-ins:
 * fastutil's float maps (not on the test classpath), items and their registry, NeoForge's compostables data map, and the
 * three classes in the merged shapes the edits key on — the composter's {@code bootStrap} adds and its three
 * {@code getValue} sites, its {@code InputContainer}, and a mod's pipe ({@code org.quarry.pipes.Chute}, as
 * fabric-transfer's {@code TopStorage} does) reading {@code COMPOSTABLES} once.
 */
@ExecutesInjector(CompostablesFallbackInjector.class)
@ResourceLock("system-properties")
class CompostablesFallbackInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelCompostables.java");
	/** A mod's pipe that reads vanilla's map before it inserts, under a name of no loader's (fabric-transfer's TopStorage is one). */
	private static final String CHUTE = "org.quarry.pipes.Chute";
	private static final List<String> TARGETS = List.of(CompostablesFallbackInjector.COMPOSTER, CompostablesFallbackInjector.INPUT, CHUTE);

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			"it.unimi.dsi.fastutil.objects.ObjectSet", "package it.unimi.dsi.fastutil.objects; public interface ObjectSet<E> extends java.util.Set<E> { }",
			"it.unimi.dsi.fastutil.objects.ObjectOpenHashSet",
			"package it.unimi.dsi.fastutil.objects; public class ObjectOpenHashSet<E> extends java.util.HashSet<E> implements ObjectSet<E> { }",
			"it.unimi.dsi.fastutil.objects.Object2FloatMap", """
					package it.unimi.dsi.fastutil.objects;

					public interface Object2FloatMap<K> {
						interface Entry<K> {
							K getKey();

							float getFloatValue();
						}

						float getFloat(Object key);

						boolean containsKey(Object key);

						float put(K key, float value);

						float removeFloat(Object key);

						int size();

						void defaultReturnValue(float value);

						float defaultReturnValue();

						ObjectSet<Entry<K>> object2FloatEntrySet();
					}
					""",
			"it.unimi.dsi.fastutil.objects.AbstractObject2FloatMap", """
					package it.unimi.dsi.fastutil.objects;

					public abstract class AbstractObject2FloatMap<K> implements Object2FloatMap<K>, java.io.Serializable {
						private float defaultReturnValue;

						public void defaultReturnValue(float value) {
							defaultReturnValue = value;
						}

						public float defaultReturnValue() {
							return defaultReturnValue;
						}

						public float put(K key, float value) {
							throw new UnsupportedOperationException();
						}

						public float removeFloat(Object key) {
							throw new UnsupportedOperationException();
						}

						public static class BasicEntry<K> implements Object2FloatMap.Entry<K> {
							private final K key;
							private final float value;

							public BasicEntry(K key, float value) {
								this.key = key;
								this.value = value;
							}

							public K getKey() {
								return key;
							}

							public float getFloatValue() {
								return value;
							}
						}
					}
					""",
			"it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap", """
					package it.unimi.dsi.fastutil.objects;

					import java.util.LinkedHashMap;
					import java.util.Map;

					public class Object2FloatOpenHashMap<K> extends AbstractObject2FloatMap<K> {
						private final Map<K, Float> entries = new LinkedHashMap<>();

						@Override
						public float put(K key, float value) {
							Float old = entries.put(key, value);
							return old == null ? defaultReturnValue() : old;
						}

						@Override
						public float removeFloat(Object key) {
							Float old = entries.remove(key);
							return old == null ? defaultReturnValue() : old;
						}

						public float getFloat(Object key) {
							Float value = entries.get(key);
							return value == null ? defaultReturnValue() : value;
						}

						public boolean containsKey(Object key) {
							return entries.containsKey(key);
						}

						public int size() {
							return entries.size();
						}

						public ObjectSet<Object2FloatMap.Entry<K>> object2FloatEntrySet() {
							ObjectSet<Object2FloatMap.Entry<K>> out = new ObjectOpenHashSet<>();
							entries.forEach((key, value) -> out.add(new BasicEntry<>(key, value)));
							return out;
						}
					}
					""",
			"net.minecraft.world.level.ItemLike", "package net.minecraft.world.level; public interface ItemLike { net.minecraft.world.item.Item asItem(); }",
			"net.minecraft.core.Holder", """
					package net.minecraft.core;

					import net.neoforged.neoforge.registries.datamaps.DataMapType;

					public class Holder<T> {
						private final T value;

						public Holder(T value) {
							this.value = value;
						}

						public <A> A getData(DataMapType<T, A> type) {
							return type.values.get(value);
						}
					}
					""",
			"net.neoforged.neoforge.registries.datamaps.DataMapType", """
					package net.neoforged.neoforge.registries.datamaps;

					import java.util.HashMap;
					import java.util.Map;

					public class DataMapType<R, T> {
						public final Map<R, T> values = new HashMap<>();
					}
					""",
			"net.neoforged.neoforge.registries.datamaps.builtin.Compostable",
			"package net.neoforged.neoforge.registries.datamaps.builtin; public record Compostable(float chance) { }",
			"net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps", """
					package net.neoforged.neoforge.registries.datamaps.builtin;

					import net.minecraft.world.item.Item;
					import net.neoforged.neoforge.registries.datamaps.DataMapType;

					public class NeoForgeDataMaps {
						public static final DataMapType<Item, Compostable> COMPOSTABLES = new DataMapType<>();
					}
					"""));

	static {
		STAND_INS.put("net.minecraft.world.item.Item", """
				package net.minecraft.world.item;

				import net.minecraft.core.Holder;
				import net.minecraft.world.level.ItemLike;

				public class Item implements ItemLike {
					private final String id;
					private final Holder<Item> holder = new Holder<>(this);

					public Item(String id) {
						this.id = id;
					}

					public Item asItem() {
						return this;
					}

					public Holder<Item> builtInRegistryHolder() {
						return holder;
					}

					@Override
					public String toString() {
						return id;
					}
				}
				""");
		STAND_INS.put("net.minecraft.world.item.ItemStack", """
				package net.minecraft.world.item;

				public record ItemStack(Item item) {
					public Item getItem() {
						return item;
					}
				}
				""");
		STAND_INS.put("net.minecraft.world.item.Items", """
				package net.minecraft.world.item;

				import net.minecraft.core.registries.BuiltInRegistries;
				import net.neoforged.neoforge.registries.datamaps.builtin.Compostable;
				import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;

				public class Items {
					public static final Item WHEAT_SEEDS = register("minecraft:wheat_seeds", 0.3F);
					public static final Item APPLE = register("minecraft:apple", 0.65F);
					public static final Item DIRT = register("minecraft:dirt", -1);
					public static final Item BERRY = register("fabricmod:berry", -1);

					/** Registered, and listed in NeoForge's compostables data map (which mirrors vanilla's own) when it has a chance. */
					private static Item register(String id, float chance) {
						Item item = new Item(id);
						BuiltInRegistries.ITEM.add(item);
						if (chance > 0) NeoForgeDataMaps.COMPOSTABLES.values.put(item, new Compostable(chance));
						return item;
					}
				}
				""");
		STAND_INS.put("net.minecraft.core.registries.BuiltInRegistries", """
				package net.minecraft.core.registries;

				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.world.item.Item;

				public class BuiltInRegistries {
					public static final List<Item> ITEM = new ArrayList<>();
				}
				""");
		STAND_INS.put(CompostablesFallbackInjector.COMPOSTER, """
				package net.minecraft.world.level.block;

				import it.unimi.dsi.fastutil.objects.Object2FloatMap;
				import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
				import net.minecraft.world.item.ItemStack;
				import net.minecraft.world.item.Items;
				import net.minecraft.world.level.ItemLike;
				import net.neoforged.neoforge.registries.datamaps.builtin.Compostable;
				import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;

				public class ComposterBlock {
					public static final Object2FloatMap<ItemLike> COMPOSTABLES = new Object2FloatOpenHashMap<>();
					public int level;
					public float lastChance = -1;

					/** Vanilla's. */
					public static void bootStrap() {
						COMPOSTABLES.defaultReturnValue(-1.0F);
						add(0.3F, Items.WHEAT_SEEDS);
						add(0.65F, Items.APPLE);
					}

					private static void add(float chance, ItemLike item) {
						COMPOSTABLES.put(item.asItem(), chance);
					}

					/** NeoForge's: its compostables data map, or -1. */
					public static float getValue(ItemStack stack) {
						Compostable compostable = stack.getItem().builtInRegistryHolder().getData(NeoForgeDataMaps.COMPOSTABLES);
						return compostable == null ? -1 : compostable.chance();
					}

					/** By hand. */
					public String useItemOn(ItemStack stack) {
						if (level < 8 && getValue(stack) > 0) {
							addItem(stack);
							return "composted";
						}
						return "refused";
					}

					/** By dropper. */
					public String insertItem(ItemStack stack) {
						if (level < 7 && getValue(stack) > 0) {
							addItem(stack);
							return "composted";
						}
						return "refused";
					}

					public void addItem(ItemStack stack) {
						lastChance = getValue(stack);
						level++;
					}

					/** By hopper. */
					public class InputContainer {
						public boolean canPlaceItemThroughFace(ItemStack stack) {
							return level < 7 && getValue(stack) > 0;
						}
					}
				}
				""");
		STAND_INS.put(CHUTE, """
				package org.quarry.pipes;

				import net.minecraft.world.item.Item;
				import net.minecraft.world.level.block.ComposterBlock;

				/** What a mod's pipe asks before it inserts. */
				public class Chute {
					public long insert(Item item, long amount) {
						return ComposterBlock.COMPOSTABLES.getFloat(item) > 0 ? 1 : 0;
					}
				}
				""");
		STAND_INS.put("fixture.FabricBerries", """
				package fixture;

				import net.minecraft.world.item.Items;
				import net.minecraft.world.level.block.ComposterBlock;

				/** fabric-content-registries' CompostableRegistry.add, after bootstrap. */
				public class FabricBerries {
					public static void register() {
						ComposterBlock.COMPOSTABLES.put(Items.BERRY, 0.5F);
					}
				}
				""");
	}

	@AfterEach void reset() {
		System.clearProperty(CompostablesFallbackInjector.PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelCompostables.java", Files.readString(HOOK_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	/** A game: bootstrapped, the Fabric mod's berry registered, and NeoForge's data map without the apple when asked. */
	private record Game(ClassLoader loader) {
		Object item(String name) throws Throwable {
			return InjectorExecution.getStatic(loader.loadClass("net.minecraft.world.item.Items"), name);
		}

		Object stack(String name) throws Throwable {
			return InjectorExecution.construct(loader.loadClass("net.minecraft.world.item.ItemStack"), item(name));
		}

		Object composter() throws Throwable {
			return InjectorExecution.construct(loader.loadClass(CompostablesFallbackInjector.COMPOSTER));
		}

		/** By hand, by dropper, by hopper, by Fabric pipe; then the chance the hand used. */
		List<Object> compost(String name) throws Throwable {
			Object byHand = composter(), byDropper = composter(), byHopper = composter();
			Object hopper = InjectorExecution.construct(loader.loadClass(CompostablesFallbackInjector.INPUT), byHopper);
			Object pipe = InjectorExecution.construct(loader.loadClass(CHUTE));
			return List.of(InjectorExecution.invoke(byHand, "useItemOn", stack(name)), InjectorExecution.invoke(byDropper, "insertItem", stack(name)),
					InjectorExecution.invoke(hopper, "canPlaceItemThroughFace", stack(name)), InjectorExecution.invoke(pipe, "insert", item(name), 1L),
					byHand.getClass().getField("lastChance").get(byHand));
		}

		void dropFromDataMap(String name) throws Throwable {
			Object map = InjectorExecution.getStatic(loader.loadClass("net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps"), "COMPOSTABLES");
			((Map<?, ?>) map.getClass().getField("values").get(map)).remove(item(name));
		}
	}

	private static Game game(Map<String, byte[]> classes) throws Throwable {
		ClassLoader loader = InjectorExecution.load(classes);
		InjectorExecution.invokeStatic(loader.loadClass(CompostablesFallbackInjector.COMPOSTER), "bootStrap");
		InjectorExecution.invokeStatic(loader.loadClass("fixture.FabricBerries"), "register");
		return new Game(loader);
	}

	@Test void aFabricModsCompostableGoesInEveryWay(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : TARGETS) {
			String internal = target.replace('.', '/');
			byte[] out = InjectorExecution.transform(new CompostablesFallbackInjector(), target, original.get(internal), EnvType.SERVER);
			assertNotSame(original.get(internal), out, target + " is the reviewed shape");
			classes.put(internal, out);
		}
		byte[] writes = original.get("fixture/FabricBerries");
		assertSame(writes, InjectorExecution.transform(new CompostablesFallbackInjector(), "fixture.FabricBerries", writes, EnvType.SERVER),
				"a mod that puts into the map still puts into the map");
		Game game = game(classes);
		for (String target : TARGETS) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), game.loader()), target);

		assertEquals(List.of("composted", "composted", true, 1L, 0.5F), game.compost("BERRY"),
				"a Fabric mod's berry composts by hand, dropper, hopper and pipe, at its own chance");
		assertEquals(List.of("composted", "composted", true, 1L, 0.65F), game.compost("APPLE"), "a data-map item, as NeoForge has it");
		assertEquals(List.of("refused", "refused", false, 0L, -1.0F), game.compost("DIRT"));
		game.dropFromDataMap("APPLE");
		assertEquals(List.of("refused", "refused", false, 0L, -1.0F), game.compost("APPLE"),
				"a datapack that removes the apple from the data map keeps it out, and the pipe agrees");

		Game stock = game(original);
		assertEquals(List.of("refused", "refused", false, 1L, -1.0F), stock.compost("BERRY"),
				"premise: as merged, the block never composts the berry while the pipe would insert it");
		stock.dropFromDataMap("APPLE");
		assertEquals(List.of("refused", "refused", false, 1L, -1.0F), stock.compost("APPLE"),
				"premise: as merged, the pipe and the block disagree about the removed apple");
		for (String target : TARGETS) {
			byte[] once = classes.get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new CompostablesFallbackInjector(), target, once, EnvType.SERVER),
					target + " already asks the kernel");
		}
	}

	@Test void switchedOffAllThreeClassesAreLeftAsMerged(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = compile(work);
		System.setProperty(CompostablesFallbackInjector.PROPERTY, "off");
		for (String target : TARGETS) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new CompostablesFallbackInjector(), target, bytes, EnvType.SERVER), target);
		}
	}
}
