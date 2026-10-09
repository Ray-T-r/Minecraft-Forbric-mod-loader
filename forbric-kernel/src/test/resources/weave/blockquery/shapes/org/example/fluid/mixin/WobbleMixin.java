package org.example.fluid.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Wraps a query the merged method asks of a fluid: a call of the same name, but nothing leads from it back to a block. */
@Mixin(Entity.class)
public abstract class WobbleMixin {
	@WrapOperation(method = "wobble", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
	private float fluid$friction(Block block, Operation<Float> original) {
		return original.call(block);
	}
}
