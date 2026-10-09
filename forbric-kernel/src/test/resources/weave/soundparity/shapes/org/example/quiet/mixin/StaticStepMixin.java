package org.example.quiet.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A step wrap with a static handler and a dotted, owner-qualified selector. */
@Mixin(Entity.class)
public abstract class StaticStepMixin {
	@WrapOperation(method = "net.minecraft.world.entity.Entity.playStepSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V",
			at = @At(value = "INVOKE", target = "net/minecraft/world/level/block/state/BlockState.getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
	private static SoundType quiet$step(BlockState state, Operation<SoundType> original) {
		return new SoundType(original.call(state).name() + "+quiet");
	}
}
