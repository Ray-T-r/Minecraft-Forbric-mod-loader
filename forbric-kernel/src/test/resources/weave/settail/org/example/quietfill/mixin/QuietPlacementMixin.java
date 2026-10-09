package org.example.quietfill.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import fixture.settail.Quiet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * setBlock's tail hooked another way than Carpet's fill and C2ME's threshold, by one mixin of another mod: the
 * neighbour update conditioned (a @WrapWithCondition, not a @Redirect, and no @ModifyConstant beside it), the chunk-status
 * check's result modified (a @ModifyExpressionValue, not a @ModifyArg), both selectors bare names.
 */
@Mixin(Level.class)
public abstract class QuietPlacementMixin {
	@WrapWithCondition(method = "setBlock",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V"))
	private boolean quietfill$onlyLoud(Level level, BlockPos pos, Block block) {
		return !Quiet.POSITIONS.contains(pos);
	}

	@ModifyExpressionValue(method = "setBlock",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/FullChunkStatus;isOrAfter(Lnet/minecraft/server/level/FullChunkStatus;)Z"))
	private boolean quietfill$evenUnticked(boolean ticking) {
		return ticking || Quiet.EVERYWHERE;
	}
}
