package fixture.decisionisland;

import org.brook.fen.Liquid;
import org.brook.fen.Wader;

/** Walks a wader through water, lava, open air and slime, a few steps each, and reports every line and its air. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		for (Liquid liquid : new Liquid[] {Liquid.WATER, Liquid.LAVA, null, Liquid.SLIME}) {
			Wader wader = new Wader();
			wader.in = liquid;
			wader.tank = 2;
			wader.air = 8;
			for (int step = 0; step < 4; step++) {
				Trace.LINES.clear();
				wader.step();
				out.append(out.isEmpty() ? "" : " | ").append(liquid == null ? "air" : liquid.name).append(step).append(": ")
						.append(String.join(", ", Trace.LINES)).append(", air ").append(wader.air);
			}
		}
		return out.toString();
	}
}
