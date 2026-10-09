package org.example.relay.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.example.relay.Damper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A relay mod's damper, written against vanilla's getSignal like the redstone control the interaction adapter was built
 * from, but another way: a bare selector, and getSignal's position and side taken the way Mixin appends a target's
 * arguments after the Operation — unannotated — instead of an {@code @Local(argsOnly = true)}. A damper passes weak power
 * only from the side it faces, and only at the position asked about.
 */
@Mixin(SignalGetter.class)
public interface RelayDampMixin {
	@WrapOperation(method = "getSignal",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isRedstoneConductor(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z"))
	private boolean damp(BlockState state, BlockGetter getter, BlockPos at, Operation<Boolean> conductor, BlockPos asked, Direction from) {
		if (state.getBlock() instanceof Damper damper) return damper.passesFrom(from, asked);
		return conductor.call(state, getter, at);
	}
}
