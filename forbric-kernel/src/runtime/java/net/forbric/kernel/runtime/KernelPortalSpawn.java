/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.ArrayDeque;
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
 * NeoForge runs first; its refusal is final. The legacy event bridge stands down only for the one event this
 * wrapper posts, so it cannot post Forge twice and remains available to every other event producer, including
 * one a NeoForge listener runs on this thread while that event is still being dispatched.
 *
 * <p>The current unmodified carriers return the input shape or empty. A mod transforming a hook can also
 * return a replacement: unlike an event-only forward, this call site has somewhere to carry that result.
 */
public final class KernelPortalSpawn {
	/** The arguments of each NeoForge dispatch this wrapper has in progress on this thread, innermost last. */
	private static final ThreadLocal<ArrayDeque<Dispatch>> NEO_DISPATCHES = new ThreadLocal<>();
	private static final AtomicBoolean WARNED = new AtomicBoolean();

	private record Dispatch(LevelAccessor level, BlockPos position, PortalShape shape) { }

	private KernelPortalSpawn() { }

	/** Whether any wrapper dispatch is in progress on this thread. Only a probe: the bridge asks the question below. */
	static boolean dispatchingNeo() { return NEO_DISPATCHES.get() != null; }

	/**
	 * Whether {@code EventHooks.onTrySpawnPortal} built this event from the innermost wrapper dispatch's own
	 * arguments. It passes them straight into the event, so identity is the match; any other producer on this
	 * thread, even one nested inside that dispatch, is somebody else's portal and still needs its forward.
	 */
	static boolean postedByWrapper(LevelAccessor level, BlockPos position, PortalShape shape) {
		ArrayDeque<Dispatch> dispatches = NEO_DISPATCHES.get();
		if (dispatches == null) return false;
		Dispatch innermost = dispatches.peekLast();
		return innermost.level() == level && innermost.position() == position && innermost.shape() == shape;
	}

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
		ArrayDeque<Dispatch> dispatches = NEO_DISPATCHES.get();
		if (dispatches == null) NEO_DISPATCHES.set(dispatches = new ArrayDeque<>());
		dispatches.addLast(new Dispatch(level, position, original == null ? null : original.orElse(null)));
		try {
			return EventHooks.onTrySpawnPortal(level, position, original);
		} finally {
			dispatches.removeLast();
			if (dispatches.isEmpty()) NEO_DISPATCHES.remove();
		}
	}
}
