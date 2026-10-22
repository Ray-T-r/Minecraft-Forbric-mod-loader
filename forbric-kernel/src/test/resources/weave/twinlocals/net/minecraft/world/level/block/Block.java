package net.minecraft.world.level.block;

import fixture.twinlocals.Trace;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/** Stand-in: a block's own reaction to being broken. */
public class Block {
	public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state) {
		return state;
	}

	public void destroy(LevelAccessor level, BlockPos pos, BlockState state) {
		Trace.add("destroy " + state);
	}
}
