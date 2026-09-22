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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Two bridges whose MinecraftForge side returns something, rather than only observing.
 *
 * <h2>Why these are not the observer shape</h2>
 *
 * <p>{@link KernelGamePlayerTrackingEvents} and the level lifecycle forward an event and discard what the hook
 * returns, because those hooks return nothing. These two do return: {@code onItemUseFinish} hands back the stack
 * the item turns into, and {@code onTrySpawnPortal} hands back the portal to build, or nothing to refuse it.
 * Forwarding them and dropping the answer would be the worst available outcome — a Forge mod's listener runs,
 * changes the value, and the game uses the value it had before. The mod is not silent and not working, which is
 * the state this branch keeps finding and deleting.
 *
 * <h2>Chosen the same way as the tracking pair</h2>
 *
 * <p>From the worklist, not a log: {@code nutritiousmilk} names {@code LivingEntityUseItemEvent$Finish} and
 * {@code collective} names {@code BlockEvent$PortalSpawnEvent}, and neither MinecraftForge hook has a call site
 * on the merged base while both NeoForge counterparts are posted and alive.
 */
public final class KernelGameResultBridges {
	private KernelGameResultBridges() {
	}

	/**
	 * NeoForge {@code LivingEntityUseItemEvent.Finish} → MinecraftForge {@code onItemUseFinish}.
	 *
	 * <p>Full fidelity: the hook's returned stack is written back with {@code setResultStack}, so a Forge mod
	 * that replaces what an item becomes — the case {@code nutritiousmilk} exists for — actually replaces it.
	 */
	public static void installItemUseFinish(Object neoBus) {
		AtomicBoolean warned = new AtomicBoolean();
		AtomicBoolean proved = new AtomicBoolean();
		((IEventBus) neoBus).addListener(EventPriority.LOWEST, false, LivingEntityUseItemEvent.Finish.class,
				event -> {
					try {
						ItemStack before = event.getResultStack();
						ItemStack after = ForgeEventFactory.onItemUseFinish(
								event.getEntity(), event.getItem(), event.getDuration(), before);
						if (after != null && after != before) event.setResultStack(after);
						if (proved.compareAndSet(false, true)) {
							ForbricLog.info("[Forbric/EventMux] bridged the first LivingEntityUseItemEvent.Finish "
									+ "to MinecraftForge, result stack included");
						}
					} catch (Throwable t) {
						if (warned.compareAndSet(false, true)) {
							ForbricLog.warn("[Forbric/EventMux] LivingEntityUseItemEvent.Finish forward failed — a "
									+ "MinecraftForge mod cannot change what an item becomes when it is finished, "
									+ "so food that should leave a bowl or a bottle leaves nothing",
									Reflect.unwrap(t));
						}
					}
				});
	}

	/**
	 * NeoForge {@code BlockEvent.PortalSpawnEvent} → MinecraftForge {@code onTrySpawnPortal}.
	 *
	 * <p><b>Partial fidelity, and it says so.</b> NeoForge's event is cancellable and exposes the shape through
	 * a getter with no setter, so a refusal carries and a REPLACEMENT cannot. A Forge mod that returns a
	 * different portal than it was given gets a line naming that, once, rather than silently having its
	 * substitution dropped — which is the failure this whole bridge exists to stop, arriving one level in.
	 */
	public static void installPortalSpawn(Object neoBus) {
		AtomicBoolean warned = new AtomicBoolean();
		AtomicBoolean proved = new AtomicBoolean();
		AtomicBoolean saidReplacement = new AtomicBoolean();
		((IEventBus) neoBus).addListener(EventPriority.LOWEST, false, BlockEvent.PortalSpawnEvent.class,
				event -> {
					try {
						PortalShape before = event.getPortalSize();
						Optional<PortalShape> after = ForgeEventFactory.onTrySpawnPortal(
								event.getLevel(), event.getPos(), Optional.ofNullable(before));
						if (after == null || after.isEmpty()) {
							event.setCanceled(true);
						} else if (after.get() != before && saidReplacement.compareAndSet(false, true)) {
							ForbricLog.warn("[Forbric/EventMux] a MinecraftForge mod returned a DIFFERENT portal "
									+ "shape from onTrySpawnPortal; NeoForge's event can be refused but not "
									+ "rewritten, so the refusal carries and the replacement does not");
						}
						if (proved.compareAndSet(false, true)) {
							ForbricLog.info("[Forbric/EventMux] bridged the first BlockEvent.PortalSpawnEvent to "
									+ "MinecraftForge — a refusal now carries");
						}
					} catch (Throwable t) {
						if (warned.compareAndSet(false, true)) {
							ForbricLog.warn("[Forbric/EventMux] BlockEvent.PortalSpawnEvent forward failed — a "
									+ "MinecraftForge mod cannot prevent a nether portal lighting",
									Reflect.unwrap(t));
						}
					}
				});
	}
}
