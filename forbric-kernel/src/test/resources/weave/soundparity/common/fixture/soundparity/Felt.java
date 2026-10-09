package fixture.soundparity;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/** A block that sounds of cloth on its own, and that one mod hears as wool. */
public class Felt extends Block {
	public Felt() {
		super(new SoundType("cloth"));
	}

	public BlockState heardAs() {
		return new BlockState(new Block(new SoundType("wool")));
	}
}
