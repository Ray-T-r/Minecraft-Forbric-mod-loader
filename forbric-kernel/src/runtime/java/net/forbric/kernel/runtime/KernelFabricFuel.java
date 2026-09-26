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
 *
 * <h2>And the mixins on vanillaBurnTimes' return</h2>
 *
 * <p>Fuel events are one way a Fabric mod adds fuel; a mixin on the RETURN of {@code vanillaBurnTimes} is the other.
 * torrential puts its Angling Table in the table that way ({@code @ModifyReturnValue}, 1.5 × the base unit) and
 * Lithium runs its whole block-info pass from there. On native Fabric — and on native MinecraftForge — the server's
 * fuel table comes out of {@code vanillaBurnTimes(Provider, FeatureFlagSet)}, which calls the three-argument overload
 * with 200, so both run on it. The merged server never calls it — its table comes from {@code populateFuelValues} —
 * so the Angling Table would not burn on a Forbric server while the client, which does call it, thought it would.
 *
 * <p>{@link #throughVanillaReturnHooks} hands the table {@code populateFuelValues} built through that same
 * two-argument call as its result: the merge-added body {@code vanillaBurnTimes(Builder, int)} starts by returning
 * the {@link #takePending pending} table when there is one, so nothing of vanilla's is rebuilt and the body's own
 * anchors (fabric-content-registries' fuel events among them, already run by {@link #apply}) are skipped, while every
 * RETURN hook on each of the three overloads runs on it once, in the order the native server runs them. It is
 * Fabric's hooks applying on top of the data map's table, the way they apply on top of vanilla's natively — a hook
 * may replace entries or return another table, as it may there. A client that calls {@code vanillaBurnTimes} itself
 * has nothing pending and gets the method as shipped.
 */
public final class KernelFabricFuel {
	/** {@code off} leaves the fuel table populateFuelValues builds out of vanillaBurnTimes' return hooks. */
	public static final String RETURN_HOOKS = "forbric.fabricFuel.returnHooks";
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static final AtomicBoolean HOOKS_WARNED = new AtomicBoolean();
	private static final AtomicBoolean HOOKS_PROVED = new AtomicBoolean();
	/** The table on its way through vanillaBurnTimes, for the one body call that takes it. */
	private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();

	private static final class Pending {
		final FuelValues values;
		boolean taken;

		Pending(FuelValues values) {
			this.values = values;
		}
	}
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

	/**
	 * Called by {@code DataMapHooks.populateFuelValues} with the table it built; returns what vanillaBurnTimes' return
	 * hooks make of it.
	 *
	 * <p>The table goes back unchanged when the body's short-circuit was never reached: a carrier whose stub stopped
	 * forwarding to it would otherwise have the full vanilla table rebuilt in place of the data map's, and a mixin
	 * that cancels vanillaBurnTimes at HEAD would throw away every NeoForge and MinecraftForge fuel with it. Natively
	 * such a cancel replaces only vanilla's table; here there is more in it than vanilla's, so the data map's result
	 * stands. A hook that throws costs its own change, not the server's fuel table.
	 */
	public static FuelValues throughVanillaReturnHooks(FuelValues built, HolderLookup.Provider registries,
			FeatureFlagSet features) {
		if (built == null || "off".equalsIgnoreCase(System.getProperty(RETURN_HOOKS, "on"))) return built;
		Pending pending = new Pending(built);
		Pending outer = PENDING.get();
		PENDING.set(pending);
		try {
			// The call the native Fabric and MinecraftForge servers make, so a hook on either outer overload runs too.
			FuelValues hooked = FuelValues.vanillaBurnTimes(registries, features);
			if (!pending.taken) {
				warnHooksOnce("vanillaBurnTimes returned without reaching the merge-added body the kernel short-circuits "
						+ "(a HEAD cancel, or a carrier whose overload no longer forwards)", null);
				return built;
			}
			if (HOOKS_PROVED.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Fuel] the fuel table NeoForge's populateFuelValues built went through "
						+ "FuelValues.vanillaBurnTimes' return hooks — the merged server never calls that method, so a "
						+ "mod's mixin there (torrential's Angling Table) never reached the fuels a furnace uses");
			}
			return hooked == null ? built : hooked;
		} catch (Throwable t) {
			warnHooksOnce("vanillaBurnTimes threw on the way through its return hooks", Reflect.unwrap(t));
			return built;
		} finally {
			if (outer == null) PENDING.remove();
			else PENDING.set(outer);
		}
	}

	/**
	 * The table {@link #throughVanillaReturnHooks} is carrying, once; null when there is none. Called at the head of
	 * the merge-added {@code FuelValues.vanillaBurnTimes(Builder, int)}, which returns it as its result instead of
	 * building vanilla's. Once only, so a hook that calls vanillaBurnTimes again gets the method as shipped.
	 */
	public static FuelValues takePending() {
		Pending pending = PENDING.get();
		if (pending == null || pending.taken) return null;
		pending.taken = true;
		return pending.values;
	}

	private static void warnHooksOnce(String what, Throwable cause) {
		if (!HOOKS_WARNED.compareAndSet(false, true)) return;
		String message = "[Forbric/Fuel] " + what + " — the data map's fuel table is kept as built, and a mod's mixin "
				+ "on vanillaBurnTimes' return does not change it";
		if (cause == null) ForbricLog.warn(message);
		else ForbricLog.warn(message, cause);
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
