/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * A Fabric mod's {@code PoiTypes.registerBlockStates}, on NeoForge's base, where NeoForge has already done it.
 *
 * <p>Vanilla maps a point-of-interest type's block states in two steps: {@code Registry.register} adds the type, then
 * {@code PoiTypes.registerBlockStates(holder, states)} puts each state into {@code TYPE_BY_STATE} and throws
 * "{@code <state> is defined in more than one PoI type}" when a state already had a type. On native Fabric nothing else
 * writes that map, so a Fabric mod registering a type does exactly those two steps (fabric-api's {@code PoiHelper}
 * through vanilla's {@code PoiTypes.register}, Balm through its own {@code @Invoker} of {@code registerBlockStates},
 * pillager_deterrent through its own {@code @Invoker} of {@code register}, friendsandfoes through its accessor).
 *
 * <p>NeoForge moved the second step into the registry: {@code NeoForgeRegistriesSetup} attaches
 * {@code NeoForgeRegistryCallbacks.PoiTypeCallbacks} to {@code POINT_OF_INTEREST_TYPE} after vanilla's bootstrap, and its
 * {@code onAdd} puts every matching state into {@code BLOCKSTATE_TO_POI_TYPE_MAP} — which is {@code TYPE_BY_STATE}
 * ({@code PoiTypes.<clinit>} stores {@code GameData.getBlockStatePointOfInterestTypeMap()}). NeoForge mods only register;
 * they never call {@code registerBlockStates}. A Fabric mod registering in the kernel's registration window gets
 * NeoForge's mapping on the add and then makes vanilla's own call, whose first {@code put} finds the state already
 * there — under the very same holder — and throws. Waystones-fabric's main entrypoint died there.
 *
 * <p>{@link #beforeRegisterBlockStates} runs at the head of that method, and vanilla's body then runs as it is (with any
 * mixin a mod put on it). For a Fabric mod's call it hands back the mappings NeoForge's callback made for this very
 * holder that no {@code registerBlockStates} call has named yet, so vanilla's body maps them itself, as on Fabric.
 * Anything else stays in place, so vanilla's own throw still answers a state another type holds, or states a mod maps
 * twice. For code that is not a Fabric mod's it changes nothing: NeoForge's behaviour, where such a call after the
 * callback is attached throws.
 *
 * <p>NeoForge's own writers assume {@code TYPE_BY_STATE} is their map. A Fabric mod may replace the field (lithium-fabric
 * swaps in a copy at the end of {@code <clinit>}; lithium-neoforge does not); NeoForge's callback and
 * {@code PoiTypeExtender} would then write a map nobody reads, and every PoI type a NeoForge mod registers would be
 * invisible to {@code PoiTypes.forState}. {@link #liveTable} gives those writers the table {@code PoiTypes} reads.
 */
public final class KernelPoiStates {
	private static final String POI_TYPES = "net.minecraft.world.entity.ai.village.poi.PoiTypes";
	private static final StackWalker CALLER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

	/**
	 * Every state a {@code registerBlockStates} call has named, whoever made it. A mapping of a state not in here was
	 * made by NeoForge's callback; a mapping of one in here is a registerBlockStates call's own.
	 */
	private static final Set<Object> NAMED = Collections.newSetFromMap(new IdentityHashMap<>());
	private static int takenOver;

	/** Whether a class is a Fabric mod's code; substitutable so a unit test needs no kernel class loader. */
	private static volatile Predicate<Class<?>> fabricCode = KernelPoiStates::loadedAsFabric;

	private static volatile Field table;
	private static volatile boolean tableUnreadable;
	private static volatile Object toldOfTable;

	private KernelPoiStates() {
	}

	/** How many callback mappings Fabric callers have taken over so far. */
	static int takenOver() {
		synchronized (NAMED) {
			return takenOver;
		}
	}

	/**
	 * Called at the head of {@code PoiTypes.registerBlockStates(Holder, Set)} with {@code TYPE_BY_STATE}; vanilla's body
	 * runs after it. For a Fabric mod's call, removes the mappings NeoForge's callback made to {@code poi} for
	 * {@code states} that no earlier call named, so vanilla's body puts them again instead of throwing on them.
	 */
	public static void beforeRegisterBlockStates(Map<Object, Object> typeByState, Object poi, Set<?> states) {
		synchronized (NAMED) {
			List<Object> callbacks = null;
			for (Object state : states) {
				if (NAMED.add(state) && typeByState.get(state) == poi) {
					if (callbacks == null) callbacks = new ArrayList<>();
					callbacks.add(state);
				}
			}
			if (callbacks == null) return; // nothing the callback made: vanilla's body decides, as everywhere
			Class<?> caller = caller();
			if (caller == null || !fabricCode.test(caller)) return; // NeoForge's own behaviour: vanilla's body throws
			for (Object state : callbacks) typeByState.remove(state, poi);
			takenOver += callbacks.size();
			ForbricLog.info("[Forbric/PoI] %s maps %d block state(s) NeoForge's registry callback already gave %s — the same "
					+ "mapping, made when the type was added; the callback's copies are handed back so vanilla's "
					+ "registerBlockStates maps them as on Fabric instead of throwing \"defined in more than one PoI type\"",
					caller.getName(), callbacks.size(), poi);
		}
	}

	/**
	 * The table {@code PoiTypes} reads, for NeoForge's writers of {@code BLOCKSTATE_TO_POI_TYPE_MAP}: that map itself
	 * while it is {@code TYPE_BY_STATE} (always, unless a mod replaced the field), else the table that replaced it.
	 */
	public static Map<Object, Object> liveTable(Map<Object, Object> neoForges) {
		Map<Object, Object> live = typeByState();
		if (live == null || live == neoForges) return neoForges;
		if (toldOfTable != live) {
			toldOfTable = live;
			ForbricLog.info("[Forbric/PoI] PoiTypes.TYPE_BY_STATE is no longer NeoForge's map (a mod replaced it with a %s); "
					+ "NeoForge's PoI type callback and PoiTypeExtender write into that table instead, so the PoI types NeoForge "
					+ "mods register stay visible to PoiTypes.forState", live.getClass().getName());
		}
		return live;
	}

	@SuppressWarnings("unchecked")
	private static Map<Object, Object> typeByState() {
		if (tableUnreadable) return null;
		try {
			Field field = table;
			if (field == null) {
				field = Class.forName(POI_TYPES, false, KernelPoiStates.class.getClassLoader()).getDeclaredField("TYPE_BY_STATE");
				field.setAccessible(true);
				table = field;
			}
			return (Map<Object, Object>) field.get(null);
		} catch (ReflectiveOperationException | RuntimeException unreadable) {
			tableUnreadable = true;
			Caught.warn(unreadable, "[Forbric/PoI] cannot read PoiTypes.TYPE_BY_STATE — NeoForge's PoI writers keep writing "
					+ "their own map, which is that table unless a mod replaced it");
			return null;
		}
	}

	/** The first frame that is neither this class nor {@code PoiTypes} (vanilla's register, or a mod's invoker in it). */
	private static Class<?> caller() {
		return CALLER.walk(frames -> frames
				.map(StackWalker.StackFrame::getDeclaringClass)
				.filter(type -> type != KernelPoiStates.class && !POI_TYPES.equals(type.getName()))
				.findFirst()
				.orElse(null));
	}

	/** Whether the kernel loader's ecosystem record says a class is a Fabric mod's. */
	static boolean loadedAsFabric(Class<?> type) {
		return type.getClassLoader() instanceof ForbricClassLoader loader && loader.ecosystemOfClass(type) == Ecosystem.FABRIC;
	}

	/** Test seam: which classes count as a Fabric mod's. */
	static void bindFabricCodeForTest(Predicate<Class<?>> isFabricCode) {
		fabricCode = isFabricCode;
	}

	/** Test seam: forget every named state and the table read. */
	static void resetForTest() {
		synchronized (NAMED) {
			NAMED.clear();
			takenOver = 0;
		}
		table = null;
		tableUnreadable = false;
		toldOfTable = null;
	}
}
