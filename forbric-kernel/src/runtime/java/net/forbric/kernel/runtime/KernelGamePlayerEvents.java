/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Re-emits the player-lifecycle and command-registration events the byte merge left NeoForge-only.
 *
 * <p>Measured on the merged base: {@code Commands} carries SEVEN references to
 * {@code net.neoforged.neoforge.*} and ZERO to {@code net.minecraftforge.*}, so
 * {@code EventHooks.onCommandRegister} is the only hook at that call site and MinecraftForge's is gone.
 * {@code PlayerList} is 13 to 0 the same way, which covers login, logout and respawn; the dimension change is
 * {@code EventHooks.firePlayerChangedDimensionEvent} with no Forge counterpart called anywhere.
 *
 * <p>What that costs is not subtle and has no log line. A MinecraftForge mod's commands DO NOT EXIST — the
 * player types one and gets "Unknown command", while the mod loaded cleanly and its {@code RegisterCommandsEvent}
 * handler was registered on a bus nobody posts to. Login rewards, join messages, per-player state restored on
 * connect, cleanup on disconnect, anything keyed to respawn or to entering the Nether: none of it runs.
 *
 * <p>Each bridge forwards at LOWEST priority, so every NeoForge listener has already seen the event and its
 * decisions stand. That is the correct order for the re-emission: the surviving hook is the authority, and the
 * MinecraftForge side is an observer of what it decided.
 */
public final class KernelGamePlayerEvents {
	private KernelGamePlayerEvents() {
	}

	/**
	 * NeoForge {@code RegisterCommandsEvent} → MinecraftForge {@code onCommandRegister}.
	 *
	 * <p>All three arguments cross unchanged — the dispatcher, the selection and the build context — because both
	 * families wrap the same vanilla trio. The dispatcher is the live one, so a Forge mod's command tree is built
	 * into the same node graph vanilla and NeoForge just built theirs into.
	 */
	public static void installCommands(Object neoBus) {
		subscribe((IEventBus) neoBus, RegisterCommandsEvent.class, "RegisterCommandsEvent",
				"MinecraftForge mods' commands will not exist — typing one answers \"Unknown command\"",
				event -> ForgeEventFactory.onCommandRegister(event.getDispatcher(), event.getCommandSelection(),
						event.getBuildContext()));
	}

	/** NeoForge {@code PlayerLoggedInEvent} → MinecraftForge {@code firePlayerLoggedIn}. */
	public static void installLoggedIn(Object neoBus) {
		subscribe((IEventBus) neoBus, PlayerEvent.PlayerLoggedInEvent.class, "PlayerLoggedInEvent",
				"MinecraftForge mods never learn a player joined, so join messages, login rewards and per-player "
						+ "state restored on connect do not happen",
				event -> ForgeEventFactory.firePlayerLoggedIn(event.getEntity()));
	}

	/** NeoForge {@code PlayerLoggedOutEvent} → MinecraftForge {@code firePlayerLoggedOut}. */
	public static void installLoggedOut(Object neoBus) {
		subscribe((IEventBus) neoBus, PlayerEvent.PlayerLoggedOutEvent.class, "PlayerLoggedOutEvent",
				"MinecraftForge mods never learn a player left, so per-player cleanup and saves on disconnect are "
						+ "skipped",
				event -> ForgeEventFactory.firePlayerLoggedOut(event.getEntity()));
	}

	/** NeoForge {@code PlayerRespawnEvent} → MinecraftForge {@code firePlayerRespawnEvent}. */
	public static void installRespawn(Object neoBus) {
		subscribe((IEventBus) neoBus, PlayerEvent.PlayerRespawnEvent.class, "PlayerRespawnEvent",
				"MinecraftForge mods never see a respawn, so whatever they restore or grant on death is lost",
				event -> ForgeEventFactory.firePlayerRespawnEvent(event.getEntity(), event.isEndConquered()));
	}

	/** NeoForge {@code PlayerChangedDimensionEvent} → MinecraftForge {@code onPlayerChangedDimension}. */
	public static void installChangedDimension(Object neoBus) {
		subscribe((IEventBus) neoBus, PlayerEvent.PlayerChangedDimensionEvent.class, "PlayerChangedDimensionEvent",
				"MinecraftForge mods never see a dimension change, so per-dimension state is not swapped when a "
						+ "player enters the Nether or the End",
				event -> ForgeEventFactory.onPlayerChangedDimension(event.getEntity(), event.getFrom(),
						event.getTo()));
	}

	/** The MinecraftForge side of one event. */
	@FunctionalInterface
	private interface ForgeForward<E> {
		void fire(E neoEvent) throws Throwable;
	}

	/**
	 * Subscribes one forward at LOWEST, reporting a failure once rather than per occurrence.
	 *
	 * <p>Once, not per event: a forward that fails on the first login fails on every login, and filling the log
	 * with one stack per player join buries whatever else went wrong. The cost sentence is in the warning because
	 * the alternative — "forward failed" — leaves the reader to work out that the mod's commands are gone.
	 */
	private static <E extends Event> void subscribe(IEventBus bus, Class<E> event, String name, String cost,
			ForgeForward<E> forge) {
		AtomicBoolean warned = new AtomicBoolean();
		Consumer<E> listener = neoEvent -> {
			try {
				forge.fire(neoEvent);
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] " + name + " forward failed — " + cost, Reflect.unwrap(t));
				}
			}
		};
		// Four-argument overload with LOWEST, as everywhere in this package: the shorter overloads open with
		// `getstatic EventPriority.NORMAL` and would silently promote the forward ahead of NeoForge's own
		// listeners, so a Forge mod would observe decisions that had not been made yet.
		bus.addListener(EventPriority.LOWEST, false, event, listener);
	}
}
