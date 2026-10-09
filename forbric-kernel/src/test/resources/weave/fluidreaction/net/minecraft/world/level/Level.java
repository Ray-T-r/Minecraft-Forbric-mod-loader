package net.minecraft.world.level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Fixture stand-in: blocks and fluids by position, and what happened. */
public class Level implements LevelAccessor {
	public final List<String> events = new ArrayList<>();
	public final Map<BlockPos, FluidState> fluids = new HashMap<>();
	private final Map<BlockPos, BlockState> blocks = new HashMap<>();

	public BlockState getBlockState(BlockPos pos) {
		return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	public FluidState getFluidState(BlockPos pos) {
		return fluids.getOrDefault(pos, Fluids.EMPTY.defaultFluidState());
	}

	public boolean setBlockAndUpdate(BlockPos pos, BlockState state) {
		blocks.put(pos, state);
		fluids.remove(pos);
		return true;
	}

	public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
		events.add("tick@" + pos);
	}

	@Override
	public void levelEvent(int type, BlockPos pos, int data) {
		events.add("event" + type + "@" + pos);
	}
}
