package org.example.elsewhere.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A wrap of the query written for a method that never played a sound on either shape: there is nothing to move it to. */
@Mixin(Entity.class)
public abstract class StepOnMixin {
	@WrapOperation(method = "stepOn",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
	private SoundType elsewhere$step(BlockState state, Operation<SoundType> original) {
		return original.call(state);
	}
}
