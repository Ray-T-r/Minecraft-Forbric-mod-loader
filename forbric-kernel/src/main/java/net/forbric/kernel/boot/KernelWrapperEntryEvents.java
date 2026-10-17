/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.ForbricLog;

/**
 * The boot-side half of {@link net.forbric.kernel.transform.WrapperEntryAddedInjector}: fires fabric-registry-sync's
 * {@code RegistryEntryAddedCallback} for an entry a MinecraftForge-wrapped registry has just registered.
 *
 * <p>What Fabric's injection at the return of {@code MappedRegistry.register} does: the registry's own add-object event,
 * which fabric-api's public {@code RegistryEntryAddedCallback.event(registry)} hands out for every {@code MappedRegistry}
 * (the wrapper included, it is one), invoked with the entry's raw id, its id and the entry. The raw id is the wrapper's
 * {@code getId}, the id its {@code ForgeRegistry} just assigned; the {@code toId} map Fabric reads stays empty on a wrapper.
 *
 * <p>Reflective, as the wrapper's other boot-side hook {@link KernelForgeWrapperSync} is, and resolved once per wrapper
 * class on the public types that declare each member — {@code NamespacedWrapper} itself ships package-private, and stays
 * so with {@code -Dforbric.publicRegistryWrappers=off}. Without fabric-api's public event API nothing listens, and the
 * registry is left alone. A listener's own exception is the registration's, exactly as on a plain registry: a listener may
 * refuse an id that is already an alias by throwing.
 */
public final class KernelWrapperEntryEvents {
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
		Object event;
		try {
			// Null only before fabric-api has given the registry its event, and nothing can listen yet then.
			event = hooks.event().invoke(null, registry);
		} catch (InvocationTargetException | IllegalAccessException | RuntimeException noEvent) {
			// The public API has no event for this registry class (fabric-api's mixin did not give it one): nothing listens.
			HOOKS.put(registry.getClass(), Optional.empty());
			ForbricLog.debug("[Forbric/Registries] %s has no Fabric entry-added event: %s", registry.getClass().getName(),
					String.valueOf(noEvent instanceof InvocationTargetException ite ? ite.getCause() : noEvent));
			return;
		}
		if (event == null) return;
		try {
			Object invoker = hooks.invoker().invoke(event);
			hooks.onEntryAdded().invoke(invoker, hooks.rawId().invoke(registry, value), hooks.identifier().invoke(key), value);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			if (cause instanceof Error error) throw error;
			throw new UndeclaredThrowableException(cause);
		} catch (IllegalAccessException e) {
			HOOKS.put(registry.getClass(), Optional.empty());
			ForbricLog.warn("[Forbric/Registries] could not fire fabric-api's RegistryEntryAddedCallback for "
					+ registry.getClass().getName() + " — a Fabric listener misses what it registers", e);
		}
	}

	private static Optional<Hooks> resolve(Class<?> wrapper) {
		ClassLoader loader = wrapper.getClassLoader();
		try {
			Class<?> callback = Class.forName(CALLBACK, false, loader);
			Method event = callback.getMethod("event", Class.forName("net.minecraft.core.Registry", false, loader));
			Method identifier = Class.forName("net.minecraft.resources.ResourceKey", false, loader).getMethod("identifier");
			return Optional.of(new Hooks(event,
					Class.forName(EVENT, false, loader).getMethod("invoker"),
					Class.forName(CALLBACK, false, loader).getMethod("onEntryAdded", int.class, identifier.getReturnType(), Object.class),
					Class.forName("net.minecraft.core.IdMap", false, loader).getMethod("getId", Object.class), identifier));
		} catch (ClassNotFoundException absent) {
			return Optional.empty(); // no fabric-api event API: nothing listens
		} catch (ReflectiveOperationException | LinkageError e) {
			ForbricLog.warn("[Forbric/Registries] fabric-registry-sync's RegistryEntryAddedCallback has changed shape; "
					+ wrapper.getName() + " registers without firing it — a Fabric listener misses what it registers", e);
			return Optional.empty();
		}
	}
}
