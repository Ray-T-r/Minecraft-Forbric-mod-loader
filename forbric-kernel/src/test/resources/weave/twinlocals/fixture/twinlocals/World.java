package fixture.twinlocals;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Stone at every odd position, air at every even one. */
public class World implements BlockAndTintGetter {
	@Override
	public BlockState getBlockState(BlockPos pos) {
		return pos.n() % 2 != 0 ? new BlockState("stone@" + pos, true) : new BlockState("air@" + pos, false);
	}
}
