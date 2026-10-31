package net.minecraftforge.fluids;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Compile-only: MinecraftForge's flowing fluid. Its source and flowing halves have to be concrete for a fixture to
 * construct them, so every abstract member of vanilla's {@code FlowingFluid} is answered here, by throwing. Never shipped.
 */
public abstract class ForgeFlowingFluid extends FlowingFluid {
	protected ForgeFlowingFluid(Properties properties) {
		throw new UnsupportedOperationException();
	}

	@Override public Fluid getFlowing() { throw new UnsupportedOperationException(); }
	@Override public Fluid getSource() { throw new UnsupportedOperationException(); }
	@Override protected boolean canConvertToSource(ServerLevel level) { throw new UnsupportedOperationException(); }
	@Override protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state) { throw new UnsupportedOperationException(); }
	@Override protected int getSlopeFindDistance(LevelReader level) { throw new UnsupportedOperationException(); }
	@Override protected int getDropOff(LevelReader level) { throw new UnsupportedOperationException(); }
	@Override public Item getBucket() { throw new UnsupportedOperationException(); }
	@Override protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid fluid, Direction direction) { throw new UnsupportedOperationException(); }
	@Override public int getTickDelay(LevelReader level) { throw new UnsupportedOperationException(); }
	@Override protected float getExplosionResistance() { throw new UnsupportedOperationException(); }
	@Override protected BlockState createLegacyBlock(FluidState state) { throw new UnsupportedOperationException(); }

	public static class Source extends ForgeFlowingFluid {
		public Source(Properties properties) {
			super(properties);
		}

		@Override public int getAmount(FluidState state) { throw new UnsupportedOperationException(); }
		@Override public boolean isSource(FluidState state) { throw new UnsupportedOperationException(); }
	}

	public static class Flowing extends ForgeFlowingFluid {
		public Flowing(Properties properties) {
			super(properties);
		}

		@Override public int getAmount(FluidState state) { throw new UnsupportedOperationException(); }
		@Override public boolean isSource(FluidState state) { throw new UnsupportedOperationException(); }
	}

	public static class Properties {
		public Properties(Supplier<? extends FluidType> fluidType, Supplier<? extends Fluid> still, Supplier<? extends Fluid> flowing) {
			throw new UnsupportedOperationException();
		}

		public Properties bucket(Supplier<? extends Item> bucket) { throw new UnsupportedOperationException(); }
		public Properties block(Supplier<? extends LiquidBlock> block) { throw new UnsupportedOperationException(); }
	}
}
