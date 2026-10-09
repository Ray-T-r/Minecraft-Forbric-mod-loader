package org.example.peal.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A step-sound wrap taking the step's position the way Mixin appends a target argument after the Operation — no
 * {@code @Local} — with a bare selector and its point written with a dotted owner and whitespace.
 */
@Mixin(Entity.class)
public abstract class StepPealMixin {
	@WrapOperation(method = "playStepSound",
			at = @At(value = "INVOKE", target = "net.minecraft.world.level.block.state.BlockState.getSoundType ()Lnet/minecraft/world/level/block/SoundType;"))
	private SoundType peal$step(BlockState state, Operation<SoundType> original, BlockPos pos) {
		return new SoundType(original.call(state).name() + "+peal" + pos.y());
	}
}
