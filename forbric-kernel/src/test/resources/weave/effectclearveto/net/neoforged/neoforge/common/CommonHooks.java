package net.neoforged.neoforge.common;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Hand-written stand-in, not NeoForge code: the applicability question NeoForge's forceAddEffect asks. */
public final class CommonHooks {
	private CommonHooks() {
	}

	public static boolean canMobEffectBeApplied(LivingEntity entity, MobEffectInstance effect, Entity source) {
		return true;
	}
}
