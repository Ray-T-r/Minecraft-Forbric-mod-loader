package fixture.twinlocals;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayerGameMode;

/** Breaks a block in survival and one in creative, and prints what each break did. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		record(out, "survival", () -> Trace.add("broke " + new ServerPlayerGameMode().destroyBlock(new BlockPos(1, false))));
		record(out, "creative", () -> Trace.add("broke " + new ServerPlayerGameMode().destroyBlock(new BlockPos(2, true))));
		return out.toString();
	}

	private static void record(StringBuilder out, String label, Runnable step) {
		Trace.LINES.clear();
		step.run();
		out.append(out.isEmpty() ? "" : " | ").append(label).append(": ").append(String.join(", ", Trace.LINES));
	}
}
