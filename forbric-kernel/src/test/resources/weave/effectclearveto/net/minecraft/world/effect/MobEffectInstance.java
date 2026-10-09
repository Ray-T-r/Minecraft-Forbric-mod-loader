package net.minecraft.world.effect;

import net.minecraft.core.Holder;

/** Hand-written stand-in, not game code: an effect applied to an entity. */
public record MobEffectInstance(Holder<MobEffect> getEffect) {
	public String name() {
		return getEffect.value().name();
	}
}
