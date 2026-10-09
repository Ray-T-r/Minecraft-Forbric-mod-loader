package net.neoforged.neoforge.event;

import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/** Hand-written stand-in, not NeoForge code: true keeps the effect. NeoForge's own listeners keep "neo_kept". */
public final class EventHooks {
	private EventHooks() {
	}

	public static boolean onEffectRemoved(LivingEntity entity, MobEffectInstance effect) {
		return effect.name().equals("neo_kept");
	}
}
