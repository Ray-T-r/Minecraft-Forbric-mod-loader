package net.minecraftforge.eventbus.api.bus;

import net.minecraftforge.eventbus.internal.Event;

/** A stand-in; only posting is needed. */
public interface EventBus {
	boolean post(Event event);
}
