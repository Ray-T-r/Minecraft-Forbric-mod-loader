package org.example.spread.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Passes the block it was handed on through an argument array it builds in a local first, Kotlin's way. */
@Mixin(Entity.class)
public abstract class SpreadMixin {
	@WrapOperation(method = "slide", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
	private float spread$friction(Block block, Operation<Float> original) {
		Object[] arguments = new Object[1];
		arguments[0] = block;
		return original.call(arguments) * 1.0F;
	}
}
