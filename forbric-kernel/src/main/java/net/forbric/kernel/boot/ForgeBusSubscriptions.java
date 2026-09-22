/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.boot;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The events a MinecraftForge mod listens for that an annotation scan cannot see.
 *
 * <h2>The half of the audit that was missing</h2>
 *
 * <p>{@link DeadEventAudit} is told who subscribes to what by {@code KernelEventSubscribers}, which reads jars
 * with ASM and {@code SKIP_CODE} — so its input is exactly {classes carrying a class-level
 * {@code @EventBusSubscriber}} × {single-argument methods carrying {@code @SubscribeEvent}}. A listener
 * registered with {@code bus.addListener(...)} or {@code bus.register(this)} lives in a method BODY, which that
 * scan never parses. Of the jars most likely to be waiting on a dead event, the ones that register that way are
 * invisible to the audit entirely: it reports nothing, and reporting nothing reads exactly like nothing being
 * wrong.
 *
 * <p>Parsing method bodies would make the scan much more expensive and would still only find the static shapes.
 * The kernel already holds the answer: it keeps each traditional-MinecraftForge mod's own {@code BusGroup}
 * ({@link KernelForgeModContext.Handle}), and a bus group's internal map is keyed by the event classes that bus
 * has actually seen. Reading it is one field access per mod, after registration is over, and it is attributed —
 * the group belongs to one mod.
 *
 * <p>Two honest limits. The map holds every event class the bus has been ASKED about, so an event that was
 * posted but never listened for can appear; and the game bus is shared, so listeners registered there carry no
 * mod attribution and are not covered here. Both make this OVER-report rather than under-report, which for an
 * audit whose failure mode is silence is the right direction — and {@code DeadEventAudit} only turns an entry
 * into a finding when the event is in its dead table anyway.
 */
public final class ForgeBusSubscriptions {

	/** MinecraftForge's EventBus 7 keeps its per-event buses here. */
	private static final String EVENT_BUSES = "eventBuses";

	private ForgeBusSubscriptions() {
	}

	/** modId → event internal names, for every mod whose bus group can be read. */
	public static Map<String, Set<String>> byMod(Map<String, KernelForgeModContext.Handle> mods) {
		Map<String, Set<String>> out = new LinkedHashMap<>();
		if (mods == null) return out;
		for (Map.Entry<String, KernelForgeModContext.Handle> e : mods.entrySet()) {
			if (e.getKey() == null || e.getValue() == null) continue;
			Set<String> events = eventsOf(e.getValue().busGroup());
			if (!events.isEmpty()) out.put(e.getKey(), events);
		}
		return out;
	}

	/**
	 * The event classes one bus group has buses for, as internal names.
	 *
	 * <p>Never throws and never logs: this runs inside an audit that exists to add information, and an audit
	 * that can fail a boot is worse than one that is incomplete.
	 */
	public static Set<String> eventsOf(Object busGroup) {
		Set<String> out = new TreeSet<>();
		if (busGroup == null) return out;
		try {
			Field field = findField(busGroup.getClass(), EVENT_BUSES);
			if (field == null) return out;
			field.setAccessible(true);
			Object value = field.get(busGroup);
			if (!(value instanceof Map<?, ?> byEvent)) return out;
			for (Object key : byEvent.keySet()) {
				if (key instanceof Class<?> type) out.add(type.getName().replace('.', '/'));
			}
		} catch (Throwable unreadable) {
			return out;
		}
		return out;
	}

	private static Field findField(Class<?> type, String name) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException keepLooking) {
				// up the chain
			}
		}
		return null;
	}

	/** Adds {@code extra}'s events into {@code into} without losing what the annotation scan already found. */
	public static Map<String, Set<String>> merge(Map<String, Set<String>> into, Map<String, Set<String>> extra) {
		Map<String, Set<String>> out = new LinkedHashMap<>();
		if (into != null) into.forEach((mod, events) -> out.put(mod, new TreeSet<>(events)));
		if (extra != null) {
			extra.forEach((mod, events) -> out.computeIfAbsent(mod, k -> new TreeSet<>()).addAll(events));
		}
		return out;
	}
}
