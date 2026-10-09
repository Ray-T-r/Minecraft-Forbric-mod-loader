package org.example.echoes.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fixture.soundparity.Felt;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A step-sound mod written another way than the sample: a bare-name selector, no captures at all, and an original it hands
 * ANOTHER state — felt is heard as wool — before marking the group it gets back.
 */
@Mixin(Entity.class)
public abstract class StepEchoMixin {
	@WrapOperation(method = "playStepSound",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getSoundType()Lnet/minecraft/world/level/block/SoundType;"))
	private SoundType echoes$step(BlockState state, Operation<SoundType> original) {
		BlockState heard = state;
		if (state.getBlock() instanceof Felt felt) heard = felt.heardAs();
		return new SoundType(original.call(heard).name() + "+echo");
	}
}
