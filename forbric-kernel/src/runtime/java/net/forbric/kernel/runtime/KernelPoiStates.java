/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * A Fabric mod's {@code PoiTypes.registerBlockStates}, on NeoForge's base, where NeoForge has already done it.
 *
 * <p>Vanilla maps a point-of-interest type's block states in two steps: {@code Registry.register} adds the type, then
 * {@code PoiTypes.registerBlockStates(holder, states)} puts each state into {@code TYPE_BY_STATE} and throws
 * "{@code <state> is defined in more than one PoI type}" when a state already had a type. On native Fabric nothing else
 * writes that map, so a Fabric mod registering a type does exactly those two steps (fabric-api's {@code PoiHelper}
 * through vanilla's {@code PoiTypes.register}, Balm through its own {@code @Invoker} of {@code registerBlockStates}).
 *
 * <p>NeoForge moved the second step into the registry: {@code NeoForgeRegistriesSetup} attaches
 * {@code NeoForgeRegistryCallbacks.PoiTypeCallbacks} to {@code POINT_OF_INTEREST_TYPE} after vanilla's bootstrap, and its
 * {@code onAdd} puts every matching state into {@code BLOCKSTATE_TO_POI_TYPE_MAP} — which IS {@code TYPE_BY_STATE}
 * ({@code GameData.getBlockStatePointOfInterestTypeMap()}). NeoForge mods only register; they never call
 * {@code registerBlockStates}. A Fabric mod registering in the kernel's registration window gets NeoForge's mapping on
 * the add and then makes vanilla's own call, whose first {@code put} finds the state already there — under the very
 * same holder — and throws. Waystones-fabric's main entrypoint died there and with it every block, item and feature it
 * registers after its PoI types (the second of its two types was never mapped at all).
 *
 * <p>So for a Fabric caller a state NeoForge's callback already gave this holder is that registration's own mapping,
 * made one step early, and is taken over rather than refused. Each state is taken over once: a Fabric mod mapping the
 * same states twice, or a state another type holds, still throws as it does on Fabric. Code that is not a Fabric mod's
 * keeps vanilla's body unchanged ({@link #registerBlockStates} returns false), which is NeoForge's behaviour: NeoForge
 * itself has no such call after its callback is attached.
 */
public final class KernelPoiStates {
	private static final String POI_TYPES = "net.minecraft.world.entity.ai.village.poi.PoiTypes";
	private static final StackWalker CALLER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

	/** States a Fabric mod's own {@code registerBlockStates} has mapped or taken over; a second claim is a real conflict. */
	private static final Set<Object> CLAIMED = Collections.newSetFromMap(new IdentityHashMap<>());
	private static int takenOver;

	/** How many states Fabric callers have taken over from NeoForge's callback so far. */
	static int takenOver() {
		synchronized (CLAIMED) {
			return takenOver;
		}
	}

	/** Whether a class is a Fabric mod's code; substitutable so a unit test needs no kernel class loader. */
	private static volatile Predicate<Class<?>> fabricCode = KernelPoiStates::loadedAsFabric;

	private KernelPoiStates() {
	}

	/**
	 * Called at the head of {@code PoiTypes.registerBlockStates(Holder, Set)} with {@code TYPE_BY_STATE}. Returns true
	 * when it mapped the states itself (a Fabric caller), false to let vanilla's body run (anyone else).
	 */
	public static boolean registerBlockStates(Map<Object, Object> typeByState, Object poi, Set<?> states) {
		Class<?> caller = caller();
		if (caller == null || !fabricCode.test(caller)) return false;
		int adopted = 0;
		synchronized (CLAIMED) {
			for (Object state : states) {
				Object old = typeByState.put(state, poi);
				boolean first = CLAIMED.add(state);
				if (old == null) continue;
				if (old == poi && first) {
					adopted++;
					continue;
				}
				// Vanilla's message; Util.pauseInIde only matters in a development IDE.
				throw new IllegalStateException(String.format(Locale.ROOT, "%s is defined in more than one PoI type", state));
			}
			if (adopted > 0) {
				takenOver += adopted;
				ForbricLog.info("[Forbric/PoI] %s maps %d block state(s) NeoForge's registry callback already gave %s — the same "
						+ "mapping, made when the type was added; a Fabric mod makes it itself with PoiTypes.registerBlockStates, "
						+ "which threw \"defined in more than one PoI type\" on its own states", caller.getName(), adopted, poi);
			}
		}
		return true;
	}

	/** The first frame that is neither this class nor {@code PoiTypes} (vanilla's register, or a mod's invoker in it). */
	private static Class<?> caller() {
		return CALLER.walk(frames -> frames
				.map(StackWalker.StackFrame::getDeclaringClass)
				.filter(type -> type != KernelPoiStates.class && !POI_TYPES.equals(type.getName()))
				.findFirst()
				.orElse(null));
	}

	private static boolean loadedAsFabric(Class<?> type) {
		return type.getClassLoader() instanceof ForbricClassLoader loader && loader.ecosystemOfClass(type) == Ecosystem.FABRIC;
	}

	/** Test seam: which classes count as a Fabric mod's. */
	static void bindFabricCodeForTest(Predicate<Class<?>> isFabricCode) {
		fabricCode = isFabricCode;
	}

	/** Test seam: forget every claim. */
	static void resetForTest() {
		synchronized (CLAIMED) {
			CLAIMED.clear();
			takenOver = 0;
		}
	}
}
