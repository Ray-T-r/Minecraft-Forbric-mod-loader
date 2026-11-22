/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Holds back a block state's {@code initCache()} while NeoForge's {@code RegisterEvent} is being dispatched, and runs it
 * once every registry's event has been dispatched — where NeoForge computes the caches of the blocks its mods register.
 *
 * <p>NeoForge registers a mod's blocks from its {@code DeferredRegister}s inside {@code RegisterEvent}, and computes none
 * of their state caches there: its block registry's add callback only collects the block, and its bake callback calls
 * {@code initCache()} on every state it collected once registration is over (NeoForge 26.2.0.93,
 * {@code NeoForgeRegistryCallbacks.BlockCallbacks.onAdd} / {@code onBake}). A mod is entitled to rely on that: a block's
 * {@code getShape} may read a {@code DeferredHolder} of a sibling block registered later in the same event, which is
 * unbound until then. fabric-registry-sync computes a block's cache the moment it is added (its {@code Blocks} mixin adds
 * a {@code RegistryEntryAddedCallback} on the block registry that calls {@code initCache()}), which is right for a Fabric
 * mod registering in its initializer — and on Forbric it fires for a NeoForge mod's block too, inside the event, where
 * {@code initCache()} computes the collision shape, the mod's {@code getShape} asks the unbound holder, and the
 * {@code NullPointerException} "Trying to access unbound value" fails the mod's {@code RegisterEvent} and stops the boot.
 *
 * <p>So, only while the window is open and only on the thread dispatching it, the repaired {@code initCache()} hands its
 * state here and returns; {@link #close} then runs each held state's {@code initCache()} once, after every registry's
 * event and before the Fabric main entrypoints, which compute their own blocks' caches on add as on Fabric. Outside the
 * window nothing is held: vanilla's bootstrap, a Fabric mod's registration and the kernel's own pass after the
 * registration window ({@code KernelRegistryContent.initialiseBlockStateCaches}) compute caches as they always did.
 *
 * <p>Names no game type, so it lives in this parent-loaded package and is called from the repaired game class; the held
 * states' {@code initCache()} is reached by reflection.
 */
public final class KernelDeferredStateCaches {
	/** {@code -Dforbric.deferredStateCaches=off} computes a block's caches wherever {@code initCache()} is called, as merged. */
	public static final String PROPERTY = "forbric.deferredStateCaches";

	private static final Object LOCK = new Object();
	/** The thread dispatching NeoForge's {@code RegisterEvent}; null outside the window. */
	private static volatile Thread window;
	/** The states held back, each once, in the order they were first asked for. */
	private static final List<Object> held = new ArrayList<>();
	private static final Set<Object> heldSet = Collections.newSetFromMap(new IdentityHashMap<>());

	private static final ClassValue<Method> INIT_CACHE = new ClassValue<>() {
		@Override protected Method computeValue(Class<?> type) {
			try {
				return type.getMethod("initCache");
			} catch (NoSuchMethodException none) {
				Caught.expected(none);
				return null;
			}
		}
	};

	private KernelDeferredStateCaches() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(ForbricSwitches.get(PROPERTY, "on"));
	}

	/**
	 * Called at the head of the repaired {@code BlockStateBase.initCache()}: true when the state is held for {@link #close}
	 * and the call returns at once, false when it computes its cache now.
	 */
	public static boolean defer(Object state) {
		if (window != Thread.currentThread()) return false;
		synchronized (LOCK) {
			if (heldSet.add(state)) held.add(state);
		}
		return true;
	}

	/** Opens the window on the calling thread: NeoForge's {@code GameData.postRegisterEvents} is about to run there. */
	public static void open() {
		if (!enabled()) return;
		synchronized (LOCK) {
			held.clear();
			heldSet.clear();
		}
		window = Thread.currentThread();
	}

	/**
	 * Closes the window and, when {@code compute}, runs {@code initCache()} on every state held in it, each once; without
	 * {@code compute} (NeoForge's registration failed and rolled back) the held states are dropped. Idempotent: a second
	 * close finds nothing held. Returns how many caches were computed.
	 */
	public static int close(boolean compute) {
		List<Object> states;
		synchronized (LOCK) {
			window = null;
			states = new ArrayList<>(held);
			held.clear();
			heldSet.clear();
		}
		if (!compute || states.isEmpty()) return 0;
		int computed = 0;
		for (Object state : states) {
			try {
				Method initCache = INIT_CACHE.get(state.getClass());
				if (initCache == null) throw new NoSuchMethodException(state.getClass().getName() + ".initCache()");
				initCache.invoke(state);
				computed++;
			} catch (Throwable t) {
				// NeoForge's bake would have thrown here too. The kernel's own pass after the registration window
				// computes every state again, and reports what still fails there.
				Caught.warn(t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t,
						"[Forbric/Lifecycle] could not compute the held-back cache of block state %s after RegisterEvent",
						state);
			}
		}
		ForbricLog.info("[Forbric/Lifecycle] computed %d of %d block state cache(s) held back during NeoForge's "
				+ "RegisterEvent — NeoForge computes the caches of its mods' blocks after registration, and a block's "
				+ "shape may read a sibling registered later in the same event", computed, states.size());
		return computed;
	}
}
