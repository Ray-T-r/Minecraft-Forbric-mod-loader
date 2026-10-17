/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import net.fabricmc.api.EnvType;

/**
 * {@link WrapperEntryAddedInjector}'s output, run: a MinecraftForge-wrapped registry's {@code register} tells
 * fabric-registry-sync's {@code RegistryEntryAddedCallback} listeners about the entry, with the raw id the wrapper
 * assigned, as {@code MappedRegistry.register} does on Fabric. That is how fabric-menu-api records the codec of a
 * menu registered after its own entrypoint (Farmer's Delight's cooking pot).
 *
 * <p>{@code WrapperEntryAddedInjectorTest} runs the real merged wrapper with fabric-api from staged jars. Here the
 * wrapper and fabric-api's public event types are stand-ins compiled under their names, and what gives a registry its
 * event — fabric-api's implementation — is a duck interface of another name ({@code org.relay.registry.Listens}): the
 * kernel asks only the public {@code RegistryEntryAddedCallback.event(registry)}.
 */
@ExecutesInjector(WrapperEntryAddedInjector.class)
@ResourceLock("system-properties")
class WrapperEntryAddedInjectorExecutionTest {
	private static final String WRAPPER = WrapperEntryAddedInjector.WRAPPER;
	private static final String CALLBACK = "net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback";

	private static final Map<String, String> GAME = Map.of(
			"net.minecraft.resources.Identifier", """
					package net.minecraft.resources;

					public record Identifier(String namespace, String path) {
						@Override
						public String toString() {
							return namespace + ":" + path;
						}
					}
					""",
			"net.minecraft.resources.ResourceKey", """
					package net.minecraft.resources;

					public record ResourceKey(Identifier identifier) {
					}
					""",
			"net.minecraft.core.IdMap", """
					package net.minecraft.core;

					public interface IdMap<T> {
						int getId(T value);
					}
					""",
			"net.minecraft.core.RegistrationInfo", "package net.minecraft.core; public record RegistrationInfo(String lifecycle) { }",
			"net.minecraft.core.Registry", "package net.minecraft.core; public interface Registry<T> extends IdMap<T> { }",
			"net.minecraft.core.Holder", """
					package net.minecraft.core;

					public interface Holder<T> {
						final class Reference<T> implements Holder<T> {
							public final Object key;
							public final T value;

							public Reference(Object key, T value) {
								this.key = key;
								this.value = value;
							}
						}
					}
					""");

	private static final Map<String, String> FABRIC = Map.of(
			"net.fabricmc.fabric.api.event.Event", """
					package net.fabricmc.fabric.api.event;

					public abstract class Event<T> {
						public abstract T invoker();

						public abstract void register(T listener);
					}
					""",
			CALLBACK, """
					package net.fabricmc.fabric.api.event.registry;

					import net.minecraft.resources.Identifier;

					public interface RegistryEntryAddedCallback<T> {
						void onEntryAdded(int rawId, Identifier id, T object);

						/** The public accessor: what the implementation gave the registry, under its own name. */
						@SuppressWarnings("unchecked")
						static <T> net.fabricmc.fabric.api.event.Event<RegistryEntryAddedCallback<T>> event(net.minecraft.core.Registry<T> registry) {
							return ((org.relay.registry.Listens<T>) registry).relay$entryAdded();
						}
					}
					""",
			"org.relay.registry.Listens", """
					package org.relay.registry;

					import net.fabricmc.fabric.api.event.Event;
					import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;

					public interface Listens<T> {
						Event<RegistryEntryAddedCallback<T>> relay$entryAdded();
					}
					""");

	/** The wrapper, with or without what fabric-registry-sync's mixin gives every MappedRegistry. */
	private static String wrapper(boolean fabric) {
		return """
				package net.minecraftforge.registries;

				import java.util.ArrayList;
				import java.util.List;
				import net.minecraft.core.Holder;
				import net.minecraft.core.IdMap;
				import net.minecraft.core.RegistrationInfo;
				import net.minecraft.core.Registry;
				import net.minecraft.resources.ResourceKey;
				%s

				public class NamespacedWrapper implements Registry<Object>%s {
					private final List<Object> forgeRegistry = new ArrayList<>();
				%s

					@Override
					public int getId(Object value) {
						return forgeRegistry.indexOf(value);
					}

					public Holder.Reference<Object> register(ResourceKey key, Object value, RegistrationInfo info) {
						forgeRegistry.add(value);
						return new Holder.Reference<>(key, value);
					}
				}
				""".formatted(
				fabric ? """
						import net.fabricmc.fabric.api.event.Event;
						import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;
						import org.relay.registry.Listens;
						""" : "",
				fabric ? ", Listens<Object>" : "",
				fabric ? """
							private final List<RegistryEntryAddedCallback<Object>> listeners = new ArrayList<>();
							private final Event<RegistryEntryAddedCallback<Object>> addObject = new Event<>() {
								@Override
								public RegistryEntryAddedCallback<Object> invoker() {
									return (rawId, id, object) -> {
										for (RegistryEntryAddedCallback<Object> listener : listeners) listener.onEntryAdded(rawId, id, object);
									};
								}

								@Override
								public void register(RegistryEntryAddedCallback<Object> listener) {
									listeners.add(listener);
								}
							};

							@Override
							public Event<RegistryEntryAddedCallback<Object>> relay$entryAdded() {
								return addObject;
							}
						""" : "");
	}

