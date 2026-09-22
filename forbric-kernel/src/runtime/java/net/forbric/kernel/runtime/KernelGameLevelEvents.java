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
import net.minecraft.world.level.Level;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Re-emits the level lifecycle — load, unload, save — which the byte merge left NeoForge-only.
 *
 * <h2>The census</h2>
 *
 * <p>MinecraftForge declares all three hooks ({@code ForgeEventFactory.onLevelLoad},
 * {@code onLevelUnload}, {@code onLevelSave}, each taking a {@code Level}) and the merged base calls none
 * of them. Every producer is NeoForge's: {@code LevelEvent$Unload} is constructed and posted from
 * {@code Minecraft} (three sites) and {@code MinecraftServer}, {@code $Load} from {@code ClientLevel} and
 * {@code MinecraftServer} (two sites), {@code $Save} from {@code ServerLevel} — verified by disassembly,
 * not by a constant-pool scan, because a scan cannot tell a producer from a listener.
 *
 * <p>So a traditional-Forge mod listening on {@code LevelEvent.Unload} has a listener on a bus nobody
 * posts to. It is the same shape as the screen mouse ({@link KernelGameScreenMouseEvents}) and the
 * server tick, in a different producer, so neither of those bridges reaches it.
 *
 * <h2>What it costs while dead</h2>
 *
 * <p>Measured on the player's 28-mod pack: Xaero's World Map and Minimap each register a
 * {@code worldUnload(LevelEvent$Unload)} listener, and it never fires. {@code MapProcessor.onWorldUnload}
 * therefore never runs, so the map processor is never paused when a dimension or a world goes away —
 * it stays hot across a quit-to-title, and {@code ServerWorldCapabilities.loaded}, which defaults true
 * and has exactly one clearing writer reachable only through that listener, is never cleared for the
 * life of the process. Nothing crashes. The map simply keeps working on a world that is gone.
 *
 * <h2>Observers, not vetoes</h2>
 *
 * <p>None of the three is {@code ICancellableEvent}, so unlike {@link KernelGameEntityEvents} there is
 * nothing to carry back: the surviving hook has already decided and the MinecraftForge side only needs
 * to be told. That makes these the same shape as the tick bridges, and they are subscribed the same way.
 *
 * <p><b>{@code getLevel()} is a {@code LevelAccessor}, and MinecraftForge's hooks take a {@code Level}.</b>
 * Not the same type: NeoForge's event is constructed from a {@code LevelAccessor} at every one of the
 * sites above. The forward therefore pattern-matches and stays silent when it is not a {@code Level},
 * rather than casting — a ClassCastException thrown inside another family's post would take the
 * unload with it, and losing the world teardown is a far worse failure than one unbridged event.
 */
public final class KernelGameLevelEvents {
	private KernelGameLevelEvents() {
	}

	/** NeoForge {@code LevelEvent.Load} → MinecraftForge {@code onLevelLoad}. */
	public static void installLevelLoad(Object neoBus) {
		forward((IEventBus) neoBus, LevelEvent.Load.class, "LevelEvent.Load",
				"a MinecraftForge mod never learns a level came up, so per-world state it builds on load — "
						+ "caches keyed by dimension, per-level managers — is never built",
				event -> ForgeEventFactory.onLevelLoad(event.getLevel() instanceof Level level ? level : null));
	}

	/** NeoForge {@code LevelEvent.Unload} → MinecraftForge {@code onLevelUnload}. */
	public static void installLevelUnload(Object neoBus) {
		forward((IEventBus) neoBus, LevelEvent.Unload.class, "LevelEvent.Unload",
				"a MinecraftForge mod never learns a level went away, so whatever it holds for that world — "
						+ "Xaero's map processor, per-dimension caches, background workers — is never told to "
						+ "stop and keeps running against a world that is gone",
				event -> ForgeEventFactory.onLevelUnload(event.getLevel() instanceof Level level ? level : null));
	}

	/** NeoForge {@code LevelEvent.Save} → MinecraftForge {@code onLevelSave}. */
	public static void installLevelSave(Object neoBus) {
		forward((IEventBus) neoBus, LevelEvent.Save.class, "LevelEvent.Save",
				"a MinecraftForge mod that persists its own per-world data alongside the level's save never gets "
						+ "the chance, so its state is silently a save behind or lost",
				event -> ForgeEventFactory.onLevelSave(event.getLevel() instanceof Level level ? level : null));
	}

	/**
	 * Subscribes one observing forward at LOWEST, and says so once if it ever fails.
	 *
	 * <p>The forward is skipped rather than passed null when the accessor is not a {@code Level}: the hooks
	 * dereference their argument, so a null would turn "this bridge cannot express that level" into an NPE
	 * inside NeoForge's post.
	 */
	private static <E extends LevelEvent> void forward(IEventBus bus, Class<E> event, String name, String cost,
			ForgeForward<E> forge) {
		AtomicBoolean warned = new AtomicBoolean();
		AtomicBoolean proved = new AtomicBoolean();
		Consumer<E> listener = neoEvent -> {
			try {
				if (!(neoEvent.getLevel() instanceof Level)) return;
				forge.fire(neoEvent);
				if (proved.compareAndSet(false, true)) {
					ForbricLog.info("[Forbric/EventMux] bridged the first %s to MinecraftForge — a Forge-family "
							+ "mod now sees the level lifecycle NeoForge won on the merged base", name);
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

	/** One forward, so the three installs read as three lines rather than three lambdas with a cast in each. */
	@FunctionalInterface
	private interface ForgeForward<E extends Event> {
		void fire(E neoEvent) throws Throwable;
	}
}
