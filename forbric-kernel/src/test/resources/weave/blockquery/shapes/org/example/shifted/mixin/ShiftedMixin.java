package org.example.shifted.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Wraps a query whose merged counterpart asks about another position: the bodies, read side by side, do not pair. */
@Mixin(Entity.class)
public abstract class ShiftedMixin {
	@WrapOperation(method = "shifted", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
	private float shifted$friction(Block block, Operation<Float> original) {
		return original.call(block);
	}
}
