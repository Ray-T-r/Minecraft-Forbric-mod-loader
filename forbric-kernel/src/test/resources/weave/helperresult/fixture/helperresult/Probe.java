package fixture.helperresult;

import org.quarry.stone.Mason;
import org.quarry.stone.Point;

/** Runs every scenario on a fresh mason and prints what each step did. */
public class Probe {
	public String probe() {
		StringBuilder out = new StringBuilder();
		record(out, "chip plain", () -> Trace.add("chipped " + new Mason(false, true).chip(new Point(1))));
		record(out, "chip careful", () -> Trace.add("chipped " + new Mason(true, true).chip(new Point(2))));
		record(out, "chip stuck", () -> Trace.add("chipped " + new Mason(false, false).chip(new Point(3))));
		record(out, "split", () -> new Mason(false, true).split(new Point(4)));
		record(out, "lay", () -> Trace.add("laid " + new Mason(false, true).lay(new Point(5))));
		return out.toString();
	}

	private static void record(StringBuilder out, String label, Runnable step) {
		Trace.LINES.clear();
		step.run();
		out.append(out.isEmpty() ? "" : " | ").append(label).append(": ").append(String.join(", ", Trace.LINES));
	}
}
