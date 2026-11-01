package net.minecraft.client.renderer.block;

import fixture.twinlocals.Trace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** As the guest's loader has it: vanilla's face test, called by the draw. */
public class ModelBlockRenderer {
	private boolean cull;

	public ModelBlockRenderer(boolean cull) {
		this.cull = cull;
	}

	public void tesselateFlat(BlockAndTintGetter level, BlockPos pos, BlockState state) {
		for (Direction direction : Direction.values()) {
			if (this.shouldRenderFace(level, state, direction, pos.relative(direction))) Trace.add("draw " + direction);
		}
	}

	private boolean shouldRenderFace(BlockAndTintGetter level, BlockState state, Direction direction, BlockPos neighborPos) {
		if (!this.cull) return true;
		BlockState neighborState = level.getBlockState(neighborPos);
		return Block.shouldRenderFace(state, neighborState, direction);
	}
}
