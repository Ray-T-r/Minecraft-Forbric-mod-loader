package org.example.leaky.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Hands its original to a helper: what the helper passes it is not this handler's to prove. */
@Mixin(Entity.class)
public abstract class LeakMixin {
	@WrapOperation(method = "slide", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;getFriction()F"))
	private float leaky$friction(Block block, Operation<Float> original) {
		return leaky$ask(original, block);
	}

	private static float leaky$ask(Operation<Float> original, Block block) {
		return original.call(block);
	}
}
