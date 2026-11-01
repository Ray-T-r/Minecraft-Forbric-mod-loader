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
 * Hand-written stand-in, not game code: NeoForge 26.2.0.93's liquid block. Placing asks vanilla's
 * {@code shouldSpreadLiquid} (javap: {@code onPlace} calls it at offset 4); a neighbour update asks NeoForge's fluid
 * interaction registry instead ({@code neighborChanged} calls {@code canInteract} at offset 2, {@code ifne} over its one
 * {@code scheduleTick}); each schedules a fluid tick when the question lets it; a shape update schedules one directly.
 * Public only so the probe can call the three paths.
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
		if (!net.neoforged.neoforge.fluids.FluidInteractionRegistry.canInteract(level, pos)) level.scheduleTick(pos, fluid, 5);
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
