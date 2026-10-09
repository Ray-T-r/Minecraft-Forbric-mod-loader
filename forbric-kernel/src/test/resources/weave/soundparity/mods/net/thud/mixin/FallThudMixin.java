package net.thud.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A landing-sound mod that captures two of the three block coordinates, the third first: {@code @Local(ordinal = 2)} is
 * the z coordinate and {@code @Local(ordinal = 0)} the x, whatever order the handler lists them in.
 */
@Mixin(LivingEntity.class)
public abstract class FallThudMixin {
	@WrapOperation(method = "playBlockFallSound()V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
	private SoundType thud$fall(BlockState state, Operation<SoundType> original, @Local(ordinal = 2) int z, @Local(ordinal = 0) int x) {
		return new SoundType(original.call(state).name() + "@" + x + "," + z);
	}
}
