package net.forbric.kernel.runtime.transfer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.util.NonNullConsumer;

/**
 * One live endpoint needs one subscription, even when a provider creates a new LazyOptional on every query.
 *
 * <p>And one LazyOptional holds ONE subscription from us however many endpoints watch it. Forge keeps its
 * listeners strongly until invalidation, and the bridge builds a fresh endpoint for every query: a listener per
 * watch piled one lambda per query into a standard cached optional (twenty a second for one polling pipe) until
 * the block entity was invalidated, which then walked all of them. Now the first watch of an optional adds a
 * single constant listener, and the watches it fans out to are held weakly and pruned as they go. An entry
 * leaves the map only when its optional is invalidated or collected, so "entry present" means "subscribed".
 */
public final class ForgeCapabilityWatch {
	private static final Map<LazyOptional<?>, List<WeakReference<ForgeCapabilityWatch>>> WATCHERS = new WeakHashMap<>();
	@SuppressWarnings({"rawtypes", "unchecked"})
	private static final NonNullConsumer DISPATCH = invalidated -> dispatch((LazyOptional<?>) invalidated);
	private final Runnable onInvalidated;
	private LazyOptional<?> current;
	public ForgeCapabilityWatch(Runnable onInvalidated) { this.onInvalidated = java.util.Objects.requireNonNull(onInvalidated); }
	@SuppressWarnings("unchecked")
	public <T> T observe(LazyOptional<T> optional) {
		if (optional != current) {
			clear();
			current = optional;
			boolean first;
			synchronized (WATCHERS) {
				var watchers = WATCHERS.get(optional);
				first = watchers == null;
				if (first) WATCHERS.put(optional, watchers = new ArrayList<>());
				watchers.removeIf(reference -> reference.get() == null);
				watchers.add(new WeakReference<>(this));
			}
			// An optional that is already invalid calls the listener at once, and dispatch then notifies this watch.
			if (first) optional.addListener(DISPATCH);
		}
		return optional.resolve().orElse(null);
	}
	private static void dispatch(LazyOptional<?> invalidated) {
		List<WeakReference<ForgeCapabilityWatch>> watchers;
		synchronized (WATCHERS) { watchers = WATCHERS.remove(invalidated); }
		if (watchers == null) return;
		for (var reference : watchers) {
			ForgeCapabilityWatch watch = reference.get();
			// Forge is iterating its listener set here; nothing below touches it. The endpoint's clear() then
			// finds no current optional and is a harmless no-op.
			if (watch != null && watch.current == invalidated) { watch.current = null; watch.onInvalidated.run(); }
		}
	}
	public void clear() {
		LazyOptional<?> old = current;
		current = null;
		if (old == null) return;
		synchronized (WATCHERS) {
			var watchers = WATCHERS.get(old);
			if (watchers != null) watchers.removeIf(reference -> reference.get() == null || reference.get() == this);
		}
	}
	public boolean isWatching() { return current != null; }
}
