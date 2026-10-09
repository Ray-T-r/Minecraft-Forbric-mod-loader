package fixture.fluidreaction;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.Fluids;

/**
 * Source lava placed beside water, flowing lava beside water whose neighbour changed, and source lava in the open;
 * reports each cell's block and what happened.
 */
public class Probe {
	public String probe() {
		Level level = new Level();
		LiquidBlock lava = new LiquidBlock(Fluids.LAVA);
		BlockPos source = new BlockPos(0, 64, 0), flowing = new BlockPos(10, 64, 0), open = new BlockPos(20, 64, 0);
		level.fluids.put(source, Fluids.LAVA.defaultFluidState());
		level.fluids.put(new BlockPos(1, 64, 0), Fluids.WATER.defaultFluidState());
		level.fluids.put(flowing, Fluids.FLOWING_LAVA.defaultFluidState());
		level.fluids.put(new BlockPos(11, 64, 0), Fluids.WATER.defaultFluidState());
		level.fluids.put(open, Fluids.LAVA.defaultFluidState());
		lava.onPlace(null, level, source, null, false);
		lava.neighborChanged(null, level, flowing, Blocks.AIR, false);
		lava.onPlace(null, level, open, null, false);
		return "source=" + level.getBlockState(source) + " flowing=" + level.getBlockState(flowing) + " open=" + level.getBlockState(open)
				+ " events=" + level.events;
	}
}
