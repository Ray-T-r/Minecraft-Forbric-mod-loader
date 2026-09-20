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

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * Re-emits the CANCELLABLE entity events the byte merge left NeoForge-only.
 *
 * <h2>Why cancellation makes these different from every other bridge</h2>
 *
 * <p>The tick, login and command bridges are observers: the surviving hook has already decided, and the
 * MinecraftForge side only needs to be told. These three are not. {@code LivingDeathEvent},
 * {@code LivingDropsEvent} and {@code EntityJoinLevelEvent} are all {@code ICancellableEvent}, and a
 * MinecraftForge mod cancelling one is the whole point of listening — a grave mod cancels the death, a mob-filter
 * cancels the join, a drop-control mod cancels the drops. Forwarding without carrying the cancel back would give
 * those mods a listener that runs, decides, and is ignored, which is worse than the listener not running: it
 * looks like it works.
 *
 * <p>It CAN be carried back, and the reason is the ordering. The merged base posts the NeoForge event and reads
 * {@code isCanceled()} off it AFTER the post returns (javap on {@code ServerLevel}: construct the event,
 * {@code IEventBus.post}, then branch on the result). These forwards run at LOWEST, i.e. last inside that post,
 * so cancelling the NeoForge event there is still observed by the caller.
 *
 * <p><b>Cancel is one-way.</b> A forward may set cancelled, never clear it. NeoForge's own listeners ran first
 * and a MinecraftForge mod has no standing to overrule them — if it did, a Forge mod that merely does not cancel
 * would silently undo a NeoForge mod's cancellation, which is a bug in the opposite direction and a much harder
 * one to find.
 */
public final class KernelGameEntityEvents {
	private KernelGameEntityEvents() {
	}

	/**
	 * NeoForge {@code LivingDeathEvent} → MinecraftForge {@code onLivingDeath}, cancel carried back.
	 *
	 * <p>{@code LivingEntity} on the merged base calls {@code CommonHooks.onLivingDeath}; MinecraftForge's site is
	 * gone. Its factory returns true when the death was cancelled.
	 */
	public static void installLivingDeath(Object neoBus) {
		subscribe((IEventBus) neoBus, LivingDeathEvent.class, "LivingDeathEvent",
				"a MinecraftForge mod that prevents or reacts to a death (graves, keep-inventory, totems) does "
						+ "nothing at all",
				event -> ForgeEventFactory.onLivingDeath(event.getEntity(), event.getSource()));
	}

	/**
	 * NeoForge {@code LivingDropsEvent} → MinecraftForge {@code onLivingDrops}, cancel carried back.
	 *
	 * <p>The drops collection crosses by REFERENCE, which is what makes the forward useful rather than decorative:
	 * a MinecraftForge mod that adds to or removes from it is editing the same collection the game is about to
	 * spill into the world.
	 */
	public static void installLivingDrops(Object neoBus) {
		subscribe((IEventBus) neoBus, LivingDropsEvent.class, "LivingDropsEvent",
				"a MinecraftForge mod that adds, removes or suppresses mob drops has no effect",
				event -> ForgeEventFactory.onLivingDrops(event.getEntity(), event.getSource(), event.getDrops(),
						event.isRecentlyHit()));
	}

	/**
	 * NeoForge {@code EntityJoinLevelEvent} → MinecraftForge {@code onEntityJoinLevel}, cancel carried back.
	 *
	 * <p>Cancelling this is how a mod refuses an entity entry to the world at all — mob filters, anti-farm rules,
	 * spawn control. javap on {@code ServerLevel} shows the merged base constructing NeoForge's event, posting it
	 * and branching on the result, with no MinecraftForge site anywhere.
	 */
	public static void installEntityJoinLevel(Object neoBus) {
		subscribe((IEventBus) neoBus, EntityJoinLevelEvent.class, "EntityJoinLevelEvent",
				"a MinecraftForge mod that refuses an entity entry to the world (mob filters, anti-farm rules, "
						+ "spawn control) is overruled silently",
				event -> ForgeEventFactory.onEntityJoinLevel(event.getEntity(), event.getLevel(),
						event.loadedFromDisk()));
	}

	/** The MinecraftForge side of one cancellable event. Returns true when MinecraftForge cancelled it. */
	@FunctionalInterface
	interface ForgeVeto<E> {
		boolean fire(E neoEvent) throws Throwable;
	}

	/**
	 * Subscribes one forward at LOWEST and carries a MinecraftForge cancellation back onto the NeoForge event.
	 *
	 * <p>LOWEST is load-bearing twice over here: it is last inside the post, so the cancel still reaches the
	 * caller that reads {@code isCanceled()} afterwards, and it means every NeoForge listener has already had its
	 * say before a MinecraftForge mod is asked.
	 */
	static <E extends Event & ICancellableEvent> void subscribe(IEventBus bus, Class<E> event, String name,
			String cost, ForgeVeto<E> forge) {
		AtomicBoolean warned = new AtomicBoolean();
		bus.addListener(EventPriority.LOWEST, false, event, neoEvent -> {
			try {
				carryVeto(neoEvent.isCanceled(), forge.fire(neoEvent), neoEvent::setCanceled);
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] " + name + " forward failed — " + cost, Reflect.unwrap(t));
				}
			}
		});
	}

	/** Applies a cancellation to the NeoForge event, or does nothing. Split out so a test can drive the rule. */
	@FunctionalInterface
	interface Veto {
		void setCanceled(boolean canceled);
	}

	/**
	 * The one-way cancel rule, as one decision.
	 *
	 * <p>Set only when MinecraftForge vetoed and NeoForge had not already. Never call {@code setCanceled} at all
	 * otherwise — not even {@code setCanceled(false)}, which would be how a MinecraftForge mod that merely does
	 * not cancel silently undoes a NeoForge mod's cancellation. That is a bug in the opposite direction and a far
	 * harder one to find than the one this bridge fixes, so the write is guarded rather than the value computed.
	 *
	 * @param alreadyCanceled whether a NeoForge listener has already cancelled the event
	 * @param forgeVetoed     whether the MinecraftForge forward reported a cancellation
	 */
	static void carryVeto(boolean alreadyCanceled, boolean forgeVetoed, Veto veto) {
		if (forgeVetoed && !alreadyCanceled) veto.setCanceled(true);
	}
}
