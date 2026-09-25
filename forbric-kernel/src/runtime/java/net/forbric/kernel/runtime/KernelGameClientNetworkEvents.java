/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraftforge.client.event.ForgeEventFactoryClient;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * The client's connection lifecycle and client commands, for MinecraftForge mods.
 *
 * <p>The merged {@code ClientPacketListener} and {@code Minecraft} post NeoForge's {@code ClientPlayerNetworkEvent}s at
 * MinecraftForge's own positions ({@code handleLogin}, {@code disconnect}, {@code handleRespawn}) and nothing calls
 * {@code ForgeEventFactoryClient.firePlayerLogin/Logout/Respawn}, so JourneyMap never handshakes or cleans up and a
 * server-synced config (configlib, WTHIT) is never applied on join or reset on leave. Those three hooks are pure
 * emitters and are called as they are.
 *
 * <p>Client commands go the same way: the merged listener runs NeoForge's {@code ClientCommandHandler}, which posts
 * NeoForge's {@code RegisterClientCommandsEvent} into the dispatcher it executes; MinecraftForge's event is posted
 * with that same dispatcher, so a MinecraftForge mod's client commands exist and run. MinecraftForge's own handler also
 * wakes on the forwarded login and builds its own tree until the server's command packet replaces it, as it would
 * natively; it is left alone, because a MinecraftForge mod may read its dispatcher.
 *
 * <p>Its own class: it names {@code net.neoforged.neoforge.client.event}, which a dedicated server must never resolve.
 */
public final class KernelGameClientNetworkEvents {
	private KernelGameClientNetworkEvents() {
	}

	public static void installLoggingIn(Object neoBus) {
		forward((IEventBus) neoBus, ClientPlayerNetworkEvent.LoggingIn.class, "ClientPlayerNetworkEvent.LoggingIn",
				event -> ForgeEventFactoryClient.firePlayerLogin(event.getMultiPlayerGameMode(), event.getPlayer(),
						event.getConnection()));
	}

	public static void installLoggingOut(Object neoBus) {
		forward((IEventBus) neoBus, ClientPlayerNetworkEvent.LoggingOut.class, "ClientPlayerNetworkEvent.LoggingOut",
				event -> ForgeEventFactoryClient.firePlayerLogout(event.getMultiPlayerGameMode(), event.getPlayer()));
	}

	public static void installClone(Object neoBus) {
		forward((IEventBus) neoBus, ClientPlayerNetworkEvent.Clone.class, "ClientPlayerNetworkEvent.Clone",
				event -> ForgeEventFactoryClient.firePlayerRespawn(event.getMultiPlayerGameMode(), event.getOldPlayer(),
						event.getNewPlayer(), event.getConnection()));
	}

	public static void installClientCommands(Object neoBus) {
		forward((IEventBus) neoBus, RegisterClientCommandsEvent.class, "RegisterClientCommandsEvent",
				event -> net.minecraftforge.client.event.RegisterClientCommandsEvent.BUS.post(
						new net.minecraftforge.client.event.RegisterClientCommandsEvent(event.getDispatcher(),
								event.getBuildContext())));
	}

	/** A LOWEST forward of one NeoForge event; one warning per event if it throws. */
	static <E extends Event> void forward(IEventBus bus, Class<E> event, String name, Consumer<E> forward) {
		AtomicBoolean warned = new AtomicBoolean();
		bus.addListener(EventPriority.LOWEST, false, event, neoEvent -> {
			try {
				forward.accept(neoEvent);
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] " + name + " forward failed — MinecraftForge mods listening "
							+ "for it are not told", Reflect.unwrap(t));
				}
			}
		});
	}
}
