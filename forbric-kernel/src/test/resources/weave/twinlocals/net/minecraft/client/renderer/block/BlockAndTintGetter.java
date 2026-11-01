package net.minecraft.client.renderer.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Stand-in for the level a block is drawn in. */
public interface BlockAndTintGetter extends BlockGetter {
	BlockState getBlockState(BlockPos pos);
}
