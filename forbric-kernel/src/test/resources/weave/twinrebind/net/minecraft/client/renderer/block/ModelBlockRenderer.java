package net.minecraft.client.renderer.block;

import fixture.twinrebind.Trace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Hand-written stand-in, not game code: the merged class's shape. NeoForge's face test, which also takes the block's
 * own position, is declared first and is the one tesselateFlat calls; vanilla's, which MinecraftForge kept, comes
 * after it and nothing calls it.
 */
public class ModelBlockRenderer {
	private boolean shouldRenderFace(BlockAndTintGetter level, BlockPos pos, BlockState state, Direction direction, BlockPos neighborPos) {
		BlockState neighborState = level.getBlockState(neighborPos);
		Trace.add("neoforge:" + pos.name + ">" + neighborPos.name);
		return !neighborState.opaque;
	}

	private boolean shouldRenderFace(BlockAndTintGetter level, BlockState state, Direction direction, BlockPos neighborPos) {
		BlockState neighborState = level.getBlockState(neighborPos);
		Trace.add("vanilla:" + neighborPos.name);
		return !neighborState.opaque;
	}

	public void tesselateFlat(BlockAndTintGetter level, BlockState state, BlockPos pos) {
		for (Direction direction : Direction.values()) {
			BlockPos neighbor = pos.relative(direction);
			if (shouldRenderFace(level, pos, state, direction, neighbor)) Trace.add("face:" + direction.name().toLowerCase());
		}
	}
}
