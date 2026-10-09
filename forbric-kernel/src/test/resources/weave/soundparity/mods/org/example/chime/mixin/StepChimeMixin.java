package org.example.chime.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A second, unrelated mod wrapping the same vanilla step-sound query: a fully described selector and the step's position as
 * an argument capture. With the other step mod it nests as vanilla nests two wraps of one call.
 */
@Mixin(Entity.class)
public abstract class StepChimeMixin {
	@WrapOperation(method = "playStepSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
	private SoundType chime$step(BlockState state, Operation<SoundType> original, @Local(argsOnly = true) BlockPos pos) {
		return new SoundType(original.call(state).name() + "+chime" + pos.y());
	}
}
