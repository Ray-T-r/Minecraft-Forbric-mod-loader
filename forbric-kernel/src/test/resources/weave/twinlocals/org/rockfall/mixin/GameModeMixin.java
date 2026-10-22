package org.rockfall.mixin;

import fixture.twinlocals.Trace;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

/** A guest written against its own loader's removeBlock(pos, canHarvest): it captures the state and the answer before the destroy. */
@Mixin(ServerPlayerGameMode.class)
abstract class GameModeMixin {
	@Inject(method = "removeBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/Block;destroy(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V"), locals = LocalCapture.CAPTURE_FAILHARD)
	private void rockfall$collapse(BlockPos pos, boolean harvest, CallbackInfoReturnable<Boolean> info, BlockState state, boolean removed) {
		Trace.add("collapse " + pos + " " + state + " harvest " + harvest + " removed " + removed);
	}
}
