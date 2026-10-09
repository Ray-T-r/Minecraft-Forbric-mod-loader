package org.example.scuba.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.Fluid;
import org.example.scuba.Gills;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Not Create, and not written like Create's pair: one wrap of the water-breathing call alone, a bare {@code "baseTick"}
 * selector, no {@code @Local}, the answer folded into one expression. Beside it, a wrap of the eye-in-water check whose
 * answer would change whether the entity drowns (goggles keep the eyes dry): NeoForge decides that itself, so this wrap
 * cannot be kept without dropping its answer, and is left as compiled — its loss reported, not hidden.
 */
@Mixin(LivingEntity.class)
public abstract class GillsMixin {
	@WrapOperation(method = "baseTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/effect/MobEffectUtil;hasWaterBreathing(Lnet/minecraft/world/entity/LivingEntity;)Z"))
	private boolean gills(LivingEntity mob, Operation<Boolean> original) {
		return original.call(mob) || Gills.grown(mob);
	}

	@WrapOperation(method = "baseTick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;isEyeInFluid(Lnet/minecraft/tags/TagKey;)Z"))
	private boolean dryEyes(LivingEntity mob, TagKey<Fluid> tag, Operation<Boolean> original) {
		return original.call(mob, tag) && !Gills.goggles(mob);
	}
}
