package org.example.magma.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A lava-meets-water rule written another way than Carpet's deepslate one: another mod and class; the target named by
 * string; the selector with its descriptor; the point with an explicit ordinal; and the rule is for SOURCE lava (Carpet's
 * is for flowing lava only) and makes its own sound instead of calling the block's {@code fizz}.
 */
@Mixin(targets = "net.minecraft.world.level.block.LiquidBlock")
public abstract class MagmaReactionMixin {
	@Inject(method = "shouldSpreadLiquid(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/material/FluidState;isSource()Z", ordinal = 0),
			cancellable = true)
	private void magma$sourceMeetsWater(Level level, BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (level.getFluidState(pos).isSource()) {
			level.setBlockAndUpdate(pos, Blocks.MAGMA_BLOCK.defaultBlockState());
			level.levelEvent(1502, pos, 0);
			cir.setReturnValue(false);
		}
	}
}
