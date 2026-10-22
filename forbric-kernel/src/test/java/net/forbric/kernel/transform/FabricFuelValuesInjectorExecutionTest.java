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
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;

/**
 * {@link FabricFuelValuesInjector}'s output, run: the fuel table the merged server builds in NeoForge's
 * {@code DataMapHooks.populateFuelValues} carries a Fabric mod's fuels ({@code FuelValueEvents.BUILD}, less its
 * {@code EXCLUSIONS}) and what a mod's hook on {@code vanillaBurnTimes}' return adds (torrential's Angling Table) — where
 * as merged it held NeoForge's data map alone, and neither ever went in a furnace.
 *
 * <p>The hook is the kernel's real {@code KernelFabricFuel}, compiled from {@code src/runtime/java} against stand-ins for
 * fabric-content-registries' events and context, items and tags, NeoForge's {@code DataMapHooks}, and a
 * {@code FuelValues} whose two vanilla overloads forward to the merge-added {@code vanillaBurnTimes(Builder, int)}.
 * torrential's RETURN injector is modelled as Mixin would weave it after the kernel's chain: a call ahead of that body's
 * one {@code areturn}.
 */
@ExecutesInjector(FabricFuelValuesInjector.class)
@ResourceLock("system-properties")
class FabricFuelValuesInjectorExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFabricFuel.java");
	/** The context the hook hands the events: the kernel's own record of the public interface. */
	private static final Path CONTEXT_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelFuelValueContext.java");
	private static final String HOOKS = FabricFuelValuesInjector.HOOKS;
	private static final String FUEL = FabricFuelValuesInjector.FUEL_VALUES_CLASS;

	private static final Map<String, String> STAND_INS = new HashMap<>(Map.of(
			"net.minecraft.core.HolderLookup", "package net.minecraft.core; public interface HolderLookup { interface Provider { } }",
			"net.minecraft.core.RegistryAccess", "package net.minecraft.core; public interface RegistryAccess extends HolderLookup.Provider { }",
			"net.minecraft.world.flag.FeatureFlagSet", "package net.minecraft.world.flag; public class FeatureFlagSet { }",
			"net.minecraft.tags.TagKey", "package net.minecraft.tags; public record TagKey<T>(String id) { }",
			"net.minecraft.tags.ItemTags", """
					package net.minecraft.tags;

					import net.minecraft.world.item.Item;

					public class ItemTags {
						public static final TagKey<Item> NON_FLAMMABLE_WOOD = new TagKey<>("minecraft:non_flammable_wood");
					}
					""",
			"net.minecraft.core.Holder", """
					package net.minecraft.core;

					import java.util.Set;
					import net.minecraft.tags.TagKey;

					public record Holder<T>(Set<TagKey<T>> tags) {
						public boolean is(TagKey<T> tag) {
							return tags.contains(tag);
						}
					}
					""",
			"net.minecraft.world.item.Item", """
					package net.minecraft.world.item;

					import java.util.Set;
					import net.minecraft.core.Holder;
					import net.minecraft.tags.TagKey;

					public class Item {
						private final String id;
						private final Holder<Item> holder;

						public Item(String id, Set<TagKey<Item>> tags) {
							this.id = id;
							this.holder = new Holder<>(tags);
						}

						public Holder<Item> builtInRegistryHolder() {
							return holder;
						}

						@Override
						public String toString() {
							return id;
						}
					}
					""",
			"net.minecraft.world.item.Items", """
					package net.minecraft.world.item;

					import java.util.Set;
					import net.minecraft.tags.ItemTags;

					public class Items {
						public static final Item COAL = new Item("minecraft:coal", Set.of());
						public static final Item BAMBOO = new Item("minecraft:bamboo", Set.of());
						public static final Item CRIMSON_PLANKS = new Item("minecraft:crimson_planks", Set.of(ItemTags.NON_FLAMMABLE_WOOD));
						public static final Item PEAT = new Item("fabricmod:peat", Set.of());
						public static final Item ANGLING_TABLE = new Item("torrential:angling_table", Set.of());
					}
					""",
			FUEL, """
					package net.minecraft.world.level.block.entity;

					import java.util.LinkedHashMap;
					import java.util.Map;
					import net.minecraft.core.HolderLookup;
					import net.minecraft.world.flag.FeatureFlagSet;
					import net.minecraft.world.item.Item;
					import net.minecraft.world.item.Items;

					public class FuelValues {
						public final Map<Item, Integer> values;

						FuelValues(Map<Item, Integer> values) {
							this.values = values;
						}

						/** What native Fabric and MinecraftForge servers call. */
						public static FuelValues vanillaBurnTimes(HolderLookup.Provider registries, FeatureFlagSet features) {
							return vanillaBurnTimes(registries, features, 200);
						}

						public static FuelValues vanillaBurnTimes(HolderLookup.Provider registries, FeatureFlagSet features, int smeltingTime) {
							return vanillaBurnTimes(new Builder(registries, features), smeltingTime);
						}

						/** The merge-added body the forwarding overload calls: vanilla's table. */
						public static FuelValues vanillaBurnTimes(Builder builder, int smeltingTime) {
							builder.add(Items.COAL, smeltingTime * 8);
							return builder.build();
						}

						public static class Builder {
							private final Map<Item, Integer> values = new LinkedHashMap<>();

							public Builder(HolderLookup.Provider registries, FeatureFlagSet features) {
							}

							public Builder add(Item item, int time) {
								values.put(item, time);
								return this;
							}

							public Builder remove(Item item) {
								values.remove(item);
								return this;
							}

							public FuelValues build() {
								return new FuelValues(new LinkedHashMap<>(values));
							}
						}
					}
					""",
			HOOKS, """
					package net.neoforged.neoforge.common;

					import net.minecraft.core.RegistryAccess;
					import net.minecraft.world.flag.FeatureFlagSet;
					import net.minecraft.world.item.Items;
					import net.minecraft.world.level.block.entity.FuelValues;

					public class DataMapHooks {
						/** NeoForge's: the furnace fuels data map, which the merged server builds its fuels from. */
						public static FuelValues populateFuelValues(RegistryAccess registries, FeatureFlagSet features) {
							FuelValues.Builder builder = new FuelValues.Builder(registries, features);
							builder.add(Items.COAL, 1600);
							builder.add(Items.BAMBOO, 50);
							return builder.build();
						}
					}
					"""));

	static {
		STAND_INS.put("net.fabricmc.fabric.api.event.Event", """
				package net.fabricmc.fabric.api.event;

				import java.util.ArrayList;
				import java.util.List;
				import java.util.function.Function;

				public class Event<T> {
					private final List<T> listeners = new ArrayList<>();
					private final Function<List<T>, T> factory;

					public Event(Function<List<T>, T> factory) {
						this.factory = factory;
					}

					public void register(T listener) {
						listeners.add(listener);
					}

					public T invoker() {
						return factory.apply(List.copyOf(listeners));
					}
				}
				""");
		STAND_INS.put("net.fabricmc.fabric.api.registry.FuelValueEvents", """
				package net.fabricmc.fabric.api.registry;

				import net.fabricmc.fabric.api.event.Event;
				import net.minecraft.world.level.block.entity.FuelValues;

				public final class FuelValueEvents {
					/** The public interface, all three methods of it, as fabric-content-registries declares it. */
					public interface Context {
						int baseSmeltTime();

						net.minecraft.core.HolderLookup.Provider registries();

						net.minecraft.world.flag.FeatureFlagSet enabledFeatures();
					}

					public interface BuildCallback {
						void build(FuelValues.Builder builder, Context context);
					}

					public interface ExclusionsCallback {
						void buildExclusions(FuelValues.Builder builder, Context context);
					}

					public static final Event<BuildCallback> BUILD = new Event<>(listeners -> (builder, context) -> {
						for (BuildCallback listener : listeners) listener.build(builder, context);
					});
					public static final Event<ExclusionsCallback> EXCLUSIONS = new Event<>(listeners -> (builder, context) -> {
						for (ExclusionsCallback listener : listeners) listener.buildExclusions(builder, context);
					});
				}
				""");
		// No class of fabric-api's own implementation: the kernel hands the events a context of its own, implementing the
		// public FuelValueEvents.Context.
		STAND_INS.put("fixture.FabricFuels", """
				package fixture;

				import net.fabricmc.fabric.api.registry.FuelValueEvents;
				import net.minecraft.world.item.Items;

				/** A Fabric mod: peat burns, and so would crimson planks; bamboo is excluded. */
				public class FabricFuels {
					/** Every context the events handed this mod, in order. */
					public static final java.util.List<FuelValueEvents.Context> SEEN = new java.util.ArrayList<>();

					public static void register() {
						FuelValueEvents.BUILD.register((builder, context) -> {
							SEEN.add(context);
							builder.add(Items.PEAT, context.baseSmeltTime() * 4);
							builder.add(Items.CRIMSON_PLANKS, 300);
						});
						FuelValueEvents.EXCLUSIONS.register((builder, context) -> {
							SEEN.add(context);
							builder.remove(Items.BAMBOO);
						});
					}
				}
				""");
		STAND_INS.put("fixture.Torrential", """
				package fixture;

				import net.minecraft.world.item.Items;
				import net.minecraft.world.level.block.entity.FuelValues;

				/** torrential's mixin on vanillaBurnTimes' return: the Angling Table burns. */
				public class Torrential {
					public static FuelValues onReturn(FuelValues table) {
						table.values.put(Items.ANGLING_TABLE, 300);
						return table;
					}
				}
				""");
	}

	@AfterEach void reset() {
		System.clearProperty(FabricFuelValuesInjector.PROPERTY);
		System.clearProperty(FabricFuelValuesInjector.RETURN_HOOKS_PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelFabricFuel.java", Files.readString(HOOK_SOURCE));
		sources.put("net/forbric/kernel/runtime/KernelFuelValueContext.java", Files.readString(CONTEXT_SOURCE));
		return InjectorExecution.compile(work, sources);
	}

	/** torrential's RETURN injector, as Mixin weaves it into the merge-added body after the kernel's chain. */
	private static byte[] withTorrential(byte[] fuelValues) {
		ClassNode node = new ClassNode();
		new ClassReader(fuelValues).accept(node, 0);
		MethodNode body = node.methods.stream().filter(m -> m.name.equals("vanillaBurnTimes") && m.desc.equals(FabricFuelValuesInjector.BODY_DESC))
				.findFirst().orElseThrow();
		for (AbstractInsnNode insn : body.instructions.toArray()) {
			if (insn.getOpcode() == Opcodes.ARETURN) {
				body.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, "fixture/Torrential", "onReturn",
						"(L" + FabricFuelValuesInjector.FUEL_VALUES + ";)L" + FabricFuelValuesInjector.FUEL_VALUES + ";", false));
			}
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The fuels the server builds, by item id, with the Fabric mod registered. */
	private static Map<String, Object> serverFuels(ClassLoader loader) throws Throwable {
		InjectorExecution.invokeStatic(loader.loadClass("fixture.FabricFuels"), "register");
		Object registries = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {loader.loadClass("net.minecraft.core.RegistryAccess")},
				(proxy, method, args) -> method.getName().equals("hashCode") ? 0 : null);
		Object features = InjectorExecution.construct(loader.loadClass("net.minecraft.world.flag.FeatureFlagSet"));
		Object table = InjectorExecution.invokeStatic(loader.loadClass(HOOKS), "populateFuelValues", registries, features);
		Map<String, Object> fuels = new java.util.TreeMap<>();
		((Map<?, ?>) table.getClass().getField("values").get(table)).forEach((item, time) -> fuels.put(item.toString(), time));
		return fuels;
	}

	private static Map<String, byte[]> transformed(Map<String, byte[]> original, List<String> targets) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : targets) {
			String internal = target.replace('.', '/');
			classes.put(internal, InjectorExecution.transform(new FabricFuelValuesInjector(), target, original.get(internal), EnvType.SERVER));
		}
		return classes;
	}

	@Test void aFabricModsFuelsAndAReturnHooksFuelReachTheServersTable(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		Map<String, byte[]> classes = transformed(original, List.of(HOOKS, FUEL));
		for (String target : List.of(HOOKS, FUEL)) assertNotSame(original.get(target.replace('.', '/')), classes.get(target.replace('.', '/')), target);
		String fuel = FabricFuelValuesInjector.FUEL_VALUES;
		classes.put(fuel, withTorrential(classes.get(fuel)));
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : List.of(HOOKS, FUEL)) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);

		assertEquals(Map.of("minecraft:coal", 1600, "fabricmod:peat", 800, "torrential:angling_table", 300), serverFuels(loader),
				"the data map's coal, the Fabric mod's peat (not its non-flammable planks, not the bamboo it excluded), and the Angling Table");

		Map<String, byte[]> stockClasses = new HashMap<>(original);
		stockClasses.put(fuel, withTorrential(original.get(fuel)));
		assertEquals(Map.of("minecraft:bamboo", 50, "minecraft:coal", 1600), serverFuels(InjectorExecution.load(stockClasses)),
				"premise: as merged, the server's fuels are NeoForge's data map alone");
		for (String target : List.of(HOOKS, FUEL)) {
			byte[] once = transformed(original, List.of(target)).get(target.replace('.', '/'));
			assertSame(once, InjectorExecution.transform(new FabricFuelValuesInjector(), target, once, EnvType.SERVER), target + " is edited once");
		}
	}

	/**
	 * The context a listener is handed is a real class implementing the public {@code FuelValueEvents.Context}, never a
	 * {@code java.lang.reflect.Proxy}, and a value as fabric-content-registries' own record is: the same registries,
	 * features and smelt time on both events of one dispatch, and equal across them.
	 */
	@Test void theFuelEventsAreHandedARealContextNotAProxy(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = transformed(compile(work), List.of(HOOKS, FUEL));
		ClassLoader loader = InjectorExecution.load(classes);
		serverFuels(loader);
		List<?> seen = (List<?>) loader.loadClass("fixture.FabricFuels").getField("SEEN").get(null);
		assertEquals(2, seen.size(), "BUILD and EXCLUSIONS each handed one context: " + seen);
		Class<?> api = loader.loadClass("net.fabricmc.fabric.api.registry.FuelValueEvents$Context");
		for (Object context : seen) {
			assertFalse(java.lang.reflect.Proxy.isProxyClass(context.getClass()), "a proxy: " + context.getClass());
			assertTrue(api.isInstance(context), "implements the public interface");
			assertTrue(context.getClass().isRecord(), "a value, as Fabric's own context is: " + context.getClass());
			assertEquals("net.forbric.kernel.runtime.KernelFuelValueContext", context.getClass().getName(),
					"the kernel's class, not one of fabric-api's implementation");
			assertEquals(200, api.getMethod("baseSmeltTime").invoke(context));
			assertNotNull(api.getMethod("registries").invoke(context), "the method's own registries");
			assertNotNull(api.getMethod("enabledFeatures").invoke(context), "the method's own features");
		}
		assertEquals(seen.get(0), seen.get(1), "equal by value across the two events of one dispatch");
		assertEquals(seen.get(0).hashCode(), seen.get(1).hashCode());
	}

	@Test void withoutTheReturnHooksTheFabricFuelsStillGoIn(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = compile(work);
		System.setProperty(FabricFuelValuesInjector.RETURN_HOOKS_PROPERTY, "off");
		Map<String, byte[]> classes = transformed(original, List.of(HOOKS, FUEL));
		assertSame(original.get(FabricFuelValuesInjector.FUEL_VALUES), classes.get(FabricFuelValuesInjector.FUEL_VALUES),
				"the body is not short-circuited");
		classes.put(FabricFuelValuesInjector.FUEL_VALUES, withTorrential(classes.get(FabricFuelValuesInjector.FUEL_VALUES)));
		assertEquals(Map.of("minecraft:coal", 1600, "fabricmod:peat", 800), serverFuels(InjectorExecution.load(classes)),
				"Fabric's events still run; the return hook's fuel does not reach the server's table");
	}

	@Test void switchedOffBothClassesAreLeftAsShipped(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = compile(work);
		System.setProperty(FabricFuelValuesInjector.PROPERTY, "off");
		for (String target : List.of(HOOKS, FUEL)) {
			byte[] bytes = original.get(target.replace('.', '/'));
			assertSame(bytes, InjectorExecution.transform(new FabricFuelValuesInjector(), target, bytes, EnvType.SERVER), target);
		}
	}
}
