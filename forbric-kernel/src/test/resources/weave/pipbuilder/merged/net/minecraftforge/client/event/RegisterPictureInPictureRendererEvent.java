package net.minecraftforge.client.event;

import java.util.List;

import com.google.common.collect.ImmutableMap;

import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.internal.Event;

/** A stand-in for MinecraftForge's registration event; no MinecraftForge mod registers anything here. */
public class RegisterPictureInPictureRendererEvent implements Event {
	public static final EventBus BUS = event -> false;

	public RegisterPictureInPictureRendererEvent(List<?> renderers, ImmutableMap.Builder<?, ?> builder) {
	}
}
