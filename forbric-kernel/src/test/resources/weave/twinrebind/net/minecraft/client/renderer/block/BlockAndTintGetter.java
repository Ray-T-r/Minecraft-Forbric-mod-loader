package net.minecraft.client.renderer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Hand-written stand-in, not game code: what is where. */
public interface BlockAndTintGetter {
	BlockState getBlockState(BlockPos pos);
}
