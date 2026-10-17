/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.forbric.kernel.transform.InjectorExecution;

/**
 * A Forge-wrapped registry that moved ids tells fabric-api's remap listeners through the public
 * {@code RegistryIdRemapCallback.event(registry)}, with a state read through the public {@code RemapState} interface — the
 * registry's own event lives behind a duck interface of another name ({@code org.relay.registry.Remaps}), so nothing
 * here may name fabric-api's implementation.
 */
class KernelForgeWrapperSyncRemapEventTest {
	private static final Map<String, String> SOURCES = Map.of(
			"net.minecraft.core.Registry", "package net.minecraft.core; public interface Registry<T> { }",
			"it.unimi.dsi.fastutil.ints.Int2IntMap", "package it.unimi.dsi.fastutil.ints; public interface Int2IntMap { }",
			"it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap", """
					package it.unimi.dsi.fastutil.ints;
					import java.util.TreeMap;
					public class Int2IntOpenHashMap implements Int2IntMap {
						private final TreeMap<Integer, Integer> entries = new TreeMap<>();
						public int put(int key, int value) { Integer was = entries.put(key, value); return was == null ? 0 : was; }
						@Override public String toString() {
							StringBuilder out = new StringBuilder("{");
							entries.forEach((k, v) -> out.append(out.length() > 1 ? ", " : "").append(k).append("=>").append(v));
							return out.append('}').toString();
						}
					}
					""",
			"net.fabricmc.fabric.api.event.Event", """
					package net.fabricmc.fabric.api.event;
					public abstract class Event<T> {
						public abstract T invoker();
					}
					""",
			"net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback", """
					package net.fabricmc.fabric.api.event.registry;
					public interface RegistryIdRemapCallback<T> {
						interface RemapState<T> {
							it.unimi.dsi.fastutil.ints.Int2IntMap getRawIdChangeMap();
							Object getIdFromOld(int oldId);
							Object getIdFromNew(int newId);
						}
						void onRemap(RemapState<T> state);
						@SuppressWarnings("unchecked")
						static <T> net.fabricmc.fabric.api.event.Event<RegistryIdRemapCallback<T>> event(net.minecraft.core.Registry<T> registry) {
							return ((org.relay.registry.Remaps<T>) registry).relay$remapped();
						}
					}
					""",
			"org.relay.registry.Remaps", """
					package org.relay.registry;
					public interface Remaps<T> {
						net.fabricmc.fabric.api.event.Event<net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback<T>> relay$remapped();
					}
					""",
			"fixture.Wrapped", """
					package fixture;
					import java.util.ArrayList;
					import java.util.List;
					import net.fabricmc.fabric.api.event.Event;
					import net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback;
					public class Wrapped implements net.minecraft.core.Registry<Object>, org.relay.registry.Remaps<Object> {
						public final List<String> heard = new ArrayList<>();
						public Event<RegistryIdRemapCallback<Object>> relay$remapped() {
							return new Event<>() {
								public RegistryIdRemapCallback<Object> invoker() {
									return state -> heard.add(state.getRawIdChangeMap() + " old0=" + state.getIdFromOld(0)
											+ " new1=" + state.getIdFromNew(1) + " new0=" + state.getIdFromNew(0));
								}
							};
						}
					}
					""");

	@Test void movedIdsReachTheListenersThroughThePublicEvent(@TempDir Path work) throws Throwable {
		ClassLoader loader = InjectorExecution.load(InjectorExecution.compile(work, SOURCES));
		Object wrapper = InjectorExecution.construct(loader.loadClass("fixture.Wrapped"));
		Map<Object, Integer> before = new LinkedHashMap<>(Map.of("mod:a", 0, "mod:b", 1));
		Map<Object, Integer> after = new LinkedHashMap<>(Map.of("mod:a", 1, "mod:b", 0));
		KernelForgeWrapperSync.announceRemapToFabric(loader, wrapper, before, after);
		assertEquals(List.of("{0=>1, 1=>0} old0=mod:a new1=mod:a new0=mod:b"), wrapper.getClass().getField("heard").get(wrapper));
	}

	@Test void unchangedIdsTellNobody(@TempDir Path work) throws Throwable {
		ClassLoader loader = InjectorExecution.load(InjectorExecution.compile(work, SOURCES));
		Object wrapper = InjectorExecution.construct(loader.loadClass("fixture.Wrapped"));
		KernelForgeWrapperSync.announceRemapToFabric(loader, wrapper, Map.of("mod:a", 0), Map.of("mod:a", 0));
		assertEquals(List.of(), wrapper.getClass().getField("heard").get(wrapper));
	}
}
