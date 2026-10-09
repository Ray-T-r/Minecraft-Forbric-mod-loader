package net.minecraft.world.level.block;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * Fixture stand-in for the merged liquid block: placement asks MinecraftForge's interaction registry and a neighbour
 * change NeoForge's. Vanilla's shouldSpreadLiquid — with its lava-meets-water point, the {@code isSource} call — is still
 * in the class, and nothing calls it.
 */
public class LiquidBlock extends Block {
	public static final List<Direction> POSSIBLE_FLOW_DIRECTIONS = List.of(Direction.DOWN, Direction.SOUTH, Direction.NORTH,
			Direction.EAST, Direction.WEST);
	protected final FlowingFluid fluid;

	public LiquidBlock(FlowingFluid fluid) {
		super("lava");
		this.fluid = fluid;
	}

	public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!net.minecraftforge.fluids.FluidInteractionRegistry.canInteract(level, pos)) {
			level.scheduleTick(pos, fluid, 30);
		}
	}

	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, boolean movedByPiston) {
		if (!net.neoforged.neoforge.fluids.FluidInteractionRegistry.canInteract(level, pos)) {
			level.scheduleTick(pos, fluid, 30);
		}
	}

	private boolean shouldSpreadLiquid(Level level, BlockPos pos, BlockState state) {
		if (this.fluid.is(FluidTags.LAVA)) {
			for (Direction direction : POSSIBLE_FLOW_DIRECTIONS) {
				BlockPos neighbour = pos.relative(direction.getOpposite());
				if (level.getFluidState(neighbour).is(FluidTags.WATER)) {
					Block convertTo = level.getFluidState(pos).isSource() ? Blocks.OBSIDIAN : Blocks.COBBLESTONE;
					level.setBlockAndUpdate(pos, convertTo.defaultBlockState());
					this.fizz(level, pos);
					return false;
				}
			}
		}
		return true;
	}

	protected void fizz(LevelAccessor level, BlockPos pos) {
		level.levelEvent(1501, pos, 0);
	}
}
