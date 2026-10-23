/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * Vanilla's air refill for a body that breathes where its eyes are, which the carrier's breathing hook would hold instead.
 *
 * <p>Vanilla's {@code LivingEntity.baseTick} refills the air of a body that does not drown — by {@code increaseAirSupply}, 4 a
 * tick up to the maximum — everywhere but one place: in its water branch (the eye in water, outside a bubble column) it refills
 * only while {@code MobEffectUtil.shouldEffectsRefillAirsupply} allows it, which is false for a body that Breath of the Nautilus
 * alone keeps from drowning. The water branch is the fluid a body can drown in: fabric-api's custom drowning fluids answer
 * vanilla's {@code isEyeInFluid(WATER)} as water, for an entity their behaviour drowns. NeoForge's
 * {@code CommonHooks.onLivingBreathe} instead hands its breathe event a refill of zero whenever the eye is in a fluid that is not
 * air and the body does not drown there (water breathing, conduit power, a drowned, an invulnerable player, lava, a mod's fluid
 * it may breathe in), so the air stays where it was. The kernel replaces that zero with what vanilla would refill
 * ({@code VanillaAirRefillInjector}); the event is still posted with it, and a NeoForge listener still sets the amounts and
 * decides whether the body breathes, as on NeoForge. Vanilla also changes the air only below the maximum, where the hook's air
 * update pulls air above it back down every tick the body breathes; {@link #refilledAir} leaves it there, unless a listener
 * set the refill.
 *
 * <p>Which fluids drown the body is asked of the body's fluid interaction — the eye's fluid types, each answering
 * {@link FluidType#canDrownIn} for this body (water's own type does, lava's does not, a mod's type answers for itself, a Fabric
 * behaviour's type answers what the behaviour says) — and not through {@code isEyeInFluid}: a native decision island that puts
 * vanilla's own {@code isEyeInFluid(WATER)} back in front of the hook (for a mod's injector there) must not see the carrier
 * asking that same call with operands of its own, or it would take the call as moved and leave the injector unbound.
 */
public final class KernelAirRefill {
	private KernelAirRefill() {
	}

	/** The hook's own protected branch: the eye is in a fluid that is not air, and the body does not drown there. */
	public static int protectedRefill(LivingEntity entity, int refill) {
		if (refills(entity)) return refill;
		// Breath of the Nautilus alone: vanilla holds the air in its water branch — the eye in a fluid the body can drown in —
		// and refills it in any other fluid (lava, a fluid it breathes in).
		return entity.getFluidInteraction().isEyeInFluidMatching(entity, KernelAirRefill::drowns) ? 0 : refill;
	}

	/** Vanilla's water branch, already known to be the one taken (the native predicate decided it), for a body that does not drown. */
	public static int waterRefill(LivingEntity entity, int refill) {
		return refills(entity) ? refill : 0;
	}

	/**
	 * The air of a body that breathes after its breathe event: NeoForge's {@code min(air + refill, max)} ({@code sum} is
	 * {@code air + decided}) — but where the listeners left the event's refill as it started ({@code started}, read from the
	 * event before it was posted) and the air is at or above the maximum, the air stays where it is, as in vanilla, which
	 * changes it only below the maximum. A refill a listener set is NeoForge's to apply, clamp included. (A listener that sets
	 * the very amount the event started from is the one case that cannot be told apart; it gets vanilla's update.)
	 */
	public static int refilledAir(int sum, int max, int started, int decided) {
		int air = sum - decided;
		return decided == started && air >= max ? air : Math.min(sum, max);
	}

	/** The type's own answer, for this body: water's type drowns everyone in it, lava's no one, a mod's type answers for itself. */
	private static boolean drowns(LivingEntity entity, FluidType type, double height) {
		return type.canDrownIn(entity);
	}

	/** At or above the maximum vanilla refills nothing (and {@link #refilledAir} leaves the air there), so the effect check is asked only below it, as there. */
	private static boolean refills(LivingEntity entity) {
		return entity.getAirSupply() >= entity.getMaxAirSupply() || MobEffectUtil.shouldEffectsRefillAirsupply(entity);
	}
}
