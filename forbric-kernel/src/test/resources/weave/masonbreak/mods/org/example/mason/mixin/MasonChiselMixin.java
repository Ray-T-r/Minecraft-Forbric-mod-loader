package org.example.mason.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A mason mod: a block a player's break turns into a chiselled state keeps that state instead of being removed. Written
 * against vanilla's destroyBlock like the break control the interaction adapter was built from, but another way: the
 * state as the one state live at vanilla's call where the sample used an ordinal, the position by an ordinal over
 * destroyBlock's parameters, and the block by its debug name.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class MasonChiselMixin {
	@Shadow
	protected ServerLevel level;

	@WrapOperation(method = "destroyBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
	private boolean mason$keepChiselled(ServerLevel world, BlockPos pos, boolean moving, Operation<Boolean> remove,
			@Local BlockState left, @Local(ordinal = 0) BlockPos asked, @Local(name = "block") Block block) {
		if (left.name().equals("chiselled " + block.name()) && asked.equals(pos)) {
			world.setBlock(pos, left);
			return false;
		}
		return remove.call(world, pos, moving);
	}
}
