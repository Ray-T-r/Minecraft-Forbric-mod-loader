/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.damagesource.DamageContainer;

/**
 * Vanilla's reads of {@code actuallyHurt}'s damage, put back for Fabric mixins (VanillaDamageReadInjector): NeoForge's
 * body keeps the hit's amount in its {@code DamageContainer}, so a Fabric mod's {@code @ModifyVariable} on vanilla's
 * local would otherwise change nothing. Each helper moves the container by exactly what the mod changed and leaves it
 * alone when nothing was changed, so NeoForge's own amount stays as NeoForge computed it.
 */
public final class KernelLivingDamage {
	private KernelLivingDamage() {
	}

	/**
	 * Vanilla's first read of {@code actuallyHurt}'s damage, which a mod may have rewritten ({@code read}), against the
	 * parameter as it came ({@code param}); VanillaDamageReadInjector places it before armour. A rewrite moves the
	 * container by the same amount; no rewrite changes nothing, so NeoForge's own amount stays exactly as it was.
	 */
	public static void vanillaRead(LivingEntity entity, DamageContainer container, float read, float param) {
		if (container == null || Float.floatToIntBits(read) == Float.floatToIntBits(param)) return;
		container.setNewDamage(Math.max(0.0F, container.getNewDamage() + (read - param)));
	}

	/**
	 * Vanilla's value at the absorption call, which a mod may have rewritten ({@code damage}), against what the container held
	 * when it was read ({@code read}); VanillaDamageReadInjector places the read before {@code setAbsorptionAmount} and this
	 * after it. A rewrite becomes the container's amount (never below zero, as NeoForge requires); no rewrite changes nothing.
	 * Returns the container's amount, which the body goes on with.
	 */
	public static float absorbedRead(LivingEntity entity, DamageContainer container, float damage, float read) {
		if (container == null) return damage;
		if (Float.floatToIntBits(damage) != Float.floatToIntBits(read)) container.setNewDamage(Math.max(0.0F, damage));
		return container.getNewDamage();
	}

	/**
	 * The argument of vanilla's second {@code setAbsorptionAmount}, after the health: {@code getAbsorptionAmount() - damage}
	 * while the absorption is spent (vanilla's case, where the call changes nothing), the current absorption otherwise.
	 */
	public static float spentAbsorption(LivingEntity entity, float damage) {
		float absorption = entity.getAbsorptionAmount();
		return absorption <= 0.0F ? absorption - damage : absorption;
	}
}
