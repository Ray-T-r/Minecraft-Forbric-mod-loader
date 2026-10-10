package fixture.masonbreak;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaks a granite wall, whose break leaves chiselled granite, and a dirt floor, which leaves nothing: what each break
 * returned and what stands there afterwards.
 */
public class Probe {
	public String probe() {
		ServerLevel level = new ServerLevel();
		Block granite = new Block("granite", "chiselled granite"), dirt = new Block("dirt", null);
		BlockPos wall = new BlockPos("wall"), floor = new BlockPos("floor");
		level.setBlock(wall, new BlockState("granite", granite));
		level.setBlock(floor, new BlockState("dirt", dirt));
		ServerPlayerGameMode mode = new ServerPlayerGameMode(level);
		boolean wallBroke = mode.destroyBlock(wall), floorBroke = mode.destroyBlock(floor);
		return "wall " + (wallBroke ? "broke" : "kept") + " " + level.at(wall) + " | floor " + (floorBroke ? "broke" : "kept") + " " + level.at(floor);
	}
}