	@AfterEach void reset() {
		System.clearProperty(WrapperEntryAddedInjector.PROPERTY);
	}

	private static Map<String, byte[]> compile(Path work, boolean fabric) throws Exception {
		Map<String, String> sources = new HashMap<>(GAME);
		if (fabric) sources.putAll(FABRIC);
		sources.put(WRAPPER, wrapper(fabric));
		return InjectorExecution.compile(work, sources);
	}

	private static ClassLoader transformed(Map<String, byte[]> original) {
		String internal = WRAPPER.replace('.', '/');
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(internal, InjectorExecution.transform(new WrapperEntryAddedInjector(), WRAPPER, original.get(internal), EnvType.SERVER));
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(classes.get(internal), loader));
		return loader;
	}

	private static Object register(ClassLoader loader, Object wrapper, String path, Object value) throws Throwable {
		Object id = InjectorExecution.construct(loader.loadClass("net.minecraft.resources.Identifier"), "farmersdelight", path);
		Object key = InjectorExecution.construct(loader.loadClass("net.minecraft.resources.ResourceKey"), id);
		Object info = InjectorExecution.construct(loader.loadClass("net.minecraft.core.RegistrationInfo"), "stable");
		return InjectorExecution.invoke(wrapper, "register", key, value, info);
	}

	/** A listener, as fabric-menu-api subscribes one, recording "rawId id value". */
	private static void listen(ClassLoader loader, Object wrapper, List<String> heard, RuntimeException refusal) throws Throwable {
		Class<?> callback = loader.loadClass(CALLBACK);
		Object listener = Proxy.newProxyInstance(loader, new Class<?>[] {callback}, (proxy, method, args) -> {
			if (!method.getName().equals("onEntryAdded")) throw new UnsupportedOperationException(method.getName());
			if (refusal != null) throw refusal;
			heard.add(args[0] + " " + args[1] + " " + args[2]);
			return null;
		});
		// Through the public accessor and Event's own public method: the event is an anonymous subclass with a bridge.
		Object event = callback.getMethod("event", loader.loadClass("net.minecraft.core.Registry")).invoke(null, wrapper);
		loader.loadClass("net.fabricmc.fabric.api.event.Event").getMethod("register", Object.class).invoke(event, listener);
	}

	@Test void anEntryRegisteredAfterAListenerSubscribedReachesItWithItsRawId(@TempDir Path work) throws Throwable {
		ClassLoader loader = transformed(compile(work, true));
		Object wrapper = InjectorExecution.construct(loader.loadClass(WRAPPER));
		register(loader, wrapper, "stove", "stove block");
		List<String> heard = new ArrayList<>();
		listen(loader, wrapper, heard, null);
		Object holder = register(loader, wrapper, "cooking_pot", "cooking pot menu");
		assertEquals(List.of("1 farmersdelight:cooking_pot cooking pot menu"), heard);
		assertEquals("cooking pot menu", holder.getClass().getField("value").get(holder), "register still returns its holder");

		ClassLoader merged = InjectorExecution.load(compile(work, true));
		Object stock = InjectorExecution.construct(merged.loadClass(WRAPPER));
		List<String> unheard = new ArrayList<>();
		listen(merged, stock, unheard, null);
		register(merged, stock, "cooking_pot", "cooking pot menu");
		assertEquals(List.of(), unheard, "premise: as merged, no listener hears of what a wrapper registers");
	}

	/** fabric-registry-sync refuses an id by throwing from its listener; that is the registration's exception. */
	@Test void aListenersRefusalIsTheRegistrations(@TempDir Path work) throws Throwable {
		ClassLoader loader = transformed(compile(work, true));
		Object wrapper = InjectorExecution.construct(loader.loadClass(WRAPPER));
		listen(loader, wrapper, new ArrayList<>(), new IllegalStateException("farmersdelight:cooking_pot is already an alias"));
		assertEquals("farmersdelight:cooking_pot is already an alias", assertThrows(IllegalStateException.class,
				() -> register(loader, wrapper, "cooking_pot", "menu")).getMessage());
	}

	@Test void withoutFabricApiTheWrapperRegistersAsBefore(@TempDir Path work) throws Throwable {
		ClassLoader loader = transformed(compile(work, false));
		Object wrapper = InjectorExecution.construct(loader.loadClass(WRAPPER));
		Object holder = register(loader, wrapper, "cooking_pot", "menu");
		assertEquals("menu", holder.getClass().getField("value").get(holder));
		assertEquals(0, InjectorExecution.invoke(wrapper, "getId", "menu"));
	}

	@Test void aSecondPassAndTheSwitchLeaveTheWrapperAlone(@TempDir Path work) throws Exception {
		byte[] original = compile(work, true).get(WRAPPER.replace('.', '/'));
		byte[] once = InjectorExecution.transform(new WrapperEntryAddedInjector(), WRAPPER, original, EnvType.SERVER);
		assertSame(once, InjectorExecution.transform(new WrapperEntryAddedInjector(), WRAPPER, once, EnvType.SERVER));
		System.setProperty(WrapperEntryAddedInjector.PROPERTY, "off");
		assertSame(original, InjectorExecution.transform(new WrapperEntryAddedInjector(), WRAPPER, original, EnvType.SERVER));
	}
}
