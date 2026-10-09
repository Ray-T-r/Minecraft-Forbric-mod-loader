package fixture.blockquery;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** How an entity slides on stone and on grease, and how far it drifts between them. Same probe on both shapes. */
public class Probe {
	public String probe() {
		Level level = new Level();
		BlockPos stone = new BlockPos(1), grease = new BlockPos(2);
		level.set(stone, new BlockState(new Block(0.6F)));
		level.set(grease, new BlockState(new Grease()));
		Entity entity = new Entity(level);
		return "stone " + entity.slide(stone) + " | grease " + entity.slide(grease) + " | drift " + entity.drift(stone, grease);
	}
}
