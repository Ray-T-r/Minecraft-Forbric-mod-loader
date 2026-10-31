package net.minecraftforge.eventbus.api.bus;

import java.util.function.Predicate;

import net.minecraftforge.eventbus.api.listener.EventListener;

/** Compile-only: EventBus 7's cancellable bus, whose listener returns true to cancel. Never shipped. */
public interface CancellableEventBus<T> extends EventBus<T> {
	EventListener addListener(Predicate<T> listener);
}
