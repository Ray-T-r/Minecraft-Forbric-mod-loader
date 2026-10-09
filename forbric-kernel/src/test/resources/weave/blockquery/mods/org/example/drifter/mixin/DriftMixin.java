package org.example.drifter.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Modifies the friction of the SECOND block drift asks about only ({@code ordinal = 1}), reading just the value. */
@Mixin(Entity.class)
public abstract class DriftMixin {
	@ModifyExpressionValue(method = "drift", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F", ordinal = 1))
	private float drifter$second(float friction) {
		return friction * 2.0F;
	}
}
