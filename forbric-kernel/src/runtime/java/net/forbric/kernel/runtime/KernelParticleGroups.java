/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.List;
import java.util.Map;

/**
 * MinecraftForge's particle groups, handed to the particle engine NeoForge's body builds (ParticleGroupsInjector).
 *
 * <p>MinecraftForge's {@code ParticleEngine.registerParticleGroup} keeps a mod's render type and group factory in static
 * collections its own constructor reads; the merged engine is NeoForge's, which builds its instance collections from
 * {@code RegisterParticleGroupsEvent} and never read those. Called right after that event, this adds what MinecraftForge
 * mods registered: each factory unless NeoForge's listeners already claimed the type, each type at the end of the
 * render order, as MinecraftForge appends it.
 */
public final class KernelParticleGroups {
	private KernelParticleGroups() {
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public static void addMinecraftForge(Map factories, List order, Map forgeFactories, List forgeOrder) {
		if (forgeFactories != null) forgeFactories.forEach((type, factory) -> factories.putIfAbsent(type, factory));
		if (forgeOrder != null) for (Object type : forgeOrder) if (!order.contains(type)) order.add(type);
	}
}
