package net.minecraft.world.entity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Hand-written stand-in, not game code: the merged effect bookkeeping. Adding asks NeoForge's applicability hook where
 * vanilla asked {@code canBeAffected}; clearing asks NeoForge once per effect whether to keep it, and has no
 * {@code activeEffects.clear()}.
 */
public class LivingEntity extends Entity {
	private final Map<Holder<MobEffect>, MobEffectInstance> activeEffects = new HashMap<>();

	public LivingEntity(Level level) {
		super(level);
	}

	public boolean canBeAffected(MobEffectInstance effect) {
		return true;
	}

	public void forceAddEffect(MobEffectInstance effect, Entity source) {
		if (!CommonHooks.canMobEffectBeApplied(this, effect, source)) return;
		activeEffects.put(effect.getEffect(), effect);
	}

	public boolean removeAllEffects() {
		if (level().isClientSide()) return false;
		boolean removed = false;
		Iterator<MobEffectInstance> effects = activeEffects.values().iterator();
		while (effects.hasNext()) {
			MobEffectInstance effect = effects.next();
			if (EventHooks.onEffectRemoved(this, effect)) continue;
			effects.remove();
			removed = true;
		}
		return removed;
	}

	public List<String> effectNames() {
		return activeEffects.values().stream().map(MobEffectInstance::name).sorted().toList();
	}
}
