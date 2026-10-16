package net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.redstone.Orientation;

/**
 * Hand-written stand-in, not game code: the liquid block as both mods' own loader has it. Placing and neighbour updates
 * ask {@code shouldSpreadLiquid} and schedule a fluid tick when it answers true; a shape update schedules one directly.
 */
public class LiquidBlock extends Block {
	private final Fluid fluid;

	public LiquidBlock(Fluid fluid) {
		this.fluid = fluid;
	}

	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (this.shouldSpreadLiquid(level, pos, state)) level.scheduleTick(pos, fluid, 5);
	}

	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, Orientation orientation, boolean movedByPiston) {
		if (this.shouldSpreadLiquid(level, pos, state)) level.scheduleTick(pos, fluid, 5);
	}

	public BlockState updateShape(BlockState state, LevelReader level, ScheduledTickAccess ticks, BlockPos pos, Direction direction,
			BlockPos neighborPos, BlockState neighborState, RandomSource random) {
		ticks.scheduleTick(pos, fluid, 5);
		return state;
	}

	private boolean shouldSpreadLiquid(Level level, BlockPos pos, BlockState state) {
		return true;
	}
}
