package org.facet.mixin;

import fixture.twinlocals.Trace;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/**
 * A guest written against vanilla's shouldRenderFace(level, state, direction, neighborPos), capturing the old way at the
 * neighbour read: it notes every face asked about and shows every top face whatever covers it.
 */
@Mixin(ModelBlockRenderer.class)
abstract class RendererMixin {
	@Inject(method = "shouldRenderFace", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"), cancellable = true, locals = LocalCapture.CAPTURE_FAILHARD)
	private void facet$peek(BlockAndTintGetter level, BlockState state, Direction direction, BlockPos neighborPos, CallbackInfoReturnable<Boolean> info) {
		Trace.add("peek " + state + " " + direction + " toward " + neighborPos);
		if (direction == Direction.UP) info.setReturnValue(true);
	}
}
