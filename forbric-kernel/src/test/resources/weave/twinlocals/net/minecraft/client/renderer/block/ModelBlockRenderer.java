package net.minecraft.client.renderer.block;

import fixture.twinlocals.Trace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Stand-in with NeoForge's shape (carrier-twins.txt's shouldRenderFace row): NeoForge's face test, taking the block's own
 * position, declared first and called by the draw; vanilla's kept after it and called by nothing.
 */
public class ModelBlockRenderer {
	private boolean cull;

	public ModelBlockRenderer(boolean cull) {
		this.cull = cull;
	}

	public void tesselateFlat(BlockAndTintGetter level, BlockPos pos, BlockState state) {
		for (Direction direction : Direction.values()) {
			if (this.shouldRenderFace(level, pos, state, direction, pos.relative(direction))) Trace.add("draw " + direction);
		}
	}

	private boolean shouldRenderFace(BlockAndTintGetter level, BlockPos pos, BlockState state, Direction direction, BlockPos neighborPos) {
		if (!this.cull) return true;
		BlockState neighborState = level.getBlockState(neighborPos);
		return Block.shouldRenderFace(level, pos, state, neighborState, direction);
	}

	private boolean shouldRenderFace(BlockAndTintGetter level, BlockState state, Direction direction, BlockPos neighborPos) {
		if (!this.cull) return true;
		BlockState neighborState = level.getBlockState(neighborPos);
		return Block.shouldRenderFace(state, neighborState, direction);
	}
}
