/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.LivingEntity;

/**
 * Vanilla's air refill for a body that breathes where its eyes are, which the carrier's breathing hook would hold instead.
 *
 * <p>Vanilla's {@code LivingEntity.baseTick} refills the air of a body that does not drown — by {@code increaseAirSupply}, 4 a
 * tick up to the maximum — everywhere but one place: in its water branch (the eye in water, outside a bubble column) it refills
 * only while {@code MobEffectUtil.shouldEffectsRefillAirsupply} allows it, which is false for a body that Breath of the Nautilus
 * alone keeps from drowning. NeoForge's {@code CommonHooks.onLivingBreathe} instead hands its breathe event a refill of zero
 * whenever the eye is in a fluid that is not air and the body does not drown there (water breathing, conduit power, a drowned,
 * an invulnerable player, lava, a mod's fluid it may breathe in), so the air stays where it was. The kernel replaces that zero
 * with what vanilla would refill ({@code VanillaAirRefillInjector}); the event is still posted with it, and a NeoForge listener
 * still sets the amounts and decides whether the body breathes, as on NeoForge.
 *
 * <p>The water question is asked of the body's fluid interaction rather than through {@code isEyeInFluid}: a native decision
 * island that puts vanilla's own {@code isEyeInFluid(WATER)} back in front of the hook (for a mod's injector there) must not see
 * the carrier asking that same call with operands of its own, or it would take the call as moved and leave the injector unbound.
 */
public final class KernelAirRefill {
	private KernelAirRefill() {
	}

	/** The hook's own protected branch: the eye is in a fluid that is not air, and the body does not drown there. */
	@SuppressWarnings("deprecation")   // NeoForge deprecates the tag question for its fluid types; it is vanilla's question, answered by them
	public static int protectedRefill(LivingEntity entity, int refill) {
		if (refills(entity)) return refill;
		// Breath of the Nautilus alone: vanilla holds the air in its water branch and refills it in any other fluid.
		return entity.getFluidInteraction().isEyeInFluid(FluidTags.WATER) ? 0 : refill;
	}

	/** Vanilla's water branch, already known to be the one taken (the native predicate decided it), for a body that does not drown. */
	public static int waterRefill(LivingEntity entity, int refill) {
		return refills(entity) ? refill : 0;
	}

	/** At the maximum the refill is zero (or less, which the hook clamps), so vanilla's effect check is asked only below it, as there. */
	private static boolean refills(LivingEntity entity) {
		return entity.getAirSupply() >= entity.getMaxAirSupply() || MobEffectUtil.shouldEffectsRefillAirsupply(entity);
	}
}
