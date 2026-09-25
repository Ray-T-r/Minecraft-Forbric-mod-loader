/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.HolderLookup;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.FuelValues;

/**
 * fabric-content-registries' fuel events, on the fuel values the merged game actually builds.
 *
 * <p>Fabric registers fuels through {@code FuelValueEvents.BUILD} and {@code EXCLUSIONS}, fired by a wrap in vanilla's
 * {@code FuelValues.vanillaBurnTimes}. The merged server never calls that: it builds its fuels from NeoForge's
 * {@code furnace_fuels} data map in {@code DataMapHooks.populateFuelValues}, as a client on a NeoForge connection does,
 * so a Fabric mod's fuel never went in a furnace. FabricFuelValuesInjector calls {@link #apply} on that builder just
 * before it is built, in Fabric's order: BUILD, vanilla's non-flammable-wood removal, EXCLUSIONS. The removal applies
 * only to what BUILD added — the data map's entries are NeoForge's decision and stay as they are.
 */
public final class KernelFabricFuel {
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static volatile boolean resolved;
	private static Object build, exclusions;
	private static Method invoker, buildMethod, exclusionsMethod;
	private static Constructor<?> context;
	private static Field values;

	private KernelFabricFuel() {
	}

	/** Called by {@code DataMapHooks.populateFuelValues} with its builder, before {@code build()}. Returns the builder. */
	public static FuelValues.Builder apply(FuelValues.Builder builder, HolderLookup.Provider registries, FeatureFlagSet features) {
		try {
			if (!resolve(builder.getClass().getClassLoader())) return builder;
			Object ctx = context.newInstance(registries, features, 200);
			@SuppressWarnings("unchecked")
			Map<Item, Integer> map = (Map<Item, Integer>) values.get(builder);
			Set<Item> before = new HashSet<>(map.keySet());
			buildMethod.invoke(invoker.invoke(build), builder, ctx);
			map.keySet().removeIf(item -> !before.contains(item) && item.builtInRegistryHolder().is(ItemTags.NON_FLAMMABLE_WOOD));
			exclusionsMethod.invoke(invoker.invoke(exclusions), builder, ctx);
		} catch (Throwable t) {
			if (WARNED.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/Fuel] fabric-content-registries' fuel events failed — Fabric mods' fuels are missing "
						+ "from this game's fuel values", Reflect.unwrap(t));
			}
		}
		return builder;
	}

	/** fabric-content-registries, once; false (for good) when it is not installed. */
	private static synchronized boolean resolve(ClassLoader loader) throws ReflectiveOperationException {
		if (resolved) return build != null;
		resolved = true;
		Class<?> events;
		try {
			events = Class.forName("net.fabricmc.fabric.api.registry.FuelValueEvents", true, loader);
		} catch (ClassNotFoundException absent) {
			return false;
		}
		ClassLoader fabric = events.getClassLoader();
		Class<?> event = Class.forName("net.fabricmc.fabric.api.event.Event", false, fabric);
		Class<?> ctx = Class.forName("net.fabricmc.fabric.api.registry.FuelValueEvents$Context", false, fabric);
		invoker = event.getMethod("invoker");
		buildMethod = Class.forName("net.fabricmc.fabric.api.registry.FuelValueEvents$BuildCallback", false, fabric)
				.getMethod("build", FuelValues.Builder.class, ctx);
		exclusionsMethod = Class.forName("net.fabricmc.fabric.api.registry.FuelValueEvents$ExclusionsCallback", false, fabric)
				.getMethod("buildExclusions", FuelValues.Builder.class, ctx);
		context = Class.forName("net.fabricmc.fabric.impl.content.registry.FuelRegistryEventsContextImpl", false, fabric)
				.getConstructor(HolderLookup.Provider.class, FeatureFlagSet.class, int.class);
		values = FuelValues.Builder.class.getDeclaredField("values");
		values.setAccessible(true);
		build = events.getField("BUILD").get(null);
		exclusions = events.getField("EXCLUSIONS").get(null);
		return true;
	}
}
