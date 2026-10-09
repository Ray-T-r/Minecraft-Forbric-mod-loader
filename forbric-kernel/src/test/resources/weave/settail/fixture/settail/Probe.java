package fixture.settail;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Sets a block in a ticking chunk, and one quietly in a chunk that ticks no blocks; reports what the level did. */
public class Probe {
	public String probe() {
		Level level = new Level();
		BlockState stone = new BlockState(new Block("minecraft:stone"));
		BlockPos quiet = new BlockPos(5, 0, 0);
		Quiet.POSITIONS.add(quiet);
		level.setBlock(new BlockPos(1, 0, 0), stone, 3, 512);
		level.setBlock(quiet, stone, 3, 512);
		return String.valueOf(level.trace);
	}
}
