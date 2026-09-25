/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;
import net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent;

/**
 * MinecraftForge's attack, shield, knockback and fall events, off the NeoForge events that now sit where they were.
 *
 * <p>The merged {@code LivingEntity} kept NeoForge's damage pipeline: nothing calls {@code ForgeHooks.onLivingAttack},
 * {@code ForgeEventFactory.onShieldBlock} or {@code onLivingKnockBack}, and {@code onLivingFall} only from horses and
 * llamas (the two bodies the merge took from MinecraftForge — NeoForge posts nothing there, so bridging falls cannot
 * post twice). These four have a NeoForge event at MinecraftForge's position, posted and read back by the caller, so
 * they are forwards at LOWEST like {@link KernelGameEntityEvents}: every NeoForge listener has decided first, a
 * MinecraftForge cancel is carried back one way, and a MinecraftForge change is written back only where it differs.
 * Hurt and Damage have no such event and are a repair instead ({@link KernelLivingDamage}).
 */
public final class KernelGameDamageEvents {
	private KernelGameDamageEvents() {
	}

	/**
	 * NeoForge {@code LivingIncomingDamageEvent} → MinecraftForge {@code LivingAttackEvent}, cancel carried back.
	 *
	 * <p>Players are left to the kernel's own seam at the head of {@code Player.hurtServer} when it is in: that is
	 * MinecraftForge's position (before difficulty scaling, and for the zero-damage hits — a snowball — that never
	 * reach this event), and {@code ForgeHooks.onLivingAttack} skips players exactly as MinecraftForge's own split
	 * does. Without the seam every entity is asked here.
	 */
	public static void installLivingAttack(Object neoBus) {
		KernelGameEntityEvents.subscribe((IEventBus) neoBus, LivingIncomingDamageEvent.class, "LivingIncomingDamageEvent",
				"a MinecraftForge mod that makes an entity immune or reacts to an attack (ghosts, shields, perks) "
						+ "does nothing",
				event -> KernelLivingDamage.playerSeamInstalled()
						? !ForgeHooks.onLivingAttack(event.getEntity(), event.getSource(), event.getAmount())
						: ForgeEventFactory.onLivingAttackEntity(event.getEntity(), event.getSource(), event.getAmount()));
	}

	/**
	 * NeoForge {@code LivingShieldBlockEvent} → MinecraftForge {@code ShieldBlockEvent}.
	 *
	 * <p>MinecraftForge never offers a bypassed hit, so a hit NeoForge does not block is not asked. A MinecraftForge
	 * cancel unblocks it (one way), a changed blocked amount is written back, and "the shield takes no damage" sets
	 * NeoForge's shield damage to zero — never raised.
	 */
	public static void installShieldBlock(Object neoBus) {
		observe((IEventBus) neoBus, LivingShieldBlockEvent.class, "LivingShieldBlockEvent",
				"a MinecraftForge mod that changes what a shield blocks or whether it wears does nothing",
				event -> {
					if (!event.getBlocked()) return;
					ItemStack with = event.getEntity().getItemBlockingWith();
					if (with == null) return;
					var forge = ForgeEventFactory.onShieldBlock(event.getEntity(), event.getDamageSource(),
							event.getBlockedDamage(), with);
					if (forge == null) {
						event.setBlocked(false);
						return;
					}
					if (forge.getBlockedDamage() != event.getBlockedDamage()) event.setBlockedDamage(forge.getBlockedDamage());
					if (!forge.shieldTakesDamage()) event.setShieldDamage(0);
				});
	}

	/** NeoForge {@code LivingKnockBackEvent} → MinecraftForge {@code LivingKnockBackEvent}: cancel and values back. */
	public static void installKnockBack(Object neoBus) {
		observe((IEventBus) neoBus, LivingKnockBackEvent.class, "LivingKnockBackEvent",
				"a MinecraftForge mod that cancels or changes knockback does nothing",
				event -> {
					if (event.isCanceled()) return;
					var forge = ForgeEventFactory.onLivingKnockBack(event.getEntity(), event.getStrength(),
							event.getRatioX(), event.getRatioZ());
					if (forge == null) {
						event.setCanceled(true);
						return;
					}
					if (forge.getStrength() != event.getStrength()) event.setStrength(forge.getStrength());
					if (forge.getRatioX() != event.getRatioX()) event.setRatioX(forge.getRatioX());
					if (forge.getRatioZ() != event.getRatioZ()) event.setRatioZ(forge.getRatioZ());
				});
	}

	/** NeoForge {@code LivingFallEvent} → MinecraftForge {@code LivingFallEvent}: cancel and values back. */
	public static void installFall(Object neoBus) {
		observe((IEventBus) neoBus, LivingFallEvent.class, "LivingFallEvent",
				"a MinecraftForge mod that cancels or changes fall damage does it for horses and llamas only",
				event -> {
					if (event.isCanceled()) return;
					var forge = ForgeEventFactory.onLivingFall(event.getEntity(), event.getDistance(),
							event.getDamageMultiplier());
					if (forge == null) {
						event.setCanceled(true);
						return;
					}
					if (forge.getDistance() != event.getDistance()) event.setDistance(forge.getDistance());
					if (forge.getDamageMultiplier() != event.getDamageMultiplier()) {
						event.setDamageMultiplier(forge.getDamageMultiplier());
					}
				});
	}

	/** A LOWEST forward that reads the MinecraftForge answer itself; one warning per event if it throws. */
	private static <E extends Event> void observe(IEventBus bus, Class<E> event, String name, String cost,
			Consumer<E> forward) {
		AtomicBoolean warned = new AtomicBoolean();
		bus.addListener(EventPriority.LOWEST, false, event, neoEvent -> {
			try {
				forward.accept(neoEvent);
			} catch (Throwable t) {
				if (warned.compareAndSet(false, true)) {
					ForbricLog.warn("[Forbric/EventMux] " + name + " forward failed — " + cost, Reflect.unwrap(t));
				}
			}
		});
	}
}
