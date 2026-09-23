/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Carries both portal hooks' complete result back to BaseFireBlock's existing Optional writeback.
 * NeoForge runs first; its refusal is final. The legacy event bridge stands down only while this wrapper
 * dispatches NeoForge, so it cannot post Forge twice and remains available to other event producers.
 *
 * <p>The current unmodified carriers return the input shape or empty. A mod transforming a hook can also
 * return a replacement: unlike an event-only forward, this call site has somewhere to carry that result.
 */
public final class KernelPortalSpawn {
	private static final ThreadLocal<Integer> NEO_DISPATCH_DEPTH = new ThreadLocal<>();
	private static final AtomicBoolean WARNED = new AtomicBoolean();

	private KernelPortalSpawn() { }

	static boolean dispatchingNeo() { return NEO_DISPATCH_DEPTH.get() != null; }

	public static Optional<PortalShape> onTrySpawnPortal(LevelAccessor level, BlockPos position,
			Optional<PortalShape> original) {
		Optional<PortalShape> neo = onTrySpawnPortalNeoOnly(level, position, original);
		if (neo == null || neo.isEmpty()) return Optional.empty();
		try {
			Optional<PortalShape> forge = ForgeEventFactory.onTrySpawnPortal(level, position, neo);
			return forge == null ? Optional.empty() : forge;
		} catch (Throwable failure) {
			// Preserve the legacy bridge's failure policy: a failed Forge listener cannot discard Neo's result.
			if (WARNED.compareAndSet(false, true)) {
				ForbricLog.warn("[Forbric/PortalSpawn] MinecraftForge portal hook failed; retaining NeoForge's result",
						Reflect.unwrap(failure));
			}
			return neo;
		}
	}

	/** Only for a caller whose own subsequent Forge dispatch, veto guards and result consumer were proved. */
	public static Optional<PortalShape> onTrySpawnPortalNeoOnly(LevelAccessor level, BlockPos position,
			Optional<PortalShape> original) {
		Integer previous = NEO_DISPATCH_DEPTH.get();
		NEO_DISPATCH_DEPTH.set(previous == null ? 1 : previous + 1);
		try {
			return EventHooks.onTrySpawnPortal(level, position, original);
		} finally {
			if (previous == null) NEO_DISPATCH_DEPTH.remove();
			else NEO_DISPATCH_DEPTH.set(previous);
		}
	}
}
