package org.example.reassigned.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fixture.blockquery.Grease;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Reassigns its own parameter on one path before passing it on: what reaches the original is not always what it got. */
@Mixin(Entity.class)
public abstract class ReassignMixin {
	@WrapOperation(method = "slide", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
	private float reassigned$friction(Block block, Operation<Float> original) {
		if (block instanceof Grease) block = new Block(0.9F);
		return original.call(block);
	}
}
