package fixture.carpetfluid;

import carpet.CarpetSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * Lava placed under blue ice, lava under blue ice whose neighbour changed, lava placed in the open, and lava under blue
 * ice whose neighbour changed in a cell the registry handles; reports each cell's block and what happened.
 */
public class Probe {
	public String probe() {
		CarpetSettings.renewableBlackstone = true;
		Level level = new Level();
		LiquidBlock lava = new LiquidBlock(new FlowingFluid(FluidTags.LAVA));
		BlockPos placed = new BlockPos(0, 64, 0), changed = new BlockPos(10, 64, 0), open = new BlockPos(20, 64, 0),
				handled = new BlockPos(30, 64, 0);
		for (BlockPos under : new BlockPos[] {placed, changed, handled}) {
			level.setBlockAndUpdate(under.above(), Blocks.BLUE_ICE.defaultBlockState());
		}
		level.nativelyHandled.add(handled);
		lava.onPlace(null, level, placed, null, false);
		lava.neighborChanged(null, level, changed, Blocks.BLUE_ICE, false);
		lava.onPlace(null, level, open, null, false);
		lava.neighborChanged(null, level, handled, Blocks.BLUE_ICE, false);
		return "placed=" + level.getBlockState(placed) + " changed=" + level.getBlockState(changed)
				+ " open=" + level.getBlockState(open) + " handled=" + level.getBlockState(handled) + " events=" + level.events;
	}
}
