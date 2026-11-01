package net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** Stand-in: a face is drawn when its neighbour does not hide it. */
public class Block {
	public static boolean shouldRenderFace(BlockState state, BlockState neighborState, Direction direction) {
		return !neighborState.solid();
	}

	/** NeoForge's: the block's own position too, for its face-hiding hooks (which hide nothing here). */
	public static boolean shouldRenderFace(BlockGetter level, BlockPos pos, BlockState state, BlockState neighborState, Direction direction) {
		return !neighborState.solid();
	}
}
