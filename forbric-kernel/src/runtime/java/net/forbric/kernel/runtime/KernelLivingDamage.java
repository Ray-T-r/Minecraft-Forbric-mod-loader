/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.ForgeHooks;
import net.neoforged.neoforge.common.damagesource.DamageContainer;

/**
 * MinecraftForge's {@code LivingHurtEvent}, {@code LivingDamageEvent} and the player half of {@code LivingAttackEvent},
 * posted from seams ForgeDamageSeamsInjector puts in the merged {@code actuallyHurt} and {@code Player.hurtServer}.
 *
 * <p>No NeoForge event sits where these were. Hurt is asked before armour — after the shield, the i-frame check and
 * NeoForge's incoming-damage event — and a MinecraftForge answer of zero or less ends the hit there, as it does
 * natively. Damage is asked after armour, magic and absorption, just before the health is written, which is where
 * MinecraftForge asks it (NeoForge's {@code LivingDamageEvent.Pre} is before absorption, so a lethal check made there
 * would fire on hits absorption would soak). Both answers go through the hit's {@code DamageContainer}, so
 * NeoForge's {@code LivingDamageEvent.Post} reports what actually happened.
 */
public final class KernelLivingDamage {
	private static final AtomicBoolean PLAYER_SEAM = new AtomicBoolean();
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	/** Hits whose Hurt ended at zero with nothing to take away: MinecraftForge never reaches Damage for them. */
	private static final ThreadLocal<Set<DamageContainer>> NO_DAMAGE =
			ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

	private KernelLivingDamage() {
	}

	/** Whether the player-attack seam is in, so the attack forward leaves players to it. */
	public static boolean playerSeamInstalled() {
		return PLAYER_SEAM.get();
	}

	/** Called once from {@code Player.<clinit>} when the seam was put in. */
	public static void notePlayerSeam() {
		PLAYER_SEAM.set(true);
	}

	/**
	 * The Hurt seam. True ends {@code actuallyHurt} now: MinecraftForge answered zero or less for a hit that had
	 * damage (its native {@code amount <= 0 → return}), or a MinecraftForge listener killed the entity (asked by the
	 * seam itself, which can read {@code dead}).
	 */
	public static boolean hurt(LivingEntity entity, DamageContainer container, DamageSource source) {
		try {
			float in = container.getNewDamage();
			float out = ForgeHooks.onLivingHurt(entity, source, in);
			if (!(out > 0)) {
				if (in > 0) {
					container.setNewDamage(0);
					return true;
				}
				NO_DAMAGE.get().add(container);
				return false;
			}
			if (out != in) container.setNewDamage(out);
		} catch (Throwable t) {
			warn("LivingHurtEvent", t);
		}
		return false;
	}

	/** The Damage seam: the health damage after armour, magic and absorption, as MinecraftForge's listeners leave it. */
	public static float damage(LivingEntity entity, DamageContainer container, DamageSource source, float amount) {
		if (NO_DAMAGE.get().remove(container)) return amount;
		try {
			float out = ForgeHooks.onLivingDamage(entity, source, amount);
			if (Float.isNaN(out) || out < 0) {
				warn("LivingDamageEvent", new IllegalArgumentException("a listener set " + out
						+ "; NeoForge's damage cannot be negative, so it is taken as 0"));
				out = 0;
			}
			if (out != amount) container.setNewDamage(out);
			return out;
		} catch (Throwable t) {
			warn("LivingDamageEvent", t);
			return amount;
		}
	}

	/** The player-attack seam, at the head of {@code Player.hurtServer}: false ends the hit, as MinecraftForge's does. */
	public static boolean playerAttack(LivingEntity player, DamageSource source, float amount) {
		try {
			return ForgeHooks.onPlayerAttack(player, source, amount);
		} catch (Throwable t) {
			warn("LivingAttackEvent", t);
			return true;
		}
	}

	private static void warn(String event, Throwable t) {
		if (WARNED.compareAndSet(false, true)) {
			ForbricLog.warn("[Forbric/Damage] MinecraftForge's " + event + " failed inside the damage pipeline — the hit "
					+ "went on as NeoForge computed it", t);
		}
	}
}
