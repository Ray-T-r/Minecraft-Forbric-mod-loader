package org.example.effects;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/** A mod's own "keep this effect through a clear" question, and a record of what it was asked. */
public final class Guards {
	public static final List<String> ASKED = new ArrayList<>();

	private Guards() {
	}

	public static boolean keep(LivingEntity entity, Holder<MobEffect> key, MobEffectInstance effect) {
		ASKED.add(effect.name());
		return key.value().name().equals("mod_kept");
	}
}
