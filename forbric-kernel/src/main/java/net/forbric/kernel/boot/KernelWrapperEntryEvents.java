/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.Caught;

/**
 * The boot-side half of {@link net.forbric.kernel.transform.WrapperEntryAddedInjector}: fires fabric-registry-sync's
 * {@code RegistryEntryAddedCallback} for an entry a MinecraftForge-wrapped registry has just registered.
 *
 * <p>What Fabric's injection at the return of {@code MappedRegistry.register} does: the registry's own add-object event,
 * which fabric-registry-sync's {@code MappedRegistryMixin} gives every {@code MappedRegistry} (the wrapper included, it is
 * one), invoked with the entry's raw id, its id and the entry. The raw id is the wrapper's {@code getId}, the id its
 * {@code ForgeRegistry} just assigned; the {@code toId} map Fabric reads stays empty on a wrapper.
 *
 * <p>Reflective, as the wrapper's other boot-side hook {@link KernelForgeWrapperSync} is, and resolved once per wrapper
 * class on the public types that declare each member — {@code NamespacedWrapper} itself ships package-private, and stays
 * so with {@code -Dforbric.publicRegistryWrappers=off}. A registry
 * class fabric-registry-sync never touched (no fabric-api installed) has no event, and is left alone. A listener's own
 * exception is the registration's, exactly as on a plain registry: fabric-registry-sync refuses an id that is already an
 * alias by throwing from its listener.
 */
public final class KernelWrapperEntryEvents {
	private static final String LISTENABLE = "net.fabricmc.fabric.impl.registry.sync.ListenableRegistry";
	private static final String EVENT = "net.fabricmc.fabric.api.event.Event";
	private static final String CALLBACK = "net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback";

	private record Hooks(Method event, Method invoker, Method onEntryAdded, Method rawId, Method identifier) {
	}

	private static final ConcurrentHashMap<Class<?>, Optional<Hooks>> HOOKS = new ConcurrentHashMap<>();

	private KernelWrapperEntryEvents() {
	}

	/** Called by the wrapper's {@code register(key, value, info)} right before it returns the entry's holder. */
	public static void entryAdded(Object registry, Object key, Object value) {
		// Not computeIfAbsent: resolving loads classes, and nothing may register into a wrapper inside the map's lock.
		Optional<Hooks> resolved = HOOKS.get(registry.getClass());
		if (resolved == null) HOOKS.putIfAbsent(registry.getClass(), resolved = resolve(registry.getClass()));
		Hooks hooks = resolved.orElse(null);
		if (hooks == null) return;
		try {
			// Null only before fabric-registry-sync's constructor injection has run, and nothing can listen yet then.
			Object event = hooks.event().invoke(registry);
			if (event == null) return;
			Object invoker = hooks.invoker().invoke(event);
			hooks.onEntryAdded().invoke(invoker, hooks.rawId().invoke(registry, value), hooks.identifier().invoke(key), value);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			if (cause instanceof Error error) throw error;
			throw new UndeclaredThrowableException(cause);
		} catch (IllegalAccessException e) {
			HOOKS.put(registry.getClass(), Optional.empty());
			Caught.warn(e, "[Forbric/Registries] could not fire fabric-registry-sync's RegistryEntryAddedCallback for "
					+ "%s — a Fabric listener misses what it registers", registry.getClass().getName());
		}
	}

	private static Optional<Hooks> resolve(Class<?> wrapper) {
		ClassLoader loader = wrapper.getClassLoader();
		try {
			Class<?> listenable = Class.forName(LISTENABLE, false, loader);
			if (!listenable.isAssignableFrom(wrapper)) return Optional.empty();
			Method identifier = Class.forName("net.minecraft.resources.ResourceKey", false, loader).getMethod("identifier");
			return Optional.of(new Hooks(listenable.getMethod("fabric_getAddObjectEvent"),
					Class.forName(EVENT, false, loader).getMethod("invoker"),
					Class.forName(CALLBACK, false, loader).getMethod("onEntryAdded", int.class, identifier.getReturnType(), Object.class),
					Class.forName("net.minecraft.core.IdMap", false, loader).getMethod("getId", Object.class), identifier));
		} catch (ClassNotFoundException absent) {
			Caught.expected(absent);
			return Optional.empty(); // no fabric-registry-sync: nothing listens
		} catch (ReflectiveOperationException | LinkageError e) {
			Caught.warn(e, "[Forbric/Registries] fabric-registry-sync's RegistryEntryAddedCallback has changed shape; "
					+ "%s registers without firing it — a Fabric listener misses what it registers", wrapper.getName());
			return Optional.empty();
		}
	}
}
