package fixture.relaysignal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import org.example.relay.Damper;

/** A conducting block of the relay mod that passes weak power read from the south only, and only where it stands. */
public class Baffle extends Block implements Damper {
	public Baffle() {
		super("baffle", true);
	}

	@Override
	public boolean passesFrom(Direction from, BlockPos asked) {
		return from == Direction.SOUTH && asked.name().equals("baffle");
	}
}
