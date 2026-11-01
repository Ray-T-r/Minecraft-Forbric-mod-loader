package fixture.twinlocals;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Draws a block with face culling on and one with it off, and prints which faces each draw asked about and drew. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		World world = new World();
		record(out, "culled", () -> new ModelBlockRenderer(true).tesselateFlat(world, new BlockPos(1), world.getBlockState(new BlockPos(1))));
		record(out, "unculled", () -> new ModelBlockRenderer(false).tesselateFlat(world, new BlockPos(3), world.getBlockState(new BlockPos(3))));
		return out.toString();
	}

	private static void record(StringBuilder out, String label, Runnable step) {
		Trace.LINES.clear();
		step.run();
		out.append(out.isEmpty() ? "" : " | ").append(label).append(": ").append(String.join(", ", Trace.LINES));
	}
}
