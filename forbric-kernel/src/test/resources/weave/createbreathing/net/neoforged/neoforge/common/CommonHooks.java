package net.neoforged.neoforge.common;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.LivingEntity;

/** A stand-in for NeoForge's air-supply calculation: whether the entity can breathe, then its air goes down or back up. */
public final class CommonHooks {
	private CommonHooks() {
	}

	public static void onLivingBreathe(LivingEntity entity, ServerLevel level, int consumeAirAmount, int refillAirAmount) {
		// NeoForge's shape: the water-breathing check is asked with the eye in ANY fluid but air (vanilla asks it in water
		// alone), and only a drowning fluid (water here) then costs air.
		boolean canBreathe = !entity.isEyeInAnyFluid() || MobEffectUtil.hasWaterBreathing(entity) || !entity.isEyeInFluid(FluidTags.WATER);
		int air = entity.getAirSupply();
		entity.setAirSupply(canBreathe ? Math.min(10, air + refillAirAmount) : air - consumeAirAmount);
	}
}
