package net.forbric.kernel.runtime.transfer;

import java.lang.ref.WeakReference;

import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.util.NonNullConsumer;

/** One live endpoint needs one subscription, even when a provider creates a new LazyOptional on every query. */
public final class ForgeCapabilityWatch {
	private final Runnable onInvalidated;
	private LazyOptional<?> current;
	private NonNullConsumer<?> listener;
	public ForgeCapabilityWatch(Runnable onInvalidated) { this.onInvalidated = java.util.Objects.requireNonNull(onInvalidated); }
	public <T> T observe(LazyOptional<T> optional) {
		if (optional != current) {
			clear();
			current = optional;
			WeakReference<ForgeCapabilityWatch> weak = new WeakReference<>(this);
			NonNullConsumer<LazyOptional<T>> callback = invalidated -> {
				ForgeCapabilityWatch watch = weak.get();
				if (watch != null && watch.current == invalidated) {
					// Do not remove a listener from inside LazyOptional's listener iteration. Clear our strong
					// references first, then notify the endpoint; its clear() becomes a harmless no-op.
					watch.current = null; watch.listener = null; watch.onInvalidated.run();
				}
			};
			listener = callback;
			optional.addListener(callback);
		}
		return optional.resolve().orElse(null);
	}
	@SuppressWarnings({"rawtypes", "unchecked"})
	public void clear() {
		LazyOptional old = current; NonNullConsumer oldListener = listener;
		current = null; listener = null;
		if (old != null && oldListener != null) old.removeListener(oldListener);
	}
	public boolean isWatching() { return current != null; }
}
