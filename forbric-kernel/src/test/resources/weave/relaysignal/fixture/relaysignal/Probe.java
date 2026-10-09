package fixture.relaysignal;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Reads the signal at a stone block, then at the relay mod's baffle from each side, each next to a strongly powered neighbour. */
public class Probe {
	public String probe() {
		Map<String, BlockState> blocks = Map.of("stone", new BlockState(new Block("stone", true)), "baffle", new BlockState(new Baffle()));
		SignalGetter level = new SignalGetter() {
			@Override
			public BlockState getBlockState(BlockPos pos) {
				return blocks.get(pos.name());
			}

			@Override
			public int getDirectSignalTo(BlockPos pos) {
				return 15;
			}
		};
		return "stone " + level.getSignal(new BlockPos("stone"), Direction.NORTH)
				+ " | baffle north " + level.getSignal(new BlockPos("baffle"), Direction.NORTH)
				+ " | baffle south " + level.getSignal(new BlockPos("baffle"), Direction.SOUTH);
	}
}
