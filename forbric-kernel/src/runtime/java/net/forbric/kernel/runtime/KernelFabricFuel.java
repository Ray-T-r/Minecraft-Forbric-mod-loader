/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.HolderLookup;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
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
 *
 * <h2>One dispatch, one firing: the client's second table</h2>
 *
 * <p>A client on a NeoForge connection builds two fuel tables for every one the native client builds: vanilla's
 * ({@code ClientPacketListener}'s constructor and {@code handleUpdateTags} call {@code vanillaBurnTimes}, where the
 * guest's own wrap fires the events) and right after it NeoForge's from the synced data map
 * ({@code ClientHooks.handleUpdateRecipes} → {@code populateFuelValues}), which is the table the client keeps. Firing
 * the events again there, and handing the table through vanillaBurnTimes' return hooks again, made every listener and
 * every return hook run three times per reload where natively they run twice (server once, client once). The second
 * table still needs what they did — a client without the Fabric fuels or the Angling Table predicts shift-clicks into a
 * furnace wrong — so it gets the native build's result instead of a second dispatch:
 *
 * <ul>
 *   <li>FabricFuelValuesInjector brackets the native point in vanilla's body — the {@code Builder.remove(TagKey)}
 *       right before its {@code build()}, which the guest wraps — with {@link #openNativePoint} and
 *       {@link #closeNativePoint}, and makes every public mutator of {@code FuelValues.Builder} report its call
 *       ({@link #recordCall}), and its constructor the registries it was made from ({@link #builderMade}).</li>
 *   <li>While the window is open, every mutator call on the window's builder is recorded in order, the native
 *       removal among them. The window counts as a dispatch only when both public events fired on that builder inside
 *       it, which the kernel's own observers, registered through the public {@code Event.register}, see.</li>
 *   <li>{@link #apply} on a builder made from the same registries (the same object, so the integrated server's table,
 *       built from its own registries on the same thread, never pairs with the client's) replays the recorded calls on
 *       it instead of firing the events: the guests' calls verbatim, and the native removal by the rule the firing path
 *       applies — only to what the replayed calls added. Every later data-map table of the same registries gets the same
 *       result, until the next native dispatch replaces it.</li>
 *   <li>The return hooks the same way: vanilla's body reports the table it built before any hook in it
 *       ({@link #nativeBuilt}), and after weaving the two-argument overload reports entering ({@link #outerEntered})
 *       and the table it hands its caller after every hook of all three overloads ({@link #outerReturned}). What changed
 *       between the two — entries set, entries removed — is carried, and {@link #throughVanillaReturnHooks} puts that on
 *       the data-map table instead of calling the hooks again. A hook's side effect (Lithium's block-info pass) runs
 *       once per native build, as natively.</li>
 * </ul>
 *
 * <p>A server has no native point in its path and fires the events once, as before. {@code -Dforbric.fabricFuel.carry=off}
 * fires them on the client's second table again.
 */
public final class KernelFabricFuel {
	/** {@code off} leaves the fuel table populateFuelValues builds out of vanillaBurnTimes' return hooks. */
	public static final String RETURN_HOOKS = "forbric.fabricFuel.returnHooks";
	private static final AtomicBoolean HOOKS_WARNED = new AtomicBoolean();
	private static final AtomicBoolean HOOKS_PROVED = new AtomicBoolean();
	/** {@code off}: the client's data-map table has the fuel events fired on it again instead of the native dispatch's result. */
	public static final String CARRY = "forbric.fabricFuel.carry";
	private static final AtomicBoolean CARRY_PROVED = new AtomicBoolean();
	/** The table on its way through vanillaBurnTimes, for the one body call that takes it. */
	private static final ThreadLocal<Pending> PENDING = new ThreadLocal<>();
	/** The builder this thread made last, with the registries it was made from. */
	private static final ThreadLocal<Made> MADE = new ThreadLocal<>();
	/** The native point a guest dispatches the fuel events from on vanilla's own table, while it runs. */
	private static final ThreadLocal<Window> WINDOW = new ThreadLocal<>();
	/** The last native build this thread made, for the data-map table of the same registries. */
	private static final ThreadLocal<Carried> CARRIED = new ThreadLocal<>();
	/** The native table builds (two-argument vanillaBurnTimes calls) this thread is inside. */
	private static final ThreadLocal<Outer> OUTER = new ThreadLocal<>();
	private static final AtomicBoolean DELTA_PROVED = new AtomicBoolean();
	private static volatile Field featuresField, builderValuesField, tableValuesField;
	/** A builder mutator by its name and descriptor, for the replay. */
	private static final Map<String, Method> MUTATORS = new ConcurrentHashMap<>();

	private record Made(WeakReference<FuelValues.Builder> builder, WeakReference<Object> registries) {
	}

	/** One mutator call on the window's builder: {@code name + descriptor} and its arguments. */
	private record Call(String member, Object[] arguments) {
		boolean is(String nativeMember, Object nativeArgument) {
			return member.equals(nativeMember) && arguments.length == 1 && Objects.equals(arguments[0], nativeArgument);
		}
	}

	/** One native table build: the two-argument vanillaBurnTimes, entered and not yet returned. */
	private static final class Outer {
		final Outer previous;
		Window window;
		Map<Item, Integer> built;

		Outer(Outer previous) {
			this.previous = previous;
		}
	}

	/** What the native return hooks did to the native table: entries set, entries removed. */
	private record Delta(Map<Item, Integer> puts, Set<Item> removals) {
	}

	private static final class Window {
		final FuelValues.Builder builder;
		final Object registries;
		final FeatureFlagSet features;
		final String nativeMember;
		final Object nativeArgument;
		final Window outer;
		final List<Call> calls = new ArrayList<>();
		boolean built, excluded;

		Window(FuelValues.Builder builder, Object registries, FeatureFlagSet features, String nativeMember, Object nativeArgument,
				Window outer) {
			this.builder = builder;
			this.registries = registries;
			this.features = features;
			this.nativeMember = nativeMember;
			this.nativeArgument = nativeArgument;
			this.outer = outer;
		}
	}

	/**
	 * The last native build on this thread: its registries and features, whether the fuel events fired at its native point
	 * and the calls made there, and (once the build returned) what its return hooks changed.
	 */
	private record Carried(Window origin, WeakReference<Object> registries, FeatureFlagSet features, boolean dispatched,
			List<Call> calls, String nativeMember, Object nativeArgument, Delta delta) {
		Carried withDelta(Delta changes) {
			return new Carried(origin, registries, features, dispatched, calls, nativeMember, nativeArgument, changes);
		}
	}

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
	/** fabric-api's public {@code FuelValueEvents.Context}, which the kernel implements for the events it fires. */
	private static Class<?> contextApi;
	private static Field values;

	private KernelFabricFuel() {
	}

	/** Called by {@code DataMapHooks.populateFuelValues} with its builder, before {@code build()}. Returns the builder. */
	public static FuelValues.Builder apply(FuelValues.Builder builder, HolderLookup.Provider registries, FeatureFlagSet features) {
		try {
			if (!resolve(builder.getClass().getClassLoader())) return builder;
			@SuppressWarnings("unchecked")
			Map<Item, Integer> map = (Map<Item, Integer>) values.get(builder);
			Set<Item> before = new HashSet<>(map.keySet());
			Carried carried = carriedFor(registries, features);
			if (carried != null && carried.dispatched()) {
				replay(carried, builder, map, before);
				return builder;
			}
			Object ctx = context(registries, features, 200);
			buildMethod.invoke(invoker.invoke(build), builder, ctx);
			map.keySet().removeIf(item -> !before.contains(item) && item.builtInRegistryHolder().is(ItemTags.NON_FLAMMABLE_WOOD));
			exclusionsMethod.invoke(invoker.invoke(exclusions), builder, ctx);
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Fuel] fabric-content-registries' fuel events failed — Fabric mods' fuels are missing "
					+ "from this game's fuel values");
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
		Carried carried = carriedFor(registries, features);
		if (carried != null && carried.delta() != null) {
			try {
				return withDelta(built, carried.delta(), registries, features);
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/Fuel] the native return hooks' changes could not be put on the data-map table — the "
						+ "data map's fuel table is kept as built, and a mod's mixin on vanillaBurnTimes' return does not change it");
				return built;
			}
		}
		Pending pending = new Pending(built);
		Pending outer = PENDING.get();
		PENDING.set(pending);
		try {
			// The call the native Fabric and MinecraftForge servers make, so a hook on either outer overload runs too.
			FuelValues hooked = FuelValues.vanillaBurnTimes(registries, features);
			if (!pending.taken) {
				warnHooksOnce("vanillaBurnTimes returned without reaching the merge-added body the kernel short-circuits "
						+ "(a HEAD cancel, or a carrier whose overload no longer forwards)");
				return built;
			}
			if (HOOKS_PROVED.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Fuel] the fuel table NeoForge's populateFuelValues built went through "
						+ "FuelValues.vanillaBurnTimes' return hooks — the merged server never calls that method, so a "
						+ "mod's mixin there (torrential's Angling Table) never reached the fuels a furnace uses");
			}
			return hooked == null ? built : hooked;
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Fuel] vanillaBurnTimes threw on the way through its return hooks — the data map's fuel"
					+ " table is kept as built, and a mod's mixin on vanillaBurnTimes' return does not change it");
			return built;
		} finally {
			if (outer == null) PENDING.remove();
			else PENDING.set(outer);
		}
	}

	/**
	 * Called by {@code FuelValues.Builder}'s constructor with the registries it was given, so the native point in
	 * vanilla's body knows which registries its builder came from.
	 */
	public static void builderMade(FuelValues.Builder builder, Object registries) {
		MADE.set(new Made(new WeakReference<>(builder), new WeakReference<>(registries)));
	}

	/**
	 * Called right before the native point in vanilla's body — {@code builder.remove(tag)} ahead of {@code build()}, the
	 * call a guest's wrap fires the fuel events around — with that call's receiver, argument and member: opens the window
	 * in which every mutator call on {@code builder} is recorded.
	 */
	public static void openNativePoint(FuelValues.Builder builder, Object argument, String member) {
		if (!carryEnabled() || builder == null) return;
		try {
			resolve(builder.getClass().getClassLoader());   // the observers, before the guest's wrap fires anything
		} catch (Throwable absent) {
			Caught.warn(absent, "[Forbric/Fuel] could not resolve fabric-content-registries' fuel events; the return hooks are carried all the same");
			// No fabric-content-registries events to observe; the return hooks are carried all the same.
		}
		try {
			Made made = MADE.get();
			Object registries = made != null && made.builder().get() == builder ? made.registries().get() : null;
			WINDOW.set(new Window(builder, registries, (FeatureFlagSet) featuresField().get(builder), member, argument, WINDOW.get()));
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Fuel] could not open the fuel carry window; the data-map table is handed everything again");
			// Without a window the data-map table is handed everything again, as without the carry.
		}
	}

	/**
	 * Called right after the native point with what it returned: closes the window and carries it, the fuel events part
	 * only when both events fired on its builder inside it.
	 */
	public static FuelValues.Builder closeNativePoint(FuelValues.Builder result) {
		Window window = WINDOW.get();
		if (window == null) return result;
		if (window.outer == null) WINDOW.remove();
		else WINDOW.set(window.outer);
		if (window.registries == null) {
			CARRIED.remove();
			return result;
		}
		CARRIED.set(new Carried(window, new WeakReference<>(window.registries), window.features, window.built && window.excluded,
				List.copyOf(window.calls), window.nativeMember, window.nativeArgument, null));
		Outer outer = OUTER.get();
		if (outer != null && outer.window == null) outer.window = window;
		return result;
	}

	/** Called at the head of every public mutator of {@code FuelValues.Builder}: recorded while its window is open. */
	public static void recordCall(FuelValues.Builder builder, String member, Object[] arguments) {
		Window window = WINDOW.get();
		if (window != null && window.builder == builder) window.calls.add(new Call(member, arguments.clone()));
	}

	/**
	 * Called right after vanilla's body builds its table, before any return hook in it: what the body built, as the
	 * baseline the return hooks' changes are measured against.
	 */
	public static FuelValues nativeBuilt(FuelValues table) {
		Outer outer = OUTER.get();
		if (outer != null && outer.window != null && outer.built == null && table != null) {
			try {
				outer.built = snapshot(table);
			} catch (Throwable t) {
				Caught.warn(t, "[Forbric/Fuel] could not take the fuel table's baseline; the data-map table goes through the return hooks itself");
				// No baseline: the data-map table goes through the return hooks itself.
			}
		}
		return table;
	}

	/** Called at the head of the two-argument {@code vanillaBurnTimes}, after weaving: one native table build begins. */
	public static void outerEntered() {
		if (carryEnabled()) OUTER.set(new Outer(OUTER.get()));
	}

	/**
	 * Called with what the two-argument {@code vanillaBurnTimes} returns, after weaving — after every return hook of all
	 * three overloads: the return hooks' changes to the native table, carried with the window that table's build closed.
	 */
	public static FuelValues outerReturned(FuelValues table) {
		Outer outer = OUTER.get();
		if (outer == null) return table;
		if (outer.previous == null) OUTER.remove();
		else OUTER.set(outer.previous);
		Carried carried = CARRIED.get();
		if (outer.window == null || outer.built == null || table == null || carried == null || carried.origin() != outer.window) {
			return table;
		}
		try {
			Map<Item, Integer> after = snapshot(table);
			Map<Item, Integer> puts = new LinkedHashMap<>();
			after.forEach((item, time) -> {
				if (!Objects.equals(outer.built.get(item), time)) puts.put(item, time);
			});
			Set<Item> removals = new LinkedHashSet<>(outer.built.keySet());
			removals.removeAll(after.keySet());
			CARRIED.set(carried.withDelta(new Delta(puts, removals)));
		} catch (Throwable t) {
			Caught.warn(t, "[Forbric/Fuel] could not take the fuel table's delta; the data-map table goes through the return hooks itself");
			// No delta: the data-map table goes through the return hooks itself.
		}
		return table;
	}

	private static boolean carryEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(CARRY, "on"));
	}

	/** The carried native build for a data-map table made from {@code registries} with {@code features}, or null. */
	private static Carried carriedFor(Object registries, FeatureFlagSet features) {
		if (!carryEnabled() || registries == null) return null;
		Carried carried = CARRIED.get();
		return carried != null && carried.registries().get() == registries && Objects.equals(carried.features(), features)
				? carried : null;
	}

	/** The kernel's own listener on one of the two public events: marks the open window's builder as dispatched. */
	private static void observed(Object builder, boolean build) {
		Window window = WINDOW.get();
		if (window == null || window.builder != builder) return;
		if (build) window.built = true;
		else window.excluded = true;
	}

	/**
	 * The native dispatch's calls, on the data-map builder: the guests' verbatim, in their order; the native removal by
	 * the rule the firing path applies — only to what the replayed calls added, since what the data map put there is
	 * NeoForge's decision.
	 */
	@SuppressWarnings("unchecked")
	private static void replay(Carried carried, FuelValues.Builder builder, Map<Item, Integer> map, Set<Item> before)
			throws ReflectiveOperationException {
		boolean nativeSeen = false;
		for (Call call : carried.calls()) {
			if (!nativeSeen && call.is(carried.nativeMember(), carried.nativeArgument())) {
				nativeSeen = true;
				if (carried.nativeArgument() instanceof TagKey<?> tag) {
					map.keySet().removeIf(item -> !before.contains(item) && item.builtInRegistryHolder().is((TagKey<Item>) tag));
				}
				continue;
			}
			mutator(call.member()).invoke(builder, call.arguments());
		}
		if (CARRY_PROVED.compareAndSet(false, true)) {
			ForbricLog.info("[Forbric/Fuel] the data-map fuel table got the result of the native fuel-event dispatch (%d "
					+ "builder call(s) replayed) instead of a second dispatch — each table the native game builds fires "
					+ "FuelValueEvents once", carried.calls().size());
		}
	}

	/** {@code built} with the native return hooks' changes, as a new table: the data map's entries, then theirs. */
	@SuppressWarnings("unchecked")
	private static FuelValues withDelta(FuelValues built, Delta delta, HolderLookup.Provider registries, FeatureFlagSet features)
			throws ReflectiveOperationException {
		FuelValues.Builder builder = new FuelValues.Builder(registries, features);
		Map<Item, Integer> map = (Map<Item, Integer>) builderValues().get(builder);
		map.putAll(snapshot(built));
		map.keySet().removeAll(delta.removals());
		map.putAll(delta.puts());
		if (DELTA_PROVED.compareAndSet(false, true)) {
			ForbricLog.info("[Forbric/Fuel] the data-map fuel table got what vanillaBurnTimes' return hooks did to the native "
					+ "table (%d entr(y/ies) set, %d removed) instead of running those hooks a second time", delta.puts().size(),
					delta.removals().size());
		}
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	private static Map<Item, Integer> snapshot(FuelValues table) throws ReflectiveOperationException {
		return new LinkedHashMap<>((Map<Item, Integer>) tableValues().get(table));
	}

	private static Method mutator(String member) throws NoSuchMethodException {
		Method found = MUTATORS.get(member);
		if (found != null) return found;
		for (Method method : FuelValues.Builder.class.getMethods()) {
			String key = method.getName() + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).toMethodDescriptorString();
			MUTATORS.putIfAbsent(key, method);
		}
		found = MUTATORS.get(member);
		if (found == null) throw new NoSuchMethodException(member);
		return found;
	}

	private static Field featuresField() throws NoSuchFieldException {
		Field f = featuresField;
		if (f == null) {
			f = FuelValues.Builder.class.getDeclaredField("enabledFeatures");
			f.setAccessible(true);
			featuresField = f;
		}
		return f;
	}

	private static Field builderValues() throws NoSuchFieldException {
		Field f = builderValuesField;
		if (f == null) {
			f = FuelValues.Builder.class.getDeclaredField("values");
			f.setAccessible(true);
			builderValuesField = f;
		}
		return f;
	}

	private static Field tableValues() throws NoSuchFieldException {
		Field f = tableValuesField;
		if (f == null) {
			f = FuelValues.class.getDeclaredField("values");
			f.setAccessible(true);
			tableValuesField = f;
		}
		return f;
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

	private static void warnHooksOnce(String what) {
		if (!HOOKS_WARNED.compareAndSet(false, true)) return;
		ForbricLog.warn("[Forbric/Fuel] " + what + " — the data map's fuel table is kept as built, and a mod's mixin "
				+ "on vanillaBurnTimes' return does not change it");
	}

	private static Object observer(Class<?> callback, String method, boolean build) {
		return java.lang.reflect.Proxy.newProxyInstance(callback.getClassLoader(), new Class<?>[] {callback},
				(proxy, invoked, args) -> {
					if (invoked.getName().equals(method) && args != null && args.length > 0) {
						observed(args[0], build);
						return null;
					}
					return switch (invoked.getName()) {
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == args[0];
						case "toString" -> "Forbric fuel-dispatch observer";
						default -> null;
					};
				});
	}

	/**
	 * The context the fuel events are handed, as fabric-api's public {@code FuelValueEvents.Context} describes it: the
	 * registries, the enabled features and the base smelt time — a {@link KernelFuelValueContext}, the kernel's own
	 * record implementing that interface, so no class of fabric-api's own implementation is named and a listener gets a
	 * real class, not a proxy. Reached only after {@link #resolve} found the interface, which the record's linkage needs.
	 */
	private static Object context(HolderLookup.Provider registries, FeatureFlagSet features, int baseSmeltTime) {
		Object context = KernelFuelValueContext.of(registries, features, baseSmeltTime);
		if (!contextApi.isInstance(context)) {
			// The record's superinterface was resolved by another loader than the one the events take their context
			// from: hand them nothing they cannot cast rather than a ClassCastException inside a mod's listener.
			throw new IllegalStateException("the kernel's fuel context implements a FuelValueEvents.Context from "
					+ context.getClass().getInterfaces()[0].getClassLoader() + ", the events take one from "
					+ contextApi.getClassLoader());
		}
		return context;
	}

	/** fabric-content-registries, once; false (for good) when it is not installed. */
	private static synchronized boolean resolve(ClassLoader loader) throws ReflectiveOperationException {
		if (resolved) return build != null;
		resolved = true;
		Class<?> events;
		try {
			events = Class.forName("net.fabricmc.fabric.api.registry.FuelValueEvents", true, loader);
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
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
		contextApi = ctx;
		values = FuelValues.Builder.class.getDeclaredField("values");
		values.setAccessible(true);
		build = events.getField("BUILD").get(null);
		exclusions = events.getField("EXCLUSIONS").get(null);
		// The kernel's observers, through the public Event.register: they see each dispatch, never change it.
		Method register = event.getMethod("register", Object.class);
		Class<?> buildCallback = buildMethod.getDeclaringClass(), exclusionsCallback = exclusionsMethod.getDeclaringClass();
		register.invoke(build, observer(buildCallback, buildMethod.getName(), true));
		register.invoke(exclusions, observer(exclusionsCallback, exclusionsMethod.getName(), false));
		return true;
	}
}
