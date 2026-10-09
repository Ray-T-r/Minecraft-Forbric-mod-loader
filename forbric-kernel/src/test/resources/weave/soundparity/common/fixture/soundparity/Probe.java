package fixture.soundparity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One entity steps onto stone and onto felt, steps onto stone with felt muffled under it, then lands on felt: the sounds
 * the level played, in order, with their volume. The same probe runs on vanilla-shaped and merged-shaped game classes.
 */
public class Probe {
	public String probe() {
		Level level = new Level();
		LivingEntity entity = new LivingEntity(level);
		BlockState stone = new BlockState(new Block(new SoundType("stone")));
		BlockState felt = new BlockState(new Felt());
		entity.stepOn(new BlockPos(1, 64, 2), stone);
		entity.stepOn(new BlockPos(1, 64, 2), felt);
		entity.stepOnCombination(stone, felt, new BlockPos(1, 64, 2));
		level.everywhere = felt;
		entity.fallAt(3.7, 70.1, -5.2);
		return String.join(", ", level.sounds);
	}
}
