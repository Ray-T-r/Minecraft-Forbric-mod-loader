package net.minecraft.world.level;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** A stand-in: block states by position. */
public class Level implements LevelReader {
	private final Map<BlockPos, BlockState> states = new HashMap<>();

	public void set(BlockPos pos, BlockState state) {
		states.put(pos, state);
	}

	public BlockState getBlockState(BlockPos pos) {
		return states.get(pos);
	}
}
