package net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * Fixture stand-in for NeoForge's liquid block: placement asks vanilla's shouldSpreadLiquid, as vanilla does, and a
 * neighbour change asks NeoForge's interaction registry instead; a cell the answer did not handle schedules its fluid tick.
 */
public class LiquidBlock {
	protected final FlowingFluid fluid;

	public LiquidBlock(FlowingFluid fluid) {
		this.fluid = fluid;
	}

	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (this.shouldSpreadLiquid(level, pos, state)) {
			level.scheduleTick(pos, fluid, 30);
		}
	}

	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, boolean movedByPiston) {
		if (!net.neoforged.neoforge.fluids.FluidInteractionRegistry.canInteract(level, pos)) {
			level.scheduleTick(pos, fluid, 30);
		}
	}

	private boolean shouldSpreadLiquid(Level level, BlockPos pos, BlockState state) {
		return true;
	}

	protected void fizz(LevelAccessor level, BlockPos pos) {
		level.levelEvent(1501, pos, 0);
	}
}
