package net.minecraftforge.eventbus.api.bus;

import java.util.function.Consumer;

import net.minecraftforge.eventbus.api.listener.EventListener;

/** Compile-only: EventBus 7's bus, the one method the fixtures call. Never shipped. */
public interface EventBus<T> {
	EventListener addListener(Consumer<T> listener);
}
