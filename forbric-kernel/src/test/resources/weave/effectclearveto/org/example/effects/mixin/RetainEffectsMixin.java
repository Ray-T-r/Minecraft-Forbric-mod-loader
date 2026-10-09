package org.example.effects.mixin;

import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

import org.example.effects.Guards;

/**
 * Synthetic guest mixin, not balm's shape: a clear-all veto written as a snapshot, the original clear, then a loop that
 * puts back what the mod's question keeps. Against vanilla it wraps {@code activeEffects.clear()}; the merged entity has
 * no such call.
 */
@Mixin(LivingEntity.class)
abstract class RetainEffectsMixin {
	@WrapOperation(method = "removeAllEffects", at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V"))
	private void retain(Map<Holder<MobEffect>, MobEffectInstance> effects, Operation<Void> original) {
		LivingEntity self = (LivingEntity) (Object) this;
		Set<Map.Entry<Holder<MobEffect>, MobEffectInstance>> before = Set.copyOf(effects.entrySet());
		original.call(effects);
		for (Map.Entry<Holder<MobEffect>, MobEffectInstance> entry : before) {
			if (Guards.keep(self, entry.getKey(), entry.getValue())) effects.put(entry.getKey(), entry.getValue());
		}
	}
}
