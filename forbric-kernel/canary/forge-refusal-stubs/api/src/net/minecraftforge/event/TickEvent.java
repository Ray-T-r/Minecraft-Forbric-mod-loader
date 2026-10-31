package net.minecraftforge.event;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.eventbus.api.bus.EventBus;

/** Compile-only: the server tick a fixture listens for. Never shipped. */
public interface TickEvent {
	interface ServerTickEvent extends TickEvent {
		MinecraftServer server();

		final class Post implements ServerTickEvent {
			public static final EventBus<Post> BUS = null;

			private Post() {
			}

			@Override
			public MinecraftServer server() {
				throw new UnsupportedOperationException();
			}
		}
	}
}
