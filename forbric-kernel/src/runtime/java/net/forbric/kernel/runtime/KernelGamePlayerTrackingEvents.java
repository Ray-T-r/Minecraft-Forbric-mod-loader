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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Re-emits "this player started/stopped tracking this entity", which the byte merge left NeoForge-only.
 *
 * <h2>How this one was chosen</h2>
 *
 * <p>Not by reading a log. {@code hook-worklist.sh} joins the hooks the merged base no longer calls to the mods
 * whose constant pools name the events those hooks post, and on the 97-jar pack this is one of seven with a
 * named waiter and no bridge: {@code collective} subscribes to {@code PlayerEvent$StartTracking}, and
 * {@code ForgeEventFactory.onStartEntityTracking} has no call site anywhere in the merged base. NeoForge's
 * counterpart is posted and alive, so a bridge has something to listen to — which is what made this one
 * buildable and three of the seven not.
 *
 * <h2>Why both halves, when only one has a waiter</h2>
 *
 * <p>Tracking is a pair. A mod told that a player started tracking an entity and never told that it stopped
 * accumulates per-viewer state for entities that are gone — the failure is a leak that grows with play time,
 * which is worse than the silence it replaces and would be blamed on the mod. {@code onStopEntityTracking} is
 * equally uncalled on the merged base and NeoForge posts its {@code StopTracking} too, so bridging one without
 * the other would be choosing to build that leak.
 *
 * <h2>Observers, not vetoes</h2>
 *
 * <p>Neither event is cancellable, so there is nothing to carry back: the surviving hook has already decided
 * and the MinecraftForge side only needs to be told. Same shape as the level lifecycle and the ticks.
 *
 * <p>The argument order is not the same on the two sides. NeoForge's event is {@code (Player, Entity)} and
 * reads back as {@code getEntity()} / {@code getTarget()}; MinecraftForge's hook takes
 * {@code (Entity target, Player player)}. Getting that backwards would compile and deliver every event with its
 * two halves swapped, which is the kind of thing that looks like the bridge working.
 */
public final class KernelGamePlayerTrackingEvents {
	private KernelGamePlayerTrackingEvents() {
	}

	/** NeoForge {@code PlayerEvent.StartTracking} → MinecraftForge {@code onStartEntityTracking}. */
	public static void installStartTracking(Object neoBus) {
		forward((IEventBus) neoBus, PlayerEvent.StartTracking.class, "PlayerEvent.StartTracking",
				"a MinecraftForge mod is never told a player began tracking an entity, so anything it builds "
						+ "per viewer — nameplate state, per-player entity data, sync on first sight — is never "
						+ "built and the entity simply behaves as though that mod were not installed",
				event -> ForgeEventFactory.onStartEntityTracking(target(event), player(event)));
	}

	/** NeoForge {@code PlayerEvent.StopTracking} → MinecraftForge {@code onStopEntityTracking}. */
	public static void installStopTracking(Object neoBus) {
		forward((IEventBus) neoBus, PlayerEvent.StopTracking.class, "PlayerEvent.StopTracking",
				"a MinecraftForge mod is never told a player stopped tracking an entity, so whatever it built "
						+ "per viewer is never torn down — a leak that grows for as long as the session lasts",
				event -> ForgeEventFactory.onStopEntityTracking(target(event), player(event)));
	}

	private static Entity target(PlayerEvent event) {
		return event instanceof PlayerEvent.StartTracking start ? start.getTarget()
				: ((PlayerEvent.StopTracking) event).getTarget();
	}

	private static Player player(PlayerEvent event) {
		return event.getEntity();
	}

	/** What a forward does with one event, so the two installs differ in nothing else. */
	@FunctionalInterface
	private interface ForgeForward<E> {
		void fire(E event);
	}

	/**
	 * Subscribes one observing forward at LOWEST, and says so once if it ever fails.
	 *
	 * <p>Tracking fires many times a second on a busy server, so both the success note and the failure warning
	 * are once-only: a per-event line here would be the loudest thing in the log and the first thing switched
	 * off.
	 */
	private static <E extends PlayerEvent> void forward(IEventBus bus, Class<E> event, String name, String cost,
			ForgeForward<E> forge) {
		AtomicBoolean warned = new AtomicBoolean();
		AtomicBoolean proved = new AtomicBoolean();
		Consumer<E> listener = neoEvent -> {
			try {
				if (neoEvent.getEntity() == null) return;
				forge.fire(neoEvent);
				if (proved.compareAndSet(false, true)) {
					ForbricLog.info("[Forbric/EventMux] bridged the first %s to MinecraftForge — a Forge-family "
							+ "mod now sees entity tracking NeoForge won on the merged base", name);
				}
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] " + name + " forward failed — " + cost, Reflect.unwrap(t));
				}
			}
		};
		// The four-argument overload, ALWAYS: the shorter ones default to NORMAL, and LOWEST is what makes this
		// forward run after every NeoForge listener has had the event. The boolean is receiveCanceled.
		bus.addListener(EventPriority.LOWEST, false, event, listener);
	}
}
