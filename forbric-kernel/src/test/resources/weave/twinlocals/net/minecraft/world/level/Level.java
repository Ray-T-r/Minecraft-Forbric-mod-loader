package net.minecraft.world.level;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Stand-in: every position holds stone named after it. */
public class Level implements LevelAccessor {
	public BlockState getBlockState(BlockPos pos) {
		return new BlockState("stone@" + pos);
	}
}
