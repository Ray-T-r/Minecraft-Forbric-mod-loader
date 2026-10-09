package org.example.swapper.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hands its original ANOTHER block: an original answering for the call site's own state would silently ignore it. */
@Mixin(Entity.class)
public abstract class SwapMixin {
	@WrapOperation(method = "slide", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
	private float swapper$friction(Block block, Operation<Float> original) {
		return original.call(new Block(0.1F));
	}
}
