package org.example.slick.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fixture.blockquery.Grease;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A friction wrap written another way than the sample: a bare-name selector, no captures, and the block it was handed kept
 * in a local before it is passed on unchanged.
 */
@Mixin(Entity.class)
public abstract class SlideMixin {
	@WrapOperation(method = "slide", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
	private float slick$friction(Block block, Operation<Float> original) {
		Block under = block;
		if (under instanceof Grease) return 0.98F;
		return original.call(under);
	}
}
