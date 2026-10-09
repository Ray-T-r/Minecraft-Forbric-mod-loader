/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;

/**
 * The runtime evidence of the merged base's proved ancestor bridges: a class one platform's runtime carrier serves,
 * whose direct superclass another platform's carrier serves.
 *
 * <p>No platform ships that edge: MinecraftForge's runtime has never heard of NeoForge's, and the other way round. It
 * exists only where the merged-base builder proved two platforms' ancestors immutable behavioural equivalents and
 * rebased one onto the other ({@code EquivalentSuperclassBridge}, written into the carrier by
 * {@code RuntimeInteropPatcher}), so that a merged class keeping either ancestor is both platforms' type. That proof
 * happens at build time and has no runtime switch; this is where it becomes observable. The edge is reported once, the
 * moment this loader defines the bridged class, with what the loader actually served — the superclass named by the
 * bytes it defined and the carrier each of the two comes from.
 *
 * <p>Nothing is decided by name. A class outside the carriers (a mod jar, the merged base, a library) and an edge within
 * one platform's carrier are not bridges and are left alone.
 */
final class PlatformAncestorBridges {
	/** A defined class and its superclass, each served by a different platform's runtime carrier. Binary names. */
	record Bridge(String type, LoaderProbePolicy.Family typeCarrier, String ancestor, LoaderProbePolicy.Family ancestorCarrier) {
		String describe() {
			return String.format("[Forbric/Hierarchy] %s, served by the %s runtime, extends %s, served by the %s runtime — "
					+ "the merged base's proved ancestor bridge is in effect: a class that keeps either one is both "
					+ "platforms' type", type, typeCarrier, ancestor, ancestorCarrier);
		}
	}

	private final Map<String, Bridge> observed = new ConcurrentHashMap<>();
	private final Map<String, Optional<LoaderProbePolicy.Family>> carrierOfAncestor = new ConcurrentHashMap<>();

	/**
	 * The bridge {@code definition} carries, or null.
	 *
	 * @param carrier       the platform carrier {@code definition} was read from, or null when it came from none
	 * @param carrierOfClass the platform carrier this loader serves an internal class name from, or null
	 */
	static Bridge of(byte[] definition, LoaderProbePolicy.Family carrier, Function<String, LoaderProbePolicy.Family> carrierOfClass) {
		if (carrier == null || definition == null || definition.length == 0) return null;
		ClassReader reader;
		try {
			reader = new ClassReader(definition);
		} catch (RuntimeException unreadable) {
			return null; // the JVM has already accepted these bytes; a reader that cannot is no evidence either way
		}
		String ancestor = reader.getSuperName();
		if (ancestor == null) return null;
		LoaderProbePolicy.Family ancestorCarrier = carrierOfClass.apply(ancestor);
		if (ancestorCarrier == null || ancestorCarrier == carrier) return null;
		return new Bridge(reader.getClassName().replace('/', '.'), carrier, ancestor.replace('/', '.'), ancestorCarrier);
	}

	/** Records and reports, once per class, the bridge a just-defined class carries. */
	void observe(byte[] definition, LoaderProbePolicy.Family carrier, Function<String, LoaderProbePolicy.Family> carrierOfClass) {
		if (carrier == null) return;
		Bridge bridge = of(definition, carrier, ancestor -> carrierOfAncestor
				.computeIfAbsent(ancestor, name -> Optional.ofNullable(carrierOfClass.apply(name))).orElse(null));
		if (bridge != null && observed.putIfAbsent(bridge.type(), bridge) == null) ForbricLog.info(bridge.describe());
	}

	/** Every bridge this loader has defined so far, in no particular order. */
	List<Bridge> observed() {
		return List.copyOf(observed.values());
	}
}
