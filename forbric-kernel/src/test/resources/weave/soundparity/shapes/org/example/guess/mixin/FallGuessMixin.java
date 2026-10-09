package org.example.guess.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A landing wrap capturing a BlockPos local. Vanilla's landing sound keeps no such local — only the merged one does — so
 * there is nothing to prove the capture against, and the merged local is not taken as a guess.
 */
@Mixin(LivingEntity.class)
public abstract class FallGuessMixin {
	@WrapOperation(method = "playBlockFallSound",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
	private SoundType guess$fall(BlockState state, Operation<SoundType> original, @Local BlockPos pos) {
		return new SoundType(original.call(state).name() + "@" + pos);
	}
}
