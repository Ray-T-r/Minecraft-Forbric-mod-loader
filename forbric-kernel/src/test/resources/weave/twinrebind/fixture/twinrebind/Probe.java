package fixture.twinrebind;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Draws one ore with stone above it and air below, and reports what ran. */
public class Probe {
	public String probe() {
		new ModelBlockRenderer().tesselateFlat(pos -> pos.name.endsWith("-up") ? BlockState.STONE : BlockState.AIR,
				BlockState.ORE, new BlockPos("ore"));
		return String.join(",", Trace.LINES);
	}
}
